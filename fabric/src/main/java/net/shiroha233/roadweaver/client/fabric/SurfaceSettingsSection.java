/* 文件职责：配置界面「地表设置」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 地表设置配置分区。
 *
 * <p>路面与路肩的地表规则。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class SurfaceSettingsSection {

    private SurfaceSettingsSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.gen_surface"));
        RoadAppearanceConfig cfg = conf.roadAppearance();
        RoadAppearanceConfig def = defaultConf.roadAppearance();

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.averaging_radius"), cfg.averagingRadius())
                .setDefaultValue(def.averagingRadius())
                .setTooltip(Component.translatable("config.roadweaver.averaging_radius.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(cfg::setAveragingRadius)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_slope_step_per_two_segments"), cfg.maxSlopeStepPerTwoSegments())
                .setDefaultValue(def.maxSlopeStepPerTwoSegments())
                .setTooltip(Component.translatable("config.roadweaver.max_slope_step_per_two_segments.tooltip"))
                .setMin(0).setMax(8)
                .setSaveConsumer(cfg::setMaxSlopeStepPerTwoSegments)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.slope_limit_enabled"), cfg.slopeLimitEnabled())
                .setDefaultValue(def.slopeLimitEnabled())
                .setTooltip(Component.translatable("config.roadweaver.slope_limit_enabled.tooltip"))
                .setSaveConsumer(cfg::setSlopeLimitEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.prevent_trees_on_road"), cfg.preventTreesOnRoad())
                .setDefaultValue(def.preventTreesOnRoad())
                .setTooltip(Component.translatable("config.roadweaver.prevent_trees_on_road.tooltip"))
                .setSaveConsumer(cfg::setPreventTreesOnRoad)
                .build());
    }
}
