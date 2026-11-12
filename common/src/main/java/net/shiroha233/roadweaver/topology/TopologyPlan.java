/* 文件职责：拓扑层的输出 —— 该连哪些节点、每段多远、哪些节点孤立。 */
package net.shiroha233.roadweaver.topology;

import java.util.List;

/**
 * 拓扑方案。
 *
 * <p>这是拓扑层与几何层之间<b>唯一的契约</b>：几何层（{@code band}）只拿到
 * 「从哪个节点到哪个节点、大致多远」，完全不知道 MST 是怎么算的；
 * 反过来拓扑层也不知道曲线长什么样。这层隔离让两边的算法可以独立替换。</p>
 *
 * @param nodes          全部节点（与 {@link GeodesicMatrix} 顺序一致）
 * @param edges          最小生成森林的边
 * @param totalDistance  边距离之和
 * @param isolatedNodes  与谁都不通的节点下标
 */
public record TopologyPlan(List<RoadNode> nodes,
                           List<Edge> edges,
                           double totalDistance,
                           List<Integer> isolatedNodes) {

    public TopologyPlan {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        isolatedNodes = List.copyOf(isolatedNodes);
    }

    /**
     * 一条边。
     *
     * @param from     起点下标
     * @param to       终点下标
     * @param distance 测地距离
     */
    public record Edge(int from, int to, double distance) {
        public Edge {
            if (from == to) throw new IllegalArgumentException("边的两端不能相同: " + from);
            if (from < 0 || to < 0) throw new IllegalArgumentException("下标不能为负");
        }

        /** 起点节点。 */
        public RoadNode fromNode(List<RoadNode> nodes) { return nodes.get(from); }

        /** 终点节点。 */
        public RoadNode toNode(List<RoadNode> nodes) { return nodes.get(to); }
    }

    /** 边数（＝节点数 − 连通分量数，无环保证）。 */
    public int edgeCount() { return edges.size(); }

    /** 是否完全连通（无孤立节点且只有一棵树）。 */
    public boolean fullyConnected() {
        return isolatedNodes.isEmpty() && edges.size() == nodes.size() - 1;
    }

    /** 便于日志的一行摘要。 */
    public String describe() {
        return "nodes=" + nodes.size()
                + " edges=" + edges.size()
                + " isolated=" + isolatedNodes.size()
                + " totalDistance=" + String.format(java.util.Locale.ROOT, "%.1f", totalDistance);
    }
}
