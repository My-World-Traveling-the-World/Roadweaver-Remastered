/* 文件职责：手绘路线的区块预加载重试 —— 必须在服务端线程执行。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteBuildService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手绘路线预加载的重试。
 *
 * <h2>为什么必须切服务端线程</h2>
 * <p>预加载会 {@code managedBlock}（同步生成区块），在客户端线程上做等于把游戏冻住。
 * 所以整段投到 {@code server.execute(...)} 里，与施工提交同一模式。</p>
 *
 * <h2>为什么重试之后还要再跑一次 preload</h2>
 * <p>{@code retryFailedPreloads} 只重试「上次失败的那些」。重试成功后，
 * 剩下的相邻区块仍然没被预加载 —— 所以紧接着再调一次
 * {@code preloadRouteChunks}，让整个区域完整。只重试不补跑，
 * 玩家会看到「重试成功了但走上去还是卡」。</p>
 */
public final class MapPreloadRetry {

    private static final Logger LOG = LoggerFactory.getLogger("roadweaver/route-build");

    private MapPreloadRetry() {}

    /** 重试失败区块并补跑整条路线的预加载。 */
    public static void retryFailed(Minecraft mc, PlayerRoute route) {
        if (mc == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        server.execute(() -> {
            try {
                int n = RouteBuildService.retryFailedPreloads(level, route);
                // 重试完还要把整条路线的区块补一遍，否则相邻区块仍是空的
                if (route != null) {
                    RouteBuildService.preloadRouteChunks(level, route);
                }
                LOG.info("手绘路线预加载重试完成：{} 条路线", n);
            } catch (Throwable t) {
                LOG.warn("手绘路线预加载重试失败", t);
            }
        });
    }
}
