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
            // 2026-11-14（审计「目标早停」）：本组边只需要「源点到这些终点」的 T 与回溯，
            // 求解到这些终点格全部冻结即停 —— 冻结值即最终值，回溯又只走更小的 T
            // （必然已冻结），所以路径与全场求解逐位相同，只是不铺无关格子。
            int groupStart = index;
            int groupEnd = index;
            while (groupEnd < remaining.size() && remaining.get(groupEnd).from() == source) {
                groupEnd++;
            }
            int[] targetXs = new int[groupEnd - groupStart];
            int[] targetZs = new int[groupEnd - groupStart];
            for (int k = groupStart; k < groupEnd; k++) {
                TopologyPlan.Edge edge = remaining.get(k);
                targetXs[k - groupStart] = nodes.get(edge.to()).x();
                targetZs[k - groupStart] = nodes.get(edge.to()).z();
            }
            ArrivalTimeField field = solver.solveReachingAll(
                    nodes.get(source).x(), nodes.get(source).z(), targetXs, targetZs);

            while (index < remaining.size() && remaining.get(index).from() == source) {
                TopologyPlan.Edge edge = remaining.get(index++);
                edges.add(buildEdge(nodes, edge, field));
            }
        }

        return new RoadNetworkPlan(nodes, edges, topology.isolatedNodes());
    }

    /**
     * 只跑拓扑层：N 次场求解 → 测地距离矩阵 → 最小生成森林。
     *
     * <p><b>2026-11-14（审计 NEW-3）</b>：这是给「只要拓扑、不要曲线」的调用方准备的。
     * {@link #run} 会在此基础上再跑第二轮（对 MST 用到的每个源重新求解 + 回溯 + 弹性带平滑），
     * 但规划的拓扑决策只需要「连谁、多远」—— 下游的单条路线自己会重建局部网格、
     * 自己跑场求解。原来 {@code PipelinePlanner} 调 {@link #run} 却只用端点对，
     * 等于把第二轮的整份几何计算白算一遍（约一半规划时间）。</p>
     *
     * <p>边集与 {@link #run} 的第一轮<b>完全相同</b>（同一个 {@link GeodesicMatrix} 与
     * {@link MinimumSpanningTree#solve}），只是不产曲线。</p>
     */
    public TopologyPlan planTopology(List<RoadNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("节点列表不能为空");
        }
        GeodesicMatrix matrix = GeodesicMatrix.build(metric, nodes);
        return MinimumSpanningTree.solve(matrix);
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

        // 2026-11-14 修复（审计 NEW-4）：弹性带的算子用镜像虚点（2·v[0] − v[1]）
        // 处理边界，所以平滑后首尾会被推离真实端点；而 QualityGate 的端点校验
        // 要求曲线两端严格等于节点坐标（否则接不上）。这里显式把首尾改回节点坐标 ——
        // 与 BandPathfinder 同口径（它在平滑后也 set(0)/set(size-1)）。
        List<BandResult.Point> aligned = alignEndpoints(smoothed.points(), from, to);

        double straight = Math.hypot((double) to.x() - from.x(), (double) to.z() - from.z());
        return new RoadNetworkPlan.RoadEdge(edge.from(), edge.to(),
                edge.distance(), straight, aligned, smoothed);
    }

    /**
     * 把曲线首尾对齐到节点坐标（2026-11-14，审计 NEW-4）。
     *
     * <p>两端都对齐，而不是只对齐起点：回溯的<b>终点</b>理论上就是传入的 to，
     * 但弹性带平滑会用镜像虚点处理边界，首尾都会被推开一点点。两处都改回来
     * 才与 {@code BandPathfinder}（生产路径）同一口径。</p>
     */
    private static List<BandResult.Point> alignEndpoints(List<BandResult.Point> points,
                                                         RoadNode from,
                                                         RoadNode to) {
        if (points.isEmpty()) return points;
        List<BandResult.Point> out = new ArrayList<>(points);
        out.set(0, new BandResult.Point(from.x(), from.z()));
        out.set(out.size() - 1, new BandResult.Point(to.x(), to.z()));
        return out;
    }

    /** 度量（供上层查配置）。 */
    public FinslerMetric metric() { return metric; }

    /** 弹性带求解器。 */
    public ElasticBandSolver bandSolver() { return bandSolver; }
}
