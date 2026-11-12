/* 文件职责：配置界面「地形剖面」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 地形剖面配置分区。
 *
 * <p>自动剖面的按地形覆盖。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class TerrainProfileSection {

    private TerrainProfileSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(
                Component.translatable("config.roadweaver.category.terrain_profile"));
        TerrainProfileConfig cfg = conf.terrainProfile();
        TerrainProfileConfig def = defaultConf.terrainProfile();

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.mountain_surface_cut"),
                        cfg.mountainSurfaceCutMaxBlocks())
                .setDefaultValue(def.mountainSurfaceCutMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.mountain_surface_cut.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setMountainSurfaceCutMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.mountain_surface_fill"),
                        cfg.mountainSurfaceFillMaxBlocks())
                .setDefaultValue(def.mountainSurfaceFillMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.mountain_surface_fill.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setMountainSurfaceFillMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.plain_surface_cut"),
                        cfg.plainSurfaceCutMaxBlocks())
                .setDefaultValue(def.plainSurfaceCutMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.plain_surface_cut.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setPlainSurfaceCutMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.plain_surface_fill"),
                        cfg.plainSurfaceFillMaxBlocks())
                .setDefaultValue(def.plainSurfaceFillMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.plain_surface_fill.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setPlainSurfaceFillMaxBlocks)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.mountain_detour_multiplier"),
                        cfg.mountainDetourMultiplier())
                .setDefaultValue(def.mountainDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.mountain_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setMountainDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.coast_detour_multiplier"),
                        cfg.coastDetourMultiplier())
                .setDefaultValue(def.coastDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.coast_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setCoastDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.river_detour_multiplier"),
                        cfg.riverDetourMultiplier())
                .setDefaultValue(def.riverDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.river_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setRiverDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.lake_detour_multiplier"),
                        cfg.lakeDetourMultiplier())
                .setDefaultValue(def.lakeDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.lake_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setLakeDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ocean_detour_multiplier"),
                        cfg.oceanDetourMultiplier())
                .setDefaultValue(def.oceanDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.ocean_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setOceanDetourMultiplier)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.ocean_bridge_min_depth"),
                        cfg.oceanBridgeMinDepth())
                .setDefaultValue(def.oceanBridgeMinDepth())
                .setTooltip(Component.translatable("config.roadweaver.ocean_bridge_min_depth.tooltip"))
                .setMin(1).setMax(10)
                .setSaveConsumer(cfg::setOceanBridgeMinDepth)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ocean_undersea_chance"),
                        cfg.oceanUnderseaChance())
                .setDefaultValue(def.oceanUnderseaChance())
                .setTooltip(Component.translatable("config.roadweaver.ocean_undersea_chance.tooltip"))
                .setMin(0.0D).setMax(1.0D)
                .setSaveConsumer(cfg::setOceanUnderseaChance)
                .build());
    }
}
