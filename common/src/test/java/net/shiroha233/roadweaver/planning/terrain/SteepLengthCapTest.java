package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.shiroha233.roadweaver.core.model.ProfileConflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;

/**
 * 0.9.062（①）：长度界「策略感知」。
 *
 * <p><b>为什么要有这一版。</b>0.9.059 把局部修复的长度上界收成
 * {@code max(直连 × 1.6, 原走廊长 × 1.05)}，削掉了那条"弦 1053 格走了 3826 格（detour 3.63）"的
 * 乱绕 —— 这一步是对的。但盘山路本身就是 2~3 倍绕远：真正的之字路在<b>搜索阶段</b>就被这个界剪掉了，
 * A* 只剩两害相权 —— "硬爬吃罚"或者"被剪成 {@code no_path}"。于是路不会盘山，只会斜着横切。</p>
 *
 * <p>本用例钉四件事：①平缓地形 factor 恒为 1.6（与 0.9.061 逐位相同）；②陡地形 factor 放到 2.6；
 * ③{@code detour 2.0} 在缓地必须被拒、在陡地必须被接受（这就是本条改动的全部目的）；
 * ④判据不成立/采样不足/开关关掉时，必须原样回到 1.6。</p>
 */
final class SteepLengthCapTest {

    @Test
    void profileRecoveryUsesTheLockedEndToGiveTheApproachMoreRoom() {
        var line = IntStream.rangeClosed(0, 400).mapToObj(x -> new BlockPos(x, 64, 0)).toList();
        var west = new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT, 0, 200, 100, 80);
        var east = new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT, 400, 200, 100, 80);
        assertEquals(List.of(new ProfileSurfaceRepair.Window(200, 48, 2),
                new ProfileSurfaceRepair.Window(176, 96, 20)), ProfileSurfaceRepair.windows(line, 200, west, 5, 96));
        assertEquals(new ProfileSurfaceRepair.Window(224, 96, 20),
                ProfileSurfaceRepair.windows(line, 200, east, 5, 96).getLast());
    }

    @Test
    void rawSteepColumnGoesToProfileSolverButUnboundedDetourDoesNot() {
        var start = new BlockPos(0, 64, 0);
        var end = new BlockPos(20, 64, 0);
        var original = List.of(start, end);
        int spike = 64 + AccurateGradeRepairPlanner.localWindowRise() + 1;
        var steep = List.of(start, new BlockPos(9, 64, 1), new BlockPos(10, spike, 1),
                new BlockPos(11, 64, 1), end);
        // 这里守的是判据交接：粗坡度拒绝不能代替正式挖填求解的结果。
        assertFalse(RepairAcceptance.isAccepted(original, steep));
        assertTrue(ProfileSurfaceRepair.candidateLengthAllowed(original, steep));
        assertFalse(ProfileSurfaceRepair.candidateLengthAllowed(original,
                List.of(start, new BlockPos(10, 64, 300), end)));
    }

    /** 一条 300 格的直连弦。 */
    private static final double CHORD = 300.0D;

    /** 采样间距（格）。 */
    private static final double SPACING = 4.0D;

    /** 平缓地形：31 个采样点全程 64 格高（总爬升 0，坡度 0）。 */
    private static int[] flatProfile() {
        int[] profile = new int[31];
        Arrays.fill(profile, 64);
        return profile;
    }

    /** 陡地形：同一条弦，每 4 格抬 2 格（12 格窗抬 6 格 = 坡度 0.50，总爬升/弦长 = 0.50）。 */
    private static int[] steepProfile() {
        int[] profile = new int[31];
        for (int i = 0; i < profile.length; i++) {
            profile[i] = 64 + i * 2;
        }
        return profile;
    }

    @Test
    void flatChordKeepsTheSixteenFactor() {
        assertFalse(SearchBudgetPolicy.chordIsSteep(flatProfile(), SPACING),
                "总爬升 0 的弦不可能判陡");
        assertEquals(1.6D, SearchBudgetPolicy.LENGTH_CAP_DETOUR_FACTOR, 1.0E-12D,
                "0.9.059 的常量必须原样留着（它是非陡地形唯一的因子）");
        assertEquals(1.6D, SearchBudgetPolicy.detourFactorFor(false), 1.0E-12D);
        assertEquals(CHORD * 1.6D,
                SearchBudgetPolicy.lengthCapFor(CHORD, CHORD, false), 1.0E-6D);
        // 两参版本（既有回归用的那个）必须与 steep=false 逐位相同
        assertEquals(SearchBudgetPolicy.lengthCapFor(CHORD, CHORD),
                SearchBudgetPolicy.lengthCapFor(CHORD, CHORD, false), 0.0D);
    }

    @Test
    void steepChordRelaxesToTwentySix() {
        assertTrue(SearchBudgetPolicy.chordIsSteep(steepProfile(), SPACING),
                "坡度 0.50 / 总爬升比 0.50 的弦必须判陡");
        assertEquals(2.6D, SearchBudgetPolicy.LENGTH_CAP_DETOUR_FACTOR_STEEP, 1.0E-12D);
        assertEquals(2.6D, SearchBudgetPolicy.detourFactorFor(true), 1.0E-12D);
        assertEquals(CHORD * 2.6D,
                SearchBudgetPolicy.lengthCapFor(CHORD, CHORD, true), 1.0E-6D);
    }

    @Test
    void detourTwoIsRejectedOnFlatTerrainAndAcceptedOnSteep() {
        double detour = CHORD * 2.0D; // 600 格 = detour 2.0
        assertFalse(SearchBudgetPolicy.chordIsSteep(flatProfile(), SPACING));
        assertFalse(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, detour, false),
                "平缓地形必须仍然用 1.6：detour 2.0 要被拒");
        assertTrue(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, detour, true),
                "陡地形放宽到 2.6 之后 detour 2.0 必须被接受 —— 这正是本条改动的目的");
        // 边界：两档各自的上界内侧/外侧
        assertTrue(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, CHORD * 1.6D, false));
        assertFalse(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, CHORD * 1.61D, false));
        assertTrue(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, CHORD * 2.6D, true));
        assertFalse(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, CHORD * 2.61D, true),
                "陡地形也仍然拒绝 2.61 倍（旧界 3.0 判过的乱绕不许回来）");
        // 3.63 倍那条真机记录在两档下都必须被拒
        assertFalse(SearchBudgetPolicy.withinLengthCap(CHORD, CHORD, CHORD * 3.6334D, true));
    }

    @Test
    void noEvidenceMeansSixteen() {
        // 采样点不足
        assertFalse(SearchBudgetPolicy.chordIsSteep(null, SPACING), "null 不判陡");
        assertFalse(SearchBudgetPolicy.chordIsSteep(new int[]{64, 70, 76}, SPACING),
                "少于 STEEP_CHORD_MIN_SAMPLES 个点不许判陡");
        assertFalse(SearchBudgetPolicy.chordIsSteep(steepProfile(), 0.0D), "没有间距不判陡");
        assertFalse(SearchBudgetPolicy.chordIsSteep(steepProfile(), Double.NaN), "NaN 间距不判陡");
        // 判据不成立（非陡）时，任何弦长下两档必须逐位相同
        for (double chord : new double[]{8.0D, 37.5D, 300.0D, 1053.0D, 4096.0D}) {
            assertEquals(SearchBudgetPolicy.lengthCapFor(chord, chord),
                    SearchBudgetPolicy.lengthCapFor(chord, chord, false), 0.0D);
        }
    }

    @Test
    void climbRatioThresholdSitsBeforeTheGeometricBreakingPoint() {
        // 0.20 是推的：loose 档每 7 站抬 1 格 ≈ 14.29%，爬 C 格至少要 7C 格水平长度，
        // 塞进 1.6 × 弦长就是 C ≤ 1.6L/7 = 0.2286L。阈值必须落在拐点之前（更早起放宽）。
        double breakingPoint = 1.6D / 7.0D;
        assertEquals(0.22857142857142856D, breakingPoint, 1.0E-15D);
        assertTrue(SearchBudgetPolicy.STEEP_CHORD_CLIMB_RATIO > 0.0D);
        assertTrue(SearchBudgetPolicy.STEEP_CHORD_CLIMB_RATIO < breakingPoint,
                "阈值必须小于几何拐点 " + breakingPoint);
        // 12.5% 的缓坡（每 8 格抬 1 格）不该判陡
        int[] gentle = new int[31];
        for (int i = 0; i < gentle.length; i++) {
            gentle[i] = 64 + i / 2;
        }
        assertFalse(SearchBudgetPolicy.chordIsSteep(gentle, SPACING),
                "总爬升 12.5% / 单窗坡度 ≤ 0.167 的缓坡不该放宽长度界");
        // 坡度阈值复用盘山重试自己的触发值：0.25
        assertEquals(0.25D, SearchBudgetPolicy.STEEP_CHORD_MAX_GRADE, 1.0E-12D);
    }

    /**
     * 0.9.062（①）：从**真实形状的高度网格**走一遍 {@code chordProfile} ——
     * 这一环是"steep 永远不成立 ⇒ ① 从不生效"最容易出问题的地方：
     * 如果吸附/查表写错，{@code chordProfile} 会恒返回 {@code null}，而 {@code chordIsSteep} 的
     * 纯函数用例仍然全绿。这里直接喂一张合成网格，钉住三条：能采到、能判陡、缺采样会截断。
     */
    @Test
    void heightMapProfileCanActuallyDeclareSteep() {
        // 网格约定与 A* 一致：step=2、origin=(0,0)，key = (x<<32) ^ (z & 0xffffffff)
        final int step = 2;
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(60, 64, 0);
        java.util.Map<Long, Integer> steep = new java.util.HashMap<>();
        java.util.Map<Long, Integer> flat = new java.util.HashMap<>();
        for (int x = 0; x <= 60; x += step) {
            steep.put(gridKey(x, 0), 64 + x / 2);   // 每 2 格抬 1 格 ⇒ 坡度 0.50
            flat.put(gridKey(x, 0), 64);
        }
        int[] steepProfile = SearchBudgetPolicy.chordProfile(steep, start, goal, 0, 0, step);
        int[] flatProfile = SearchBudgetPolicy.chordProfile(flat, start, goal, 0, 0, step);
        assertNotNull(steepProfile, "完整网格必须采得到（否则 steep 永远不成立、① 从不生效）");
        assertNotNull(flatProfile);
        assertEquals(31, steepProfile.length, "60 格 / 步长 2 = 30 段 = 31 个采样点");
        assertEquals(steepProfile.length, flatProfile.length);
        assertEquals(64 + 30, steepProfile[30], "最后一个采样点是终点那一格");

        double spacing = SearchBudgetPolicy.chordSampleSpacing(start, goal, step);
        assertEquals(2.0D, spacing, 1.0E-9D, "采样间距必须等于网格步长");
        assertTrue(SearchBudgetPolicy.chordIsSteep(steepProfile, spacing),
                "每 2 格抬 1 格（50%）必须判陡 ⇒ factor 2.6");
        assertFalse(SearchBudgetPolicy.chordIsSteep(flatProfile, spacing),
                "全程同高必须判不陡 ⇒ factor 1.6");

        // 网格在弦中途断掉 ⇒ 只取有效前缀；前缀不足 5 个点 ⇒ 返回 null（判不出来就回退 1.6）
        // 阈值是 STEEP_CHORD_MIN_SAMPLES = 5：4 个点不够（返回 null），5 个点刚好够（见下一条断言）。
        java.util.Map<Long, Integer> truncated = new java.util.HashMap<>();
        for (int x = 0; x <= 6; x += step) {
            truncated.put(gridKey(x, 0), 64 + x / 2);
        }
        int[] prefix = SearchBudgetPolicy.chordProfile(truncated, start, goal, 0, 0, step);
        assertNull(prefix, "只有 4 个有效点时宁可判不出来，也不许凭半条弦放宽长度界");
        java.util.Map<Long, Integer> five = new java.util.HashMap<>();
        for (int x = 0; x <= 8; x += step) {
            five.put(gridKey(x, 0), 64 + x / 2);
        }
        assertNotNull(SearchBudgetPolicy.chordProfile(five, start, goal, 0, 0, step),
                "正好 MIN_SAMPLES(5) 个点就必须能判（边界不能写成 <= MIN_SAMPLES 以外）");

        java.util.Map<Long, Integer> half = new java.util.HashMap<>();
        for (int x = 0; x <= 20; x += step) {
            half.put(gridKey(x, 0), 64 + x / 2);
        }
        int[] halfProfile = SearchBudgetPolicy.chordProfile(half, start, goal, 0, 0, step);
        assertNotNull(halfProfile);
        assertEquals(11, halfProfile.length, "11 个有效点之后断掉，必须截断而不是补 0");
    }

    /** 与 {@code AccurateGradeRepairPlanner.key(int,int)} 同式。 */
    private static long gridKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Test
    void switchOffRestoresTheOldBehaviour() {
        String key = RoadConstants.STEEP_LENGTH_CAP_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertTrue(SearchBudgetPolicy.steepLengthCapEnabled(), "默认开");
            System.setProperty(key, "true");
            assertTrue(SearchBudgetPolicy.steepLengthCapEnabled());
            System.setProperty(key, "false");
            assertFalse(SearchBudgetPolicy.steepLengthCapEnabled(), "false = 回到 0.9.061 口径");
            System.setProperty(key, "0");
            assertFalse(SearchBudgetPolicy.steepLengthCapEnabled(), "0 = 关");
            System.setProperty(key, "off");
            assertFalse(SearchBudgetPolicy.steepLengthCapEnabled(), "off = 关");
            System.setProperty(key, "");
            assertTrue(SearchBudgetPolicy.steepLengthCapEnabled(), "空串 = 默认开");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
