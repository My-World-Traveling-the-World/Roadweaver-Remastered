/* 文件职责：对每个节点跑一次场求解，得到完整的测地距离矩阵（拓扑层的输入）。 */
package net.shiroha233.roadweaver.topology;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;

/**
 * 测地距离矩阵。
 *
 * <p>每个有合法候选边的源运行一次 FMM；一张场可回答多个目标，无需逐边重搜。
 * 可选的端点准入限制只缩减要等待的目标数，场仍可自由绕行。全量场的最坏复杂度为
 * {@code O(N × n log n)}，其中 {@code N} 是端点数，{@code n} 是网格点数。有限半径且稀疏时，
 * 只为准入边保留测地距离。</p>
 *
 * <p>拓扑无环只约束抽象端点图；地形上的几条边仍可共享走廊或交叉，需要后续合并。</p>
 *
 * <h2>不可达</h2>
 * <p>被完全围死的节点到别人的距离是 {@link Double#POSITIVE_INFINITY}。
 * 本类<b>不丢</b>它们 —— 由 {@link MinimumSpanningTree} 决定怎么处理，
 * 因为「丢掉一个端点」和「报错让人看见」是两种完全不同的产品行为。</p>
 *
 * @see MinimumSpanningTree
 */
public final class GeodesicMatrix {

    private final List<RoadNode> nodes;
    private final double[][] distances;
    // 稀疏行按同序下标存权重；密集场仍可直接按节点 ID 访问。
    private final int[][] targets;

    private GeodesicMatrix(List<RoadNode> nodes, double[][] distances, int[][] targets) {
        this.nodes = List.copyOf(nodes);
        this.distances = distances;
        this.targets = targets;
    }

    /**
     * 对每个节点求解并填满矩阵。
     *
     * @param metric 度量（内含地形与障碍）
     * @param nodes  节点列表（至少 1 个）
     */
    public static GeodesicMatrix build(FinslerMetric metric, List<RoadNode> nodes) {
        return build(metric, nodes, null);
    }

    /** 只等可能入选的终点冻结；候选限制不妨碍 FMM 经过其它格子绕行。 */
    public static GeodesicMatrix build(FinslerMetric metric, List<RoadNode> nodes,
                                       MinimumSpanningTree.EdgeFilter allowed) {
        validate(metric, nodes);
        return measure(metric, nodes, null, allowed == null ? null : candidateTargets(nodes.size(), allowed));
    }

    /** 只为有限半径的稀疏图省去全量枚举；精确的三维判据由端点所有方传入。 */
    public static GeodesicMatrix buildWithin(FinslerMetric metric, List<RoadNode> nodes,
                                             int radius, MinimumSpanningTree.EdgeFilter allowed) {
        validate(metric, nodes);
        if (radius <= 0 || allowed == null) return build(metric, nodes, allowed);
        int[][] nearby = TopologyCandidates.nearby(nodes, radius, allowed);
        return nearby == null ? build(metric, nodes, allowed) : measure(metric, nodes, nearby, null);
    }

    private static void validate(FinslerMetric metric, List<RoadNode> nodes) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        if (nodes == null || nodes.isEmpty()) throw new IllegalArgumentException("节点列表不能为空");
    }

    private static GeodesicMatrix measure(FinslerMetric metric, List<RoadNode> nodes,
                                           int[][] candidates, BitSet[] denseCandidates) {
        boolean sparse = candidates != null;
        int n = nodes.size();
        double[][] d = new double[n][];
        FastMarchingSolver solver = new FastMarchingSolver(metric);

        // 2026-11-14（审计「目标早停」）：本矩阵只读**节点位置**上的 T，
        // 不需要把整张场铺完。预先收集全部节点坐标，每个源求解到
        // 「所有节点格都冻结」即停 —— 被冻结的格子 T 就是最终值，
        // 所以矩阵每一项与全场求解**逐位相同**，只是少铺了无关的格子。
        int[] nodeXs = new int[n];
        int[] nodeZs = new int[n];
        for (int j = 0; j < n; j++) {
            nodeXs[j] = nodes.get(j).x();
            nodeZs[j] = nodes.get(j).z();
        }

        for (int i = 0; i < n; i++) {
            RoadNode from = nodes.get(i);
            // 密集受限图只解包当前行，避免同时保留整份整数邻接表和稠密权重。
            int[] selected = sparse ? candidates[i]
                    : denseCandidates == null ? null : denseCandidates[i].stream().toArray();
            int count = selected == null ? n : selected.length;
            double[] row = d[i] = new double[sparse ? count : n];
            Arrays.fill(row, Double.POSITIVE_INFINITY);
            if (!sparse) row[i] = 0.0;
            // 无合法邻居的节点没有任何可用边，不必初始化场或扫整幅地图。
            if (count == 0 || !metric.terrain().contains(from.x(), from.z())) continue;

            int[] xs = nodeXs, zs = nodeZs;
            if (selected != null && count < n - 1) {
                xs = new int[count];
                zs = new int[count];
                for (int k = 0; k < count; k++) {
                    xs[k] = nodeXs[selected[k]];
                    zs[k] = nodeZs[selected[k]];
                }
            }
            // 每行独立求解，保留离散格式里可能不同的正反向权重。
            ArrivalTimeField field = solver.solveReachingAll(from.x(), from.z(), xs, zs);
            for (int k = 0; k < count; k++) {
                int j = selected == null ? k : selected[k];
                if (i != j) row[sparse ? k : j] = field.at(nodeXs[j], nodeZs[j]);
            }
        }

        // 对称化：理论上 d[i][j] == d[j][i]，但一阶格式的离散误差会让它们差一点点。
        // 取同一准入边的两个方向较小值，与原选树排序口径一致。
        for (int i = 0; i < n; i++) {
            if (sparse) {
                for (int k = 0; k < candidates[i].length; k++) {
                    int j = candidates[i][k];
                    if (j <= i) continue;
                    int reverse = Arrays.binarySearch(candidates[j], i);
                    double min = Math.min(d[i][k], d[j][reverse]);
                    d[i][k] = min;
                    d[j][reverse] = min;
                }
            } else {
                for (int j = i + 1; j < n; j++) {
                    double min = Math.min(d[i][j], d[j][i]);
                    d[i][j] = min;
                    d[j][i] = min;
                }
            }
        }
        return new GeodesicMatrix(nodes, d, sparse ? candidates : null);
    }

    private static BitSet[] candidateTargets(int size, MinimumSpanningTree.EdgeFilter allowed) {
        BitSet[] targets = new BitSet[size];
        for (int i = 0; i < size; i++) targets[i] = new BitSet(size);
        // 沿用 Kruskal 的 (较小下标, 较大下标) 契约，只调用一次准入条件。
        for (int from = 0; from < size; from++) {
            for (int to = from + 1; to < size; to++) {
                if (!allowed.allows(from, to)) continue;
                targets[from].set(to);
                targets[to].set(from);
            }
        }
        return targets;
    }

    /** 节点列表（不可变）。 */
    public List<RoadNode> nodes() { return nodes; }

    /** 节点数。 */
    public int size() { return nodes.size(); }

    /** 从 i 到 j 的测地距离；不可达为无穷。 */
    public double distance(int i, int j) {
        if (targets == null) return distances[i][j];
        if (i == j) return 0.0;
        int slot = Arrays.binarySearch(targets[i], j);
        return slot < 0 ? Double.POSITIVE_INFINITY : distances[i][slot];
    }

    @FunctionalInterface
    public interface FiniteEdgeConsumer {
        void accept(int from, int to, double weight);
    }

    /** 仅访问有限权重的无向边；按 (from,to) 保序以维持同权决策。 */
    public void forEachFiniteEdge(FiniteEdgeConsumer consumer) {
        for (int i = 0; i < distances.length; i++) {
            double[] row = distances[i];
            if (targets == null) {
                for (int j = i + 1; j < row.length; j++) {
                    if (Double.isFinite(row[j])) consumer.accept(i, j, row[j]);
                }
            } else {
                for (int k = 0; k < row.length; k++) {
                    int j = targets[i][k];
                    if (j > i && Double.isFinite(row[k])) consumer.accept(i, j, row[k]);
                }
            }
        }
    }

    /** 两个节点是否互相可达。 */
    public boolean reachable(int i, int j) {
        return Double.isFinite(distances[i][j]);
    }

    /** 某节点是否与至少一个其他节点连通。 */
    public boolean hasAnyConnection(int i) {
        if (targets != null) {
            for (double weight : distances[i]) if (Double.isFinite(weight)) return true;
            return false;
        }
        for (int j = 0; j < nodes.size(); j++) {
            if (i != j && reachable(i, j)) return true;
        }
        return false;
    }

    /** 从某节点出发的孤立情况：它与谁都不通。 */
    public int isolatedCount() {
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (!hasAnyConnection(i)) count++;
        }
        return count;
    }
}
