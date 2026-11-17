/* 文件职责：同JVM对照生产FMM与离线方向候选，输出路径代理量和求解耗时。 */
package net.shiroha233.roadweaver.metric;

import java.util.*;

public final class DirectionalCostProbe {
    private static volatile double sink;
    public static void main(String[] args) {
        for (int degrees : new int[]{0, 45}) {
            var metric = FinslerMetric.of(DirectionalUpwindCandidateTest.plane(degrees, .25), ObstacleField.NONE);
            for (int i = 0; i < 4; i++) {
                sink = new FastMarchingSolver(metric).solve(0, 0).at(12, 12);
                sink = DirectionalUpwindCandidate.solve(metric, 0, 0).at(12, 12);
            }
            long[][] nanos = new long[2][9];
            ArrivalTimeField[] last = new ArrivalTimeField[2];
            for (int i = 0; i < 9; i++) for (int j = 0; j < 2; j++) {
                int lane = (i + j) % 2;
                long start = System.nanoTime();
                last[lane] = lane == 0 ? new FastMarchingSolver(metric).solve(0, 0)
                        : DirectionalUpwindCandidate.solve(metric, 0, 0);
                nanos[lane][i] = System.nanoTime() - start;
                sink = last[lane].at(12, 12);
            }
            for (int lane = 0; lane < 2; lane++) {
                Arrays.sort(nanos[lane]);
                int ux = 12, uz = degrees == 0 ? 0 : 12;
                int cx = degrees == 0 ? 0 : 12, cz = degrees == 0 ? 12 : -12;
                System.out.printf(Locale.ROOT,
                        "DIRECTION_RESULT rotation=%d implementation=%s solveP50Ns=%d uphillPerBlock=%.6f contourPerBlock=%.6f uphillPath=%s contourPath=%s%n",
                        degrees, lane == 0 ? "production" : "candidate", nanos[lane][4],
                        last[lane].at(ux, uz) / Math.hypot(ux, uz), last[lane].at(cx, cz) / Math.hypot(cx, cz),
                        describe(metric, last[lane].pathTo(ux, uz)), describe(metric, last[lane].pathTo(cx, cz)));
            }
        }
    }

    private static String describe(FinslerMetric metric, List<int[]> path) {
        if (path.size() < 2) throw new AssertionError("empty candidate route");
        double length = 0, cost = 0, maxGrade = 0, turn = 0;
        for (int i = 1; i < path.size(); i++) {
            int[] a = path.get(i - 1), b = path.get(i);
            double dx = b[0] - a[0], dz = b[1] - a[1], step = Math.hypot(dx, dz);
            if (step == 0 || !metric.isPassable(b[0], b[1]) || metric.blocksEdge(a[0], a[1], b[0], b[1]))
                throw new AssertionError("invalid backtrace");
            length += step; cost += step * metric.cost(b[0], b[1], dx, dz);
            double[] g = metric.terrain().gradient(b[0], b[1]);
            maxGrade = Math.max(maxGrade, Math.abs(g[0] * dx + g[1] * dz) / step);
            if (i > 1) {
                int[] previous = path.get(i - 2);
                double px = a[0] - previous[0], pz = a[1] - previous[1];
                turn += Math.acos(Math.max(-1, Math.min(1, (px * dx + pz * dz) / (Math.hypot(px, pz) * step))));
            }
        }
        // 仅报告回溯折线的代价与转角；没有桥隧DP/实际挖填，不能包装成建造成本。
        return String.format(Locale.ROOT, "{length=%.3f,directionalCost=%.3f,maxGrade=%.3f,turnRadians=%.3f}", length, cost, maxGrade, turn);
    }
}
