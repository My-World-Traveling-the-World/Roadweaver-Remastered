/* 文件职责：汇总初始道路生成期间的阶段、采样与路径进度。 */
package net.shiroha233.roadweaver.generation.progress;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.InitialRouteTaskDiagnostics;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingStats;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot.RoutePhaseProgress;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 初始生成进度状态总线。
 */
public final class InitialGenerationProgressTracker {
    private InitialGenerationProgressTracker() {}

    private static final AtomicReference<InitialGenerationStage> STAGE = new AtomicReference<>(InitialGenerationStage.FINISHED);
    /** 道路生成开始后不允许后台结构刷新倒退启动阶段显示。 */
    private static final AtomicBoolean ROUTE_GENERATION_LOCKED = new AtomicBoolean();
    private static final AtomicLong STARTED_AT = new AtomicLong(0L);

    private static final AtomicInteger CONNECTIONS_TOTAL = new AtomicInteger();
    private static final AtomicInteger CONNECTIONS_GENERATING = new AtomicInteger();
    private static final AtomicInteger CONNECTIONS_DONE = new AtomicInteger();
    private static final AtomicInteger CONNECTIONS_FAILED = new AtomicInteger();
    private static final AtomicInteger CONNECTIONS_LOCAL_SAMPLING = new AtomicInteger();
    private static final AtomicInteger CONNECTIONS_FALLBACK_CANDIDATES = new AtomicInteger();
    private static final AtomicReference<RoutePhase> ROUTE_PHASE = new AtomicReference<>(RoutePhase.ORIGINAL);
    private static final AtomicInteger[] PHASE_TOTAL = phaseCounters();
    private static final AtomicInteger[] PHASE_GENERATING = phaseCounters();
    private static final AtomicInteger[] PHASE_DONE = phaseCounters();
    private static final AtomicInteger[] PHASE_FAILED = phaseCounters();
    private static final AtomicInteger[] PHASE_RETAINED = phaseCounters();
    private static final AtomicInteger[] PHASE_HANDED_OFF = phaseCounters();
    /** 跨重试波保留阶段总数与结果，避免进度被重新置零误导玩家。 */
    private static final AtomicBoolean[] PHASE_STARTED = phaseFlags();
    private static final AtomicReference<String>[] PHASE_OPERATION = phaseOperations();
    private static final AtomicInteger[] PHASE_SAMPLING_STEPS = phaseCounters();
    private static final AtomicLong ROAD_TASK_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT_ROAD_TASK = new ThreadLocal<>();
    private static final ConcurrentHashMap<Long, Integer> ROAD_TASK_PROGRESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> ROAD_TASK_SAMPLING_STEPS = new ConcurrentHashMap<>();

    private static final AtomicInteger PLANNING_TILES_TOTAL = new AtomicInteger();
    private static final AtomicInteger PLANNING_TILES_DONE = new AtomicInteger();
    private static final AtomicInteger PLANNING_STARTS_TOTAL = new AtomicInteger();
    private static final AtomicInteger PLANNING_STARTS_DONE = new AtomicInteger();
    private static final AtomicInteger PLANNING_EMPTY_STARTS = new AtomicInteger();
    private static final AtomicInteger PLANNING_SNAPSHOTS_READY = new AtomicInteger();

    private static final AtomicInteger TILES_TOTAL = new AtomicInteger();
    private static final AtomicInteger TILES_LOADED = new AtomicInteger();
    private static final AtomicInteger TILES_SAMPLED = new AtomicInteger();
    private static final AtomicInteger TILES_FROM_MEMORY = new AtomicInteger();
    private static final AtomicInteger TILES_FROM_DISK = new AtomicInteger();
    private static final AtomicInteger COARSE_PATHS_TOTAL = new AtomicInteger();
    private static final AtomicInteger COARSE_PATHS_DONE = new AtomicInteger();
    private static final AtomicLong EXACT_SAMPLES_TOTAL = new AtomicLong();
    private static final AtomicLong EXACT_SAMPLES_DONE = new AtomicLong();
    private static final AtomicInteger EXACT_PATHS_TOTAL = new AtomicInteger();
    private static final AtomicInteger EXACT_PATHS_DONE = new AtomicInteger();
    private static final AtomicInteger CONSTRUCTION_CHUNKS_TOTAL = new AtomicInteger();
    private static final AtomicInteger CONSTRUCTION_CHUNKS_DONE = new AtomicInteger();

    private static final AtomicLong SAMPLES_TOTAL = new AtomicLong();
    private static final AtomicLong SAMPLES_DONE = new AtomicLong();
    private static final AtomicLong LAST_SAMPLE_SNAPSHOT_AT = new AtomicLong();
    private static final AtomicLong LAST_SAMPLE_SNAPSHOT_COUNT = new AtomicLong();
    private static final AtomicReference<Double> SAMPLES_PER_SECOND = new AtomicReference<>(0.0);
    private static final AtomicInteger LAST_BATCH_SAMPLES = new AtomicInteger();
    private static final AtomicLong LAST_BATCH_MILLIS = new AtomicLong();

    private static final AtomicReference<String> BACKEND = new AtomicReference<>("-");
    private static final AtomicReference<String> DEVICE_NAME = new AtomicReference<>("-");
    private static final AtomicReference<String> DEVICE_PREFERENCE = new AtomicReference<>("-");
    private static final AtomicReference<String> FALLBACK_REASON = new AtomicReference<>("");
    private static final AtomicReference<String> CURRENT_OPERATION = new AtomicReference<>("");

    public enum RoutePhase {
        ORIGINAL,
        LOCAL_REPAIR,
        CANDIDATE
    }

    private static final AtomicInteger INITIAL_THREADS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger CURRENT_SAMPLING_STEP = new AtomicInteger(-1);

    /**
     * 0.8.355：总进度的历史峰值，用于保证进度条**只增不减**。
     *
     * <p>为什么必须加：{@link InitialGenerationStage} 的 {@code basePercent} **不是单调递增**的
     * （PLANNING=0 / EXACT_SAMPLING=15 / EXACT_PATHING=45 / **COARSE_SAMPLING=15** /
     * **COARSE_PATHING=45** / ROAD_GENERATION=60 / POST_PROCESSING=90），而
     * {@link #computeOverallPercent} 只做上下界钳制、没有单调性保证。于是玩家会看到
     * 进度从 58% 暴跌回 15%（EXACT_PATHING → COARSE_SAMPLING 切换时），
     * 而 COARSE_SAMPLING 本身要跑 40 分钟 —— 观感上像"卡住/倒退"。
     * 另外 {@link #percent} 在 {@code total<=0} 时返回 100，阶段刚进入、总数未就绪时
     * 会先显示满值再掉回，同样需要峰值保护。</p>
     */
    private static final AtomicInteger PEAK_OVERALL_PERCENT = new AtomicInteger();

    /**
     * 0.8.422：记录每个阶段是否已经公布过它的工作量总数。
     *
     * <p>{@link #percent} 在 {@code total<=0} 时返回 100，本意是「这个阶段没活干 = 已完成」，
     * 但阶段刚进入、统计尚未跑完时 total 同样是 0 —— 两者被混为一谈。配合
     * {@link #monotonicOverallPercent} 的峰值锁存，后者会被**永久**钉在阶段终点值上：
     * 实测玩家看到进度条瞬间跳一大截（COARSE_SAMPLING 一进入就 45%），然后几十分钟不动，
     * 最后再跳一次。有了这个标记，「还没统计」返回 0（进度未知），「确实没活」才返回 100。</p>
     *
     * <p>按会话重置即可：各阶段在一次初始生成里顺序执行一次，标记只会由 false 变 true。</p>
     */
    private static final AtomicBoolean[] STAGE_PLANNED = stageFlags(InitialGenerationStage.values().length);

    private static AtomicBoolean[] stageFlags(int size) {
        AtomicBoolean[] flags = new AtomicBoolean[size];
        for (int i = 0; i < size; i++) {
            flags[i] = new AtomicBoolean();
        }
        return flags;
    }

    /** 发布本会话某阶段的实际工作量，避免用未知总数冒充已完成。 */
    private static void markPlanned(InitialGenerationStage stage) {
        if (stage != null) STAGE_PLANNED[stage.ordinal()].set(true);
    }

    public static void reset() {
        STARTED_AT.set(System.currentTimeMillis());
        STAGE.set(InitialGenerationStage.PLANNING);
        PEAK_OVERALL_PERCENT.set(0);
        for (AtomicBoolean planned : STAGE_PLANNED) planned.set(false);
        ROUTE_GENERATION_LOCKED.set(false);
        CONNECTIONS_TOTAL.set(0);
        CONNECTIONS_GENERATING.set(0);
        CONNECTIONS_DONE.set(0);
        CONNECTIONS_FAILED.set(0);
        CONNECTIONS_LOCAL_SAMPLING.set(0);
        CONNECTIONS_FALLBACK_CANDIDATES.set(0);
        ROUTE_PHASE.set(RoutePhase.ORIGINAL);
        resetPhaseCounters();
        for (AtomicBoolean started : PHASE_STARTED) started.set(false);
        ROAD_TASK_SEQUENCE.set(0L);
        CURRENT_ROAD_TASK.remove();
        ROAD_TASK_PROGRESS.clear();
        ROAD_TASK_SAMPLING_STEPS.clear();
        PLANNING_TILES_TOTAL.set(0);
        PLANNING_TILES_DONE.set(0);


        PLANNING_EMPTY_STARTS.set(0);
        PLANNING_SNAPSHOTS_READY.set(0);
        TILES_TOTAL.set(0);
        TILES_LOADED.set(0);
        TILES_SAMPLED.set(0);
        TILES_FROM_MEMORY.set(0);
        TILES_FROM_DISK.set(0);
        COARSE_PATHS_TOTAL.set(0);
        COARSE_PATHS_DONE.set(0);
        EXACT_SAMPLES_TOTAL.set(0L);
        EXACT_SAMPLES_DONE.set(0L);
        EXACT_PATHS_TOTAL.set(0);
        EXACT_PATHS_DONE.set(0);
        CONSTRUCTION_CHUNKS_TOTAL.set(0);
        CONSTRUCTION_CHUNKS_DONE.set(0);
        SAMPLES_TOTAL.set(0L);
        SAMPLES_DONE.set(0L);
        LAST_SAMPLE_SNAPSHOT_AT.set(0L);
        LAST_SAMPLE_SNAPSHOT_COUNT.set(0L);
        SAMPLES_PER_SECOND.set(0.0);
        LAST_BATCH_SAMPLES.set(0);
        LAST_BATCH_MILLIS.set(0L);
        BACKEND.set("-");
        DEVICE_NAME.set("-");
        DEVICE_PREFERENCE.set(resolveDevicePreference());
        FALLBACK_REASON.set("");
        CURRENT_OPERATION.set("initializing");
        INITIAL_THREADS.set(resolveInitialThreads());
        ACTIVE_WORKERS.set(0);
        CURRENT_SAMPLING_STEP.set(resolveConfiguredSamplingStep());
    }

    public static void finish() {
        STAGE.set(InitialGenerationStage.FINISHED);
        CURRENT_OPERATION.set("finished");
        ACTIVE_WORKERS.set(0);
        CURRENT_SAMPLING_STEP.set(-1);
    }

    public static void enterStage(InitialGenerationStage stage, String operation) {
        if (stage == null) return;
        if (ROUTE_GENERATION_LOCKED.get()
                && stage != InitialGenerationStage.ROAD_GENERATION
                && stage != InitialGenerationStage.POST_PROCESSING
                && stage != InitialGenerationStage.FINISHED) {
            return;
        }
        if (stage == InitialGenerationStage.ROAD_GENERATION) {
            ROUTE_GENERATION_LOCKED.set(true);
        }
        STAGE.set(stage);
        if (operation != null && !operation.isBlank()) {
            CURRENT_OPERATION.set(operation);
        }
    }

    public static void updateConnections(int total, int generating, int done, int failed) {
        CONNECTIONS_TOTAL.set(Math.max(0, total));
        markPlanned(InitialGenerationStage.ROAD_GENERATION);
        CONNECTIONS_GENERATING.set(Math.max(0, generating));
        CONNECTIONS_DONE.set(Math.max(0, done));
        CONNECTIONS_FAILED.set(Math.max(0, failed));
        ACTIVE_WORKERS.set(Math.max(0, generating));
    }

    /** 进入新的路线阶段时计数按本次波次重置，历史结果另行保留。 */
    public static void beginRoutePhase(RoutePhase phase, int total) {
        beginRoutePhase(phase, total, "");
    }

    /** 发布路线阶段与当前操作，避免阶段切换时界面显示空闲。 */
    public static void beginRoutePhase(RoutePhase phase, int total, String operation) {
        RoutePhase safe = phase == null ? RoutePhase.ORIGINAL : phase;
        ROUTE_PHASE.set(safe);
        int index = safe.ordinal();
        int requestedTotal = Math.max(0, total);
        if (PHASE_STARTED[index].compareAndSet(false, true)) {
            PHASE_TOTAL[index].set(requestedTotal);
            PHASE_DONE[index].set(0);
            PHASE_FAILED[index].set(0);
            PHASE_RETAINED[index].set(0);
            PHASE_HANDED_OFF[index].set(0);
        } else {
            // A phase can be entered repeatedly for finer retry waves. Keep
            // the cumulative route total/results instead of making the HUD
            // appear to disappear when the next phase starts.
            PHASE_TOTAL[index].updateAndGet(previous -> Math.max(previous, requestedTotal));
        }
        PHASE_GENERATING[index].set(0);
        PHASE_OPERATION[index].set(operation == null || operation.isBlank() ? "idle" : operation);
        if (operation != null && operation.toLowerCase(Locale.ROOT).contains("sampling")) {
            PHASE_SAMPLING_STEPS[index].set(-1);
        }
    }

    public static RoutePhase currentRoutePhase() {
        return ROUTE_PHASE.get();
    }

    public static void setRoutePhaseOperation(RoutePhase phase, String operation) {
        RoutePhase safe = phase == null ? ROUTE_PHASE.get() : phase;
        PHASE_OPERATION[safe.ordinal()].set(
                operation == null || operation.isBlank() ? "idle" : operation);
    }

    public static void routePhaseWorkerStarted() {
        PHASE_GENERATING[ROUTE_PHASE.get().ordinal()].incrementAndGet();
    }

    public static void routePhaseWorkerFinished() {
        PHASE_GENERATING[ROUTE_PHASE.get().ordinal()]
                .updateAndGet(value -> Math.max(0, value - 1));
    }

    public static void routePhaseAttemptCompleted(boolean success) {
        int index = ROUTE_PHASE.get().ordinal();
        if (success) {
            PHASE_DONE[index].incrementAndGet();
        } else {
            PHASE_FAILED[index].incrementAndGet();
        }
    }

    /** 记录路线保留或交给下阶段的处置，不将重试计成最终失败。 */
    public static void routePhaseRetained() {
        PHASE_RETAINED[ROUTE_PHASE.get().ordinal()].incrementAndGet();
    }

    /** 重试移交或收尾后发布阶段结算，避免未结任务混入完成数。 */
    public static void settleRoutePhase(RoutePhase phase, int done, int failed, int retained,
                                         int handedOff, String operation) {
        RoutePhase safe = phase == null ? ROUTE_PHASE.get() : phase;
        int index = safe.ordinal();
        PHASE_GENERATING[index].set(0);
        PHASE_DONE[index].set(Math.max(0, done));
        PHASE_FAILED[index].set(Math.max(0, failed));
        PHASE_RETAINED[index].set(Math.max(0, retained));
        PHASE_HANDED_OFF[index].set(Math.max(0, handedOff));
        PHASE_OPERATION[index].set(operation == null || operation.isBlank() ? "idle" : operation);
    }

    public static boolean isFinished() {
        return STAGE.get() == InitialGenerationStage.FINISHED;
    }

    /** 待重试与最终失败分开计数，避免在途工作被误报失败。
 *
     * Publishes routes that are no longer on their first attempt but have not
     * reached a terminal state. These counters are deliberately separate from
     * {@code connectionsFailed}: a retry is pending work, not a final failure.
     */
    public static void updateRetryStates(int localSampling, int fallbackCandidates) {
        CONNECTIONS_LOCAL_SAMPLING.set(Math.max(0, localSampling));
        CONNECTIONS_FALLBACK_CANDIDATES.set(Math.max(0, fallbackCandidates));
    }

    public static void beginRoadTask(String operation) {
        beginRoadTask(operation, null, -1);
    }

    public static void beginRoadTask(String operation, StructureConnection connection, int step) {
        long taskId = ROAD_TASK_SEQUENCE.incrementAndGet();
        CURRENT_ROAD_TASK.set(taskId);
        ROAD_TASK_PROGRESS.put(taskId, 0);
        ROAD_TASK_SAMPLING_STEPS.put(taskId, -1);
        InitialRouteTaskDiagnostics.register(taskId, connection, step, operation);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void advanceCurrentRoadTask(int permille, String operation) {
        Long taskId = CURRENT_ROAD_TASK.get();
        if (taskId == null) return;
        int bounded = Math.max(0, Math.min(999, permille));
        ROAD_TASK_PROGRESS.computeIfPresent(taskId, (ignored, previous) -> Math.max(previous, bounded));
        InitialRouteTaskDiagnostics.heartbeat(taskId, operation, "none", bounded);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void reportRoadTaskWaiting(String waitingOn) {
        Long taskId = CURRENT_ROAD_TASK.get();
        if (taskId != null) InitialRouteTaskDiagnostics.waiting(taskId, waitingOn);
    }

    public static void finishRoadTask() {
        Long taskId = CURRENT_ROAD_TASK.get();
        if (taskId != null) {
            ROAD_TASK_PROGRESS.remove(taskId);
            ROAD_TASK_SAMPLING_STEPS.remove(taskId);
            InitialRouteTaskDiagnostics.finish(taskId);
            refreshCurrentSamplingStep();
        }
        CURRENT_ROAD_TASK.remove();
    }

    /** 发布当前工作线程所用精度，避免并行波次共用错误提示。 */
    public static void setCurrentSamplingStep(int step) {
        int safe = step > 0 ? step : -1;
        Long taskId = CURRENT_ROAD_TASK.get();
        if (taskId == null) {
            CURRENT_SAMPLING_STEP.set(safe);
            if (safe > 0) PHASE_SAMPLING_STEPS[ROUTE_PHASE.get().ordinal()].set(safe);
            return;
        }
        ROAD_TASK_SAMPLING_STEPS.put(taskId, safe);
        if (safe > 0) PHASE_SAMPLING_STEPS[ROUTE_PHASE.get().ordinal()].set(safe);
        refreshCurrentSamplingStep();
    }

    public static void setPlanningTilePlan(int total, String operation) {
        int previousTiles = PLANNING_TILES_TOTAL.get();
        int completedTiles = PLANNING_TILES_DONE.get();
        int completedStarts = PLANNING_STARTS_DONE.get();
        int previousStarts = PLANNING_STARTS_TOTAL.get();
        PLANNING_TILES_TOTAL.set(Math.max(0, total));
        PLANNING_TILES_DONE.set(Math.max(0, Math.min(completedTiles, Math.max(0, total))));
        PLANNING_STARTS_TOTAL.set(Math.max(0, previousStarts));
        PLANNING_STARTS_DONE.set(Math.max(0, Math.min(completedStarts, previousStarts)));
        PLANNING_EMPTY_STARTS.set(0);
        PLANNING_SNAPSHOTS_READY.set(0);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void recordPlanningTileDone() {
        PLANNING_TILES_DONE.updateAndGet(done -> Math.min(PLANNING_TILES_TOTAL.get(), done + 1));
    }

    public static void setPlanningStartPlan(int total, String operation) {
        PLANNING_STARTS_TOTAL.set(Math.max(0, total));
        PLANNING_STARTS_DONE.set(0);
        PLANNING_EMPTY_STARTS.set(0);
        PLANNING_SNAPSHOTS_READY.set(0);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void recordPlanningStartDone(boolean empty) {
        PLANNING_STARTS_DONE.updateAndGet(done -> Math.min(PLANNING_STARTS_TOTAL.get(), done + 1));
        if (empty) PLANNING_EMPTY_STARTS.incrementAndGet();
    }

    public static void setPlanningActiveWorkers(int workers) {
        ACTIVE_WORKERS.set(Math.max(0, workers));
    }

    public static void completePlanningSnapshots(String operation) {
        PLANNING_TILES_DONE.set(PLANNING_TILES_TOTAL.get());
        PLANNING_STARTS_DONE.set(PLANNING_STARTS_TOTAL.get());
        PLANNING_SNAPSHOTS_READY.set(1);
        ACTIVE_WORKERS.set(0);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void setTilePlan(int totalTiles, long totalSamples, String operation) {
        // A retry/refinement is a new batch. Keeping the previous batch's done
        // count made the next bar start at 100% or remain frozen at its old total.
        TILES_TOTAL.set(Math.max(0, totalTiles));
        markPlanned(InitialGenerationStage.COARSE_SAMPLING);
        TILES_LOADED.set(0);
        TILES_SAMPLED.set(0);
        TILES_FROM_MEMORY.set(0);
        TILES_FROM_DISK.set(0);
        SAMPLES_TOTAL.set(Math.max(0L, totalSamples));
        SAMPLES_DONE.set(0L);
        LAST_SAMPLE_SNAPSHOT_AT.set(0L);
        LAST_SAMPLE_SNAPSHOT_COUNT.set(0L);
        SAMPLES_PER_SECOND.set(0.0);
        if (operation != null && !operation.isBlank()) {
            CURRENT_OPERATION.set(operation);
        }
    }

    public static void recordTileLoaded() {
        TILES_LOADED.updateAndGet(done -> Math.min(TILES_TOTAL.get(), done + 1));
    }

    public static void recordTileMemoryHit() {
        TILES_FROM_MEMORY.incrementAndGet();
    }

    public static void recordTileDiskHit() {
        TILES_FROM_DISK.incrementAndGet();
    }

    public static void recordTileSampled() {
        TILES_SAMPLED.incrementAndGet();
    }

    public static void setExactSamplingPlan(long totalSamples, String operation) {
        EXACT_SAMPLES_TOTAL.set(Math.max(0L, totalSamples));
        markPlanned(InitialGenerationStage.EXACT_SAMPLING);
        EXACT_SAMPLES_DONE.set(0L);
        SAMPLES_TOTAL.set(Math.max(0L, totalSamples));
        SAMPLES_DONE.set(0L);
        LAST_SAMPLE_SNAPSHOT_AT.set(0L);
        LAST_SAMPLE_SNAPSHOT_COUNT.set(0L);
        SAMPLES_PER_SECOND.set(0.0);
        LAST_BATCH_SAMPLES.set(0);
        LAST_BATCH_MILLIS.set(0L);
        if (operation != null && !operation.isBlank()) {
            CURRENT_OPERATION.set(operation);
        }
    }

    public static void recordExactSampleBatch(int sampleCount,
                                              long millis,
                                              String backend,
                                              String deviceName) {
        int safeSamples = Math.max(0, sampleCount);
        long previous = EXACT_SAMPLES_DONE.getAndUpdate(
                done -> Math.min(EXACT_SAMPLES_TOTAL.get(), done + safeSamples));
        int acceptedSamples = Math.toIntExact(Math.max(0L,
                Math.min(EXACT_SAMPLES_TOTAL.get(), previous + safeSamples) - previous));
        recordSampleBatch(backend, acceptedSamples, millis, deviceName, "");
    }

    public static void completeExactSampling() {
        EXACT_SAMPLES_DONE.set(EXACT_SAMPLES_TOTAL.get());
    }

    public static void setExactPathPlan(int totalPaths, String operation) {
        EXACT_PATHS_TOTAL.set(Math.max(0, totalPaths));
        markPlanned(InitialGenerationStage.EXACT_PATHING);
        EXACT_PATHS_DONE.set(0);
        if (operation != null && !operation.isBlank()) {
            CURRENT_OPERATION.set(operation);
        }
    }

    public static void recordExactPathDone() {
        EXACT_PATHS_DONE.updateAndGet(done -> Math.min(EXACT_PATHS_TOTAL.get(), done + 1));
    }

    /** 规划定案后发布实际道路施工量，不能从规划READY反推落块完工。 */
    public static void setConstructionChunkPlan(int totalChunks, String operation) {
        CONSTRUCTION_CHUNKS_TOTAL.set(Math.max(0, totalChunks));
        markPlanned(InitialGenerationStage.POST_PROCESSING);
        CONSTRUCTION_CHUNKS_DONE.set(0);
        if (operation != null && !operation.isBlank()) CURRENT_OPERATION.set(operation);
    }

    public static void recordConstructionChunksDone(int completedChunks) {
        int total = CONSTRUCTION_CHUNKS_TOTAL.get();
        CONSTRUCTION_CHUNKS_DONE.set(Math.max(0, total <= 0 ? completedChunks : Math.min(total, completedChunks)));
    }

    /** 完成数单调更新，避免并发完成回调覆盖较大的已完成值。 */
    public static void recordFinalRoadDone(int completedRoads) {
        int total = CONSTRUCTION_CHUNKS_TOTAL.get();
        if (total <= 0) {
            CONSTRUCTION_CHUNKS_DONE.set(Math.max(0, completedRoads));
            return;
        }
        CONSTRUCTION_CHUNKS_DONE.accumulateAndGet(completedRoads, (current, update) ->
                Math.max(current, Math.min(total, update)));
    }

    public static void setCoarsePathPlan(int totalPaths, String operation) {
        COARSE_PATHS_TOTAL.set(Math.max(0, totalPaths));
        markPlanned(InitialGenerationStage.COARSE_PATHING);
        COARSE_PATHS_DONE.set(0);
        if (operation != null && !operation.isBlank()) {
            CURRENT_OPERATION.set(operation);
        }
    }

    public static void recordCoarsePathDone() {
        COARSE_PATHS_DONE.updateAndGet(done -> Math.min(COARSE_PATHS_TOTAL.get(), done + 1));
    }

    public static void recordSampleBatch(String backend, int sampleCount, long millis, String deviceName, String fallbackReason) {
        int safeSamples = Math.max(0, sampleCount);
        long safeMillis = Math.max(0L, millis);
        SAMPLES_DONE.addAndGet(safeSamples);
        LAST_BATCH_SAMPLES.set(safeSamples);
        LAST_BATCH_MILLIS.set(safeMillis);
        if (backend != null && !backend.isBlank()) {
            BACKEND.set(backend);
        }
        if (deviceName != null && !deviceName.isBlank()) {
            DEVICE_NAME.set(deviceName);
        }
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            FALLBACK_REASON.set(fallbackReason);
        }
        updateSampleRate();
    }

    public static void setBackend(String backend, String deviceName, String fallbackReason) {
        if (backend != null && !backend.isBlank()) {
            BACKEND.set(backend);
        }
        if (deviceName != null && !deviceName.isBlank()) {
            DEVICE_NAME.set(deviceName);
        }
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            FALLBACK_REASON.set(fallbackReason);
        }
    }

    public static InitialGenerationProgressSnapshot snapshot(boolean active) {
        InitialGenerationStage stage = STAGE.get();
        int stagePercent = computeStagePercent(stage);
        // 0.8.355：改用单调版本，修「进度条回退」（阶段权重表 base 非单调 + total 未就绪返回 100）。
        int overallPercent = monotonicOverallPercent(stage, stagePercent, active);
        long startedAt = STARTED_AT.get();
        long elapsed = startedAt == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - startedAt);
        return new InitialGenerationProgressSnapshot(
                active,
                stage,
                stage.labelKey(),
                startedAt,
                elapsed,
                overallPercent,
                stagePercent,
                CONNECTIONS_TOTAL.get(),
                CONNECTIONS_GENERATING.get(),
                CONNECTIONS_DONE.get(),
                CONNECTIONS_FAILED.get(),
                CONNECTIONS_LOCAL_SAMPLING.get(),
                CONNECTIONS_FALLBACK_CANDIDATES.get(),
                PLANNING_TILES_TOTAL.get(),
                PLANNING_TILES_DONE.get(),
                PLANNING_STARTS_TOTAL.get(),
                PLANNING_STARTS_DONE.get(),
                PLANNING_EMPTY_STARTS.get(),
                TILES_TOTAL.get(),
                TILES_LOADED.get(),
                TILES_SAMPLED.get(),
                TILES_FROM_MEMORY.get(),
                TILES_FROM_DISK.get(),
                SAMPLES_TOTAL.get(),
                SAMPLES_DONE.get(),
                EXACT_PATHS_TOTAL.get(),
                EXACT_PATHS_DONE.get(),
                CONSTRUCTION_CHUNKS_TOTAL.get(),
                CONSTRUCTION_CHUNKS_DONE.get(),
                SAMPLES_PER_SECOND.get(),
                LAST_BATCH_SAMPLES.get(),
                LAST_BATCH_MILLIS.get(),
                safe(BACKEND.get()),
                safe(DEVICE_NAME.get()),
                safe(DEVICE_PREFERENCE.get()),
                safe(FALLBACK_REASON.get()),
                INITIAL_THREADS.get(),
                ACTIVE_WORKERS.get(),
                CURRENT_SAMPLING_STEP.get(),
                safe(CURRENT_OPERATION.get()),
                TerrainSamplingStats.getCacheHitRatePercent(),
                TerrainSamplingStats.getTotalNoiseSamples(),
                TerrainSamplingStats.updateAndGetSamplesPerSecond(),
                AccurateSamplingStats.getCacheHitRatePercent(),
                AccurateSamplingStats.getTotalBaseHeightSamples(),
                AccurateSamplingStats.updateAndGetSamplesPerSecond(),
                phaseProgress(RoutePhase.ORIGINAL),
                phaseProgress(RoutePhase.LOCAL_REPAIR),
                phaseProgress(RoutePhase.CANDIDATE)
        );
    }

    private static RoutePhaseProgress phaseProgress(RoutePhase phase) {
        int index = phase.ordinal();
        return new RoutePhaseProgress(
                PHASE_TOTAL[index].get(),
                PHASE_GENERATING[index].get(),
                PHASE_DONE[index].get(),
                PHASE_FAILED[index].get(),
                PHASE_RETAINED[index].get(),
                PHASE_HANDED_OFF[index].get(),
                PHASE_OPERATION[index].get(),
                PHASE_SAMPLING_STEPS[index].get());
    }

    private static AtomicInteger[] phaseCounters() {
        AtomicInteger[] counters = new AtomicInteger[RoutePhase.values().length];
        for (int i = 0; i < counters.length; i++) counters[i] = new AtomicInteger();
        return counters;
    }

    private static AtomicBoolean[] phaseFlags() {
        AtomicBoolean[] flags = new AtomicBoolean[RoutePhase.values().length];
        for (int i = 0; i < flags.length; i++) flags[i] = new AtomicBoolean();
        return flags;
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String>[] phaseOperations() {
        AtomicReference<String>[] operations = new AtomicReference[RoutePhase.values().length];
        for (int i = 0; i < operations.length; i++) operations[i] = new AtomicReference<>("idle");
        return operations;
    }

    private static void resetPhaseCounters() {
        for (int i = 0; i < RoutePhase.values().length; i++) {
            PHASE_TOTAL[i].set(0);
            PHASE_GENERATING[i].set(0);
            PHASE_DONE[i].set(0);
            PHASE_FAILED[i].set(0);
            PHASE_RETAINED[i].set(0);
            PHASE_HANDED_OFF[i].set(0);
            PHASE_OPERATION[i].set("idle");
            PHASE_SAMPLING_STEPS[i].set(-1);
        }
    }

    private static int computeStagePercent(InitialGenerationStage stage) {
        return switch (stage) {
            case PLANNING -> planningPercent();
            case EXACT_SAMPLING -> percent(EXACT_SAMPLES_DONE.get(), EXACT_SAMPLES_TOTAL.get(), stage);
            case EXACT_PATHING -> percent(EXACT_PATHS_DONE.get(), EXACT_PATHS_TOTAL.get(), stage);
            case COARSE_SAMPLING -> percent(TILES_LOADED.get(), TILES_TOTAL.get(), stage);
            case COARSE_PATHING -> percent(COARSE_PATHS_DONE.get(), COARSE_PATHS_TOTAL.get(), stage);
            case ROAD_GENERATION -> roadGenerationPercent();
            case POST_PROCESSING -> percent(CONSTRUCTION_CHUNKS_DONE.get(), CONSTRUCTION_CHUNKS_TOTAL.get(), stage);
            case FINISHED -> 100;
        };
    }

    private static int computeOverallPercent(InitialGenerationStage stage, int stagePercent, boolean active) {
        if (!active || stage == InitialGenerationStage.FINISHED) return 100;
        int raw = stage.basePercent() + Math.round(stage.weightPercent() * (stagePercent / 100.0f));
        return Math.max(0, Math.min(99, raw));
    }

    /**
     * 0.8.355：在 {@link #computeOverallPercent} 之上加「只增不减」保护。
     *
     * <p>回退有两个独立来源，本方法同时处理：</p>
     * <ol>
     *   <li>阶段权重表的 {@code basePercent} 非单调（COARSE_SAMPLING 的 base=15 低于
     *       前一个 EXACT_PATHING 的 base=45），阶段切换时总进度必然下跌；</li>
     *   <li>{@link #percent} 在 {@code total<=0} 时返回 100（"没活干=已完成"），
     *       阶段刚进入、总数尚未统计出来时会先显示满值，随后掉回真实值。</li>
     * </ol>
     * <p>记录历史峰值并取较大者，进度条即单调。代价是进度会短暂"停住"等待真实进度追上，
     * 这比倒退更符合直觉。任务结束（FINISHED / 非 active）时返回 100，不受峰值影响。</p>
     */
    private static int monotonicOverallPercent(InitialGenerationStage stage, int stagePercent, boolean active) {
        int computed = computeOverallPercent(stage, stagePercent, active);
        if (!active || stage == InitialGenerationStage.FINISHED) {
            PEAK_OVERALL_PERCENT.set(0);
            return computed;
        }
        int peak = PEAK_OVERALL_PERCENT.accumulateAndGet(computed, Math::max);
        // 阶段满值时仍允许显示 99，但绝不因阶段回退而低于已达成的峰值。
        return Math.max(computed, Math.min(99, peak));
    }

    private static int planningPercent() {
        if (PLANNING_SNAPSHOTS_READY.get() != 0) return 100;
        int tileTotal = PLANNING_TILES_TOTAL.get();
        int startTotal = PLANNING_STARTS_TOTAL.get();
        if (startTotal > 0) {
            int starts = percent(PLANNING_STARTS_DONE.get(), startTotal);
            return Math.min(99, 25 + Math.round(starts * 0.74f));
        }
        if (tileTotal > 0) {
            int tiles = percent(PLANNING_TILES_DONE.get(), tileTotal);
            return Math.min(24, Math.round(tiles * 0.24f));
        }
        return 0;
    }

    private static int roadGenerationPercent() {
        int total = CONNECTIONS_TOTAL.get();
        // Same split as percent(): a connection total of 0 means either "no roads to build"
        // (complete) or "the total has not been published yet" (unknown). Only the first is 100.
        if (total <= 0) {
            return STAGE_PLANNED[InitialGenerationStage.ROAD_GENERATION.ordinal()].get() ? 100 : 0;
        }
        int terminal = Math.min(total, Math.max(0,
                CONNECTIONS_DONE.get() + CONNECTIONS_FAILED.get()));
        long activePermille = 0L;
        for (int progress : ROAD_TASK_PROGRESS.values()) {
            activePermille += Math.max(0, Math.min(1000, progress));
        }
        long numerator = Math.min((long) total * 1000L,
                (long) terminal * 1000L + activePermille);
        int result = floorPercent(numerator, (long) total * 1000L);
        if (terminal < total || CONNECTIONS_GENERATING.get() > 0 || !ROAD_TASK_PROGRESS.isEmpty()) {
            // A worker can be visible before its first sub-step is published.
            // Keep the bar visibly active instead of rendering a misleading 0%.
            return Math.max(1, Math.min(99, result));
        }
        return 100;
    }

    private static int floorPercent(long value, long total) {
        if (total <= 0L) return 0;
        return (int) Math.max(0, Math.min(100, (value * 100L) / total));
    }

    private static int percent(long value, long total) {
        // A stage with no planned work is already complete. Returning 0 here
        // made empty post-processing and skipped sampling stages look frozen.
        if (total <= 0L) return 100;
        return (int) Math.max(0, Math.min(100, Math.round(value * 100.0 / total)));
    }

    /**
     * 0.8.422：阶段内进度百分比，区分「总数还没统计出来」与「这个阶段确实没活干」。
     *
     * <p>{@link #percent(long, long)} 把两者都当成 100，于是刚进入阶段时会先报满值；
     * {@link #monotonicOverallPercent} 的峰值锁存又把这个虚高值永久保留下来，
     * 玩家看到的就是「一下蹦满然后长时间不动」。总数一旦公布（{@link #markPlanned}），
     * 空工作仍然报 100，与旧行为一致。</p>
     */
    private static int percent(long value, long total, InitialGenerationStage stage) {
        if (total <= 0L) {
            return stage != null && STAGE_PLANNED[stage.ordinal()].get() ? 100 : 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(value * 100.0 / total)));
    }

    private static void updateSampleRate() {
        long now = System.currentTimeMillis();
        long done = SAMPLES_DONE.get();
        long previousAt = LAST_SAMPLE_SNAPSHOT_AT.get();
        if (previousAt == 0L) {
            LAST_SAMPLE_SNAPSHOT_AT.set(now);
            LAST_SAMPLE_SNAPSHOT_COUNT.set(done);
            return;
        }
        long elapsed = now - previousAt;
        if (elapsed < 500L) return;
        long previousDone = LAST_SAMPLE_SNAPSHOT_COUNT.get();
        SAMPLES_PER_SECOND.set((done - previousDone) * 1000.0 / Math.max(1L, elapsed));
        LAST_SAMPLE_SNAPSHOT_AT.set(now);
        LAST_SAMPLE_SNAPSHOT_COUNT.set(done);
    }

    private static int resolveInitialThreads() {
        try {
            return ThreadPoolManager.initialGenerationThreadCapacity();
        } catch (Throwable ignored) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
    }

    private static int resolveConfiguredSamplingStep() {
        try {
            return ConfigService.get().pathfindingCost().effectivePathGridStep();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void refreshCurrentSamplingStep() {
        int minimum = Integer.MAX_VALUE;
        for (int step : ROAD_TASK_SAMPLING_STEPS.values()) {
            if (step > 0) minimum = Math.min(minimum, step);
        }
        CURRENT_SAMPLING_STEP.set(minimum == Integer.MAX_VALUE ? -1 : minimum);
    }

    private static String resolveDevicePreference() {
        try {
            ModConfig config = ConfigService.get();
            return config.performance().openclDevicePreference().toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "AUTO";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
