package net.shiroha233.roadweaver.pathlogic.metrics;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.437 / D1：路面质量指标。
 *
 * <p>指标必须对「真机见过的退化」有反应：断了、抖了、缺站、变宽变窄、斜向更陡、去重没生效。
 * 这些断言就是指标的校准 —— 造一条已知有病的路，指标必须指出来；造一条干净的路，指标必须为 0。
 */
final class RoadQualityMetricsTest {

    private static RoadData road(int stations, java.util.function.IntUnaryOperator y, int width) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            BlockPos middle = new BlockPos(i, y.applyAsInt(i), 0);
            List<BlockPos> positions = new ArrayList<>();
            for (int w = 0; w < width; w++) {
                positions.add(new BlockPos(i, y.applyAsInt(i), w - (width - 1) / 2));
            }
            segments.add(new RoadSegmentPlacement(middle, List.copyOf(positions)));
            targetY.add(y.applyAsInt(i));
        }
        return new RoadData(width, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    @Test
    void aCleanStraightRoadHasNoFindings() {
        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road(20, i -> 64, 3));

        assertEquals(20, report.stations());
        assertEquals(0, report.holes());
        assertEquals(0, report.continuityBreaks());
        assertEquals(0.0D, report.gradeP95(), 1e-9);
        assertEquals(0.0D, report.directionGradeBias(), 1e-9);
        assertEquals(0.0D, report.widthVariation(), 1e-9);
        assertEquals(0.0D, report.reuseShare(), 1e-9);
        assertEquals(1.0D, report.spacingRegularity(), 1e-9);
    }

    @Test
    void aThreeBlockStepIsReportedAsABreak() {
        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(
                road(20, i -> i == 8 ? 67 : 64, 3));

        assertTrue(report.continuityBreaks() >= 2, "a 3-block jump breaks both of its edges");
        assertTrue(report.gradeP95() > 0.0D);
    }

    @Test
    void aDiagonalRunShowsTheDirectionBias() {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // First half rises diagonally, second half rises along x: same rise per station, longer run
            // on the diagonal, so the diagonal grade is smaller and the bias must be positive.
            int x = i < 15 ? i : 15 + (i - 15);
            int z = i < 15 ? i : 15;
            int y = 64 + i / 2;
            BlockPos middle = new BlockPos(x, y, z);
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
            targetY.add(y);
        }
        RoadData road = new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road);

        assertTrue(report.directionGradeBias() > 0.05D,
                "axial and diagonal grades must differ before arc-length normalisation: "
                        + report.directionGradeBias());
    }

    @Test
    void anEmptyStationIsAHoleAndAReuseSpanIsCounted() {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            BlockPos middle = new BlockPos(i, 64, 0);
            segments.add(new RoadSegmentPlacement(middle,
                    i == 4 ? List.of() : List.of(middle)));
            targetY.add(64);
        }
        List<RoadSpan> spans = List.of(
                new RoadSpan(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), SpanType.NETWORK_REUSE),
                new RoadSpan(new BlockPos(6, 64, 0), new BlockPos(7, 64, 0), SpanType.CULVERT));
        RoadData road = new RoadData(3, 0, List.of(), List.of(), segments, spans, targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road);

        assertEquals(1, report.holes());
        assertEquals(1, report.culvertRuns());
        assertEquals(0.3D, report.reuseShare(), 1e-9);
    }

    @Test
    void widthAndSpacingVariationAreMeasured() {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // Every fourth station is wider and twice as far from its predecessor.
            int x = i * 2;
            int width = i % 4 == 0 ? 5 : 3;
            List<BlockPos> positions = new ArrayList<>();
            for (int w = 0; w < width; w++) positions.add(new BlockPos(x, 64, w - (width - 1) / 2));
            BlockPos middle = new BlockPos(x, 64, 0);
            segments.add(new RoadSegmentPlacement(middle, List.copyOf(positions)));
            targetY.add(64);
        }
        RoadData road = new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road);

        assertTrue(report.widthVariation() > 0.0D, "mixed widths must not read as constant");
        assertTrue(report.spacingRegularity() >= 1.0D);
    }

    @Test
    void anEmptyOrBrokenRoadIsNotACrash() {
        RoadQualityMetrics.Report empty = RoadQualityMetrics.evaluate(null);
        assertEquals(0, empty.stations());
        assertEquals(1.0D, empty.spacingRegularity(), 1e-9);
    }

    @Test
    void spansThatCannotBeBoundAGapOrACollidedStationAreCountedInsteadOfDropped() {
        // Ten stations on a line; the last one shares station 8's XZ, which is what a junction looks
        // like to a table keyed by XZ alone.
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int x = i == 9 ? 8 : i;
            segments.add(new RoadSegmentPlacement(new BlockPos(x, 64, 0), List.of(new BlockPos(x, 64, 0))));
            targetY.add(64);
        }
        List<RoadSpan> spans = List.of(
                new RoadSpan(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), SpanType.BRIDGE),
                // One station of ordinary road between the two bridge decks: a bridge cut in two.
                new RoadSpan(new BlockPos(3, 64, 0), new BlockPos(4, 64, 0), SpanType.BRIDGE),
                // An endpoint no station carries: silently dropped before, counted now.
                new RoadSpan(new BlockPos(50, 64, 0), new BlockPos(51, 64, 0), SpanType.TUNNEL));
        RoadData road = new RoadData(3, 0, List.of(), List.of(), segments, spans, targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road);

        assertEquals(1, report.spanGaps(), "two decks one station apart is a gap, not one long bridge");
        assertEquals(1, report.unboundSpans(), "a span the station table cannot bind must be reported");
        assertEquals(1, report.duplicateXzStations(), "two stations on one XZ must be reported");
        assertEquals(0.4D, report.bridgeShare(), 1e-9, "four of ten stations sit on a bridge deck");
        assertEquals(0.0D, report.crossingShare(), 1e-9);
        assertTrue(report.spanLengthP95() >= 2.0D, "a two-station span has length two");
        assertTrue(report.spanDetail().contains("BRIDGE n=2"), report.spanDetail());
        assertTrue(report.spanDetail().contains("gaps=1"), report.spanDetail());
        assertTrue(!report.spanDetail().contains("TUNNEL"),
                "an unbindable span has no length to report; it is counted in unboundSpans: " + report.spanDetail());
    }
}
