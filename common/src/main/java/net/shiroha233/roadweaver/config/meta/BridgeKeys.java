/* 文件职责：BridgeKeys当前配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** 当前生产读取项的元数据；删除未接线控件，避免把显示或日志读取误判为生效。 */
public final class BridgeKeys {

    private BridgeKeys() {}

    /** 字段与元数据的双向一致性由回归强制检查。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.integer("lightIntervalBlocks", RoadConstants.DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS, 0, 2048,
                    ConfigCategory.CROSSING, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "模板桥灯间隔", "Template bridge light spacing",
                    "沿桥自身跨度里程布置新增海晶灯；0关闭新增灯，不删除模板保存的灯",
                    "格", "陆路路灯间隔按站计数，与本项独立"),

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
                    "程序桥桥墩宽度",
                    "Procedural bridge pier width",
                    "程序桥桥墩的横向宽度；模板桥的补充支撑仍使用模板已有列，不缩放体素。",
                    "格",
                    ""),

            ConfigKey.integer("pierMaxHeight", RoadConstants.DEFAULT_BRIDGE_PIER_MAX_HEIGHT,
                    RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MIN, RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MAX,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "桥墩最大高度", "Pier max height",
                    "0 表示不限制。超过此高度改用浮标",
                    "格", ""),

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
                    "桥面上方净空",
                    "Clearance above bridge deck",
                    "桥面上方清理高度，0跟随隧道净空；不向下挖，不是桥下通航高度。",
                    "格",
                    ""),

            ConfigKey.integer("sideClear", 2, 0, 4,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "程序桥侧向净空",
                    "Procedural bridge side clearance",
                    "程序桥上方净空带两侧各清几格；模板桥不以本项扩挖模板周围。",
                    "格",
                    "")
    );
}
