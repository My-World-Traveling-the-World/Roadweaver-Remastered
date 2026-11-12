/* 文件职责：RoadsideVillageConfig 15 个键的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code RoadsideVillageConfig} 的键元数据表。
 *
 * <p>纯生成期配方参数：什么地形能建村、建多大、离路多远。15 个键都有真实读取点。
 *
 * @see ConfigKeyTable
 */
public final class RoadsideVillageKeys {

    private RoadsideVillageKeys() {}

    /** 15 个键，顺序与同名字段声明顺序一致。 */
    public static final java.util.List<ConfigKey> ALL = java.util.List.of(
            ConfigKey.bool("enabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启用路旁村庄", "Roadside villages",
                    "沿道路生成小型村落",
                    ""),

            ConfigKey.integer("maxVillagesPerRoad", RoadConstants.DEFAULT_MAX_ROADSIDE_VILLAGES_PER_ROAD, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "单条路最多村庄数", "Max villages per road",
                    "一条路沿线最多生成几个村落",
                    "个", ""),

            ConfigKey.integer("minRoadSegments", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最小路段数", "Min road segments",
                    "路太短就不生成村庄",
                    "段", ""),

            ConfigKey.integer("windowSegments", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_WINDOW_SEGMENTS, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "搜索窗口段数", "Window segments",
                    "沿路搜索可建村位置的窗口宽度",
                    "段", ""),

            ConfigKey.integer("targetNodeCountMin", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_NODE_COUNT_MIN, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "目标节点数下限", "Target node count min",
                    "村庄至少包含几个建造节点",
                    "个", ""),

            ConfigKey.integer("targetNodeCountMax", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_NODE_COUNT_MAX, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "目标节点数上限", "Target node count max",
                    "村庄最多包含几个建造节点",
                    "个", ""),

            ConfigKey.integer("maxHeightDiff", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MAX_HEIGHT_DIFF, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最大高差", "Max height difference",
                    "村内允许的地形高差，超过就不在这里建村",
                    "格", ""),

            ConfigKey.integer("maxLocalSlope", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MAX_LOCAL_SLOPE, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最大局部坡度", "Max local slope",
                    "村址的坡度上限",
                    "格", ""),

            ConfigKey.decimal("minCurveAngle", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MIN_CURVE_ANGLE, 0.0, 1000000.0,
                    ConfigCategory.BUILD, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "最小弯角", "Min curve angle",
                    "村庄更愿意出现在弯道外侧",
                    "度", ""),

            ConfigKey.decimal("maxCurveAngle", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MAX_CURVE_ANGLE, 0.0, 1000000.0,
                    ConfigCategory.BUILD, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "最大弯角", "Max curve angle",
                    "弯道过急也不宜建村",
                    "度", ""),

            ConfigKey.integer("roadBufferBlocks", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_ROAD_BUFFER_BLOCKS, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "离路缓冲", "Road buffer",
                    "村庄建筑至少离路面多远",
                    "格", ""),

            ConfigKey.integer("buildingGapInterval", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "建筑间距", "Building gap interval",
                    "村内建筑之间的间隔",
                    "格", ""),

            ConfigKey.integer("maxStepHeight", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MAX_STEP_HEIGHT, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最大台阶高差", "Max step height",
                    "建筑地基允许的单级落差",
                    "格", ""),

            ConfigKey.integer("maxDistanceFromCenter", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_MAX_DISTANCE_FROM_CENTER, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "离中心最远距离", "Max distance from center",
                    "建筑离村中心最远多远",
                    "格", ""),

            ConfigKey.decimal("spawnChance", RoadConstants.DEFAULT_ROADSIDE_VILLAGE_SPAWN_CHANCE, 0.0, 1000000.0,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "生成概率", "Spawn chance",
                    "满足条件的路旁位置真的建村的概率",
                    "概率", "")
    );
}
