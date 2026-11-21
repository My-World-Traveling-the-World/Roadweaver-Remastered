/* 文件职责：配置界面「性能」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 性能配置分区。
 *
 * <p>线程数与生成期并发。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class PerformanceSection {

    private PerformanceSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.gen_performance"));
        PerformanceConfig cfg = conf.performance();
        PerformanceConfig def = defaultConf.performance();
        PlanningConfig planning = conf.planning();
        PlanningConfig planningDef = defaultConf.planning();

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.terrain_sampling_mode"),
                        TerrainSamplingMode.class, planning.terrainSamplingMode())
                .setDefaultValue(planningDef.terrainSamplingMode())
                .setTooltip(Component.translatable("config.roadweaver.terrain_sampling_mode.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.terrain_sampling_mode.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(planning::setTerrainSamplingMode)
                .build());

        // 0.8.343：三项上限从 128 改为与实际生效值一致。
        // initialGenerationThreads 实际封顶 RoadConstants.COMPUTE_THREADS_MAX；
        // sharedWorkerThreads / chunkyRoadThreadCap 的运行时上限是
        // ThreadPoolManager.maxRuntimeRoadThreads() = max(1, min(12, 核数-2))。
        // 原来写 128 会让玩家填一个永远达不到的值、体感"写了没反应"。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.initial_generation_threads"), cfg.initialGenerationThreads())
                .setDefaultValue(def.initialGenerationThreads())
                .setTooltip(Component.translatable("config.roadweaver.initial_generation_threads.tooltip"))
                .setMin(0).setMax(RoadConstants.COMPUTE_THREADS_MAX)
                .setSaveConsumer(cfg::setInitialGenerationThreads)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.shared_worker_threads"), cfg.sharedWorkerThreads())
                .setDefaultValue(def.sharedWorkerThreads())
                .setTooltip(Component.translatable("config.roadweaver.shared_worker_threads.tooltip"))
                .setMin(0).setMax(RoadConstants.COMPUTE_THREADS_MAX)
                .setSaveConsumer(cfg::setSharedWorkerThreads)
                .build());

        // 0.8.343：chunkyRoadThreadCap 是**死键**。0.9.027 曾一度把它从死键名单里划掉，
        // 理由是「被 ThreadPoolManager:113 消费」—— 那条结论是错的：:113 只是
        // LOGGER.info("… chunkyRoadCap={}", …) 的**实参列表**，值只被打进启动那行日志，
        // 没有任何分支拿它去限制 Chunky 期间的线程（chunkySupplyThreads 的增减也不读它）。
        // 全仓读取点只有：PerformanceConfig 的字段/sanitize/copy/存取器、本界面、
        // PerformanceSchedulingConfigTest。tooltip 已注明不生效。

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.idle_generation_enabled"), cfg.idleGenerationEnabled())
                .setDefaultValue(def.idleGenerationEnabled())
                .setTooltip(Component.translatable("config.roadweaver.idle_generation_enabled.tooltip"))
                .setSaveConsumer(cfg::setIdleGenerationEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_concurrent_generations"), cfg.maxConcurrentGenerations())
                .setDefaultValue(def.maxConcurrentGenerations())
                .setTooltip(Component.translatable("config.roadweaver.max_concurrent_generations.tooltip"))
                .setMin(1).setMax(128)
                .setSaveConsumer(cfg::setMaxConcurrentGenerations)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.idle_concurrent_generations"), cfg.idleConcurrentGenerations())
                .setDefaultValue(def.idleConcurrentGenerations())
                .setTooltip(Component.translatable("config.roadweaver.idle_concurrent_generations.tooltip"))
                .setMin(1).setMax(128)
                .setSaveConsumer(cfg::setIdleConcurrentGenerations)
                .build());

        category.addEntry(eb
                .startIntSlider(Component.translatable("config.roadweaver.thread_duty_cycle"), cfg.threadDutyCycle(), 1, 100)
                .setDefaultValue(def.threadDutyCycle())
                .setTooltip(Component.translatable("config.roadweaver.thread_duty_cycle.tooltip"))
                .setSaveConsumer(cfg::setThreadDutyCycle)
                .build());

        category.addEntry(eb
                .startIntSlider(Component.translatable("config.roadweaver.idle_thread_duty_cycle"),
                        cfg.idleThreadDutyCycle(), 1, 100)
                .setDefaultValue(def.idleThreadDutyCycle())
                .setTooltip(Component.translatable("config.roadweaver.idle_thread_duty_cycle.tooltip"))
                .setSaveConsumer(cfg::setIdleThreadDutyCycle)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_coarse_sampling_enabled"), cfg.openclCoarseSamplingEnabled())
                .setDefaultValue(def.openclCoarseSamplingEnabled())
                .setTooltip(Component.translatable("config.roadweaver.opencl_coarse_sampling_enabled.tooltip"))
                .setSaveConsumer(cfg::setOpenclCoarseSamplingEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.opencl_coarse_gpu_concurrency_cap"),
                        cfg.openclCoarseGpuConcurrencyCap())
                .setDefaultValue(def.openclCoarseGpuConcurrencyCap())
                .setTooltip(Component.translatable("config.roadweaver.opencl_coarse_gpu_concurrency_cap.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setOpenclCoarseGpuConcurrencyCap)
                .build());

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.opencl_device_preference"),
                        OpenCLDevicePreference.class, OpenCLDevicePreference.valueOf(cfg.openclDevicePreference()))
                .setDefaultValue(OpenCLDevicePreference.valueOf(def.openclDevicePreference()))
                .setTooltip(Component.translatable("config.roadweaver.opencl_device_preference.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.opencl_device_preference.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(v -> cfg.setOpenclDevicePreference(v.name()))
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.opencl_min_samples"), cfg.openclMinSamples())
                .setDefaultValue(def.openclMinSamples())
                .setTooltip(Component.translatable("config.roadweaver.opencl_min_samples.tooltip"))
                .setMin(0).setMax(RoadConstants.COARSE_REGION_MAX_SAMPLES)
                .setSaveConsumer(cfg::setOpenclMinSamples)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_validate_samples"), cfg.openclValidateSamples())
                .setDefaultValue(def.openclValidateSamples())
                .setTooltip(Component.translatable("config.roadweaver.opencl_validate_samples.tooltip"))
                .setSaveConsumer(cfg::setOpenclValidateSamples)
                .build());

        // 0.8.343：显存预算两项。热读（每批采样前读），但**可观测效果很弱** ——
        // 见 OpenCLCoarseHeightBatchSampler 里 0.8.342 审计修正的注释：
        // 当前整合包参数下（nodes=677、tileSizeChunks=32）单批 scratch 仅约 96.9 MiB，
        // 而闸门额度默认约 4096 MiB，需显存占用 >98.3% 才触发。
        // 因此 tooltip 明确写"需小到 128~256 量级才会真正限流"，避免玩家以为没生效。
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.gpu_budget_free_fraction"),
                        cfg.gpuBudgetFreeFraction())
                .setDefaultValue(def.gpuBudgetFreeFraction())
                .setTooltip(Component.translatable("config.roadweaver.gpu_budget_free_fraction.tooltip"))
                .setMin(0.05D).setMax(1.0D)
                .setSaveConsumer(cfg::setGpuBudgetFreeFraction)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.gpu_budget_hard_cap_mib"),
                        cfg.gpuBudgetHardCapMiB())
                .setDefaultValue(def.gpuBudgetHardCapMiB())
                .setTooltip(Component.translatable("config.roadweaver.gpu_budget_hard_cap_mib.tooltip"))
                .setMin(0).setMax(65_536)
                .setSaveConsumer(cfg::setGpuBudgetHardCapMiB)
                .build());

        // 0.8.343：死键标注。该开关全项目**无任何读取点**
        // （grep openclAccurateSamplingEnabled：只有 ConfigService 迁移里赋值 + 本菜单），
        // 因为精采样自 0.8.340 起就是刻意 CPU-only（AccurateHeightBackends.create 无条件 return cpu）。
        // 保留条目以免玩家以为选项丢失，但 tooltip 必须写明当前版本不生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_accurate_sampling_enabled"),
                        cfg.openclAccurateSamplingEnabled())
                .setDefaultValue(def.openclAccurateSamplingEnabled())
                .setTooltip(Component.translatable("config.roadweaver.opencl_accurate_sampling_enabled.tooltip"))
                .setSaveConsumer(cfg::setOpenclAccurateSamplingEnabled)
                .build());
    }
}
