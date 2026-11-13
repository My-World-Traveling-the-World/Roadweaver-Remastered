/* 文件职责：验证按步长采样的地形场与寻路器工厂的算法选择。 */
package net.shiroha233.roadweaver.bridge;

import java.util.List;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.PathfinderFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步长采样与工厂选择。
 *
 * <h2>为什么步长必须单独测</h2>
 * <p>步长错误是「看起来能跑、结果系统性偏一点」的典型：</p>
 * <ul>
 *   <li>梯度忘了除以 step ⇒ 坡度放大 step 倍 ⇒ 平原上也开始绕坡</li>
 *   <li>慢度忘了乘 step ⇒ 到达时间低估 step 倍 ⇒ 绕行比算出来偏小</li>
 * </ul>
 * <p>这两种都不会崩，只会让结果慢慢偏离，而且极难反推。</p>
 *
 * @see StridedTerrainField
 */
class StridedTerrainFieldTest {

    @Test
    void 步长采样必须按块坐标吸附且梯度除以步长() {
        // 沿 +x 每格升 1 的斜坡，步长 4
        TerrainField field = StridedTerrainField.sample(0, 0, 41, 41, 4,
                (minX, minZ, w, d) -> {
                    int[] heights = new int[w * d];
                    for (int iz = 0; iz < d; iz++) {
                        for (int ix = 0; ix < w; ix++) {
                            heights[ix + iz * w] = minX + ix;
                        }
                    }
                    return heights;
                });

        assertEquals(4, field.step(), "步长必须被保留");
        assertEquals(11, field.columns(), "41 格、步长 4 → 11 个采样点");
        assertEquals(11, field.rows());
        assertEquals(0, field.minX());
        assertEquals(40, field.maxX(), "最后一个采样点在 x=40");

        // 逐格交互式读取：非采样点必须吸附到最近采样点
        for (int x = 0; x <= 40; x++) {
            // 吸附是**就近**（round），不是向下取整：
            // x=2 距 0 和 4 各 2 格，round 取上界 4；写成 floor 会差半格
            int expected = Math.min(40, (int) Math.round(x / 4.0) * 4);
            assertEquals(expected, field.height(x, 3),
                    "x=" + x + " 应就近吸附到 4 的倍数");
        }

        // 梯度必须按「每格」归一：斜坡每格升 1 ⇒ 梯度恒为 1
        double[] g = field.gradient(20, 20);
        assertEquals(1.0, g[0], 1e-9,
                "梯度必须除以步长。若不除，这里会得到 4.0 —— "
                        + "坡罚放大 16 倍，表现为平原上也开始绕坡");
        assertEquals(0.0, g[1], 1e-9);
    }

    @Test
    void 步长下快速行进仍应得到正确的路程() {
        TerrainField field = StridedTerrainField.of(0, 0, 11, 11, 4,
                new int[11 * 11]);          // 全平地
        FinslerMetric metric = new FinslerMetric(field, ObstacleField.NONE, 0.0, 0.0);

        ArrivalTimeField result = new FastMarchingSolver(metric).solve(0, 0);

        assertEquals(4, result.step(), "到达时间场必须记住步长");
        // 正交方向 40 格 ⇒ 到达时间必须是 40（步长 4 时是 10 个网格间距）
        assertEquals(40.0, result.at(40, 0), 1e-6,
                "正交方向到达时间必须等于真实格数。"
                        + "若得到 10，说明慢度忘了乘 step");
        assertEquals(40.0, result.at(0, 40), 1e-6);

        // 回溯出的路径必须是方块坐标（相邻点间距为 step）
        List<int[]> path = result.pathTo(40, 0);
        assertFalse(path.isEmpty(), "路径不应为空");
        // pathTo 返回的是「源 → 终点」顺序，所以终点在最后
        assertEquals(0, path.get(0)[0], "路径起点必须是源点 x=0");
        assertEquals(40, path.get(path.size() - 1)[0], "路径终点必须是方块坐标 40");
        for (int i = 1; i < path.size(); i++) {
            int dx = Math.abs(path.get(i)[0] - path.get(i - 1)[0]);
            assertTrue(dx == 4 || dx == 0,
                    "相邻点间距必须是步长 4（或 0），实际 " + dx);
        }
    }

    @Test
    void 步长为1时行为必须与逐格完全一致() {
        TerrainField filed = StridedTerrainField.of(0, 0, 21, 21, 1,
                new int[21 * 21]);
        FinslerMetric metric = new FinslerMetric(filed, ObstacleField.NONE, 0.0, 0.0);
        ArrivalTimeField result = new FastMarchingSolver(metric).solve(0, 0);

        assertEquals(1, result.step());
        assertEquals(20.0, result.at(20, 0), 1e-6, "步长 1 时行为必须与逐格一致");
    }

    // ── 工厂 ──────────────────────────────────────────────────

    @Test
    void 工厂必须能造出新架构寻路器且不放走旧算法() {
        assertInstanceOf(BandPathfinder.class, PathfinderFactory.create(PathfindingAlgorithm.BAND),
                "BAND 必须造出新架构寻路器");

        // 旧算法必须都还在（换算法是加法，不是替换）
        for (PathfindingAlgorithm algorithm : PathfindingAlgorithm.values()) {
            Pathfinder pathfinder = PathfinderFactory.create(algorithm);
            assertTrue(pathfinder != null, algorithm + " 必须能造出实例");
        }
    }

    @Test
    void 出厂默认算法必须是新内核() {
        var config = new net.shiroha233.roadweaver.config.sub.PathfindingCostConfig();
        assertEquals(PathfindingAlgorithm.BAND, config.pathfindingAlgorithm(),
                "2026-11-13 起出厂默认是 BAND —— 用户拍板「全用最新的」；"
                        + "老配置里的旧枚举名由 sanitize() 迁移到 BAND");
    }
}
