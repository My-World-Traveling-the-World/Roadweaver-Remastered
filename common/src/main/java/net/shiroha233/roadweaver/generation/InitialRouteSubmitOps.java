/* 文件职责：初始路线批次的提交与收集 —— 分步提交、增量收集、预取高度。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.pathlogic.structures.EndpointFilterOutcome;
import net.shiroha233.roadweaver.planning.RouteRefinementOps;
import net.shiroha233.roadweaver.planning.ConflictWindowOps;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.GreatWallPrewarmCompat;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadPlanConsistencyService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.RoadPositionQuery;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.files.InitialGenerationCheckpointStore;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.planning.ConnectionFallbackService;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.structures.placement.SpawnCabinPlacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.generation.InitialGenManager.*;
import static net.shiroha233.roadweaver.generation.InitialRoutePersistOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanLifecycleOps.*;

/**
 * 初始路线批次的提交与收集。
 *
 * <h2>为什么要「分步」提交</h2>
 * <p>一次把所有候选丢给规划器会把线程池打满、并且首条路的延迟被拉到很长。
 * 这里按<b>步长</b>提交（step），每提交一步就收一次已完成的结果 —— 保证「先出的先落地」。</p>
 *
 * <h2>★ 预取是为了让采样与规划重叠</h2>
 * <p>{@code prefetchInitialRoadHeights} 在规划之前先把这批路要用的高度采样
 * 丢进缓存线程池。**采样是最贵的一步**，把它与规划重叠能省掉整段等待。</p>
 *
 * <p>本类是从 2174 行的 {@code InitialGenManager} 里<b>纯搬迁</b>出来的，
 * 步长与批大小一字未改（含缩进）。</p>
 */
public final class InitialRouteSubmitOps {
    public static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step) {
        return submitAndCollectAtStep(level, tasks, step, false, null);
    }

    public static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step, boolean localOnly) {
        return submitAndCollectAtStep(level, tasks, step, localOnly, null);
    }

    public static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step, boolean localOnly,
            List<BlockPos> prefetchedRoutePositions) {
        roadGenerationWorkersActive = true;
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            prefetchInitialRoadHeights(level, tasks, step, prefetchedRoutePositions);
            List<Future<GenResult>> futures = new ArrayList<>();
            for (StructureConnection task : tasks) {
                futures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                    generating.incrementAndGet();
                    InitialGenerationProgressTracker.routePhaseWorkerStarted();
                    InitialGenerationProgressTracker.beginRoadTask("road_step_" + step, task, step);
                    InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                    InitialGenerationProgressTracker.updateConnections(
                            total.get(), generating.get(), done.get(), failed.get());
                    try {
                        long taskStartedAt = System.nanoTime();
                        RoadGenerationService.InitialGenerationResult generated =
                                RoadGenerationService.generateInitialRoadAtStep(level, task, step, localOnly);
                        RoadGenerationService.InitialTaskResult result = generated.result();
                        if (localOnly) {
                            // 0.8.325：精细重算逐路计时（纯观测）。真机该阶段
                            // 91s/7 条，只有这里能看出每条实际花了多少、谁在拖。
                            // 0.9.092：改名 fine regenerate —— 原 "local repair" 与
                            // RoadPathCalculator 里真正的冲突窗口半径采样修复同名。
                            LOGGER.info("【精细重算耗时与产物站数】 " + "RoadWeaver fine regenerate timing: step={} wallMs={} result={} "
                                            + "stations={} connection={}->{}",
                                    step, elapsedMs(taskStartedAt), result,
                                    generated.road() == null ? -1
                                            : generated.road().roadSegmentList().size(),
                                    task.from(), task.to());
                        }
                        InitialGenerationProgressTracker.updateConnections(
                                total.get(), generating.get(), done.get(), failed.get());
                        StructureConnection effective = generated.connection() != null
                                ? generated.connection() : task;
                        return new GenResult(PlannedPathKey.of(task), effective, result, generated.road());
                    } finally {
                        InitialGenerationProgressTracker.finishRoadTask();
                        InitialGenerationProgressTracker.routePhaseWorkerFinished();
                        generating.decrementAndGet();
                        InitialGenerationProgressTracker.updateConnections(
                                total.get(), generating.get(), done.get(), failed.get());
                    }
                }));
            }

            return collectInitialRouteBatch(level, tasks, step, futures);
        } finally {
            roadGenerationWorkersActive = false;
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    public static BatchGenerationResult submitAndCollect(ServerLevel level,
                                                           List<StructureConnection> tasks) {
        roadGenerationWorkersActive = true;
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            prefetchInitialRoadHeights(level, tasks, null, null);
            List<Future<GenResult>> futures = new ArrayList<>();
            for (StructureConnection task : tasks) {
                futures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                    generating.incrementAndGet();
                    InitialGenerationProgressTracker.routePhaseWorkerStarted();
                    InitialGenerationProgressTracker.beginRoadTask("road_preparing", task, -1);
                    InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                    try {
                        RoadGenerationService.InitialGenerationResult generated =
                                RoadGenerationService.generateInitialRoad(level, task);
                        RoadGenerationService.InitialTaskResult result = generated.result();
                        // Initial roads are fully planned in memory; persistence is committed
                        // after the complete batch has formed its early junctions.
                        if (result == RoadGenerationService.InitialTaskResult.COMPLETED) {
                            done.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                        StructureConnection effective = generated.connection() != null ? generated.connection() : task;
                        return new GenResult(PlannedPathKey.of(task), effective, result, generated.road());
                    } finally {
                        InitialGenerationProgressTracker.finishRoadTask();
                        InitialGenerationProgressTracker.routePhaseWorkerFinished();
                        generating.decrementAndGet();
                        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                    }
                }));
            }

            return collectInitialRouteBatch(level, tasks, -1, futures);
        } finally {
            roadGenerationWorkersActive = false;
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    public static void prefetchInitialRoadHeights(
            ServerLevel level, List<StructureConnection> tasks, Integer step,
            List<BlockPos> prefetchedRoutePositions) {
        if (level == null) return;
        boolean reusePrewarm = prefetchedRoutePositions != null && !prefetchedRoutePositions.isEmpty();
        if (!reusePrewarm && (tasks == null || tasks.isEmpty())) return;
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<BlockPos> positions = new ArrayList<>();
        int routes = 0;
        if (reusePrewarm) {
            routes = tasks == null ? 0 : tasks.size();
            for (BlockPos position : prefetchedRoutePositions) {
                if (position == null) continue;
                long key = net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample
                        .key(position.getX(), position.getZ());
                if (seen.add(key)) positions.add(new BlockPos(position.getX(), 0, position.getZ()));
            }
        } else {
            ModConfig config = ConfigService.get();
            var mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
            var pathConfig = config.pathfindingCost();
            for (StructureConnection task : tasks) {
                if (task == null) continue;
                List<BlockPos> path = PlannedPathCache.find(level, task, mode, pathConfig).orElse(null);
                if (path == null || path.isEmpty()) continue;
                routes++;
                for (BlockPos position : path) {
                    if (position == null) continue;
                    long key = net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample
                            .key(position.getX(), position.getZ());
                    if (seen.add(key)) positions.add(new BlockPos(position.getX(), 0, position.getZ()));
                }
            }
        }
        if (positions.isEmpty()) return;

        final int routeCount = routes;
        final List<BlockPos> prefetchPositions = List.copyOf(positions);
        final String prefetchStep = step == null ? "default" : step.toString();
        long startedAt = System.nanoTime();
        AccurateHeightSampler sampler = AccurateHeightSampler.create(level);
        final int batchCount = (prefetchPositions.size() + INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS - 1)
                / INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS;
        LOGGER.info("【初始精确高度预取开始】 " + "RoadWeaver initial precise height prefetch start: step={} routes={} uniqueColumns={} "
                        + "batches={} batchColumns={} reusedPrewarm={} backend={} device={}",
                prefetchStep, routeCount, prefetchPositions.size(), batchCount,
                INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS, reusePrewarm,
                sampler.backendName(), sampler.deviceName());
        List<Future<?>> prefetchFutures = new ArrayList<>(batchCount);
        for (int offset = 0, batch = 1; offset < prefetchPositions.size();
             offset += INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS, batch++) {
            final int batchNumber = batch;
            int end = Math.min(prefetchPositions.size(), offset + INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS);
            List<BlockPos> slice = prefetchPositions.subList(offset, end);
            prefetchFutures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                long batchStarted = System.nanoTime();
                sampler.samplePositions(slice, progress -> LOGGER.info(
                        "【初始精确高度预取批次进度】 " + "RoadWeaver initial precise height prefetch batch progress: step={} batch={}/{} columns={} "
                                + "elapsedMs={} backend={} device={}",
                        prefetchStep, batchNumber, batchCount, progress.completedColumns(),
                        (System.nanoTime() - batchStarted) / 1_000_000L,
                        sampler.backendName(), sampler.deviceName()));
                LOGGER.info("【初始精确高度预取批次完成】 " + "RoadWeaver initial precise height prefetch batch complete: step={} batch={}/{} "
                                + "batchColumns={} elapsedMs={} backend={} device={}",
                        prefetchStep, batchNumber, batchCount, slice.size(),
                        (System.nanoTime() - batchStarted) / 1_000_000L,
                        sampler.backendName(), sampler.deviceName());
            }));
        }
        for (Future<?> future : prefetchFutures) {
            try {
                awaitInitialFuture(level, future);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during initial precise height prefetch", interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Initial precise height prefetch batch failed", failure.getCause());
            }
        }
        LOGGER.info("【初始精确高度预取结束】 " + "RoadWeaver initial precise height prefetch complete: step={} routes={} uniqueColumns={} elapsedMs={} backend={} device={}",
                prefetchStep, routeCount, prefetchPositions.size(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                sampler.backendName(), sampler.deviceName());
    }

    public static BatchGenerationResult collectInitialRouteBatch(
            ServerLevel level, List<StructureConnection> tasks, int step,
            List<Future<GenResult>> futures) {
        Map<PlannedPathKey, GenResult> results = new HashMap<>();
        List<RoadData> roads = new ArrayList<>();
        Set<Future<GenResult>> pending = new HashSet<>(futures);
        long startedAt = System.nanoTime();
        long lastLogAt = 0L;
        while (!pending.isEmpty()) {
            boolean madeProgress = false;
            for (int i = 0; i < futures.size(); i++) {
                Future<GenResult> future = futures.get(i);
                if (!pending.contains(future) || !future.isDone()) continue;
                StructureConnection task = i < tasks.size() ? tasks.get(i) : null;
                pending.remove(future);
                madeProgress = true;
                try {
                    GenResult result = future.get();
                    if (result != null) {
                        results.put(result.originalKey(), result);
                        if (result.road() != null) roads.add(result.road());
                        // At-step batches do not update the aggregate route
                        // counters inside the worker. Publish each finished
                        // route here, immediately when its future completes,
                        // so the loading HUD never appears stuck at zero.
                        if (step != -1) {
                            boolean retryPhase = InitialGenerationProgressTracker.currentRoutePhase()
                                    != InitialGenerationProgressTracker.RoutePhase.ORIGINAL;
                            boolean protectedGapNeedsRetry = result.result()
                                    == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null
                                    && result.road().hasProtectedGap()
                                    && task != null
                                    && !hasOnlyEndpointProtectedGaps(
                                    level, task, result.road());
                            if (result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                if (protectedGapNeedsRetry) {
                                    // A usable protected-gap route is not a
                                    // failure: it is either handed to local
                                    // repair or retained for final fallback.
                                    // 修复 HUD 冻结：ORIGINAL 波里带 gap 的路
                                    // 保持"尚未完成"（要交给 local repair），
                                    // 但 LOCAL_REPAIR 波里它已经处理完毕（
                                    // 无论 retain 还是后续 handoff），实时
                                    // 递增 done，让主进度条随逐条完成前进；
                                    // 波末 settleRoutePhase 用 resolved.size()
                                    // 精确覆盖，实时近似值不会污染终值。
                                    if (retryPhase) {
                                        done.incrementAndGet();
                                        InitialGenerationProgressTracker.routePhaseRetained();
                                    }
                                } else {
                                    done.incrementAndGet();
                                    InitialGenerationProgressTracker.routePhaseAttemptCompleted(true);
                                }
                            } else {
                                // A local-repair/candidate miss is still
                                // retryable work. Keep it out of the aggregate
                                // terminal-failure count until all candidates
                                // have been exhausted.
                                if (!retryPhase) failed.incrementAndGet();
                                InitialGenerationProgressTracker.routePhaseAttemptCompleted(false);
                            }
                            InitialGenerationProgressTracker.updateConnections(
                                    total.get(), generating.get(), done.get(), failed.get());
                        }
                    }
                } catch (CancellationException cancelled) {
                    recordInitialRouteFailure(results, task, step, "cancelled", cancelled);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    recordInitialRouteFailure(results, task, step, "interrupted", interrupted);
                } catch (ExecutionException failedTask) {
                    Throwable cause = failedTask.getCause() == null ? failedTask : failedTask.getCause();
                    recordInitialRouteFailure(results, task, step, "worker_exception", cause);
                }
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastLogAt >= INITIAL_ROUTE_WATCHDOG_LOG_MS) {
                List<StructureConnection> pendingTasks = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    if (pending.contains(futures.get(i)) && i < tasks.size()) pendingTasks.add(tasks.get(i));
                }
                InitialRouteTaskDiagnostics.logBatch(step, pendingTasks);
                lastLogAt = nowMs;
            }

            if (!madeProgress && !pending.isEmpty()) {
                // P0-4: 250ms was a coarse quantum on top of every wave; 10ms
                // keeps the completion check responsive without busy-spinning.
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Initial road batch interrupted while monitoring routes");
                }
            }
        }
        return new BatchGenerationResult(Map.copyOf(results), List.copyOf(roads));
    }
}
