/* 文件职责：同时跑新旧两套算法并给出逐项对比，让「换算法」变成可测量的决定。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.List;
import java.util.function.BiFunction;

import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * AB 双跑。
 *
 * <h2>它回答的问题</h2>
 * <p>不是「新算法更快吗」，而是<b>「同一条路，新算法画出来比旧的好还是差」</b>。
 * 四项指标逐项对照，任何一项明显变差都要能在真机之前看见。</p>
 *
 * <h2>怎么把旧链接进来</h2>
 * <p>旧链不需要实现本包的接口 —— 用 {@link #classicFrom} 把一个
 * {@code (from, to) -> List<int[]>} 的函数包起来即可。这样接旧链<b>不用改旧代码</b>。</p>
 *
 * <h2>判定口径</h2>
 * <p>不搞加权总分 —— 那会把「某条路长了 40%」摊薄成看不见的小数。
 * 逐项给出「更好 / 更差 / 持平」，并单独标出<b>任何一项明显变差</b>的项目。</p>
 *
 * @see RouteMetrics
 */
public final class AbRunner {

    /** 差异小于这个比例就算持平。 */
    private static final double TOLERANCE = 0.02;

    private AbRunner() {}

    /**
     * 把旧链的求解函数包成 {@link RouteSolver}。
     *
     * @param provider {@code (from, to) -> 站坐标序列}；返回 null 表示不可达
     */
    public static RouteSolver classicFrom(BiFunction<RoadNode, RoadNode, List<int[]>> provider) {
        if (provider == null) throw new IllegalArgumentException("provider 不能为 null");
        return new RouteSolver() {
            @Override
            public SolverMode mode() {
                return SolverMode.CLASSIC;
            }

            @Override
            public RouteSolution solve(RoadNode from, RoadNode to) {
                List<int[]> points = provider.apply(from, to);
                if (points == null || points.isEmpty()) {
                    return RouteSolution.unreachable();
                }
                return new RouteSolution(points, RouteMetrics.fromPairs(points), true);
            }
        };
    }

    /**
     * 对比一条路线。
     *
     * @return 对比报告；任一侧不可达时对应字段为 {@code null}
     */
    public static AbReport compare(RouteSolver classic, RouteSolver band,
                                   RoadNode from, RoadNode to) {
        RouteSolver.RouteSolution classicResult = classic.solve(from, to);
        RouteSolver.RouteSolution bandResult = band.solve(from, to);

        return new AbReport(
                label(from, to),
                classicResult.reachable() ? classicResult.metrics() : null,
                bandResult.reachable() ? bandResult.metrics() : null);
    }

    /**
     * 对比<b>已经算好</b>的两份结果。
     *
     * <h2>为什么需要这个重载（2026-11-13 接线时加的）</h2>
     * <p>上面那个重载会自己调 {@code solve} —— 但生产里的 AB 观测
     * （{@code AbObservingPathfinder}）<b>已经把两条路都算完了</b>，
     * 再走一遍 {@code solve} 等于把寻路跑第二次，而寻路是秒级的。</p>
     *
     * <p>所以这里收两份现成结果，只做度量与判定。这也是 {@link AbRunner}
     * 第一次进入生产路径 —— 在此之前它只有测试在用。</p>
     *
     * @param route   路线标签（日志用）
     * @param classic 旧链结果，可为 {@code null}
     * @param band    新链结果，可为 {@code null}
     */
    public static AbReport compare(String route,
                                   RouteSolver.RouteSolution classic,
                                   RouteSolver.RouteSolution band) {
        return new AbReport(route,
                classic != null && classic.reachable() ? classic.metrics() : null,
                band != null && band.reachable() ? band.metrics() : null);
    }

    private static String label(RoadNode from, RoadNode to) {
        return from.describe() + " -> " + to.describe();
    }

    /**
     * 一条路线的对比报告。
     *
     * @param route   路线标签
     * @param classic 旧链指标（不可达为 null）
     * @param band    新链指标（不可达为 null）
     */
    public record AbReport(String route, RouteMetrics classic, RouteMetrics band) {

        /** 两侧都可达。 */
        public boolean bothReachable() {
            return classic != null && band != null;
        }

        /** 新链是否明显更长（>2%）。 */
        public boolean bandMuchLonger() {
            if (!bothReachable()) return false;
            return band.length() > classic.length() * (1.0 + TOLERANCE);
        }

        /** 新链是否明显更短（>2%）。 */
        public boolean bandMuchShorter() {
            if (!bothReachable()) return false;
            return band.length() < classic.length() * (1.0 - TOLERANCE);
        }

        /** 新链重复 XZ 是否更多。 */
        public boolean bandMoreDuplicateXz() {
            return bothReachable() && band.duplicateXz() > classic.duplicateXz();
        }

        /** 新链曲率能量是否明显更高（更弯）。 */
        public boolean bandMoreCurved() {
            if (!bothReachable()) return false;
            return band.curvatureEnergy() > classic.curvatureEnergy() * (1.0 + TOLERANCE);
        }

        /** 是否存在「明显变差」的项 —— 这是 AB 判定为不通过的唯一依据。 */
        public boolean bandRegressed() {
            if (!bothReachable()) return false;
            return bandMuchLonger() || bandMoreDuplicateXz() || bandMoreCurved();
        }

        /** 逐项对比的人类可读报告。 */
        public List<String> diffs() {
            if (!bothReachable()) {
                String side = classic == null ? "旧链" : "新链";
                return List.of(route + "：" + side + " 不可达");
            }
            return List.of(
                    route,
                    "  长度    classic=" + fmt(classic.length()) + "  band=" + fmt(band.length())
                            + "  " + (bandMuchShorter() ? "新更短"
                            : bandMuchLonger() ? "⚠ 新更长" : "持平"),
                    "  重复XZ  classic=" + classic.duplicateXz() + "  band=" + band.duplicateXz()
                            + "  " + (bandMoreDuplicateXz() ? "⚠ 新更多" : "不更差"),
                    "  曲率    classic=" + fmt(classic.curvatureEnergy())
                            + "  band=" + fmt(band.curvatureEnergy())
                            + "  " + (bandMoreCurved() ? "⚠ 新更弯" : "不更差"),
                    "  最大转角 classic=" + fmt(classic.maxTurnDegrees()) + "°"
                            + "  band=" + fmt(band.maxTurnDegrees()) + "°",
                    "  点数    classic=" + classic.points() + "  band=" + band.points());
        }

        /** 一行结论。 */
        public String verdict() {
            if (!bothReachable()) return "SKIP（一侧不可达）";
            return bandRegressed() ? "REGRESSED" : "OK";
        }

        private static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }
    }
}
