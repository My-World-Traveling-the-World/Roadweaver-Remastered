/* 文件职责：在同一 JVM 中对拍并测量旧、新 FMM，避免把缓存热度当优化收益。 */
package net.shiroha233.roadweaver.metric;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.topology.GeodesicMatrix;
import net.shiroha233.roadweaver.topology.MinimumSpanningTree;
import net.shiroha233.roadweaver.topology.RoadNode;
import net.shiroha233.roadweaver.topology.TopologyPlan;

public final class FmmProbe {
    private static final int SIDE = 289, STEP = 8, MAX = (SIDE - 1) * STEP;
    private static final int[] HEIGHTS = new int[SIDE * SIDE];
    private static volatile double sink;
    private static volatile TopologyPlan forestSink;
    private static final ThreadMXBean THREADS = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    private static final long THREAD = Thread.currentThread().threadId();

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
        if (args.length == 1 && ("working-set".equals(args[0]) || "working-set-paged".equals(args[0]))) {
            compareWorkingSet("working-set-paged".equals(args[0]));
            return;
        }
        if (args.length == 1 && "direction".equals(args[0])) {
            compareDirections();
            return;
        }
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
        compareEarlyFields();
        compare(source, source + 32, 10, "near-target");
        compare(source, target, 1, "far-target");
        compareTopology();
        compareDirections();
    }

    private static void compareWorkingSet(boolean forcePaged) {
        int side = 1025, step = 8, origin = -4096, max = origin + (side - 1) * step;
        int[] heights = new int[side * side];
        for (int i = 0; i < heights.length; i++) heights[i] = 64 + (i % side) / 47 + (i / side) / 61;
        TerrainField grid = new GridTerrainField(origin, origin, side, side, step, heights);
        var currentMetric = FinslerMetric.of(grid, ObstacleField.NONE);
        var baselineMetric = BaselineFinslerMetric.of(grid, ObstacleField.NONE);
        var current = forcePaged ? new FastMarchingSolver(currentMetric, 0) : new FastMarchingSolver(currentMetric);
        System.out.println("WORKSET_STORAGE mode=" + (forcePaged ? "forced-paged" : "public-auto"));
        var baseline = new BaselineFastMarchingSolver(baselineMetric);
        int[] xs = new int[30], zs = new int[30];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = origin + (i * 37 % side) * step;
            zs[i] = origin + (i * 71 % side) * step;
        }
        xs[0] = max; zs[0] = max;
        var a = baseline.solveReachingAll(origin, origin, xs, zs);
        var b = current.solveReachingAll(origin, origin, xs, zs);
        for (int z = origin; z <= max; z += step) for (int x = origin; x <= max; x += step) {
            if (Double.doubleToLongBits(a.at(x, z)) != Double.doubleToLongBits(b.at(x, z))) {
                throw new AssertionError("workset field mismatch: " + x + ":" + z);
            }
        }
        List<int[]> before = a.pathTo(max, max), after = b.pathTo(max, max);
        if (before.isEmpty() || before.size() != after.size()) throw new AssertionError("workset path size");
        for (int i = 0; i < before.size(); i++) if (!Arrays.equals(before.get(i), after.get(i))) {
            throw new AssertionError("workset path point " + i);
        }
        System.out.printf("WORKSET_MATCH cells=%d routePoints=%d heapMax=%d%n", side * side, before.size(), Runtime.getRuntime().maxMemory());
        // 热求解器隔离每源工作区；冷求解器另计预计算。两组都在同一固定堆中交替运行。
        compareWorkingSetRuns("workset-hot-near-single",
                () -> sink = baseline.solve(origin, origin, origin + 16, origin + 16).at(origin + 16, origin + 16),
                () -> sink = current.solve(origin, origin, origin + 16, origin + 16).at(origin + 16, origin + 16));
        compareWorkingSetRuns("workset-hot-far-multi",
                () -> sink = baseline.solveReachingAll(origin, origin, xs, zs).at(max, max),
                () -> sink = current.solveReachingAll(origin, origin, xs, zs).at(max, max));
        compareWorkingSetRuns("workset-cold-far-multi",
                () -> sink = new BaselineFastMarchingSolver(baselineMetric).solveReachingAll(origin, origin, xs, zs).at(max, max),
                () -> sink = (forcePaged ? new FastMarchingSolver(currentMetric, 0) : new FastMarchingSolver(currentMetric))
                        .solveReachingAll(origin, origin, xs, zs).at(max, max));
    }

    private static void compareWorkingSetRuns(String label, Runnable baseline, Runnable current) {
        for (int i = 0; i < 8; i++) { baseline.run(); current.run(); }
        double[] oldMs = new double[11], newMs = new double[11];
        long[] oldBytes = new long[11], newBytes = new long[11];
        for (int i = 0; i < 11; i++) {
            if ((i & 1) == 0) {
                collect(measureWorkingSet(baseline), oldMs, oldBytes, i);
                collect(measureWorkingSet(current), newMs, newBytes, i);
            } else {
                collect(measureWorkingSet(current), newMs, newBytes, i);
                collect(measureWorkingSet(baseline), oldMs, oldBytes, i);
            }
        }
        printTiming(label, oldMs, newMs);
        if (oldBytes[0] >= 0 && newBytes[0] >= 0) {
            Arrays.sort(oldBytes); Arrays.sort(newBytes);
            System.out.printf("%s allocation p50 baseline=%d B current=%d B samples=11%n", label, oldBytes[5], newBytes[5]);
        }
    }

    private static Sample measureWorkingSet(Runnable run) {
        long bytes = threadBytes(), began = System.nanoTime();
        run.run();
        long ended = System.nanoTime(), after = threadBytes();
        return new Sample((ended - began) / 1_000_000.0, bytes < 0 || after < 0 ? -1 : after - bytes);
    }

    private static void compareDirections() {
        int diagonal = 57; // 约 80.6 格，数据按弦长归一再比较旋转误差。
        double gradient = 0.25;
        double component = gradient / Math.sqrt(2.0);
        direction("axis-uphill", gradient, 0, 80, 0, false);
        direction("axis-contour", gradient, 0, 0, 80, false);
        direction("rotated-uphill", component, component, diagonal, diagonal, false);
        direction("rotated-contour", component, component, -diagonal, diagonal, false);
        direction("axis-narrow-pass", gradient, 0, 0, 80, true);
        direction("rotated-narrow-pass", component, component, -diagonal, diagonal, true);
    }

    private static void direction(String name, double gx, double gz, int tx, int tz, boolean narrow) {
        TerrainField plane = new TerrainField() {
            public int minX() { return -96; }
            public int maxX() { return 96; }
            public int minZ() { return -96; }
            public int maxZ() { return 96; }
            public int height(int x, int z) { return 64 + (int) Math.round(gx * x + gz * z); }
            // 用解析梯度固定坡度，排除整数高度图的台阶噪声。
            public double[] gradient(int x, int z) { return new double[]{gx, gz}; }
        };
        double chord = Math.hypot(tx, tz);
        ObstacleField mask = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) {
                return narrow && Math.abs((double) x * tz - (double) z * tx) / chord > 3;
            }
        };
        var metric = FinslerMetric.of(plane, mask);
        var field = new FastMarchingSolver(metric).solve(0, 0, tx, tz);
        var path = field.pathTo(tx, tz);
        if (path.size() < 2 || !Arrays.equals(path.getFirst(), new int[]{0, 0})
                || !Arrays.equals(path.getLast(), new int[]{tx, tz}) || !field.reachable(tx, tz)) {
            throw new AssertionError("missing directional trace " + name);
        }
        double pathCost = 0, maxGrade = 0, length = 0;
        for (int i = 1; i < path.size(); i++) {
            var a = path.get(i - 1);
            var b = path.get(i);
            if (mask.impassable(b[0], b[1])) throw new AssertionError("trace left corridor " + name);
            int dx = b[0] - a[0], dz = b[1] - a[1];
            double arc = Math.hypot(dx, dz);
            length += arc;
            pathCost += metric.cost(b[0], b[1], dx, dz) * arc;
            maxGrade = Math.max(maxGrade, Math.abs(gx * dx + gz * dz) / arc);
        }
        // 场值与完整方向公式各报一次；只能比较离散误差，不把斜坡夹具当真机道路。
        System.out.printf(Locale.ROOT, "direction %s fmmPerBlock=%.4f chordMetric=%.4f traceMetric=%.4f "
                        + "detour=%.4f maxGrade=%.4f stations=%d%n", name, field.at(tx, tz) / chord,
                metric.cost(0, 0, tx, tz), pathCost / chord, length / chord, maxGrade, path.size());
    }

    private static void compareEarlyFields() {
        var old = new BaselineFastMarchingSolver(BaselineFinslerMetric.of(GRID, OBSTACLES));
        var current = new FastMarchingSolver(FinslerMetric.of(GRID, OBSTACLES));
        // 早停保留临时值，改变同权出队顺序会打开或关闭少数格；重复源同时检验堆复用。
        for (int source : new int[]{192, 1536, 128}) {
            int target = source + 128;
            var before = old.solve(source, source, target, target);
            var after = current.solve(source, source, target, target);
            for (int z = 0; z <= MAX; z += STEP) {
                for (int x = 0; x <= MAX; x += STEP) {
                    if (Double.doubleToLongBits(before.at(x, z)) != Double.doubleToLongBits(after.at(x, z))) {
                        throw new AssertionError("early arrival mismatch source=" + source + " x=" + x + " z=" + z);
                    }
                }
            }
        }
        System.out.printf("EARLY MATCH sources=3 cellsEach=%d%n", SIDE * SIDE);
    }

    private static void compareTopology() {
        List<RoadNode> nodes = new ArrayList<>();
        for (int cz : new int[]{192, 1632}) {
            for (int cx : new int[]{192, 1632}) {
                for (int i = 0; i < 6; i++) {
                    nodes.add(new RoadNode("v" + nodes.size(), cx + (i % 3) * 64, cz + (i / 3) * 80));
                }
            }
        }
        // 四簇各六点，300 格是测试用上限；四簇之间的边均不会进入 Kruskal。
        MinimumSpanningTree.EdgeFilter allowed =
                (i, j) -> Math.hypot(nodes.get(i).x() - nodes.get(j).x(), nodes.get(i).z() - nodes.get(j).z()) <= 300;
        FinslerMetric metric = FinslerMetric.of(GRID, OBSTACLES);
        var full = GeodesicMatrix.build(metric, nodes);
        var filtered = GeodesicMatrix.build(metric, nodes, allowed);
        int allowedEdges = 0;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                if (!allowed.allows(i, j)) continue;
                allowedEdges++;
                if (Double.doubleToLongBits(full.distance(i, j)) != Double.doubleToLongBits(filtered.distance(i, j))) {
                    throw new AssertionError("topology distance mismatch " + i + "->" + j);
                }
            }
        }
        var before = MinimumSpanningTree.solve(full, allowed);
        var after = MinimumSpanningTree.solve(filtered);
        if (before.edgeCount() != 20 || before.componentCount() != 4 || !before.equals(after)) {
            throw new AssertionError("topology mismatch");
        }
        System.out.printf("FOREST MATCH nodes=%d allowedEdges=%d edges=%d components=%d%n",
                nodes.size(), allowedEdges, after.edgeCount(), after.componentCount());
        Supplier<TopologyPlan> oldRun = () -> MinimumSpanningTree.solve(GeodesicMatrix.build(metric, nodes), allowed);
        Supplier<TopologyPlan> newRun = () -> MinimumSpanningTree.solve(GeodesicMatrix.build(metric, nodes, allowed));
        for (int i = 0; i < 4; i++) {
            timeTopology(oldRun);
            timeTopology(newRun);
        }
        double[] oldMs = new double[7], newMs = new double[7];
        for (int i = 0; i < oldMs.length; i++) {
            if ((i & 1) == 0) {
                oldMs[i] = timeTopology(oldRun);
                newMs[i] = timeTopology(newRun);
            } else {
                newMs[i] = timeTopology(newRun);
                oldMs[i] = timeTopology(oldRun);
            }
        }
        printTiming("clustered-topology", oldMs, newMs);
    }

    private static double timeTopology(Supplier<TopologyPlan> run) {
        long start = System.nanoTime();
        forestSink = run.get();
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void compare(int source, int target, int reps, String label) {
        for (int i = 0; i < 8; i++) {
            run(false, source, target, reps);
            run(true, source, target, reps);
        }
        double[] oldMs = new double[21], newMs = new double[21];
        long[] oldAlloc = new long[oldMs.length], newAlloc = new long[newMs.length];
        for (int i = 0; i < oldMs.length; i++) {
            // 交替顺序，减少温度、JIT 和 GC 定向影响某一组的机会。
            if ((i & 1) == 0) {
                collect(run(false, source, target, reps), oldMs, oldAlloc, i);
                collect(run(true, source, target, reps), newMs, newAlloc, i);
            } else {
                collect(run(true, source, target, reps), newMs, newAlloc, i);
                collect(run(false, source, target, reps), oldMs, oldAlloc, i);
            }
        }
        printTiming(label, oldMs, newMs);
        if (oldAlloc[0] >= 0 && newAlloc[0] >= 0) {
            Arrays.sort(oldAlloc);
            Arrays.sort(newAlloc);
            System.out.printf("%s allocation p50 baseline=%d B current=%d B%n",
                    label, oldAlloc[oldAlloc.length / 2], newAlloc[newAlloc.length / 2]);
        }
    }

    private static void printTiming(String label, double[] oldMs, double[] newMs) {
        Arrays.sort(oldMs);
        Arrays.sort(newMs);
        int mid = oldMs.length / 2;
        int p95 = (int) Math.ceil(oldMs.length * .95) - 1;
        System.out.printf(Locale.ROOT, "%s baseline p50=%.3f ms p95=%.3f ms current p50=%.3f ms p95=%.3f ms%n",
                label, oldMs[mid], oldMs[p95], newMs[mid], newMs[p95]);
    }

    private record Sample(double ms, long allocated) {}

    private static void collect(Sample sample, double[] times, long[] allocations, int index) {
        times[index] = sample.ms();
        allocations[index] = sample.allocated();
    }

    private static long threadBytes() {
        return THREADS == null ? -1 : THREADS.getThreadAllocatedBytes(THREAD);
    }

    private static Sample run(boolean current, int source, int target, int reps) {
        long before = threadBytes();
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            // 每条生产单线都新建求解器；重用实例会绕过本次要衡量的预计算。
            sink = current
                    ? new FastMarchingSolver(FinslerMetric.of(GRID, OBSTACLES)).solve(source, source, target, target).at(target, target)
                    : new BaselineFastMarchingSolver(BaselineFinslerMetric.of(GRID, OBSTACLES)).solve(source, source, target, target).at(target, target);
        }
        double ms = (System.nanoTime() - start) / (1_000_000.0 * reps);
        long after = threadBytes();
        // 这里量当前线程实际分配，不用 GC 次数猜测对象数；JVM 不支持时不输出分配数据。
        return new Sample(ms, before < 0 || after < 0 ? -1 : (after - before) / reps);
    }
}

