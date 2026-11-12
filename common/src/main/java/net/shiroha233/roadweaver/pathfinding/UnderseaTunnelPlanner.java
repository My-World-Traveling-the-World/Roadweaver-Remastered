/* 文件职责：长海桥改海底隧道 —— 桥太长就沉到海底走隧道。 */
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
import static net.shiroha233.roadweaver.pathfinding.FillBridgeExtender.*;
import static net.shiroha233.roadweaver.pathfinding.SpanConflictResolver.*;
import static net.shiroha233.roadweaver.pathfinding.TunnelFootprintSampler.*;

/**
 * 超长海桥 -> 海底隧道。
 *
 * <h2>为什么要有这一条</h2>
 * <p>跨海的桥如果太长，桥墩会非常密集、观感也很差；改成海底隧道反而更自然。
 * 判据是「桥长超过阈值 + 水深够（{@code effectiveBridgeMinDepth}）」，
 * 两个条件都满足才改。</p>
 *
 * <h2>★ 改完之后必须平滑接近段</h2>
 * <p>隧道口有落差，直接把两端站点接上会出现「垂直墙」。
 * {@code smoothUnderseaApproach} 把接近段逐站插值 —— 这是观感问题，
 * 但没有它整条路就没法走。</p>
 *
 * <p>本类是从 1817 行的 {@code PathSpanExtractor} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class UnderseaTunnelPlanner {
    public static List<RoadSpan> convertLongOceanBridgesToUnderseaTunnels(
            List<RoadSpan> existing,
            List<RoadSegmentPlacement> segments,
            ServerLevel level,
            TerrainSamplingCache cache,
            Map<Long, AccurateHeightSample> sampledCenters) {
        if (existing == null || existing.isEmpty() || segments == null
                || segments.size() < 3 || level == null || cache == null) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(existing);
            centers.add(segment.middlePos());
        }
        Map<Long, AccurateHeightSample> centerSamples = sampledCenters == null ? Map.of() : sampledCenters;
        List<RoadSpan> result = new ArrayList<>();
        int converted = 0;
        for (RoadSpan span : existing) {
            if (span == null || span.type() != SpanType.BRIDGE) {
                if (span != null) result.add(span);
                continue;
            }
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) {
                result.add(span);
                continue;
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (crossingWidthBlocks(centers, from, to) < UNDERSEA_TUNNEL_MIN_LENGTH_BLOCKS) {
                result.add(span);
                continue;
            }
            if (!chooseUnderseaTunnel(span, centers)) {
                result.add(span);
                LOGGER.info("RoadWeaver retained long ocean bridge by 50%% selection range={}..{}",
                        from, to);
                continue;
            }
            int oceanColumns = 0;
            int waterColumns = 0;
            int minimumBed = Integer.MAX_VALUE;
            int maximumDepth = 0;
            for (int index = from; index <= to; index++) {
                BlockPos center = centers.get(index);
                AccurateHeightSample sample = centerSamples.get(
                        AccurateHeightSample.key(center.getX(), center.getZ()));
                if (sample == null) continue;
                boolean ocean = cache.getBiome(level, center.getX(), center.getZ()).is(BiomeTags.IS_OCEAN);
                boolean water = SurfaceWaterClassifier.isWaterColumn(
                        sample.worldSurfaceWg(), sample.oceanFloorWg(), ocean, level.getSeaLevel());
                if (!ocean || !water) continue;
                oceanColumns++;
                waterColumns++;
                minimumBed = Math.min(minimumBed, sample.oceanFloorWg());
                maximumDepth = Math.max(maximumDepth,
                        SurfaceWaterClassifier.waterDepth(sample.worldSurfaceWg(), sample.oceanFloorWg(),
                                ocean, level.getSeaLevel(), true));
            }
            int spanColumns = Math.max(1, to - from + 1);
            if (oceanColumns < Math.max(3, Math.ceilDiv(spanColumns * 3, 4))
                    || waterColumns != oceanColumns
                    || maximumDepth < UNDERSEA_TUNNEL_MIN_DEPTH_BLOCKS
                    || minimumBed == Integer.MAX_VALUE) {
                result.add(span);
                continue;
            }
            int tunnelFrom = extendIndexByDistance(centers, from, -1, UNDERSEA_APPROACH_TUNNEL_BLOCKS);
            int tunnelTo = extendIndexByDistance(centers, to, 1, UNDERSEA_APPROACH_TUNNEL_BLOCKS);
            result.add(new RoadSpan(centers.get(tunnelFrom), centers.get(tunnelTo), SpanType.TUNNEL));
            converted++;
            LOGGER.info("RoadWeaver converted long ocean bridge to undersea tunnel range={}..{} approach={} lengthBlocks={} minBedY={} maxDepth={}",
                    tunnelFrom, tunnelTo, UNDERSEA_APPROACH_TUNNEL_BLOCKS,
                    crossingWidthBlocks(centers, from, to), minimumBed, maximumDepth);
        }
        return converted == 0 ? List.copyOf(existing) : normalizeTunnelSpans(result, centers);
    }

    public static boolean chooseUnderseaTunnel(RoadSpan span, List<BlockPos> centers) {
        if (span == null || centers == null) return false;
        long seed = 0x554E444552534541L;
        seed = seed * 31L + span.start().asLong();
        seed = seed * 31L + span.end().asLong();
        if (!centers.isEmpty()) seed ^= centers.get(0).asLong();
        double probability = ConfigService.get().terrainProfile().oceanUnderseaChance();
        return new SplittableRandom(seed).nextDouble() < probability;
    }

    public static int extendIndexByDistance(List<BlockPos> centers, int start, int direction, int blocks) {
        int index = Math.max(0, Math.min(centers.size() - 1, start));
        double travelled = 0.0D;
        while (travelled < blocks) {
            int next = index + direction;
            if (next < 0 || next >= centers.size()) break;
            travelled += horizontalDistance(centers.get(index), centers.get(next));
            index = next;
        }
        return index;
    }

    /** Lowers converted ocean-tunnel stations below the sampled sea floor. */
    public static List<Integer> applyUnderseaTunnelProfile(
            List<Integer> targetY,
            List<RoadSpan> spans,
            List<RoadSegmentPlacement> segments,
            ServerLevel level,
            TerrainSamplingCache cache,
            Map<Long, AccurateHeightSample> sampledCenters,
            int clearHeight) {
        if (targetY == null || spans == null || segments == null || level == null || cache == null
                || targetY.size() != segments.size() || segments.size() < 3) {
            return targetY == null ? List.of() : List.copyOf(targetY);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(targetY);
            centers.add(segment.middlePos());
        }
        Map<Long, AccurateHeightSample> samples = sampledCenters == null ? Map.of() : sampledCenters;
        ArrayList<Integer> result = new ArrayList<>(targetY);
        int safeClear = Math.max(2, Math.min(16, clearHeight));
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            int firstWater = -1;
            int lastWater = -1;
            int waterCount = 0;
            Map<Integer, Integer> underwaterTargets = new java.util.LinkedHashMap<>();
            for (int index = from; index <= to; index++) {
                BlockPos center = centers.get(index);
                AccurateHeightSample sample = samples.get(
                        AccurateHeightSample.key(center.getX(), center.getZ()));
                if (sample == null || !cache.getBiome(level, center.getX(), center.getZ()).is(BiomeTags.IS_OCEAN)
                        || !SurfaceWaterClassifier.isWaterColumn(
                        sample.worldSurfaceWg(), sample.oceanFloorWg(), true, level.getSeaLevel())) continue;
                if (firstWater < 0) firstWater = index;
                lastWater = index;
                waterCount++;
                underwaterTargets.put(index, underseaRoadY(sample.oceanFloorWg()));
            }
            if (firstWater < 0 || waterCount < Math.max(3, Math.ceilDiv(to - from + 1, 2))) continue;

            for (Map.Entry<Integer, Integer> target : underwaterTargets.entrySet()) {
                int index = target.getKey();
                result.set(index, Math.min(result.get(index), target.getValue()));
            }

            // Build short ramps at both sea-floor tunnel mouths instead of a
            // vertical drop from the surface bridge deck.
            smoothUnderseaApproach(result, targetY, firstWater, -1, safeClear);
            smoothUnderseaApproach(result, targetY, lastWater, 1, safeClear);
        }
        return List.copyOf(result);
    }

    /**
     * The deck level for an undersea tunnel station, from the ocean floor's first water block.
     *
     * <p>{@code oceanFloorWg} is "topmost solid block + 1", so the topmost solid block is
     * {@code oceanFloorWg - 1}. The deck must sit deep enough that the roof - {@code roofOffset()} above the
     * deck - leaves {@link RoadTunnelBuilder#ROCKS_ABOVE_ROOF} solid blocks below the water. The old formula
     * ({@code oceanFloorWg - safeClear - 1}) put the roof one block <em>above</em> the seabed, i.e. in the
     * first water block: the "tunnel" was a single stone-brick layer lying on the sea floor with the ocean
     * resting on it, and nothing in the pipeline noticed because the overburden test measured to the water
     * surface.
     */
    public static int underseaRoadY(int oceanFloorWg) {
        return oceanFloorWg - 1 - RoadTunnelBuilder.roofOffset() - RoadTunnelBuilder.ROCKS_ABOVE_ROOF;
    }

    public static void smoothUnderseaApproach(List<Integer> result, List<Integer> original,
                                               int mouth, int direction, int clearHeight) {
        if (mouth < 0 || mouth >= result.size()) return;
        int underwater = result.get(mouth);
        int reference = original.get(mouth);
        int maxApproach = Math.max(16, Math.min(128, Math.abs(reference - underwater) * 2 + 16));
        int anchorIndex = mouth + direction * maxApproach;
        if (anchorIndex < 0 || anchorIndex >= original.size()) {
            anchorIndex = mouth + direction * Math.min(16, maxApproach);
        }
        anchorIndex = Math.max(0, Math.min(original.size() - 1, anchorIndex));
        int anchorY = original.get(anchorIndex);
        for (int distance = 1; distance <= maxApproach; distance++) {
            int index = mouth + direction * distance;
            if (index < 0 || index >= result.size()) break;
            double ratio = distance / (double) maxApproach;
            int ramp = (int) Math.round(underwater + (anchorY - underwater) * ratio);
            result.set(index, Math.min(result.get(index), ramp));
        }
    }
}
