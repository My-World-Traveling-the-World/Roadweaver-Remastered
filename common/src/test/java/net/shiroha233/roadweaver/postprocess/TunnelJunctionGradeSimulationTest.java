package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlanCompiler;

import java.util.ArrayList;
import java.util.List;

/** Pure profile simulation for a high tunnel joining a lower tunnel. */
public final class TunnelJunctionGradeSimulationTest {
    private TunnelJunctionGradeSimulationTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<RoadSegmentPlacement> primarySegments = segments(25, 100);
        List<Integer> primaryY = heights(25, 100);
        RoadData primary = new RoadData(5, 0, List.of(), List.of(), primarySegments,
                List.of(new RoadSpan(primarySegments.get(0).middlePos(),
                        primarySegments.get(24).middlePos(), SpanType.TUNNEL)),
                primaryY, 1L, 2L);

        List<RoadSegmentPlacement> connectedSegments = segments(25, 100);
        List<Integer> connectedY = heights(25, 100);
        for (int i = 7; i <= 16; i++) connectedY.set(i, 96);
        RoadData connected = new RoadData(5, 0, List.of(), List.of(), connectedSegments,
                List.of(
                        new RoadSpan(connectedSegments.get(7).middlePos(),
                                connectedSegments.get(16).middlePos(), SpanType.NETWORK_REUSE),
                        new RoadSpan(connectedSegments.get(11).middlePos(),
                                connectedSegments.get(12).middlePos(), SpanType.TUNNEL_JUNCTION)),
                connectedY, 1L, 3L);

        RoadData lowered = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(primary, connected);
        require(lowered != primary, "high tunnel must receive an owner replacement");
        require(lowered.targetY().get(11) == 96, "junction must use the lower tunnel grade");
        require(lowered.targetY().get(6) == 97 && lowered.targetY().get(5) == 98,
                "left transition must descend one block per station");
        require(lowered.targetY().get(17) == 97 && lowered.targetY().get(18) == 98,
                "right transition must descend one block per station");
        for (int i = 1; i < lowered.targetY().size(); i++) {
            require(Math.abs(lowered.targetY().get(i) - lowered.targetY().get(i - 1)) <= 1,
                    "tunnel profile contains a height step at " + i);
        }
        RoadData trunk = horizontalTunnel(-20, 20, 100, 1L, 2L);
        testLowBranchTJunction(trunk);
        testLowBranchCrossJunction(trunk);
        testExpandedGradeTJunction(trunk, 92, 8);
        testExpandedGradeTJunction(trunk, 85, 15);
        testSurfaceToTunnelEndpointJunction();
        System.out.println("TunnelJunctionGradeSimulationTest: PASS");
    }

    private static void testSurfaceToTunnelEndpointJunction() {
        RoadData surface = horizontalSurface(-20, 20, 100, 11L, 12L);
        RoadData tunnelBranch = verticalBranch(-20, 0, 96, 13L, 11L);
        RoadData connected = JunctionConnector.connectSecondaryToPrimary(surface, tunnelBranch);
        require(connected != null, "surface-to-tunnel endpoint junction was rejected");
        require(hasSpan(connected, SpanType.TUNNEL_JUNCTION),
                "surface-to-tunnel endpoint lost TUNNEL_JUNCTION metadata");
        require(connected.targetY().get(connected.targetY().size() - 1) == 100,
                "tunnel endpoint was not raised to the surface portal grade");
    }

    private static void testExpandedGradeTJunction(RoadData primary, int branchY, int expectedDelta) {
        RoadData branch = verticalBranch(-20, 0, branchY, 30L + expectedDelta, 1L);
        RoadData connected = JunctionConnector.connectSecondaryToPrimary(primary, branch);
        require(connected != null, "expanded high/low T junction was rejected for delta " + expectedDelta);
        require(hasSpan(connected, SpanType.TUNNEL_JUNCTION),
                "expanded T junction lost TUNNEL_JUNCTION metadata for delta " + expectedDelta);
        RoadData lowered = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(primary, connected);
        require(lowestNearCenter(lowered) == branchY,
                "expanded T junction did not lower trunk for delta " + expectedDelta);
        assertLowBranchRemainsLow(connected, "expanded T junction delta " + expectedDelta, branchY);
        for (int i = 1; i < lowered.targetY().size(); i++) {
            require(Math.abs(lowered.targetY().get(i) - lowered.targetY().get(i - 1)) <= 1,
                    "expanded tunnel profile contains a height step at " + i
                            + " for delta " + expectedDelta);
        }
    }

    private static void testLowBranchTJunction(RoadData primary) {
        RoadData branch = verticalBranch(-20, 0, 96, 3L, 1L);
        RoadData connected = JunctionConnector.connectSecondaryToPrimary(primary, branch);
        require(connected != null, "high/low T junction was rejected");
        require(hasSpan(connected, SpanType.TUNNEL_JUNCTION),
                "T junction lost TUNNEL_JUNCTION metadata");
        RoadData loweredPrimary = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(primary, connected);
        require(lowestNearCenter(loweredPrimary) == 96,
                "T junction did not lower the high trunk at the crossing");
        assertLowBranchRemainsLow(connected, "T junction");
        assertNoDuplicateCenters(connected, "T junction");
    }

    private static void testLowBranchCrossJunction(RoadData primary) {
        RoadData north = verticalBranch(-20, 0, 96, 3L, 1L);
        RoadData first = JunctionConnector.connectSecondaryToPrimary(primary, north);
        require(first != null, "first branch of high/low cross junction was rejected");
        RoadData loweredPrimary = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(primary, first);
        require(lowestNearCenter(loweredPrimary) == 96,
                "first cross branch did not lower the high trunk at the crossing");

        RoadData south = verticalBranch(20, 0, 96, 4L, 1L);
        RoadData second = JunctionConnector.connectSecondaryToPrimary(loweredPrimary, south);
        require(second != null, "second branch of high/low cross junction was rejected");
        require(hasSpan(first, SpanType.TUNNEL_JUNCTION)
                        && hasSpan(second, SpanType.TUNNEL_JUNCTION),
                "cross junction lost TUNNEL_JUNCTION metadata");
        assertNoDuplicateCenters(first, "cross north branch");
        assertNoDuplicateCenters(second, "cross south branch");
        assertLowBranchRemainsLow(first, "cross north branch");
        assertLowBranchRemainsLow(second, "cross south branch");
        assertNoDuplicateConstruction(loweredPrimary, first, second);
    }

    private static RoadData verticalBranch(int startZ, int endZ, int y, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        int step = startZ <= endZ ? 1 : -1;
        for (int z = startZ; ; z += step) {
            BlockPos pos = new BlockPos(0, y, z);
            segments.add(new RoadSegmentPlacement(pos, List.of(pos)));
            targetY.add(y);
            if (z == endZ) break;
        }
        return new RoadData(5, 0, List.of(), List.of(), segments,
                List.of(new RoadSpan(segments.get(0).middlePos(),
                        segments.get(segments.size() - 1).middlePos(), SpanType.TUNNEL)),
                targetY, ownerA, ownerB);
    }

    private static RoadData horizontalTunnel(int startX, int endX, int y, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        int step = startX <= endX ? 1 : -1;
        for (int x = startX; ; x += step) {
            BlockPos pos = new BlockPos(x, y, 0);
            segments.add(new RoadSegmentPlacement(pos, List.of(pos)));
            targetY.add(y);
            if (x == endX) break;
        }
        return new RoadData(5, 0, List.of(), List.of(), segments,
                List.of(new RoadSpan(segments.get(0).middlePos(),
                        segments.get(segments.size() - 1).middlePos(), SpanType.TUNNEL)),
                targetY, ownerA, ownerB);
    }

    private static RoadData horizontalSurface(int startX, int endX, int y, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        int step = startX <= endX ? 1 : -1;
        for (int x = startX; ; x += step) {
            BlockPos pos = new BlockPos(x, y, 0);
            segments.add(new RoadSegmentPlacement(pos, List.of(pos)));
            targetY.add(y);
            if (x == endX) break;
        }
        return new RoadData(5, 0, List.of(), List.of(), segments,
                List.of(), targetY, ownerA, ownerB);
    }

    private static boolean hasSpan(RoadData road, SpanType type) {
        return road.spans().stream().anyMatch(span -> span != null && span.type() == type);
    }

    private static void assertNoDuplicateCenters(RoadData road, String label) {
        java.util.Set<Long> centers = new java.util.HashSet<>();
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            BlockPos pos = segment.middlePos();
            require(centers.add((((long) pos.getX()) << 32) ^ (pos.getZ() & 0xFFFFFFFFL)),
                    label + " contains duplicate physical stations");
        }
    }

    private static void assertNoDuplicateConstruction(RoadData... roads) {
        RoadChunkPlan plan = RoadChunkPlanCompiler.compile(
                new ChunkPos(-2, -2), List.of(roads), true, 0, 1L);
        java.util.Map<Long, Integer> physicalOwners = new java.util.HashMap<>();
        for (var slice : plan.slices()) {
            for (int offset = 0; offset < slice.segmentCount(); offset++) {
                int index = slice.segmentIndexAt(offset);
                if (slice.isTunnelJunction(index)) continue;
                BlockPos pos = slice.road().roadSegmentList().get(index).middlePos();
                long key = (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
                physicalOwners.merge(key, 1, Integer::sum);
            }
        }
        require(physicalOwners.values().stream().noneMatch(count -> count > 1),
                "cross junction has duplicate non-junction physical construction");
    }

    private static int lowestNearCenter(RoadData road) {
        int lowest = Integer.MAX_VALUE;
        for (int i = 15; i <= 25 && i < road.targetY().size(); i++) {
            lowest = Math.min(lowest, road.targetY().get(i));
        }
        return lowest;
    }

    private static void assertLowBranchRemainsLow(RoadData road, String label) {
        assertLowBranchRemainsLow(road, label, 96);
    }

    private static void assertLowBranchRemainsLow(RoadData road, String label, int expectedY) {
        int limit = Math.min(18, road.targetY().size());
        for (int i = 0; i < limit; i++) {
            require(road.targetY().get(i) == expectedY,
                    label + " raised the low branch at station " + i);
        }
    }

    private static List<RoadSegmentPlacement> segments(int count, int y) {
        List<RoadSegmentPlacement> result = new ArrayList<>();
        for (int x = 0; x < count; x++) {
            BlockPos pos = new BlockPos(x, y, 0);
            result.add(new RoadSegmentPlacement(pos, List.of(pos)));
        }
        return result;
    }

    private static List<Integer> heights(int count, int y) {
        return new ArrayList<>(java.util.Collections.nCopies(count, y));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
