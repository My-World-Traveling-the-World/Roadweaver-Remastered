/* 文件职责：把度量、拓扑、几何三层串成一条端到端流水线。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.IntBinaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shiroha233.roadweaver.band.BandResult;
import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.pipeline.ab.BandRouteSolver;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");
    // 影子只读少量边；把格数乘最坏求解次数，避免选项在大区域额外跑满整张图。
    private static final int SHADOW_EDGES = 12;
    private static final int SHADOW_ALTERNATIVES = 4;
    private static final int SHADOW_NODES = 256;
    private static final int SHADOW_CELL_BUDGET = 600_000;

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
        boolean[] connected = new boolean[nodes.size()];

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
                RoadNetworkPlan.RoadEdge road = buildEdge(nodes, edge, field);
                // 粗拓扑只证明采样格连通；短接线被挡时不能把一条无曲线的边计入产物。
                if (road.band().points().isEmpty()) continue;
                edges.add(road);
                connected[edge.from()] = true;
                connected[edge.to()] = true;
            }
        }

        List<Integer> isolated = new ArrayList<>();
        for (int i = 0; i < connected.length; i++) {
            if (!connected[i]) isolated.add(i);
        }
        return new RoadNetworkPlan(nodes, edges, isolated);
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
        return planTopology(nodes, null);
    }

    // 上层知道端点的限制（含 Y）；内核仍只关心测地距离与候选下标。
    public TopologyPlan planTopology(List<RoadNode> nodes, MinimumSpanningTree.EdgeFilter allowed) {
        return planTopologyWithin(nodes, 0, allowed);
    }

    public TopologyPlan planTopologyWithin(List<RoadNode> nodes, int radius, MinimumSpanningTree.EdgeFilter allowed) {
        return planTopology(nodes, radius, allowed, null, null);
    }

    public TopologyPlan planTopologyWithShadow(List<RoadNode> nodes, int radius, MinimumSpanningTree.EdgeFilter allowed,
                                               IntBinaryOperator waterProxy) {
        return planTopology(nodes, radius, allowed,
                (x, z) -> new BuildabilityShadow.Column(metric.terrain().height(x, z),
                        waterProxy.applyAsInt(x, z), BuildabilityShadow.Protection.UNKNOWN),
                (report, nanos) -> LOGGER.info("RoadWeaver buildability shadow nodes={} origin={}:{} step={} "
                                + "water=heightmap-gap protection=unprepared shadowMs={} {} coordinates={}",
                        nodes.size(), nodes.getFirst().x(), nodes.getFirst().z(), metric.terrain().step(),
                        nanos / 1_000_000, report, report.edges().stream().map(e -> {
                            var a = nodes.get(e.choice().from());
                            var b = nodes.get(e.choice().to());
                            return a.x() + ":" + a.z() + "->" + b.x() + ":" + b.z();
                        }).toList()));
    }

    TopologyPlan planTopology(List<RoadNode> nodes, MinimumSpanningTree.EdgeFilter allowed,
                              BuildabilityShadow.Probe probe,
                              BiConsumer<BuildabilityShadow.Report, Long> observer) {
        return planTopology(nodes, 0, allowed, probe, observer);
    }

    private TopologyPlan planTopology(List<RoadNode> nodes, int radius, MinimumSpanningTree.EdgeFilter allowed,
                                      BuildabilityShadow.Probe probe,
                                      BiConsumer<BuildabilityShadow.Report, Long> observer) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("节点列表不能为空");
        }
        // 让被长度上限淘汰的终点也退出传播早停条件；选树仍读相同的有效边权。
        GeodesicMatrix matrix = GeodesicMatrix.buildWithin(metric, nodes, radius, allowed);
        TopologyPlan selected = MinimumSpanningTree.solve(matrix);
        if (observer != null) {
            try {
                long started = System.nanoTime();
                var report = shadow(matrix, selected, probe);
                observer.accept(report, System.nanoTime() - started);
            } catch (RuntimeException failure) {
                // 实验失败不能触发生产规划器回退到直线 MST。
                LOGGER.warn("RoadWeaver buildability shadow failed (nodes={})", nodes.size(), failure);
            }
        }
        return selected;
    }

    private BuildabilityShadow.Report shadow(GeodesicMatrix matrix, TopologyPlan topology,
                                             BuildabilityShadow.Probe probe) {
        long cells = (long) metric.terrain().columns() * metric.terrain().rows();
        int budget = (int) Math.min(SHADOW_EDGES, SHADOW_CELL_BUDGET / cells);
        var selected = topology.edges();
        if (selected.isEmpty()) return BuildabilityShadow.Report.skipped("no-selected-edges", 0);
        if (budget < 2 || matrix.size() > SHADOW_NODES) {
            return BuildabilityShadow.Report.skipped("budget", selected.size());
        }

        int alternateLimit = Math.min(SHADOW_ALTERNATIVES, budget / 2);
        Comparator<BuildabilityShadow.Choice> nearest = Comparator
                .comparingDouble(BuildabilityShadow.Choice::geodesic)
                .thenComparingInt(BuildabilityShadow.Choice::from)
                .thenComparingInt(BuildabilityShadow.Choice::to);
        var candidates = new PriorityQueue<>(alternateLimit, nearest.reversed());
        var selectedKeys = new HashSet<Long>();
        for (var edge : selected) selectedKeys.add(shadowKey(edge.from(), edge.to()));
        matrix.forEachFiniteEdge((a, b, distance) -> {
            if (selectedKeys.contains(shadowKey(a, b))) return;
            // 复用同一有序边表，让候补并列时继续保留较小下标。
            if (candidates.size() == alternateLimit && distance >= candidates.peek().geodesic()) return;
            candidates.add(new BuildabilityShadow.Choice(a, b, distance, false));
            if (candidates.size() > alternateLimit) candidates.remove();
        });
        var choices = new ArrayList<BuildabilityShadow.Choice>(budget);
        int selectedCount = Math.min(selected.size(), budget - candidates.size());
        for (int i = 0; i < selectedCount; i++) {
            // 抽样覆盖已选边的距离分布，别只看最容易建的近邻。
            var edge = selected.get(selectedCount == 1 ? 0 : i * (selected.size() - 1) / (selectedCount - 1));
            choices.add(new BuildabilityShadow.Choice(edge.from(), edge.to(), edge.distance(), true));
        }
        candidates.stream().sorted(nearest).forEach(choices::add);

        var bySource = new TreeMap<Integer, List<BuildabilityShadow.Choice>>();
        for (var choice : choices) bySource.computeIfAbsent(choice.from(), ignored -> new ArrayList<>()).add(choice);
        var evidence = new ArrayList<BuildabilityShadow.Evidence>(choices.size());
        var solver = new FastMarchingSolver(metric);
        var nodes = topology.nodes();
        for (var entry : bySource.entrySet()) {
            var source = nodes.get(entry.getKey());
            var targets = entry.getValue();
            int[] xs = new int[targets.size()], zs = new int[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                var target = nodes.get(targets.get(i).to());
                xs[i] = target.x();
                zs[i] = target.z();
            }
            // 仅回溯粗格，不重复跑后段弹性带、结构探测或实际施工。
            var field = solver.solveReachingAll(source.x(), source.z(), xs, zs);
            for (int i = 0; i < targets.size(); i++) {
                evidence.add(BuildabilityShadow.measure(targets.get(i), field.pathTo(xs[i], zs[i]), probe));
            }
        }
        return BuildabilityShadow.Report.summarize(selected.size(), bySource.size(), evidence);
    }

    private static long shadowKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    private RoadNetworkPlan.RoadEdge buildEdge(List<RoadNode> nodes,
                                               TopologyPlan.Edge edge,
                                               ArrivalTimeField field) {
        RoadNode from = nodes.get(edge.from());
        RoadNode to = nodes.get(edge.to());

        BandResult band = BandRouteSolver.trace(from, to, field, bandSolver, null);

        double straight = Math.hypot((double) to.x() - from.x(), (double) to.z() - from.z());
        return new RoadNetworkPlan.RoadEdge(edge.from(), edge.to(),
                edge.distance(), straight, band.points(), band);
    }

    /** 度量（供上层查配置）。 */
    public FinslerMetric metric() { return metric; }

    /** 弹性带求解器。 */
    public ElasticBandSolver bandSolver() { return bandSolver; }
}
