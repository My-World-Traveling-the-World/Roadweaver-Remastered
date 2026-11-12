package net.shiroha233.roadweaver.planning.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the height-aware coarse search trigger: it runs only when enabled and
 * either the route has an unbuildable transition or the profile is
 * TUNNEL_FIRST on a mountain-heavy route. Coarse planning passes
 * {@code tunnelFirst = (mode == TUNNEL_FIRST)}.
 */
final class TerrainProfileModeSearchBehaviorTest {

    @Test
    void disabledNeverTriggers() {
        for (boolean transition : new boolean[]{true, false}) {
            for (boolean tunnelFirst : new boolean[]{true, false}) {
                for (boolean mountain : new boolean[]{true, false}) {
                    assertFalse(RawPathSearch.wantsHeightAware(
                            false, transition, tunnelFirst, mountain));
                }
            }
        }
    }

    @Test
    void unbuildableTransitionTriggersOnEveryMode() {
        assertTrue(RawPathSearch.wantsHeightAware(true, true, false, false));
        assertTrue(RawPathSearch.wantsHeightAware(true, true, true, false));
        assertTrue(RawPathSearch.wantsHeightAware(true, true, false, true));
        assertTrue(RawPathSearch.wantsHeightAware(true, true, true, true));
    }

    @Test
    void tunnelFirstMountainRoutesTriggerWithoutTransition() {
        assertTrue(RawPathSearch.wantsHeightAware(true, false, true, true));
    }

    @Test
    void tunnelFirstWithoutMountainDoesNotTrigger() {
        assertFalse(RawPathSearch.wantsHeightAware(true, false, true, false));
    }

    @Test
    void nonTunnelFirstModesNeedATransition() {
        assertFalse(RawPathSearch.wantsHeightAware(true, false, false, true));
        assertFalse(RawPathSearch.wantsHeightAware(true, false, false, false));
    }
}
