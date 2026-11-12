package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Low-overhead aggregate timings for the initial detailed-road phase. */
public final class DetailedRoadGenerationStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private static final LongAdder ROADS = new LongAdder();
    private static final LongAdder COMPLETED_ROADS = new LongAdder();
    private static final LongAdder FAILED_ROADS = new LongAdder();
    private static final LongAdder ROAD_NANOS = new LongAdder();
    private static final LongAdder PLANNED_PATH_LOOKUP_NANOS = new LongAdder();
    private static final LongAdder EXACT_HEIGHT_NANOS = new LongAdder();
    private static final LongAdder GRADE_REPAIR_NANOS = new LongAdder();
    private static final LongAdder STRUCTURE_CORRIDOR_NANOS = new LongAdder();
    private static final LongAdder GLOBAL_REROUTE_NANOS = new LongAdder();
    private static final LongAdder SEGMENT_BUILD_NANOS = new LongAdder();
    private static final LongAdder CONSTRUCTION_SETUP_NANOS = new LongAdder();
    private static final LongAdder STREAMS_PREWARM_NANOS = new LongAdder();
    private static final LongAdder SPAN_EXTRACTION_NANOS = new LongAdder();
    private static final LongAdder TARGET_HEIGHT_NANOS = new LongAdder();
    /** Between the target-height computation and final validation: conflict repair, consistency, volume. */
    private static final LongAdder PLANNING_REPAIR_NANOS = new LongAdder();
    /**
     * 0.9.013：{@code planningRepairMs} 内部的两个大子项。
     *
     * <p>背景：真机一局 {@code planningRepairMs=50,059}，而该段是「修坡→水面验收→台阶修复→
     * 体积校验」一整块未拆分的代码。已计量合计只有 122,665 ms / wallMs 233,558，
     * 剩下 111 秒落在这一段里却没有任何名字。这里先给两个最可能的耗时段命名：
     * 水面验收（{@code FinalRoadWaterValidator.validate}，一次修复里最多调 5 遍）与
     * 路面台阶修复（{@code RoadDataValidator.repairFinalHeightSteps}）。
     * 两者都是 planningRepair 的子集，所以 planningRepairMs 仍 ≥ 它们之和。</p>
     */
    private static final LongAdder WATER_VALIDATION_NANOS = new LongAdder();
    private static final LongAdder HEIGHT_STEP_REPAIR_NANOS = new LongAdder();
    /** The closing validation pass, measured per route before but never surfaced in this line. */
    private static final LongAdder FINAL_VALIDATION_NANOS = new LongAdder();
    private static final LongAdder ROADSIDE_STRUCTURE_NANOS = new LongAdder();
    private static final LongAdder ROADSIDE_VILLAGE_NANOS = new LongAdder();
    private static final LongAdder CORRIDOR_REFRESHES = new LongAdder();
    private static final LongAdder CORRIDOR_SKIPS = new LongAdder();
    private static final LongAdder CORRIDOR_NEW_CHUNKS = new LongAdder();
    private static final LongAdder CORRIDOR_NEW_STARTS = new LongAdder();

    // Periodic runtime summary state (guarded by synchronized methods).
    private static long lastLoggedTotalNanos = 0L;
    private static long lastSummaryAtNanos = 0L;
    /** 上次打印 D 阶段放大诊断时的请求数，避免周期性汇总重复打印同一份数据。 */
    private static long lastChunkBatchRequests = -1L;
    private static final long SUMMARY_MIN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private DetailedRoadGenerationStats() {
    }

    public static void reset() {
        ROADS.reset();
        COMPLETED_ROADS.reset();
        FAILED_ROADS.reset();
        ROAD_NANOS.reset();
        PLANNED_PATH_LOOKUP_NANOS.reset();
        EXACT_HEIGHT_NANOS.reset();
        GRADE_REPAIR_NANOS.reset();
        STRUCTURE_CORRIDOR_NANOS.reset();
        GLOBAL_REROUTE_NANOS.reset();
        SEGMENT_BUILD_NANOS.reset();
        CONSTRUCTION_SETUP_NANOS.reset();
        STREAMS_PREWARM_NANOS.reset();
        SPAN_EXTRACTION_NANOS.reset();
        TARGET_HEIGHT_NANOS.reset();
        ROADSIDE_STRUCTURE_NANOS.reset();
        ROADSIDE_VILLAGE_NANOS.reset();
        CORRIDOR_REFRESHES.reset();
        CORRIDOR_SKIPS.reset();
        CORRIDOR_NEW_CHUNKS.reset();
        CORRIDOR_NEW_STARTS.reset();
        lastLoggedTotalNanos = 0L;
        lastSummaryAtNanos = 0L;
        lastChunkBatchRequests = -1L;
    }

    public static void recordRoad(long nanos, boolean completed) {
        // Counters were startup-only (isActive-gated), which made runtime road
        // timing invisible to logSummary/logSummaryIfDue. reset() at startup
        // keeps the startup summary clean; runtime roads now accumulate for
        // the periodic runtime summary.
        ROADS.increment();
        ROAD_NANOS.add(nanos);
        if (completed) COMPLETED_ROADS.increment();
        else FAILED_ROADS.increment();
    }

    public static void recordPlannedPathLookup(long nanos) { add(PLANNED_PATH_LOOKUP_NANOS, nanos); }
    public static void recordExactHeight(long nanos) { add(EXACT_HEIGHT_NANOS, nanos); }
    public static void recordGradeRepair(long nanos) { add(GRADE_REPAIR_NANOS, nanos); }
    public static void recordStructureCorridor(long nanos) { add(STRUCTURE_CORRIDOR_NANOS, nanos); }
    public static void recordGlobalReroute(long nanos) { add(GLOBAL_REROUTE_NANOS, nanos); }
    public static void recordSegmentBuild(long nanos) { add(SEGMENT_BUILD_NANOS, nanos); }
    public static void recordConstructionSetup(long nanos) { add(CONSTRUCTION_SETUP_NANOS, nanos); }
    public static void recordStreamsPrewarm(long nanos) { add(STREAMS_PREWARM_NANOS, nanos); }
    public static void recordSpanExtraction(long nanos) { add(SPAN_EXTRACTION_NANOS, nanos); }
    public static void recordTargetHeight(long nanos) { add(TARGET_HEIGHT_NANOS, nanos); }
    public static void recordPlanningRepair(long nanos) { add(PLANNING_REPAIR_NANOS, nanos); }
    /** 0.9.013：planningRepair 的子项 —— 水面验收（一次修复最多 5 次调用，累计）。 */
    public static void recordWaterValidation(long nanos) { add(WATER_VALIDATION_NANOS, nanos); }
    /** 0.9.013：planningRepair 的子项 —— 路面台阶修复。 */
    public static void recordHeightStepRepair(long nanos) { add(HEIGHT_STEP_REPAIR_NANOS, nanos); }
    public static void recordFinalValidation(long nanos) { add(FINAL_VALIDATION_NANOS, nanos); }
    public static void recordRoadsideStructure(long nanos) { add(ROADSIDE_STRUCTURE_NANOS, nanos); }
    public static void recordRoadsideVillage(long nanos) { add(ROADSIDE_VILLAGE_NANOS, nanos); }

    public static void recordCorridorRefresh(int newChunks, int newStarts, boolean skipped) {
        if (!InitialGenManager.isActive()) return;
        CORRIDOR_REFRESHES.increment();
        if (skipped) CORRIDOR_SKIPS.increment();
        if (newChunks > 0) CORRIDOR_NEW_CHUNKS.add(newChunks);
        if (newStarts > 0) CORRIDOR_NEW_STARTS.add(newStarts);
    }

    public static void recordCorridorNewStarts(int newStarts) {
        if (!InitialGenManager.isActive()) return;
        if (newStarts > 0) CORRIDOR_NEW_STARTS.add(newStarts);
    }

    public static void logSummary(long wallNanos) {
        LOGGER.info("RoadWeaver detailed generation timing: roads={} completed={} failed={} wallMs={} "
                        + "roadWorkerMs={} plannedLookupMs={} exactHeightMs={} gradeRepairMs={} "
                        + "structureCorridorMs={} globalRerouteMs={} segmentBuildMs={} constructionSetupMs={} "
                        + "streamsPrewarmMs={} spanExtractionMs={} targetHeightMs={} planningRepairMs={} "
                        + "finalValidationMs={} "
                        + "waterValidationMs={} heightStepRepairMs={} "
                        + "roadsideStructuresMs={} roadsideVillagesMs={} corridorRefreshes={} "
                        + "corridorSkips={} corridorNewChunks={} corridorNewStarts={}",
                ROADS.sum(), COMPLETED_ROADS.sum(), FAILED_ROADS.sum(), wallNanos / 1_000_000L,
                millis(ROAD_NANOS),
                millis(PLANNED_PATH_LOOKUP_NANOS), millis(EXACT_HEIGHT_NANOS), millis(GRADE_REPAIR_NANOS),
                millis(STRUCTURE_CORRIDOR_NANOS), millis(GLOBAL_REROUTE_NANOS), millis(SEGMENT_BUILD_NANOS),
                millis(CONSTRUCTION_SETUP_NANOS), millis(STREAMS_PREWARM_NANOS), millis(SPAN_EXTRACTION_NANOS),
                millis(TARGET_HEIGHT_NANOS), millis(PLANNING_REPAIR_NANOS), millis(FINAL_VALIDATION_NANOS),
                millis(WATER_VALIDATION_NANOS), millis(HEIGHT_STEP_REPAIR_NANOS),
                millis(ROADSIDE_STRUCTURE_NANOS), millis(ROADSIDE_VILLAGE_NANOS),
                CORRIDOR_REFRESHES.sum(), CORRIDOR_SKIPS.sum(), CORRIDOR_NEW_CHUNKS.sum(),
                CORRIDOR_NEW_STARTS.sum());
        logChunkBatch();
    }

    /**
     * D 阶段放大诊断：{@code sampleByChunk} 的请求列数 vs 实际计算列数。
     *
     * <p>用于回答"整块预取是不是白算"：把 {@code computed} 与同一段窗口内
     * {@code final validation totalColumns} 的量级对比。若两者接近，说明整块预取算的
     * 列就是校验要用的列，只是提前到并行度更高的阶段；若 {@code computed} 远大于校验需求，
     * 才值得动阈值。</p>
     */
    private static void logChunkBatch() {
        long requests = AccurateSamplingStats.getChunkBatchRequests();
        if (requests <= 0L || requests == lastChunkBatchRequests) return;
        lastChunkBatchRequests = requests;
        long requested = AccurateSamplingStats.getChunkBatchRequestedColumns();
        long computed = AccurateSamplingStats.getChunkBatchComputedColumns();
        LOGGER.info("RoadWeaver accurate chunk batch: requests={} requestedColumns={} wholeChunks={} "
                        + "computedColumns={} amplification={}x",
                requests, requested, AccurateSamplingStats.getChunkBatchWholeChunks(), computed,
                requested <= 0L ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", computed / (double) requested));
    }

    private static long millis(LongAdder nanos) {
        return nanos.sum() / 1_000_000L;
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void add(LongAdder counter, long nanos) {
        // No isActive gate: runtime/idle roads must feed the periodic
        // logSummaryIfDue; reset() at startup keeps windows clean.
        counter.add(nanos);
    }

    /**
     * Runtime observability: logs the same per-stage summary as
     * {@link #logSummary(long)} at most every 10 minutes, and only when at
     * least one stage timer moved since the previous summary (wallMs is 0 —
     * the wall window belongs to the caller). Call after each finished road;
     * the early-exit keeps the per-road cost at a few adds.
     */
    public static synchronized void logSummaryIfDue() {
        long total = totalStageNanos();
        if (total == lastLoggedTotalNanos) return;
        long now = System.nanoTime();
        if (lastSummaryAtNanos != 0L && now - lastSummaryAtNanos < SUMMARY_MIN_INTERVAL_NANOS) return;
        lastSummaryAtNanos = now;
        lastLoggedTotalNanos = total;
        logSummary(0L);
    }

    private static long totalStageNanos() {
        return PLANNED_PATH_LOOKUP_NANOS.sum() + EXACT_HEIGHT_NANOS.sum()
                + GRADE_REPAIR_NANOS.sum() + STRUCTURE_CORRIDOR_NANOS.sum()
                + GLOBAL_REROUTE_NANOS.sum() + SEGMENT_BUILD_NANOS.sum()
                + CONSTRUCTION_SETUP_NANOS.sum() + STREAMS_PREWARM_NANOS.sum()
                + SPAN_EXTRACTION_NANOS.sum() + TARGET_HEIGHT_NANOS.sum();
    }

    /**
     * 逐路阶段计时收集器。
     *
     * <p>目标：以后能一眼看出"哪条路的哪一步慢"。每个道路工作线程持有一份自己的
     * 收集器（ThreadLocal），各阶段代码把耗时/缺列数写进来，{@code logRouteTiming}
     * 在一条路最终校验与 span 提取都完成后打一行汇总并清零。</p>
     *
     * <p>只记录观测数据，不参与任何判定，因此不影响道路结果。</p>
     */
    public static final class RouteTiming {
        private final String connection;
        private long spanNanos;
        private long validationNanos;
        private long missingColumns;
        private long totalColumns;
        private long columnSourceCached;
        private long columnSourcePrefetched;
        private long columnSourceSampled;

        private RouteTiming(String connection) {
            this.connection = connection == null ? "?" : connection;
        }

        public void addSpanExtraction(long nanos) {
            if (nanos > 0L) spanNanos += nanos;
        }

        public void addValidation(long nanos) {
            if (nanos > 0L) validationNanos += nanos;
        }

        public void addMissingColumns(int total, int missing) {
            totalColumns += Math.max(0, total);
            missingColumns += Math.max(0, missing);
        }

        /** 缺失列来源：命中缓存 / 整块预取 / 逐列采样。 */
        public void addColumnSource(int cached, int prefetched, int sampled) {
            columnSourceCached += Math.max(0, cached);
            columnSourcePrefetched += Math.max(0, prefetched);
            columnSourceSampled += Math.max(0, sampled);
        }

        private void log() {
            if (spanNanos == 0L && validationNanos == 0L && totalColumns == 0L
                    && columnSourceCached == 0L) return;
            LOGGER.info("RoadWeaver route timing: connection={} spanMs={} finalValidationMs={} "
                            + "missingColumns={} totalColumns={}",
                    connection, nanosToMillis(spanNanos), nanosToMillis(validationNanos),
                    missingColumns, totalColumns);
            if (columnSourceCached > 0L || columnSourcePrefetched > 0L || columnSourceSampled > 0L) {
                LOGGER.info("RoadWeaver final validation column source: cached={} prefetched={} sampled={} connection={}",
                        columnSourceCached, columnSourcePrefetched, columnSourceSampled, connection);
            }
        }
    }

    private static final ThreadLocal<RouteTiming> ROUTE_TIMING = new ThreadLocal<>();

    /**
     * 开始（或复用）本线程当前道路的计时收集。一条路可能重试多次，重试会覆盖
     * 上一轮数据，只保留最后一次尝试的耗时。
     */
    public static RouteTiming beginRouteTiming(String connection) {
        RouteTiming timing = new RouteTiming(connection);
        ROUTE_TIMING.set(timing);
        return timing;
    }

    public static RouteTiming routeTiming() {
        return ROUTE_TIMING.get();
    }

    /** 打完本线程当前道路的计时行并清理。 */
    public static void logRouteTiming() {
        RouteTiming timing = ROUTE_TIMING.get();
        if (timing == null) return;
        try {
            timing.log();
        } finally {
            ROUTE_TIMING.remove();
        }
    }

    public static void clearRouteTiming() {
        ROUTE_TIMING.remove();
    }
}
