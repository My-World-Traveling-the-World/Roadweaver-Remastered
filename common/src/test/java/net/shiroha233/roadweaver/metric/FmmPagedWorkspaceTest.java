/* 文件职责：对拍分页传播的场、回溯与路网，并以真实分配守住大域近目标。 */
package net.shiroha233.roadweaver.metric;

import net.shiroha233.roadweaver.topology.GeodesicMatrix;
import net.shiroha233.roadweaver.topology.MinimumSpanningTree;
import net.shiroha233.roadweaver.topology.RoadNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FmmPagedWorkspaceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void pagedPropagationMatchesDenseBitsAndPathsIncludingEarlyStopAndSourceReuse(int scenario) {
        var metric = fixture(scenario);
        var terrain = metric.terrain();
        var actual = new FastMarchingSolver(metric, 0);
        var reference = new DenseFmmReference(metric);
        int step = terrain.step(), x = terrain.minX(), z = terrain.minZ();
        int tx = terrain.maxX(), tz = terrain.maxZ();
        var retained = actual.solve(x, z);
        var retainedReference = reference.solve(x, z);
        assertSameField(retainedReference, retained);
        for (int source = 0; source < 3; source++) {
            int sx = x + Math.min(source, terrain.columns() - 1) * step;
            int sz = z + Math.min(source, terrain.rows() - 1) * step;
            assertSameField(reference.solve(sx, sz), actual.solve(sx, sz));
            assertSameField(reference.solve(sx, sz, tx, tz), actual.solve(sx, sz, tx, tz));
            int[] xs = {sx, tx, tx, tx + step / 3, x - 10};
            int[] zs = {sz, tz, tz, tz + step / 3, z - 10};
            assertSameField(reference.solveReachingAll(sx, sz, xs, zs), actual.solveReachingAll(sx, sz, xs, zs));
            // 空目标仍代表完整传播；不能把“没有待等目标”误当成整条道路无需产出。
            assertSameField(reference.solve(sx, sz), actual.solveReachingAll(sx, sz, new int[0], new int[0]));
            assertSameField(retainedReference, retained);
        }
        assertTrue(retained.reachable(x, z));
        assertFalse(retained.pathTo(x, z).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void blockedOutsideAndSnappedSourcesKeepTheirOriginalMeaning(int mode) {
        var terrain = new GridTerrainField(-31, -29, 67, 43, 8, new int[67 * 43]);
        var metric = FinslerMetric.of(terrain, new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == -23; }
        });
        int x = mode == 0 ? -32 : mode == 1 ? -23 : -24;
        assertSameField(new DenseFmmReference(metric).solve(x, -29), new FastMarchingSolver(metric, 0).solve(x, -29));
        assertFalse(new FastMarchingSolver(metric, 0).solve(x, -29).reachable(-31, -29));
    }

    @Test
    void pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex() {
        int size = Integer.MAX_VALUE - 8;
        var workspace = new FmmWorkspace(size, true);
        var oracle = new HashMap<Integer, Double>();
        int[] indexes = {0, 1, 63, 64, 1023, 1024, 1025, 262143, 262144, 262145, size - 2, size - 1};
        for (int i : indexes) {
            assertEquals(Double.POSITIVE_INFINITY, workspace.values.get(i));
            assertFalse(workspace.frozen(i));
            workspace.values.set(i, i + 0.25);
            workspace.freeze(i);
            oracle.put(i, i + 0.25);
        }
        for (int i : indexes) for (int offset = -2; offset <= 2; offset++) {
            long neighbor = (long) i + offset;
            if (neighbor < 0 || neighbor >= size) continue;
            int index = (int) neighbor;
            assertEquals(oracle.getOrDefault(index, Double.POSITIVE_INFINITY), workspace.values.get(index));
            assertEquals(oracle.containsKey(index), workspace.frozen(index));
        }
        // 反向读取与重复写同一页不能把另一目录的值变成零或覆盖掉。
        workspace.values.set(262144, -3.5);
        assertEquals(-3.5, workspace.values.get(262144));
        assertEquals(0.25, workspace.values.get(0));
        assertEquals(1024.25, workspace.values.get(1024));
        assertThrows(IllegalArgumentException.class, () -> new FmmPages<>(0));
    }

    @Test
    void readingMissingPagesDoesNotAllocateOrTurnThemIntoFreeSources() {
        var values = new FmmValues(268435456, true);
        var bean = allocationBean();
        long thread = Thread.currentThread().threadId();
        double sum = 0;
        // 先预热同一读口，避免把 JIT/类初始化算进每格查询。
        for (int i = 0; i < 10000; i++) sum += values.get(i * 1024);
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10000; i++) sum += values.get(i * 2048);
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(Double.POSITIVE_INFINITY, sum);
        assertTrue(allocated < 4096, "只读未访问页不应创建值数组：" + allocated);
    }

    @Test
    void publicThresholdAvoidsEagerCostsArrivalArraysAndDistantTerrainQueries() {
        var terrain = analytic(2048, 2048, (x, z) -> {
            assertTrue(x <= 16 && z <= 16, "生产阈值必须接入按需准备");
            return 64;
        });
        var metric = FinslerMetric.of(terrain, ObstacleField.NONE);
        for (int i = 0; i < 8; i++) new FastMarchingSolver(metric).solve(0, 0, 3, 3);
        var bean = allocationBean();
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var field = new FastMarchingSolver(metric).solve(0, 0, 3, 3);
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertTrue(allocated < 2 * 1024 * 1024, "生产阈值不能分配整场代价或到达数组：" + allocated);
        assertTrue(field.reachable(3, 3));
    }

    @Test
    void sourceTargetsAndDuplicatesStopBeforePreparingNeighborPages() {
        var metric = fixture(5);
        var terrain = metric.terrain();
        int x = terrain.minX(), z = terrain.minZ();
        var solver = new FastMarchingSolver(metric, 0);
        var field = solver.solveReachingAll(x, z, new int[]{x, x}, new int[]{z, z});
        assertEquals(0.0, field.at(x, z));
        assertEquals(Double.POSITIVE_INFINITY, field.at(x + terrain.step(), z));
        assertPath(List.of(new int[]{x, z}), field.pathTo(x, z));
        assertTrue(solver.solve(x, z).reachable(x + terrain.step(), z), "夹具必须真的能继续传播");
    }

    @Test
    void publicSolverCanReachANearTargetInA268MillionCellDomainWithoutScanningOrAllocatingIt() {
        var heightReads = new AtomicInteger();
        var terrain = analytic(16384, 16384, (x, z) -> {
            heightReads.incrementAndGet();
            assertTrue(x <= 16 && z <= 16, "近目标不应触及远端地形：" + x + ":" + z);
            return 64;
        });
        var metric = FinslerMetric.of(terrain, ObstacleField.NONE);
        var bean = allocationBean();
        // 同样是公共构造器，阈值接错或只修测试专用入口都会被实际分配量抓住。
        for (int i = 0; i < 8; i++) new FastMarchingSolver(metric).solve(0, 0, 3, 3);
        heightReads.set(0);
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var result = new FastMarchingSolver(metric).solve(0, 0, 3, 3);
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertTrue(allocated < 2 * 1024 * 1024, "近目标不应创建面积数组：" + allocated);
        assertTrue(heightReads.get() > 0 && heightReads.get() < 400, "必须确实计算局部代价：" + heightReads);
        var small = FinslerMetric.of(analytic(33, 33, (x, z) -> 64), ObstacleField.NONE);
        var expected = new DenseFmmReference(small).solve(0, 0, 3, 3);
        assertEquals(Double.doubleToLongBits(expected.at(3, 3)), Double.doubleToLongBits(result.at(3, 3)));
        assertPath(expected.pathTo(3, 3), result.pathTo(3, 3));
        assertEquals(Double.POSITIVE_INFINITY, result.at(16383, 16383));
        assertEquals(16384, result.width()); assertEquals(16384, result.depth());
    }

    @Test
    void aFarEndCorridorStillCrossesTheWholeGlobalDomain() {
        var terrain = analytic(16384, 16384, (x, z) -> 64);
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return z != 0; }
        };
        var solver = new FastMarchingSolver(FinslerMetric.of(terrain, obstacles));
        var forward = solver.solve(0, 0, 16383, 0);
        var backward = solver.solve(16383, 0, 0, 0);
        assertEquals(16383.0, forward.at(16383, 0));
        assertEquals(16383.0, backward.at(0, 0));
        var path = forward.pathTo(16383, 0);
        assertEquals(16384, path.size());
        for (int i = 0; i < path.size(); i++) assertArrayEquals(new int[]{i, 0}, path.get(i));
        assertEquals(Double.POSITIVE_INFINITY, forward.at(8192, 1));
        assertEquals(16383.0, forward.at(16383, 0), "后一源不能污染前一源的快照");
    }

    @Test
    void unreachableFarTargetsExhaustOnlyTheConnectedComponent() {
        var terrain = analytic(16384, 16384, (x, z) -> {
            assertTrue(x < 20 && z < 20, "封闭分量外不得采样地形");
            return 64;
        });
        var obstacle = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 12 || z == 12; }
        };
        var field = new FastMarchingSolver(FinslerMetric.of(terrain, obstacle)).solve(0, 0, 16383, 16383);
        assertTrue(field.reachable(11, 11));
        assertFalse(field.reachable(12, 11)); assertFalse(field.reachable(16383, 16383));
        assertEquals(List.of(), field.pathTo(16383, 16383));
        assertFalse(field.pathTo(11, 11).isEmpty(), "真正传播过的分量不能被整体丢弃");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void denseVisitsThroughBothStorageModesKeepEveryCellAndPathBitwiseEqual(boolean forcePaged) {
        var metric = FinslerMetric.of(analytic(1025, 1025, (x, z) -> 64 + x / 71 + z / 53), ObstacleField.NONE);
        var actual = (forcePaged ? new FastMarchingSolver(metric, 0) : new FastMarchingSolver(metric))
                .solve(0, 0, 1024, 1024);
        var expected = new DenseFmmReference(metric).solve(0, 0, 1024, 1024);
        assertSameField(expected, actual);
        assertTrue(actual.reachable(1024, 1024));
        assertFalse(actual.pathTo(1024, 1024).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void actualNetworkWeightsComponentsAndChosenEdgesMatchTheDenseOracle(int scenario) {
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return scenario >= 2 ? (x + z) % 5 : 0; }
            public boolean impassable(int x, int z) {
                return x >= 60 || z >= 60 || (scenario % 2 == 1 && x == 30);
            }
        };
        var actualMetric = new FinslerMetric(analytic(16384, 16384, (x, z) -> 64 + x / 7 + z / 9), obstacles, 2, 0.3);
        var oracleMetric = new FinslerMetric(analytic(60, 60, (x, z) -> 64 + x / 7 + z / 9), obstacles, 2, 0.3);
        var nodes = List.of(new RoadNode("a", 3, 3), new RoadNode("b", 20, 20),
                new RoadNode("c", 40, 10), new RoadNode("d", 55, 25));
        MinimumSpanningTree.EdgeFilter allowed = (i, j) -> scenario < 2 || j - i <= 1;
        var matrix = scenario < 2 ? GeodesicMatrix.build(actualMetric, nodes)
                : GeodesicMatrix.buildWithin(actualMetric, nodes, 100, allowed);
        double[][] distances = new double[4][4];
        var reference = new DenseFmmReference(oracleMetric);
        for (int i = 0; i < 4; i++) {
            var field = reference.solve(nodes.get(i).x(), nodes.get(i).z());
            for (int j = 0; j < 4; j++) distances[i][j] = field.at(nodes.get(j).x(), nodes.get(j).z());
        }
        record Edge(int a, int b, double value) {}
        var candidates = new ArrayList<Edge>();
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
            double weight = allowed.allows(i, j) ? Math.min(distances[i][j], distances[j][i]) : Double.POSITIVE_INFINITY;
            assertEquals(Double.doubleToLongBits(weight), Double.doubleToLongBits(matrix.distance(i, j)));
            if (Double.isFinite(weight)) candidates.add(new Edge(i, j, weight));
        }
        assertFalse(candidates.isEmpty(), "必须真的产生候选，防止空图对拍恒绿");
        candidates.sort(java.util.Comparator.comparingDouble(Edge::value));
        int[] groups = {0, 1, 2, 3}; var selected = new ArrayList<String>();
        for (var edge : candidates) {
            int a = groups[edge.a()], b = groups[edge.b()];
            if (a == b) continue;
            selected.add(edge.a() + ":" + edge.b() + ":" + edge.value());
            for (int k = 0; k < 4; k++) if (groups[k] == b) groups[k] = a;
        }
        var plan = MinimumSpanningTree.solve(matrix);
        assertEquals(selected, plan.edges().stream().map(e -> e.from() + ":" + e.to() + ":" + e.distance()).toList());
        assertEquals(Arrays.stream(groups).distinct().count(), plan.componentCount());
        assertEquals(scenario % 2 == 1 ? 2 : 1, plan.componentCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void callbackFailuresRemainFailuresAndRetryDoesNotCacheThemAsSuccess(int mode) {
        var fail = new AtomicBoolean(true);
        var terrain = analytic(67, 43, (x, z) -> 64);
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) {
                if (mode == 1 && fail.getAndSet(false)) throw new IllegalStateException("potential fault");
                return 2;
            }
            public boolean impassable(int x, int z) {
                if (mode == 0 && fail.getAndSet(false)) throw new IllegalStateException("passability fault");
                return false;
            }
        };
        FinslerMetric.EdgeBlock edge = (x0, z0, x1, z1) -> {
            if (mode == 2 && fail.getAndSet(false)) throw new IllegalStateException("edge fault");
            return x0 == 20 && x1 == 21;
        };
        var cache = new FmmCostCache(new FinslerMetric(terrain, obstacles, 1, 4, null, edge),
                new GridLayout(0, 0, 67, 43, 1), true);
        int index = 20 + 20 * 67;
        Runnable query = mode == 0 ? () -> cache.passable(index)
                : mode == 1 ? () -> cache.prepare(index) : () -> cache.blockedEdges(index);
        assertThrows(IllegalStateException.class, query::run);
        query.run();
        assertFalse(fail.get());
        assertTrue(cache.passable(index));
        cache.prepare(index);
        assertEquals(9.0, cache.costX(index)); assertEquals(9.0, cache.costZ(index));
        assertEquals(1, cache.blockedEdges(index));
    }

    @Test
    void cachedCellCostsAndUndirectedEdgesArePreparedOnceAcrossSourceChanges() {
        var gradients = new HashMap<Integer, Integer>();
        TerrainField terrain = new TerrainField() {
            public int height(int x, int z) { return 64; }
            public double[] gradient(int x, int z) { gradients.merge(x + z * 67, 1, Integer::sum); return new double[]{0, 0}; }
            public int minX() { return 0; } public int minZ() { return 0; }
            public int maxX() { return 66; } public int maxZ() { return 42; }
        };
        var edgeCalls = new HashMap<String, Integer>();
        var metric = new FinslerMetric(terrain, ObstacleField.NONE, 1, 1, null, (x0, z0, x1, z1) -> {
            assertTrue(x1 > x0 || z1 > z0, "缓存必须保持原预计算的规范方向");
            edgeCalls.merge(x0 + ":" + z0 + ":" + x1 + ":" + z1, 1, Integer::sum);
            return false;
        });
        var solver = new FastMarchingSolver(metric, 0);
        solver.solve(0, 0, 20, 20);
        solver.solve(20, 20, 0, 0);
        assertTrue(gradients.size() > 20); assertTrue(edgeCalls.size() > 20);
        assertTrue(gradients.values().stream().allMatch(n -> n == 1));
        assertTrue(edgeCalls.values().stream().allMatch(n -> n == 1));
    }

    @Test
    void extremeNegativeOriginsStillUseValidFinalWorldCoordinates() {
        var terrain = new GridTerrainField(Integer.MIN_VALUE, Integer.MIN_VALUE, 3, 3,
                Integer.MAX_VALUE, new int[9]);
        var metric = FinslerMetric.of(terrain, ObstacleField.NONE);
        var actual = new FastMarchingSolver(metric, 0).solve(Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertSameField(new DenseFmmReference(metric).solve(Integer.MIN_VALUE, Integer.MIN_VALUE), actual);
        assertTrue(actual.reachable(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1));
    }

    private static FinslerMetric fixture(int scenario) {
        int step = scenario % 2 == 0 ? 1 : 8;
        int w = scenario == 8 ? 1 : 67, d = scenario == 9 ? 1 : 43;
        int[] heights = new int[w * d];
        for (int i = 0; i < heights.length; i++) heights[i] = 64 + i % w / 5 + i / w / 7;
        var terrain = new GridTerrainField(-101, -69, w, d, step, heights);
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return scenario >= 4 ? Math.floorMod(x + z, 7) : 0; }
            public boolean impassable(int x, int z) {
                int ix = (x + 101) / step, iz = (z + 69) / step;
                return scenario == 3 ? ix == 33 : scenario >= 2 && scenario < 8 && ix == 33 && iz < 36;
            }
        };
        FinslerMetric.EdgeBlock edge = scenario >= 6 ? (x0, z0, x1, z1) -> {
            int ix0 = (x0 + 101) / step, ix1 = (x1 + 101) / step;
            int iz0 = (z0 + 69) / step, iz1 = (z1 + 69) / step;
            // 单行/单列也必须包含真禁边；普通场同时验证规范方向和隔断后的局部不可达。
            if (scenario == 8) return Math.min(iz0, iz1) == 20 && iz0 != iz1;
            if (scenario == 9) return Math.min(ix0, ix1) == 40 && ix0 != ix1;
            return ix0 == 50 && ix1 == 51 && Math.min(iz0, iz1) < 40;
        } : null;
        return new FinslerMetric(terrain, obstacles, 2.25, 0.3,
                scenario >= 5 ? (x, z) -> x < -101 + 20 * step ? 0.7 : 1.4 : null, edge);
    }

    @FunctionalInterface
    private interface Height { int at(int x, int z); }

    private static TerrainField analytic(int width, int depth, Height heights) {
        return new TerrainField() {
            public int height(int x, int z) { return heights.at(x, z); }
            public int minX() { return 0; } public int minZ() { return 0; }
            public int maxX() { return width - 1; } public int maxZ() { return depth - 1; }
        };
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = assertInstanceOf(com.sun.management.ThreadMXBean.class, ManagementFactory.getThreadMXBean());
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static void assertSameField(ArrivalTimeField expected, ArrivalTimeField actual) {
        assertEquals(expected.width(), actual.width()); assertEquals(expected.depth(), actual.depth());
        for (int iz = 0; iz < expected.depth(); iz++) for (int ix = 0; ix < expected.width(); ix++) {
            int x = expected.minX() + ix * expected.step(), z = expected.minZ() + iz * expected.step();
            assertEquals(Double.doubleToLongBits(expected.at(x, z)), Double.doubleToLongBits(actual.at(x, z)), x + ":" + z);
        }
        // 大场逐格比值，小场另外跨页、跨障碍、四边和中心比路径，断言不能空集合空转。
        int[] xs = {0, expected.width() / 2, expected.width() - 1};
        int[] zs = {0, expected.depth() / 2, expected.depth() - 1};
        for (int ix : xs) for (int iz : zs) {
            int x = expected.minX() + ix * expected.step(), z = expected.minZ() + iz * expected.step();
            assertPath(expected.pathTo(x, z), actual.pathTo(x, z));
        }
    }

    private static void assertPath(List<int[]> expected, List<int[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i));
    }
}
