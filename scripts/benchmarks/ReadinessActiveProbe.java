/* 文件职责：对照活动区域的全局扫描与按世界分桶，区分真实单世界规模和合成多世界规模。 */
package net.shiroha233.roadweaver.planning;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class ReadinessActiveProbe {
    private record Key(int world, int region) { }
    private static final int QUERIES = 100_000;
    private static volatile long consumed;

    public static void main(String[] args) {
        for (int worlds : new int[]{1, 16}) for (int lanes : new int[]{4, 8}) {
            Set<Key> global = ConcurrentHashMap.newKeySet();
            Map<Integer, Set<Key>> buckets = new ConcurrentHashMap<>();
            for (int w = 0; w < worlds; w++) for (int i = 0; i < lanes; i++) {
                Key key = new Key(w, i); global.add(key);
                buckets.computeIfAbsent(w, unused -> ConcurrentHashMap.newKeySet()).add(key);
            }
            LongSupplier scan = () -> {
                long count = 0;
                for (int q = 0; q < QUERIES; q++) for (Key key : global) if (key.world == 0) count++;
                return count;
            };
            LongSupplier indexed = () -> {
                long count = 0;
                for (int q = 0; q < QUERIES; q++) count += buckets.get(0).size();
                return count;
            };
            for (int i = 0; i < 5; i++) { measure(scan, lanes); measure(indexed, lanes); }
            long[] old = new long[11], next = new long[11];
            for (int i = 0; i < old.length; i++) {
                if ((i & 1) == 0) { old[i] = measure(scan, lanes); next[i] = measure(indexed, lanes); }
                else { next[i] = measure(indexed, lanes); old[i] = measure(scan, lanes); }
            }
            Arrays.sort(old); Arrays.sort(next);
            // 报单次查询微秒；16世界只是压力原型，生产入口并不接受这些并行世界。
            System.out.printf(Locale.ROOT, "ACTIVE_PROBE worlds=%d lanes=%d queries=%d scanP50Us=%.5f indexP50Us=%.5f scanP95Us=%.5f indexP95Us=%.5f visited=%d->%d%n",
                    worlds, lanes, QUERIES, old[5] / (QUERIES * 1000.0), next[5] / (QUERIES * 1000.0),
                    old[10] / (QUERIES * 1000.0), next[10] / (QUERIES * 1000.0), worlds * lanes, 0);
        }
        System.out.println("ACTIVE_PROBE_PASS consumed=" + consumed);
    }
    private static long measure(LongSupplier query, int lanes) {
        long start = System.nanoTime(), count = query.getAsLong(), time = System.nanoTime() - start;
        if (count != (long) QUERIES * lanes) throw new AssertionError("活动数对拍失败");
        consumed = count; return time;
    }
}
