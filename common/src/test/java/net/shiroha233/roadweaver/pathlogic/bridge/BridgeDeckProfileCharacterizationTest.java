package net.shiroha233.roadweaver.pathlogic.bridge;

import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BridgeDeckProfile} 的特征测试（此前零覆盖）。
 *
 * <p>这个类决定「桥面高度」与「引道怎么爬上去」，是 0.9.037 那个真 bug 的所在地：
 * {@code applyMask} 曾经读**已被上一次调用改过**的 heights 当引道基准，
 * 同一数组应用两次会让引道每站再偏移 1 格（实测 70→69→68），桥头被一点点啃出台阶。
 * 所以下面专门有一条「幂等性」断言。</p>
 */
class BridgeDeckProfileCharacterizationTest {

    @Test
    void minimumDeckUsesWaterHeightWhenWaterIsKnown() {
        assertEquals(72, BridgeDeckProfile.minimumDeckY(70, 60, 61, 2));
    }

    @Test
    void minimumDeckFallsBackToBankHeightWithoutWater() {
        // highestWaterY == Integer.MIN_VALUE 表示没有水面采样
        assertEquals(63, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 60, 61, 2));
        assertEquals(63, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 61, 60, 2));
    }

    @Test
    void minimumDeckClampsClearanceToOne() {
        assertEquals(71, BridgeDeckProfile.minimumDeckY(70, 60, 60, 0));
        assertEquals(71, BridgeDeckProfile.minimumDeckY(70, 60, 60, -5));
    }

    @Test
    void applyMaskSetsEveryBridgeStationToTheDeckHeight() {
        int[] heights = new int[]{66, 66, 66, 66, 66, 66, 66, 66, 66, 66};
        boolean[] bridge = new boolean[heights.length];
        Arrays.fill(bridge, 3, 7, true);      // 站 3..6 是桥
        int deck = 72;
        BridgeDeckProfile.applyMask(heights, bridge, deck, 2);
        for (int i = 3; i <= 6; i++) {
            assertEquals(deck, heights[i], "桥面站必须等于甲板高度 index=" + i);
        }
    }

    /**
     * <b>实测结论（写测试时才确认，与直觉相反）：{@code applyMask} 只有「桥面站」幂等。</b>
     *
     * <p>引道站<b>不幂等</b>：引道公式的基准是 {@code original[left]} / {@code original[right]}
     * —— 也就是<b>本次调用前</b>的剖面。第二次调用时那两个位置已经存着上一次算出的引道值，
     * 于是引道会朝甲板方向再爬约 1 格（66→68→69→70… 逐次逼近 deckY）。</p>
     *
     * <p>这是 0.9.037 那个修复的<b>残留面</b>：clone 只堵住了「读到已被改写的桥面站」，
     * 没堵住「读到已被改写的引道站」。这里把它钉成已知行为 —— 若将来有人把
     * {@code applyMask} 改成严格幂等，这条测试会红，那时应同步确认调用方语义。</p>
     */
    @Test
    void applyMaskIsIdempotentOnBridgeStationsButNotOnRamps() {
        int[] heights = new int[]{66, 66, 66, 66, 66, 66, 66, 66, 66, 66};
        boolean[] bridge = new boolean[heights.length];
        Arrays.fill(bridge, 3, 7, true);
        int deck = 72;
        BridgeDeckProfile.applyMask(heights, bridge, deck, 2);
        int[] afterFirst = heights.clone();
        BridgeDeckProfile.applyMask(heights, bridge, deck, 2);

        // 桥面站：严格幂等
        for (int i = 3; i <= 6; i++) {
            assertEquals(deck, afterFirst[i], "首次应用后桥面站应为甲板高度 index=" + i);
            assertEquals(deck, heights[i], "重复应用后桥面站仍应为甲板高度 index=" + i);
        }
        // 引道站：第二次应用会更靠近甲板（不幂等），但必须单调收敛且不越过甲板
        boolean rampMoved = false;
        for (int i = 0; i < heights.length; i++) {
            if (bridge[i]) continue;
            assertTrue(heights[i] <= deck,
                    "引道不得高于甲板 index=" + i + " value=" + heights[i]);
            if (heights[i] != afterFirst[i]) rampMoved = true;
        }
        assertTrue(rampMoved,
                "引道站重复应用会朝甲板爬升 —— 这是已知行为，若不再发生说明语义变了");
    }

    @Test
    void applyMaskLeavesFarStationsUntouched() {
        int[] heights = new int[20];
        Arrays.fill(heights, 66);
        boolean[] bridge = new boolean[heights.length];
        Arrays.fill(bridge, 8, 12, true);
        BridgeDeckProfile.applyMask(heights, bridge, 72, 2);   // 引道 2 站
        assertEquals(66, heights[0], "远处的岸上站不得被改动");
        assertEquals(66, heights[19], "远处的岸上站不得被改动");
    }

    @Test
    void applyMaskIgnoresMismatchedArguments() {
        int[] heights = new int[]{66, 66, 66};
        int[] before = heights.clone();
        BridgeDeckProfile.applyMask(heights, new boolean[5], 72, 2);
        assertArrayEquals(before, heights, "掩码长度不符时必须原样返回");
        BridgeDeckProfile.applyMask(null, new boolean[3], 72, 2);
        BridgeDeckProfile.applyMask(heights, null, 72, 2);
    }

    @Test
    void adjacentBridgesNeverStepDown() {
        // 两段紧邻的桥：后一段甲板更低时必须被抬到前一段的高度
        int[] deck = new int[]{80, 74};
        SpanType[] types = new SpanType[]{SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = new int[]{2, 6};
        int[] ends = new int[]{5, 9};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertEquals(80, deck[1], "紧邻的后段甲板不得低于前段（消除桥间台阶）");
    }

    @Test
    void distantBridgesAreNotForcedToMatch() {
        int[] deck = new int[]{80, 74};
        SpanType[] types = new SpanType[]{SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = new int[]{2, 2000};
        int[] ends = new int[]{5, 2005};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertEquals(74, deck[1], "相距很远的桥不该被强行拉平");
    }

    /**
     * <b>实测结论：中间夹一个非 BRIDGE 跨度，不会阻断两座桥的连续性比较。</b>
     *
     * <p>判据只看「本次 BRIDGE 的 start」与「上一个 BRIDGE 的 end」之差是否
     * {@code <= BRIDGE_SPAN_CONTINUITY_GAP}；非 BRIDGE 跨度只是在循环里被
     * {@code continue} 跳过，既不更新 {@code prevDeck}/{@code prevEnd}，也不打断比较。
     * 所以 {@code ROAD_CROSSING} 夹在中间时，后一座桥仍会被抬到前一座的高度。</p>
     *
     * <p>这个语义值得记住：跨度的「类型差异」不影响甲板连续性，只有「距离」影响。</p>
     */
    @Test
    void nonBridgeSpansDoNotBreakAdjacencyForContinuity() {
        int[] deck = new int[]{80, 40, 74};
        SpanType[] types = new SpanType[]{SpanType.BRIDGE, SpanType.ROAD_CROSSING, SpanType.BRIDGE};
        int[] starts = new int[]{2, 5, 8};
        int[] ends = new int[]{4, 7, 10};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        // start(8) - prevEnd(4) = 4 <= gap ⇒ 仍在「相邻」判据内，后桥被抬平
        assertEquals(80, deck[2],
                "夹了非桥跨度但距离仍够近时，后一座桥会被抬到前一座高度（已知行为）");
        assertEquals(40, deck[1], "非 BRIDGE 跨度自身的甲板值不得被改写");
    }

    @Test
    void findStableBankRangeStaysInsideTheArray() {
        int[] terrain = new int[40];
        Arrays.fill(terrain, 70);
        int[] range = BridgeDeckProfile.findStableBankRange(terrain, 15, 25, 60);
        assertTrue(range[0] >= 0 && range[0] < terrain.length, "左界必须落在数组内");
        assertTrue(range[1] >= 0 && range[1] < terrain.length, "右界必须落在数组内");
        assertTrue(range[0] <= 15 && range[1] >= 25,
                "稳定岸必须至少覆盖水面区间，实际 [" + range[0] + "," + range[1] + "]");
    }

    @Test
    void findStableBankRangeHandlesDegenerateInput() {
        assertArrayEquals(new int[]{0, 0},
                BridgeDeckProfile.findStableBankRange(null, 0, 0, 0));
        assertArrayEquals(new int[]{0, 0},
                BridgeDeckProfile.findStableBankRange(new int[0], 0, 0, 0));
    }
}
