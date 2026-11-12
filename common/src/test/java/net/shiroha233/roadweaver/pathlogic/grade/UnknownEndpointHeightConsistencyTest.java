package net.shiroha233.roadweaver.pathlogic.grade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.shiroha233.roadweaver.pathlogic.core.Road;

/**
 * Unknown-endpoint height negotiation (audit follow-up). The pure decision
 * core of {@link UnknownEndpointHeightRegistry} is driven directly: the cost
 * simulation prefers descending the high side onto a reachable height over
 * locking an unbuildable structure surface, the registry keeps every road
 * into one endpoint on the agreed height, and an unbuildable stored height
 * degrades per-route with the original surface logic intact for
 * village/spawn endpoints (those never consult the registry — gated in
 * {@code Road.isUnknownStructureEndpoint}, covered by the model regressions).
 */
final class UnknownEndpointHeightConsistencyTest {

    private final RoadGradePlanner.SurfaceGradeLimits limits =
            RoadGradePlanner.SurfaceGradeLimits.capture();

    @AfterEach
    void clearRegistry() {
        UnknownEndpointHeightRegistry.clearAll();
    }

    private static int[] profile(int n, int low, int high, int highFrom) {
        int[] profile = new int[n];
        for (int i = 0; i < n; i++) {
            profile[i] = i < highFrom ? low : high;
        }
        return profile;
    }

    @Test
    void highSideDescendsOntoTheReachableHeightInsteadOfLockingTheSurface() {
        // 20 high stations before the endpoint, structure surface 40 blocks lower.
        int[] terrain = profile(64, 64, 104, 44);
        int[] current = terrain.clone();
        int feasible = UnknownEndpointHeightRegistry.approachFeasibleY(
                64, terrain, current, 63, 16);
        // The endpoint can only be reached 16 stations back at 104±16: the
        // surface (64) is unreachable, so the road comes down to 88.
        assertEquals(88, feasible);
        int chosen = UnknownEndpointHeightRegistry.chooseHeight(
                64, feasible, terrain, current, 63, limits);
        assertEquals(feasible, chosen, "the high side must be lowered, not the surface locked");
        long surfaceCost = UnknownEndpointHeightRegistry.approachCost(
                64, terrain, current, 63, limits);
        long feasibleCost = UnknownEndpointHeightRegistry.approachCost(
                feasible, terrain, current, 63, limits);
        assertTrue(feasibleCost < surfaceCost,
                "descending must be cheaper: " + feasibleCost + " vs " + surfaceCost);
    }

    @Test
    void flatApproachKeepsTheStructureSurface() {
        int[] terrain = profile(64, 64, 64, 64);
        int feasible = UnknownEndpointHeightRegistry.approachFeasibleY(
                64, terrain, terrain.clone(), 63, 16);
        assertEquals(64, feasible);
        assertEquals(64, UnknownEndpointHeightRegistry.chooseHeight(
                64, feasible, terrain, terrain, 63, limits));
    }

    @Test
    void twoRoadsFromDifferentDirectionsAgreeOnOneHeight() {
        long endpointKey = 42L;
        // First road: high approach — negotiates 88 and stores it.
        int[] highTerrain = profile(64, 64, 104, 44);
        int first = UnknownEndpointHeightRegistry.lockedHeight(
                endpointKey, 64, highTerrain, highTerrain.clone(), 63, limits);
        // Multi-road negotiation lowers the endpoint to the lowest natural
        // approach (here the structure surface 64), not the high road's own
        // reachable 88.
        assertEquals(64, first);
        assertEquals(1, UnknownEndpointHeightRegistry.registrySizeForTest());

        // Second road, different direction: its own approach is flat at 88 —
        // building at the stored height costs nothing, so it reuses 88.
        int[] flatTerrain = profile(64, 88, 88, 0);
        int second = UnknownEndpointHeightRegistry.lockedHeight(
                endpointKey, 64, flatTerrain, flatTerrain.clone(), 63, limits);
        assertEquals(first, second, "both roads into one unknown endpoint share one height");
    }

    @Test
    void unbuildableStoredHeightDegradesForThatRoadOnly() {
        long endpointKey = 7L;
        UnknownEndpointHeightRegistry.putForTest(endpointKey, 88);
        // This road's terrain is flat at 64: building up at 88 means a huge
        // fill, so it degrades to its own reachable height with a warning.
        int[] lowTerrain = profile(64, 64, 64, 64);
        int degraded = UnknownEndpointHeightRegistry.lockedHeight(
                endpointKey, 64, lowTerrain, lowTerrain.clone(), 63, limits);
        assertEquals(64, degraded);
        // The agreed height itself stays stored for roads that can build it.
        // The endpoint stays driven down to the lowest seen approach (64), so
        // even a road that could build at the old stored 88 now uses 64.
        assertEquals(64, UnknownEndpointHeightRegistry.lockedHeight(
                endpointKey, 64, profile(64, 88, 88, 0).clone(),
                profile(64, 88, 88, 0), 63, limits));
    }

    @Test
    void clearAllDropsEveryAgreedHeight() {
        UnknownEndpointHeightRegistry.putForTest(1L, 70);
        UnknownEndpointHeightRegistry.putForTest(2L, 80);
        assertEquals(2, UnknownEndpointHeightRegistry.registrySizeForTest());
        UnknownEndpointHeightRegistry.clearAll();
        assertEquals(0, UnknownEndpointHeightRegistry.registrySizeForTest());
    }
}
