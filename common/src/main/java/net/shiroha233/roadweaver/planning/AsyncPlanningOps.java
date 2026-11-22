/* 文件职责：异步规划编排 —— 分批、逐轮推进、收尾与日志。 */
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
import net.shiroha233.roadweaver.planning.ComponentConnector;
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

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.planning.RoadPlanningService.*;
import static net.shiroha233.roadweaver.planning.ConflictWindowOps.*;
import static net.shiroha233.roadweaver.planning.PlanningBoundsOps.*;

/**
 * 异步规划编排。
 *
 * <h2>它在编排什么</h2>
 * <p>规划不是「一次算完全部」，而是<b>分轮</b>推进：先发一批候选、算完看结果、
 * 再决定下一轮要不要做（{@code planRectRound} / {@code planRectRounds} /
 * {@code finishAdaptivePlan}）。这样玩家能看到进度，也不会一口气卡死主线程。</p>
 *
 * <h2>★ 轮次是自适应的</h2>
 * <p>每轮之后看「还有没有值得继续的候选」：全都被拒了就提前收尾，
 * 而不是死板跑满 {@code maxRounds}。判断依据是候选的接受率与剩余预算。</p>
 *
 * <h2>★ 日志以轮为单位</h2>
 * <p>{@code logAsyncPlanning} 每轮打一行（轮次 / 候选数 / 接受数 / 耗时）。
 * 排障时看的是这张逐轮表，而不是最后的总数 —— **总数看不出是哪一轮掉下来的**。</p>
 *
 * <p>本类是从 2204 行的 {@code RoadPlanningService} 里<b>纯搬迁</b>出来的，
 * 轮次判据与预算一字未改（含缩进）。</p>
 */
public final class AsyncPlanningOps {
    public static CompletableFuture<Void> planRectAsync(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        return planRectAsync(
                level, minBlockX, minBlockZ, maxBlockX, maxBlockZ,
                ThreadPoolManager.TaskRole.PLANNING, false);
    }

    public static CompletableFuture<Void> planRectForChunkyAsync(
            ServerLevel level,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        return planRectAsync(
                level, minBlockX, minBlockZ, maxBlockX, maxBlockZ,
                ThreadPoolManager.TaskRole.COORDINATOR, true);
    }

    /**
     * 0.9.059（②）：自适应候选范围的外层驱动。
     *
     * <p>旧行为：候选枚举范围固定 = coreBounds 四边各外扩 {@code planMaxEdgeLenBlocks}(2048)，
     * 2048×2048 的核心区变成 6144×6144 的枚举区（9 倍面积），真机一局 4,374 个候选起点里
     * 3,997 个（91.4%）解析完是空的。</p>
     *
     * <p>新行为：先只外扩 {@link AdaptiveCandidateBounds#marginFor}（0.9.060 起是相对值：
     * 核心区对角线 × {@link AdaptiveCandidateBounds#MARGIN_RATIO}，夹在
     * {@code MARGIN_FLOOR..MARGIN_CAP} 之间，并按 {@link AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS}
     * 做一次面积守卫收窄）跑一轮；跑完看每条路<b>实际走线的包围盒</b>，只有越出当前余量的路
     * 会被退回、连同新看到的结构一起重规划（范围 ×4 扩一档，最多到 {@code capMargin}）。
     * 合规路不参与任何后续轮次，发布的就是它第一轮算出来的那份走线 —— 等价性论证写在
     * {@link AdaptiveCandidateBounds} 的类注释里（admission 等价 + 不复算等价；跨路耦合是已知边界）。</p>
     *
     * <p>红线：guard 触发时只收窄外扩的 margin，<b>绝不裁 {@code coreBounds}</b> —— 候选矩形按
     * {@link AdaptiveCandidateBounds#boundsWithMargin}（非负外扩）构造，core 恒被完整枚举。</p>
     */
    public static CompletableFuture<Void> planRectAsync(
            ServerLevel level,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ,
            ThreadPoolManager.TaskRole taskRole,
            boolean requireCompletePaths) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return CompletableFuture.completedFuture(null);
        final long epoch = ThreadPoolManager.currentEpoch();
        final AsyncPlanningTiming timing = new AsyncPlanningTiming();
        final int[] coreBounds = normalizedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        final boolean startupProgress = taskRole == ThreadPoolManager.TaskRole.INITIAL;
        final List<StructureConnection> existingSnapshot;
        {
            WorldDataProvider prov = WorldDataProvider.getInstance();
            List<StructureConnection> ex = prov.getStructureConnections(level);
            existingSnapshot = ConnectionTopologyReconciler.reconcile(ex, List.of()).connections();
        }
        // 0.9.060（②）：预热规模保护。coreBounds 随预热范围变大（256/512/1024/2048 区块 ⇒ 核心区
        // 边长 8192/16384/32768/65536），固定 256 格余量会失去意义甚至反噬（候选比旧 6144 还大）。
        // 这里按 coreDiagonal 取相对余量，并在候选面积超 MAX_CANDIDATE_AREA_BLOCKS 时只收窄余量。
        // 红线：coreBounds 永远完整枚举 —— 收窄只作用在外扩的 margin 上，候选矩形恒包含 core。
        final AdaptiveCandidateBounds.CandidateGuard guard = AdaptiveCandidateBounds.guard(coreBounds);
        if (guard.tripped()) {
            LOGGER.warn("【异步规划前置条件未满足】 {} ({})", guard.describe(), guard.coreAloneExceedsCap()
                    ? "core 自身面积已超 cap，几何上无法压进；余量落到 MARGIN_FLOOR，core 仍完整枚举（红线）"
                    : "余量已收窄到能落进 cap；core 仍完整枚举（红线）");
        }
        final AdaptivePlanState state =
                new AdaptivePlanState(configuredPlanMaxEdgeLenBlocks(), guard.finalMargin());
        final PrepareTiming prepTiming =
                new PrepareTiming();
        return planRectRound(level, epoch, coreBounds, taskRole, requireCompletePaths,
                        existingSnapshot, timing, startupProgress, state, prepTiming)
                .thenCompose(round -> planRectRounds(level, epoch, coreBounds, taskRole,
                        requireCompletePaths, existingSnapshot, timing, startupProgress, state,
                        prepTiming, round))
                .whenComplete((ignored, failure) ->
                        logAsyncPlanning(level, coreBounds, state, timing, prepTiming, failure));
    }

    /** 0.9.059（②）：一轮「给定候选范围」的完整规划（拓扑 → 快照 → 地形 → 走廊快照），不发布。 */
    public static CompletableFuture<AdaptiveRound> planRectRound(
            ServerLevel level,
            long epoch,
            int[] coreBounds,
            ThreadPoolManager.TaskRole taskRole,
            boolean requireCompletePaths,
            List<StructureConnection> existingSnapshot,
            AsyncPlanningTiming timing,
            boolean startupProgress,
            AdaptivePlanState state,
            PrepareTiming prepTiming) {
        final int[] candidateBounds = AdaptiveCandidateBounds
                .boundsWithMargin(coreBounds, state.margin()).toArray();
        if (startupProgress) {
            // 每次扩域显式发布轮次，界面不把重新规划误认成阶段乱跳。
            InitialGenerationProgressTracker.beginPlanningRound(state.rounds());
            // Structure discovery is one bounded startup work item. The
            // connection count is published once the topology pass has a
            // stable snapshot, before terrain sampling takes over the HUD.
            InitialGenerationProgressTracker.setPlanningTilePlan(
                    1, "discovering_structures");
        }
        // 0.9.059（②）：候选范围由 planRectAsync 的自适应轮次决定，这里不再自己算。
        // 旧口径的语义保留：coreBounds 是"必须覆盖"的核心，不是端点硬裁剪 —— 越出核心
        // 的端点能看多远由候选范围决定，而候选范围现在从相对余量（0.9.060：
        // coreDiagonal × 0.25，夹在 512..4096，并按面积 cap 守卫）起步、越界才扩档。
        // 注意：候选矩形恒包含 coreBounds（非负外扩），guard 永不裁剪核心区内的枚举。
        return ComputeService.supplyAsync(taskRole, () -> {
            long phaseStartedAt = System.nanoTime();
            try {
                if (Thread.currentThread().isInterrupted()) return new PlannedRegionResult(List.of());
                if (!ThreadPoolManager.isEpoch(epoch)) return new PlannedRegionResult(List.of());
                ArrayList<BlockPos> points = new ArrayList<>();
                HashSet<Long> seenPos = new HashSet<>();
                collectStructurePointsInto(
                        level,
                        candidateBounds[0], candidateBounds[1], candidateBounds[2], candidateBounds[3],
                        points, seenPos);
                for (StructureConnection c : existingSnapshot) {
                    BlockPos f = new BlockPos(c.from().getX(), 0, c.from().getZ());
                    BlockPos t = new BlockPos(c.to().getX(), 0, c.to().getZ());
                    if (isPlanningEndpointAllowed(level, f)
                            && inRect2d(f, candidateBounds[0], candidateBounds[1], candidateBounds[2], candidateBounds[3])) {
                        long kf = PlanningUtils.pos2dKey(f);
                        if (seenPos.add(kf)) points.add(f);
                    }
                    if (isPlanningEndpointAllowed(level, t)
                            && inRect2d(t, candidateBounds[0], candidateBounds[1], candidateBounds[2], candidateBounds[3])) {
                        long kt = PlanningUtils.pos2dKey(t);
                        if (seenPos.add(kt)) points.add(t);
                    }
                }
                timing.structureCount = points.size();
                if (startupProgress) {
                    InitialGenerationProgressTracker.beginCoarseTopology(points.size());
                }
                if (points.size() < 2) {
                    if (startupProgress) {
                        InitialGenerationProgressTracker.completePlanningSnapshots(
                                "structure_topology_ready");
                    }
                    return new PlannedRegionResult(List.of());
                }

                ModConfig cfg0 = ConfigService.get();
                ArrayList<StructureConnection> existingInRect = new ArrayList<>();
                ArrayList<StructureConnection> missingExistingPaths = new ArrayList<>();
                for (StructureConnection c : existingSnapshot) {
                    if (inRect2d(c.from(), candidateBounds[0], candidateBounds[1], candidateBounds[2], candidateBounds[3]) &&
                            inRect2d(c.to(), candidateBounds[0], candidateBounds[1], candidateBounds[2], candidateBounds[3])) {
                        existingInRect.add(c);
                        if (edgeTouchesRect(c, coreBounds[0], coreBounds[1], coreBounds[2], coreBounds[3])
                                && c.status() != ConnectionStatus.COMPLETED
                                && c.status() != ConnectionStatus.PROTECTED_GAP
                                && c.status() != ConnectionStatus.FAILED
                                && PlannedPathCache.find(
                                        level,
                                        c,
                                        TerrainSamplingSessions.forLevel(level).effectiveMode(),
                                        cfg0.pathfindingCost()).isEmpty()) {
                            missingExistingPaths.add(c);
                        }
                    }
                }

                List<StructureConnection> primaryEdges = planPrimaryEdges(level, points, cfg0);
                if (primaryEdges.isEmpty() && existingInRect.isEmpty()) return new PlannedRegionResult(List.of());

                primaryEdges = primaryEdges.stream()
                        .filter(connection -> edgeTouchesRect(
                                connection, coreBounds[0], coreBounds[1], coreBounds[2], coreBounds[3]))
                        .toList();

                List<StructureConnection> filteredPrimary =
                        ConnectionTopologyReconciler.filterNewConnections(existingSnapshot, primaryEdges);

                ArrayList<StructureConnection> base = new ArrayList<>(existingInRect);
                base.addAll(filteredPrimary);
                ArrayList<BlockPos> componentPoints = collectComponentPoints(points, existingInRect, filteredPrimary);
                List<StructureConnection> bridges = ComponentConnector.connectComponents(
                        componentPoints, base,
                        cfg0.planning().bridgeJoinLenBlocks(),
                        cfg0.planning().componentMinAngleDeg(),
                        cfg0.planning().componentDegreeCap()
                );
                bridges = bridges.stream()
                        .filter(connection -> edgeTouchesRect(
                                connection, coreBounds[0], coreBounds[1], coreBounds[2], coreBounds[3]))
                        .toList();

                ArrayList<StructureConnection> incoming = new ArrayList<>(missingExistingPaths);
                incoming.addAll(filteredPrimary);
                incoming.addAll(bridges);
                incoming = new ArrayList<>(StructureFileStorage.filterSuppressedConnections(level, incoming));
                // 0.9.059（②）：上一轮已经算过、且走线当时就落在余量内的路不再重算 ——
                // 它们的走线就是最终结果（{@code AdaptiveCandidateBounds} 等价性第 2 条）。
                if (!state.plannedKeys().isEmpty()) {
                    ArrayList<StructureConnection> fresh = new ArrayList<>(incoming.size());
                    for (StructureConnection connection : incoming) {
                        if (!state.plannedKeys().contains(PlannedPathKey.of(connection))) fresh.add(connection);
                    }
                    incoming = fresh;
                }
                timing.connectionCount = incoming.size();
                if (startupProgress) {
                    InitialGenerationProgressTracker.completePlanningSnapshots(
                            "structure_topology_ready");
                }
                return new PlannedRegionResult(incoming);
            } finally {
                // 0.9.059（②）：多轮时各段累加（字段语义从"最后一轮"改成"本轮累计"）。
                timing.topologyNanos += System.nanoTime() - phaseStartedAt;
            }
        }).thenCompose(result -> {
                    if (result == null || result.incoming().isEmpty() || !ThreadPoolManager.isEpoch(epoch)) {
                        return CompletableFuture.completedFuture(new TerrainPlannedRegionResult(result, null));
                    }
                    long phaseStartedAt = System.nanoTime();
                    long[] submittedAt = new long[1];
                    return preparePlanningSnapshotsAsync(
                                    level, result.incoming(), prepTiming)
                            .thenCompose(ignored -> {
                                // 0.9.044（T-7）：快照准备到这一刻为止，单独记账。
                                submittedAt[0] = System.nanoTime();
                                timing.terrainSnapshotPrepNanos += submittedAt[0] - phaseStartedAt;
                                return ComputeService.supplyAsync(taskRole, () -> {
                        RoadTerrainPlanningPort.Result terrain = null;
                        if (ThreadPoolManager.isEpoch(epoch)) {
                            int[] terrainBounds = boundsIncludingConnections(
                                    coreBounds[0], coreBounds[1], coreBounds[2], coreBounds[3],
                                    result.incoming());
                            // 0.9.044（T-7）：lambda 真正开跑 - 提交时刻 = 纯排队等待。
                            timing.terrainQueueNanos += System.nanoTime() - submittedAt[0];
                            long[] terrainParts = new long[2];
                            terrain = planRoadTerrainWithPreparedSnapshots(
                                    level,
                                    terrainBounds[0], terrainBounds[1], terrainBounds[2], terrainBounds[3],
                                    result.incoming(), epoch, terrainParts, startupProgress);
                            timing.terrainCoreNanos += terrainParts[0];
                            timing.terrainTailNanos += terrainParts[1];
                        }
                        return new TerrainPlannedRegionResult(result, terrain);
                    }).whenComplete((completed, failure) ->
                            timing.terrainNanos += System.nanoTime() - phaseStartedAt);
                            });
                }).thenCompose(planned -> {
                    if (planned == null || planned.region() == null
                            || !ThreadPoolManager.isEpoch(epoch)) {
                        return CompletableFuture.completedFuture(AdaptiveRound.empty(state.margin()));
                    }
                    PlannedRegionResult region = planned.region();
                    if (region.incoming().isEmpty()) {
                        return CompletableFuture.completedFuture(
                                AdaptiveRound.of(region.incoming(), Map.of(), state.margin()));
                    }
                    Map<StructureConnection, List<BlockPos>> paths = planned.terrain() == null
                            || planned.terrain().paths() == null ? Map.of() : planned.terrain().paths();
                    // 0.9.059（②）：走线不完整不再立刻抛 —— 交给自适应轮次决定是否扩一档再试；
                    // requireCompletePaths 的硬失败移到最后一轮（见 planRectRounds 的终点）。
                    if (paths.isEmpty() || !containsAllPaths(region.incoming(), paths)) {
                        return CompletableFuture.completedFuture(
                                AdaptiveRound.of(region.incoming(), paths, state.margin()));
                    }
                    long phaseStartedAt = System.nanoTime();
                    return preparePlanningSnapshotsForPathsAsync(
                                    level, paths, taskRole)
                            .thenApply(ignored -> AdaptiveRound.of(region.incoming(), paths, state.margin()))
                            .whenComplete((ignored, failure) ->
                                    timing.snapshotNanos += System.nanoTime() - phaseStartedAt);
                });
    }

    /**
     * 0.9.059（②）：一轮跑完后的扩张判定 + 递归下一轮。
     *
     * <p>触发条件两条：① 有路的走线越出本轮余量（几何判据，主触发）；②
     * {@code requireCompletePaths} 下走线不完整（Chunky 就绪区要求每条连接都有路，
     * 扩一档可能就能求出解）。两者都不满足、或已到 cap 就收尾发布。</p>
     */
    public static CompletableFuture<Void> planRectRounds(
            ServerLevel level,
            long epoch,
            int[] coreBounds,
            ThreadPoolManager.TaskRole taskRole,
            boolean requireCompletePaths,
            List<StructureConnection> existingSnapshot,
            AsyncPlanningTiming timing,
            boolean startupProgress,
            AdaptivePlanState state,
            PrepareTiming prepTiming,
            AdaptiveRound round) {
        if (round == null) return finishAdaptivePlan(level, epoch, timing, state);
        state.observeStructures(timing.structureCount);
        state.absorb(round);
        AdaptiveCandidateBounds.Rect bounds =
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, round.margin());
        List<AdaptiveCandidateBounds.PathBounds> roadBounds =
                List.copyOf(round.pathBounds().values());
        int roadsOutside = AdaptiveCandidateBounds.roadsOutside(bounds, roadBounds);
        boolean complete = round.complete() && state.complete();
        boolean mustExpand = roadsOutside > 0 || (requireCompletePaths && !complete);
        if (!mustExpand || !state.canExpand()) {
            if (requireCompletePaths && !complete) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "RoadWeaver could not persist every path required by a Chunky readiness region"));
            }
            return finishAdaptivePlan(level, epoch, timing, state);
        }
        // 只把越界的路退回：合规路保留第一轮走线（等价性第 2 条）。
        for (Map.Entry<PlannedPathKey, AdaptiveCandidateBounds.PathBounds> entry
                : round.pathBounds().entrySet()) {
            if (!entry.getValue().within(bounds)) state.rewind(entry.getKey());
        }
        state.roadsExpanded += Math.max(1, roadsOutside);
        int fromMargin = round.margin();
        state.expand();
        LOGGER.info("【规划边界按路线越界情况扩大】 " + "[PLAN-ADAPTIVE] expand round={} margin={} fromMargin={} toMargin={} roadsOutside={} "
                        + "maxOvershoot={} fromBounds=[{},{} -> {},{}] toBounds=[{},{} -> {},{}]",
                state.rounds(), state.margin(), fromMargin, state.margin(), roadsOutside,
                AdaptiveCandidateBounds.maxOvershoot(bounds, roadBounds),
                bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin()).minX(),
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin()).minZ(),
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin()).maxX(),
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin()).maxZ());
        return planRectRound(level, epoch, coreBounds, taskRole, requireCompletePaths,
                        existingSnapshot, timing, startupProgress, state, prepTiming)
                .thenCompose(next -> planRectRounds(level, epoch, coreBounds, taskRole,
                        requireCompletePaths, existingSnapshot, timing, startupProgress, state,
                        prepTiming, next));
    }

    /** 0.9.059（②）：所有轮次结束后一次性发布（多轮不重复发布，避免拓扑抖动）。 */
    public static CompletableFuture<Void> finishAdaptivePlan(
            ServerLevel level, long epoch, AsyncPlanningTiming timing, AdaptivePlanState state) {
        List<StructureConnection> incoming = state.plannedConnections();
        timing.connectionCount = incoming.size();
        if (incoming.isEmpty()) return CompletableFuture.completedFuture(null);
        long phaseStartedAt = System.nanoTime();
        return publishIncomingConnectionsAsync(level, epoch, incoming)
                .whenComplete((ignored, failure) ->
                        timing.publishNanos = System.nanoTime() - phaseStartedAt);
    }

    /** 规划阶段诊断保留稳定标记，便于将采样、快照和自适应预算分开分析。 */
    public static void logAsyncPlanning(
            ServerLevel level,
            int[] coreBounds,
            AdaptivePlanState state,
            AsyncPlanningTiming timing,
            PrepareTiming prepTiming,
            Throwable failure) {
        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(coreBounds);
        AdaptiveCandidateBounds.Rect expanded =
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin());
        LOGGER.info(
                "【异步规划各阶段与发布耗时】 " + "RoadWeaver async planning timing: dimension={} coreBounds=[{},{} -> {},{}] candidateBounds=[{},{} -> {},{}] structures={} connections={} topologyMs={} structureSnapshotsMs={} terrainPlanningMs={} terrainQueueMs={} terrainSnapshotPrepMs={} terrainCoreMs={} terrainTailMs={} publishMs={} totalMs={} success={}",
                level.dimension().location(), coreBounds[0], coreBounds[1], coreBounds[2], coreBounds[3],
                expanded.minX(), expanded.minZ(), expanded.maxX(), expanded.maxZ(),
                state.structures(), timing.connectionCount,
                nanosToMillis(timing.topologyNanos), nanosToMillis(timing.snapshotNanos),
                nanosToMillis(timing.terrainNanos),
                nanosToMillis(timing.terrainQueueNanos), nanosToMillis(timing.terrainSnapshotPrepNanos),
                nanosToMillis(timing.terrainCoreNanos),
                nanosToMillis(timing.terrainTailNanos),
                nanosToMillis(timing.publishNanos),
                elapsedMillis(timing.startedAt), failure == null);
        if (prepTiming != null && prepTiming.ran()) {
            LOGGER.info("【规划快照阶段耗时明细】 " + "[PLAN-SNAPSHOT-BREAKDOWN] {}", prepTiming.describe());
        }
        // 0.9.060（②/④）：coreDiag= 核心区对角线、marginRatio= 相对余量比例、
        // margin= 未守卫的请求余量、guardTripped= 面积守卫是否触发、finalMargin= 守卫后余量；
        // appliedMargin= 多轮扩张后真正生效的余量（旧 margin= 字段的口径，改名以免和上面撞名）。
        LOGGER.info("【自适应规划边界和扩展轮次汇总】 " + "[PLAN-ADAPTIVE] initialBounds=[{},{} -> {},{}] expanded=[{},{} -> {},{}] "
                        + "roadsExpanded={} expandedRounds={} structures={} connections={} "
                        + "{} appliedMargin={} capMargin={}",
                initial.minX(), initial.minZ(), initial.maxX(), initial.maxZ(),
                expanded.minX(), expanded.minZ(), expanded.maxX(), expanded.maxZ(),
                state.roadsExpanded(), state.expandedRounds(), state.structures(),
                state.plannedConnections().size(),
                AdaptiveCandidateBounds.guard(coreBounds).diagnostics(),
                state.margin(), state.capMargin());
    }
}
