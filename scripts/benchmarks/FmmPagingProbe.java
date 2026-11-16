/* 文件职责：在固定小堆内验证大域稀疏传播和密集传播，不用游戏帧率代替内核证据。 */
package net.shiroha233.roadweaver.metric;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

public final class FmmPagingProbe {
    private FmmPagingProbe() {}

    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("thread allocation unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var metric = FinslerMetric.of(terrain(16384, 16384), ObstacleField.NONE);
        for (int i = 0; i < 8; i++) new FastMarchingSolver(metric).solve(0, 0, 3, 3);
        double[] millis = new double[11]; long[] bytes = new long[11];
        ArrivalTimeField last = null;
        for (int i = 0; i < millis.length; i++) {
            long allocated = bean.getThreadAllocatedBytes(thread), began = System.nanoTime();
            last = new FastMarchingSolver(metric).solve(0, 0, 3, 3);
            millis[i] = (System.nanoTime() - began) / 1e6;
            bytes[i] = bean.getThreadAllocatedBytes(thread) - allocated;
        }
        var expected = new BaselineFastMarchingSolver(BaselineFinslerMetric.of(terrain(33, 33), ObstacleField.NONE))
                .solve(0, 0, 3, 3);
        same(expected.at(3, 3), last.at(3, 3));
        if (last.pathTo(3, 3).isEmpty() || last.width() != 16384) throw new AssertionError("near route lost");
        Arrays.sort(millis); Arrays.sort(bytes);
        System.out.printf("PAGING_NEAR_MATCH cells=268435456 heapMax=%d allocationP50=%d p50=%.3fms p95=%.3fms%n",
                Runtime.getRuntime().maxMemory(), bytes[5], millis[5], millis[10]);
        var corridor = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return z != 0; }
        };
        var far = new FastMarchingSolver(FinslerMetric.of(terrain(16384, 16384), corridor)).solve(0, 0, 16383, 0);
        same(16383.0, far.at(16383, 0));
        if (far.pathTo(16383, 0).size() != 16384) throw new AssertionError("global corridor truncated");
        System.out.println("PAGING_GLOBAL_ROUTE points=16384 target=16383:0");
        var sealed = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 12 || z == 12; }
        };
        var enclosed = new FastMarchingSolver(FinslerMetric.of(terrain(16384, 16384), sealed)).solve(0, 0, 16383, 16383);
        if (!enclosed.reachable(11, 11) || enclosed.reachable(16383, 16383)) throw new AssertionError("component changed");
        System.out.println("PAGING_ENCLOSED_MATCH reachable=11:11 unreachable=16383:16383");
        denseCase();
    }

    private static void denseCase() {
        // 参考求解器只在这个方法内暂存，完成后仅保留到达数组，避免同时保留两套无关缓存。
        var expected = denseReference();
        var actual = new FastMarchingSolver(FinslerMetric.of(terrain(1025, 1025), ObstacleField.NONE))
                .solve(0, 0, 1024, 1024);
        for (int z = 0; z < 1025; z++) for (int x = 0; x < 1025; x++) same(expected.at(x, z), actual.at(x, z));
        var a = expected.pathTo(1024, 1024); var b = actual.pathTo(1024, 1024);
        if (a.isEmpty() || a.size() != b.size()) throw new AssertionError("dense route lost");
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) throw new AssertionError("dense route changed");
        System.out.printf("PAGING_DENSE_MATCH cells=1050625 routePoints=%d%n", b.size());
    }

    private static BaselineArrivalTimeField denseReference() {
        return new BaselineFastMarchingSolver(BaselineFinslerMetric.of(terrain(1025, 1025), ObstacleField.NONE))
                .solve(0, 0, 1024, 1024);
    }

    private static TerrainField terrain(int columns, int rows) {
        return new TerrainField() {
            public int height(int x, int z) { return 64; }
            public int minX() { return 0; } public int minZ() { return 0; }
            public int maxX() { return columns - 1; } public int maxZ() { return rows - 1; }
        };
    }

    private static void same(double expected, double actual) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            throw new AssertionError(expected + " != " + actual);
        }
    }
}
