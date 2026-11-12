package net.shiroha233.roadweaver.pathlogic.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;

/**
 * Geometry contracts of the road deck and the tunnel bore.
 *
 * <p>Every existing surface test feeds an axis-aligned route, which is why the diagonal cases below were
 * never caught: a tunnel's lateral offsets step by whole cells, and a diagonal tangent's normal is not a
 * unit axis vector.
 */
class RoadDeckAndTunnelGeometryTest {

    @Test
    void aDiagonalTunnelBoreKeepsItsLateralOffsetsAtOneProjectionUnitPerStep() {
        // 45-degree centre line: the projection onto the normal is p = -x + z.
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1), new BlockPos(2, 64, 2));
        assertLateralSteps(centers, 1);
    }

    @Test
    void anAxisAlignedTunnelBoreKeepsItsLateralOffsetsAtOneProjectionUnitPerStep() {
        // Along +x: the normal is +z, so p = z.
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        assertLateralSteps(centers, 0);
    }

    /**
     * Asserts the bore, its clearance columns and its wall columns sit at the intended lateral distances.
     *
     * <p>The bug this pins: the wall offset used to be applied along `(nx, nz)` without normalising, so on a
     * diagonal one cell of offset moved two projection units. The wall landed at |p| = 2 * (WALL_OFFSET), the
     * clearance column at |p| = 2 * SIDE_CLEARANCE, and every column in between stayed natural rock - a bore
     * that looks three wide instead of five, with the lining buried behind a ridge.
     */
    private static void assertLateralSteps(List<BlockPos> centers, int axis) {
        BlockPos middle = centers.get(centers.size() / 2);
        RoadSegmentPlacement segment = new RoadSegmentPlacement(middle, List.of(middle));
        int[] profile = new int[centers.size()];
        java.util.Arrays.fill(profile, 64);
        RoadTunnelBuilder.PlannedSection section =
                RoadTunnelBuilder.plannedSection(segment, centers.size() / 2, centers, profile, 0);
        assertNotNull(section, "a three-station centre line must produce a section");

        int nx = axis == 0 ? 0 : -1;
        int nz = axis == 0 ? 1 : 1;
        Set<Integer> interior = new HashSet<>();
        for (BlockPos pos : section.interior()) {
            interior.add(Math.abs(pos.getX() * nx + pos.getZ() * nz));
        }
        Set<Integer> walls = new HashSet<>();
        for (BlockPos pos : section.walls()) {
            walls.add(Math.abs(pos.getX() * nx + pos.getZ() * nz));
        }
        assertEquals(Set.of(0, RoadTunnelBuilder.SIDE_CLEARANCE), interior,
                "clearance columns must sit at exactly one projection unit");
        assertEquals(Set.of(RoadTunnelBuilder.WALL_OFFSET), walls,
                "wall columns must sit at exactly WALL_OFFSET projection units");
    }

    @Test
    void anUnderseaTunnelAlwaysLeavesRockBetweenItsRoofAndTheWater() {
        for (int oceanFloorWg : new int[] {40, 62, 63, 100}) {
            int roadY = net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner.underseaRoadY(oceanFloorWg);
            int roofY = roadY + RoadTunnelBuilder.roofOffset();
            int topmostSolid = oceanFloorWg - 1;
            assertEquals(RoadTunnelBuilder.ROCKS_ABOVE_ROOF, topmostSolid - roofY,
                    "the roof must keep ROCKS_ABOVE_ROOF solid blocks under the first water block");
        }
    }

    @Test
    void aFlatCrossSectionTakesTheCentreColumnNotTheMedian() {
        // Width columns project onto slightly different points of the centre line, so on a slope the two
        // sides differ by a block; the median used to alternate between them and the plate came out as a
        // 0,1,0,1 staircase.
        int[] profile = {64, 65, 64};
        int[] terrain = {64, 65, 64};
        RoadConstructionSafety.resolveRoadCrossSection(profile, terrain, 1);
        assertEquals(65, profile[0]);
        assertEquals(65, profile[2]);
        // Without a centre index the old median behaviour keeps working.
        int[] median = {64, 65, 64};
        RoadConstructionSafety.resolveRoadCrossSection(median, terrain);
        assertEquals(64, median[0]);
    }
}
