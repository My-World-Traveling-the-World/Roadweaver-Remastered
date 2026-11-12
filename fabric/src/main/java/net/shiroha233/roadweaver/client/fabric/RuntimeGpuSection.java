/* 文件职责：配置界面「运行时 GPU 策略」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 运行时 GPU 策略配置分区。
 *
 * <p>游玩中 / 挂机时 GPU 怎么用，与「生成期线程数」语义不同；全部热读，改完即时生效。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class RuntimeGpuSection {

    private RuntimeGpuSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.runtime_gpu"));
        PerformanceConfig cfg = conf.performance();
        PerformanceConfig def = defaultConf.performance();
        PlanningConfig planningCfg = conf.planning();
        PlanningConfig planningDef = defaultConf.planning();

        // 游玩期 GPU 总开关：热读（CoarseHeightBatchSamplers.tryCreateOpenCL 每次建采样器时判断）。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.runtime_coarse_use_cpu_in_gameplay"),
                        cfg.runtimeCoarseUseCpuInGameplay())
                .setDefaultValue(def.runtimeCoarseUseCpuInGameplay())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_use_cpu_in_gameplay.tooltip"))
                .setSaveConsumer(cfg::setRuntimeCoarseUseCpuInGameplay)
                .build());

        // 0.8.344：开地图时让 GPU 粗采样让路（默认开启）。热读，改完即时生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pause_coarse_gpu_while_map_open"),
                        cfg.pauseCoarseGpuWhileMapOpen())
                .setDefaultValue(def.pauseCoarseGpuWhileMapOpen())
                .setTooltip(Component.translatable("config.roadweaver.pause_coarse_gpu_while_map_open.tooltip"))
                .setSaveConsumer(cfg::setPauseCoarseGpuWhileMapOpen)
                .build());

        // 0.8.348：开任意界面（背包/ESC/熔炉/工作台/设置）时让 GPU 粗采样让路（默认开启）。
        // 真机取证：开 GUI 不发服务端交互包，既有的"交互让路"信号不亮；0.8.344 的地图闸
        // 又只覆盖地图，故背包/ESC/熔炉这些界面此前完全没有让路。热读，改完即时生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pause_coarse_gpu_while_gui_open"),
                        cfg.pauseCoarseGpuWhileGuiOpen())
                .setDefaultValue(def.pauseCoarseGpuWhileGuiOpen())
                .setTooltip(Component.translatable("config.roadweaver.pause_coarse_gpu_while_gui_open.tooltip"))
                .setSaveConsumer(cfg::setPauseCoarseGpuWhileGuiOpen)
                .build());

        // 0.8.344：游玩期总线程上限（结构解析 + 粗采样共用）。热读（每 tick 的预算刷新会读到）。
        // 只作用于普通游玩；Chunky/开局不受限（用户："别的时候打满打满没事"）。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.gameplay_thread_cap"),
                        cfg.gameplayThreadCap())
                .setDefaultValue(def.gameplayThreadCap())
                .setTooltip(Component.translatable("config.roadweaver.gameplay_thread_cap.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setGameplayThreadCap)
                .build());

        // 活跃态提交间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_gpu_active_min_interval_ms"),
                        cfg.runtimeCoarseGpuActiveMinIntervalMs())
                .setDefaultValue(def.runtimeCoarseGpuActiveMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_gpu_active_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeCoarseGpuActiveMinIntervalMs)
                .build());

        // 挂机态提交间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_gpu_min_interval_ms"),
                        cfg.runtimeCoarseGpuMinIntervalMs())
                .setDefaultValue(def.runtimeCoarseGpuMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_gpu_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeCoarseGpuMinIntervalMs)
                .build());

        // 挂机态并发上限：sanitizeGpuConcurrencyCap = 0..16。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_max_threads"),
                        cfg.runtimeCoarseMaxThreads())
                .setDefaultValue(def.runtimeCoarseMaxThreads())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_max_threads.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setRuntimeCoarseMaxThreads)
                .build());

        // 压力态并发下限：sanitize = 1..16。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_min_threads"),
                        cfg.runtimeCoarseMinThreads())
                .setDefaultValue(def.runtimeCoarseMinThreads())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_min_threads.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(cfg::setRuntimeCoarseMinThreads)
                .build());

        // 道路完成后的规划间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_road_completion_min_interval_ms"),
                        cfg.runtimeRoadCompletionMinIntervalMs())
                .setDefaultValue(def.runtimeRoadCompletionMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_road_completion_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeRoadCompletionMinIntervalMs)
                .build());
    }
}
