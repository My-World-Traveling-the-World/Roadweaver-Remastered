/* 文件职责：初始规划的收尾与生命周期 —— 连接状态归一、收尾、日志汇总、中止。 */
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
import static net.shiroha233.roadweaver.generation.InitialRoutePersistOps.*;

/**
 * 初始规划的收尾与生命周期。
 *
 * <h2>它管「结束」</h2>
 * <p>批次跑完之后还有一堆收尾：把连接状态批量归一
 * （{@code batchUpdateConnectionStatus}）、确保每条连接都有终态
 * （{@code ensureInitialConnectionsTerminal}）、flush、打汇总、以及中止路径。</p>
 *
 * <h2>★ 「终态」是硬要求</h2>
 * <p>每条连接最终必须处于 COMPLETED / FAILED / SKIPPED 之一。
 * 卡在中间态的连接会让 UI 一直显示「规划中」，玩家以为卡死。
 * {@code ensureInitialConnectionsTerminal} 就是那条兜底。</p>
 *
 * <p>本类是从 2174 行的 {@code InitialGenManager} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class InitialPlanLifecycleOps {
    public static void batchUpdateConnectionStatus(WorldDataProvider provider,
                                                    ServerLevel level,
                                                    Map<PlannedPathKey, GenResult> results,
                                                    Set<PlannedPathKey> persistedEdges,
                                                    List<StructureConnection> supplementalConnections) {
        if ((results == null || results.isEmpty())
                && (supplementalConnections == null || supplementalConnections.isEmpty())) return;

        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null) return;

        List<StructureConnection> updated = new ArrayList<>(current);
        List<ConnectionChange> changedConnections = new ArrayList<>();
        boolean changed = false;
        if (supplementalConnections != null) {
            for (StructureConnection supplemental : supplementalConnections) {
                if (supplemental == null) continue;
                PlannedPathKey key = PlannedPathKey.of(supplemental);
                boolean alreadyPresent = updated.stream().anyMatch(existing ->
                        existing != null && PlannedPathKey.of(existing).equals(key));
                if (!alreadyPresent && persistedEdges.contains(key)) {
                    StructureConnection completed = new StructureConnection(
                            supplemental.from(), supplemental.to(), ConnectionStatus.COMPLETED);
                    updated.add(completed);
                    changedConnections.add(new ConnectionChange(null, completed));
                    changed = true;
                    LOGGER.info("【已加入保留的候补路网边】 " + "RoadWeaver added retained candidate network edge {} -> {}",
                            completed.from(), completed.to());
                }
            }
        }
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection original = updated.get(i);
            PlannedPathKey k = PlannedPathKey.of(original);
            GenResult result = results == null ? null : results.get(k);
            if (result == null) continue;
            StructureConnection effective = result.effectiveConnection() != null ? result.effectiveConnection() : original;
            PlannedPathKey effectiveKey = PlannedPathKey.of(effective);
            boolean completed = result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                    && persistedEdges.contains(effectiveKey);
            ConnectionStatus newStatus = !completed ? ConnectionStatus.FAILED
                    : result.road() != null && result.road().hasProtectedGap()
                    ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED;
            StructureConnection changedConnection = new StructureConnection(
                    completed ? effective.from() : original.from(),
                    completed ? effective.to() : original.to(),
                    newStatus);
            updated.set(i, changedConnection);
            changedConnections.add(new ConnectionChange(original, changedConnection));
            changed = true;
            // 0.8.355：每条连接的最终状态单独一行，便于玩家/我们按端点定位到具体哪条路失败。
            if (newStatus == ConnectionStatus.FAILED) {
                if (result.road() == null) {
                    LOGGER.warn("【初始连接失败，精度层级和局部修复均未得到可用路线】 " + "RoadWeaver initial connection FAILED (no usable route): {} -> {} "
                                    + "[all precision tiers, local repair and fallbacks were exhausted]",
                            original.from(), original.to());
                } else {
                    LOGGER.warn("【已算出路线但未持久化，连接标记失败】 " + "RoadWeaver initial connection FAILED (route computed but not persisted, "
                                    + "likely failed a final validator): {} -> {}",
                            original.from(), original.to());
                }
            } else {
                LOGGER.info("【初始道路连接处理结果】 " + "RoadWeaver initial connection {} : {} -> {} (stations={})",
                        newStatus, changedConnection.from(), changedConnection.to(),
                        result.road() == null ? -1 : roadStationCount(result.road()));
            }
        }
        if (changed) {
            provider.setStructureConnections(level, updated);
            for (ConnectionChange change : changedConnections) {
                if (change.original() == null) {
                    MapPatchService.publishConnection(level, change.updated());
                } else if (!PlanningUtils.sameEdge(change.original(), change.updated())) {
                    MapPatchService.publishConnectionReplacement(level, change.original(), change.updated());
                } else {
                    MapPatchService.publishConnection(level, change.updated());
                }
                if (change.updated().status() == ConnectionStatus.COMPLETED
                        || change.updated().status() == ConnectionStatus.PROTECTED_GAP) {
                    MapPatchService.publishRoadForConnectionAsync(level, change.updated());
                }
            }
        }
    }

    public static void ensureInitialConnectionsTerminal(WorldDataProvider provider, ServerLevel level) {
        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null) return;
        List<StructureConnection> pending = new ArrayList<>();
        for (StructureConnection connection : current) {
            if (connection == null) continue;
            if (connection.status() == ConnectionStatus.PLANNED
                    || connection.status() == ConnectionStatus.GENERATING) {
                pending.add(connection);
            }
        }
        if (pending.isEmpty()) return;
        // Task 2: never abort world entry over non-terminal connections. The
        // generation waves above have already run local repair and candidate
        // fallbacks, so anything still PLANNED/GENERATING here has no pending
        // work — push it to a reliable terminal state (FAILED) instead of
        // throwing, which used to skip flushAndFinish and the completion
        // checkpoint and forced a full restart re-run.
        String endpoints = pending.stream()
                .limit(8)
                .map(connection -> connection.from() + "->" + connection.to())
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown");
        LOGGER.error("【进世界前仍未结案的连接被明确标记失败】 " + "RoadWeaver forcing initial connections to terminal FAILED state before world entry: "
                + pending.size() + " endpoints=" + endpoints);
        List<StructureConnection> updated = new ArrayList<>(current);
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection connection = updated.get(i);
            if (connection == null) continue;
            if (connection.status() == ConnectionStatus.PLANNED
                    || connection.status() == ConnectionStatus.GENERATING) {
                StructureConnection terminal = new StructureConnection(
                        connection.from(), connection.to(), ConnectionStatus.FAILED);
                updated.set(i, terminal);
                MapPatchService.publishConnection(level, terminal);
            }
        }
        provider.setStructureConnections(level, updated);
    }

    public static PlannedPathKey ownerKey(RoadData road) {
        return PlannedPathKey.of(
                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerB2dKey()));
    }

    public static void flushAndFinish(ServerLevel level) {
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.POST_PROCESSING, "finalizing_network");
        long startedAt = System.nanoTime();
        RoadShardStorage.flushAll(level);
        long flushMs = elapsedMs(startedAt);
        RoadPositionQuery.clearCache(level);
        RoadPlanningReadinessIndex.markInitialCoverageReady(level);
        compactPreparedStartCacheAfterInitialGeneration();
        InitialGenerationCheckpointStore.markCompleted(level);
        LOGGER.info("【初始生成收尾持久化耗时】 " + "RoadWeaver initial finalizing timing: flushAllMs={} totalMs={}",
                flushMs, elapsedMs(startedAt));
        // 0.8.355：收尾汇总 —— 明确写出「本轮一共做了什么、哪些是复用的」，
        // 这是玩家判断「这次有没有白跑」的唯一依据。
        logCompletionSummary(level);
        active = false;
        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
        InitialGenerationProgressTracker.finish();
    }

    public static void logCompletionSummary(ServerLevel level) {
        try {
            List<StructureConnection> all = WorldDataProvider.getInstance().getStructureConnections(level);
            int completed = countByStatus(all, ConnectionStatus.COMPLETED);
            int protectedGap = countByStatus(all, ConnectionStatus.PROTECTED_GAP);
            int failed = countByStatus(all, ConnectionStatus.FAILED);
            int filtered = countByStatus(all, ConnectionStatus.FILTERED);
            int stillPending = countByStatus(all, ConnectionStatus.PLANNED)
                    + countByStatus(all, ConnectionStatus.GENERATING);
            LOGGER.info("【初始规划流程结束，连接状态已保存；不等同于全部方块竣工】 " + "RoadWeaver initial generation COMPLETED. Network status: total={} completed={} "
                            + "protectedGap={} failed={} filtered={} pending={}. "
                            + "checkpoint.txt is now state=COMPLETED, so the next world load skips initial "
                            + "generation entirely; only failed routes stay eligible for later repair waves.",
                    completed + protectedGap + failed + filtered + stillPending,
                    completed, protectedGap, failed, filtered, stillPending);
        } catch (Throwable ignored) {
            // 汇总日志失败不能影响收尾。
        }
    }

    public static void abort(String phase, Throwable failure) {
        LOGGER.error("【初始道路生成阶段异常中止】 " + "Initial RoadWeaver generation aborted during {}", phase, failure);
        // 0.8.355：中止时保留 checkpoint（不 markCompleted），下一次世界加载会按
        // phase/done/total 续接，而不是从头重跑。
        LOGGER.warn("【保留最近检查点，下次加载继续未完成阶段】 " + "RoadWeaver initial generation aborted; checkpoint kept at the last recorded progress "
                + "so the next world load can resume instead of restarting. phase={} done={}/{}",
                phase, done.get(), total.get());
        roadGenerationWorkersActive = false;
        compactPreparedStartCacheAfterInitialGeneration();
        active = false;
        generating.set(0);
        failed.incrementAndGet();
        InitialGenerationProgressTracker.updateRetryStates(0, 0);
        InitialGenerationProgressTracker.updateConnections(total.get(), 0, done.get(), failed.get());
        InitialGenerationProgressTracker.finish();
    }

    public static void update(ServerLevel level) {
        if (active) return;

        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> conns = provider.getStructureConnections(level);

        if (conns == null || conns.isEmpty()) {
            total.set(0);
            generating.set(0);
            done.set(0);
            failed.set(0);
            return;
        }

        int g = 0, c = 0, f = 0;
        for (StructureConnection sc : conns) {
            ConnectionStatus s = sc.status();
            if (s == ConnectionStatus.GENERATING) g++;
            else if (s == ConnectionStatus.COMPLETED || s == ConnectionStatus.PROTECTED_GAP) c++;
            else if (s == ConnectionStatus.FAILED) f++;
        }
        total.set(conns.size());
        generating.set(g);
        done.set(c);
        failed.set(f);
    }

    public static void removeGeneratedRoad(List<RoadData> roads, RoadData target) {
        if (roads == null || target == null || !target.hasOwnerPair()) return;
        roads.removeIf(road -> road != null
                && road.hasOwnerPair()
                && road.ownerA2dKey() == target.ownerA2dKey()
                && road.ownerB2dKey() == target.ownerB2dKey());
    }

    public static void addSupplementalConnection(List<StructureConnection> supplemental,
                                                   StructureConnection candidate) {
        if (supplemental == null || candidate == null) return;
        PlannedPathKey key = PlannedPathKey.of(candidate);
        for (StructureConnection existing : supplemental) {
            if (existing != null && PlannedPathKey.of(existing).equals(key)) return;
        }
        supplemental.add(new StructureConnection(candidate.from(), candidate.to(), ConnectionStatus.COMPLETED));
    }

    public static boolean candidateConnectsToExistingNetwork(
            RoadData candidateRoad, List<RoadData> generatedRoads) {
        if (candidateRoad == null || candidateRoad.roadSegmentList().isEmpty()
                || generatedRoads == null) return false;
        for (RoadData existing : generatedRoads) {
            if (existing != null && roadsGeometricallyConnected(existing, candidateRoad)) return true;
        }
        return false;
    }

    public static boolean roadsGeometricallyConnected(RoadData first, RoadData second) {
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

    public static void await(ServerLevel level, CompletableFuture<?> future) {
        if (future == null) return;
        MinecraftServer server = level == null ? null : level.getServer();
        if (server != null && server.isSameThread()) {
            server.managedBlock(future::isDone);
        }
        future.join();
    }
}
