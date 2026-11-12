/* 文件职责：证明拓扑层求的是「真的最小」生成树，且不静默丢节点。 */
package net.shiroha233.roadweaver.topology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.ObstacleField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
