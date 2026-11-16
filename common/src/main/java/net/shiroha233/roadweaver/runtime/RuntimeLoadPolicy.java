/* 文件职责：与其他运行时控制器的<b>可选集成</b> —— 全部走 roadweaver-api 契约，不含任何反射。
 *
 * 提供方没注册时每个信号返回「无意见」，道路在普通整合包里照样能用。
 * 历史上这里曾有 9 处 Class.forName 硬编码探测，是「静默失效」的重灾区。
 */
package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PerformanceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import static net.shiroha233.roadweaver.runtime.RuntimeSignalMethods.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 与 PlainCraft 各运行时控制器的<b>可选集成</b>。
 *
 * <p>全部走 {@code roadweaver-api} 的标准契约（{@code RoadLoadSignals} /
 * {@code StreamsThreadControl}）—— <b>不再有任何反射</b>。提供方没注册时，
 * 每个信号返回"无意见"的默认值，调用方按自己的默认值走，道路在普通整合包里照样能用。</p>
 *
 * <p>历史：这里曾经有 9 处 {@code Class.forName} 硬编码探测别的 mod，
 * 是"静默失效"的重灾区。提供方全部迁到 API 后按
 * {@code LoadSignalBridge} 里写好的计划把兜底路径删掉了。</p>
 */
public final class RuntimeLoadPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final long REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long RECOVERY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long WORKER_REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final AtomicLong NEXT_REFRESH = new AtomicLong();
    private static final AtomicLong NEXT_COORDINATOR_REFRESH = new AtomicLong();
    private static final AtomicLong NEXT_PAUSE_LOG = new AtomicLong();
    private static final AtomicLong NEXT_WORKER_REFRESH = new AtomicLong();
    private static final AtomicLong RECOVERY_UNTIL = new AtomicLong();
    private static final AtomicInteger ACTIVE_COARSE_UNITS = new AtomicInteger();
    private static final OptionalBooleanMethod AUXILIARY_PAUSE = new OptionalBooleanMethod(
            "local.plaincraftchunkyautogen.AdaptiveChunkController", "shouldPauseAuxiliaryWorldgen");
    private static final OptionalBooleanMethod CHUNKY_ACTIVE = new OptionalBooleanMethod(
            "local.plaincraftchunkyautogen.AdaptiveChunkController", "hasActiveTasks");
    private static final OptionalBooleanMethod ROAD_COORDINATOR_PAUSE = new OptionalBooleanMethod(
            "local.plaincraftchunkyautogen.AdaptiveChunkController", "shouldPauseRoadCoordinator");
    private static final OptionalIntMethod ROAD_COORDINATOR_LANES = new OptionalIntMethod(
            "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadPlanningLanes");
    private static final OptionalIntOneArgMethod ROAD_WORKER_THREADS = new OptionalIntOneArgMethod(
            "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadWorkerThreads");
    private static final OptionalVoidIntMethod STREAMS_SUPPLY_THREADS = new OptionalVoidIntMethod(
            "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkySupplyThreads");
    private static final OptionalBooleanOneArgMethod STREAMS_CHUNKY_EXCLUSIVE = new OptionalBooleanOneArgMethod(
            "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkyExclusive");
    private static final OptionalBooleanMethod INTERACTION_ACTIVE = new OptionalBooleanMethod(
            "local.plaincraftchunkyautogen.PlayerChunkPriorityGuard", "isInteractionActive");
    private static final OptionalBooleanMethod CRUISE_ACTIVE = new OptionalBooleanMethod(
            "local.plaincraft.hyperspeed.CruiseState", "isCpuProtectionActive");
    private static volatile boolean chunkyPreflightActive;
    private static volatile boolean chunkyExclusiveActive;
    private static volatile boolean gameplayStreamsLimited;
    private static volatile int gameplayStreamsTarget = -1;
    private static volatile PauseReason cached = PauseReason.NONE;
    private static volatile PauseReason cachedCoordinator = PauseReason.NONE;

    /**
     * 0.8.344：RoadWeaver 地图界面是否打开。
     *
     * <p>真机取证（20260911 19:58~20:23）：游玩活跃态 GPU 粗采样连续占满 GPU 达 25 分钟
     * （{@code nvidia-smi} 显示 utilization 持续 100%，活跃态记录 148 条），
     * 期间玩家按 H 打开地图会**明显卡顿** —— 因为地图要渲染 Xaero 地形，
     * 而粗采样正把 GPU 占满，两者抢同一块 GPU。仅靠"提交间隔"（默认 1500ms）挡不住：
     * 一个 187 万样本的区域要分约 100 批，即使每批间隔 1.5 秒，也有约 150 秒持续占用。</p>
     *
     * <p>因此本版新增"地图优先"闸：地图打开时暂停 GPU 粗采样（转 CPU 或等待），
     * 让地图渲染独享 GPU；关闭地图立即恢复。由 {@code performance.pauseCoarseGpuWhileMapOpen}
     * 控制（默认开启）。</p>
     */
    private static volatile boolean mapScreenOpen = false;

    /**
     * 0.8.348：**任意客户端界面**是否打开（背包 / ESC / 熔炉 / 工作台 / 设置 …）。
     *
     * <p>真机取证（20260912 夜 23:26 会话）：玩家开背包 / ESC / 改设置时卡顿被日志证实 ——
     * {@code 23:34:05} 首次 {@code Saving and pausing} 后，&gt;3 秒日志静默由暂停前的 <b>4 次</b>
     * 暴增到 <b>15+ 次</b>；且 {@code RW-CoarseTile} 仍每 5~6 秒输出
     * {@code phase=GAMEPLAY_ACTIVE} 直到 {@code 23:38:07}。</p>
     *
     * <p><b>根因</b>：开 GUI 是**纯客户端行为**，不发任何服务端交互包，因此
     * {@code PlayerChunkPriorityGuard.markInteraction()} 不被调用（其唯一入口是
     * {@code ServerPlayInteractionPriorityMixin} 注入的 5 个服务端包：挖掘/放置/使用/攻击/交互方块），
     * 加上开 GUI 时玩家位置不变，{@code INTERACTION_ACTIVE} 与 {@code RuntimePlayerActivity}
     * 两个信号都不亮 → 相位被误判；而 0.8.344 的地图闸只由 {@code RoadMapScreen} 设置，
     * 覆盖不到背包/ESC/熔炉/工作台。</p>
     *
     * <p><b>注意</b>：真机日志显示卡顿时预算**已经是** {@code budget=1/1 pressure=HIGH}
     * （即"压到最小并发 + 拉长间隔"这套节流已完全生效却仍然卡），因此本闸采用与地图一致的
     * **彻底暂停**，而不是再调小预算。</p>
     */
    private static volatile boolean guiScreenOpen = false;

    /** 地图界面打开/关闭时由 RoadMapScreen 调用（客户端侧）。 */
    public static void setMapScreenOpen(boolean open) {
        mapScreenOpen = open;
    }

    public static boolean isMapScreenOpen() {
        return mapScreenOpen;
    }

    /**
     * 任意界面（背包/ESC/熔炉/设置/**聊天栏**…）打开/关闭时由客户端每 tick 调用。
     *
     * <p>0.8.351 起**聊天栏也算**：0.8.348 曾刻意排除它，真机证伪（玩家开聊天后仍卡）——
     * 详见 {@code ClientInit.syncScreenPauseState} 的说明。</p>
     */
    public static void setGuiScreenOpen(boolean open) {
        guiScreenOpen = open;
    }

    /** 原始标志：是否有非地图界面打开。 */
    public static boolean isGuiScreenOpen() {
        return guiScreenOpen;
    }

    /**
     * 0.8.348：界面让路闸是否生效 —— 地图**或**任意 GUI 打开。
     *
     * <p>**刻意排除开局生成与 Chunky 独占期**：这两个阶段客户端同样会持有界面
     * （加载界面 / 预生成覆盖层），若一并暂停会把预生成拖慢，违背"别的时候打满打满没事"
     * 的既有口径。正常游玩下地图侧行为不变（开局/Chunky 期玩家本就不会开地图）。</p>
     */
    public static boolean isScreenPauseActive() {
        return (mapScreenOpen || guiScreenOpen)
                && !InitialGenManager.isActive()
                && !chunkyExclusiveActive;
    }

    private RuntimeLoadPolicy() {
    }

    public static boolean pauseScheduling() {
        return pauseReason() != PauseReason.NONE;
    }

    /**
     * 当前暂停原因（★ 2026-09-26 公开，供「手绘施工优先」按**原因**豁免）。
     *
     * <h2>为什么把它公开</h2>
     * WorkBuddy 复查（W-2）指出：0.8.397 的优先模式**一刀切地推翻了暂停语义**，
     * 连 {@link PauseReason#CHUNKY} 也豁免 —— 那是 0.8.372 用真机取证换来的
     * 「Chunky 独占期让路」约定（12 lanes 抢空 14 个 c2me-worker）。
     *
     * <p>红线 6 要求「与 Chunky/Streams/Voxy 的资源协调**只走配置层**」。
     * 公开本方法后，豁免逻辑变成**查表比较**
     * （{@code pauseReason() ∈ 配置的豁免集合}），
     * 协调通道仍全部走配置，代码只做判定 —— 符合红线。</p>
     *
     * <p>返回 {@link PauseKind} 而不是内部枚举，是为了不把内部类型暴露给
     * 外部调用方（配置层只需要一个稳定标识）。</p>
     */
    public static PauseKind currentPauseKind() {
        return switch (pauseReason()) {
            case NONE -> PauseKind.NONE;
            case PLAYER_TRAVEL -> PauseKind.PLAYER_TRAVEL;
            case CHUNKY -> PauseKind.CHUNKY;
            case PLAYER_INTERACTION -> PauseKind.PLAYER_INTERACTION;
            case SYSTEM_PRESSURE -> PauseKind.SYSTEM_PRESSURE;
            case RECOVERY -> PauseKind.RECOVERY;
        };
    }

    /**
     * 暂停原因的外部可见分类（★ 2026-09-26）。
     *
     * <p>与内部 {@code PauseReason} 一一对应，但作为**稳定契约**对外，
     * 让配置层可以按名字指定"豁免哪些原因"而不依赖内部枚举。</p>
     */
    public enum PauseKind {
        /** 没有暂停。 */
        NONE,
        /** 玩家跑图（巡航保护）。 */
        PLAYER_TRAVEL,
        /** Chunky 独占生成期 —— ★ 默认**不豁免**。 */
        CHUNKY,
        /** 玩家开着界面（地图 / GUI）—— ★ 默认豁免。 */
        PLAYER_INTERACTION,
        /** Tick/FPS/内存压力。 */
        SYSTEM_PRESSURE,
        /** 前台恢复冷却（交互后 2 秒）—— ★ 默认豁免。 */
        RECOVERY
    }

    /** Chunky owns the machine during its exclusive generation phase.
     * 非独占的 Chunky 运行(CHUNKY_ACTIVE,无 exclusive)不再全停 GPU 粗采样,
     * 改由 RuntimeCoarseGpuBudget 的"压到 1"预算限流(四态策略 · 状态 3)。 */
    public static boolean shouldPauseOpenClCoarseSampling() {
        return chunkyExclusiveActive;
    }

    /** True while Chunky owns or is preparing a generation task. */
    public static boolean isChunkyActive() {
        return chunkyPreflightActive || chunkyExclusiveActive || CHUNKY_ACTIVE.invoke();
    }

    /** 四态 · CHUNKY_RUNNING:Chunky 正式生成中(非 preflight 预热)。 */
    public static boolean isChunkyRunning() {
        return !chunkyPreflightActive && (chunkyExclusiveActive || CHUNKY_ACTIVE.invoke());
    }

    /** 四态生命周期,供采样后端选择、线程预算与日志归因。 */
    public enum RuntimePhase {
        /** 开局预热:一切开满,不受游玩限制。 */
        STARTUP,
        /** Chunky 预热(preflight 规划):同开局开满。 */
        CHUNKY_PREFLIGHT,
        /** Chunky 正式运行:RoadWeaver 后台让路,GPU 粗采样压到下限。 */
        CHUNKY_RUNNING,
        /** 普通游玩且玩家移动/交互:CPU 粗采样 + 低线程保护。 */
        GAMEPLAY_ACTIVE,
        /** 普通游玩且玩家长时间静止:GPU 粗采样(上限+间隔节流)。 */
        GAMEPLAY_IDLE
    }

    public static RuntimePhase currentPhase() {
        if (InitialGenManager.isActive()) return RuntimePhase.STARTUP;
        if (chunkyPreflightActive) return RuntimePhase.CHUNKY_PREFLIGHT;
        if (isChunkyRunning()) return RuntimePhase.CHUNKY_RUNNING;
        return CRUISE_ACTIVE.invoke() || INTERACTION_ACTIVE.invoke() || RuntimePlayerActivity.isActive()
                ? RuntimePhase.GAMEPLAY_ACTIVE
                : RuntimePhase.GAMEPLAY_IDLE;
    }

    /** 四态 · GAMEPLAY_IDLE:普通游玩且玩家长时间静止(不在开局/Chunky)。 */
    public static boolean isGameplayIdle() {
        return currentPhase() == RuntimePhase.GAMEPLAY_IDLE;
    }

    private static final AtomicLong LAST_ROAD_COMPLETION = new AtomicLong();

    /** RoadGenerationService 在一条运行时道路完成(持久化+回填)后调用。 */
    public static void noteRoadCompletion() {
        LAST_ROAD_COMPLETION.set(System.nanoTime());
    }

    /**
     * 四态 · GAMEPLAY_IDLE 节流闸:一条道路完成后,下一次低优先级区域规划
     * 必须至少间隔 {@code runtimeRoadCompletionMinIntervalMs}。非挂机态直接放行;
     * 阈值为 0 表示不限。调用方在闸关闭时应保持待命(不丢弃、不推进)。
     */
    public static boolean idlePlanningGateOpen() {
        if (currentPhase() != RuntimePhase.GAMEPLAY_IDLE) return true;
        long last = LAST_ROAD_COMPLETION.get();
        if (last == 0L) return true;
        long minimum;
        try {
            minimum = ConfigService.get().performance().runtimeRoadCompletionMinIntervalMs();
        } catch (Throwable ignored) {
            return true;
        }
        if (minimum <= 0L) return true;
        return System.nanoTime() - last >= TimeUnit.MILLISECONDS.toNanos(minimum);
    }

    /** Foreground pressure used by adaptive GPU coarse sampling. */
    public static boolean isForegroundPressureActive() {
        return CRUISE_ACTIVE.invoke()
                || INTERACTION_ACTIVE.invoke()
                || RuntimePlayerActivity.isActive();
    }

    /** True only for ordinary gameplay foreground protection, never startup or Chunky. */
    public static boolean isGameplayForegroundProtected() {
        return !InitialGenManager.isActive()
                && !isChunkyActive()
                && isForegroundPressureActive();
    }

    /** Tick hook used to apply the gameplay-only Streams override even when no road task is running. */
    public static void tickGameplayPolicy() {
        refreshRoadWorkerBudget();
    }

    public static int maximumRoadWorkerThreads() {
        return ThreadPoolManager.maxRuntimeRoadThreads();
    }

    /**
     * A manual Chunky request may need a narrow planning safety band before
     * generation can begin. This is not normal background work: it has no
     * active Chunky task yet and must finish before that task can start.
     */
    public static void setChunkyPreflightActive(boolean active) {
        chunkyPreflightActive = active;
        if (!active) {
            cachedCoordinator = PauseReason.NONE;
        }
    }

    /** Chunky owns world-generation threads after the road/river preflight. */
    public static void setChunkyExclusiveActive(boolean active) {
        chunkyExclusiveActive = active;
        chunkyExclusiveSinceMs = active ? System.currentTimeMillis() : 0L;
        exclusiveWarnedAtMs = 0L;
        STREAMS_CHUNKY_EXCLUSIVE.invoke(active);
        cachedCoordinator = active ? PauseReason.CHUNKY : PauseReason.NONE;
        LOGGER.info("RoadWeaver runtime phase={}",
                active ? "CHUNKY_EXCLUSIVE" : "POST_CHUNKY_RECOVERY");
    }

    // ─────────────────── chunkyExclusiveActive 看门狗（审计 P1-3）───────────────────
    //
    // 问题：chunkyExclusiveActive 由外部反射设置
    // （RoadPlanningReadinessIndex.setChunkyExclusiveActive ← plaincraft-chunky-autogen），
    // 但**清除路径只有停服/重载世界**（RoadGenerationService:106/153）。
    // Chunky 异常退出而未回调 → 标志永久残留 true。
    //
    // 后果：pauseReason() 每 50ms 重算返回 CHUNKY，所有非 INITIAL/MAP_READ 任务
    // 在 awaitRuntimePermit() 里 25ms 自旋 park —— 线程没真挂死，
    // 但 RoadWeaver 运行时规划**无限期停摆**（玩家观感：路再也不生成了）。

    /** 标志为真但 Chunky 探针为假时，连续多久才判定为泄漏（毫秒）。 */
    private static final long EXCLUSIVE_LEAK_GRACE_MS = 120_000L;

    /** 看门狗重新告警的间隔（避免刷屏）。 */
    private static final long EXCLUSIVE_WARN_INTERVAL_MS = 60_000L;

    private static volatile long chunkyExclusiveSinceMs;
    private static volatile long exclusiveWarnedAtMs;

    /**
     * 由服务端 tick 调用：检测并自愈「Chunky 独占标志泄漏」。
     *
     * <p>判据：标志为真**超过 {@value #EXCLUSIVE_LEAK_GRACE_MS} 毫秒**，
     * 且真正的 Chunky 探针 {@code CHUNKY_ACTIVE} 为假。
     * 宽限期是必要的 —— Chunky 独占期开始时探针本就可能短暂滞后，
     * 不能一看到探针为假就立刻降级。</p>
     *
     * <p>取 2 分钟：正常的 Chunky 独占是**分钟级**的，宽限期太短会误降级。</p>
     *
     * @return true 表示本次调用执行了自愈（供日志/测试断言）
     */
    public static boolean watchChunkyExclusiveLeak() {
        if (!chunkyExclusiveActive) {
            return false;
        }
        long since = chunkyExclusiveSinceMs;
        if (since <= 0L) {
            // 没有起始时间（例如标志由旧版本设置）→ 补记一次，下轮再判
            chunkyExclusiveSinceMs = System.currentTimeMillis();
            return false;
        }
        long heldMs = System.currentTimeMillis() - since;
        if (heldMs < EXCLUSIVE_LEAK_GRACE_MS) {
            return false;
        }
        // 宽限期已过：看真正的 Chunky 探针
        if (CHUNKY_ACTIVE.invoke()) {
            return false;   // Chunky 真在跑，标志是合法的
        }
        long now = System.currentTimeMillis();
        if (now - exclusiveWarnedAtMs < EXCLUSIVE_WARN_INTERVAL_MS) {
            return false;   // 告警节流：还没到下次可告警时间
        }
        exclusiveWarnedAtMs = now;
        LOGGER.warn("RoadWeaver Chunky 独占标志已持有 {}ms 但 Chunky 探针为假，"
                + "判定为泄漏并自动降级（否则运行时规划会无限期停摆）", heldMs);
        setChunkyExclusiveActive(false);
        return true;
    }

    /** 当前 Chunky 独占标志是否被置起（诊断用）。 */
    public static boolean isChunkyExclusiveActive() {
        return chunkyExclusiveActive;
    }

    /**
     * Chunky 独占期开始时释放可重建缓存，把内存与 CPU 让给 Chunky。
     *
     * <p>由平台层（{@code ServerPlanningHooks} 的 tick，那里持有 {@code ServerLevel}）
     * 在检测到 Chunky 独占转正时调用一次。逻辑委托给 {@code CacheManager#onChunkyBegin}：
     * 只清「有磁盘备份、能廉价重建」的规划侧缓存，结构避让与施工账本保持常驻。</p>
     */
    public static void onChunkyOwnershipStarted(ServerLevel level) {
        try {
            CacheManager.onChunkyBegin(level);
        } catch (Throwable failure) {
            LOGGER.warn("Chunky 让位释放缓存失败（不影响生成）: {}", failure.toString());
        }
    }

    // 执行层统一读取预热豁免，采样层不再直接依赖 InitialGenManager。
    static boolean isCpuWarmup() {
        return InitialGenManager.isActive() || chunkyPreflightActive;
    }

    /**
     * Chunky preflight must use the same planning-quality path as initial
     * generation, but only before a real Chunky task has started.
     */
    public static boolean isChunkyPreflightActive() {
        return chunkyPreflightActive;
    }

    static void beforeTask(ThreadPoolManager.TaskRole role) {
        beforeTask(role, () -> false);
    }

    // 在闸门内也检查排队任务的取消/代次，FutureTask 尚未执行时 cancel(true) 没有可中断的 runner。
    static void beforeTask(ThreadPoolManager.TaskRole role, java.util.function.BooleanSupplier cancelled) {
        cancelIfStopping();
        if (cancelled.getAsBoolean()) throw new CancellationException("task scope expired before admission");
        if (role == ThreadPoolManager.TaskRole.INITIAL || InitialGenManager.isActive()) return;
        if (role == ThreadPoolManager.TaskRole.MAP) {
            beforeMapUnit(cancelled);
            return;
        }
        // 注意：MAP_READ 不在这里改道 beforeMapUnit —— 那会连 Chunky 独占期与
        // 玩家跑动的让路一起绕过。它只需豁免「界面暂停」，该逻辑写在
        // awaitRuntimePermit() 内部，精准且不影响其它让路语义。
        refreshRoadWorkerBudget();
        if (role == ThreadPoolManager.TaskRole.COORDINATOR) {
            if (chunkyPreflightActive) return;
            awaitCoordinatorPermit(cancelled);
            return;
        }
        awaitRuntimePermit(cancelled);
    }

    /**
     * Yield point for long-running runtime work. Initial world planning stays
     * unrestricted; dynamic and map sampling re-check foreground pressure at
     * every bounded unit instead of only once when the outer task starts.
     */
    public static void beforeBackgroundUnit(boolean initialGeneration) {
        beforeBackgroundUnit(initialGeneration, () -> false);
    }

    // 取消条件传到暂停循环内部，否则换世界后仍可能一直等前台保护结束。
    private static void beforeBackgroundUnit(boolean initialGeneration,
                                             java.util.function.BooleanSupplier cancelled) {
        beforeBackgroundUnit(initialGeneration, cancelled, ThreadPoolManager.currentTaskRole());
    }

    private static void beforeBackgroundUnit(boolean initialGeneration,
                                             java.util.function.BooleanSupplier cancelled,
                                             ThreadPoolManager.TaskRole originRole) {
        cancelIfStopping();
        if (initialGeneration || InitialGenManager.isActive()) return;
        if (originRole == ThreadPoolManager.TaskRole.MAP) {
            beforeMapUnit(cancelled);
            return;
        }
        refreshRoadWorkerBudget();
        if (originRole == ThreadPoolManager.TaskRole.COORDINATOR) {
            if (chunkyPreflightActive) return;
            awaitCoordinatorPermit(cancelled);
            return;
        }
        awaitRuntimePermit(cancelled, originRole);
    }

    public static boolean acquireCoarseUnit(boolean initialGeneration) {
        return acquireCoarseUnit(initialGeneration, () -> false);
    }

    /** 共用已有粗采样计数，精确采样不能再另开一份预算绕过并发上限。 */
    public static boolean acquireCoarseUnit(boolean initialGeneration,
                                             java.util.function.BooleanSupplier cancelled) {
        return acquireCoarseUnit(initialGeneration, cancelled, ThreadPoolManager.currentTaskRole());
    }

    /** 子任务按提交方角色让路，不能因落在 INITIAL 池就丢失地图读取的界面豁免。 */
    public static boolean acquireCoarseUnit(boolean initialGeneration,
                                             java.util.function.BooleanSupplier cancelled,
                                             ThreadPoolManager.TaskRole originRole) {
        if (initialGeneration) return true;
        while (!Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            beforeBackgroundUnit(false, cancelled, originRole);
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return false;
            int limit = Math.max(1, recommendedRoadWorkerThreads());
            int active = ACTIVE_COARSE_UNITS.get();
            if (active < limit && ACTIVE_COARSE_UNITS.compareAndSet(active, active + 1)) return true;
            LockSupport.parkNanos(PAUSE_NANOS);
        }
        return false;
    }

    public static void releaseCoarseUnit(boolean initialGeneration) {
        if (initialGeneration) return;
        ACTIVE_COARSE_UNITS.updateAndGet(value -> Math.max(0, value - 1));
    }

    public static int recommendedRoadWorkerThreads() {
        // ★ 0.8.401：**Chunky 预热期完全豁免**（用户明确要求：「预热阶段加豁免，
        //   不要考虑任何压力，直接全力加载」，且经确认**连玩家活动也豁免**）。
        //
        // 【为什么必须放在所有分支（含配置读取）之前】
        // 本方法原先**没有任何预热分支** —— 预热期会一路落到下面的
        // CRUISE / 界面 / 交互 / 移动 分支，或被末尾的
        // clampGameplayThreadCap 钳到 gameplayThreadCap（默认 2）。
        // 真机取证：预热期 worker capacity 只在 1↔2 之间振荡，**从未到过 3+**。
        //
        // 而方法上方的注释曾声称「Chunky 预热会在更早的分支返回」——该注释
        // 与实际代码不符（预热分支根本不存在），本次一并更正。
        //
        // 【为什么这些守卫在预热期必须被绕过】
        // 预热期的语义是「Chunky 已把整批 region 押给 RoadWeaver，等它规划完才开工」。
        // 此时「有压力」是**必然且预期**的（CPU 与 IO 都在满负荷干活），
        // 用压力反向压制劳动者会造成负反馈自杀：
        //   越努力 → tick 越慢 → 被判 critical → 压到 1 → 更慢。
        // 玩家移动/交互同理：预热是玩家主动发起的等待，理应全力完成。
        //
        // 【唯一的例外：堆 >88%】
        // 只保留堆这一条保险丝 —— 它不是「主观压力」而是会**崩掉存档**的硬约束
        // （真机事故：905 region 一次性入队 → 堆贴顶 12GB → 11 处 OutOfMemoryError）。
        // 堆正常时（如真机 41.6%）本分支返回 8，即「全力」。
        //
        // 刻意不读 ConfigService：预热豁免应当自成一体，配置读取失败也不能
        // 让它退化（放在最前面同时保证离线测试可覆盖）。
        if (chunkyPreflightActive && !chunkyExclusiveActive) {
            return currentHeapUsageRatio() >= 0.88D
                    ? 2
                    : Math.min(8, ThreadPoolManager.maxRuntimeRoadThreads());
        }

        int maximum = ThreadPoolManager.maxRuntimeRoadThreads();
        PerformanceConfig performance = ConfigService.get().performance();
        int normalCap = cap(performance.sharedWorkerThreads(), maximum);
        if (CRUISE_ACTIVE.invoke()) {
            // ★ 审计 P2-1：由 `return 1` 改为 `return 2`（沿用本类的"死锁下限"约定）。
            //
            // 这是**第一个分支**，会绕过后面所有「至少 2」的兜底，且能覆盖 Chunky 路径
            // （HyperSpeed 巡航保护与 Chunky 预热同时成立时走这里）。原先返回 1。
            //
            // 当前并不构成死锁 —— 因为所有「父任务同步等待的子任务」都已经挪到独立池
            // （结构快照 → RW-Snapshot 固定 2 线程；粗路径搜索 → INITIAL 独立池）。
            // 但**这条不变量很脆**：一旦将来有人在共享池里加回任何嵌套子任务，
            // 「1 个线程 + 等待同池子任务」立刻变成永久挂起。
            //
            // 回到 2 的代价只是巡航期多一个线程（相对 8~12 的常态可忽略），
            // 换来的是对上述回归免疫。取舍：稳定性 > 那一点点 CPU。
            return clampGameplayThreadCap(2);
        }
        if (chunkyExclusiveActive || CHUNKY_ACTIVE.invoke()) {
            // 0.8.372：Chunky 期不再无条件返回 maximum。
            //
            // 真机取证（20260919 08:38~08:53 会话）：玩家跑图时 14 个 c2me-worker
            // 全部 WAITING(parking) 在同一把 Semaphore 上、游戏总 CPU 仅 27%
            // （434%/1600%），而 RoadWeaver 同期开着 12 lanes + 8 个 RW-NativeStructure
            // + 7 个 RW-Initial ≈ 27 个线程。即"Chunky 期无限开线程"把区块生成
            // 的 worker 全抢空了 —— 与"让路给生成"的初衷相反。
            //
            // 因此这里改为：Chunky 期仍给足并发（优先完成 readiness，不能饿死），
            // 但不得超过 gameplayThreadCap 的 4 倍，避免把机器占满。
            // 默认 gameplayThreadCap=2 ⇒ 上限 8，足以跑完 readiness 又不抢空生成。
            int chunkyCeiling = Math.max(2, clampGameplayThreadCap(2) * 4);
            return Math.min(maximum, chunkyCeiling);
        }
        // 0.8.350：界面打开时压到最小并发（与"让路"配套）。
        //
        // 真机取证（20260913 01:19 会话，0.8.349 已生效）：开背包后仍卡 2~3s。
        // 日志显示 GUI 期间 RW-NativeStructure-1..8 **八个线程同时活跃**
        // （每线程各 8~12 条日志），即"让路"只让 worker 在**单元之间**停一下，
        // 却没有把**并发上限**压下来 —— 8 个结构解析线程仍在抢 CPU。
        //
        // 根因：玩家站着不动开背包时，INTERACTION_ACTIVE 与 RuntimePlayerActivity
        // 都不亮（前者只认服务端交互包，后者只认位移），于是跳过下面的
        // GAMEPLAY_ACTIVE 分支（那才会 clampGameplayThreadCap(2)），
        // 直接落到 normalCap —— 结构线程全开。
        //
        // 因此这里补一个显式的界面分支，压到与 GAMEPLAY_ACTIVE 相同的死锁下限 2
        // （1 个跑路任务 + 1 个嵌套子任务；压到 1 会自锁）。
        // 与 isScreenPauseActive() 同源，故排除开局/Chunky 期的语义一致。
        if (isScreenPauseActive()) {
            return clampGameplayThreadCap(2);
        }
        // Ordinary gameplay protection takes precedence over external
        // recommendations. Movement/interactions hold exactly two workers:
        // one for the road task and one for its nested planning/snapshot
        // subtask — a single worker would block forever waiting for a
        // subtask it can never schedule while the player is moving.
        // 四态 · GAMEPLAY_ACTIVE:道路 worker 默认 2(死锁下限);更高压力
        // (HyperSpeed CPU 保护)已在上方降为 1。
        if (INTERACTION_ACTIVE.invoke() || RuntimePlayerActivity.isActive()) {
            // 0.8.344：游玩期总线程上限（用户要求"别让他跑满"）。
            // 真机取证：游玩期 RW-NativeStructure 8 线程累计 1735s CPU（占 RoadWeaver 总量 70%），
            // 叠加 Render/c2me/Server 后把 CPU 打满导致卡顿。
            // 默认 2 = 保持既有的"死锁下限"语义（1 个跑路任务 + 1 个嵌套子任务），
            // 故本键默认与旧行为一致；用户可调小到 1（更安静、更慢）。
            // 注意：本上限同时经 acquireNativeDirectLane 作用于结构解析，
            // 即"结构解析 + 采样"共用同一个游玩期预算。
            return clampGameplayThreadCap(2);
        }
        Integer recommendation = ROAD_WORKER_THREADS.invoke(maximum);
        if (recommendation != null) {
            return clampGameplayThreadCap(Math.max(1, Math.min(normalCap, recommendation)));
        }
        if (CHUNKY_ACTIVE.invoke()) return Math.min(normalCap, 4);
        return clampGameplayThreadCap(normalCap);
    }

    /**
     * 0.8.344：游玩期总线程上限（{@code performance.gameplayThreadCap}）。
     *
     * <p>只作用于<b>普通游玩</b>路径：Chunky 运行/预热与开局生成都会在更早的分支返回，
     * 不经此处 —— 即"游玩时限流、别的时候放开"，符合用户要求
     * 「别的时候打满打满没事」。</p>
     *
     * <p>0 或负数 = 不限（保持旧行为）。</p>
     */
    private static int clampGameplayThreadCap(int value) {
        try {
            int cap = ConfigService.get().performance().gameplayThreadCap();
            if (cap > 0) return Math.max(1, Math.min(value, cap));
        } catch (Throwable ignored) {
            // 配置不可用时不干预。
        }
        return value;
    }

    private static int cap(int configured, int maximum) {
        return configured <= 0
                ? maximum
                : Math.max(1, Math.min(maximum, configured));
    }

    // ─────────────────── 堆压力护栏（审计 P1-2）───────────────────
    //
    // 真机 2026-09-24-1.log：905 个 region（≈92.7 万 chunk）一次性进入
    // Chunky readiness 预检队列，Chunky 一个都没消费（completed=0、active=false），
    // readyAhead 一路涨到 751，堆在崩溃前 1 小时就贴顶 12 GB（99.1%），
    // 最终 11 处 OutOfMemoryError 崩服。
    //
    // 关键对照：同一批日志里，Chunky 真在跑（active=true）的那次堆只有 8.4–9.4 GB、0 次 OOM。
    // ⇒ 崩溃不是 Chunky 造成的，是 **RoadWeaver 自己的预检积压在 Chunky 启动前就把堆吃满**。
    //
    // 这里加一道按堆占用率的护栏：占用越高，允许的规划 lane 越少。

    /**
     * 按堆占用率返回 lane 的**最大**允许值。
     *
     * <pre>
     *   堆占用 &lt; 70%  → 不限制（返回上限常量）
     *   70% ~ 80%      → 最多 4
     *   80% ~ 88%      → 最多 2
     *   ≥ 88%          → 最多 1（保命档：只留最低限度前进，避免完全停摆）
     * </pre>
     *
     * <p>为什么用「降速」而不是「直接停」：直接停会让 Chunky 永远等不到 READY
     * （它要求整批 68 个 region 就绪），变成另一种死锁。降到 1 是「慢但仍在推进」。</p>
     *
     * <p>堆信息取不到时返回上限常量（不干预）—— 宁可不管，也不能因为读不到
     * 内存就误把规划压死。</p>
     */
    private static int heapPressureLaneCap() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long max = runtime.maxMemory();
            if (max <= 0L) {
                return Integer.MAX_VALUE;
            }
            long used = runtime.totalMemory() - runtime.freeMemory();
            double ratio = (double) used / (double) max;
            if (ratio >= 0.88D) return 1;
            if (ratio >= 0.80D) return 2;
            if (ratio >= 0.70D) return 4;
            return Integer.MAX_VALUE;
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /** 当前堆占用率（0..1），取不到返回 -1。诊断与日志用。 */
    public static double currentHeapUsageRatio() {
        try {
            long max = Runtime.getRuntime().maxMemory();
            if (max <= 0L) return -1D;
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            return (double) used / (double) max;
        } catch (Throwable ignored) {
            return -1D;
        }
    }

    private static void refreshRoadWorkerBudget() {
        if (RoadLifecycle.isStopping()) return;
        long now = System.nanoTime();
        long refresh = NEXT_WORKER_REFRESH.get();
        if (now < refresh || !NEXT_WORKER_REFRESH.compareAndSet(refresh, now + WORKER_REFRESH_NANOS)) return;
        boolean chunky = chunkyExclusiveActive || CHUNKY_ACTIVE.invoke();
        ThreadPoolManager.setRuntimeAdaptiveThreads(recommendedRoadWorkerThreads());
        if (chunky || chunkyPreflightActive || InitialGenManager.isActive()) {
            if (gameplayStreamsLimited) {
                STREAMS_SUPPLY_THREADS.invoke(0);
                gameplayStreamsLimited = false;
            }
            gameplayStreamsTarget = -1;
            return;
        }
        // 四态 · GAMEPLAY_ACTIVE 的河流辅助线程三态:压力(HyperSpeed/交互)
        // → 1;移动 → 2;静止挂机 → 0(交外部 Streams 控制)。
        int target;
        String reason;
        if (CRUISE_ACTIVE.invoke() || INTERACTION_ACTIVE.invoke()) {
            target = 1;
            reason = "foreground pressure";
        } else if (RuntimePlayerActivity.isActive()) {
            target = 2;
            reason = "player movement";
        } else {
            target = 0;
            reason = "player-configured";
        }
        if (target != gameplayStreamsTarget) {
            STREAMS_SUPPLY_THREADS.invoke(target);
            if (target > 0 || gameplayStreamsLimited) {
                LOGGER.info("RoadWeaver gameplay auxiliary concurrency: streams={} reason={}",
                        target > 0 ? Integer.toString(target) : "player-configured", reason);
            }
            gameplayStreamsTarget = target;
            gameplayStreamsLimited = target == 1;
        }
    }

    private static void cancelIfStopping() {
        if (RoadLifecycle.isStopping()) {
            throw new CancellationException("RoadWeaver server is stopping");
        }
    }

    private static void awaitCoordinatorPermit() {
        awaitCoordinatorPermit(() -> false);
    }

    private static void awaitCoordinatorPermit(java.util.function.BooleanSupplier cancelled) {
        PauseReason current = coordinatorPauseReason();
        while (current != PauseReason.NONE && !Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            cancelIfStopping();
            logPause(current);
            LockSupport.parkNanos(PAUSE_NANOS);
            current = coordinatorPauseReason();
        }
        if (!Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            ThreadPoolManager.throttle();
        }
    }

    private static void awaitRuntimePermit() {
        awaitRuntimePermit(() -> false);
    }

    private static void awaitRuntimePermit(java.util.function.BooleanSupplier cancelled) {
        awaitRuntimePermit(cancelled, ThreadPoolManager.currentTaskRole());
    }

    private static void awaitRuntimePermit(java.util.function.BooleanSupplier cancelled,
                                           ThreadPoolManager.TaskRole originRole) {
        PauseReason current = pauseReason();
        // 0.8.360：地图读取（MAP_READ）豁免「界面让路」暂停。
        // 打开地图界面本身就会让 isScreenPauseActive() 为真，若不豁免，
        // 界面要读的 12 个瓦片会被自己触发的暂停永久挡住（实测卡在 0/12 三分钟）。
        // 只放行 PLAYER_INTERACTION；Chunky / 跑动 / 系统压力等仍照常让路。
        boolean mapReadLane = originRole == ThreadPoolManager.TaskRole.MAP_READ;
        while (current != PauseReason.NONE && !Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            if (mapReadLane && current == PauseReason.PLAYER_INTERACTION) {
                break;
            }
            cancelIfStopping();
            logPause(current);
            LockSupport.parkNanos(PAUSE_NANOS);
            current = pauseReason();
        }
        if (!Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            ThreadPoolManager.throttle();
        }
    }

    private static void beforeMapUnit() {
        beforeMapUnit(() -> false);
    }

    private static void beforeMapUnit(java.util.function.BooleanSupplier cancelled) {
        // Chunky pregeneration still needs road planning to produce ready-ahead
        // regions. Never park forever here: yield briefly instead of starving
        // the road coordinator that Chunky is waiting on.
        if (chunkyPreflightActive) return;
        if (chunkyExclusiveActive || CHUNKY_ACTIVE.invoke()) {
            cancelIfStopping();
            logPause(PauseReason.CHUNKY);
            LockSupport.parkNanos(PAUSE_NANOS);
        }
        if (Thread.currentThread().isInterrupted()) return;
        while (CRUISE_ACTIVE.invoke() && !Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
            cancelIfStopping();
            logPause(PauseReason.PLAYER_TRAVEL);
            LockSupport.parkNanos(PAUSE_NANOS);
        }
        if (Thread.currentThread().isInterrupted()) return;
        if (INTERACTION_ACTIVE.invoke()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        } else if (AUXILIARY_PAUSE.invoke()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    private static PauseReason pauseReason() {
        long now = System.nanoTime();
        long refresh = NEXT_REFRESH.get();
        if (now >= refresh && NEXT_REFRESH.compareAndSet(refresh, now + REFRESH_NANOS)) {
            // 0.8.349：界面打开也算前台压力。0.8.348 只让 GPU 粗采样让路，
            // CPU 侧后台 worker（结构解析 / 粗采样 CPU 兜底 / 回填）仍照跑 ——
            // 真机实测开背包后仍有 4s 卡顿，且日志显示那时 GPU 已是 active=0，
            // 说明残留压力来自 CPU 侧。这里补上，让 CPU worker 也在界面期间停手。
            PauseReason observed = chunkyPreflightActive
                    ? PauseReason.NONE
                    : chunkyExclusiveActive || CHUNKY_ACTIVE.invoke()
                    ? PauseReason.CHUNKY
                    : CRUISE_ACTIVE.invoke()
                    ? PauseReason.PLAYER_TRAVEL
                    : isScreenPauseActive()
                    ? PauseReason.PLAYER_INTERACTION
                    : PauseReason.NONE;
            if (observed != PauseReason.NONE) {
                RECOVERY_UNTIL.set(now + RECOVERY_NANOS);
                cached = observed;
            } else {
                cached = now < RECOVERY_UNTIL.get() ? PauseReason.RECOVERY : PauseReason.NONE;
            }
        }
        return cached;
    }

    public static int recommendedCoordinatorLanes(
            int pendingRegions,
            int readyAheadRegions,
            int currentLanes) {
        if (pendingRegions <= 0) return 0;
        if (chunkyPreflightActive) {
            // ★ 0.8.401：预热期**自决**，不再咨询 chunky-autogen。
            //
            // 【为什么删掉 ROAD_COORDINATOR_LANES 咨询】
            // 它的 recommendedRoadPlanningLanes 无条件把 critical/pressured 映射为 1，
            // 且**完全不引用 roadReadinessPreflightActive**：
            //     if (pressure.critical)  return 1;
            //     if (pressure.pressured) return 1;
            // 而预热期「有压力」是必然的（全体满负荷干活），于是
            //     min(heapCap, min(8, 1)) = 1
            // ⇒ 派发循环每 tick 只推 1 个 region ⇒ 3190 个 region 永远做不完。
            // 真机日志印证：ready 每次都只涨 1~5 个，queued 长期钉在 511。
            //
            // 【为什么这是安全的】
            // 1) 预热期 Chunky 自己 active=false、不生成区块，不存在「抢空生成 worker」
            //    的对象（那正是 0.8.372 的事故场景，发生在**正式生成期**）；
            // 2) 上限仍取 8（不是 maxRuntimeRoadThreads 的无限制），
            //    符合 0.8.372 的教训「不要开满」；
            // 3) heapPressureLaneCap 保留 —— 删掉压力咨询后，它是预热期唯一的
            //    内存保险丝；堆正常时返回 MAX_VALUE（不构成限速）。
            int lanes = Math.max(1, Math.min(heapPressureLaneCap(), 8));
            logPreflightLanesOnce(pendingRegions, lanes);
            return lanes;
        }
        if (chunkyExclusiveActive || CHUNKY_ACTIVE.invoke()) return 1;
        if (CRUISE_ACTIVE.invoke()) return 0;
        // 显式暂停优先于别的提供方的正数建议；开局预热和 Chunky readiness 已在前面放行。
        if (ROAD_COORDINATOR_PAUSE.invoke()) return 0;
        if (INTERACTION_ACTIVE.invoke()) return 1;
        Integer recommendation = ROAD_COORDINATOR_LANES.invoke(
                pendingRegions, readyAheadRegions, currentLanes);
        if (recommendation != null) return Math.max(0, Math.min(4, recommendation));
        if (readyAheadRegions > 0) return 1;
        if (pendingRegions >= 12) return 4;
        return pendingRegions >= 3 ? 2 : 1;
    }

    public static void setStreamsSupplyThreads(int threads) {
        STREAMS_SUPPLY_THREADS.invoke(Math.max(0, threads));
    }

    /**
     * 预热期 lane 决策日志（★ 0.8.401，限频：每 5 秒最多一条）。
     *
     * <p>加这条的直接原因：本次排查中 <b>完全看不到</b> lane 是怎么算出来的 ——
     * 只能从 chunky-autogen 的 QoS 行间接推断，导致三方分析出现分歧。
     * 现在把「预热自决」的输入与输出明确打出来。</p>
     */
    private static final AtomicLong NEXT_PREFLIGHT_LANES_LOG = new AtomicLong();
    private static final long PREFLIGHT_LANES_LOG_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static void logPreflightLanesOnce(int pendingRegions, int lanes) {
        long now = System.nanoTime();
        long next = NEXT_PREFLIGHT_LANES_LOG.get();
        if (now < next || !NEXT_PREFLIGHT_LANES_LOG.compareAndSet(next, now + PREFLIGHT_LANES_LOG_NANOS)) {
            return;
        }
        LOGGER.info("RoadWeaver Chunky preflight lanes (自决，不咨询 chunky-autogen): "
                        + "pending={} heapCap={} heapRatio={} chosen={}",
                pendingRegions, heapPressureLaneCap(),
                String.format(java.util.Locale.ROOT, "%.1f%%", currentHeapUsageRatio() * 100.0),
                lanes);
    }

    private static PauseReason coordinatorPauseReason() {
        if (chunkyPreflightActive) return PauseReason.NONE;
        if (chunkyExclusiveActive || CHUNKY_ACTIVE.invoke()) return PauseReason.CHUNKY;
        long now = System.nanoTime();
        long refresh = NEXT_COORDINATOR_REFRESH.get();
        if (now >= refresh
                && NEXT_COORDINATOR_REFRESH.compareAndSet(refresh, now + REFRESH_NANOS)) {
            cachedCoordinator = CRUISE_ACTIVE.invoke()
                    ? PauseReason.PLAYER_TRAVEL
                    : ROAD_COORDINATOR_PAUSE.invoke()
                    ? PauseReason.SYSTEM_PRESSURE
                    : PauseReason.NONE;
        }
        return cachedCoordinator;
    }

    private static void logPause(PauseReason reason) {
        long now = System.nanoTime();
        long next = NEXT_PAUSE_LOG.get();
        if (now < next || !NEXT_PAUSE_LOG.compareAndSet(next, now + LOG_INTERVAL_NANOS)) return;
        LOGGER.info("RoadWeaver runtime work paused for {}.", switch (reason) {
            case PLAYER_TRAVEL -> "player travel protection";
            case CHUNKY -> "active Chunky task";
            case PLAYER_INTERACTION -> "player interaction priority";
            case SYSTEM_PRESSURE -> "runtime Tick/FPS/heap pressure";
            case RECOVERY -> "foreground recovery cooldown";
            case NONE -> "normal scheduling";
        });
    }

    private enum PauseReason {
        NONE,
        PLAYER_TRAVEL,
        CHUNKY,
        PLAYER_INTERACTION,
        SYSTEM_PRESSURE,
        RECOVERY
    }

}
