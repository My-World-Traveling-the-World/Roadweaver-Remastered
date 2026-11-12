/* 文件职责：隧道洞口与覆盖层采样 —— 判断这一段挖得出来、覆土够不够。 */
package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.isWaterLike;

// 仍留在其它同包类里的常量与工具（包内可见）。
import static net.shiroha233.roadweaver.pathfinding.PathSpanExtractor.*;
import static net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner.*;
import static net.shiroha233.roadweaver.pathfinding.FillBridgeExtender.*;
import static net.shiroha233.roadweaver.pathfinding.SpanConflictResolver.*;

/**
 * 隧道洞口与覆盖层采样。
 *
 * <h2>为什么洞口要单独算</h2>
 * <p>隧道不能凭空出现在山体中间 —— 两端必须有<b>露天的洞口</b>，
 * 否则施工时那段路会埋在山里、玩家看不到入口。
 * {@code appendTunnelWithSurfaceMouths} 就是把跨度两端各加一段露天段。</p>
 *
 * <h2>覆盖层为什么要按 footprint 采</h2>
 * <p>只看中心线的覆土量会偏乐观：路有宽度，中心线上面有 7 格岩体时,
 * 路边缘可能已经削穿了山顶。所以按 {@code tunnelFootprintRadius} 逐列采样，
 * 取<b>最小</b>覆土（{@code maximumFootprintOverburden} 取的是需要的最严值）。</p>
 *
 * <p>本类是从 1817 行的 {@code PathSpanExtractor} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class TunnelFootprintSampler {
    public static void appendTunnelWithSurfaceMouths(List<RoadSpan> result,
            List<RoadSegmentPlacement> segments,
            List<BlockPos> centers, List<Integer> targetY, boolean[] bridge,
            Map<Long, AccurateHeightSample> samples,
            AccurateHeightSampler accurate,
            int buriedStart, int buriedEnd,
            RoadGradePlanner.SurfaceGradeLimits limits) {
        if (buriedEnd - buriedStart + 1 < TUNNEL_RUN_MIN_LENGTH) return;

        // Include the complete approach up to the last surface-height segment.
        // The builder treats those mouth sections as open portals instead of
        // demanding a full stone roof. Keeping only the >=7 block core left a
        // buried gap between the visible road and the tunnel interior.
        int leftMouth = buriedStart - 1;
        while (leftMouth >= 0 && !bridge[leftMouth]
                && !fullWidthSurfaceConnected(segments, centers, targetY, samples, accurate,
                leftMouth, limits)) leftMouth--;
        int rightMouth = buriedEnd + 1;
        while (rightMouth < centers.size() && !bridge[rightMouth]
                && !fullWidthSurfaceConnected(segments, centers, targetY, samples, accurate,
                rightMouth, limits)) rightMouth++;

        if (leftMouth < 0 || rightMouth >= centers.size()
                || bridge[Math.max(0, Math.min(bridge.length - 1, leftMouth))]
                || bridge[Math.max(0, Math.min(bridge.length - 1, rightMouth))]) {
            // The route is already a sustained deep cut. Do not discard the
            // tunnel classification merely because a clean surface mouth
            // could not be found (for example at a connected mountain edge).
            // Otherwise the same stations fall back to ordinary-road paving
            // and are rejected later as cut-too-deep, leaving a gap instead
            // of excavating the planned tunnel.
            int fallbackStart = buriedStart;
            int fallbackEnd = buriedEnd;
            if (fallbackStart > 0 && !bridge[fallbackStart - 1]) fallbackStart--;
            if (fallbackEnd + 1 < centers.size() && !bridge[fallbackEnd + 1]) fallbackEnd++;
            if (fallbackEnd - fallbackStart + 1 >= TUNNEL_RUN_MIN_LENGTH) {
                result.add(new RoadSpan(centers.get(fallbackStart), centers.get(fallbackEnd), SpanType.TUNNEL));
                LOGGER.warn("RoadWeaver retained deep-cut tunnel without stable surface mouths "
                                + "range={}..{} buried={}..{}",
                        fallbackStart, fallbackEnd, buriedStart, buriedEnd);
            }
            return;
        }
        int tunnelStart = leftMouth;
        int tunnelEnd = rightMouth;
        if (tunnelEnd - tunnelStart + 1 >= TUNNEL_RUN_MIN_LENGTH) {
            result.add(new RoadSpan(centers.get(tunnelStart), centers.get(tunnelEnd), SpanType.TUNNEL));
        }
    }

    public static Map<Long, AccurateHeightSample> sampleTunnelFootprints(
            List<RoadSegmentPlacement> segments,
            List<BlockPos> centers,
            List<Integer> targetY,
            ServerLevel level,
            TerrainSamplingCache cache) {
        if (segments == null || level == null || cache == null) return Map.of();
        List<BlockPos> positions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null) continue;
            // 0.9.063：中心站列**必须**被采到 —— 突起度判据吃的是中心站的地形高度，
            // 而 footprint 不保证包含中心列（见 TwoStagePathfindingManager / RoadSnapService）。
            // 漏采会退回 center.getY()（那是**规划路面**高度），突起度就会恒为 0。
            if (segment.middlePos() != null) positions.add(segment.middlePos());
            if (segment.positions() == null) continue;
            positions.addAll(segment.positions());
        }
        return positions.isEmpty() ? Map.of() : cache.getAccurateSampler(level).samplePositions(positions);
    }

    public static boolean fullWidthSurfaceConnected(List<RoadSegmentPlacement> segments,
                                                     List<BlockPos> centers,
                                                     List<Integer> targetY,
                                                     Map<Long, AccurateHeightSample> samples,
                                                     AccurateHeightSampler accurate,
                                                     int index,
                                                     RoadGradePlanner.SurfaceGradeLimits limits) {
        // Same cut/fill source as RoadGradePlanner (TerrainProfileConfig +
        // terrain profile mode) so tunnel portal selection cannot disagree
        // with the profile clamps that produced the road height.
        int cutLimit = limits.cut(false);
        int fillLimit = limits.fill(false);
        if (accurate == null || samples == null || samples.isEmpty()) {
            int fallbackOverburden = overburden(centers, targetY, index);
            return fallbackOverburden <= cutLimit
                    && fallbackOverburden >= -fillLimit;
        }
        RoadSegmentPlacement segment = segments.get(index);
        if (segment == null || segment.positions() == null || segment.positions().isEmpty()) return false;
        int roadY = targetY.get(index);
        for (BlockPos position : segment.positions()) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            int terrainY = sample != null ? accurate.surfaceHeight(sample) : centers.get(index).getY();
            // A tunnel may begin immediately after a legal open cut.  Requiring
            // a near-flat (two-block) station here left a 5 -> 6 block hillside
            // transition without a valid portal, so construction later skipped
            // the six-block core instead of building the tunnel.
            if (terrainY - roadY > cutLimit
                    || roadY - terrainY > fillLimit) return false;
        }
        return true;
    }

    public static int maximumFootprintOverburden(List<RoadSegmentPlacement> segments,
                                                   List<BlockPos> centers,
                                                   List<Integer> targetY,
                                                   Map<Long, AccurateHeightSample> samples,
                                                   AccurateHeightSampler accurate,
                                                   int index) {
        if (index < 0 || index >= segments.size() || index >= targetY.size()) return Integer.MAX_VALUE;
        int roadY = targetY.get(index);
        RoadSegmentPlacement segment = segments.get(index);
        if (accurate == null || samples == null || samples.isEmpty()
                || segment == null || segment.positions() == null || segment.positions().isEmpty()) {
            return overburden(centers, targetY, index);
        }
        int maximum = Integer.MIN_VALUE;
        for (BlockPos position : segment.positions()) {
            if (position == null) continue;
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            int terrainY = sample != null ? accurate.surfaceHeight(sample) : centers.get(index).getY();
            maximum = Math.max(maximum, terrainY - roadY);
        }
        return maximum == Integer.MIN_VALUE ? overburden(centers, targetY, index) : maximum;
    }

    public static int overburden(List<BlockPos> centers, List<Integer> targetY, int index) {
        BlockPos center = centers.get(index);
        Integer target = targetY.get(index);
        return center == null || target == null ? Integer.MAX_VALUE : center.getY() - target;
    }
}
