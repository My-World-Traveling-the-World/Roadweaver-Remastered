/* 文件职责：配置界面「道路生成」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 道路生成配置分区。
 *
 * <p>坡度上限、桥隧阈值等生成期参数。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class RoadGenerationSection {

    private RoadGenerationSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.road_generation"));
        RoadAppearanceConfig cfg = conf.roadAppearance();
        RoadAppearanceConfig def = defaultConf.roadAppearance();
        StructurePredictionConfig structureCfg = conf.structurePrediction();
        StructurePredictionConfig structureDef = defaultConf.structurePrediction();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roads_enabled"), cfg.roadsEnabled())
                .setDefaultValue(def.roadsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.roads_enabled.tooltip"))
                .setSaveConsumer(cfg::setRoadsEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.allow_artificial"), cfg.allowArtificial())
                .setDefaultValue(def.allowArtificial())
                .setTooltip(Component.translatable("config.roadweaver.allow_artificial.tooltip"))
                .setSaveConsumer(cfg::setAllowArtificial)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.allow_natural"), cfg.allowNatural())
                .setDefaultValue(def.allowNatural())
                .setTooltip(Component.translatable("config.roadweaver.allow_natural.tooltip"))
                .setSaveConsumer(cfg::setAllowNatural)
                .build());


        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.spawn_cabin_enabled"), cfg.spawnCabinEnabled())
                .setDefaultValue(def.spawnCabinEnabled())
                .setTooltip(Component.translatable("config.roadweaver.spawn_cabin_enabled.tooltip"))
                .setSaveConsumer(cfg::setSpawnCabinEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.road_width"), cfg.roadWidth())
                .setDefaultValue(def.roadWidth())
                .setTooltip(Component.translatable("config.roadweaver.road_width.tooltip"))
                .setMin(0).setMax(15)
                .setSaveConsumer(cfg::setRoadWidth)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.other_structure_road_offset"), structureCfg.otherStructureRoadOffset())
                .setDefaultValue(structureDef.otherStructureRoadOffset())
                .setTooltip(Component.translatable("config.roadweaver.other_structure_road_offset.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(structureCfg::setOtherStructureRoadOffset)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.road_signs_enabled"), cfg.roadSignsEnabled())
                .setDefaultValue(def.roadSignsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.road_signs_enabled.tooltip"))
                .setSaveConsumer(cfg::setRoadSignsEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.lamp_interval"), cfg.lampInterval())
                .setDefaultValue(def.lampInterval())
                .setTooltip(Component.translatable("config.roadweaver.lamp_interval.tooltip"))
                .setMin(1).setMax(2048)
                .setSaveConsumer(cfg::setLampInterval)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.road_clear_height"), cfg.roadClearHeight())
                .setDefaultValue(def.roadClearHeight())
                .setTooltip(Component.translatable("config.roadweaver.road_clear_height.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(cfg::setRoadClearHeight)
                .build());

        category.addEntry(new OpenPresetEditorEntry());
    }
}
