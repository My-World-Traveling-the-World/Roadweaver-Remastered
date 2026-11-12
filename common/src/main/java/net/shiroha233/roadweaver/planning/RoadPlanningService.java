/* 文件职责：协调结构发现、连接拓扑规划与道路地形规划用例。 */
package net.shiroha233.roadweaver.planning;

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
import net.shiroha233.roadweaver.planning.impl.KNNPlanner;
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

/**
 * 道路规划服务
 */
public final class RoadPlanningService {
    private RoadPlanningService() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** Endpoint-side stations merged into one shared approach profile. */
    private static final int APPROACH_WINDOW_STATIONS = 16;
    private static final Set<Long> ROAD_BACKFILL_PLAN_TILES = ConcurrentHashMap.newKeySet();
    private static final Set<Long> PLANNING_TILES = ConcurrentHashMap.newKeySet();
    /**
     * Carries construction-time protected-gap locations into the local retry
     * phase. The retry receives only a connection, so without this handoff it
     * has to rediscover a conflict from the coarse path and can miss it.
     */
    private static final Map<PlannedPathKey, List<BlockPos>> PROTECTED_GAP_HINTS = new ConcurrentHashMap<>();

    /**
     * Pre-plans one agreed height for every unknown structure endpoint before
     * any road is generated. All incoming routes are gathered from the task
     * list; for each route the coarse planned path's endpoint-side window (the
     * 16 stations nearest the endpoint) is read, and the endpoint is pinned to
     * the lowest such natural approach height. This makes every road into the
     * same unknown node build to one height regardless of generation order, so
     * a high-side first road cannot lock a tall surface and force a low-side
     * road to build a sudden bridge. Spawn and village endpoints are excluded.
     */
        public static void preplanUnknownEndpointHeights(ServerLevel level,
                                                      List<StructureConnection> tasks) {
        if (level == null || tasks == null || tasks.isEmpty()) return;
        Map<Long, List<StructureConnection>> byEndpoint = new HashMap<>();
        Map<Long, BlockPos> endpointPos = new HashMap<>();
        for (StructureConnection conn : tasks) {
            if (conn == null) continue;
            if (Road.isUnknownStructureEndpoint(level, conn, true)) {
                long key = PlanningUtils.pos2dKey(conn.from());
                byEndpoint.computeIfAbsent(key, k -> new ArrayList<>()).add(conn);
                endpointPos.putIfAbsent(key, conn.from());
            }
            if (Road.isUnknownStructureEndpoint(level, conn, false)) {
                long key = PlanningUtils.pos2dKey(conn.to());
                byEndpoint.computeIfAbsent(key, k -> new ArrayList<>()).add(conn);
                endpointPos.putIfAbsent(key, conn.to());
            }
        }
        if (byEndpoint.isEmpty()) return;
        TerrainSamplingMode mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
        PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
        AccurateHeightSampler sampler = AccurateHeightSampler.create(level);
        for (Map.Entry<Long, List<StructureConnection>> entry : byEndpoint.entrySet()) {
            BlockPos ep = endpointPos.get(entry.getKey());
            if (ep == null) continue;
            String classification = classifyUnknownEndpoint(level, ep);
            int[] envelope = null;
            int agreed = Integer.MAX_VALUE;
            int windowLowest = Integer.MAX_VALUE;
            for (StructureConnection conn : entry.getValue()) {
                Optional<List<BlockPos>> planned = PlannedPathCache.find(level, conn, mode, cfg);
                if (planned.isEmpty()) continue;
                List<BlockPos> path = planned.get();
                if (path.size() < 2) continue;
                boolean tail = conn.to() != null
                        && conn.to().getX() == ep.getX() && conn.to().getZ() == ep.getZ();
                int stations = Math.min(APPROACH_WINDOW_STATIONS, path.size());
                int[] terrain = new int[stations];
                int maxReach = Integer.MAX_VALUE;
                for (int j = 0; j < stations; j++) {
                    int idx = tail ? path.size() - 1 - j : j;
                    BlockPos point = path.get(idx);
                    int surface = sampleSurface(sampler, point);
                    terrain[j] = surface;
                    windowLowest = Math.min(windowLowest, surface);
                    maxReach = Math.min(maxReach, surface + j);
                }
                agreed = Math.min(agreed, maxReach);
                if (envelope == null) {
                    envelope = terrain;
                } else {
                    for (int j = 0; j < Math.min(envelope.length, terrain.length); j++) {
                        envelope[j] = Math.min(envelope[j], terrain[j]);
                    }
                }
            }
            if (envelope != null && envelope.length > 0) {
                int[] shared = new int[envelope.length];
                shared[0] = agreed;
                for (int j = 1; j < shared.length; j++) {
                    shared[j] = Math.max(shared[j - 1] - 1,
                            Math.min(shared[j - 1] + 1, envelope[j]));
                }
                UnknownEndpointHeightRegistry.preplan(entry.getKey(), agreed, shared);
                LOGGER.info("RoadWeaver unknown endpoint preplanned agreed height={} endpoint=({},{}) "
                                + "incoming={} class={} windowLowest={} sharedStations={}",
                        agreed, ep.getX(), ep.getZ(), entry.getValue().size(),
                        classification, windowLowest, shared.length);
            }
        }
    }

    /** Real surface height for one column, guarded against transient sampling failures. */
    private static int sampleSurface(AccurateHeightSampler sampler, BlockPos point) {
        if (point == null) return 0;
        try {
            return sampler.surfaceHeight(point.getX(), point.getZ());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** Endpoint identity from the structure snapshot, for planning diagnostics. */
    private static String classifyUnknownEndpoint(ServerLevel level, BlockPos endpoint) {
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot != null && snapshot.locations() != null) {
            for (StructureInfo info : snapshot.locations().structureInfos()) {
                if (info == null || info.pos() == null) continue;
                if (info.pos().getX() == endpoint.getX()
                        && info.pos().getZ() == endpoint.getZ()) {
                    String id = info.structureId();
                    if (StructureInfo.isVillageId(id)) return "village";
                    if (StructureInfo.isKnownId(id)) return "known:" + id;
                    return "unknown-listed";
                }
            }
        }
        return "topology-only";
    }
public static void rememberProtectedGapHints(StructureConnection connection, RoadData road) {
        if (connection == null || road == null || road.spans() == null || road.spans().isEmpty()) return;
        List<BlockPos> centers = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            BlockPos start = span.start();
            BlockPos end = span.end();
            if (start != null) centers.add(start);
            if (end != null) centers.add(end);
            if (start != null && end != null) {
                centers.add(new BlockPos(
                        Math.floorDiv(start.getX() + end.getX(), 2),
                        Math.floorDiv(start.getY() + end.getY(), 2),
                        Math.floorDiv(start.getZ() + end.getZ(), 2)));
            }
        }
        if (centers.isEmpty()) return;
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos center : centers) {
            if (center != null) unique.putIfAbsent(center.asLong(), center);
        }
        if (!unique.isEmpty()) {
            PROTECTED_GAP_HINTS.put(PlannedPathKey.of(connection), List.copyOf(unique.values()));
            LOGGER.info("RoadWeaver persisted protected-gap local hints centers={} radius={} connection={}->{}",
                    unique.size(), ConfigService.get().planning().localConflictRadiusBlocks(),
                    connection.from(), connection.to());
        }
    }

    private static List<BlockPos> consumeProtectedGapHints(StructureConnection connection) {
        if (connection == null) return List.of();
        List<BlockPos> hints = PROTECTED_GAP_HINTS.remove(PlannedPathKey.of(connection));
        return hints == null ? List.of() : hints;
    }

    /**
     * Returns whether the persisted route currently intersects a real building.
     * The path fingerprint binds the query to one A* precision, so callers
     * must pass the same step the route was registered (or rebound) under.
     */
    public static boolean hasBuildingConflict(ServerLevel level, StructureConnection connection, int step) {
        if (level == null || connection == null) return false;
        ModConfig config = ConfigService.get();
        List<BlockPos> path = PlannedPathCache.find(
                level, connection, TerrainSamplingSessions.forLevel(level).effectiveMode(),
                config.pathfindingCost().withAStarStep(Math.max(1, step))).orElse(List.of());
        if (path.isEmpty()) return false;
        try (StructureAvoidanceService.PlanningScope ignored =
                     StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
            for (BlockPos point : path) {
                if (point != null && StructureAvoidanceService.shouldAvoidPlanning(point)) return true;
            }
            for (int i = 1; i < path.size(); i++) {
                if (pathEdgeConflicts(path, i)) return true;
            }
        }
        return false;
    }

    /**
     * Rebinds an already generated coarse route to a retry precision. Retry
     * generation must refine the saved corridor; it must not lose the path
     * cache and rescan the entire endpoint-to-endpoint region again.
     */
    public static void rebindGeneratedRouteForRetry(ServerLevel level,
                                                    StructureConnection connection,
                                                    RoadData road,
                                                    int step) {
        if (level == null || connection == null || road == null
                || road.roadSegmentList() == null || road.roadSegmentList().size() < 2) return;
        List<BlockPos> centers = new ArrayList<>(road.roadSegmentList().size());
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            if (segment == null || segment.middlePos() == null) return;
            centers.add(segment.middlePos());
        }
        PathfindingCostConfig retryConfig = ConfigService.get().pathfindingCost()
                .withAStarStep(Math.max(1, step));
        PlannedPathCache.register(level,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                retryConfig,
                Map.of(connection, List.copyOf(centers)));
        LOGGER.info("RoadWeaver rebound generated route for local retry: step={} nodes={} connection={}->{}",
                retryConfig.effectiveAStarStep(), centers.size(), connection.from(), connection.to());
    }
    private static final RoadTerrainPlanningPipeline TERRAIN_PLANNING = new RoadTerrainPlanningPipeline();

    private static void prunePlannedIfTooLarge(Level level) {
        // Planned tile markers are persistent progress, not a bounded cache.
        // Never drop arbitrary entries after a large-radius scan: doing so
        // schedules already planned regions again and can create duplicate work.
        if (level != null) {
            LOGGER.debug("RoadWeaver retained all persistent planning markers dimension={}",
                    level.dimension().location());
        }
    }

    public static void initialPlan(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.PLANNING, "discovering_structures");
        ModConfig cfg = ConfigService.get();
        int radiusChunks = Math.max(1, cfg.planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = (cx - radiusChunks) * 16;
        int maxX = (cx + radiusChunks) * 16;
        int minZ = (cz - radiusChunks) * 16;
        int maxZ = (cz + radiusChunks) * 16;
        planRect(level, minX, minZ, maxX, maxZ);
    }

    public static void planAroundPlayer(ServerPlayer player) {
        IdleRoadGenerationService.tickPlayer(player);
    }

    private static void planAroundPlayerLegacy(ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        ModConfig cfg = ConfigService.get();
        if (!cfg.planning().dynamicPlanEnabled()) return;
        int radiusChunks = Math.max(1, cfg.planning().dynamicPlanRadiusChunks());
        int stride = Math.max(1, cfg.planning().dynamicPlanStrideChunks());
        int tile = Math.max(RoadConstants.PLAN_TILE_MIN, Math.min(RoadConstants.PLAN_TILE_MAX, stride));
        int pcx = player.chunkPosition().x;
        int pcz = player.chunkPosition().z;
        int kx = floorDiv(pcx, tile);
        int kz = floorDiv(pcz, tile);
        long key = (((long) kx) << 32) ^ (kz & 0xffffffffL);
        int minX = (pcx - radiusChunks) * 16;
        int maxX = (pcx + radiusChunks) * 16;
        int minZ = (pcz - radiusChunks) * 16;
        int maxZ = (pcz + radiusChunks) * 16;
        WorldDataProvider provider0 = WorldDataProvider.getInstance();
        boolean alreadyPlanned = provider0.getPlannedTileKeys(level).contains(key);
        boolean hasExistingRoads = RoadShardStorage.hasRoadInRect(level, minX, minZ, maxX, maxZ);
        boolean backfillPlan = hasExistingRoads && markRoadBackfillPlanTile(level, key);
        boolean shouldPlan = !alreadyPlanned || backfillPlan;
        if (!shouldPlan) {
            return;
        }
        if (!markPlanningTile(level, key)) return;
        AutomaticPlanningSamplingActivities.Activity samplingActivity = beginDynamicPlanningSampling(
                level,
                minX,
                minZ,
                maxX,
                maxZ,
                cfg.planning().terrainSamplingMode());
        try {
            planRectAsync(level, minX, minZ, maxX, maxZ).whenComplete((ignored, error) -> {
                try {
                    unmarkPlanningTile(level, key);
                    if (error != null) {
                        if (backfillPlan) unmarkRoadBackfillPlanTile(level, key);
                        LOGGER.warn("动态规划 tile 失败，保留重试机会 dimension={} tile=[{},{}]", level.dimension().location(), kx, kz, error);
                        return;
                    }
                    markPlannedTile(level, key, pcx, pcz);
                    prunePlannedIfTooLarge(level);
                } finally {
                    finishDynamicPlanningSampling(level, samplingActivity);
                }
            });
        } catch (RuntimeException submissionFailure) {
            unmarkPlanningTile(level, key);
            if (backfillPlan) {
                unmarkRoadBackfillPlanTile(level, key);
            }
            finishDynamicPlanningSampling(level, samplingActivity);
            throw submissionFailure;
        }
    }

    private static boolean markRoadBackfillPlanTile(ServerLevel level, long tileKey) {
        return ROAD_BACKFILL_PLAN_TILES.add(tileKey);
    }

    private static void unmarkRoadBackfillPlanTile(ServerLevel level, long tileKey) {
        ROAD_BACKFILL_PLAN_TILES.remove(tileKey);
    }

    private static boolean markPlanningTile(ServerLevel level, long tileKey) {
        return PLANNING_TILES.add(tileKey);
    }

    private static void unmarkPlanningTile(ServerLevel level, long tileKey) {
        PLANNING_TILES.remove(tileKey);
    }

    private static AutomaticPlanningSamplingActivities.Activity beginDynamicPlanningSampling(
            ServerLevel level,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ,
            TerrainSamplingMode mode) {
        if (mode == null || mode == TerrainSamplingMode.LEGACY_DIRECT) {
            return null;
        }
        AutomaticPlanningSamplingActivities.Activity activity = AutomaticPlanningSamplingActivities.begin(
                level,
                new AutomaticPlanningSamplingBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ));
        publishDynamicPlanningSampling(level);
        return activity;
    }

    private static void finishDynamicPlanningSampling(ServerLevel level,
                                                       AutomaticPlanningSamplingActivities.Activity activity) {
        if (activity == null) {
            return;
        }
        activity.close();
        publishDynamicPlanningSampling(level);
    }

    private static void publishDynamicPlanningSampling(ServerLevel level) {
        if (level == null) {
            return;
        }
        try {
            ServerMapPlanningActivityBridge.broadcast(level);
        } catch (RuntimeException failure) {
            LOGGER.debug("自动规划采样范围同步失败 dimension={}", level.dimension().location(), failure);
        }
    }

    private static void markPlannedTile(ServerLevel level, long tileKey, int centerChunkX, int centerChunkZ) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        Set<Long> set = new HashSet<>(provider.getPlannedTileKeys(level));
        set.add(tileKey);
        Map<Long, Long> centers = new HashMap<>(provider.getPlannedTileCenters(level));
        centers.putIfAbsent(tileKey, (((long) centerChunkX) << 32) ^ (centerChunkZ & 0xffffffffL));
        provider.setPlannedTileKeys(level, set);
        provider.setPlannedTileCenters(level, centers);
    }

    private static void planRect(ServerLevel level,
                                 int minBlockX,
                                 int minBlockZ,
                                 int maxBlockX,
                                 int maxBlockZ) {
        long startedAt = System.nanoTime();
        StructureFileStorage.pruneUnknownNonSpawnEndpoints(level);
        List<BlockPos> points = new ArrayList<>();
        HashSet<Long> seenPos = new HashSet<>();
        collectStructurePointsInto(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, points, seenPos);
        long structureIndexMillis = elapsedMillis(startedAt);
        if (points.size() < 2) {
            LOGGER.info("RoadWeaver planning timing: dimension={} structures={} structureIndexMs={} terrainPlanningMs=0 totalMs={}",
                    level.dimension().location(), points.size(), structureIndexMillis, elapsedMillis(startedAt));
            return;
        }

        ModConfig cfg0 = ConfigService.get();
        List<StructureConnection> primaryEdges = planPrimaryEdges(points, cfg0);
        if (primaryEdges.isEmpty()) return;

        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> current = provider.getStructureConnections(level);
        ConnectionTopologyReconciler.Result existingTopology =
                ConnectionTopologyReconciler.reconcile(current, List.of());
        List<StructureConnection> existing = existingTopology.connections();

        ArrayList<StructureConnection> existingInRect = new ArrayList<>();
        if (existing != null) {
            for (StructureConnection c : existing) {
                if (inRect2d(c.from(), minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                        && inRect2d(c.to(), minBlockX, minBlockZ, maxBlockX, maxBlockZ)) {
                    existingInRect.add(c);
                }
            }
        }

        List<StructureConnection> filteredPrimary =
                ConnectionTopologyReconciler.filterNewConnections(existing, primaryEdges);
        ArrayList<StructureConnection> base = new ArrayList<>(existingInRect);
        base.addAll(filteredPrimary);
        ArrayList<BlockPos> componentPoints = collectComponentPoints(points, existingInRect, filteredPrimary);

        List<StructureConnection> bridges = KNNPlanner.connectComponents(
                componentPoints, base,
                cfg0.planning().bridgeJoinLenBlocks(),
                cfg0.planning().componentMinAngleDeg(),
                cfg0.planning().componentDegreeCap()
        );

        ArrayList<StructureConnection> incoming = new ArrayList<>(filteredPrimary);
        incoming.addAll(bridges);
        incoming = new ArrayList<>(StructureFileStorage.filterSuppressedConnections(level, incoming));
        ConnectionTopologyReconciler.Result reconciled =
                ConnectionTopologyReconciler.reconcile(current, incoming);
        if (reconciled.changed()) {
            provider.setStructureConnections(level, reconciled.connections());
            publishTopologyChanges(level, reconciled);
        }
        long terrainStartedAt = System.nanoTime();
        planRoadTerrain(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, incoming);

        // 0.9.083：路径全算完了，趁进入生成之前把「末尾并行」的重复路截断成接入主干。
        //
        // 真机取证（存档 20261111 1704）：三条连接汇聚到同一村庄 (1936,-400)，
        // 其中 (1936,-400)->(2464,-672) 1570 站与 (2880,-1152)->(1936,-400) 2441 站
        // 足迹范围几乎完全重叠、却各自曲折穿插 —— 这就是地图上那片乱网。
        // 规划期改写拓扑最省事：不用碰已生成的方块，也不用重跑整轮规划。
        Map<StructureConnection, StructureConnection> truncatedEdges =
                RoadOverlapTruncator.findReplacements(level, incoming);
        if (!truncatedEdges.isEmpty()) {
            for (int i = 0; i < incoming.size(); i++) {
                StructureConnection replacement = truncatedEdges.get(incoming.get(i));
                if (replacement != null) incoming.set(i, replacement);
            }
            // 已持久化拓扑必须「删旧加新」——只走 reconcile 会把旧连接留着，
            // 结果 A→C 与 A→Q 同时存在，生成时又变成两条路。
            List<StructureConnection> persisted = provider.getStructureConnections(level);
            ArrayList<StructureConnection> rebuilt = new ArrayList<>();
            if (persisted != null) {
                for (StructureConnection connection : persisted) {
                    if (truncatedEdges.containsKey(connection)) continue;
                    rebuilt.add(connection);
                }
            }
            rebuilt.addAll(truncatedEdges.values());
            provider.setStructureConnections(level, rebuilt);
            LOGGER.info("RoadWeaver overlap truncation committed: replaced={} topologySize={}",
                    truncatedEdges.size(), rebuilt.size());
        }
        LOGGER.info("RoadWeaver planning timing: dimension={} structures={} connections={} structureIndexMs={} terrainPlanningMs={} totalMs={}",
                level.dimension().location(), points.size(), incoming.size(), structureIndexMillis,
                elapsedMillis(terrainStartedAt), elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 0.8.420：按配置生成主边。KNN 算法时把 PlanningConfig 的四个拓扑旋钮
     * （k / alpha / minAngleDeg / degreeCap）显式传给 {@link KNNPlanner}；
     * 其它算法走 {@link NetworkPlanner} 默认两参接口。配置值与默认值相同时
     * 产出与旧硬编码路径逐位一致。
     * 0.8.421：长度上限也从 {@code RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS}
     * 改为读配置（{@code planMaxEdgeLenBlocks}），默认值不变。
     */
    private static List<StructureConnection> planPrimaryEdges(List<BlockPos> points, ModConfig cfg0) {
        NetworkPlanner planner = NetworkPlannerFactory.create(cfg0.planning().planningAlgorithm());
        int maxEdge = cfg0.planning().planMaxEdgeLenBlocks();
        if (planner instanceof KNNPlanner knn) {
            return knn.plan(points, maxEdge,
                    cfg0.planning().knnK(),
                    cfg0.planning().knnAlpha(),
                    cfg0.planning().knnMinAngleDeg(),
                    cfg0.planning().knnDegreeCap());
        }
        return planner.plan(points, maxEdge);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static void collectStructurePointsInto(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, List<BlockPos> out, Set<Long> seenPos) {
        StructureIndexService.predictAndVerifyInRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        List<StructureInfo> cached = StructureFileStorage.queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        if (cached == null || cached.isEmpty()) return;
        for (StructureInfo info : cached) {
            if (info == null || info.pos() == null) continue;
            if (!isPlanningEndpointAllowed(level, info)) continue;
            BlockPos p = info.pos();
            int x = p.getX(), z = p.getZ();
            if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) continue;
            BlockPos q = new BlockPos(x, 0, z);
            long key = PlanningUtils.pos2dKey(q);
            if (seenPos.add(key)) out.add(q);
        }
    }

    private static boolean isPlanningEndpointAllowed(ServerLevel level, StructureInfo info) {
        return info != null && (StructureInfo.isVillageId(info.structureId())
                || isPlanningEndpointAllowed(level, info.pos()));
    }

    private static boolean isPlanningEndpointAllowed(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        return spawn != null && spawn.getX() == pos.getX() && spawn.getZ() == pos.getZ();
    }

    /**
     * Keeps connections whose two endpoints are the shared spawn or any
     * collected structure location, including unknown structure entries.
     * Only topology-only placeholder nodes that were never collected as a
     * real structure remain excluded.
     */
    public static List<StructureConnection> filterVillageAndSpawnConnections(
            ServerLevel level, List<StructureConnection> connections) {
        Set<Long> keep = collectedEndpointKeys(level);
        if (connections == null || connections.isEmpty() || keep.isEmpty()) return List.of();
        List<StructureConnection> result = new ArrayList<>();
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            long fromKey = PlanningUtils.pos2dKey(new BlockPos(connection.from().getX(), 0, connection.from().getZ()));
            long toKey = PlanningUtils.pos2dKey(new BlockPos(connection.to().getX(), 0, connection.to().getZ()));
            if (keep.contains(fromKey) && keep.contains(toKey)) result.add(connection);
        }
        return List.copyOf(result);
    }

    private static Set<Long> collectedEndpointKeys(ServerLevel level) {
        Set<Long> keep = new HashSet<>();
        if (level == null) return keep;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null) {
            keep.add(PlanningUtils.pos2dKey(new BlockPos(spawn.getX(), 0, spawn.getZ())));
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot != null && snapshot.locations() != null
                && snapshot.locations().structureInfos() != null) {
            for (StructureInfo info : snapshot.locations().structureInfos()) {
                if (info == null || info.pos() == null) continue;
                keep.add(PlanningUtils.pos2dKey(new BlockPos(info.pos().getX(), 0, info.pos().getZ())));
            }
        }
        return keep;
    }
    public static List<StructureConnection> filterConnectionsToSelectedEndpoints(
            ServerLevel level, List<StructureConnection> connections) {
        Set<Long> keep = selectedEndpointKeys(level);
        if (connections == null || connections.isEmpty()) return List.of();
        if (keep.isEmpty()) return List.of();
        List<StructureConnection> result = new ArrayList<>();
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            long fromKey = PlanningUtils.pos2dKey(new BlockPos(connection.from().getX(), 0, connection.from().getZ()));
            long toKey = PlanningUtils.pos2dKey(new BlockPos(connection.to().getX(), 0, connection.to().getZ()));
            if (keep.contains(fromKey) && keep.contains(toKey)) {
                result.add(connection);
            }
        }
        return List.copyOf(result);
    }

    private static Set<Long> selectedEndpointKeys(ServerLevel level) {
        Set<Long> keep = new HashSet<>();
        if (level == null) return keep;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null) {
            keep.add(PlanningUtils.pos2dKey(new BlockPos(spawn.getX(), 0, spawn.getZ())));
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot != null && snapshot.locations() != null
                && snapshot.locations().structureInfos() != null) {
            for (StructureInfo info : snapshot.locations().structureInfos()) {
                if (info == null || info.pos() == null) continue;
                keep.add(PlanningUtils.pos2dKey(new BlockPos(info.pos().getX(), 0, info.pos().getZ())));
            }
        }
        return keep;
    }

    public static CompletableFuture<Void> initialPlanAsync(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return CompletableFuture.completedFuture(null);
        ModConfig cfg = ConfigService.get();
        int radiusChunks = Math.max(1, cfg.planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = (cx - radiusChunks) * 16;
        int maxX = (cx + radiusChunks) * 16;
        int minZ = (cz - radiusChunks) * 16;
        int maxZ = (cz + radiusChunks) * 16;
        // Initial planning runs behind the loading screen.  It must use the
        // dedicated initial pool rather than the one-lane runtime pool; the
        // latter is reserved for in-game player-first background work.
        return planRectAsync(
                level, minX, minZ, maxX, maxZ,
                ThreadPoolManager.TaskRole.INITIAL, false);
    }

    /**
     * Samples one startup-wide terrain batch while planning only the supplied
     * unresolved connections. Successful routes therefore keep their original
     * path, while all failed routes share the same precision-specific tiles.
     */
    public static CompletableFuture<Void> refineInitialBatchAsync(
            ServerLevel level,
            int[] samplingBounds,
            List<StructureConnection> connectionsToPlan,
            int step) {
        return refineInitialBatchAsync(level, samplingBounds, connectionsToPlan, step, null, 0);
    }

    private static CompletableFuture<Void> refineInitialBatchAsync(
            ServerLevel level,
            int[] samplingBounds,
            List<StructureConnection> connectionsToPlan,
            int step,
            BlockPos samplingCenter,
            int samplingRadius) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || connectionsToPlan == null || connectionsToPlan.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        ModConfig config = ConfigService.get();
        TerrainSamplingMode mode = config.planning().terrainSamplingMode();
        if (mode == null || mode == TerrainSamplingMode.LEGACY_DIRECT) {
            return CompletableFuture.completedFuture(null);
        }
        int[] bounds = normalizedBounds(
                samplingBounds == null ? 0 : samplingBounds[0],
                samplingBounds == null ? 0 : samplingBounds[1],
                samplingBounds == null ? 0 : samplingBounds[2],
                samplingBounds == null ? 0 : samplingBounds[3]);
        PathfindingCostConfig pathfinding = config.pathfindingCost().withAStarStep(step);
        long epoch = ThreadPoolManager.currentEpoch();
        List<StructureConnection> tasks = List.copyOf(connectionsToPlan);
        LOGGER.info("RoadWeaver initial refinement batch scheduled: step={} bounds=[{},{} -> {},{}] "
                        + "samplingMask={} center={} radius={} connections={}",
                pathfinding.effectiveAStarStep(), bounds[0], bounds[1], bounds[2], bounds[3],
                samplingCenter != null, samplingCenter, Math.max(0, samplingRadius), tasks.size());
        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            if (!ThreadPoolManager.isEpoch(epoch)) return null;
            RoadTerrainPlanningPort.Request request = new RoadTerrainPlanningPort.Request(
                    level,
                    new RoadTerrainPlanningPort.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]),
                    tasks,
                    pathfinding,
                    samplingCenter,
                    samplingRadius);
            RoadTerrainPlanningPort.Result result = TERRAIN_PLANNING.plan(request);
            if (!ThreadPoolManager.isEpoch(epoch)) return null;
            result = filterSuppressedPaths(level, result);
            if (result == null || result.paths().isEmpty()) return result;
            result = SharedEndpointPathReuse.reuseSharedEndpointTails(
                    level, result, pathfinding.effectiveAStarStep());
            PlannedPathCache.register(level, result.mode(), pathfinding, result.paths());
            LOGGER.info("RoadWeaver initial refinement batch planned: step={} requested={} resolvedPaths={}",
                    pathfinding.effectiveAStarStep(), tasks.size(), result.paths().size());
            return result;
        }).thenCompose(result -> result == null || result.paths().isEmpty()
                        || !ThreadPoolManager.isEpoch(epoch)
                ? CompletableFuture.completedFuture(null)
                : prepareLocalSnapshotsAndPaths(level, result, samplingCenter, samplingRadius));
    }

    private static CompletableFuture<Void> prepareLocalSnapshotsAndPaths(
            ServerLevel level,
            RoadTerrainPlanningPort.Result result,
            BlockPos samplingCenter,
            int samplingRadius) {
        CompletableFuture<Void> circle = CompletableFuture.completedFuture(null);
        if (samplingCenter != null && samplingRadius > 0 && result.paths().size() == 1) {
            StructureConnection connection = result.paths().keySet().iterator().next();
            circle = StructureAvoidanceService.ensurePlanningSnapshotForCircleAsync(
                    level, connection, samplingCenter, samplingRadius);
        }
        return circle.thenCompose(ignored ->
                StructureAvoidanceService.preparePlanningSnapshotsForPathsAsync(
                        level, result.paths(), ThreadPoolManager.TaskRole.INITIAL));
    }

    /**
     * Refines failed routes one at a time using an independent local window.
     * Keeping windows per connection prevents distant failures from expanding
     * one union rectangle across the whole startup region.
     */
    public static CompletableFuture<Void> refineInitialRoutesAsync(
            ServerLevel level,
            List<StructureConnection> connections,
            int step,
            int paddingBlocks) {
        if (level == null || connections == null || connections.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        // Local repair is intentionally fixed to the user's 100-block
        // conflict radius. The caller's legacy padding argument must not
        // accidentally enlarge the building search or sampling window.
        int padding = ConfigService.get().planning().localConflictRadiusBlocks();
        List<CompletableFuture<Void>> refinements = new java.util.ArrayList<>();
        Map<StructureConnection, List<BlockPos>> originalPaths = new LinkedHashMap<>();
        Map<StructureConnection, List<LocalSamplingWindow>> windowsByConnection = new LinkedHashMap<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            ModConfig config = ConfigService.get();
            TerrainSamplingMode mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
            PathfindingCostConfig retryPathConfig = config.pathfindingCost().withAStarStep(step);
            List<BlockPos> originalPath = PlannedPathCache.find(
                    level, connection, mode, retryPathConfig).orElse(List.of());
            if (originalPath.size() < 3) {
                LOGGER.warn("RoadWeaver local route refinement skipped: missing persisted route path "
                                + "connection={}->{}", connection.from(), connection.to());
                continue;
            }
            List<LocalSamplingWindow> localWindows = conflictSamplingWindows(
                    level, connection, padding, retryPathConfig);
            if (localWindows.isEmpty()) {
                LOGGER.warn("RoadWeaver local route refinement skipped broad endpoint scan: "
                                + "no local conflict was identified connection={}->{}",
                        connection.from(), connection.to());
                // A route with no repair window has no safe local operation to
                // perform. Refuse to re-run it as a disguised full-route
                // generation; the bounded candidate phase handles it next.
                PlannedPathCache.discard(level, connection);
                continue;
            }
            originalPaths.put(connection, List.copyOf(originalPath));
            windowsByConnection.put(connection, localWindows);
        }

        // Discover every conflict window before any local planner starts. The
        // old route-by-route flow made each worker request its own circle and
        // repeatedly sampled overlapping columns. Build one de-duplicated
        // conflict-point set and let the accelerated sampler process it as a
        // single GPU-first batch. Route workers then perform independent A*
        // splices against the shared persisted samples.
        CompletableFuture<Void> sharedConflictPrefetch = prefetchConflictWindowsAsync(
                level, windowsByConnection, Math.max(1, step));
        LOGGER.info("RoadWeaver local conflict prefetch scheduled: routes={} windows={} step={} radius={} ",
                windowsByConnection.size(),
                windowsByConnection.values().stream().mapToInt(List::size).sum(),
                Math.max(1, step), padding);

        for (Map.Entry<StructureConnection, List<LocalSamplingWindow>> entry : windowsByConnection.entrySet()) {
            StructureConnection connection = entry.getKey();
            List<LocalSamplingWindow> localWindows = entry.getValue();
            java.util.concurrent.atomic.AtomicReference<List<BlockPos>> currentPath =
                    new java.util.concurrent.atomic.AtomicReference<>(originalPaths.get(connection));
            CompletableFuture<Void> routeRefinement = sharedConflictPrefetch;
            for (int windowIndex = 0; windowIndex < localWindows.size(); windowIndex++) {
                LocalSamplingWindow localWindow = localWindows.get(windowIndex);
                int[] localBounds = localWindow.bounds();
                final int currentIndex = windowIndex;
                LOGGER.info("RoadWeaver local route refinement window: step={} padding={} index={}/{} "
                                + "bounds=[{},{} -> {},{}] connection={}->{}",
                        step, padding, currentIndex + 1, localWindows.size(),
                        localBounds[0], localBounds[1], localBounds[2], localBounds[3],
                        connection.from(), connection.to());
                // Keep windows for one route ordered. This avoids concurrent
                // cache writes for the same connection while still allowing
                // different failed routes to refine in parallel.
                routeRefinement = routeRefinement.thenCompose(ignored ->
                        refineLocalRouteWindowAsync(level, connection, currentPath, localWindow, step));
            }
            // A failed local exact sample must stay route-local. Complete the
            // wave exceptionally only in the worker's result path so the
            // startup coordinator can reject this route and advance to its
            // bounded topology candidate instead of aborting every route.
            refinements.add(routeRefinement.exceptionally(failure -> {
                PlannedPathCache.discard(level, connection);
                LOGGER.warn("RoadWeaver local exact repair failed; refusing broad fallback "
                                + "and leaving route for candidate handling connection={}->{}",
                        connection.from(), connection.to(), failure);
                return null;
            }));
        }
        return CompletableFuture.allOf(refinements.toArray(CompletableFuture[]::new));
    }

    /**
     * Samples all local conflict circles once before route-specific A* work.
     * Windows are de-duplicated by X/Z, so overlapping buildings or multiple
     * conflicts on one route do not repeatedly submit the same GPU columns.
     * The sampler persists the result, allowing the independent route workers
     * below to read the same terrain snapshot without another worldgen call.
     */
    private static CompletableFuture<Void> prefetchConflictWindowsAsync(
            ServerLevel level,
            Map<StructureConnection, List<LocalSamplingWindow>> windowsByConnection,
            int step) {
        if (level == null || windowsByConnection == null || windowsByConnection.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        int localStep = Math.max(1, step);
        for (List<LocalSamplingWindow> windows : windowsByConnection.values()) {
            if (windows == null) continue;
            for (LocalSamplingWindow window : windows) {
                if (window == null || window.center() == null || window.radius() <= 0) continue;
                int radius = window.radius();
                long radiusSquared = (long) radius * radius;
                int minX = saturatingAdd(window.center().getX(), -radius);
                int maxX = saturatingAdd(window.center().getX(), radius);
                int minZ = saturatingAdd(window.center().getZ(), -radius);
                int maxZ = saturatingAdd(window.center().getZ(), radius);
                for (int z = minZ; z <= maxZ; z += localStep) {
                    for (int x = minX; x <= maxX; x += localStep) {
                        long dx = (long) x - window.center().getX();
                        long dz = (long) z - window.center().getZ();
                        if (dx * dx + dz * dz <= radiusSquared) {
                            unique.putIfAbsent(
                                    net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample.key(x, z),
                                    new BlockPos(x, 0, z));
                        }
                    }
                }
            }
        }
        if (unique.isEmpty()) return CompletableFuture.completedFuture(null);
        List<BlockPos> positions = List.copyOf(unique.values());
        long epoch = ThreadPoolManager.currentEpoch();
        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            if (!ThreadPoolManager.isEpoch(epoch)) return null;
            AccurateHeightSampler sampler = AccurateHeightSampler.create(level);
            long started = System.nanoTime();
            sampler.samplePositions(positions, batch -> LOGGER.debug(
                    "RoadWeaver unified local conflict sampling progress: completed={} total={} batch={} backend={} device={}",
                    batch.completedColumns(), batch.totalColumns(), batch.batchColumns(),
                    sampler.backendName(), sampler.deviceName()));
            LOGGER.info("RoadWeaver unified local conflict sampling complete: routes={} windows={} uniqueColumns={} step={} elapsedMs={} backend={} device={}",
                    windowsByConnection.size(),
                    windowsByConnection.values().stream().mapToInt(List::size).sum(),
                    positions.size(), localStep, (System.nanoTime() - started) / 1_000_000L,
                    sampler.backendName(), sampler.deviceName());
            return null;
        });
    }

    /**
     * Plans only the local slice around one conflict and splices that slice
     * back into the already-persisted route. The temporary connection keeps
     * the local A* endpoints inside the radius-100 circle; using the original
     * endpoints here would make a circular region incapable of finding a
     * complete route and would tempt a broad fallback scan.
     */
    private static CompletableFuture<Void> refineLocalRouteWindowAsync(
            ServerLevel level,
            StructureConnection connection,
            java.util.concurrent.atomic.AtomicReference<List<BlockPos>> currentPath,
            LocalSamplingWindow window,
            int step) {
        List<BlockPos> originalPath = currentPath.get();
        List<LocalRouteSlice> slices = localRouteSlices(originalPath, window.center(), window.radius());
        if (slices.isEmpty()) {
            LOGGER.warn("RoadWeaver local route splice skipped: conflict has no interior entry/exit "
                            + "within radius={} connection={}->{}",
                    window.radius(), connection.from(), connection.to());
            PlannedPathCache.discard(level, connection);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("no local interior entry/exit"));
        }
        int[] bounds = window.bounds();
        int localStep = Math.max(1, step);
        ModConfig config = ConfigService.get();
        PathfindingCostConfig pathfinding = config.pathfindingCost().withAStarStep(localStep);

        return ComputeService.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            RoadTerrainPlanningPort.Bounds localBounds =
                    new RoadTerrainPlanningPort.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]);
            int candidateIndex = 0;
            long lastPlanMillis = 0L;
            for (LocalRouteSlice slice : slices) {
                candidateIndex++;
                StructureConnection localConnection = new StructureConnection(
                        slice.start(), slice.end(), ConnectionStatus.PLANNED);
                RoadTerrainPlanningPort.Request request = new RoadTerrainPlanningPort.Request(
                        level, localBounds, List.of(localConnection), pathfinding,
                        window.center(), window.radius());
                LOGGER.info("RoadWeaver local splice attempt: candidate={}/{} step={} radius={} center={} "
                                + "slice={}..{} nodes={} connection={}->{}",
                        candidateIndex, slices.size(), localStep, window.radius(), window.center(),
                        slice.startIndex(), slice.endIndex(), slice.path().size(),
                        connection.from(), connection.to());
                RoadTerrainPlanningPort.Result result;
                long planStartedAt = System.nanoTime();
                try {
                    result = TERRAIN_PLANNING.plan(request);
                } finally {
                    lastPlanMillis = (System.nanoTime() - planStartedAt) / 1_000_000L;
                }
                List<BlockPos> localPath = result.paths().get(localConnection);
                if (localPath == null || localPath.size() < 2) {
                    LOGGER.info("RoadWeaver local splice rejected no_path candidate={}/{} step={} radius={} "
                                    + "bounds=[{},{} -> {},{}] slice={}..{} localEndpoints={}->{} planMs={} connection={}->{}",
                            candidateIndex, slices.size(), localStep, window.radius(),
                            bounds[0], bounds[1], bounds[2], bounds[3],
                            slice.startIndex(), slice.endIndex(), slice.start(), slice.end(),
                            lastPlanMillis, connection.from(), connection.to());
                    continue;
                }
                List<BlockPos> merged = spliceRouteSlice(originalPath, slice.startIndex(),
                        slice.endIndex(), localPath);
                // Local repair is read back by the route generator using the
                // world's effective sampling mode. Circular exact planning
                // may return FULL_REGION even when the session is configured
                // for COARSE_CORRIDOR, so store the merged path under the
                // effective mode to keep the retry fingerprint consistent.
                PlannedPathCache.register(level, resultMode(level), pathfinding,
                        Map.of(connection, merged));
                currentPath.set(merged);
                LOGGER.info("RoadWeaver local splice accepted: candidate={}/{} step={} radius={} "
                                + "localNodes={} routeNodes={} -> {} planMs={} connection={}->{}",
                        candidateIndex, slices.size(), localStep, window.radius(), localPath.size(),
                        originalPath.size(), merged.size(), lastPlanMillis,
                        connection.from(), connection.to());
                return null;
            }
            throw new IllegalStateException("local splice planner returned no usable candidate path"
                    + " center=" + window.center() + " radius=" + window.radius()
                    + " bounds=" + java.util.Arrays.toString(bounds)
                    + " connection=" + connection.from() + "->" + connection.to());
        }).thenCompose(ignored -> {
            List<BlockPos> merged = PlannedPathCache.find(
                    level, connection, resultMode(level), pathfinding).orElse(List.of());
            return merged.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : StructureAvoidanceService.preparePlanningSnapshotsForPathsAsync(
                            level, Map.of(connection, merged), ThreadPoolManager.TaskRole.INITIAL);
        });
    }

    private static TerrainSamplingMode resultMode(ServerLevel level) {
        return TerrainSamplingSessions.forLevel(level).effectiveMode();
    }

    private static LocalRouteSlice localRouteSlice(List<BlockPos> path, BlockPos center, int radius) {
        List<LocalRouteSlice> slices = localRouteSlices(path, center, radius);
        return slices.isEmpty() ? null : slices.getFirst();
    }

    /**
     * Builds several bounded entry/exit pairs for one conflict circle.  The
     * old implementation selected one nearest pivot and could reject a valid
     * detour when that pivot did not leave two usable anchors.  Candidates are
     * kept on the original polyline and never expand beyond the local circle.
     */
    private static List<LocalRouteSlice> localRouteSlices(List<BlockPos> path, BlockPos center, int radius) {
        if (path == null || path.size() < 3 || center == null) return List.of();
        int usableRadius = Math.max(8, radius - 8);
        long limit = (long) usableRadius * usableRadius;
        List<Integer> inside = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            BlockPos point = path.get(index);
            if (point != null && horizontalDistanceSquared(point, center) <= limit) inside.add(index);
        }
        if (inside.size() < 2) return List.of();
        List<int[]> runs = new ArrayList<>();
        int runStart = inside.getFirst();
        int previous = runStart;
        for (int index = 1; index < inside.size(); index++) {
            int current = inside.get(index);
            if (current != previous + 1) {
                runs.add(new int[]{runStart, previous});
                runStart = current;
            }
            previous = current;
        }
        runs.add(new int[]{runStart, previous});

        // Try boundary-near pairs first within each contiguous circle run.
        // A route that leaves and re-enters the circle must never produce one
        // slice spanning the outside section.
        List<LocalRouteSlice> result = new ArrayList<>();
        for (int[] run : runs) {
            int first = run[0];
            int last = run[1];
            if (first == last || (first == 0 && last == path.size() - 1)) continue;
            int spread = Math.min(8, Math.max(1, last - first));
            for (int left = first; left <= Math.min(last - 1, first + spread); left++) {
                for (int right = Math.max(left + 1, last - spread); right <= last; right++) {
                    if (left == 0 && right == path.size() - 1) continue;
                    result.add(new LocalRouteSlice(left, right, List.copyOf(path.subList(left, right + 1))));
                }
            }
        }
        return List.copyOf(result);
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        if (first == null || second == null) return Long.MAX_VALUE;
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static List<BlockPos> spliceRouteSlice(List<BlockPos> original, int start, int end,
                                                    List<BlockPos> replacement) {
        ArrayList<BlockPos> merged = new ArrayList<>(original.size() - (end - start + 1)
                + replacement.size());
        merged.addAll(original.subList(0, start));
        appendWithoutDuplicate(merged, replacement);
        for (BlockPos point : original.subList(end + 1, original.size())) {
            appendWithoutDuplicate(merged, List.of(point));
        }
        return List.copyOf(merged);
    }

    private static void appendWithoutDuplicate(List<BlockPos> target, List<BlockPos> points) {
        for (BlockPos point : points) {
            if (point == null) continue;
            if (target.isEmpty() || !target.get(target.size() - 1).equals(point)) target.add(point);
        }
    }

    private record LocalRouteSlice(int startIndex, int endIndex, List<BlockPos> path) {
        private BlockPos start() { return path.getFirst(); }
        private BlockPos end() { return path.getLast(); }
    }

    /**
     * Finds only local conflict corridors on the persisted coarse path.
     * Windows are built around contiguous structure-collision runs instead of
     * the complete endpoint-to-endpoint corridor. This keeps a long failed
     * route from turning a 50-block repair into a multi-million-cell scan.
     */
    private static List<LocalSamplingWindow> conflictSamplingWindows(ServerLevel level,
                                                                      StructureConnection connection,
                                                                      int padding,
                                                                      PathfindingCostConfig pathConfig) {
        if (level == null || connection == null) return List.of();
        List<LocalSamplingWindow> windows = new ArrayList<>();
        for (BlockPos hintedCenter : consumeProtectedGapHints(connection)) {
            windows.add(windowForConflictCenter(hintedCenter, padding));
        }
        if (!windows.isEmpty()) {
            LOGGER.info("RoadWeaver local refinement using persisted protected-gap hints "
                            + "windows={} radius={} connection={}->{}",
                    windows.size(), padding, connection.from(), connection.to());
        }

        ModConfig config = ConfigService.get();
        List<BlockPos> path = PlannedPathCache.find(
                level, connection,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                pathConfig == null ? config.pathfindingCost() : pathConfig).orElse(List.of());
        if (path.size() < 2) {
            return mergeNearbyWindows(windows, padding);
        }
        int runStart = -1;
        int runEnd = -1;
        // Do not pass the terrain radius as an additional building radius.
        // The snapshot already carries the physical road-width footprint.
        try (StructureAvoidanceService.PlanningScope ignored =
                     StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
            for (int i = 0; i < path.size(); i++) {
                BlockPos point = path.get(i);
                boolean structureConflict = point != null
                        && (StructureAvoidanceService.shouldAvoidPlanning(point)
                        || pathEdgeConflicts(path, i));
                boolean gradeConflict = false;
                if (i > 0 && point != null && path.get(i - 1) != null) {
                    BlockPos previous = path.get(i - 1);
                    double horizontal = Math.hypot(point.getX() - previous.getX(),
                            point.getZ() - previous.getZ());
                    int vertical = Math.abs(point.getY() - previous.getY());
                    // Coarse paths can legitimately climb a few blocks. A
                    // sharp jump is the useful signal for a local height
                    // conflict; it keeps ordinary slopes out of the repair.
                    gradeConflict = vertical >= 4 || (horizontal > 0.0D && vertical > horizontal * 1.5D);
                }
                boolean conflict = structureConflict || gradeConflict;
                if (conflict) {
                    if (runStart < 0) runStart = i;
                    runEnd = i;
                } else if (runStart >= 0) {
                    windows.add(windowForPathRange(path, runStart, runEnd, padding));
                    runStart = -1;
                    runEnd = -1;
                }
            }
        }
        if (runStart >= 0) windows.add(windowForPathRange(path, runStart, runEnd, padding));
        List<LocalSamplingWindow> merged = mergeNearbyWindows(windows, padding);
        if (merged.isEmpty()) {
            // A final accurate construction preflight can discover a deep
            // depression even when the coarse path has no sharp Y jump. The
            // road generator records that exact station; use only that
            // conflict-centered circle instead of silently skipping local
            // repair or rescanning the entire route.
            BlockPos hintedConflict = RoadPathCalculator.consumeConstructionConflictHint(connection);
            if (hintedConflict != null) {
                merged = List.of(windowForConflictCenter(hintedConflict, padding));
                LOGGER.info("RoadWeaver local refinement recovered final construction conflict hint: "
                                + "center={} radius={} connection={}->{}",
                        hintedConflict, padding, connection.from(), connection.to());
            }
        }
        if (merged.isEmpty()) {
            LOGGER.info("RoadWeaver local refinement found no conflict corridor: route={}->{} pathNodes={} "
                            + "action=skip_sampling",
                    connection.from(), connection.to(), path.size());
        }
        return merged;
    }

    private static LocalSamplingWindow windowForConflictCenter(BlockPos center, int padding) {
        int radius = Math.max(1, padding);
        return new LocalSamplingWindow(new int[]{
                saturatingAdd(center.getX(), -radius),
                saturatingAdd(center.getZ(), -radius),
                saturatingAdd(center.getX(), radius),
                saturatingAdd(center.getZ(), radius)}, center, radius);
    }

    /** Checks the swept line between two coarse path nodes for a structure hit. */
    private static boolean pathEdgeConflicts(List<BlockPos> path, int index) {
        if (path == null || index <= 0 || index >= path.size()) return false;
        BlockPos from = path.get(index - 1);
        BlockPos to = path.get(index);
        if (from == null || to == null) return false;
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(
                to.getX() - from.getX(), to.getZ() - from.getZ())));
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            BlockPos probe = new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
            if (StructureAvoidanceService.shouldAvoidPlanning(probe)) return true;
        }
        return false;
    }

    private static LocalSamplingWindow windowForPathRange(List<BlockPos> path, int start, int end, int padding) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = Math.max(0, start); i <= Math.min(end, path.size() - 1); i++) {
            BlockPos point = path.get(i);
            if (point == null) continue;
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }
        if (minX == Integer.MAX_VALUE) return new LocalSamplingWindow(new int[]{0, 0, 0, 0}, null, 0);
        BlockPos first = path.get(Math.max(0, start));
        BlockPos last = path.get(Math.min(end, path.size() - 1));
        BlockPos center = new BlockPos(
                Math.floorDiv(first.getX() + last.getX(), 2),
                Math.floorDiv(first.getY() + last.getY(), 2),
                Math.floorDiv(first.getZ() + last.getZ(), 2));
        return new LocalSamplingWindow(new int[]{
                saturatingAdd(minX, -padding),
                saturatingAdd(minZ, -padding),
                saturatingAdd(maxX, padding),
                saturatingAdd(maxZ, padding)}, center, padding);
    }

    private static List<LocalSamplingWindow> mergeNearbyWindows(List<LocalSamplingWindow> windows, int padding) {
        if (windows == null || windows.isEmpty()) return List.of();
        // Hints from one physical structure commonly contain its start, end,
        // midpoint and adjacent protected columns. Collapse very-near centers;
        // distinct buildings remain separate circles.
        //
        // 0.8.316：合并半径从 padding/4（=25）放大到 localRepairWindowClusterRadiusBlocks
        // （默认 50）。同一条路的相邻冲突中心常相距 30~60 格，圆几乎完全重叠，却各自
        // 跑一遍完整的局部重规划（step=2 圆内 A* + 粗地形预检 + 隧道重路由 + 走廊快照）。
        // 圆内 A* 的节点数由半径决定、与圆里有几个冲突无关，所以合并成更少的圆是
        // "单次成本不变、次数减少"。设 0/负数恢复旧阈值。
        int configuredClusterRadius = 0;
        try {
            configuredClusterRadius = ConfigService.get().planning().localRepairWindowClusterRadiusBlocks();
        } catch (Throwable ignored) {
        }
        int clusterRadius = configuredClusterRadius > 0
                ? configuredClusterRadius
                : Math.max(8, Math.min(32, padding / 4));
        List<LocalSamplingWindow> result = new ArrayList<>();
        for (LocalSamplingWindow current : windows) {
            if (current == null || current.bounds().length < 4) continue;
            boolean merged = false;
            for (int index = 0; index < result.size(); index++) {
                LocalSamplingWindow existing = result.get(index);
                if (existing.center() == null || current.center() == null) continue;
                long dx = (long) existing.center().getX() - current.center().getX();
                long dz = (long) existing.center().getZ() - current.center().getZ();
                if (dx * dx + dz * dz <= (long) clusterRadius * clusterRadius) {
                    BlockPos a = existing.center();
                    BlockPos b = current.center();
                    BlockPos midpoint = new BlockPos(
                            Math.floorDiv(a.getX() + b.getX(), 2),
                            Math.floorDiv(a.getY() + b.getY(), 2),
                            Math.floorDiv(a.getZ() + b.getZ(), 2));
                    result.set(index, windowForConflictCenter(midpoint, padding));
                    merged = true;
                    break;
                }
            }
            if (!merged) result.add(current);
        }
        return List.copyOf(result);
    }

    private record LocalSamplingWindow(int[] bounds, BlockPos center, int radius) {
    }

    /** Returns the startup-wide sampling rectangle, including all endpoint closures. */
    public static int[] initialSamplingBounds(
            ServerLevel level, List<StructureConnection> connections) {
        if (level == null) return new int[]{0, 0, 0, 0};
        ModConfig config = ConfigService.get();
        int radiusChunks = Math.max(1, config.planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = (cx - radiusChunks) * 16;
        int maxX = (cx + radiusChunks) * 16;
        int minZ = (cz - radiusChunks) * 16;
        int maxZ = (cz + radiusChunks) * 16;
        return boundsIncludingConnections(minX, minZ, maxX, maxZ, connections);
    }

    /** Returns a bounded union rectangle for candidate corridors and detours. */
    public static int[] samplingBoundsForConnections(
            List<StructureConnection> connections, int paddingBlocks) {
        int[] bounds = boundsIncludingConnections(0, 0, 0, 0, connections);
        int padding = Math.max(0, paddingBlocks);
        return new int[]{
                saturatingAdd(bounds[0], -padding),
                saturatingAdd(bounds[1], -padding),
                saturatingAdd(bounds[2], padding),
                saturatingAdd(bounds[3], padding)};
    }

    /** Returns only the endpoint corridor window for one failed route. */
    public static int[] samplingBoundsForConnection(
            StructureConnection connection, int paddingBlocks) {
        if (connection == null) return new int[]{0, 0, 0, 0};
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        if (from != null) {
            minX = Math.min(minX, from.getX());
            minZ = Math.min(minZ, from.getZ());
            maxX = Math.max(maxX, from.getX());
            maxZ = Math.max(maxZ, from.getZ());
        }
        if (to != null) {
            minX = Math.min(minX, to.getX());
            minZ = Math.min(minZ, to.getZ());
            maxX = Math.max(maxX, to.getX());
            maxZ = Math.max(maxZ, to.getZ());
        }
        if (minX == Integer.MAX_VALUE) return new int[]{0, 0, 0, 0};
        int padding = Math.max(0, paddingBlocks);
        return new int[]{
                saturatingAdd(minX, -padding),
                saturatingAdd(minZ, -padding),
                saturatingAdd(maxX, padding),
                saturatingAdd(maxZ, padding)};
    }

    private record PlannedRegionResult(List<StructureConnection> incoming) {}

    private record TerrainPlannedRegionResult(PlannedRegionResult region,
                                              RoadTerrainPlanningPort.Result terrain) {}

    private static final class AsyncPlanningTiming {
        private final long startedAt = System.nanoTime();
        private volatile int structureCount;
        private volatile int connectionCount;
        private volatile long topologyNanos;
        private volatile long snapshotNanos;
        private volatile long terrainNanos;
        /**
         * 0.9.038：把 {@code terrainPlanningMs}（实测占 async planning 的 71~99.9%）拆成三段。
         *
         * <p>它们与 {@code terrainNanos} 的关系是互斥的：
         * {@code terrainNanos ≈ terrainQueueNanos + terrainCoreNanos + terrainTailNanos}。</p>
         *
         * <ul>
         *   <li>{@code terrainQueueNanos} —— 提交到任务真正开跑之间的等待（被别的波次/别的任务占着算力）。</li>
         *   <li>{@code terrainCoreNanos} —— {@code TERRAIN_PLANNING.plan(request)} 本体（真活儿）。</li>
         *   <li>{@code terrainTailNanos} —— 过滤被抑制的连接 + 共享端点尾巴复用 + 计划缓存登记。</li>
         * </ul>
         */
        private volatile long terrainQueueNanos;
        /**
         * 0.9.044（T-7）：结构快照准备（{@code preparePlanningSnapshotsAsync}，会 server.execute 回主线程）
         * 的耗时。这一段以前被算进了 {@code terrainQueueNanos} —— 于是「主线程卡」会被误判成
         * 「线程池排队」。拆出来之后四段互斥：queue + snapshotPrep + core + tail ≈ terrainPlanningMs。
         */
        private volatile long terrainSnapshotPrepNanos;
        private volatile long terrainCoreNanos;
        private volatile long terrainTailNanos;
        private volatile long publishNanos;
    }

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
    private static CompletableFuture<Void> planRectAsync(
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
            LOGGER.warn("{} ({})", guard.describe(), guard.coreAloneExceedsCap()
                    ? "core 自身面积已超 cap，几何上无法压进；余量落到 MARGIN_FLOOR，core 仍完整枚举（红线）"
                    : "余量已收窄到能落进 cap；core 仍完整枚举（红线）");
        }
        final AdaptivePlanState state =
                new AdaptivePlanState(configuredPlanMaxEdgeLenBlocks(), guard.finalMargin());
        final StructureAvoidanceService.PrepareTiming prepTiming =
                new StructureAvoidanceService.PrepareTiming();
        return planRectRound(level, epoch, coreBounds, taskRole, requireCompletePaths,
                        existingSnapshot, timing, startupProgress, state, prepTiming)
                .thenCompose(round -> planRectRounds(level, epoch, coreBounds, taskRole,
                        requireCompletePaths, existingSnapshot, timing, startupProgress, state,
                        prepTiming, round))
                .whenComplete((ignored, failure) ->
                        logAsyncPlanning(level, coreBounds, state, timing, prepTiming, failure));
    }

    /** 0.9.059（②）：一轮「给定候选范围」的完整规划（拓扑 → 快照 → 地形 → 走廊快照），不发布。 */
    private static CompletableFuture<AdaptiveRound> planRectRound(
            ServerLevel level,
            long epoch,
            int[] coreBounds,
            ThreadPoolManager.TaskRole taskRole,
            boolean requireCompletePaths,
            List<StructureConnection> existingSnapshot,
            AsyncPlanningTiming timing,
            boolean startupProgress,
            AdaptivePlanState state,
            StructureAvoidanceService.PrepareTiming prepTiming) {
        final int[] candidateBounds = AdaptiveCandidateBounds
                .boundsWithMargin(coreBounds, state.margin()).toArray();
        if (startupProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "discovering_structures");
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
                    InitialGenerationProgressTracker.setPlanningStartPlan(
                            Math.max(1, points.size()), "planning_structure_connections");
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

                List<StructureConnection> primaryEdges = planPrimaryEdges(points, cfg0);
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
                List<StructureConnection> bridges = KNNPlanner.connectComponents(
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
                    return StructureAvoidanceService.preparePlanningSnapshotsAsync(
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
                                    result.incoming(), epoch, terrainParts);
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
                    return StructureAvoidanceService.preparePlanningSnapshotsForPathsAsync(
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
    private static CompletableFuture<Void> planRectRounds(
            ServerLevel level,
            long epoch,
            int[] coreBounds,
            ThreadPoolManager.TaskRole taskRole,
            boolean requireCompletePaths,
            List<StructureConnection> existingSnapshot,
            AsyncPlanningTiming timing,
            boolean startupProgress,
            AdaptivePlanState state,
            StructureAvoidanceService.PrepareTiming prepTiming,
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
        LOGGER.info("[PLAN-ADAPTIVE] expand round={} margin={} fromMargin={} toMargin={} roadsOutside={} "
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
    private static CompletableFuture<Void> finishAdaptivePlan(
            ServerLevel level, long epoch, AsyncPlanningTiming timing, AdaptivePlanState state) {
        List<StructureConnection> incoming = state.plannedConnections();
        timing.connectionCount = incoming.size();
        if (incoming.isEmpty()) return CompletableFuture.completedFuture(null);
        long phaseStartedAt = System.nanoTime();
        return publishIncomingConnectionsAsync(level, epoch, incoming)
                .whenComplete((ignored, failure) ->
                        timing.publishNanos = System.nanoTime() - phaseStartedAt);
    }

    /** 0.9.059：{@code async planning timing} + {@code [PLAN-SNAPSHOT-BREAKDOWN]} + {@code [PLAN-ADAPTIVE]}。 */
    private static void logAsyncPlanning(
            ServerLevel level,
            int[] coreBounds,
            AdaptivePlanState state,
            AsyncPlanningTiming timing,
            StructureAvoidanceService.PrepareTiming prepTiming,
            Throwable failure) {
        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(coreBounds);
        AdaptiveCandidateBounds.Rect expanded =
                AdaptiveCandidateBounds.boundsWithMargin(coreBounds, state.margin());
        LOGGER.info(
                "RoadWeaver async planning timing: dimension={} coreBounds=[{},{} -> {},{}] candidateBounds=[{},{} -> {},{}] structures={} connections={} topologyMs={} structureSnapshotsMs={} terrainPlanningMs={} terrainQueueMs={} terrainSnapshotPrepMs={} terrainCoreMs={} terrainTailMs={} publishMs={} totalMs={} success={}",
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
            LOGGER.info("[PLAN-SNAPSHOT-BREAKDOWN] {}", prepTiming.describe());
        }
        // 0.9.060（②/④）：coreDiag= 核心区对角线、marginRatio= 相对余量比例、
        // margin= 未守卫的请求余量、guardTripped= 面积守卫是否触发、finalMargin= 守卫后余量；
        // appliedMargin= 多轮扩张后真正生效的余量（旧 margin= 字段的口径，改名以免和上面撞名）。
        LOGGER.info("[PLAN-ADAPTIVE] initialBounds=[{},{} -> {},{}] expanded=[{},{} -> {},{}] "
                        + "roadsExpanded={} expandedRounds={} structures={} connections={} "
                        + "{} appliedMargin={} capMargin={}",
                initial.minX(), initial.minZ(), initial.maxX(), initial.maxZ(),
                expanded.minX(), expanded.minZ(), expanded.maxX(), expanded.maxZ(),
                state.roadsExpanded(), state.expandedRounds(), state.structures(),
                state.plannedConnections().size(),
                AdaptiveCandidateBounds.guard(coreBounds).diagnostics(),
                state.margin(), state.capMargin());
    }

    /**
     * 0.9.059（②）：一轮候选范围下的规划产物。{@code pathBounds} 是"这条路实际走线的 XZ 包围盒"，
     * 用来回答"它有没有越出本轮的候选余量"（纯几何，{@link AdaptiveCandidateBounds} 里判）。
     */
    private record AdaptiveRound(int margin,
                                 List<StructureConnection> incoming,
                                 Set<PlannedPathKey> pathKeys,
                                 Map<PlannedPathKey, AdaptiveCandidateBounds.PathBounds> pathBounds,
                                 boolean complete) {
        static AdaptiveRound empty(int margin) {
            return new AdaptiveRound(margin, List.of(), Set.of(), Map.of(), true);
        }

        static AdaptiveRound of(List<StructureConnection> incoming,
                                Map<StructureConnection, List<BlockPos>> paths,
                                int margin) {
            List<StructureConnection> connectionList = incoming == null ? List.of() : List.copyOf(incoming);
            if (paths == null || paths.isEmpty() || connectionList.isEmpty()) {
                return new AdaptiveRound(margin, connectionList, Set.of(), Map.of(), connectionList.isEmpty());
            }
            Set<PlannedPathKey> keys = new HashSet<>();
            Map<PlannedPathKey, AdaptiveCandidateBounds.PathBounds> bounds = new LinkedHashMap<>();
            for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
                StructureConnection connection = entry.getKey();
                List<BlockPos> path = entry.getValue();
                if (connection == null || path == null || path.isEmpty()) continue;
                PlannedPathKey key = PlannedPathKey.of(connection);
                keys.add(key);
                int[] xs = new int[path.size()];
                int[] zs = new int[path.size()];
                for (int i = 0; i < path.size(); i++) {
                    BlockPos pos = path.get(i);
                    xs[i] = pos.getX();
                    zs[i] = pos.getZ();
                }
                AdaptiveCandidateBounds.PathBounds pathBounds =
                        AdaptiveCandidateBounds.PathBounds.of(xs, zs);
                if (pathBounds != null) bounds.put(key, pathBounds);
            }
            boolean complete = true;
            for (StructureConnection connection : connectionList) {
                if (connection == null || !keys.contains(PlannedPathKey.of(connection))) {
                    complete = false;
                    break;
                }
            }
            return new AdaptiveRound(margin, connectionList, keys, bounds, complete);
        }
    }

    /** 0.9.059（②）：一次 {@code planRectAsync} 的自适应账（只用于判定与诊断行）。 */
    private static final class AdaptivePlanState {
        private final int capMargin;
        private final Map<PlannedPathKey, StructureConnection> planned = new LinkedHashMap<>();
        private final Set<PlannedPathKey> unrouted = new LinkedHashSet<>();
        private int margin;
        private int rounds = 1;
        private int expandedRounds;
        private int roadsExpanded;
        private int structures;

        /**
         * @param configuredCapMargin 用户配置的单边外扩上限（{@code planMaxEdgeLenBlocks}）
         * @param initialMargin       0.9.060：经 {@link AdaptiveCandidateBounds#guard} 收窄后的相对余量
         */
        AdaptivePlanState(int configuredCapMargin, int initialMargin) {
            this.capMargin = Math.max(AdaptiveCandidateBounds.MARGIN_FLOOR, configuredCapMargin);
            // 初始余量本身也不越过 cap（否则 canExpand 永远为假、诊断里 margin > capMargin 自相矛盾）。
            this.margin = Math.max(AdaptiveCandidateBounds.MARGIN_FLOOR,
                    Math.min(initialMargin, capMargin));
        }

        int margin() {
            return margin;
        }

        int capMargin() {
            return capMargin;
        }

        int rounds() {
            return rounds;
        }

        int expandedRounds() {
            return expandedRounds;
        }

        int roadsExpanded() {
            return roadsExpanded;
        }

        int structures() {
            return structures;
        }

        Set<PlannedPathKey> plannedKeys() {
            return planned.keySet();
        }

        List<StructureConnection> plannedConnections() {
            return List.copyOf(planned.values());
        }

        boolean canExpand() {
            return margin < capMargin;
        }

        void expand() {
            margin = AdaptiveCandidateBounds.nextMargin(margin, capMargin);
            expandedRounds++;
            rounds++;
        }

        void observeStructures(int count) {
            structures = Math.max(structures, count);
        }

        void absorb(AdaptiveRound round) {
            if (round == null) return;
            for (StructureConnection connection : round.incoming()) {
                PlannedPathKey key = PlannedPathKey.of(connection);
                planned.putIfAbsent(key, connection);
                if (round.pathKeys().contains(key)) unrouted.remove(key);
                else unrouted.add(key);
            }
        }

        void rewind(PlannedPathKey key) {
            if (key == null) return;
            planned.remove(key);
            unrouted.remove(key);
        }

        boolean complete() {
            return unrouted.isEmpty();
        }
    }

    private static boolean containsAllPaths(
            List<StructureConnection> connections,
            Map<StructureConnection, List<BlockPos>> paths) {
        if (connections == null || connections.isEmpty()) return true;
        if (paths == null || paths.isEmpty()) return false;
        Set<PlannedPathKey> available = new HashSet<>();
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                available.add(PlannedPathKey.of(entry.getKey()));
            }
        }
        for (StructureConnection connection : connections) {
            if (connection != null && !available.contains(PlannedPathKey.of(connection))) return false;
        }
        return true;
    }

    static int[] expandedRuntimeCandidateBounds(
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        int[] normalized = normalizedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        int padding = configuredPlanMaxEdgeLenBlocks();
        return new int[]{
                saturatingAdd(normalized[0], -padding),
                saturatingAdd(normalized[1], -padding),
                saturatingAdd(normalized[2], padding),
                saturatingAdd(normalized[3], padding)
        };
    }

    /**
     * 0.8.421：候选矩形外扩量跟随「主连边长度上限」。读不到配置（离线/测试环境）时
     * 回落到出厂常量，保证任何调用时机都不会因配置缺失而抛异常。
     */
    private static int configuredPlanMaxEdgeLenBlocks() {
        try {
            return ConfigService.get().planning().planMaxEdgeLenBlocks();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;
        }
    }

    static boolean edgeTouchesRect(
            StructureConnection connection,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        return connection != null && (inRect2d(
                connection.from(), minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                || inRect2d(connection.to(), minBlockX, minBlockZ, maxBlockX, maxBlockZ));
    }

    private static int[] boundsIncludingConnections(
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ,
            List<StructureConnection> connections) {
        int[] bounds = normalizedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        if (connections == null) return bounds;
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            BlockPos from = connection.from();
            BlockPos to = connection.to();
            if (from != null) {
                bounds[0] = Math.min(bounds[0], from.getX());
                bounds[1] = Math.min(bounds[1], from.getZ());
                bounds[2] = Math.max(bounds[2], from.getX());
                bounds[3] = Math.max(bounds[3], from.getZ());
            }
            if (to != null) {
                bounds[0] = Math.min(bounds[0], to.getX());
                bounds[1] = Math.min(bounds[1], to.getZ());
                bounds[2] = Math.max(bounds[2], to.getX());
                bounds[3] = Math.max(bounds[3], to.getZ());
            }
        }
        return bounds;
    }

    private static int[] normalizedBounds(int minX, int minZ, int maxX, int maxZ) {
        return new int[]{Math.min(minX, maxX), Math.min(minZ, maxZ),
                Math.max(minX, maxX), Math.max(minZ, maxZ)};
    }

    private static int saturatingAdd(int value, int delta) {
        long result = (long) value + delta;
        return result < Integer.MIN_VALUE ? Integer.MIN_VALUE
                : result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : (int) result;
    }

    private static CompletableFuture<Void> publishIncomingConnectionsAsync(
            ServerLevel level, long epoch, List<StructureConnection> incoming) {
        if (level == null || incoming == null || incoming.isEmpty() || !ThreadPoolManager.isEpoch(epoch)) {
            return CompletableFuture.completedFuture(null);
        }
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        final List<StructureConnection> snapshot = List.copyOf(
                StructureFileStorage.filterSuppressedConnections(level, incoming));
        if (snapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> published = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                if (ThreadPoolManager.isEpoch(epoch)) {
                    WorldDataProvider provider = WorldDataProvider.getInstance();
                    List<StructureConnection> existing = provider.getStructureConnections(level);
                    ConnectionTopologyReconciler.Result reconciled =
                            ConnectionTopologyReconciler.reconcile(existing, snapshot);
                    if (reconciled.changed()) {
                        provider.setStructureConnections(level, reconciled.connections());
                        publishTopologyChanges(level, reconciled);
                    }
                }
                published.complete(null);
            } catch (Throwable failure) {
                published.completeExceptionally(failure);
            }
        };
        if (server.isSameThread()) task.run();
        else server.execute(task);
        return published;
    }

    private static void publishTopologyChanges(ServerLevel level,
                                               ConnectionTopologyReconciler.Result result) {
        if (level == null || result == null) return;
        for (StructureConnection removed : result.removed()) {
            MapPatchService.publishConnectionRemoval(level, removed);
            LOGGER.info("RoadWeaver pruned redundant pending topology edge: {} -> {} status={}",
                    removed.from(), removed.to(), removed.status());
        }
        for (StructureConnection added : result.added()) {
            MapPatchService.publishConnection(level, added);
        }
    }

    private static void planRoadTerrain(ServerLevel level,
                                        int minBlockX,
                                        int minBlockZ,
                                        int maxBlockX,
                                        int maxBlockZ,
                                        List<StructureConnection> connections) {
        if (level == null || connections == null || connections.isEmpty()) return;
        ModConfig config = ConfigService.get();
        TerrainSamplingMode mode = config.planning().terrainSamplingMode();
        if (mode == null || mode == TerrainSamplingMode.LEGACY_DIRECT) {
            return;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        ComputeService.supplyAsync(() -> planRoadTerrainWithPreparedSnapshots(
                        level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, connections, epoch))
                .thenCompose(result -> result == null || result.paths().isEmpty()
                                || !ThreadPoolManager.isEpoch(epoch)
                        ? CompletableFuture.completedFuture(null)
                        : StructureAvoidanceService.preparePlanningSnapshotsForPathsAsync(
                                level, result.paths()))
                .exceptionally(failure -> {
                    if (ThreadPoolManager.isEpoch(epoch)) {
                        LOGGER.error("RoadWeaver terrain or path-corridor structure preparation failed", failure);
                    }
                    return null;
                });
    }

    private static RoadTerrainPlanningPort.Result planRoadTerrainWithPreparedSnapshots(
                                                              ServerLevel level,
                                                              int minBlockX,
                                                              int minBlockZ,
                                                              int maxBlockX,
                                                              int maxBlockZ,
                                                              List<StructureConnection> connections,
                                                              long epoch) {
        return planRoadTerrainWithPreparedSnapshots(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ,
                connections, epoch, null);
    }

    /**
     * @param partsOut 可选，长度 ≥2 的 long[]：{@code [0]=coreNanos}（{@code TERRAIN_PLANNING.plan} 本体）、
     *                 {@code [1]=tailNanos}（过滤 + 共享端点复用 + 缓存登记）。0.9.038 只读观测。
     */
    private static RoadTerrainPlanningPort.Result planRoadTerrainWithPreparedSnapshots(
                                                              ServerLevel level,
                                                              int minBlockX,
                                                              int minBlockZ,
                                                              int maxBlockX,
                                                              int maxBlockZ,
                                                              List<StructureConnection> connections,
                                                              long epoch,
                                                              long[] partsOut) {
        if (level == null || connections == null || connections.isEmpty()
                || !ThreadPoolManager.isEpoch(epoch)) return null;
        ModConfig config = ConfigService.get();
        TerrainSamplingMode mode = config.planning().terrainSamplingMode();
        if (mode == null || mode == TerrainSamplingMode.LEGACY_DIRECT) return null;
        RoadTerrainPlanningPort.Request request = new RoadTerrainPlanningPort.Request(
                level,
                new RoadTerrainPlanningPort.Bounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ),
                connections,
                config.pathfindingCost());
        long phaseStartedAt = System.nanoTime();
        RoadTerrainPlanningPort.Result result = TERRAIN_PLANNING.plan(request);
        if (partsOut != null && partsOut.length > 0) {
            partsOut[0] = System.nanoTime() - phaseStartedAt;
        }
        if (!ThreadPoolManager.isEpoch(epoch)) return null;
        long tailStartedAt = System.nanoTime();
        result = filterSuppressedPaths(level, result);
        if (result != null && !result.paths().isEmpty()) {
            result = SharedEndpointPathReuse.reuseSharedEndpointTails(
                    level, result, config.pathfindingCost().effectiveAStarStep());
            PlannedPathCache.register(level, result.mode(), config.pathfindingCost(), result.paths());
        }
        if (partsOut != null && partsOut.length > 1) {
            partsOut[1] = System.nanoTime() - tailStartedAt;
        }
        return result;
    }

    private static RoadTerrainPlanningPort.Result filterSuppressedPaths(
            ServerLevel level, RoadTerrainPlanningPort.Result result) {
        if (result == null || result.paths() == null || result.paths().isEmpty()) return result;
        List<StructureConnection> allowedConnections = StructureFileStorage.filterSuppressedConnections(
                level, new ArrayList<>(result.paths().keySet()));
        Set<PlannedPathKey> allowedKeys = new HashSet<>();
        for (StructureConnection connection : allowedConnections) {
            allowedKeys.add(PlannedPathKey.of(connection));
        }
        Map<StructureConnection, List<BlockPos>> allowed = new LinkedHashMap<>();
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : result.paths().entrySet()) {
            StructureConnection connection = entry.getKey();
            if (connection != null && allowedKeys.contains(PlannedPathKey.of(connection))) {
                allowed.put(connection, entry.getValue());
            }
        }
        return new RoadTerrainPlanningPort.Result(result.mode(), allowed);
    }

    private static ArrayList<BlockPos> collectComponentPoints(List<BlockPos> seed,
                                                              List<StructureConnection> existingInRect,
                                                              List<StructureConnection> incomingEdges) {
        ArrayList<BlockPos> out = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        if (seed != null) {
            for (BlockPos p : seed) addUnique2d(out, seen, p);
        }
        if (existingInRect != null) {
            for (StructureConnection c : existingInRect) {
                if (c == null) continue;
                addUnique2d(out, seen, c.from());
                addUnique2d(out, seen, c.to());
            }
        }
        if (incomingEdges != null) {
            for (StructureConnection c : incomingEdges) {
                if (c == null) continue;
                addUnique2d(out, seen, c.from());
                addUnique2d(out, seen, c.to());
            }
        }
        return out;
    }

    private static void addUnique2d(List<BlockPos> out, Set<Long> seen, BlockPos p) {
        if (p == null) return;
        BlockPos q = new BlockPos(p.getX(), 0, p.getZ());
        long k = PlanningUtils.pos2dKey(q);
        if (seen.add(k)) out.add(q);
    }

    private static boolean inRect2d(BlockPos p, int minX, int minZ, int maxX, int maxZ) {
        if (p == null) return false;
        int x = p.getX();
        int z = p.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    public static Set<Long> getPlannedTiles(ServerLevel level) {
        Set<Long> s = WorldDataProvider.getInstance().getPlannedTileKeys(level);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    public static Map<Long, Long> getPlannedTileCenters(ServerLevel level) {
        Map<Long, Long> m = WorldDataProvider.getInstance().getPlannedTileCenters(level);
        if (m == null || m.isEmpty()) return Map.of();
        return Map.copyOf(m);
    }

    public static int getStrideTileSizeChunks() {
        ModConfig cfg = ConfigService.get();
        int stride = Math.max(1, cfg.planning().dynamicPlanStrideChunks());
        return Math.max(RoadConstants.PLAN_TILE_MIN, Math.min(RoadConstants.PLAN_TILE_MAX, stride));
    }

    public static int getDynamicPlanRadiusChunks() {
        ModConfig cfg = ConfigService.get();
        return Math.max(1, cfg.planning().dynamicPlanRadiusChunks());
    }

    public static void resetAll() {
        ROAD_BACKFILL_PLAN_TILES.clear();
        PLANNING_TILES.clear();
        PROTECTED_GAP_HINTS.clear();
        AutomaticPlanningSamplingActivities.clearAll();
    }
}
