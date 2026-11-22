/* 文件职责：原边确有高程失败且隔断可用分量时，只跑一轮有上限的开局补边。 */
package net.shiroha233.roadweaver.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.ConnectionFallbackService;
import net.shiroha233.roadweaver.planning.RouteRefinementOps;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import static net.shiroha233.roadweaver.generation.InitialGenManager.*;

final class InitialConnectivityRepair {
    private static final int MAX_FAILURES_INSPECTED = 8;
    private static final int MAX_CANDIDATES = 4;

    private InitialConnectivityRepair() {}

    static List<StructureConnection> run(ServerLevel level, List<StructureConnection> current,
            List<StructureConnection> unresolved, Map<PlannedPathKey, GenResult> resolved,
            Map<PlannedPathKey, ProfileConflict> profileFailures, int configuredStep, int edgeLengthLimit,
            List<RoadData> generated) throws InterruptedException {
        if (unresolved.isEmpty() || profileFailures.isEmpty()) return List.of();
        var settled = knownConnections(current, unresolved, resolved);
        var topology = InitialNetworkConnectivity.from(settled);
        var candidates = new ArrayList<StructureConnection>();
        var owners = new HashMap<PlannedPathKey, StructureConnection>();
        var reservedEndpoints = new HashSet<Long>();
        int inspected = 0;
        for (StructureConnection failed : unresolved) {
            if (!profileFailures.containsKey(PlannedPathKey.of(failed))
                    || topology.connected(failed.from(), failed.to())) continue;
            // 预热最多检查八条和生成四条，失败多时也不能重新精修整张网。
            if (inspected++ >= MAX_FAILURES_INSPECTED || candidates.size() >= MAX_CANDIDATES) break;
            for (StructureConnection candidate : ConnectionFallbackService.startupCandidates(
                    level, failed, settled, edgeLengthLimit)) {
                long from = flatKey(candidate.from().getX(), candidate.from().getZ());
                long to = flatKey(candidate.to().getX(), candidate.to().getZ());
                var key = PlannedPathKey.of(candidate);
                if (reservedEndpoints.contains(from) || reservedEndpoints.contains(to)
                        || topology.connected(candidate.from(), candidate.to())
                        || owners.putIfAbsent(key, failed) != null) continue;
                reservedEndpoints.add(from);
                reservedEndpoints.add(to);
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) return List.of();

        var phase = InitialGenerationProgressTracker.RoutePhase.CANDIDATE;
        int step = RoutePrecisionRetryPolicy.candidateBatchSteps(configuredStep).getFirst();
        InitialGenerationProgressTracker.beginRoutePhase(phase, candidates.size(), "sampling");
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.ROAD_GENERATION, "candidate_refinement_step_" + step);
        var retained = new ArrayList<StructureConnection>();
        int rejected = 0;
        try {
            awaitInitialFuture(level, RouteRefinementOps.refineInitialRoutesAsync(level, candidates, step, 100));
            InitialGenerationProgressTracker.setRoutePhaseOperation(phase, "generating");
            var batch = InitialRouteSubmitOps.submitAndCollectAtStep(level, candidates, step);
            for (StructureConnection candidate : candidates) {
                var key = PlannedPathKey.of(candidate);
                var result = batch.results().get(key);
                boolean suppressed = ConnectionDeletionService.shouldAbort(level, candidate);
                if (suppressed || !usable(candidate, result)) {
                    rejected++;
                    // 用户删除、取消或关服不是高程不合格，不能记成下次永不重试的负样本。
                    if (result != null && !suppressed) {
                        StructureFileStorage.recordFailedFallbackAttempt(level, owners.get(key), candidate);
                    }
                    continue;
                }
                if (topology.connected(candidate.from(), candidate.to())) continue;
                // 只扩展图；原连接仍报失败，候补还须通过同一批最终占地验收并落盘。
                topology.join(candidate);
                generated.add(result.road());
                retained.add(candidate);
            }
            LOGGER.info("【开局补边提交终态验收】 [INITIAL-CONNECTIVITY-REPAIR] inspected={} attempted={} retained={} rejected={} step={}",
                    Math.min(inspected, MAX_FAILURES_INSPECTED), candidates.size(), retained.size(), rejected, step);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
            rejected = candidates.size();
            LOGGER.warn("【开局补边未完成，保留既有道路计划】 [INITIAL-CONNECTIVITY-REPAIR-FAILED]", failure.getCause());
        } finally {
            InitialGenerationProgressTracker.settleRoutePhase(phase, retained.size(), rejected, 0, 0, "finished");
        }
        return List.copyOf(retained);
    }

    /** 候选几何必须与实际提交端点相符，带缺口的候选不能用来证明接通。 */
    static boolean usable(StructureConnection candidate, GenResult result) {
        return result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                && result.road() != null && result.road().hasOwnerPair() && !result.road().hasProtectedGap()
                && PlannedPathKey.of(candidate).equals(InitialPlanLifecycleOps.ownerKey(result.road()));
    }

    /** 使用本轮已跑过的结果覆盖待办状态，禁止用还没完成的边做连通证据。 */
    private static List<StructureConnection> knownConnections(List<StructureConnection> current,
            List<StructureConnection> unresolved, Map<PlannedPathKey, GenResult> resolved) {
        var settled = new ArrayList<StructureConnection>();
        if (current == null) return settled;
        Set<PlannedPathKey> failed = new HashSet<>();
        for (StructureConnection edge : unresolved) failed.add(PlannedPathKey.of(edge));
        for (StructureConnection edge : current) {
            if (edge == null) continue;
            var key = PlannedPathKey.of(edge);
            if (failed.contains(key)) {
                settled.add(new StructureConnection(edge.from(), edge.to(), ConnectionStatus.FAILED));
                continue;
            }
            var result = resolved.get(key);
            if (result == null || result.result() != RoadGenerationService.InitialTaskResult.COMPLETED
                    || result.road() == null || result.effectiveConnection() == null) {
                settled.add(edge);
                continue;
            }
            var effective = result.effectiveConnection();
            settled.add(new StructureConnection(effective.from(), effective.to(),
                    result.road().hasProtectedGap() ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED));
        }
        return settled;
    }

    private static long flatKey(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }
}
