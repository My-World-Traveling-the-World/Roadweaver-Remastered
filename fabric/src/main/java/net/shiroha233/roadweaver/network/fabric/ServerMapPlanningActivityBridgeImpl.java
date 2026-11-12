/* 文件职责：在 Fabric 服务端向当前维度客户端同步自动规划采样范围。 */
package net.shiroha233.roadweaver.network.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import java.util.List;

/**
 * Fabric 自动规划采样范围同步实现。
 */
public final class ServerMapPlanningActivityBridgeImpl {
    private ServerMapPlanningActivityBridgeImpl() {}

    public static void broadcast(ServerLevel level) {
        if (level == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || RoadLifecycle.isStopping()) {
            return;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        server.execute(() -> {
            if (!ThreadPoolManager.isEpoch(epoch)
                    || RoadLifecycle.isStopping()
                    || level.getServer() != server) {
                return;
            }
            List<AutomaticPlanningSamplingBounds> snapshot = AutomaticPlanningSamplingActivities.snapshot(level);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null && player.serverLevel() == level) {
                    MapNetworkFabric.broadcastAutomaticPlanningSampling(
                            player,
                            level.dimension().location(),
                            snapshot);
                }
            }
        });
    }
}
