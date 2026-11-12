package net.shiroha233.roadweaver.planning.path;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.8.369 (T1b) offline verification of the planned-corridor match.
 *
 * <p>A real save recorded 11 reuse ranges across 9 connections and kept
 * <b>none</b> of them ({@code ranges=9 kept=0}). 0.8.368 replaced the impossible
 * exact-coordinate lookup with a geometric match, and that run split the
 * failures into {@code noPrimaryPath=4} and {@code noOverlap=5}: the primary's
 * cache entry had usually been discarded by the time the secondary asked, and
 * where it survived the 12-block reach found nothing because the planned path is
 * recorded before four geometry passes and a boundary trim reshape the corridor.</p>
 *
 * <p>This test exercises {@code PlannedPathCache.sharedStations}, the matcher
 * behind both fixes, and pins the two properties that decide whether a shared
 * corridor is recognised:</p>
 * <ol>
 *   <li>a corridor displaced further than the snap reach is still recognised —
 *       this is the {@code noOverlap} case the wide-tolerance fallback exists
 *       for, and it fails without that fallback;</li>
 *   <li>the fallback cannot invent a match: a road running well away from the
 *       planned corridor, or crossing it at a different height, still yields no
 *       run, so no pavement is skipped that the planner never shared.</li>
 * </ol>
 */
public final class PlannedReuseMatchTest {
    /** Snap reach used by the strict pass. */
    private static final int SNAP_REACH = 12;
    /** Length of the synthetic planned corridor. */
    private static final int LENGTH = 120;

    private PlannedReuseMatchTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<BlockPos> planned = corridor(LENGTH, 0, 64);

        // --- A corridor inside the snap reach ------------------------------
        List<RoadSegmentPlacement> near = segments(corridor(LENGTH, 8, 64));
        boolean[] nearShared = PlannedPathCache.sharedStations(planned, near);
        if (runLength(nearShared) < LENGTH - 1) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(20, nearShared.length); i++) {
                head.append(nearShared[i] ? '1' : '0');
            }
            System.out.printf("diagnostic: plannedSize=%d segmentSize=%d maskHead=%s%n",
                    planned.size(), near.size(), head);
        }
        require(runLength(nearShared) >= LENGTH - 1,
                "a corridor 8 blocks away must match the whole planned path, matched run="
                        + runLength(nearShared));

        // --- The noOverlap case: displaced beyond the snap reach -----------
        // The planned path is recorded before the geometry passes and the
        // boundary trim reshape the final corridor, so the two can end up
        // further apart than SNAP_REACH. Without the wide fallback this returns
        // no run at all, which is exactly what the save showed 5 times.
        List<RoadSegmentPlacement> displaced =
                segments(corridor(LENGTH, SNAP_REACH * 2, 64));
        boolean[] displacedShared = PlannedPathCache.sharedStations(planned, displaced);
        int displacedRun = runLength(displacedShared);
        require(displacedRun >= LENGTH - 1,
                "a corridor " + (SNAP_REACH * 2) + " blocks away (beyond the " + SNAP_REACH
                        + "-block snap reach) must still match through the wide fallback, matched run="
                        + displacedRun);

        // --- The fallback must not invent matches --------------------------
        // Far outside even the wide reach: no shared pavement.
        List<RoadSegmentPlacement> far =
                segments(corridor(LENGTH, SNAP_REACH * 8, 64));
        boolean[] farShared = PlannedPathCache.sharedStations(planned, far);
        require(runLength(farShared) < 3,
                "a corridor " + (SNAP_REACH * 8) + " blocks away must not match, matched run="
                        + runLength(farShared));

        // Grade-separated: same XZ, 20 blocks lower. A flyover is not a shared
        // surface corridor and must not have its pavement skipped.
        List<RoadSegmentPlacement> underpass =
                segments(corridor(LENGTH, 0, 64 - 20));
        boolean[] underpassShared = PlannedPathCache.sharedStations(planned, underpass);
        require(runLength(underpassShared) < 3,
                "a grade-separated crossing 20 blocks lower must not match, matched run="
                        + runLength(underpassShared));

        System.out.printf("planned reuse match: near=%d  displaced(%d blocks)=%d  far=%d  underpass=%d  (stations=%d)%n",
                runLength(nearShared), SNAP_REACH * 2, displacedRun,
                runLength(farShared), runLength(underpassShared), LENGTH);
        System.out.println("planned reuse match test OK");
    }

    /** A straight corridor of {@code count} stations 2 blocks apart. */
    private static List<BlockPos> corridor(int count, int zOffset, int y) {
        List<BlockPos> path = new ArrayList<>(count);
        for (int i = 0; i < count; i++) path.add(new BlockPos(i * 2, y, zOffset));
        return path;
    }

    private static List<RoadSegmentPlacement> segments(List<BlockPos> positions) {
        List<RoadSegmentPlacement> segments = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            segments.add(new RoadSegmentPlacement(position, List.of(position)));
        }
        return segments;
    }

    private static int runLength(boolean[] mask) {
        int best = 0;
        int run = 0;
        for (boolean value : mask) {
            run = value ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return best;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
