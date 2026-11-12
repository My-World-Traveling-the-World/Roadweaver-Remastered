/* 文件职责：地图访问的平台桥（@ExpectPlatform，由各加载器实现）。 */
package net.shiroha233.roadweaver.map.permission;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

public final class MapAccessPlatformBridge {
    private MapAccessPlatformBridge() {
    }

    @ExpectPlatform
    public static MapAccessPolicy getPolicy(MinecraftServer server) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void setPolicy(MinecraftServer server, MapAccessPolicy policy) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void syncPlayer(ServerPlayer player) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void syncAll(MinecraftServer server) {
        throw new AssertionError();
    }
}
