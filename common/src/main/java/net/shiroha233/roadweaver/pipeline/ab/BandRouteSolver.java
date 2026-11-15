/* 文件职责：用新三层内核求解单条路线（度量 → 回溯 → 曲率平滑）。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.ArrayList;
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
    private static final BandResult UNREACHABLE_BAND = new BandResult(List.of(), 0, 0.0, 0.0, 0.0);

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
        return solve(from, to, null);
    }

    /** 生产接线要补查粗采样之外的保护列；A/B 夹具仍可直接用度量场。 */
    public RouteSolution solve(RoadNode from, RoadNode to, ElasticBandSolver.BlockedCheck exactBlock) {
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
        List<BandResult.Point> points = trace(from, to, field, bandSolver, exactBlock).points();
        if (points.isEmpty()) return RouteSolution.unreachable();
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

    /**
     * 单路和完整路网共享网格曲线；平滑只处理网格主线；短接线用原始世界坐标查障碍并固定两端。
     * {@code exactBlock} 补查只采样粗格的生产障碍场看不见的保护列。
     */
    public static BandResult trace(RoadNode from, RoadNode to, ArrivalTimeField field,
                                   ElasticBandSolver bandSolver, ElasticBandSolver.BlockedCheck exactBlock) {
        List<BandResult.Point> grid = field.pathTo(to.x(), to.z()).stream().map(p -> new BandResult.Point(p[0], p[1])).toList();
        if (grid.isEmpty()) return UNREACHABLE_BAND;

        FinslerMetric metric = field.metric();
        BandResult.Point start = new BandResult.Point(from.x(), from.z()), end = new BandResult.Point(to.x(), to.z());
        BandResult.Point first = grid.getFirst(), last = grid.getLast();

        // 同一格的两个不同端点优先直连；被挡时仍可尝试两条合法短边经采样点绕开。
        if (grid.size() == 1 && clearConnector(start, end, metric, exactBlock)) {
            return new BandResult(start.equals(end) ? List.of(start) : List.of(start, end),
                    0, 0.0, 0.0, 0.0);
        }
        if (start.equals(end)) return UNREACHABLE_BAND; // 同坐标且被挡，没有可绕行的端点。
        if ((!start.equals(first) && !clearConnector(start, first, metric, exactBlock))
                || (!end.equals(last) && !clearConnector(last, end, metric, exactBlock))) {
            return UNREACHABLE_BAND;
        }

        BandResult band = bandSolver.smooth(grid, (x, z) -> !metric.isPassable(x, z));
        if (metric.hasEdgeBlock() && crossesBlockedEdge(band.points(), metric)) {
            // 平滑可切过窄禁带；用同一次 FMM 已核过禁行边的网格路径，诊断也反映保留的线。
            double rawCurvature = ElasticBandSolver.discreteCurvature(grid);
            return attach(new BandResult(grid, 0, 0.0, rawCurvature, rawCurvature), start, end);
        }
        return attach(band, start, end);
    }

    private static BandResult attach(BandResult band, BandResult.Point start, BandResult.Point end) {
        boolean addStart = !start.equals(band.points().getFirst());
        boolean addEnd = !end.equals(band.points().getLast());
        if (!addStart && !addEnd) return band;
        List<BandResult.Point> points = new ArrayList<>(band.size() + 2);
        if (addStart) points.add(start);
        points.addAll(band.points());
        if (addEnd) points.add(end);
        // 曲率诊断仍属于弹性带；短边与主线的步长口径不同，不能拿两者的能量直接比较。
        return new BandResult(points, band.iterations(), band.residual(),
                band.curvatureBefore(), band.curvatureAfter(), band.projectedPoints());
    }

    private static boolean crossesBlockedEdge(List<BandResult.Point> points, FinslerMetric metric) {
        for (int i = 1; i < points.size(); i++) {
            BandResult.Point a = points.get(i - 1), b = points.get(i);
            if (metric.blocksEdge(a.x(), a.z(), b.x(), b.z())) return true;
        }
        return false;
    }

    private static boolean clearConnector(BandResult.Point a, BandResult.Point b, FinslerMetric metric,
                                          ElasticBandSolver.BlockedCheck exactBlock) {
        if (metric.blocksEdge(a.x(), a.z(), b.x(), b.z())) return false;
        int x = a.x(), z = a.z(), endX = b.x(), endZ = b.z();
        int sx = Integer.compare(endX, x), sz = Integer.compare(endZ, z);
        long dx = Math.abs((long) endX - x), dz = Math.abs((long) endZ - z);
        // 从方块中心走到中心；用半格为单位比较过界时刻，共角时两侧列也必须安全。
        for (long nextX = 1, nextZ = 1; ; ) {
            if (blocked(x, z, metric, exactBlock)) return false;
            if (x == endX && z == endZ) return true;
            int order = x == endX ? 1 : z == endZ ? -1 : Long.compare(nextX * dz, nextZ * dx);
            if (order == 0 && (blocked(x + sx, z, metric, exactBlock)
                    || blocked(x, z + sz, metric, exactBlock))) return false;
            if (order <= 0) {
                x += sx;
                nextX += 2;
            }
            if (order >= 0) {
                z += sz;
                nextZ += 2;
            }
        }
    }

    private static boolean blocked(int x, int z, FinslerMetric metric, ElasticBandSolver.BlockedCheck exactBlock) {
        return !metric.isPassable(x, z) || (exactBlock != null && exactBlock.blocked(x, z));
    }
}
