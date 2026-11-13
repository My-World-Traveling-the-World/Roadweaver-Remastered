/* 文件职责：规划范围计算 —— 候选边界扩展、边长上限、矩形相交判定。 */
package net.shiroha233.roadweaver.planning;

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
import static net.shiroha233.roadweaver.planning.AsyncPlanningOps.*;
import static net.shiroha233.roadweaver.planning.ConflictWindowOps.*;

/**
 * 规划范围计算。
 *
 * <h2>为什么范围要「扩展」而不是直接用连接矩形</h2>
 * <p>路在两点之间往往要绕行（地形、避让、既有路网），所以规划范围必须比
 * 两点的包围盒<b>大一圈</b>（{@code expandedRuntimeCandidateBounds}）。
 * 扩得太小 ⇒ A* 在边界上被迫回头，走出 U 形；扩得太大 ⇒ 白算。
 * {@code configuredPlanMaxEdgeLenBlocks} 是那条上限。</p>
 *
 * <h2>★ 相交判定要按「边」而不是按「包含」</h2>
 * <p>{@code edgeTouchesRect} 处理的是「路径擦过矩形边界」的情形。
 * 只判点是否在矩形内，会漏掉正好压在边界上的路径。</p>
 *
 * <p>本类是从 2204 行的 {@code RoadPlanningService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class PlanningBoundsOps {
    public static boolean containsAllPaths(
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

    public static int[] expandedRuntimeCandidateBounds(
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
    public static int configuredPlanMaxEdgeLenBlocks() {
        try {
            return ConfigService.get().planning().planMaxEdgeLenBlocks();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;
        }
    }

    public static boolean edgeTouchesRect(
            StructureConnection connection,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        return connection != null && (inRect2d(
                connection.from(), minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                || inRect2d(connection.to(), minBlockX, minBlockZ, maxBlockX, maxBlockZ));
    }

    public static int[] boundsIncludingConnections(
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

    public static int[] normalizedBounds(int minX, int minZ, int maxX, int maxZ) {
        return new int[]{Math.min(minX, maxX), Math.min(minZ, maxZ),
                Math.max(minX, maxX), Math.max(minZ, maxZ)};
    }

    public static int saturatingAdd(int value, int delta) {
        long result = (long) value + delta;
        return result < Integer.MIN_VALUE ? Integer.MIN_VALUE
                : result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : (int) result;
    }

    public static CompletableFuture<Void> publishIncomingConnectionsAsync(
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

    public static void publishTopologyChanges(ServerLevel level,
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

    public static void planRoadTerrain(ServerLevel level,
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
                        : preparePlanningSnapshotsForPathsAsync(
                                level, result.paths()))
                .exceptionally(failure -> {
                    if (ThreadPoolManager.isEpoch(epoch)) {
                        LOGGER.error("RoadWeaver terrain or path-corridor structure preparation failed", failure);
                    }
                    return null;
                });
    }

    public static RoadTerrainPlanningPort.Result planRoadTerrainWithPreparedSnapshots(
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
    public static RoadTerrainPlanningPort.Result planRoadTerrainWithPreparedSnapshots(
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

    public static RoadTerrainPlanningPort.Result filterSuppressedPaths(
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

    public static ArrayList<BlockPos> collectComponentPoints(List<BlockPos> seed,
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

    public static void addUnique2d(List<BlockPos> out, Set<Long> seen, BlockPos p) {
        if (p == null) return;
        BlockPos q = new BlockPos(p.getX(), 0, p.getZ());
        long k = PlanningUtils.pos2dKey(q);
        if (seen.add(k)) out.add(q);
    }

    public static boolean inRect2d(BlockPos p, int minX, int minZ, int maxX, int maxZ) {
        if (p == null) return false;
        int x = p.getX();
        int z = p.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public static int floorDiv(int a, int b) {
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
