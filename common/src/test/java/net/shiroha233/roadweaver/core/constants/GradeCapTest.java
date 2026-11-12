package net.shiroha233.roadweaver.core.constants;

import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;

/**
 * 0.9.061：最大坡度三档。
 *
 * <p>三件事必须钉死：①{@code roadweaver.gradeCap} 的解析（四个输入 + 非法回落）；
 * ②纯函数 {@link GradeCap#maxRise} 的取整方向与边界（短窗口只会更严，绝不因浮点放宽）；
 * ③窗口约束真的把一条 1 格/站的坡道压成「每 N 站抬 1 格」的台阶，
 * 而不是「配置里写着 12.5%、实际还是 50%」。</p>
 */
final class GradeCapTest {

    private static final String PROPERTY = RoadConstants.GRADE_CAP_PROPERTY;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void threeTiersResolveAndIllegalValuesFallBackToTheDefault() {
        assertEquals(GradeCap.Mode.TIGHT, GradeCap.resolve("tight"), "tight");
        assertEquals(GradeCap.Mode.NORMAL, GradeCap.resolve("normal"), "normal");
        assertEquals(GradeCap.Mode.LOOSE, GradeCap.resolve("loose"), "loose");
        assertEquals(GradeCap.Mode.TIGHT, GradeCap.resolve("  TIGHT  "), "trim + 大小写不敏感");
        // 0.9.062（③）：默认档从 normal(12.5%) 改成 loose(14.29%)。这里钉的语义仍然是
        // 「非法输入回落 DEFAULT_MODE」，只是把期望值从写死的 normal 换成 DEFAULT_MODE ——
        // 否则每次动默认档都要改四处，而且会看不出哪一条是"回退语义"、哪一条是"默认档本身"。
        assertEquals(GradeCap.Mode.LOOSE, GradeCap.DEFAULT_MODE, "0.9.062 起默认档必须是 loose");
        assertEquals(GradeCap.DEFAULT_MODE, GradeCap.resolve("bogus"), "非法值回落 DEFAULT_MODE");
        assertEquals(GradeCap.DEFAULT_MODE, GradeCap.resolve(""), "空串回落 DEFAULT_MODE");
        assertEquals(GradeCap.DEFAULT_MODE, GradeCap.resolve("   "), "空白回落 DEFAULT_MODE");
        assertEquals(GradeCap.DEFAULT_MODE, GradeCap.resolve(null), "null 回落 DEFAULT_MODE");
        assertEquals(10, GradeCap.riseStations(GradeCap.Mode.TIGHT));
        assertEquals(8, GradeCap.riseStations(GradeCap.Mode.NORMAL));
        assertEquals(7, GradeCap.riseStations(GradeCap.Mode.LOOSE));
        assertEquals(1, GradeCap.riseBlocks(GradeCap.Mode.NORMAL), "三档都是每 N 站抬 1 格");
    }

    @Test
    void currentReadsTheRuntimePropertyAndFallsBackToTheDefault() {
        System.clearProperty(PROPERTY);
        assertEquals(GradeCap.Mode.LOOSE, GradeCap.current(), "没设属性时是 loose（0.9.062 起的默认档）");
        System.setProperty(PROPERTY, "loose");
        assertEquals(GradeCap.Mode.LOOSE, GradeCap.current(), "运行时可切到 loose");
        System.setProperty(PROPERTY, "tight");
        assertEquals(GradeCap.Mode.TIGHT, GradeCap.current(), "运行时可切到 tight");
        System.setProperty(PROPERTY, "12.5%");
        assertEquals(GradeCap.DEFAULT_MODE, GradeCap.current(), "非法值一律回落 DEFAULT_MODE");
    }

    @Test
    void maxRiseIsAFloorOfTheWindowAndNeverRoundsUp() {
        // 用户点名的三个边界
        assertEquals(1, GradeCap.maxRise(10.0D, GradeCap.Mode.TIGHT), "10 格 @tight = 1 格");
        assertEquals(1, GradeCap.maxRise(10.0D, GradeCap.Mode.NORMAL), "10 格 @normal = 1 格");
        assertEquals(2, GradeCap.maxRise(20.0D, GradeCap.Mode.NORMAL), "20 格 @normal = 2 格");
        // 窗口边界：差一点点就不许抬
        assertEquals(1, GradeCap.maxRise(8.0D, GradeCap.Mode.NORMAL), "正好 8 格 = 1 格");
        assertEquals(0, GradeCap.maxRise(7.999D, GradeCap.Mode.NORMAL), "7.999 格仍然 0 格");
        assertEquals(1, GradeCap.maxRise(7.0D, GradeCap.Mode.LOOSE), "正好 7 格 @loose = 1 格");
        assertEquals(0, GradeCap.maxRise(6.999D, GradeCap.Mode.LOOSE), "6.999 格 @loose = 0 格");
        // 更长距离线性放大
        assertEquals(8, GradeCap.maxRise(80.0D, GradeCap.Mode.TIGHT), "80 格 @tight = 8 格");
        assertEquals(10, GradeCap.maxRise(80.0D, GradeCap.Mode.NORMAL), "80 格 @normal = 10 格");
        // 退化输入
        assertEquals(0, GradeCap.maxRise(0.0D, GradeCap.Mode.NORMAL));
        assertEquals(0, GradeCap.maxRise(-5.0D, GradeCap.Mode.NORMAL), "负距离不产生爬升额度");
        assertEquals(0, GradeCap.maxRise(Double.NaN, GradeCap.Mode.NORMAL), "NaN 不产生爬升额度");
    }

    @Test
    void gradePercentMatchesTheTier() {
        assertEquals(10.0D, GradeCap.gradePercent(GradeCap.Mode.TIGHT), 1e-9);
        assertEquals(12.5D, GradeCap.gradePercent(GradeCap.Mode.NORMAL), 1e-9);
        assertEquals(100.0D / 7.0D, GradeCap.gradePercent(GradeCap.Mode.LOOSE), 1e-9);
        assertEquals(1.0D, GradeCap.stationSpacing(GradeCap.Mode.NORMAL), 1e-9,
                "实测站距是 1.0 格/站（PathPostProcessor.extractCenters）");
    }

    @Test
    void theWindowConstraintStaircasesAOneBlockPerStationRamp() {
        int stations = 40;
        int[] ramp = new int[stations];
        for (int i = 0; i < stations; i++) ramp[i] = i;   // 每站抬 1 格 = 100%
        int[] before = ramp.clone();

        int[] capped = RoadGradePlanner.enforceGradeCap(
                ramp, null, null, null, GradeCap.Mode.NORMAL);

        assertArrayEquals(before, ramp, "约束函数不许改输入数组");
        for (int i = 0; i < stations; i++) {
            assertEquals(i / 8, capped[i],
                    "每 8 站才允许抬 1 格，站 " + i + " 应在第 " + (i / 8) + " 级台阶上");
        }
        // 逐窗口直接复核：任意 8 站窗口内的抬升都不超过 1 格
        for (int start = 0; start + 8 < stations; start++) {
            assertTrue(capped[start + 8] - capped[start] <= 1,
                    "窗口 " + start + ".." + (start + 8) + " 抬了 "
                            + (capped[start + 8] - capped[start]) + " 格");
        }
    }

    @Test
    void aProfileInsideTheCapIsLeftUntouched() {
        int[] flat = new int[40];
        java.util.Arrays.fill(flat, 64);
        assertArrayEquals(flat, RoadGradePlanner.enforceGradeCap(
                flat, null, null, null, GradeCap.Mode.TIGHT), "平路一个站都不该动");

        int[] gentle = new int[40];
        for (int i = 0; i < gentle.length; i++) gentle[i] = 64 + i / 10;   // 每 10 站 1 格
        assertArrayEquals(gentle, RoadGradePlanner.enforceGradeCap(
                gentle, null, null, null, GradeCap.Mode.TIGHT), "正好在 tight 档内的坡不该被压");
    }

    @Test
    void theSummaryLineCarriesEveryFieldInOrder() {
        assertEquals("[GRADE-CAP] mode=normal risePerStations=8 blocks=1 maxGradePct=12.50 "
                        + "cappedStations=3 totalStations=40 cappedPct=7.50",
                GradeCap.summary(GradeCap.Mode.NORMAL, 3, 40));
        assertEquals("[GRADE-CAP] mode=tight risePerStations=10 blocks=1 maxGradePct=10.00 "
                        + "cappedStations=0 totalStations=0 cappedPct=0.00",
                GradeCap.summary(GradeCap.Mode.TIGHT, 0, 0));
        assertEquals("[GRADE-CAP] mode=loose risePerStations=7 blocks=1 maxGradePct=14.29 "
                        + "cappedStations=40 totalStations=40 cappedPct=100.00",
                GradeCap.summary(GradeCap.Mode.LOOSE, 40, 40));
    }

    /**
     * 生产路径（{@code Road.Road()} 的唯一剖面入口）必须真的吃这个档位：
     * 同一个 1 格/站的地形，tight 出 10 站一级台阶、loose 出 7 站一级台阶，两者不相等。
     */
    @Test
    void theProductionProfilePathFollowsTheRuntimeSwitch() {
        int stations = 60;
        int[] terrain = new int[stations];
        for (int i = 0; i < stations; i++) terrain[i] = 64 + i;

        System.setProperty(PROPERTY, "tight");
        int[] tight = RoadGradePlanner.stabilizeSurfaceProfile(
                terrain, terrain, terrain.clone(), new boolean[stations], true, 1);
        System.setProperty(PROPERTY, "loose");
        int[] loose = RoadGradePlanner.stabilizeSurfaceProfile(
                terrain, terrain, terrain.clone(), new boolean[stations], true, 1);

        assertEquals(stations, tight.length, "剖面长度必须原样");
        for (int start = 0; start + 10 < stations; start++) {
            assertTrue(tight[start + 10] - tight[start] <= 1,
                    "tight 档：落盘剖面任意 10 站窗口最多抬 1 格，窗口 " + start);
        }
        for (int start = 0; start + 7 < stations; start++) {
            assertTrue(loose[start + 7] - loose[start] <= 1,
                    "loose 档：落盘剖面任意 7 站窗口最多抬 1 格，窗口 " + start);
        }
        assertTrue(!java.util.Arrays.equals(tight, loose),
                "运行时切档必须真的改变落盘剖面（tight 与 loose 不许完全相同）");
    }
}
