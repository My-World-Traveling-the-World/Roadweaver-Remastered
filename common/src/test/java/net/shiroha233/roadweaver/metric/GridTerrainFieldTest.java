/* 文件职责：网格地形场的构造与梯度正确性，以及它与度量层/求解器的整合。 */
package net.shiroha233.roadweaver.metric;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GridTerrainField} 的正确性，以及「地形 + 度量 + 求解器」整合后的行为。
 *
 * <h2>为什么要有整合测试</h2>
 * <p>单元测试各自绿、拼起来不工作，是重构里最常见的事故。这里用一张真实的
 * <b>山脊</b>地形跑完整链路：{@code GridTerrainField} → {@code FinslerMetric}
 * → {@code FastMarchingSolver}，断言路径会绕开陡坡而不是硬爬。</p>
 *
 * @see GridTerrainField
 * @see FinslerMetric
 */
class GridTerrainFieldTest {

    @Test
    void 按函数构造的网格必须逐点一致() {
        GridTerrainField field = GridTerrainField.fromFunction(100, 200, 5, 4,
                (x, z) -> x * 2 + z);

        assertEquals(100, field.minX());
        assertEquals(104, field.maxX());
        assertEquals(200, field.minZ());
        assertEquals(203, field.maxZ());
        assertEquals(100 * 2 + 200, field.height(100, 200));
        assertEquals(104 * 2 + 203, field.height(104, 203));
        assertEquals(5, field.width(), "宽度应为 5");
        assertEquals(4, field.depth(), "高度应为 4");
    }

    @Test
    void 越界访问必须快速失败而不是静默返回0() {
        GridTerrainField field = GridTerrainField.fromFunction(0, 0, 4, 4, (x, z) -> 0);
        assertThrows(IllegalArgumentException.class, () -> field.height(-1, 0),
                "越界读取必须抛异常 —— 静默返回 0 会让「采样漏了一块」变成幽灵 bug");
        assertThrows(IllegalArgumentException.class, () -> field.height(4, 0),
                "越界读取必须抛异常");
    }

    @Test
    void 边界上的梯度必须退化为单侧差分而不是越界() {
        // 沿 +x 每格升 2
        GridTerrainField field = GridTerrainField.fromFunction(0, 0, 11, 11, (x, z) -> x * 2);

        // 左边界：中心差分要取 x-1（越界）⇒ 必须夹回，退化为单侧差分
        double[] left = field.gradient(0, 5);
        assertEquals(2.0, left[0], 1e-12, "左边界 dh/dx 仍为 2（单侧差分）");

        // 右边界同理
        double[] right = field.gradient(10, 5);
        assertEquals(2.0, right[0], 1e-12, "右边界 dh/dx 仍为 2（单侧差分）");

        // 四个角必须都能算，不抛异常
        for (int[] corner : new int[][]{{0, 0}, {10, 0}, {0, 10}, {10, 10}}) {
            double[] g = field.gradient(corner[0], corner[1]);
            assertEquals(2.0, g[0], 1e-12, "角点 (" + corner[0] + "," + corner[1] + ") 的 dh/dx");
            assertEquals(0.0, g[1], 1e-12, "角点的 dh/dz");
        }
    }

    @Test
    void 斜坡的梯度应等于每格升幅() {
        // 沿 +x 每格升 2，沿 z 不变
        GridTerrainField field = GridTerrainField.fromFunction(0, 0, 11, 11,
                (x, z) -> x * 2);
        double[] gradient = field.gradient(5, 5);
        assertEquals(2.0, gradient[0], 1e-12, "dh/dx 应为 2（中心差分精确）");
        assertEquals(0.0, gradient[1], 1e-12, "dh/dz 应为 0");
    }

    @Test
    void 整合_路径必须绕开陡坡() {
        // 一道南北向的山脊：x ∈ [10,12] 且 z ∈ [5,35] 处高度骤升 30 格，其余平地。
        // **z 方向必须留出绕行空间** —— 如果山脊铺满整条 z，就根本无从绕开，
        // 那测的就不是「绕陡坡」而是「硬翻」了。
        GridTerrainField terrain = GridTerrainField.fromFunction(0, 0, 31, 41, (x, z) -> {
            if (x >= 10 && x <= 12 && z >= 5 && z <= 35) return 30;
            return 0;
        });
        FinslerMetric metric = new FinslerMetric(terrain, ObstacleField.NONE, 1.0, 0.0);
        ArrivalTimeField field = new FastMarchingSolver(metric).solve(5, 20);

        assertTrue(field.reachable(20, 20), "山脊另一侧必须可达");

        // 直线穿山要爬 30 格高的台阶，坡度 = 15/格 ⇒ 每格代价 1 + 15² = 226
        // ⇒ 硬翻约 450+。绕行（北上到 z=4、东行 15、南下 16）约 47。
        // 所以最优解必然是绕行，代价应落在 40~70 区间。
        double actual = field.at(20, 20);
        assertTrue(actual > 15.0, "绕行必须比平地直线 15 更贵，实际 " + actual);
        assertTrue(actual < 120.0,
                "带坡罚时必须选择绕行（约 47）而不是硬翻（约 450+），实际 " + actual);

        // 路径上不应出现高山脊格（坡度罚把它赶开了）
        List<int[]> path = field.pathTo(20, 20);
        assertTrue(!path.isEmpty(), "路径不应为空");
        long onRidge = path.stream()
                .filter(p -> terrain.height(p[0], p[1]) >= 30)
                .count();
        assertEquals(0L, onRidge,
                "带坡罚的路径不应踩上山脊（实际踩了 " + onRidge + " 格）");
    }
}
