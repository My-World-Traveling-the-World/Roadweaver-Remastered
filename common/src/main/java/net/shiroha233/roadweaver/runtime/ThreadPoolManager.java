/* 文件职责：共享优先级工作池的生命周期与线程数管理 —— 启动、停止、按配置缩放。
 *
 * 状态变更与容量调整在同一管理锁内互斥，防止停服时重新创建线程池；
 * 结果取消放在管理锁外，避免同步完成回调重入门面时互等。
 */
package net.shiroha233.roadweaver.runtime;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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

        // 排序实现迁出门面，仍按原角色权重与提交序号比较。
        int priority() { return priority; }
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

    public static void onServerStarted(MinecraftServer server) {
        try {
            synchronized (ThreadPoolManager.class) {
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
                LOGGER.info("【按硬件资源生成的启动和游玩预算】 " + "RoadWeaver adaptive startup profile: processors={}, maxHeapMiB={}, roads={}, coarse={}, "
                                + "structures={}, nativeStructures={}, startupCap={}, runtimeCap={}, runtimeConcurrency={}, "
                                + "idleConcurrency={}.",
                        startupProfile.processors(), startupProfile.maxHeapMiB(), startupProfile.roadWorkers(),
                        startupProfile.coarseWorkers(), startupProfile.structureWorkers(),
                        startupProfile.nativeStructureWorkers(), configuredInitialGenerationLimit(),
                        performance.sharedWorkerThreads(), performance.maxConcurrentGenerations(),
                        performance.idleConcurrentGenerations());
            }
        } finally { PoolRetirements.drain(); }
    }

    public static void onServerStopping() {
        List<ThreadPoolExecutor> closing;
        synchronized (ThreadPoolManager.class) {
            EPOCH.incrementAndGet(); chunkySupplyThreads = 0;
            closing = java.util.stream.Stream.of(
                    INITIAL_WORKERS.getAndSet(null), SHARED_EXEC.getAndSet(null),
                    STRUCTURE_WORKERS.getAndSet(null), SNAPSHOT_WORKERS.getAndSet(null),
                    MAP_WORKERS.getAndSet(null), MAP_READ_WORKERS.getAndSet(null))
                    .filter(java.util.Objects::nonNull).toList();
            STARTUP_PROFILE.set(null);
        }
        // CompletableFuture 的完成回调可同步重入门面；不持管理锁进行取消和线程中断。
        closing.forEach(ThreadPoolManager::shutdownQuietly);
        LOGGER.debug("ThreadPoolManager: 工作池已关闭 (epoch={})", EPOCH.get());
    }

    // ==================== 运行时重建 ====================

    public static void resizeInitialPool(int threads) {
        try {
            synchronized (ThreadPoolManager.class) {
                int resolved = threads <= 0 ? resolveInitialGenerationThreads() : Math.max(1, threads);
                rebuildInitialPool(resolved);
            }
        } finally { PoolRetirements.drain(); }
    }

    public static void resizeSharedPool(int threads) {
        try {
            synchronized (ThreadPoolManager.class) {
                int resolved = threads <= 0 ? resolveSharedWorkerThreads() : Math.max(1, threads);
                rebuildSharedPool(resolved);
            }
        } finally { PoolRetirements.drain(); }
    }

    /** 开局恢复硬件预算时不继承普通游玩QoS缩小值，避免等待通道被错误压缩。 */
    public static void prepareInitialGeneration() {
        try {
            synchronized (ThreadPoolManager.class) {
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
                LOGGER.info("【初始生成的道路、结构和共享通道已准备】 " + "RoadWeaver initial generation hardware lanes prepared: roads={} structures={} shared={}",
                        profile.roadWorkers(), profile.structureWorkers(), currentSharedRuntimeThreads());
            }
        } finally { PoolRetirements.drain(); }
    }

    /** 调整已有共享池但不取消已排队/运行工作；预检临时供给结束恢复配置预算。
 *
     * Adjusts the existing shared worker pool without cancelling queued or
     * running planning work. Chunky readiness uses this for a small temporary
     * supply boost; ordinary runtime work returns it to the configured size.
     */
    public static void setSharedRuntimeThreads(int requestedThreads) {
        try {
            synchronized (ThreadPoolManager.class) {
                if (RoadLifecycle.isStopping()) return;
                int target = Math.max(1, Math.min(maxRuntimeRoadThreads(), requestedThreads));
                ThreadPoolExecutor executor = sharedExecutor();
                applySharedRuntimeThreads(executor, target);
            }
        } finally { PoolRetirements.drain(); }
    }

    public static void setRuntimeAdaptiveThreads(int requestedThreads) {
        try {
            synchronized (ThreadPoolManager.class) {
                if (RoadLifecycle.isStopping()) return;
                runtimeAdaptiveThreads = clampRuntimeThreads(requestedThreads);
                applyResolvedRuntimeThreads();
            }
        } finally { PoolRetirements.drain(); }
    }

    public static void setChunkySupplyThreads(int requestedThreads) {
        try {
            synchronized (ThreadPoolManager.class) {
                if (RoadLifecycle.isStopping()) return;
                chunkySupplyThreads = requestedThreads <= 0 ? 0 : clampRuntimeThreads(requestedThreads);
                applyResolvedRuntimeThreads();
            }
        } finally { PoolRetirements.drain(); }
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

    /** 目标容量稳定足够久才应用变更，避免每次压力抖动都调整线程池。
 *
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
        LOGGER.info("【游玩期共享工作线程容量已调整】 " + "RoadWeaver shared runtime worker capacity changed: {} -> {}.", current, target);
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
        LOGGER.info("【游玩期结构工作线程容量已调整】 " + "RoadWeaver structure runtime worker capacity changed: {} -> {}.", current, bounded);
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

    /** 普通工作预算预留服务端及系统余量；只约束该池，不保证全部池合计始终留出两核。 */
    public static int maxRuntimeRoadThreads() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(12, Math.max(1, processors - 2)));
    }

    public static int maxRuntimeStructureThreads() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(2, Math.min(8, processors / 2));
    }

    public static void resizeGenerationPool(int threads) {
        try {
            synchronized (ThreadPoolManager.class) {
                resizeSharedPool(threads);
            }
        } finally { PoolRetirements.drain(); }
    }

    public static void resizeComputePool(int threads) {
        try {
            synchronized (ThreadPoolManager.class) {
                resizeSharedPool(threads);
            }
        } finally { PoolRetirements.drain(); }
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
     * <p>Future 使用 submit/execute；CompletableFuture 使用本类 runAsync/supplyAsync，
     * JDK 的不透明异步包装会明确拒收，避免停服时无法结束其关联结果。</p>
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
        java.util.Objects.requireNonNull(runnable);
        return supplyAsync(role, () -> { runnable.run(); return null; });
    }

    public static <T> CompletableFuture<T> supplyAsync(TaskRole role, Supplier<T> supplier) {
        // 自有任务同时持有可取消执行体与对外结果，不能依赖 JDK 内部包装的取消状态。
        return LifecycleCompletableTask.submit(roleExecutor(role), supplier);
    }

    // ==================== Epoch ====================

    public static long currentEpoch() {
        return EPOCH.get();
    }

    public static boolean isEpoch(long epoch) {
        return EPOCH.get() == epoch;
    }

    /** 换代但不重建物理池，同时取消旧代次结果，避免未执行任务永远等不到 worker。 */
    public static long invalidateRuntimeEpoch() {
        long epoch;
        List<ThreadPoolExecutor> pools;
        synchronized (ThreadPoolManager.class) {
            epoch = EPOCH.incrementAndGet();
            pools = java.util.stream.Stream.of(INITIAL_WORKERS.get(), SHARED_EXEC.get(), STRUCTURE_WORKERS.get(),
                    SNAPSHOT_WORKERS.get(), MAP_WORKERS.get(), MAP_READ_WORKERS.get())
                    .filter(java.util.Objects::nonNull).toList();
        }
        // 单纯换代也要结束旧结果，不能只指望旧 worker 最终取得执行机会。
        pools.forEach(pool -> ((LifecycleWorkerPool) pool).cancelExpiredTasks());
        LOGGER.info("【运行任务代次已失效，旧任务应停止发布】 " + "RoadWeaver runtime epoch invalidated: {}", epoch);
        return epoch;
    }

    public static TaskRole currentTaskRole() {
        return CURRENT_ROLE.get();
    }

    // 角色上下文仍由门面持有，执行包装只借此成对进入/恢复，防止串到下一任务。
    static TaskRole enterTaskRole(TaskRole role) {
        TaskRole previous = CURRENT_ROLE.get(); CURRENT_ROLE.set(role); return previous;
    }

    static void restoreTaskRole(TaskRole previous) {
        if (previous == null) CURRENT_ROLE.remove(); else CURRENT_ROLE.set(previous);
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
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = INITIAL_WORKERS.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = INITIAL_WORKERS.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildInitialPool(resolveInitialGenerationThreads());
                        e = INITIAL_WORKERS.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    private static ThreadPoolExecutor sharedExecutor() {
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = SHARED_EXEC.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = SHARED_EXEC.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildSharedPool(resolveSharedWorkerThreads());
                        e = SHARED_EXEC.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    private static ThreadPoolExecutor mapExecutor() {
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = MAP_WORKERS.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = MAP_WORKERS.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildMapPool(resolveMapWorkerThreads());
                        e = MAP_WORKERS.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    private static ThreadPoolExecutor structureExecutor() {
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = STRUCTURE_WORKERS.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = STRUCTURE_WORKERS.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildStructurePool(resolveStructureWorkerThreads());
                        e = STRUCTURE_WORKERS.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    private static ThreadPoolExecutor mapReadExecutor() {
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = MAP_READ_WORKERS.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = MAP_READ_WORKERS.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildMapReadPool();
                        e = MAP_READ_WORKERS.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    /** 快照预测使用独立小通道，避免道路等待的工作被排在道路自己后面。 */
    private static ThreadPoolExecutor snapshotExecutor() {
        try {
            if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
            ThreadPoolExecutor e = SNAPSHOT_WORKERS.get();
            if (e == null || e.isShutdown()) {
                synchronized (ThreadPoolManager.class) {
                    e = SNAPSHOT_WORKERS.get();
                    if (e == null || e.isShutdown()) {
                        // 等管理锁期间可能已经停服；外层检查不能阻止旧调用重新创建线程池。
                        if (RoadLifecycle.isStopping()) throw new RejectedExecutionException("RoadWeaver is stopping");
                        rebuildSnapshotPool();
                        e = SNAPSHOT_WORKERS.get();
                    }
                }
            }
            return e;
        } finally { PoolRetirements.drain(); }
    }

    /** 游玩快照独立于共享池QoS收缩，开局预测走INITIAL通道；小池不承诺所有请求无排队。 */
    private static void rebuildSnapshotPool() {
        shutdownQuietly(SNAPSHOT_WORKERS.get());
        SNAPSHOT_WORKERS.set(new LifecycleWorkerPool(
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
        INITIAL_WORKERS.set(new LifecycleWorkerPool(
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
        SHARED_EXEC.set(new LifecycleWorkerPool(
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
        STRUCTURE_WORKERS.set(new LifecycleWorkerPool(
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
        MAP_WORKERS.set(new LifecycleWorkerPool(
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
        MAP_READ_WORKERS.set(new LifecycleWorkerPool(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                namedFactory("RW-MapRead")
        ));
    }

    private static void shutdownQuietly(ExecutorService exec) {
        // 已经 graceful shutdown 的池仍可能有队列；重建时先发布新池，再在锁外取消旧结果。
        PoolRetirements.retire(exec);
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
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(java.util.concurrent.Callable<T> action) {
            // 标准完成服务通过此虚方法创建内层 Future，也要随实际物理池结束。
            return ((LifecycleWorkerPool) executorFor(role)).createTask(action);
        }

        @Override
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(Runnable action, T value) {
            return newTaskFor(java.util.concurrent.Executors.callable(action, value));
        }

        @Override
        public void execute(Runnable command) {
            if (command == null) throw new NullPointerException("command");
            if (RoadLifecycle.isStopping()) {
                throw new RejectedExecutionException("RoadWeaver server is stopping");
            }
            // JDK 的异步包装没有公开的关联结果访问口；拒收比停服后遗留 pending 更可诊断。
            if (command instanceof CompletableFuture.AsynchronousCompletionTask) {
                throw new RejectedExecutionException("Use ThreadPoolManager.runAsync/supplyAsync for lifecycle completion");
            }
            executorFor(role).execute(new PrioritizedTask(role, command));
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

}
