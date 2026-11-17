/* 文件职责：地图增量补丁的生成与应用。 */
package net.shiroha233.roadweaver.map;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotPatch;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.network.ServerMapPatchBridge;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlanCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图局部更新发布服务。
 */
public final class MapPatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private MapPatchService() {}

    public static void publishConnection(ServerLevel level, StructureConnection connection) {
        if (!isOverworld(level) || connection == null) return;
        List<StructureInfo> endpointInfos = collectEndpointInfos(level, connection);
        MapSnapshotPatch patch = new MapSnapshotPatch(
                endpointInfos,
                collectSources(level, endpointInfos),
                List.of(normalize(connection)),
                List.of(),
                List.of());
        broadcast(level, patch);
    }

    public static void publishStructures(ServerLevel level, List<StructureInfo> structures) {
        if (level == null || structures == null || structures.isEmpty()) return;
        ArrayList<StructureInfo> normalized = new ArrayList<>(structures.size());
        for (StructureInfo info : structures) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            normalized.add(new StructureInfo(normalize(info.pos()), info.structureId()));
        }
        if (normalized.isEmpty()) return;
        broadcast(level, new MapSnapshotPatch(normalized, collectSources(level, normalized), List.of(), List.of(), List.of()));
    }

    public static void publishConnectionStatus(ServerLevel level, StructureConnection connection, ConnectionStatus status) {
        if (connection == null || status == null) return;
        publishConnection(level, new StructureConnection(connection.from(), connection.to(), status));
    }

    public static void publishConnectionReplacement(ServerLevel level,
                                                    StructureConnection removed,
                                                    StructureConnection replacement) {
        if (!isOverworld(level) || removed == null || replacement == null) return;
        List<StructureInfo> endpointInfos = collectEndpointInfos(level, replacement);
        broadcast(level, new MapSnapshotPatch(
                endpointInfos,
                collectSources(level, endpointInfos),
                List.of(normalize(replacement)),
                List.of(),
                List.of(),
                List.of(normalize(removed))));
    }

    public static void publishConnectionRemoval(ServerLevel level, StructureConnection removed) {
        if (!isOverworld(level) || removed == null) return;
        broadcast(level, new MapSnapshotPatch(
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(normalize(removed))));
    }

    public static void publishRoadForConnectionAsync(ServerLevel level, StructureConnection connection) {
        if (!isOverworld(level) || connection == null || RoadLifecycle.isStopping()) return;
        long epoch = ThreadPoolManager.currentEpoch();
        ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP_READ, () -> buildRoadPatch(level, connection))
                .thenAccept(patch -> {
                    if (ThreadPoolManager.isEpoch(epoch)) broadcast(level, patch);
                })
                .exceptionally(failure -> {
                    if (ThreadPoolManager.isEpoch(epoch) && !RoadLifecycle.isStopping()) {
                        LOGGER.warn("【异步地图补丁发布失败】 " + "RoadWeaver could not publish an asynchronous road map patch", failure);
                    }
                    return null;
                });
    }

    public static void publishRoads(ServerLevel level, List<RoadData> roads) {
        if (!isOverworld(level) || roads == null || roads.isEmpty() || RoadLifecycle.isStopping()) return;
        ArrayList<MapSnapshotPatch.RoadPolylinePatch> roadPatches = new ArrayList<>();
        ArrayList<Long> removedKeys = new ArrayList<>();
        List<RoadData> normalized = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(roads);
        for (RoadData road : normalized) {
            if (road == null || !road.hasOwnerPair()) continue;
            long edgeKey = PlanningUtils.edgeKey(road.ownerA2dKey(), road.ownerB2dKey());
            removedKeys.add(edgeKey);
            for (int stalePart = 1; stalePart <= 16; stalePart++) removedKeys.add(mixKey(edgeKey, stalePart));
            int part = 0;
            for (List<BlockPos> polyline : RoadMapGeometry.visiblePolylines(road)) {
                if (polyline.size() < 2) continue;
                roadPatches.add(new MapSnapshotPatch.RoadPolylinePatch(
                        part == 0 ? edgeKey : mixKey(edgeKey, part), polyline));
                part++;
            }
        }
        if (!roadPatches.isEmpty() || !removedKeys.isEmpty()) {
            broadcast(level, new MapSnapshotPatch(List.of(), Map.of(), List.of(), roadPatches,
                    List.of(), List.of(), removedKeys));
        }
    }

    private static void broadcast(ServerLevel level, MapSnapshotPatch patch) {
        if (level == null || patch == null || patch.isEmpty() || RoadLifecycle.isStopping()) return;
        long epoch = ThreadPoolManager.currentEpoch();
        var server = level.getServer();
        if (server != null) {
            server.execute(() -> {
                if (ThreadPoolManager.isEpoch(epoch) && !RoadLifecycle.isStopping()) {
                    ServerMapPatchBridge.broadcast(level, level.dimension().location(), patch);
                }
            });
        } else {
            if (ThreadPoolManager.isEpoch(epoch)) {
                ServerMapPatchBridge.broadcast(level, level.dimension().location(), patch);
            }
        }
    }

    private static MapSnapshotPatch buildRoadPatch(ServerLevel level, StructureConnection connection) {
        int minX = Math.min(connection.from().getX(), connection.to().getX());
        int maxX = Math.max(connection.from().getX(), connection.to().getX());
        int minZ = Math.min(connection.from().getZ(), connection.to().getZ());
        int maxZ = Math.max(connection.from().getZ(), connection.to().getZ());
        int pad = 256;
        List<RoadData> roads = RoadShardStorage.queryRect(level, minX - pad, minZ - pad, maxX + pad, maxZ + pad);
        long edgeKey = PlanningUtils.edgeKey(connection.from(), connection.to());
        ArrayList<MapSnapshotPatch.RoadPolylinePatch> roadPatches = new ArrayList<>();
        ArrayList<Long> removedKeys = staleRoadPartKeys(edgeKey);
        for (RoadData road : RoadChunkPlanCompiler.normalizeForPhysicalOwnership(roads)) {
            if (road == null || !road.hasOwnerPair()) continue;
            long roadEdgeKey = PlanningUtils.edgeKey(road.ownerA2dKey(), road.ownerB2dKey());
            if (roadEdgeKey != edgeKey) continue;
            int part = 0;
            for (List<BlockPos> polyline : RoadMapGeometry.visiblePolylines(road)) {
                if (polyline.size() >= 2) {
                    roadPatches.add(new MapSnapshotPatch.RoadPolylinePatch(
                            part == 0 ? edgeKey : mixKey(edgeKey, part), polyline));
                    part++;
                }
            }
        }
        // A route can become entirely reused. Still send the removals so the
        // client does not retain its earlier, pre-merge polyline.
        return new MapSnapshotPatch(List.of(), Map.of(), List.of(), roadPatches,
                List.of(), List.of(), removedKeys);
    }

    private static ArrayList<Long> staleRoadPartKeys(long edgeKey) {
        ArrayList<Long> keys = new ArrayList<>(17);
        keys.add(edgeKey);
        for (int stalePart = 1; stalePart <= 16; stalePart++) keys.add(mixKey(edgeKey, stalePart));
        return keys;
    }

    private static long mixKey(long key, int part) {
        long mixed = key ^ (0x9E3779B97F4A7C15L * (part + 1L));
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        return mixed ^ (mixed >>> 31);
    }

    private static List<StructureInfo> collectEndpointInfos(ServerLevel level, StructureConnection connection) {
        ArrayList<StructureInfo> out = new ArrayList<>(2);
        addEndpointInfo(level, connection.from(), out);
        if (!samePos(connection.from(), connection.to())) addEndpointInfo(level, connection.to(), out);
        return out;
    }

    private static void addEndpointInfo(ServerLevel level, BlockPos endpoint, List<StructureInfo> out) {
        if (endpoint == null) return;
        int x = endpoint.getX();
        int z = endpoint.getZ();
        for (StructureInfo info : StructureFileStorage.queryRect(level, x, z, x, z)) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            out.add(new StructureInfo(new BlockPos(x, 0, z), info.structureId()));
            return;
        }
    }

    private static boolean samePos(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX()
                && first.getZ() == second.getZ();
    }

    private static StructureConnection normalize(StructureConnection connection) {
        return new StructureConnection(
                normalize(connection.from()),
                normalize(connection.to()),
                connection.status());
    }

    private static BlockPos normalize(BlockPos pos) {
        return new BlockPos(pos.getX(), 0, pos.getZ());
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

    private static Map<BlockPos, Integer> collectSources(ServerLevel level, List<StructureInfo> structures) {
        if (level == null || structures == null || structures.isEmpty()) return Map.of();
        StructureFileStorage.StructureSnapshot snapshot = StructureFileStorage.getStructureSnapshot(level);
        LinkedHashMap<BlockPos, Integer> sources = new LinkedHashMap<>();
        for (StructureInfo info : structures) {
            if (info == null || info.pos() == null) continue;
            BlockPos pos = normalize(info.pos());
            sources.put(pos, snapshot.sourceAt(pos));
        }
        return sources;
    }
}
