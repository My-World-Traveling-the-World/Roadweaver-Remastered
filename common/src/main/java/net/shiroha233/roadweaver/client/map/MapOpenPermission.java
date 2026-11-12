/* 文件职责：判断当前玩家能不能打开地图；不能时的统一处置。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.map.permission.MapAccessService;
// ClientMapAccessGuard 与本类同包（client.map），不需要 import

/**
 * 地图打开权限。
 *
 * <h2>判定顺序是有意义的</h2>
 * <p>三道关卡按「从便宜到贵」排：</p>
 * <ol>
 *   <li>{@code mc == null} —— 无客户端上下文，放行（不算拒绝）；</li>
 *   <li>客户端预判 {@code isAllowedOrUnknown()} —— 便宜的本地判断，已知被拒就立刻停；</li>
 *   <li>服务端权威判定 {@code canOpenMap(player)} —— 真正的判据。</li>
 * </ol>
 *
 * <h2>三个刻意的「放行」分支</h2>
 * <p>非单机（{@code getSingleplayerServer() == null}）、没有本地玩家、
 * 或服务端查不到该玩家时一律<b>放行</b>。理由：地图界面是只读视图，
 * 误拒会让玩家完全没法用；而真正的权限校验在服务端的命令与施工路径上还有一道。</p>
 */
public final class MapOpenPermission {

    private MapOpenPermission() {}

    /** 当前玩家是否可以打开地图。 */
    public static boolean allows(Minecraft mc) {
        if (mc == null) {
            return true;
        }
        if (!ClientMapAccessGuard.isAllowedOrUnknown()) {
            return false;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return true;
        }
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        return serverPlayer == null || MapAccessService.canOpenMap(serverPlayer);
    }

    /** 被拒绝时的统一处置：提示、清缓存、关闭界面。 */
    public static void denyAndClose(Minecraft mc, Runnable closeAction) {
        ClientMapAccessGuard.notifyDenied(mc);
        MapSnapshotCache.clearNow();
        closeAction.run();
    }
}
