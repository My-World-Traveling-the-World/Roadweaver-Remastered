package net.shiroha233.roadweaver.pathlogic.bridge;

import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 0.9.066（B2）：相邻 BRIDGE 跨度甲板连续性（两段桥之间的台阶）。
 */
public class BridgeSpanContinuityTest {

    @Test
    void thirdBridgeInNewGroupNeverInheritsDistantHighDeck() {
        // 两桥不能暴露累计状态泄漏，第三桥才会误继承前一水域的100格甲板。
        int[] deck = {100, 70, 60};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck,
                new SpanType[]{SpanType.BRIDGE, SpanType.BRIDGE, SpanType.BRIDGE},
                new int[]{2, 2000, 2010}, new int[]{5, 2005, 2015});
        assertArrayEquals(new int[]{100, 70, 70}, deck);
    }

    @Test
    void independentGroupIsPreservedWithUnsortedFordBetweenItsBridges() {
        // 跨度原序不能决定分组，浅滩的999也不能污染桥面或清掉同组桥高。
        int[] deck = {60, 100, 999, 70};
        var types = new SpanType[]{SpanType.BRIDGE, SpanType.BRIDGE, SpanType.ROAD_CROSSING, SpanType.BRIDGE};
        int[] starts = {2010, 2, 2007, 2000}, ends = {2015, 5, 2009, 2005};
        var originalTypes = types.clone(); var originalStarts = starts.clone(); var originalEnds = ends.clone();
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{70, 100, 999, 70}, deck);
        assertArrayEquals(originalTypes, types);
        assertArrayEquals(originalStarts, starts);
        assertArrayEquals(originalEnds, ends);
    }

    @Test
    void gapThresholdStartsNewGroupAndKeepsItsOwnMaximum() {
        // 25格断组，恰好24格仍连续；后继较高桥成为本组新的高度基准。
        int[] deck = {90, 70, 60, 80, 50};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck,
                new SpanType[]{SpanType.BRIDGE, SpanType.BRIDGE, SpanType.BRIDGE, SpanType.BRIDGE, SpanType.BRIDGE},
                new int[]{0, 35, 64, 94, 124}, new int[]{10, 40, 70, 100, 130});
        assertArrayEquals(new int[]{90, 70, 70, 80, 80}, deck);
    }

    @Test
    void adjacentBridgeWithinGapLiftsLowerDeck() {
        int[] deck = {71, 69};
        SpanType[] types = {SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = {0, 14};
        int[] ends = {10, 20};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{71, 71}, deck);
    }

    @Test
    void alreadyMonotonicIsUntouched() {
        int[] deck = {62, 73};
        SpanType[] types = {SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = {0, 14};
        int[] ends = {10, 20};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{62, 73}, deck);
    }

    @Test
    void wideGapIsIndependent() {
        int[] deck = {71, 69};
        SpanType[] types = {SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = {0, 50};
        int[] ends = {10, 60};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{71, 69}, deck);
    }

    @Test
    void unsortedInputIsSortedInternally() {
        int[] deck = {69, 71};
        SpanType[] types = {SpanType.BRIDGE, SpanType.BRIDGE};
        int[] starts = {14, 0};
        int[] ends = {20, 10};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{71, 71}, deck);
    }

    @Test
    void nonBridgeSpansIgnored() {
        int[] deck = {71, 69};
        SpanType[] types = {SpanType.BRIDGE, SpanType.ROAD_CROSSING};
        int[] starts = {0, 14};
        int[] ends = {10, 20};
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deck, types, starts, ends);
        assertArrayEquals(new int[]{71, 69}, deck);
    }

    @Test
    void nullsAreSafe() {
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(null, null, null, null);
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(new int[0], new SpanType[0], new int[0], new int[0]);
    }
}
