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

        // 2026-11-13：旧 A* 系寻路器全部退役（用户拍板「全用最新的，旧的全部删除」）。
        // 算法选择器改为固定说明 —— 只剩 BAND（Finsler + Fast Marching + 弹性带）一种。
        category.addEntry(eb
                .startTextDescription(Component.translatable(
                        "config.roadweaver.pathfinding_algorithm.fixed_band"))
                .setColor(0xFF88FF88)
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
