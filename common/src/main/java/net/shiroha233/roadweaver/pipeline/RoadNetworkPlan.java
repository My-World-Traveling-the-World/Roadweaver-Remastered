/* 文件职责：整条流水线的输出 —— 节点、相连的边、每一条边的具体曲线。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.List;

import net.shiroha233.roadweaver.band.BandResult;
import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * 路网规划结果。
 *
 * @param nodes        全部节点
 * @param edges        相连的边（含曲线）
 * @param isolatedNodes 与谁都不通的节点下标
 */
public record RoadNetworkPlan(List<RoadNode> nodes,
                              List<RoadEdge> edges,
                              List<Integer> isolatedNodes) {

    public RoadNetworkPlan {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        isolatedNodes = List.copyOf(isolatedNodes);
    }

    /**
     * 一条边的全部信息：拓扑 + 几何。
     *
     * @param from             起点节点下标
     * @param to               终点节点下标
     * @param geodesicDistance 场求解给出的测地距离
     * @param straightDistance 两端直线距离（算绕行比用）
     * @param curve            平滑后的曲线点列
     * @param band             弹性带的诊断（曲率前后对比在这里）
     */
    public record RoadEdge(int from, int to,
                           double geodesicDistance,
                           double straightDistance,
                           List<BandResult.Point> curve,
                           BandResult band) {

        public RoadEdge {
            curve = List.copyOf(curve);
            if (from == to) throw new IllegalArgumentException("边的两端不能相同: " + from);
        }

        /** 绕行比 = 测地距离 / 直线距离；直线距离为 0 时返回 1。 */
        public double detourRatio() {
            if (straightDistance <= 1e-9) return 1.0;
            return geodesicDistance / straightDistance;
        }
    }

    /** 边数。 */
    public int edgeCount() { return edges.size(); }

    /** 是否完全连通。 */
    public boolean fullyConnected() {
        return isolatedNodes.isEmpty() && edges.size() == nodes.size() - 1;
    }

    /** 全部边的测地距离之和。 */
    public double totalGeodesicDistance() {
        return edges.stream().mapToDouble(RoadEdge::geodesicDistance).sum();
    }

    /** 全部边的直线距离之和。 */
    public double totalStraightDistance() {
        return edges.stream().mapToDouble(RoadEdge::straightDistance).sum();
    }

    /** 加权绕行比 = Σ测地 / Σ直线（按长度加权，不是简单平均）。 */
    public double weightedDetourRatio() {
        double straight = totalStraightDistance();
        if (straight <= 1e-9) return 1.0;
        return totalGeodesicDistance() / straight;
    }

    /** 便于日志的一行摘要。 */
    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "nodes=%d edges=%d isolated=%d weightedDetour=%.3f",
                nodes.size(), edges.size(), isolatedNodes.size(), weightedDetourRatio());
    }
}
