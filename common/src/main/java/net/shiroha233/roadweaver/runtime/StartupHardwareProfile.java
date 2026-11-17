/* 文件职责：按硬件缩放开局并发，并带堆内存护栏防止开局打爆内存。 */
package net.shiroha233.roadweaver.runtime;

/** 开局并发随硬件及堆预算缩放，仍不能替代真实工作集内存验收。 */
public record StartupHardwareProfile(
        int processors,
        long maxHeapMiB,
        int roadWorkers,
        int coarseWorkers,
        int structureWorkers,
        int nativeStructureWorkers) {

    private static final long MIB = 1024L * 1024L;

    public static StartupHardwareProfile current(int configuredRoadLimit) {
        return resolve(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(),
                configuredRoadLimit);
    }

    static StartupHardwareProfile resolve(int availableProcessors,
                                          long maxHeapBytes,
                                          int configuredRoadLimit) {
        int processors = Math.max(1, availableProcessors);
        long heapMiB = Math.max(512L, maxHeapBytes / MIB);

        int reserved = processors >= 12 ? 2 : processors >= 6 ? 1 : 0;
        int cpuBudget = Math.max(1, processors - reserved);

        // Road tasks retain terrain and structure snapshots. Below 12 GiB,
        // memory becomes the useful concurrency limit before CPU count does.
        long heapGiB = Math.max(1L, heapMiB / 1024L);
        int memoryBudget = heapGiB <= 4L
                ? Math.max(2, processors / 3)
                : (int) Math.min(128L, Math.max(4L, (heapGiB - 4L) * 2L));
        int configuredCap = configuredRoadLimit <= 0 ? 128 : configuredRoadLimit;
        int roadWorkers = Math.max(1, Math.min(cpuBudget, Math.min(memoryBudget, configuredCap)));

        int coarseWorkers = Math.max(1, Math.min(24, roadWorkers));
        int structureWorkers = Math.max(2,
                Math.min(12, Math.min(roadWorkers, Math.max(4, (processors + 2) / 3))));

        // Exact structure-start resolution is read-only and runs in its own
        // executor. The old processors/3 limit left a 16-thread machine at
        // five workers even when the CPU was below half utilization. Give the
        // native lane an independent, hardware-scaled budget while keeping a
        // memory floor for small heaps. Startup prewarm is intentionally more
        // aggressive than ordinary runtime work, but remains bounded so a
        // large world cannot create an unbounded native queue.
        int nativeCpuBudget = processors >= 12
                ? Math.max(2, processors - 2)
                : Math.max(2, processors / 3);
        int nativeMemoryBudget = heapGiB <= 4L
                ? 2
                : heapGiB <= 8L
                ? 4
                : heapGiB <= 16L
                ? 8
                : heapGiB <= 24L
                ? 12
                : 16;
        int nativeWorkers = Math.max(2,
                Math.min(16, Math.min(nativeCpuBudget, nativeMemoryBudget)));
        return new StartupHardwareProfile(
                processors, heapMiB, roadWorkers, coarseWorkers, structureWorkers, nativeWorkers);
    }
}
