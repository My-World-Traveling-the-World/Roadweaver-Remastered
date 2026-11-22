/* 文件职责：初始规划阻塞等待循环 —— 只等结果、更新状态、处理超时。 */
package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.core.model.ProfileConflict;

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
import net.shiroha233.roadweaver.persistence.files.InitialGenerationCheckpointStore;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
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
import static net.shiroha233.roadweaver.generation.InitialRouteSubmitOps.*;
import static net.shiroha233.roadweaver.generation.InitialRoutePersistOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanLifecycleOps.*;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.generation.InitialGenManager.*;
import static net.shiroha233.roadweaver.generation.InitialRouteSubmitOps.*;
import static net.shiroha233.roadweaver.generation.InitialRoutePersistOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanLifecycleOps.*;

/**
 * 初始规划的阻塞等待循环。
 *
 * <h2>它只做等待，不做决策</h2>
 * <p>这一大段跑的是「提交一点 → 等一点 → 更新一点状态 → 再提交」的循环，
 * 外加超时与中止处理。<b>「接哪条规划链、用哪个算法」不在这里</b> ——
 * 那些决策留在 {@code InitialGenManager.begin} 与批次提交处。</p>
 *
 * <h2>★ 为什么循环必须有硬超时</h2>
 * <p>异步规划某一步卡住时，没有超时就会永久阻塞服务器线程 ——
 * 玩家看到的是「世界载入卡在 0%」。所以每轮都检查截止时间，
 * 超时就走中止路径并留下日志（{@code abort}）。</p>
 *
 * <h2>★ 状态更新要与等待同节奏</h2>
 * <p>等待期间每轮都要更新进度（done/total），否则 UI 上的数字会长时间不动，
 * 看起来像卡死 —— 即使内部还在正常工作。</p>
 *
 * <p>本类是从 2174 行的 {@code InitialGenManager} 里<b>纯搬迁</b>出来的，
 * 超时阈值与轮询节奏一字未改（含缩进）。</p>
 */
public final class InitialPlanWaiter {
    public static void blockUntilDoneInternal(ServerLevel level) {
        if (!active || level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        try {
            WorldDataProvider provider = WorldDataProvider.getInstance();

            List<StructureConnection> list = provider.getStructureConnections(level);
            if (list == null || list.isEmpty()) {
                active = false;
                InitialGenerationProgressTracker.finish();
                return;
            }
            List<StructureConnection> roadTasks = filterPlanned(list);
            // B 阶段：规划快照已就绪（planRectAsync 已为 incoming 连接准备），在规划波次
            // 开始之前过滤"村庄 V ↔ V 结构盒内的未知端点"连接。被过滤的连接不进规划、
            // 不进施工、不进局部修复；安全阀触发时整体放弃并只打告警。
            EndpointFilterOutcome endpointFilter =
                    StructureAvoidanceService.filterUnknownEndpointInsideVillage(level, roadTasks);
            if (endpointFilter.filteredCount() > 0) {
                roadTasks = endpointFilter.kept();
                markFilteredConnections(provider, level, endpointFilter.filtered());
            }
            final int filteredRouteCount = endpointFilter.filteredCount();
            if (filteredRouteCount > 0) {
                LOGGER.info("【初始路线过滤与保留数量】 " + "RoadWeaver initial route filter summary: filtered={} kept={}",
                        filteredRouteCount, roadTasks.size());
            }
            total.set(roadTasks.size());
            // 0.8.355：把「哪些阶段靠持久化数据跳过了」明确打出来。玩家跑 1024 区块时
            // 单是粗采样就 40 分钟、河流预热 30 分钟，必须能一眼确认没有重算。
            logPersistedReuse(level, roadTasks.size());

            InitialGenerationProgressTracker.enterStage(InitialGenerationStage.ROAD_GENERATION, "generating_roads");
            InitialGenerationProgressTracker.updateRetryStates(0, 0);
            InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
            // 0.8.355：进入道路生成阶段即落一次断点，重启后能定位到该阶段。
            recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION, 0, roadTasks.size());
            LOGGER.info("【开始初始道路生成阶段，逐路记录检查点】 " + "RoadWeaver initial road generation phase starting: plannedRoutes={} (alreadyCompleted={} "
                            + "failed={} filtered={} protectedGap={}) - a checkpoint is written after every route "
                            + "so an unclean shutdown can resume instead of restarting.",
                    roadTasks.size(), countByStatus(list, ConnectionStatus.COMPLETED),
                    countByStatus(list, ConnectionStatus.FAILED), countByStatus(list, ConnectionStatus.FILTERED),
                    countByStatus(list, ConnectionStatus.PROTECTED_GAP));

            Set<Long> initialConstructionChunks = Set.of();
            if (!roadTasks.isEmpty()) {
                // Publish the original route phase before any startup-wide
                // river/height prewarm.  Otherwise the HUD shows the aggregate
                // route total while all three phase rows are still zero.
                InitialGenerationProgressTracker.beginRoutePhase(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL, roadTasks.size(),
                        "waiting_for_initial_routes");
                // 河流预热 -> 长城预热 -> 道路预热. The order is the whole point, and the wall has to
                // come before the road's own prewarm, not between that prewarm and the generation: with
                // the gate after prewarmInitialRiverPlates the road had already read the world and
                // started loading structure starts (6331 of them in a measured session) while the wall
                // was still sampling, so the two fought for the same cores and the wall's own stages
                // crawled. Nothing of the road's runs until the wall publishes.
                awaitGreatWallPrewarm(level);
                List<BlockPos> prewarmedRoutePositions = prewarmInitialRiverPlates(level, roadTasks);
        // 河流预热完成 —— 替代 great-wall 往 prewarmInitialRiverPlates 上打的
        // @Pseudo @Mixin(require = 1)（那条 require=1 意味着本方法一改名，长城就类加载崩溃）。
        net.shiroha233.roadweaver.apiimpl.ApiBackendImpl.fireRiversPrewarmed();
                DetailedRoadGenerationStats.reset();
                long detailedGenerationStartedAt = System.nanoTime();
                InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL, "generating_original_routes");

                Map<PlannedPathKey, GenResult> resolved = new HashMap<>();
                // A protected-gap route is a valid last resort, but it must
                // still get one chance to use a replacement structure first.
                Map<PlannedPathKey, GenResult> protectedGapFallbacks = new HashMap<>();
                // 后续没有可用局部路径时不以空失败覆盖先前的可重放证据。
                Map<PlannedPathKey, ProfileConflict> profileFailures =
                        new HashMap<>();
                List<RoadData> generatedRoads = new ArrayList<>();
                List<StructureConnection> remaining = new ArrayList<>(roadTasks);
                int configuredStep = ConfigService.get().pathfindingCost().effectivePathGridStep();
                int[] startupBounds = ConflictWindowOps.initialSamplingBounds(level, roadTasks);
                List<Integer> tiers = RoutePrecisionRetryPolicy.initialBatchSteps(configuredStep);
                int candidateRetained = 0;

                for (int tierIndex = 0; tierIndex < tiers.size() && !remaining.isEmpty(); tierIndex++) {
                    int step = tiers.get(tierIndex);
                    // A route that survived the first attempt is now in the
                    // local precision-retry queue. Publish this before the
                    // workers start so the HUD does not look idle between
                    // retry waves.
                    // initialBatchSteps() always yields exactly one tier, so
                    // the old tierIndex > 0 local-repair branch here was
                    // unreachable and has been removed; local repair runs as
                    // its own explicit wave below.
                    InitialGenerationProgressTracker.updateRetryStates(
                            tierIndex == 0 ? 0 : remaining.size(), 0);
                    if (tierIndex == 0) {
                        InitialGenerationProgressTracker.beginRoutePhase(
                                InitialGenerationProgressTracker.RoutePhase.ORIGINAL, remaining.size(),
                                "sampling");
                    }
                    // Batch barrier: finish terrain sampling/path planning for
                    // every route in this precision wave before any road
                    // generation worker starts. Without this barrier each
                    // route enters Road.step while still loading its own
                    // coarse tiles, causing shared sampler contention and
                    // silent waits at progress=0.
                    if (tierIndex == 0) {
                        await(level, RouteRefinementOps.refineInitialBatchAsync(
                                level, startupBounds, remaining, step));
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL,
                        "planning");
                    }
                    // Pre-plan every unknown endpoint's agreed height from all
                    // incoming routes before any road is generated, so roads
                    // into the same unknown node share one height.
                    if (tierIndex == 0) {
                        RoadPlanningService.preplanUnknownEndpointHeights(level, remaining);
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            tierIndex == 0
                                    ? InitialGenerationProgressTracker.RoutePhase.ORIGINAL
                                    : InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            "generating");
                    InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                    BatchGenerationResult batch = submitAndCollectAtStep(level, remaining, step, tierIndex > 0,
                            tierIndex == 0 ? prewarmedRoutePositions : null);
                    generatedRoads.addAll(batch.roads());
                    List<StructureConnection> next = new ArrayList<>();
                    // 0.8.355：逐连接细粒度日志 + 断点。玩家跑 1024 区块时道路阶段以小时计，
                    // 需要能一眼看出「现在做到第几条、每条成没成、失败原因是什么」，
                    // 并且每条处理完立刻落盘，崩溃最多丢最后一条。
                    int processedInRound = 0;
                    for (StructureConnection task : remaining) {
                        GenResult result = batch.results().get(PlannedPathKey.of(task));
                        rememberProfileConflict(profileFailures, result);
                        processedInRound++;
                        String outcome;
                        if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                && result.road() != null) {
                            boolean endpointOnlyGap = hasOnlyEndpointProtectedGaps(
                                    level, task, result.road());
                            if (!result.road().hasProtectedGap() || endpointOnlyGap) {
                                outcome = "COMPLETED" + (endpointOnlyGap ? "(endpoint-only gap)" : "");
                            } else {
                                outcome = "COMPLETED(protected gap, handed to local repair)";
                            }
                            if (result.road().hasProtectedGap() && !endpointOnlyGap) {
                                if (tierIndex == 0) {
                                    if (!ConfigService.get().planning().localRepairOnlyOnUnavoidable()) {
                                        // Config switch: keep the protected-gap
                                        // route as-is right after the initial
                                        // batch and skip the local-repair wave
                                        // entirely (wall-clock saving on
                                        // gap-heavy batches). Reverting the
                                        // config restores the exact prior
                                        // local-repair handoff.
                                        resolved.put(result.originalKey(), result);
                                        InitialGenerationProgressTracker.routePhaseRetained();
                                        LOGGER.info("【按配置保留保护缺口，不执行局部修复】 " + "RoadWeaver retained protected-gap route without "
                                                        + "local repair (localRepairOnlyOnUnavoidable=false) "
                                                        + "connection={}->{}",
                                                task.from(), task.to());
                                    } else {
                                        int gapRuns = protectedGapRuns(result.road());
                                        if (gapRuns < MIN_LOCAL_REPAIR_GAP_RUNS) {
                                            // 只有 1 个断口的短缺口不值得再跑一次 step=2 局部修复
                                            // （真机数据：这种路修完断口反而从 1 变 4/7）。
                                            resolved.put(result.originalKey(), result);
                                            InitialGenerationProgressTracker.routePhaseRetained();
                                            LOGGER.info("【保护缺口较小，保留路线并跳过局部修复】 " + "RoadWeaver kept small protected-gap route without local repair "
                                                            + "gapRuns={} connection={}->{}",
                                                    gapRuns, task.from(), task.to());
                                        } else {
                                            RoadPlanningService.rememberProtectedGapHints(task, result.road());
                                            RoadPlanningService.rebindGeneratedRouteForRetry(level, task, result.road(),
                                                    RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep).get(0));
                                            protectedGapFallbacks.put(result.originalKey(), result);
                                            removeGeneratedRoad(generatedRoads, result.road());
                                            next.add(task);
                                            outcome = "PROTECTED_GAP(h" + gapRuns + " runs -> fine regenerate)";
                                            InitialGenerationProgressTracker.routePhaseRetained();
                                            LOGGER.info("【保护缺口路线已交给精细重算】 " + "RoadWeaver initial protected-gap route handed to fine regenerate connection={}->{}",
                                                    task.from(), task.to());
                                        }
                                    }
                                } else {
                                    resolved.put(result.originalKey(), result);
                                    outcome = "PROTECTED_GAP(retained after local repair)";
                                    LOGGER.info("【局部修复后仍有可用路线，保留保护缺口】 " + "RoadWeaver local repair retained usable protected-gap route connection={}->{}",
                                            task.from(), task.to());
                                }
                            } else {
                                if (endpointOnlyGap) {
                                    LOGGER.info("【仅端点存在保护缺口，保留原拓扑连接】 " + "RoadWeaver retained endpoint-only protected gap without topology replacement connection={}->{}",
                                            task.from(), task.to());
                                }
                                resolved.put(result.originalKey(), result);
                            }
                        } else {
                            next.add(task);
                            outcome = result == null ? "PENDING(no result)" : "PENDING(retry)";
                        }
                        // 每条连接一行：序号 / 端点 / 结果 / 累计进度。
                        LOGGER.info("【初始道路当前连接处理进度】 " + "RoadWeaver initial connection [{}/{}] step={} {} -> {} : {} (totalDone={} pending={})",
                                processedInRound, remaining.size(), step,
                                task.from(), task.to(), outcome,
                                resolved.size(), next.size());
                        // 每条都更新断点：崩溃最多丢“最后一条”。
                        recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION,
                                resolved.size(), roadTasks.size());
                    }
                    remaining = next;
                    done.set(resolved.size());
                    // Remaining routes are pending retries, not terminal failures.
                    failed.set(0);
                    InitialGenerationProgressTracker.updateRetryStates(
                            tierIndex + 1 < tiers.size() ? remaining.size() : 0, 0);
                    InitialGenerationProgressTracker.updateConnections(
                            roadTasks.size(), 0, done.get(), 0);
                    LOGGER.info("【本轮初始道路请求和完成数量】 " + "RoadWeaver initial generation round: step={} requested={} completed={} remaining={}",
                            step, batch.results().size(), resolved.size(), remaining.size());
                }

                InitialGenerationProgressTracker.settleRoutePhase(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL,
                        resolved.size(), 0, 0, remaining.size(),
                        remaining.isEmpty() ? "finished" : "handed_to_local");
                int waveClean = 0;
                int waveProtectedGap = 0;
                for (GenResult waveResult : resolved.values()) {
                    if (waveResult.road() == null) continue;
                    if (waveResult.road().hasProtectedGap()) waveProtectedGap++;
                    else waveClean++;
                }
                LOGGER.info("【初始道路波次结果与耗时】 " + "RoadWeaver initial route wave summary: step={} requested={} resolved={} clean={} "
                                + "protectedGap={} handedToLocalRepair={} filtered={} wallMs={}",
                        configuredStep, roadTasks.size(), resolved.size(), waveClean, waveProtectedGap,
                        remaining.size(), filteredRouteCount, elapsedMs(detailedGenerationStartedAt));

                // The first batch is now complete.  Unresolved routes get one
                // explicit local repair wave before any topology candidate is
                // considered.  This phase is deliberately separate from the
                // full-route precision tiers so its HUD counters describe the
                // actual failed-route repair work.
                if (!remaining.isEmpty() && tiers.size() == 1) {
                    int localInput = remaining.size();
                    int localResolvedBefore = resolved.size();
                    int[] localRetained = {0};
                    int[] localCompleted = {0};
                    Set<PlannedPathKey> buildingConflictRoutes = new HashSet<>();
                    // The rebound path cache entry lives under the local-repair
                    // step, so the conflict probe must query the same step or
                    // the fingerprint check silently misses it.
                    int localProbeStep =
                            RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep).get(0);
                    for (StructureConnection task : remaining) {
                        PlannedPathKey key = PlannedPathKey.of(task);
                        if (protectedGapFallbacks.containsKey(key)
                                || RoadPlanningService.hasBuildingConflict(level, task, localProbeStep)) {
                            buildingConflictRoutes.add(key);
                        }
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            InitialGenerationProgressTracker.RoutePhase.ORIGINAL, "finished");
                    InitialGenerationProgressTracker.beginRoutePhase(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, remaining.size(),
                            "sampling");
                    InitialGenerationProgressTracker.updateRetryStates(remaining.size(), 0);
                    long localRepairWaveStartedAt = System.nanoTime();
                    for (int step : RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep)) {
                        InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                        // 0.8.318：把局部修复波次拆成"splice 阶段"与"整条重生成阶段"两段计时。
                        // 真机该波次 wallMs=224621，但 24 次 splice 本身只花 14s、统一预取 21s，
                        // 剩余约 190s 都在按 step=2 重生成被交接的路。纯观测。
                        long splicePhaseStartedAt = System.nanoTime();
                        await(level, RouteRefinementOps.refineInitialRoutesAsync(
                                level, remaining, step, 100));
                        long splicePhaseMs = elapsedMs(splicePhaseStartedAt);
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                                InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, "planning");
                        InitialGenerationProgressTracker.enterStage(
                                InitialGenerationStage.ROAD_GENERATION,
                                "local_repair_step_" + step);
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                                InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, "generating");
                        long regenerateStartedAt = System.nanoTime();
                        // 0.8.325：局部 splice 没产出 rebound 路径的路，重生成必然立刻失败
                        // （旧代码会照排一次任务再在 worker 里拒收）。这里提交前就剔掉，
                        // 并把"剔了几条 / 实际重生成几条"写进拆分日志，省掉纯白跑的调度。
                        List<StructureConnection> regenerable = new ArrayList<>(remaining.size());
                        int skippedNoRebound = 0;
                        for (StructureConnection task : remaining) {
                            if (RoadGenerationService.hasLocalRepairReboundPath(level, task, step)) {
                                regenerable.add(task);
                            } else {
                                skippedNoRebound++;
                                LOGGER.info("【缺少绑定路径，跳过局部修复重算】 " + "RoadWeaver local repair regenerate skipped: no rebound path "
                                                + "step={} connection={}->{}", step, task.from(), task.to());
                            }
                        }
                        BatchGenerationResult batch = regenerable.isEmpty()
                                ? new BatchGenerationResult(Map.of(), List.of())
                                : submitAndCollectAtStep(level, regenerable, step, true);
                        long regenerateMs = elapsedMs(regenerateStartedAt);
                        LOGGER.info("【局部修复拼接和重算耗时】 " + "RoadWeaver local repair step split: step={} routes={} regenerated={} "
                                        + "skippedNoRebound={} spliceMs={} regenerateMs={}",
                                step, remaining.size(), regenerable.size(), skippedNoRebound,
                                splicePhaseMs, regenerateMs);
                        generatedRoads.addAll(batch.roads());
                        List<StructureConnection> next = new ArrayList<>();
                        int localProcessed = 0;
                        for (StructureConnection task : remaining) {
                            PlannedPathKey taskKey = PlannedPathKey.of(task);
                            GenResult result = batch.results().get(PlannedPathKey.of(task));
                            rememberProfileConflict(profileFailures, result);
                            localProcessed++;
                            String localOutcome;
                            if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                localOutcome = result.road().hasProtectedGap()
                                        ? "COMPLETED(protected gap retained)" : "COMPLETED";
                            } else {
                                localOutcome = "PENDING(after local repair)";
                            }
                            if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                if (result.road().hasProtectedGap()) {
                                    // Building conflicts never enter topology
                                    // candidates. A usable locally repaired
                                    // route with a protected gap is terminal —
                                    // but only when it is not worse than the
                                    // first-wave route we still hold: the
                                    // finer step can find a shorter corridor
                                    // that crosses more buildings (real-machine
                                    // data: 1 run -> 4 and 1 run -> 7 runs).
                                    GenResult fallback = protectedGapFallbacks.get(taskKey);
                                    GenResult chosen = result;
                                    if (fallback != null && fallback.road() != null
                                            && leavesFewerGaps(fallback.road(), result.road())) {
                                        chosen = fallback;
                                        removeGeneratedRoad(generatedRoads, result.road());
                                        generatedRoads.add(fallback.road());
                                        LOGGER.info("【修复结果未更优，保留首轮保护缺口路线】 " + "RoadWeaver kept first-wave gap route over local repair result "
                                                        + "firstRuns={} firstBlocks={} repairedRuns={} repairedBlocks={} connection={}->{}",
                                                protectedGapRuns(fallback.road()),
                                                Math.round(protectedGapBlocks(fallback.road())),
                                                protectedGapRuns(result.road()),
                                                Math.round(protectedGapBlocks(result.road())),
                                                task.from(), task.to());
                                    }
                                    resolved.put(taskKey, chosen);
                                    InitialGenerationProgressTracker.routePhaseRetained();
                                    localRetained[0]++;
                                    LOGGER.info("【建筑局部修复已得到可保留路线，停止候补替换】 " + "RoadWeaver local building repair succeeded with retained gap; candidate handoff skipped connection={}->{}",
                                            task.from(), task.to());
                                } else {
                                    resolved.put(result.originalKey(), result);
                                    localCompleted[0]++;
                                }
                            } else {
                                GenResult protectedFallback = protectedGapFallbacks.get(taskKey);
                                if (protectedFallback != null && protectedFallback.road() != null) {
                                    // The building detour failed. Keep the
                                    // original route and its explicit gap;
                                    // never try a different topology candidate.
                                    generatedRoads.add(protectedFallback.road());
                                    resolved.put(taskKey, protectedFallback);
                                    InitialGenerationProgressTracker.routePhaseRetained();
                                    localRetained[0]++;
                                    LOGGER.warn("【建筑局部修复失败，保留保护缺口并跳过候补】 " + "RoadWeaver building local repair failed; retained protected gap and skipped candidates connection={}->{} reason=see_previous_local_repair_diagnostics",
                                            task.from(), task.to());
                                } else if (!buildingConflictRoutes.contains(taskKey)) {
                                    next.add(task);
                                } else {
                                    LOGGER.error("【建筑局部修复失败且无可保留路线，跳过候补】 " + "RoadWeaver building local repair failed without retainable route; candidates skipped connection={}->{}",
                                            task.from(), task.to());
                                    resolved.put(taskKey, new GenResult(taskKey, task,
                                            RoadGenerationService.InitialTaskResult.FAILED, null));
                                }
                            }
                            // 0.8.355：精细重算波次同样逐条打日志 + 落断点。
                            LOGGER.info("【精细重算连接处理进度】 " + "RoadWeaver fine regenerate connection [{}/{}] step={} {} -> {} : {} "
                                            + "(totalDone={} remaining={})",
                                    localProcessed, remaining.size(), step,
                                    task.from(), task.to(), localOutcome,
                                    resolved.size(), next.size());
                            recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION,
                                    resolved.size(), roadTasks.size());
                        }
                        remaining = next;
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            "finished");
                    done.set(resolved.size());
                    failed.set(remaining.size());
                    InitialGenerationProgressTracker.updateRetryStates(0, 0);
                    InitialGenerationProgressTracker.updateConnections(
                            roadTasks.size(), 0, done.get(), 0);
                    LOGGER.info("【局部修复波次结束】 " + "RoadWeaver local repair wave finished: completed={} remaining={} wallMs={}",
                            resolved.size(), remaining.size(),
                            (System.nanoTime() - localRepairWaveStartedAt) / 1_000_000L);
                    InitialGenerationProgressTracker.settleRoutePhase(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            localCompleted[0],
                            remaining.size(), localRetained[0], 0,
                            "finished");
                }

                // 原边仍报告真实失败；只有能连接已确认分量的补边才另行提交终态验收。
                List<StructureConnection> supplementalConnections = InitialConnectivityRepair.run(
                        level, provider.getStructureConnections(level), remaining, resolved, profileFailures,
                        configuredStep, ConfigService.get().planning().planMaxEdgeLenBlocks(), generatedRoads);

                for (StructureConnection unresolved : remaining) {
                    PlannedPathKey key = PlannedPathKey.of(unresolved);
                    GenResult protectedFallback = protectedGapFallbacks.get(key);
                    if (protectedFallback != null) {
                        resolved.putIfAbsent(key, protectedFallback);
                        generatedRoads.add(protectedFallback.road());
                        LOGGER.warn("【局部修复耗尽，保留原保护缺口路线】 " + "RoadWeaver retained original protected-gap route after local repair exhausted connection={}->{}",
                                unresolved.from(), unresolved.to());
                    } else {
                        resolved.putIfAbsent(key,
                                new GenResult(key, unresolved,
                                        RoadGenerationService.InitialTaskResult.FAILED, null, profileFailures.get(key)));
                    }
                }
                InitialGenerationProgressTracker.updateRetryStates(0, 0);
                int terminalFailures = 0;
                for (GenResult result : resolved.values()) {
                    if (result.result() == RoadGenerationService.InitialTaskResult.FAILED) {
                        terminalFailures++;
                    }
                }
                done.set(Math.max(0, resolved.size() - terminalFailures));
                failed.set(terminalFailures);
                InitialGenerationProgressTracker.updateConnections(
                        roadTasks.size(), 0, done.get(), failed.get());
                BatchGenerationResult finalBatch = new BatchGenerationResult(
                        Map.copyOf(resolved), List.copyOf(generatedRoads));
                DetailedRoadGenerationStats.logSummary(System.nanoTime() - detailedGenerationStartedAt);
                PersistedBatch persisted = persistInitialRoadBatch(level, finalBatch.roads());
                initialConstructionChunks = persisted.chunkKeys();
                batchUpdateConnectionStatus(provider, level, finalBatch.results(), persisted,
                        supplementalConnections);
            }
            ensureInitialConnectionsTerminal(provider, level);
            // Planning is complete, so publish every affected chunk to the
            // deferred construction queue. Construction still happens only
            // when Chunky or a player has already loaded the chunk, but the
            // queue must exist or those planned tunnel chunks are never
            // revisited after world entry.
            DeferredInitialRoadConstructionService.enqueue(level, initialConstructionChunks);
            // Planning, topology reconciliation, validation, and persistence
            // are the world-entry gate. Physical chunk loading and construction
            // remain owned by Chunky or real player-loaded chunks; startup must
            // never force-load the road footprint.
            LOGGER.info("【初始规划就绪，方块施工等待区块实际加载】 " + "RoadWeaver initial planning ready; physical construction deferred to Chunky/player-loaded chunks: chunks={}",
                    initialConstructionChunks.size());
            flushAndFinish(level);
        } catch (Throwable failure) {
            abort("blockUntilDone", failure);
        } finally {
            // 无任务或提前终止也结束这次交接，避免下一次开局拿到上一批路径。
            TerrainSamplingSessions.forLevel(level).clearInitialBand();
        }
    }

    /** 同一原路线有多次尝试，保留最早的实测拒绝；候补边不能覆盖它。 */
    private static void rememberProfileConflict(
            Map<PlannedPathKey, ProfileConflict> failures,
            GenResult result) {
        if (result != null && result.profileConflict() != null) {
            failures.putIfAbsent(result.originalKey(), result.profileConflict());
        }
    }
}
