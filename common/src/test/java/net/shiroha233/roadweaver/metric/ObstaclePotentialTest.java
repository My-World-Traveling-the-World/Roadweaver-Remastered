/* 文件职责：证明距离变换是「精确欧氏」而不是近似 —— 与暴力解逐点对照。 */
package net.shiroha233.roadweaver.metric;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 距离变换势场的正确性。
 *
 * <h2>为什么必须对暴力解</h2>
 * <p>距离变换是「<b>精确</b>欧氏距离」这个说法很容易写成近似实现还自我感觉良好 ——
 * 典型错误是把两次一维变换的次序搞反（必须先行后列）、或在下包络算法里把
 * 交点比较写成 {@code <} 而不是 {@code <=}（会吃掉并列抛物线）。这些错误
 * 在小图上肉眼看不出来，但会让势场整体偏移。</p>
 *
 * <p>所以本测试用 {@code O(n²)} 暴力解做<b>逐点</b>对照 —— 小图上代价可以忽略，
 * 但能钉死每一个像素。</p>
 *
 * @see ObstaclePotential
 */
class ObstaclePotentialTest {

    /** 暴力解：到最近障碍的欧氏距离。 */
    private static double bruteForceDistance(boolean[] blocked, int width, int depth,
                                             int x, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (int iz = 0; iz < depth; iz++) {
            for (int ix = 0; ix < width; ix++) {
                if (!blocked[ix + iz * width]) continue;
                double dx = ix - x;
                double dz = iz - z;
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
        }
        return best;
    }

    @Test
    void 距离变换必须与暴力解逐点相同() {
        Random random = new Random(20261112L);

        for (int trial = 0; trial < 20; trial++) {
            int width = 4 + random.nextInt(9);       // 4..12
            int depth = 4 + random.nextInt(9);
            boolean[] blocked = new boolean[width * depth];
            int blockedCount = 0;
            for (int i = 0; i < blocked.length; i++) {
                if (random.nextDouble() < 0.25) {
                    blocked[i] = true;
                    blockedCount++;
                }
            }
            if (blockedCount == 0) continue;          // 全空没有对照意义

            ObstaclePotential potential = ObstaclePotential.fromMask(
                    0, 0, width, depth, blocked, 64.0);

            for (int iz = 0; iz < depth; iz++) {
                for (int ix = 0; ix < width; ix++) {
                    double expected = bruteForceDistance(blocked, width, depth, ix, iz);
                    double actual = potential.distanceAt(ix, iz);
                    assertEquals(expected, actual, 1e-9,
                            "第 " + trial + " 轮 (" + ix + "," + iz + ") 的距离不一致"
                                    + "（网格 " + width + "x" + depth + "）");
                }
            }
        }
    }

    @Test
    void 单点障碍的对角距离应为根号二() {
        boolean[] blocked = new boolean[11 * 11];
        blocked[5 + 5 * 11] = true;                  // 中心设一个障碍

        ObstaclePotential potential = ObstaclePotential.fromMask(0, 0, 11, 11, blocked, 64.0);

        assertEquals(0.0, potential.distanceAt(5, 5), 1e-12, "障碍格自身距离为 0");
        assertEquals(1.0, potential.distanceAt(6, 5), 1e-12, "正交相邻距离为 1");
        assertEquals(Math.sqrt(2.0), potential.distanceAt(6, 6), 1e-12,
                "对角相邻距离必须是 √2 —— 这是「欧氏」而非「曼哈顿」的判据。"
                        + "若得到 2.0 说明用的是棋盘/曼哈顿距离，路径会沿着 45° 走");
        assertEquals(2.0, potential.distanceAt(7, 5), 1e-12, "两格开外为 2");
    }

    @Test
    void 一面墙的距离等于到墙的垂直距离() {
        int width = 21;
        int depth = 21;
        boolean[] blocked = new boolean[width * depth];
        for (int iz = 0; iz < depth; iz++) {
            blocked[10 + iz * width] = true;         // x = 10 一整列
        }

        ObstaclePotential potential = ObstaclePotential.fromMask(0, 0, width, depth, blocked, 64.0);

        assertEquals(5.0, potential.distanceAt(5, 0), 1e-12, "墙左侧 5 格");
        assertEquals(5.0, potential.distanceAt(5, 20), 1e-12, "同列任何 z 都一样（墙无限长）");
        assertEquals(3.0, potential.distanceAt(13, 7), 1e-12, "墙右侧 3 格");
    }

    @Test
    void 势值随距离线性衰减且半径外为0() {
        boolean[] blocked = new boolean[21 * 21];
        blocked[10 + 10 * 21] = true;

        ObstaclePotential potential = ObstaclePotential.fromMask(0, 0, 21, 21, blocked, 4.0);

        assertEquals(1.0, potential.potential(10, 10), 1e-12, "障碍格势为 1");
        assertEquals(0.75, potential.potential(11, 10), 1e-12, "距离 1、半径 4 → 1−1/4");
        assertEquals(0.5, potential.potential(12, 10), 1e-12, "距离 2 → 0.5");
        assertEquals(0.0, potential.potential(14, 10), 1e-12, "距离 4 = 半径 → 0");
        assertEquals(0.0, potential.potential(20, 10), 1e-12, "半径外恒为 0");
    }

    @Test
    void 空掩码全为0_满掩码全为1() {
        boolean[] empty = new boolean[30];
        ObstaclePotential none = ObstaclePotential.fromMask(0, 0, 6, 5, empty, 8.0);
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 6; x++) {
                assertEquals(0.0, none.potential(x, z), 1e-12, "无掩码时处处为 0");
            }
        }

        boolean[] full = new boolean[30];
        java.util.Arrays.fill(full, true);
        ObstaclePotential all = ObstaclePotential.fromMask(0, 0, 6, 5, full, 8.0);
        assertEquals(1.0, all.potential(3, 2), 1e-12, "满掩码时处处为 1");
    }

    @Test
    void 区域外必须视为最差以免路径走出规划范围() {
        boolean[] blocked = new boolean[9];
        ObstaclePotential potential = ObstaclePotential.fromMask(0, 0, 3, 3, blocked, 8.0);

        assertEquals(1.0, potential.potential(-1, 0), 1e-12, "区域外势为 1");
        assertEquals(1.0, potential.potential(3, 0), 1e-12, "区域外势为 1");
        assertTrue(Double.isInfinite(potential.distanceAt(99, 99)), "区域外距离为无穷");
    }
}
