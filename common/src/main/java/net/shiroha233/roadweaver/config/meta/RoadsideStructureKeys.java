/* 文件职责：RoadsideStructureConfig 5 个键的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code RoadsideStructureConfig} 的键元数据表。
 *
 * <p>与路旁村庄的区别：这里生成单体建筑而不是聚落，所以只有「生成多少个」
 * 与「按体量分档离路多远」两类参数。
 *
 * @see ConfigKeyTable
 */
public final class RoadsideStructureKeys {

    private RoadsideStructureKeys() {}

    /** 5 个键，顺序与同名字段声明顺序一致。 */
    public static final java.util.List<ConfigKey> ALL = java.util.List.of(
            ConfigKey.bool("enabled", true,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启用路旁结构", "Roadside structures",
                    "沿道路生成小型建筑",
                    ""),

            ConfigKey.integer("maxStructuresPerRoad", RoadConstants.DEFAULT_MAX_STRUCTURES_PER_ROAD, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "单条路最多结构数", "Max structures per road",
                    "一条路沿线最多生成几个小型结构",
                    "个", ""),

            ConfigKey.integer("smallStructureOffset", RoadConstants.DEFAULT_SMALL_STRUCTURE_OFFSET, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "小结构离路偏移", "Small structure offset",
                    "小型建筑离路面多远",
                    "格", ""),

            ConfigKey.integer("mediumStructureOffset", RoadConstants.DEFAULT_MEDIUM_STRUCTURE_OFFSET, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "中结构离路偏移", "Medium structure offset",
                    "中型建筑离路面多远",
                    "格", ""),

            ConfigKey.integer("largeStructureOffset", RoadConstants.DEFAULT_LARGE_STRUCTURE_OFFSET, 0, 1000000,
                    ConfigCategory.BUILD, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "大结构离路偏移", "Large structure offset",
                    "大型建筑离路面多远",
                    "格", "")
    );
}
