/* 文件职责：规划快照的编排 —— 决定「为哪条路线、在什么时机、取哪批结构」。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;

// 仍留在其它同包类里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;

/**
 * 规划快照编排器。
 *
 * <h2>它负责的边界</h2>
 * <p>「结构起点怎么解析」由 {@code PreparedStartResolver} 负责；
 * 「哪些结构盒会挡路」由 {@code PlanningSnapshot} 负责。
 * 本类只管<b>调度</b>：为哪条路线、在什么时机、并行取几批、谁来等结果。</p>
 *
 * <h2>三条通道，成本差一个数量级</h2>
 * <ul>
 *   <li><b>走廊通道</b>（{@code preparePathCorridorSnapshots}）：已知完整走线，
 *       精确、最贵，用于最终确认；</li>
 *   <li><b>弦通道</b>（{@code preparePlanningSnapshotsForPathsAsync}）：只知道两端，
 *       便宜、可能漏，用于早期粗筛；</li>
 *   <li><b>圆通道</b>（{@code ensurePlanningSnapshotForCircleAsync}）：改道时只知道
 *       圆心与半径，按圆域取一批 —— 半径受 {@code clampCircleProbeRadius} 限制，
 *       否则一次改道会把整条快照通道据为己有。</li>
 * </ul>
 *
 * <h2>为什么全是异步</h2>
 * <p>取快照要读区块。规划线程不能等，所以先返回 future，规划侧拿不到就用空快照
 * 继续 —— 宁可少避让一次，也不许把服务端线程卡住。</p>
 *
 * <p>本类是从 1830 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 逻辑未改（含缩进）。</p>
 */
public final class SnapshotOrchestrator {
    public static CompletableFuture<Void> preparePlanningSnapshotsAsync(
            ServerLevel level, Collection<StructureConnection> connections) {
        return preparePlanningSnapshotsAsync(level, connections, null);
    }

    /** 0.9.059（①）：带子段计时的同名入口；{@code timing} 为 null 时行为与旧入口逐位相同。 */
    public static CompletableFuture<Void> preparePlanningSnapshotsAsync(
            ServerLevel level, Collection<StructureConnection> connections, PrepareTiming timing) {
        if (level == null || connections == null || connections.isEmpty()
                || !Level.OVERWORLD.equals(level.dimension())
                || !ConfigService.get().structureAvoidanceEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        List<StructureConnection> connectionSnapshot = List.copyOf(connections);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                preparePlanningSnapshotsOnServerThread(level, connectionSnapshot, timing)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) result.complete(null);
                            else result.completeExceptionally(failure);
                        });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (server.isSameThread()) task.run();
        else server.execute(task);
        return result;
    }

    /**
     * Resolves exact structure starts only for chunks that can reach the
     * already-computed road paths. Terrain pathfinding therefore remains
     * unchanged and no endpoint-to-endpoint scan is performed.
     */
    public static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level, Map<StructureConnection, List<BlockPos>> paths) {
        return preparePlanningSnapshotsForPathsAsync(level, paths, true, null);
    }

    public static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            ThreadPoolManager.TaskRole requestedRole) {
        return preparePlanningSnapshotsForPathsAsync(level, paths, true, requestedRole);
    }

    /** Incremental corridor refresh used after grade or structure rerouting. */
    public static CompletableFuture<Void> ensurePlanningSnapshotForPathAsync(
            ServerLevel level, StructureConnection connection, List<BlockPos> path) {
        if (connection == null || path == null || path.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return preparePlanningSnapshotsForPathsAsync(
                level, Map.of(connection, List.copyOf(path)), false,
                ThreadPoolManager.TaskRole.SNAPSHOT);
    }

    /**
     * Loads structure starts covering a local conflict circle. The synthetic
     * probe path is used only to enumerate chunks; it is never persisted as a
     * road path. This keeps local building discovery independent from the
     * terrain sampling mask and guarantees that every chunk intersecting the
     * radius-100 repair area is inspected before rerouting.
     */
    /**
     * Upper bound on probe chunks for one conflict-circle snapshot prediction.
     * A radius-200 circle probes ~490 chunks and monopolized the snapshot lane
     * when local detours escalated through every radius step.
     */
    public static final int MAX_CIRCLE_PROBE_CHUNKS = 256;

    /**
     * Largest radius whose circle probe set stays within
     * {@link #MAX_CIRCLE_PROBE_CHUNKS} (probes ≈ π·r²/256, matching the chunk
     * centre enumeration below).
     */
    public static int clampCircleProbeRadius(int requestedRadius) {
        int radius = Math.max(1, requestedRadius);
        while (radius > 16
                && (long) (Math.PI * radius * radius / 256.0D) > MAX_CIRCLE_PROBE_CHUNKS) {
            radius -= 16;
        }
        return radius;
    }

    public static CompletableFuture<Void> ensurePlanningSnapshotForCircleAsync(
            ServerLevel level,
            StructureConnection connection,
            BlockPos center,
            int radius) {
        if (level == null || connection == null || center == null || radius <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        int safeRadius = Math.max(1, radius);
        int effectiveRadius = clampCircleProbeRadius(safeRadius);
        if (effectiveRadius < safeRadius) {
            LOGGER.info("RoadWeaver circle snapshot radius clamped by probe budget: requested={} effective={} maxProbeChunks={}",
                    safeRadius, effectiveRadius, MAX_CIRCLE_PROBE_CHUNKS);
        }
        int minChunkX = Math.floorDiv(center.getX() - effectiveRadius, 16);
        int maxChunkX = Math.floorDiv(center.getX() + effectiveRadius, 16);
        int minChunkZ = Math.floorDiv(center.getZ() - effectiveRadius, 16);
        int maxChunkZ = Math.floorDiv(center.getZ() + effectiveRadius, 16);
        long radiusSquared = (long) effectiveRadius * effectiveRadius;
        List<BlockPos> probes = new ArrayList<>();
        probes.add(center);
        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                int x = cx * 16 + 8;
                int z = cz * 16 + 8;
                long dx = (long) x - center.getX();
                long dz = (long) z - center.getZ();
                if (dx * dx + dz * dz <= radiusSquared + 256L) {
                    probes.add(new BlockPos(x, center.getY(), z));
                }
            }
        }
        LOGGER.info("RoadWeaver local structure circle snapshot: center=({}, {}) radius={} probeChunks={}",
                center.getX(), center.getZ(), effectiveRadius, probes.size());
        return preparePlanningSnapshotsForPathsAsync(
                level, Map.of(connection, List.copyOf(probes)), false,
                ThreadPoolManager.TaskRole.SNAPSHOT);
    }

    static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (level == null || paths == null || paths.isEmpty()
                || !Level.OVERWORLD.equals(level.dimension())
                || !ConfigService.get().structureAvoidanceEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        Map<StructureConnection, List<BlockPos>> pathSnapshot = new LinkedHashMap<>();
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            pathSnapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        if (pathSnapshot.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            CompletableFuture<Void> source = preparePathCorridorSnapshots(
                    level,
                    pathSnapshot,
                    trackInitialProgress && InitialGenManager.isActive(),
                    requestedRole);
            source.whenComplete((ignored, failure) -> {
                        if (failure == null) result.complete(null);
                        else result.completeExceptionally(failure);
                    });
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) source.cancel(true);
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    static CompletableFuture<Void> preparePathCorridorSnapshots(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (trackInitialProgress) {
            return preparePathCorridorSnapshotsNow(level, paths, true, requestedRole);
        }
        List<CompletableFuture<Void>> routeFutures = new ArrayList<>(paths.size());
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            StructureConnection connection = entry.getKey();
            List<BlockPos> path = entry.getValue();
            if (connection == null || path == null || path.isEmpty()) continue;
            RouteKey routeKey = routeKey(level, connection);
            AtomicReference<CompletableFuture<Void>> createdRef = new AtomicReference<>();
            StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.compute(routeKey, (ignored, existing) -> {
                CompletableFuture<Void> predecessor = existing == null
                        ? CompletableFuture.completedFuture(null)
                        : existing.handle((completed, failure) -> null);
                CompletableFuture<Void> created = predecessor.thenCompose(completed ->
                        preparePathCorridorSnapshotWithPermit(
                                level,
                                Map.of(connection, List.copyOf(path)),
                                trackInitialProgress,
                                requestedRole));
                createdRef.set(created);
                return created;
            });
            CompletableFuture<Void> created = createdRef.get();
            created.whenComplete((completed, failure) ->
                    StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.remove(routeKey, created));
            routeFutures.add(created);
        }
        return routeFutures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(routeFutures.toArray(CompletableFuture[]::new));
    }

    static CompletableFuture<Void> preparePathCorridorSnapshotWithPermit(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (trackInitialProgress) {
            return preparePathCorridorSnapshotsNow(level, paths, true, requestedRole);
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        return CompletableFuture.runAsync(() -> {
            try {
                StructureAvoidanceCache.RUNTIME_CORRIDOR_PERMITS.acquire();
                acquired.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interrupted);
            }
        }).thenCompose(ignored ->
                preparePathCorridorSnapshotsNow(level, paths, false, requestedRole))
                .whenComplete((completed, failure) -> {
                    if (acquired.get()) StructureAvoidanceCache.RUNTIME_CORRIDOR_PERMITS.release();
                });
    }

    static CompletableFuture<Void> preparePathCorridorSnapshotsNow(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        long startedAt = System.nanoTime();
        long epoch = ThreadPoolManager.currentEpoch();
        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        ThreadPoolManager.TaskRole predictionRole = trackInitialProgress
                ? ThreadPoolManager.TaskRole.INITIAL
                : requestedRole == null ? ThreadPoolManager.TaskRole.STRUCTURE : requestedRole;
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "predicting_road_corridor_structures");
        }

        return ThreadPoolManager.supplyAsync(predictionRole, () -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                throw new CancellationException("RoadWeaver world changed before corridor prediction");
            }
            Map<RouteKey, PathCorridorPlan> corridors = buildPathCorridors(level, paths, true);
            Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> obstacleCandidates =
                    predictObstacleCandidatesForCorridors(level, corridors, trackInitialProgress);
            Map<RouteKey, RouteStartPlan> routePlans = new LinkedHashMap<>();
            Set<PreparedStartKey> uniqueStarts = new LinkedHashSet<>();
            int reusedStarts = 0;
            for (Map.Entry<RouteKey, PathCorridorPlan> entry : corridors.entrySet()) {
                PathCorridorPlan corridor = entry.getValue();
                Set<PreparedStartKey> collected = collectCorridorStartKeys(
                        level,
                        storageSnapshot,
                        corridor.candidateChunks(),
                        corridor.path(),
                        obstacleCandidates.getOrDefault(entry.getKey(), List.of()));
                PlanningSnapshot previous = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(entry.getKey());
                Set<PreparedStartKey> starts = new LinkedHashSet<>(collected);
                if (previous != null && !previous.resolvedStarts().isEmpty()) {
                    int before = starts.size();
                    starts.removeAll(previous.resolvedStarts());
                    reusedStarts += before - starts.size();
                }
                DetailedRoadGenerationStats.recordCorridorNewStarts(starts.size());
                routePlans.put(entry.getKey(), new RouteStartPlan(
                        corridor.connection(), starts, corridor.candidateChunks()));
                uniqueStarts.addAll(starts);
            }
            if (reusedStarts > 0) {
                LOGGER.info("RoadWeaver path corridor reused exact structure starts: routes={} reused={} newlyRequired={}",
                        routePlans.size(), reusedStarts, uniqueStarts.size());
            }
            return new SnapshotPreparation(routePlans, List.copyOf(uniqueStarts));
        }).thenCompose(preparation -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                return CompletableFuture.failedFuture(
                        new CancellationException("RoadWeaver world changed before corridor start resolution"));
            }
            if (trackInitialProgress) {
                InitialGenerationProgressTracker.setPlanningStartPlan(
                        preparation.uniqueStarts().size(), "loading_road_corridor_structure_starts");
            }
            ThreadPoolManager.TaskRole resolutionRole = trackInitialProgress
                    ? ThreadPoolManager.TaskRole.INITIAL
                    : requestedRole == null ? ThreadPoolManager.TaskRole.STRUCTURE : requestedRole;
            return resolvePreparedStartsAsync(
                    level, preparation.uniqueStarts(), trackInitialProgress, resolutionRole)
                    .thenAccept(resolvedStarts -> {
                        if (!ThreadPoolManager.isEpoch(epoch)) return;
                        int mergedBoxes = 0;
                        for (Map.Entry<RouteKey, RouteStartPlan> entry : preparation.routePlans().entrySet()) {
                            Set<OwnedStructureBox> boxes = new HashSet<>();
                            for (PreparedStartKey start : entry.getValue().starts()) {
                                List<OwnedStructureBox> prepared = resolvedStarts.get(start);
                                if (prepared != null) boxes.addAll(prepared);
                            }
                            RouteKey key = entry.getKey();
                            indexConstructionBoxes(boxes);
                            AtomicReference<PlanningSnapshot> previousRef = new AtomicReference<>();
                            AtomicReference<PlanningSnapshot> mergedRef = new AtomicReference<>();
                            StructureAvoidanceCache.PLANNING_SNAPSHOTS.compute(key, (ignored, current) -> {
                                previousRef.set(current);
                                Set<OwnedStructureBox> allBoxes = new HashSet<>();
                                if (current != null) allBoxes.addAll(current.boxes());
                                allBoxes.addAll(boxes);
                                Set<StructureOwnerKey> endpointOwners = resolveEndpointOwners(
                                        entry.getValue().connection(), storageSnapshot, allBoxes);
                                PlanningSnapshot mergedSnapshot = PlanningSnapshot.merge(
                                        entry.getValue().connection(), current, boxes,
                                        entry.getValue().starts(), entry.getValue().coveredCandidateChunks(),
                                        endpointOwners);
                                mergedRef.set(mergedSnapshot);
                                return mergedSnapshot;
                            });
                            PlanningSnapshot previous = previousRef.get();
                            PlanningSnapshot merged = mergedRef.get();
                            mergedBoxes += Math.max(0, merged.boxes().size()
                                    - (previous == null ? 0 : previous.boxes().size()));
                            StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(key, merged.endpointOwners());
                            if (previous == null) {
                                StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER.add(key);
                                trimBounded(StructureAvoidanceCache.PLANNING_SNAPSHOTS, StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER,
                                        PLANNING_SNAPSHOT_CACHE_CAPACITY);
                            }
                        }
                        if (trackInitialProgress) {
                            InitialGenerationProgressTracker.completePlanningSnapshots(
                                    "road_corridor_structures_ready");
                        }
                        LOGGER.info("RoadWeaver path corridor snapshots: dimension={} routes={} uniqueStarts={} newlyMergedBoxes={} totalMs={}",
                                level.dimension().location(), preparation.routePlans().size(),
                                preparation.uniqueStarts().size(), mergedBoxes, elapsedMillis(startedAt));
                    });
        });
    }
}
