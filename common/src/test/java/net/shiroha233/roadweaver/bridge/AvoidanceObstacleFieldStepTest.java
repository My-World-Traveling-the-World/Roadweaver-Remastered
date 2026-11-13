/* 文件职责：钉住 AvoidanceObstacleField 的采样步长语义 —— 尤其「只在采样点调判据」。 */
package net.shiroha233.roadweaver.bridge;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AvoidanceObstacleField} 的步长语义。
 *
 * <h2>为什么这个类必须支持步长（2026-11-13）</h2>
 * <p>它此前恒按 <b>1:1 逐格</b>采样，而它要配合的地形场正改成按步长采样
 * （网络级规划 1500×1500 的区域在 step=8 时只有 187×187 个点）。两者尺寸若不匹配，
 * 根本配不到一起 —— 这才是本类长期零引用的结构性原因：
 * <b>不是它没用，是接不上。</b></p>
 *
 * <h2>省下来的到底是什么</h2>
 * <p>判据 {@code blocksPlanningObstacle} 内部要过 ThreadLocal 快照、包围盒相交、
 * 村庄群聚合盒（带缓存但仍有哈希开销）。1:1 采样 289 次 vs step=8 采样 9 次 ——
 * 差 32 倍，而区域越大差距越悬殊（step=8 是 64 倍量级）。这不是微优化：
 * 类注释自己写着「逐点回调会把判据的常数项放大到主导地位」。</p>
 */
class AvoidanceObstacleFieldStepTest {

    /** 一块平坦地形，尺寸按 step 折算成 3×3 个采样点。 */
    private static TerrainField flat17() {
        return SampledTerrainField.of(0, 0, 17, 17, 8,
                (minX, minZ, columns, rows) -> new int[columns * rows]);
    }

    @Test
    void 带步长时只在采样点调判据而不是逐格() {
        TerrainField terrain = flat17();
        int[] calls = {0};
        AvoidanceObstacleField.sample(terrain, 0, 0, 17, 17, 8, 0, 3.0,
                (x, y, z) -> {
                    calls[0]++;
                    return false;
                });
        // 17 格 / step=8 => 采样点 x = 0/8/16 共 3 个，行列各 3 => 9 次
        assertEquals(9, calls[0],
                "step=8 时判据只该被调 9 次（3×3 采样点）；若是 289 次说明还在逐格采样");
    }

    @Test
    void 步长为一时的调用次数与区域面积相等() {
        TerrainField terrain = SampledTerrainField.of(0, 0, 5, 4,
                (minX, minZ, columns, rows) -> new int[columns * rows]);
        int[] calls = {0};
        AvoidanceObstacleField.sample(terrain, 0, 0, 5, 4, 0, 3.0,
                (x, y, z) -> {
                    calls[0]++;
                    return false;
                });
        assertEquals(20, calls[0], "step=1 时应逐格调 5×4 = 20 次");
    }

    @Test
    void 带步长时越界仍然视为不可通行() {
        TerrainField terrain = flat17();
        ObstacleField field = AvoidanceObstacleField.sample(terrain, 0, 0, 17, 17, 8, 0, 3.0,
                (x, y, z) -> false);                       // 什么都不挡

        assertFalse(field.impassable(0, 0), "区域内且判据不挡 => 可通行");
        assertFalse(field.impassable(16, 16), "最后一个采样点仍可通行");

        assertTrue(field.impassable(-1, 0), "区域外一律不可通行（fail-safe，防求解器走出去）");
        assertTrue(field.impassable(17, 0), "maxX = 0 + (3-1)*8 = 16，17 已越界");
        assertTrue(field.impassable(0, 17), "maxZ = 16，17 已越界");
    }

    @Test
    void 带步长时判据挡住的采样点必须影响其吸附范围() {
        TerrainField terrain = flat17();
        // 只挡 (8, 8) 这一个采样点 —— 它吸附覆盖 x∈[4,12) 与 z∈[4,12) 的方块
        ObstacleField field = AvoidanceObstacleField.sample(terrain, 0, 0, 17, 17, 8, 0, 3.0,
                (x, y, z) -> x == 8 && z == 8);

        assertTrue(field.impassable(8, 8), "被挡的采样点本身不可通行");
        assertTrue(field.impassable(5, 5), "就近吸附到 (8,8) => 也不可通行");
        assertTrue(field.impassable(10, 11), "吸附半径内仍不可通行");
        assertFalse(field.impassable(0, 0), "另一个采样点不受影响");
        assertFalse(field.impassable(16, 16), "最后一个采样点不受影响");
    }

    @Test
    void 步长默认跟随地形场() {
        TerrainField terrain = flat17();               // step = 8
        int[] calls = {0};
        // 不传 step 的重载应当自动用地形场的 8
        AvoidanceObstacleField.sample(terrain, 0, 0, 17, 17, 0, 3.0,
                (x, y, z) -> {
                    calls[0]++;
                    return false;
                });
        assertEquals(9, calls[0],
                "不传 step 时必须跟随地形场的 step=8（而不是回落成 1 的 289 次）");
    }
}