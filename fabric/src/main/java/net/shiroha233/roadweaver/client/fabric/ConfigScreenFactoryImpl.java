/* 文件职责：构建 Fabric 配置界面。 */
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
 * Fabric 平台配置屏幕工厂实现
 */
public class ConfigScreenFactoryImpl {

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.roadweaver.title"));

        PresetService.reload();
        ModConfig conf = ConfigService.get();
        ModConfig defaultConf = new ModConfig();
        builder.setSavingRunnable(ConfigService::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        StructurePredictionSection.build(builder, eb, conf, defaultConf);
        
        PlanningSection.build(builder, eb, conf, defaultConf);
        TerrainProfileSection.build(builder, eb, conf, defaultConf);
        
        
        RoadGenerationSection.build(builder, eb, conf, defaultConf);
        
        SurfaceSettingsSection.build(builder, eb, conf, defaultConf);
        
        BridgeSection.build(builder, eb, conf, defaultConf);
        
        RoadsideStructuresSection.build(builder, eb, conf, defaultConf);

        RoadsideVillageSection.build(builder, eb, conf, defaultConf);
        
        PerformanceSection.build(builder, eb, conf, defaultConf);
        
        RuntimeGpuSection.build(builder, eb, conf, defaultConf);
        
        PathfindingCostsSection.build(builder, eb, conf, defaultConf);
        
        ClientSection.build(builder, eb, conf, defaultConf);

        return builder.build();
    }

}
