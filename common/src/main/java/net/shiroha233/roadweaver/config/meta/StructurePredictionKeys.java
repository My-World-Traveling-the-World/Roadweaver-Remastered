/* 文件职责：StructurePredictionConfig 8 个键的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code StructurePredictionConfig} 的键元数据表。
 *
 * <h2>这是全仓最贵的一段</h2>
 * <p>真机实测：结构解析占 async planning 的 53%，4374 个起点里
 * <b>91.4% 解析完是空的</b>，累计 119 批 / 177 秒。</p>
 *
 * <h2>biomePrefilter 是「已判定不合格但保留开关」的典型</h2>
 * <p>0.9.035 真机计数：probed=37181 / wouldReject=33288 /
 * wouldRejectNonEmpty=769。预筛方向对，但会静默丢 769 个真有方块的结构起点。
 * 根因是单点 getNoiseBiome(quart(locatePos), quart(64)) 复刻不了 vanilla
 * 按 heightmapProjection 多点取样的群系判定。</p>
 *
 * @see ConfigKeyTable
 */
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

            ConfigKey.integer("predictRadiusChunks", RoadConstants.DEFAULT_PREDICT_RADIUS_CHUNKS, 0, 1000000,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "预测半径", "Predict radius",
                    "在路线周围多大半径内预测结构",
                    "区块", "真机实测 4374 个结构起点中 91.4% 解析完是空的，预测半径直接决定这段成本"),

            ConfigKey.bool("biomePrefilter", true,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "群系预筛", "Biome prefilter",
                    "枚举起点时先用群系筛掉不可能的区块",
                    "⚠ 0.9.035 真机判定不合格：会挡掉 89.5% 起点，但其中 769 个真有方块（误判 2.3%）⇒ 开启会静默丢结构起点。保留开关是为了诊断"),

            ConfigKey.list("structureWhitelist", new ArrayList<>(List.of("#minecraft:village")),
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "结构白名单", "Structure whitelist",
                    "总是参与避让的结构集合",
                    "写成 #命名空间:标签 或 命名空间:id"),

            ConfigKey.list("structureBlacklist", new ArrayList<>(),
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "结构黑名单", "Structure blacklist",
                    "永不参与避让的结构集合",
                    "白名单里的会被黑名单覆盖"),

            ConfigKey.integer("villageRoadOffset", RoadConstants.DEFAULT_VILLAGE_ROAD_OFFSET, 0, 1000000,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "村庄让路偏移", "Village road offset",
                    "村庄建筑与路面的额外间隙",
                    "格", ""),

            ConfigKey.integer("otherStructureRoadOffset", RoadConstants.DEFAULT_OTHER_STRUCTURE_ROAD_OFFSET, 0, 1000000,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "其它结构让路偏移", "Other structure road offset",
                    "非村庄结构（遗迹/神庙等）与路面的额外间隙",
                    "格", "")
    );
}
