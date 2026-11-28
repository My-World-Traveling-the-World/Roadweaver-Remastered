package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;

/** Deterministic model checks for junction anchors and water fallbacks. */
public final class RoadTopologyWaterSimulationTest {
    private RoadTopologyWaterSimulationTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        testCrossJunctionAnchor();
        testShortCrossing();
        testLongBridge();
        testTunnelRoofAtRiverbed();
        testWaterProtectionGap();
        testFourthSmallHeightStepBecomesGap();
        System.out.println("RoadTopologyWaterSimulationTest: PASS");
    }

    private static void testCrossJunctionAnchor() {
        List<BlockPos> centers = List.of(
                p(-2, 0), p(-1, 0), p(0, 0), p(1, 0), p(2, 0),
                p(1, 0), p(0, 0), p(0, -1), p(0, -2));
        RoadData normalized = RoadDataValidator.normalizeDuplicateJunctions(road(centers, List.of()));
        require(normalized.spans().stream().anyMatch(s -> s.type() == SpanType.JUNCTION_ANCHOR
                        && s.start().getX() == 0 && s.start().getZ() == 0),
                "repeated center station did not become a junction anchor");
        require(RoadDataValidator.validateFinalRoad(normalized).valid(),
                "cross-junction topology was rejected after anchor normalization");
    }

    private static void testShortCrossing() {
        RoadData crossing = road(line(5), List.of(new RoadSpan(p(1, 0), p(3, 0),
                SpanType.ROAD_CROSSING)));
        require(!FinalRoadWaterValidator.validate(crossing, 2,
                        RoadTopologyWaterSimulationTest::riverAtX2).safe(),
                "short crossing label alone must not waive a submerged deck");
        crossing = new RoadData(crossing.width(), crossing.roadType(), crossing.materials(), crossing.slabMaterials(),
                crossing.roadSegmentList(), crossing.spans(), List.of(66, 67, 67, 67, 66), 1L, 2L);
        require(FinalRoadWaterValidator.validate(crossing, 2,
                        RoadTopologyWaterSimulationTest::riverAtX2).safe(),
                "short direct crossing was rejected");
    }

    private static void testLongBridge() {
        RoadData bridge = road(line(7), List.of(new RoadSpan(p(1, 0), p(5, 0),
                SpanType.BRIDGE)));
        require(FinalRoadWaterValidator.validate(bridge, 2,
                        RoadTopologyWaterSimulationTest::riverAtX2).safe(),
                "long bridge span was rejected");
    }

    private static void testTunnelRoofAtRiverbed() {
        RoadData tunnel = road(line(5), List.of(new RoadSpan(p(1, 0), p(3, 0),
                SpanType.TUNNEL)));
        require(FinalRoadWaterValidator.validate(tunnel, 5,
                        (x, z) -> x >= 1 && x <= 3
                                ? FinalRoadWaterValidator.WaterColumn.wet(70, 69, "simulation")
                                : FinalRoadWaterValidator.WaterColumn.dry()).safe(),
                "tunnel roof touching riverbed was rejected");
    }

    private static void testWaterProtectionGap() {
        RoadData ordinary = road(line(5), List.of());
        FinalRoadWaterValidator.ValidationResult failed = FinalRoadWaterValidator.validate(
                ordinary, 2, RoadTopologyWaterSimulationTest::riverAtX2);
        require(!failed.safe() && "ordinary_construction_in_water".equals(failed.reason()),
                "ordinary construction in a river did not fail before fallback");
        RoadData gapped = FinalRoadWaterValidator.retainProtectedGapAt(
                ordinary, failed.collision().segmentIndex());
        require(gapped != null && gapped.hasProtectedGap(),
                "water collision did not produce a protected gap");
        require(FinalRoadWaterValidator.validate(gapped, 2,
                        RoadTopologyWaterSimulationTest::riverAtX2).safe(),
                "protected water gap still rejected the remaining road");
    }

    private static void testFourthSmallHeightStepBecomesGap() {
        List<BlockPos> centers = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            int y = (x == 2 || x == 4 || x == 6 || x == 8) ? 66 : 64;
            centers.add(new BlockPos(x, y, 0));
        }
        RoadData repaired = RoadDataValidator.repairFinalHeightSteps(road(centers, List.of()));
        require(RoadDataValidator.validateFinalRoad(repaired).valid(),
                "fourth small height step was not repaired or gapped");
    }

    private static FinalRoadWaterValidator.WaterColumn riverAtX2(int x, int z) {
        return x == 2
                ? FinalRoadWaterValidator.WaterColumn.wet(65, 60, "simulation")
                : FinalRoadWaterValidator.WaterColumn.dry();
    }

    private static RoadData road(List<BlockPos> centers, List<RoadSpan> spans) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (BlockPos center : centers) {
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(center.getY());
        }
        return new RoadData(3, 0, List.of(), List.of(), segments, spans, heights, 1L, 2L);
    }

    private static List<BlockPos> line(int count) {
        List<BlockPos> result = new ArrayList<>(count);
        for (int x = 0; x < count; x++) result.add(p(x, 0));
        return result;
    }

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
