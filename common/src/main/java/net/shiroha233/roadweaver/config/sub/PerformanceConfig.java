/* 文件职责：保存线程调度与 OpenCL 地形采样性能配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 性能与线程配置
 */
public final class PerformanceConfig implements SubConfig {
    /** 零值交给硬件策略选择普通工作线程上限，避免把自动预算误作禁用。 */
    private int sharedWorkerThreads = 0;
    /** 零值使用硬件自适应开局预算，避免写死某台机器的并行度。 */
    private int initialGenerationThreads = 0;
    /** 限制开局结束后的普通道路在途任务数，控制后台工作积压。 */
    private int maxConcurrentGenerations = 3;
    /** 单独限制空闲低优先级任务，避免其占满普通道路通道。 */
    private int idleConcurrentGenerations = 1;

    // ═══════════ 手绘路线「施工优先」配置（★ 2026-09-26 修 W-2：收回配置层）═══════════
    //
    // 背景：0.8.397 直接在 RoadGenerationService.tick 里一刀切地豁免了
    // RuntimeLoadPolicy 的暂停（连 CHUNKY 也豁免）—— 触碰红线 6
    // 「与 Chunky/Streams/Voxy 的资源协调只走配置层」。
    // 现在把豁免范围与并发上限全部做成**配置**，代码只做查表判定。

    /** 是否启用手绘路线施工优先（关掉则完全退回让路规则）。 */
    private boolean playerRoutePriorityEnabled = true;

    /**
     * 优先模式**豁免哪些暂停原因**（逗号分隔的 {@code PauseKind} 名字）。
     *
     * <p>可选值：{@code PLAYER_INTERACTION}（玩家开着界面）、
     * {@code RECOVERY}（前台恢复冷却）、{@code PLAYER_TRAVEL}（玩家跑图）、
     * {@code SYSTEM_PRESSURE}（Tick/FPS/内存压力）、{@code CHUNKY}（Chunky 独占期）。</p>
     *
     * <p>默认 {@code PLAYER_INTERACTION,RECOVERY,PLAYER_TRAVEL} ——
     * 前两个是 98 秒卡顿日志的**真实原因**；{@code PLAYER_TRAVEL} 按用户要求加入
     * （「手动道路优先级最高」，跑图时施工不该停）。</p>
     *
     * <p>⚠️ <b>默认不含 {@code CHUNKY}</b> —— 那是 0.8.372 用真机取证换来的
     * 「Chunky 独占期让路」约定（12 lanes 会抢空 14 个 c2me-worker）。
     * 且用户明确「Chunky 的时候玩家是不会进行手动规划路的」，
     * 所以不豁免它既符合红线、也不影响体感。</p>
     *
     * <p>⚠️ <b>默认不含 {@code SYSTEM_PRESSURE}</b> ——
     * 那是真实的 Tick/FPS/内存压力，硬扛会伤体验。</p>
     */
    private String playerRoutePriorityExemptPauseReasons =
            "PLAYER_INTERACTION,RECOVERY,PLAYER_TRAVEL";

    /**
     * 优先模式的并发上限；{@code 0} = 自动（沿用 {@code maxConcurrentGenerations}）。
     *
     * <p>★ 修 W-5：原先 0.8.397 写死 {@code max(2, maxRuntimeRoadThreads())}
     * （通常 12），既写死挡位（触碰红线 2 的精神），又因
     * {@code RUNTIME_SLOTS} 被 {@code maxConcurrentGenerations} 封顶而**实际落空**。
     * 现在改成配置项，并让它**真正生效**（同步抬高 slot 水位）。</p>
     */
    private int playerRoutePriorityMaxThreads = 0;

    /**
     * 优先模式的**安全上限（分钟）**；{@code 0} = 不限。
     *
     * <p>用户口径：「建完为止 + 安全上限10分钟」。做成配置便于按需调整
     * （真机觉得 10 分钟太短时不用改代码）。</p>
     */
    private int playerRoutePriorityHardLimitMinutes = 10;

    private int threadDutyCycle = RoadConstants.DEFAULT_DUTY_CYCLE;
    private boolean idleGenerationEnabled = false;
    private int idleThreadDutyCycle = 20;
    /** 粗采样GPU入口仍受运行压力预算约束；不要与已废弃的精确GPU开关混淆。 */
    private boolean openclCoarseSamplingEnabled = true;
    private String openclDevicePreference = "AUTO";
    private int openclMinSamples = 1024;
    private boolean openclValidateSamples = false;
    /** 游玩期粗采样GPU请求上限；零值由运行策略决定。 */
    private int openclCoarseGpuConcurrencyCap = 0;
    /** 普通游玩默认用CPU粗采样，避免GPU突发影响前台；开局和Chunky走各自预算。 */
    private boolean runtimeCoarseUseCpuInGameplay = true;
    /**
     * 四态策略 · 挂机(idle)态 GPU 粗采样并发上限;0 = 沿用硬件/自动推导。
     * 仅在普通游玩且玩家长时间静止(非开局、非 Chunky)时生效。
     */
    private int runtimeCoarseMaxThreads = 2;
    /** 四态策略 · 压力态(前台保护/Chunky 运行)GPU 粗采样并发下限。 */
    private int runtimeCoarseMinThreads = 1;
    /** 四态策略 · 挂机态两次 GPU 粗采样提交之间的最小间隔;0 = 不限。 */
    private int runtimeCoarseGpuMinIntervalMs = 1500;
    /**
     * 0.8.341：游玩活跃态（玩家移动/交互/巡航）两次 GPU 粗采样提交之间的最小间隔;0 = 不限。
     *
     * <p>背景：0.8.341 起游玩期也开放 GPU 粗采样（{@code runtimeCoarseUseCpuInGameplay=false}）。
     * 原实现**只有挂机态**有提交间隔闸（{@code acquire} 里的判断是 {@code isGameplayIdle()}），
     * 活跃态没有 —— 一旦开放游玩期 GPU，活跃态就变成"背靠背连续提交"，
     * 反而可能比挂机态更抖，违背"分批次慢慢采样"的初衷。本键补上该闸。</p>
     *
     * <p>与挂机态**分开设键**：活跃态玩家正在操作、对延迟更敏感，通常希望比挂机态更保守，
     * 因此允许单独调大（例如 3000）而不影响挂机态。</p>
     */
    private int runtimeCoarseGpuActiveMinIntervalMs = 1500;
    /**
     * 0.8.344：RoadWeaver 地图界面打开时是否暂停 GPU 粗采样（默认 **true** = 让路）。
     *
     * <p>真机取证（20260911）：游玩活跃态粗采样连续占满 GPU 达 25 分钟
     * （{@code nvidia-smi} utilization 持续 100%，活跃态日志 148 条），
     * 此时玩家按 H 打开地图会**明显卡顿** —— 地图要渲染 Xaero 地形，与粗采样抢同一块 GPU。</p>
     *
     * <p>仅靠 {@link #runtimeCoarseGpuActiveMinIntervalMs} 挡不住：它限制的是"两次提交的间隔"，
     * 而一个 187 万样本的区域要分约 100 批，即使每批隔 1.5 秒也会有约 150 秒的持续占用。</p>
     *
     * <p>设为 false 可关闭让路（地图与采样同时抢 GPU，卡顿会回来）。</p>
     */
    private boolean pauseCoarseGpuWhileMapOpen = true;
    /**
     * 0.8.348：**任意客户端界面**（背包 / ESC / 熔炉 / 工作台 / 设置 …）打开时是否暂停 GPU 粗采样。
     * 默认 **true** = 让路。
     *
     * <p>真机取证（20260912 夜）：玩家开背包/ESC/改设置时卡顿 —— {@code 23:34:05} 首次
     * {@code Saving and pausing} 后，&gt;3 秒日志静默由 <b>4 次</b> 暴增到 <b>15+ 次</b>。</p>
     *
     * <p>根因：开 GUI 是纯客户端行为、不发服务端交互包，{@code INTERACTION_ACTIVE} 与
     * {@code RuntimePlayerActivity} 都不亮，而 {@link #pauseCoarseGpuWhileMapOpen} 的闸
     * 只由 {@code RoadMapScreen} 设置，覆盖不到背包/ESC/熔炉/工作台。</p>
     *
     * <p><b>0.8.351：聊天栏也纳入让路范围。</b>0.8.348 曾刻意排除聊天栏
     * （理由是"打字时后台继续跑"），真机证伪 —— 玩家开聊天后仍卡。</p>
     *
     * <p>设为 false 可只保留地图闸（旧行为）。</p>
     */
    private boolean pauseCoarseGpuWhileGuiOpen = true;
    /**
     * 0.8.344：**游玩期**总线程上限（结构解析 + 粗采样共用）。默认 2。
     *
     * <p>真机取证（20260911）：游玩期 {@code RW-NativeStructure} 8 线程累计 **1735s** CPU
     * （占 RoadWeaver 总量 ~70%），叠加 Render/c2me/Server 后把 CPU 打满 → 卡顿。
     * 用户要求「别让他跑满」，且明确「别的时候打满打满没事」。</p>
     *
     * <p>因此本键**只作用于普通游玩**路径：Chunky 运行/预热、开局生成都会在更早的分支返回，
     * 不经此处 —— 即"游玩时限流、挂机与 Chunky 期放开"。</p>
     *
     * <p>默认 2 与旧行为一致（既是既有语义，也避免压到 1 导致"1 个 worker 等嵌套子任务"的自锁）。
     * 调到 1 更安静但更慢；0 = 不限（恢复旧行为）。</p>
     */
    private int gameplayThreadCap = 2;
    /**
     * 0.8.350：粗采样高度批次**分带数**（S1-A）。默认 8；1 = 不分带（旧行为）。
     *
     * <p>真机取证（20260913 手动规划道路时开背包卡顿）：
     * {@code coarse terrain load timing} 实测单次采样的
     * {@code heightBatchWorkerMs} 高达 **278006ms**（占 {@code sampleWorkerMs} 的 96.2%），
     * 而 {@code sampled=31 / tiles=35} 说明约 89% 瓦片在**现场重算**。
     * 换算单瓦片高度采样约 **8.97 秒**，而让路闸（{@code beforeBackgroundUnit}）
     * 只在**瓦片之间**检查 —— 玩家开界面若正落在这 9 秒内，闸门根本没有机会反应。</p>
     *
     * <p>本键把一次高度采样按 Z 轴切成 N 条横带，**每条带之间**检查一次让路闸，
     * 从而把"闸门响应间隔"从约 9 秒压到约 9/N 秒。</p>
     *
     * <p><b>正确性</b>：内核 {@code roadweaver_coarse_height_sample} 逐样本完全独立
     * （{@code index} 唯一决定 x/z，{@code scratch_base = index * node_count}），
     * 且 {@code align_to_cpu_grid} 在 step 对齐网格上精确无漂移，
     * 因此切横带后**逐样本结果与不分带完全一致**（等价于把一次大请求拆成 N 次小请求）。</p>
     *
     * <p><b>代价</b>：每条带各提交一次 kernel 并各占一份 scratch 缓冲，
     * 总耗时上升（带数越多越明显）。默认 4 = 响应间隔约 2.2 秒；
     * 想更灵敏可调到 8（响应约 1.1 秒，但开销更大），想恢复旧行为设 1。</p>
     *
     * <p><b>0.8.352：开局预热与 Chunky 预热不分带。</b>分带的唯一目的是"让路给玩家操作"，
     * 而让路闸对这两阶段本就是直接 return（它们不受游玩限制），故分带在那里
     * **只付 kernel 提交开销、零收益**。真机实测游玩期分带后单瓦片采样由约 9.6 秒
     * 升到约 30 秒（每条带重走一遍 prepare/kernel 启动/readInts 同步回读），
     * 这个代价换"玩家操作不卡"值得，但开局不需要。</p>
     *
     * <p><b>0.8.353：默认值 8 → 4（用户决定）。</b>用户原话「8改成4呢，是不是会好很多。
     * 同时也不是那么卡顿严重」—— 用略长的响应间隔换显著更小的总开销。
     * 用户已明确「游玩的后台规划无所谓的」，故该交换成立。</p>
     */
    private int coarseHeightBandCount = 4;
    /** 四态策略 · 一条道路完成后,下一次低优先级道路/区域规划的最小间隔;0 = 不限。 */
    private int runtimeRoadCompletionMinIntervalMs = 1500;
    /**
     * 0.8.340：GPU 显存预算 —— 允许使用的「空闲显存占比」。默认 0.70。
     *
     * <p>空闲量优先来自 Windows PDH 实时读数（总显存 - 全卡已用，含游戏本体与其他 mod）；
     * 读不到时退化为「总显存 x 本值」。因此小显存卡上游戏本体占用越大，留给
     * RoadWeaver 的额度自动越小，避免"采样突然吃满显存导致卡顿"。</p>
     */
    private double gpuBudgetFreeFraction = 0.70;
    /**
     * 0.8.340：GPU 显存预算的绝对上限（MiB）。默认 4096（4GB）。
     *
     * <p>大显存卡（16/24GB）按比例算出的额度可能很大，单批一次申请过多显存同样会
     * 造成突发卡顿；本上限把单批预算封顶。设为 0 表示不封顶。</p>
     */
    private int gpuBudgetHardCapMiB = 4096;

    @Override
    public void sanitize() {
        sharedWorkerThreads = sanitizeOptionalThreadCap(sharedWorkerThreads);
        initialGenerationThreads = sanitizeInitialGenerationThreads(initialGenerationThreads);
        maxConcurrentGenerations = Math.max(1, Math.min(128, maxConcurrentGenerations));
        idleConcurrentGenerations = Math.max(1, Math.min(128, idleConcurrentGenerations));
        // ★ 手绘优先配置（2026-09-26）
        if (playerRoutePriorityExemptPauseReasons == null) {
            playerRoutePriorityExemptPauseReasons = "PLAYER_INTERACTION,RECOVERY,PLAYER_TRAVEL";
        }
        // 0 = auto；非 0 时夹到合理范围（不写死上限，但防手滑填 9999）
        playerRoutePriorityMaxThreads = (playerRoutePriorityMaxThreads <= 0)
                ? 0 : Math.max(1, Math.min(128, playerRoutePriorityMaxThreads));
        // 0 = 不限；负值当 0
        playerRoutePriorityHardLimitMinutes = Math.max(0, playerRoutePriorityHardLimitMinutes);
        if (threadDutyCycle < RoadConstants.DUTY_CYCLE_MIN || threadDutyCycle > RoadConstants.DUTY_CYCLE_MAX) {
            threadDutyCycle = RoadConstants.DEFAULT_DUTY_CYCLE;
        }
        if (idleThreadDutyCycle < RoadConstants.DUTY_CYCLE_MIN || idleThreadDutyCycle > RoadConstants.DUTY_CYCLE_MAX) {
            idleThreadDutyCycle = 20;
        }
        openclDevicePreference = normalizeOpenCLDevicePreference(openclDevicePreference);
        openclMinSamples = Math.max(0, Math.min(RoadConstants.COARSE_REGION_MAX_SAMPLES, openclMinSamples));
        openclCoarseGpuConcurrencyCap = sanitizeGpuConcurrencyCap(openclCoarseGpuConcurrencyCap);
        runtimeCoarseMaxThreads = sanitizeGpuConcurrencyCap(runtimeCoarseMaxThreads);
        runtimeCoarseMinThreads = Math.max(1, Math.min(16, runtimeCoarseMinThreads));
        runtimeCoarseGpuMinIntervalMs = Math.max(0, Math.min(60_000, runtimeCoarseGpuMinIntervalMs));
        runtimeCoarseGpuActiveMinIntervalMs = Math.max(0, Math.min(60_000, runtimeCoarseGpuActiveMinIntervalMs));
        gameplayThreadCap = Math.max(0, Math.min(16, gameplayThreadCap));
        coarseHeightBandCount = Math.max(1, Math.min(64, coarseHeightBandCount));
        runtimeRoadCompletionMinIntervalMs = Math.max(0, Math.min(60_000, runtimeRoadCompletionMinIntervalMs));
        gpuBudgetFreeFraction = sanitizeGpuBudgetFraction(gpuBudgetFreeFraction);
        gpuBudgetHardCapMiB = Math.max(0, Math.min(65536, gpuBudgetHardCapMiB));
    }

    @Override
    public PerformanceConfig snapshot() {
        PerformanceConfig copy = new PerformanceConfig();
        copy.sharedWorkerThreads = this.sharedWorkerThreads;
        copy.initialGenerationThreads = this.initialGenerationThreads;
        copy.maxConcurrentGenerations = this.maxConcurrentGenerations;
        copy.idleConcurrentGenerations = this.idleConcurrentGenerations;
        // ★ 手绘优先配置（2026-09-26）
        copy.playerRoutePriorityEnabled = this.playerRoutePriorityEnabled;
        copy.playerRoutePriorityExemptPauseReasons = this.playerRoutePriorityExemptPauseReasons;
        copy.playerRoutePriorityMaxThreads = this.playerRoutePriorityMaxThreads;
        copy.playerRoutePriorityHardLimitMinutes = this.playerRoutePriorityHardLimitMinutes;
        copy.threadDutyCycle = this.threadDutyCycle;
        copy.idleGenerationEnabled = this.idleGenerationEnabled;
        copy.idleThreadDutyCycle = this.idleThreadDutyCycle;
        copy.openclCoarseSamplingEnabled = this.openclCoarseSamplingEnabled;
        copy.openclDevicePreference = this.openclDevicePreference;
        copy.openclMinSamples = this.openclMinSamples;
        copy.openclValidateSamples = this.openclValidateSamples;
        copy.openclCoarseGpuConcurrencyCap = this.openclCoarseGpuConcurrencyCap;
        copy.runtimeCoarseUseCpuInGameplay = this.runtimeCoarseUseCpuInGameplay;
        copy.runtimeCoarseMaxThreads = this.runtimeCoarseMaxThreads;
        copy.runtimeCoarseMinThreads = this.runtimeCoarseMinThreads;
        copy.runtimeCoarseGpuMinIntervalMs = this.runtimeCoarseGpuMinIntervalMs;
        copy.runtimeCoarseGpuActiveMinIntervalMs = this.runtimeCoarseGpuActiveMinIntervalMs;
        copy.pauseCoarseGpuWhileMapOpen = this.pauseCoarseGpuWhileMapOpen;
        copy.pauseCoarseGpuWhileGuiOpen = this.pauseCoarseGpuWhileGuiOpen;
        copy.gameplayThreadCap = this.gameplayThreadCap;
        copy.coarseHeightBandCount = this.coarseHeightBandCount;
        copy.runtimeRoadCompletionMinIntervalMs = this.runtimeRoadCompletionMinIntervalMs;
        copy.gpuBudgetFreeFraction = this.gpuBudgetFreeFraction;
        copy.gpuBudgetHardCapMiB = this.gpuBudgetHardCapMiB;
        return copy;
    }

    public int sharedWorkerThreads() { return sharedWorkerThreads; }
    public void setSharedWorkerThreads(int v) { this.sharedWorkerThreads = sanitizeOptionalThreadCap(v); }
    public int initialGenerationThreads() { return initialGenerationThreads; }
    public void setInitialGenerationThreads(int v) { this.initialGenerationThreads = sanitizeInitialGenerationThreads(v); }
    public int maxConcurrentGenerations() { return maxConcurrentGenerations; }
    public void setMaxConcurrentGenerations(int v) { this.maxConcurrentGenerations = Math.max(1, Math.min(128, v)); }

    // ★ 手绘路线「施工优先」配置（2026-09-26 修 W-2/W-5）
    public boolean playerRoutePriorityEnabled() { return playerRoutePriorityEnabled; }
    public void setPlayerRoutePriorityEnabled(boolean v) { this.playerRoutePriorityEnabled = v; }
    public String playerRoutePriorityExemptPauseReasons() { return playerRoutePriorityExemptPauseReasons; }
    public void setPlayerRoutePriorityExemptPauseReasons(String v) { this.playerRoutePriorityExemptPauseReasons = v; }
    public int playerRoutePriorityMaxThreads() { return playerRoutePriorityMaxThreads; }
    public void setPlayerRoutePriorityMaxThreads(int v) {
        this.playerRoutePriorityMaxThreads = (v <= 0) ? 0 : Math.max(1, Math.min(128, v));
    }
    public int playerRoutePriorityHardLimitMinutes() { return playerRoutePriorityHardLimitMinutes; }
    public void setPlayerRoutePriorityHardLimitMinutes(int v) { this.playerRoutePriorityHardLimitMinutes = Math.max(0, v); }
    public int idleConcurrentGenerations() { return idleConcurrentGenerations; }
    public void setIdleConcurrentGenerations(int v) { this.idleConcurrentGenerations = Math.max(1, Math.min(128, v)); }
    public int threadDutyCycle() { return threadDutyCycle; }
    public void setThreadDutyCycle(int v) { this.threadDutyCycle = Math.max(RoadConstants.DUTY_CYCLE_MIN, Math.min(RoadConstants.DUTY_CYCLE_MAX, v)); }
    public boolean idleGenerationEnabled() { return idleGenerationEnabled; }
    public void setIdleGenerationEnabled(boolean v) { this.idleGenerationEnabled = v; }
    public int idleThreadDutyCycle() { return idleThreadDutyCycle; }
    public void setIdleThreadDutyCycle(int v) { this.idleThreadDutyCycle = Math.max(RoadConstants.DUTY_CYCLE_MIN, Math.min(RoadConstants.DUTY_CYCLE_MAX, v)); }
    public boolean openclCoarseSamplingEnabled() { return openclCoarseSamplingEnabled; }
    public void setOpenclCoarseSamplingEnabled(boolean v) { this.openclCoarseSamplingEnabled = v; }
    public String openclDevicePreference() { return normalizeOpenCLDevicePreference(openclDevicePreference); }
    public void setOpenclDevicePreference(String v) { this.openclDevicePreference = normalizeOpenCLDevicePreference(v); }
    public int openclMinSamples() { return Math.max(0, openclMinSamples); }
    public void setOpenclMinSamples(int v) { this.openclMinSamples = Math.max(0, Math.min(RoadConstants.COARSE_REGION_MAX_SAMPLES, v)); }
    public boolean openclValidateSamples() { return openclValidateSamples; }
    public void setOpenclValidateSamples(boolean v) { this.openclValidateSamples = v; }
    public int openclCoarseGpuConcurrencyCap() { return openclCoarseGpuConcurrencyCap; }
    public boolean runtimeCoarseUseCpuInGameplay() { return runtimeCoarseUseCpuInGameplay; }
    public void setRuntimeCoarseUseCpuInGameplay(boolean v) { this.runtimeCoarseUseCpuInGameplay = v; }
    public int runtimeCoarseMaxThreads() { return runtimeCoarseMaxThreads; }
    public void setRuntimeCoarseMaxThreads(int v) { this.runtimeCoarseMaxThreads = sanitizeGpuConcurrencyCap(v); }
    public int runtimeCoarseMinThreads() { return runtimeCoarseMinThreads; }
    public void setRuntimeCoarseMinThreads(int v) { this.runtimeCoarseMinThreads = Math.max(1, Math.min(16, v)); }
    public int runtimeCoarseGpuMinIntervalMs() { return runtimeCoarseGpuMinIntervalMs; }
    public void setRuntimeCoarseGpuMinIntervalMs(int v) { this.runtimeCoarseGpuMinIntervalMs = Math.max(0, Math.min(60_000, v)); }
    public int runtimeCoarseGpuActiveMinIntervalMs() { return runtimeCoarseGpuActiveMinIntervalMs; }
    public void setRuntimeCoarseGpuActiveMinIntervalMs(int v) { this.runtimeCoarseGpuActiveMinIntervalMs = Math.max(0, Math.min(60_000, v)); }
    public boolean pauseCoarseGpuWhileMapOpen() { return pauseCoarseGpuWhileMapOpen; }
    public void setPauseCoarseGpuWhileMapOpen(boolean v) { this.pauseCoarseGpuWhileMapOpen = v; }
    public boolean pauseCoarseGpuWhileGuiOpen() { return pauseCoarseGpuWhileGuiOpen; }
    public void setPauseCoarseGpuWhileGuiOpen(boolean v) { this.pauseCoarseGpuWhileGuiOpen = v; }
    public int gameplayThreadCap() { return gameplayThreadCap; }
    public void setGameplayThreadCap(int v) { this.gameplayThreadCap = Math.max(0, Math.min(16, v)); }
    public int coarseHeightBandCount() { return Math.max(1, Math.min(64, coarseHeightBandCount)); }
    public void setCoarseHeightBandCount(int v) { this.coarseHeightBandCount = Math.max(1, Math.min(64, v)); }
    public int runtimeRoadCompletionMinIntervalMs() { return runtimeRoadCompletionMinIntervalMs; }
    public void setRuntimeRoadCompletionMinIntervalMs(int v) { this.runtimeRoadCompletionMinIntervalMs = Math.max(0, Math.min(60_000, v)); }
    public double gpuBudgetFreeFraction() { return sanitizeGpuBudgetFraction(gpuBudgetFreeFraction); }
    public void setGpuBudgetFreeFraction(double v) { this.gpuBudgetFreeFraction = sanitizeGpuBudgetFraction(v); }
    public int gpuBudgetHardCapMiB() { return Math.max(0, gpuBudgetHardCapMiB); }
    public void setGpuBudgetHardCapMiB(int v) { this.gpuBudgetHardCapMiB = Math.max(0, Math.min(65536, v)); }
    /** 硬上限换算成字节；0 表示不设上限。 */
    public long gpuBudgetHardCapBytes() {
        long mib = Math.max(0, gpuBudgetHardCapMiB);
        return mib == 0L ? 0L : mib * 1024L * 1024L;
    }
    public void setOpenclCoarseGpuConcurrencyCap(int v) { this.openclCoarseGpuConcurrencyCap = sanitizeGpuConcurrencyCap(v); }

    public boolean shouldThrottle() { return threadDutyCycle < RoadConstants.DUTY_CYCLE_MAX; }

    private static int sanitizeInitialGenerationThreads(int value) {
        if (value <= 0) return 0;
        return Math.max(1, Math.min(RoadConstants.COMPUTE_THREADS_MAX, value));
    }

    private static int sanitizeOptionalThreadCap(int value) {
        if (value <= 0) return 0;
        return Math.max(1, Math.min(RoadConstants.COMPUTE_THREADS_MAX, value));
    }

    private static int sanitizeGpuConcurrencyCap(int value) {
        return Math.max(0, Math.min(16, value));
    }

    /** 0.8.340：空闲显存占比必须落在 (0, 1]；越界或 NaN 回到默认 0.70。 */
    private static double sanitizeGpuBudgetFraction(double value) {
        if (Double.isNaN(value) || value <= 0.0D || value > 1.0D) return 0.70D;
        return value;
    }

    private static String normalizeOpenCLDevicePreference(String value) {
        if (value == null || value.isBlank()) {
            return "AUTO";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "AUTO", "GPU", "CPU" -> normalized;
            default -> "AUTO";
        };
    }
}
