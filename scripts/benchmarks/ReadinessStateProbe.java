/* 文件职责：同 JVM 比较实际状态表的读取收益与转换成本，不把纯内核数据当作游戏帧率。 */
package net.shiroha233.roadweaver.planning;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.LongSupplier;

final class ReadinessStateProbe {
    private static final int BATCH = 500, WARMUP = 5, SAMPLES = 11;
    private static volatile long consumed;
    private record Key(int world, int region) { }
    private record Measurement(long nanos, long bytes, long checksum) { }

    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int regions : new int[]{512, 8_192, 65_536}) {
            var old = new BaselineReadinessRegionTable<Key, Integer>(Key::world);
            var current = new ReadinessRegionTable<Key, Integer>(Key::world);
            for (int i = 0; i < regions; i++) {
                Key key = new Key(i & 1, i);
                String state = i < 512 ? (i % 4 == 0 ? "QUEUED" : "PLANNING") : "READY";
                old.putIfAbsent(key, new BaselineReadinessRegionTable.Entry(BaselineReadinessRegionTable.State.valueOf(state)));
                current.putIfAbsent(key, new ReadinessRegionTable.Entry(ReadinessRegionTable.State.valueOf(state)));
            }
            Key first = new Key(0, 0);
            var before = old.get(first);
            var after = current.get(first);
            // 先精确比较各世界的全部字段，再测重复读取，防止空结果使优化显得便宜。
            for (int world = 0; world < 2; world++) {
                if (!old.snapshot(world).toString().equals(current.snapshot(world).toString())) {
                    throw new AssertionError("state mismatch");
                }
            }
            LongSupplier oldRead = () -> {
                long checksum = 0;
                for (int i = 0; i < BATCH; i++) {
                    var sample = old.snapshot(i & 1);
                    checksum += sample.pending() + sample.readyAhead() * 7L + sample.failed() * 11L;
                }
                return checksum;
            };
            LongSupplier newRead = () -> {
                long checksum = 0;
                for (int i = 0; i < BATCH; i++) {
                    var sample = current.snapshot(i & 1);
                    checksum += sample.pending() + sample.readyAhead() * 7L + sample.failed() * 11L;
                }
                return checksum;
            };
            LongSupplier oldWrite = () -> {
                for (int i = 0; i < BATCH; i++) {
                    if (!old.transition(first, before, BaselineReadinessRegionTable.State.PLANNING)
                            || !old.transition(first, before, BaselineReadinessRegionTable.State.FAILED)
                            || !old.transition(first, before, BaselineReadinessRegionTable.State.QUEUED)) throw new AssertionError();
                }
                return 3L * BATCH;
            };
            LongSupplier newWrite = () -> {
                for (int i = 0; i < BATCH; i++) {
                    if (!current.transition(first, after, ReadinessRegionTable.State.PLANNING)
                            || !current.transition(first, after, ReadinessRegionTable.State.FAILED)
                            || !current.transition(first, after, ReadinessRegionTable.State.QUEUED)) throw new AssertionError();
                }
                return 3L * BATCH;
            };
            compare(bean, regions, "snapshot-500", oldRead, newRead);
            compare(bean, regions, "transition-1500", oldWrite, newWrite);
            if (!old.snapshot(0).toString().equals(current.snapshot(0).toString())) throw new AssertionError("mutation mismatch");
        }
        System.out.println("READINESS_PROBE_PASS warmup=" + WARMUP + " samples=" + SAMPLES + " consumed=" + consumed);
    }

    private static void compare(com.sun.management.ThreadMXBean bean, int regions, String operation,
                                LongSupplier old, LongSupplier current) {
        for (int i = 0; i < WARMUP; i++) { measure(bean, old); measure(bean, current); }
        Measurement[] baseline = new Measurement[SAMPLES], optimized = new Measurement[SAMPLES];
        // 交替先后顺序；p95 为这 11 个批次中靠后的样本，不是游戏尾延迟。
        for (int i = 0; i < SAMPLES; i++) {
            if ((i & 1) == 0) { baseline[i] = measure(bean, old); optimized[i] = measure(bean, current); }
            else { optimized[i] = measure(bean, current); baseline[i] = measure(bean, old); }
            if (baseline[i].checksum != optimized[i].checksum || baseline[i].checksum <= 0) throw new AssertionError("checksum mismatch");
        }
        print(regions, operation, "full-scan", baseline);
        print(regions, operation, "incremental", optimized);
    }

    private static Measurement measure(com.sun.management.ThreadMXBean bean, LongSupplier operation) {
        long thread = Thread.currentThread().threadId(), allocation = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime(), checksum = operation.getAsLong(), elapsed = System.nanoTime() - start;
        long bytes = bean.getThreadAllocatedBytes(thread) - allocation;
        consumed = checksum;
        return new Measurement(elapsed, bytes, checksum);
    }

    private static void print(int regions, String operation, String version, Measurement[] samples) {
        long[] nanos = Arrays.stream(samples).mapToLong(Measurement::nanos).sorted().toArray();
        long[] bytes = Arrays.stream(samples).mapToLong(Measurement::bytes).sorted().toArray();
        System.out.printf(Locale.ROOT, "READY_PROBE regions=%d operation=%s version=%s p50Ms=%.6f p95Ms=%.6f allocationP50=%d%n",
                regions, operation, version, nanos[SAMPLES / 2] / 1e6,
                nanos[(int) Math.ceil(SAMPLES * .95) - 1] / 1e6, bytes[SAMPLES / 2]);
    }
}
