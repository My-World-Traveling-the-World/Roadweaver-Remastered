/* 文件职责：路径收尾与冲突修复 —— 最终化、按走廊刷新做结构/湖/河的局部修复、构建站序列。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair.ExactStructureRepairOutcome;
import net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccuratePathHeightResolver;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPipeline;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;
import net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicy;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.snapToGrid;
import net.shiroha233.roadweaver.postprocess.SplineHelper;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator.*;
import static net.shiroha233.roadweaver.pathlogic.pathcalc.PathMetricsOps.*;

/**
 * 路径收尾与冲突修复。
 *
 * <h2>它在链路末端</h2>
 * <p>A* / 规划给出粗线之后，这里做「落地前」的收尾：finalizePath 定稿、
 * 按<b>走廊刷新</b>做结构/湖/河的局部修复、最后 buildSegments 把中心线变成站序列。</p>
 *
 * <h2>★ 为什么修复要「刷新走廊」</h2>
 * <p>局部修复会把某一段路挪走，而走廊快照是按旧线算的。不刷新就继续用旧快照，
 * 会在<b>新位置</b>上重复撞到结构 —— 表现为「修了但没修好，来回修」。</p>
 *
 * <h2>★ 最终化的顺序不可换</h2>
 * <p>先结构、再湖、再河：结构是硬的（撞了必须让），湖次之，河最软（可以沿河走）。
 * 反过来的话，河修复挪出来的位置可能又撞上结构。</p>
 *
 * <p>本类是从 1717 行的 {@code RoadPathCalculator} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class PathFinalizeOps {
    public static PathCalculationResult calculateFromPlannedPath(List<BlockPos> plannedPath,
                                                                  int width,
                                                                  ServerLevel level,
                                                                  TerrainSamplingCache cache,
                                                                  RoadGenerationConfig cfg,
                                                                  StructureConnection connection) {
        if (plannedPath == null || plannedPath.isEmpty()) {
            return PathCalculationResult.failure();
        }
        PathfindingCostConfig pathCfg = cfg != null ? cfg.pathfinding().snapshot() : null;
        if (pathCfg != null) pathCfg.sanitize();
        return finalizePath(plannedPath, width, level, cache, null, pathCfg, connection);
    }

    public static PathCalculationResult calculateFromPlannedPath(List<BlockPos> plannedPath,
                                                                  int width,
                                                                  ServerLevel level,
                                                                  TerrainSamplingCache cache) {
        return calculateFromPlannedPath(plannedPath, width, level, cache, null, null);
    }

    public static PathCalculationResult finalizePath(List<BlockPos> rawPath,
                                                      int width,
                                                      ServerLevel level,
                                                      TerrainSamplingCache cache,
                                                      PathTerrainField terrain,
                                                      PathfindingCostConfig pathCfg,
                                                      StructureConnection connection) {
        if (rawPath == null || rawPath.size() < 2) {
            return PathCalculationResult.failure();
        }
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        InitialGenerationProgressTracker.advanceCurrentRoadTask(50, "road_exact_height");
        long exactHeightStartedAt = System.nanoTime();
        List<BlockPos> upstreamFallbackPath = AccuratePathHeightResolver.resolve(rawPath, terrain, accurate);
        DetailedRoadGenerationStats.recordExactHeight(System.nanoTime() - exactHeightStartedAt);
        InitialGenerationProgressTracker.advanceCurrentRoadTask(220, "road_grade_repair");
        List<BlockPos> finalPath = upstreamFallbackPath;
        if (pathCfg != null) {
            finalPath = repairAndRecordGrade(
                    finalPath, level, accurate, pathCfg, connection);
            finalPath = repairStructureIntersectionsWithCorridorRefresh(
                    finalPath, level, accurate, pathCfg, connection);
            List<BlockPos> beforeWater = finalPath;
            List<BlockPos> afterRiver = repairRiverIntersectionsWithCorridorRefresh(
                    finalPath, width, level, accurate, pathCfg, connection);
            List<BlockPos> afterLake = afterRiver == null ? null
                    : repairLakeIntersectionsWithCorridorRefresh(
                    afterRiver, width, level, accurate, pathCfg, connection);
            finalPath = afterLake;
            // The closing structure pass only matters when a water repair
            // actually moved the path: both water repairs return their input
            // instance when they were no-ops (leaving the snapshot untouched
            // too), and the structure repair is deterministic for an unchanged
            // path + snapshot, so re-running it would return the same result.
            if (finalPath != null && (afterRiver != beforeWater || afterLake != afterRiver)) {
                finalPath = repairStructureIntersectionsWithCorridorRefresh(
                        finalPath, level, accurate, pathCfg, connection);
            }
            if (finalPath == null) {
                LOGGER.warn("RoadWeaver local structure/lake detours exhausted; rejecting connection for topology fallback connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
                return PathCalculationResult.failure();
            }
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(600, "road_segment_build");
        long segmentBuildStartedAt = System.nanoTime();
        List<RoadSegmentPlacement> segments = buildSegments(
                finalPath, width, level, cache, terrain, accurate, connection);
        DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
        if (connection != null && segments != null) {
            segments = stopAtEndpointBuilding(level, connection, segments);
            if (segments == null) return PathCalculationResult.failure();
            segments = stopAtVillageBuilding(level, connection, segments);
            if (segments == null) return PathCalculationResult.failure();
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(760, "road_construction_height");
        if (segments != null && pathCfg != null && segments.size() >= 3) {
            long constructionSetupStartedAt = System.nanoTime();
            List<BlockPos> constructionCenters = new ArrayList<>(segments.size());
            for (RoadSegmentPlacement segment : segments) {
                constructionCenters.add(segment.middlePos());
            }
            DetailedRoadGenerationStats.recordConstructionSetup(System.nanoTime() - constructionSetupStartedAt);
            exactHeightStartedAt = System.nanoTime();
            List<BlockPos> constructionTerrain = AccuratePathHeightResolver.resolve(
                    constructionCenters, null, accurate);
            DetailedRoadGenerationStats.recordExactHeight(System.nanoTime() - exactHeightStartedAt);
            InitialGenerationProgressTracker.advanceCurrentRoadTask(840, "road_construction_repair");
            List<BlockPos> repairedConstruction = repairAndRecordGrade(
                    constructionTerrain, level, accurate, pathCfg, connection);
            repairedConstruction = repairStructureIntersectionsWithCorridorRefresh(
                    repairedConstruction, level, accurate, pathCfg, connection);
            List<BlockPos> beforeConstructionWater = repairedConstruction;
            List<BlockPos> constructionAfterRiver = repairRiverIntersectionsWithCorridorRefresh(
                    repairedConstruction, width, level, accurate, pathCfg, connection);
            List<BlockPos> constructionAfterLake = constructionAfterRiver == null ? null
                    : repairLakeIntersectionsWithCorridorRefresh(
                    constructionAfterRiver, width, level, accurate, pathCfg, connection);
            repairedConstruction = constructionAfterLake;
            // Same no-op short-circuit as the path-level chain above: only
            // re-run the closing structure pass when water actually moved it.
            if (repairedConstruction != null
                    && (constructionAfterRiver != beforeConstructionWater
                    || constructionAfterLake != constructionAfterRiver)) {
                repairedConstruction = repairStructureIntersectionsWithCorridorRefresh(
                        repairedConstruction, level, accurate, pathCfg, connection);
            }
            if (repairedConstruction == null) {
                LOGGER.warn("RoadWeaver rejected construction-center route after exact lake/structure repair connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
                return PathCalculationResult.failure();
            }
            if (repairedConstruction != null && !repairedConstruction.equals(constructionTerrain)) {
                segmentBuildStartedAt = System.nanoTime();
                segments = buildSegments(
                        repairedConstruction, width, level, cache, null, accurate, connection);
                DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
                if (connection != null && segments != null) {
                    segments = stopAtEndpointBuilding(level, connection, segments);
                    if (segments == null) return PathCalculationResult.failure();
                    segments = stopAtVillageBuilding(level, connection, segments);
                    if (segments == null) return PathCalculationResult.failure();
                }
            }
        }
        if (segments == null && upstreamFallbackPath != null && upstreamFallbackPath.size() >= 2
                && connection == null) {
            List<BlockPos> fallback = pathCfg == null
                    ? upstreamFallbackPath
                    : repairAndRecordGrade(
                            upstreamFallbackPath, level, accurate, pathCfg, connection);
            segmentBuildStartedAt = System.nanoTime();
            segments = buildSegments(fallback, width, level, cache, terrain, accurate, null);
            DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
            if (segments != null) {
                LOGGER.warn("RoadWeaver accepted path-level upstream fallback while retaining current construction safety connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
            }
        }
        if (segments == null) {
            return PathCalculationResult.failure();
        }
        if (connection != null
                && intersectsPreparedRoute(level, connection, segments)
                && intersectsPreparedRouteWithUnresolvedStructure(level, connection, segments)) {
            LOGGER.error("RoadWeaver refused to finalize a route whose exact footprint still intersects a protected structure connection={}->{}",
                    connection.from(), connection.to());
            return PathCalculationResult.failure();
        }
        if (connection != null
                && intersectsPreparedRoute(level, connection, segments)) {
            LOGGER.info("RoadWeaver retained verified structure protection gaps connection={}->{}; construction will skip only overlapping columns",
                    connection.from(), connection.to());
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(980, "road_finalizing");
        return new PathCalculationResult(segments, terrain);
    }

    public static List<BlockPos> repairStructureIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null) return path;
        var server = level == null ? null : level.getServer();
        if (server == null) return null;
        if (server.isSameThread()) {
            LOGGER.error("RoadWeaver refused synchronous corridor resolution on the server thread connection={}->{}",
                    connection.from(), connection.to());
            return null;
        }

        // Local repair uses the same player-selected precision as the route.
        // RoutePrecisionRetryPolicy.allowsLocalDetour(step) is true for every
        // sanitized precision, so the old "defer at coarse step" gate here was
        // unreachable and has been removed; local detours always run. The
        // first radius iteration refreshes the snapshot for this path below,
        // so no separate pre-loop refresh is needed (working starts as a copy
        // of path and the corridor cache deduplicates identical content).
        List<BlockPos> working = List.copyOf(path);
        // 0.8.340：长度闸门的基准 —— 进入修复那一刻的原长。后续每一轮都用它比对，
        // 避免「以上一轮绕行结果为基准」导致闸门本身也被逐级放大而失效。
        final int detourBaselineStations = Math.max(1, working.size());
        try {
            BlockPos previousRejectedCenter = null;
            int previousRejectedRadius = -1;
            int identicalConflictStreak = 0;
            for (int localRepairRadius : LOCAL_STRUCTURE_DETOUR_BLOCKS) {
                InitialGenerationProgressTracker.advanceCurrentRoadTask(
                        360, "road_structure_local_detour_" + localRepairRadius);
                LOGGER.info("RoadWeaver starting single building local repair radius={} fine-precision connection={}->{}",
                        localRepairRadius, connection.from(), connection.to());
                refreshPlanningSnapshot(level, connection, working);
                ExactStructureRepairOutcome outcome =
                        StructureRepair.repairStructureIntersectionsAtExactClearanceDetailed(
                                working, level, accurate, pathCfg, connection, localRepairRadius);
                List<BlockPos> exact = outcome.path();
                if (exact != null && !exact.isEmpty()) {
                    // 0.8.340 长度闸门：绕行结果若比进入修复时的原长超出
                    // LOCAL_DETOUR_MAX_LENGTH_RATIO 倍，说明这一轮是"越绕越远"的
                    // 累积膨胀（真机 624 -> 3042 站），继续放大半径只会更长。
                    // 直接停止升级并保留原路 + 保护缺口（既有兜底路径），
                    // 避免后面还要为这条超长路重算坡度、验证数万列地形再判失败。
                    if (exact.size() > detourBaselineStations * LOCAL_DETOUR_MAX_LENGTH_RATIO) {
                        LOGGER.warn("RoadWeaver building local detour stopped early: detour would grow route "
                                        + "beyond {}x baseline ({} -> {} stations, ratio={}) at radius={}; "
                                        + "retaining route with building protection gap connection={}->{}",
                                LOCAL_DETOUR_MAX_LENGTH_RATIO, detourBaselineStations, exact.size(),
                                String.format("%.2f", exact.size() / (double) detourBaselineStations),
                                localRepairRadius, connection.from(), connection.to());
                        break;
                    }
                    refreshPlanningSnapshot(level, connection, exact);
                    if (!intersectsPreparedPath(level, connection, exact, accurate)) {
                        LOGGER.info("RoadWeaver accepted single building local repair radius={} fine-precision connection={}->{}",
                                localRepairRadius, connection.from(), connection.to());
                        return exact;
                    }
                    working = List.copyOf(exact);
                }
                if (!intersectsPreparedPath(level, connection, working, accurate)) {
                    LOGGER.info("RoadWeaver accepted verified structure protection gap after building radius={} connection={}->{}",
                            localRepairRadius, connection.from(), connection.to());
                    return working;
                }
                // Early gap retention: the same conflict centre failed again, so a
                // larger circle will not converge either. Stop escalating instead of
                // hard-trying every radius.
                //
                // Endpoint-approach conflicts (within 24 stations of either route end,
                // e.g. the road spiralling into a village) legitimately need the larger
                // circles to switchback along the structure boundary — but only while
                // the conflict centre keeps MOVING along that boundary. A centre that
                // stays put is a geometric dead end (the endpoint itself sits on the
                // structure); escalating it just burns the whole radius ladder.
                BlockPos rejectedCenter = outcome.lastRejectedCenter();
                int rejectedIndex = outcome.lastRejectedIndex();
                boolean endpointApproach = rejectedIndex >= 0
                        && (rejectedIndex <= ENDPOINT_APPROACH_STATIONS
                        || rejectedIndex >= working.size() - 1 - ENDPOINT_APPROACH_STATIONS);
                if (rejectedCenter != null && previousRejectedCenter != null
                        && rejectedCenter.getX() == previousRejectedCenter.getX()
                        && rejectedCenter.getZ() == previousRejectedCenter.getZ()) {
                    identicalConflictStreak++;
                } else {
                    identicalConflictStreak = 1;
                }
                if (rejectedCenter != null
                        && RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(identicalConflictStreak, endpointApproach)) {
                    LOGGER.warn("RoadWeaver building local detour stopped early: identical conflict center=({},{}) "
                                    + "failed at {} consecutive radius steps (last {} -> {}); endpointApproach={}; "
                                    + "retaining route with building protection gap connection={}->{}",
                            rejectedCenter.getX(), rejectedCenter.getZ(), identicalConflictStreak,
                            previousRejectedRadius, localRepairRadius, endpointApproach,
                            connection.from(), connection.to());
                    break;
                }
                previousRejectedCenter = rejectedCenter;
                previousRejectedRadius = localRepairRadius;
                // Gameplay-pressure yield: escalating to a larger detour circle
                // is low priority while the player is moving; retain the gap
                // instead of competing with chunk loading. Initial generation
                // (loading screen) is unaffected.
                if (!InitialGenManager.isActive()
                        && RuntimeLoadPolicy.isGameplayForegroundProtected()) {
                    LOGGER.info("RoadWeaver deferred building local detour escalation under gameplay pressure "
                                    + "completedRadius={} connection={}->{}",
                            localRepairRadius, connection.from(), connection.to());
                    break;
                }
            }
            // Every detour radius was exhausted. Keep the original path and
            // leave a building protection gap instead of killing the route.
            LOGGER.warn("RoadWeaver building local detours exhausted; retaining route with building protection gap connection={}->{}",
                    connection.from(), connection.to());
            return working;
        } catch (RuntimeException failure) {
            LOGGER.error("RoadWeaver failed to resolve the exact structure corridor connection={}->{}",
                    connection.from(), connection.to(), failure);
            throw failure;
        }
    }

    public static List<BlockPos> repairLakeIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            int width,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null) return path;
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if ((!streams.isAvailable() && !streams.isFailed())
                || !AccurateGradeRepairPlanner.intersectsLakeConstraints(
                path, width, accurate, streams)) return path;

        List<BlockPos> working = List.copyOf(path);
        PathfindingCostConfig finePathCfg = pathCfg.withAStarStep(2);
        Set<Long> routeSignatures = new HashSet<>();
        routeSignatures.add(routeSignature(working));
        for (int detour : LOCAL_LAKE_DETOUR_BLOCKS) {
            for (int cycle = 0; cycle < MAX_EXACT_CONSTRAINT_CYCLES; cycle++) {
                if (!AccurateGradeRepairPlanner.intersectsLakeConstraints(
                        working, width, accurate, streams)) return working;
                List<BlockPos> repaired = LakeRiverRepair.repairLakeIntersections(
                        working, level, accurate, finePathCfg, connection, streams, width, detour);
                if (repaired == null || repaired.isEmpty() || repaired.equals(working)) break;
                long signature = routeSignature(repaired);
                if (!routeSignatures.add(signature)) break;
                working = List.copyOf(repaired);

                refreshPlanningSnapshot(level, connection, working);
                working = repairAndRecordGrade(working, level, accurate, pathCfg, connection);
                working = repairStructureIntersectionsWithCorridorRefresh(
                        working, level, accurate, pathCfg, connection);
                if (working == null || working.isEmpty()) return null;
                signature = routeSignature(working);
                if (!routeSignatures.add(signature)
                        && AccurateGradeRepairPlanner.intersectsLakeConstraints(
                        working, width, accurate, streams)) break;
            }
            if (!AccurateGradeRepairPlanner.intersectsLakeConstraints(
                    working, width, accurate, streams)) return working;
        }
        // Lake detours are exhausted. Bridge first; when no bridge is
        // buildable keep the original corridor and let the final water
        // validator mark the exact wet stations as a protected gap.
        LOGGER.warn("RoadWeaver exhausted local lake detours; retaining route for bridge/water gap fallback connection={}->{}",
                connection.from(), connection.to());
        return working;
    }

    public static List<BlockPos> repairRiverIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            int width,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null || pathCfg == null) return path;
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if ((!streams.isAvailable() && !streams.isFailed())
                || !AccurateGradeRepairPlanner.intersectsRiver(path, width, streams)) return path;

        LOGGER.info("RoadWeaver starting river local repair window={} connection={}->{}",
                RIVER_LOCAL_DETOUR_BLOCKS, connection.from(), connection.to());
        List<BlockPos> repaired = LakeRiverRepair.repairRiverIntersections(
                path, level, accurate, pathCfg.withAStarStep(1), connection, streams,
                width, RIVER_LOCAL_DETOUR_BLOCKS);
        if (repaired != null && !repaired.equals(path)
                && !AccurateGradeRepairPlanner.intersectsRiver(repaired, width, streams)) {
            refreshPlanningSnapshot(level, connection, repaired);
            LOGGER.info("RoadWeaver accepted river local repair connection={}->{}",
                    connection.from(), connection.to());
            return repaired;
        }
        // Keep the original path. FinalRoadWaterValidator will mark the exact
        // failing station as PROTECTED_GAP instead of rejecting unrelated road
        // sections or forcing an endpoint candidate solely for a short river.
        LOGGER.warn("RoadWeaver river local repair unavailable; retaining route for water protection gap "
                        + "connection={}->{}", connection.from(), connection.to());
        return path;
    }

    /**
     * Late water fallback used after span extraction.  This is intentionally
     * separate from normal path finalization so valid short crossings and long
     * bridges are not proactively rerouted around their rivers.
     */
    public static List<RoadSegmentPlacement> repairRiverRouteSegments(
            List<RoadSegmentPlacement> existing,
            int width,
            ServerLevel level,
            TerrainSamplingCache cache,
            RoadGenerationConfig cfg,
            StructureConnection connection) {
        if (existing == null || existing.size() < 3 || level == null || cache == null
                || cfg == null || connection == null) return existing;
        List<BlockPos> path = new ArrayList<>(existing.size());
        for (RoadSegmentPlacement segment : existing) {
            if (segment == null || segment.middlePos() == null) return existing;
            path.add(segment.middlePos());
        }
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if (!streams.isAvailable()
                || !AccurateGradeRepairPlanner.intersectsRiver(path, width, streams)) return existing;
        PathfindingCostConfig pathCfg = cfg.pathfinding().snapshot();
        pathCfg.setAStarStep(1);
        pathCfg.sanitize();
        List<BlockPos> repaired = LakeRiverRepair.repairRiverIntersections(
                path, level, cache.getAccurateSampler(level), pathCfg, connection, streams,
                width, RIVER_LOCAL_DETOUR_BLOCKS);
        if (repaired == null || repaired.equals(path)
                || AccurateGradeRepairPlanner.intersectsRiver(repaired, width, streams)) return existing;
        refreshPlanningSnapshot(level, connection, repaired);
        List<RoadSegmentPlacement> rebuilt = buildSegments(
                repaired, width, level, cache, null, cache.getAccurateSampler(level), connection);
        if (rebuilt == null || rebuilt.size() < 3) return existing;
        LOGGER.info("RoadWeaver rebuilt road after river local repair connection={}->{} nodes={} -> {}",
                connection.from(), connection.to(), existing.size(), rebuilt.size());
        return rebuilt;
    }

    public static void refreshPlanningSnapshot(ServerLevel level,
                                                StructureConnection connection,
                                                List<BlockPos> path) {
        long startedAt = System.nanoTime();
        try {
            PlannedPathKey key = PlannedPathKey.of(connection);
            int fingerprint = pathFingerprint(path);
            SnapshotWaitEntry cached = SNAPSHOT_WAIT_CACHE.get(key);
            if (cached != null && cached.pathFingerprint() == fingerprint
                    && cached.future().isDone() && !cached.future().isCompletedExceptionally()) {
                // Same connection, byte-identical path, snapshot already
                // prepared: skip the redundant prediction/sampling round that
                // every detour radius and lake cycle used to repeat.
                return;
            }
            InitialGenerationProgressTracker.reportRoadTaskWaiting("STRUCTURE_SNAPSHOT_FUTURE");
            CompletableFuture<Void> future =
                    ensurePlanningSnapshotForPathAsync(level, connection, path);
            future.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    SNAPSHOT_WAIT_CACHE.put(key, new SnapshotWaitEntry(fingerprint, future));
                }
            });
            awaitPlanningSnapshot(future);
        } finally {
            InitialGenerationProgressTracker.advanceCurrentRoadTask(370, "road_structure_snapshot_ready");
            DetailedRoadGenerationStats.recordStructureCorridor(System.nanoTime() - startedAt);
        }
    }
}
