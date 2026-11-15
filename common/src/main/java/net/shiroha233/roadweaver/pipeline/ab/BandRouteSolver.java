/* 文件职责：用新三层内核求解单条路线（度量 → 回溯 → 曲率平滑）。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.List;

import net.shiroha233.roadweaver.band.BandResult;
import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * 新内核的单路线求解器。
 *
 * <p>做的事和 {@code RoadPipeline} 里每条边做的完全一样，只是不收拓扑约束 ——
 * AB 对比要的是「同一条路线两边各画成什么样」，所以跳过 MST。</p>
 *
 * @see RouteSolver
 * @see net.shiroha233.roadweaver.pipeline.RoadPipeline
 */
public final class BandRouteSolver implements RouteSolver {

    private final FinslerMetric metric;
    private final ElasticBandSolver bandSolver;

    public BandRouteSolver(FinslerMetric metric, ElasticBandSolver bandSolver) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        this.metric = metric;
        this.bandSolver = bandSolver == null ? ElasticBandSolver.defaults() : bandSolver;
    }

    @Override
    public SolverMode mode() {
        return SolverMode.BAND;
    }

    @Override
    public RouteSolution solve(RoadNode from, RoadNode to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("端点不能为 null");
        }
        if (!metric.terrain().contains(from.x(), from.z())
                || !metric.terrain().contains(to.x(), to.z())) {
            return RouteSolution.unreachable();
        }

        // 2026-11-14（审计「目标早停」）：单条路线只需源点→终点的 T 与回溯，
        // 求解到终点格冻结即停。冻结值即最终值，回溯只走更小的 T（必然已冻结），
        // 所以路径与全场求解逐位相同，只是不铺无关格子。
        ArrivalTimeField field = new FastMarchingSolver(metric)
                .solve(from.x(), from.z(), to.x(), to.z());
        if (!field.reachable(to.x(), to.z())) {
            return RouteSolution.unreachable();
        }

        List<BandResult.Point> raw = field.pathTo(to.x(), to.z()).stream()
                .map(p -> new BandResult.Point(p[0], p[1]))
                .toList();
        // 2026-11-14 修复（审计 B）：曲率平滑之后必须做硬障碍投影 ——
        // 度量层只让「尽量少走」，真正「绝不穿过」由 band 层负责（契约见
        // FinslerMetric / ObstaclePotential 的注释）。这里把不可通行判据喂进去。
        BandResult smoothed = bandSolver.smooth(raw, (x, z) -> !metric.isPassable(x, z));

        // 平滑投影是点判据，可能把两个自由点连过窄禁区。仅舍弃这次平滑，保留同一 FMM 的合法线。
        List<BandResult.Point> points = smoothed.points();
        if (metric.hasEdgeBlock() && crossesBlockedEdge(points)) points = raw;
        int n = points.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i).x();
            zs[i] = points.get(i).z();
        }
        RouteMetrics metrics = RouteMetrics.from(xs, zs);
        return new RouteSolution(points.stream()
                .map(p -> new int[]{p.x(), p.z()})
                .toList(), metrics, true);
    }

    private boolean crossesBlockedEdge(List<BandResult.Point> points) {
        for (int i = 1; i < points.size(); i++) {
            BandResult.Point a = points.get(i - 1), b = points.get(i);
            if (metric.blocksEdge(a.x(), a.z(), b.x(), b.z())) return true;
        }
        return false;
    }
}
