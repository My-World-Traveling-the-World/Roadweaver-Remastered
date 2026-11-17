/* 文件职责：填方桥跨度的补齐与延长 —— 让桥跨覆盖真正的岸到岸。 */
package net.shiroha233.roadweaver.pathfinding;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import static net.shiroha233.roadweaver.pathfinding.SpanConflictResolver.*;
import static net.shiroha233.roadweaver.pathfinding.TunnelFootprintSampler.*;

/**
 * 填方桥跨度的补齐与延长。
 *
 * <h2>它解决什么</h2>
 * <p>跨度提取是按「水面列」判的，但桥要落在<b>两岸</b>上。如果只按水面判，
 * 桥跨会比实际的河道窄，桥头会悬空。所以这里把跨度向外补到实岸
 * （{@code extendBridgeBank} / {@code extendExistingBridgesIntoCorridor}）。</p>
 *
 * <h2>★ 补的时候要看「走廊是否恢复」</h2>
 * <p>{@code corridorResumes} 判断延长之后路面是否真的回到陆地上 ——
 * 否则会在水中间把桥结束掉。这一条是补岸的唯一停止条件。</p>
 *
 * <p>本类是从 1817 行的 {@code PathSpanExtractor} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class FillBridgeExtender {
    public static List<RoadSpan> refineFinalFillBridgeSpans(List<RoadSpan> existing,
                                                             List<RoadSegmentPlacement> segments,
                                                             List<Integer> targetY,
                                                             ServerLevel level,
                                                             TerrainSamplingCache cache) {
        if (segments == null || targetY == null || segments.size() != targetY.size()
                || segments.size() < 3 || level == null || cache == null) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        Set<Long> sampledKeys = new HashSet<>();
        List<BlockPos> positions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return existing == null ? List.of() : List.copyOf(existing);
            centers.add(segment.middlePos());
            if (segment.positions() == null) continue;
            for (BlockPos position : segment.positions()) {
                if (position != null && sampledKeys.add(AccurateHeightSample.key(position.getX(), position.getZ()))) {
                    positions.add(position);
                }
            }
        }
        Map<Long, AccurateHeightSample> samples = cache.getAccurateSampler(level).samplePositions(positions);
        int[] lowest = new int[segments.size()];
        java.util.Arrays.fill(lowest, Integer.MAX_VALUE);
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        for (int index = 0; index < segments.size(); index++) {
            List<BlockPos> footprint = segments.get(index).positions();
            if (footprint == null) continue;
            for (BlockPos position : footprint) {
                if (position == null) continue;
                AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
                if (sample != null) lowest[index] = Math.min(lowest[index], accurate.surfaceHeight(sample));
            }
        }
        return refineFinalFillBridgeSpans(existing, centers, targetY, lowest);
    }

    /** 只延伸已选中的河流桥到实际雕刻河岸；干走廊本身不能创建新桥。
 *
     * Extends an already selected Streams bridge through the remainder of its
     * own carved corridor.  This is deliberately not a new crossing detector:
     * a dry Streams corridor cannot create a bridge by itself.  It only keeps
     * a bridge chosen from a real channel from stopping before the bank.
     */
    public static List<RoadSpan> extendExistingStreamsBridgeApproaches(List<RoadSpan> existing,
                                                                         List<RoadSegmentPlacement> segments,
                                                                         StreamsReflowingCompat.SamplingSession streams) {
        if (existing == null || existing.isEmpty() || segments == null || segments.size() < 2
                || streams == null || !streams.isAvailable()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(existing);
            centers.add(segment.middlePos());
        }
        boolean[] corridor = new boolean[centers.size()];
        for (int index = 0; index < centers.size(); index++) {
            BlockPos center = centers.get(index);
            corridor[index] = streams.sample(center.getX(), center.getZ()).inCorridor();
        }
        return extendExistingBridgesIntoCorridor(existing, centers, corridor);
    }

    public static List<RoadSpan> extendExistingBridgesIntoCorridor(List<RoadSpan> existing,
                                                              List<BlockPos> centers,
                                                              boolean[] corridor) {
        if (existing == null || existing.isEmpty() || centers == null || centers.isEmpty()
                || corridor == null || corridor.length != centers.size()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<int[]> ranges = new ArrayList<>();
        List<RoadSpan> result = new ArrayList<>();
        boolean changed = false;
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
            int lower = Math.min(start, end);
            int upper = Math.max(start, end);
            int extendedLower = extendBridgeBank(corridor, lower, -1);
            int extendedUpper = extendBridgeBank(corridor, upper, 1);
            changed |= extendedLower != lower || extendedUpper != upper;
            ranges.add(new int[]{extendedLower, extendedUpper});
        }
        if (ranges.isEmpty() || !changed) return List.copyOf(existing);

        ranges.sort(java.util.Comparator.comparingInt(range -> range[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty() || range[0] > merged.getLast()[1] + 1) {
                merged.add(range);
            } else {
                merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            }
        }
        for (int[] range : merged) {
            result.add(new RoadSpan(centers.get(range[0]), centers.get(range[1]), SpanType.BRIDGE));
        }
        return List.copyOf(result);
    }

    public static int extendBridgeBank(boolean[] corridor, int bridgeEdge, int direction) {
        int index = bridgeEdge;
        for (int candidate = bridgeEdge + direction;
             candidate >= 0 && candidate < corridor.length;
             candidate += direction) {
            if (corridor[candidate]) {
                index = candidate;
                continue;
            }
            if (corridorResumes(corridor, candidate, direction)) {
                index = candidate;
                continue;
            }
            // Include one stable bank station so the template and the ordinary
            // road own the same landing column, then stop on dry land.
            if (index != bridgeEdge) index = candidate;
            break;
        }
        return index;
    }

    public static boolean corridorResumes(boolean[] corridor, int from, int direction) {
        for (int step = 1; step <= STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS; step++) {
            int candidate = from + direction * step;
            if (candidate < 0 || candidate >= corridor.length) return false;
            if (corridor[candidate]) return true;
        }
        return false;
    }

    public static List<RoadSpan> refineFinalFillBridgeSpans(List<RoadSpan> existing,
                                                      List<BlockPos> centers,
                                                      List<Integer> targetY,
                                                      int[] lowestFootprintSurface) {
        if (centers == null || targetY == null || lowestFootprintSurface == null
                || centers.size() != targetY.size() || centers.size() != lowestFootprintSurface.length
                || centers.size() < 3) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<RoadSpan> result = new ArrayList<>(existing == null ? List.of() : existing);
        boolean[] special = specialSpanMask(result, centers);
        // Dry-depression bridging has its own threshold; the ordinary fill
        // limit stays owned by RoadGradePlanner's shared grade snapshot.
        int dryBridgeDepth = dryDepressionBridgeDepth();
        boolean[] unsafeFill = new boolean[targetY.size()];
        for (int index = 0; index < targetY.size(); index++) {
            if (special[index] || lowestFootprintSurface[index] == Integer.MAX_VALUE) continue;
            unsafeFill[index] = targetY.get(index) - lowestFootprintSurface[index] >= dryBridgeDepth;
        }
        appendFinalFillBridgeSpans(result, centers, targetY, lowestFootprintSurface, unsafeFill, special);
        return List.copyOf(result);
    }

    public static boolean[] specialSpanMask(List<RoadSpan> spans, List<BlockPos> centers) {
        boolean[] special = new boolean[centers.size()];
        if (spans == null) return special;
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING
                    && span.type() != SpanType.TUNNEL
                    && span.type() != SpanType.TUNNEL_JUNCTION
                    && span.type() != SpanType.NETWORK_REUSE
                    && span.type() != SpanType.CULVERT
                    && span.type() != SpanType.PROTECTED_GAP)) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            for (int index = Math.min(start, end); index <= Math.max(start, end); index++) special[index] = true;
        }
        return special;
    }

    public static void appendFinalFillBridgeSpans(List<RoadSpan> spans,
                                                   List<BlockPos> centers,
                                                   List<Integer> targetY,
                                                   int[] lowestFootprintSurface,
                                                   boolean[] unsafeFill,
                                                   boolean[] special) {
        for (int index = 0; index < unsafeFill.length; ) {
            while (index < unsafeFill.length && !unsafeFill[index]) index++;
            if (index >= unsafeFill.length) return;
            int unsafeStart = index;
            while (index + 1 < unsafeFill.length && unsafeFill[index + 1]) index++;
            int unsafeEnd = index++;

            int bridgeStart = findDryDepressionBank(
                    unsafeStart, -1, unsafeFill, special);
            int bridgeEnd = findDryDepressionBank(
                    unsafeEnd, 1, unsafeFill, special);
            // A deep fill can start or end at the route boundary. There is no
            // ordinary bank on the missing side, but the endpoint itself is a
            // valid landing station. Without this fallback the unsafe run was
            // silently left unclassified and ordinary paving later skipped it
            // one station at a time, producing a disconnected canyon.
            if (bridgeStart < 0 && unsafeStart == 0 && !special[0]) bridgeStart = 0;
            if (bridgeEnd < 0 && unsafeEnd == unsafeFill.length - 1
                    && !special[unsafeFill.length - 1]) bridgeEnd = unsafeFill.length - 1;
            if (bridgeStart < 0 || bridgeEnd < 0 || bridgeStart >= bridgeEnd) continue;
            int maximumDepth = 0;
            for (int candidate = unsafeStart; candidate <= unsafeEnd; candidate++) {
                maximumDepth = Math.max(maximumDepth,
                        targetY.get(candidate) - lowestFootprintSurface[candidate]);
            }
            int widthBlocks = crossingWidthBlocks(centers, bridgeStart, bridgeEnd);
            int bankHeightDelta = Math.abs(targetY.get(bridgeStart) - targetY.get(bridgeEnd));
            SpanType type = crossingTypeForWidth(widthBlocks);
            // A deep depression or a large bank mismatch cannot be represented
            // by ordinary fill. Promote it to a bridge even when its horizontal
            // width is short; this prevents a 20+ block vertical fill from
            // being retried as a normal road and then rejected downstream.
            if (maximumDepth >= DRY_DEPRESSION_FORCE_BRIDGE_DEPTH
                    || bankHeightDelta > MAX_DRY_CROSSING_ENDPOINT_DELTA) {
                type = SpanType.BRIDGE;
            }
            appendNormalizedWaterSpan(spans, centers, bridgeStart, bridgeEnd, type);
            LOGGER.info("【最终纵断面的干谷跨越方案】 " + "RoadWeaver final-grade dry depression plan type={} widthBlocks={} "
                            + "unsafeFillRange={}..{} stableRange={}..{} depth={} bankHeightDelta={}",
                    type, widthBlocks, unsafeStart, unsafeEnd, bridgeStart, bridgeEnd,
                    maximumDepth, bankHeightDelta);
        }
    }

    public static int findDryDepressionBank(int start,
                                             int direction,
                                             boolean[] unsafeFill,
                                             boolean[] special) {
        if (unsafeFill == null || special == null || unsafeFill.length != special.length) return -1;
        for (int distance = 1; distance <= DRY_DEPRESSION_BANK_SEARCH_STATIONS; distance++) {
            int index = start + direction * distance;
            if (index < 0 || index >= unsafeFill.length) return -1;
            if (!unsafeFill[index] && !special[index]) return index;
        }
        return -1;
    }
}
