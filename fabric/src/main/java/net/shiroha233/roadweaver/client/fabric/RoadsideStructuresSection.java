/* 文件职责：配置界面「路边结构」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 路边结构配置分区。
 *
 * <p>路边结构的开关与密度。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class RoadsideStructuresSection {

    private RoadsideStructuresSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.roadside_structures"));
        RoadsideStructureConfig cfg = conf.roadsideStructure();
        RoadsideStructureConfig def = defaultConf.roadsideStructure();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roadside_structures_enabled"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.roadside_structures_enabled.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_structures_per_road"), cfg.maxStructuresPerRoad())
                .setDefaultValue(def.maxStructuresPerRoad())
                .setTooltip(Component.translatable("config.roadweaver.max_structures_per_road.tooltip"))
                .setMin(0).setMax(20)
                .setSaveConsumer(cfg::setMaxStructuresPerRoad)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.small_structure_offset"), cfg.smallStructureOffset())
                .setDefaultValue(def.smallStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.small_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setSmallStructureOffset)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.medium_structure_offset"), cfg.mediumStructureOffset())
                .setDefaultValue(def.mediumStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.medium_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setMediumStructureOffset)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.large_structure_offset"), cfg.largeStructureOffset())
                .setDefaultValue(def.largeStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.large_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setLargeStructureOffset)
                .build());
    }
}
