/* 文件职责：ClientConfig 4 个键的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code ClientConfig} 的键元数据表。
 *
 * <p>四个纯客户端开关，只影响显示、不影响生成结果，所以多人游戏里
 * 每个客户端可以各自设置。
 *
 * @see ConfigKeyTable
 */
public final class ClientKeys {

    private ClientKeys() {}

    /** 4 个键，顺序与同名字段声明顺序一致。 */
    public static final java.util.List<ConfigKey> ALL = java.util.List.of(
            ConfigKey.bool("loadingTipsEnabled", true,
                    ConfigCategory.CLIENT, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "加载提示", "Loading tips",
                    "生成期间显示操作提示",
                    ""),

            ConfigKey.bool("loadingProgressEnabled", true,
                    ConfigCategory.CLIENT, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "加载进度", "Loading progress",
                    "生成期间显示进度条与阶段文字",
                    ""),

            ConfigKey.bool("routeEditorHudEnabled", true,
                    ConfigCategory.CLIENT, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "路线编辑器 HUD", "Route editor HUD",
                    "路线编辑器里显示辅助 HUD",
                    ""),

            ConfigKey.bool("mapEndpointMarkersEnabled", false,
                    ConfigCategory.CLIENT, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "地图端点标记", "Map endpoint markers",
                    "在地图上标出道路端点",
                    "")
    );
}
