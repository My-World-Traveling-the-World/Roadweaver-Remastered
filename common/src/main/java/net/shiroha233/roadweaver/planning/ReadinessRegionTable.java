/* 文件职责：集中规划区域的运行态转换，让统计只读取完整状态，不接触世界或持久化。 */
package net.shiroha233.roadweaver.planning;

import java.util.List;
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

        Entry(State state) { this.state = Objects.requireNonNull(state); }
        State state() { return state; }
        boolean dispatched() { return dispatched; }
    }

    record Snapshot(int queued, int planning, int readyAhead, int failed) {
        int pending() { return queued + planning; }
    }

    private final ConcurrentHashMap<K, Entry> entries = new ConcurrentHashMap<>();
    private final Function<K, W> worldOf;

    ReadinessRegionTable(Function<K, W> worldOf) {
        this.worldOf = Objects.requireNonNull(worldOf);
    }

    Entry get(K key) { return entries.get(key); }
    int size() { return entries.size(); }

    synchronized Entry putIfAbsent(K key, Entry entry) {
        return entries.putIfAbsent(key, entry);
    }

    synchronized boolean transition(K key, Entry expected, State next) {
        // 清理后同坐标可能已属于新会话；旧回调不能修改替代条目。
        if (entries.get(key) != expected) return false;
        expected.state = Objects.requireNonNull(next);
        return true;
    }

    synchronized void markDispatched(K key) {
        Entry entry = entries.get(key);
        if (entry != null) entry.dispatched = true;
    }

    synchronized Snapshot snapshot(W world) {
        int queued = 0, planning = 0, readyAhead = 0, failed = 0;
        for (Map.Entry<K, Entry> candidate : entries.entrySet()) {
            if (!Objects.equals(worldOf.apply(candidate.getKey()), world)) continue;
            Entry entry = candidate.getValue();
            switch (entry.state) {
                case QUEUED -> queued++;
                case PLANNING -> planning++;
                case READY -> { if (!entry.dispatched) readyAhead++; }
                case FAILED -> failed++;
                default -> { }
            }
        }
        return new Snapshot(queued, planning, readyAhead, failed);
    }

    // 生命周期会在锁外取消 Future；不能让外部完成回调在状态表锁内执行。
    synchronized List<Entry> values() { return List.copyOf(entries.values()); }
    synchronized List<Map.Entry<K, Entry>> entrySet() { return List.copyOf(entries.entrySet()); }
    synchronized void clear() { entries.clear(); }
}
