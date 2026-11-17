/* 文件职责：在测地距离矩阵上求最小生成树（Kruskal + 并查集），得到不相交的边集。 */
package net.shiroha233.roadweaver.topology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 最小生成树。
 *
 * <h2>为什么先定拓扑再画几何</h2>
 * <p>旧实现让每条连接<b>各自独立</b>规划，然后靠三套检测器事后修补并行与交叉。
 * 真机实测那三套基本没工作（{@code parallel corridor} 26 次全拒、
 * {@code braidPairs=0}、{@code braidScanMs=0}），并行段与环留在世界里。</p>
 *
 * <p>根因不是检测器写得差，而是<b>顺序错了</b>：边一旦先画好就只能事后修。
 * 树的边<b>天然不相交</b>，所以那些检测器要防的问题在拓扑层根本不存在。</p>
 *
 * <h2>不连通怎么处理</h2>
 * <p>返回的是<b>最小生成森林</b>：每个连通分量各得一棵树。
 * 被完全围死的节点不会凭空多出一条边，但也不会被静默丢掉 ——
 * {@link TopologyPlan#isolatedNodes()} 会把它们列出来。</p>
 *
 * <h2>为什么用 Kruskal 而不是 Prim</h2>
 * <p>距离矩阵是<b>稠密</b>的（N×N 全部有值），两者都是 O(N²)；
 * 但 Kruskal 的并查集写法更容易验证「不加环」这条不变量，
 * 而且天然按距离升序，恰好是我们希望的「短的先建」。</p>
 *
 * @see GeodesicMatrix
 * @see TopologyPlan
 */
public final class MinimumSpanningTree {

    private MinimumSpanningTree() {}

    /**
     * 在距离矩阵上求最小生成森林。
     *
     * @param matrix 测地距离矩阵
     * @return 拓扑方案（边 + 孤立节点）
     */
    public static TopologyPlan solve(GeodesicMatrix matrix) {
        return solve(matrix, (a, b) -> true);
    }

    @FunctionalInterface
    public interface EdgeFilter {
        boolean allows(int from, int to);
    }

    public static TopologyPlan solve(GeodesicMatrix matrix, EdgeFilter allowed) {
        // 稀疏矩阵只枚举准入边，稳定排序继续按原 (from,to) 次序处理同权。
        List<TopologyPlan.Edge> candidates = new ArrayList<>();
        matrix.forEachFiniteEdge((from, to, weight) -> {
            if (allowed.allows(from, to)) candidates.add(new TopologyPlan.Edge(from, to, weight));
        });
        return choose(matrix.nodes(), candidates);
    }

    @FunctionalInterface
    interface EdgeDistance {
        double distance(int from, int to);
    }

    static TopologyPlan solve(List<RoadNode> nodes, EdgeDistance distances, EdgeFilter allowed) {
        int n = nodes.size();
        List<TopologyPlan.Edge> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                // 禁边必须在候选集入口裁掉；先选树后删会错失合法的替代边。
                if (!allowed.allows(i, j)) continue;
                double d = distances.distance(i, j);
                if (Double.isFinite(d)) {
                    candidates.add(new TopologyPlan.Edge(i, j, d));
                }
            }
        }
        return choose(nodes, candidates);
    }

    // 不论稀疏或密集，树边选择由同一排序和并查集负责。
    private static TopologyPlan choose(List<RoadNode> nodes, List<TopologyPlan.Edge> candidates) {
        int n = nodes.size();
        candidates.sort(Comparator.comparingDouble(TopologyPlan.Edge::distance));

        UnionFind uf = new UnionFind(n);
        List<TopologyPlan.Edge> chosen = new ArrayList<>();
        double total = 0.0;

        boolean[] incident = new boolean[n];
        for (TopologyPlan.Edge edge : candidates) {
            if (uf.union(edge.from(), edge.to())) {
                chosen.add(edge);
                total += edge.distance();
                incident[edge.from()] = true;
                incident[edge.to()] = true;
            }
        }

        // 按实际选出的边计算孤点，不能把被长度上限裁掉的邻居当作已连接。
        List<Integer> isolated = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!incident[i]) isolated.add(i);
        }

        return new TopologyPlan(nodes, chosen, total, isolated);
    }

    /** 并查集（路径压缩 + 按秩合并）。 */
    private static final class UnionFind {
        private final int[] parent;
        private final int[] rank;

        UnionFind(int n) {
            parent = new int[n];
            rank = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];   // 路径压缩（隔代）
                x = parent[x];
            }
            return x;
        }

        /** 仅合并不同分量，保证Kruskal接受的边不会制造环。
 * @return 是否真的合并了两个不同分量（＝这条边不构成环） */
        boolean union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) return false;
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb;
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra;
            } else {
                parent[rb] = ra;
                rank[ra]++;
            }
            return true;
        }
    }
}
