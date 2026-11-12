/* 文件职责：配置界面「客户端」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 客户端配置分区。
 *
 * <p>纯客户端显示项。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class ClientSection {

    private ClientSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.test"));
        ClientConfig cfg = conf.client();
        ClientConfig def = defaultConf.client();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.loading_tips_enabled"), cfg.loadingTipsEnabled())
                .setDefaultValue(def.loadingTipsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.loading_tips_enabled.tooltip"))
                .setSaveConsumer(cfg::setLoadingTipsEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.loading_progress_enabled"), cfg.loadingProgressEnabled())
                .setDefaultValue(def.loadingProgressEnabled())
                .setTooltip(Component.translatable("config.roadweaver.loading_progress_enabled.tooltip"))
                .setSaveConsumer(cfg::setLoadingProgressEnabled)
                .build());
    }
}
