/* 文件职责：与Streams Reflowing的可选集成层；反射读取河流引擎计划，生命周期就绪另走公开API。 */
package net.shiroha233.roadweaver.compat;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** 只读接入河流公开规划API；可选模组缺席由兼容层处理。 */
public final class StreamsReflowingCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final AtomicBoolean ENABLED_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicInteger PREWARM_THREAD_ID = new AtomicInteger();
    private static final AtomicLong PREWARM_GENERATION = new AtomicLong();
    private static final int PREWARM_WORKERS = resolvePrewarmWorkers();
    private static final double PREWARM_RADIUS = 64.0D;

    /**
     * 河流 plate 预热的等待上限（毫秒）。
     *
     * <p>审计 P2-2：原来是无参 {@code join()}，等的是**外部 Streams mod** ——
     * 真机转储里 RW-Initial-7 在 {@code CountDownLatch} 上卡了 ≥2 分钟。
     * 取 60 秒：河流预热正常是秒级，60 秒足够区分「慢」与「卡死」，
     * 又不会误伤大范围的正当预热。</p>
     */
    private static final long STREAMS_PREWARM_TIMEOUT_MS = 60_000L;

    /**
     * 单列查询的上限。正常情况下 Streams 的 plan(x,z) 是毫秒级的;实测出现过一次卡 186 秒,
     * 把整条路的关键路径一起拖住。给一个有界等待不是为了省时间,是为了"卡住时不把整局拖死":
     * 超时这一条连接就降级成只用原版河道识别(等于没装 Streams),路会扭一下,但不会卡死,
     * 也不会崩——RiverColumn.UNKNOWN 本来就是"查不到"的既有表达,消费方按未知处理。
     */
    private static final long STREAMS_QUERY_TIMEOUT_MS = 15_000L;

    /** 读配置失败时的兜底（不允许退化成无限等）。 */
    private static long streamsPrewarmTimeoutMs() {
        try {
            return Math.max(5_000L, STREAMS_PREWARM_TIMEOUT_MS);
        } catch (Throwable ignored) {
            return STREAMS_PREWARM_TIMEOUT_MS;
        }
    }
    private static final ExecutorService PREWARM_EXECUTOR = Executors.newFixedThreadPool(
            PREWARM_WORKERS, new PrewarmThreadFactory());
    private static final ConcurrentHashMap<Object, PrewarmCoordinator> PREWARM_COORDINATORS =
            new ConcurrentHashMap<>();
    private static volatile boolean prewarmPausedForDimension;
    private static volatile Access access;
    private static volatile boolean initialized;
    private static volatile boolean accessFailure;

    private StreamsReflowingCompat() {
    }

    private static int resolvePrewarmWorkers() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        long heapMiB = Math.max(512L, Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        int cpuBudget = Math.max(2, processors - (processors >= 12 ? 2 : 1));
        int memoryBudget = (int) Math.max(2L, Math.min(8L, Math.max(2L, (heapMiB / 1024L - 2L) * 2L)));
        return Math.max(2, Math.min(8, Math.min(cpuBudget, memoryBudget)));
    }

    public static boolean isAvailable() {
        return access() != null;
    }

    public static RiverColumn sample(ServerLevel level, int x, int z) {
        return openSession(level).sample(x, z);
    }

    public static SamplingSession openSession(ServerLevel level) {
        if (level == null) return SamplingSession.disabled();
        Access current = access();
        if (current == null) return accessFailure ? SamplingSession.failed() : SamplingSession.disabled();
        try {
            Object engine = current.engine().invoke(null, level);
            return engine == null ? SamplingSession.failed() : new SamplingSession(current, engine, false);
        } catch (Throwable failure) {
            logFailure(failure);
            return SamplingSession.failed();
        }
    }

    public static void prewarmInitialRoutes(ServerLevel level, List<BlockPos> positions) {
        if (prewarmPausedForDimension || level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        // Initial readiness is a hard gate for Chunky. Do not merely enqueue
        // the plates: wait until every required river network has been built.
        openSession(level).prewarmStrict(positions, "initial-global");
    }

    /** 向区块预生成协调器提供异步河流预热完成门，不自行宣布道路完工。 */
    public static CompletableFuture<Void> prewarmRoutesAsync(
            ServerLevel level, List<BlockPos> positions, String scope) {
        if (prewarmPausedForDimension || level == null
                || !Level.OVERWORLD.equals(level.dimension())
                || positions == null || positions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> openSession(level).prewarmStrict(positions,
                        scope == null ? "route" : scope));
    }

    public static void clearPrewarmState() {
        PREWARM_GENERATION.incrementAndGet();
        PREWARM_COORDINATORS.values().forEach(PrewarmCoordinator::clear);
        PREWARM_COORDINATORS.clear();
        prewarmPausedForDimension = false;
    }

    /** 道路的河流桥接只服务主世界；暂停标志不修改Chunky本身。 */
    public static void setPrewarmPausedForDimension(boolean paused) {
        if (prewarmPausedForDimension == paused) return;
        prewarmPausedForDimension = paused;
        if (paused) {
            clearPrewarmState();
            prewarmPausedForDimension = true;
            LOGGER.info("【离开主世界，暂停河流接入工作】 " + "RoadWeaver Streams bridge paused outside minecraft:overworld.");
        } else {
            LOGGER.info("【返回主世界，恢复河流接入工作】 " + "RoadWeaver Streams bridge resumed in minecraft:overworld.");
        }
    }

    private static Access access() {
        if (initialized) return access;
        synchronized (StreamsReflowingCompat.class) {
            if (initialized) return access;
            initialized = true;
            try {
                Class<?> worldgen = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
                Class<?> engineClass = Class.forName("dev.streamsreflowing.core.RiverEngine");
                Class<?> planClass = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
                Class<?> lakeClass = Class.forName("dev.streamsreflowing.core.river.Lake");
                Method engine = findEngineMethod(worldgen);
                Method plan = engineClass.getMethod("plan", int.class, int.class);
                Method lakeAt = engineClass.getMethod("lakeAt", int.class, int.class);
                Method plateAt = engineClass.getMethod("plateAt", int.class, int.class);
                Method platesNear = engineClass.getMethod(
                        "platesNear", double.class, double.class, double.class);
                Class<?> plateClass = Class.forName("dev.streamsreflowing.core.plate.PlateKey");
                Method networkFor = engineClass.getMethod("networkFor", plateClass);
                Access resolved = new Access(
                        engine,
                        plan,
                        lakeAt,
                        plateAt,
                        platesNear,
                        networkFor,
                        planClass.getMethod("inChannel"),
                        planClass.getMethod("inCorridor"),
                        planClass.getMethod("waterSurfaceY"),
                        planClass.getMethod("bedY"),
                        planClass.getMethod("distance"),
                        planClass.getMethod("width"),
                        lakeClass.getMethod("level"),
                        lakeClass.getMethod("digDepth"));
                access = resolved;
                if (ENABLED_LOGGED.compareAndSet(false, true)) {
                    LOGGER.info("【河流规划兼容接口已启用】 " + "RoadWeaver Streams Reflowing river-plan compatibility enabled");
                }
            } catch (ClassNotFoundException absent) {
                access = null;
            } catch (Throwable failure) {
                accessFailure = true;
                access = null;
                if (FAILURE_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warn("【河流兼容接口初始化失败】 " + "RoadWeaver Streams Reflowing compatibility initialization failed: {}",
                            failure.toString());
                }
            }
            return access;
        }
    }

    private static Method findEngineMethod(Class<?> worldgen) throws NoSuchMethodException {
        for (Method method : worldgen.getMethods()) {
            if (method.getName().equals("engine") && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException("StreamsWorldgen.engine");
    }

    private record Access(Method engine,
                          Method plan,
                          Method lakeAt,
                          Method plateAt,
                          Method platesNear,
                          Method networkFor,
                          Method inChannel,
                          Method inCorridor,
                          Method waterSurfaceY,
                          Method bedY,
                          Method distance,
                          Method width,
                          Method lakeLevel,
                          Method lakeDigDepth) {
    }

    private static void logFailure(Throwable failure) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("【河流规划查询失败，本次改用地形判据】 " + "RoadWeaver could not query Streams Reflowing river plans; using terrain fallback: {}",
                    failure.toString());
        }
    }

    public static final class SamplingSession {
        private static final SamplingSession DISABLED = new SamplingSession(null, null, false);
        private static final SamplingSession FAILED = new SamplingSession(null, null, true);

        private final Access access;
        private final Object engine;
        private final boolean failed;
        private final PrewarmCoordinator prewarmCoordinator;
        /** 某次查询超时后置位:这一份会话余下的列直接按"查不到"走,不再让每一列各等一次。 */
        private volatile boolean degraded;
        private final ConcurrentHashMap<Long, RiverColumn> columns = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, LakeColumn> lakes = new ConcurrentHashMap<>();

        private SamplingSession(Access access, Object engine, boolean failed) {
            this.access = access;
            this.engine = engine;
            this.failed = failed;
            this.prewarmCoordinator = access == null || engine == null
                    ? null
                    : PREWARM_COORDINATORS.computeIfAbsent(
                            engine, ignored -> new PrewarmCoordinator(
                                    access, engine, PREWARM_GENERATION.get()));
        }

        private static SamplingSession disabled() {
            return DISABLED;
        }

        private static SamplingSession failed() {
            return FAILED;
        }

        public boolean isAvailable() {
            return access != null && engine != null;
        }

        public boolean isFailed() {
            return failed || degraded;
        }

        public RiverColumn sample(int x, int z) {
            if (failed || degraded) return RiverColumn.UNKNOWN;
            if (!isAvailable()) return RiverColumn.NONE;
            return columns.computeIfAbsent(columnKey(x, z), ignored -> query(x, z));
        }

        public LakeColumn sampleLake(int x, int z) {
            if (failed || degraded) return LakeColumn.UNKNOWN;
            if (!isAvailable()) return LakeColumn.NONE;
            return lakes.computeIfAbsent(columnKey(x, z), ignored -> queryLake(x, z));
        }

        public void prewarm(List<BlockPos> positions) {
            prewarm(positions, "route", true);
        }

        private void prewarmStrict(List<BlockPos> positions, String scope) {
            prewarm(positions, scope, true, true);
        }

        private void schedulePrewarm(List<BlockPos> positions, String scope) {
            prewarm(positions, scope, false);
        }

        private void prewarm(List<BlockPos> positions, String scope, boolean await) {
            prewarm(positions, scope, await, false);
        }

        private void prewarm(List<BlockPos> positions, String scope, boolean await, boolean strict) {
            if (!isAvailable() || positions == null || positions.isEmpty()) return;

            Set<Object> plates = new LinkedHashSet<>();
            try {
                for (BlockPos pos : positions) {
                    if (pos == null) continue;
                    Object nearby = access.platesNear().invoke(
                            engine, (double) pos.getX(), (double) pos.getZ(), PREWARM_RADIUS);
                    if (nearby == null || !nearby.getClass().isArray()) continue;
                    int count = Array.getLength(nearby);
                    for (int i = 0; i < count; i++) {
                        Object plate = Array.get(nearby, i);
                        if (plate != null) plates.add(plate);
                    }
                }
            } catch (Throwable failure) {
                logFailure(failure);
                return;
            }
            if (plates.isEmpty()) return;

            long started = System.nanoTime();
            List<CompletableFuture<Void>> futures = prewarmCoordinator.schedule(plates);
            if (await) {
                try {
                    // ★ 审计 P2-2：改为有界等待。
                    //
                    // 原为无参 join() —— 真机转储里 RW-Initial-7 在
                    // CountDownLatch@42c641ff 上卡了 ≥3 个转储周期（≥2 分钟）。
                    // 等的是**外部 Streams mod**，它卡住我们就永久等下去。
                    //
                    // 超时只记日志并继续（不抛）：河流预热是**加速手段**而非
                    // 正确性前提 —— 没有它路照样能建，只是慢些。
                    // 让整条道路生成因为河流预热卡住才是真正的损失。
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(streamsPrewarmTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException timedOut) {
                    LOGGER.warn("RoadWeaver Streams plate prewarm 超时（{}ms），继续后续流程；"
                                    + "河流预热只是加速手段，不应阻塞道路生成 scope={} plates={}",
                            streamsPrewarmTimeoutMs(), scope, plates.size());
                } catch (java.util.concurrent.ExecutionException execution) {
                    Throwable cause = execution.getCause() == null ? execution : execution.getCause();
                    logFailure(cause);
                    if (strict) throw new CompletionException(cause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(new java.util.concurrent.CancellationException(
                            "streams prewarm interrupted"));
                } catch (CompletionException failure) {
                    logFailure(failure.getCause() == null ? failure : failure.getCause());
                    if (strict) throw failure;
                }
            }
            LOGGER.info("【河流板块预热范围与耗时】 " + "RoadWeaver Streams plate prewarm: scope={} positions={} requiredPlates={} workers={} await={} elapsedMs={}",
                    scope, positions.size(), plates.size(), PREWARM_WORKERS, await,
                    Math.round((System.nanoTime() - started) / 1_000_000.0D));
        }

        /** {@link #bounded} 里"等超时了"的哨兵。不能用 null:null 本来就是"这里没有河"的正常答案。 */
        private static final Object BOUNDED_TIMED_OUT = new Object();

        /**
         * 在预热线程池上跑一次反射调用,最多等 {@link #STREAMS_QUERY_TIMEOUT_MS}。
         *
         * <p>正常情况毫秒级就回来;卡住的时候宁可让这一条路用原版河道识别(等于没装 Streams),
         * 也不要把整条关键路径钉在那里——实测卡过一次 186 秒,占道路阶段的 32%。
         * 超时只报一次(和 logFailure 同一个"只吵一次"的口径),不刷屏。
         */
        private static Object bounded(String what, Callable<Object> call) throws Exception {
            Future<Object> future = PREWARM_EXECUTOR.submit(call);
            try {
                return future.get(STREAMS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                future.cancel(true);
                LOGGER.warn("【河流接口响应超时，本条道路后续仅按地形分类】 " + "RoadWeaver Streams Reflowing {} answered slower than {} ms; "
                        + "falling back to terrain-only classification for the rest of this route",
                        what, STREAMS_QUERY_TIMEOUT_MS);
                return BOUNDED_TIMED_OUT;
            }
        }

        /** 某一次查询等超时了:整份会话降级,余下的列不再各等一次。 */
        private RiverColumn timedOut() {
            degraded = true;
            return RiverColumn.UNKNOWN;
        }

        private LakeColumn lakeTimedOut() {
            degraded = true;
            return LakeColumn.UNKNOWN;
        }

        private RiverColumn query(int x, int z) {
            try {
                Object plan = bounded("plan", () -> access.plan().invoke(engine, x, z));
                if (plan == BOUNDED_TIMED_OUT) return timedOut();
                if (plan == null) return RiverColumn.NONE;
                boolean inChannel = (boolean) access.inChannel().invoke(plan);
                boolean inCorridor = (boolean) access.inCorridor().invoke(plan);
                if (!inChannel && !inCorridor) return RiverColumn.NONE;
                int waterSurfaceY = (int) access.waterSurfaceY().invoke(plan);
                int bedY = (int) access.bedY().invoke(plan);
                double distance = (double) access.distance().invoke(plan);
                double width = (double) access.width().invoke(plan);
                return new RiverColumn(inChannel, inCorridor, waterSurfaceY, bedY, distance, width);
            } catch (Throwable failure) {
                logFailure(failure);
                return RiverColumn.UNKNOWN;
            }
        }

        private LakeColumn queryLake(int x, int z) {
            try {
                Object lake = bounded("lakeAt", () -> access.lakeAt().invoke(engine, x, z));
                if (lake == BOUNDED_TIMED_OUT) return lakeTimedOut();
                if (lake == null) return LakeColumn.NONE;
                int surfaceY = (int) access.lakeLevel().invoke(lake);
                int bedY = surfaceY - Math.max(1, (int) access.lakeDigDepth().invoke(lake));
                return new LakeColumn(true, surfaceY, bedY);
            } catch (Throwable failure) {
                logFailure(failure);
                return LakeColumn.UNKNOWN;
            }
        }

        private static long columnKey(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }

    private static final class PrewarmCoordinator {
        private final Access access;
        private final Object engine;
        private final long generation;
        private final ConcurrentHashMap<Object, CompletableFuture<Void>> plateFutures =
                new ConcurrentHashMap<>();

        private PrewarmCoordinator(Access access, Object engine, long generation) {
            this.access = access;
            this.engine = engine;
            this.generation = generation;
        }

        private List<CompletableFuture<Void>> schedule(Iterable<Object> plates) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Object plate : plates) {
                futures.add(plateFutures.computeIfAbsent(plate, this::submit));
            }
            return futures;
        }

        private CompletableFuture<Void> submit(Object plate) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (PREWARM_GENERATION.get() != generation) return;
                try {
                    access.networkFor().invoke(engine, plate);
                } catch (Throwable failure) {
                    throw new CompletionException(failure);
                }
            }, PREWARM_EXECUTOR);
            future.whenComplete((ignored, failure) -> {
                if (failure != null) plateFutures.remove(plate, future);
            });
            return future;
        }

        private void clear() {
            plateFutures.values().forEach(future -> future.cancel(true));
            plateFutures.clear();
        }
    }

    private static final class PrewarmThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    "RW-Streams-Prewarm-" + PREWARM_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }

    public record RiverColumn(boolean inChannel,
                              boolean inCorridor,
                              int waterSurfaceY,
                              int bedY,
                              double distance,
                              double width) {
        public static final RiverColumn NONE =
                new RiverColumn(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Double.POSITIVE_INFINITY, 0.0D);
        public static final RiverColumn UNKNOWN =
                new RiverColumn(true, true, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Double.NaN, Double.NaN);

        public boolean isUnknown() {
            return Double.isNaN(distance) && Double.isNaN(width);
        }

        public int depth() {
            return inChannel ? Math.max(0, waterSurfaceY - bedY) : 0;
        }
    }

    public record LakeColumn(boolean present, int surfaceY, int bedY) {
        public static final LakeColumn NONE =
                new LakeColumn(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
        public static final LakeColumn UNKNOWN =
                new LakeColumn(true, Integer.MIN_VALUE, Integer.MIN_VALUE);

        public boolean isUnknown() {
            return present && surfaceY == Integer.MIN_VALUE && bedY == Integer.MIN_VALUE;
        }

        public int depth() {
            return present ? Math.max(0, surfaceY - bedY) : 0;
        }
    }
}
