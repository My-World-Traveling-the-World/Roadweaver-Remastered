/* 文件职责：桥梁配置的元数据表（17 键，样板）。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 桥梁配置元数据 —— 全仓 243 个键里最先落地的一张。
 *
 * <h2>为什么先做桥梁</h2>
 * <p>它只有 17 个键，但<b>三种接线状态全都有</b>，正好当样板：</p>
 * <ul>
 *   <li>{@code enabled} / {@code deckClearance} / … —— 正常已接线</li>
 *   <li>{@code keepLamps} / {@code minLength} —— <b>死键</b>（代码里不再读取，
 *       改了完全无效），标 {@link ConfigStatus#DEPRECATED}</li>
 *   <li>{@code minWaterDepth} —— <b>已接线</b>，标 {@link ConfigStatus#ACTIVE}。
 *       <b>2026-11-13 更正</b>：旧注释说「真实水深在 7 处硬编码为 1」，
 *       那条来自 O-11 的早期分析，后来已经改掉了 —— 现在
 *       {@code CrossingNegotiation.bridgeMinWaterDepth()}、{@code RawPathSearch}（2 处）、
 *       {@code PathPostProcessor} 都<b>真的读本配置</b>。唯一的例外是
 *       {@code terrainProfileMode=BRIDGE_FIRST} 时固定取 1（那条分支是有意的）。</li>
 * </ul>
 *
 * <p>这三种状态以前在 UI 上长得<b>一模一样</b>，玩家没法知道自己调的是不是真的。
 * 这张表就是为了让它们可见。</p>
 *
 * <p><b>范围来源</b>：{@code min}/{@code max} 与 {@code BridgeConfig.sanitize()}
 * 用的是<b>同一批</b> {@code RoadConstants} 常量，不重复定义，所以不会漂移。</p>
 *
 * @see net.shiroha233.roadweaver.config.sub.BridgeConfig
 */
public final class BridgeKeys {

    private BridgeKeys() {}

    /** 17 个键，顺序与 {@code BridgeConfig} 字段声明顺序一致。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.bool("enabled", true,
                    ConfigCategory.CROSSING, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用桥梁", "Enable bridges",
                    "关闭后所有过水改由涵洞或路基处理，不再架桥",
                    ""),

            ConfigKey.integer("deckClearance", RoadConstants.DEFAULT_BRIDGE_DECK_CLEARANCE,
                    RoadConstants.BRIDGE_DECK_CLEARANCE_MIN, RoadConstants.BRIDGE_DECK_CLEARANCE_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥面净空", "Deck clearance",
                    "桥面高出水面的格数，决定桥下能否通行",
                    "格", ""),

            ConfigKey.integer("maxLengthBlocks", RoadConstants.DEFAULT_BRIDGE_MAX_LENGTH_BLOCKS,
                    0, RoadConstants.BRIDGE_MAX_LENGTH_MAX,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "单桥最大长度", "Max bridge length",
                    "0 表示不限制。超过此长度的水域改走涵洞或绕行",
                    "格", ""),

            ConfigKey.bool("useBuoysInstead", false,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "用浮标代替桥墩", "Use buoys instead of piers",
                    "深水区不建桥墩，改用浮标标记桥位",
                    "深水区建桥墩成本高时开启"),

            ConfigKey.bool("useBuoysWhenSkipped", false,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "跳过建桥时用浮标", "Buoys when bridge skipped",
                    "某处水域放弃架桥时，用浮标标出原本的过河点",
                    ""),

            ConfigKey.integer("buoyIntervalBlocks", RoadConstants.DEFAULT_BUOY_INTERVAL_BLOCKS,
                    RoadConstants.BUOY_INTERVAL_MIN, RoadConstants.BUOY_INTERVAL_MAX,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "浮标间隔", "Buoy interval",
                    "每隔多少格放一个浮标",
                    "格", ""),

            ConfigKey.integer("pierInterval", RoadConstants.DEFAULT_BRIDGE_PIER_INTERVAL,
                    RoadConstants.BRIDGE_PIER_INTERVAL_MIN, RoadConstants.BRIDGE_PIER_INTERVAL_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥墩间隔", "Pier interval",
                    "每隔多少站立一个桥墩",
                    "站", ""),

            ConfigKey.integer("pierWidth", RoadConstants.DEFAULT_BRIDGE_PIER_WIDTH,
                    RoadConstants.BRIDGE_PIER_WIDTH_MIN, RoadConstants.BRIDGE_PIER_WIDTH_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥墩宽度", "Pier width",
                    "单个桥墩的横向宽度",
                    "格", ""),

            ConfigKey.integer("pierMaxHeight", RoadConstants.DEFAULT_BRIDGE_PIER_MAX_HEIGHT,
                    RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MIN, RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MAX,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "桥墩最大高度", "Pier max height",
                    "0 表示不限制。超过此高度改用浮标",
                    "格", ""),

            ConfigKey.bool("keepLamps", true,
                    ConfigCategory.CROSSING, ConfigTier.DEPRECATED, ConfigStatus.DEPRECATED,
                    "保留桥上路灯", "Keep bridge lamps",
                    "【已废弃】代码里不再读取此键，改了没有任何效果",
                    "曾用于在桥面按固定间隔放路灯；现在路灯由 RoadAppearanceConfig 统一控制，"
                            + "这个键已无消费方。保留仅为兼容旧配置文件的 JSON 形状"),

            ConfigKey.integer("rampSegments", RoadConstants.DEFAULT_BRIDGE_RAMP_SEGMENTS,
                    0, RoadConstants.BRIDGE_RAMP_SEGMENTS_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥头引桥段数", "Ramp segments",
                    "桥两端各用多少站做坡度过渡，越大越平缓也越占地方",
                    "站", ""),

            ConfigKey.integer("minWaterDepth", RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH,
                    RoadConstants.BRIDGE_MIN_WATER_DEPTH_MIN, RoadConstants.BRIDGE_MIN_WATER_DEPTH_MAX,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最小水深", "Min water depth",
                    "水浅于这个值就不架桥（改成浅滩/涵洞）。桥优先地形档下固定按 1 处理",
                    "格", ""),

            ConfigKey.integer("minLength", RoadConstants.DEFAULT_BRIDGE_MIN_LENGTH,
                    RoadConstants.BRIDGE_MIN_LENGTH_MIN, RoadConstants.BRIDGE_MIN_LENGTH_MAX,
                    ConfigCategory.CROSSING, ConfigTier.DEPRECATED, ConfigStatus.DEPRECATED,
                    "最短桥长", "Min bridge length",
                    "【已废弃】代码里不再读取此键，改了没有任何效果",
                    "格", "现在是否架桥由 span 宽度与过水价目档决定，不再看最小长度。"
                            + "保留仅为兼容旧配置文件的 JSON 形状"),

            ConfigKey.integer("mergeGap", RoadConstants.DEFAULT_BRIDGE_MERGE_GAP,
                    RoadConstants.BRIDGE_MERGE_GAP_MIN, RoadConstants.BRIDGE_MERGE_GAP_MAX,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "相邻桥合并间距", "Bridge merge gap",
                    "两段水相隔小于此值时合并成一座桥，避免桥连桥出现台阶",
                    "格", ""),

            ConfigKey.integer("shareRadius", 24, 0, 96,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.EXPERIMENTAL,
                    "共享跨越搜索半径", "Shared crossing radius",
                    "多条路在同一片水域过河时，在这个半径内寻找可合并的桥",
                    "格", "效果未真机验证：实测 cross-wave 生成的桥从未进入共享检查"),

            ConfigKey.integer("clearHeight", 0, 0, 16,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥下净空", "Clearance below deck",
                    "桥面到水面之间保留的空隙高度，0 表示按桥墩自动决定",
                    "格", ""),

            ConfigKey.integer("sideClear", 2, 0, 4,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥两侧留空", "Side clearance",
                    "桥面外侧额外清空的宽度，防止岸边树倒进桥里",
                    "格", "")
    );
}
