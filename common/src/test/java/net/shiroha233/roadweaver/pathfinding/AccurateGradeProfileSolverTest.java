package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the optimal accurate-grade profile solver used at the planning stage.
 *
 * <p>The solver replaces "follow the exact terrain and repair cliffs later" with
 * a layered-DAG shortest path over the per-station offset, so the profile is
 * globally optimal under the configured slope limit and the cut/fill budgets.
 * These tests assert the invariants the pipeline depends on rather than exact
 * heights.</p>
 */
final class AccurateGradeProfileSolverTest {

    private static final int STEP2 = 1;

    @Test
    void flatTerrainIsLeftAlone() {
        int[] terrain = new int[40];
        java.util.Arrays.fill(terrain, 64);
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, null, STEP2);
        assertArrayEqualsMessage(terrain, profile, "flat terrain must stay flat");
    }

    @Test
    void gentleClimbIsFollowedExactly() {
        int[] terrain = new int[60];
        for (int i = 0; i < terrain.length; i++) {
            terrain[i] = 64 + i / 2;
        }
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, null, STEP2);
        assertArrayEqualsMessage(terrain, profile,
                "a climb inside the grade limit must not need any cut or fill");
    }

    @Test
    void shortSteepClimbIsCutInsteadOfFollowed() {
        int[] terrain = new int[60];
        for (int i = 0; i < terrain.length; i++) {
            if (i < 20) {
                terrain[i] = 64;
            } else if (i < 26) {
                terrain[i] = 64 + (i - 19) * 2;
            } else {
                terrain[i] = 76;
            }
        }
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, null, STEP2);
        assertTrue(profile[25] < terrain[25],
                "a short 2-blocks-per-station ramp must be cut, not followed");
        for (int i = 0; i < profile.length; i++) {
            assertTrue(terrain[i] - profile[i] <= 16,
                    "cut must stay inside the accurate cut budget at station " + i);
            assertTrue(profile[i] - terrain[i] <= 6,
                    "fill must stay inside the accurate fill budget at station " + i);
        }
    }

    @Test
    void shortSteepDescentIsFilledInsteadOfFollowed() {
        int[] terrain = new int[60];
        for (int i = 0; i < terrain.length; i++) {
            if (i < 20) {
                terrain[i] = 100;
            } else if (i < 26) {
                terrain[i] = 100 - (i - 19) * 2;
            } else {
                terrain[i] = 88;
            }
        }
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, null, STEP2);
        assertTrue(profile[25] > terrain[25],
                "a short steep descent must be filled, not followed");
        for (int i = 0; i < profile.length; i++) {
            assertTrue(profile[i] - terrain[i] <= 6,
                    "fill must stay inside the accurate fill budget at station " + i);
            assertTrue(terrain[i] - profile[i] <= 16,
                    "cut must stay inside the accurate cut budget at station " + i);
        }
    }

    @Test
    void endpointsStayAnchoredWhenTheGradeAllowsIt() {
        int[] terrain = new int[60];
        for (int i = 0; i < terrain.length; i++) {
            terrain[i] = 64 + i / 2;
        }
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, null, STEP2);
        assertEquals(terrain[0], profile[0], "the profile must stay anchored at the start");
        assertEquals(terrain[terrain.length - 1], profile[profile.length - 1],
                "the profile must stay anchored at the end");
    }

    @Test
    void protectedStationsKeepTerrainAndSplitSegments() {
        int[] terrain = new int[40];
        for (int i = 0; i < terrain.length; i++) {
            terrain[i] = 64 + i * 2;
        }
        boolean[] mask = new boolean[terrain.length];
        mask[20] = true;
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(terrain, mask, STEP2);
        assertEquals(terrain[20], profile[20],
                "a protected (water/tunnel) station must keep its exact height");
    }

    @Test
    void tunnelTargetProducesALegalPortalRamp() {
        int[] target = new int[60];
        java.util.Arrays.fill(target, 120);
        boolean[] flat = new boolean[target.length];
        for (int i = 20; i < 40; i++) {
            target[i] = 108;
            flat[i] = true;
        }
        int[] profile = RoadGradePlanner.solveOptimalGradeProfile(target, null, STEP2, flat);
        assertEquals(108, profile[30], "the tunnel core must sit on the bore chord");
        for (int i = 1; i < profile.length; i++) {
            assertTrue(Math.abs(profile[i] - profile[i - 1]) <= 1,
                    "the portal must be a legal ramp, not a step, at " + i);
        }
    }

    @Test
    void disabledSlopeLimitReturnsTerrain() {
        int[] terrain = {64, 70, 76, 82, 88, 94};
        int[] profile = RoadGradePlanner.planAccurateGradeProfile(terrain, null, STEP2, false);
        assertArrayEqualsMessage(terrain, profile, "disabled slope limit must keep the exact terrain");
    }

    @Test
    void planningEntryPointAppliesTheSolver() {
        int[] terrain = new int[60];
        for (int i = 0; i < terrain.length; i++) {
            if (i < 20) {
                terrain[i] = 64;
            } else if (i < 26) {
                terrain[i] = 64 + (i - 19) * 2;
            } else {
                terrain[i] = 76;
            }
        }
        int[] profile = RoadGradePlanner.planAccurateGradeProfile(terrain, null, STEP2, true);
        assertTrue(profile[25] < terrain[25],
                "the planning entry point must return the grade-limited profile");
    }

    private static void assertArrayEqualsMessage(int[] expected, int[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], message + " at " + i);
        }
    }
}
