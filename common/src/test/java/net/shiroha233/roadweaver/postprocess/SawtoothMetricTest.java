package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.019（O-01）：锯齿判据 {@link SawtoothMetric} 的正控与三个负控。
 *
 * <p>三个负控是验收的一部分 —— 没有它们，「把所有弯都拉直」也能通过正控。
 * 这正是 0.8.418 之前反复踩的坑：用 {@code |sharpTurns - 8|} 当目标函数时，
 * 拉直会被奖励。</p>
 *
 * <p>负控三条（对应汇总文档 §3.3）：</p>
 * <ol>
 *   <li><b>90° 真弯</b>不得被削 —— 判据不得把它算成锯齿；</li>
 *   <li><b>幅 6 / 周期 64 的正弦</b>（真实山间缓弯）不得被削 —— 它横向峰值只有 6 格、
 *       落在幅度条件之内，必须靠「腿长」这一条挡住；</li>
 *   <li><b>switchback</b>（腿长 ≥ 4×步距且换向）不得触发判据。</li>
 * </ol>
 */
final class SawtoothMetricTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final double STEP = 8.0D;

    /** 沿 x 每 8 格一站、横向在 0 与 amp 之间交替 —— 复刻 02 分册的幅 3.58 楼梯型样本。 */
    private static List<BlockPos> staircase(int stations, int stepX, int amplitude) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            int z = (k % 2 == 0) ? 0 : amplitude;
            points.add(new BlockPos(k * stepX, 64, z));
        }
        return points;
    }

    /** 横向 offset 按正弦变化，周期 periodBlocks、幅 amplitude。 */
    private static List<BlockPos> sine(int stations, int stepX, int periodBlocks, double amplitude) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            int x = k * stepX;
            double z = amplitude * Math.sin(2.0D * Math.PI * x / periodBlocks);
            points.add(new BlockPos(x, 64, (int) Math.round(z)));
        }
        return points;
    }

    /** 正控：楼梯型锯齿必须判成 zigzag。 */
    @Test
    void staircaseIsZigzag() {
        SawtoothMetric.Result result = SawtoothMetric.measure(staircase(41, 8, 7), STEP);

        assertTrue(result.zigzag(), "沿路反复左右偏移的楼梯必须判成锯齿，reason=" + result.reason());
        assertEquals("zigzag", result.reason());
        assertTrue(result.flips() >= SawtoothMetric.MIN_FLIPS, "每站一次变号 ⇒ 翻转次数应接近站数");
        assertTrue(result.flipsPer100Stations() > 50.0D,
                "楼梯型的每百站翻转率应远高于 50，实测=" + result.flipsPer100Stations());
        assertTrue(result.maxAmplitude() <= SawtoothMetric.AMPLITUDE_FACTOR * STEP,
                "横向峰值必须落在幅度条件内，实测=" + result.maxAmplitude());
        assertTrue(result.legLengthP50() <= SawtoothMetric.LEG_FACTOR * STEP,
                "腿长必须落在腿长条件内，实测=" + result.legLengthP50());
    }

    /** 正控（另一种形态）：小幅交替对角同样必须判成 zigzag。 */
    @Test
    void alternatingDiagonalIsZigzag() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k < 41; k++) {
            points.add(new BlockPos(k * 8, 64, (k % 2 == 0) ? 0 : 8));
        }
        SawtoothMetric.Result result = SawtoothMetric.measure(points, STEP);
        assertTrue(result.zigzag(), "交替对角也是锯齿，reason=" + result.reason());
    }

    /** 负控 1：一条直路接 90° 转弯，判据不得把它算成锯齿。 */
    @Test
    void rightAngleTurnIsNotZigzag() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k <= 20; k++) {
            points.add(new BlockPos(k * 8, 64, 0));
        }
        for (int k = 1; k <= 20; k++) {
            points.add(new BlockPos(160, 64, k * 8));
        }
        SawtoothMetric.Result result = SawtoothMetric.measure(points, STEP);

        assertFalse(result.zigzag(), "单次换向是转弯不是振荡，reason=" + result.reason());
        assertEquals("few-flips", result.reason());
    }

    /** 负控 1b：完全笔直的直路。 */
    @Test
    void straightLineIsNotZigzag() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k <= 30; k++) {
            points.add(new BlockPos(k * 8, 64, 0));
        }
        SawtoothMetric.Result result = SawtoothMetric.measure(points, STEP);
        assertFalse(result.zigzag());
        assertEquals(0, result.flips());
    }

    /**
     * 负控 2：幅 6 / 周期 64 的正弦 —— 真实山间缓弯。
     *
     * <p>它的横向峰值只有 6 格（&lt; 1.2×8 = 9.6），<b>只有「腿长」这一条能挡住</b>：
     * 半周期 32 格 = 4×步距，远超 2×步距的上限。这条用例就是腿长条件的守门人。</p>
     */
    @Test
    void gentleSineSixBySixtyFourIsNotZigzag() {
        SawtoothMetric.Result result = SawtoothMetric.measure(sine(129, 1, 64, 6.0D), STEP);

        assertFalse(result.zigzag(),
                "幅 6 周期 64 的真缓弯不得判成锯齿，否则平滑会把真实弯道一起削掉；reason=" + result.reason());
        assertEquals("legs-too-long", result.reason());
        assertTrue(result.flips() >= SawtoothMetric.MIN_FLIPS, "它确实有多次变号，是被腿长挡下来的");
        assertTrue(result.legLengthP50() > SawtoothMetric.LEG_FACTOR * STEP,
                "腿长应明显超过上限，实测=" + result.legLengthP50());
    }

    /** 负控 3：switchback（腿长 ≈ 5×步距且换向）不得触发判据。 */
    @Test
    void switchbackIsNotZigzag() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k < 21; k++) {
            points.add(new BlockPos(k * 40, 64, (k % 2 == 0) ? 0 : 8));
        }
        SawtoothMetric.Result result = SawtoothMetric.measure(points, STEP);

        assertFalse(result.zigzag(), "switchback 的腿长远超上限，不得判成锯齿，reason=" + result.reason());
        assertEquals("legs-too-long", result.reason());
    }

    /** 点列太短时必须安全返回，不能抛异常、也不能判成锯齿。 */
    @Test
    void shortInputIsSafe() {
        assertFalse(SawtoothMetric.measure(null, STEP).zigzag());
        assertFalse(SawtoothMetric.measure(List.of(), STEP).zigzag());
        assertFalse(SawtoothMetric.measure(List.of(
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)), STEP).zigzag());
        assertEquals("too-short", SawtoothMetric.measure(List.of(
                new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)), STEP).reason());
        assertEquals("null", SawtoothMetric.measure(null, STEP).reason());
    }

    /** 首末重合（环线/短弧）时弦退化，必须安全返回。 */
    @Test
    void closedLoopIsSafe() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k <= 24; k++) {
            points.add(new BlockPos(k * 8, 64, 0));
        }
        for (int k = 24; k >= 0; k--) {
            points.add(new BlockPos(k * 8, 64, 0));
        }
        SawtoothMetric.Result result = SawtoothMetric.measure(points, STEP);
        assertEquals("chord-degenerate", result.reason());
        assertFalse(result.zigzag());
    }
}
