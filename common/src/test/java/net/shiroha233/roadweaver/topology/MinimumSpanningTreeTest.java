/* 文件职责：证明拓扑层求的是「真的最小」生成树，且不静默丢节点。 */
package net.shiroha233.roadweaver.topology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.pipeline.RoadPipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拓扑层正确性。
 *
 * <h2>核心测试：与暴力枚举对照</h2>
 * <p>「最小生成树」里的「最小」很容易写成「差不多小」—— 比如并查集路径压缩写错、
 * 边排序用了整数比较、或者把不可达当成 0。小图上可以把<b>所有生成树</b>
 * 枚举一遍取最小值，与本类结果逐位对照。5 个节点只有 210 种 4 边子集，
 * 代价可以忽略，但能把「最小」钉死。</p>
 *
 * @see MinimumSpanningTree
 * @see GeodesicMatrix
 */
class MinimumSpanningTreeTest {

    private static FinslerMetric flatMetric(int size) {
        GridTerrainField terrain = GridTerrainField.fromFunction(0, 0, size, size, (x, z) -> 0);
        return new FinslerMetric(terrain, ObstacleField.NONE, 0.0, 0.0);
    }

    // ── 性质 1：与暴力枚举的最小生成树权重完全相同 ──────────────

    @Test
    void 生成树权重必须等于暴力枚举的最小值() {
        // 5 个节点摆在平地上，位置故意不规则，避免对称退化成特例
        List<RoadNode> nodes = List.of(
                new RoadNode("a", 5, 5),
                new RoadNode("b", 20, 8),
                new RoadNode("c", 12, 18),
                new RoadNode("d", 25, 25),
                new RoadNode("e", 6, 22));

        GeodesicMatrix matrix = GeodesicMatrix.build(flatMetric(40), nodes);
        TopologyPlan plan = MinimumSpanningTree.solve(matrix);
        double bruteForce = bruteForceMinimumSpanningTree(matrix);

        assertEquals(bruteForce, plan.totalDistance(), 1e-6,
                "MST 总权重必须等于枚举所有生成树得到的最小值。"
                        + "暴力=" + bruteForce + " 实际=" + plan.totalDistance());
        assertEquals(4, plan.edgeCount(), "5 个连通节点应有 4 条边");
        assertTrue(plan.fullyConnected(), "应完全连通");
    }

    // ── 性质 2：正方形四角 → 3 条边、总长为 3 倍边长 ────────────

    @Test
    void 正方形四角应得到三条边() {
        List<RoadNode> nodes = List.of(
                new RoadNode("sw", 10, 10),
                new RoadNode("se", 20, 10),
                new RoadNode("ne", 20, 20),
                new RoadNode("nw", 10, 20));

        GeodesicMatrix matrix = GeodesicMatrix.build(flatMetric(40), nodes);
        TopologyPlan plan = MinimumSpanningTree.solve(matrix);

        assertEquals(3, plan.edgeCount(), "4 个节点应有 3 条边");
        // 正方形成员：3 条边各约 10 格（有条边必然是对角线长度，但 MST 会避开它）
        assertEquals(30.0, plan.totalDistance(), 1.0,
                "正方形 MST 应是三条边而非包含对角线（对角线 14.1 > 边长 10）");
        assertTrue(plan.fullyConnected(), "应完全连通");
    }

    // ── 性质 3：孤立节点必须被列出，不能被静默丢掉 ──────────────

    @Test
    void 被围死的节点必须出现在孤立列表里() {
        // 用**硬不可通行**围一圈把 (20,20) 完全封死。
        //
        // 注意：这里不能用「把地形抬高 99 格」来模拟围死 —— 软坡罚只会让路径
        // 很贵，但求解器总能付代价硬穿过去，于是节点并不真的孤立。
        // 「不可达」必须来自硬约束，这也是 ObstacleField.impassable 存在的理由。
        GridTerrainField terrain = GridTerrainField.fromFunction(0, 0, 41, 41, (x, z) -> 0);
        ObstacleField ring = new ObstacleField() {
            @Override public double potential(int x, int z) {
                return impassable(x, z) ? 1.0 : 0.0;
            }

            @Override public boolean impassable(int x, int z) {
                int dx = Math.abs(x - 20);
                int dz = Math.abs(z - 20);
                return dx <= 1 && dz <= 1 && (dx == 1 || dz == 1);   // 3x3 外圈
            }
        };
        FinslerMetric metric = new FinslerMetric(terrain, ring, 0.0, 8.0);

        List<RoadNode> nodes = List.of(
                new RoadNode("inside", 20, 20),
                new RoadNode("outside1", 5, 5),
                new RoadNode("outside2", 35, 35));

        GeodesicMatrix matrix = GeodesicMatrix.build(metric, nodes);
        TopologyPlan plan = MinimumSpanningTree.solve(matrix);

        assertEquals(1, plan.isolatedNodes().size(),
                "被完全围死的节点必须被列出（不是静默丢弃），实际孤立 "
                        + plan.isolatedNodes().size() + " 个");
        assertEquals(0, plan.isolatedNodes().get(0), "孤立的应是下标 0 的 inside 节点");
        assertEquals(1, plan.edgeCount(), "外部两个连通节点之间应有 1 条边");
        assertFalse(plan.fullyConnected(), "有孤立节点就不算完全连通");
    }

    // ── 性质 4：确定性 ─────────────────────────────────────────

    @Test
    void 相同输入必须得到相同结果() {
        List<RoadNode> nodes = List.of(
                new RoadNode("a", 5, 5),
                new RoadNode("b", 30, 7),
                new RoadNode("c", 18, 30),
                new RoadNode("d", 35, 35));

        FinslerMetric metric = flatMetric(45);
        TopologyPlan first = MinimumSpanningTree.solve(GeodesicMatrix.build(metric, nodes));
        TopologyPlan second = MinimumSpanningTree.solve(GeodesicMatrix.build(metric, nodes));

        assertEquals(first.totalDistance(), second.totalDistance(), 1e-12,
                "相同输入必须得到相同总权重（MST 不能有随机性）");
        assertEquals(first.edgeCount(), second.edgeCount());
        for (int i = 0; i < first.edgeCount(); i++) {
            assertEquals(first.edges().get(i).from(), second.edges().get(i).from(),
                    "第 " + i + " 条边起点必须一致");
            assertEquals(first.edges().get(i).to(), second.edges().get(i).to(),
                    "第 " + i + " 条边终点必须一致");
        }
    }

    @Test
    void 超限捷径不可挤掉可连接第三点的合法边() {
        // 测地计价可能优先选择距离上限不允许的边；合成权重独立于准入判定。
        List<RoadNode> nodes = List.of(new RoadNode("a", 0, 0),
                new RoadNode("b", 4, 0), new RoadNode("c", 8, 0));
        double[][] distances = {{0, 4, 5}, {4, 0, 9}, {5, 9, 0}};
        var unbounded = MinimumSpanningTree.solve(nodes, (a, b) -> distances[a][b], (a, b) -> true);
        assertEquals(List.of(new TopologyPlan.Edge(0, 1, 4), new TopologyPlan.Edge(0, 2, 5)), unbounded.edges());

        var bounded = MinimumSpanningTree.solve(nodes, (a, b) -> distances[a][b],
                (a, b) -> Math.abs(nodes.get(a).x() - nodes.get(b).x()) <= 4);
        assertEquals(List.of(new TopologyPlan.Edge(0, 1, 4), new TopologyPlan.Edge(1, 2, 9)), bounded.edges());
        assertTrue(bounded.fullyConnected());
        assertEquals(1, bounded.componentCount());
    }

    @Test
    void sparseForestMatchesAllPairsAcrossNegativeBucketsAndUsesBothDirections() {
        // 跨两轴桶边界 + XZ 很近但高度超限 + 孤立点，不能用同一命中掩码做自证。
        List<RoadNode> nodes = List.of(new RoadNode("a", -9, -9), new RoadNode("b", 0, 0),
                new RoadNode("high", -9, 0), new RoadNode("c", -1, 0), new RoadNode("d", 17, 16),
                new RoadNode("e", 29, 16), new RoadNode("f", 19, 17), new RoadNode("isolated", 44, 44));
        int[] height = {0, 0, 35, 0, 0, 0, 0, 0};
        int radius = 13;
        var metric = FinslerMetric.of(GridTerrainField.fromFunction(-12, -12, 62, 62,
                (x, z) -> Math.floorMod(x * (z + 7) / 13, 7)), ObstacleField.NONE);
        var solver = new FastMarchingSolver(metric);
        MinimumSpanningTree.EdgeFilter allowed = (i, j) -> within(nodes, height, radius, i, j);
        GeodesicMatrix dense = GeodesicMatrix.build(metric, nodes, allowed);
        GeodesicMatrix sparse = GeodesicMatrix.buildWithin(metric, nodes, radius, allowed);
        assertTrue(Double.isFinite(sparse.distance(0, 1)), "跨 X/Z 桶的合法边必须保留");
        assertEquals(Double.POSITIVE_INFINITY, sparse.distance(0, 2), "同一水平范围不代表通过三维限长");
        assertEquals(List.of(2, 7), MinimumSpanningTree.solve(sparse).isolatedNodes());

        boolean hasAsymmetry = false;
        int checked = 0;
        for (int i = 0; i < nodes.size(); i++) {
            assertEquals(0.0, sparse.distance(i, i));
            for (int j = i + 1; j < nodes.size(); j++) {
                assertEquals(dense.distance(i, j), sparse.distance(i, j));
                assertEquals(dense.distance(j, i), sparse.distance(j, i));
                if (!allowed.allows(i, j)) continue;
                RoadNode a = nodes.get(i), b = nodes.get(j);
                double forward = solver.solveReachingAll(a.x(), a.z(), new int[]{b.x()}, new int[]{b.z()}).at(b.x(), b.z());
                double reverse = solver.solveReachingAll(b.x(), b.z(), new int[]{a.x()}, new int[]{a.z()}).at(a.x(), a.z());
                hasAsymmetry |= forward > reverse;
                assertEquals(Math.min(forward, reverse), sparse.distance(i, j));
                checked++;
            }
        }
        assertTrue(hasAsymmetry, "至少一条边只测正向会变重");
        assertEquals(6, checked);
        TopologyPlan expected = MinimumSpanningTree.solve(dense);
        TopologyPlan actual = new RoadPipeline(metric, null).planTopologyWithin(nodes, radius, allowed);
        assertEquals(4, expected.edgeCount());
        assertEquals(4, actual.componentCount());
        assertEquals(expected, actual);
    }

    @Test
    void sparseReachabilityUsesNodeIdsAndKeepsIsolatedDiagonal() {
        var nodes = List.of(new RoadNode("a", 0, 0), new RoadNode("isolated", 20, 20),
                new RoadNode("b", 0, 2));
        var metric = flatMetric(30);
        MinimumSpanningTree.EdgeFilter allowed = (from, to) -> from == 0 && to == 2;
        assertNotNull(TopologyCandidates.nearby(nodes, 2, allowed), "夹具必须使用稀疏行");
        var sparse = GeodesicMatrix.buildWithin(metric, nodes, 2, allowed);
        // 行内槽 0 存的是节点 2，不能把它当作通向节点 0 或节点 1 的权重。
        assertTrue(assertDoesNotThrow(() -> sparse.reachable(0, 2)),
                "目标节点号大于行长度时仍须找到合法边");
        assertTrue(sparse.reachable(2, 0));
        assertFalse(sparse.reachable(0, 1));
        assertFalse(sparse.reachable(1, 0));
        assertTrue(sparse.reachable(1, 1), "与稠密矩阵一样，孤立点的自身距离仍为零");
        assertFalse(sparse.hasAnyConnection(1), "自达不能冒充连接了别的节点");
    }

    @Test
    void sparseCandidateCountMatchesExhaustiveAndDenseInputsKeepExactPath() {
        List<RoadNode> nodes = new ArrayList<>();
        int[] height = new int[900];
        for (int x = -15; x < 15; x++) {
            for (int z = -15; z < 15; z++) {
                int i = nodes.size();
                nodes.add(new RoadNode("p" + i, 48 * x, 48 * z));
                height[i] = Math.floorMod(31 * x + 17 * z, 53) - 26;
            }
        }
        int[] checks = {0};
        int[][] sparse = TopologyCandidates.nearby(nodes, 76, (i, j) -> {
            checks[0]++;
            return within(nodes, height, 76, i, j);
        });
        assertEquals(8798, checks[0], "有限半径候选不能重新扫全图");
        int accepted = 0;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                boolean expected = within(nodes, height, 76, i, j);
                assertEquals(expected, Arrays.binarySearch(sparse[i], j) >= 0);
                assertEquals(expected, Arrays.binarySearch(sparse[j], i) >= 0);
                if (expected) accepted++;
            }
        }
        assertEquals(3119, accepted);

        List<RoadNode> denseNodes = nodes.subList(0, 16);
        assertNull(TopologyCandidates.nearby(denseNodes, 4096, (i, j) -> true),
                "点全在相邻桶时不要另建大邻接表");
        assertNull(TopologyCandidates.nearby(nodes, 0, (i, j) -> true),
                "无限半径保留全量通路");
    }

    private static boolean within(List<RoadNode> nodes, int[] heights, int radius, int i, int j) {
        long dx = (long) nodes.get(i).x() - nodes.get(j).x();
        long dy = (long) heights[i] - heights[j];
        long dz = (long) nodes.get(i).z() - nodes.get(j).z();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    // ── 性质 5：距离矩阵自身 ───────────────────────────────────

    @Test
    void 距离矩阵必须对称且对角为零() {
        List<RoadNode> nodes = List.of(
                new RoadNode("a", 4, 4),
                new RoadNode("b", 16, 6),
                new RoadNode("c", 9, 16));

        GeodesicMatrix matrix = GeodesicMatrix.build(flatMetric(30), nodes);

        for (int i = 0; i < matrix.size(); i++) {
            assertEquals(0.0, matrix.distance(i, i), 1e-12, "对角必须为 0");
            for (int j = 0; j < matrix.size(); j++) {
                assertEquals(matrix.distance(i, j), matrix.distance(j, i), 1e-12,
                        "距离矩阵必须对称（MST 依赖这一点）");
            }
        }
        assertEquals(0, matrix.isolatedCount(), "平地上不该有孤立节点");
    }

    // ── 暴力枚举所有生成树 ────────────────────────────────────

    /** 枚举所有 (n−1) 条边的子集，取「连通且无环」里的最小总权重。 */
    private static double bruteForceMinimumSpanningTree(GeodesicMatrix matrix) {
        int n = matrix.size();
        List<int[]> allEdges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Double.isFinite(matrix.distance(i, j))) allEdges.add(new int[]{i, j});
            }
        }
        int need = n - 1;
        double best = Double.POSITIVE_INFINITY;
        int[] pick = new int[need];
        best = search(matrix, allEdges, pick, 0, 0, best);
        return best;
    }

    private static double search(GeodesicMatrix matrix, List<int[]> edges, int[] pick,
                                 int index, int start, double best) {
        if (index == pick.length) {
            return Math.min(best, evaluate(matrix, edges, pick));
        }
        for (int i = start; i <= edges.size() - (pick.length - index); i++) {
            pick[index] = i;
            best = search(matrix, edges, pick, index + 1, i + 1, best);
        }
        return best;
    }

    /** 若这组边构成生成树，返回总权重；否则返回无穷。 */
    private static double evaluate(GeodesicMatrix matrix, List<int[]> edges, int[] pick) {
        int n = matrix.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int[] find = new int[1];
        double total = 0.0;
        for (int p : pick) {
            int a = edges.get(p)[0];
            int b = edges.get(p)[1];
            int ra = root(parent, a);
            int rb = root(parent, b);
            if (ra == rb) return Double.POSITIVE_INFINITY;   // 成环
            parent[ra] = rb;
            total += matrix.distance(a, b);
        }
        int r = root(parent, 0);
        for (int i = 1; i < n; i++) {
            if (root(parent, i) != r) return Double.POSITIVE_INFINITY;  // 不连通
        }
        return total;
    }

    private static int root(int[] parent, int x) {
        while (parent[x] != x) x = parent[x];
        return x;
    }
}
