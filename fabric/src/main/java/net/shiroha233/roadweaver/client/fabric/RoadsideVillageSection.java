/* 文件职责：配置界面「路边村庄」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 路边村庄配置分区。
 *
 * <p>路边村庄的预计算与接驳。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class RoadsideVillageSection {

    private RoadsideVillageSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.roadside_village"));
        RoadsideVillageConfig cfg = conf.roadsideVillage();
        RoadsideVillageConfig def = defaultConf.roadsideVillage();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roadside_village_enabled"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_enabled.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_spawn_chance"), cfg.spawnChance())
                .setDefaultValue(def.spawnChance())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_spawn_chance.tooltip"))
                .setMin(0.0D).setMax(1.0D)
                .setSaveConsumer(cfg::setSpawnChance)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_per_road"), cfg.maxVillagesPerRoad())
                .setDefaultValue(def.maxVillagesPerRoad())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_per_road.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_PER_ROAD_MAX)
                .setSaveConsumer(cfg::setMaxVillagesPerRoad)
                .build());

        // ===== 0.8.343 补齐：路边村庄几何组 12 项（此前整个几何组都不在菜单里）=====
        // 所有 setMin/setMax 一律取自 RoadsideVillageConfig.sanitize() 的钳制区间
        // （不是 setter 区间 —— 两者不同，例如 setMinRoadSegments 只 max(1,v) 而
        //  sanitize 强制 >= 32，若按 setter 写会静默改回 32 造成"输了没用"）。

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_min_road_segments"),
                        cfg.minRoadSegments())
                .setDefaultValue(def.minRoadSegments())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_min_road_segments.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MAX)
                .setSaveConsumer(cfg::setMinRoadSegments)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_window_segments"),
                        cfg.windowSegments())
                .setDefaultValue(def.windowSegments())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_window_segments.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MAX)
                .setSaveConsumer(cfg::setWindowSegments)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_node_count_min"),
                        cfg.targetNodeCountMin())
                .setDefaultValue(def.targetNodeCountMin())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_node_count_min.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MAX)
                .setSaveConsumer(cfg::setTargetNodeCountMin)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_node_count_max"),
                        cfg.targetNodeCountMax())
                .setDefaultValue(def.targetNodeCountMax())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_node_count_max.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MAX)
                .setSaveConsumer(cfg::setTargetNodeCountMax)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_height_diff"),
                        cfg.maxHeightDiff())
                .setDefaultValue(def.maxHeightDiff())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_height_diff.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_HEIGHT_DIFF_MAX)
                .setSaveConsumer(cfg::setMaxHeightDiff)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_local_slope"),
                        cfg.maxLocalSlope())
                .setDefaultValue(def.maxLocalSlope())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_local_slope.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_LOCAL_SLOPE_MAX)
                .setSaveConsumer(cfg::setMaxLocalSlope)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_min_curve_angle"),
                        cfg.minCurveAngle())
                .setDefaultValue(def.minCurveAngle())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_min_curve_angle.tooltip"))
                .setMin(0.0D).setMax(180.0D)
                .setSaveConsumer(cfg::setMinCurveAngle)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_max_curve_angle"),
                        cfg.maxCurveAngle())
                .setDefaultValue(def.maxCurveAngle())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_curve_angle.tooltip"))
                .setMin(0.0D).setMax(180.0D)
                .setSaveConsumer(cfg::setMaxCurveAngle)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_road_buffer_blocks"),
                        cfg.roadBufferBlocks())
                .setDefaultValue(def.roadBufferBlocks())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_road_buffer_blocks.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_ROAD_BUFFER_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_ROAD_BUFFER_MAX)
                .setSaveConsumer(cfg::setRoadBufferBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_building_gap_interval"),
                        cfg.buildingGapInterval())
                .setDefaultValue(def.buildingGapInterval())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_building_gap_interval.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MAX)
                .setSaveConsumer(cfg::setBuildingGapInterval)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_step_height"),
                        cfg.maxStepHeight())
                .setDefaultValue(def.maxStepHeight())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_step_height.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MAX)
                .setSaveConsumer(cfg::setMaxStepHeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_distance_from_center"),
                        cfg.maxDistanceFromCenter())
                .setDefaultValue(def.maxDistanceFromCenter())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_distance_from_center.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MAX_DISTANCE_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_DISTANCE_MAX)
                .setSaveConsumer(cfg::setMaxDistanceFromCenter)
                .build());

    }
}
