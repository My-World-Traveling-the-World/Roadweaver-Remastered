/* 文件职责：配置界面「结构预测」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 结构预测配置分区。
 *
 * <p>预测结构与避让相关的开关。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class StructurePredictionSection {

    private StructurePredictionSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.structure_filters"));
        StructurePredictionConfig cfg = conf.structurePrediction();
        StructurePredictionConfig def = defaultConf.structurePrediction();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.enable_prediction"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.enable_prediction.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.radius_chunks"), cfg.predictRadiusChunks())
                .setDefaultValue(def.predictRadiusChunks())
                .setTooltip(Component.translatable("config.roadweaver.radius_chunks.tooltip"))
                .setMin(1).setMax(4096)
                .setSaveConsumer(cfg::setPredictRadiusChunks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.biome_prefilter"), cfg.biomePrefilter())
                .setDefaultValue(def.biomePrefilter())
                .setTooltip(Component.translatable("config.roadweaver.biome_prefilter.tooltip"))
                .setSaveConsumer(cfg::setBiomePrefilter)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.structure_avoidance_enabled"), cfg.structureAvoidanceEnabled())
                .setDefaultValue(def.structureAvoidanceEnabled())
                .setTooltip(Component.translatable("config.roadweaver.structure_avoidance_enabled.tooltip"))
                .setSaveConsumer(cfg::setStructureAvoidanceEnabled)
                .build());

        // 0.8.343 补齐：villageRoadOffset（StructurePredictionConfig）。
        // 该键 sanitize() 只设了下界（>=0），无上界；参考同族键
        // other_structure_road_offset 在菜单中的既有口径（0..64），此处同样取 0..64。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.village_road_offset"), cfg.villageRoadOffset())
                .setDefaultValue(def.villageRoadOffset())
                .setTooltip(Component.translatable("config.roadweaver.village_road_offset.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(cfg::setVillageRoadOffset)
                .build());

        category.addEntry(new OpenStructureSelectionEntry());
    }
}
