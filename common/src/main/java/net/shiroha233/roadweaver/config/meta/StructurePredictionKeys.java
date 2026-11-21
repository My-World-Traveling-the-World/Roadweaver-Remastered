/* 文件职责：StructurePredictionConfig 8 个键的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** 预测索引与最终施工避让分开说明，避免把不同用途的筛选器误认成同一开关。 */
public final class StructurePredictionKeys {

    private StructurePredictionKeys() {}

    /** 8 个键，顺序与同名字段声明顺序一致。 */
    public static final java.util.List<ConfigKey> ALL = java.util.List.of(
            ConfigKey.bool("enabled", true,
                    ConfigCategory.STRUCTURE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用结构预测", "Structure prediction",
                    "预测路线附近会生成哪些结构，用于提前避让",
                    ""),

            ConfigKey.bool("structureAvoidanceEnabled", true,
                    ConfigCategory.STRUCTURE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "启用结构避让", "Structure avoidance",
                    "让道路避开已验证的建筑与村庄",
                    ""),

            ConfigKey.integer("predictRadiusChunks", RoadConstants.DEFAULT_PREDICT_RADIUS_CHUNKS, 1, 4096,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "预测半径", "Predict radius",
                    "在路线周围多大半径内预测结构",
                    "区块", "真机实测 4374 个结构起点中 91.4% 解析完是空的，预测半径直接决定这段成本"),

            ConfigKey.bool("biomePrefilter", true,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "结构预测群系预筛",
                    "Prediction biome prefilter",
                    "结构预测器按多高度群系样本筛起点；不是结构避让服务中已停用的固定Y64预筛。",
                    ""),

            ConfigKey.list("structureWhitelist", new ArrayList<>(List.of("#minecraft:village")),
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "结构预测白名单",
                    "Structure prediction whitelist",
                    "仅预测符合这些ID、通配符或#标签的结构；空列表不限。与独立结构选择页、最终避让范围不同。",
                    ""),

            ConfigKey.list("structureBlacklist", new ArrayList<>(),
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "结构预测黑名单",
                    "Structure prediction blacklist",
                    "预测时排除匹配ID、通配符或#标签的结构；优先于白名单，不取消最终施工保护。",
                    ""),

            ConfigKey.integer("villageRoadOffset", RoadConstants.DEFAULT_VILLAGE_ROAD_OFFSET, 0, 64,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "村庄让路偏移", "Village road offset",
                    "村庄建筑与路面的额外间隙",
                    "格", ""),

            ConfigKey.integer("otherStructureRoadOffset", RoadConstants.DEFAULT_OTHER_STRUCTURE_ROAD_OFFSET, 0, 64,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "其它结构让路偏移", "Other structure road offset",
                    "非村庄结构（遗迹/神庙等）与路面的额外间隙",
                    "格", "")
    );
}
