/* 文件职责：按原代次、服务器线程和进度顺序准备并发布规划快照。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;

// 大块编排整体迁移，保留代次检查和发布次序，避免拆散后让旧世界结果进入新缓存。
final class PlanningSnapshotPreparation {
    private PlanningSnapshotPreparation() {}
    static CompletableFuture<Void> preparePlanningSnapshotsOnServerThread(
            ServerLevel level, Collection<StructureConnection> connections,
            PrepareTiming timing) {
        var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Structure snapshots must be submitted on the server thread"));
        }
        if (timing != null) timing.markRan();

        long snapshotStartedAt = System.nanoTime();
        long predictedBefore = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get();
        long manualBefore = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get();
        long cacheBefore = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get();
        long emptyBefore = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get();
        long boxesBefore = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get();
        long tilesBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get();
        long predictionChunksBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get();
        long tileCacheBefore = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get();
        long candidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get();
        long corridorCandidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get();
        long directStartsBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get();
        long directFallbacksBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get();
        boolean trackInitialProgress = InitialGenManager.isActive();
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "predicting_structure_obstacles");
        }
        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        long epoch = ThreadPoolManager.currentEpoch();
        ThreadPoolManager.TaskRole predictionRole = trackInitialProgress
                ? ThreadPoolManager.TaskRole.INITIAL
                : ThreadPoolManager.TaskRole.STRUCTURE;
        // 0.9.059（①）：服务器线程上的前缀段（取快照 + 取 epoch），不计入下面任何子段。
        if (timing != null) timing.addServerInit(System.nanoTime() - snapshotStartedAt);
        return ThreadPoolManager.supplyAsync(predictionRole, () -> {
            long predictionStartedAt = System.nanoTime();
            if (!ThreadPoolManager.isEpoch(epoch)) {
                throw new java.util.concurrent.CancellationException("RoadWeaver world changed before structure prediction");
            }
            long tileSampleStartedAt = System.nanoTime();
            Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> obstacleCandidates =
                    predictObstacleCandidates(level, connections);
            if (timing != null) timing.addTileSample(System.nanoTime() - tileSampleStartedAt);
            long startKeyStartedAt = System.nanoTime();
            Map<RouteKey, RouteStartPlan> routePlans = new LinkedHashMap<>();
            Set<PreparedStartKey> uniqueStarts = new LinkedHashSet<>();
            for (StructureConnection connection : connections) {
                if (connection == null) continue;
                RouteKey key = routeKey(level, connection);
                if (StructureAvoidanceCache.PLANNING_SNAPSHOTS.containsKey(key)) continue;
                Set<PreparedStartKey> starts = collectPlanningStartKeys(
                        level, connection, storageSnapshot, obstacleCandidates.getOrDefault(key, List.of()));
                routePlans.put(key, new RouteStartPlan(connection, starts, Set.of()));
                uniqueStarts.addAll(starts);
            }
            if (timing != null) {
                timing.addStartKeys(System.nanoTime() - startKeyStartedAt);
                timing.setRoutes(routePlans.size());
                timing.setStarts(uniqueStarts.size());
            }
            SnapshotPreparation preparation = new SnapshotPreparation(routePlans, List.copyOf(uniqueStarts));
            LOGGER.debug("RoadWeaver structure prediction timing: dimension={} routes={} uniqueStarts={} elapsedMs={}",
                    level.dimension().location(), routePlans.size(), uniqueStarts.size(),
                    elapsedMillis(predictionStartedAt));
            return preparation;
        }).thenCompose(preparation -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                        "RoadWeaver world changed before structure snapshot loading"));
            }
            if (trackInitialProgress) {
                InitialGenerationProgressTracker.setPlanningStartPlan(
                        preparation.uniqueStarts().size(), "loading_exact_structure_starts");
            }
            long resolutionStartedAt = System.nanoTime();
            return resolvePreparedStartsAsync(level, preparation.uniqueStarts(), trackInitialProgress, timing)
                    .thenAccept(resolvedStarts -> {
                if (!ThreadPoolManager.isEpoch(epoch)) return;
                // 0.9.059（①）：结构占用叠加段 = 精确起点解析整段。
                if (timing != null) timing.addStructureOverlay(System.nanoTime() - resolutionStartedAt);
                long cacheWriteStartedAt = System.nanoTime();
                for (Map.Entry<RouteKey, RouteStartPlan> entry : preparation.routePlans().entrySet()) {
                    Set<OwnedStructureBox> boxes = new HashSet<>();
                    for (PreparedStartKey start : entry.getValue().starts()) {
                        List<OwnedStructureBox> prepared = resolvedStarts.get(start);
                        if (prepared != null) boxes.addAll(prepared);
                    }
                    indexConstructionBoxes(boxes);
                    Set<StructureOwnerKey> endpointOwners = resolveEndpointOwners(
                            entry.getValue().connection(), storageSnapshot, boxes);
                    PlanningSnapshot snapshot = PlanningSnapshot.forRoute(
                            entry.getValue().connection(), boxes, entry.getValue().starts(), Set.of(), endpointOwners);
                    StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(entry.getKey(), snapshot.endpointOwners());
                    putBounded(StructureAvoidanceCache.PLANNING_SNAPSHOTS, StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER, entry.getKey(), snapshot,
                            PLANNING_SNAPSHOT_CACHE_CAPACITY);
                }
                if (trackInitialProgress) {
                    InitialGenerationProgressTracker.completePlanningSnapshots("structure_snapshots_ready");
                }
                // 0.9.059（①）：写缓存段 = 合并进 StructureAvoidanceCache.PLANNING_SNAPSHOTS + 施工盒索引 + 有界淘汰。
                if (timing != null) {
                    timing.addCacheWrite(System.nanoTime() - cacheWriteStartedAt);
                    timing.observeWorkerThread();
                }

                long predicted = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get() - predictedBefore;
                long manual = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get() - manualBefore;
                long cacheHits = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get() - cacheBefore;
                long empty = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get() - emptyBefore;
                long boxes = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get() - boxesBefore;
                long tiles = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get() - tilesBefore;
                long predictionChunks = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get() - predictionChunksBefore;
                long tileCacheHits = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get() - tileCacheBefore;
                long candidates = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get() - candidatesBefore;
                long corridorCandidates = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get() - corridorCandidatesBefore;
                long directStarts = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get() - directStartsBefore;
                long directFallbacks = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get() - directFallbacksBefore;
                if (timing != null) {
                    timing.setObstacleTiles((int) Math.min(Integer.MAX_VALUE, tiles));
                    timing.setResolvedBoxes((int) Math.min(Integer.MAX_VALUE, boxes));
                }
                if (predicted + manual + cacheHits + tiles + tileCacheHits > 0L) {
                    LOGGER.info("RoadWeaver structure snapshots: obstacleTiles={} predictionChunks={} obstacleTileCacheHits={} obstacleCandidates={} corridorCandidates={} predictedDirect={} nativeDirectStarts={} realChunkFallbacks={} manualReferenceFallback={} cacheHits={} emptyResults={} exactPieceBoxes={} prefilterSkipped={} prefilterKept={} prefilterEnabled={}",
                            tiles, predictionChunks, tileCacheHits, candidates, corridorCandidates,
                            predicted, directStarts, directFallbacks,
                            manual, cacheHits, empty, boxes,
                            PREFILTERED_CANDIDATES.get(), PREFILTER_KEPT_CANDIDATES.get(),
                            STRUCTURE_PREFILTER_ENABLED.get());
                }
                LOGGER.info("RoadWeaver structure snapshot timing: dimension={} routes={} uniqueStarts={} resolutionMs={} totalMs={}",
                        level.dimension().location(), preparation.routePlans().size(),
                        preparation.uniqueStarts().size(), elapsedMillis(resolutionStartedAt),
                        elapsedMillis(snapshotStartedAt));
                // 0.9.034（R-07 计数模式）：累计值（进程内），不改任何结果。
                // wouldRejectNonEmpty 就是「开群系预筛会丢掉的真实结构数」—— 必须是 0 才算可证明安全。
                LOGGER.info("RoadWeaver structure biome-prefilter probe (count only, results unchanged): "
                                + "probed={} wouldReject={} wouldRejectNonEmpty={} emptyResults={}",
                        StructureAvoidanceDiagnostics.BIOME_PROBE_PROBED.get(), StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED.get(),
                        StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED_NONEMPTY.get(), empty);
            });
        });
    }
}
