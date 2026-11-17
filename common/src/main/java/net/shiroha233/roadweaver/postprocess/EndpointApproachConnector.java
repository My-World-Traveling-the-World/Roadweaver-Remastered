/* 文件职责：找端点附近的「可接入段」并把副线接上去 —— 只做几何连接。 */
package net.shiroha233.roadweaver.postprocess;

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

// 仍留在 RoadSnapService 与 NetworkReuseMerger 里的常量、类型与工具。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;

/**
 * 端点接入：找可接入段 + 生成连接器。
 *
 * <h2>它解决什么</h2>
 * <p>副线的端点不能就这么悬在空中 —— 必须接到主线的某个合适位置上。
 * 「合适」有三类：<b>共享端点</b>、<b>地面/隧道过渡段</b>、<b>高差过渡段</b>。
 * 这三类的判据完全不同，所以拆成三个 {@code find*Approach}，而不是一个带分支的大函数。</p>
 *
 * <h2>为什么连接器要单独算 footprint</h2>
 * <p>连接器不是一条线，是一段有宽度的路面。两条路能不能真的接上，要看
 * <b>两个 footprint 有没有重叠列</b>（{@code footprintsConnect}）——
 * 中心线相交但宽度方向错开一格，游戏里就是断的。</p>
 *
 * <h2>锚点计划（AnchorPlan）</h2>
 * <p>接入点的纵断面要跟主线对齐，但<b>不能对齐到隧道的深挖底</b>。
 * 所以先算一份 AnchorPlan（每站的目标高度），再由
 * {@code validateJunctionGeometry} 校验这条路口的几何是否可用。</p>
 *
 * <p>本类是从 2886 行的 {@code RoadSnapService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
final class EndpointApproachConnector {
    static JunctionCandidate findSharedEndpointApproach(RoadData primary,
                                                                 boolean[] primarySpecial,
                                                                 RoadData secondary,
                                                                 boolean[] secondarySpecial,
                                                                 boolean sharedAtStart) {
        List<RoadSegmentPlacement> secondarySegments = secondary.roadSegmentList();
        List<RoadSegmentPlacement> primarySegments = primary.roadSegmentList();
        int maxOffset = Math.min(ENDPOINT_RUN_MAX_OFFSET, secondarySegments.size() - 2);
        JunctionCandidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int offset = 0; offset <= maxOffset; offset++) {
            int secondaryIndex = sharedAtStart ? offset : secondarySegments.size() - 1 - offset;
            int adjacentIndex = sharedAtStart ? secondaryIndex + 1 : secondaryIndex - 1;
            if (isSpecial(secondarySpecial, secondaryIndex)
                    || isSpecial(secondarySpecial, adjacentIndex)
                    || (!isSafeJunctionIndex(secondary, adjacentIndex)
                    && !isIndexInSpan(secondary, adjacentIndex, SpanType.TUNNEL))) continue;
            RoadSegmentPlacement adjacent = secondarySegments.get(adjacentIndex);
            if (adjacent == null || adjacent.middlePos() == null) continue;
            for (int primaryIndex = 0; primaryIndex < primarySegments.size(); primaryIndex++) {
                if (isSpecial(primarySpecial, primaryIndex)
                        || (!isSafeJunctionIndex(primary, primaryIndex)
                        && !isTunnelJunctionPair(primary, primaryIndex, secondary, adjacentIndex))) continue;
                RoadSegmentPlacement candidate = primarySegments.get(primaryIndex);
                if (candidate == null || candidate.middlePos() == null) continue;
                BlockPos a = adjacent.middlePos();
                BlockPos b = candidate.middlePos();
                int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
                if (distance > MAX_APPROACH_CONNECTOR_DISTANCE
                        || !gradeCompatibleAtJunction(primary, primaryIndex, secondary, adjacentIndex, distance)
                        || (distance == 0 && !isTunnelJunctionPair(primary, primaryIndex,
                        secondary, adjacentIndex))) {
                    continue;
                }
                if (!safeBoundary(primary, primaryIndex, secondary, adjacentIndex)) continue;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new JunctionCandidate(secondaryIndex, primaryIndex);
                }
            }
            if (best != null && bestDistance <= RoadConstants.MAX_CENTER_STEP) break;
        }
        return best;
    }

    /** 一条隧道来路与地表来路可在共享端点用有界洞口过渡连接，特殊跨度和端点保护仍保留。
 *
     * Fallback for a shared endpoint where one route reaches the node through
     * a tunnel and the other reaches it on the surface. The normal junction
     * search intentionally requires matching grades; this variant keeps the
     * same endpoint/special-span guards but permits a bounded tunnel portal
     * transition.
     */
    static JunctionCandidate findSurfaceTunnelEndpointApproach(
            RoadData primary,
            boolean[] primarySpecial,
            RoadData secondary,
            boolean[] secondarySpecial,
            boolean sharedAtStart) {
        List<RoadSegmentPlacement> secondarySegments = secondary.roadSegmentList();
        List<RoadSegmentPlacement> primarySegments = primary.roadSegmentList();
        int maxOffset = Math.min(ENDPOINT_RUN_MAX_OFFSET, secondarySegments.size() - 2);
        JunctionCandidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int offset = 0; offset <= maxOffset; offset++) {
            int secondaryIndex = sharedAtStart ? offset : secondarySegments.size() - 1 - offset;
            int adjacentIndex = sharedAtStart ? secondaryIndex + 1 : secondaryIndex - 1;
            if (isSpecial(secondarySpecial, secondaryIndex)
                    || isSpecial(secondarySpecial, adjacentIndex)
                    || (!isSafeJunctionIndex(secondary, adjacentIndex)
                    && !isTunnelBoundaryNear(secondary, adjacentIndex))) continue;
            RoadSegmentPlacement adjacent = secondarySegments.get(adjacentIndex);
            if (adjacent == null || adjacent.middlePos() == null) continue;
            for (int primaryIndex = 0; primaryIndex < primarySegments.size(); primaryIndex++) {
                if (isSpecial(primarySpecial, primaryIndex)
                        || !isSafeJunctionIndex(primary, primaryIndex)) continue;
                RoadSegmentPlacement candidate = primarySegments.get(primaryIndex);
                if (candidate == null || candidate.middlePos() == null
                        || !isTunnelGradeTransitionPair(primary, primaryIndex, secondary, adjacentIndex)) continue;
                BlockPos a = adjacent.middlePos();
                BlockPos b = candidate.middlePos();
                int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
                if (distance > MAX_APPROACH_CONNECTOR_DISTANCE
                        || !gradeCompatibleAtJunction(primary, primaryIndex, secondary, adjacentIndex, distance)
                        || (distance == 0 && !isTunnelGradeTransitionPair(primary, primaryIndex,
                        secondary, adjacentIndex))) continue;
                if (!safeBoundary(primary, primaryIndex, secondary, adjacentIndex)) continue;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new JunctionCandidate(secondaryIndex, primaryIndex);
                }
            }
            if (best != null && bestDistance <= RoadConstants.MAX_CENTER_STEP) break;
        }
        return best;
    }

    /** 普通路大高差最后接入仅限端点接近段，桥、水跨越及保护站不能套用此放宽。
 *
     * Last-resort endpoint join for two ordinary routes whose shared crossing
     * has a large Y mismatch. It is deliberately limited to the endpoint
     * approach and never admits bridge/crossing/protected stations.
     */
    static JunctionCandidate findHeightTransitionEndpointApproach(
            RoadData primary,
            boolean[] primarySpecial,
            RoadData secondary,
            boolean[] secondarySpecial,
            boolean sharedAtStart) {
        List<RoadSegmentPlacement> secondarySegments = secondary.roadSegmentList();
        List<RoadSegmentPlacement> primarySegments = primary.roadSegmentList();
        int maxOffset = Math.min(ENDPOINT_RUN_MAX_OFFSET, secondarySegments.size() - 2);
        JunctionCandidate best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int offset = 0; offset <= maxOffset; offset++) {
            int secondaryIndex = sharedAtStart ? offset : secondarySegments.size() - 1 - offset;
            int adjacentIndex = sharedAtStart ? secondaryIndex + 1 : secondaryIndex - 1;
            if (isSpecial(secondarySpecial, secondaryIndex)
                    || isSpecial(secondarySpecial, adjacentIndex)
                    || !isSafeJunctionIndex(secondary, adjacentIndex)) continue;
            RoadSegmentPlacement adjacent = secondarySegments.get(adjacentIndex);
            if (adjacent == null || adjacent.middlePos() == null) continue;
            for (int primaryIndex = 0; primaryIndex < primarySegments.size(); primaryIndex++) {
                if (isSpecial(primarySpecial, primaryIndex)
                        || !isSafeJunctionIndex(primary, primaryIndex)) continue;
                RoadSegmentPlacement candidate = primarySegments.get(primaryIndex);
                if (candidate == null || candidate.middlePos() == null) continue;
                BlockPos a = adjacent.middlePos();
                BlockPos b = candidate.middlePos();
                int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
                if (distance > MAX_APPROACH_CONNECTOR_DISTANCE) continue;
                int height = Math.abs(targetAt(primary, primaryIndex)
                        - targetAt(secondary, adjacentIndex));
                int allowed = Math.max(distance, MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS);
                if (height > allowed || !safeBoundaryIgnoringGrade(
                        primary, primaryIndex, secondary, adjacentIndex)) continue;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new JunctionCandidate(secondaryIndex, primaryIndex);
                }
            }
            if (best != null && bestDistance <= RoadConstants.MAX_CENTER_STEP) break;
        }
        return best;
    }

    static boolean safeBoundaryIgnoringGrade(RoadData primary, int primaryIndex,
                                                      RoadData secondary, int secondaryIndex) {
        RoadSegmentPlacement a = primary.roadSegmentList().get(primaryIndex);
        RoadSegmentPlacement b = secondary.roadSegmentList().get(secondaryIndex);
        if (a == null || b == null || a.middlePos() == null || b.middlePos() == null) return false;
        int distance = Math.max(Math.abs(a.middlePos().getX() - b.middlePos().getX()),
                Math.abs(a.middlePos().getZ() - b.middlePos().getZ()));
        if (distance == 0) return true;
        // The height-transition path inserts a real connector. For a one or
        // two block centre offset, requiring pre-existing footprint overlap
        // would reject the exact cross-intersection this fallback is meant to
        // repair.
        return distance <= MAX_APPROACH_CONNECTOR_DISTANCE;
    }

    static void alignSecondaryGradeToAnchor(List<Integer> targetY,
                                                     int anchorFrom,
                                                     int anchorTo,
                                                     boolean sharedAtStart) {
        int junctionIndex = sharedAtStart ? anchorTo : anchorFrom;
        if (targetY == null || junctionIndex < 0 || junctionIndex >= targetY.size()) return;
        int junctionY = targetY.get(junctionIndex);
        int direction = sharedAtStart ? 1 : -1;
        for (int distance = 1, index = junctionIndex + direction;
             index >= 0 && index < targetY.size();
             distance++, index += direction) {
            int permittedDelta = (distance + 1) / 2;
            int value = Math.max(junctionY - permittedDelta,
                    Math.min(junctionY + permittedDelta, targetY.get(index)));
            int previous = index - direction;
            value = Math.max(targetY.get(previous) - 1,
                    Math.min(targetY.get(previous) + 1, value));
            int previousTwo = index - direction * 2;
            if (previousTwo >= 0 && previousTwo < targetY.size()) {
                value = Math.max(targetY.get(previousTwo) - 1,
                        Math.min(targetY.get(previousTwo) + 1, value));
            }
            targetY.set(index, value);
        }
    }

    /** 隧道丁字路口以较低转弯隧道为锚，局部降低高主干并逐站接坡，避免上下叠两层地板。
 *
     * A lower turning tunnel is the preferred grade at a tunnel T/plus
     * junction. Lower the higher trunk locally instead of leaving two floors
     * stacked above one another. The transition is limited to the junction
     * window and changes by at most one block per station.
     */
    static void alignJunctionToLowerTunnelGrade(List<Integer> targetY,
                                                         int anchorFrom,
                                                         int anchorTo,
                                                         boolean sharedAtStart,
                                                         int transitionSegments) {
        if (targetY == null || targetY.isEmpty()) return;
        int junction = sharedAtStart ? anchorTo : anchorFrom;
        int other = sharedAtStart ? anchorTo + 1 : anchorFrom - 1;
        if (junction < 0 || junction >= targetY.size()
                || other < 0 || other >= targetY.size()) return;
        int lower = Math.min(targetY.get(junction), targetY.get(other));
        targetY.set(junction, lower);
        smoothGradeSide(targetY, junction,
                sharedAtStart ? -1 : 1, lower, transitionSegments);
        smoothGradeSide(targetY, junction,
                sharedAtStart ? 1 : -1, lower, transitionSegments);
        repairLocalHeightJoins(targetY);
    }

    static void smoothGradeSide(List<Integer> targetY, int start,
                                        int direction, int lower, int limit) {
        int previous = start;
        for (int distance = 1, index = start + direction;
             distance <= Math.max(1, limit) && index >= 0 && index < targetY.size();
             distance++, index += direction) {
            int value = targetY.get(index);
            if (value > lower) value = Math.min(value, targetY.get(previous) + 1);
            else value = Math.max(value, targetY.get(previous) - 1);
            targetY.set(index, value);
            previous = index;
        }
    }


    static Boolean targetIndicesIncrease(int[] targets, int[] run) {
        if (targets == null || run == null || run.length < 2) return null;
        int previous = -1;
        for (int i = Math.max(0, run[0]); i <= Math.min(targets.length - 1, run[1]); i++) {
            int current = targets[i];
            if (current < 0) continue;
            if (previous >= 0 && current != previous) return current > previous;
            previous = current;
        }
        return null;
    }

    static JunctionCandidate findSafeJunction(RoadData primary,
                                                       boolean[] primarySpecial,
                                                       RoadData secondary,
                                                       boolean[] secondarySpecial,
                                                       int[] targets,
                                                       int[] run,
                                                       boolean sharedAtStart) {
        int start = sharedAtStart ? run[1] : run[0];
        int end = sharedAtStart ? run[0] : run[1];
        int step = sharedAtStart ? -1 : 1;
        for (int secondaryIndex = start; ; secondaryIndex += step) {
            int adjacentIndex = sharedAtStart ? secondaryIndex + 1 : secondaryIndex - 1;
            int primaryIndex = findSafePrimaryIndex(
                    primary, primarySpecial, secondary, adjacentIndex, targets[secondaryIndex]);
            if (adjacentIndex >= 0 && adjacentIndex < secondary.roadSegmentList().size()
                    && primaryIndex >= 0 && primaryIndex < primary.roadSegmentList().size()
                    && !isSpecial(secondarySpecial, secondaryIndex)
                    && !isSpecial(secondarySpecial, adjacentIndex)
                    && (isSafeJunctionIndex(secondary, adjacentIndex)
                    || isTunnelJunctionPair(primary, primaryIndex, secondary, adjacentIndex))
                    ) {
                return new JunctionCandidate(secondaryIndex, primaryIndex);
            }
            if (secondaryIndex == end) break;
        }
        return null;
    }

    static int findSafePrimaryIndex(RoadData primary,
                                            boolean[] primarySpecial,
                                            RoadData secondary,
                                            int secondaryIndex,
                                            int suggestedIndex) {
        if (secondary == null || secondaryIndex < 0
                || secondaryIndex >= secondary.roadSegmentList().size()) return -1;
        if (suggestedIndex >= 0 && suggestedIndex < primary.roadSegmentList().size()
                && !isSpecial(primarySpecial, suggestedIndex)
                && (isSafeJunctionIndex(primary, suggestedIndex)
                    || isTunnelJunctionPair(primary, suggestedIndex, secondary, secondaryIndex))
                && safeBoundary(primary, suggestedIndex, secondary, secondaryIndex)) return suggestedIndex;

        BlockPos target = secondary.roadSegmentList().get(secondaryIndex).middlePos();
        if (target == null) return -1;
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int primaryIndex = 0; primaryIndex < primary.roadSegmentList().size(); primaryIndex++) {
            if (isSpecial(primarySpecial, primaryIndex)
                    || (!isSafeJunctionIndex(primary, primaryIndex)
                    && !isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex))) continue;
            RoadSegmentPlacement candidate = primary.roadSegmentList().get(primaryIndex);
            if (candidate == null || candidate.middlePos() == null
                    || !safeBoundary(primary, primaryIndex, secondary, secondaryIndex)) continue;
            long distance = dist2XZ(target, candidate.middlePos());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = primaryIndex;
            }
        }
        return bestIndex;
    }

    static boolean safeBoundary(RoadData primary,
                                        int primaryIndex,
                                        RoadData secondary,
                                        int secondaryIndex) {
        RoadSegmentPlacement primarySegment = primary.roadSegmentList().get(primaryIndex);
        RoadSegmentPlacement secondarySegment = secondary.roadSegmentList().get(secondaryIndex);
        if (primarySegment == null || secondarySegment == null
                || primarySegment.middlePos() == null || secondarySegment.middlePos() == null) return false;

        BlockPos a = primarySegment.middlePos();
        BlockPos b = secondarySegment.middlePos();
        int centerStep = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        if (centerStep > MAX_APPROACH_CONNECTOR_DISTANCE
                || !gradeCompatibleAtJunction(primary, primaryIndex, secondary, secondaryIndex, centerStep)) {
            return false;
        }
        if (centerStep == 0) {
            return isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex);
        }
        // Direct joins must share a physical road edge.  A nearby parallel
        // approach receives interpolated stations below, so it remains a real
        // road rather than an invalid jump between two distant centers.
        return centerStep > RoadConstants.MAX_CENTER_STEP || footprintsConnect(primarySegment, secondarySegment);
    }

    /** 仅在已确认安全且同高的近距普通路边界插入连续短接，不能穿过已排除的水桥。
 *
     * Inserts a narrow, continuous approach from the current output endpoint
     * to the first segment that remains on the secondary route.  It is used
     * only after {@link #safeBoundary} has proven a close, grade-compatible
     * ordinary-road join; bridge and water spans were excluded before that.
     */
    static boolean appendApproachConnector(List<RoadSegmentPlacement> output,
                                                    List<Integer> targetY,
                                                    int width,
                                                    RoadSegmentPlacement destination,
                                                    int destinationY) {
        return appendApproachConnector(output, targetY, width, destination, destinationY, false);
    }

    static boolean appendApproachConnector(List<RoadSegmentPlacement> output,
                                                    List<Integer> targetY,
                                                    int width,
                                                    RoadSegmentPlacement destination,
                                                    int destinationY,
                                                    boolean tunnelGradeTransition) {
        return appendApproachConnector(output, targetY, width, destination, destinationY,
                tunnelGradeTransition, MAX_TUNNEL_JUNCTION_GRADE_DELTA);
    }

    static boolean appendApproachConnector(List<RoadSegmentPlacement> output,
                                                    List<Integer> targetY,
                                                    int width,
                                                    RoadSegmentPlacement destination,
                                                    int destinationY,
                                                    boolean tunnelGradeTransition,
                                                    int tunnelGradeLimit) {
        if (output == null || output.isEmpty() || targetY == null || targetY.isEmpty()
                || destination == null || destination.middlePos() == null) return false;
        RoadSegmentPlacement originSegment = output.getLast();
        BlockPos origin = originSegment == null ? null : originSegment.middlePos();
        BlockPos destinationPos = destination.middlePos();
        if (origin == null) return false;
        int dx = destinationPos.getX() - origin.getX();
        int dz = destinationPos.getZ() - origin.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int originY = targetY.getLast();
        if (steps == 0 || steps > MAX_APPROACH_CONNECTOR_DISTANCE
                || (!tunnelGradeTransition && Math.abs(destinationY - originY) > steps)
                || (tunnelGradeTransition
                && Math.abs(destinationY - originY) > Math.max(MAX_TUNNEL_JUNCTION_GRADE_DELTA,
                Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS, tunnelGradeLimit)))) return false;
        if (steps <= RoadConstants.MAX_CENTER_STEP) return true;

        Set<Long> centers = new HashSet<>();
        for (RoadSegmentPlacement segment : output) {
            if (segment != null && segment.middlePos() != null) {
                centers.add(posKey(segment.middlePos().getX(), segment.middlePos().getZ()));
            }
        }
        for (int step = 1; step < steps; step++) {
            int x = (int) Math.round(origin.getX() + dx * (step / (double) steps));
            int z = (int) Math.round(origin.getZ() + dz * (step / (double) steps));
            long key = posKey(x, z);
            if (!centers.add(key)) return false;
            int y = (int) Math.round(originY + (destinationY - originY) * (step / (double) steps));
            BlockPos center = new BlockPos(x, y, z);
            output.add(new RoadSegmentPlacement(center,
                    connectorFootprint(center, dx, dz, width)));
            targetY.add(y);
        }
        return true;
    }

    static List<BlockPos> connectorFootprint(BlockPos center, int dx, int dz, int width) {
        int laneWidth = Math.max(1, width);
        double length = Math.hypot(dx, dz);
        if (length < 1.0D) return List.of(new BlockPos(center.getX(), 0, center.getZ()));
        double normalX = -dz / length;
        double normalZ = dx / length;
        int lower = -(laneWidth / 2);
        int upper = lower + laneWidth - 1;
        Set<BlockPos> footprint = new LinkedHashSet<>();
        for (int offset = lower; offset <= upper; offset++) {
            footprint.add(new BlockPos(
                    (int) Math.round(center.getX() + normalX * offset),
                    0,
                    (int) Math.round(center.getZ() + normalZ * offset)));
        }
        return List.copyOf(footprint);
    }

    static boolean footprintsConnect(RoadSegmentPlacement first, RoadSegmentPlacement second) {
        Set<Long> firstColumns = footprintColumns(first);
        Set<Long> secondColumns = footprintColumns(second);
        if (firstColumns.isEmpty() || secondColumns.isEmpty()) return false;

        Set<Long> smaller = firstColumns.size() <= secondColumns.size() ? firstColumns : secondColumns;
        Set<Long> larger = smaller == firstColumns ? secondColumns : firstColumns;
        int requiredContacts = Math.min(2, smaller.size());
        int contacts = 0;
        for (long packed : smaller) {
            int x = (int) (packed >> 32);
            int z = (int) packed;
            boolean touches = false;
            for (int dx = -1; dx <= 1 && !touches; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (larger.contains(posKey(x + dx, z + dz))) {
                        touches = true;
                        break;
                    }
                }
            }
            if (touches && ++contacts >= requiredContacts) return true;
        }
        return false;
    }

    static Set<Long> footprintColumns(RoadSegmentPlacement segment) {
        Set<Long> columns = new LinkedHashSet<>();
        if (segment == null || segment.middlePos() == null) return columns;
        columns.add(posKey(segment.middlePos().getX(), segment.middlePos().getZ()));
        if (segment.positions() != null) {
            for (BlockPos pos : segment.positions()) {
                if (pos != null) columns.add(posKey(pos.getX(), pos.getZ()));
            }
        }
        return columns;
    }

    static AnchorPlan buildAnchorPlan(List<RoadSegmentPlacement> primarySegments,
                                              List<Integer> primaryTargetY,
                                              boolean[] primarySpecial,
                                              int targetIndex,
                                              boolean increasingTargets,
                                              boolean sharedAtStart) {
        return buildAnchorPlan(primarySegments, primaryTargetY, primarySpecial,
                targetIndex, increasingTargets, sharedAtStart, JUNCTION_ANCHOR_SEGMENTS);
    }

    static AnchorPlan buildAnchorPlan(List<RoadSegmentPlacement> primarySegments,
                                              List<Integer> primaryTargetY,
                                              boolean[] primarySpecial,
                                              int targetIndex,
                                              boolean increasingTargets,
                                              boolean sharedAtStart,
                                              int anchorSegments) {
        int preferredStep = increasingTargets ? 1 : -1;
        AnchorPlan preferred = buildAnchorPlan(
                primarySegments, primaryTargetY, primarySpecial,
                targetIndex, preferredStep, sharedAtStart, anchorSegments);
        if (preferred != null) return preferred;
        return buildAnchorPlan(
                primarySegments, primaryTargetY, primarySpecial,
                targetIndex, -preferredStep, sharedAtStart, anchorSegments);
    }

    static AnchorPlan buildAnchorPlan(List<RoadSegmentPlacement> primarySegments,
                                              List<Integer> primaryTargetY,
                                              boolean[] primarySpecial,
                                              int targetIndex,
                                              int outputStep,
                                              boolean sharedAtStart) {
        return buildAnchorPlan(primarySegments, primaryTargetY, primarySpecial,
                targetIndex, outputStep, sharedAtStart, JUNCTION_ANCHOR_SEGMENTS);
    }

    static AnchorPlan buildAnchorPlan(List<RoadSegmentPlacement> primarySegments,
                                              List<Integer> primaryTargetY,
                                              boolean[] primarySpecial,
                                              int targetIndex,
                                              int outputStep,
                                              boolean sharedAtStart,
                                              int anchorSegments) {
        int count = Math.max(JUNCTION_ANCHOR_SEGMENTS,
                Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS, anchorSegments));
        int firstIndex = sharedAtStart
                ? targetIndex - outputStep * (count - 1)
                : targetIndex;
        int lastIndex = sharedAtStart
                ? targetIndex
                : targetIndex + outputStep * (count - 1);
        if (firstIndex < 0 || firstIndex >= primarySegments.size()
                || lastIndex < 0 || lastIndex >= primarySegments.size()) return null;

        List<RoadSegmentPlacement> segments = new ArrayList<>(count);
        List<Integer> targetY = new ArrayList<>(count);
        int index = firstIndex;
        for (int i = 0; i < count; i++, index += outputStep) {
            if (index < 0 || index >= primarySegments.size()
                    || (primarySpecial != null && index < primarySpecial.length && primarySpecial[index])) return null;
            RoadSegmentPlacement segment = primarySegments.get(index);
            if (segment == null || segment.middlePos() == null) return null;
            // 锚点不改分站内容；复用已冻结对象，避免每次候选再次复制全部铺装列。
            segments.add(segment);
            targetY.add(primaryTargetY != null && index < primaryTargetY.size()
                    ? primaryTargetY.get(index) : segment.middlePos().getY());
        }
        return new AnchorPlan(List.copyOf(segments), List.copyOf(targetY));
    }

    static void appendSecondaryRange(RoadData secondary,
                                             int fromInclusive,
                                             int toExclusive,
                                             List<RoadSegmentPlacement> segments,
                                             List<Integer> targetY) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(secondary.roadSegmentList().size(), toExclusive);
        for (int i = from; i < to; i++) {
            segments.add(secondary.roadSegmentList().get(i));
            targetY.add(targetAt(secondary, i));
        }
    }

    static boolean validateJunctionGeometry(List<RoadSegmentPlacement> segments,
                                                    List<Integer> targetY,
                                                    int anchorFrom,
                                                    int anchorTo,
                                                    boolean sharedAtStart) {
        int from = Math.max(0, anchorFrom - (sharedAtStart ? 0 : 3));
        int to = Math.min(segments.size() - 1, anchorTo + (sharedAtStart ? 3 : 0));
        Set<Long> centers = new HashSet<>();
        RoadSegmentPlacement previous = null;
        int previousY = 0;
        for (int i = from; i <= to; i++) {
            RoadSegmentPlacement current = segments.get(i);
            if (current == null || current.middlePos() == null) return false;
            long key = posKey(current.middlePos().getX(), current.middlePos().getZ());
            if (!centers.add(key)) return false;
            int currentY = targetY.get(i);
            if (previous != null) {
                BlockPos a = previous.middlePos();
                BlockPos b = current.middlePos();
                int step = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
                if (step == 0 || step > RoadConstants.MAX_CENTER_STEP || Math.abs(currentY - previousY) > RoadConstants.MAX_HEIGHT_STEP) {
                    return false;
                }
            }
            previous = current;
            previousY = currentY;
        }
        return true;
    }

    static int targetAt(RoadData road, int index) {
        List<Integer> targetY = road.targetY();
        if (targetY != null && index >= 0 && index < targetY.size()) return targetY.get(index);
        return road.roadSegmentList().get(index).middlePos().getY();
    }

    static boolean isUsable(RoadData road) {
        return road != null && road.roadSegmentList() != null && road.roadSegmentList().size() >= MIN_RUN;
    }
}
