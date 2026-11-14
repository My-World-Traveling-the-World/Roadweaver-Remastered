/* 文件职责：钉住弹性带的硬障碍投影 —— band 层不能再把曲线拉进建筑/山体。 */
package net.shiroha233.roadweaver.band;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹性带障碍投影测试（2026-11-14，审计 B）。
 *
 * <h2>契约是什么</h2>
 * <p>{@code FinslerMetric} 与 {@code ObstaclePotential} 的注释都写着
 * 「『绝不穿结构』由 band 层的硬投影负责」。而 {@link ElasticBandSolver} 原本
 * 完全不认识障碍 —— 它只做曲率最小化，会把曲线拉直、拉进山体和建筑里，
 * 契约是断的。这里钉住修复后的行为。</p>
 *
 * @see ElasticBandSolver
 */
class ElasticBandObstacleProjectionTest {

    /** 竖墙：x == wallX 的整列不可通行。 */
    private static ElasticBandSolver.BlockedCheck verticalWall(int wallX) {
        return (x, z) -> x == wallX;
    }

    private static List<BandResult.Point> straightLine(int fromX, int toX, int z) {
        List<BandResult.Point> points = new ArrayList<>();
        for (int x = fromX; x <= toX; x += 2) {
            points.add(new BandResult.Point(x, z));
        }
        if (points.get(points.size() - 1).x() != toX) {
            points.add(new BandResult.Point(toX, z));
        }
        return points;
    }

    @Test
    void 直线穿过竖墙时被推出且端点不变() {
        ElasticBandSolver solver = ElasticBandSolver.defaults();
        List<BandResult.Point> input = straightLine(0, 10, 10);   // x = 0,2,4,6,8,10

        BandResult result = solver.smooth(input, verticalWall(6));

        BandResult.Point first = result.points().get(0);
        BandResult.Point last = result.points().get(result.size() - 1);
        assertEquals(0, first.x(), "起点必须不变");
        assertEquals(10, last.x(), "终点必须不变");

        for (BandResult.Point p : result.points()) {
            assertFalse(verticalWall(6).blocked(p.x(), p.z()),
                    "投影后不应有任何点落在硬障碍上，实际 (" + p.x() + "," + p.z() + ")");
        }
        assertTrue(result.projectedPoints() > 0,
                "应有被投影的点，实际 projectedPoints=" + result.projectedPoints());
    }

    @Test
    void 无障碍时不投影且点数与老行为一致() {
        ElasticBandSolver solver = ElasticBandSolver.defaults();
        List<BandResult.Point> input = straightLine(0, 10, 10);

        BandResult withCheck = solver.smooth(input, (x, z) -> false);
        BandResult without = solver.smooth(input);

        assertEquals(0, withCheck.projectedPoints(), "处处可通行时不该有投影");
        assertEquals(without.size(), withCheck.size(), "点数必须与老行为一致（生产站距不能变）");
        assertEquals(without.curvatureAfter(), withCheck.curvatureAfter(), 1e-9,
                "无障碍时曲率必须与老行为完全相同");
    }

    @Test
    void 传入null判定时严格等价于老行为() {
        ElasticBandSolver solver = ElasticBandSolver.defaults();
        List<BandResult.Point> input = List.of(
                new BandResult.Point(0, 0),
                new BandResult.Point(4, 1),
                new BandResult.Point(8, 0),
                new BandResult.Point(12, 2));

        BandResult legacy = solver.smooth(input);
        BandResult withNull = solver.smooth(input, null);

        assertEquals(legacy.points(), withNull.points(), "点列必须逐点相同");
        assertEquals(legacy.curvatureAfter(), withNull.curvatureAfter(), 1e-12, "曲率必须相同");
        assertEquals(0, withNull.projectedPoints(), "null 判定不应产生投影");
    }

    @Test
    void 整片封死时保留原点而不是抛出异常() {
        ElasticBandSolver solver = ElasticBandSolver.defaults();
        List<BandResult.Point> input = straightLine(0, 10, 10);

        // 半径内全封死：inside 一个 40 格见方的禁区
        BandResult result = solver.smooth(input, (x, z) -> true);

        assertEquals(input.size(), result.size(), "推不出去时必须保留原曲线，不能崩、不能丢点");
        assertEquals(0, result.projectedPoints(), "无一处可推时为 0");
    }
}