/* 文件职责：调度道路生成任务并维护规划路径缓存生命周期。 */
package net.shiroha233.roadweaver.generation;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileCache;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.ExistingRoadAttachment;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.ConnectionFallbackService;
import net.shiroha233.roadweaver.planning.ConnectionTopologyReconciler;
import net.shiroha233.roadweaver.planning.ExistingRoadEndpointReuse;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 道路生成调度器，管理 Path 队列的 tick 驱动异步生成
 */
public final class RoadGenerationService {
    /** Keep failures on the original edge for diagnosis; no topology fallback. */
    private static final boolean TOPOLOGY_CANDIDATES_ENABLED = false;
    private RoadGenerationService() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private static final ConcurrentLinkedQueue<StructureConnection> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<PlannedPathKey, Boolean> PROCESSED = new ConcurrentHashMap<>();
    private static final AtomicInteger RUNNING_COUNT = new AtomicInteger();
    /** Default maximum total runtime road generations, including the idle lane. */
    private static final int DEFAULT_RUNTIME_GENERATIONS = 3;
    private static final AtomicInteger RUNTIME_SLOTS = new AtomicInteger(DEFAULT_RUNTIME_GENERATIONS);
    private static final Set<Future<?>> ALL_RUNNING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<PlannedPathKey, RunningTask> RUNNING_TASKS = new ConcurrentHashMap<>();
    private static final CachedPlayerList PLAYER_LIST_CACHE = new CachedPlayerList();
    private static final int PLAYER_LIST_REFRESH_TICKS = 5;
    private static volatile boolean runtimePausedForDimension;
    private static long CURRENT_TICK = 0L;
    /** P1-4: one-shot off-thread warm of the planned paths the queue will read. */
    private static final AtomicBoolean PLANNED_PATH_PREWARM_STARTED = new AtomicBoolean();
    /**
     * P0-9: the topology reconcile result is a pure function of the connection
     * table content, yet {@code refreshQueue} ran it on every tick. Cache the
     * last input/result pair (main-thread only, like {@link #CURRENT_TICK}) so
     * an unchanged table skips the quadratic pass entirely.
     */
    private static ServerLevel reconciledLevel;
    private static List<StructureConnection> reconciledInput;
    private static ConnectionTopologyReconciler.Result reconciledResult;

    public static long currentTick() {
        return CURRENT_TICK;
    }

    private static final ResourceLocation ROAD_CF_ID = ResourceLocation.fromNamespaceAndPath("roadweaver", "road_feature");

    /**
     * 0.9.018（D2 平行双桥「方案 C：后者整体改道」）：把一条路按正式生成链路
     * <b>重算一遍且不落盘</b>，供改道后替换批次里的原件。
     *
     * <p>为什么不自己拼 {@code RoadData}：桥/隧 span、纵断面 {@code targetY}、
     * 高度台阶修复、结构避让、最终校验全都在 {@code Road.generateRoad} 内部，
     * 自己拼一个只有站点列表的 {@code RoadData} 等于跳过这整条收尾链，
     * 产出的路会在纵断面和桥上出问题。这里完整复用同一条链路，改道产物与
     * 正常规划的产物同构。</p>
     *
     * <p>禁行走廊由调用方通过 {@code CorridorBan.withScoped} 在当前线程挂好，
     * 这里不需要也不会传禁令——{@code RoadGenerationService} 内部照常从
     * {@code ConfigService} 取配置，{@code PathfindingCostConfig.snapshot()}
     * 会把线程作用域里的那条禁令取进本次请求。</p>
     *
     * <p>任何失败都返回 {@code null}，调用方据此保留原路（退化为 0.9.017 行为），
     * 绝不因为「改道不成功」把一条路丢掉。</p>
     */
    public static RoadData regenerateRoadWithoutPersist(ServerLevel level, StructureConnection conn) {
        if (level == null || conn == null) return null;
        try {
            var registry = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
            ConfiguredFeature<?, ?> feature = registry.get(ROAD_CF_ID);
            PathFeatureConfig featureConfig = (feature != null && feature.config() instanceof PathFeatureConfig rfc)
                    ? rfc : new PathFeatureConfig();
            RoadGenerationConfig genConfig = RoadGenerationConfig.from(ConfigService.get());
            return new Road(level, conn, featureConfig, genConfig)
                    .generateRoad(genConfig.pathfinding().aStarMaxSteps(), false);
        } catch (RuntimeException failure) {
            LOGGER.warn("RoadWeaver braid reroute could not regenerate {}->{}; keeping the original route",
                    conn.from(), conn.to(), failure);
            return null;
        }
    }

    public static void onServerStarting() {
        ALL_RUNNING.clear();
        RUNNING_TASKS.clear();
        QUEUE.clear();
        PROCESSED.clear();
        RUNNING_COUNT.set(0);
        RUNTIME_SLOTS.set(runtimeGenerationLimit());
        PLAYER_LIST_CACHE.clear();
        runtimePausedForDimension = false;
        PlannedPathCache.clearAll();
        CoarseTerrainTileCache.clearAll();
        clearAll();
        RoadPlanningReadinessIndex.clearRuntime();
        ConnectionDeletionService.clearRuntime();
        RoadPathCalculator.clearRuntimeHints();
    }

    /** Compatibility entry point for non-Fabric loaders; Fabric owns the reset at SERVER_STARTING. */
    public static void onServerStarted() {
        onServerStarting();
    }

    /** Runtime road work is only enabled while a player is in the Overworld. */
    public static void setRuntimePausedForDimension(boolean paused) {
        if (runtimePausedForDimension == paused) return;
        runtimePausedForDimension = paused;
        if (!paused) {
            LOGGER.info("RoadWeaver Overworld runtime road work resumed.");
            return;
        }
        // Invalidate all epoch-bound continuations before cancelling futures.
        // Future.cancel(true) is cooperative; a worker already inside terrain
        // or structure code may otherwise continue and publish stale results
        // after the player returns to the Overworld.
        ThreadPoolManager.invalidateRuntimeEpoch();
        ALL_RUNNING.forEach(f -> { try { if (f != null) f.cancel(true); } catch (Throwable ignored) {} });
        ALL_RUNNING.clear();
        RUNNING_TASKS.values().forEach(task -> { try { if (task.future != null) task.future.cancel(true); } catch (Throwable ignored) {} });
        RUNNING_TASKS.clear();
        QUEUE.clear();
        PROCESSED.clear();
        RUNNING_COUNT.set(0);
        RUNTIME_SLOTS.set(runtimeGenerationLimit());
        LOGGER.info("RoadWeaver runtime roads paused outside minecraft:overworld; world plans retained.");
    }

    public static void onServerStopping() {
        ALL_RUNNING.forEach(f -> f.cancel(true));
        ALL_RUNNING.clear();
        RUNNING_TASKS.clear();
        QUEUE.clear();
        PROCESSED.clear();
        RUNNING_COUNT.set(0);
        RUNTIME_SLOTS.set(runtimeGenerationLimit());
        PLAYER_LIST_CACHE.clear();
        RoadPlanningService.resetAll();
        PlannedPathCache.clearAll();
        CoarseTerrainTileCache.clearAll();
        clearAll();
        RoadPlanningReadinessIndex.clearRuntime();
        ConnectionDeletionService.clearRuntime();
        RoadPathCalculator.clearRuntimeHints();
        IdleRoadGenerationService.onServerStopping();
        reconciledLevel = null;
        reconciledInput = null;
        reconciledResult = null;
        PLANNED_PATH_PREWARM_STARTED.set(false);
    }

    // ==================== tick 调度 ====================

    public static void tick(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        if (runtimePausedForDimension) return;
        CURRENT_TICK++;
        if (PLANNED_PATH_PREWARM_STARTED.compareAndSet(false, true)) {
            ThreadPoolManager.submit(ThreadPoolManager.TaskRole.MAP_READ, () -> prewarmPlannedPaths(level));
        }

        // ★ 任务 C（2026-09-26）：先算出「有没有手绘路线在等施工」，
        //   它决定后面是否豁免暂停、是否给足 worker。
        //
        //   用户要求：「手动道路优先级最高」。
        //   真机日志实证过：不豁免时，手绘施工会被"为性能让路"的暂停
        //   挡住 98 秒才真正开始（玩家点了施工盯着屏幕干等）。
        int playerRoutePending = countPendingPlayerRouteConnections(level);
        net.shiroha233.roadweaver.route.PlayerRoutePriority
                .setActiveCount(level, playerRoutePending);
        final boolean playerRoutePriority = playerRoutePending > 0;
        // ★ 任务 C（2026-09-26）：**安全上限 10 分钟**（用户要求「建完为止 + 安全上限10分钟」）。
        //
        //   正常情况下"建完为止"；但若优先模式持续超过上限，说明可能出了问题
        //   （某条连接反复重试 / 后台被饿死太久）—— 此时**不再豁免暂停**，
        //   让后台规划恢复，避免"玩家施工卡住 + 后台全停"的最坏组合。
        //
        //   ⚠️ **2026-09-26 修 W-4：更正措辞**。原先注释写"施工照常继续"，
        //      这不准确 —— 超限后手绘施工**重新受让路规则约束**，
        //      若此时正好处于暂停状态（例如玩家开着地图），
        //      tick 会在 refreshQueue 前 return，连接**确实会一起被暂停**。
        //      这是**刻意的**：上限的意义正是"不再为手绘破例"，
        //      让后台有机会恢复。玩家的路不会被取消，只是回到正常优先级。
        //
        //   ★ 另：上限值现在是**配置项**
        //      （performance.playerRoutePriorityHardLimitMinutes，0=不限），
        //      真机觉得 10 分钟太短时不用改代码。
        final boolean overLimit = playerRoutePriority
                && net.shiroha233.roadweaver.route.PlayerRoutePriority.isOverHardLimit(level);
        if (overLimit) {
            net.shiroha233.roadweaver.route.PlayerRoutePriority
                    .warnIfOverLimitOnce(level, playerRoutePending);
        }

        if (playerRoutePriority && !overLimit) {
            net.shiroha233.roadweaver.route.PlayerRoutePriority
                    .notePriorityStart(level, null, playerRoutePending);
            // ★ 任务 C：长时优先模式的可观测性（用户要求「超长路线分段提交防止彻底卡死」）。
            //   不设硬上限（用户选「建完为止」），只在持续时间过长时打 warn，
            //   让日志能看出"后台被豁免了很久"。施工本身是分段的（每 tick 一条连接），
            //   所以不会出现单次不可中断的长任务。
            net.shiroha233.roadweaver.route.PlayerRoutePriority
                    .warnIfLongRunning(level, playerRoutePending);
        } else if (!playerRoutePriority) {
            // 没有手绘任务了 ⇒ 退出优先模式（记录持续时长供观察）
            net.shiroha233.roadweaver.route.PlayerRoutePriority
                    .notePriorityEnd(level, null);
        }

        // overLimit 时按普通规则走（受让路暂停约束），不再为手绘开路。
        //
        // ★ 2026-09-26 修 W-2（红线 6）：豁免**不再一刀切**，而是
        //   按**暂停原因**与**配置的豁免集合**比较 —— 协调通道全部走配置层。
        //   默认豁免 PLAYER_INTERACTION / RECOVERY / PLAYER_TRAVEL，
        //   **不含 CHUNKY**（0.8.372 真机取证换来的让路约定）、
        //   **不含 SYSTEM_PRESSURE**（真实 Tick/FPS/内存压力）。
        final boolean exemptPause = playerRoutePriority && !overLimit
                && isPauseExemptForPlayerRoute();

        // ★ 0.8.400：**预加载兜底必须在暂停 return 之前跑**。
        //
        //   ⚠️ 这里的顺序是有意的，别往下挪。
        //   `RouteChunkPreloader.drainPending` 负责「路线连接全部结算后强制加载区块」，
        //   它自身有 0.5 秒节流 + 无待办时零成本快路径，开销可忽略。
        //
        //   为什么不能放在 return 之后：玩家点完施工常常开着地图盯进度，
        //   此时 `pauseScheduling()` 为真 —— 若兜底在 return 之后，
        //   它就永远不执行，正是 W-1「鸡生蛋」死锁的同一形状
        //   （用暂停状态挡住了本该在暂停期也要推进的玩家操作）。
        try {
            net.shiroha233.roadweaver.route.RouteChunkPreloader.drainPending(level);
        } catch (Throwable t) {
            // 预加载兜底失败绝不能让整个调度 tick 中断
            LOGGER.warn("RoadWeaver 预加载兜底检测异常（不影响施工调度）", t);
        }

        if (RuntimeLoadPolicy.pauseScheduling() && !exemptPause) {
            return;
        }
        refreshQueue(level);
        ALL_RUNNING.removeIf(f -> f == null || f.isDone() || f.isCancelled());

        ConcurrentLinkedQueue<StructureConnection> q = QUEUE;
        if (q.isEmpty()) return;

        // Initial road planning uses TaskRole.INITIAL and the startup pool.
        // Runtime work follows the adaptive QoS budget; player travel and
        // interaction reduce this value through RuntimeLoadPolicy.
        //
        // ★ 任务 C：**手绘路线优先时不受让路降级** —— 让路的本意是
        //   「别在玩家需要性能时抢资源」，而"玩家自己点了施工"恰恰是最该
        //   优先执行的操作。所以这里给足预算（仍受硬件上限约束，
        //   不会无限开线程）。
        int limit = (playerRoutePriority && !overLimit && exemptPause)
                ? playerRoutePriorityLimit()
                : Math.min(runtimeGenerationLimit(),
                        Math.max(1, RuntimeLoadPolicy.recommendedRoadWorkerThreads()));
        AtomicInteger cnt = RUNNING_COUNT;

        List<ServerPlayer> players = getCachedPlayers(level);
        if (players == null) return;

        while (cnt.get() < limit) {
            if (q.isEmpty()) break;
            if (!tryAcquireRuntimeSlot()) break;

            StructureConnection conn = pollNearest(q, players, level);
            if (conn == null) {
                releaseRuntimeSlot();
                continue;
            }
            if (RoadPlanningReadinessIndex.shouldReserveForCoordinator(level, conn)) {
                PROCESSED.remove(PlannedPathKey.of(conn));
                releaseRuntimeSlot();
                continue;
            }

            updateConnectionStatus(level, conn, ConnectionStatus.GENERATING);

            final StructureConnection task = conn;
            final PlannedPathKey taskKey = PlannedPathKey.of(task);
            final RunningTask runningTask = new RunningTask();
            cnt.incrementAndGet();
            long epoch = ThreadPoolManager.currentEpoch();

            try {
                Future<?> fut = ThreadPoolManager.submit(ThreadPoolManager.TaskRole.GENERATION, () -> {
                    try {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (!ThreadPoolManager.isEpoch(epoch)) return;
                        safeGenerate(level, task, epoch);
                    } finally {
                        finishRunningTask(taskKey, runningTask);
                    }
                });
                runningTask.future = fut;
                RUNNING_TASKS.put(taskKey, runningTask);
                if (runningTask.released.get()) RUNNING_TASKS.remove(taskKey, runningTask);
                if (ConnectionDeletionService.shouldAbort(level, task)) {
                    fut.cancel(true);
                    finishRunningTask(taskKey, runningTask);
                }
                ALL_RUNNING.add(fut);
            } catch (Throwable submitFailure) {
                cnt.decrementAndGet();
                releaseRuntimeSlot();
                PROCESSED.remove(PlannedPathKey.of(task));
                updateConnectionStatus(level, task, ConnectionStatus.PLANNED);
                throw submitFailure;
            }
        }
    }

    static boolean tryAcquireRuntimeSlot() {
        while (true) {
            int available = RUNTIME_SLOTS.get();
            if (available <= 0) return false;
            if (RUNTIME_SLOTS.compareAndSet(available, available - 1)) return true;
        }
    }

    static void releaseRuntimeSlot() {
        int limit = runtimeGenerationLimit();
        RUNTIME_SLOTS.updateAndGet(value -> Math.min(limit, value + 1));
    }

    static int runtimeGenerationLimit() {
        try {
            return Math.max(1, Math.min(128,
                    ConfigService.get().performance().maxConcurrentGenerations()));
        } catch (Throwable ignored) {
            return DEFAULT_RUNTIME_GENERATIONS;
        }
    }

    public static void cancelConnection(StructureConnection connection) {
        if (connection == null) return;
        PlannedPathKey key = PlannedPathKey.of(connection);
        QUEUE.removeIf(candidate -> PlannedPathKey.of(candidate).equals(key));
        PROCESSED.remove(key);
        RunningTask task = RUNNING_TASKS.remove(key);
        if (task != null) {
            Future<?> future = task.future;
            if (future != null) future.cancel(true);
            finishRunningTask(key, task);
        }
    }

    private static void finishRunningTask(PlannedPathKey key, RunningTask task) {
        if (task == null || !task.released.compareAndSet(false, true)) return;
        RUNNING_TASKS.remove(key, task);
        RUNNING_COUNT.updateAndGet(value -> Math.max(0, value - 1));
        releaseRuntimeSlot();
    }

    // ==================== 纯生成逻辑（无副作用） ====================

    public static boolean generateTask(ServerLevel level, StructureConnection conn) {
        return generateTaskWithFallback(level, conn, true).outcome().success();
    }

    /**
     * Generates a connection and retains the effective endpoint selected by
     * bounded topology fallback. Callers that own status persistence must use
     * this instead of the boolean convenience method.
     */
    public static GenerationResult generateTaskResult(ServerLevel level, StructureConnection conn) {
        GenerationAttempt attempt = generateTaskWithFallback(level, conn, true);
        if (attempt.outcome().success()) {
            return new GenerationResult(true, attempt.connection(), attempt.outcome().road());
        }
        // 0.9.079：A* 彻底失败后，尝试把端点就近改接到「已有道路」上再生成一次。
        //
        // 真机取证：23 条连接里 3 条报 all precision tiers / local repair / fallbacks exhausted，
        // 但它们两端距最近已有道路只有 0 / 16.6 / 49.2 / 55.0 / 64.3 格（只有一条终点在 872 格外）。
        // 也就是说路早就修到旁边了 —— 缺的不是「可行路线」，而是「允许它接进现有路网」。
        // 改接后生成的是几十格接入短段，几乎必然成功。
        GenerationAttempt attached = tryAttachAndRegenerate(level, conn, true);
        if (attached != null) {
            return new GenerationResult(true, attached.connection(), attached.outcome().road());
        }
        return new GenerationResult(false, attempt.connection(), attempt.outcome().road());
    }

    /**
     * 就近接入已有道路并重新生成；不适用时返回 {@code null}（保持原失败结果）。
     *
     * <p><b>0.9.082：两个生成入口共用的底层实现。</b>原来只挂在
     * {@link #generateTaskResult} 上，而初始生成走的是
     * {@link #generateInitialRoad} / {@link #generateInitialRoadAtStep} ——
     * 真机日志 {@code attaching failed connection} 恰好 0 次就是这么来的：
     * 24 条初始连接失败 4 条，接入逻辑一次都没被执行过。</p>
     */
    private static GenerationAttempt tryAttachAndRegenerate(ServerLevel level, StructureConnection conn,
                                                            boolean persistImmediately) {
        if (level == null || conn == null || conn.from() == null || conn.to() == null) return null;
        int radius;
        try {
            radius = ConfigService.get().planning().attachToExistingRoadRadiusBlocks();
        } catch (Throwable couldNotRead) {
            return null;
        }
        if (radius <= 0) return null;
        StructureConnection attached;
        try {
            attached = ExistingRoadAttachment.find(level, conn, radius);
        } catch (Throwable couldNotAttach) {
            LOGGER.debug("RoadWeaver attachment lookup failed", couldNotAttach);
            return null;
        }
        if (attached == null) return null;
        GenerationAttempt retry = generateTaskWithFallback(level, attached, persistImmediately);
        if (!retry.outcome().success()) {
            LOGGER.info("RoadWeaver attachment retry also failed: {} -> {} (original {} -> {})",
                    attached.from(), attached.to(), conn.from(), conn.to());
            return null;
        }
        LOGGER.info("RoadWeaver attached to existing road and generated: {} -> {} (original {} -> {}, stations={})",
                attached.from(), attached.to(), conn.from(), conn.to(),
                retry.outcome().road() == null ? -1 : retry.outcome().road().roadSegmentList().size());
        return retry;
    }

    private static GenerationAttempt generateTaskWithFallback(
            ServerLevel level, StructureConnection connection, boolean persistImmediately) {
        try (ConnectionDeletionService.TaskScope ignored = ConnectionDeletionService.beginTask(level, connection)) {
            if (ConnectionDeletionService.shouldAbort(level, connection)) {
                return new GenerationAttempt(connection, GenerationOutcome.failed());
            }
            int configuredStep = ConfigService.get().pathfindingCost().effectiveAStarStep();
            StructureConnection endpointReuse = ExistingRoadEndpointReuse.findCandidate(level, connection);

            // ★ 审计 P1-4：手绘路线必须跳过 endpoint reuse 分支。
            //
            // 该分支会把 connection 替换成「附近的既有道路端点」，
            // 并在替换前 discard 掉我们预置的玩家路径 —— 结果是玩家画的线
            // 被既有道路的走向悄悄替换掉（静默失效，正是最该避免的一类）。
            // 手绘路线的整条意义就是「按玩家画的走」，所以直接跳过这个优化。
            if (endpointReuse != null
                    && net.shiroha233.roadweaver.route.RouteBuildRegistry
                    .isPlayerRoute(level, connection)) {
                LOGGER.info("RoadWeaver skipped existing-road endpoint reuse for player-drawn route: {} -> {}",
                        connection.from(), connection.to());
                endpointReuse = null;
            }

            if (endpointReuse != null) {
                PlannedPathCache.discard(level, connection);
                LOGGER.info("RoadWeaver trying existing-road endpoint reuse before original generation: "
                                + "{} -> {} replaced by {} -> {}",
                        connection.from(), connection.to(), endpointReuse.from(), endpointReuse.to());
                GenerationOutcome reused = generateTaskInternal(
                        level, endpointReuse, persistImmediately, configuredStep);
                if (reused.success()) {
                    LOGGER.info("RoadWeaver accepted existing-road endpoint reuse: {} -> {} replaced by {} -> {}",
                            connection.from(), connection.to(), endpointReuse.from(), endpointReuse.to());
                    return new GenerationAttempt(endpointReuse, reused);
                }
                PlannedPathCache.discard(level, endpointReuse);
                discardPlanningSnapshot(level, endpointReuse);
                LOGGER.warn("RoadWeaver existing-road endpoint reuse failed; retaining original connection: "
                                + "{} -> {}",
                        connection.from(), connection.to());
            }
            GenerationOutcome primary = generateTaskInternal(level, connection, persistImmediately, configuredStep);
            boolean primaryProtectedGap = primary.success()
                    && primary.road() != null
                    && primary.road().hasProtectedGap();
            boolean endpointOnlyProtectedGap = primaryProtectedGap
                    && hasOnlyEndpointProtectedGaps(
                    level, connection, primary.road());
            if ((primary.success() && (!primaryProtectedGap || endpointOnlyProtectedGap))
                    || ConnectionDeletionService.shouldAbort(level, connection)) {
                if (endpointOnlyProtectedGap) {
                    LOGGER.info("RoadWeaver retained endpoint-only protected gap without topology replacement connection={}->{}",
                            connection.from(), connection.to());
                }
                return new GenerationAttempt(connection, primary);
            }
            if (primaryProtectedGap) {
                LOGGER.info("RoadWeaver primary route has a protected building gap; trying topology candidates before retaining it connection={}->{}",
                        connection.from(), connection.to());
            }
            // ★ 审计 P1-4：手绘路线不 discard 预置路径。
            // 这里 discard 之后再走 topology fallback，重试必然退回 A*，
            // 玩家画的线就丢了。手绘路线宁可直接判定失败，也不静默改成算法路径。
            if (net.shiroha233.roadweaver.route.RouteBuildRegistry.isPlayerRoute(level, connection)) {
                LOGGER.warn("RoadWeaver player-drawn route failed at step={}; "
                                + "keeping player path instead of falling back to A*: {} -> {}",
                        configuredStep, connection.from(), connection.to());
                return new GenerationAttempt(connection, primary);
            }
            PlannedPathCache.discard(level, connection);
            discardPlanningSnapshot(level, connection);

            LOGGER.warn("RoadWeaver original connection failed at configured precision step={} before topology fallback: {} -> {}",
                    configuredStep, connection.from(), connection.to());

            if (!TOPOLOGY_CANDIDATES_ENABLED) {
                LOGGER.warn("RoadWeaver topology candidates disabled; retaining original failure for diagnosis: {} -> {}",
                        connection.from(), connection.to());
                return new GenerationAttempt(connection, primary);
            }

            List<StructureConnection> candidates = ConnectionFallbackService.candidates(level, connection);
            if (candidates.isEmpty()) {
                LOGGER.warn("RoadWeaver topology fallback has no eligible replacement candidates: {} -> {}",
                        connection.from(), connection.to());
            } else {
                LOGGER.info("RoadWeaver topology fallback candidates={} for {} -> {}",
                        candidates.size(), connection.from(), connection.to());
            }
            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                if (ConnectionDeletionService.shouldAbort(level, connection)) break;
                StructureConnection replacement = candidates.get(attempt);
                LOGGER.warn("RoadWeaver attempting bounded topology fallback {}/{}: {} -> {} replaced by {} -> {}",
                        attempt + 1, candidates.size(), connection.from(), connection.to(),
                        replacement.from(), replacement.to());
                PlannedPathCache.discard(level, replacement);
                discardPlanningSnapshot(level, replacement);
                GenerationOutcome outcome = generateTaskInternal(
                        level, replacement, persistImmediately, configuredStep);
                if (outcome.success()) {
                    boolean connected = candidateConnectsToExistingNetwork(level, outcome.road(), primary.road());
                    boolean spawnConnected = !primaryProtectedGap || primary.road() == null
                            || SpawnConnectivityValidator.replacementRemainsConnectedToPersistedNetwork(
                            level, outcome.road(), primary.road());
                    boolean originalSpawnConnected = primaryProtectedGap && primary.road() != null
                            && SpawnConnectivityValidator.routeRemainsConnectedToPersistedNetwork(
                            level, primary.road());
                    if (primaryProtectedGap && primary.road() != null) {
                        boolean sameDirection = SpawnConnectivityValidator.sameOverallDirection(
                                primary.road(), outcome.road());
                        if (!sameDirection) {
                            LOGGER.info("RoadWeaver retained different-direction runtime original and candidate original={}->{} candidate={}->{} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                    connection.from(), connection.to(), replacement.from(), replacement.to(),
                                    originalSpawnConnected, spawnConnected);
                            return new GenerationAttempt(connection, primary, replacement);
                        }
                        if (persistImmediately && primary.road() != null) {
                            RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(primary.road()));
                        }
                        LOGGER.info("RoadWeaver accepted same-direction runtime candidate replacement original={}->{} candidate={}->{} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                connection.from(), connection.to(), replacement.from(), replacement.to(),
                                originalSpawnConnected, spawnConnected);
                        return new GenerationAttempt(replacement, outcome);
                    }
                    if (primaryProtectedGap && persistImmediately && primary.road() != null) {
                        RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(primary.road()));
                    }
                    LOGGER.info("RoadWeaver accepted runtime topology candidate connection={} -> {} candidate={} -> {} connected={}",
                            connection.from(), connection.to(), replacement.from(), replacement.to(), connected);
                    return new GenerationAttempt(replacement, outcome);
                }
                StructureFileStorage.recordFailedFallbackAttempt(level, connection, replacement);
                LOGGER.warn("RoadWeaver recorded failed topology fallback {}/{}: {} -> {} replacement={} -> {}",
                        attempt + 1, candidates.size(), connection.from(), connection.to(),
                        replacement.from(), replacement.to());
            }
            return new GenerationAttempt(connection, primary);
        }
    }

    private static GenerationOutcome generateTaskInternal(
            ServerLevel level, StructureConnection conn, boolean persistImmediately) {
        return generateTaskInternal(level, conn, persistImmediately, null);
    }

    private static GenerationOutcome generateTaskInternal(
            ServerLevel level, StructureConnection conn, boolean persistImmediately, Integer precisionStep) {
        int effectiveSamplingStep = precisionStep == null
                ? ConfigService.get().pathfindingCost().effectiveAStarStep()
                : precisionStep;
        InitialGenerationProgressTracker.setCurrentSamplingStep(effectiveSamplingStep);
        if (level == null || conn == null || !Level.OVERWORLD.equals(level.dimension())) {
            return GenerationOutcome.failed();
        }
        try {
            if (ConnectionDeletionService.shouldAbort(level, conn)) return GenerationOutcome.failed();

            var reg = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
            ConfiguredFeature<?, ?> cf = reg.get(ROAD_CF_ID);
            PathFeatureConfig cfg = (cf != null && cf.config() instanceof PathFeatureConfig rfc) ? rfc : new PathFeatureConfig();

            if (ConnectionDeletionService.shouldAbort(level, conn)) return GenerationOutcome.failed();
            ModConfig modCfg = ConfigService.get();
            if (!modCfg.roadAppearance().roadsEnabled()) {
                PlannedPathCache.discard(level, conn);
                return GenerationOutcome.completedWithoutRoad();
            }

            RoadGenerationConfig genCfg = RoadGenerationConfig.from(modCfg);
            if (precisionStep != null) {
                genCfg.pathfinding().setAStarStep(precisionStep);
                genCfg.pathfinding().sanitize();
            }
            RoadData generated = new Road(level, conn, cfg, genCfg)
                    .generateRoad(genCfg.pathfinding().aStarMaxSteps(), persistImmediately);

            // ── 玩家手绘路线：用玩家意图覆盖管线产出 ──
            //
            // 管线会把高度吸附回地面、并自己判定哪段是桥/隧道。
            // 手绘路线必须反过来：高度与类型以玩家为准（「画什么生成什么」）。
            // 几何（宽度/平滑/避结构）保持管线结果不动。
            if (generated != null) {
                generated = applyPlayerRouteOverrides(level, conn, generated);
            }

            return generated == null ? GenerationOutcome.failed() : GenerationOutcome.completed(generated);
        } catch (Throwable t) {
            if (ConnectionDeletionService.shouldAbort(level, conn)
                    || Thread.currentThread().isInterrupted()
                    || t instanceof java.util.concurrent.CancellationException) {
                return GenerationOutcome.failed();
            }
            LOGGER.error("Road generation failed for connection {} -> {}", conn.from(), conn.to(), t);
            return GenerationOutcome.failed();
        }
    }

    /**
     * 若该连接来自玩家手绘路线，则用玩家意图覆盖管线产出。
     *
     * <p>覆盖内容：桥/隧道的高度与类型（见 {@code RouteRoadDataPatcher}）。
     * 不是手绘路线时原样返回，对既有自动规划零影响。</p>
     *
     * <h2>★ 覆盖后必须补校验（审计 P1-3）</h2>
     * {@code generateRoad()} 内部的 {@code RoadDataValidator} /
     * {@code FinalRoadStructureValidator} / {@code repairFinalHeightConflict}
     * 全部跑在 patch <b>之前</b>。patch 之后直接返回的话，
     * 覆盖后的数据一次都没被校验过 —— 一旦不合法
     * （桥抬太高 → {@code invalid-height-step}、span 重叠 →
     * {@code overlapping-physical-spans}、span 端点不在 segment 列表 →
     * {@code invalid-span-endpoint}），失败会推迟到 chunkgen
     * （{@code PathFeature}）才发生，玩家只看到「路凭空不建了」。
     *
     * <p>所以这里补一次校验：<b>不通过就回退为管线原结果</b>
     * （而不是留下必然失败的数据）。回退意味着高度/类型以算法为准，
     * 但至少路能建出来 —— 比静默消失好。</p>
     */
    private static RoadData applyPlayerRouteOverrides(ServerLevel level,
                                                      StructureConnection conn,
                                                      RoadData generated) {
        try {
            net.shiroha233.roadweaver.route.PlayerRoute route =
                    net.shiroha233.roadweaver.route.RouteBuildRegistry.routeFor(level, conn);
            if (route == null) {
                return generated;
            }
            List<net.minecraft.core.BlockPos> centers = new ArrayList<>(
                    generated.roadSegmentList().size());
            for (var placement : generated.roadSegmentList()) {
                centers.add(placement.middlePos());
            }
            RoadData patched =
                    net.shiroha233.roadweaver.route.RouteRoadDataPatcher.patch(generated, route, centers);
            if (patched == generated) {
                return generated;
            }

            // ── 覆盖后补校验；不通过则**按段**回退（审计 P1-3）──
            //
            // ★ R3-P1-3：从「整条丢弃」改为「尽量保住玩家意图」。
            //
            // 原先只要校验失败就 return generated —— 一处局部错误
            // （桥抬太高 → invalid-height-step）会连累整条路线，
            // 玩家看到的是「一条桥都没建，全是贴地路」，日志里只有一行 warn。
            //
            // 现在三级策略：
            //   ① 先看收敛后的 patched（RouteRoadDataPatcher 已做坡度收敛，通常直接过）
            //   ② 不过 → 把**校验报出的那一小段**恢复成管线值，其余保留玩家意图
            //   ③ 还不过 → 才退回整条管线结果
            var validation =
                    net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator
                            .validateFinalRoad(patched);
            if (!validation.valid()) {
                RoadData partial =
                        net.shiroha233.roadweaver.route.RouteRoadDataPatcher
                                .revertRangeToPipeline(patched, generated, validation.index());
                if (partial != null
                        && net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator
                                .validateFinalRoad(partial).valid()) {
                    LOGGER.info("RoadWeaver player-route override partially reverted at idx={} "
                                    + "connection={}->{} route={} reason={}",
                            validation.index(), conn.from(), conn.to(), route.id(), validation);
                    return partial;
                }
                LOGGER.warn("RoadWeaver player-route override produced invalid RoadData; "
                                + "falling back to pipeline result connection={}->{} route={} reason={}",
                        conn.from(), conn.to(), route.id(), validation);
                return generated;
            }

            LOGGER.info("RoadWeaver applied player-route overrides connection={}->{} route={} segments={}",
                    conn.from(), conn.to(), route.id(), centers.size());
            return patched;
        } catch (Throwable t) {
            // 覆盖失败不能让整条路建不出来 —— 退回管线结果并记日志
            LOGGER.warn("RoadWeaver player-route override failed; keeping pipeline result connection={}->{}",
                    conn.from(), conn.to(), t);
            return generated;
        }
    }

    private record GenerationOutcome(boolean success, RoadData road) {
        static GenerationOutcome failed() {
            return new GenerationOutcome(false, null);
        }

        static GenerationOutcome completedWithoutRoad() {
            return new GenerationOutcome(true, null);
        }

        static GenerationOutcome completed(RoadData road) {
            return new GenerationOutcome(true, road);
        }
    }

    private record GenerationAttempt(StructureConnection connection,
                                     GenerationOutcome outcome,
                                     StructureConnection supplementalConnection) {
        private GenerationAttempt(StructureConnection connection, GenerationOutcome outcome) {
            this(connection, outcome, null);
        }
    }

    public record GenerationResult(boolean success,
                                   StructureConnection effectiveConnection,
                                   RoadData road) {
        public ConnectionStatus finalStatus() {
            if (!success) return ConnectionStatus.FAILED;
            return road != null && road.hasProtectedGap()
                    ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED;
        }
    }

    public enum InitialTaskResult {
        COMPLETED,
        FAILED
    }

    public record InitialGenerationResult(InitialTaskResult result, RoadData road, StructureConnection connection) {
        public InitialGenerationResult(InitialTaskResult result, RoadData road) {
            this(result, road, null);
        }
    }

    /**
     * Initial generation must leave every connection in a terminal state before
     * world entry. The Road implementation already prefers the persisted coarse
     * path and falls back to the detailed planner when that path is unavailable.
     */
    /** <b>⚠️ 死代码：只被 {@link #generateInitialRoad} 调用，而后者零调用点。</b> */
    public static InitialTaskResult generateInitialTask(ServerLevel level, StructureConnection conn) {
        return generateInitialRoad(level, conn).result();
    }

    /**
     * <b>⚠️ 死代码：全仓零调用点（0.9.085 核实）。</b>
     *
     * <p>唯一曾经调用它的 {@code InitialGenManager.submitAndCollect()} 本身也没有调用者 ——
     * 初始生成真正走的是 {@link #generateInitialRoadAtStep}。所以任何加在这里的
     * 「失败兜底」逻辑都<b>不会执行</b>（0.9.082 的接入就栽在这上面，真机 0 次触发）。
     * 保留是为了不改变公开 API，但请勿在此处新增行为。</p>
     */
    public static InitialGenerationResult generateInitialRoad(ServerLevel level, StructureConnection conn) {
        if (level == null || conn == null || !Level.OVERWORLD.equals(level.dimension())) {
            return new InitialGenerationResult(InitialTaskResult.FAILED, null);
        }
        try {
            ModConfig cfg = ConfigService.get();
            boolean hasStartupPath = PlannedPathCache.find(
                    level,
                    conn,
                    TerrainSamplingSessions.forLevel(level).effectiveMode(),
                    cfg.pathfindingCost()).isPresent();
            if (!hasStartupPath) {
                LOGGER.warn("Initial road has no coarse planned path: {} -> {}. Running the detailed planner before world entry.",
                        conn.from(), conn.to());
            }
            GenerationAttempt attempt = generateTaskWithFallback(level, conn, false);
            GenerationOutcome outcome = attempt.outcome();
            if (!outcome.success()) {
                // 0.9.082：所有精度档 + 局部修复 + 降级都试过仍失败时，最后再试一次就近接入。
                // 真机 4 条失败连接的端点距最近已有道路只有 0~65 格（只有 1 条终点在 872 格外）。
                GenerationAttempt attached = tryAttachAndRegenerate(level, conn, false);
                if (attached != null) {
                    return new InitialGenerationResult(InitialTaskResult.COMPLETED,
                            attached.outcome().road(), attached.connection());
                }
            }
            return new InitialGenerationResult(
                    outcome.success() ? InitialTaskResult.COMPLETED : InitialTaskResult.FAILED,
                    outcome.road(), attempt.connection());
        } catch (Throwable failure) {
            LOGGER.error("Initial road generation failed for connection {} -> {}", conn.from(), conn.to(), failure);
            return new InitialGenerationResult(InitialTaskResult.FAILED, null);
        }
    }

    /**
     * Runs exactly one initial-generation attempt at the supplied precision.
     * Startup coordination owns the retry tiers; this method must never fall
     * through to another precision or topology candidate.
     */
    public static InitialGenerationResult generateInitialRoadAtStep(
            ServerLevel level, StructureConnection conn, int step) {
        return generateInitialRoadAtStep(level, conn, step, false);
    }

    /**
     * Runs one startup retry. A local-only retry must already have a rebound
     * path cache entry; otherwise fail the retry rather than silently starting
     * a full endpoint-to-endpoint coarse scan.
     */
    /**
     * 0.8.325：局部修复重生成是否有可用的 rebound 路径（= 局部 splice 是否真的产出过结果）。
     * 没有的话重生成会立刻失败，调用方应在提交前就跳过，而不是白排一次任务。
     */
    public static boolean hasLocalRepairReboundPath(ServerLevel level, StructureConnection conn, int step) {
        if (level == null || conn == null) return false;
        try {
            ModConfig cfg = ConfigService.get();
            int safeStep = Math.max(RoadConstants.ASTAR_STEP_MIN, step);
            return PlannedPathCache.find(level, conn,
                    TerrainSamplingSessions.forLevel(level).effectiveMode(),
                    cfg.pathfindingCost().withAStarStep(safeStep)).isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static InitialGenerationResult generateInitialRoadAtStep(
            ServerLevel level, StructureConnection conn, int step, boolean localOnly) {
        if (level == null || conn == null || !Level.OVERWORLD.equals(level.dimension())) {
            return new InitialGenerationResult(InitialTaskResult.FAILED, null, conn);
        }
        int safeStep = Math.max(RoadConstants.ASTAR_STEP_MIN, step);
        try (ConnectionDeletionService.TaskScope ignored =
                     ConnectionDeletionService.beginTask(level, conn)) {
            if (ConnectionDeletionService.shouldAbort(level, conn)) {
                return new InitialGenerationResult(InitialTaskResult.FAILED, null, conn);
            }
            if (localOnly) {
                if (!hasLocalRepairReboundPath(level, conn, safeStep)) {
                    LOGGER.warn("RoadWeaver local-only retry refused full-route sampling: missing rebound path "
                                    + "step={} connection={}->{}",
                            safeStep, conn.from(), conn.to());
                    return new InitialGenerationResult(InitialTaskResult.FAILED, null, conn);
                }
            }
            GenerationOutcome outcome = generateTaskInternal(level, conn, false, safeStep);

        if (!outcome.success() && localOnly) {
            // 0.9.085：这才是**真正会被执行**的失败兜底路径。
            //
            // 真机 4 条 initial connection FAILED（存档 20261111 1704）走的是
            // 精度档位 → 局部修复波次；而 0.9.082 把接入逻辑加在了
            // generateInitialRoad() 里 —— 那个方法与 submitAndCollect() 一起
            // **零调用点**（全仓检索只有定义），于是接入一次都没执行过。
            //
            // localOnly=true 只出现在「局部修复波次」，是最后一道；
            // 此前的档位重试不接，避免过早把路接到还不完整的路网上。
            GenerationAttempt attached = tryAttachAndRegenerate(level, conn, false);
            if (attached != null) {
                return new InitialGenerationResult(InitialTaskResult.COMPLETED,
                        attached.outcome().road(), attached.connection());
            }
        }
            return new InitialGenerationResult(
                    outcome.success() ? InitialTaskResult.COMPLETED : InitialTaskResult.FAILED,
                    outcome.road(), conn);
        } catch (Throwable failure) {
            LOGGER.error("Initial road single-step generation failed for connection {} -> {} step={} localOnly={}",
                    conn.from(), conn.to(), safeStep, localOnly, failure);
            return new InitialGenerationResult(InitialTaskResult.FAILED, null, conn);
        }
    }

    // ==================== 安全生成包装 ====================

    private static void safeGenerate(ServerLevel level, StructureConnection conn, long epoch) {
        try {
            if (runtimePausedForDimension) return;
            if (Thread.currentThread().isInterrupted()) return;
            if (!ThreadPoolManager.isEpoch(epoch)) return;

            GenerationAttempt attempt = generateTaskWithFallback(level, conn, true);
            if (!ThreadPoolManager.isEpoch(epoch) || runtimePausedForDimension) {
                if (attempt.outcome().success() && attempt.outcome().road() != null) {
                    RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(attempt.outcome().road()));
                }
                removeProcessed(level, conn);
                return;
            }
            if (ConnectionDeletionService.shouldAbort(level, conn)) {
                removeProcessed(level, conn);
                return;
            }
            boolean ok = attempt.outcome().success();
            ConnectionStatus st = !ok ? ConnectionStatus.FAILED
                    : attempt.outcome().road() != null && attempt.outcome().road().hasProtectedGap()
                    ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED;
            // Runtime observability: per-stage timing summary at most every
            // 10 minutes, only when stage timers moved.
            DetailedRoadGenerationStats.logSummaryIfDue();

            executeOnMainThread(level, epoch, () -> {
                if (ConnectionDeletionService.shouldAbort(level, conn)) {
                    removeProcessed(level, conn);
                    return;
                }
                // ★ 2026-09-25：**必须在 updateConnectionResult 之前**取这条连接
                //   属于哪条手绘路线 —— 因为那个方法在转 COMPLETED 时会调
                //   RouteBuildRegistry.clearCompleted() 把登记项删掉。
                //   晚一步 routeFor 就返回 null，强加载分支永远走不到。
                final net.shiroha233.roadweaver.route.PlayerRoute playerRouteForConn =
                        net.shiroha233.roadweaver.route.RouteBuildRegistry
                                .routeFor(level, conn);
                updateConnectionResult(level, conn, attempt.connection(), st);
                if (attempt.supplementalConnection() != null) {
                    addCompletedSupplementalConnection(level, attempt.supplementalConnection());
                }
                if (st == ConnectionStatus.COMPLETED || st == ConnectionStatus.PROTECTED_GAP) {
                    reconcileSavedRoadsAndBackfill(level, attempt, epoch, playerRouteForConn);
                }
                removeProcessed(level, conn);
            });
        } catch (Throwable t) {
            if (ConnectionDeletionService.shouldAbort(level, conn)) {
                removeProcessed(level, conn);
                return;
            }
            LOGGER.error("Asynchronous road generation failed for connection {} -> {}", conn.from(), conn.to(), t);
            executeOnMainThread(level, epoch, () -> {
                if (ConnectionDeletionService.shouldAbort(level, conn)) {
                    removeProcessed(level, conn);
                    return;
                }
                updateConnectionStatus(level, conn, ConnectionStatus.FAILED);
                removeProcessed(level, conn);
            });
        }
    }

    private static void executeOnMainThread(ServerLevel level, long epoch, Runnable action) {
        var server = level.getServer();
        if (server != null) {
            server.execute(() -> {
                if (!ThreadPoolManager.isEpoch(epoch)) return;
                action.run();
            });
        } else {
            if (!ThreadPoolManager.isEpoch(epoch)) return;
            action.run();
        }
    }

    // ==================== 状态更新辅助 ====================

    private static void updateConnectionStatus(ServerLevel level, StructureConnection conn, ConnectionStatus status) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> origin = provider.getStructureConnections(level);
        List<StructureConnection> all = origin != null ? new ArrayList<>(origin) : new ArrayList<>();
        StructureConnection updated = null;
        for (int i = 0; i < all.size(); i++) {
            StructureConnection c = all.get(i);
            if (PlanningUtils.sameEdge(c, conn)) {
                updated = new StructureConnection(c.from(), c.to(), status);
                all.set(i, updated);
                break;
            }
        }
        provider.setStructureConnections(level, all);
        if (updated != null) {
            MapPatchService.publishConnection(level, updated);
            if (status == ConnectionStatus.COMPLETED || status == ConnectionStatus.PROTECTED_GAP) {
                MapPatchService.publishRoadForConnectionAsync(level, updated);
                // ★ 审计 R3-P1-4：完工后清掉手绘路线登记项（历史计数不受影响）。
                // 原先只有「删除连接」会清，正常完工的项永远留在表里。
                net.shiroha233.roadweaver.route.RouteBuildRegistry.clearCompleted(level, updated);
            }
        }
    }

    private static void updateConnectionResult(ServerLevel level,
                                               StructureConnection original,
                                               StructureConnection effective,
                                               ConnectionStatus status) {
        if (effective == null || PlanningUtils.sameEdge(original, effective)) {
            updateConnectionStatus(level, original, status);
            return;
        }
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> current = provider.getStructureConnections(level);
        ArrayList<StructureConnection> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < updated.size(); i++) {
            if (PlanningUtils.sameEdge(updated.get(i), original)) {
                updated.set(i, new StructureConnection(effective.from(), effective.to(), status));
                replaced = true;
                break;
            }
        }
        if (!replaced) updated.add(new StructureConnection(effective.from(), effective.to(), status));
        provider.setStructureConnections(level, updated);
        StructureConnection replacement = new StructureConnection(effective.from(), effective.to(), status);
        MapPatchService.publishConnectionReplacement(level, original, replacement);
        if (status == ConnectionStatus.COMPLETED || status == ConnectionStatus.PROTECTED_GAP) {
            MapPatchService.publishRoadForConnectionAsync(level, replacement);
        }
    }

    /**
     * Reconcile this completed route with roads saved by sibling workers before
     * replaying construction. The reconciliation is local to the route bounds,
     * never loads chunks, and is retried for a supplemental candidate when the
     * original and candidate routes are both retained.
     */
    private static void reconcileSavedRoadsAndBackfill(ServerLevel level,
                                                       GenerationAttempt attempt,
                                                       long epoch) {
        reconcileSavedRoadsAndBackfill(level, attempt, epoch, null);
    }

    /**
     * 与上面同名方法的完整版 —— 多带一个「这条连接属于哪条手绘路线」。
     *
     * @param playerRoute 手绘路线本体；{@code null} 表示这是**自动生成**的连接。
     *                    ⚠️ 必须由调用方在 {@code updateConnectionResult} **之前**取好
     *                    （那一步会清登记项）。为兼容既有调用点保留了两参数重载。
     */
    private static void reconcileSavedRoadsAndBackfill(ServerLevel level,
                                                       GenerationAttempt attempt,
                                                       long epoch,
                                                       @org.jetbrains.annotations.Nullable
                                                       net.shiroha233.roadweaver.route.PlayerRoute playerRoute) {
        if (level == null || attempt == null || attempt.connection() == null) return;
        // 四态 · GAMEPLAY_IDLE 节流信号:一条运行时道路完成持久化后记录,
        // Idle 规划的下一次 region 提交将至少间隔 runtimeRoadCompletionMinIntervalMs。
        RuntimeLoadPolicy.noteRoadCompletion();
        CompletableFuture<Void> reconciliation = RoadSnapService.snapAroundConnectionAsync(
                level, attempt.connection().from(), attempt.connection().to());
        if (attempt.supplementalConnection() != null) {
            StructureConnection supplemental = attempt.supplementalConnection();
            reconciliation = reconciliation.thenCompose(ignored ->
                    RoadSnapService.snapAroundConnectionAsync(
                            level, supplemental.from(), supplemental.to()));
        }
        reconciliation.whenComplete((ignored, failure) -> executeOnMainThread(level, epoch, () -> {
            if (failure != null) {
                LOGGER.warn("RoadWeaver saved-road reconciliation failed; retaining persisted routes", failure);
            }
            if (attempt.outcome().road() != null) {
                // ★ 2026-09-25 用户要求：手绘路线**强制加载其经过的区块并立即施工**。
                //
                // 这里是**唯一正确的挂钩点** —— 此刻 RoadData 刚产出并落盘，
                // 强加载的区块能立刻拿到有效计划去铺。若放在 submit() 之后
                // （RoadData 还没生成）就会拿到空计划，把区块误标成
                // 「终态无需施工」而永久跳过。
                //
                // 判定用调用方**提前取好**的 playerRoute（登记项已在
                // updateConnectionResult 里被清掉，这里再查会得 null）。
                // 自动生成的路**保持原行为**（只回填已加载的，不强加载）——
                // 用户明确「不要多拉区块，道路没有经过的不要拉」。
                if (playerRoute != null) {
                    net.shiroha233.roadweaver.route.RouteBuildService
                            .preloadRouteChunks(level, playerRoute);
                } else {
                    LoadedChunkRoadBackfill.placeLoadedRoad(level, attempt.outcome().road());
                }
            }
            if (attempt.supplementalConnection() != null) {
                StructureConnection supplemental = attempt.supplementalConnection();
                LoadedChunkRoadBackfill.placeLoadedConnection(level,
                        supplemental.from(), supplemental.to());
            }
        }));
    }

    private static void addCompletedSupplementalConnection(ServerLevel level,
                                                           StructureConnection supplemental) {
        if (level == null || supplemental == null) return;
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> current = provider.getStructureConnections(level);
        ArrayList<StructureConnection> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
        for (StructureConnection existing : updated) {
            if (PlanningUtils.sameEdge(existing, supplemental)) return;
        }
        StructureConnection completed = new StructureConnection(
                supplemental.from(), supplemental.to(), ConnectionStatus.COMPLETED);
        updated.add(completed);
        provider.setStructureConnections(level, updated);
        MapPatchService.publishConnection(level, completed);
        MapPatchService.publishRoadForConnectionAsync(level, completed);
        LOGGER.info("RoadWeaver added disconnected runtime candidate edge {} -> {} while retaining original route",
                completed.from(), completed.to());
    }

    /** A candidate only counts as connected when it touches a persisted road other than the deferred original. */
    private static boolean candidateConnectsToExistingNetwork(ServerLevel level,
                                                              RoadData candidate,
                                                              RoadData deferredOriginal) {
        if (level == null || candidate == null || candidate.roadSegmentList().isEmpty()) return false;
        long deferredFingerprint = deferredOriginal == null ? Long.MIN_VALUE
                : RoadShardStorage.computeFingerprint(deferredOriginal);
        long candidateFingerprint = RoadShardStorage.computeFingerprint(candidate);
        for (RoadData existing : RoadShardStorage.loadAll(level)) {
            if (existing == null) continue;
            long fingerprint = RoadShardStorage.computeFingerprint(existing);
            if (fingerprint == deferredFingerprint || fingerprint == candidateFingerprint) continue;
            if (roadsGeometricallyConnected(existing, candidate)) return true;
        }
        return false;
    }

    private static boolean roadsGeometricallyConnected(RoadData first, RoadData second) {
        if (first == null || second == null) return false;
        int horizontal = Math.max(8, (first.width() + second.width()) / 2 + 4);
        long maxHorizontalSq = (long) horizontal * horizontal;
        for (var a : first.roadSegmentList()) {
            if (a == null || a.middlePos() == null) continue;
            BlockPos ap = a.middlePos();
            for (var b : second.roadSegmentList()) {
                if (b == null || b.middlePos() == null) continue;
                BlockPos bp = b.middlePos();
                long dx = (long) ap.getX() - bp.getX();
                long dz = (long) ap.getZ() - bp.getZ();
                if (dx * dx + dz * dz <= maxHorizontalSq
                        && Math.abs(ap.getY() - bp.getY()) <= 8) return true;
            }
        }
        return false;
    }

    private static void removeProcessed(ServerLevel level, StructureConnection conn) {
        PROCESSED.remove(PlannedPathKey.of(conn));
    }

    // ==================== 队列刷新 ====================

    /**
     * P1-4: after a world reload the in-memory planned-path map is empty, so the
     * first ticks would read one file per pending connection on the server
     * thread. Warm exactly the entries {@code refreshQueue} is about to ask for,
     * on the IO lane, using the same mode/config as the runtime reader so the
     * fingerprints match.
     */
    private static void prewarmPlannedPaths(ServerLevel level) {
        if (level == null || RoadLifecycle.isStopping()) return;
        long epoch = ThreadPoolManager.currentEpoch();
        List<StructureConnection> list = WorldDataProvider.getInstance().getStructureConnections(level);
        if (list == null || list.isEmpty()) return;
        var mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
        var pathConfig = ConfigService.get().pathfindingCost();
        int warmed = 0;
        for (StructureConnection connection : list) {
            if (connection == null || !ThreadPoolManager.isEpoch(epoch) || RoadLifecycle.isStopping()) return;
            if (connection.status() != ConnectionStatus.PLANNED
                    && connection.status() != ConnectionStatus.GENERATING) continue;
            PlannedPathCache.find(level, connection, mode, pathConfig);
            warmed++;
        }
        LOGGER.info("RoadWeaver prewarmed planned paths off the server thread: connections={}", warmed);
    }

    private static ConnectionTopologyReconciler.Result reconcileConnections(ServerLevel level,
                                                                            List<StructureConnection> list) {
        if (level == reconciledLevel && list.equals(reconciledInput)) {
            return reconciledResult;
        }
        ConnectionTopologyReconciler.Result result = ConnectionTopologyReconciler.reconcile(list, List.of());
        reconciledLevel = level;
        reconciledInput = new ArrayList<>(list);
        reconciledResult = result;
        return result;
    }

    private static void refreshQueue(ServerLevel level) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> list = provider.getStructureConnections(level);
        if (list == null) return;

        ConnectionTopologyReconciler.Result topology = reconcileConnections(level, list);
        if (!topology.removed().isEmpty()) {
            provider.setStructureConnections(level, topology.connections());
            Set<PlannedPathKey> removedKeys = new HashSet<>();
            for (StructureConnection removed : topology.removed()) {
                PlannedPathKey key = PlannedPathKey.of(removed);
                removedKeys.add(key);
                PROCESSED.remove(key);
                MapPatchService.publishConnectionRemoval(level, removed);
                LOGGER.info("RoadWeaver removed redundant pending connection already covered by completed network: {} -> {} status={}",
                        removed.from(), removed.to(), removed.status());
            }
            QUEUE.removeIf(connection -> removedKeys.contains(PlannedPathKey.of(connection)));
            list = topology.connections();
        }

        ConcurrentLinkedQueue<StructureConnection> q = QUEUE;
        ConcurrentHashMap<PlannedPathKey, Boolean> proc = PROCESSED;

        for (StructureConnection c : list) {
            if (c.status() != ConnectionStatus.PLANNED && c.status() != ConnectionStatus.GENERATING) continue;
            if (RoadPlanningReadinessIndex.shouldReserveForCoordinator(level, c)) {
                continue;
            }
            if (c.status() == ConnectionStatus.PLANNED
                    && IdleRoadGenerationService.shouldReserveForIdle(level, c)) {
                continue;
            }
            if (c.status() == ConnectionStatus.GENERATING
                    && IdleRoadGenerationService.isManagedByIdle(level, c)) {
                continue;
            }
            PlannedPathKey key = PlannedPathKey.of(c);
            if (proc.putIfAbsent(key, Boolean.TRUE) == null) {
                q.add(c);
            }
        }
    }

    // ==================== 优先级轮询 ====================

    private static StructureConnection pollNearest(ConcurrentLinkedQueue<StructureConnection> q,
                                                   List<ServerPlayer> players,
                                                   ServerLevel level) {
        if (q.isEmpty()) return null;

        // ★ 任务 C（2026-09-26）：**手绘路线连接永远先出队**
        //   （用户要求「手动道路优先级最高」）。
        //
        //   为什么必须加这一级：队列原本只按「离玩家近」排序，
        //   而自动规划可能一次性入队几十条连接 —— 手绘路线虽然刚提交、
        //   也离玩家近，但仍可能排在若干更近的自动连接后面。
        //   玩家点了施工在盯着屏幕，必须让它插到最前。
        //
        //   用 isPlayerRoute 判定（登记表），O(1) 查表；异常时按非手绘处理，
        //   绝不让优先级判定影响主流程。
        if (level != null) {
            for (StructureConnection e : q) {
                if (e != null && isPlayerRouteSafe(level, e)) {
                    if (q.remove(e)) return e;
                }
            }
        }

        if (players == null || players.isEmpty()) return q.poll();

        StructureConnection best = null;
        long bestDist = Long.MAX_VALUE;
        for (StructureConnection e : q) {
            long d = playerDistance2(e, players);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best != null && q.remove(best)) return best;
        return q.poll();
    }

    /** 安全的 isPlayerRoute 包装（异常时按非手绘处理）。 */
    private static boolean isPlayerRouteSafe(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) {
            return false;
        }
        try {
            return net.shiroha233.roadweaver.route.RouteBuildRegistry
                    .isPlayerRoute(level, connection);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * 当前暂停原因**是否属于手绘优先模式的豁免集合**（★ 2026-09-26 修 W-2）。
     *
     * <h2>为什么这样做才符合红线 6</h2>
     * 红线 6：「与 Chunky / Streams / Voxy 的资源协调**只走配置层**」。
     * 0.8.397 直接在 tick 里一刀切地推翻暂停语义（连 CHUNKY 都豁免）—— 越线。
     * 现在改成：**读配置的豁免集合 → 与当前暂停原因比较**，
     * 代码只做查表，协调策略完全由配置决定。
     *
     * <p>配置项：{@code performance.playerRoutePriorityExemptPauseReasons}
     * （逗号分隔的 {@code PauseKind} 名字）。默认
     * {@code PLAYER_INTERACTION,RECOVERY,PLAYER_TRAVEL}。</p>
     *
     * @return true 表示当前暂停原因在豁免集合内（可以为手绘施工开路）
     */
    private static boolean isPauseExemptForPlayerRoute() {
        try {
            var cfg = ConfigService.get();
            if (cfg == null || cfg.performance() == null
                    || !cfg.performance().playerRoutePriorityEnabled()) {
                return false;
            }
            String spec = cfg.performance().playerRoutePriorityExemptPauseReasons();
            if (spec == null || spec.isBlank()) {
                return false;
            }
            var kind = RuntimeLoadPolicy.currentPauseKind();
            if (kind == RuntimeLoadPolicy.PauseKind.NONE) {
                return true;   // 没暂停，无需豁免
            }
            String wanted = kind.name();
            for (String part : spec.split(",")) {
                if (part != null && part.trim().equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            // 配置读不到就按"不豁免"（保守：退回让路规则）
            return false;
        }
    }

    /**
     * 优先模式的并发上限（★ 2026-09-26 修 W-5）。
     *
     * <h2>原来错在哪</h2>
     * 0.8.397 写的是 {@code Math.max(2, ThreadPoolManager.maxRuntimeRoadThreads())}
     * （通常 12）—— 两个问题：
     * <ol>
     *   <li><b>写死挡位</b>，绕过 {@code recommendedRoadWorkerThreads()} 的全部自适应
     *       （游玩压 2、界面压 2、Chunky 压 8）⇒ 触碰红线 2 的精神</li>
     *   <li><b>实际落空</b>：{@code tryAcquireRuntimeSlot()} 受
     *       {@code RUNTIME_SLOTS} 封顶，而它 ≤ {@code maxConcurrentGenerations}
     *       （默认 3）⇒ 写 12 实际只拿到 3（WorkBuddy W-5）</li>
     * </ol>
     *
     * <p>现在：配置 {@code playerRoutePriorityMaxThreads}
     * —— {@code 0}=auto（沿用 {@code maxConcurrentGenerations}）；
     * {@code >0} 时既作为上限、又**同步抬高 slot 水位**让它真正生效。</p>
     */
    private static int playerRoutePriorityLimit() {
        try {
            var cfg = ConfigService.get();
            int configured = (cfg == null || cfg.performance() == null)
                    ? 0 : cfg.performance().playerRoutePriorityMaxThreads();
            if (configured > 0) {
                // ★ 让它真正生效：把本次的 slot 水位抬到配置值
                //   （RUNTIME_SLOTS 由 tryAcquireRuntimeSlot 读取）
                RUNTIME_SLOTS.set(Math.max(RUNTIME_SLOTS.get(), configured));
                return configured;
            }
            // auto：沿用常规上限，但仍保证至少 2（避免"优先了却只有 1 个 worker"）
            return Math.max(2, runtimeGenerationLimit());
        } catch (RuntimeException | LinkageError e) {
            return Math.max(2, runtimeGenerationLimit());
        }
    }

    /**
     * 统计「还有多少条**手绘路线**的连接待施工」（★ 任务 C，2026-09-26 修 W-1/I-1）。
     *
     * <h2>★ 为什么改走 provider 口径（原实现是死锁）</h2>
     * 原实现遍历调度队列 {@code QUEUE}，但 **QUEUE 只在
     * {@code pauseScheduling() == false} 时才由 {@link #refreshQueue} 填充**，
     * 而本方法的结果又决定是否豁免暂停 —— 于是死锁：
     * <pre>
     *   玩家点施工（确认框是 Screen）⇒ isScreenPauseActive() 真
     *     ⇒ pauseScheduling() 真 ⇒ tick 在 refreshQueue 之前就 return
     *     ⇒ QUEUE 永远空 ⇒ 本方法返回 0 ⇒ 不豁免 ⇒ 循环
     * </pre>
     * 即「**开着地图盯进度**」恰恰是让施工不动的操作（WorkBuddy W-1）。
     *
     * <p>现在改用 {@link RouteBuildProgress#countActivePlayerRouteConnections}
     * —— 它查的是连接表（`WorldDataProvider`）这个**真源**，
     * 与暂停状态完全无关；且天然包含 {@code GENERATING}（已出队、在建）的连接，
     * 正好补上原 javadoc 承诺却从未实现的「把正在施工的也算进去」（W-3）。</p>
     *
     * <p>同时消解第三个问题：10 分钟上限不再被「最后一条连接出队后计数归零」
     * 意外重置（续杯）。</p>
     */
    private static int countPendingPlayerRouteConnections(ServerLevel level) {
        return net.shiroha233.roadweaver.route.RouteBuildProgress
                .countActivePlayerRouteConnections(level);
    }

    private static long playerDistance2(StructureConnection c, List<ServerPlayer> players) {
        if (players == null || players.isEmpty()) return Long.MAX_VALUE;
        long best = Long.MAX_VALUE;
        int mx = (c.from().getX() + c.to().getX()) >> 1;
        int mz = (c.from().getZ() + c.to().getZ()) >> 1;
        BlockPos mid = new BlockPos(mx, 0, mz);
        for (ServerPlayer p : players) {
            BlockPos pb = p.blockPosition();
            best = Math.min(best, dist2XZ(pb, c.from()));
            best = Math.min(best, dist2XZ(pb, c.to()));
            best = Math.min(best, dist2XZ(pb, mid));
        }
        return best;
    }

    private static long dist2XZ(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static List<ServerPlayer> getCachedPlayers(ServerLevel level) {
        CachedPlayerList cache = PLAYER_LIST_CACHE;
        if (CURRENT_TICK - cache.lastTick >= PLAYER_LIST_REFRESH_TICKS || cache.players == null) {
            List<ServerPlayer> fresh = new ArrayList<>();
            var server = level.getServer();
            if (server == null) return null;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p != null && p.serverLevel() == level) fresh.add(p);
            }
            cache.players = fresh;
            cache.lastTick = CURRENT_TICK;
        }
        return cache.players;
    }

    private static final class CachedPlayerList {
        long lastTick = Long.MIN_VALUE;
        List<ServerPlayer> players = null;

        void clear() {
            lastTick = Long.MIN_VALUE;
            players = null;
        }
    }

    private static final class RunningTask {
        final AtomicBoolean released = new AtomicBoolean(false);
        volatile Future<?> future;
    }
}
