/* 文件职责：让新地形瓦片穿过真实采样门面，验证驱逐、认领与关闭没有被旁路。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.bridge.TiledTerrainField;
import net.shiroha233.roadweaver.metric.GridLayout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TiledSamplerIntegrationTest {
    @Test
    void localEvictionRereadsThroughRealSamplerCacheWithoutRepeatingBackendWork() {
        var backend = new Backend();
        var sampler = new AccurateHeightSampler(null, backend);
        var terrain = new TiledTerrainField(new GridLayout(-9, -7, 9, 9, 8), 4, 1, sampler::sampleGrid);
        try {
            assertEquals(73, terrain.height(-9, -7));
            assertEquals(73, terrain.height(23, -7));
            assertEquals(73, terrain.height(-9, -7));
            assertEquals(14, terrain.waterProxy(-9, -7));
            assertEquals(3, terrain.stats().loadedTiles());
            assertEquals(2, terrain.stats().evictions());
            assertEquals(2, backend.calls.get(), "本地驱逐后仍经真实门面命中其列缓存");
            assertEquals(32, sampler.cachedColumnCount());
        } finally { sampler.clear(); }
    }

    @Test
    void closeDuringStorePublicationCannotPublishAReadyTerrainTile() {
        var backend = new Backend();
        var reference = new AtomicReference<AccurateHeightSampler>();
        var writes = new AtomicInteger();
        AccurateSampleStore store = new AccurateSampleStore() {
            public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) { throw new AssertionError(); }
            public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) { return Map.of(); }
            public void saveChunks(Map<Long, AccurateHeightChunk> chunks) { throw new AssertionError(); }
            public void saveSamples(Map<Long, AccurateHeightSample> samples) {
                assertEquals(4096, samples.size());
                writes.incrementAndGet(); reference.get().clear();
            }
        };
        var sampler = new AccurateHeightSampler(null, backend, store); reference.set(sampler);
        var surface = SampledTerrainField.planningSurface(new GridLayout(0, 0, 16384, 16384, 8), sampler::sampleGrid);
        var terrain = assertInstanceOf(TiledTerrainField.class, surface.terrain());
        try {
            assertThrows(IllegalStateException.class, () -> terrain.height(0, 0));
            assertEquals(1, backend.calls.get()); assertEquals(1, writes.get());
            assertEquals(0, sampler.cachedColumnCount());
            assertEquals(new TiledTerrainField.Stats(1, 0, 0, 0, 0, 0), terrain.stats());
            assertThrows(IllegalStateException.class, () -> terrain.height(0, 0));
            assertEquals(1, backend.calls.get(), "关闭后重试必须在门面拒绝，不能再次进入后端");
            assertEquals(0, terrain.stats().loadedTiles());
        } finally { sampler.clear(); }
    }

    @Test
    void independentPlanningFieldsShareRealInFlightClaims() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var backend = new Backend();
        backend.action = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        };
        var sampler = new AccurateHeightSampler(null, backend);
        var layout = new GridLayout(0, 0, 16384, 16384, 8);
        var first = SampledTerrainField.planningSurface(layout, sampler::sampleGrid);
        var second = SampledTerrainField.planningSurface(layout, sampler::sampleGrid);
        var waiter = new AtomicReference<Thread>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> first.terrain().height(0, 0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var follower = executor.submit(() -> {
                waiter.set(Thread.currentThread()); return second.terrain().height(8, 8);
            });
            awaitClaim(waiter);
            assertEquals(1, backend.calls.get());
            release.countDown();
            assertEquals(73, owner.get(5, TimeUnit.SECONDS));
            assertEquals(73, follower.get(5, TimeUnit.SECONDS));
            assertEquals(14, second.waterProxy(8, 8));
            assertEquals(1, backend.calls.get(), "两个规划对象自己的 LRU 不得绕过世界级在途认领");
            assertEquals(4096, sampler.cachedColumnCount());
        } finally {
            release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            sampler.clear();
        }
    }

    private static void awaitClaim(AtomicReference<Thread> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Thread thread = reference.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().contains("AccurateTileClaims") && frame.getMethodName().equals("await"))) return;
            Thread.sleep(1);
        }
        fail("第二个规划未进入真实瓦片认领等待");
    }

    private static final class Backend implements AccurateHeightBackend {
        final AtomicInteger calls = new AtomicInteger();
        Runnable action = () -> {};
        public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) { throw new AssertionError(); }
        public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request, AccurateSamplingProgress progress) {
            calls.incrementAndGet(); action.run();
            assertTrue(request.sampleCount() <= 4096);
            int[] motion = new int[request.sampleCount()], surface = new int[motion.length], floor = new int[motion.length];
            Arrays.fill(motion, 73); Arrays.fill(surface, 81); Arrays.fill(floor, 67);
            return new AccurateHeightGrid(request, surface, floor, motion);
        }
        public String backendName() { return "tiled-integration-fake-world"; }
    }
}
