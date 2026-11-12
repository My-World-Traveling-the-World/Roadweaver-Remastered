/* 文件职责：地图快照的加载调度 —— 单机分阶段构建 / 联机分阶段请求。 */
package net.shiroha233.roadweaver.client.map;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.client.map.data.MapDataCollector;
import net.shiroha233.roadweaver.client.map.data.MapSnapshot;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.network.ClientNetBridge;
import net.shiroha233.roadweaver.util.ComputeService;

/**
 * 地图快照的分阶段加载。
 *
 * <h2>它解决的两个竞态</h2>
 * <p>单机路径是「异步算 + 主线程收货」，中间隔着一段时间，这段时间里玩家可能：</p>
 * <ol>
 *   <li><b>换了存档</b> —— 算出来的旧世界数据不能贴到新世界的图上；</li>
 *   <li><b>换了维度 / 重进了世界</b> —— {@code ServerLevel} 实例变了，
 *       旧 place 的结果同样作废。</li>
 * </ol>
 * <p>所以收货前要同时校验世界 ID 与 level 实例，两者任一不符就整批丢弃。
 * 这两条校验原本写在界面类的匿名 lambda 里，一改就容易漏，现在集中在这里。</p>
 *
 * @see MapLoadPhase
 */
public final class MapSnapshotLoading {

    /** 收到一阶段快照时的回调（由界面负责落到 snapshotStore）。 */
    @FunctionalInterface
    public interface PartAcceptor {
        void accept(int requestSeq, ResourceLocation dimensionId,
                    MapLoadPhase phase, int responseIndex, MapSnapshot snapshot);
    }

    private MapSnapshotLoading() {}

    /**
     * 单机：把每个阶段的构建任务投到地图读取线程，完成后切回主线程交货。
     */
    public static void scheduleSingleplayer(net.minecraft.client.Minecraft mc,
                                            ServerLevel level,
                                            int requestSeq,
                                            List<MapLoadSession.ResponseRequest> requests,
                                            PartAcceptor acceptor) {
        ResourceLocation dimensionId = level.dimension().location();
        final String worldId = MapSnapshotCache.getCurrentWorldId();
        final ServerLevel expectedLevel = level;
        for (int i = 0; i < requests.size(); i++) {
            MapLoadSession.ResponseRequest request = requests.get(i);
            final int responseIndex = i;
            final MapLoadPhase phase = request.phase();
            final MapViewportController.RequestRect rect = request.rect();
            Supplier<MapSnapshot> supplier = () -> buildSnapshotForPhase(level, phase, rect);
            java.util.concurrent.CompletableFuture
                    .supplyAsync(supplier, ComputeService.mapReadExecutor())
                    .thenAccept(snap -> mc.execute(() -> {
                        // 换存档了 → 整批作废
                        if (!Objects.equals(worldId, MapSnapshotCache.getCurrentWorldId())) return;
                        // 换维度 / 重进世界了 → 整批作废
                        if (expectedLevel == null
                                || MapViewportController.resolveSingleplayerLevel(mc) != expectedLevel) {
                            return;
                        }
                        acceptor.accept(requestSeq, dimensionId, phase, responseIndex, snap);
                    }));
        }
    }

    /** 联机：逐个阶段向服务端要。 */
    public static void dispatchRemote(int requestSeq,
                                      ResourceLocation dimensionId,
                                      List<MapLoadSession.ResponseRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            MapLoadSession.ResponseRequest request = requests.get(i);
            MapViewportController.RequestRect rect = request.rect();
            ClientNetBridge.requestSnapshot(requestSeq, dimensionId, request.phase(), i,
                    rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        }
    }

    /** 按阶段收集快照。 */
    public static MapSnapshot buildSnapshotForPhase(ServerLevel level,
                                                    MapLoadPhase phase,
                                                    MapViewportController.RequestRect rect) {
        return switch (phase) {
            case STRUCTURES -> MapDataCollector.buildStructuresSnapshot(
                    level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
            case ROADS -> MapDataCollector.buildRoadsSnapshot(
                    level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
            case CONNECTIONS -> MapDataCollector.buildConnectionsSnapshot(
                    level, rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        };
    }
}
