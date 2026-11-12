package net.shiroha233.roadweaver.planning.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.059（③）：局部修复 A* 的长度上界 = {@code max(直连 × 1.6, 原走廊长 × 1.05)}。
 *
 * <p>真机一局出现"弦 1053 格的路走了 3826 格（detour 3.63）"，而旧界
 * {@code max(原走廊长 + 200, 原走廊长 × 3.0)} 比它还松 —— 于是这条 3.63 倍绕行在旧口径下
 * 是"合法解"。这里把三个 detour 档位钉死：<b>1.0 接受 / 1.6 接受 / 3.63 拒绝</b>。</p>
 *
 * <p>同时钉住"1.6 不能再被配置关成 0"这件事：{@code landDetourFactor} 可以被关，
 * 而这个上界是常量 —— 否则一次关闸门会让所有局部修复直接判 {@code no_path}。</p>
 */
final class LengthCapRegressionTest {

    /** 真机那条路的弦长（格）。 */
    private static final double CHORD = 1053.0D;

    @Test
    void threeDetourSamplesAtRealChord() {
        // 原走廊就是直连（最保守的假设：没有可借用的既有绕行）
        double cap = AccurateGradeRepairPlanner.lengthCapFor(CHORD, CHORD);
        assertEquals(CHORD * 1.6D, cap, 1.0E-6D);

        // detour 1.0 —— 接受
        assertTrue(AccurateGradeRepairPlanner.withinLengthCap(CHORD, CHORD, CHORD));
        // detour 1.6 —— 接受（正好压在上界上）
        assertTrue(AccurateGradeRepairPlanner.withinLengthCap(CHORD, CHORD, CHORD * 1.6D));
        // detour 3.63 —— 拒绝（真机 3826 格 = 1053 × 3.6334）
        assertEquals(3.6334D, 3826.0D / CHORD, 5.0E-4D);
        assertFalse(AccurateGradeRepairPlanner.withinLengthCap(CHORD, CHORD, 3826.0D));
    }

    @Test
    void longerCorridorGetsFivePercentHeadroomInsteadOfThreeTimes() {
        // 原走廊本身已经是 5 倍弦长的之字路：新界 = max(160, 525) = 525，旧界会是 1500
        double chord = 100.0D;
        double original = 500.0D;
        assertEquals(525.0D, AccurateGradeRepairPlanner.lengthCapFor(chord, original), 1.0E-6D);
        assertTrue(AccurateGradeRepairPlanner.withinLengthCap(chord, original, 520.0D));
        assertFalse(AccurateGradeRepairPlanner.withinLengthCap(chord, original, 530.0D));
        // 旧口径（3 × 原走廊长 = 1500）会接受 1000 格，新口径不会
        assertFalse(AccurateGradeRepairPlanner.withinLengthCap(chord, original, 1000.0D));
    }

    @Test
    void capIsNeverTighterThanFactorTimesChord() {
        // 只要弦长占主导，上界就恒为 1.6 × 弦 —— 这是"detour ≤ 1.6 结果不变"的依据
        assertEquals(1.6D, AccurateGradeRepairPlanner.LENGTH_CAP_DETOUR_FACTOR, 1.0E-12D);
        for (double chord : new double[]{8.0D, 32.0D, 256.0D, 1053.0D, 4096.0D}) {
            double cap = AccurateGradeRepairPlanner.lengthCapFor(chord, chord);
            assertTrue(cap >= chord * 1.6D - 1.0E-9D, "cap 必须 >= 1.6 × 弦");
            assertTrue(AccurateGradeRepairPlanner.withinLengthCap(chord, chord, chord * 1.6D));
        }
    }

    @Test
    void degenerateInputsDoNotProduceAcceptingCap() {
        // 零长度走线不接受；弦/原长为 0 时上界仍有 map 到 8 的下限（landDetourCap 的老约定）
        assertFalse(AccurateGradeRepairPlanner.withinLengthCap(0.0D, 0.0D, 0.0D));
        assertFalse(AccurateGradeRepairPlanner.withinLengthCap(0.0D, 0.0D, -1.0D));
        assertEquals(8.0D * 1.6D, AccurateGradeRepairPlanner.lengthCapFor(0.0D, 0.0D), 1.0E-9D);
    }
}
