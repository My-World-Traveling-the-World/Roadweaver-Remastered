/* 文件职责：验证 AB 开关的默认值、中立度量与两套算法的对比判定。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.topology.RoadNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AB 双跑层。
 *
 * <h2>最要紧的一条是「默认不许换算法」</h2>
 * <p>新算法还没真机验证过，所以任何「不认识 / 缺失 / 写错」的配置都必须
 * 回落到 CLASSIC —— 静默换掉全部道路形态是绝对不能接受的。</p>
 *
 * @see SolverMode
 * @see RouteMetrics
 * @see AbRunner
 */
class AbRunnerTest {

    // ── 1. 模式解析与默认值 ───────────────────────────────────

    @Test
    void 无法识别的配置必须回落到旧链() {
        assertEquals(SolverMode.CLASSIC, SolverMode.parse(null), "null 必须回落 CLASSIC");
        assertEquals(SolverMode.CLASSIC, SolverMode.parse(""), "空串必须回落 CLASSIC");
        assertEquals(SolverMode.CLASSIC, SolverMode.parse("whatever"), "未知值必须回落 CLASSIC");
        assertEquals(SolverMode.CLASSIC, SolverMode.parse("CLASSIC"), "大小写不敏感");
        assertEquals(SolverMode.BAND, SolverMode.parse("  band  "), "前后空格要容忍");
        assertEquals(SolverMode.AB, SolverMode.parse("ab"));
    }

    @Test
    void AB模式下旧链生效新链只观察() {
        assertEquals(SolverMode.CLASSIC, SolverMode.AB.effective(),
                "AB 模式必须让旧链生效 —— 否则这个模式没法用来「安全地看新算法表现」");
        assertTrue(SolverMode.AB.runsClassic());
        assertTrue(SolverMode.AB.runsBand());

        assertEquals(SolverMode.BAND, SolverMode.BAND.effective());
        assertFalse(SolverMode.BAND.runsClassic());

        assertEquals(SolverMode.CLASSIC, SolverMode.CLASSIC.effective());
        assertFalse(SolverMode.CLASSIC.runsBand());
    }

    // ── 2. 中立度量 ───────────────────────────────────────────

    @Test
    void 直线度量必须无曲率无转角无重复() {
        RouteMetrics m = RouteMetrics.from(
                new int[]{0, 10, 20, 30},
                new int[]{0, 0, 0, 0});

        assertEquals(4, m.points());
        assertEquals(30.0, m.length(), 1e-9, "直线弧长 = 30");
        assertEquals(0, m.duplicateXz(), "直线不该有重复 XZ");
        assertEquals(0.0, m.curvatureEnergy(), 1e-9, "直线曲率能量为 0");
        assertEquals(0.0, m.maxTurnDegrees(), 1e-9, "直线不转角");
    }

    @Test
    void 直角弯的最大转角必须是九十度() {
        // 沿 +x 走，然后沿 +z 走
        RouteMetrics m = RouteMetrics.from(
                new int[]{0, 10, 10},
                new int[]{0, 0, 10});

        assertEquals(90.0, m.maxTurnDegrees(), 1e-6,
                "直角弯必须是 90° —— 这一项故意用最大值而不是平均值："
                        + "平均值会把一个 90° 急弯摊薄到看不见（历史上 sharpTurnCount 用 >90 严格判据，"
                        + "而 D1 的转向角恰好等于 90°，结构性抓不到）");
        assertTrue(m.curvatureEnergy() > 0.0, "直角弯曲率能量应大于 0");
    }

    @Test
    void 重复XZ必须被数出来() {
        RouteMetrics m = RouteMetrics.from(
                new int[]{0, 10, 10, 20},
                new int[]{0, 0, 0, 0});
        assertEquals(1, m.duplicateXz(), "第二个 (10,0) 是一次重复");
    }

    // ── 3. 新链单路线求解器 ───────────────────────────────────

    private static FinslerMetric flatMetric(int size) {
        return new FinslerMetric(
                GridTerrainField.fromFunction(0, 0, size, size, (x, z) -> 0),
                ObstacleField.NONE, 0.0, 0.0);
    }

    @Test
    void 新链单路线求解必须端点精确且可达() {
        BandRouteSolver solver = new BandRouteSolver(flatMetric(61), ElasticBandSolver.defaults());
        RouteSolver.RouteSolution solution = solver.solve(
                new RoadNode("a", 10, 10), new RoadNode("b", 50, 30));

        assertTrue(solution.reachable(), "平地上必须可达");
        assertFalse(solution.points().isEmpty(), "可达时点列不能为空");

        int[] first = solution.points().get(0);
        int[] last = solution.points().get(solution.points().size() - 1);
        assertEquals(10, first[0], "起点 x 必须锁死");
        assertEquals(10, first[1], "起点 z 必须锁死");
        assertEquals(50, last[0], "终点 x 必须锁死");
        assertEquals(30, last[1], "终点 z 必须锁死");

        // 长度不短于直线（理论上应略长，因为 FM 对角线高估）
        double straight = Math.hypot(40, 20);
        assertTrue(solution.metrics().length() >= straight - 1e-6,
                "弧长不该短于直线，实际 " + solution.metrics().length());
    }

    @Test
    void 硬挡死时新链必须报不可达() {
        ObstacleField wall = new ObstacleField() {
            @Override public double potential(int x, int z) { return x == 30 ? 1.0 : 0.0; }
            @Override public boolean impassable(int x, int z) { return x == 30; }
        };
        FinslerMetric metric = new FinslerMetric(
                GridTerrainField.fromFunction(0, 0, 61, 61, (x, z) -> 0), wall, 0.0, 8.0);

        BandRouteSolver solver = new BandRouteSolver(metric, ElasticBandSolver.defaults());
        RouteSolver.RouteSolution solution = solver.solve(
                new RoadNode("west", 10, 30), new RoadNode("east", 50, 30));

        assertFalse(solution.reachable(), "被硬挡死时必须报不可达");
        assertTrue(solution.points().isEmpty(), "不可达时点列必须为空");
    }

    // ── 4. AB 对比 ────────────────────────────────────────────

    @Test
    void AB对比必须同时给出两侧指标() {
        // 假旧链：直接走直线
        RouteSolver classic = AbRunner.classicFrom((from, to) -> {
            List<int[]> points = new ArrayList<>();
            int steps = 10;
            for (int i = 0; i <= steps; i++) {
                points.add(new int[]{
                        from.x() + (to.x() - from.x()) * i / steps,
                        from.z() + (to.z() - from.z()) * i / steps});
            }
            return points;
        });
        RouteSolver band = new BandRouteSolver(flatMetric(61), ElasticBandSolver.defaults());

        RoadNode a = new RoadNode("a", 10, 10);
        RoadNode b = new RoadNode("b", 50, 10);
        AbRunner.AbReport report = AbRunner.compare(classic, band, a, b);

        assertTrue(report.bothReachable(), "平地上两侧都应可达");
        assertFalse(report.diffs().isEmpty(), "必须产出逐项对比");
        assertTrue(report.verdict().equals("OK") || report.verdict().equals("REGRESSED"),
                "结论必须是 OK 或 REGRESSED，实际 " + report.verdict());

        // 旧链直线长度应为 40
        assertEquals(40.0, report.classic().length(), 1e-6, "旧链直线长度 40");
        // 新链长度也应为 40（正交方向 FM 是精确的）
        assertEquals(40.0, report.band().length(), 1e-6,
                "正交方向上 Fast Marching 是精确的，长度也必须是 40");
    }

    @Test
    void AB对比必须能识别出更长的结果() {
        // 假旧链：直线 40 格
        RouteSolver classic = AbRunner.classicFrom((from, to) -> List.of(
                new int[]{from.x(), from.z()},
                new int[]{to.x(), to.z()}));
        // 假新链：故意绕远路（长度翻倍）
        RouteSolver band = AbRunner.classicFrom((from, to) -> List.of(
                new int[]{from.x(), from.z()},
                new int[]{(from.x() + to.x()) / 2, from.z() + 40},
                new int[]{to.x(), to.z()}));

        AbRunner.AbReport report = AbRunner.compare(
                classic, band, new RoadNode("a", 0, 0), new RoadNode("b", 40, 0));

        assertTrue(report.bandMuchLonger(), "绕远路必须被识别为更长");
        assertTrue(report.bandRegressed(), "明显变长必须判定为回退");
        assertTrue(report.verdict().equals("REGRESSED"),
                "结论应为 REGRESSED，实际 " + report.verdict());
    }

    @Test
    void 一侧不可达时必须跳过而不是编造数据() {
        RouteSolver classic = AbRunner.classicFrom((from, to) -> null);
        RouteSolver band = new BandRouteSolver(flatMetric(21), ElasticBandSolver.defaults());

        AbRunner.AbReport report = AbRunner.compare(
                classic, band, new RoadNode("a", 5, 5), new RoadNode("b", 15, 15));

        assertFalse(report.bothReachable(), "一侧不可达时不能声称两侧都可达");
        assertNull(report.classic(), "旧链不可达时指标必须为 null，不能编造");
        assertFalse(report.bandRegressed(), "数据不全时不能判定回退");
        assertEquals("SKIP（一侧不可达）", report.verdict());
    }
}
