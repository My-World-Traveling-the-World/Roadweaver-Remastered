/* 文件职责：沿一条路线铺出「避让走廊」—— 只做几何与候选枚举，不做避让判定。 */
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

// 仍留在避让服务里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;

/**
 * 避让走廊构建器。
 *
 * <h2>走廊是什么</h2>
 * <p>一条路的实际走线可能离两端连线（弦）很远 —— U 形绕行时横向偏离可达上千格。
 * 如果只按弦去取结构快照，A* 在绕行段就<b>看不见真正的障碍</b>，会一头撞进村子里。
 * 走廊就是这个问题的答案：把路线覆盖的区块铺成一条带宽的带子，
 * 快照按「带子 + 弦」的并集去取。</p>
 *
 * <h2>为什么是「区块级」而不是「逐格」</h2>
 * <p>下游的索引单位就是区块（结构盒按区块分桶、结构预测也按区块枚举）。
 * 逐格铺带会得到同一批区块的重复集合，还要额外去重；直接按区块铺，
 * 一次 {@code long} 集合搞定，且与预测器的输入口径天然对齐。</p>
 *
 * <h2>本类不做什么</h2>
 * <p>它<b>不判定</b>任何结构是否该避让 —— 只回答「这条路线经过哪些区块」。
 * 判定留给 {@code StructureAvoidanceService} 与 {@code PlanningSnapshot}。</p>
 *
 * <p>本类是从 2539 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 逻辑未改（含缩进）。两侧调用点靠双向静态导入保持零改动。</p>
 */
public final class PathCorridorBuilder {
    static Map<RouteKey, PathCorridorPlan> buildPathCorridors(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean onlyUncoveredChunks) {
        Map<RouteKey, PathCorridorPlan> result = new LinkedHashMap<>();
        int reachChunks = STRUCTURE_START_REACH_CHUNKS + PATH_CORRIDOR_MARGIN_CHUNKS;
        int reachSquared = reachChunks * reachChunks;
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            StructureConnection connection = entry.getKey();
            List<BlockPos> path = entry.getValue();
            if (connection == null || path == null || path.isEmpty()) continue;

            Set<Long> pathChunks = new LinkedHashSet<>();
            BlockPos previous = null;
            for (BlockPos point : path) {
                if (point == null) continue;
                int chunkX = point.getX() >> 4;
                int chunkZ = point.getZ() >> 4;
                if (previous == null) {
                    pathChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                } else {
                    addChunkLine(pathChunks,
                            previous.getX() >> 4, previous.getZ() >> 4, chunkX, chunkZ);
                }
                previous = point;
            }
            if (pathChunks.isEmpty()) continue;

            RouteKey routeKey = routeKey(level, connection);
            PlanningSnapshot previousSnapshot = onlyUncoveredChunks
                    ? StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey)
                    : null;
            LongSet coveredChunks = previousSnapshot == null
                    ? LongSets.emptySet()
                    : previousSnapshot.coveredCandidateChunks();
            LongSet candidateChunks = new LongOpenHashSet();
            for (long pathChunkKey : pathChunks) {
                ChunkPos pathChunk = new ChunkPos(pathChunkKey);
                for (int dx = -reachChunks; dx <= reachChunks; dx++) {
                    for (int dz = -reachChunks; dz <= reachChunks; dz++) {
                        if (dx * dx + dz * dz > reachSquared) continue;
                        long candidateChunk = ChunkPos.asLong(pathChunk.x + dx, pathChunk.z + dz);
                        if (!coveredChunks.contains(candidateChunk)) candidateChunks.add(candidateChunk);
                    }
                }
            }
            boolean skipped = candidateChunks.isEmpty();
            DetailedRoadGenerationStats.recordCorridorRefresh(candidateChunks.size(), 0, skipped);
            if (!skipped) {
                result.put(routeKey, new PathCorridorPlan(
                        connection, List.copyOf(path), Set.copyOf(pathChunks),
                        LongSets.unmodifiable(candidateChunks)));
            }
        }
        return result;
    }

    static void addChunkLine(Set<Long> chunks, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int error = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            chunks.add(ChunkPos.asLong(x, z));
            if (x == x1 && z == z1) return;
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x += sx;
            }
            if (doubled < dx) {
                error += dx;
                z += sz;
            }
        }
    }

    static Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>>
    predictObstacleCandidatesForCorridors(
            ServerLevel level,
            Map<RouteKey, PathCorridorPlan> corridors,
            boolean trackInitialProgress) {
        Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> result = new HashMap<>();
        Set<ObstacleTileKey> requiredTiles = new LinkedHashSet<>();
        for (PathCorridorPlan corridor : corridors.values()) {
            for (long chunkKey : corridor.candidateChunks()) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                requiredTiles.add(new ObstacleTileKey(
                        level.dimension().location().toString(),
                        level.getSeed(),
                        Math.floorDiv(chunk.x, AVOIDANCE_PREDICTION_TILE_CHUNKS),
                        Math.floorDiv(chunk.z, AVOIDANCE_PREDICTION_TILE_CHUNKS)));
            }
        }

        if (trackInitialProgress) {
            InitialGenerationProgressTracker.setPlanningTilePlan(
                    requiredTiles.size(), "predicting_road_corridor_structure_tiles");
        }
        for (ObstacleTileKey tileKey : requiredTiles) {
            List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                    StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
            if (tileCandidates == null) {
                int tileMinChunkX = tileKey.tileX() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMinChunkZ = tileKey.tileZ() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                tileCandidates = List.copyOf(StructurePredictor.predictAvoidanceStructuresInRect(
                        level,
                        tileMinChunkX,
                        tileMinChunkZ,
                        tileMinChunkX + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1,
                        tileMinChunkZ + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1));
                List<StructurePredictor.AvoidanceStructureCandidate> existing = putIfAbsentBounded(
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES, StructureAvoidanceCache.OBSTACLE_TILE_ORDER, tileKey, tileCandidates,
                        OBSTACLE_TILE_CACHE_CAPACITY);
                if (existing == null) {
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.incrementAndGet();
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.addAndGet(
                            (long) AVOIDANCE_PREDICTION_TILE_CHUNKS * AVOIDANCE_PREDICTION_TILE_CHUNKS);
                    StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.addAndGet(tileCandidates.size());
                } else {
                    tileCandidates = existing;
                    StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
                }
            } else {
                StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
            }
            if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningTileDone();
        }

        for (Map.Entry<RouteKey, PathCorridorPlan> routeEntry : corridors.entrySet()) {
            PathCorridorPlan corridor = routeEntry.getValue();
            Set<PredictedCandidateKey> seen = new HashSet<>();
            List<StructurePredictor.AvoidanceStructureCandidate> candidates = new ArrayList<>();
            Set<ObstacleTileKey> routeTiles = new LinkedHashSet<>();
            for (long chunkKey : corridor.candidateChunks()) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                routeTiles.add(new ObstacleTileKey(
                        level.dimension().location().toString(),
                        level.getSeed(),
                        Math.floorDiv(chunk.x, AVOIDANCE_PREDICTION_TILE_CHUNKS),
                        Math.floorDiv(chunk.z, AVOIDANCE_PREDICTION_TILE_CHUNKS)));
            }
            for (ObstacleTileKey tileKey : routeTiles) {
                List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
                if (tileCandidates == null) continue;
                for (StructurePredictor.AvoidanceStructureCandidate candidate : tileCandidates) {
                    if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                    long chunkKey = ChunkPos.asLong(candidate.pos().getX() >> 4, candidate.pos().getZ() >> 4);
                    if (!corridor.candidateChunks().contains(chunkKey)) continue;
                    PredictedCandidateKey candidateKey = new PredictedCandidateKey(
                            chunkKey, candidate.structureSet());
                    if (seen.add(candidateKey)) candidates.add(candidate);
                }
            }
            StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.addAndGet(candidates.size());
            result.put(routeEntry.getKey(), List.copyOf(candidates));
        }
        long pathChunks = corridors.values().stream().mapToLong(corridor -> corridor.pathChunks().size()).sum();
        long candidateChunks = corridors.values().stream()
                .mapToLong(corridor -> corridor.candidateChunks().size()).sum();
        long predictedCandidates = result.values().stream().mapToLong(List::size).sum();
        Map<String, Integer> candidatesBySet = new HashMap<>();
        for (List<StructurePredictor.AvoidanceStructureCandidate> candidates : result.values()) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : candidates) {
                String id = candidate.structureSet().unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse(candidate.structureSet().value().getClass().getName());
                candidatesBySet.merge(id, 1, Integer::sum);
            }
        }
        String topSets = candidatesBySet.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(8)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        LOGGER.info("RoadWeaver corridor prediction scope: routes={} pathChunks={} candidateChunks={} predictionTiles={} predictedCandidates={} topSets={}",
                corridors.size(), pathChunks, candidateChunks, requiredTiles.size(), predictedCandidates, topSets);
        return result;
    }

    static Set<PreparedStartKey> collectCorridorStartKeys(
            ServerLevel level,
            StructureFileStorage.StructureSnapshot storageSnapshot,
            Set<Long> candidateChunks,
            List<BlockPos> corridorPath,
            List<StructurePredictor.AvoidanceStructureCandidate> obstacleCandidates) {
        Set<PreparedStartKey> starts = new LinkedHashSet<>();
        List<StructureInfo> known = storageSnapshot.locations().structureInfos();
        if (known != null) {
            for (StructureInfo info : known) {
                if (info == null || info.pos() == null) continue;
                long chunkKey = ChunkPos.asLong(info.pos().getX() >> 4, info.pos().getZ() >> 4);
                // T-35: 圆盘候选集只覆盖路径区块附近的 chunk,绕山路(U 型/沿山脊)
                // 会让村庄锚点 chunk 落在圆盘外,落库村庄因此从未进入快照,过路
                // 掩码全空,道路整片穿过村庄(施工期才逐建筑补救)。沿用老路径
                // collectPlanningStartKeys 的"距实际路径 <= 192 格"标准,把这类
                // 村庄强制纳入候选,使其能参与 passThroughVillageGapMask。
                boolean reachable = candidateChunks.contains(chunkKey)
                        || withinCorridorPath(info.pos(), corridorPath);
                if (!reachable) continue;
                collectCandidateStartKey(level, info, storageSnapshot.sourceAt(info.pos()), starts);
            }
        }
        if (obstacleCandidates != null) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : obstacleCandidates) {
                if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                int candidateChunkX = candidate.pos().getX() >> 4;
                int candidateChunkZ = candidate.pos().getZ() >> 4;
                // E 阶段预筛：放置数学判定不可能的候选不进精确解析。
                if (!isPotentialStructureChunk(level, candidate.structureSet(), candidateChunkX, candidateChunkZ)) {
                    PREFILTERED_CANDIDATES.incrementAndGet();
                    continue;
                }
                PREFILTER_KEPT_CANDIDATES.incrementAndGet();
                starts.add(preparedStartKey(
                        level,
                        candidateChunkX,
                        candidateChunkZ,
                        StructureFileStorage.SOURCE_PREDICTED,
                        candidate.structureSet()));
            }
        }
        return Set.copyOf(starts);
    }

    /**
     * True when any polyline segment of the corridor path lies within
     * {@link #PLANNING_CORRIDOR_RADIUS} blocks of the given position.
     */
    static boolean withinCorridorPath(BlockPos pos, List<BlockPos> corridorPath) {
        if (pos == null || corridorPath == null || corridorPath.isEmpty()) return false;
        long reachSquared = (long) PLANNING_CORRIDOR_RADIUS * PLANNING_CORRIDOR_RADIUS;
        BlockPos previous = null;
        for (BlockPos point : corridorPath) {
            if (point == null) continue;
            if (previous == null) {
                previous = point;
                continue;
            }
            if (distanceSquaredToSegment(pos.getX(), pos.getZ(), previous, point) <= reachSquared) {
                return true;
            }
            previous = point;
        }
        return false;
    }

    static Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> predictObstacleCandidates(
            ServerLevel level, Collection<StructureConnection> connections) {
        Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> result = new HashMap<>();
        int reachBlocks = PLANNING_CORRIDOR_RADIUS + STRUCTURE_START_REACH_CHUNKS * 16;
        long reachSquared = (long) reachBlocks * reachBlocks;
        Map<RouteKey, RoutePredictionBounds> routeBounds = new LinkedHashMap<>();
        Set<ObstacleTileKey> requiredTiles = new LinkedHashSet<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            RouteKey routeKey = routeKey(level, connection);
            if (StructureAvoidanceCache.PLANNING_SNAPSHOTS.containsKey(routeKey)) continue;
            int minChunkX = Math.floorDiv(Math.min(connection.from().getX(), connection.to().getX()) - reachBlocks, 16);
            int maxChunkX = Math.floorDiv(Math.max(connection.from().getX(), connection.to().getX()) + reachBlocks, 16);
            int minChunkZ = Math.floorDiv(Math.min(connection.from().getZ(), connection.to().getZ()) - reachBlocks, 16);
            int maxChunkZ = Math.floorDiv(Math.max(connection.from().getZ(), connection.to().getZ()) + reachBlocks, 16);
            int minTileX = Math.floorDiv(minChunkX, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int maxTileX = Math.floorDiv(maxChunkX, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int minTileZ = Math.floorDiv(minChunkZ, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int maxTileZ = Math.floorDiv(maxChunkZ, AVOIDANCE_PREDICTION_TILE_CHUNKS);

            RoutePredictionBounds bounds = new RoutePredictionBounds(
                    connection, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    minTileX, maxTileX, minTileZ, maxTileZ, reachSquared);
            routeBounds.put(routeKey, bounds);
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                    requiredTiles.add(new ObstacleTileKey(
                            level.dimension().location().toString(), level.getSeed(), tileX, tileZ));
                }
            }
        }

        boolean trackInitialProgress = InitialGenManager.isActive();
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.setPlanningTilePlan(
                    requiredTiles.size(), "predicting_structure_obstacle_tiles");
        }
        for (ObstacleTileKey tileKey : requiredTiles) {
            List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                    StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
            if (tileCandidates == null) {
                int tileMinChunkX = tileKey.tileX() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMinChunkZ = tileKey.tileZ() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMaxChunkX = tileMinChunkX + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1;
                int tileMaxChunkZ = tileMinChunkZ + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1;
                tileCandidates = List.copyOf(StructurePredictor.predictAvoidanceStructuresInRect(
                        level, tileMinChunkX, tileMinChunkZ, tileMaxChunkX, tileMaxChunkZ));
                List<StructurePredictor.AvoidanceStructureCandidate> existing = putIfAbsentBounded(
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES, StructureAvoidanceCache.OBSTACLE_TILE_ORDER, tileKey, tileCandidates,
                        OBSTACLE_TILE_CACHE_CAPACITY);
                if (existing == null) {
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.incrementAndGet();
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.addAndGet(
                            (long) AVOIDANCE_PREDICTION_TILE_CHUNKS * AVOIDANCE_PREDICTION_TILE_CHUNKS);
                    StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.addAndGet(tileCandidates.size());
                } else {
                    StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
                }
            } else {
                StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
            }
            if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningTileDone();
        }

        for (Map.Entry<RouteKey, RoutePredictionBounds> routeEntry : routeBounds.entrySet()) {
            RoutePredictionBounds bounds = routeEntry.getValue();
            Set<PredictedCandidateKey> seenStarts = new HashSet<>();
            List<StructurePredictor.AvoidanceStructureCandidate> routeCandidates = new ArrayList<>();
            for (int tileX = bounds.minTileX(); tileX <= bounds.maxTileX(); tileX++) {
                for (int tileZ = bounds.minTileZ(); tileZ <= bounds.maxTileZ(); tileZ++) {
                    ObstacleTileKey tileKey = new ObstacleTileKey(
                            level.dimension().location().toString(), level.getSeed(), tileX, tileZ);
                    List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                            StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
                    if (tileCandidates == null) continue;
                    for (StructurePredictor.AvoidanceStructureCandidate candidate : tileCandidates) {
                        if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                        BlockPos pos = candidate.pos();
                        int cx = pos.getX() >> 4;
                        int cz = pos.getZ() >> 4;
                        if (cx < bounds.minChunkX() || cx > bounds.maxChunkX()
                                || cz < bounds.minChunkZ() || cz > bounds.maxChunkZ()) continue;
                        if (distanceSquaredToSegment(pos.getX(), pos.getZ(),
                                bounds.connection().from(), bounds.connection().to()) > bounds.reachSquared()) continue;
                        PredictedCandidateKey candidateKey = new PredictedCandidateKey(
                                ChunkPos.asLong(cx, cz), candidate.structureSet());
                        if (seenStarts.add(candidateKey)) routeCandidates.add(candidate);
                    }
                }
            }
            result.put(routeEntry.getKey(), List.copyOf(routeCandidates));
        }
        return result;
    }

    static Set<PreparedStartKey> collectPlanningStartKeys(ServerLevel level,
                                                                  StructureConnection connection,
                                                                  StructureFileStorage.StructureSnapshot storageSnapshot,
                                                                  List<StructurePredictor.AvoidanceStructureCandidate> obstacleCandidates) {
        int minX = Math.min(connection.from().getX(), connection.to().getX()) - PLANNING_CORRIDOR_RADIUS;
        int maxX = Math.max(connection.from().getX(), connection.to().getX()) + PLANNING_CORRIDOR_RADIUS;
        int minZ = Math.min(connection.from().getZ(), connection.to().getZ()) - PLANNING_CORRIDOR_RADIUS;
        int maxZ = Math.max(connection.from().getZ(), connection.to().getZ()) + PLANNING_CORRIDOR_RADIUS;
        List<StructureInfo> known = storageSnapshot.locations().structureInfos();

        Set<PreparedStartKey> starts = new LinkedHashSet<>();
        if (known != null) {
            for (StructureInfo info : known) {
                if (info == null || info.pos() == null) continue;
                BlockPos pos = info.pos();
                if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
                if (distanceSquaredToSegment(pos.getX(), pos.getZ(), connection.from(), connection.to())
                        > (long) PLANNING_CORRIDOR_RADIUS * PLANNING_CORRIDOR_RADIUS) continue;
                collectCandidateStartKey(level, info, storageSnapshot.sourceAt(pos), starts);
            }
        }
        if (obstacleCandidates != null) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : obstacleCandidates) {
                if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                int cx = candidate.pos().getX() >> 4;
                int cz = candidate.pos().getZ() >> 4;
                // E 阶段预筛：放置数学判定不可能的候选不进精确解析。
                if (!isPotentialStructureChunk(level, candidate.structureSet(), cx, cz)) {
                    PREFILTERED_CANDIDATES.incrementAndGet();
                    continue;
                }
                PREFILTER_KEPT_CANDIDATES.incrementAndGet();
                starts.add(preparedStartKey(level, cx, cz,
                        StructureFileStorage.SOURCE_PREDICTED, candidate.structureSet()));
            }
        }
        return Set.copyOf(starts);
    }

    static void collectCandidateStartKey(ServerLevel level,
                                                 StructureInfo info,
                                                 int source,
                                                 Set<PreparedStartKey> starts) {
        if (info == null || info.pos() == null) return;
        int cx = info.pos().getX() >> 4;
        int cz = info.pos().getZ() >> 4;
        starts.add(preparedStartKey(level, cx, cz, source, null));
    }
}
