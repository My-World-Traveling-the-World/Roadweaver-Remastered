/* 文件职责：端点走廊吸收与端点连接 —— 覆盖「副线整条落在主线走廊里」这类情形。 */
package net.shiroha233.roadweaver.postprocess;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;

// 仍留在其它同包类里的常量、类型与工具。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionTrimOps.*;

/**
 * 端点走廊吸收 / 端点连接。
 *
 * <h2>它处理什么</h2>
 * <p>前两类接法都是「副线伸过去接主线」。还有一种情形：<b>副线整条就落在</b>
 * <b>主线已有的走廊里</b> —— 这时不该再铺一条，而该把它「吸收」进主线
 * （{@code tryAbsorbEndpointCorridor}）。</p>
 *
 * <h2>吸收前的三道兼容性检查</h2>
 * <ul>
 *   <li>{@code endpointCorridorBoundaryCompatible} —— 端点边界能否对上；</li>
 *   <li>{@code monotonicTargetRun} —— 目标高度是否单调（中间不能凸起）；</li>
 *   <li>{@code corridorCoverageCompatible} —— 覆盖范围是否真的相容。</li>
 * </ul>
 * <p>三道全过才吸收。任何一道不过就走普通连接 —— <b>宁可多铺一条，也不吸收错</b>，
 * 因为吸收错的后果是两条路被合成一条，玩家看到路「消失」了一段。</p>
 *
 * <p>本类是从 1832 行的 {@code RoadSnapService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class EndpointConnectOps {
    public static RoadData tryAbsorbEndpointCorridor(RoadData primary,
                                                      RoadData secondary,
                                                      int[] targets,
                                                      List<int[]> runs) {
        if (primary == null || secondary == null || targets == null || runs == null
                || secondary.roadSegmentList().size() < OVERLAP_MERGE_MIN_RUN) return null;
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        int[] corridor = null;
        for (int[] run : runs) {
            if (run != null && run.length >= 2
                    && run[0] <= ENDPOINT_RUN_MAX_OFFSET
                    && run[1] >= sec.size() - 1 - ENDPOINT_RUN_MAX_OFFSET) {
                corridor = run;
                break;
            }
        }
        if (corridor == null) return null;

        int secFrom = Math.max(0, corridor[0]);
        int secTo = Math.min(sec.size() - 1, corridor[1]);
        int primaryFrom = targets[secFrom];
        int primaryTo = targets[secTo];
        if (primaryFrom < 0 || primaryTo < 0 || primaryFrom == primaryTo
                || !monotonicTargetRun(targets, secFrom, secTo, Integer.signum(primaryTo - primaryFrom))) {
            return null;
        }

        boolean[] primaryBlocked = junctionBlockedSpanMask(primary);
        boolean[] secondaryBlocked = junctionBlockedSpanMask(secondary);
        if (!endpointCorridorBoundaryCompatible(
                primary, primaryFrom, primaryBlocked, secondary, secFrom, secondaryBlocked)
                || !endpointCorridorBoundaryCompatible(
                primary, primaryTo, primaryBlocked, secondary, secTo, secondaryBlocked)) return null;

        int primaryCount = Math.abs(primaryTo - primaryFrom) + 1;
        int secondaryCount = secTo - secFrom + 1;
        if (primaryCount < OVERLAP_MERGE_MIN_RUN
                || primaryCount > secondaryCount * 2 + SNAP_THRESHOLD * 2
                || !corridorCoverageCompatible(primary, primaryFrom, primaryTo, secondary)) return null;

        List<RoadSegmentPlacement> merged = new ArrayList<>();
        List<Integer> mergedY = new ArrayList<>();
        appendRange(secondary, 0, secFrom + 1, merged, mergedY);

        boolean startExact = sameCenter(
                sec.get(secFrom).middlePos(), pri.get(primaryFrom).middlePos());
        if (startExact) {
            merged.removeLast();
            mergedY.removeLast();
        } else if (!appendApproachConnector(merged, mergedY, secondary.width(),
                pri.get(primaryFrom), targetAt(primary, primaryFrom))) {
            return null;
        }

        int reuseStart = merged.size();
        appendDirected(primary, primaryFrom, primaryTo, merged, mergedY);
        int reuseEnd = merged.size() - 1;

        boolean endExact = sameCenter(
                pri.get(primaryTo).middlePos(), sec.get(secTo).middlePos());
        if (endExact) {
            appendRange(secondary, secTo + 1, sec.size(), merged, mergedY);
        } else {
            if (!appendApproachConnector(merged, mergedY, secondary.width(),
                    sec.get(secTo), targetAt(secondary, secTo))) return null;
            appendRange(secondary, secTo, sec.size(), merged, mergedY);
        }
        removeConsecutiveDuplicateCenters(merged, mergedY);
        if (!validMergedSequence(merged, mergedY)) return null;

        List<RoadSpan> spans = new ArrayList<>(
                filterSecondarySpansOutsideRange(secondary, secFrom, secTo, merged));
        addClippedOwnerSpans(spans, secondary, secFrom, secTo, merged);
        int pMin = Math.min(primaryFrom, primaryTo);
        int pMax = Math.max(primaryFrom, primaryTo);
        addClippedOwnerSpans(spans, primary, pMin, pMax, merged);
        addNetworkReuse(spans, merged, reuseStart, reuseEnd);
        if (!startExact && reuseStart > 0) {
            addJunctionMarker(spans, merged, reuseStart - 1, reuseStart, primary, primaryFrom);
        }
        if (!endExact && reuseEnd + 1 < merged.size()) {
            addJunctionMarker(spans, merged, reuseEnd, reuseEnd + 1, primary, primaryTo);
        }
        return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                secondary.slabMaterials(), merged, spans, mergedY,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());
    }

    public static boolean endpointCorridorBoundaryCompatible(RoadData primary,
                                                               int primaryIndex,
                                                               boolean[] primaryBlocked,
                                                               RoadData secondary,
                                                               int secondaryIndex,
                                                               boolean[] secondaryBlocked) {
        BlockPos a = primary.roadSegmentList().get(primaryIndex).middlePos();
        BlockPos b = secondary.roadSegmentList().get(secondaryIndex).middlePos();
        if (a == null || b == null) return false;
        int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        int height = Math.abs(targetAt(primary, primaryIndex) - targetAt(secondary, secondaryIndex));
        if (distance > MAX_APPROACH_CONNECTOR_DISTANCE
                || !gradeCompatibleAtJunction(primary, primaryIndex, secondary, secondaryIndex, distance)) {
            return false;
        }
        if (distance == 0) {
            return isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex)
                    && height <= MAX_TUNNEL_JUNCTION_GRADE_DELTA;
        }
        // The connector between two road ends may rise at most one block per two blocks of run, which is the
        // road's own grade limit. It used to allow one block per block - a 45-degree connector - and with a
        // snap radius of twelve that accepted junctions twelve blocks apart in height, so two roads could
        // meet in a cliff and neither side looked wrong on its own.
        return ((height <= Math.max(1, distance / 2))
                || isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex))
                && !isSpecial(primaryBlocked, primaryIndex)
                && !isSpecial(secondaryBlocked, secondaryIndex);
    }

    public static boolean monotonicTargetRun(int[] targets, int from, int to, int step) {
        if (step == 0) return false;
        int previous = targets[from];
        if (previous < 0) return false;
        for (int i = from + 1; i <= to; i++) {
            int current = targets[i];
            if (current < 0 || (current != previous && Integer.signum(current - previous) != step)) return false;
            previous = current;
        }
        return true;
    }

    public static boolean corridorCoverageCompatible(RoadData primary,
                                                      int primaryFrom,
                                                      int primaryTo,
                                                      RoadData secondary) {
        SegmentGrid secondaryGrid = buildGrid(secondary, null);
        int step = primaryFrom <= primaryTo ? 1 : -1;
        int total = 0;
        int near = 0;
        for (int index = primaryFrom; ; index += step) {
            RoadSegmentPlacement segment = primary.roadSegmentList().get(index);
            NearestResult nearest = segment == null || segment.middlePos() == null
                    ? null : secondaryGrid.findNearest(segment.middlePos());
            if (nearest == null || nearest.dist2() > SPLIT_DIST2) return false;
            if (nearest.dist2() <= SNAP_DIST2) near++;
            total++;
            if (index == primaryTo) break;
        }
        return total > 0 && near * 10 >= total * 9;
    }

    public static boolean sameCenter(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    public static List<RoadSpan> filterSecondarySpansOutsideRange(RoadData secondary,
                                                                    int replacedFrom,
                                                                    int replacedTo,
                                                                    List<RoadSegmentPlacement> retained) {
        if (secondary.spans() == null || secondary.spans().isEmpty()) return List.of();
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < secondary.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = secondary.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        Set<Long> retainedKeys = new HashSet<>();
        for (RoadSegmentPlacement segment : retained) {
            if (segment != null && segment.middlePos() != null) retainedKeys.add(xzKey(segment.middlePos()));
        }
        List<RoadSpan> spans = new ArrayList<>();
        for (RoadSpan span : secondary.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (to >= replacedFrom && from <= replacedTo) continue;
            if (retainedKeys.contains(xzKey(span.start())) && retainedKeys.contains(xzKey(span.end()))) {
                spans.add(span);
            }
        }
        return spans;
    }

    public static RoadData tryConnectEndpoint(RoadData primary,
                                               boolean[] primarySpecial,
                                               RoadData secondary,
                                               boolean[] secondarySpecial,
                                               int[] snapTarget,
                                               List<int[]> runs,
                                               boolean sharedAtStart,
                                               long sharedOwner,
                                               Boolean knownIncreasingTargets) {
        List<RoadSegmentPlacement> secSegs = secondary.roadSegmentList();
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        int[] endpointRun = selectEndpointRun(runs, secSegs.size(), sharedAtStart);
        Boolean increasingTargets = knownIncreasingTargets;
        JunctionCandidate junction = null;
        if (endpointRun != null) {
            increasingTargets = increasingTargets != null
                    ? increasingTargets : targetIndicesIncrease(snapTarget, endpointRun);
            if (increasingTargets != null) {
                endpointRun = stableEndpointRun(snapTarget, endpointRun, increasingTargets, sharedAtStart);
                if (endpointRun != null) {
                    junction = findSafeJunction(
                            primary, primarySpecial, secondary, secondarySpecial,
                            snapTarget, endpointRun, sharedAtStart);
                }
            }
        }
        if (junction == null && sharedOwner != RoadData.NO_OWNER_2D) {
            junction = findSharedEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
            if (junction == null) {
                junction = findSurfaceTunnelEndpointApproach(
                        primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
            }
            if (increasingTargets == null) {
                increasingTargets = sharedAtStart == (primary.ownerA2dKey() == sharedOwner
                        && primary.ownerB2dKey() != sharedOwner);
            }
        }
        if (junction == null) {
            junction = findSurfaceTunnelEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
        }
        if (junction == null) {
            junction = findHeightTransitionEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
        }
        if (junction != null && increasingTargets == null) {
            // A spatial endpoint join has no snap-target monotonicity array.
            // Anchor construction tries both directions, so a neutral default
            // is sufficient and prevents a valid height-normalized junction
            // from being rejected only because it was discovered spatially.
            increasingTargets = Boolean.TRUE;
        }
        if (junction == null || increasingTargets == null) {
            return null;
        }

        logJunctionConnection(primary, secondary, junction, sharedAtStart);

        boolean tunnelGradeJoin = isTunnelJunctionPair(
                primary, junction.primaryIndex(), secondary, junction.secondaryIndex());
        boolean oneSidedTunnelJoin = !tunnelGradeJoin
                && isTunnelGradeTransitionPair(primary, junction.primaryIndex(), secondary,
                junction.secondaryIndex());
        boolean heightTransitionJoin = !tunnelGradeJoin && !oneSidedTunnelJoin;
        boolean gradeJoin = tunnelGradeJoin || oneSidedTunnelJoin || heightTransitionJoin;
        int tunnelGradeLimit = gradeJoin
                ? tunnelGradeLimit(primary, junction.primaryIndex(), secondary, junction.secondaryIndex())
                : MAX_TUNNEL_JUNCTION_GRADE_DELTA;
        int anchorSegmentCount = gradeJoin
                ? requiredTunnelAnchorSegments(
                targetAt(primary, junction.primaryIndex()), targetAt(secondary, junction.secondaryIndex()))
                : JUNCTION_ANCHOR_SEGMENTS;
        AnchorPlan anchor = buildAnchorPlan(
                priSegs, primary.targetY(), primarySpecial,
                junction.primaryIndex(), increasingTargets, sharedAtStart, anchorSegmentCount);
        if (anchor == null) return null;

        List<RoadSegmentPlacement> connectedSegments = new ArrayList<>();
        List<Integer> connectedY = new ArrayList<>();
        int retainedFrom;
        int retainedTo;
        int anchorFrom;
        int anchorTo;

        if (sharedAtStart) {
            connectedSegments.addAll(anchor.segments());
            connectedY.addAll(anchor.targetY());
            retainedFrom = junction.secondaryIndex() + 1;
            retainedTo = secSegs.size();
            if (!appendApproachConnector(connectedSegments, connectedY,
                    secondary.width(), secSegs.get(retainedFrom), targetAt(secondary, retainedFrom),
                    gradeJoin,
                    tunnelGradeLimit)) {
                return null;
            }
            appendSecondaryRange(secondary, retainedFrom, retainedTo, connectedSegments, connectedY);
            anchorFrom = 0;
            anchorTo = anchor.segments().size() - 1;
        } else {
            retainedFrom = 0;
            retainedTo = junction.secondaryIndex();
            appendSecondaryRange(secondary, retainedFrom, retainedTo, connectedSegments, connectedY);
            if (connectedSegments.isEmpty()) return null;
            int connectorAnchorFrom = connectedSegments.size();
            if (!appendApproachConnector(connectedSegments, connectedY,
                    secondary.width(), anchor.segments().getFirst(), anchor.targetY().getFirst(),
                    gradeJoin,
                    tunnelGradeLimit)) {
                return null;
            }
            anchorFrom = connectedSegments.size();
            connectedSegments.addAll(anchor.segments());
            connectedY.addAll(anchor.targetY());
            anchorTo = connectedSegments.size() - 1;
        }

        if (tunnelGradeJoin) {
            // Pick the lower tunnel before the generic anchor alignment. The
            // generic routine is allowed to raise a normal road, but it must
            // never raise the low side of a tunnel junction.
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    anchor.segments().size());
        } else if (oneSidedTunnelJoin) {
            boolean primaryTunnel = isTunnelAt(primary, junction.primaryIndex());
            boolean secondaryTunnel = isTunnelAt(secondary, junction.secondaryIndex());
            if (!primaryTunnel && secondaryTunnel) {
                // Keep the ordinary donor corridor at its surface height and
                // raise the tunnel branch into a portal transition.
                alignSecondaryGradeToAnchor(connectedY, anchorFrom, anchorTo, sharedAtStart);
            } else {
                // A tunnel donor remains authoritative; the existing owner
                // lowering pass will propagate this lower junction grade.
                alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                        anchor.segments().size());
            }
        } else if (heightTransitionJoin) {
            // Ordinary roads with a large elevation mismatch use the same
            // bounded lower-grade transition, but remain a normal junction.
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    anchor.segments().size());
        }
        // Tunnel junction grading is handled by the lower-grade routine below.
        // The generic secondary alignment may raise the low branch while it
        // tries to meet the old high anchor, which defeats the junction rule.
        if (!gradeJoin) {
            alignSecondaryGradeToAnchor(connectedY, anchorFrom, anchorTo, sharedAtStart);
        }
        if (!gradeJoin) {
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    JUNCTION_ANCHOR_SEGMENTS);
        }

        if (connectedSegments.size() < MIN_RUN
                || !validateJunctionGeometry(connectedSegments, connectedY, anchorFrom, anchorTo, sharedAtStart)) {
            return null;
        }

        List<RoadSpan> connectedSpans = new ArrayList<>(
                filterSpansByRetainedSegments(secondary.spans(), connectedSegments));
        addNetworkReuse(connectedSpans, connectedSegments, anchorFrom, anchorTo);
        int boundaryA = sharedAtStart ? anchorTo : Math.max(0, anchorFrom - 1);
        int boundaryB = sharedAtStart ? Math.min(connectedSegments.size() - 1, anchorTo + 1) : anchorFrom;
        boolean tunnelJunction = tunnelGradeJoin || oneSidedTunnelJoin;
        connectedSpans.add(new RoadSpan(
                connectedSegments.get(boundaryA).middlePos(),
                connectedSegments.get(boundaryB).middlePos(),
                tunnelJunction ? SpanType.TUNNEL_JUNCTION : SpanType.JUNCTION_ANCHOR));

        RoadData connected = new RoadData(
                secondary.width(), secondary.roadType(),
                secondary.materials(), secondary.slabMaterials(),
                connectedSegments, connectedSpans, connectedY,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());

        LOGGER.info("【主次道路直接路口连接结果】 " + "RoadWeaver direct junction primary={} secondary={} mode={} owner={} side={} segments={}->{} cutIndex={} anchorSegments={}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                sharedOwner == RoadData.NO_OWNER_2D ? "spatial" : "shared-owner",
                sharedOwner == RoadData.NO_OWNER_2D ? "none" : Long.toString(sharedOwner),
                sharedAtStart ? "start" : "end",
                secSegs.size(), connectedSegments.size(),
                junction.secondaryIndex(), anchor.segments().size());
        return connected;
    }

    public static int[] selectEndpointRun(List<int[]> runs, int size, boolean sharedAtStart) {
        if (runs == null || runs.isEmpty() || size <= 0) return null;
        if (sharedAtStart) {
            for (int[] run : runs) {
                if (run != null && run.length >= 2 && run[0] <= ENDPOINT_RUN_MAX_OFFSET) return run;
            }
            return null;
        }
        for (int i = runs.size() - 1; i >= 0; i--) {
            int[] run = runs.get(i);
            if (run != null && run.length >= 2 && run[1] >= size - 1 - ENDPOINT_RUN_MAX_OFFSET) return run;
        }
        return null;
    }

    public static int[] stableEndpointRun(int[] targets,
                                           int[] run,
                                           boolean increasing,
                                           boolean sharedAtStart) {
        if (targets == null || run == null || run.length < 2) return null;
        int index = sharedAtStart ? run[0] : run[1];
        int limit = sharedAtStart ? run[1] : run[0];
        int step = sharedAtStart ? 1 : -1;
        boolean outwardIncreasing = sharedAtStart ? increasing : !increasing;
        int previous = -1;
        int repeated = 0;
        int lastValid = index - step;
        for (; ; index += step) {
            int current = targets[index];
            if (current < 0) break;
            if (previous >= 0) {
                int delta = current - previous;
                if ((outwardIncreasing && delta < 0) || (!outwardIncreasing && delta > 0)
                        || Math.abs(delta) > MAX_TARGET_INDEX_STEP) break;
                if (delta == 0) {
                    repeated++;
                    if (repeated > MAX_REPEATED_TARGETS) break;
                } else {
                    repeated = 0;
                }
            }
            previous = current;
            lastValid = index;
            if (index == limit) break;
        }
        int start = Math.min(sharedAtStart ? run[0] : lastValid, sharedAtStart ? lastValid : run[1]);
        int end = Math.max(sharedAtStart ? run[0] : lastValid, sharedAtStart ? lastValid : run[1]);
        return end - start + 1 >= MIN_RUN ? new int[]{start, end} : null;
    }

    public static int[] markSnapTargets(RoadData primaryRoad,
                                         RoadData secondaryRoad,
                                         List<RoadSegmentPlacement> secondarySegments,
                                         List<RoadSegmentPlacement> primarySegments,
                                         SegmentGrid primaryGrid,
                                         boolean[] secondarySpecial) {
        int n = secondarySegments.size();
        int[] snapTarget = new int[n];
        Arrays.fill(snapTarget, -1);
        boolean snapping = false;

        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement secondary = secondarySegments.get(i);
            if (secondary == null || secondary.middlePos() == null) {
                snapping = false;
                continue;
            }
            NearestResult nearest = primaryGrid.findNearest(secondary.middlePos());
            if (nearest == null) {
                snapping = false;
                continue;
            }

            if (!snapping) {
                boolean endpointTunnelJunction = (i == 0 || i == n - 1)
                        && isTunnelJunctionPair(primaryRoad, nearest.index(), secondaryRoad, i);
                if (nearest.dist2() <= SNAP_DIST2
                        && (directionCompatible(secondarySegments, i, primarySegments, nearest.index())
                        || endpointTunnelJunction)) {
                    snapping = true;
                    snapTarget[i] = nearest.index();
                }
            } else if (nearest.dist2() > SPLIT_DIST2) {
                snapping = false;
            } else {
                snapTarget[i] = nearest.index();
            }
        }
        return snapTarget;
    }
}
