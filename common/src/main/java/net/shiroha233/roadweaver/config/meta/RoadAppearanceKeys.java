/* 文件职责：RoadAppearanceKeys当前配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** 当前生产读取项的元数据；删除未接线控件，避免把显示或日志读取误判为生效。 */
public final class RoadAppearanceKeys {

    private RoadAppearanceKeys() {}

    /** 字段与元数据的双向一致性由回归强制检查。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.bool("roadsEnabled", true,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用道路生成", "Roads enabled",
                    "总开关。关闭后不再生成任何新道路",
                    "字段类型是 Boolean（可为 null），GSON 缺键时会补默认值"),

            ConfigKey.bool("allowArtificial", true,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "允许人工道路", "Allow artificial roads",
                    "允许生成带铺装/路灯的人工化道路",
                    ""),

            ConfigKey.bool("allowNatural", true,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "允许自然道路", "Allow natural roads",
                    "允许生成更贴近地形的自然土路",
                    ""),

            ConfigKey.bool("spawnCabinEnabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "生成起点小屋", "Spawn cabin",
                    "在出生点附近生成一间小屋作为起点",
                    ""),

            ConfigKey.integer("averagingRadius", RoadConstants.DEFAULT_AVERAGING_RADIUS, 0, 32,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "高度平滑半径", "Averaging radius",
                    "路面高度在这个半径内做平均，抑制单格突起",
                    "格", ""),

            ConfigKey.integer("roadWidth", RoadConstants.DEFAULT_ROAD_WIDTH, 1, 16,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "道路宽度", "Road width",
                    "路面的宽度。全仓有 73 处引用，是最核心的观感参数之一",
                    "格", ""),

            ConfigKey.bool("roadSignsEnabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "生成路标", "Road signs",
                    "在路口与岔路放置路标",
                    ""),

            ConfigKey.integer("lampInterval", RoadConstants.DEFAULT_LAMP_INTERVAL, 0, 64,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "路灯间距", "Lamp interval",
                    "每隔多少格放一盏路灯。0 表示不放",
                    "格", ""),

            ConfigKey.integer("roadClearHeight", RoadConstants.DEFAULT_ROAD_CLEAR_HEIGHT, 1, 16,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "路面净空高度", "Road clear height",
                    "路面上方要清空多高，避免树/方块挡路",
                    "格", ""),

            ConfigKey.bool("tunnelEnabled", false,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启用隧道", "Tunnel enabled",
                    "允许在山体中挖隧道穿过",
                    ""),

            ConfigKey.integer("tunnelClearHeight", RoadConstants.DEFAULT_TUNNEL_CLEAR_HEIGHT, 1, 16,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道净空高度", "Tunnel clear height",
                    "洞身的高度",
                    "格", ""),

            ConfigKey.bool("preventTreesOnRoad", true,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "抑制路面树木", "Prevent trees on road",
                    "路面及其上方不长树，避免路口被树封死",
                    ""),

            ConfigKey.integer("maxSlopeStepPerTwoSegments", RoadConstants.DEFAULT_MAX_SLOPE_STEP, 0, 16,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "两站最大高差", "Max slope step per two segments",
                    "相邻两站之间允许的最大高度变化",
                    "格", "★ 单位口径：profile 的「站」约等于 1.0 格/站，所以本值 1 的实际含义接近 100% 坡度，真正绑定的是每两站 1 格 = 50%"),

            ConfigKey.bool("slopeLimitEnabled", true,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用坡度限制", "Slope limit",
                    "超过坡度限制的站会被纵断面规划修复。关闭后路会直接硬爬",
                    ""),

            ConfigKey.bool("tunnelDrySeal", true,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道干封", "Tunnel dry seal",
                    "把隧道洞身与水隔离，避免洞内渗水",
                    ""),

            ConfigKey.bool("culvertEnabled", true,
                    ConfigCategory.CROSSING, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用涵洞", "Culvert enabled",
                    "浅水且窄时用涵洞跨越，而不是架桥",
                    ""),

            // ★ 2026-11-15：范围 0..64 → **1..16**。
            //   原来声明 0..64，但下游三处各自更窄：
            //     · RoadAppearanceConfig.setCulvertMaxStations 实夹 1..16
            //     · LocalRouteSearch 规划期夹 0..16
            //     · 施工期 CulvertJudge 原来硬编码 6
            //   用户在 UI 上拖到 30 时，实际生效的是 16 ⇒ 声明与实现不符。
            //   现在统一到 1..16（规划期实际能生效的范围）。
            //   注意：0 不再可选；要关掉涵洞请用 culvertEnabled。
            ConfigKey.integer("culvertMaxStations", 6, 1, 16,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "涵洞最大站数", "Culvert max stations",
                    "超过这个站数的水体改走窄水桥或绕行",
                    "站", ""),

            ConfigKey.integer("culvertMaxWaterDepth", 2, 0, 16,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "涵洞最大水深", "Culvert max water depth",
                    "水深超过这个值就不能用涵洞",
                    "格", ""),

            ConfigKey.bool("slopeByArcLength", false,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "坡度按弧长计", "Slope by arc length",
                    "开启后坡度按实际弧长而不是按站数计算",
                    "关闭时按站数，站距不均会让坡度被高估或低估"),

            ConfigKey.bool("forcedClearProtected", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "强制清除受保护方块", "Force clear protected",
                    "道路施工时可以拆掉受保护的方块（箱子/告示牌等）",
                    "与 0.9.033 的单列保护逻辑相关：路面格受保护时会走「只铺路面不动路基」的窄路径"),

            ConfigKey.bool("landTunnelEnabled", true,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "陆上隧道", "Land tunnel",
                    "在陆地上的高地也挖隧道穿过（关掉则只在过水处用隧道）",
                    ""),

            ConfigKey.bool("bumpCutEnabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平路面突起", "Bump cut",
                    "把路面上的单格突起削掉",
                    ""),

            ConfigKey.integer("bumpCutMaxBlocks", 5, 0, 32,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平最大高度", "Bump cut max blocks",
                    "最多削掉多少格高的突起",
                    "格", ""),

            ConfigKey.decimal("bumpCutMaxColumnRatio", 0.5D, 0.0, 1.0D,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平列占比上限", "Bump cut max column ratio",
                    "受影响列超过这个比例就判定为地形而不是突起，不削",
                    "比例", "")
    );
}
