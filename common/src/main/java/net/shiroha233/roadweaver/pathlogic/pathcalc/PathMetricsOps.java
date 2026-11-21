/* 文件职责：路径度量与小工具 —— 指纹、快照等待超时、坡度记录、最近站、直连 A*。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.snapToGrid;
import net.shiroha233.roadweaver.postprocess.SplineHelper;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator.*;
import static net.shiroha233.roadweaver.pathlogic.pathcalc.PathFinalizeOps.*;

/**
 * 路径度量与小工具。
 *
 * <h2>指纹是做什么的</h2>
 * <p>{@code pathFingerprint} 把一条路径压成一个可比较的值，用来判断
 * 「这次算出来的路与上次是不是同一条」—— 缓存、快照复用、重复修复检测都依赖它。</p>
 *
 * <h2>★ 快照等待必须有超时</h2>
 * <p>{@code awaitPlanningSnapshot} 等的是结构避让的规划快照。没有超时就会在
 * 快照迟迟不来的情况下永久挂住 —— 而且这个等待发生在**很热的路径**上。</p>
 *
 * <h2>★ 直连 A* 是「有没有路」的快速判定</h2>
 * <p>{@code calculateAStarRoadPathDetailed} 跑的是最朴素的 A*，只用来看
 * 「这两点之间到底通不通」。**它不做避让、不做坡度优化**，所以不能拿它的结果
 * 当最终路线 —— 只在「连通性」这个判断上用。</p>
 *
 * <p>本类是从 1717 行的 {@code RoadPathCalculator} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class PathMetricsOps {
    private static final boolean LAND_RELAX_SHADOW =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.landRelaxShadow"));

    public static int pathFingerprint(List<BlockPos> path) {
        return path == null ? 0 : path.hashCode();
    }

    /** 快照预测等待有上下界，与采样等待区分，避免无限阻塞。 */
    public static long snapshotWaitTimeoutMs() {
        try {
            long samplingTimeoutMs = Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
            return Math.max(5_000L, Math.min(120_000L, samplingTimeoutMs * 10L));
        } catch (Throwable ignored) {
            return 30_000L;
        }
    }

    public static void awaitPlanningSnapshot(CompletableFuture<Void> future) {
        try {
            // Bounded wait: predictions run on the dedicated RW-Snapshot pool
            // and always complete, so this only fires if that lane is wedged.
            // Failing the route (topology fallback) beats hanging the road
            // worker forever on an unsafe "proceed without snapshot".
            future.get(snapshotWaitTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            future.cancel(true);
            throw new CancellationException("Road structure-corridor prediction timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("Road structure-corridor wait interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Road structure-corridor preparation failed", cause);
        }
    }

    public static List<BlockPos> repairAndRecordGrade(List<BlockPos> path,
                                                       ServerLevel level,
                                                       AccurateHeightSampler accurate,
                                                       PathfindingCostConfig pathCfg,
                                                       StructureConnection connection) {
        long startedAt = System.nanoTime();
        try {
            // During local repair the initial pass already recorded the
            // construction-height conflict. Reroute ONLY that bounded 50-block
            // circle instead of re-running the whole-path grade repair. The
            // reroute merges on success and keeps the original route on failure.
            BlockPos hinted = consumeConstructionConflictHint(connection);
            if (hinted != null && path != null && path.size() >= 3) {
                int segment = nearestSegmentIndex(path, hinted);
                if (segment >= 0) {
                    LOGGER.info("【已读取局部修复的持久冲突提示】 " + "RoadWeaver local-repair conflict-hint consumed connection={}->{} hint={},{} segment={} nodes={}",
                            connection == null ? "?" : connection.from(),
                            connection == null ? "?" : connection.to(),
                            hinted.getX(), hinted.getZ(), segment, path.size());
                    ArrayList<BlockPos> rerouted = new ArrayList<>(
                            AccurateGradeRepairPlanner.rerouteConstructionHeightConflict(
                                    path, segment, level, accurate, pathCfg, connection));
                    if (!rerouted.equals(path)) {
                        LOGGER.info("【冲突局部绕行已拼回原路线】 " + "RoadWeaver local-repair conflict reroute MERGED segment={} nodes={} -> {}",
                                segment, path.size(), rerouted.size());
                        return List.copyOf(rerouted);
                    }
                    LOGGER.warn("【局部绕行未避开冲突，保留原保护缺口】 " + "RoadWeaver local-repair conflict reroute could not bypass, keeping original gap "
                                    + "connection={}->{} hint={},{} segment={}",
                            connection == null ? "?" : connection.from(),
                            connection == null ? "?" : connection.to(),
                            hinted.getX(), hinted.getZ(), segment);
                }
            }
            if (hinted != null) {
                LOGGER.info("【冲突提示没有可用局部段，转入整路修复】 " + "RoadWeaver local-repair hint found but no usable segment, falling back to whole-path repair "
                                + "connection={}->{} hint={},{}",
                        connection == null ? "?" : connection.from(),
                        connection == null ? "?" : connection.to(),
                        hinted.getX(), hinted.getZ());
            }
            LOGGER.info("【开始整路纵坡修复】 " + "RoadWeaver whole-path grade repair connection={}->{} nodes={}",
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to(),
                    path == null ? 0 : path.size());
            return AccurateGradeRepairPlanner.repair(path, level, accurate, pathCfg, connection);
        } finally {
            DetailedRoadGenerationStats.recordGradeRepair(System.nanoTime() - startedAt);
        }
    }

    public static int nearestSegmentIndex(List<BlockPos> path, BlockPos target) {
        if (path == null || target == null) return -1;
        int nearest = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos pos = path.get(i);
            if (pos == null) continue;
            long dx = (long) pos.getX() - target.getX();
            long dz = (long) pos.getZ() - target.getZ();
            long dist = dx * dx + dz * dz;
            if (dist < best) {
                best = dist;
                nearest = i;
            }
        }
        return nearest;
    }

    public static long routeSignature(List<BlockPos> path) {
        long hash = 0xcbf29ce484222325L;
        if (path == null) return hash;
        for (BlockPos pos : path) {
            if (pos == null) continue;
            hash ^= pos.asLong();
            hash *= 0x100000001b3L;
        }
        hash ^= path.size();
        return hash;
    }

    /** 几何观测用的连接标识（与其它 AB 日志同口径）。 */
    private static String geometryTag(StructureConnection connection) {
        return connection == null ? "?" : connection.from() + "->" + connection.to();
    }

    /** 0.8.318：成段失败的细分原因（纯观测），见 {@link #consumeRebuildFailureReason()}。 */
    public static final ThreadLocal<String> LAST_SEGMENT_FAILURE = new ThreadLocal<>();

    public static String consumeSegmentFailureReason() {
        String reason = LAST_SEGMENT_FAILURE.get();
        LAST_SEGMENT_FAILURE.remove();
        return reason == null ? "unknown" : reason;
    }

    public static List<RoadSegmentPlacement> buildSegments(List<BlockPos> path,
                                                             int width,
                                                             ServerLevel level,
                                                             TerrainSamplingCache cache,
                                                             PathTerrainField terrain,
                                                             AccurateHeightSampler accurate,
                                                             StructureConnection connection) {
        // ── 几何漂移观测（批 5 前置，默认关闭）────────────────────────────
        // 入参 path 是**弹性带平滑之后**的路径；下面 PathPostProcessor 会再平滑一遍。
        // 两处各打一份指标，才能回答「谁把路形改了多少」。
        // 开关：-Droadweaver.geometryAb=on
        boolean observeDrift = GeometryDriftObserver.enabled();
        boolean observeRelax = LAND_RELAX_SHADOW && connection != null;
        String geometryTag = observeDrift || observeRelax ? geometryTag(connection) : null;
        GeometryDriftObserver.Metrics bandMetrics = null;
        if (observeDrift) {
            bandMetrics = GeometryDriftObserver.measure(path);
            GeometryDriftObserver.observe(geometryTag, "band", path);
        }
        List<RoadSegmentPlacement> curved = PathPostProcessor.processWithLandRelaxShadow(
                path,
                width,
                level,
                cache,
                terrain,
                PathPostProcessor.configuredBridgeMinWaterDepth(),
                net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode.CATMULL_ROM,
                accurate,
                observeRelax ? (base, shadow) -> reportLandRelax(geometryTag, level, connection, base, shadow) : null);
        if (observeDrift) {
            GeometryDriftObserver.Metrics postMetrics =
                    GeometryDriftObserver.measure(GeometryDriftObserver.centersOf(curved));
            GeometryDriftObserver.observe(geometryTag, "post",
                    GeometryDriftObserver.centersOf(curved));
            GeometryDriftObserver.report(geometryTag, bandMetrics, postMetrics);
        }
        return chooseGeometry(curved, () -> PathPostProcessor.processUnrelaxedLinear(
                        path, width, level, cache, terrain,
                        PathPostProcessor.configuredBridgeMinWaterDepth(), accurate),
                connection,
                new GeometryProtection(segments -> intersectsPreparedRoute(level, connection, segments),
                        segments -> protectedGapSpans(level, connection, segments).size(),
                        PathMetricsOps::preferUnsmoothedOnProtectionGap));
    }

    private static void reportLandRelax(String tag, ServerLevel level, StructureConnection connection,
                                        List<RoadSegmentPlacement> base, List<RoadSegmentPlacement> shadow) {
        var baseCenters = GeometryDriftObserver.centersOf(base);
        var shadowCenters = GeometryDriftObserver.centersOf(shadow);
        Set<Long> before = occupiedColumns(base), after = occupiedColumns(shadow);
        long added = after.stream().filter(p -> !before.contains(p)).count();
        long removed = before.stream().filter(p -> !after.contains(p)).count();
        // 比较真正铺装的唯一列与站距；站数相同也可能挤进保护区或漏一列。
        LOGGER.info("【第二次陆地松弛的影子产物对照】 " + "[LAND-RELAX-SHADOW] tag={} changed={} base={} withRelaxProtection={} "
                        + "shadow={} withoutRelaxProtection={} maxStep={}->{} columns={}->{} added={} removed={}",
                tag, !base.equals(shadow), GeometryDriftObserver.measure(baseCenters),
                describePreparedProtection(level, connection, base), GeometryDriftObserver.measure(shadowCenters),
                describePreparedProtection(level, connection, shadow),
                maxStep(baseCenters), maxStep(shadowCenters), before.size(), after.size(), added, removed);
    }

    private static Set<Long> occupiedColumns(List<RoadSegmentPlacement> road) {
        Set<Long> columns = new HashSet<>();
        for (var station : road) {
            for (var pos : station.positions()) columns.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
        }
        return columns;
    }

    private static int maxStep(List<BlockPos> centers) {
        int max = 0;
        for (int i = 1; i < centers.size(); i++) {
            var a = centers.get(i - 1);
            var b = centers.get(i);
            max = Math.max(max, Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())));
        }
        return max;
    }

    // 保护判据在平滑之后执行，离线可用真实路面列验证哪种几何能被下发。
    record GeometryProtection(Predicate<List<RoadSegmentPlacement>> intersects,
                              ToIntFunction<List<RoadSegmentPlacement>> gapCount,
                              BooleanSupplier preferUnsmoothed) {}

    static List<RoadSegmentPlacement> chooseGeometry(
            List<RoadSegmentPlacement> curved, Supplier<List<RoadSegmentPlacement>> unrelaxed,
            StructureConnection connection, GeometryProtection protection) {
        if (curved == null || curved.size() < 2) {
            curved = null;
            LAST_SEGMENT_FAILURE.set("curved_null");
        }
        if (curved != null && (connection == null
                || !protection.intersects().test(curved))) {
            return curved;
        }
        if (curved != null && connection != null
                && protection.gapCount().applyAsInt(curved) > 0) {
            // 0.8.316：平滑（Catmull-Rom）会把已经绕开建筑的折线推进建筑里——真机
            // "retained route with internal building protection gap" 全部出现在平滑路径上。
            // 旧代码在这里直接保留带缺口的平滑路径，连"不做平滑的直线版本"都没算过。
            // 这里补算一次未平滑版本：只要它的保护缺口更少就换用它（缺口 = 路面被建筑
            // 打断，比路稍显生硬严重得多），从源头减少交给局部修复的缺口数。
            int curvedGaps = protection.gapCount().applyAsInt(curved);
            if (protection.preferUnsmoothed().getAsBoolean()) {
                List<RoadSegmentPlacement> unsmoothed = unrelaxed.get();
                if (unsmoothed != null && unsmoothed.size() >= 3) {
                    int unsmoothedGaps = protection.gapCount().applyAsInt(unsmoothed);
                    if (unsmoothedGaps < curvedGaps) {
                        // 0.8.324：未平滑版是原始 A* 走廊，可能明显更长、更折。真机
                        // 20260909 16.15 存档 (288,-256)->(880,208)：缺口 5->0，但站点
                        // 从 1217 涨到 2067（+70%），8 次局部 splice 的成果全被丢弃，
                        // 路面上留下 61 处来回折角。只有长度增幅不超过阈值才换用它。
                        if (unsmoothed.size() <= Math.round(curved.size() * UNSMOOTHED_MAX_LENGTH_RATIO)) {
                            LOGGER.info("【未平滑路线保护缺口更少，采用未平滑产物】 " + "RoadWeaver preferred unsmoothed route: protection gaps {} -> {} "
                                            + "curvedSegments={} unsmoothedSegments={} connection={}->{}",
                                    curvedGaps, unsmoothedGaps, curved.size(), unsmoothed.size(),
                                    connection.from(), connection.to());
                            return unsmoothed;
                        }
                        LOGGER.info("【未平滑路线过长，保留平滑产物】 " + "RoadWeaver kept smoothed route: unsmoothed variant too long "
                                        + "protection gaps {} -> {} curvedSegments={} unsmoothedSegments={} "
                                        + "connection={}->{}",
                                curvedGaps, unsmoothedGaps, curved.size(), unsmoothed.size(),
                                connection.from(), connection.to());
                    }
                }
            }
            LOGGER.warn("【保留含内部建筑保护缺口的路线】 " + "RoadWeaver retained route with internal building protection gap gaps={} connection={}->{}",
                    curvedGaps, connection.from(), connection.to());
            return curved;
        }

        List<RoadSegmentPlacement> linear = unrelaxed.get();
        if (linear == null || linear.size() < 2) {
            LAST_SEGMENT_FAILURE.set("linear_null");
        }
        if (linear != null && linear.size() >= 2
                && !protection.intersects().test(linear)) {
            LOGGER.info("【为保护建筑保留未平滑路线】 " + "RoadWeaver retained unsmoothed route to preserve a structure connection={}->{}",
                    connection.from(), connection.to());
            return linear;
        }
        if (linear != null && linear.size() >= 3 && connection != null
                && protection.gapCount().applyAsInt(linear) > 0) {
            LOGGER.warn("【保留含内部建筑保护缺口的线性路线】 " + "RoadWeaver retained linear route with internal building protection gap connection={}->{}",
                    connection.from(), connection.to());
            return linear;
        }
        LAST_SEGMENT_FAILURE.set("still_intersects_protected_structure");
        LOGGER.warn("【后处理后仍侵入保护建筑，拒绝路线】 " + "RoadWeaver rejected post-processed route that still intersects a protected structure connection={}->{}",
                connection.from(), connection.to());
        return null;
    }

    /** 0.8.316：平滑路径出现保护缺口时，是否先试未平滑版本并择优。 */
    public static boolean preferUnsmoothedOnProtectionGap() {
        try {
            return ConfigService.get().planning().preferUnsmoothedOnProtectionGap();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static int searchMargin(BlockPos start, BlockPos end) {
        int distance = Math.abs(start.getX() - end.getX()) + Math.abs(start.getZ() - end.getZ());
        return Math.min(configuredPlanMaxEdgeLenBlocks(), Math.max(512, distance));
    }

    /**
     * 0.8.421：搜索外扩上限跟随「主连边长度上限」配置（默认 2048，与出厂常量一致）。
     * 读不到配置（离线/测试环境）时回落到常量，不抛异常。
     */
    public static int configuredPlanMaxEdgeLenBlocks() {
        try {
            return ConfigService.get().planning().planMaxEdgeLenBlocks();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;
        }
    }

    public static List<BlockPos> lowerProfileToSafeFill(List<BlockPos> profile, int[] minimumTerrain, boolean[] bypass) {
        if (profile == null || profile.size() < 3 || minimumTerrain == null
                || minimumTerrain.length != profile.size()) return profile;
        int[] heights = new int[profile.size()];
        for (int i = 0; i < profile.size(); i++) heights[i] = profile.get(i).getY();
        for (int i = 1; i < heights.length - 1; i++) {
            if (bypass == null || !bypass[i]) {
                if (minimumTerrain[i] != Integer.MAX_VALUE) {
                    heights[i] = Math.min(heights[i], minimumTerrain[i]);
                }
            }
        }
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < heights.length - 1; i++) {
                int limit = (int) Math.floor(horizontalDistance(profile.get(i - 1), profile.get(i)));
                heights[i] = clamp(heights[i], heights[i - 1] - Math.max(0, limit),
                        heights[i - 1] + Math.max(0, limit));
            }
            for (int i = heights.length - 2; i >= 1; i--) {
                int limit = (int) Math.floor(horizontalDistance(profile.get(i), profile.get(i + 1)));
                heights[i] = clamp(heights[i], heights[i + 1] - Math.max(0, limit),
                        heights[i + 1] + Math.max(0, limit));
            }
        }
        List<BlockPos> result = new ArrayList<>(profile.size());
        for (int i = 0; i < profile.size(); i++) {
            BlockPos source = profile.get(i);
            result.add(new BlockPos(source.getX(), heights[i], source.getZ()));
        }
        return result;
    }

    /** 只把普通深挖站抬至地表挖方限值，已有物理跨度保持原剖面。 */
    public static List<BlockPos> raiseProfileToSafeCut(List<BlockPos> profile, int[] minimumTerrain, boolean[] bypass) {
        if (profile == null || profile.size() < 3 || minimumTerrain == null
                || minimumTerrain.length != profile.size()) return profile;
        List<BlockPos> result = new ArrayList<>(profile.size());
        for (int i = 0; i < profile.size(); i++) {
            BlockPos source = profile.get(i);
            int y = source.getY();
            if (i > 0 && i < profile.size() - 1 && minimumTerrain[i] != Integer.MAX_VALUE
                    && (bypass == null || !bypass[i])) {
                y = Math.max(y, minimumTerrain[i] - RoadConstructionSafety.MAX_CUT_DEPTH);
            }
            result.add(new BlockPos(source.getX(), y, source.getZ()));
        }
        return result;
    }

    public static double horizontalDistance(BlockPos a, BlockPos b) {
        if (a == null || b == null) return 0.0D;
        long dx = (long) b.getX() - a.getX();
        long dz = (long) b.getZ() - a.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    public static List<RoadSegmentPlacement> calculateAStarRoadPath(BlockPos startIn,
                                                                    BlockPos endIn,
                                                                    int width,
                                                                    ServerLevel level,
                                                                    TerrainSamplingCache cache,
                                                                    RoadGenerationConfig cfg) {
        PathCalculationResult result = calculateAStarRoadPathDetailed(startIn, endIn, width, level, cache, cfg);
        try {
            return result.segments();
        } finally {
            if (result.terrain() != null) {
                result.terrain().dispose();
            }
        }
    }

    public static PathCalculationResult calculateAStarRoadPathDetailed(BlockPos startIn,
                                                                       BlockPos endIn,
                                                                       int width,
                                                                       ServerLevel level,
                                                                       TerrainSamplingCache cache,
                                                                       RoadGenerationConfig cfg) {
        return calculateAStarRoadPathDetailed(startIn, endIn, width, level, cache, cfg, null);
    }

    /**
     * 0.9.018：带硬禁行走廊的单条直连重算（D2 平行双桥「方案 C：后者整体改道」）。
     *
     * <p>{@code corridorBan} 为 {@code null} 时与上一个重载逐字节等价——禁令只在
     * 寻路器的邻接扩展里多一次空判断。非 null 时：起点／终点、宽度、外扩边距、
     * 后续的桥隧 span、纵断面与高度修复全部沿用同一条流水线（
     * {@link #finalizePath} 之后的部分），所以改道产物与正常规划的产物同构，
     * 不需要另写一套收尾。</p>
     *
     * <p>外扩边距（{@code searchMargin}，至少 512 格）足以容纳绕开一条
     * 18 格净空走廊所需的侧向偏移，所以禁令不会把路线逼出地形场。</p>
     */
    public static PathCalculationResult calculateAStarRoadPathDetailed(BlockPos startIn,
                                                                       BlockPos endIn,
                                                                       int width,
                                                                       ServerLevel level,
                                                                       TerrainSamplingCache cache,
                                                                       RoadGenerationConfig cfg,
                                                                       net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan) {
        if (startIn == null || endIn == null || level == null || cache == null || cfg == null) {
            return PathCalculationResult.failure();
        }
        PathfindingCostConfig pathCfg = cfg.pathfinding().snapshot();
        pathCfg.sanitize();
        // 仅用显式禁令覆盖快照；普通重载传 null，不能清掉作用域已捕获的改道禁令。
        if (corridorBan != null) pathCfg.setCorridorBan(corridorBan);
        int dGrid = pathCfg.effectivePathGridStep();
        DirectRouteConnections routeConnections = directRouteConnections(startIn, endIn, dGrid);
        StructureConnection planningConnection = routeConnections.planningConnection();
        StructureConnection ownerConnection = routeConnections.ownerConnection();
        BlockPos start = planningConnection.from();
        BlockPos end = planningConnection.to();

        int margin = searchMargin(start, end);
        RoadTerrainPlanningPort.Result planned;
        try {
            planned = TERRAIN_PLANNING.plan(new RoadTerrainPlanningPort.Request(
                    level,
                    new RoadTerrainPlanningPort.Bounds(
                            Math.min(start.getX(), end.getX()) - margin,
                            Math.min(start.getZ(), end.getZ()) - margin,
                            Math.max(start.getX(), end.getX()) + margin,
                            Math.max(start.getZ(), end.getZ()) + margin),
                    List.of(planningConnection),
                    pathCfg));
        } catch (RuntimeException failure) {
            return PathCalculationResult.failure();
        }
        List<BlockPos> rawPath = planned.paths().get(planningConnection);
        if (rawPath == null || rawPath.isEmpty()) {
            return PathCalculationResult.failure();
        }
        // Bulk planning prepares this exact corridor before it publishes a
        // connection. A bounded topology fallback reaches this direct A* path
        // without that bulk stage, so prepare the replacement corridor here
        // before village-boundary clipping or structure repair reads it.
        try {
            awaitPlanningSnapshot(
                    ensurePlanningSnapshotForPathAsync(level, ownerConnection, rawPath));
        } catch (RuntimeException snapshotFailure) {
            LOGGER.warn("【无法为直连路线准备结构走廊】 " + "RoadWeaver could not prepare a structure corridor for direct route connection={}->{}",
                    ownerConnection.from(), ownerConnection.to(), snapshotFailure);
            return PathCalculationResult.failure();
        }
        return finalizePath(rawPath, width, level, cache, null, pathCfg, ownerConnection);
    }

    public static DirectRouteConnections directRouteConnections(BlockPos startIn, BlockPos endIn, int gridStep) {
        int step = Math.max(1, gridStep);
        BlockPos planningStart = new BlockPos(
                snapToGrid(startIn.getX(), step), startIn.getY(), snapToGrid(startIn.getZ(), step));
        BlockPos planningEnd = new BlockPos(
                snapToGrid(endIn.getX(), step), endIn.getY(), snapToGrid(endIn.getZ(), step));
        return new DirectRouteConnections(
                new StructureConnection(planningStart, planningEnd, ConnectionStatus.PLANNED),
                new StructureConnection(startIn, endIn, ConnectionStatus.PLANNED));
    }
}
