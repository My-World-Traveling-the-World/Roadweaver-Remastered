package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 共享优先级工作池管理器。
 */
public final class ThreadPoolManager {
    private ThreadPoolManager() {}

    public enum TaskRole {
        INITIAL(0),
        MAP_READ(2),
        STRUCTURE(4),
        COORDINATOR(5),
        // Dedicated lane for the structure-snapshot predictions that road
        // tasks synchronously wait on: a road worker occupying the shared
        // pool's only worker must never also be owed a subtask from it.
        SNAPSHOT(6),
        PLANNING(10),
        COARSE(10),
        GENERATION(20),
        MAP(30),
        POSTPROCESS(30),
        IDLE(40);

        private final int priority;

        TaskRole(int priority) {
            this.priority = priority;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private static final AtomicReference<ThreadPoolExecutor> INITIAL_WORKERS = new AtomicReference<>();
    private static final AtomicReference<ThreadPoolExecutor> SHARED_EXEC = new AtomicReference<>();
    private static final AtomicReference<ThreadPoolExecutor> STRUCTURE_WORKERS = new AtomicReference<>();
    private static final AtomicReference<ThreadPoolExecutor> SNAPSHOT_WORKERS = new AtomicReference<>();
    private static final AtomicReference<ThreadPoolExecutor> MAP_WORKERS = new AtomicReference<>();
    private static final AtomicReference<ThreadPoolExecutor> MAP_READ_WORKERS = new AtomicReference<>();
    /**
     * 0.8.357：这些线程池服务于**玩家主动打开的界面**（道路地图及其数据读取），
     * 保持普通优先级，不参与后台线程的统一降级。详见 {@link #namedFactory}。
     *
     * <p>注意 {@code RW-TileLoader}（地图纹理）由 {@code ClientMapTileTextureCache}
     * 自建、本就不设优先级，因此不在此列。</p>
     */
    private static final java.util.Set<String> MAP_PRIORITY_PREFIXES =
            java.util.Set.of("RW-MapRead", "RW-Map");
    private static final AtomicLong EPOCH = new AtomicLong(0L);
    private static final AtomicLong TASK_SEQ = new AtomicLong(0L);
    private static final AtomicReference<StartupHardwareProfile> STARTUP_PROFILE = new AtomicReference<>();
    private static int runtimeAdaptiveThreads = 1;
    private static int chunkySupplyThreads;

    private static final ExecutorService INITIAL_EXEC = new RoleExecutorService(TaskRole.INITIAL);
    private static final ExecutorService MAP_READ_EXEC = new RoleExecutorService(TaskRole.MAP_READ);
    private static final ExecutorService STRUCTURE_EXEC = new RoleExecutorService(TaskRole.STRUCTURE);
    private static final ExecutorService SNAPSHOT_EXEC = new RoleExecutorService(TaskRole.SNAPSHOT);
    private static final ExecutorService COORDINATOR_EXEC = new RoleExecutorService(TaskRole.COORDINATOR);
    private static final ExecutorService PLANNING_EXEC = new RoleExecutorService(TaskRole.PLANNING);
    private static final ExecutorService COARSE_EXEC = new RoleExecutorService(TaskRole.COARSE);
    private static final ExecutorService GENERATION_EXEC = new RoleExecutorService(TaskRole.GENERATION);
    private static final ExecutorService MAP_EXEC = new RoleExecutorService(TaskRole.MAP);
    private static final ExecutorService POSTPROCESS_EXEC = new RoleExecutorService(TaskRole.POSTPROCESS);
    private static final ExecutorService IDLE_EXEC = new RoleExecutorService(TaskRole.IDLE);

    private static final ThreadLocal<Long> WORK_START = ThreadLocal.withInitial(System::currentTimeMillis);
    private static final ThreadLocal<TaskRole> CURRENT_ROLE = new ThreadLocal<>();

    // ==================== 生命周期 ====================

    public static synchronized void onServerStarted(MinecraftServer server) {
        EPOCH.incrementAndGet();
        StartupHardwareProfile startupProfile = resolveStartupHardwareProfile();
        STARTUP_PROFILE.set(startupProfile);
        runtimeAdaptiveThreads = resolveSharedWorkerThreads();
        chunkySupplyThreads = 0;
        rebuildInitialPool(startupProfile.roadWorkers());
        rebuildSharedPool(runtimeAdaptiveThreads);
        rebuildStructurePool(startupProfile.structureWorkers());
        rebuildSnapshotPool();
        rebuildMapPool(resolveMapWorkerThreads());
        rebuildMapReadPool();
        var performance = ConfigService.get().performance();
        LOGGER.info("RoadWeaver adaptive startup profile: processors={}, maxHeapMiB={}, roads={}, coarse={}, "
                        + "structures={}, nativeStructures={}, startupCap={}, runtimeCap={}, runtimeConcurrency={}, "
                        + "idleConcurrency={}, chunkyRoadCap={}.",
                startupProfile.processors(), startupProfile.maxHeapMiB(), startupProfile.roadWorkers(),
                startupProfile.coarseWorkers(), startupProfile.structureWorkers(),
                startupProfile.nativeStructureWorkers(), configuredInitialGenerationLimit(),
                performance.sharedWorkerThreads(), performance.maxConcurrentGenerations(),
                performance.idleConcurrentGenerations(), performance.chunkyRoadThreadCap());
    }

    public static synchronized void onServerStopping() {
        EPOCH.incrementAndGet();
        chunkySupplyThreads = 0;
        shutdownQuietly(INITIAL_WORKERS.get());
        shutdownQuietly(SHARED_EXEC.get());
        shutdownQuietly(STRUCTURE_WORKERS.get());
        shutdownQuietly(SNAPSHOT_WORKERS.get());
        shutdownQuietly(MAP_WORKERS.get());
        shutdownQuietly(MAP_READ_WORKERS.get());
        INITIAL_WORKERS.set(null);
        SHARED_EXEC.set(null);
        STRUCTURE_WORKERS.set(null);
        SNAPSHOT_WORKERS.set(null);
        MAP_WORKERS.set(null);
        MAP_READ_WORKERS.set(null);
        STARTUP_PROFILE.set(null);
        LOGGER.debug("ThreadPoolManager: 工作池已关闭 (epoch={})", EPOCH.get());
    }

    // ==================== 运行时重建 ====================

    public static synchronized void resizeInitialPool(int threads) {
        int resolved = threads <= 0 ? resolveInitialGenerationThreads() : Math.max(1, threads);
        rebuildInitialPool(resolved);
    }

    public static synchronized void resizeSharedPool(int threads) {
        int resolved = threads <= 0 ? resolveSharedWorkerThreads() : Math.max(1, threads);
        rebuildSharedPool(resolved);
    }

    /**
     * Restores the startup hardware budget after runtime QoS has reduced one
     * of the pools while the world was preparing to enter. Initial generation
     * is a bounded gate, so no player-facing runtime work should shrink its
     * road or exact-structure lanes underneath it.
     */
    public static synchronized void prepareInitialGeneration() {
        if (RoadLifecycle.isStopping()) return;
        StartupHardwareProfile profile = startupHardwareProfile();
        ThreadPoolExecutor initial = INITIAL_WORKERS.get();
        if (initial == null || initial.isShutdown()
                || initial.getCorePoolSize() != profile.roadWorkers()) {
            rebuildInitialPool(profile.roadWorkers());
        }
        ThreadPoolExecutor structure = STRUCTURE_WORKERS.get();
        if (structure == null || structure.isShutdown()
                || structure.getCorePoolSize() != profile.structureWorkers()) {
            rebuildStructurePool(profile.structureWorkers());
        }
        setSharedRuntimeThreads(Math.max(
                configuredSharedWorkerThreads(), profile.roadWorkers()));
        LOGGER.info("RoadWeaver initial generation hardware lanes prepared: roads={} structures={} shared={}",
                profile.roadWorkers(), profile.structureWorkers(), currentSharedRuntimeThreads());
    }

    /**
     * Adjusts the existing shared worker pool without cancelling queued or
     * running planning work. Chunky readiness uses this for a small temporary
     * supply boost; ordinary runtime work returns it to the configured size.
     */
    public static synchronized void setSharedRuntimeThreads(int requestedThreads) {
        if (RoadLifecycle.isStopping()) return;
        int target = Math.max(1, Math.min(maxRuntimeRoadThreads(), requestedThreads));
        ThreadPoolExecutor executor = sharedExecutor();
        applySharedRuntimeThreads(executor, target);
    }

    public static synchronized void setRuntimeAdaptiveThreads(int requestedThreads) {
        if (RoadLifecycle.isStopping()) return;
        runtimeAdaptiveThreads = clampRuntimeThreads(requestedThreads);
        applyResolvedRuntimeThreads();
    }

    public static synchronized void setChunkySupplyThreads(int requestedThreads) {
        if (RoadLifecycle.isStopping()) return;
        chunkySupplyThreads = requestedThreads <= 0 ? 0 : clampRuntimeThreads(requestedThreads);
        applyResolvedRuntimeThreads();
    }

    static int resolveRuntimeThreadTarget(int adaptiveThreads, int supplyThreads, int maximum) {
        int boundedMaximum = Math.max(1, maximum);
        int adaptive = Math.max(1, Math.min(boundedMaximum, adaptiveThreads));
        if (supplyThreads <= 0) return adaptive;
        int supply = Math.max(1, Math.min(boundedMaximum, supplyThreads));
        // Chunky readiness is a guaranteed supply floor. Treating it as a cap
        // can shrink the shared pool to one worker while that worker waits for
        // a nested planning task, permanently stalling the final region.
        return Math.max(adaptive, supply);
    }

    private static int clampRuntimeThreads(int requestedThreads) {
        return Math.max(1, Math.min(maxRuntimeRoadThreads(), requestedThreads));
    }

    private static void applyResolvedRuntimeThreads() {
        int target = resolveRuntimeThreadTarget(
                runtimeAdaptiveThreads, chunkySupplyThreads, maxRuntimeRoadThreads());
        applySharedRuntimeThreads(sharedExecutor(), target);
        applyStructureRuntimeThreads(
                structureExecutor(), Math.min(target, maxRuntimeStructureThreads()));
    }

    // ==================== 0.8.345：池子容量去抖 ====================

    /**
     * 容量变更的稳定窗口。目标值需在这个时间内**保持不变**才真正应用到池子。
     *
     * <p>真机取证（20260911，玩家 12 核 / 9.6G 堆，4 小时单局）：
     * RoadWeaver 自己的容量变更共 **14 次**（shared 7 + structure 7），
     * 分布为 {@code 2->6}/{@code 2->10}/{@code 1->2} 各 2 次等，属<b>平稳</b>变化。</p>
     *
     * <p>⚠️ 更正：分析初期曾据一次错误的正则统计得出"3369 次抖动、3&harr;1 交替 1398 次"，
     * 后经复核证伪 —— 那 3369 条里绝大多数是 **Streams mod 自己的**
     * {@code Streams Reflowing runtime adaptive capacity changed} 日志，与 RoadWeaver 无关。
     * 因此本去抖<b>不是</b>卡顿的主因修复，而是<b>防御性改进</b>：
     * 每次变更都会 {@code prestartAllCoreThreads()}（重建全部核心线程）并写一条 INFO 日志，
     * 而同秒内 shared 与 structure 会一起变（真机 22:04:46 可见），
     * 短期内反复变仍会带来不必要的线程重建。</p>
     *
     * <p>真正的游玩期卡顿主因是<b>并发过高 + 堆打满</b>（9~12 路同时解析 5994 个 starts、
     * 堆 9632MiB 打满导致 GC 频繁 → 单批从 1.2 秒退化到 35 秒），
     * 由 {@code gameplayThreadCap}（0.8.344）与后续并发治理应对。</p>
     *
     * <p>去抖语义：<b>先记录意图，稳定满 {@link #CAPACITY_SETTLE_NANOS} 才生效</b>。
     * 抖动期间保持当前容量不变，把短期反复变更收敛为一次。
     * 窗口取 3 秒：远小于正常相位切换（数分钟），又能吃掉秒级抖动。</p>
     */
    private static final long CAPACITY_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private static final AtomicLong PENDING_SHARED_TARGET = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong SHARED_STABLE_SINCE = new AtomicLong();
    private static final AtomicLong PENDING_STRUCTURE_TARGET = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong STRUCTURE_STABLE_SINCE = new AtomicLong();

    /** 仅供测试：清空去抖状态。 */
    static void resetCapacityDebounceForTest() {
        PENDING_SHARED_TARGET.set(Long.MIN_VALUE);
        SHARED_STABLE_SINCE.set(0L);
        PENDING_STRUCTURE_TARGET.set(Long.MIN_VALUE);
        STRUCTURE_STABLE_SINCE.set(0L);
    }

    /**
     * @return true 表示这次变更应当真正应用（目标已稳定足够久）
     */
    private static boolean capacitySettled(AtomicLong pending, AtomicLong stableSince, int target) {
        long now = System.nanoTime();
        long previous = pending.get();
        if (previous != target) {
            // 目标变了 → 重新计时，本次不应用。
            pending.set(target);
            stableSince.set(now);
            return false;
        }
        long since = stableSince.get();
        if (since == 0L) {
            stableSince.set(now);
            return false;
        }
        return now - since >= CAPACITY_SETTLE_NANOS;
    }

    private static void applySharedRuntimeThreads(ThreadPoolExecutor executor, int target) {
        int current = executor.getCorePoolSize();
        if (current == target && executor.getMaximumPoolSize() == target) {
            // 已达标：清掉待应用状态，避免下次抖动误判为"刚稳定"。
            PENDING_SHARED_TARGET.set(Long.MIN_VALUE);
            SHARED_STABLE_SINCE.set(0L);
            return;
        }
        if (!capacitySettled(PENDING_SHARED_TARGET, SHARED_STABLE_SINCE, target)) {
            // 0.8.345：目标尚未稳定 —— 保持当前容量，不 prestartAllCoreThreads。
            return;
        }
        if (target > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(target);
            executor.setCorePoolSize(target);
        } else {
            executor.setCorePoolSize(target);
            executor.setMaximumPoolSize(target);
        }
        executor.prestartAllCoreThreads();
        PENDING_SHARED_TARGET.set(Long.MIN_VALUE);
        SHARED_STABLE_SINCE.set(0L);
        LOGGER.info("RoadWeaver shared runtime worker capacity changed: {} -> {}.", current, target);
    }

    private static void applyStructureRuntimeThreads(ThreadPoolExecutor executor, int target) {
        int bounded = Math.max(1, target);
        int current = executor.getCorePoolSize();
        if (current == bounded && executor.getMaximumPoolSize() == bounded) {
            PENDING_STRUCTURE_TARGET.set(Long.MIN_VALUE);
            STRUCTURE_STABLE_SINCE.set(0L);
            return;
        }
        // 0.8.345：与 shared 池同样去抖 —— 真机日志里 structure 池的变更同样平稳
        // （4 小时 7 次），但两池会在同一秒一起变（22:04:46 可见），
        // 同步去抖可避免短期内重复的 prestartAllCoreThreads。
        if (!capacitySettled(PENDING_STRUCTURE_TARGET, STRUCTURE_STABLE_SINCE, bounded)) {
            return;
        }
        if (bounded > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(bounded);
            executor.setCorePoolSize(bounded);
        } else {
            executor.setCorePoolSize(bounded);
            executor.setMaximumPoolSize(bounded);
        }
        executor.prestartAllCoreThreads();
        PENDING_STRUCTURE_TARGET.set(Long.MIN_VALUE);
        STRUCTURE_STABLE_SINCE.set(0L);
        LOGGER.info("RoadWeaver structure runtime worker capacity changed: {} -> {}.", current, bounded);
    }

    public static int configuredSharedWorkerThreads() {
        return resolveSharedWorkerThreads();
    }

    public static int currentSharedRuntimeThreads() {
        ThreadPoolExecutor executor = sharedExecutor();
        return Math.max(1, executor.getMaximumPoolSize());
    }

    public static int initialGenerationThreadCapacity() {
        StartupHardwareProfile profile = startupHardwareProfile();
        return profile.roadWorkers();
    }

    public static int startupCoarseThreadCapacity() {
        return startupHardwareProfile().coarseWorkers();
    }

    public static int startupNativeStructureThreadCapacity() {
        return startupHardwareProfile().nativeStructureWorkers();
    }

    /**
     * P0-7: the old {@code processors / 2} budget left a 16-core machine at
     * eight runtime road workers even when the initial-generation lane was
     * allowed sixteen. Reserving two cores keeps the server thread and the OS
     * comfortable while letting the pool scale with the machine.
     */
    public static int maxRuntimeRoadThreads() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(12, Math.max(1, processors - 2)));
    }

    public static int maxRuntimeStructureThreads() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(2, Math.min(8, processors / 2));
    }

    public static synchronized void resizeGenerationPool(int threads) {
        resizeSharedPool(threads);
    }

    public static synchronized void resizeComputePool(int threads) {
        resizeSharedPool(threads);
    }

    // ==================== Executor 访问 ====================

    public static ExecutorService computeExecutor() {
        return roleExecutor(TaskRole.PLANNING);
    }

    public static ExecutorService generationExecutor() {
        return roleExecutor(TaskRole.GENERATION);
    }

    /**
     * 取某个角色**应该用的执行器**（对外唯一入口）。
     *
     * <h2>★ 本方法 vs {@code executorFor}：两个方法职责不同，极易被误读</h2>
     * 这里有两个名字很像、职责完全不同的方法。**2026-09-25 第三轮外部审计
     * 把它们当成"映射表不一致"报了一条 P2，复核后是误报** —— 特此写清避免重蹈：
     *
     * <pre>
     *   roleExecutor(role)   ← 对外门面：11 个角色**每个都有**一个 RoleExecutorService
     *                          （它自己再决定落到哪个物理池）
     *   executorFor(role)    ← 内部实现：这个角色该落到哪个**物理** ThreadPoolExecutor。
     *                          只显式列出 5 个「有独立物理池」的角色，
     *                          其余 default -&gt; sharedExecutor() 是**有意的**
     * </pre>
     *
     * <p>物理池共 **6 个**：INITIAL / MAP_READ / STRUCTURE / SNAPSHOT / MAP 五个专用
     * + SHARED 一个共享兜底，与 6 个 {@code *_WORKERS} / {@code SHARED_EXEC} 引用一一对应。</p>
     */
    public static ExecutorService roleExecutor(TaskRole role) {
        return switch (role == null ? TaskRole.PLANNING : role) {
            case INITIAL -> INITIAL_EXEC;
            case MAP_READ -> MAP_READ_EXEC;
            case STRUCTURE -> STRUCTURE_EXEC;
            case SNAPSHOT -> SNAPSHOT_EXEC;
            case COORDINATOR -> COORDINATOR_EXEC;
            case PLANNING -> PLANNING_EXEC;
            case COARSE -> COARSE_EXEC;
            case GENERATION -> GENERATION_EXEC;
            case MAP -> MAP_EXEC;
            case POSTPROCESS -> POSTPROCESS_EXEC;
            case IDLE -> IDLE_EXEC;
        };
    }

    public static void execute(TaskRole role, Runnable runnable) {
        roleExecutor(role).execute(runnable);
    }

    public static java.util.concurrent.Future<?> submit(TaskRole role, Runnable runnable) {
        return roleExecutor(role).submit(runnable);
    }

    public static <T> java.util.concurrent.Future<T> submit(TaskRole role, java.util.concurrent.Callable<T> callable) {
        return roleExecutor(role).submit(callable);
    }

    public static CompletableFuture<Void> runAsync(TaskRole role, Runnable runnable) {
        return CompletableFuture.runAsync(runnable, roleExecutor(role));
    }

    public static <T> CompletableFuture<T> supplyAsync(TaskRole role, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, roleExecutor(role));
    }

    // ==================== Epoch ====================

    public static long currentEpoch() {
        return EPOCH.get();
    }

    public static boolean isEpoch(long epoch) {
        return EPOCH.get() == epoch;
    }

    /** Invalidates runtime continuations without rebuilding any executor. */
    public static synchronized long invalidateRuntimeEpoch() {
        long epoch = EPOCH.incrementAndGet();
        LOGGER.info("RoadWeaver runtime epoch invalidated: {}", epoch);
        return epoch;
    }

    public static TaskRole currentTaskRole() {
        return CURRENT_ROLE.get();
    }

    public static boolean isCoordinatorTask() {
        return CURRENT_ROLE.get() == TaskRole.COORDINATOR;
    }

    // ==================== 占空比节流 ====================

    public static void throttle(int duty) {
        if (duty >= RoadConstants.DUTY_CYCLE_MAX) return;
        if (duty <= 0) duty = RoadConstants.DEFAULT_DUTY_CYCLE;

        long elapsed = System.currentTimeMillis() - WORK_START.get();
        if (elapsed >= RoadConstants.WORK_PERIOD_MS) {
            long sleepMs = (long) (RoadConstants.WORK_PERIOD_MS * (100.0 - duty) / duty);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(Math.min(sleepMs, RoadConstants.MAX_SLEEP_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            WORK_START.set(System.currentTimeMillis());
        }
    }

    public static void throttle() {
        try {
            throttle(ConfigService.get().performance().threadDutyCycle());
        } catch (Throwable ignored) {}
    }

    public static void resetThrottle() {
        WORK_START.set(System.currentTimeMillis());
    }

    public static void clearThrottle() {
        WORK_START.remove();
    }

    // ==================== 内部方法 ====================

    private static ThreadPoolExecutor executorFor(TaskRole role) {
        return switch (role == null ? TaskRole.PLANNING : role) {
            case INITIAL -> initialExecutor();
            case MAP_READ -> mapReadExecutor();
            case STRUCTURE -> structureExecutor();
            case SNAPSHOT -> snapshotExecutor();
            case MAP -> mapExecutor();
            default -> sharedExecutor();
        };
    }

    private static ThreadPoolExecutor initialExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = INITIAL_WORKERS.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = INITIAL_WORKERS.get();
                if (e == null || e.isShutdown()) {
                    rebuildInitialPool(resolveInitialGenerationThreads());
                    e = INITIAL_WORKERS.get();
                }
            }
        }
        return e;
    }

    private static ThreadPoolExecutor sharedExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = SHARED_EXEC.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = SHARED_EXEC.get();
                if (e == null || e.isShutdown()) {
                    rebuildSharedPool(resolveSharedWorkerThreads());
                    e = SHARED_EXEC.get();
                }
            }
        }
        return e;
    }

    private static ThreadPoolExecutor mapExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = MAP_WORKERS.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = MAP_WORKERS.get();
                if (e == null || e.isShutdown()) {
                    rebuildMapPool(resolveMapWorkerThreads());
                    e = MAP_WORKERS.get();
                }
            }
        }
        return e;
    }

    private static ThreadPoolExecutor structureExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = STRUCTURE_WORKERS.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = STRUCTURE_WORKERS.get();
                if (e == null || e.isShutdown()) {
                    rebuildStructurePool(resolveStructureWorkerThreads());
                    e = STRUCTURE_WORKERS.get();
                }
            }
        }
        return e;
    }

    private static ThreadPoolExecutor mapReadExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = MAP_READ_WORKERS.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = MAP_READ_WORKERS.get();
                if (e == null || e.isShutdown()) {
                    rebuildMapReadPool();
                    e = MAP_READ_WORKERS.get();
                }
            }
        }
        return e;
    }

    /** Small dedicated lane for snapshot predictions that road tasks block on. */
    private static ThreadPoolExecutor snapshotExecutor() {
        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
        ThreadPoolExecutor e = SNAPSHOT_WORKERS.get();
        if (e == null || e.isShutdown()) {
            synchronized (ThreadPoolManager.class) {
                e = SNAPSHOT_WORKERS.get();
                if (e == null || e.isShutdown()) {
                    rebuildSnapshotPool();
                    e = SNAPSHOT_WORKERS.get();
                }
            }
        }
        return e;
    }

    /**
     * Two workers: enough to serve the bounded runtime road concurrency
     * (maxConcurrentGenerations) without queueing predictions behind each
     * other, and independent of the runtime QoS shrinking of the shared and
     * structure pools, so a waiting road task is never hostage to them.
     * Startup predictions stay on the INITIAL lane (trackInitialProgress).
     */
    private static void rebuildSnapshotPool() {
        shutdownQuietly(SNAPSHOT_WORKERS.get());
        SNAPSHOT_WORKERS.set(new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-Snapshot")
        ));
    }

    private static void rebuildInitialPool(int threads) {
        shutdownQuietly(INITIAL_WORKERS.get());
        INITIAL_WORKERS.set(new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-Initial")
        ));
    }

    private static void rebuildSharedPool(int threads) {
        shutdownQuietly(SHARED_EXEC.get());
        SHARED_EXEC.set(new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-Worker")
        ));
    }

    private static void rebuildStructurePool(int threads) {
        shutdownQuietly(STRUCTURE_WORKERS.get());
        STRUCTURE_WORKERS.set(new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-Structure")
        ));
    }

    private static void rebuildMapPool(int threads) {
        shutdownQuietly(MAP_WORKERS.get());
        MAP_WORKERS.set(new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-Map")
        ));
    }

    private static void rebuildMapReadPool() {
        shutdownQuietly(MAP_READ_WORKERS.get());
        // 0.8.357：1 -> 2。地图一次要取 12 个瓦片，单线程串行时即便优先级正常
        // 也偏慢；两个线程足以并行处理当前/相邻瓦片，且不会与生成任务争抢过多
        // CPU（相比开局 22+ 线程可忽略）。
        MAP_READ_WORKERS.set(new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-MapRead")
        ));
    }

    private static void shutdownQuietly(ExecutorService exec) {
        if (exec != null && !exec.isShutdown()) {
            try { exec.shutdownNow(); } catch (Throwable ignored) {}
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicLong next = new AtomicLong(1L);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + next.getAndIncrement());
            t.setDaemon(true);
            // 0.8.357：地图/地图纹理线程保持**普通优先级**。
            //
            // 背景（真机）：1024 区块生成期间玩家打开道路地图，界面长期停在
            // "地图加载 0/12" 且不推进。根因是地图读线程池只有 1 个线程、又被
            // 统一降级到 NORM_PRIORITY-2，而同期 RoadWeaver/Chunky 有 22+ 个
            // 普通优先级线程在跑 —— 低优先级的地图线程抢不到时间片，请求全部
            // 滞留在 PriorityBlockingQueue 里。
            //
            // 判断依据：地图是**玩家主动打开**的交互操作（与 ESC/背包同类），
            // 应当优先于后台批量生成；这与 0.8.348 起「打开界面时让后台让路」
            // 的既定取舍一致。仅提升优先级，不改变生成并发上限。
            if (MAP_PRIORITY_PREFIXES.contains(prefix)) {
                t.setPriority(Thread.NORM_PRIORITY);
            } else if (!"RW-Initial".equals(prefix)) {
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            }
            return t;
        };
    }

    private static int resolveInitialGenerationThreads() {
        return startupHardwareProfile().roadWorkers();
    }

    private static int resolveSharedWorkerThreads() {
        try {
            int configured = ConfigService.get().performance().sharedWorkerThreads();
            if (configured > 0) return Math.max(1, configured);
        } catch (Throwable ignored) {}
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, available - 1));
    }

    private static int resolveStructureWorkerThreads() {
        return startupHardwareProfile().structureWorkers();
    }

    private static StartupHardwareProfile startupHardwareProfile() {
        StartupHardwareProfile current = STARTUP_PROFILE.get();
        return current != null ? current : resolveStartupHardwareProfile();
    }

    private static StartupHardwareProfile resolveStartupHardwareProfile() {
        return StartupHardwareProfile.current(configuredInitialGenerationLimit());
    }

    private static int configuredInitialGenerationLimit() {
        try {
            return ConfigService.get().performance().initialGenerationThreads();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int resolveMapWorkerThreads() {
        return 1;
    }

    private static final class RoleExecutorService extends AbstractExecutorService {
        private final TaskRole role;

        private RoleExecutorService(TaskRole role) {
            this.role = role;
        }

        @Override
        public void shutdown() {
            // A single role executor must never stop the whole pool system.
            // Only the lifecycle hook onServerStopping() owns full shutdown.
        }

        @Override
        public List<Runnable> shutdownNow() {
            // No-op for the same reason as shutdown().
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            if (RoadLifecycle.isStopping()) return true;
            ThreadPoolExecutor e = executorFor(role);
            return e == null || e.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            if (RoadLifecycle.isStopping()) {
                ThreadPoolExecutor e = existingExecutorFor(role);
                return e == null || e.isTerminated();
            }
            ThreadPoolExecutor e = executorFor(role);
            return e == null || e.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (RoadLifecycle.isStopping()) {
                ThreadPoolExecutor e = existingExecutorFor(role);
                return e == null || e.awaitTermination(timeout, unit);
            }
            ThreadPoolExecutor e = executorFor(role);
            return e == null || e.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            if (command == null) throw new NullPointerException("command");
            if (RoadLifecycle.isStopping()) {
                throw new RejectedExecutionException("RoadWeaver server is stopping");
            }
            executorFor(role).execute(new PrioritizedRunnable(role, command));
        }
    }

    private static ThreadPoolExecutor existingExecutorFor(TaskRole role) {
        return switch (role == null ? TaskRole.PLANNING : role) {
            case INITIAL -> INITIAL_WORKERS.get();
            case MAP_READ -> MAP_READ_WORKERS.get();
            case STRUCTURE -> STRUCTURE_WORKERS.get();
            case SNAPSHOT -> SNAPSHOT_WORKERS.get();
            case MAP -> MAP_WORKERS.get();
            default -> SHARED_EXEC.get();
        };
    }

    private static final class PrioritizedRunnable implements Runnable, Comparable<PrioritizedRunnable> {
        private final TaskRole role;
        private final Runnable delegate;
        private final long sequence;

        private PrioritizedRunnable(TaskRole role, Runnable delegate) {
            this.role = role == null ? TaskRole.PLANNING : role;
            this.delegate = delegate;
            this.sequence = TASK_SEQ.getAndIncrement();
        }

        @Override
        public void run() {
            TaskRole previous = CURRENT_ROLE.get();
            CURRENT_ROLE.set(role);
            try {
                // 0.8.345：**先决定是否运行 delegate，再运行**。
                //
                // 关键链条：submit()/runAsync() 路径下 delegate 其实是 FutureTask
                // （AbstractExecutorService.submit 先包 FutureTask，再经本类的
                // execute() 包成 PrioritizedRunnable）。而 FutureTask.run()
                // **自己会捕获异常**，所以从 delegate 内部抛出的异常不会到达这里；
                // 真正会穿透的是 **beforeTask(role)** —— 它在 delegate 之外。
                //
                // 若 beforeTask 抛出且我们直接返回，后果是：
                //   delegate 从未执行 → FutureTask **永不完成**
                //   → 任何 future.get() / join() 的调用方**永久挂起**
                //   （而本项目所有 join() 都没有超时 → 就是死锁）。
                // 因此除"正在停服"外，gate 故障一律**失败开放**：照样跑 delegate。
                boolean runDelegate = true;
                try {
                    RuntimeLoadPolicy.beforeTask(role);
                    if (Thread.currentThread().isInterrupted()) runDelegate = false;
                } catch (CancellationException stopping) {
                    // cancelIfStopping() 在停服时**故意**抛 CancellationException，
                    // 属正常控制流：此时不应再跑 delegate，且不记日志（否则关服刷屏）。
                    runDelegate = false;
                } catch (Throwable gateFailure) {
                    // gate 本身故障：**失败开放**，让 Future 能正常完成，避免永久挂起。
                    LOGGER.warn("RoadWeaver admission gate failed on role={} thread={}; running task anyway "
                                    + "to avoid a permanently pending future: {}",
                            role, Thread.currentThread().getName(), gateFailure.toString(), gateFailure);
                }
                if (runDelegate) {
                    delegate.run();
                }
            } catch (Throwable failure) {
                // 兜底：worker 线程死亡会让 ThreadPoolExecutor 用同一工厂补位
                // → 线程编号单调递增（真机见到 RW-Structure-1554，正常应 1~6）；
                // 且任务被永久丢弃、不重排、无日志 → "无法诊断的偶发失败"。
                // 这里只记录不重排（重排可能造成无限循环 / 雪崩）。
                LOGGER.warn("RoadWeaver task failed on role={} thread={} (task dropped, not re-queued): {}",
                        role, Thread.currentThread().getName(), failure.toString(), failure);
            } finally {
                if (previous == null) CURRENT_ROLE.remove();
                else CURRENT_ROLE.set(previous);
            }
        }

        @Override
        public int compareTo(PrioritizedRunnable other) {
            int byPriority = Integer.compare(this.role.priority, other.role.priority);
            if (byPriority != 0) return byPriority;
            return Long.compare(this.sequence, other.sequence);
        }
    }
}
