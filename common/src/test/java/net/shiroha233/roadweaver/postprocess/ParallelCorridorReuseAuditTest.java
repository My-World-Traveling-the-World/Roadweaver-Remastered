package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;

/** Model-level audit for long parallel corridors at the snap threshold. */
public final class ParallelCorridorReuseAuditTest {
    private ParallelCorridorReuseAuditTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RoadData primary = line(80, 0, 1L, 2L);
        RoadData offsetEight = line(80, 8, 3L, 4L);
        RoadData offsetTwelve = line(80, 12, 5L, 6L);
        RoadData offsetTwenty = line(80, 20, 7L, 8L);

        RoadData mergedEight = JunctionConnector.connectSecondaryToPrimary(primary, offsetEight);
        require(hasReuse(mergedEight), "8-block parallel corridor was not reused");
        require(JunctionTrimOps.networkReuseStationCount(mergedEight) >= 50,
                "8-block parallel corridor fell back to a short endpoint snap");

        RoadData mergedTwelve = JunctionConnector.connectSecondaryToPrimary(primary, offsetTwelve);
        require(hasReuse(mergedTwelve), "12-block parallel corridor was not reused");
        require(JunctionTrimOps.networkReuseStationCount(mergedTwelve) >= 50,
                "12-block parallel corridor fell back to a short endpoint snap");

        RoadData protectedGap = lineWithGap(80, 8, 9L, 10L, 28, 40);
        RoadData mergedGap = JunctionConnector.connectSecondaryToPrimary(primary, protectedGap);
        require(hasReuse(mergedGap), "parallel corridor with a protected building gap was not reused");

        RoadData rejectedTwenty = JunctionConnector.connectSecondaryToPrimary(primary, offsetTwenty);
        require(!hasReuse(rejectedTwenty), "20-block independent corridor was incorrectly reused");
        System.out.println("ParallelCorridorReuseAuditTest: PASS");
    }

    private static RoadData line(int count, int zOffset, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos center = new BlockPos(i * 2, 64, zOffset);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(5, 0, List.of(), List.of(), segments, List.of(),
                heights, ownerA, ownerB);
    }

    private static RoadData lineWithGap(int count, int zOffset, long ownerA, long ownerB,
                                        int gapStart, int gapEnd) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos center = new BlockPos(i * 2, 64, zOffset);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        BlockPos gapStartPos = segments.get(gapStart).middlePos();
        BlockPos gapEndPos = segments.get(gapEnd).middlePos();
        List<net.shiroha233.roadweaver.core.model.RoadSpan> spans = List.of(
                new net.shiroha233.roadweaver.core.model.RoadSpan(
                        gapStartPos, gapEndPos, SpanType.PROTECTED_GAP));
        return new RoadData(5, 0, List.of(), List.of(), segments, spans,
                heights, ownerA, ownerB);
    }

    private static boolean hasReuse(RoadData road) {
        return hasSpan(road, SpanType.NETWORK_REUSE);
    }

    private static boolean hasSpan(RoadData road, SpanType type) {
        return road != null && road.spans().stream()
                .anyMatch(span -> span != null && span.type() == type);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
