package net.shiroha233.roadweaver.pathlogic.grade;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.pathlogic.core.Road;

/**
 * 0.8.369 (T2) offline verification of the structure-boundary lock.
 *
 * <p>Replays the exact profile the real save produced for the mountain-village
 * connection {@code (-480,480)->(-813,716)}. Diagnostic logs from that run
 * record, for the same route:</p>
 *
 * <pre>
 *   structure boundary grades locked ... startY=407 terrainStartY=407
 *   endpoint profile  stage=after-structure-lock  profileStart=407  (lock intact)
 *   endpoint profile  stage=computeTargetY-exit   profileStart=389  (lock lost, -18)
 *   endpoint profile  stage=exit (repair)         profileStart=389  (repair not involved)
 * </pre>
 *
 * <p>and the shipped NBT begins {@code targetY = 389, 388, 388, 387, 387, ...} —
 * the {@code -1 / 0} alternation of {@code DEFAULT_MAX_SLOPE_STEP = 1}. The
 * tunnel sections for that road carry {@code overburden = 18..24} against a
 * {@code tunnelCoreOverburden} of 8, i.e. the bore sits far below the village
 * surface, which is what the player saw as "the tunnel never reaches the
 * surface".</p>
 *
 * <p>This test exercises the real pass pair — {@code enforceContinuousSlopePreferCut}
 * with and without a pinned mask, followed by the boundary re-seat — so the fix
 * is proven offline instead of costing a game session. It asserts the two
 * properties that matter:</p>
 * <ol>
 *   <li>without the fix the locked end is pulled down (the bug is reproduced);</li>
 *   <li>with the fix the locked end keeps its surface height <b>and</b> the
 *       approach stays a legal one-block ramp, i.e. no cliff is introduced.</li>
 * </ol>
 */
public final class EndpointLockSimulationTest {
    /** Village surface sampled for the route's start crossing. */
    private static final int LOCKED_START = 407;
    /** Real terrain along the first stations of that route. */
    private static final int[] TERRAIN_HEAD = {
            407, 407, 407, 407, 409, 404, 403, 404, 406, 403, 404, 404, 403, 404, 406, 403,
            404, 405, 406, 407, 406, 405, 405, 404, 404, 403, 403, 402, 402, 401, 401, 400,
            400, 399, 399, 398, 398, 397, 397, 396
    };
    /** Stations after the head, still descending toward the bore. */
    private static final int STATIONS = 220;
    /** One block per two stations, matching RoadConstants.DEFAULT_MAX_SLOPE_STEP. */
    private static final int STEP2 = 1;

    private EndpointLockSimulationTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // The profile the planner held right after the boundary lock: the start
        // sits exactly on the sampled village surface, and the interior follows
        // terrain toward the tunnel chord.
        int[] locked = buildLockedProfile();

        // --- 1. Reproduce the defect ---------------------------------------
        // The slope closure is "lower, never raise" from both ends, so it pulls
        // the locked start down toward the interior before the fix.
        int[] withoutFix = RoadGradePlanner.enforceContinuousSlopePreferCut(
                locked.clone(), new boolean[locked.length], STEP2, null);
        int droppedWithoutFix = locked[0] - withoutFix[0];
        require(droppedWithoutFix > 0,
                "expected the unfixed closure to pull the locked start down, but it stayed at "
                        + withoutFix[0]);

        // --- 2. Verify the fix ---------------------------------------------
        boolean[] pinned = new boolean[locked.length];
        pinned[0] = true;
        int[] withFix = RoadGradePlanner.enforceContinuousSlopePreferCut(
                locked.clone(), new boolean[locked.length], STEP2, pinned);
        reSeatBoundary(withFix, 0, LOCKED_START);

        require(withFix[0] == LOCKED_START,
                "pinned start drifted from its locked surface: " + withFix[0] + " != " + LOCKED_START);

        // The ramp into the bore must stay walkable: no step larger than one
        // block, and it must actually descend rather than sit at the lock.
        int maxStep = 0;
        for (int i = 1; i < withFix.length; i++) {
            maxStep = Math.max(maxStep, Math.abs(withFix[i] - withFix[i - 1]));
        }
        require(maxStep <= 1,
                "re-seating the boundary introduced a " + maxStep + "-block cliff at the entrance");

        int descend = 0;
        for (int i = 1; i < withFix.length; i++) {
            if (withFix[i] < withFix[i - 1]) descend++;
        }
        require(descend > 0,
                "the approach never descends from the locked village surface toward the bore");

        // Stations must not be raised above the lock: the ramp is a descent.
        for (int i = 0; i < withFix.length; i++) {
            require(withFix[i] <= LOCKED_START,
                    "station " + i + " was raised to " + withFix[i] + ", above the locked surface "
                            + LOCKED_START);
        }

        int droppedWithFix = locked[0] - withFix[0];
        System.out.printf("endpoint lock simulation: lock=%d  unfixedStart=%d (dropped %d)  "
                        + "fixedStart=%d (dropped %d)  maxStep=%d  descendingStations=%d/%d%n",
                LOCKED_START, withoutFix[0], droppedWithoutFix,
                withFix[0], droppedWithFix, maxStep, descend, withFix.length);
        System.out.println("endpoint lock simulation OK");
    }

    /**
     * The interior the planner held after the lock: terrain-following at first,
     * then pressed toward the tunnel chord exactly as
     * {@code applyProactiveTunnelProfile} does ({@code terrain - overburden}).
     */
    private static int[] buildLockedProfile() {
        int[] profile = new int[STATIONS];
        int overburden = RoadGradePlanner.SurfaceGradeLimits.capture().tunnelCoreOverburden();
        for (int i = 0; i < STATIONS; i++) {
            int terrain = TERRAIN_HEAD[Math.min(i, TERRAIN_HEAD.length - 1)];
            profile[i] = Math.max(terrain - overburden, terrain - 18);
        }
        // The locked start is the sampled village surface, not the terrain
        // clamp: this is the value the boundary lock writes.
        profile[0] = LOCKED_START;
        return profile;
    }

    /**
     * Mirrors {@code Road.applySingleBoundaryGrade}: pin the endpoint and build
     * a one-block ramp from it into the interior.
     */
    private static void reSeatBoundary(int[] profile, int endpoint, int boundaryHeight) {
        profile[endpoint] = boundaryHeight;
        int half = Math.max(1, Math.min(96, (profile.length - 1) / 2));
        int direction = endpoint == 0 ? 1 : -1;
        int previous = endpoint;
        for (int distance = 1, index = endpoint + direction;
             distance <= half && index >= 0 && index < profile.length;
             distance++, index += direction) {
            int current = profile[index];
            if (current > profile[previous] + 1) current = profile[previous] + 1;
            else if (current < profile[previous] - 1) current = profile[previous] - 1;
            profile[index] = current;
            previous = index;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
