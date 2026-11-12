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

        ArrivalTimeField field = new FastMarchingSolver(metric).solve(from.x(), from.z());
        if (!field.reachable(to.x(), to.z())) {
            return RouteSolution.unreachable();
        }

        List<BandResult.Point> raw = field.pathTo(to.x(), to.z()).stream()
                .map(p -> new BandResult.Point(p[0], p[1]))
                .toList();
        BandResult smoothed = bandSolver.smooth(raw);

        int n = smoothed.points().size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = smoothed.points().get(i).x();
            zs[i] = smoothed.points().get(i).z();
        }
        RouteMetrics metrics = RouteMetrics.from(xs, zs);
        return new RouteSolution(smoothed.points().stream()
                .map(p -> new int[]{p.x(), p.z()})
                .toList(), metrics, true);
    }
}
