/* 文件职责：汇总精确高度采样的缓存、后端吞吐、校验与回退统计。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 精确采样统计
 */
public final class AccurateSamplingStats {
    private AccurateSamplingStats() {}

    private static final AtomicLong totalRequests = new AtomicLong(0);
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong baseHeightSamples = new AtomicLong(0);
    private static final AtomicLong cpuChunks = new AtomicLong(0);
    private static final AtomicLong gpuChunks = new AtomicLong(0);
    private static final AtomicLong cpuNanos = new AtomicLong(0);
    private static final AtomicLong gpuNanos = new AtomicLong(0);
    private static final AtomicLong gpuKernelNanos = new AtomicLong(0);
    private static final AtomicLong gpuLatticeKernelNanos = new AtomicLong(0);
    private static final AtomicLong gpuPreliminaryKernelNanos = new AtomicLong(0);
    private static final AtomicLong gpuAquiferKernelNanos = new AtomicLong(0);
    private static final AtomicLong gpuHeightKernelNanos = new AtomicLong(0);
    private static final AtomicLong gpuQueueWaitNanos = new AtomicLong(0);
    private static final AtomicLong hybridGpuRequests = new AtomicLong(0);
    private static final AtomicLong hybridGpuColumns = new AtomicLong(0);
    private static final AtomicLong hybridCpuDivertedRequests = new AtomicLong(0);
    private static final AtomicLong hybridCpuDivertedColumns = new AtomicLong(0);
    private static final AtomicLong validationPasses = new AtomicLong(0);
    private static final AtomicLong validationFailures = new AtomicLong(0);
    /**
     * D 阶段按 chunk 批量的放大诊断：请求列数 / 整块数 / 实际计算列数。
     *
     * <p>真机要回答的问题是"stage D 的整块预取到底算了多少列，这些列是不是后面
     * 最终校验也要用的"。请求列数来自路径自身的缺列，计算列数 = 整块数 × 256 + 稀疏列数，
     * 和 {@code final validation totalColumns} 对比即可判断有没有白算。</p>
     */
    private static final AtomicLong chunkBatchRequests = new AtomicLong(0);
    private static final AtomicLong chunkBatchRequestedColumns = new AtomicLong(0);
    private static final AtomicLong chunkBatchWholeChunks = new AtomicLong(0);
    private static final AtomicLong chunkBatchComputedColumns = new AtomicLong(0);
    private static final AtomicLong fallbacks = new AtomicLong(0);
    private static volatile String validationStatus = "NOT_RUN";
    private static volatile String lastFallbackReason = "";

    private static volatile long lastSnapshotTimeMs = 0;
    private static volatile long lastSnapshotSamples = 0;
    private static volatile double samplesPerSecond = 0.0;

    public static void recordCacheHit() {
        totalRequests.incrementAndGet();
        cacheHits.incrementAndGet();
    }

    public static void recordCacheMiss() {
        totalRequests.incrementAndGet();
        baseHeightSamples.addAndGet(AccurateHeightChunk.COLUMN_COUNT);
    }

    public static void recordColumnCacheHit() {
        totalRequests.incrementAndGet();
        cacheHits.incrementAndGet();
    }

    public static void recordColumnCacheMiss() {
        totalRequests.incrementAndGet();
        baseHeightSamples.incrementAndGet();
    }

    public static void recordTransientColumns(long columns) {
        long count = Math.max(0L, columns);
        totalRequests.addAndGet(count);
        baseHeightSamples.addAndGet(count);
    }

    public static int getCacheHitRatePercent() {
        long total = totalRequests.get();
        if (total == 0) return 0;
        return (int) Math.round((cacheHits.get() * 100.0) / total);
    }

    public static long getTotalBaseHeightSamples() {
        return baseHeightSamples.get();
    }

    public static void recordCpuBatch(int chunks, long nanos) {
        cpuChunks.addAndGet(Math.max(0, chunks));
        cpuNanos.addAndGet(Math.max(0L, nanos));
    }

    /**
     * D 阶段诊断：记录一次 {@code sampleByChunk} 的放大倍数。
     *
     * @param requestedColumns 本次请求的去重列数
     * @param wholeChunks      走整块采样的 chunk 数
     * @param computedColumns  后端实际要算的列数（整块 × 256 + 稀疏列）
     */
    public static void recordChunkBatch(int requestedColumns, int wholeChunks, int computedColumns) {
        chunkBatchRequests.incrementAndGet();
        chunkBatchRequestedColumns.addAndGet(Math.max(0, requestedColumns));
        chunkBatchWholeChunks.addAndGet(Math.max(0, wholeChunks));
        chunkBatchComputedColumns.addAndGet(Math.max(0, computedColumns));
    }

    public static long getChunkBatchRequests() {
        return chunkBatchRequests.get();
    }

    public static long getChunkBatchRequestedColumns() {
        return chunkBatchRequestedColumns.get();
    }

    public static long getChunkBatchWholeChunks() {
        return chunkBatchWholeChunks.get();
    }

    public static long getChunkBatchComputedColumns() {
        return chunkBatchComputedColumns.get();
    }

    public static void recordGpuBatch(int chunks, long nanos, long kernelNanos) {
        gpuChunks.addAndGet(Math.max(0, chunks));
        gpuNanos.addAndGet(Math.max(0L, nanos));
        gpuKernelNanos.addAndGet(Math.max(0L, kernelNanos));
    }

    public static void recordGpuColumnBatch(int columns, long nanos, long kernelNanos) {
        gpuNanos.addAndGet(Math.max(0L, nanos));
        gpuKernelNanos.addAndGet(Math.max(0L, kernelNanos));
    }

    public static void recordGpuQueueWait(long nanos) {
        gpuQueueWaitNanos.addAndGet(Math.max(0L, nanos));
    }

    public static long recordHybridGpuDispatch(long columns) {
        hybridGpuColumns.addAndGet(Math.max(0L, columns));
        return hybridGpuRequests.incrementAndGet();
    }

    public static long recordHybridCpuDiversion(long columns) {
        hybridCpuDivertedColumns.addAndGet(Math.max(0L, columns));
        return hybridCpuDivertedRequests.incrementAndGet();
    }

    public static void recordGpuKernelStages(long latticeNanos,
                                             long preliminaryNanos,
                                             long aquiferNanos,
                                             long heightNanos) {
        gpuLatticeKernelNanos.addAndGet(Math.max(0L, latticeNanos));
        gpuPreliminaryKernelNanos.addAndGet(Math.max(0L, preliminaryNanos));
        gpuAquiferKernelNanos.addAndGet(Math.max(0L, aquiferNanos));
        gpuHeightKernelNanos.addAndGet(Math.max(0L, heightNanos));
    }

    public static void recordValidationStart() {
        validationStatus = "RUNNING";
    }

    public static void recordValidationPass() {
        validationPasses.incrementAndGet();
        validationStatus = "PASSED";
    }

    public static void recordValidationFailure() {
        validationFailures.incrementAndGet();
        validationStatus = "FAILED";
    }

    public static void recordFallback() {
        recordFallback("unknown");
    }

    public static void recordFallback(String reason) {
        fallbacks.incrementAndGet();
        lastFallbackReason = reason == null ? "unknown" : reason;
    }

    public static BackendSnapshot backendSnapshot() {
        return new BackendSnapshot(
                cpuChunks.get(),
                gpuChunks.get(),
                cpuChunks.get() * AccurateHeightChunk.COLUMN_COUNT,
                gpuChunks.get() * AccurateHeightChunk.COLUMN_COUNT,
                cpuNanos.get(),
                gpuNanos.get(),
                gpuKernelNanos.get(),
                gpuLatticeKernelNanos.get(),
                gpuPreliminaryKernelNanos.get(),
                gpuAquiferKernelNanos.get(),
                gpuHeightKernelNanos.get(),
                gpuQueueWaitNanos.get(),
                hybridGpuRequests.get(),
                hybridGpuColumns.get(),
                hybridCpuDivertedRequests.get(),
                hybridCpuDivertedColumns.get(),
                validationPasses.get(),
                validationFailures.get(),
                fallbacks.get(),
                validationStatus,
                lastFallbackReason);
    }

    public static double updateAndGetSamplesPerSecond() {
        long now = System.currentTimeMillis();
        long currentSamples = baseHeightSamples.get();

        if (lastSnapshotTimeMs == 0) {
            lastSnapshotTimeMs = now;
            lastSnapshotSamples = currentSamples;
            return 0.0;
        }

        long elapsedMs = now - lastSnapshotTimeMs;
        if (elapsedMs >= 500) {
            long delta = currentSamples - lastSnapshotSamples;
            samplesPerSecond = (delta * 1000.0) / elapsedMs;
            lastSnapshotTimeMs = now;
            lastSnapshotSamples = currentSamples;
        }

        return samplesPerSecond;
    }

    public static void reset() {
        totalRequests.set(0);
        cacheHits.set(0);
        baseHeightSamples.set(0);
        cpuChunks.set(0);
        gpuChunks.set(0);
        cpuNanos.set(0);
        gpuNanos.set(0);
        gpuKernelNanos.set(0);
        gpuLatticeKernelNanos.set(0);
        gpuPreliminaryKernelNanos.set(0);
        gpuAquiferKernelNanos.set(0);
        gpuHeightKernelNanos.set(0);
        gpuQueueWaitNanos.set(0);
        hybridGpuRequests.set(0);
        hybridGpuColumns.set(0);
        hybridCpuDivertedRequests.set(0);
        hybridCpuDivertedColumns.set(0);
        validationPasses.set(0);
        validationFailures.set(0);
        chunkBatchRequests.set(0);
        chunkBatchRequestedColumns.set(0);
        chunkBatchWholeChunks.set(0);
        chunkBatchComputedColumns.set(0);
        fallbacks.set(0);
        validationStatus = "NOT_RUN";
        lastFallbackReason = "";
        lastSnapshotTimeMs = 0;
        lastSnapshotSamples = 0;
        samplesPerSecond = 0.0;
    }

    public record BackendSnapshot(long cpuChunks,
                                  long gpuChunks,
                                  long cpuColumns,
                                  long gpuColumns,
                                   long cpuNanos,
                                   long gpuNanos,
                                   long gpuKernelNanos,
                                   long gpuLatticeKernelNanos,
                                   long gpuPreliminaryKernelNanos,
                                   long gpuAquiferKernelNanos,
                                   long gpuHeightKernelNanos,
                                   long gpuQueueWaitNanos,
                                   long hybridGpuRequests,
                                   long hybridGpuColumns,
                                   long hybridCpuDivertedRequests,
                                   long hybridCpuDivertedColumns,
                                   long validationPasses,
                                  long validationFailures,
                                  long fallbacks,
                                  String validationStatus,
                                  String lastFallbackReason) {}
}
