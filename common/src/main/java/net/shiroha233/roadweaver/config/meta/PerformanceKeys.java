/* 文件职责：PerformanceKeys当前配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** 当前生产读取项的元数据；删除未接线控件，避免把显示或日志读取误判为生效。 */
public final class PerformanceKeys {

    private PerformanceKeys() {}

    /** 字段与元数据的双向一致性由回归强制检查。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.integer("sharedWorkerThreads", 0, 0, 256,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "共享工作线程数", "Shared worker threads",
                    "0 表示按硬件自动决定。多条路并行生成时共用这个池",
                    "线程", ""),

            ConfigKey.integer("initialGenerationThreads", 0, 0, 256,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "初始生成线程数", "Initial generation threads",
                    "开局批量生成路网时的线程数，0 表示自动",
                    "线程", ""),

            ConfigKey.integer("maxConcurrentGenerations", 3, 1, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最大并发生成数", "Max concurrent generations",
                    "同时最多生成几条路。太大反而互相抢采样缓存",
                    "条", ""),

            ConfigKey.integer("idleConcurrentGenerations", 1, 0, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "空闲并发生成数", "Idle concurrent generations",
                    "玩家在游玩时后台最多同时生成几条",
                    "条", ""),

            ConfigKey.bool("playerRoutePriorityEnabled", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "玩家路线优先", "Player route priority",
                    "玩家正在走的那条路优先完成，其余让路",
                    ""),

            ConfigKey.string("playerRoutePriorityExemptPauseReasons", "PLAYER_INTERACTION,RECOVERY,PLAYER_TRAVEL",
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "豁免让路的暂停原因", "Exempt pause reasons",
                    "这些原因导致暂停时不受「玩家路线优先」压制",
                    "逗号分隔。对应 RuntimeLoadPolicy 的 PauseReason 名称"),

            ConfigKey.integer("playerRoutePriorityMaxThreads", 0, 0, 256,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "玩家路线优先线程上限", "Player route priority thread cap",
                    "0 表示自动",
                    "线程", ""),

            ConfigKey.integer("playerRoutePriorityHardLimitMinutes", 10, 1, 240,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "玩家路线优先硬上限", "Player route priority hard limit",
                    "压制超过这么多分钟就强制恢复正常调度，防止饿死",
                    "分钟", ""),

            ConfigKey.integer("threadDutyCycle", RoadConstants.DEFAULT_DUTY_CYCLE, 1, 100,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "线程占空比", "Thread duty cycle",
                    "工作线程的忙碌比例，剩下的时间留给主线程与区块生成",
                    "%", ""),

            ConfigKey.bool("idleGenerationEnabled", false,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启用空闲生成", "Idle generation",
                    "玩家游玩时也在后台慢慢生成路网。关闭则只在开局与传送后生成",
                    ""),

            ConfigKey.integer("idleThreadDutyCycle", 20, 1, 100,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "空闲占空比", "Idle duty cycle",
                    "空闲生成时工作线程的忙碌比例",
                    "%", "调高会更吵。这个值直接决定玩家是否能感觉到卡顿"),

            ConfigKey.bool("openclCoarseSamplingEnabled", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "OpenCL 粗采样", "OpenCL coarse sampling",
                    "粗地形采样走 GPU。关闭则全部走 CPU",
                    ""),

            ConfigKey.bool("openclAccurateSamplingEnabled", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.UNWIRED,
                    "OpenCL 精确采样", "OpenCL accurate sampling",
                    "精确高度采样走 GPU",
                    "★ 已实证未接线：全仓 0 处外部引用。AccurateHeightBackends 无条件返回 CPU 后端（该文件里直接 return cpu），所以本键无论怎么改都不会生效"),

            ConfigKey.string("openclDevicePreference", "AUTO",
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "OpenCL 设备偏好", "OpenCL device preference",
                    "优先使用哪块 GPU",
                    "AUTO 表示自选；也可填设备名片段"),

            ConfigKey.integer("openclMinSamples", 1024, 1, 1048576,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "OpenCL 最小批样本", "OpenCL min samples",
                    "样本数低于此值就走 CPU，避免 GPU 调度开销盖过收益",
                    "样本", ""),

            ConfigKey.bool("openclValidateSamples", false,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "OpenCL 结果校验", "OpenCL validate samples",
                    "把 GPU 采样结果与 CPU 逐一比对。只在排查时开，很慢",
                    ""),

            ConfigKey.integer("openclCoarseGpuConcurrencyCap", 0, 0, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "粗采样 GPU 并发上限", "Coarse GPU concurrency cap",
                    "0 表示不限制",
                    "并发", ""),

            ConfigKey.bool("runtimeCoarseUseCpuInGameplay", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "游玩期粗采样用 CPU", "Runtime coarse use CPU in gameplay",
                    "玩家游玩时不用 GPU 做粗采样，避免抢占渲染",
                    ""),

            ConfigKey.integer("runtimeCoarseMaxThreads", 2, 1, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "游玩期粗采样最大线程", "Runtime coarse max threads",
                    "玩家游玩时粗采样最多用几个线程",
                    "线程", ""),

            ConfigKey.integer("runtimeCoarseMinThreads", 1, 1, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "游玩期粗采样最小线程", "Runtime coarse min threads",
                    "玩家游玩时粗采样至少保留几个线程",
                    "线程", ""),

            ConfigKey.integer("runtimeCoarseGpuMinIntervalMs", 1500, 0, 600000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "游玩期 GPU 最小间隔", "Runtime GPU min interval",
                    "两次 GPU 粗采样之间至少隔多久",
                    "毫秒", ""),

            ConfigKey.integer("runtimeCoarseGpuActiveMinIntervalMs", 1500, 0, 600000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "有任务时 GPU 最小间隔", "Runtime GPU active min interval",
                    "存在待生成任务时的 GPU 采样间隔",
                    "毫秒", ""),

            ConfigKey.bool("pauseCoarseGpuWhileMapOpen", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "开地图时暂停 GPU 采样", "Pause GPU while map open",
                    "打开道路地图界面时暂停 GPU 粗采样，把显存留给地图",
                    ""),

            ConfigKey.bool("pauseCoarseGpuWhileGuiOpen", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "开 GUI 时暂停 GPU 采样", "Pause GPU while GUI open",
                    "打开任意界面时暂停 GPU 粗采样",
                    ""),

            ConfigKey.integer("gameplayThreadCap", 2, 0, 256,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "游玩期线程上限", "Gameplay thread cap",
                    "玩家游玩时道路生成可用的线程上限",
                    "线程", ""),

            ConfigKey.integer("coarseHeightBandCount", 4, 1, 64,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "粗采样分带数量", "Coarse height band count",
                    "把一次网格采样切成几段并行提交",
                    "段", "分带数与池宽不匹配会造成「多份工作串行挤一个线程」，河流侧实测过 15 倍膨胀"),

            ConfigKey.integer("runtimeRoadCompletionMinIntervalMs", 1500, 0, 600000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "路由完成最小间隔", "Runtime road completion min interval",
                    "两次触发道路完成回调之间的最小间隔",
                    "毫秒", ""),

            ConfigKey.decimal("gpuBudgetFreeFraction", 0.70, 0.0, 1.0D,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "GPU 预算可用比例", "GPU budget free fraction",
                    "只使用空闲显存的这个比例，剩下的留给渲染",
                    "比例", ""),

            ConfigKey.integer("gpuBudgetHardCapMiB", 4096, 64, 65536,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "GPU 预算硬上限", "GPU budget hard cap",
                    "显存预算的绝对上限",
                    "MiB", "")
    );
}
