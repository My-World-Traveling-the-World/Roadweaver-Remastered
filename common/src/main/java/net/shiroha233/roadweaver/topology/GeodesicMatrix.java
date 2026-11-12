/* 文件职责：对每个节点跑一次场求解，得到完整的测地距离矩阵（拓扑层的输入）。 */
package net.shiroha233.roadweaver.topology;

import java.util.List;

import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;

/**
 * 测地距离矩阵。
 *
 * <h2>这是相对旧实现最大的结构优势</h2>
 * <p>旧实现为每条连接<b>各自独立</b>跑 A*，真机实测 23 条连接花 <b>215 秒</b>，
 * 然后还要靠事后三套检测器（parallel corridor / braid / mergeRoads）去发现并行与交叉。</p>
 *
 * <p>这里对每个节点跑<b>一次</b>场求解（{@code O(n log n)}），就得到该节点到<b>所有</b>
 * 其他节点的距离 —— N 个节点共 N 次求解，得到完整的 N×N 矩阵。
 * 拓扑优化（MST）因此变成免费，而且<b>不需要事后检测并行</b>：
 * 树的边天然不相交。</p>
 *
 * <h2>复杂度</h2>
 * <p>{@code O(N × n log n)}，{@code n} 是网格点数。N=24、n≈30k（粗网格）时
 * 预计百毫秒级。旧实现的 215 秒主要花在重复探索同一张地形上。</p>
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

    private GeodesicMatrix(List<RoadNode> nodes, double[][] distances) {
        this.nodes = List.copyOf(nodes);
        this.distances = distances;
    }

    /**
     * 对每个节点求解并填满矩阵。
     *
     * @param metric 度量（内含地形与障碍）
     * @param nodes  节点列表（至少 1 个）
     */
    public static GeodesicMatrix build(FinslerMetric metric, List<RoadNode> nodes) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("节点列表不能为空");
        }

        int n = nodes.size();
        double[][] d = new double[n][n];
        FastMarchingSolver solver = new FastMarchingSolver(metric);

        for (int i = 0; i < n; i++) {
            RoadNode from = nodes.get(i);
            if (!metric.terrain().contains(from.x(), from.z())) {
                // 节点在可行区域外：整行不可达，但不抛异常 ——
                // 让上层决定是「扩大区域」还是「报告该端点无法规划」。
                java.util.Arrays.fill(d[i], Double.POSITIVE_INFINITY);
                d[i][i] = 0.0;
                continue;
            }
            ArrivalTimeField field = solver.solve(from.x(), from.z());
            for (int j = 0; j < n; j++) {
                d[i][j] = (i == j) ? 0.0 : field.at(nodes.get(j).x(), nodes.get(j).z());
            }
        }

        // 对称化：理论上 d[i][j] == d[j][i]，但一阶格式的离散误差会让它们差一点点。
        // 取较小值 —— 距离矩阵必须是度量，MST 依赖三角不等式。
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double min = Math.min(d[i][j], d[j][i]);
                d[i][j] = min;
                d[j][i] = min;
            }
        }
        return new GeodesicMatrix(nodes, d);
    }

    /** 节点列表（不可变）。 */
    public List<RoadNode> nodes() { return nodes; }

    /** 节点数。 */
    public int size() { return nodes.size(); }

    /** 从 i 到 j 的测地距离；不可达为无穷。 */
    public double distance(int i, int j) { return distances[i][j]; }

    /** 两个节点是否互相可达。 */
    public boolean reachable(int i, int j) {
        return Double.isFinite(distances[i][j]);
    }

    /** 某节点是否与至少一个其他节点连通。 */
    public boolean hasAnyConnection(int i) {
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
