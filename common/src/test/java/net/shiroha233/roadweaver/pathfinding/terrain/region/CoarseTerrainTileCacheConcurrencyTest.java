package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.resources.ResourceLocation;
import net.shiroha233.roadweaver.config.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic concurrency regression for the coarse tile in-flight
 * coordination (audit P2-7 / P2-13). Drives
 * {@link CoarseTerrainTileCache#coordinateInFlight} with injected loaders so
 * no Minecraft level is required: contended keys load once, stalled owners
 * are taken over after the configured timeout, and a clearAll() generation
 * bump cancels the old-generation load instead of publishing it.
 */
final class CoarseTerrainTileCacheConcurrencyTest {

    private static final int TEST_TIMEOUT_MS = 200;

    @AfterEach
    void restoreDefaults() {
        ConfigService.get().planning().setSamplingInFlightTimeoutMs(3_000);
        CoarseTerrainTileCache.clearAll();
    }

    private static CoarseTerrainTileKey key(int salt) {
        return new CoarseTerrainTileKey(
                ResourceLocation.fromNamespaceAndPath("roadweaver", "cache_regression"),
                900 + salt, 900, 8, 4, 1);
    }

    private static CoarseTerrainTile tile(CoarseTerrainTileKey key, short surface) {
        return new CoarseTerrainTile(key, 63, 1, 1,
                new short[]{surface}, new short[]{(short) (surface - 4)},
                new byte[]{0}, new int[]{0xFF205050});
    }

    @Test
    void contendedKeyIsLoadedByExactlyOneThread() throws Exception {
        CoarseTerrainTileKey shared = key(1);
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<CoarseTerrainTile>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return CoarseTerrainTileCache.coordinateInFlight(shared, () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return tile(shared, (short) 70);
                    });
                }));
            }
            CoarseTerrainTile first = results.get(0).get(5, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<CoarseTerrainTile> result : results) {
                assertSame(first, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get(), "exactly one thread must run the loader");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timeoutTakesOverStalledOwner() throws Exception {
        ConfigService.get().planning().setSamplingInFlightTimeoutMs(TEST_TIMEOUT_MS);
        CoarseTerrainTileKey shared = key(2);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CoarseTerrainTile> owner = pool.submit(() ->
                    CoarseTerrainTileCache.coordinateInFlight(shared, () -> {
                        ownerStarted.countDown();
                        try {
                            releaseOwner.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return tile(shared, (short) 64);
                    }));
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS),
                    "owner must be inside its loader before the waiter starts");

            Future<CoarseTerrainTile> waiter = pool.submit(() ->
                    CoarseTerrainTileCache.coordinateInFlight(shared, () -> tile(shared, (short) 80)));
            // Waiter hits the 200 ms bound, removes the stalled entry, becomes
            // the owner, and succeeds with its own loader.
            CoarseTerrainTile takenOver = waiter.get(5, TimeUnit.SECONDS);
            assertNotNull(takenOver);
            assertEquals((short) 80, takenOver.heights()[0]);

            // The original owner still completes with its own tile once released.
            releaseOwner.countDown();
            CoarseTerrainTile original = owner.get(5, TimeUnit.SECONDS);
            assertNotNull(original);
            assertEquals((short) 64, original.heights()[0]);
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void clearAllGenerationBumpCancelsInFlightOwner() throws Exception {
        CoarseTerrainTileKey shared = key(3);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<CoarseTerrainTile> owner = pool.submit(() ->
                    CoarseTerrainTileCache.coordinateInFlight(shared, () -> {
                        ownerStarted.countDown();
                        try {
                            releaseOwner.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return tile(shared, (short) 64);
                    }));
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));
            // Generation bump while the load is in flight: the result must be
            // discarded (cancelled) rather than published or waited on.
            CoarseTerrainTileCache.clearAll();
            releaseOwner.countDown();
            assertNull(owner.get(5, TimeUnit.SECONDS));
        } finally {
            releaseOwner.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void recursiveSameThreadRequestFailsFast() {
        CoarseTerrainTileKey shared = key(4);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CoarseTerrainTile result = CoarseTerrainTileCache.coordinateInFlight(shared, () -> {
            try {
                CoarseTerrainTileCache.coordinateInFlight(shared, () -> tile(shared, (short) 64));
            } catch (Throwable failure) {
                observed.set(failure);
            }
            return tile(shared, (short) 64);
        });
        assertNotNull(result);
        assertTrue(observed.get() instanceof IllegalStateException,
                "recursive same-thread request must fail fast, got " + observed.get());
    }
}
