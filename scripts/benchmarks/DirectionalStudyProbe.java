/* 文件职责：同JVM比较生产、冻结候选和缓存候选，分别记录求值数、分配与细化误差。 */
package net.shiroha233.roadweaver.metric;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectionalStudyProbe {
    private static volatile double sink;
    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int degrees : new int[]{0, 45}) measure(bean, "plane-" + degrees,
                DirectionalUpwindCandidateTest.plane(degrees, .25));
        for (int degrees : new int[]{0, 30, 90}) measure(bean, "ridge-" + degrees,
                DirectionalCandidateStudyTest.terrain(degrees, 2, true));
        for (int degrees : new int[]{0, 30, 45}) {
            double along = .25 * (Math.cos(Math.toRadians(degrees)) * 20 + Math.sin(Math.toRadians(degrees)) * 12) / Math.hypot(20, 12);
            double exact = Math.hypot(20, 12) * (1 + along * along);
            for (int step : new int[]{4, 2, 1}) {
                var metric = FinslerMetric.of(DirectionalCandidateStudyTest.terrain(degrees, step, false), ObstacleField.NONE);
                double value = DirectionalUpwindCandidate.solve(metric, 0, 0).at(20, 12);
                System.out.printf(Locale.ROOT, "DIRECTION_REFINEMENT degrees=%d step=%d arrival=%.9f exact=%.9f absError=%.9f%n",
                        degrees, step, value, exact, Math.abs(value - exact));
            }
        }
    }

    private static ArrivalTimeField solve(int lane, FinslerMetric metric) {
        return switch (lane) {
            case 0 -> new FastMarchingSolver(metric).solve(0, 0);
            case 1 -> FrozenDirectionalCandidate.solve(metric, 0, 0);
            default -> DirectionalUpwindCandidate.solve(metric, 0, 0);
        };
    }

    private static void measure(com.sun.management.ThreadMXBean bean, String name, TerrainField terrain) {
        var metric = FinslerMetric.of(terrain, ObstacleField.NONE);
        // 先验证冻结候选的整场误差，再计时；生产与方向候选的语义不要求相等。
        var before = solve(1, metric); var after = solve(2, metric);
        double maxDiff = 0;
        for (int x = terrain.minX(); x <= terrain.maxX(); x += terrain.step())
            for (int z = terrain.minZ(); z <= terrain.maxZ(); z += terrain.step()) {
                if (before.reachable(x, z) != after.reachable(x, z)) throw new AssertionError("reachability changed");
                if (before.reachable(x, z)) maxDiff = Math.max(maxDiff, Math.abs(before.at(x, z) - after.at(x, z)));
            }
        if (maxDiff > 1e-8) throw new AssertionError("arrival drift " + maxDiff);
        for (int warm = 0; warm < 4; warm++) for (int lane = 0; lane < 3; lane++) sink = solve(lane, metric).at(12, 12);
        long[][] ns = new long[3][9], bytes = new long[3][9];
        for (int repeat = 0; repeat < 9; repeat++) for (int j = 0; j < 3; j++) {
            int lane = (repeat + j) % 3;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            sink = solve(lane, metric).at(12, 12);
            ns[lane][repeat] = System.nanoTime() - start;
            bytes[lane][repeat] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        }
        for (int lane = 0; lane < 3; lane++) {
            Arrays.sort(ns[lane]); Arrays.sort(bytes[lane]);
            var calls = new AtomicInteger();
            solve(lane, FinslerMetric.of(DirectionalCandidateStudyTest.counted(terrain, calls), ObstacleField.NONE));
            // 求值计数另跑，不把AtomicInteger计数器成本混进被比较的求解耗时。
            System.out.printf(Locale.ROOT,
                    "DIRECTION_STUDY case=%s implementation=%s solveP50Ns=%d allocatedP50Bytes=%d gradientCalls=%d candidateMaxArrivalDiff=%.12g%n",
                    name, new String[]{"production", "frozen-candidate", "cached-candidate"}[lane],
                    ns[lane][4], bytes[lane][4], calls.get(), maxDiff);
        }
    }
}
