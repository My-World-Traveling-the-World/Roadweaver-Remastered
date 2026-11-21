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
                    "连接出生点道路",
                    "Connect spawn road",
                    "登记出生点结构及连接节点，使出生点加入新路网。",
                    ""),

            ConfigKey.integer("averagingRadius", RoadConstants.DEFAULT_AVERAGING_RADIUS, RoadConstants.AVERAGING_RADIUS_MIN, RoadConstants.AVERAGING_RADIUS_MAX,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "高度中位数窗口半径",
                    "Height median window radius",
                    "取相邻站索引窗口的高度中位数抑制离群值；单位为站，不是方块半径。",
                    "站",
                    ""),

            ConfigKey.integer("roadWidth", RoadConstants.DEFAULT_ROAD_WIDTH, 1, RoadConstants.ROAD_WIDTH_MAX,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "道路宽度",
                    "Road width",
                    "正奇数1..15格；偶数不能表示对称中心线占地，菜单会拒绝偶数。",
                    "格",
                    ""),

            ConfigKey.bool("roadSignsEnabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "生成路标", "Road signs",
                    "在路口与岔路放置路标",
                    ""),

            ConfigKey.integer("lampInterval", RoadConstants.DEFAULT_LAMP_INTERVAL, RoadConstants.LAMP_INTERVAL_MIN, RoadConstants.LAMP_INTERVAL_MAX,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "陆路路灯间隔",
                    "Land lamp spacing",
                    "每隔指定站数放路灯，范围1..2048；与模板桥按格计的桥灯间隔独立。",
                    "站",
                    ""),

            ConfigKey.integer("roadClearHeight", RoadConstants.DEFAULT_ROAD_CLEAR_HEIGHT, 1, 16,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "路面净空高度", "Road clear height",
                    "路面上方要清空多高，避免树/方块挡路",
                    "格", ""),

            ConfigKey.bool("tunnelEnabled", false,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "允许水下隧道转换",
                    "Allow underwater tunnel conversion",
                    "允许水域跨越转换为隧道；陆上隧道入口由“陆上隧道”单独控制。",
                    ""),

            ConfigKey.integer("tunnelClearHeight", RoadConstants.DEFAULT_TUNNEL_CLEAR_HEIGHT, RoadConstants.TUNNEL_CLEAR_HEIGHT_MIN, RoadConstants.TUNNEL_CLEAR_HEIGHT_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道净空高度", "Tunnel clear height",
                    "洞身的高度",
                    "格", ""),

            ConfigKey.bool("preventTreesOnRoad", true,
                    ConfigCategory.BUILD, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "抑制路面树木", "Prevent trees on road",
                    "路面及其上方不长树，避免路口被树封死",
                    ""),

            ConfigKey.integer("maxSlopeStepPerTwoSegments", RoadConstants.DEFAULT_MAX_SLOPE_STEP, 1, RoadConstants.MAX_SLOPE_STEP_MAX,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "每两段最大高差",
                    "Maximum rise per two segments",
                    "范围1..8格/两段。数值0会校正为1；要取消限制请关闭“启用坡度限制”。",
                    "格/两段",
                    ""),

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

            ConfigKey.integer("culvertMaxWaterDepth", 2, 1, 4,
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
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "桥面上方强制净空",
                    "Force bridge overhead clearance",
                    "只影响桥面上方净空遇到受保护方块时的处理，不是取消长城、巨树及结构整列保护的总开关。",
                    ""),

            ConfigKey.bool("landTunnelEnabled", true,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "陆上隧道", "Land tunnel",
                    "在陆地上的高地也挖隧道穿过（关掉则只在过水处用隧道）",
                    ""),

            ConfigKey.bool("bumpCutEnabled", true,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平路面突起", "Bump cut",
                    "把路面上的单格突起削掉",
                    ""),

            ConfigKey.integer("bumpCutMaxBlocks", 5, 0, 24,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平最大高度", "Bump cut max blocks",
                    "最多削掉多少格高的突起",
                    "格", ""),

            ConfigKey.decimal("bumpCutMaxColumnRatio", 0.5D, 0.0, 1.0D,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "削平列占比上限", "Bump cut max column ratio",
                    "受影响列超过这个比例就判定为地形而不是突起，不削",
                    "比例", "")
    );
}
