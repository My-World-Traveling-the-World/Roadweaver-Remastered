/* 文件职责：按已安装版本选择可选的 Diorama 底图实现。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

import net.fabricmc.loader.api.FabricLoader;
import net.shiroha233.roadweaver.client.map.MapTerrainBackground;

/** 只对已核对的 Diorama 发行版加载底图实现，独立运行的道路不需要地图类。 */
public final class DioramaBackgroundCompat {
    private DioramaBackgroundCompat() {}

    public static MapTerrainBackground create() {
        var version = FabricLoader.getInstance().getModContainer("diorama")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
        return supports(version) ? new DioramaTerrainBackground() : MapTerrainBackground.NONE;
    }

    static boolean supports(String version) {
        return "0.11.0+1.21-1.21.1".equals(version);
    }
}
