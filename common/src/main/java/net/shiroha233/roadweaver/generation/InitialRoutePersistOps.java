/* 文件职责：初始路线的落盘与失败记账 —— 持久化批次、记录失败、处理最终路段。 */
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
import static net.shiroha233.roadweaver.generation.InitialRouteSubmitOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanLifecycleOps.*;

/**
 * 初始路线的落盘与失败记账。
 *
 * <h2>为什么失败要单独记账</h2>
 * <p>某条路生成失败不该让整批回滚 —— 其余路照常落盘，失败的那条记下来
 * （{@code recordInitialRouteFailure}）供后续重试或上报。
 * **批量作业里「一条失败拖垮全批」是最常见的事故形态。**</p>
 *
 * <h2>★ 最终路段的处理是单独一步</h2>
 * <p>{@code processFinalRoad} 处理的是「所有批次都落完之后」的收尾：
 * 水体验证、缺口修补、状态归一。它必须在全部落盘之后跑，否则会改到还没落盘的路。</p>
 *
 * <p>本类是从 2174 行的 {@code InitialGenManager} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class InitialRoutePersistOps {
    public static void recordInitialRouteFailure(
            Map<PlannedPathKey, GenResult> results, StructureConnection task,
            int step, String reason, Throwable failure) {
        if (task == null) return;
        PlannedPathKey key = PlannedPathKey.of(task);
        results.putIfAbsent(key, new GenResult(key, task,
                RoadGenerationService.InitialTaskResult.FAILED, null));
        if (step != -1) {
            if (InitialGenerationProgressTracker.currentRoutePhase()
                    == InitialGenerationProgressTracker.RoutePhase.ORIGINAL) {
                failed.incrementAndGet();
            }
            InitialGenerationProgressTracker.routePhaseAttemptCompleted(false);
            InitialGenerationProgressTracker.updateConnections(
                    Math.max(0, total.get()), generating.get(), done.get(), failed.get());
        }
        if (failure == null) {
            LOGGER.warn("【初始路线标记为可重试失败】 " + "RoadWeaver initial route marked retryable failure: step={} route={}->{} reason={}",
                    step, task.from(), task.to(), reason);
        } else {
            LOGGER.error("【初始路线标记为可重试失败】 " + "RoadWeaver initial route marked retryable failure: step={} route={}->{} reason={}",
                    step, task.from(), task.to(), reason, failure);
        }
    }

    public static PersistedBatch persistInitialRoadBatch(ServerLevel level, List<RoadData> roads) {
        if (roads == null || roads.isEmpty()) return new PersistedBatch(Set.of(), Set.of());
        // P0-post C: the merge refine and the per-road finalization both run on the
        // shared runtime pool, which the precision waves have already restored to
        // the configured size (3 by default). Post-processing is the last startup
        // phase and nothing else competes for CPU, so lend it the startup lane
        // budget. Real-machine log (16 routes, 6 merged) showed refineMs=77410 on
        // 3 threads versus donorSelectMs=1069 after the caching fix: the pool size,
        // not the algorithm, was the remaining bottleneck.
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            return persistInitialRoadBatchInternal(level, roads);
        } finally {
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    public static PersistedBatch persistInitialRoadBatchInternal(ServerLevel level, List<RoadData> roads) {
        long postProcessStartedAt = System.nanoTime();
        InitialGenerationProgressTracker.enterStage(
                InitialGenerationStage.POST_PROCESSING, "merging_final_network");
        InitialGenerationProgressTracker.setConstructionChunkPlan(
                Math.max(1, roads.size()), "merging_final_network");
        Map<PlannedPathKey, RoadData> originalsByEdge = new HashMap<>();
        for (RoadData road : roads) {
            if (road != null && road.hasOwnerPair()) {
                originalsByEdge.put(ownerKey(road), road);
            }
        }
        // Candidate retries are already resolved by the caller. Merge the final
        // successful routes as one immutable topology before writing any road
        // data or starting construction.
        ExistingRoadJunctionService.MergeBatchResult mergeBatch =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(level, roads);
        List<RoadData> connected = mergeBatch.roads();
        Set<Long> refinedRoadFingerprints = mergeBatch.refinedRoadFingerprints();
        long mergeMs = elapsedMs(postProcessStartedAt);
        // msPerRoad is the number E3 needs: nine roads and 22 seconds is either a fixed geometry cost
        // per road or something quadratic, and the ratio separates them without a profiler.
        LOGGER.info("【初始网络合并完成，准备持久化】 " + "RoadWeaver initial final network ready before persistence: inputRoads={} finalRoads={} "
                        + "refined={} mergeMs={} msPerRoad={}",
                roads.size(), connected.size(), refinedRoadFingerprints.size(), mergeMs,
                roads.isEmpty() ? mergeMs : mergeMs / roads.size());
        // ── ★ 批 6「5 条可观测」的落地出口（2026-11-15）──────────────────
        //
        // 问题：这 5 个计数器一直存在、也在累加、也有单测，但**生产侧一个读者都没有**
        // （全源码里各只出现 1 次 —— 就是它自己的 getter 定义）。
        // 后果：真机上「避让完全正常（盲查 0）」与「避让一直失明（盲查 50 万）」
        // 在日志里完全同形 —— 这是病根 C 在观测层的残留。
        //
        // 这里把它们**一次性打在同一行**，放在已经有 inputRoads/finalRoads 的那条汇总日志之后，
        // 好处是排查时一个 grep 就能拿到「这一局规划阶段避让到底发生了什么」。
        // 只加日志、不改任何判据或返回值。
        LOGGER.info("RoadWeaver structure-avoidance counters: blindQuery={} fallbackBoxOnly={} "
                        + "droppedPending={} fullPassBatch={} unresolvedEndpointOwner={}"
                        + " (blindQuery>0 说明有避让查询发生在没有可用规划快照的线程上；"
                        + "snapshot=absent 时结构来源整段未生效)",
                net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceCache.blindQueryCount(),
                net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceDiagnostics
                        .fallbackBoxOnlyQueries(),
                net.shiroha233.roadweaver.structures.precompute.StructureInjector.droppedPendingCount(),
                net.shiroha233.roadweaver.search.StructureIndexService.fullPassBatchCount(),
                net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver
                        .unresolvedEndpointOwnerCount());
        // Re-baseline the progress plan after network merge: merging can
        // produce more final roads than the input batch, otherwise the
        // percentage overshoots 100 and jumps back down.
        InitialGenerationProgressTracker.setConstructionChunkPlan(
                Math.max(1, connected.size()), "processing_final_roads");
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        Set<PlannedPathKey> persistedEdges = new HashSet<>();
        Set<Long> constructionChunks = new HashSet<>();
        Object constructionLock = new Object();
        int processedFinalRoads = 0;
        List<RoadData> roadsToPersist = new ArrayList<>();
        AtomicReference<Throwable> firstFinalRoadFailure = new AtomicReference<>();
        AtomicLong finalRoadTotalNanos = new AtomicLong();
        AtomicLong finalRoadSlowestNanos = new AtomicLong();
        AtomicInteger finalRoadChanged = new AtomicInteger();
        AtomicInteger finalRoadRejected = new AtomicInteger();
        long finalRoadsStartedAt = System.nanoTime();
        List<CompletableFuture<FinalRoadWork>> finalRoadFutures = new ArrayList<>();
        for (RoadData connectedRoad : connected) {
            final RoadData finalConnectedRoad = connectedRoad;
            // 终态道路任务必须绑定生命周期结果，停服丢队列不能留下 join 等待。
            finalRoadFutures.add(ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.POSTPROCESS, () -> {
                long roadStartedAt = System.nanoTime();
                try {
                    FinalRoadWork work = processFinalRoad(level, finalConnectedRoad, originalsByEdge, streams,
                            refinedRoadFingerprints);
                    if (work.road() == null) {
                        finalRoadRejected.incrementAndGet();
                    } else if (work.road() != finalConnectedRoad) {
                        finalRoadChanged.incrementAndGet();
                    }
                    return work;
                } finally {
                    long spent = System.nanoTime() - roadStartedAt;
                    finalRoadTotalNanos.addAndGet(spent);
                    finalRoadSlowestNanos.accumulateAndGet(spent, Math::max);
                }
            }));
        }
        for (CompletableFuture<FinalRoadWork> future : finalRoadFutures) {
            try {
                FinalRoadWork work = future.join();
                processedFinalRoads++;
                InitialGenerationProgressTracker.recordFinalRoadDone(processedFinalRoads);
                if (work.road() != null) {
                    synchronized (constructionLock) {
                        roadsToPersist.add(work.road());
                        constructionChunks.addAll(RoadFootprint.from(work.road()).chunkKeys());
                        persistedEdges.add(work.owner());
                    }
                }
            } catch (Throwable failure) {
                firstFinalRoadFailure.compareAndSet(null, failure);
            }
        }
        if (firstFinalRoadFailure.get() != null) {
            throw new IllegalStateException("initial final-road processing failed", firstFinalRoadFailure.get());
        }
        long finalRoadsMs = elapsedMs(finalRoadsStartedAt);
        int roadCount = Math.max(1, connected.size());
        LOGGER.info("【最终道路处理数量与耗时】 " + "RoadWeaver final road processing timing: roads={} changed={} rejected={} wallMs={} "
                        + "avgMs={} slowestMs={} threads={}",
                connected.size(), finalRoadChanged.get(), finalRoadRejected.get(), finalRoadsMs,
                (finalRoadTotalNanos.get() / roadCount) / 1_000_000L,
                finalRoadSlowestNanos.get() / 1_000_000L,
                ThreadPoolManager.currentSharedRuntimeThreads());
        InitialGenerationProgressTracker.recordConstructionChunksDone(connected.size());
        long persistStartedAt = System.nanoTime();
        RoadShardStorage.addRoads(level, roadsToPersist);
        long persistMs = elapsedMs(persistStartedAt);
        LOGGER.info("【初始道路后处理和持久化耗时】 " + "RoadWeaver initial post-processing timing: mergeMs={} finalRoadsMs={} persistMs={} "
                        + "totalMs={} persistedRoads={} constructionChunks={}",
                mergeMs, finalRoadsMs, persistMs, elapsedMs(postProcessStartedAt),
                roadsToPersist.size(), constructionChunks.size());
        return new PersistedBatch(Set.copyOf(persistedEdges), Set.copyOf(constructionChunks));
    }

    public static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    public static int protectedGapRuns(RoadData road) {
        if (road == null || road.spans() == null) return 0;
        int runs = 0;
        for (RoadSpan span : road.spans()) {
            if (span != null && span.type() == SpanType.PROTECTED_GAP) runs++;
        }
        return runs;
    }

    public static double protectedGapBlocks(RoadData road) {
        if (road == null || road.spans() == null) return 0.0D;
        double blocks = 0.0D;
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            if (span.start() == null || span.end() == null) continue;
            blocks += Math.hypot(span.end().getX() - span.start().getX(),
                    span.end().getZ() - span.start().getZ());
        }
        return blocks;
    }

    public static boolean leavesFewerGaps(RoadData candidate, RoadData reference) {
        if (candidate == null) return false;
        if (reference == null) return true;
        int candidateRuns = protectedGapRuns(candidate);
        int referenceRuns = protectedGapRuns(reference);
        if (candidateRuns != referenceRuns) return candidateRuns < referenceRuns;
        return protectedGapBlocks(candidate) < protectedGapBlocks(reference) - 0.5D;
    }

    public static FinalRoadWork processFinalRoad(ServerLevel level,
                                                   RoadData connectedRoad,
                                                   Map<PlannedPathKey, RoadData> originalsByEdge,
                                                   StreamsReflowingCompat.SamplingSession streams,
                                                   Set<Long> refinedRoadFingerprints) {
        TerrainSamplingCache roadsideCache = new TerrainSamplingCache();
        DetailedRoadGenerationStats.beginRouteTiming(connectedRoad.ownerA2dKey() + "->" + connectedRoad.ownerB2dKey());
        try {
            RoadData road = RoadDataValidator.normalizeDuplicateJunctions(connectedRoad);
                // T-19 一致性边界:junction 合并/normalize 未改变道路时,Road.generateRoad
                // 产出的最终快照(已经过 continuity 修复、validateFinalRoad、水/结构校验)
                // 就是施工与最终校验的唯一输入。阶段二不得用相同阈值重测重改——
                // 旧的 postJunctionRepair 无条件重跑 continuity 扫描,对已被规划接受
                // 的中心切/填(maxCutDepth/maxFillDepth ≤ buildable 上限)反复触发
                // "final validation filling missing terrain columns" 与高度二次修改。
                // 只有合并/normalize 真正改变了道路时才允许阶段二修复。
                boolean alreadyRefined = refinedRoadFingerprints.contains(
                        RoadShardStorage.computeFingerprint(connectedRoad));
                if (road != null && road.hasOwnerPair() && road != connectedRoad && !alreadyRefined) {
                    RoadData postJunctionRepair = RoadPathCalculator.repairAfterNetworkMerge(
                            road, level, roadsideCache,
                            new StructureConnection(
                                    PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                                    PlanningUtils.posFrom2dKey(road.ownerB2dKey()),
                                    ConnectionStatus.GENERATING));
                    if (postJunctionRepair == null) {
                        LOGGER.error("【合并后施工连续性无法修复，拒绝该路线】 " + "RoadWeaver rejected initial merged road because construction continuity repair failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        LOGGER.warn("【路线被过滤：合并后施工连续性修复失败】 " + "RoadWeaver route filtered: reason=merged_construction_continuity_repair_failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                    road = postJunctionRepair;
                }
                // Junction merging and height repair can change the physical
                // water/tunnel/bridge spans. Reconcile spans once more before
                // the final validators inspect or persist the merged road.
                if (road != null && road != connectedRoad) {
                    RoadData reconciled = RoadPlanConsistencyService.reconcile(
                            level, road, roadsideCache, null, streams,
                            ConfigService.get().bridgeMinWaterDepth());
                    if (reconciled == null) {
                        LOGGER.error("【最终跨度协调失败，拒绝初始路线】 " + "RoadWeaver rejected initial merged road during final span reconciliation owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        LOGGER.warn("【路线被过滤：最终跨度协调失败】 " + "RoadWeaver route filtered: reason=final_span_reconcile_failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                    road = reconciled;
                }
                RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(road);
                if (!validation.valid() && "invalid-height-step".equals(validation.reason())) {
                    int repairedIndex = validation.index();
                    RoadData smoothed = RoadDataValidator.repairFinalHeightSteps(road);
                    if (smoothed != road) {
                        road = smoothed;
                        validation = RoadDataValidator.validateFinalRoad(road);
                        LOGGER.info("【已修复最终道路的局部纵断面】 " + "RoadWeaver repaired finalized local height profile owners={} -> {} index={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), repairedIndex);
                    }
                }
                if (!validation.valid()) {
                    LOGGER.error("【最终道路数据无效，拒绝写入】 " + "RoadWeaver rejected invalid finalized initial road owners={} -> {} "
                                    + "reason={} index={} pos={} detail={}",
                            road == null ? "null" : road.ownerA2dKey(),
                            road == null ? "null" : road.ownerB2dKey(),
                            validation.reason(), validation.index(),
                            validation.position(), validation.detail());
                    LOGGER.warn("【路线被过滤：最终道路数据无效】 " + "RoadWeaver route filtered: reason=invalid_finalized_initial_road:{} owners={} -> {}",
                            validation.reason(), road == null ? "null" : road.ownerA2dKey(),
                            road == null ? "null" : road.ownerB2dKey());
                    return new FinalRoadWork(ownerKey(connectedRoad), null);
                }
                FinalRoadWaterValidator.ValidationResult waterValidation =
                        road == connectedRoad ? FinalRoadWaterValidator.ValidationResult.success()
                                : timedFinalWaterValidation(level, road, roadsideCache, streams);
                if (!waterValidation.safe()) {
                    PlannedPathKey edge = ownerKey(road);
                    RoadData original = originalsByEdge.get(edge);
                    FinalRoadWaterValidator.ValidationResult originalWater = original != null && original != road
                            ? FinalRoadWaterValidator.validate(level, original, roadsideCache, streams)
                            : null;
                    if (originalWater != null && originalWater.safe()) {
                        LOGGER.warn("【路口改写导致入水，保留已通过校验的原路】 " + "RoadWeaver discarded underwater initial junction rewrite and retained the "
                                        + "validated original road owners={} -> {} collision={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), waterValidation.collision());
                        road = original;
                    } else {
                        FinalRoadWaterValidator.Collision collision = waterValidation.collision();
                        // 0.8.322：循环标记保护缺口（旧实现只标一格，多个水洼时整条路仍被拒）。
                        RoadData gapped = collision != null
                                && "ordinary_construction_in_water".equals(waterValidation.reason())
                                ? FinalRoadWaterValidator.retainProtectedGaps(
                                        road, FinalRoadWaterValidator.MAX_PROTECTED_GAP_FALLBACK,
                                        candidate -> FinalRoadWaterValidator.validate(
                                                level, candidate, roadsideCache, streams))
                                : null;
                        if (gapped != null) {
                            road = gapped;
                            LOGGER.warn("【初始路线保留河流保护缺口】 " + "RoadWeaver retained initial river protection gaps owners={} -> {} "
                                            + "firstSegment={} pos={} source={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(),
                                    collision == null ? -1 : collision.segmentIndex(),
                                    collision == null ? null : collision.position(),
                                    collision == null ? null : collision.source());
                        } else {
                            LOGGER.error("【最终初始道路入水，拒绝该路线】 " + "RoadWeaver rejected finalized initial road in water owners={} -> {} reason={} "
                                            + "segment={} pos={} targetY={} waterSurfaceY={} bedY={} span={} source={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), waterValidation.reason(),
                                    collision == null ? -1 : collision.segmentIndex(),
                                    collision == null ? null : collision.position(),
                                    collision == null ? null : collision.targetY(),
                                    collision == null ? null : collision.waterSurfaceY(),
                                    collision == null ? null : collision.bedY(),
                                    collision == null ? null : collision.activeSpan(),
                                    collision == null ? null : collision.source());
                            LOGGER.warn("【路线被过滤：最终道路入水】 " + "RoadWeaver route filtered: reason=finalized_initial_road_in_water:{} owners={} -> {}",
                                    waterValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                            return new FinalRoadWork(ownerKey(connectedRoad), null);
                        }
                    }
                }
                FinalRoadStructureValidator.ValidationResult structureValidation =
                        road == connectedRoad ? FinalRoadStructureValidator.ValidationResult.success()
                                : timedFinalStructureValidation(level, road, roadsideCache, streams);
                if (!structureValidation.safe()) {
                    RoadData gapped = FinalRoadStructureValidator.retainVerifiedBuildingGaps(
                            level, new StructureConnection(
                                    PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                                    PlanningUtils.posFrom2dKey(road.ownerB2dKey()),
                                    ConnectionStatus.GENERATING),
                            road, roadsideCache, streams);
                    if (gapped != null) {
                        FinalRoadStructureValidator.ValidationResult gapValidation =
                                FinalRoadStructureValidator.validate(level, gapped, roadsideCache, streams);
                        if (gapValidation.safe()) {
                            LOGGER.warn("【初始路网校验后保留已确认建筑的保护缺口】 " + "RoadWeaver retained verified-building protection gaps after initial network junction validation owners={} -> {}",
                                    road.ownerA2dKey(), road.ownerB2dKey());
                            road = gapped;
                            structureValidation = gapValidation;
                        }
                    }
                }
                if (!structureValidation.safe()) {
                    PlannedPathKey edge = ownerKey(road);
                    RoadData original = originalsByEdge.get(edge);
                    if (original != null && original != road) {
                        FinalRoadStructureValidator.ValidationResult originalValidation =
                                FinalRoadStructureValidator.validate(level, original, roadsideCache, streams);
                        if (originalValidation.safe()) {
                            LOGGER.warn("【路口改写不安全，保留已通过校验的原路】 " + "RoadWeaver discarded unsafe initial junction rewrite and retained the "
                                            + "validated original road owners={} -> {} collision={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                            road = original;
                        } else {
                            LOGGER.error("【初始路线最终施工体积校验失败，拒绝该路线】 " + "RoadWeaver rejected initial road after final construction-volume validation "
                                            + "owners={} -> {} collision={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                            LOGGER.warn("【路线被过滤：施工体积与保护区域相交】 " + "RoadWeaver route filtered: reason=initial_construction_volume_intersection:{} owners={} -> {}",
                                    structureValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                            return new FinalRoadWork(ownerKey(connectedRoad), null);
                        }
                    } else {
                        LOGGER.error("【初始路线最终施工体积校验失败，拒绝该路线】 " + "RoadWeaver rejected initial road after final construction-volume validation "
                                        + "owners={} -> {} collision={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                        LOGGER.warn("【路线被过滤：施工体积与保护区域相交】 " + "RoadWeaver route filtered: reason=initial_construction_volume_intersection:{} owners={} -> {}",
                                structureValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                }
                markRoadCorridorPrepared(road.roadSegmentList());
                try {
                    RandomSource random = RandomSource.create(level.getSeed()
                            ^ RoadShardStorage.computeFingerprint(road));
                    long roadsideStructuresStartedAt = System.nanoTime();
                    RoadsideStructurePrecomputer.precomputeStructures(level, road.roadSegmentList(), road.spans(),
                            road.width(), roadsideCache, null, random, road.targetY());
                    DetailedRoadGenerationStats.recordRoadsideStructure(System.nanoTime() - roadsideStructuresStartedAt);
                    long roadsideVillagesStartedAt = System.nanoTime();
                    RoadsideVillagePrecomputer.precomputeVillages(level, road.roadSegmentList(), road.spans(),
                            road.width(), roadsideCache, null, random, road.targetY());
                    DetailedRoadGenerationStats.recordRoadsideVillage(System.nanoTime() - roadsideVillagesStartedAt);
                } catch (RuntimeException roadsideFailure) {
                    LOGGER.error("【道路已处理，失败的沿路内容已跳过】 " + "RoadWeaver finalized initial road but skipped failed roadside content owners={} -> {}",
                            road.ownerA2dKey(), road.ownerB2dKey(), roadsideFailure);
                }
            return new FinalRoadWork(ownerKey(road), road);
        } finally {
            roadsideCache.clear();
            DetailedRoadGenerationStats.logRouteTiming();
        }
    }

    public static FinalRoadWaterValidator.ValidationResult timedFinalWaterValidation(
            ServerLevel level, RoadData road, TerrainSamplingCache cache,
            StreamsReflowingCompat.SamplingSession streams) {
        long startedAt = System.nanoTime();
        try {
            return FinalRoadWaterValidator.validate(level, road, cache, streams);
        } finally {
            DetailedRoadGenerationStats.RouteTiming timing = DetailedRoadGenerationStats.routeTiming();
            if (timing != null) timing.addValidation(System.nanoTime() - startedAt);
        }
    }

    public static FinalRoadStructureValidator.ValidationResult timedFinalStructureValidation(
            ServerLevel level, RoadData road, TerrainSamplingCache cache,
            StreamsReflowingCompat.SamplingSession streams) {
        long startedAt = System.nanoTime();
        try {
            return FinalRoadStructureValidator.validate(level, road, cache, streams);
        } finally {
            DetailedRoadGenerationStats.RouteTiming timing = DetailedRoadGenerationStats.routeTiming();
            if (timing != null) timing.addValidation(System.nanoTime() - startedAt);
        }
    }
}
