/* 文件职责：协调结构发现、连接拓扑规划与道路地形规划用例。 */
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
import static net.shiroha233.roadweaver.planning.AsyncPlanningOps.*;
import static net.shiroha233.roadweaver.planning.ConflictWindowOps.*;
import static net.shiroha233.roadweaver.planning.PlanningBoundsOps.*;
import static net.shiroha233.roadweaver.planning.RouteRefinementOps.*;

/**
 * 道路规划服务
 */
public final class RoadPlanningService {
    private RoadPlanningService() {}

    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** 共端点共享接近剖面只覆盖附近有限站数，避免改动全路。 */
    static final int APPROACH_WINDOW_STATIONS = 16;
    static final Set<Long> ROAD_BACKFILL_PLAN_TILES = ConcurrentHashMap.newKeySet();
    static final Set<Long> PLANNING_TILES = ConcurrentHashMap.newKeySet();
    /** 把施工期精确保护冲突点移交局部重试，避免下一波仅看粗路径漏掉真实侧列冲突。
 *
     * Carries construction-time protected-gap locations into the local retry
     * phase. The retry receives only a connection, so without this handoff it
     * has to rediscover a conflict from the coarse path and can miss it.
     */
    static final Map<PlannedPathKey, List<BlockPos>> PROTECTED_GAP_HINTS = new ConcurrentHashMap<>();

    /** 生成前汇总未知节点全部来路的接近高度并定共同入口；出生点与村庄继续使用原锁高。
 *
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
            if (RoadSpanProfileOps.isUnknownStructureEndpoint(level, conn, true)) {
                long key = PlanningUtils.pos2dKey(conn.from());
                byEndpoint.computeIfAbsent(key, k -> new ArrayList<>()).add(conn);
                endpointPos.putIfAbsent(key, conn.from());
            }
            if (RoadSpanProfileOps.isUnknownStructureEndpoint(level, conn, false)) {
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
                LOGGER.info("【未知端点预规划共享高度已确定】 " + "RoadWeaver unknown endpoint preplanned agreed height={} endpoint=({},{}) "
                                + "incoming={} class={} windowLowest={} sharedStations={}",
                        agreed, ep.getX(), ep.getZ(), entry.getValue().size(),
                        classification, windowLowest, shared.length);
            }
        }
    }

    /** 精确地表查询对临时失败保留既定处理，不能把缺样当作已验证地面。 */
    static int sampleSurface(AccurateHeightSampler sampler, BlockPos point) {
        if (point == null) return 0;
        try {
            return sampler.surfaceHeight(point.getX(), point.getZ());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** 诊断从结构快照读取入口身份，不以位置近似替代持久身份。 */
    static String classifyUnknownEndpoint(ServerLevel level, BlockPos endpoint) {
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
            LOGGER.info("【保护缺口局部提示已持久化】 " + "RoadWeaver persisted protected-gap local hints centers={} radius={} connection={}->{}",
                    unique.size(), ConfigService.get().planning().localConflictRadiusBlocks(),
                    connection.from(), connection.to());
        }
    }

    static List<BlockPos> consumeProtectedGapHints(StructureConnection connection) {
        if (connection == null) return List.of();
        List<BlockPos> hints = PROTECTED_GAP_HINTS.remove(PlannedPathKey.of(connection));
        return hints == null ? List.of() : hints;
    }

    /** 保护交叉查询须使用注册或重绑路径时相同精度键，避免查错粗走廊。
 *
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

    /** 重试先将已有走廊重绑到目标精度，避免缓存键失配触发全端点区域重搜。
 *
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
        LOGGER.info("【已将生成路线重新绑定供局部重试】 " + "RoadWeaver rebound generated route for local retry: step={} nodes={} connection={}->{}",
                retryConfig.effectiveAStarStep(), centers.size(), connection.from(), connection.to());
    }
    static final RoadTerrainPlanningPipeline TERRAIN_PLANNING = new RoadTerrainPlanningPipeline();

    static void prunePlannedIfTooLarge(Level level) {
        // Planned tile markers are persistent progress, not a bounded cache.
        // Never drop arbitrary entries after a large-radius scan: doing so
        // schedules already planned regions again and can create duplicate work.
        if (level != null) {
            LOGGER.debug("【保留该维度全部持久规划标记】 " + "RoadWeaver retained all persistent planning markers dimension={}",
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

    static void planAroundPlayerLegacy(ServerPlayer player) {
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

    static boolean markRoadBackfillPlanTile(ServerLevel level, long tileKey) {
        return ROAD_BACKFILL_PLAN_TILES.add(tileKey);
    }

    static void unmarkRoadBackfillPlanTile(ServerLevel level, long tileKey) {
        ROAD_BACKFILL_PLAN_TILES.remove(tileKey);
    }

    static boolean markPlanningTile(ServerLevel level, long tileKey) {
        return PLANNING_TILES.add(tileKey);
    }

    static void unmarkPlanningTile(ServerLevel level, long tileKey) {
        PLANNING_TILES.remove(tileKey);
    }

    static AutomaticPlanningSamplingActivities.Activity beginDynamicPlanningSampling(
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

    static void finishDynamicPlanningSampling(ServerLevel level,
                                                       AutomaticPlanningSamplingActivities.Activity activity) {
        if (activity == null) {
            return;
        }
        activity.close();
        publishDynamicPlanningSampling(level);
    }

    static void publishDynamicPlanningSampling(ServerLevel level) {
        if (level == null) {
            return;
        }
        try {
            ServerMapPlanningActivityBridge.broadcast(level);
        } catch (RuntimeException failure) {
            LOGGER.debug("自动规划采样范围同步失败 dimension={}", level.dimension().location(), failure);
        }
    }

    static void markPlannedTile(ServerLevel level, long tileKey, int centerChunkX, int centerChunkZ) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        Set<Long> set = new HashSet<>(provider.getPlannedTileKeys(level));
        set.add(tileKey);
        Map<Long, Long> centers = new HashMap<>(provider.getPlannedTileCenters(level));
        centers.putIfAbsent(tileKey, (((long) centerChunkX) << 32) ^ (centerChunkZ & 0xffffffffL));
        provider.setPlannedTileKeys(level, set);
        provider.setPlannedTileCenters(level, centers);
    }

    static void planRect(ServerLevel level,
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
            LOGGER.info("【结构索引阶段耗时，本次没有地形规划】 " + "RoadWeaver planning timing: dimension={} structures={} structureIndexMs={} terrainPlanningMs=0 totalMs={}",
                    level.dimension().location(), points.size(), structureIndexMillis, elapsedMillis(startedAt));
            return;
        }

        ModConfig cfg0 = ConfigService.get();
        List<StructureConnection> primaryEdges = planPrimaryEdges(level, points, cfg0);
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

        List<StructureConnection> bridges = ComponentConnector.connectComponents(
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
            LOGGER.info("【道路重叠裁切已提交】 " + "RoadWeaver overlap truncation committed: replaced={} topologySize={}",
                    truncatedEdges.size(), rebuilt.size());
        }
        LOGGER.info("【道路规划各阶段耗时】 " + "RoadWeaver planning timing: dimension={} structures={} connections={} structureIndexMs={} terrainPlanningMs={} totalMs={}",
                level.dimension().location(), points.size(), incoming.size(), structureIndexMillis,
                elapsedMillis(terrainStartedAt), elapsedMillis(startedAt));
    }

    static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 无世界入口供离线调用；工厂统一使用新版PIPELINE，旧算法键仅用于读取旧配置。
     * 长度上限仍取配置，但测地距离需要世界，生产应使用下面带世界的重载。
     */
    static List<StructureConnection> planPrimaryEdges(List<BlockPos> points, ModConfig cfg0) {
        return planPrimaryEdges(null, points, cfg0);
    }

    /**
     * 带世界的版本 —— {@code planningAlgorithm = PIPELINE} 需要它。
     *
     * <p>2026-11-13 加：测地距离规划器必须先采地形，而地形来自世界。
     * 真实规划缺少世界时明确失败；取消/采样失败也不转换为空集合或直线MST。</p>
     */
    static List<StructureConnection> planPrimaryEdges(ServerLevel level, List<BlockPos> points,
                                                      ModConfig cfg0) {
        NetworkPlanner planner = NetworkPlannerFactory.create(
                cfg0.planning().planningAlgorithm(), level);
        int maxEdge = cfg0.planning().planMaxEdgeLenBlocks();
        // 2026-11-13：旧 KNNPlanner 已删除（用户拍板「全用最新」）。
        // 工厂只产出 PipelinePlanner，这里不再需要 instanceof 分支。
        return planner.plan(points, maxEdge);
    }

    static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    static void collectStructurePointsInto(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, List<BlockPos> out, Set<Long> seenPos) {
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

    static boolean isPlanningEndpointAllowed(ServerLevel level, StructureInfo info) {
        return info != null && (StructureInfo.isVillageId(info.structureId())
                || isPlanningEndpointAllowed(level, info.pos()));
    }

    static boolean isPlanningEndpointAllowed(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        return spawn != null && spawn.getX() == pos.getX() && spawn.getZ() == pos.getZ();
    }

    /** 真实收集的未知结构可作节点；从未收集的纯拓扑占位点仍排除。
 *
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

    static Set<Long> collectedEndpointKeys(ServerLevel level) {
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

    static Set<Long> selectedEndpointKeys(ServerLevel level) {
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

    /** 只重规划未解决连接并共用一批地形，成功路线保持原路径。
 *
     * Samples one startup-wide terrain batch while planning only the supplied
     * unresolved connections. Successful routes therefore keep their original
     * path, while all failed routes share the same precision-specific tiles.
     */
    static CompletableFuture<Void> prepareLocalSnapshotsAndPaths(
            ServerLevel level,
            RoadTerrainPlanningPort.Result result,
            BlockPos samplingCenter,
            int samplingRadius) {
        CompletableFuture<Void> circle = CompletableFuture.completedFuture(null);
        if (samplingCenter != null && samplingRadius > 0 && result.paths().size() == 1) {
            StructureConnection connection = result.paths().keySet().iterator().next();
            circle = ensurePlanningSnapshotForCircleAsync(
                    level, connection, samplingCenter, samplingRadius);
        }
        return circle.thenCompose(ignored ->
                preparePlanningSnapshotsForPathsAsync(
                        level, result.paths(), ThreadPoolManager.TaskRole.INITIAL));
    }



    /** 只替换冲突附近片段并拼回原走廊，临时端点须在局部圆窗内。
 *
     * Plans only the local slice around one conflict and splices that slice
     * back into the already-persisted route. The temporary connection keeps
     * the local A* endpoints inside the radius-100 circle; using the original
     * endpoints here would make a circular region incapable of finding a
     * complete route and would tempt a broad fallback scan.
     */
    /** 与当前世界采样会话使用同一模式，避免缓存与后续读取的指纹不一致。 */
    static TerrainSamplingMode resultMode(ServerLevel level) {
        return TerrainSamplingSessions.forLevel(level).effectiveMode();
    }

    static LocalRouteSlice localRouteSlice(List<BlockPos> path, BlockPos center, int radius) {
        List<LocalRouteSlice> slices = localRouteSlices(path, center, radius);
        return slices.isEmpty() ? null : slices.getFirst();
    }

    /** 局部圆窗生成有限组入出锚点，防止单个最近枢轴失效就否定整片可行绕路。
 *
     * Builds several bounded entry/exit pairs for one conflict circle.  The
     * old implementation selected one nearest pivot and could reject a valid
     * detour when that pivot did not leave two usable anchors.  Candidates are
     * kept on the original polyline and never expand beyond the local circle.
     */
    static List<LocalRouteSlice> localRouteSlices(List<BlockPos> path, BlockPos center, int radius) {
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

    static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        if (first == null || second == null) return Long.MAX_VALUE;
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    static List<BlockPos> spliceRouteSlice(List<BlockPos> original, int start, int end,
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

    static void appendWithoutDuplicate(List<BlockPos> target, List<BlockPos> points) {
        for (BlockPos point : points) {
            if (point == null) continue;
            if (target.isEmpty() || !target.get(target.size() - 1).equals(point)) target.add(point);
        }
    }

    record LocalRouteSlice(int startIndex, int endIndex, List<BlockPos> path) {
        BlockPos start() { return path.getFirst(); }
        BlockPos end() { return path.getLast(); }
    }

    /** 按连续结构碰撞段提取窗口，避免长失败路把小修复变成百万格扫描。
 *
     * Finds only local conflict corridors on the persisted coarse path.
     * Windows are built around contiguous structure-collision runs instead of
     * the complete endpoint-to-endpoint corridor. This keeps a long failed
     * route from turning a 50-block repair into a multi-million-cell scan.
     */
    record LocalSamplingWindow(int[] bounds, BlockPos center, int radius) {
    }

    /** 开局采样矩形包括各端点闭合范围，避免边界站缺样。 */
    record PlannedRegionResult(List<StructureConnection> incoming) {}

    record TerrainPlannedRegionResult(PlannedRegionResult region,
                                              RoadTerrainPlanningPort.Result terrain) {}

    static final class AsyncPlanningTiming {
        final long startedAt = System.nanoTime();
        volatile int structureCount;
        volatile int connectionCount;
        volatile long topologyNanos;
        volatile long snapshotNanos;
        volatile long terrainNanos;
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
        volatile long terrainQueueNanos;
        /**
         * 0.9.044（T-7）：结构快照准备（{@code preparePlanningSnapshotsAsync}，会 server.execute 回主线程）
         * 的耗时。这一段以前被算进了 {@code terrainQueueNanos} —— 于是「主线程卡」会被误判成
         * 「线程池排队」。拆出来之后四段互斥：queue + snapshotPrep + core + tail ≈ terrainPlanningMs。
         */
        volatile long terrainSnapshotPrepNanos;
        volatile long terrainCoreNanos;
        volatile long terrainTailNanos;
        volatile long publishNanos;
    }

    /**
     * 0.9.059（②）：一轮候选范围下的规划产物。{@code pathBounds} 是"这条路实际走线的 XZ 包围盒"，
     * 用来回答"它有没有越出本轮的候选余量"（纯几何，{@link AdaptiveCandidateBounds} 里判）。
     */
    record AdaptiveRound(int margin,
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
    static final class AdaptivePlanState {
        final int capMargin;
        final Map<PlannedPathKey, StructureConnection> planned = new LinkedHashMap<>();
        final Set<PlannedPathKey> unrouted = new LinkedHashSet<>();
        int margin;
        int rounds = 1;
        int expandedRounds;
        int roadsExpanded;
        int structures;

        /** 自适应余量受配置及面积守卫共同约束，不改变用户要求的核心规划范围。
 *
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

}
