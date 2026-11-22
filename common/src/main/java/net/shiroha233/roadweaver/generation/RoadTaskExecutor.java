/* 文件职责：道路生成任务的执行骨架 —— 每 tick 推进、单任务生成、失败回退。 */
package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.core.model.ProfileConflict;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.generation.RoadGenerationService.*;
import static net.shiroha233.roadweaver.generation.RoadQueueOps.*;

/**
 * 道路生成任务的执行骨架。
 *
 * <h2>它在整条链里的位置</h2>
 * <p>规划算完一条路之后，真正「生成 + 落盘」是这里做的：每 tick 推进队列、
 * 逐任务生成、失败时按策略回退（{@code generateTaskWithFallback}）。</p>
 *
 * <h2>★ 失败回退不是重试，是降级</h2>
 * <p>{@code generateTaskWithFallback} 在生成失败时不会简单重跑同一条 ——
 * 重跑几乎必然再失败（同样的输入同样的结果）。它走的是<b>降级</b>：
 * 放宽约束 / 换更保守的策略 / 必要时跳过并记账。
 * 这也是为什么必须有 {@code safeGenerate} 这层：**单条路失败不该拖垮整批**。</p>
 *
 * <h2>★ tick 的预算是「每次多少条」不是「每 tick 多久」</h2>
 * <p>用时间做预算会让跨机行为不可复现（同存档在快慢机器上切出不同的批次边界）。
 * 这里按条数切，保证同一存档重跑得到同样的分批结果。</p>
 *
 * <p>本类是从 1484 行的 {@code RoadGenerationService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class RoadTaskExecutor {
    public static void tick(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension()) || RoadLifecycle.isStopping()) return;
        // 主世界无人时撤销在途票据、保留续跑状态；地图暂停自动规划时仍推进手绘。
        try {
            net.shiroha233.roadweaver.route.RouteChunkPreloader.drainPending(level, runtimePausedForDimension);
        } catch (Throwable failure) {
            LOGGER.warn("RoadWeaver 手绘预加载异常（不影响施工调度）", failure);
        }
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

    public static RoadData applyPlayerRouteOverrides(ServerLevel level,
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
                    LOGGER.info("【手绘覆盖局部不合法，已撤销对应站点改写】 " + "RoadWeaver player-route override partially reverted at idx={} "
                                    + "connection={}->{} route={} reason={}",
                            validation.index(), conn.from(), conn.to(), route.id(), validation);
                    return partial;
                }
                LOGGER.warn("【手绘覆盖产生无效道路，保留当前规划链结果】 " + "RoadWeaver player-route override produced invalid RoadData; "
                                + "falling back to pipeline result connection={}->{} route={} reason={}",
                        conn.from(), conn.to(), route.id(), validation);
                return generated;
            }

            LOGGER.info("【手绘路线覆盖已应用】 " + "RoadWeaver applied player-route overrides connection={}->{} route={} segments={}",
                    conn.from(), conn.to(), route.id(), centers.size());
            return patched;
        } catch (Throwable t) {
            // 覆盖失败不能让整条路建不出来 —— 退回管线结果并记日志
            LOGGER.warn("【手绘覆盖执行失败，保留当前规划链结果】 " + "RoadWeaver player-route override failed; keeping pipeline result connection={}->{}",
                    conn.from(), conn.to(), t);
            return generated;
        }
    }

    public static GenerationAttempt tryAttachAndRegenerate(ServerLevel level, StructureConnection conn,
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
            LOGGER.debug("【既有道路挂接查询失败】 " + "RoadWeaver attachment lookup failed", couldNotAttach);
            return null;
        }
        if (attached == null) return null;
        GenerationAttempt retry = generateTaskWithFallback(level, attached, persistImmediately);
        if (!retry.outcome().success()) {
            LOGGER.info("【挂接既有道路后的重试仍失败】 " + "RoadWeaver attachment retry also failed: {} -> {} (original {} -> {})",
                    attached.from(), attached.to(), conn.from(), conn.to());
            return null;
        }
        LOGGER.info("【已挂接既有道路并生成连接】 " + "RoadWeaver attached to existing road and generated: {} -> {} (original {} -> {}, stations={})",
                attached.from(), attached.to(), conn.from(), conn.to(),
                retry.outcome().road() == null ? -1 : retry.outcome().road().roadSegmentList().size());
        return retry;
    }

    public static GenerationAttempt generateTaskWithFallback(
            ServerLevel level, StructureConnection connection, boolean persistImmediately) {
        try (ConnectionDeletionService.TaskScope ignored = ConnectionDeletionService.beginTask(level, connection)) {
            if (ConnectionDeletionService.shouldAbort(level, connection)) {
                return new GenerationAttempt(connection, GenerationOutcome.failed());
            }
            int configuredStep = ConfigService.get().pathfindingCost().effectivePathGridStep();
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
                LOGGER.info("【手绘路线跳过既有道路端点复用】 " + "RoadWeaver skipped existing-road endpoint reuse for player-drawn route: {} -> {}",
                        connection.from(), connection.to());
                endpointReuse = null;
            }

            if (endpointReuse != null) {
                PlannedPathCache.discard(level, connection);
                LOGGER.info("【生成前尝试复用既有道路端点】 " + "RoadWeaver trying existing-road endpoint reuse before original generation: "
                                + "{} -> {} replaced by {} -> {}",
                        connection.from(), connection.to(), endpointReuse.from(), endpointReuse.to());
                GenerationOutcome reused = generateTaskInternal(
                        level, endpointReuse, persistImmediately, configuredStep);
                if (reused.success()) {
                    LOGGER.info("【已接受既有道路端点复用】 " + "RoadWeaver accepted existing-road endpoint reuse: {} -> {} replaced by {} -> {}",
                            connection.from(), connection.to(), endpointReuse.from(), endpointReuse.to());
                    return new GenerationAttempt(endpointReuse, reused);
                }
                PlannedPathCache.discard(level, endpointReuse);
                discardPlanningSnapshot(level, endpointReuse);
                LOGGER.warn("【既有道路端点复用失败，保留原连接】 " + "RoadWeaver existing-road endpoint reuse failed; retaining original connection: "
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
                    LOGGER.info("【仅端点存在保护缺口，保留原拓扑连接】 " + "RoadWeaver retained endpoint-only protected gap without topology replacement connection={}->{}",
                            connection.from(), connection.to());
                }
                return new GenerationAttempt(connection, primary);
            }
            if (primaryProtectedGap) {
                LOGGER.info("【原路存在建筑保护缺口，先尝试拓扑候补】 " + "RoadWeaver primary route has a protected building gap; trying topology candidates before retaining it connection={}->{}",
                        connection.from(), connection.to());
            }
            // ★ 审计 P1-4：手绘路线不 discard 预置路径。
            // 这里 discard 之后再走 topology fallback，重试必然退回 A*，
            // 玩家画的线就丢了。手绘路线宁可直接判定失败，也不静默改成算法路径。
            if (net.shiroha233.roadweaver.route.RouteBuildRegistry.isPlayerRoute(level, connection)) {
                LOGGER.warn("【手绘路线失败，保留玩家路径且不转用旧寻路】 " + "RoadWeaver player-drawn route failed at step={}; "
                                + "keeping player path instead of falling back to A*: {} -> {}",
                        configuredStep, connection.from(), connection.to());
                return new GenerationAttempt(connection, primary);
            }
            PlannedPathCache.discard(level, connection);
            discardPlanningSnapshot(level, connection);

            LOGGER.warn("【原连接在配置精度下失败，准备评估拓扑候补】 " + "RoadWeaver original connection failed at configured precision step={} before topology fallback: {} -> {}",
                    configuredStep, connection.from(), connection.to());

            if (!TOPOLOGY_CANDIDATES_ENABLED) {
                LOGGER.warn("【拓扑候补已禁用，保留原失败原因】 " + "RoadWeaver topology candidates disabled; retaining original failure for diagnosis: {} -> {}",
                        connection.from(), connection.to());
                return new GenerationAttempt(connection, primary);
            }

            List<StructureConnection> candidates = ConnectionFallbackService.candidates(level, connection);
            if (candidates.isEmpty()) {
                LOGGER.warn("【没有符合条件的拓扑候补】 " + "RoadWeaver topology fallback has no eligible replacement candidates: {} -> {}",
                        connection.from(), connection.to());
            } else {
                LOGGER.info("【可尝试的拓扑候补数量】 " + "RoadWeaver topology fallback candidates={} for {} -> {}",
                        candidates.size(), connection.from(), connection.to());
            }
            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                if (ConnectionDeletionService.shouldAbort(level, connection)) break;
                StructureConnection replacement = candidates.get(attempt);
                LOGGER.warn("【正在尝试有界拓扑候补替换】 " + "RoadWeaver attempting bounded topology fallback {}/{}: {} -> {} replaced by {} -> {}",
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
                            LOGGER.info("【游玩期原路与候补方向不同，保留两条连接】 " + "RoadWeaver retained different-direction runtime original and candidate original={}->{} candidate={}->{} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                    connection.from(), connection.to(), replacement.from(), replacement.to(),
                                    originalSpawnConnected, spawnConnected);
                            return new GenerationAttempt(connection, primary, replacement);
                        }
                        if (persistImmediately && primary.road() != null) {
                            RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(primary.road()));
                        }
                        LOGGER.info("【已接受游玩期同方向候补替换】 " + "RoadWeaver accepted same-direction runtime candidate replacement original={}->{} candidate={}->{} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                connection.from(), connection.to(), replacement.from(), replacement.to(),
                                originalSpawnConnected, spawnConnected);
                        return new GenerationAttempt(replacement, outcome);
                    }
                    if (primaryProtectedGap && persistImmediately && primary.road() != null) {
                        RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(primary.road()));
                    }
                    LOGGER.info("【已接受游玩期拓扑候补】 " + "RoadWeaver accepted runtime topology candidate connection={} -> {} candidate={} -> {} connected={}",
                            connection.from(), connection.to(), replacement.from(), replacement.to(), connected);
                    return new GenerationAttempt(replacement, outcome);
                }
                // 执行异常没有道路不可施工的证据，下次规划仍可重试。
                if (outcome.profileConflict() == null || ConnectionDeletionService.shouldAbort(level, replacement)) continue;
                StructureFileStorage.recordFailedFallbackAttempt(level, connection, replacement);
                LOGGER.warn("【本次拓扑候补替换失败，已记录结果】 " + "RoadWeaver recorded failed topology fallback {}/{}: {} -> {} replacement={} -> {}",
                        attempt + 1, candidates.size(), connection.from(), connection.to(),
                        replacement.from(), replacement.to());
            }
            return new GenerationAttempt(connection, primary);
        }
    }

    public static GenerationOutcome generateTaskInternal(
            ServerLevel level, StructureConnection conn, boolean persistImmediately) {
        return generateTaskInternal(level, conn, persistImmediately, null);
    }

    public static void safeGenerate(ServerLevel level, StructureConnection conn, long epoch) {
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
            LOGGER.error("【异步道路连接生成失败】 " + "Asynchronous road generation failed for connection {} -> {}", conn.from(), conn.to(), t);
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


    public static GenerationOutcome generateTaskInternal(
            ServerLevel level, StructureConnection conn, boolean persistImmediately, Integer precisionStep) {
        int effectiveSamplingStep = precisionStep == null
                ? ConfigService.get().pathfindingCost().effectivePathGridStep()
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
                genCfg.pathfinding().setPathGridStep(precisionStep);
                genCfg.pathfinding().sanitize();
            }
            RoadData generated = new Road(level, conn, cfg, genCfg)
                    .generateRoad(persistImmediately);

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
            LOGGER.error("【道路连接生成失败】 " + "Road generation failed for connection {} -> {}", conn.from(), conn.to(), t);
            // 只向上带可重放的高程冲突，不在批次中保留异常堆栈或世界引用。
            return GenerationOutcome.failed(
                    t instanceof ProfileConflict.Rejected rejected
                            ? rejected.conflict() : null);
        }
    }
}
