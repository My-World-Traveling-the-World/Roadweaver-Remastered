package net.shiroha233.roadweaver.compat;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 只量相同按需查询的调度，外部引擎以固定纯计算代替，不能外推河流生成时间。 */
public final class StreamsBatchProbe {
    private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long retained;
    public static void main(String[] args) throws Exception {
        var executor = Executors.newFixedThreadPool(1);
        var json = new StringBuilder("{\"unit\":\"milliseconds_per_run\",\"allocation_unit\":\"caller_bytes_per_run\",\"warmups\":3,\"samples\":9,\"cases\":[");
        try {
            int[] sizes = {16, 256, 1024};
            for (int c = 0; c < sizes.length; c++) {
                int size = sizes[c];
                for (int i = 0; i < 3; i++) { measure(executor, size, false); measure(executor, size, true); }
                double[][] old = new double[9][], next = new double[9][];
                for (int i = 0; i < 9; i++) {
                    if ((i & 1) == 0) { old[i] = measure(executor, size, false); next[i] = measure(executor, size, true); }
                    else { next[i] = measure(executor, size, true); old[i] = measure(executor, size, false); }
                }
                if (c > 0) json.append(',');
                json.append("{\"queries\":").append(size).append(",\"old_tasks\":").append(size)
                        .append(",\"new_tasks\":").append((size + 63) / 64)
                        .append(",\"old_ms_p50\":").append(median(old, 0)).append(",\"new_ms_p50\":").append(median(next, 0))
                        .append(",\"old_bytes_p50\":").append(median(old, 1)).append(",\"new_bytes_p50\":").append(median(next, 1)).append('}');
            }
        } finally { executor.shutdownNow(); }
        json.append("]}\n"); Files.writeString(Path.of(args[0]), json); System.out.print(json);
    }
    private static double[] measure(ExecutorService executor, int size, boolean current) throws Exception {
        long allocation = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        long sum;
        if (current) sum = StreamsQueryBatch.run(executor, 1000, () -> {
            long value = 0;
            for (int i = 0; i < size; i++) {
                final int n = i;
                try { value += (int) StreamsQueryBatch.query(() -> calculate(n)); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }
            return value;
        });
        else {
            sum = 0;
            for (int i = 0; i < size; i++) { final int n = i; sum += executor.submit(() -> calculate(n)).get(1, TimeUnit.SECONDS); }
        }
        long expected = 0;
        for (int i = 0; i < size; i++) expected += calculate(i);
        if (sum != expected) throw new AssertionError("查询结果不等价");
        retained = sum;
        return new double[]{(System.nanoTime() - start) / 1e6, MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocation};
    }
    private static int calculate(int value) { for (int i = 0; i < 32; i++) value = value * 31 + i; return value; }
    private static double median(double[][] values, int column) {
        double[] sorted = Arrays.stream(values).mapToDouble(row -> row[column]).sorted().toArray();
        return sorted[sorted.length / 2];
    }
}
