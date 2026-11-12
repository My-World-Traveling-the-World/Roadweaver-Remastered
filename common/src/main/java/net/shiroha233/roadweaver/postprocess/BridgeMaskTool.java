/* 文件职责：桥面掩码与拉直 —— 判断哪一段要走桥、并把桥面拉直。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;
import java.util.*;
import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.isWaterLike;
import static net.shiroha233.roadweaver.postprocess.SplineHelper.*;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.postprocess.PathPostProcessor.*;

/**
 * 桥面掩码与拉直。
 *
 * <h2>掩码是什么</h2>
 * <p>{@code detectBridgeMask} 逐站打标记：这一站属于桥（要架空）、还是普通路。
 * 后续所有施工都读这张表。</p>
 *
 * <h2>★ 桥面为什么要拉直</h2>
 * <p>桥面上如果保留后处理造出来的抖动，桥看起来就是「扭的」——
 * 而且桥墩位置会跟着抖，施工量翻倍。所以桥段单独做一次拉直
 * （{@code straightenBridgeRuns}），**与普通路段的平滑策略不同**。</p>
 *
 * <h2>★ 与「长桥正弦弯」的关系</h2>
 * <p>弦长 ≥ 96 格的长桥**故意**保留 ±5 格正弦弯（用户已定案保留，见
 * {@code BridgeSegment} 的 {@code BRIDGE_CURVE_MAX_OFFSET_BLOCKS}）。
 * 所以这里的「拉直」只处理短桥与桥头接缝，**不能把长桥的弯也拉掉**。
 * 改动这段前必须先确认它在不在长桥分支上。</p>
 *
 * <p>本类是从 1028 行的 {@code PathPostProcessor} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class BridgeMaskTool {
    static boolean[] detectBridgeMask(List<BlockPos> nodes, ServerLevel level,
                                      TerrainSamplingCache cache, PathTerrainField terrain,
                                      int bridgeMinWaterDepth,
                                      AccurateHeightSampler sampler) {
        int n = nodes.size();
        boolean[] mask = new boolean[n];
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos node : nodes) {
            if (terrain == null || !terrain.hasAccurateSample(node.getX(), node.getZ())) {
                missing.add(node);
            }
        }
        Map<Long, AccurateHeightSample> samples = sampler.samplePositions(missing);
        int dryViaduct = 0;
        for (int i = 0; i < n; i++) {
            BlockPos p = nodes.get(i);
            // A dry gorge needs a viaduct too. This mask used to ask only "is this water", so a span with
            // no water in it was never straightened and never grouped: the deck followed the terrain, the
            // railings wobbled with it, and a deep dry valley carried a road that hugged a wall of rock -
            // while the bridge right next to it, over water, was dead straight. The planned Y rising far
            // above the sampled ground is the same signal the span extractor uses for a dry depression.
            // A column the height backend refused must not take the whole route with it: two of sixteen
            // connections died here in a measured session, both with "Accurate height backend returned
            // no chunk" thrown from this line. Treat the ground as level and keep the road.
            int ground;
            try {
                ground = sampler.worldSurfaceWg(p.getX(), p.getZ());
            } catch (RuntimeException missingHeight) {
                if (MISSING_HEIGHTS.getAndIncrement() < 8L) {
                    LOGGER.warn("Height backend refused column {},{}; treating the ground as level and "
                            + "keeping the route", p.getX(), p.getZ(), missingHeight);
                }
                ground = p.getY();
            }
            boolean overDryDepression = p.getY() - ground >= DRY_VIADUCT_MIN_DROP;
            if (terrain != null && terrain.hasAccurateSample(p.getX(), p.getZ())) {
                mask[i] = terrain.isBridgeWater(p.getX(), p.getZ(), bridgeMinWaterDepth) || overDryDepression;
                if (overDryDepression) dryViaduct++;
                continue;
            }
            int sea = level.getSeaLevel();
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(p.getX(), p.getZ()));
            int oceanFloor = sample != null ? sample.oceanFloorWg() : sampler.oceanFloorWg(p.getX(), p.getZ());
            int surfaceY;
            if (sample != null) {
                surfaceY = sample.worldSurfaceWg();
            } else {
                try {
                    surfaceY = sampler.worldSurfaceWg(p.getX(), p.getZ());
                } catch (RuntimeException missingHeight) {
                    // Same rule as above: no height means "assume the road's own level", which classifies
                    // the column as dry and leaves the mask unchanged for it.
                    surfaceY = p.getY();
                }
            }
            boolean waterBiome = isWaterLike(cache, p.getX(), p.getZ(), level);
            boolean waterColumn = SurfaceWaterClassifier.isWaterColumn(
                    surfaceY, oceanFloor, waterBiome, sea);
            int waterDepth = SurfaceWaterClassifier.waterDepth(
                    surfaceY, oceanFloor, waterBiome, sea, waterColumn);
            mask[i] = waterDepth >= Math.max(1, bridgeMinWaterDepth) || overDryDepression;
            if (overDryDepression) dryViaduct++;
        }
        if (dryViaduct > 0) {
            LOGGER.info("RoadWeaver DRY_VIADUCT stations={} minDrop={} of={}",
                    dryViaduct, DRY_VIADUCT_MIN_DROP, n);
        }
        return mask;
    }

    static List<BlockPos> straightenBridgeRuns(List<BlockPos> nodes, boolean[] bridgeMask) {
        if (nodes == null || nodes.size() < 3 || bridgeMask == null || bridgeMask.length != nodes.size()) {
            return nodes;
        }
        int n = nodes.size();
        List<BlockPos> out = new ArrayList<>(nodes);
        int i = 0;
        while (i < n) {
            if (!bridgeMask[i]) { i++; continue; }
            int runStart = i;
            while (i < n && bridgeMask[i]) i++;
            int runEnd = i - 1;
            int entryIdx = Math.max(0, runStart - 1);
            int exitIdx = Math.min(n - 1, runEnd + 1);
            if (exitIdx <= entryIdx) continue;
            BlockPos a = nodes.get(entryIdx), b = nodes.get(exitIdx);
            int ax = a.getX(), az = a.getZ(), dx = b.getX() - ax, dz = b.getZ() - az;
            for (int k = runStart; k <= runEnd; k++) {
                double t = Math.max(0.0, Math.min(1.0, (k - entryIdx) / (double) (exitIdx - entryIdx)));
                out.set(k, new BlockPos((int) Math.round(ax + dx * t), out.get(k).getY(),
                        (int) Math.round(az + dz * t)));
            }
        }
        return out;
    }
}
