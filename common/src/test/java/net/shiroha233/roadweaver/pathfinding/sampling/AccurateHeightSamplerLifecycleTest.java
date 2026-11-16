package net.shiroha233.roadweaver.pathfinding.sampling;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 同一世界内共享计算，但线程取消和世界关闭必须各自结案，不能跨调用者传播。 */
final class AccurateHeightSamplerLifecycleTest {
    @Test
    void tileWaiterInterruptionLeavesSharedFutureUsable() throws Exception {
        CompletableFuture<Void> owner = new CompletableFuture<>();
        ConcurrentHashMap<Long, CompletableFuture<Void>> claims = new ConcurrentHashMap<>();
        claims.put(7L, owner);
        AtomicReference<Throwable> fault = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                SamplerStatics.joinWaitingTiles(Map.of(7L, owner), claims);
            } catch (Throwable failure) {
                fault.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "tile-cancelled-waiter");
        waiter.start();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertInstanceOf(CancellationException.class, fault.get());
        assertTrue(interruptPreserved.get());
        assertFalse(owner.isCancelled(), "瓦片 Future 由所有者和其他调用者共用，单个等待者无权取消");
        assertSame(owner, claims.get(7L));
        assertTrue(owner.complete(null));
        assertDoesNotThrow(() -> SamplerStatics.joinWaitingTiles(Map.of(7L, owner), claims));
    }

    @Test
    void backendStillClosesWhenStoreCloseThrows() {
        AtomicInteger backendCloses = new AtomicInteger();
        AtomicInteger storeCloses = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("injected store close fault");
        IllegalStateException secondary = new IllegalStateException("injected backend close fault");
        AccurateHeightBackend backend = new AccurateHeightBackend() {
            @Override
            public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) { return Map.of(); }
            @Override
            public String backendName() { return "close-tracking"; }
            @Override
            public void close() { backendCloses.incrementAndGet(); throw secondary; }
        };
        AccurateSampleStore store = new AccurateSampleStore() {
            @Override
            public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) { return Map.of(); }
            @Override
            public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) { return Map.of(); }
            @Override
            public void saveChunks(Map<Long, AccurateHeightChunk> chunks) {}
            @Override
            public void saveSamples(Map<Long, AccurateHeightSample> samples) {}
            @Override
            public void close() { storeCloses.incrementAndGet(); throw expected; }
        };
        AccurateHeightSampler sampler = new AccurateHeightSampler(null, backend, store);

        assertSame(expected, assertThrows(IllegalStateException.class, sampler::clear));
        assertArrayEquals(new Throwable[]{secondary}, expected.getSuppressed(), "两次关闭失败都必须可诊断");
        assertEquals(1, backendCloses.get(), "存储关闭失败也必须释放后端持有的世界和采样资源");
        assertDoesNotThrow(sampler::clear);
        assertEquals(1, storeCloses.get());
        assertEquals(1, backendCloses.get());
    }

    @Test
    void separateSamplerInstancesDoNotShareWorldSamples() {
        AtomicInteger calls = new AtomicInteger();
        AccurateHeightBackend backend = new AccurateHeightBackend() {
            @Override
            public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) {
                calls.incrementAndGet();
                Map<Long, AccurateHeightChunk> chunks = new java.util.LinkedHashMap<>();
                for (long key : keys) {
                    int[] values = new int[AccurateHeightChunk.COLUMN_COUNT];
                    java.util.Arrays.fill(values, 68);
                    chunks.put(key, new AccurateHeightChunk(0, 0, values, values.clone(), values.clone()));
                }
                return chunks;
            }
            @Override
            public String backendName() { return "world-isolation"; }
        };
        AccurateHeightSampler first = new AccurateHeightSampler(null, backend, AccurateSampleStore.noop());
        AccurateHeightSampler second = new AccurateHeightSampler(null, backend, AccurateSampleStore.noop());
        try {
            assertEquals(1, first.sampleChunks(java.util.List.of(0L)).size());
            first.clear();
            assertEquals(1, second.sampleChunks(java.util.List.of(0L)).size());
            assertEquals(2, calls.get(), "新世界实例必须用自己的缓存和认领重新取值");
        } finally {
            first.clear();
            second.clear();
        }
    }
}
