/* 文件职责：两条路「复用同一段走廊」时的合并判定 —— 判定 + 合并，不改既有路。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 仍留在 RoadSnapService 里的常量、类型与工具（包内可见）。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;

/**
 * 路网复用（NETWORK_REUSE）的合并判定。
 *
 * <h2>它在决定什么</h2>
 * <p>两条路如果有一段走廊重合，让它们<b>共用一段路面</b>比各铺一条更合理 ——
 * 否则会出现两条几乎平行的路。但「看起来重合」不等于「可以合」：高低不同、
 * 隧道/桥的等级不同、端点朝向不一致，合了就是错。所以这里有一整套判据。</p>
 *
 * <h2>三种合并来源</h2>
 * <ul>
 *   <li>{@code tryReuseBetweenTwoApproaches} —— 两条路的<b>接近段</b>重合；</li>
 *   <li>{@code tryReuseBetweenTwoJunctions} —— 两条路<b>各自的路口</b>相邻；</li>
 *   <li>{@code tryMergeOverlappingRun} —— 已经确认重合，做实际合并，
 *       失败必须走 {@code logOverlapReject} 留证据。</li>
 * </ul>
 *
 * <h2>★ 与 PROTECTED_GAP 的关键差别</h2>
 * <p>复用这条链路有<b>校验兜底</b>（{@code verifySurrenderedReuse}）——交出施工权后会回头
 * 验证接收方真的接管了；保护缺口那条判据没有 fail-safe。任何改动都必须同时改那个校验。</p>
 *
 * <p>本类是从 3639 行的 {@code RoadSnapService} 里<b>纯搬迁</b>出来的，
 * 判据与所有阈值一字未改（含缩进）。</p>
 */
final class NetworkReuseMerger {
    static RoadData tryReuseBetweenTwoApproaches(RoadData primary,
                                                          RoadData secondary,
                                                          int[] targets,
                                                          List<int[]> runs) {
        if (targets == null || runs == null || runs.isEmpty()) return null;
        List<RoadSegmentPlacement> primarySegments = primary.roadSegmentList();
        List<RoadSegmentPlacement> secondarySegments = secondary.roadSegmentList();
        boolean[] primaryBlocked = mergeBlockedSpanMask(primary);
        boolean[] secondaryBlocked = mergeBlockedSpanMask(secondary);
        for (int[] run : runs) {
            if (run == null || run.length < 2) continue;
            int fromLimit = Math.max(run[0], ENDPOINT_RUN_MAX_OFFSET + 1);
            int toLimit = Math.min(run[1], secondarySegments.size() - 2 - ENDPOINT_RUN_MAX_OFFSET);
            if (toLimit - fromLimit + 1 < OVERLAP_MERGE_MIN_RUN) continue;

            ApproachBoundary fromBoundary = findApproachBoundary(
                    primary, primaryBlocked, secondary, targets, fromLimit, toLimit, 1);
            ApproachBoundary toBoundary = findApproachBoundary(
                    primary, primaryBlocked, secondary, targets, toLimit, fromLimit, -1);
            if (fromBoundary == null || toBoundary == null) continue;
            int from = fromBoundary.secondaryIndex();
            int to = toBoundary.secondaryIndex();
            if (to - from + 1 < OVERLAP_MERGE_MIN_RUN) continue;
            int primaryFrom = fromBoundary.primaryIndex();
            int primaryTo = toBoundary.primaryIndex();
            if (primaryFrom < 0 || primaryTo < 0 || primaryFrom == primaryTo) continue;
            int primaryMin = Math.min(primaryFrom, primaryTo);
            int primaryMax = Math.max(primaryFrom, primaryTo);
            if (primaryMax - primaryMin + 1 < OVERLAP_MERGE_MIN_RUN
                    || rangeHasSpecial(primaryBlocked, primaryMin, primaryMax)
                    || rangeHasSpecial(secondaryBlocked, from, to)) continue;
            Boolean increasing = targetIndicesIncrease(targets, new int[]{from, to});
            if (increasing == null
                    || !stableContactRun(targets, new int[]{from, to}, increasing ? 1 : -1)) continue;

            List<RoadSegmentPlacement> merged = new ArrayList<>();
            List<Integer> mergedY = new ArrayList<>();
            appendRange(secondary, 0, from + 1, merged, mergedY);
            if (!appendApproachConnector(merged, mergedY, secondary.width(),
                    primarySegments.get(primaryFrom), targetAt(primary, primaryFrom))) continue;
            int reuseStart = merged.size();
            appendDirected(primary, primaryFrom, primaryTo, merged, mergedY);
            int reuseEnd = merged.size() - 1;
            if (!appendApproachConnector(merged, mergedY, secondary.width(),
                    secondarySegments.get(to), targetAt(secondary, to))) continue;
            appendRange(secondary, to, secondarySegments.size(), merged, mergedY);
            removeConsecutiveDuplicateCenters(merged, mergedY);
            if (!validMergedSequence(merged, mergedY)) continue;

            List<RoadSpan> spans = new ArrayList<>(filterSpansByRetainedSegments(secondary.spans(), merged));
            addClippedOwnerSpans(spans, secondary, from, to, merged);
            addClippedOwnerSpans(spans, primary, primaryMin, primaryMax, merged);
            addNetworkReuse(spans, merged, reuseStart, reuseEnd);
            addJunctionMarker(spans, merged, Math.max(0, reuseStart - 1), reuseStart,
                    primary, primaryFrom);
            addJunctionMarker(spans, merged, reuseEnd, Math.min(merged.size() - 1, reuseEnd + 1),
                    primary, primaryTo);
            LOGGER.info("RoadWeaver joined nearby middle corridor primary={} secondary={} secondaryRun={}..{} "
                            + "primaryRun={}..{} segments={}",
                    RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                    from, to, primaryFrom, primaryTo, merged.size());
            return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                    secondary.slabMaterials(), merged, spans, mergedY,
                    secondary.ownerA2dKey(), secondary.ownerB2dKey());
        }
        return null;
    }

    static ApproachBoundary findApproachBoundary(RoadData primary,
                                                          boolean[] primaryBlocked,
                                                          RoadData secondary,
                                                          int[] targets,
                                                          int start,
                                                          int end,
                                                          int step) {
        for (int index = start; ; index += step) {
            int primaryIndex = findSafePrimaryIndex(
                    primary, primaryBlocked, secondary, index, targets[index]);
            if (primaryIndex >= 0 && isSafeJunctionIndex(secondary, index)) {
                return new ApproachBoundary(index, primaryIndex);
            }
            if (index == end) break;
        }
        return null;
    }

    static void addJunctionMarker(List<RoadSpan> spans,
                                          List<RoadSegmentPlacement> segments,
                                          int from,
                                          int to,
                                          RoadData primary,
                                          int primaryIndex) {
        if (from < 0 || to < 0 || from >= segments.size() || to >= segments.size() || from == to) return;
        BlockPos first = segments.get(from).middlePos();
        BlockPos second = segments.get(to).middlePos();
        if (first == null || second == null) return;
        SpanType type = isIndexInSpan(primary, primaryIndex, SpanType.TUNNEL)
                ? SpanType.TUNNEL_JUNCTION : SpanType.JUNCTION_ANCHOR;
        spans.add(new RoadSpan(first, second, type));
    }

    /**
     * Reuses an existing road between two separated approach runs. This is the
     * planning-stage form of the blue-point case: the later route enters the
     * owner road, follows its exact segments, then leaves at the second point.
     */
    static RoadData tryReuseBetweenTwoJunctions(RoadData primary,
                                                        RoadData secondary,
                                                        int[] targets,
                                                        List<int[]> runs) {
        if (targets == null || runs == null || runs.size() < 2) return null;
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        boolean[] primaryBlocked = mergeBlockedSpanMask(primary);
        boolean[] secondaryBlocked = mergeBlockedSpanMask(secondary);
        for (int first = 0; first < runs.size() - 1; first++) {
            int[] left = runs.get(first);
            if (left == null || left.length < 2) continue;
            for (int second = first + 1; second < runs.size(); second++) {
                int[] right = runs.get(second);
                if (right == null || right.length < 2) continue;
                int secFrom = Math.max(0, left[1]);
                int secTo = Math.min(sec.size() - 1, right[0]);
                if (secFrom >= secTo || secFrom < ENDPOINT_RUN_MAX_OFFSET
                        || secTo >= sec.size() - 1 - ENDPOINT_RUN_MAX_OFFSET) continue;
                int pFrom = targets[secFrom];
                int pTo = targets[secTo];
                if (pFrom < 0 || pTo < 0 || pFrom == pTo) continue;
                int pStep = pTo > pFrom ? 1 : -1;
                int pStart = Math.min(pFrom, pTo);
                int pEnd = Math.max(pFrom, pTo);
                if (pEnd - pStart + 1 < OVERLAP_MERGE_MIN_RUN
                        || rangeHasSpecial(primaryBlocked, pStart, pEnd)) continue;
                if (!stableContactRun(targets, left, Integer.signum(targets[left[1]] - targets[left[0]]))
                        || !stableContactRun(targets, right, Integer.signum(targets[right[1]] - targets[right[0]]))
                        || !junctionNear(primary, secondary, targets, secFrom)
                        || !junctionNear(primary, secondary, targets, secTo)) continue;
                if (rangeHasSpecial(secondaryBlocked, secFrom, secTo)
                        && !boundaryOnlySpecial(secondaryBlocked, secFrom, secTo)) continue;
                List<RoadSegmentPlacement> merged = new ArrayList<>();
                List<Integer> mergedY = new ArrayList<>();
                appendRange(secondary, 0, secFrom, merged, mergedY);
                appendDirected(primary, pFrom, pTo, merged, mergedY);
                appendRange(secondary, secTo, sec.size(), merged, mergedY);
                removeConsecutiveDuplicateCenters(merged, mergedY);
                if (!validMergedSequence(merged, mergedY)) continue;

                List<RoadSpan> spans = new ArrayList<>(filterSpansByRetainedSegments(secondary.spans(), merged));
                addClippedOwnerSpans(spans, secondary, secFrom, secTo, merged);
                int sharedStart = secFrom;
                int sharedEnd = sharedStart + Math.abs(pTo - pFrom);
                addClippedOwnerSpans(spans, primary, pStart, pEnd, merged);
                addNetworkReuse(spans, merged, sharedStart, sharedEnd);
                addJunctionAnchor(spans, merged, Math.max(0, sharedStart - 1), sharedStart);
                addJunctionAnchor(spans, merged, sharedEnd, Math.min(merged.size() - 1, sharedEnd + 1));
                LOGGER.info("RoadWeaver planned network reuse primary={} secondary={} entry={} exit={} ownerSpan={} segments={}",
                        RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                        secFrom, secTo, pStart + ".." + pEnd, merged.size());
                return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                        secondary.slabMaterials(), merged, spans, mergedY,
                        secondary.ownerA2dKey(), secondary.ownerB2dKey());
            }
        }
        return null;
    }

    static boolean stableContactRun(int[] targets, int[] run, int step) {
        if (run == null || run.length < 2 || run[1] - run[0] + 1 < OVERLAP_MERGE_MIN_RUN) return false;
        if (step == 0) return false;
        int previous = -1;
        for (int i = run[0]; i <= run[1]; i++) {
            int current = targets[i];
            if (current < 0) return false;
            if (previous >= 0 && current != previous && Integer.signum(current - previous) != step) return false;
            previous = current;
        }
        return true;
    }

    static boolean boundaryOnlySpecial(boolean[] blocked, int from, int to) {
        for (int i = from + 1; i < to; i++) if (blocked[i]) return false;
        return true;
    }

    /**
     * Endpoint snapping cannot remove the duplicate corridor shown by two
     * roads travelling beside one another through the same mountain pass.
     * Replace only a long, direction-compatible middle run with the primary
     * road's exact segments.  Short near misses remain independent roads.
     *
     * <p>0.8.366 (M1): a single blocked station no longer discards the whole
     * run. Real saves show one <b>678-station</b> parallel corridor being
     * rejected because one station lay inside a bridge; the two roads then
     * weave through each other with only fragments deduplicated — exactly the
     * braided duplicate seen on the map. The run is now reduced to its longest
     * unblocked sub-range first, and the original whole-range veto is kept as a
     * final guard, so this change can only accept merges the old code rejected
     * and never rejects one it accepted.</p>
     */
    static RoadData tryMergeOverlappingRun(RoadData primary,
                                                   RoadData secondary,
                                                   int[] targets,
                                                   List<int[]> runs) {
        if (primary == null || secondary == null || targets == null || runs == null) return null;
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        if (pri == null || sec == null || sec.size() < OVERLAP_MERGE_MIN_RUN * 2) {
            logOverlapReject("size", primary, secondary, -1, -1, -1, -1);
            return null;
        }
        boolean[] priBlocked = mergeBlockedSpanMask(primary);
        boolean[] secBlocked = mergeBlockedSpanMask(secondary);
        boolean anyRun = false;
        for (int[] run : runs) {
            if (run == null || run.length < 2) continue;
            anyRun = true;
            int from = Math.max(0, run[0]);
            int to = Math.min(sec.size() - 1, run[1]);
            while (from <= to && !corridorBoundaryNear(primary, secondary, targets, from)) from++;
            while (to >= from && !corridorBoundaryNear(primary, secondary, targets, to)) to--;
            if (to - from + 1 < OVERLAP_MERGE_MIN_RUN) {
                logOverlapReject("corridor-boundary", primary, secondary, run[0], run[1], from, to);
                continue;
            }

            // 0.8.367 (B): the endpoint guard used to discard the WHOLE run when
            // its head or tail merely touched the protected 12-station margin.
            // Roads that leave one shared structure overlap precisely at that
            // head, so real saves showed runs like [0..967] over a 1142-station
            // road — 85% of the route — thrown away over its first 13 stations.
            // Shrink the run into the safe zone instead, and let the normal
            // checks below decide. This is strictly permissive: a run that
            // already sat inside the margin follows the identical path.
            int safeFrom = Math.max(from, ENDPOINT_RUN_MAX_OFFSET + 1);
            int safeTo = Math.min(to, sec.size() - 2 - ENDPOINT_RUN_MAX_OFFSET);
            if (safeTo - safeFrom + 1 < OVERLAP_MERGE_MIN_RUN) {
                logOverlapReject("endpoint-margin", primary, secondary, from, to, safeFrom, safeTo);
                continue;
            }

            Boolean increasing = targetIndicesIncrease(targets, new int[]{safeFrom, safeTo});
            if (increasing == null) {
                logOverlapReject("targets-not-monotonic", primary, secondary, safeFrom, safeTo, -1, -1);
                continue;
            }

            // 0.8.366 (M1): shrink onto the longest clean sub-range instead of
            // discarding the entire run over one blocked station. The endpoint
            // margin is already applied above, so no second guard is needed
            // here (clean is a sub-range of [safeFrom..safeTo] by construction).
            int[] clean = longestUnblockedSubRun(safeFrom, safeTo, targets, priBlocked, secBlocked);
            if (clean == null) {
                logOverlapReject("no-clean-subrun", primary, secondary, safeFrom, safeTo, -1, -1);
                continue;
            }
            int cleanFrom = clean[0];
            int cleanTo = clean[1];

            int primaryFrom = targets[cleanFrom];
            int primaryTo = targets[cleanTo];
            if (primaryFrom < 0 || primaryTo < 0) {
                logOverlapReject("primary-map-missing", primary, secondary, cleanFrom, cleanTo, primaryFrom, primaryTo);
                continue;
            }
            int pFrom = Math.min(primaryFrom, primaryTo);
            int pTo = Math.max(primaryFrom, primaryTo);
            // Final guard: the mapped primary range must be clean too, because
            // the secondary run may not touch every blocked primary station.
            if (pTo - pFrom + 1 < OVERLAP_MERGE_MIN_RUN) {
                logOverlapReject("primary-range-short", primary, secondary, cleanFrom, cleanTo, pFrom, pTo);
                continue;
            }
            if (rangeHasSpecial(priBlocked, pFrom, pTo)) {
                // Fall back to the original whole-range behaviour rather than
                // persisting a merge that still overlaps an engineered span.
                logOverlapReject("primary-range-engineered", primary, secondary, cleanFrom, cleanTo, pFrom, pTo);
                continue;
            }
            from = cleanFrom;
            to = cleanTo;
            if (!parallelCorridor(secondary, from, to, primary, pFrom, pTo, targets)) {
                logOverlapReject("not-parallel", primary, secondary, from, to, pFrom, pTo);
                continue;
            }

            List<RoadSegmentPlacement> merged = new ArrayList<>(sec.size() - (to - from + 1) + (pTo - pFrom + 1));
            List<Integer> mergedY = new ArrayList<>(merged.size());
            appendRange(secondary, 0, from + 1, merged, mergedY);
            appendRange(primary, pFrom, pTo + 1, merged, mergedY);
            appendRange(secondary, to, sec.size(), merged, mergedY);
            int mergedSharedStart = from + 1;
            int mergedSharedEnd = mergedSharedStart + (pTo - pFrom);
            if (!validMergedSequence(merged, mergedY)) {
                // 0.8.366 (M3): the two corridors can overlap in XZ yet meet at
                // different heights. The middle of the merged road is the
                // primary's exact (already-built) geometry, so only the
                // secondary side may move: ramp its approach down onto the
                // primary's join height, exactly one block per station and
                // never raising a station (same prefer-cut rule as
                // alignSecondaryGradeToAnchor). Re-validate afterwards, so a
                // merge that still cannot be graded is discarded as before.
                boolean lowered = false;
                if (mergedSharedStart > 0 && mergedSharedStart < mergedY.size()) {
                    lowered |= lowerToward(mergedY, mergedSharedStart, -1, mergedY.get(mergedSharedStart));
                }
                if (mergedSharedEnd >= 0 && mergedSharedEnd + 1 < mergedY.size()) {
                    lowered |= lowerToward(mergedY, mergedSharedEnd, 1, mergedY.get(mergedSharedEnd));
                }
                if (!lowered || !validMergedSequence(merged, mergedY)) continue;
                LOGGER.info("RoadWeaver aligned parallel corridor join grades primary={} secondary={} "
                                + "secondaryRun={}..{} primaryRun={}..{} segments={}",
                        RoadShardStorage.computeFingerprint(primary),
                        RoadShardStorage.computeFingerprint(secondary), from, to, pFrom, pTo, merged.size());
            }

            List<RoadSpan> spans = new ArrayList<>(filterSpansByRetainedSegments(secondary.spans(), merged));
            addClippedOwnerSpans(spans, secondary, from, to, merged);
            int sharedStart = mergedSharedStart;
            int sharedEnd = mergedSharedEnd;
            addClippedOwnerSpans(spans, primary, pFrom, pTo, merged);
            addNetworkReuse(spans, merged, sharedStart, sharedEnd);
            addJunctionAnchor(spans, merged, from, sharedStart);
            addJunctionAnchor(spans, merged, sharedEnd, Math.min(merged.size() - 1, sharedEnd + 1));
            LOGGER.info("RoadWeaver merged parallel corridor primary={} secondary={} secondaryRun={}..{} primaryRun={}..{} segments={}",
                    RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                    from, to, pFrom, pTo, merged.size());
            return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                    secondary.slabMaterials(), merged, spans, mergedY,
                    secondary.ownerA2dKey(), secondary.ownerB2dKey());
        }
        return null;
    }

    /**
     * 0.8.368 (T3): diagnosis only — records why a parallel-corridor merge was
     * rejected. The candidate has never once been produced on a real save
     * (0.8.366 and 0.8.367 both logged zero {@code merged parallel corridor}
     * lines), and a simplified offline model said eleven of thirteen overlapping
     * pairs should pass, so the blocking check is not where it was assumed to
     * be. One real run with these lines identifies it. Nothing here changes any
     * decision.
     */
    static void logOverlapReject(String reason,
                                         RoadData primary,
                                         RoadData secondary,
                                         int from,
                                         int to,
                                         int primaryFrom,
                                         int primaryTo) {
        if (!LOGGER.isInfoEnabled()) return;
        LOGGER.info("RoadWeaver parallel corridor rejected reason={} primary={} secondary={} "
                        + "secondaryRange={}..{} primaryRange={}..{}",
                reason,
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                from, to, primaryFrom, primaryTo);
    }

    static boolean junctionNear(RoadData primary, RoadData secondary, int[] targets, int secondaryIndex) {
        if (secondaryIndex < 0 || secondaryIndex >= targets.length) return false;
        int primaryIndex = targets[secondaryIndex];
        if (primaryIndex < 0 || primaryIndex >= primary.roadSegmentList().size()) return false;
        BlockPos a = secondary.roadSegmentList().get(secondaryIndex).middlePos();
        BlockPos b = primary.roadSegmentList().get(primaryIndex).middlePos();
        if (a == null || b == null) return false;
        int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        return distance <= RoadConstants.MAX_CENTER_STEP
                && gradeCompatibleAtJunction(primary, primaryIndex, secondary, secondaryIndex, distance);
    }

    /**
     * True when the two runs really are one corridor rather than a crossing.
     *
     * <p>0.8.369 (T2b): two defects made this reject the exact case it exists
     * for. Real runs such as {@code secondary=[13..228] / primary=[1477..1685]}
     * — 216 stations of shared corridor — were thrown away 23 times per save,
     * which is why the braid survived even though the merge candidate was
     * produced.</p>
     *
     * <ol>
     *   <li>The heading was sampled at the single midpoint. A long mountain
     *       corridor bends, so one sample does not describe its direction and
     *       the dot product fell below {@code OVERLAP_MERGE_MIN_DIRECTION_DOT}.
     *       The heading is now sampled at several points and the run is accepted
     *       when a majority of them agree; a genuine crossing still fails,
     *       because its headings are opposed all the way along.</li>
     *   <li>A single out-of-range station vetoed the whole run. That is the same
     *       "one bad station drops everything" mistake already fixed in
     *       {@code longestUnblockedSubRun}; the corridor is allowed a small
     *       fraction of outliers, but only when most stations still sit inside
     *       the merge distance.</li>
     *   <li>The two ranges were compared by <b>index fraction</b>: station
     *       {@code i} of the secondary was paired with the same fraction of the
     *       primary, however different the two spans were. 0.8.370 replaced that
     *       with the real nearest-neighbour mapping ({@code targets}) that the run
     *       was extracted from, because the two are not the same thing: the run
     *       boundaries come from {@code corridorBoundaryNear}, which maps each
     *       secondary station to its spatially closest primary station, while the
     *       fraction formula assumes the stations are evenly spread along both
     *       runs. Measured on a real save, the fraction pairing put 91.9% of a
     *       235-station overlap 12..22 blocks apart while the headings still
     *       agreed — a spatial correspondence error, not a geometry mismatch.</li>
     * </ol>
     */
    static boolean parallelCorridor(RoadData secondaryRoad, int from, int to,
                                            RoadData primaryRoad, int pFrom, int pTo,
                                            int[] targets) {
        List<RoadSegmentPlacement> secondary = secondaryRoad.roadSegmentList();
        List<RoadSegmentPlacement> primary = primaryRoad.roadSegmentList();
        int secondaryCount = to - from + 1;
        int primaryCount = pTo - pFrom + 1;
        int count = Math.min(secondaryCount, primaryCount);
        if (count < OVERLAP_MERGE_MIN_RUN) return false;
        int tolerance = Math.max(0, (int) Math.floor(count * PARALLEL_CORRIDOR_OUTLIER_FRACTION));
        int stride = Math.max(1, count / PARALLEL_CORRIDOR_HEADING_SAMPLES);
        // 0.8.370：改走 targets（真实最近邻映射）。targets[secondaryIndex] 是
        // markSnapTargets 为每个 secondary 站点找出的空间最近 primary 站点，
        // 也是 extractRuns 划出本 run 的依据；用等比例公式配对照样是这两条路，
        // 但会把不同物理位置的站点比在一起（真机实测 91.9% 的点相距 12~22 格）。
        int outliers = 0;
        int checked = 0;
        int parallelVotes = 0;
        int votes = 0;
        double worstDot = 1.0D;
        for (int si = from; si <= to; si++) {
            int pi = mappedTarget(targets, si, pFrom, pTo);
            if (pi < 0) continue;
            RoadSegmentPlacement a = secondary.get(si);
            RoadSegmentPlacement b = primary.get(pi);
            if (a == null || b == null || a.middlePos() == null || b.middlePos() == null) return false;
            checked++;
            int distance = Math.max(Math.abs(a.middlePos().getX() - b.middlePos().getX()),
                    Math.abs(a.middlePos().getZ() - b.middlePos().getZ()));
            if (distance > OVERLAP_MERGE_MAX_DISTANCE
                    || !gradeCompatibleAtJunction(primaryRoad, pi, secondaryRoad, si, distance)) {
                outliers++;
                if (outliers > tolerance) {
                    LOGGER.info("RoadWeaver parallel corridor rejected reason=not-parallel-distance "
                                    + "primary={} secondary={} secondaryRange={}..{} primaryRange={}..{} "
                                    + "outliers={} tolerance={} checked={} sampleDistance={}",
                            RoadShardStorage.computeFingerprint(primaryRoad),
                            RoadShardStorage.computeFingerprint(secondaryRoad),
                            from, to, pFrom, pTo, outliers, tolerance, checked, distance);
                    return false;
                }
            }
            // 方向投票：按站间隔取样，用同一套 targets 映射。
            if ((si - from) % stride == 0) {
                double[] aDir = segmentDirection(secondary, si);
                double[] bDir = segmentDirection(primary, pi);
                if (aDir != null && bDir != null) {
                    votes++;
                    double dot = Math.abs(aDir[0] * bDir[0] + aDir[1] * bDir[1]);
                    worstDot = Math.min(worstDot, dot);
                    double minDot = ConfigService.get().pathfindingCost().braidMinDirectionDot();
                    if (dot >= minDot) parallelVotes++;
                }
            }
        }
        if (checked == 0) return false;
        boolean accepted = votes == 0 || parallelVotes * 2 >= votes;
        // 0.8.409 麻花危险区：方向接近平行（点积 ≥ minDot）却未通过平行合并判定时，
        // 若禁麻花且不允许并排重叠，则记录但暂不改判定语义（新逻辑默认关闭，待真机验证）。
        // 这里仅保留 worstDot 供日志诊断；braidAllowParallelOverlap 的强制拒绝在后续阶段接入。
        if (!accepted) {
            LOGGER.info("RoadWeaver parallel corridor rejected reason=not-parallel-samples "
                            + "primary={} secondary={} secondaryRange={}..{} primaryRange={}..{} "
                            + "votes={}/{} worstDot={} checked={}",
                    RoadShardStorage.computeFingerprint(primaryRoad),
                    RoadShardStorage.computeFingerprint(secondaryRoad),
                    from, to, pFrom, pTo,
                    parallelVotes, votes, String.format("%.3f", worstDot), checked);
        }
        return accepted;
    }

    /**
     * 0.8.370：取 secondary 站点在 primary 上的映射索引。
     * 优先用 markSnapTargets 的真实最近邻结果；缺失时退化为所在 run 内的等比例位置，
     * 保证边界站仍有定义（run 的两端来自 corridorBoundaryNear，本身就是合法的映射）。
     */
    static int mappedTarget(int[] targets, int secondaryIndex, int pFrom, int pTo) {
        if (targets != null && secondaryIndex >= 0 && secondaryIndex < targets.length) {
            int mapped = targets[secondaryIndex];
            if (mapped >= 0) return mapped;
        }
        return -1;
    }

    /**
     * 0.8.409 麻花检测：两条路在某个重叠 run 内方向「近平行」（点积落在危险区）却无法
     * 被 {@link #parallelCorridor} 合并时，它们的并排重叠就是麻花。丁字/十字（方向接近
     * 正交，点积低）不算麻花。
     *
     * <p>判定：对每个 run 的 sampled 站，算 secondary 与 primary 的方向点积，取最小点积
     * {@code worstDot}。当 {@code worstDot} 落在 [braidMinDirectionDot, braidNearParallelDot]
     * 区间（近平行危险区）时，该 run 是麻花。点积 < minDot（正交）或 > nearParallelDot
     * （明确平行、parallelCorridor 会合并）都不算麻花。</p>
     */
    static boolean detectsBraid(RoadData primary, RoadData secondary,
                                        int[] targets, List<int[]> runs) {
        if (primary == null || secondary == null || targets == null || runs == null || runs.isEmpty()) {
            return false;
        }
        PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
        double minDot = cfg.braidMinDirectionDot();
        double nearParallelDot = cfg.braidNearParallelDot();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        if (sec == null || pri == null) return false;
        for (int[] run : runs) {
            if (run == null || run.length < 2) continue;
            int from = Math.max(0, run[0]);
            int to = Math.min(sec.size() - 1, run[1]);
            if (to - from + 1 < OVERLAP_MERGE_MIN_RUN) continue;
            int stride = Math.max(1, (to - from + 1) / PARALLEL_CORRIDOR_HEADING_SAMPLES);
            int samples = 0;
            double worstDot = 1.0D;
            for (int si = from; si <= to; si++) {
                int pi = mappedTarget(targets, si, -1, -1);
                if (pi < 0) continue;
                if ((si - from) % stride != 0) continue;
                double[] aDir = segmentDirection(sec, si);
                double[] bDir = segmentDirection(pri, pi);
                if (aDir == null || bDir == null) continue;
                samples++;
                double dot = Math.abs(aDir[0] * bDir[0] + aDir[1] * bDir[1]);
                worstDot = Math.min(worstDot, dot);
            }
            if (samples == 0) continue;
            // 近平行危险区：方向点积高到不该是丁字，但平行合并没有成功。
            if (worstDot >= minDot && worstDot <= nearParallelDot) {
                return true;
            }
        }
        return false;
    }

    static boolean rangeHasSpecial(boolean[] mask, int from, int to) {
        if (mask == null) return false;
        for (int i = Math.max(0, from); i <= Math.min(mask.length - 1, to); i++) if (mask[i]) return true;
        return false;
    }

    /**
     * 0.8.366 (M1): the longest sub-range of {@code [from..to]} that is free of
     * engineered spans on <b>both</b> roads and whose primary mapping stays
     * monotonic.
     *
     * <p>{@code appendRange(secondary, 0, from + 1)} / {@code appendRange(primary,
     * pFrom, pTo + 1)} concatenate the two corridors in index order, so the
     * chosen sub-range must keep {@code targets} monotonic; a run that folds
     * back on itself would produce a zig-zag road. {@code increasing} is decided
     * once for the whole run and reused here.</p>
     *
     * <p>Returns {@code null} when nothing usable remains, which preserves the
     * caller's original "skip this run" behaviour. Scanned as a sliding window
     * so the result is the <em>longest</em> clean stretch, not merely the
     * first one.</p>
     */
    static int[] longestUnblockedSubRun(int from, int to, int[] targets,
                                                boolean[] priBlocked, boolean[] secBlocked) {
        int bestStart = -1;
        int bestEnd = -1;
        int start = -1;
        int previousTarget = -1;
        /** +1 ascending, -1 descending, 0 not yet decided within this window. */
        int direction = 0;
        for (int i = from; i <= to; i++) {
            boolean blocked = (secBlocked != null && i < secBlocked.length && secBlocked[i]);
            int target = (targets != null && i < targets.length) ? targets[i] : -1;
            if (target < 0) blocked = true;
            if (!blocked && priBlocked != null && target < priBlocked.length && priBlocked[target]) blocked = true;
            if (!blocked && start >= 0 && previousTarget >= 0 && target != previousTarget) {
                int step = target > previousTarget ? 1 : -1;
                if (direction == 0) {
                    direction = step;
                } else if (step != direction) {
                    // A fold-back would make the concatenated corridor zig-zag.
                    blocked = true;
                }
            }
            if (blocked) {
                if (start >= 0 && i - 1 - start > bestEnd - bestStart) {
                    bestStart = start;
                    bestEnd = i - 1;
                }
                start = -1;
                previousTarget = -1;
                direction = 0;
                continue;
            }
            if (start < 0) start = i;
            previousTarget = target;
        }
        if (start >= 0 && to - start > bestEnd - bestStart) {
            bestStart = start;
            bestEnd = to;
        }
        if (bestStart < 0 || bestEnd - bestStart + 1 < OVERLAP_MERGE_MIN_RUN) return null;
        return new int[]{bestStart, bestEnd};
    }

    static void appendRange(RoadData road, int from, int to,
                                    List<RoadSegmentPlacement> segments, List<Integer> targetY) {
        int start = Math.max(0, from);
        int end = Math.min(road.roadSegmentList().size(), to);
        for (int i = start; i < end; i++) {
            segments.add(road.roadSegmentList().get(i));
            targetY.add(targetAt(road, i));
        }
    }

    static void appendDirected(RoadData road, int from, int to,
                                       List<RoadSegmentPlacement> segments, List<Integer> targetY) {
        int step = from <= to ? 1 : -1;
        for (int i = from; ; i += step) {
            segments.add(road.roadSegmentList().get(i));
            targetY.add(targetAt(road, i));
            if (i == to) break;
        }
    }

    static void removeConsecutiveDuplicateCenters(List<RoadSegmentPlacement> segments,
                                                          List<Integer> targetY) {
        for (int i = segments.size() - 1; i > 0; i--) {
            BlockPos previous = segments.get(i - 1).middlePos();
            BlockPos current = segments.get(i).middlePos();
            if (previous != null && current != null
                    && previous.getX() == current.getX() && previous.getZ() == current.getZ()) {
                segments.remove(i);
                targetY.remove(i);
            }
        }
    }

    static boolean validMergedSequence(List<RoadSegmentPlacement> segments, List<Integer> targetY) {
        if (segments.size() < MIN_RUN || segments.size() != targetY.size()) return false;
        repairLocalHeightJoins(targetY);
        int heightStepExceptions = 0;
        for (int i = 1; i < segments.size(); i++) {
            BlockPos a = segments.get(i - 1).middlePos();
            BlockPos b = segments.get(i).middlePos();
            if (a == null || b == null || (a.getX() == b.getX() && a.getZ() == b.getZ())
                    || Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())) > RoadConstants.MAX_CENTER_STEP) return false;
            if (Math.abs(targetY.get(i) - targetY.get(i - 1)) > RoadConstants.MAX_HEIGHT_STEP
                    && ++heightStepExceptions > RoadConstants.MAX_HEIGHT_STEP_EXCEPTIONS) return false;
        }
        return true;
    }

    static boolean corridorBoundaryNear(RoadData primary, RoadData secondary,
                                                int[] targets, int secondaryIndex) {
        if (secondaryIndex < 0 || secondaryIndex >= targets.length) return false;
        int primaryIndex = targets[secondaryIndex];
        if (primaryIndex < 0 || primaryIndex >= primary.roadSegmentList().size()) return false;
        BlockPos a = secondary.roadSegmentList().get(secondaryIndex).middlePos();
        BlockPos b = primary.roadSegmentList().get(primaryIndex).middlePos();
        if (a == null || b == null) return false;
        int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        return distance <= MAX_APPROACH_CONNECTOR_DISTANCE
                && gradeCompatibleAtJunction(primary, primaryIndex, secondary, secondaryIndex, distance);
    }

    /**
     * A junction can inherit a one-sample two-block discontinuity after the
     * anchor and secondary profile are concatenated. Clamp only that local
     * join before rejecting the whole candidate; larger terrain jumps remain
     * the responsibility of the grade-repair planner.
     */
    static void repairLocalHeightJoins(List<Integer> targetY) {
        if (targetY == null || targetY.size() < 2) return;
        for (int i = 1; i < targetY.size(); i++) {
            Integer previous = targetY.get(i - 1);
            Integer current = targetY.get(i);
            if (previous == null || current == null) continue;
            int delta = current - previous;
            if (Math.abs(delta) == RoadConstants.MAX_HEIGHT_STEP + 1) {
                targetY.set(i, previous + Integer.signum(delta));
            }
        }
    }

    /**
     * 0.8.366 (M3): lowers stations away from {@code anchorIndex} in
     * {@code step} direction onto a ramp that meets {@code seatY} at the anchor.
     * Station {@code anchorIndex ± d} may sit at most {@code seatY + d}, so the
     * resulting profile descends at one block per station and every step stays
     * within {@link #RoadConstants.MAX_HEIGHT_STEP}.
     *
     * <p>It only ever <b>lowers</b> a station (prefer-cut), so a road is never
     * raised into terrain, and it stops at the first absent height. Returns
     * whether anything changed; the caller re-validates and discards the merge
     * when the result still fails.</p>
     */
    static boolean lowerToward(List<Integer> targetY, int anchorIndex, int step, int seatY) {
        if (targetY == null || step == 0) return false;
        boolean changed = false;
        for (int distance = 1, index = anchorIndex + step;
             index >= 0 && index < targetY.size();
             distance++, index += step) {
            Integer current = targetY.get(index);
            if (current == null) break;
            int ceiling = seatY + distance;
            if (current > ceiling) {
                targetY.set(index, ceiling);
                changed = true;
            }
        }
        return changed;
    }

    static void addJunctionAnchor(List<RoadSpan> spans, List<RoadSegmentPlacement> segments, int a, int b) {
        if (a < 0 || b < 0 || a >= segments.size() || b >= segments.size()) return;
        BlockPos first = segments.get(a).middlePos();
        BlockPos second = segments.get(b).middlePos();
        if (first != null && second != null) spans.add(new RoadSpan(first, second, SpanType.JUNCTION_ANCHOR));
    }

    static void addNetworkReuse(List<RoadSpan> spans, List<RoadSegmentPlacement> segments, int a, int b) {
        if (a < 0 || b < 0 || a >= segments.size() || b >= segments.size() || a == b) return;
        BlockPos first = segments.get(a).middlePos();
        BlockPos second = segments.get(b).middlePos();
        if (first != null && second != null) spans.add(new RoadSpan(first, second, SpanType.NETWORK_REUSE));
    }

    /**
     * Marks spans whose physical construction cannot be replaced by an
     * ordinary donor corridor.  A protected building gap is deliberately not
     * blocked here: both routes may have been detoured around the same
     * building, and retaining that gap is safe as long as the final structure
     * validator accepts the merged road.  NETWORK_REUSE is also not a hard
     * barrier; it is evidence that this corridor already has a reusable owner.
     */
    static boolean[] mergeBlockedSpanMask(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] blocked = new boolean[segments.size()];
        if (road.spans() == null || road.spans().isEmpty()) return blocked;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null
                    || (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING)) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) blocked[i] = true;
        }
        return blocked;
    }
}
