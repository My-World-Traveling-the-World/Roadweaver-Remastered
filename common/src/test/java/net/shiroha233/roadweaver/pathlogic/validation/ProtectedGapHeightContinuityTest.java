package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protected-gap height continuity (audit follow-up: invalid-height-step after
 * gap retention). Drives {@code FinalRoadStructureValidator.smoothProtectedGapApproaches}
 * on synthetic profiles: a 40-block delta across a gap must be pulled into one
 * continuous prefer-cut ramp, an already-continuous gap must be untouched, and
 * a descent that cannot fit its ramp window must keep the original profile
 * (handing the route to the topology fallback instead of a cliff).
 */
final class ProtectedGapHeightContinuityTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadData gapRoad(int n, int lowY, int highY, int gapStart, int gapEnd) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int y = i <= gapEnd ? lowY : highY;
            BlockPos center = new BlockPos(i, y, 0);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(y);
        }
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(segments.get(gapStart).middlePos(),
                segments.get(gapEnd).middlePos(), SpanType.PROTECTED_GAP));
        return new RoadData(4, 1, List.of(), List.of(), segments, spans, heights,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    /** Same adjacent-pair rule as RoadDataValidator: gap-adjacent pairs exempt. */
    private static int ordinarySteps(List<Integer> targetY, int gapStart, int gapEnd) {
        int steps = 0;
        for (int i = 1; i < targetY.size(); i++) {
            boolean gapSide = (i >= gapStart && i <= gapEnd) || (i - 1 >= gapStart && i - 1 <= gapEnd);
            if (!gapSide && Math.abs(targetY.get(i) - targetY.get(i - 1)) > 1) steps++;
        }
        return steps;
    }

    @Test
    void fortyBlockDeltaIsPulledIntoOneContinuousRamp() {
        RoadData road = gapRoad(60, 64, 104, 25, 27);
        RoadData smoothed = FinalRoadStructureValidator.smoothProtectedGapApproaches(road);
        assertNotSame(road, smoothed, "the 40-block delta must be smoothed");
        List<Integer> targetY = smoothed.targetY();
        // Both pavement edges sit on the low side; the high side descends onto
        // it instead of towering over the gap.
        assertEquals(targetY.get(24), targetY.get(28), "gap edges must be level");
        assertEquals(64, targetY.get(24), "edges seat onto the lower side");
        assertEquals(0, ordinarySteps(targetY, 25, 27),
                "no adjacent ordinary pair may exceed one block");
        assertTrue(RoadDataValidator.validateFinalRoad(smoothed).valid(),
                "smoothed road must still validate");
        // Prefer-cut: the far end of the high side was lowered into the ramp.
        assertTrue(targetY.get(59) < 104, "high side must come down, not keep its cliff");
    }

    @Test
    void alreadyContinuousGapIsUntouched() {
        RoadData road = gapRoad(40, 64, 64, 20, 22);
        RoadData smoothed = FinalRoadStructureValidator.smoothProtectedGapApproaches(road);
        assertSame(road, smoothed, "no delta means no rewrite");
    }

    @Test
    void descentTooLargeForItsWindowKeepsTheOriginalProfile() {
        // 60-block delta but only a 48-station window: the ramp cannot absorb
        // it, so the original profile is kept and the caller's failure path
        // (topology fallback) decides the route.
        RoadData road = gapRoad(120, 64, 124, 25, 27);
        RoadData smoothed = FinalRoadStructureValidator.smoothProtectedGapApproaches(road);
        assertSame(road, smoothed, "unflattenable descent must not be half-smoothed");
        assertEquals(road.targetY(), smoothed.targetY());
    }
}
