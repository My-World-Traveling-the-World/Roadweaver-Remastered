/* 文件职责：验证 terrain 适配器把采样接成地形场后，度量层能正确工作。 */
package net.shiroha233.roadweaver.bridge;

import java.util.List;

import net.shiroha233.roadweaver.metric.ArrivalTimeField;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SampledTerrainField} 的正确性，以及「采样 → 地形场 → 度量 → 求解」的端到端行为。
 *
 * <h2>为什么这一层必须有测试</h2>
 * <p>适配器是<b>两块自洽代码的接缝</b>。单元测试各自绿、接缝错位，是重构里最常见的
 * 事故：数组下标约定不一致（{@code x*width+z} vs {@code z*width+x}）、
 * 区域边界差一格、取样了错误的高度图 —— 这些都不会崩，只会让道路静默走偏。</p>
 *
 * <p>所以这里逐点断言「适配器返回的高度 == 采样器给的高度」，
 * 并用一张程序化丘陵地形跑完整链路。</p>
 *
 * @see SampledTerrainField
 */
class SampledTerrainFieldTest {

    @Test
    void 适配器必须逐点保留采样器给的高度() {
        // 采样器故意用「x*7 + z*13」这种不对称函数，能立刻暴露行列颠倒
        TerrainField field = SampledTerrainField.of(100, 200, 6, 5,
                (minX, minZ, width, depth) -> {
                    int[] heights = new int[width * depth];
                    for (int iz = 0; iz < depth; iz++) {
                        for (int ix = 0; ix < width; ix++) {
                            // 下标约定必须是 (x−minX) + (z−minZ)*width
                            heights[ix + iz * width] =
                                    (minX + ix) * 7 + (minZ + iz) * 13;
                        }
                    }
                    return heights;
                });

        assertEquals(100, field.minX());
        assertEquals(105, field.maxX());
        assertEquals(200, field.minZ());
        assertEquals(204, field.maxZ());

        for (int z = 200; z <= 204; z++) {
            for (int x = 100; x <= 105; x++) {
                assertEquals(x * 7 + z * 13, field.height(x, z),
                        "(" + x + "," + z + ") 的高度不一致 —— 行列下标很可能颠倒了");
            }
        }
    }

    @Test
    void 采样器返回长度不对时必须快速失败() {
        assertThrows(IllegalArgumentException.class,
                () -> SampledTerrainField.of(0, 0, 4, 4, (a, b, w, d) -> new int[3]),
                "采样器返回长度不对必须抛异常，不能静默截断/补齐");
        assertThrows(IllegalArgumentException.class,
                () -> SampledTerrainField.of(0, 0, 4, 4, (a, b, w, d) -> null),
                "采样器返回 null 必须抛异常");
        assertThrows(IllegalArgumentException.class,
                () -> SampledTerrainField.of(0, 0, 4, 4, null),
                "采样器为 null 必须抛异常");
    }

    @Test
    void 端到端_程序化丘陵上路径必须绕开陡坡() {
        // 程序化丘陵：主脊在 x≈18，两侧缓坡，便于断言「绕开最陡处」
        int minX = 0;
        int minZ = 0;
        int width = 41;
        int depth = 41;
        TerrainField terrain = SampledTerrainField.of(minX, minZ, width, depth,
                (x0, z0, w, d) -> {
                    int[] heights = new int[w * d];
                    for (int iz = 0; iz < d; iz++) {
                        for (int ix = 0; ix < w; ix++) {
                            int x = x0 + ix;
                            int z = z0 + iz;
                            // 中部一道台阶山脊（x 17..19 高 20），其余缓丘
                            int ridge = (x >= 17 && x <= 19 && z >= 8 && z <= 32) ? 20 : 0;
                            int hill = (int) Math.round(2.0 * Math.sin(z / 6.0));
                            heights[ix + iz * w] = ridge + hill;
                        }
                    }
                    return heights;
                });

        FinslerMetric metric = new FinslerMetric(terrain, ObstacleField.NONE, 1.0, 0.0);
        ArrivalTimeField field = new FastMarchingSolver(metric).solve(5, 20);

        assertTrue(field.reachable(35, 20), "山脊另一侧必须可达");

        // 沿 z 方向绕行（z<8 或 z>32）比硬翻 20 格台阶便宜得多
        double actual = field.at(35, 20);
        assertTrue(actual > 30.0, "绕行必须比直线 30 更贵，实际 " + actual);
        assertTrue(actual < 90.0, "应当选择绕行而不是硬翻，实际 " + actual);

        // 路径零山脊格
        List<int[]> path = field.pathTo(35, 20);
        assertTrue(!path.isEmpty(), "路径不应为空");
        long onRidge = path.stream()
                .filter(p -> terrain.height(p[0], p[1]) >= 20)
                .count();
        assertEquals(0L, onRidge, "路径不应踩上山脊，实际踩了 " + onRidge + " 格");
    }

    @Test
    void 适配器产生的地形场梯度必须可用() {
        TerrainField field = SampledTerrainField.of(0, 0, 11, 11,
                (x0, z0, w, d) -> {
                    int[] heights = new int[w * d];
                    for (int iz = 0; iz < d; iz++) {
                        for (int ix = 0; ix < w; ix++) {
                            heights[ix + iz * w] = (x0 + ix) * 3;
                        }
                    }
                    return heights;
                });

        double[] g = field.gradient(5, 5);
        assertEquals(3.0, g[0], 1e-12, "沿 +x 每格升 3，dh/dx 应为 3");
        assertEquals(0.0, g[1], 1e-12, "dh/dz 应为 0");
    }
}
