/* 文件职责：结构相交修复 —— 判断路切进建筑后怎么改线，改不动就保持原样。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;

// 仍留在其它同包类里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.planningCollisionBox;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.describePlanningCollision;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;

/**
 * 结构相交修复。
 *
 * <h2>它做什么</h2>
 * <p>路切进建筑时，在冲突窗口里重找一条走线，让路绕开建筑。
 * 三种入口对应三种严格度：普通修复、<b>精确净空</b>修复
 * （{@code repairStructureIntersectionsAtExactClearance}，连「擦边」都不许）、
 * 以及返回 {@code ExactStructureRepairOutcome} 的可观测版本。</p>
 *
 * <h2>★ 改不动就保持原样</h2>
 * <p>修复失败时<b>不返回空、也不返回半成品</b>，而是把原路径原样退回
 * （{@code keepCandidateOnNull}）。理由：修复的触发条件是「有冲突」，
 * 但修复结果可能让路绕得离谱（绕远、爬坡）。**宁可留着这一次冲突，
 * 也不许把路改坏** —— 冲突会在最终校验里报出来，改坏的几何没人看得出来。</p>
 *
 * <h2>为什么失败要写日志</h2>
 * <p>{@code describeStructureCandidateFailure} 把「为什么没修成」拆成几类
 * （没有候选 / 候选绕太远 / 候选自己又撞上别的）。这条日志是唯一能事后
 * 判断「是这个策略太保守，还是这个地形真的没解」的证据。</p>
 *
 * <p>本类是从 2193 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 判据、净空阈值与回退语义一字未改（含缩进）。</p>
 */
public final class StructureRepair {
    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection) {
        return repairStructureIntersections(
                accuratePath, level, sampler, config, connection, STRUCTURE_SEARCH_MARGIN_BLOCKS);
    }

    /**
     * Repairs only the window around the first exact structure conflict. The
     * caller controls the lateral detour allowance; this never re-plans the
     * full endpoint-to-endpoint connection.
     */
    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks) {
        return repairStructureIntersections(accuratePath, level, sampler, config, connection,
                localDetourBlocks, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    /**
     * Final building-only precision pass.  The search stays local to the
     * conflicting building but permits grazing its real footprint; only the
     * actual road corridor may not overlap decoded template blocks.
     */
    public static List<BlockPos> repairStructureIntersectionsAtExactClearance(
            List<BlockPos> accuratePath,
            ServerLevel level,
            AccurateHeightSampler sampler,
            PathfindingCostConfig config,
            StructureConnection connection,
            int localDetourBlocks) {
        return repairStructureIntersectionsAtExactClearanceDetailed(
                accuratePath, level, sampler, config, connection, localDetourBlocks).path();
    }

    /** Outcome of one exact-clearance building repair pass. */
    public record ExactStructureRepairOutcome(List<BlockPos> path,
                                              BlockPos lastRejectedCenter,
                                              int lastRejectedIndex,
                                              String lastRejectionReason) {
    }

    /**
     * Same as {@link #repairStructureIntersectionsAtExactClearance} but also
     * reports the conflict centre that could not be rerouted, so callers can
     * stop escalating the detour radius when the same conflict already failed
     * at a smaller radius.
     */
    public static ExactStructureRepairOutcome repairStructureIntersectionsAtExactClearanceDetailed(
            List<BlockPos> accuratePath,
            ServerLevel level,
            AccurateHeightSampler sampler,
            PathfindingCostConfig config,
            StructureConnection connection,
            int localDetourBlocks) {
        PathfindingCostConfig exact = config == null ? null : config.withAStarStep(1);
        List<BlockPos> rejectedCenters = new ArrayList<>(1);
        List<Integer> rejectedIndices = new ArrayList<>(1);
        List<BlockPos> path = repairStructureIntersections(accuratePath, level, sampler, exact,
                connection, localDetourBlocks, 0, rejectedCenters, rejectedIndices);
        BlockPos rejectedCenter = rejectedCenters.isEmpty() ? null : rejectedCenters.getLast();
        int rejectedIndex = rejectedIndices.isEmpty() ? -1 : rejectedIndices.getLast();
        return new ExactStructureRepairOutcome(path, rejectedCenter, rejectedIndex,
                rejectedCenter == null ? null : "unresolved");
    }

    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks,
                                                                int structureClearance) {
        return repairStructureIntersections(accuratePath, level, sampler, config, connection,
                localDetourBlocks, structureClearance, null, null);
    }

    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks,
                                                                int structureClearance,
                                                                List<BlockPos> rejectedCenterSink,
                                                                List<Integer> rejectedIndexSink) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null) return accuratePath;

        // Exact building repair still has to reserve the complete road body.
        // A centre-line-only A* result can look clear while its five-block
        // road edge clips the building on a bend.
        int roadBodyRadius = roadBodyRadius();
        int collisionClearance = structureClearance > 0
                ? structureClearance : roadBodyRadius;
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        // The exact-clearance entry point passes zero to avoid adding an
        // artificial collision margin, but the snapshot still has to index
        // structures throughout the complete local detour window.  Without
        // this, a valid bend that crosses into the neighbouring chunk cannot
        // see the same building and is incorrectly accepted or rejected.
        // Snapshot expansion is a physical road-width margin. The local
        // terrain search radius is independent and must not enlarge buildings.
        int snapshotRadius = Math.max(0, roadBodyRadius());
        while (repaired < MAX_STRUCTURE_REPAIRS) {
            Violation conflict;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                conflict = findFirstStructureConflict(working, collisionClearance);
            }
            if (conflict == null) break;

            // The route snapshot may cover the original corridor but not every
            // chunk in this conflict-centered repair circle. Resolve the circle
            // before A* so unknown neighboring chunks do not all look blocked.
            BlockPos localCenter = conflictCenter(working, conflict);
            if (localCenter != null && localDetourBlocks > 0) {
                awaitLocalStructureCircle(level, connection, localCenter, localDetourBlocks);
            }
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                conflict = findFirstStructureConflict(working, collisionClearance);
                if (conflict != null) {
                    conflict = expandNearbyStructureCluster(
                            working, conflict, collisionClearance, localDetourBlocks);
                }
            }
            if (conflict == null) break;

            int repairContext = Math.max(1, Math.min(STRUCTURE_CONTEXT_BLOCKS, localDetourBlocks));
            List<RepairSlice> repairSlices = structureRepairSlices(
                    working, conflict, localCenter, repairContext);
            if (repairSlices.isEmpty()) break;
            RepairSlice selectedSlice = null;
            List<BlockPos> candidate = null;
            boolean accepted;
            boolean tunnelSearchUsed = false;
            String conflictDetail;
            int rerouteStep = 2;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                BlockPos conflictPos = conflict.start() >= 0 && conflict.start() < working.size()
                        ? working.get(conflict.start()) : null;
                BlockPos conflictEnd = conflict.end() >= 0 && conflict.end() < working.size()
                        ? working.get(conflict.end()) : conflictPos;
                BlockPos searchCenter = conflictPos == null || conflictEnd == null
                        ? conflictPos
                        : new BlockPos(
                        (conflictPos.getX() + conflictEnd.getX()) / 2,
                        (conflictPos.getY() + conflictEnd.getY()) / 2,
                        (conflictPos.getZ() + conflictEnd.getZ()) / 2);
                conflictDetail = describePlanningCollision(conflictPos, conflictEnd);
                // The 100-block value is only the terrain sampling/search
                // radius. Building collision remains limited to the actual
                // road body width so nearby but non-touching structures do
                // not incorrectly block a valid edge-grazing detour.
                BoundingBox conflictBox = planningCollisionBox(
                        conflictPos, collisionClearance);

                accepted = false;
                // Try several endpoint-preserving slices. A single slice can
                // fail when the conflict is close to a route endpoint or when
                // its snapped grid point falls inside the protected box.
                // Every slice remains bounded by the same local detour radius.
                for (RepairSlice slice : repairSlices) {
                    List<BlockPos> slicePath = slice.path();
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            EdgeObstacle.NONE, 2, false, collisionClearance, conflictBox,
                            Integer.MIN_VALUE, searchCenter);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=surface radius={} roadWidth={} "
                                        + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                                conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=surface radius={} roadWidth={} "
                                    + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} reason={} {} connection={}->{}",
                            localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                            conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            describeStructureCandidateFailure(slicePath, candidate, collisionClearance),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());

                    // 0.8.330：surface 搜索"根本没路"时跳过隧道搜索。真机 20260909 21_51
                    // 存档：18 次隧道尝试全部 no_path、平均 13.2 秒（最长 25.8 秒），
                    // 合计约 240 秒纯浪费；而同批 surface 搜索平均只要 0.7 秒，换一个
                    // slice 再试 surface 往往就成功了。隧道搜索只有在"surface 找得到路
                    // 但过不去"时才值得一试。
                    if (candidate == null || candidate.size() < 2) {
                        LOGGER.info("RoadWeaver structure local tunnel search skipped: surface found no path "
                                        + "conflictEdge={}..{} center={} slice={}..{} connection={}->{}",
                                conflict.start(), conflict.end(), searchCenter,
                                slice.startIndex(), slice.endIndex(), connection.from(), connection.to());
                        continue;
                    }

                    tunnelSearchUsed = true;
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            EdgeObstacle.NONE, rerouteStep, true, collisionClearance, conflictBox,
                            localDetourTunnelFloor(level, searchCenter), searchCenter, true);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=tunnel radius={} roadWidth={} "
                                        + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                                conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=tunnel radius={} roadWidth={} "
                                    + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} reason={} {} connection={}->{}",
                            localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                            conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            describeStructureCandidateFailure(slicePath, candidate, collisionClearance),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());

                    // ③ 硬绕行重试：把冲突建筑盒设为"禁止进入"的边障碍，强制局部 A*
                    // 从盒外绕过去，而不是贴着或穿过它（用户要求：建筑该绕行还是得绕行）。
                    // 比"四方向盲试"更对症：禁止进入后，A* 会自己从各个方向找出口。
                    EdgeObstacle blockEnteringBox = (from, to) -> pointInsideBox(conflictBox, to);
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            blockEnteringBox, 2, false, collisionClearance, conflictBox,
                            Integer.MIN_VALUE, searchCenter);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=hard-detour radius={} "
                                        + "conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=hard-detour radius={} "
                                    + "conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} {} connection={}->{}",
                            localDetourBlocks, conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());
                }
            }
            if (!accepted) {
                LOGGER.warn("RoadWeaver exact structure repair could not reroute conflict edge={}..{} windowNodes={} "
                                + "slices={} rerouteStep={} clearance={} tunnelSearch={} {}",
                        conflict.start(), conflict.end(), repairSlices.get(0).path().size(), repairSlices.size(),
                        rerouteStep, collisionClearance, tunnelSearchUsed, conflictDetail);
                if (rejectedCenterSink != null) {
                    BlockPos center = conflictCenter(working, conflict);
                    if (center != null) rejectedCenterSink.add(center);
                }
                if (rejectedIndexSink != null) {
                    rejectedIndexSink.add(Math.max(0, Math.min(working.size() - 1,
                            (conflict.start() + conflict.end()) / 2)));
                }
                break;
            }

            List<BlockPos> original = selectedSlice.path();
            int startIndex = selectedSlice.startIndex();
            int endIndex = selectedSlice.endIndex();
            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            LOGGER.info("Accepted exact local RoadWeaver structure reroute: detour={} clearance={} "
                            + "tunnelSearch={} conflictEdge={}..{} nodes={} -> {}",
                    localDetourBlocks, localDetourBlocks, tunnelSearchUsed,
                    conflict.start(), conflict.end(),
                    original.size(), candidate.size());
        }
        return List.copyOf(working);
    }

    public static String describeStructureCandidateFailure(List<BlockPos> original,
                                                            List<BlockPos> candidate,
                                                            int structureClearance) {
        if (candidate == null) return "no_path";
        if (candidate.size() < 2) return "path_too_short";
        if (sameFullPath(original, candidate)) return "unchanged_path";
        Violation grade = findFirstViolation(candidate);
        if (grade != null) {
            return "grade edge=" + grade.start() + ".." + grade.end()
                    + " rise=" + grade.rise() + " length=" + format(grade.length());
        }
        Violation structure = findFirstStructureConflict(candidate, structureClearance);
        if (structure != null) {
            BlockPos from = candidate.get(Math.max(0, structure.start()));
            BlockPos to = candidate.get(Math.max(0, Math.min(candidate.size() - 1, structure.end())));
            return "structure edge=" + structure.start() + ".." + structure.end() + " "
                    + describePlanningCollision(from, to);
        }
        double originalLength = routeLength(original);
        double candidateLength = routeLength(candidate);
        if (candidateLength > Math.max(originalLength + 64.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER)) {
            return "detour_too_long candidate=" + format(candidateLength)
                    + " original=" + format(originalLength);
        }
        return "rejected_by_acceptance_policy";
    }

    /**
     * Force a local, endpoint-preserving height profile when no alternate X/Z
     * route can be found.  The caller has already bounded the input to the
     * height-repair window; only Y values are changed here.
     */
    public static List<BlockPos> forceSmoothHeightWindow(List<BlockPos> original) {
        if (original == null || original.size() < 3) return null;
        int size = original.size();
        double[] distances = new double[size];
        for (int i = 1; i < size; i++) {
            BlockPos previous = original.get(i - 1);
            BlockPos current = original.get(i);
            if (previous == null || current == null) return null;
            distances[i] = distances[i - 1] + horizontalDistance(previous, current);
        }
        double total = distances[size - 1];
        if (total <= 0.001D) return null;

        int startY = original.get(0).getY();
        int endY = original.get(size - 1).getY();
        int[] heights = new int[size];
        heights[0] = startY;
        heights[size - 1] = endY;
        for (int i = 1; i < size - 1; i++) {
            double ratio = distances[i] / total;
            heights[i] = (int) Math.round(startY + (endY - startY) * ratio);
        }

        // Project the interpolated profile onto the one-block grade limit.
        // Repeating both directions keeps the correction local while
        // preserving the two endpoint heights whenever they are feasible.
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < size - 1; i++) {
                int limit = Math.max(0, (int) Math.floor(
                        horizontalDistance(original.get(i - 1), original.get(i))));
                heights[i] = clamp(heights[i], heights[i - 1] - limit, heights[i - 1] + limit);
            }
            for (int i = size - 2; i >= 1; i--) {
                int limit = Math.max(0, (int) Math.floor(
                        horizontalDistance(original.get(i), original.get(i + 1))));
                heights[i] = clamp(heights[i], heights[i + 1] - limit, heights[i + 1] + limit);
            }
        }

        List<BlockPos> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos source = original.get(i);
            result.add(new BlockPos(source.getX(), heights[i], source.getZ()));
        }
        return result;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 绕行候选的接缝高度修复。绕行 A* 只优化 XZ，其返回的候选路径与原
     * 路径的接缝段常出现 11~15 格的相邻高差(实测 1136/1357 次 grade 拒绝
     * 集中在 edge=0..1)，被 findFirstViolation 的坡度检查直接拒掉，导致
     * 绕行"明明绕开了建筑却判失败"、退化成 protected-gap 进入局部修复。
     * 这里仅在候选存在坡度违反时，用已有的 forceSmoothHeightWindow 把它
     * 投影到坡度限制(保留首尾 Y 以维持与原路径的接缝)，平滑后无违反则
     * 采用；否则保持原候选，不改变原有拒绝语义。
     */
    public static List<BlockPos> smoothCandidateGrade(List<BlockPos> candidate) {
        if (candidate == null || candidate.size() < 3) return candidate;
        if (findFirstViolation(candidate) == null) return candidate;
        List<BlockPos> smoothed = forceSmoothHeightWindow(candidate);
        if (smoothed == null || smoothed.size() != candidate.size()) return candidate;
        return findFirstViolation(smoothed) == null ? smoothed : candidate;
    }

    public static int roadBodyRadius() {
        int width = Math.max(1, ConfigService.get().roadAppearance().roadWidth());
        // PathPostProcessor rasterizes the complete width around the centerline.
        // Use the ceiling of the half-width so the far edge of even-width roads
        // is protected as well; this is a collision radius, not extra building
        // padding and remains independent from the 100-block terrain window.
        return Math.max(0, (int) Math.ceil(width / 2.0D));
    }
}
