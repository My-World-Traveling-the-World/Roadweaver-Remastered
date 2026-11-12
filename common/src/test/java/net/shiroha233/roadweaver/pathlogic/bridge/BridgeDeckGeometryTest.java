package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegment;
import net.shiroha233.roadweaver.util.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.037（离线补测）：桥面几何的回归。
 *
 * <p><b>为什么补这一组</b>：{@code BridgeDeckProfile} 与 {@code BridgeSegment} 在 91 个测试类里
 * <b>被 0 个引用</b>，而它们正是这些版本反复动的地方 —— 甲板 Y、±5 格正弦弯、桥台/引道、
 * 落桥站判定（0.9.033 的「陆侧桥口缺甲板」就修在 {@code isLanding} 的下游）。
 * 现在改桥完全是摸黑改。</p>
 *
 * <p>本组只钉**已经被用户拍板、且一旦被改坏玩家立刻能看出来**的契约：</p>
 * <ol>
 *   <li>甲板必须**完全水平**（同一 deckY），引道之外一格都不许动；</li>
 *   <li>{@code applyMask} 必须**幂等** —— 规划期与生成期都可能调用，重复应用不能继续抬高/降低；</li>
 *   <li>{@code applyFixedRange} 的甲板**永不低于** minimumDeckY；</li>
 *   <li>长桥（弦长 ≥ {@value 96} 格）的 ±5 格正弦弯**保留**（用户 2026-11-07 明确「不关 可以留着」）；</li>
 *   <li>{@code isLanding} 只标每段桥运行的**首末站**。</li>
 * </ol>
 */
final class BridgeDeckGeometryTest {

    // ---------------- BridgeDeckProfile ----------------

    @Test
    void deckIsPerfectlyFlatAndOutsideIsUntouched() {
        int[] heights = new int[40];
        java.util.Arrays.fill(heights, 70);
        boolean[] bridge = new boolean[40];
        for (int i = 15; i <= 25; i++) bridge[i] = true;

        BridgeDeckProfile.applyMask(heights, bridge, 63, 0); // rampSegments = 0 ⇒ 完全不碰桥外

        for (int i = 15; i <= 25; i++) {
            assertEquals(63, heights[i], "deck station " + i + " must sit exactly on the deck Y");
        }
        for (int i = 0; i < 15; i++) {
            assertEquals(70, heights[i], "station " + i + " is outside the span and must not move");
        }
        for (int i = 26; i < 40; i++) {
            assertEquals(70, heights[i], "station " + i + " is outside the span and must not move");
        }
    }

    @Test
    void applyMaskIsNotIdempotentAndIsDeadCodeInProduction() {
        // 0.9.037 补测时确认：applyMask / applyRanges / applyStableBankMask **在生产代码里零调用点**
        // （全仓只有 Road.java:1454 调 applyStableBankRanges、:1473 调 applyFixedRange）。
        // 所以这里不断言「幂等」这种生产契约，而是把**真实行为**钉住，防止有人以为它幂等：
        // 引道基准读的是 heights[left]，同一数组再应用一次就再向 deckY 靠一格（70→69→68）。
        //
        // ⇒ 若将来有人把它接进生产，必须先解决幂等性（基准取调用前的剖面），否则同一段
        //    上重复应用会把桥头啃出台阶。这条注释就是那个前置条件。
        int[] once = new int[40];
        java.util.Arrays.fill(once, 70);
        boolean[] bridge = new boolean[40];
        for (int i = 15; i <= 25; i++) bridge[i] = true;
        BridgeDeckProfile.applyMask(once, bridge, 63, 4);

        int[] twice = once.clone();
        BridgeDeckProfile.applyMask(twice, bridge, 63, 4);

        // 桥面本身是幂等的（直接写 deckY）。
        for (int i = 15; i <= 25; i++) {
            assertEquals(63, once[i]);
            assertEquals(63, twice[i]);
        }
        // 引道不幂等：第二次再靠一格。实测 index 11 由 69 变 68。
        assertEquals(69, once[11], "first application lands on 69");
        assertEquals(68, twice[11], "second application drifts one more block toward the deck");
    }

    @Test
    void rampBridgesTheHeightGapMonotonically() {
        int[] heights = new int[40];
        java.util.Arrays.fill(heights, 80);
        boolean[] bridge = new boolean[40];
        for (int i = 15; i <= 25; i++) bridge[i] = true;

        BridgeDeckProfile.applyMask(heights, bridge, 60, 5);

        // 实测语义（写测试时用真实行为校正过一遍）：引道占 left..start-1 **含 left 自己**，
        // 左基准 original[left]=80 只在算 progress 时用，heights[left] 本身会被改。
        // left = 15-5 = 10 → heights[10] = round(80 + (60-80)×1/6) = 77。
        assertEquals(80, heights[9], "station before the ramp window must stay untouched");
        assertEquals(77, heights[10], "the outermost ramp station is itself interpolated");
        for (int i = 11; i <= 15; i++) {
            assertTrue(heights[i] <= heights[i - 1],
                    "ramp must decrease monotonically toward the deck, broke at " + i);
        }
        assertEquals(60, heights[15]);
    }

    @Test
    void degenerateInputNeverMutatesOrThrows() {
        int[] heights = {1, 2, 3};
        BridgeDeckProfile.applyMask(heights, null, 9, 3);
        assertArrayEquals(new int[]{1, 2, 3}, heights);

        BridgeDeckProfile.applyMask(heights, new boolean[]{true}, 9, 3); // 长度不匹配
        assertArrayEquals(new int[]{1, 2, 3}, heights);

        int[] empty = new int[0];
        BridgeDeckProfile.applyMask(empty, new boolean[0], 9, 3);
        BridgeDeckProfile.applyFixedRange(null, 0, 0, 0, 0);
        BridgeDeckProfile.applyRanges(null, List.of(), 0, 0);
    }

    @Test
    void fixedRangeNeverDropsBelowTheMinimumDeck() {
        int[] profile = new int[40];
        java.util.Arrays.fill(profile, 50); // 两岸都远低于 minimumDeck
        BridgeDeckProfile.applyFixedRange(profile, 15, 25, 64, 2);
        for (int i = 15; i <= 25; i++) {
            assertTrue(profile[i] >= 64,
                    "deck must never sit below the requested minimum, broke at " + i);
        }
    }

    @Test
    void fixedRangeIsFlatWhenBothBanksAgree() {
        int[] profile = new int[40];
        java.util.Arrays.fill(profile, 50);
        for (int i = 15; i <= 25; i++) profile[i] = 66;
        BridgeDeckProfile.applyFixedRange(profile, 15, 25, 0, 0);
        for (int i = 15; i <= 25; i++) {
            assertEquals(66, profile[i], "equal banks must produce a dead-flat deck");
        }
    }

    @Test
    void adaptiveRampIsCappedAtThirtyStations() {
        int[] profile = new int[100];
        java.util.Arrays.fill(profile, 60);
        for (int i = 50; i <= 60; i++) profile[i] = 100; // 桥面比岸高 40 ⇒ 岸差 40 > 30
        int[] original = profile.clone();

        BridgeDeckProfile.applyFixedRange(profile, 50, 60, 0, 0);

        int changedLeft = 0;
        for (int i = 0; i < 50; i++) if (profile[i] != original[i]) changedLeft++;
        assertEquals(30, changedLeft, "adaptive ramp must clamp at MAX_ADAPTIVE_RAMP_SEGMENTS = 30");
        assertEquals(60, profile[19], "stations beyond the capped ramp must stay untouched");
    }

    @Test
    void stableBankRangeAlwaysContainsTheWaterRange() {
        // R-04 的真机结论：findStableBankRange 会把水域区间向外扩（最夸张 72 站撑到 106 站），
        // 但**永远不许缩小** —— 缩了就是桥没盖到水上。
        int[] terrain = new int[80];
        java.util.Arrays.fill(terrain, 70);
        for (int i = 30; i <= 40; i++) terrain[i] = 50; // 一段河槽

        int[] stable = BridgeDeckProfile.findStableBankRange(terrain, 30, 40, 64);
        assertTrue(stable[0] <= 30, "stable left bank must not cut into the water range");
        assertTrue(stable[1] >= 40, "stable right bank must not cut into the water range");
    }

    // ---------------- BridgeSegment ----------------

    private static List<RoadSegmentPlacement> straightLine(int count, int stepX, int y) {
        List<RoadSegmentPlacement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos middle = new BlockPos(i * stepX, y, 0);
            out.add(new RoadSegmentPlacement(middle, List.of(middle)));
        }
        return out;
    }

    @Test
    void landingMarksOnlyTheFirstAndLastStationOfEachRun() {
        List<RoadSegmentPlacement> stations = straightLine(30, 4, 64);
        boolean[] bridge = new boolean[30];
        for (int i = 5; i <= 12; i++) bridge[i] = true;
        for (int i = 20; i <= 26; i++) bridge[i] = true;

        BridgeSegment segment = new BridgeSegment(bridge, stations);

        assertTrue(segment.isLanding(5));
        assertTrue(segment.isLanding(12));
        assertTrue(segment.isLanding(20));
        assertTrue(segment.isLanding(26));
        // 中间站不是落桥站 —— 0.9.033 的桥口修复就挂在 isLanding 上，标错会把引道当桥口。
        for (int i = 0; i < 30; i++) {
            if (i == 5 || i == 12 || i == 20 || i == 26) continue;
            assertFalse(segment.isLanding(i), "station " + i + " must not be a landing");
        }
    }

    @Test
    void lineExistsExactlyForBridgeStationsAndIsSharedPerRun() {
        List<RoadSegmentPlacement> stations = straightLine(30, 4, 64);
        boolean[] bridge = new boolean[30];
        for (int i = 5; i <= 12; i++) bridge[i] = true;

        BridgeSegment segment = new BridgeSegment(bridge, stations);

        assertNull(segment.getLine(0), "non-bridge station must have no bridge line");
        assertNull(segment.getLine(13), "non-bridge station must have no bridge line");
        Line first = segment.getLine(5);
        assertNotNull(first);
        for (int i = 6; i <= 12; i++) {
            // 同一段桥必须是同一条曲线；每站各造一条会让桥面逐站错位。
            assertSame(first, segment.getLine(i), "all stations of one run must share one line");
        }
    }

    /** 短桥实测最大侧移（0.9.037 写测试时量的）。来自 {@code Line} 自身拟合，不是正弦弯。 */
    private static final double SHORT_SPAN_MEASURED_LATERAL = 0.5D;
    /** 长桥实测最大侧移：{@code BRIDGE_CURVE_MAX_OFFSET_BLOCKS = 5.0} 是**弯曲参数**，不是峰值。 */
    private static final double LONG_SPAN_MEASURED_LATERAL = 5.5D;

    @Test
    void longSpanKeepsTheApprovedHorizontalBend() {
        // 用户 2026-11-07 拍板：「长桥（弦长 ≥ 96 格）的 ±5 格正弦弯保留，不关」。
        // 这条一直没有测试，等于随时可能被当成 bug 删掉。
        int count = 40;
        List<RoadSegmentPlacement> stations = straightLine(count, 4, 64); // 弦长 (40-1)*4 = 156 ≥ 96
        boolean[] bridge = new boolean[count];
        java.util.Arrays.fill(bridge, true);

        BridgeSegment segment = new BridgeSegment(bridge, stations);
        Line line = segment.getLine(0);
        assertNotNull(line);

        double maxLateral = 0.0D;
        for (double t = 0.0D; t <= 1.0D; t += 0.02D) {
            var point = line.pointAt(t);
            maxLateral = Math.max(maxLateral, Math.abs(point.z)); // 弦在 z=0 上
        }
        assertTrue(maxLateral > 1.0D,
                "long spans must keep the approved horizontal bend, measured maxLateral=" + maxLateral);
        // 实测 5.5 = 参数 5.0 的 1.1 倍（正弦弯叠加 Line 自身拟合）。这里钉**实测值**，
        // 谁把 BRIDGE_CURVE_MAX_OFFSET_BLOCKS 或 Line 的弯法改了都会红。
        assertEquals(LONG_SPAN_MEASURED_LATERAL, maxLateral, 0.05D,
                "long-span peak lateral drifted; parameter is 5.0 but the achieved peak is 5.5");
    }

    @Test
    void shortSpanBendStaysFarBelowTheLongSpanBend() {
        int count = 10;
        List<RoadSegmentPlacement> stations = straightLine(count, 4, 64); // 弦长 36 < 96
        boolean[] bridge = new boolean[count];
        java.util.Arrays.fill(bridge, true);

        Line line = new BridgeSegment(bridge, stations).getLine(0);
        assertNotNull(line);
        double maxLateral = 0.0D;
        for (double t = 0.0D; t <= 1.0D; t += 0.01D) {
            maxLateral = Math.max(maxLateral, Math.abs(line.pointAt(t).z));
        }
        // 短桥不套正弦弯；剩下的 0.5 是 Line 自身拟合残差（实测值）。
        assertEquals(SHORT_SPAN_MEASURED_LATERAL, maxLateral, 0.05D,
                "short spans must not receive the sine bend");
        assertTrue(maxLateral < LONG_SPAN_MEASURED_LATERAL / 2.0D,
                "short-span lateral must stay far below the long-span bend");
    }

    @Test
    void noBridgeMeansNoLineAndNoLanding() {
        List<RoadSegmentPlacement> stations = straightLine(8, 4, 64);
        BridgeSegment segment = new BridgeSegment(new boolean[8], stations);
        for (int i = 0; i < 8; i++) {
            assertNull(segment.getLine(i));
            assertFalse(segment.isLanding(i));
        }
        assertNotSame(stations, segment);
    }
}
