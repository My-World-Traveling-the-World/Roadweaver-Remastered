/* 文件职责：把压缩状态与冻结的整场实现对拍到每格场值、每个回溯点及最终拓扑。 */
package net.shiroha233.roadweaver.metric;

import net.shiroha233.roadweaver.topology.GeodesicMatrix;
import net.shiroha233.roadweaver.topology.MinimumSpanningTree;
import net.shiroha233.roadweaver.topology.RoadNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FmmWorkingSetTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void compressedStateMatchesTheFrozenDenseSolverIncludingTemporaryValuesAndPaths(int scenario) {
        FinslerMetric metric = fixture(scenario);
        TerrainField terrain = metric.terrain();
        var actual = new FastMarchingSolver(metric);
        var expected = new DenseFmmReference(metric);
        int step = terrain.step();
        int x0 = terrain.minX(), z0 = terrain.minZ();
        int targetX = x0 + 19 * step, targetZ = z0 + 15 * step;
        // 多源交替包含早停遗留前沿、负原点和非对齐吸附；不能只比矩阵节点而漏掉临时值漂移。
        for (int i = 0; i < 3; i++) {
            int sx = x0 + (2 + i) * step, sz = z0 + 3 * step;
            assertSameField(expected.solve(sx, sz), actual.solve(sx, sz));
            assertSameField(expected.solve(sx, sz, targetX, targetZ), actual.solve(sx, sz, targetX, targetZ));
            int[] xs = {sx, targetX, targetX, targetX + step / 3, x0 - 10};
            int[] zs = {sz, targetZ, targetZ, targetZ + step / 3, z0 - 10};
            assertSameField(expected.solveReachingAll(sx, sz, xs, zs), actual.solveReachingAll(sx, sz, xs, zs));
        }
        assertTrue(actual.solve(x0 + 2 * step, z0 + 3 * step).reachable(x0 + 3 * step, z0 + 3 * step));
    }

    @Test
    void duplicateSourceAndSingleCellTargetsStopAtTheOriginalFrozenEvent() {
        var metric = fixture(1);
        int x = metric.terrain().minX(), z = metric.terrain().minZ();
        var full = new FastMarchingSolver(metric).solve(x, z);
        var single = new FastMarchingSolver(metric).solve(x, z, x, z);
        var duplicates = new FastMarchingSolver(metric).solveReachingAll(x, z, new int[]{x, x}, new int[]{z, z});
        assertSameField(new DenseFmmReference(metric).solve(x, z, x, z), single);
        assertSameField(single, duplicates);
        assertEquals(0.0, single.at(x, z));
        assertEquals(Double.POSITIVE_INFINITY, single.at(x + 8, z));
        assertTrue(Double.isFinite(full.at(x + 8, z)), "夹具必须能传播，才能证明重复目标没有漏停");
    }

    @Test
    void emptyTargetListsKeepTheExistingFullSolveMeaning() {
        var metric = fixture(2);
        var terrain = metric.terrain();
        int x = terrain.minX(), z = terrain.minZ();
        var solver = new FastMarchingSolver(metric);
        assertSameField(solver.solve(x, z), solver.solveReachingAll(x, z, new int[0], new int[0]));
    }

    @Test
    void denseTargetsCrossMultipleBitWordsAndCannotAliasCellZero() {
        var metric = fixture(0);
        var terrain = metric.terrain();
        int count = terrain.columns() * terrain.rows();
        int[] xs = new int[count], zs = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = terrain.minX() + i % terrain.columns();
            zs[i] = terrain.minZ() + i / terrain.columns();
        }
        var actual = new FastMarchingSolver(metric).solveReachingAll(xs[0], zs[0], xs, zs);
        assertSameField(new DenseFmmReference(metric).solveReachingAll(xs[0], zs[0], xs, zs), actual);
        assertTrue(actual.reachable(xs[count - 1], zs[count - 1]));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void outOfBoundsAndHardBlockedSourcesKeepTheUnreachableContract(int mode) {
        var metric = fixture(mode == 0 ? 2 : 3);
        var terrain = metric.terrain();
        int x = mode == 0 ? terrain.minX() - 1 : terrain.minX() + 11 * terrain.step() - (mode == 2 ? 1 : 0);
        int z = terrain.minZ() + 4 * terrain.step();
        var actual = new FastMarchingSolver(metric).solve(x, z);
        assertSameField(new DenseFmmReference(metric).solve(x, z), actual);
        assertFalse(actual.reachable(terrain.minX(), terrain.minZ()));
    }

    @Test
    void sparseTargetStorageDependsOnEndpointCountEvenAtTheMaximumRepresentableGrid() {
        var targets = new FmmTargets(Integer.MAX_VALUE - 8, 30);
        assertEquals(256, targets.storagePayloadBytes());
        assertEquals(256, FmmTargets.payloadBytesUpperBound(Integer.MAX_VALUE - 8, 30));
        targets.add(0); targets.add(Integer.MAX_VALUE - 9); targets.add(0);
        assertEquals(2, targets.count());
        assertTrue(targets.contains(0));
        assertTrue(targets.contains(Integer.MAX_VALUE - 9));
        assertFalse(targets.contains(1));
        assertFalse(targets.contains(Integer.MAX_VALUE - 8));
        var single = new FmmTargets(Integer.MAX_VALUE - 8, 1);
        single.add(126);
        assertEquals(0, single.storagePayloadBytes());
        assertTrue(single.contains(126));
        assertFalse(single.contains(0));
    }

    @Test
    void hashCollisionsDuplicatesMissingCellsAndWordBoundariesMatchAnIndependentSet() {
        for (int expected : new int[]{0, 1, 2, 30, 600}) {
            int cells = expected == 600 ? 4097 : 100003;
            var targets = new FmmTargets(cells, expected);
            var oracle = new HashSet<Integer>();
            var random = new Random(371126 + expected);
            for (int i = 0; i < expected; i++) {
                int value = i == 0 ? 0 : i == 1 ? cells - 1 : random.nextInt(cells);
                targets.add(value); targets.add(value); oracle.add(value);
            }
            assertEquals(oracle.size(), targets.count());
            assertEquals(FmmTargets.payloadBytesUpperBound(cells, expected), targets.storagePayloadBytes());
            for (int i = -1; i <= cells; i++) assertEquals(oracle.contains(i), targets.contains(i), "cell=" + i);
        }
        var dense = new FmmTargets(130, Integer.MAX_VALUE);
        for (int i : new int[]{0, 63, 64, 65, 127, 129}) dense.add(i);
        assertEquals(24, dense.storagePayloadBytes());
        assertEquals(6, dense.count());
        assertFalse(dense.contains(128));
    }

    @Test
    void stateStorageUsesOneBitPerCellAndRetainedSnapshotsDoNotChangeOnTheNextSource() throws Exception {
        var metric = fixture(6);
        var solver = new FastMarchingSolver(metric);
        int x = metric.terrain().minX(), z = metric.terrain().minZ();
        var first = solver.solve(x, z);
        var expected = new DenseFmmReference(metric).solve(x, z);
        solver.solve(x + 8, z + 8);
        assertSameField(expected, first);
        // 验证实际保留的通行存储，防止只改估算日志而仍然分配字节数组。
        var cacheField = FastMarchingSolver.class.getDeclaredField("cachedCosts");
        cacheField.setAccessible(true);
        var cache = assertInstanceOf(FmmCostCache.class, cacheField.get(solver));
        var field = FmmCostCache.class.getDeclaredField("cachedPassable");
        field.setAccessible(true);
        var bits = assertInstanceOf(GridFlags.class, field.get(cache));
        assertEquals(8L * ((23 * 19 + 63) / 64), bits.storagePayloadBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 63, 64, 65, 129, 65537})
    void fixedGridFlagsMatchIndependentBooleanStatesAcrossWordBoundaries(int size) {
        var flags = new GridFlags(size);
        boolean[] oracle = new boolean[size];
        var random = new Random(377 + size);
        for (int i = 0; i < size; i++) {
            assertFalse(flags.get(i));
            if (i == 0 || i == size - 1 || random.nextBoolean()) { flags.set(i); oracle[i] = true; }
        }
        for (int i = 0; i < size; i++) assertEquals(oracle[i], flags.get(i), "cell=" + i);
        assertEquals(((size + 63L) / 64) * 8, flags.storagePayloadBytes());
        assertThrows(IllegalArgumentException.class, () -> new GridFlags(0));
    }

    @Test
    void hotNearTargetAllocatesArrivalValuesAndPackedFlagsWithoutAFullTargetMask() {
        // 记录当前线程真实分配，避免只有日志估算变小、生产仍分配整场布尔数组。
        var bean = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        assertNotNull(bean, "本回归使用 JDK21 的线程分配计数器，不可静默跳过");
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        int side = 257, cells = side * side;
        var metric = FinslerMetric.of(new GridTerrainField(0, 0, side, side, new int[cells]), ObstacleField.NONE);
        var solver = new FastMarchingSolver(metric);
        for (int i = 0; i < 8; i++) solver.solve(0, 0, 2, 2);
        long thread = Thread.currentThread().threadId(), smallest = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long before = bean.getThreadAllocatedBytes(thread);
            var field = solver.solve(0, 0, 2, 2);
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            assertTrue(field.reachable(2, 2));
            assertEquals(0.0, field.at(0, 0));
            smallest = Math.min(smallest, allocated);
        }
        assertTrue(smallest >= cells * 8L, "到达值快照不能被少算或丢弃");
        assertTrue(smallest < cells * 17L / 2,
                "8.5字节/格预算能容纳到达值和位图，装不下任一整场字节状态或目标掩码：" + smallest);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void unreachableSnapshotsKeepTheirGeometryWithoutAllocatingArrivalArrays(int mode) {
        var bean = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        assertNotNull(bean);
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        var terrain = new GridTerrainField(0, 0, 257, 257, 8, new int[257 * 257]);
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 8; }
        };
        var metric = FinslerMetric.of(terrain, obstacles);
        var solver = new FastMarchingSolver(metric);
        // 先建共享代价缓存；第3档专门验证真实点合法但吸附格被禁的返回路径。
        solver.solve(0, 0, 0, 0);
        java.util.function.Supplier<ArrivalTimeField> create = () -> switch (mode) {
            case 0 -> ArrivalTimeField.unreachable(0, 0, 257, 257, 8, metric);
            case 1 -> solver.solve(-1, 0);
            case 2 -> solver.solve(8, 0);
            default -> solver.solve(7, 0);
        };
        create.get();
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var field = create.get();
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertTrue(allocated < 4096, "返回不可达语义不应再制造约528KiB的面积数组：" + allocated);
        assertEquals(257, field.width()); assertEquals(257, field.depth()); assertEquals(8, field.step());
        assertEquals(0, field.minX()); assertEquals(0, field.minZ()); assertSame(metric, field.metric());
        assertTrue(field.contains(0, 0)); assertTrue(field.contains(2055, 2055));
        assertFalse(field.contains(-1, 0)); assertFalse(field.contains(2056, 2056));
        for (int coordinate : new int[]{-1, 0, 7, 8, 63, 64, 129, 2048, 2055, 2056}) {
            assertEquals(Double.POSITIVE_INFINITY, field.at(coordinate, coordinate));
            assertFalse(field.reachable(coordinate, coordinate));
            assertEquals(List.of(), field.pathTo(coordinate, coordinate));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 6})
    void candidateWeightsAndTheChosenNetworkMatchTheDenseOracle(int scenario) {
        var metric = fixture(scenario);
        var terrain = metric.terrain();
        int x = terrain.minX(), z = terrain.minZ(), step = terrain.step();
        var nodes = List.of(new RoadNode("a", x + step, z + step),
                new RoadNode("b", x + 6 * step, z + 4 * step),
                new RoadNode("c", x + 14 * step, z + 7 * step),
                new RoadNode("d", x + 21 * step, z + 16 * step));
        var matrix = GeodesicMatrix.build(metric, nodes);
        double[][] oracle = new double[4][4];
        var reference = new DenseFmmReference(metric);
        for (int i = 0; i < 4; i++) {
            var arrival = reference.solve(nodes.get(i).x(), nodes.get(i).z());
            for (int j = 0; j < 4; j++) oracle[i][j] = arrival.at(nodes.get(j).x(), nodes.get(j).z());
        }
        record Edge(int from, int to, double weight) {}
        var candidates = new ArrayList<Edge>();
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
            double weight = Math.min(oracle[i][j], oracle[j][i]);
            assertEquals(Double.doubleToLongBits(weight), Double.doubleToLongBits(matrix.distance(i, j)));
            if (Double.isFinite(weight)) candidates.add(new Edge(i, j, weight));
        }
        assertFalse(candidates.isEmpty(), "候选不能为空，否则终态对拍空转");
        candidates.sort(java.util.Comparator.comparingDouble(Edge::weight));
        int[] component = {0, 1, 2, 3};
        List<String> selected = new ArrayList<>();
        for (var candidate : candidates) {
            int a = component[candidate.from()], b = component[candidate.to()];
            if (a == b) continue;
            selected.add(candidate.from() + ":" + candidate.to() + ":" + candidate.weight());
            for (int i = 0; i < 4; i++) if (component[i] == b) component[i] = a;
        }
        var plan = MinimumSpanningTree.solve(matrix);
        assertEquals(selected, plan.edges().stream().map(e -> e.from() + ":" + e.to() + ":" + e.distance()).toList());
        assertEquals((int) Arrays.stream(component).distinct().count(), plan.componentCount());
    }

    private static FinslerMetric fixture(int scenario) {
        int step = scenario % 2 == 0 ? 1 : 8;
        int[] heights = new int[23 * 19];
        for (int i = 0; i < heights.length; i++) {
            int ix = i % 23, iz = i / 23;
            heights[i] = scenario == 0 ? 64 : 64 + ix / 3 + (int) Math.round(4 * Math.sin(iz / 3.0));
        }
        var terrain = new GridTerrainField(-101, -69, 23, 19, step, heights);
        ObstacleField obstacles = new ObstacleField() {
            public double potential(int x, int z) { return scenario >= 4 ? Math.floorMod(x + z, 7) : 0; }
            public boolean impassable(int x, int z) {
                int ix = (x + 101) / step, iz = (z + 69) / step;
                return scenario == 3 ? ix == 11 : scenario >= 4 && ix == 11 && iz < 15;
            }
        };
        FinslerMetric.EdgeBlock edges = scenario >= 5
                ? (x0, z0, x1, z1) -> Math.min(x0, x1) <= -101 + 16 * step
                    && Math.max(x0, x1) > -101 + 16 * step && Math.min(z0, z1) < -69 + 10 * step
                : null;
        return new FinslerMetric(terrain, obstacles, 2.25, 0.3,
                scenario >= 6 ? (x, z) -> x < -101 + 7 * step ? 0.7 : 1.4 : null, edges);
    }

    private static void assertSameField(ArrivalTimeField expected, ArrivalTimeField actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.depth(), actual.depth());
        for (int iz = 0; iz < expected.depth(); iz++) for (int ix = 0; ix < expected.width(); ix++) {
            int x = expected.minX() + ix * expected.step(), z = expected.minZ() + iz * expected.step();
            assertEquals(Double.doubleToLongBits(expected.at(x, z)), Double.doubleToLongBits(actual.at(x, z)), x + ":" + z);
            var a = expected.pathTo(x, z); var b = actual.pathTo(x, z);
            assertEquals(a.size(), b.size(), x + ":" + z);
            for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i), b.get(i));
        }
        assertEquals(expected.reachable(expected.minX(), expected.minZ()), actual.reachable(actual.minX(), actual.minZ()));
    }
}
