package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import net.shiroha233.roadweaver.pathfinding.sampling.opencl.bridge.OpenCLBridge;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime budget for coarse OpenCL requests.
 *
 * The OpenCL session is still serialized by OpenCLSubmissionGate. This class
 * only decides how many coarse requests may be admitted to the GPU path before
 * excess work is diverted to CPU, and changes that budget gradually as load
 * changes. A single GPU request is always retained outside Chunky-exclusive
 * generation so a busy foreground cannot disable the accelerator completely.
 */
public final class RuntimeCoarseGpuBudget {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MIN_BUDGET = 1;
    private static final int HARD_MAX_BUDGET = 16;
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long RECOVERY_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long LOG_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final AtomicLong NEXT_REFRESH = new AtomicLong();
    private static final AtomicLong LOW_PRESSURE_SINCE = new AtomicLong();
    private static final AtomicLong NEXT_LOG = new AtomicLong();
    private static final AtomicLong LAST_GPU_SUBMIT = new AtomicLong();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final AtomicInteger BUDGET = new AtomicInteger(MIN_BUDGET);
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile int hardwareMaximum = MIN_BUDGET;
    private static volatile Pressure lastPressure = Pressure.NORMAL;

    private RuntimeCoarseGpuBudget() {
    }

    /**
     * Waits for a GPU concurrency slot instead of diverting queued work to the
     * slower CPU. The wait is interruptible so shutdown/cancellation cannot
     * strand a road worker indefinitely.
     *
     * <p>The coarse submit interval gate (idle/active minimum spacing between
     * GPU submissions) is <b>not</b> charged here any more. It is charged once
     * per logical tile by {@link #awaitTileSubmitInterval()}, so banded tile
     * sampling (one tile split into {@code coarseHeightBandCount} bands) is no
     * longer multiplied by the band count.</p>
     */
    static Permit acquire(OpenCLRuntime runtime) {
        if (runtime == null || !runtime.isUsable()) {
            return null;
        }
        while (!Thread.currentThread().isInterrupted()) {
            if (!runtime.isUsable()) {
                return null;
            }
            if (RuntimeLoadPolicy.shouldPauseOpenClCoarseSampling()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                continue;
            }
            // 0.8.344：RoadWeaver 地图界面打开时让路（地图优先）。
            // 真机取证：粗采样连续占满 GPU 25 分钟（nvidia-smi 100%），
            // 玩家开地图时两者抢同一块 GPU → 地图明显卡顿。
            // 仅靠提交间隔挡不住（187 万样本的区域分约 100 批 ≈ 150 秒持续占用），
            // 因此在源头上等待：地图一关立即恢复采样。
            // 0.8.348：闸门由"仅地图"推广为"地图或任意 GUI"（背包/ESC/熔炉/工作台/设置）。
            // 真机取证（20260912 夜）：开背包/ESC 时玩家不移动、也不发服务端交互包，
            // INTERACTION_ACTIVE 与 RuntimePlayerActivity 都不亮，且当时预算**已经是**
            // budget=1/1 pressure=HIGH（节流已完全生效却仍卡），故必须走暂停而非再调预算。
            if (shouldPauseForScreen()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                continue;
            }
            refresh(runtime);
            int budget = BUDGET.get();
            int active = ACTIVE.get();
            if (active >= budget) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                continue;
            }
            if (ACTIVE.compareAndSet(active, active + 1)) {
                return new Permit();
            }
        }
        throw new CancellationException("Interrupted while waiting for runtime OpenCL coarse GPU budget");
    }

    /**
     * Charges the coarse GPU submit interval gate once per logical tile.
     *
     * <p>The interval gate decides "when the next tile may submit", not "how
     * many bands a tile is split into". A tile sampled as {@code N} bands calls
     * {@link #acquire} once per band, so if {@code LAST_GPU_SUBMIT} were still
     * refreshed inside {@code acquire} the gate would be multiplied by
     * {@code N}. This method is therefore invoked exactly once per tile by
     * {@code CoarseTerrainTileSampler.sampleHeightsBanded} before the first
     * band, and records the tile's submit time after the wait.</p>
     *
     * <p>Startup / Chunky are exempt: those phases do not enter the idle or
     * foreground-protected branches, so this method only records the timestamp
     * without waiting (matching the previous acquire behavior).</p>
     */
    public static void awaitTileSubmitInterval() {
        while (!Thread.currentThread().isInterrupted()) {
            long minimum;
            if (RuntimeLoadPolicy.isGameplayIdle()) {
                minimum = gpuMinIntervalMs();
            } else if (RuntimeLoadPolicy.isGameplayForegroundProtected()) {
                // 0.8.341：游玩**活跃**态（玩家移动/交互/巡航）的提交间隔闸。
                // 原来只有挂机态有闸；本版起游玩期也开放 GPU 粗采样，
                // 若活跃态不设闸就会"背靠背连续提交"，比挂机态更抖。
                // 活跃态玩家正在操作、对延迟更敏感，因此单独用更保守的键。
                minimum = gpuActiveMinIntervalMs();
            } else {
                break;
            }
            long last = LAST_GPU_SUBMIT.get();
            if (minimum <= 0L || last == 0L) {
                break;
            }
            long elapsedMs = (System.nanoTime() - last) / 1_000_000L;
            if (elapsedMs < minimum) {
                LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(Math.max(1L, minimum - elapsedMs)));
                continue;
            }
            break;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Interrupted while waiting for coarse GPU tile submit interval");
        }
        LAST_GPU_SUBMIT.set(System.nanoTime());
    }

    private static long gpuMinIntervalMs() {
        try {
            return ConfigService.get().performance().runtimeCoarseGpuMinIntervalMs();
        } catch (Throwable ignored) {
            return 1500L;
        }
    }

    /** 0.8.341：游玩活跃态的提交间隔（与挂机态分开设键，活跃态通常更保守）。 */
    private static long gpuActiveMinIntervalMs() {
        try {
            return ConfigService.get().performance().runtimeCoarseGpuActiveMinIntervalMs();
        } catch (Throwable ignored) {
            return 1500L;
        }
    }

    /**
     * 0.8.344：是否在 RoadWeaver 地图打开时暂停 GPU 粗采样（默认开启）。
     * 读不到配置时按 true 处理 —— 让路是更安全的一侧（宁可慢，不要卡）。
     */
    private static boolean mapOpenPauseEnabled() {
        try {
            return ConfigService.get().performance().pauseCoarseGpuWhileMapOpen();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 0.8.348：是否在**任意客户端界面**（背包/ESC/熔炉/工作台/设置）打开时暂停 GPU 粗采样。
     * 默认开启。读不到配置时按 true 处理。
     */
    private static boolean guiOpenPauseEnabled() {
        try {
            return ConfigService.get().performance().pauseCoarseGpuWhileGuiOpen();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 0.8.348：界面让路闸是否应暂停 —— **两个闸独立判定**。
     *
     * <p>地图闸（0.8.344）与 GUI 闸（0.8.348）各自可由用户单独关闭，
     * 因此不能把两者 OR 成一个开关（那样关掉 GUI 闸仍会被地图闸拦住）。</p>
     */
    private static boolean shouldPauseForScreen() {
        if (!RuntimeLoadPolicy.isScreenPauseActive()) return false;
        if (mapOpenPauseEnabled() && RuntimeLoadPolicy.isMapScreenOpen()) return true;
        // 地图本身也是 GUI 的一种，但它有自己的专属开关；此处覆盖的是
        // 背包/ESC/熔炉/工作台/设置等非地图界面。
        return guiOpenPauseEnabled() && RuntimeLoadPolicy.isGuiScreenOpen();
    }

    private static void refresh(OpenCLRuntime runtime) {
        long now = System.nanoTime();
        long next = NEXT_REFRESH.get();
        if (now < next || !NEXT_REFRESH.compareAndSet(next, now + REFRESH_NANOS)) {
            return;
        }
        int hardwareMaximumValue = hardwareMaximum(runtime.deviceInfo());
        // 四态状态机:
        // STARTUP / CHUNKY_PREFLIGHT → 硬件上限(开满,不加间隔);
        // CHUNKY_RUNNING → 压到 runtimeCoarseMinThreads(默认 1),不与 Chunky 抢 GPU;
        // GAMEPLAY_ACTIVE → 前台保护,压到 runtimeCoarseMinThreads(默认 1);
        //                   0.8.341 起若配置开放游玩期 GPU(runtimeCoarseUseCpuInGameplay=false),
        //                   该态也会真正走 GPU,并由 acquire 里的**活跃态提交间隔闸**节流;
        //                   若配置未开放,粗采样本体已转 CPU,此处预算实际不生效。
        // GAMEPLAY_IDLE → runtimeCoarseMaxThreads(默认 2)+ 挂机态提交间隔闸(acquire)。
        boolean chunkyRunning = RuntimeLoadPolicy.isChunkyRunning();
        boolean normalGameplay = !InitialGenManager.isActive()
                && !RuntimeLoadPolicy.isChunkyPreflightActive()
                && !chunkyRunning
                && !RuntimeLoadPolicy.shouldPauseOpenClCoarseSampling();
        boolean gameplayProtected = normalGameplay
                && RuntimeLoadPolicy.isGameplayForegroundProtected();
        int minimumThreads = configuredMinThreads();
        int maximum;
        if (gameplayProtected) {
            maximum = Math.max(MIN_BUDGET, Math.min(hardwareMaximumValue, minimumThreads));
        } else if (chunkyRunning) {
            maximum = Math.max(MIN_BUDGET, Math.min(hardwareMaximumValue, minimumThreads));
        } else if (normalGameplay) {
            maximum = applyIdleCap(hardwareMaximumValue);
        } else {
            maximum = hardwareMaximumValue;
        }
        hardwareMaximum = maximum;
        Pressure pressure = observePressure();
        int old = BUDGET.get();
        if (INITIALIZED.compareAndSet(false, true)) {
            int initial = gameplayProtected || pressure.level >= 2 || chunkyRunning ? Math.min(maximum, minimumThreads) : maximum;
            BUDGET.set(initial);
            LOW_PRESSURE_SINCE.set(now);
            lastPressure = pressure;
            LOGGER.info("RoadWeaver runtime GPU coarse budget initialized: budget={}/{} configuredCap={} phase={} pressure={} device={}",
                    initial, maximum, configuredCapLabel(normalGameplay), RuntimeLoadPolicy.currentPhase(), pressure.name(), runtime.deviceName());
            return;
        }
        int nextBudget = old;
        if (gameplayProtected || chunkyRunning) {
            nextBudget = Math.min(maximum, minimumThreads);
            LOW_PRESSURE_SINCE.set(0L);
        } else if (pressure.level >= 2) {
            LOW_PRESSURE_SINCE.set(0L);
            nextBudget = Math.max(MIN_BUDGET, old - 1);
        } else if (pressure == Pressure.NORMAL) {
            long stableSince = LOW_PRESSURE_SINCE.get();
            if (stableSince == 0L) {
                LOW_PRESSURE_SINCE.compareAndSet(0L, now);
            } else if (now - stableSince >= RECOVERY_NANOS) {
                nextBudget = Math.min(maximum, old + 1);
                LOW_PRESSURE_SINCE.set(now);
            }
        }
        nextBudget = Math.max(MIN_BUDGET, Math.min(maximum, nextBudget));
        BUDGET.set(nextBudget);
        lastPressure = pressure;
        if (nextBudget != old || shouldLog(now)) {
            LOGGER.info("RoadWeaver runtime GPU coarse budget changed: budget={}/{} configuredCap={} active={} phase={} pressure={} heap={}%% device={}",
                    nextBudget, maximum, configuredCapLabel(normalGameplay), ACTIVE.get(),
                    RuntimeLoadPolicy.currentPhase(), pressure.name(), heapPercent(), runtime.deviceName());
        }
    }

    /** 四态 · GAMEPLAY_IDLE 的 GPU 并发上限:runtimeCoarseMaxThreads(默认 2),0 = 旧自动逻辑。 */
    private static int applyIdleCap(int hardwareMaximumValue) {
        try {
            int idleCap = ConfigService.get().performance().runtimeCoarseMaxThreads();
            if (idleCap > 0) {
                return Math.max(MIN_BUDGET, Math.min(hardwareMaximumValue, idleCap));
            }
        } catch (Throwable ignored) {
        }
        return applyConfiguredCap(hardwareMaximumValue);
    }

    private static int configuredMinThreads() {
        try {
            return Math.max(MIN_BUDGET, Math.min(HARD_MAX_BUDGET,
                    ConfigService.get().performance().runtimeCoarseMinThreads()));
        } catch (Throwable ignored) {
            return MIN_BUDGET;
        }
    }

    private static int applyConfiguredCap(int hardwareMaximumValue) {
        int configured = ConfigService.get().performance().openclCoarseGpuConcurrencyCap();
        return configured <= 0
                ? hardwareMaximumValue
                : Math.max(MIN_BUDGET, Math.min(hardwareMaximumValue, configured));
    }

    private static String configuredCapLabel(boolean normalGameplay) {
        if (!normalGameplay) return "phase-default";
        int configured = ConfigService.get().performance().openclCoarseGpuConcurrencyCap();
        return configured <= 0 ? "auto" : Integer.toString(configured);
    }

    private static boolean shouldLog(long now) {
        long next = NEXT_LOG.get();
        return now >= next && NEXT_LOG.compareAndSet(next, now + LOG_NANOS);
    }

    private static int hardwareMaximum(OpenCLBridge.DeviceInfo info) {
        if (info == null || !info.gpu()) {
            return MIN_BUDGET;
        }
        long memory = info.globalMemoryBytes();
        if (memory <= 0L) {
            return Math.min(HARD_MAX_BUDGET, Math.max(MIN_BUDGET,
                    Runtime.getRuntime().availableProcessors() / 2));
        }
        // A coarse request owns temporary buffers; reserve half of device RAM.
        long slots = Math.max(1L, (memory / 2L) / (256L * 1024L * 1024L));
        return (int) Math.max(MIN_BUDGET, Math.min(HARD_MAX_BUDGET, slots));
    }

    private static Pressure observePressure() {
        int level = 0;
        if (RuntimeLoadPolicy.isForegroundPressureActive()) {
            level = Math.max(level, 2);
        }
        int heap = heapPercent();
        if (heap >= 90) {
            level = Math.max(level, 3);
        } else if (heap >= 80) {
            level = Math.max(level, 2);
        }
        return level >= 3 ? Pressure.CRITICAL : level == 2 ? Pressure.HIGH
                : level == 1 ? Pressure.ELEVATED : Pressure.NORMAL;
    }

    private static int heapPercent() {
        try {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            MemoryUsage usage = bean.getHeapMemoryUsage();
            long max = usage.getMax() > 0 ? usage.getMax() : usage.getCommitted();
            return max <= 0 ? 0 : (int) Math.min(100L, usage.getUsed() * 100L / max);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static final class Permit implements AutoCloseable {
        private boolean released;

        @Override
        public void close() {
            if (!released) {
                released = true;
                ACTIVE.updateAndGet(value -> Math.max(0, value - 1));
            }
        }
    }

    private enum Pressure {
        NORMAL(0), ELEVATED(1), HIGH(2), CRITICAL(3);

        private final int level;

        Pressure(int level) {
            this.level = level;
        }
    }
}
