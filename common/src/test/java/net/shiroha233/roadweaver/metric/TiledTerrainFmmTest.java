/* 文件职责：用冻结整场求解器验证瓦片高度不会改变场值、回溯和最终选边。 */
package net.shiroha233.roadweaver.metric;

import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.bridge.TiledTerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.topology.GeodesicMatrix;
import net.shiroha233.roadweaver.topology.MinimumSpanningTree;
import net.shiroha233.roadweaver.topology.RoadNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TiledTerrainFmmTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void tiledSamplingPreservesDenseFieldsPathsWeightsAndSelectedForest(int scenario) {
        int step = scenario < 3 ? 1 : 8;
        var layout = new GridLayout(-333, -227, 67, 71, step);
        var calls = new AtomicInteger();
        TiledTerrainField.GridSampler sample = request -> {
            calls.incrementAndGet();
            return sampled(request, false);
        };
        var tiled = new TiledTerrainField(layout, 16, 8, sample);
        var full = sampled(new AccurateHeightGridRequest(layout.minX(), layout.minZ(),
                layout.columns(), layout.rows(), step), false);
        var dense = new GridTerrainField(layout.minX(), layout.minZ(), layout.columns(), layout.rows(), step, full.motionBlocking());
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return scenario % 3 == 2 && x > layout.minX() + 15 * step ? 3 : 0; }
            public boolean impassable(int x, int z) {
                return scenario % 3 != 0 && x == layout.minX() + 32 * step
                        && (scenario % 3 == 2 || z != layout.minZ() + 61 * step);
            }
        };
        var metric = new FinslerMetric(tiled, obstacles, 0.7, 2.0);
        var reference = new DenseFmmReference(new FinslerMetric(dense, obstacles, 0.7, 2.0));
        var solver = new FastMarchingSolver(metric, 0);
        for (int source : new int[]{3, 52, 7}) {
            int sx = layout.minX() + source * step, sz = layout.minZ() + 5 * step;
            int tx = layout.minX() + 55 * step, tz = layout.minZ() + 64 * step;
            sameField(layout, reference.solve(sx, sz), solver.solve(sx, sz));
            sameField(layout, reference.solve(sx, sz, tx, tz), solver.solve(sx, sz, tx, tz));
            sameField(layout, reference.solveReachingAll(sx, sz, new int[]{tx, sx, tx}, new int[]{tz, sz, tz}),
                    solver.solveReachingAll(sx, sz, new int[]{tx, sx, tx}, new int[]{tz, sz, tz}));
        }
        var nodes = List.of(new RoadNode("a", layout.minX() + 3 * step, layout.minZ() + 5 * step),
                new RoadNode("b", layout.minX() + 15 * step, layout.minZ() + 55 * step),
                new RoadNode("c", layout.minX() + 55 * step, layout.minZ() + 64 * step),
                new RoadNode("d", layout.minX() + 52 * step, layout.minZ() + 5 * step));
        var matrix = GeodesicMatrix.build(metric, nodes);
        double[][] expected = new double[4][4];
        for (int i = 0; i < 4; i++) {
            var field = reference.solve(nodes.get(i).x(), nodes.get(i).z());
            for (int j = 0; j < 4; j++) expected[i][j] = field.at(nodes.get(j).x(), nodes.get(j).z());
        }
        record Edge(int a, int b, double weight) {}
        var candidates = new ArrayList<Edge>();
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
            double weight = Math.min(expected[i][j], expected[j][i]);
            assertEquals(Double.doubleToLongBits(weight), Double.doubleToLongBits(matrix.distance(i, j)));
            if (Double.isFinite(weight)) candidates.add(new Edge(i, j, weight));
        }
        assertFalse(candidates.isEmpty());
        candidates.sort(java.util.Comparator.comparingDouble(Edge::weight));
        int[] groups = {0, 1, 2, 3}; var edges = new ArrayList<String>();
        for (var edge : candidates) {
            int a = groups[edge.a()], b = groups[edge.b()];
            if (a == b) continue;
            edges.add(edge.a() + ":" + edge.b() + ":" + edge.weight());
            for (int i = 0; i < groups.length; i++) if (groups[i] == b) groups[i] = a;
        }
        var plan = MinimumSpanningTree.solve(matrix);
        assertEquals(edges, plan.edges().stream().map(e -> e.from() + ":" + e.to() + ":" + e.distance()).toList());
        assertEquals(scenario % 3 == 2 ? 2 : 1, plan.componentCount());
        assertTrue(calls.get() > 1, "对拍必须真正跨过瓦片接缝");
    }

    @Test
    void hugeProductionFactoryAndSolverReadOnlyNearbyTilesWithSmallActualAllocation() {
        // 2.68 亿格使用真实工厂与公共求解入口；假采样器只替代世界读，不替代瓦片缓存。
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 8; i++) nearby(new AtomicInteger());
        var calls = new AtomicInteger();
        long before = bean.getCurrentThreadAllocatedBytes();
        var field = nearby(calls);
        long allocated = bean.getCurrentThreadAllocatedBytes() - before;
        assertTrue(calls.get() > 0 && calls.get() < 5, "近目标不应先请求远端高度");
        assertTrue(allocated < 2 * 1024 * 1024, "纯适配器近目标实际分配=" + allocated);
        assertFalse(field.pathTo(-65512, -65512).isEmpty());
    }

    @Test
    void evictionStillAllowsFullDomainTravelAndOldSnapshotSurvivesReverseSource() {
        var layout = new GridLayout(-65536, -65536, 16384, 16384, 8);
        var requests = new AtomicInteger();
        var surface = SampledTerrainField.planningSurface(layout, request -> {
            requests.incrementAndGet();
            assertTrue(request.sampleCount() <= 4096);
            return sampled(request, true);
        });
        var metric = FinslerMetric.of(surface.terrain(), new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return z != layout.minZ(); }
        });
        var solver = new FastMarchingSolver(metric);
        var forward = solver.solve(layout.minX(), layout.minZ(), layout.maxX(), layout.minZ());
        assertEquals(131064.0, forward.at(layout.maxX(), layout.minZ()));
        assertEquals(16384, forward.pathTo(layout.maxX(), layout.minZ()).size());
        assertEquals(256, requests.get(), "横穿全域必须读完 256 块，不能只走近端核心");
        var reverse = solver.solve(layout.maxX(), layout.minZ(), layout.minX(), layout.minZ());
        assertEquals(131064.0, reverse.at(layout.minX(), layout.minZ()));
        // 首轮源格无需准备自身代价；反向把它作为目标时才补算，恰好重读第一块。
        assertEquals(257, requests.get(), "已准备的代价复用，只允许补读原源格所需的一块");
        assertEquals(131064.0, forward.at(layout.maxX(), layout.minZ()));
        assertEquals(7, surface.waterProxy(layout.minX() + 64 * 8, layout.minZ()));
        assertEquals(258, requests.get(), "被驱逐的影子水代理仍能正确重读");
    }

    private static ArrivalTimeField nearby(AtomicInteger calls) {
        var layout = new GridLayout(-65536, -65536, 16384, 16384, 8);
        var surface = SampledTerrainField.planningSurface(layout, request -> {
            assertEquals(new AccurateHeightGridRequest(-65536, -65536, 64, 64, 8), request);
            calls.incrementAndGet();
            return sampled(request, true);
        });
        return new FastMarchingSolver(FinslerMetric.of(surface.terrain(), ObstacleField.NONE))
                .solve(-65536, -65536, -65512, -65512);
    }

    private static AccurateHeightGrid sampled(AccurateHeightGridRequest request, boolean flat) {
        int[] motion = new int[request.sampleCount()], surface = new int[motion.length], floor = new int[motion.length];
        for (int i = 0; i < motion.length; i++) {
            long x = request.blockX(i), z = request.blockZ(i);
            motion[i] = flat ? 64 : 64 + (int) Math.floorMod(3 * x + 7 * z + x * z, 23);
            surface[i] = motion[i] + 4; floor[i] = motion[i] - 3;
        }
        return new AccurateHeightGrid(request, surface, floor, motion);
    }

    private static void sameField(GridLayout layout, ArrivalTimeField expected, ArrivalTimeField actual) {
        for (int iz = 0; iz < layout.rows(); iz++) for (int ix = 0; ix < layout.columns(); ix++) {
            int x = layout.minX() + ix * layout.step(), z = layout.minZ() + iz * layout.step();
            assertEquals(Double.doubleToLongBits(expected.at(x, z)), Double.doubleToLongBits(actual.at(x, z)),
                    "场值不一致: " + x + "," + z);
        }
        for (int ix : new int[]{3, 15, 55}) {
            int x = layout.minX() + ix * layout.step(), z = layout.minZ() + 5 * layout.step();
            var path = actual.pathTo(x, z); var reference = expected.pathTo(x, z);
            assertEquals(reference.size(), path.size());
            if (expected.reachable(x, z)) assertFalse(path.isEmpty());
            for (int i = 0; i < path.size(); i++) assertArrayEquals(reference.get(i), path.get(i));
        }
    }
}
