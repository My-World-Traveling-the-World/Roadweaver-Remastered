package net.shiroha233.roadweaver.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.shiroha233.roadweaver.api.incoming.GreatWallReadinessApi;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadiness;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The road's gate on the Great Wall now reads a typed contract instead of reflecting into the wall.
 *
 * <h2>Why these tests exist</h2>
 *
 * <p>The old test here pinned how the gate <em>assembled</em> a progress signature out of nine
 * reflected record accessors, including the property that a wall-clock duration must never enter the
 * signature (a signature that moves on its own makes the stall detector dead code, and the gate then
 * only has its absolute cap). That assembly moved to the wall itself
 * ({@code GreatWallReadinessProvider}), so this class pins what is left on this side: the contract's
 * <b>fail-closed</b> semantics.</p>
 *
 * <p><b>Direction matters.</b> Answering "ready" when the probe is broken is the one failure mode
 * that is worse than not gating at all, because it looks gated - the road lays itself over a wall that
 * has not sampled a column. Every test below therefore fails toward {@code false} / {@code null}.</p>
 */
class GreatWallReadinessApiTest {

    @AfterEach
    void clearRegistry() {
        for (String id : GreatWallReadinessRegistry.providers()) {
            GreatWallReadinessRegistry.unregister(id);
        }
    }

    /** Minimal provider; subclasses/instances override what the test needs. */
    private record Fake(String ownerId, boolean ready, boolean throwOnReady,
                        boolean awaitReturn, String signature, boolean throwOnSignature)
            implements GreatWallReadiness {
        static Fake ready(String signature) {
            return new Fake("fake", true, false, true, signature, false);
        }

        static Fake notReady() {
            return new Fake("fake", false, false, false, null, false);
        }

        @Override
        public boolean isReady() {
            if (throwOnReady) throw new IllegalStateException("probe broke");
            return ready;
        }

        @Override
        public boolean awaitReady(long timeoutMillis) {
            if (throwOnReady) throw new IllegalStateException("probe broke");
            return awaitReturn;
        }

        @Override
        public String progressSignature() {
            if (throwOnSignature) throw new IllegalStateException("snapshot broke");
            return signature;
        }
    }

    @Test
    void unlinkedContractAnswersNotReady() {
        assertFalse(GreatWallReadinessApi.isLinked());
        assertFalse(GreatWallReadinessApi.isReady(), "no provider must never read as ready");
        assertFalse(GreatWallReadinessApi.awaitReady(0L));
        assertFalse(GreatWallReadinessApi.awaitReady(50L));
        assertNull(GreatWallReadinessApi.progressSignature());
        assertTrue(GreatWallReadinessApi.describe().contains("linked=false"));
    }

    @Test
    void registeredProviderIsQueriedThroughTheContract() {
        GreatWallReadinessRegistry.register(Fake.ready("a|b|c"));
        assertTrue(GreatWallReadinessApi.isLinked());
        assertTrue(GreatWallReadinessApi.isReady());
        assertTrue(GreatWallReadinessApi.awaitReady(0L));
        assertTrue(GreatWallReadinessApi.awaitReady(10L));
        assertEquals("a|b|c", GreatWallReadinessApi.progressSignature());
    }

    @Test
    void throwingProbeFailsClosedNeverOpen() {
        GreatWallReadinessRegistry.register(new Fake("broken", true, true, true, "x", true));
        // Even though the provider's fields say "ready", a throwing probe must not answer ready.
        assertFalse(GreatWallReadinessApi.isReady());
        assertFalse(GreatWallReadinessApi.awaitReady(10L));
        assertNull(GreatWallReadinessApi.progressSignature(),
                "a broken snapshot must read as 'no signal', never as a constant");
    }

    @Test
    void notReadyProviderHoldsTheGate() {
        GreatWallReadinessRegistry.register(Fake.notReady());
        assertTrue(GreatWallReadinessApi.isLinked());
        assertFalse(GreatWallReadinessApi.isReady());
        assertFalse(GreatWallReadinessApi.awaitReady(10L));
    }

    @Test
    void nonPositiveTimeoutDoesNotBlock() {
        // awaitReady(<=0) must be a plain query; a provider that only implements awaitReady
        // (returning a different value) must not be consulted for its blocking form.
        GreatWallReadinessRegistry.register(new Fake("fake", true, false, false, null, false));
        assertTrue(GreatWallReadinessApi.awaitReady(0L), "0 means query, not wait");
        assertFalse(GreatWallReadinessApi.awaitReady(10L), "positive timeout uses the blocking form");
    }

    @Test
    void blankOwnerIdIsRejected() {
        boolean threw = false;
        try {
            GreatWallReadinessRegistry.register(new Fake("  ", true, false, true, null, false));
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "a provider without an id cannot be identified in diagnostics");
    }
}
