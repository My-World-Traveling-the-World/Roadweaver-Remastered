package net.shiroha233.roadweaver.pathlogic.bridge;

import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 0.9.066（B2）：相邻 BRIDGE 跨度甲板连续性（两段桥之间的台阶）。
 */
public class BridgeSpanContinuityTest {

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
