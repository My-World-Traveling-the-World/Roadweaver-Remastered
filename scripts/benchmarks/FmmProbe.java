/* 文件职责：在同一 JVM 中对拍并测量旧、新 FMM，避免把缓存热度当优化收益。 */
package net.shiroha233.roadweaver.metric;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class FmmProbe {
    private static final int SIDE = 289, STEP = 8, MAX = (SIDE - 1) * STEP;
    private static final int[] HEIGHTS = new int[SIDE * SIDE];
    private static volatile double sink;

    static {
        // 固定同一片起伏地形，避免随机数据或平地掩盖梯度与障碍势的成本。
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                HEIGHTS[x + z * SIDE] = 80 + (int) (15 * Math.sin(x * .08) + 18 * Math.cos(z * .11));
            }
        }
    }

    private static final class Grid implements TerrainField {
        public int height(int x, int z) { return HEIGHTS[x / STEP + (z / STEP) * SIDE]; }
        public int minX() { return 0; }
        public int minZ() { return 0; }
        public int maxX() { return MAX; }
        public int maxZ() { return MAX; }
        public int step() { return STEP; }
        public double[] gradient(int x, int z) {
            int west = Math.max(0, x - STEP), east = Math.min(MAX, x + STEP);
            int north = Math.max(0, z - STEP), south = Math.min(MAX, z + STEP);
            return new double[]{(double) (height(east, z) - height(west, z)) / (east - west),
                    (double) (height(x, south) - height(x, north)) / (south - north)};
        }
    }

    private static final TerrainField GRID = new Grid();
    private static final ObstacleField OBSTACLES = new ObstacleField() {
        public double potential(int x, int z) { return ((x / STEP + z / STEP) % 19) * 0.025; }
        public boolean impassable(int x, int z) { return x == 1000 && z > 200 && z < 1400; }
    };

    public static void main(String[] args) {
        int source = 192, target = 1728;
        // 逐位核对整场与回溯点，再计时，避免把少算或换线路当作性能提升。
        var baseline = new BaselineFastMarchingSolver(BaselineFinslerMetric.of(GRID, OBSTACLES)).solve(source, source);
        var candidate = new FastMarchingSolver(FinslerMetric.of(GRID, OBSTACLES)).solve(source, source);
        for (int z = 0; z <= MAX; z += STEP) {
            for (int x = 0; x <= MAX; x += STEP) {
                if (Double.doubleToLongBits(baseline.at(x, z)) != Double.doubleToLongBits(candidate.at(x, z))) {
                    throw new AssertionError("arrival mismatch x=" + x + " z=" + z);
                }
            }
        }
        List<int[]> before = baseline.pathTo(target, target), after = candidate.pathTo(target, target);
        if (before.isEmpty() || before.size() != after.size()) throw new AssertionError("path size");
        for (int i = 0; i < before.size(); i++) {
            if (!Arrays.equals(before.get(i), after.get(i))) throw new AssertionError("path at " + i);
        }
        System.out.printf("MATCH cells=%d routePoints=%d%n", SIDE * SIDE, before.size());
        compare(source, source + 32, 10, "near-target");
        compare(source, target, 1, "far-target");
    }

    private static void compare(int source, int target, int reps, String label) {
        for (int i = 0; i < 8; i++) {
            run(false, source, target, reps);
            run(true, source, target, reps);
        }
        double[] oldMs = new double[11], newMs = new double[11];
        for (int i = 0; i < oldMs.length; i++) {
            // 交替顺序，减少温度、JIT 和 GC 定向影响某一组的机会。
            if ((i & 1) == 0) {
                oldMs[i] = run(false, source, target, reps);
                newMs[i] = run(true, source, target, reps);
            } else {
                newMs[i] = run(true, source, target, reps);
                oldMs[i] = run(false, source, target, reps);
            }
        }
        Arrays.sort(oldMs);
        Arrays.sort(newMs);
        int mid = oldMs.length / 2;
        System.out.printf(Locale.ROOT, "%s baseline=%.3f ms [%.3f..%.3f] current=%.3f ms [%.3f..%.3f]%n",
                label, oldMs[mid], oldMs[0], oldMs[oldMs.length - 1],
                newMs[mid], newMs[0], newMs[newMs.length - 1]);
    }

    private static double run(boolean current, int source, int target, int reps) {
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            // 每条生产单线都新建求解器；重用实例会绕过本次要衡量的预计算。
            sink = current
                    ? new FastMarchingSolver(FinslerMetric.of(GRID, OBSTACLES)).solve(source, source, target, target).at(target, target)
                    : new BaselineFastMarchingSolver(BaselineFinslerMetric.of(GRID, OBSTACLES)).solve(source, source, target, target).at(target, target);
        }
        return (System.nanoTime() - start) / (1_000_000.0 * reps);
    }
}

