/* 文件职责：RoadAppearanceConfig 25 个键的元数据 —— 路面观感、桥隧开关与施工行为。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code RoadAppearanceConfig} 的键元数据表。
 *
 * <h2>为什么这一类的错误最难发现</h2>
 * <p>观感类旋钮的失败模式是「路还在，只是不好看」—— 不会崩、不会报错，
 * 玩家只会觉得「这个选项好像没用」。本表里两个键已实证就是这个状态：</p>
 * <ul>
 *   <li>{@code placeWaypoints} —— 全仓 0 处外部引用。</li>
 *   <li>{@code landTunnelEnabled} —— <b>已接线</b>，标 {@link ConfigStatus#ACTIVE}。
 *       <b>2026-11-13 更正</b>：旧注释说它是「假开关（{@code buried = oldBuried}，
 *       {@code newBuried} 只进日志）」，那是 0.9.005-B 时期的状态。
 *       现在 {@code PathSpanExtractor} 里是
 *       {@code boolean buried = landTunnelSwitch && newBuried;} ——
 *       <b>真的参与判定</b>，并且还传进后续调用。关掉它就不会挖陆上隧道。</li>
 * </ul>
 *
 * <h2>单位口径要说清</h2>
 * <p>{@code maxSlopeStepPerTwoSegments} 的「站」约等于 1.0 格/站，
 * 所以本值 1 的实际含义接近 100% 坡度 —— 真正绑定的是「每两站 1 格 = 50%」。
 * 这个口径在多次分析里都被搞错过，直接写在说明里。</p>
 *
 * @see ConfigKeyTable
 */
public final class RoadAppearanceKeys {

    private RoadAppearanceKeys() {}

    /** 25 个键，顺序与 {@code RoadAppearanceConfig} 字段声明顺序一致。 */
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

            ConfigKey.bool("placeWaypoints", false,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.UNWIRED,
                    "放置路径点", "Place waypoints",
                    "沿途放置传送/导航用的路径点",
                    "★ 已实证未接线：全仓 0 处外部引用，改了不会有任何效果"),

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

            ConfigKey.integer("culvertMaxStations", 6, 0, 64,
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
