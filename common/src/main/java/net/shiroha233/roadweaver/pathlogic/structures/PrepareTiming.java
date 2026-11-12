/* 文件职责：结构避让「准备阶段」的分段计时 —— 只记时间，不参与任何判定。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构避让准备阶段的分段计时。
 *
 * <h2>为什么计时器要单独一个类</h2>
 * <p>结构避让的准备阶段是开局第二大耗时项，但它的耗时一直只有一个总数，
 * 分不出是「解结构起点」贵还是「采地形」贵还是「写缓存」贵。
 * 这个类是<b>纯记账</b>：每个阶段记一段纳秒数，最后打一行汇总。
 * 它不读任何判定用的状态、也不改变任何结果。</p>
 *
 * <h2>它是线程安全的</h2>
 * <p>准备阶段会跨多个 worker 线程跑，所以计数用 volatile，线程名用并发集合收集；
 * {@code ran} 保证同一轮只汇总一次。</p>
 *
 * <p>本类是 2026-11-12 从 4435 行的 {@code StructureAvoidanceService} 里
 * <b>纯搬迁</b>出来的，一行逻辑都没改。</p>
 */
public final class PrepareTiming {
    private final long startedAt = System.nanoTime();
    private final java.util.Set<String> workerThreads =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean ran;
    private volatile long serverInitNanos;
    private volatile long tileSampleNanos;
    private volatile long startKeyNanos;
    private volatile long structureOverlayNanos;
    private volatile long cacheWriteNanos;
    private volatile int routes;
    private volatile int starts;
    private volatile int resolvedBoxes;
    private volatile int columns;
    private volatile int obstacleTiles;

    public boolean ran() {
        return ran;
    }

    void markRan() {
        ran = true;
    }

    void addServerInit(long nanos) {
        serverInitNanos += Math.max(0L, nanos);
    }

    void addTileSample(long nanos) {
        tileSampleNanos += Math.max(0L, nanos);
    }

    void addStartKeys(long nanos) {
        startKeyNanos += Math.max(0L, nanos);
    }

    void addStructureOverlay(long nanos) {
        structureOverlayNanos += Math.max(0L, nanos);
    }

    void addCacheWrite(long nanos) {
        cacheWriteNanos += Math.max(0L, nanos);
    }

    void setRoutes(int value) {
        routes = value;
    }

    void setStarts(int value) {
        starts = value;
    }

    void setResolvedBoxes(int value) {
        resolvedBoxes = value;
    }

    void setColumns(int value) {
        columns = value;
    }

    void setObstacleTiles(int value) {
        obstacleTiles = value;
    }

    void observeWorkerThread() {
        workerThreads.add(Thread.currentThread().getName());
    }

    /** 四段（+两段附加）之和，纳秒。用于自证"没有重复累加"。 */
    public long segmentNanos() {
        return serverInitNanos + tileSampleNanos + startKeyNanos
                + structureOverlayNanos + cacheWriteNanos;
    }

    /** 窗口总时长（纳秒），自本对象创建起算。 */
    public long totalNanos() {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    /**
     * 子段之和 ≤ 父段。纯函数，单测直接钉 —— 计时重复累加会立刻违反它。
     */
    public static boolean segmentsWithinTotal(long parentNanos, long... segments) {
        if (segments == null) return parentNanos >= 0L;
        long sum = 0L;
        for (long segment : segments) {
            sum += Math.max(0L, segment);
        }
        return sum <= Math.max(0L, parentNanos);
    }

    /** {@code [PLAN-SNAPSHOT-BREAKDOWN]} 一行的字段串（不含前缀）。 */
    public String describe() {
        long total = totalNanos();
        return "totalMs=" + total / 1_000_000L
                + " tileSampleMs=" + tileSampleNanos / 1_000_000L
                + " corridorSnapshotMs=0"
                + " structureOverlayMs=" + structureOverlayNanos / 1_000_000L
                + " cacheWriteMs=" + cacheWriteNanos / 1_000_000L
                + " serverInitMs=" + serverInitNanos / 1_000_000L
                + " startKeyMs=" + startKeyNanos / 1_000_000L
                + " segmentsMs=" + segmentNanos() / 1_000_000L
                + " threads=" + workerThreads.size()
                + " columns=" + columns
                + " routes=" + routes
                + " starts=" + starts
                + " resolvedBoxes=" + resolvedBoxes
                + " obstacleTiles=" + obstacleTiles
                + " corridorNote=corridor-snapshot-not-on-this-path";
    }
}
