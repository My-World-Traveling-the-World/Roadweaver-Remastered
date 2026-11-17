/* 文件职责：实现 Fabric 平台道路地图的请求、快照与增量状态网络通信。 */
package net.shiroha233.roadweaver.network.fabric;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.shiroha233.roadweaver.api.RoadNetworkApi;
import net.shiroha233.roadweaver.generation.ConnectionDeletionService;
import net.shiroha233.roadweaver.client.map.ClientMapAccessGuard;
import net.shiroha233.roadweaver.client.map.MapLoadPhase;
import net.shiroha233.roadweaver.client.map.RoadMapScreen;
import net.shiroha233.roadweaver.client.map.data.MapDataCollector;
import net.shiroha233.roadweaver.client.map.data.MapSnapshot;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotPatch;
import net.shiroha233.roadweaver.client.map.data.MapAutomaticPlanningSamplingCache;
import net.shiroha233.roadweaver.map.permission.MapAccessService;
import net.shiroha233.roadweaver.network.MapNetworkPayloads;
import net.shiroha233.roadweaver.map.search.MapStructureSearchService;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fabric 平台网络通信实现。 */
public final class MapNetworkFabric {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final long MAX_MAP_REQUEST_SPAN_BLOCKS = 524_288L;
    private static final long MAX_MAP_REQUEST_AREA_BLOCKS = 4_294_967_296L;
    private static final int MAX_MAP_RESPONSE_INDEX = 4_095;
    private static final int MAX_IN_FLIGHT_MAP_REQUESTS_PER_PLAYER = 24;
    private static final ConcurrentHashMap<UUID, AtomicInteger> IN_FLIGHT_MAP_REQUESTS =
            new ConcurrentHashMap<>();
    private static boolean payloadTypesRegistered;

    private MapNetworkFabric() {}

    public static void clearRuntime() {
        IN_FLIGHT_MAP_REQUESTS.clear();
    }

    private static synchronized void registerPayloadTypes() {
        if (payloadTypesRegistered) return;

        PayloadTypeRegistry.playC2S().register(MapNetworkPayloads.REQ_RECT, MapNetworkPayloads.MapRequestRectPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapNetworkPayloads.TP_REQ, MapNetworkPayloads.MapTeleportPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapNetworkPayloads.MAN_REQ, MapNetworkPayloads.MapManualConnectPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapNetworkPayloads.DELETE_REQ, MapNetworkPayloads.MapDeleteConnectionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapNetworkPayloads.SEARCH_REQ, MapNetworkPayloads.MapSearchRequestPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.SNAP, MapNetworkPayloads.MapSnapshotPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.PATCH, MapNetworkPayloads.MapPatchPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.AUTO_PLANNING_SAMPLING, MapNetworkPayloads.MapAutomaticPlanningSamplingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.TP_ACK, MapNetworkPayloads.MapTeleportAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.ACCESS_SYNC, MapNetworkPayloads.MapAccessSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapNetworkPayloads.SEARCH_RESP, MapNetworkPayloads.MapSearchResponsePayload.CODEC);

        payloadTypesRegistered = true;
    }

    public static void registerServerReceivers() {
        registerPayloadTypes();

        ServerPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.REQ_RECT, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!MapAccessService.canOpenMap(player)) {
                context.server().execute(() -> syncMapAccess(player));
                return;
            }
            var level = player.serverLevel();
            if (!level.dimension().location().equals(payload.dimension())
                    || !validMapRequest(payload)) {
                sendEmptySnapshotResponse(context.server(), player, payload);
                return;
            }
            if (!tryBeginMapRequest(player.getUUID())) {
                sendEmptySnapshotResponse(context.server(), player, payload);
                return;
            }
            long epoch = ThreadPoolManager.currentEpoch();
            try {
                // 结果与角色任务同生命周期，退出世界时也会释放请求占位。
                ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP_READ, () -> {
                            if (!ThreadPoolManager.isEpoch(epoch)) return null;
                            MapSnapshot snapshot = buildSnapshot(level, payload.phase(), payload.minX(), payload.minZ(), payload.maxX(), payload.maxZ());
                            return new MapNetworkPayloads.MapSnapshotPayload(
                                    payload.requestSeq(),
                                    level.dimension().location(),
                                    payload.phase(),
                                    payload.responseIndex(),
                                    snapshot,
                                    AutomaticPlanningSamplingActivities.snapshot(level)
                            );
                        })
                        .whenComplete((reply, failure) -> {
                            finishMapRequest(player.getUUID());
                            if (failure != null) {
                                if (ThreadPoolManager.isEpoch(epoch)) {
                                    LOGGER.warn("【地图道路快照请求失败】 " + "RoadWeaver map snapshot request failed", failure);
                                }
                                return;
                            }
                            if (reply == null || !ThreadPoolManager.isEpoch(epoch)) return;
                            context.server().execute(() -> {
                                if (ThreadPoolManager.isEpoch(epoch)
                                        && !player.isRemoved()
                                        && player.serverLevel() == level) {
                                    ServerPlayNetworking.send(player, reply);
                                }
                            });
                        });
            } catch (RuntimeException submissionFailure) {
                finishMapRequest(player.getUUID());
                if (ThreadPoolManager.isEpoch(epoch)) {
                    LOGGER.warn("【地图道路快照请求提交失败】 " + "RoadWeaver could not submit a map snapshot request", submissionFailure);
                }
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.TP_REQ, (payload, context) ->
                context.server().execute(() -> handleTeleport(payload, context.player(), context.server()))
        );

        ServerPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.MAN_REQ, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (!player.hasPermissions(2)) {
                        player.displayClientMessage(Component.translatable("gui.roadweaver.map.manual_connect.denied"), true);
                        return;
                    }
                    if (!Level.OVERWORLD.equals(player.serverLevel().dimension())) return;
                    RoadNetworkApi.ensureConnection(player.serverLevel(), payload.from(), payload.to());
                })
        );

        ServerPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.DELETE_REQ, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (!player.hasPermissions(2)) {
                        player.displayClientMessage(Component.translatable("gui.roadweaver.map.delete_connection.denied"), true);
                        return;
                    }
                    if (!Level.OVERWORLD.equals(player.serverLevel().dimension())) return;
                    ConnectionDeletionService.Result result = ConnectionDeletionService.delete(
                            player.serverLevel(), payload.from(), payload.to());
                    String key = switch (result) {
                        case DELETED -> "gui.roadweaver.map.delete_connection.deleted";
                        case COMPLETED_PROTECTED -> "gui.roadweaver.map.delete_connection.completed_protected";
                        case NOT_FOUND -> "gui.roadweaver.map.delete_connection.not_found";
                        case INVALID -> "gui.roadweaver.map.delete_connection.invalid";
                    };
                    player.displayClientMessage(Component.translatable(key), true);
                })
        );

        ServerPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.SEARCH_REQ, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!MapAccessService.canOpenMap(player)) {
                context.server().execute(() -> ServerPlayNetworking.send(player,
                        new MapNetworkPayloads.MapSearchResponsePayload(payload.requestSeq(), payload.dimension(), false, java.util.List.of())));
                return;
            }
            var level = player.serverLevel();
            if (!level.dimension().location().equals(payload.dimension())
                    || !MapStructureSearchService.tryBeginRequest(player.getUUID())) {
                context.server().execute(() -> ServerPlayNetworking.send(player,
                        new MapNetworkPayloads.MapSearchResponsePayload(payload.requestSeq(), payload.dimension(), false, java.util.List.of())));
                return;
            }
            long epoch = ThreadPoolManager.currentEpoch();
            try {
                // 搜索沿用读取角色；取消必须驱动 handle 的请求清理。
                ThreadPoolManager.<java.util.List<net.shiroha233.roadweaver.map.search.MapSearchResult>>supplyAsync(
                                ThreadPoolManager.TaskRole.MAP_READ, () -> ThreadPoolManager.isEpoch(epoch)
                                        ? MapStructureSearchService.search(level, payload.query())
                                        : java.util.List.of())
                        .handle((results, failure) -> {
                            MapStructureSearchService.finishRequest(player.getUUID());
                            return new MapNetworkPayloads.MapSearchResponsePayload(
                                    payload.requestSeq(), payload.dimension(), failure == null,
                                    failure == null ? results : java.util.List.of());
                        })
                        .thenAccept(reply -> context.server().execute(() -> {
                            if (ThreadPoolManager.isEpoch(epoch)
                                    && !player.isRemoved()
                                    && player.serverLevel() == level) {
                                ServerPlayNetworking.send(player, reply);
                            }
                        }));
            } catch (RuntimeException submissionFailure) {
                MapStructureSearchService.finishRequest(player.getUUID());
                context.server().execute(() -> ServerPlayNetworking.send(player,
                        new MapNetworkPayloads.MapSearchResponsePayload(payload.requestSeq(), payload.dimension(), false, java.util.List.of())));
            }
        });
    }

    public static void registerClientReceivers() {
        registerPayloadTypes();

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.SNAP, (payload, context) ->
                context.client().execute(() -> {
                    MapAutomaticPlanningSamplingCache.replace(
                            payload.dimension(),
                            payload.automaticPlanningSamplingBounds());
                    if (context.client().screen instanceof RoadMapScreen screen) {
                        screen.acceptSnapshotPart(payload.requestSeq(), payload.dimension(), payload.phase(), payload.responseIndex(), payload.snapshot());
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.PATCH, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof RoadMapScreen screen) {
                        screen.acceptPatch(payload.dimension(), payload.patch());
                    } else {
                        MapSnapshotCache.applyPatch(payload.dimension(), payload.patch());
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.TP_ACK, (payload, context) ->
                context.client().execute(() -> {
                    if (context.player() == null) return;
                    if (payload.success()) {
                        context.player().displayClientMessage(
                                Component.translatable("gui.roadweaver.map.teleport.success_pos", payload.x(), payload.y(), payload.z()), true);
                    } else {
                        context.player().displayClientMessage(Component.translatable("gui.roadweaver.map.teleport.denied"), true);
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.ACCESS_SYNC, (payload, context) ->
                context.client().execute(() -> ClientMapAccessGuard.applyServerState(context.client(), payload.allowed()))
        );

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.AUTO_PLANNING_SAMPLING, (payload, context) ->
                context.client().execute(() -> MapAutomaticPlanningSamplingCache.replace(
                        payload.dimension(),
                        payload.bounds()))
        );

        ClientPlayNetworking.registerGlobalReceiver(MapNetworkPayloads.SEARCH_RESP, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof RoadMapScreen screen) {
                        screen.acceptSearchResults(payload.requestSeq(), payload.dimension(), payload.success(), payload.results());
                    }
                })
        );
    }

    private static MapSnapshot buildSnapshot(net.minecraft.server.level.ServerLevel level,
                                             MapLoadPhase phase,
                                             int minX,
                                             int minZ,
                                             int maxX,
                                             int maxZ) {
        return switch (phase) {
            case STRUCTURES -> MapDataCollector.buildStructuresSnapshot(level, minX, minZ, maxX, maxZ);
            case ROADS -> MapDataCollector.buildRoadsSnapshot(level, minX, minZ, maxX, maxZ);
            case CONNECTIONS -> MapDataCollector.buildConnectionsSnapshot(level, minX, minZ, maxX, maxZ);
        };
    }

    private static void handleTeleport(MapNetworkPayloads.MapTeleportPayload payload,
                                       ServerPlayer player,
                                       net.minecraft.server.MinecraftServer server) {
        if (!player.isCreative() && !player.hasPermissions(2)) {
            ServerPlayNetworking.send(player, new MapNetworkPayloads.MapTeleportAckPayload(false, 0, 0, 0));
            return;
        }

        var level = player.serverLevel();
        long epoch = ThreadPoolManager.currentEpoch();
        int x = payload.x();
        int z = payload.z();
        level.getChunkSource().getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true)
                .whenComplete((ignored, failure) -> server.execute(() -> {
                    if (!ThreadPoolManager.isEpoch(epoch)
                            || player.isRemoved()
                            || player.hasDisconnected()
                            || player.serverLevel() != level) return;
                    if (failure != null) {
                        ServerPlayNetworking.send(player, new MapNetworkPayloads.MapTeleportAckPayload(false, 0, 0, 0));
                        return;
                    }
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    y = y <= level.getMinBuildHeight() ? level.getSeaLevel() + 1 : y + 1;
                    player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
                    ServerPlayNetworking.send(player, new MapNetworkPayloads.MapTeleportAckPayload(true, x, y, z));
                }));
    }

    private static boolean validMapRequest(MapNetworkPayloads.MapRequestRectPayload payload) {
        if (payload == null || payload.phase() == null || payload.dimension() == null
                || payload.responseIndex() < 0
                || payload.responseIndex() > MAX_MAP_RESPONSE_INDEX
                || payload.maxX() < payload.minX()
                || payload.maxZ() < payload.minZ()) return false;
        long spanX = (long) payload.maxX() - payload.minX() + 1L;
        long spanZ = (long) payload.maxZ() - payload.minZ() + 1L;
        return spanX > 0L && spanZ > 0L
                && spanX <= MAX_MAP_REQUEST_SPAN_BLOCKS
                && spanZ <= MAX_MAP_REQUEST_SPAN_BLOCKS
                && spanX <= MAX_MAP_REQUEST_AREA_BLOCKS / spanZ;
    }

    private static void sendEmptySnapshotResponse(
            net.minecraft.server.MinecraftServer server,
            ServerPlayer player,
            MapNetworkPayloads.MapRequestRectPayload payload) {
        if (server == null || player == null || payload == null) return;
        server.execute(() -> {
            if (!player.isRemoved() && !player.hasDisconnected()) {
                ServerPlayNetworking.send(player, new MapNetworkPayloads.MapSnapshotPayload(
                        payload.requestSeq(), payload.dimension(), payload.phase(), payload.responseIndex(),
                        MapSnapshot.empty(), List.of()));
            }
        });
    }

    private static boolean tryBeginMapRequest(UUID playerId) {
        if (playerId == null) return false;
        AtomicInteger counter = IN_FLIGHT_MAP_REQUESTS.computeIfAbsent(playerId, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= MAX_IN_FLIGHT_MAP_REQUESTS_PER_PLAYER) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private static void finishMapRequest(UUID playerId) {
        if (playerId == null) return;
        AtomicInteger counter = IN_FLIGHT_MAP_REQUESTS.get(playerId);
        if (counter == null) return;
        int remaining = counter.updateAndGet(current -> Math.max(0, current - 1));
        if (remaining == 0) IN_FLIGHT_MAP_REQUESTS.remove(playerId, counter);
    }

    public static void requestSnapshot(int requestSeq,
                                       ResourceLocation dimensionId,
                                       MapLoadPhase phase,
                                       int responseIndex,
                                       int minX,
                                       int minZ,
                                       int maxX,
                                       int maxZ) {
        ClientPlayNetworking.send(new MapNetworkPayloads.MapRequestRectPayload(
                requestSeq, dimensionId, phase, responseIndex, minX, minZ, maxX, maxZ));
    }

    public static void requestTeleport(int x, int y, int z) {
        ClientPlayNetworking.send(new MapNetworkPayloads.MapTeleportPayload(x, y, z));
    }

    public static void requestManualConnect(int ax, int az, int bx, int bz) {
        ClientPlayNetworking.send(new MapNetworkPayloads.MapManualConnectPayload(
                new BlockPos(ax, 0, az), new BlockPos(bx, 0, bz)));
    }

    public static void requestDeleteConnection(int ax, int az, int bx, int bz) {
        ClientPlayNetworking.send(new MapNetworkPayloads.MapDeleteConnectionPayload(
                new BlockPos(ax, 0, az), new BlockPos(bx, 0, bz)));
    }

    public static void requestSearch(int requestSeq, ResourceLocation dimensionId, String query) {
        ClientPlayNetworking.send(new MapNetworkPayloads.MapSearchRequestPayload(requestSeq, dimensionId, query));
    }

    public static void broadcastPatch(ServerPlayer player, ResourceLocation dimensionId, MapSnapshotPatch patch) {
        if (player == null || dimensionId == null || patch == null || patch.isEmpty()) return;
        ServerPlayNetworking.send(player, new MapNetworkPayloads.MapPatchPayload(dimensionId, patch));
    }

    public static void broadcastAutomaticPlanningSampling(ServerPlayer player,
                                                          ResourceLocation dimensionId,
                                                          List<AutomaticPlanningSamplingBounds> bounds) {
        if (player == null || dimensionId == null) return;
        ServerPlayNetworking.send(player,
                new MapNetworkPayloads.MapAutomaticPlanningSamplingPayload(dimensionId, bounds));
    }

    public static void syncMapAccess(ServerPlayer player) {
        if (player != null) {
            ServerPlayNetworking.send(player, new MapNetworkPayloads.MapAccessSyncPayload(MapAccessService.canOpenMap(player)));
        }
    }
}
