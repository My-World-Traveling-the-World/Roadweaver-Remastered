/* 文件职责：路线精化 —— 初始路线与局部窗口的异步精化、预取。 */

package net.shiroha233.roadweaver.planning;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。



import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;

import net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps;

import net.shiroha233.roadweaver.pathlogic.structures.PrepareTiming;

import net.minecraft.world.level.Level;

import net.minecraft.core.BlockPos;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.server.level.ServerPlayer;

import net.minecraft.server.MinecraftServer;

import net.shiroha233.roadweaver.config.ConfigService;

import net.shiroha233.roadweaver.config.ModConfig;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

import net.shiroha233.roadweaver.core.model.ConnectionStatus;

import net.shiroha233.roadweaver.core.model.RoadData;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import net.shiroha233.roadweaver.core.model.RoadSpan;

import net.shiroha233.roadweaver.core.model.SpanType;

import net.shiroha233.roadweaver.core.model.StructureConnection;

import net.shiroha233.roadweaver.core.model.StructureInfo;

import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;

import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;

import net.shiroha233.roadweaver.generation.IdleRoadGenerationService;

import net.shiroha233.roadweaver.map.MapPatchService;

import net.shiroha233.roadweaver.persistence.WorldDataProvider;

import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;

import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;

import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;

import net.shiroha233.roadweaver.planning.path.PlannedPathCache;

import net.shiroha233.roadweaver.planning.path.PlannedPathKey;

import net.shiroha233.roadweaver.planning.path.SharedEndpointPathReuse;

import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPipeline;

import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;

import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;

import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;

import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;

import net.shiroha233.roadweaver.network.ServerMapPlanningActivityBridge;

import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import net.shiroha233.roadweaver.search.StructureIndexService;

import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;

import net.shiroha233.roadweaver.pathlogic.core.Road;

import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;

import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;

import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;

import net.shiroha233.roadweaver.util.ComputeService;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.*;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ConcurrentHashMap;

import static net.shiroha233.roadweaver.planning.AsyncPlanningOps.*;

import static net.shiroha233.roadweaver.planning.ConflictWindowOps.*;

import static net.shiroha233.roadweaver.planning.PlanningBoundsOps.*;



// 仍留在同包其它类里的常量与工具。

import static net.shiroha233.roadweaver.planning.RoadPlanningService.*;

import static net.shiroha233.roadweaver.planning.AsyncPlanningOps.*;

import static net.shiroha233.roadweaver.planning.ConflictWindowOps.*;

import static net.shiroha233.roadweaver.planning.PlanningBoundsOps.*;



/**

 * 路线精化。

 *

 * <h2>它在整条链里的位置</h2>

 * <p>粗规划给的是一条「能走」的路线；精化把它变成「能施工」的路线 ——

 * 补采样、压坡度、处理局部冲突。它<b>只改窗口内的站</b>，窗口外一律不动。</p>

 *

 * <h2>★ 三档精化，成本递增</h2>

 * <ul>

 *   <li>{@code refineInitialRoutesAsync} —— 整批初始路线（最粗）；</li>

 *   <li>{@code refineInitialBatchAsync} —— 按批（中等）；</li>

 *   <li>{@code refineLocalRouteWindowAsync} —— 只修某个冲突窗口（最细、最贵）。</li>

 * </ul>

 * <p>顺序是「先粗后细」：粗精化把明显的问题消掉，细精化只处理剩下的少数窗口。

 * 反过来会把大量时间花在最终不会保留的路段上。</p>

 *

 * <h2>★ 预取是独立一步</h2>

 * <p>{@code prefetchConflictWindowsAsync} 在精化之前先把窗口内的地形采样

 * 丢给后台预取 —— 让采样与规划<b>重叠</b>，而不是串行等待。</p>

 *

 * <p>本类是从 2204 行的 {@code RoadPlanningService} 里<b>纯搬迁</b>出来的，

 * 判据与阈值一字未改（含缩进）。</p>

 */

public final class RouteRefinementOps {

    public static CompletableFuture<Void> refineInitialRoutesAsync(

            ServerLevel level,

            List<StructureConnection> connections,

            int step,

            int paddingBlocks) {

        if (level == null || connections == null || connections.isEmpty()) {

            return CompletableFuture.completedFuture(null);

        }

        // Local repair is intentionally fixed to the user's 100-block

        // conflict radius. The caller's legacy padding argument must not

        // accidentally enlarge the building search or sampling window.

        int padding = ConfigService.get().planning().localConflictRadiusBlocks();

        List<CompletableFuture<Void>> refinements = new java.util.ArrayList<>();

        Map<StructureConnection, List<BlockPos>> originalPaths = new LinkedHashMap<>();

        Map<StructureConnection, List<LocalSamplingWindow>> windowsByConnection = new LinkedHashMap<>();

        for (StructureConnection connection : connections) {

            if (connection == null) continue;

            ModConfig config = ConfigService.get();

            TerrainSamplingMode mode = TerrainSamplingSessions.forLevel(level).effectiveMode();

            PathfindingCostConfig retryPathConfig = config.pathfindingCost().withPathGridStep(step);

            List<BlockPos> originalPath = PlannedPathCache.find(

                    level, connection, mode, retryPathConfig).orElse(List.of());

            if (originalPath.size() < 3) {

                LOGGER.warn("【缺少持久化路径，跳过局部路线精修】 " + "RoadWeaver local route refinement skipped: missing persisted route path "

                                + "connection={}->{}", connection.from(), connection.to());

                continue;

            }

            List<LocalSamplingWindow> localWindows = conflictSamplingWindows(

                    level, connection, padding, retryPathConfig);

            if (localWindows.isEmpty()) {

                LOGGER.warn("【没有识别出局部冲突，跳过大范围端点扫描】 " + "RoadWeaver local route refinement skipped broad endpoint scan: "

                                + "no local conflict was identified connection={}->{}",

                        connection.from(), connection.to());

                // A route with no repair window has no safe local operation to

                // perform. Refuse to re-run it as a disguised full-route

                // generation; the bounded candidate phase handles it next.

                PlannedPathCache.discard(level, connection);

                continue;

            }

            originalPaths.put(connection, List.copyOf(originalPath));

            windowsByConnection.put(connection, localWindows);

        }



        // Discover every conflict window before any local planner starts. The

        // old route-by-route flow made each worker request its own circle and

        // repeatedly sampled overlapping columns. Build one de-duplicated

        // conflict-point set and let the accelerated sampler process it as a

        // single GPU-first batch. Route workers then perform independent A*

        // splices against the shared persisted samples.

        CompletableFuture<Void> sharedConflictPrefetch = prefetchConflictWindowsAsync(

                level, windowsByConnection, Math.max(1, step));

        LOGGER.info("【局部冲突窗口预取任务已提交】 " + "RoadWeaver local conflict prefetch scheduled: routes={} windows={} step={} radius={} ",

                windowsByConnection.size(),

                windowsByConnection.values().stream().mapToInt(List::size).sum(),

                Math.max(1, step), padding);



        for (Map.Entry<StructureConnection, List<LocalSamplingWindow>> entry : windowsByConnection.entrySet()) {

            StructureConnection connection = entry.getKey();

            List<LocalSamplingWindow> localWindows = entry.getValue();

            java.util.concurrent.atomic.AtomicReference<List<BlockPos>> currentPath =

                    new java.util.concurrent.atomic.AtomicReference<>(originalPaths.get(connection));

            CompletableFuture<Void> routeRefinement = sharedConflictPrefetch;

            for (int windowIndex = 0; windowIndex < localWindows.size(); windowIndex++) {

                LocalSamplingWindow localWindow = localWindows.get(windowIndex);

                int[] localBounds = localWindow.bounds();

                final int currentIndex = windowIndex;

                LOGGER.info("【当前局部精修窗口与采样精度】 " + "RoadWeaver local route refinement window: step={} padding={} index={}/{} "

                                + "bounds=[{},{} -> {},{}] connection={}->{}",

                        step, padding, currentIndex + 1, localWindows.size(),

                        localBounds[0], localBounds[1], localBounds[2], localBounds[3],

                        connection.from(), connection.to());

                // Keep windows for one route ordered. This avoids concurrent

                // cache writes for the same connection while still allowing

                // different failed routes to refine in parallel.

                routeRefinement = routeRefinement.thenCompose(ignored ->

                        refineLocalRouteWindowAsync(level, connection, currentPath, localWindow, step));

            }

            // A failed local exact sample must stay route-local. Complete the

            // wave exceptionally only in the worker's result path so the

            // startup coordinator can reject this route and advance to its

            // bounded topology candidate instead of aborting every route.

            refinements.add(routeRefinement.exceptionally(failure -> {

                PlannedPathCache.discard(level, connection);

                LOGGER.warn("【精确局部修复失败，拒绝扩大范围并交候补处理】 " + "RoadWeaver local exact repair failed; refusing broad fallback "

                                + "and leaving route for candidate handling connection={}->{}",

                        connection.from(), connection.to(), failure);

                return null;

            }));

        }

        return CompletableFuture.allOf(refinements.toArray(CompletableFuture[]::new));

    }



    public static CompletableFuture<Void> refineLocalRouteWindowAsync(

            ServerLevel level,

            StructureConnection connection,

            java.util.concurrent.atomic.AtomicReference<List<BlockPos>> currentPath,

            LocalSamplingWindow window,

            int step) {

        List<BlockPos> originalPath = currentPath.get();

        List<LocalRouteSlice> slices = localRouteSlices(originalPath, window.center(), window.radius());

        if (slices.isEmpty()) {

            LOGGER.warn("【冲突在当前半径内没有内部出入口，跳过局部拼接】 " + "RoadWeaver local route splice skipped: conflict has no interior entry/exit "

                            + "within radius={} connection={}->{}",

                    window.radius(), connection.from(), connection.to());

            PlannedPathCache.discard(level, connection);

            return CompletableFuture.failedFuture(

                    new IllegalStateException("no local interior entry/exit"));

        }

        int[] bounds = window.bounds();

        int localStep = Math.max(1, step);

        ModConfig config = ConfigService.get();

        PathfindingCostConfig pathfinding = config.pathfindingCost().withPathGridStep(localStep);



        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {

            RoadTerrainPlanningPort.Bounds localBounds =

                    new RoadTerrainPlanningPort.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);

            int candidateIndex = 0;

            long lastPlanMillis = 0L;

            for (LocalRouteSlice slice : slices) {

                candidateIndex++;

                StructureConnection localConnection = new StructureConnection(

                        slice.start(), slice.end(), ConnectionStatus.PLANNED);

                RoadTerrainPlanningPort.Request request = new RoadTerrainPlanningPort.Request(

                        level, localBounds, List.of(localConnection), pathfinding,

                        window.center(), window.radius());

                LOGGER.info("【正在尝试局部路径拼接】 " + "RoadWeaver local splice attempt: candidate={}/{} step={} radius={} center={} "

                                + "slice={}..{} nodes={} connection={}->{}",

                        candidateIndex, slices.size(), localStep, window.radius(), window.center(),

                        slice.startIndex(), slice.endIndex(), slice.path().size(),

                        connection.from(), connection.to());

                RoadTerrainPlanningPort.Result result;

                long planStartedAt = System.nanoTime();

                try {

                    result = TERRAIN_PLANNING.plan(request);

                } finally {

                    lastPlanMillis = (System.nanoTime() - planStartedAt) / 1_000_000L;

                }

                List<BlockPos> localPath = result.paths().get(localConnection);

                if (localPath == null || localPath.size() < 2) {

                    LOGGER.info("【局部拼接未找到路径，拒绝该候选】 " + "RoadWeaver local splice rejected no_path candidate={}/{} step={} radius={} "

                                    + "bounds=[{},{} -> {},{}] slice={}..{} localEndpoints={}->{} planMs={} connection={}->{}",

                            candidateIndex, slices.size(), localStep, window.radius(),

                            bounds[0], bounds[1], bounds[2], bounds[3],

                            slice.startIndex(), slice.endIndex(), slice.start(), slice.end(),

                            lastPlanMillis, connection.from(), connection.to());

                    continue;

                }

                List<BlockPos> merged = spliceRouteSlice(originalPath, slice.startIndex(),

                        slice.endIndex(), localPath);

                // Local repair is read back by the route generator using the

                // world's effective sampling mode. Circular exact planning

                // may return FULL_REGION even when the session is configured

                // for COARSE_CORRIDOR, so store the merged path under the

                // effective mode to keep the retry fingerprint consistent.

                PlannedPathCache.register(level, resultMode(level), pathfinding,

                        Map.of(connection, merged));

                currentPath.set(merged);

                LOGGER.info("【局部路径拼接已接受】 " + "RoadWeaver local splice accepted: candidate={}/{} step={} radius={} "

                                + "localNodes={} routeNodes={} -> {} planMs={} connection={}->{}",

                        candidateIndex, slices.size(), localStep, window.radius(), localPath.size(),

                        originalPath.size(), merged.size(), lastPlanMillis,

                        connection.from(), connection.to());

                return null;

            }

            throw new IllegalStateException("local splice planner returned no usable candidate path"

                    + " center=" + window.center() + " radius=" + window.radius()

                    + " bounds=" + java.util.Arrays.toString(bounds)

                    + " connection=" + connection.from() + "->" + connection.to());

        }).thenCompose(ignored -> {

            List<BlockPos> merged = PlannedPathCache.find(

                    level, connection, resultMode(level), pathfinding).orElse(List.of());

            return merged.isEmpty()

                    ? CompletableFuture.completedFuture(null)

                    : preparePlanningSnapshotsForPathsAsync(

                            level, Map.of(connection, merged), ThreadPoolManager.TaskRole.INITIAL);

        });

    }



    public static CompletableFuture<Void> refineInitialBatchAsync(

            ServerLevel level,

            int[] samplingBounds,

            List<StructureConnection> connectionsToPlan,

            int step) {

        return refineInitialBatchAsync(level, samplingBounds, connectionsToPlan, step, null, 0, true);

    }



    public static CompletableFuture<Void> prefetchConflictWindowsAsync(

            ServerLevel level,

            Map<StructureConnection, List<LocalSamplingWindow>> windowsByConnection,

            int step) {

        if (level == null || windowsByConnection == null || windowsByConnection.isEmpty()) {

            return CompletableFuture.completedFuture(null);

        }

        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();

        int localStep = Math.max(1, step);

        for (List<LocalSamplingWindow> windows : windowsByConnection.values()) {

            if (windows == null) continue;

            for (LocalSamplingWindow window : windows) {

                if (window == null || window.center() == null || window.radius() <= 0) continue;

                int radius = window.radius();

                long radiusSquared = (long) radius * radius;

                int minX = saturatingAdd(window.center().getX(), -radius);

                int maxX = saturatingAdd(window.center().getX(), radius);

                int minZ = saturatingAdd(window.center().getZ(), -radius);

                int maxZ = saturatingAdd(window.center().getZ(), radius);

                for (int z = minZ; z <= maxZ; z += localStep) {

                    for (int x = minX; x <= maxX; x += localStep) {

                        long dx = (long) x - window.center().getX();

                        long dz = (long) z - window.center().getZ();

                        if (dx * dx + dz * dz <= radiusSquared) {

                            unique.putIfAbsent(

                                    net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample.key(x, z),

                                    new BlockPos(x, 0, z));

                        }

                    }

                }

            }

        }

        if (unique.isEmpty()) return CompletableFuture.completedFuture(null);

        List<BlockPos> positions = List.copyOf(unique.values());

        long epoch = ThreadPoolManager.currentEpoch();

        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {

            if (!ThreadPoolManager.isEpoch(epoch)) return null;

            AccurateHeightSampler sampler = AccurateHeightSampler.create(level);

            long started = System.nanoTime();

            sampler.samplePositions(positions, batch -> LOGGER.debug(

                    "【统一局部冲突采样批次进度】 " + "RoadWeaver unified local conflict sampling progress: completed={} total={} batch={} backend={} device={}",

                    batch.completedColumns(), batch.totalColumns(), batch.batchColumns(),

                    sampler.backendName(), sampler.deviceName()));

            LOGGER.info("【统一局部冲突采样完成】 " + "RoadWeaver unified local conflict sampling complete: routes={} windows={} uniqueColumns={} step={} elapsedMs={} backend={} device={}",

                    windowsByConnection.size(),

                    windowsByConnection.values().stream().mapToInt(List::size).sum(),

                    positions.size(), localStep, (System.nanoTime() - started) / 1_000_000L,

                    sampler.backendName(), sampler.deviceName());

            return null;

        });

    }



    public static CompletableFuture<Void> refineInitialBatchAsync(
            ServerLevel level,
            int[] samplingBounds,
            List<StructureConnection> connectionsToPlan,
            int step,
            BlockPos samplingCenter,
            int samplingRadius) {
        return refineInitialBatchAsync(level, samplingBounds, connectionsToPlan, step, samplingCenter, samplingRadius, false);
    }

    private static CompletableFuture<Void> refineInitialBatchAsync(
            ServerLevel level, int[] samplingBounds, List<StructureConnection> connectionsToPlan,
            int step, BlockPos samplingCenter, int samplingRadius, boolean replayInitialSolve) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || connectionsToPlan == null || connectionsToPlan.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        ModConfig config = ConfigService.get();
        TerrainSamplingMode mode = config.planning().terrainSamplingMode();
        if (mode == null || mode == TerrainSamplingMode.LEGACY_DIRECT) {
            return CompletableFuture.completedFuture(null);
        }
        int[] bounds = normalizedBounds(
                samplingBounds == null ? 0 : samplingBounds[0],
                samplingBounds == null ? 0 : samplingBounds[1],
                samplingBounds == null ? 0 : samplingBounds[2],
                samplingBounds == null ? 0 : samplingBounds[3]);
        PathfindingCostConfig pathfinding = config.pathfindingCost().withPathGridStep(step);
        long epoch = ThreadPoolManager.currentEpoch();
        List<StructureConnection> tasks = List.copyOf(connectionsToPlan);
        LOGGER.info("【初始精修批次已调度】 " + "RoadWeaver initial refinement batch scheduled: step={} bounds=[{},{} -> {},{}] "
                        + "samplingMask={} center={} radius={} connections={}",
                pathfinding.effectivePathGridStep(), bounds[0], bounds[1], bounds[2], bounds[3],
                samplingCenter != null, samplingCenter, Math.max(0, samplingRadius), tasks.size());
        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            if (!ThreadPoolManager.isEpoch(epoch)) return null;
            RoadTerrainPlanningPort.Request request = new RoadTerrainPlanningPort.Request(
                    level,
                    new RoadTerrainPlanningPort.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]),
                    tasks,
                    pathfinding,
                    samplingCenter,
                    samplingRadius,
                    replayInitialSolve ? RoadTerrainPlanningPort.InitialSolvePass.REPLAY
                            : RoadTerrainPlanningPort.InitialSolvePass.NONE);
            RoadTerrainPlanningPort.Result result = TERRAIN_PLANNING.plan(request);
            if (!ThreadPoolManager.isEpoch(epoch)) return null;
            result = filterSuppressedPaths(level, result);
            if (result == null || result.paths().isEmpty()) return result;
            result = SharedEndpointPathReuse.reuseSharedEndpointTails(
                    level, result, pathfinding.effectivePathGridStep());
            PlannedPathCache.register(level, result.mode(), pathfinding, result.paths());
            LOGGER.info("【初始精修批次规划结束】 " + "RoadWeaver initial refinement batch planned: step={} requested={} resolvedPaths={}",
                    pathfinding.effectivePathGridStep(), tasks.size(), result.paths().size());
            return result;
        }).thenCompose(result -> result == null || result.paths().isEmpty()
                        || !ThreadPoolManager.isEpoch(epoch)
                ? CompletableFuture.completedFuture(null)
                : prepareLocalSnapshotsAndPaths(level, result, samplingCenter, samplingRadius));
    }
}
