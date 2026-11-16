/* 文件职责：手绘路线的区块预加载重试 —— 必须在服务端线程执行。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.route.RouteBuildService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 预加载状态由服务端 tick 独占；客户端的重试按钮只发入队意图。 */
public final class MapPreloadRetry {

    private static final Logger LOG = LoggerFactory.getLogger("roadweaver/route-build");

    private MapPreloadRetry() {}

    /** 按失败路线入队；当前编辑器可能已经换成新草稿。 */
    public static void retryFailed(Minecraft mc) {
        if (mc == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        server.execute(() -> {
            try {
                int n = RouteBuildService.retryFailedPreloads(level);
                LOG.info("手绘路线预加载重试已入队：{} 条路线", n);
            } catch (Throwable t) {
                LOG.warn("手绘路线预加载重试失败", t);
            }
        });
    }
}
