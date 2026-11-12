/* 文件职责：把度量、拓扑、几何三层串成一条端到端流水线。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.band.BandResult;
import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.topology.GeodesicMatrix;
import net.shiroha233.roadweaver.topology.MinimumSpanningTree;
import net.shiroha233.roadweaver.topology.RoadNode;
import net.shiroha233.roadweaver.topology.TopologyPlan;

/**
 * 端到端道路流水线。
 *
 * <h2>它替换掉的旧链</h2>
 * <pre>
 * 旧：A*(8邻域) → DP 简化 → Catmull-Rom → 1-2-1 平滑 → relax → 锯齿检测
 *     → 急弯冻结 → 窗口 splice → 纵断面修复 → merge → braid → parallel corridor
 *     → fine regenerate
 *
 * 新：metric（度量+场求解） → topology（MST） → band（曲率平滑）
 * </pre>
 *
 * <h2>为什么需要两轮求解</h2>
 * <p>拓扑决策需要<b>完整</b>的距离矩阵（N 次求解），但那时还不知道哪几条边会进 MST；
 * 而几何又需要每一条边的路径。若在第一轮就为每个源保留整张时间场，
 * 24 个节点 × 49 万格 × 8 字节 ≈ 94 MB —— 不能常驻。</p>
 *
 * <p>所以分两轮：第一轮只留距离（场丢弃）→ 求 MST → 第二轮只对<b>MST 用到的源</b>
 * 重新求解并回溯路径。代价是不超过 2N 次求解，收益是内存 O(1)。
 * 粗网格上单次求解是毫秒级，2N 次仍然远快于旧实现的 215 秒。</p>
 *
 * <h2>为什么不并行</h2>
 * <p>场求解之间彼此独立、确实可并行，但旧实现的 215 秒主要花在重复探索同一张地形上，
 * 不是缺并行。先把算法换成 O(N) 次求解，再考虑并行 —— 顺序反了会拿并行掩盖复杂度。</p>
 *
 * @see RoadNetworkPlan
 * @see QualityGate
 */
public final class RoadPipeline {

    private final FinslerMetric metric;
    private final ElasticBandSolver bandSolver;

    public RoadPipeline(FinslerMetric metric, ElasticBandSolver bandSolver) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        this.metric = metric;
        this.bandSolver = bandSolver == null ? ElasticBandSolver.defaults() : bandSolver;
    }

    /**
     * 跑完整条流水线。
     *
     * @param nodes 结构节点（至少 1 个）
     * @return 路网规划结果
     */
    public RoadNetworkPlan run(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("节点列表不能为空");
        }

        // ① 度量 + 拓扑：N 次求解得到距离矩阵，再求最小生成树
        GeodesicMatrix matrix = GeodesicMatrix.build(metric, nodes);
        TopologyPlan topology = MinimumSpanningTree.solve(matrix);

        // ② 几何：按「源节点」分组，每个源只求解一次
        FastMarchingSolver solver = new FastMarchingSolver(metric);
        List<RoadNetworkPlan.RoadEdge> edges = new ArrayList<>(topology.edgeCount());

        List<TopologyPlan.Edge> remaining = new ArrayList<>(topology.edges());
        remaining.sort((a, b) -> Integer.compare(a.from(), b.from()));

        int index = 0;
        while (index < remaining.size()) {
            int source = remaining.get(index).from();
            ArrivalTimeField field = solver.solve(
                    nodes.get(source).x(), nodes.get(source).z());

            while (index < remaining.size() && remaining.get(index).from() == source) {
                TopologyPlan.Edge edge = remaining.get(index++);
                edges.add(buildEdge(nodes, edge, field));
            }
        }

        return new RoadNetworkPlan(nodes, edges, topology.isolatedNodes());
    }

    private RoadNetworkPlan.RoadEdge buildEdge(List<RoadNode> nodes,
                                               TopologyPlan.Edge edge,
                                               ArrivalTimeField field) {
        RoadNode from = nodes.get(edge.from());
        RoadNode to = nodes.get(edge.to());

        // 场回溯得到准测地线（方向连续，无 45° 量化）
        List<BandResult.Point> raw = field.pathTo(to.x(), to.z()).stream()
                .map(p -> new BandResult.Point(p[0], p[1]))
                .toList();

        // 弹性带只做曲率平滑：端点锁死、曲率最小、保形
        BandResult smoothed = bandSolver.smooth(raw);

        double straight = Math.hypot((double) to.x() - from.x(), (double) to.z() - from.z());
        return new RoadNetworkPlan.RoadEdge(edge.from(), edge.to(),
                edge.distance(), straight, smoothed.points(), smoothed);
    }

    /** 度量（供上层查配置）。 */
    public FinslerMetric metric() { return metric; }

    /** 弹性带求解器。 */
    public ElasticBandSolver bandSolver() { return bandSolver; }
}
