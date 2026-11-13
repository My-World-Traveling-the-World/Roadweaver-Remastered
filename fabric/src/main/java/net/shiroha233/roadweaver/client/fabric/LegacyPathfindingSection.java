/* 文件职责：配置界面「遗留寻路参数」分区 —— 旧 A* 算法专属参数（仅作历史保留）。
 *
 * 2026-11-13：旧 A* 系寻路器（BasicAStar/Bidirectional/GradientDescent/PotentialField）
 * 已全部删除，这些参数不再被任何生产路径读取。
 * 但它们仍被 {@code PlannedPathFingerprintService} 用于计算规划指纹（缓存失效判据），
 * 所以**字段不能删**，只能从主分区移到本遗留分区，并诚实标注「仅作历史保留」。
 */
package net.shiroha233.roadweaver.client.fabric;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 遗留寻路参数分区（旧 A* 算法专属参数，仅作历史保留）。
 *
 * <h2>为什么保留这些字段</h2>
 * <p>旧 A* 系寻路器已全部删除，这些参数不再被任何生产路径读取。
 * 但它们仍被 {@code PlannedPathFingerprintService} 用于计算规划指纹
 * （缓存失效判据）—— 改这些值会改指纹、令已有路线重算。
 * 所以字段不能删，只能从主分区移到本遗留分区。</p>
 *
 * <h2>诚实标注</h2>
 * <p>本分区所有参数都标注「旧 A* 算法参数，仅作历史保留」，
 * 与 {@code ConfigUiHonestyTest} 的诚实标注口径一致。</p>
 */
public final class LegacyPathfindingSection {

    private LegacyPathfindingSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(
                Component.translatable("config.roadweaver.category.legacy_pathfinding"));
        PathfindingCostConfig cfg = conf.pathfindingCost();
        PathfindingCostConfig def = defaultConf.pathfindingCost();

        // ── 遗留说明（所有参数都已不被生产路径读取）─────────────────────────
        category.addEntry(eb
                .startTextDescription(Component.translatable(
                        "config.roadweaver.legacy_pathfinding.notice"))
                .setColor(0xFFFFAA00)
                .build());

        // ===== 势场法（POTENTIAL_FIELD）晋升常量（0.8.411 起可调）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_discount"), cfg.contourDiscount())
                .setDefaultValue(def.contourDiscount())
                .setTooltip(Component.translatable("config.roadweaver.contour_discount.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setContourDiscount)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.soft_grade_limit"), cfg.softGradeLimit())
                .setDefaultValue(def.softGradeLimit())
                .setTooltip(Component.translatable("config.roadweaver.soft_grade_limit.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setSoftGradeLimit)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.hard_grade_limit"), cfg.hardGradeLimit())
                .setDefaultValue(def.hardGradeLimit())
                .setTooltip(Component.translatable("config.roadweaver.hard_grade_limit.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setHardGradeLimit)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.soft_grade_penalty"), cfg.softGradePenalty())
                .setDefaultValue(def.softGradePenalty())
                .setTooltip(Component.translatable("config.roadweaver.soft_grade_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setSoftGradePenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.hard_grade_penalty"), cfg.hardGradePenalty())
                .setDefaultValue(def.hardGradePenalty())
                .setTooltip(Component.translatable("config.roadweaver.hard_grade_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setHardGradePenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.gradient_align_penalty"), cfg.gradientAlignPenalty())
                .setDefaultValue(def.gradientAlignPenalty())
                .setTooltip(Component.translatable("config.roadweaver.gradient_align_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setGradientAlignPenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.pf_water_base_penalty"), cfg.pfWaterBasePenalty())
                .setDefaultValue(def.pfWaterBasePenalty())
                .setTooltip(Component.translatable("config.roadweaver.pf_water_base_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setPfWaterBasePenalty)
                .build());

        // ===== 蜿蜒 / 群系走向（0.8.413 起可调）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_water"), cfg.biomeCostWater())
                .setDefaultValue(def.biomeCostWater())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_water.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostWater)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_plains"), cfg.biomeCostPlains())
                .setDefaultValue(def.biomeCostPlains())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_plains.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostPlains)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_forest"), cfg.biomeCostForest())
                .setDefaultValue(def.biomeCostForest())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_forest.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostForest)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_mountain"), cfg.biomeCostMountain())
                .setDefaultValue(def.biomeCostMountain())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_mountain.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostMountain)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_swamp"), cfg.biomeCostSwamp())
                .setDefaultValue(def.biomeCostSwamp())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_swamp.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostSwamp)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_desert"), cfg.biomeCostDesert())
                .setDefaultValue(def.biomeCostDesert())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_desert.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostDesert)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_other"), cfg.biomeCostOther())
                .setDefaultValue(def.biomeCostOther())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_other.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostOther)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_follow_weight"), cfg.contourFollowWeight())
                .setDefaultValue(def.contourFollowWeight())
                .setTooltip(Component.translatable("config.roadweaver.contour_follow_weight.tooltip"))
                .setMin(0).setMax(20)
                .setSaveConsumer(cfg::setContourFollowWeight)
                .build());

        // ===== 0.8.409 分段/软惩罚（最细粒度奖励惩罚体系）=====

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_gentle_max"), cfg.elevationGentleMax())
                .setDefaultValue(def.elevationGentleMax())
                .setTooltip(Component.translatable("config.roadweaver.elevation_gentle_max.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setElevationGentleMax)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_gentle_weight"), cfg.elevationGentleWeight())
                .setDefaultValue(def.elevationGentleWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_gentle_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationGentleWeight)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_steep_weight"), cfg.elevationSteepWeight())
                .setDefaultValue(def.elevationSteepWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_steep_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationSteepWeight)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_cliff_threshold"), cfg.elevationCliffThreshold())
                .setDefaultValue(def.elevationCliffThreshold())
                .setTooltip(Component.translatable("config.roadweaver.elevation_cliff_threshold.tooltip"))
                .setMin(1).setMax(32)
                .setSaveConsumer(cfg::setElevationCliffThreshold)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_cliff_weight"), cfg.elevationCliffWeight())
                .setDefaultValue(def.elevationCliffWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_cliff_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationCliffWeight)
                .build());
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.elevation_segmented_cost"), cfg.elevationSegmentedCost())
                .setDefaultValue(def.elevationSegmentedCost())
                .setTooltip(Component.translatable("config.roadweaver.elevation_segmented_cost.tooltip"))
                .setSaveConsumer(cfg::setElevationSegmentedCost)
                .build());
    }
}
