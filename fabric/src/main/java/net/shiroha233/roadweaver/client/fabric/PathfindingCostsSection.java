/* 文件职责：配置界面「寻路代价」分区 —— 只构造控件与绑定，不做业务判断。 */
package net.shiroha233.roadweaver.client.fabric;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.config.sub.*;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLDevicePreference;
import java.util.Locale;

/**
 * 寻路代价配置分区。
 *
 * <p>各寻路算法的代价权重。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class PathfindingCostsSection {

    private PathfindingCostsSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.pathfinding_costs"));
        PathfindingCostConfig cfg = conf.pathfindingCost();
        PathfindingCostConfig def = defaultConf.pathfindingCost();

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.pathfinding_algorithm"),
                        PathfindingCostConfig.PathfindingAlgorithm.class, cfg.pathfindingAlgorithm())
                .setDefaultValue(def.pathfindingAlgorithm())
                .setTooltip(Component.translatable("config.roadweaver.pathfinding_algorithm.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.pathfinding_algorithm.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(cfg::setPathfindingAlgorithm)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.a_star_step"), cfg.aStarStep())
                .setDefaultValue(def.aStarStep())
                .setTooltip(Component.translatable("config.roadweaver.a_star_step.tooltip"))
                .setMin(RoadConstants.ASTAR_STEP_MIN).setMax(RoadConstants.ASTAR_STEP_MAX)
                .setSaveConsumer(cfg::setAStarStep)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.a_star_max_steps"), cfg.aStarMaxSteps())
                .setDefaultValue(def.aStarMaxSteps())
                .setTooltip(Component.translatable("config.roadweaver.a_star_max_steps.tooltip"))
                .setMin(100).setMax(RoadConstants.ASTAR_MAX_STEPS_MAX)
                .setSaveConsumer(cfg::setAStarMaxSteps)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ortho_step_cost"), cfg.orthoStepCost())
                .setDefaultValue(def.orthoStepCost())
                .setTooltip(Component.translatable("config.roadweaver.ortho_step_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setOrthoStepCost)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.diag_step_cost"), cfg.diagStepCost())
                .setDefaultValue(def.diagStepCost())
                .setTooltip(Component.translatable("config.roadweaver.diag_step_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setDiagStepCost)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_weight"), cfg.elevationWeight())
                .setDefaultValue(def.elevationWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.biome_weight"), cfg.biomeWeight())
                .setDefaultValue(def.biomeWeight())
                .setTooltip(Component.translatable("config.roadweaver.biome_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setBiomeWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.stability_weight"), cfg.stabilityWeight())
                .setDefaultValue(def.stabilityWeight())
                .setTooltip(Component.translatable("config.roadweaver.stability_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setStabilityWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.water_depth_weight"), cfg.waterDepthWeight())
                .setDefaultValue(def.waterDepthWeight())
                .setTooltip(Component.translatable("config.roadweaver.water_depth_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setWaterDepthWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.near_water_cost"), cfg.nearWaterCost())
                .setDefaultValue(def.nearWaterCost())
                .setTooltip(Component.translatable("config.roadweaver.near_water_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setNearWaterCost)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.water_proximity_cost"), cfg.waterProximityCost())
                .setDefaultValue(def.waterProximityCost())
                .setTooltip(Component.translatable("config.roadweaver.water_proximity_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setWaterProximityCost)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.heuristic_weight"), cfg.heuristicWeight())
                .setDefaultValue(def.heuristicWeight())
                .setTooltip(Component.translatable("config.roadweaver.heuristic_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setHeuristicWeight)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.deviation_weight"), cfg.deviationWeight())
                .setDefaultValue(def.deviationWeight())
                .setTooltip(Component.translatable("config.roadweaver.deviation_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setDeviationWeight)
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

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.deviation_free_radius"), cfg.deviationFreeRadius())
                .setDefaultValue(def.deviationFreeRadius())
                .setTooltip(Component.translatable("config.roadweaver.deviation_free_radius.tooltip"))
                .setMin(0).setMax(2048)
                .setSaveConsumer(cfg::setDeviationFreeRadius)
                .build());
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.deviation_squared"), cfg.deviationSquared())
                .setDefaultValue(def.deviationSquared())
                .setTooltip(Component.translatable("config.roadweaver.deviation_squared.tooltip"))
                .setSaveConsumer(cfg::setDeviationSquared)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.deviation_hard_cap"), cfg.deviationHardCap())
                .setDefaultValue(def.deviationHardCap())
                .setTooltip(Component.translatable("config.roadweaver.deviation_hard_cap.tooltip"))
                .setMin(0).setMax(8192)
                .setSaveConsumer(cfg::setDeviationHardCap)
                .build());

        // ── 0.8.418 奖励惩罚补全：蜿蜒旋钮（对抗「道路过直」）──────────────────
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_factor_floor"), cfg.contourFactorFloor())
                .setDefaultValue(def.contourFactorFloor())
                .setTooltip(Component.translatable("config.roadweaver.contour_factor_floor.tooltip"))
                .setMin(0.02).setMax(1.0)
                .setSaveConsumer(cfg::setContourFactorFloor)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.near_water_decay_radius"), cfg.nearWaterDecayRadius())
                .setDefaultValue(def.nearWaterDecayRadius())
                .setTooltip(Component.translatable("config.roadweaver.near_water_decay_radius.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setNearWaterDecayRadius)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.near_water_decay_rate"), cfg.nearWaterDecayRate())
                .setDefaultValue(def.nearWaterDecayRate())
                .setTooltip(Component.translatable("config.roadweaver.near_water_decay_rate.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setNearWaterDecayRate)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.contour_bonus"), cfg.contourBonus())
                .setDefaultValue(def.contourBonus())
                .setTooltip(Component.translatable("config.roadweaver.contour_bonus.tooltip"))
                .setMin(-100).setMax(0)
                .setSaveConsumer(cfg::setContourBonus)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.accumulated_slope_window"), cfg.accumulatedSlopeWindow())
                .setDefaultValue(def.accumulatedSlopeWindow())
                .setTooltip(Component.translatable("config.roadweaver.accumulated_slope_window.tooltip"))
                .setMin(2).setMax(16)
                .setSaveConsumer(cfg::setAccumulatedSlopeWindow)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.accumulated_slope_weight"), cfg.accumulatedSlopeWeight())
                .setDefaultValue(def.accumulatedSlopeWeight())
                .setTooltip(Component.translatable("config.roadweaver.accumulated_slope_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setAccumulatedSlopeWeight)
                .build());

        // ===== 0.8.409 麻花/重叠判定（禁麻花 + 允许丁字/十字）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.braid_min_direction_dot"), cfg.braidMinDirectionDot())
                .setDefaultValue(def.braidMinDirectionDot())
                .setTooltip(Component.translatable("config.roadweaver.braid_min_direction_dot.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setBraidMinDirectionDot)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.braid_near_parallel_dot"), cfg.braidNearParallelDot())
                .setDefaultValue(def.braidNearParallelDot())
                .setTooltip(Component.translatable("config.roadweaver.braid_near_parallel_dot.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setBraidNearParallelDot)
                .build());
        // 0.8.418 起移除「允许丁字/十字路口」条目：这个开关在全仓库没有任何读取方
        // （RoadSnapService 只用 braidMinDirectionDot / braidNearParallelDot /
        // braidAllowParallelOverlap 三个），留在界面上等于骗玩家去调一个死参数。
        // 字段与语言键保留，以免破坏已有配置文件、也便于将来真接上时一键恢复。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.braid_allow_parallel_overlap"), cfg.braidAllowParallelOverlap())
                .setDefaultValue(def.braidAllowParallelOverlap())
                .setTooltip(Component.translatable("config.roadweaver.braid_allow_parallel_overlap.tooltip"))
                .setSaveConsumer(cfg::setBraidAllowParallelOverlap)
                .build());
    }
}
