/* 文件职责：集中规划区域的运行态转换，让统计只读取完整状态，不接触世界或持久化。 */
package net.shiroha233.roadweaver.planning;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class ReadinessRegionTable<K, W> {
    enum State { UNPLANNED, QUEUED, PLANNING, READY, FAILED, UNSUPPORTED }

    static final class Entry {
        private volatile State state;
        private volatile boolean dispatched;
        volatile int attempts;
        volatile CompletableFuture<Void> planningFuture;
        // 重试排队期间保留真实失败，避免写盘故障与正常等待在诊断上不可区分。
        volatile Throwable lastFailure;

        Entry(State state) { this.state = Objects.requireNonNull(state); }
        State state() { return state; }
        boolean dispatched() { return dispatched; }
    }

    record Snapshot(int queued, int planning, int readyAhead, int failed) {
        int pending() { return queued + planning; }
    }

    private final ConcurrentHashMap<K, Entry> entries = new ConcurrentHashMap<>();
    private final Map<W, int[]> counts = new HashMap<>();
    private final Function<K, W> worldOf;

    ReadinessRegionTable(Function<K, W> worldOf) {
        this.worldOf = Objects.requireNonNull(worldOf);
    }

    Entry get(K key) { return entries.get(key); }
    int size() { return entries.size(); }

    synchronized Entry putIfAbsent(K key, Entry entry) {
        Entry previous = entries.putIfAbsent(key, entry);
        if (previous == null) adjust(key, entry, 1);
        return previous;
    }

    synchronized boolean transition(K key, Entry expected, State next) {
        // 清理后同坐标可能已属于新会话；旧回调不能修改替代条目。
        if (entries.get(key) != expected) return false;
        Objects.requireNonNull(next);
        if (expected.state == next) return true;
        adjust(key, expected, -1);
        expected.state = next;
        adjust(key, expected, 1);
        return true;
    }

    synchronized void markDispatched(K key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.dispatched) return;
        adjust(key, entry, -1);
        entry.dispatched = true;
        adjust(key, entry, 1);
    }

    synchronized Snapshot snapshot(W world) {
        int[] values = counts.get(world);
        return values == null ? new Snapshot(0, 0, 0, 0)
                : new Snapshot(values[0], values[1], values[2], values[3]);
    }

    private void adjust(K key, Entry entry, int delta) {
        // 转换和四个计数在同一把短锁内发布，查询无需遍历历史 READY 区域。
        int slot = switch (entry.state) {
            case QUEUED -> 0;
            case PLANNING -> 1;
            case READY -> entry.dispatched ? -1 : 2;
            case FAILED -> 3;
            default -> -1;
        };
        if (slot >= 0) counts.computeIfAbsent(worldOf.apply(key), ignored -> new int[4])[slot] += delta;
    }

    // 生命周期会在锁外取消 Future；不能让外部完成回调在状态表锁内执行。
    synchronized List<Entry> values() { return List.copyOf(entries.values()); }
    synchronized List<Map.Entry<K, Entry>> entrySet() { return List.copyOf(entries.entrySet()); }
    synchronized void clear() {
        entries.clear();
        counts.clear();
    }
}
