/* 文件职责：跨度冲突消解 —— 同段路上桥与隧道重叠、水跨重叠时如何取舍。 */
package net.shiroha233.roadweaver.pathfinding;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps;
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
import static net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner.*;
import static net.shiroha233.roadweaver.pathfinding.FillBridgeExtender.*;
import static net.shiroha233.roadweaver.pathfinding.TunnelFootprintSampler.*;

/**
 * 跨度冲突消解。
 *
 * <h2>为什么会有冲突</h2>
 * <p>桥是「水面列」判出来的、隧道是「覆盖层」判出来的，两套判据独立运行,
 * 在同一段路上可能同时成立。**同一段路不能既是桥又是隧道** ——
 * 施工时后写的会覆盖先写的，结果是半截桥半截隧道。</p>
 *
 * <h2>消解顺序（很重要）</h2>
 * <ol>
 *   <li>{@code normalizeTunnelSpans} —— 先把隧道跨度规整（合并相邻、去掉过短）；</li>
 *   <li>{@code resolveOverlappingWaterSpans} —— 再处理水跨重叠；</li>
 *   <li>{@code retainVerticallyRelevantCrossings} —— 最后按<b>垂直相关性</b>保留：
 *       水下还有地面的才留桥，否则让隧道赢。</li>
 * </ol>
 * <p>顺序不能换：先规整再判重叠，否则会用未规整的边界去算交集。</p>
 *
 * <p>本类是从 1817 行的 {@code PathSpanExtractor} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class SpanConflictResolver {
    public static List<RoadSpan> resolvePhysicalSpanOverlaps(
            List<RoadSpan> spans, List<BlockPos> centers) {
        return normalizeTunnelSpans(spans, centers);
    }

    public static List<RoadSpan> normalizeTunnelSpans(List<RoadSpan> spans, List<BlockPos> centers) {
        if (spans == null || spans.isEmpty() || centers == null || centers.isEmpty()) {
            return spans == null ? List.of() : List.copyOf(spans);
        }
        // One x/z -> index lookup per call: the per-span loops below would
        // otherwise rescan the whole center list for every span endpoint,
        // turning an O(spans + centers) pass into O(spans * centers).
        Map<Long, Integer> centerIndex = xzIndex(centers);
        List<RoadSpan> result = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        // T-26: bridge ranges are collected on the same pass so they can be
        // merged below. Before this, TUNNEL was the only physical type that got
        // a merge, so the final-fill / canyon / water producers could each
        // append a BRIDGE over the same water and every one of them survived.
        List<int[]> bridgeRanges = new ArrayList<>();
        // Spans of every other type keep their original relative order.
        List<RoadSpan> passthrough = new ArrayList<>();
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.TUNNEL && span.type() != SpanType.BRIDGE)) {
                passthrough.add(span);
                continue;
            }
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) {
                // Unresolvable endpoint: keep the span untouched. TUNNEL used to
                // be dropped here and still is; a BRIDGE was kept, so it stays
                // kept (it simply cannot join the merge below).
                if (span.type() == SpanType.BRIDGE) passthrough.add(span);
                continue;
            }
            // Keep the pre-existing drop of single-station TUNNELs; a
            // one-station BRIDGE was always retained, so it still is here and
            // simply participates in the merge below.
            if (start == end && span.type() == SpanType.TUNNEL) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (span.type() == SpanType.TUNNEL) ranges.add(new int[]{from, to});
            else bridgeRanges.add(new int[]{from, to});
        }
        result.addAll(passthrough);
        mergeRangesInto(result, centers, ranges, SpanType.TUNNEL);
        mergeRangesInto(result, centers, bridgeRanges, SpanType.BRIDGE);
        return resolveOverlappingWaterSpans(result, centers, centerIndex);
    }

    /** 同物理类型的重叠或相邻跨度合并后统一插值，避免共享站被两次剖面覆盖产生错层。
 *
     * T-26: merges every overlapping-or-adjacent range of one physical type into
     * a single span.
     *
     * <p>Real case (2026-09-16): one water crossing produced two BRIDGE spans
     * whose endpoints differed by 1..30 stations. Both were persisted, and
     * {@code RoadSpanProfileOps.applyCrossingProfiles} then ran {@code applyFixedRange} once
     * per range — each doing its own linear bank-to-bank interpolation over the
     * shared stations. The later range overwrote the earlier deck height, so a
     * single bridge came out as two parallel decks a few blocks apart, with a
     * visible step at the overlap.</p>
     *
     * <p>Adjacency ({@code range[0] <= end + 1}) merges too, matching the
     * TUNNEL behaviour: two bridges separated by a single dry station are one
     * structure, and leaving a one-station gap there produces the same deck
     * discontinuity.</p>
     */
    public static void mergeRangesInto(List<RoadSpan> result,
                                        List<BlockPos> centers,
                                        List<int[]> ranges,
                                        SpanType type) {
        if (ranges.isEmpty()) return;
        ranges.sort(java.util.Comparator.comparingInt(range -> range[0]));
        int start = -1;
        int end = -1;
        for (int[] range : ranges) {
            if (start < 0) {
                start = range[0];
                end = range[1];
            } else if (range[0] <= end + 1) {
                end = Math.max(end, range[1]);
            } else {
                result.add(new RoadSpan(centers.get(start), centers.get(end), type));
                start = range[0];
                end = range[1];
            }
        }
        if (start >= 0) result.add(new RoadSpan(centers.get(start), centers.get(end), type));
    }

    /** 桥、短跨越与隧道按站序互斥；隧道优先后保留水跨度的非重叠部分。
 *
     * Physical-span mutual exclusion (T5): a station may carry only one of
     * bridge / crossing / tunnel. Tunnel selection is based on the real
     * overburden profile and the undersea conversion is a deliberate
     * water-column decision, so where a tunnel range overlaps a bridge or
     * crossing the tunnel wins and the water span is dropped or shrunk to its
     * non-overlapping remainder. Without this, a converted undersea tunnel's
     * ±128-station approach swallows a neighbouring bridge and the validator
     * rejects the road with overlapping-physical-spans. Runs after every
     * tunnel-normalizing producer, so the self-check covers all of them.
     */
    public static List<RoadSpan> resolveOverlappingWaterSpans(List<RoadSpan> spans,
                                                               List<BlockPos> centers) {
        return resolveOverlappingWaterSpans(spans, centers, xzIndex(centers));
    }

    public static List<RoadSpan> resolveOverlappingWaterSpans(List<RoadSpan> spans,
                                                               List<BlockPos> centers,
                                                               Map<Long, Integer> centerIndex) {
        int n = centers.size();
        boolean[] tunnel = new boolean[n];
        boolean anyTunnel = false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL) continue;
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                tunnel[i] = true;
                anyTunnel = true;
            }
        }
        if (!anyTunnel) return spans;
        List<RoadSpan> result = new ArrayList<>(spans.size());
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) {
                result.add(span);
                continue;
            }
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) {
                result.add(span);
                continue;
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            List<int[]> freeRuns = new ArrayList<>();
            int runStart = -1;
            for (int i = from; i <= to; i++) {
                if (!tunnel[i]) {
                    if (runStart < 0) runStart = i;
                } else if (runStart >= 0) {
                    freeRuns.add(new int[]{runStart, i - 1});
                    runStart = -1;
                }
            }
            if (runStart >= 0) freeRuns.add(new int[]{runStart, to});
            if (freeRuns.size() == 1 && freeRuns.get(0)[0] == from
                    && freeRuns.get(0)[1] == to) {
                result.add(span);
                continue;
            }
            LOGGER.info("【物理跨度重叠已消解，隧道优先并保留其余片段】 " + "RoadWeaver resolved physical span overlap: tunnel wins, {} range={}..{} "
                            + "kept as {} remainder run(s)",
                    span.type(), from, to, freeRuns.size());
            for (int[] run : freeRuns) {
                // A bridge needs two distinct endpoints; a one-station bridge
                // remainder is invalid, so it is dropped (the tunnel already
                // implements those stations). A one-station crossing is valid.
                if (span.type() == SpanType.BRIDGE && run[0] == run[1]) {
                    LOGGER.info("【隧道内部的单站桥余段已移除】 " + "RoadWeaver dropped one-station bridge remainder inside tunnel range={}..{}",
                            from, to);
                    continue;
                }
                result.add(new RoadSpan(centers.get(run[0]), centers.get(run[1]), span.type()));
            }
        }
        return result;
    }

    /** 只有完整隧道净空低于河床时才移除水跨越，仍接触水列的路保留原水跨度判定。
 *
     * Removes a surface-water crossing when the complete tunnel clearance is
     * below the river bed. Crossing width and bridge selection stay unchanged
     * for roads whose construction volume actually reaches the water column.
     */
    public static List<RoadSpan> retainVerticallyRelevantCrossings(
            List<RoadSpan> existing,
            List<RoadSpan> provisionalTunnels,
            List<RoadSegmentPlacement> segments,
            List<Integer> provisionalTargetY,
            ServerLevel level,
            TerrainSamplingCache cache,
            PathTerrainField terrain,
            StreamsReflowingCompat.SamplingSession streams,
            int tunnelClearHeight,
            int bridgeMinWaterDepth) {
        if (existing == null || existing.isEmpty() || segments == null
                || provisionalTargetY == null || segments.size() != provisionalTargetY.size()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }

        List<BlockPos> centers = new ArrayList<>(segments.size());
        Map<Long, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            BlockPos center = segments.get(i).middlePos();
            centers.add(center);
            indices.putIfAbsent(xzKey(center), i);
        }
        boolean[] tunnel = new boolean[segments.size()];
        if (provisionalTunnels != null) {
            for (RoadSpan span : provisionalTunnels) {
                if (span == null || span.type() != SpanType.TUNNEL) continue;
                Integer start = indices.get(xzKey(span.start()));
                Integer end = indices.get(xzKey(span.end()));
                if (start == null || end == null) continue;
                for (int i = Math.min(start, end); i <= Math.max(start, end); i++) tunnel[i] = true;
            }
        }

        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        Map<Long, AccurateHeightSample> samples = accurate.samplePositions(centers);
        List<RoadSpan> retained = new ArrayList<>(existing.size());
        int clearHeight = Math.max(2, Math.min(16, tunnelClearHeight));
        for (RoadSpan span : existing) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) {
                retained.add(span);
                continue;
            }
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) {
                retained.add(span);
                continue;
            }

            int from = Math.min(start, end);
            int to = Math.max(start, end);
            boolean foundWater = false;
            boolean safelyBelowEveryWaterColumn = true;
            int maximumTunnelCeiling = Integer.MIN_VALUE;
            int minimumBed = Integer.MAX_VALUE;
            for (int i = from; i <= to; i++) {
                BlockPos center = centers.get(i);
                BridgeWaterSample water = bridgeWaterSample(
                        level, cache, terrain, accurate, samples, streams, center, bridgeMinWaterDepth);
                if (!water.water()) continue;
                foundWater = true;
                int ceilingY = provisionalTargetY.get(i) + clearHeight - 1;
                maximumTunnelCeiling = Math.max(maximumTunnelCeiling, ceilingY);
                minimumBed = Math.min(minimumBed, water.bedY());
                if (!tunnel[i] || !tunnelFitsBelowWaterBed(
                        provisionalTargetY.get(i), clearHeight, water.bedY())) {
                    safelyBelowEveryWaterColumn = false;
                    break;
                }
            }
            if (foundWater && safelyBelowEveryWaterColumn) {
                LOGGER.info("【水面下仍有足够覆土，保留地下隧道】 " + "RoadWeaver retained underground tunnel below surface water type={} range={}..{} "
                                + "maximumTunnelCeilingY={} minimumRiverBedY={}",
                        span.type(), from, to, maximumTunnelCeiling, minimumBed);
                continue;
            }
            retained.add(span);
        }
        return retained;
    }

    public static boolean tunnelFitsBelowWaterBed(int roadY, int clearHeight, int bedY) {
        if (bedY == Integer.MIN_VALUE) return false;
        int safeClearHeight = Math.max(2, Math.min(16, clearHeight));
        long ceilingY = (long) roadY + safeClearHeight - 1L;
        return ceilingY < bedY;
    }
}
