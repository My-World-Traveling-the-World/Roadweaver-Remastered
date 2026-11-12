package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.8.369 (T2b) offline verification of the parallel-corridor merge on a
 * <b>bending</b> corridor, driven through the real
 * {@link RoadSnapService#connectSecondaryToPrimary} entry point.
 *
 * <p>Diagnostic logs from a real save recorded 45 rejections of an already
 * produced merge candidate, 23 of them {@code not-parallel}, including runs
 * that were plainly one corridor ({@code secondaryRange=[13..228]} over 216
 * stations). Two defects let that happen:</p>
 *
 * <ol>
 *   <li>the heading came from a single midpoint sample, and
 *       {@code segmentDirection} measures over ±2 stations — a genuinely
 *       bending mountain corridor could disagree there even while the two roads
 *       shared the whole stretch;</li>
 *   <li>one station outside {@code OVERLAP_MERGE_MAX_DISTANCE} vetoed the whole
 *       run, the same "one bad station drops everything" mistake already fixed
 *       for engineered spans.</li>
 * </ol>
 *
 * <p>The existing {@code parallelCorridorReuseAudit} covers straight corridors
 * only, so it never exercised either path. This test adds the bending case and
 * asserts the relaxation still refuses genuinely separate roads.</p>
 */
public final class ParallelCorridorHeadingTest {
    private ParallelCorridorHeadingTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // A long corridor that turns as it goes: 216 stations, 6 blocks per
        // station, bending from due +X to about 60 degrees off it. The tracking
        // road follows the same bend 6 blocks to one side — inside the merge
        // distance the whole way, which is the shape the straight-corridor audit
        // never covered.
        RoadData primary = bendingCorridor(216, 0.0D, 11L, 12L);
        RoadData tracking = bendingCorridor(216, 6.0D, 13L, 14L);

        // Control: reproduce the straight-corridor case the existing audit
        // already covers, so a null result here means the synthetic geometry —
        // not the bending — is what the service refuses.
        RoadData straightPrimary = straightLine(216, 0.0D, 41L, 42L);
        RoadData straightTracking = straightLine(216, 6.0D, 43L, 44L);
        RoadData straightMerged =
                JunctionConnector.connectSecondaryToPrimary(straightPrimary, straightTracking);
        System.out.printf("control: straight merged=%s reused=%d%n",
                straightMerged == null ? "null" : "ok",
                straightMerged == null ? 0 : RoadSnapService.networkReuseStationCount(straightMerged));

        RoadData merged = JunctionConnector.connectSecondaryToPrimary(primary, tracking);
        require(merged != null, "a road tracking the same bending corridor was dropped entirely");
        require(hasReuse(merged),
                "a bending shared corridor produced no NETWORK_REUSE span");
        require(RoadSnapService.networkReuseStationCount(merged) >= 100,
                "a bending shared corridor reused only "
                        + RoadSnapService.networkReuseStationCount(merged)
                        + " stations instead of the shared stretch");

        // --- The relaxation must not glue separate roads together -----------
        // A road running perpendicular through the corridor crosses it at one
        // point; every sample disagrees on heading, so it must stay separate.
        RoadData crossing = crossingRoad(216, 60, 21L, 22L);
        RoadData crossed = JunctionConnector.connectSecondaryToPrimary(primary, crossing);
        require(crossed == null || RoadSnapService.networkReuseStationCount(crossed) < 100,
                "a perpendicular crossing was merged as a shared corridor ("
                        + (crossed == null ? 0 : RoadSnapService.networkReuseStationCount(crossed))
                        + " stations)");

        // A parallel road far enough away is still independent, as the straight
        // audit already asserts; repeated here on the bending corridor so the
        // widened outlier budget cannot adopt it.
        RoadData distant = bendingCorridor(216, 24.0D, 31L, 32L);
        RoadData distantMerged = JunctionConnector.connectSecondaryToPrimary(primary, distant);
        require(distantMerged == null || RoadSnapService.networkReuseStationCount(distantMerged) < 100,
                "a corridor 24 blocks away was merged as shared ("
                        + (distantMerged == null ? 0
                        : RoadSnapService.networkReuseStationCount(distantMerged)) + " stations)");

        System.out.printf("parallel corridor heading test: stations=%d  reusedStations=%d  "
                        + "crossingReused=%d  distantReused=%d%n",
                216, RoadSnapService.networkReuseStationCount(merged),
                crossed == null ? 0 : RoadSnapService.networkReuseStationCount(crossed),
                distantMerged == null ? 0 : RoadSnapService.networkReuseStationCount(distantMerged));
        System.out.println("parallel corridor heading test OK");
    }

    /** A straight road of {@code count} stations at {@code zOffset}. */
    private static RoadData straightLine(int count, double zOffset, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos center = new BlockPos(i * 2, 64, (int) Math.round(zOffset));
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(5, 0, List.of(), List.of(), segments, List.of(),
                heights, ownerA, ownerB);
    }

    /**
     * A corridor of {@code count} stations that starts along +X and turns by
     * {@code turnDegrees} per station, offset {@code zOffset} blocks in Z.
     */
    private static RoadData bendingCorridor(int count, double zOffset, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        double x = 0.0D;
        double z = 0.0D;
        double turn = Math.toRadians(0.3D);
        for (int i = 0; i < count; i++) {
            // The offset is applied perpendicular to the local heading, so the
            // two corridors stay the requested distance apart while both bend.
            double heading = turn * i;
            double offsetX = -Math.sin(heading) * zOffset;
            double offsetZ = Math.cos(heading) * zOffset;
            BlockPos center = new BlockPos((int) Math.round(x + offsetX), 64,
                    (int) Math.round(z + offsetZ));
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
            x += 2.0D * Math.cos(heading);
            z += 2.0D * Math.sin(heading);
        }
        return new RoadData(5, 0, List.of(), List.of(), segments, List.of(),
                heights, ownerA, ownerB);
    }

    /** A road crossing the bending corridor at station {@code at}. */
    private static RoadData crossingRoad(int count, int at, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        // Walk the corridor to find the crossing point, then run perpendicular.
        double x = 0.0D;
        double z = 0.0D;
        double turn = Math.toRadians(0.3D);
        for (int i = 0; i < at; i++) {
            x += 6.0D * Math.cos(turn * i);
            z += 6.0D * Math.sin(turn * i);
        }
        double heading = turn * at;
        double px = -Math.sin(heading);
        double pz = Math.cos(heading);
        double half = (count / 2.0D) * 2.0D;
        for (int i = 0; i < count; i++) {
            double t = -half + i * 2.0D;
            BlockPos center = new BlockPos((int) Math.round(x + px * t), 64,
                    (int) Math.round(z + pz * t));
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(5, 0, List.of(), List.of(), segments, List.of(),
                heights, ownerA, ownerB);
    }

    private static boolean hasReuse(RoadData road) {
        if (road == null || road.spans() == null) return false;
        for (RoadSpan span : road.spans()) {
            if (span != null && span.type() == SpanType.NETWORK_REUSE) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
