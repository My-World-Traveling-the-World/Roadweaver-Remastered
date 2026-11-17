package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 用锁存器控制真实认领顺序，避免靠睡眠碰运气或空集合断言掩盖并发缺陷。 */
final class AccurateChunkBatchTest {
    private static final long A = ChunkPos.asLong(-2, 3);
    private static final long B = ChunkPos.asLong(4, -5);

    @Test
    void preservesOrderAndOnlyComputesUncachedUnstoredUniqueChunks() {
        Store store = new Store();
        store.stored.put(B, chunk(B));
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> {
            calls.incrementAndGet();
            assertEquals(List.of(A), new ArrayList<>(keys));
            return chunks(keys);
        }, store, () -> 5_000);
        long cached = ChunkPos.asLong(8, 9);
        h.values.put(cached, chunk(cached));

        Map<Long, AccurateHeightChunk> result = h.batch.sample(List.of(B, A, cached, A));

        assertEquals(List.of(B, A, cached), new ArrayList<>(result.keySet()));
        assertEquals(1, calls.get());
        assertEquals(1, store.saves.get());
        assertEquals(List.of(A), store.lastSaved);
        assertEquals(result, h.batch.sample(List.of(B, A, cached)));
        assertEquals(1, calls.get(), "完整缓存命中不能重新计算");
    }

    @Test
    void computesFreeSubsetBeforeJoiningOverlappingOwner() throws Exception {
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch freeComputed = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> {
            if (calls.incrementAndGet() == 1) {
                ownerEntered.countDown();
                await(release);
            } else {
                assertEquals(List.of(B), new ArrayList<>(keys));
                freeComputed.countDown();
            }
            return chunks(keys);
        }, new Store(), () -> { joining.countDown(); return 5_000; });
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(ownerEntered);
                Future<Map<Long, AccurateHeightChunk>> follower =
                        pool.submit(() -> h.batch.sample(List.of(B, A)));
                await(freeComputed);
                await(joining);
                assertFalse(owner.isDone(), "必须在原认领仍未完成时处理无冲突子集");
                release.countDown();
                assertEquals(List.of(B, A), new ArrayList<>(follower.get(5, TimeUnit.SECONDS).keySet()));
                owner.get(5, TimeUnit.SECONDS);
                assertEquals(2, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timeoutReclaimsChunkAndReturnsEveryRequestedKey() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
            return chunks(keys);
        }, new Store(), () -> 25);
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(entered);
                Map<Long, AccurateHeightChunk> result =
                        pool.submit(() -> h.batch.sample(List.of(A))).get(5, TimeUnit.SECONDS);
                assertEquals(List.of(A), new ArrayList<>(result.keySet()), "超时只是释放认领，不能把缺失项当作已完成");
                assertEquals(2, calls.get(), "停滞所有者未返回时，新调用者必须真正接管求值");
            } finally {
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void interruptedWaiterDoesNotCancelSharedOwnerOrHealthyWaiter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return chunks(keys);
        }, new Store(), () -> { joining.countDown(); return 5_000; });
        AtomicReference<Throwable> cancelled = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                h.batch.sample(List.of(A));
            } catch (Throwable failure) {
                cancelled.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "accurate-cancelled-waiter");
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(entered);
                waiter.start();
                Future<Map<Long, AccurateHeightChunk>> healthy =
                        pool.submit(() -> h.batch.sample(List.of(A)));
                await(joining);
                waiter.interrupt();
                waiter.join(5_000);
                assertFalse(waiter.isAlive());
                assertInstanceOf(CancellationException.class, cancelled.get());
                assertTrue(interruptPreserved.get(), "取消必须保留调用线程的中断标志");
                release.countDown();
                assertEquals(List.of(A), new ArrayList<>(healthy.get(5, TimeUnit.SECONDS).keySet()));
                owner.get(5, TimeUnit.SECONDS);
                assertEquals(1, calls.get(), "等待者取消不能毒化共享 Future 或迫使其他调用者重算");
            } finally {
                release.countDown();
                waiter.interrupt();
                waiter.join(5_000);
            }
        }
    }

    @Test
    void backendFailureReachesBothCallersAndNextRequestRetries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        IllegalStateException fault = new IllegalStateException("injected backend failure");
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                throw fault;
            }
            return chunks(keys);
        }, new Store(), () -> { joining.countDown(); return 5_000; });
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(entered);
                Future<?> waiter = pool.submit(() -> h.batch.sample(List.of(A)));
                await(joining);
                release.countDown();
                assertSame(fault, failure(owner));
                assertSame(fault, failure(waiter));
                assertTrue(h.values.isEmpty(), "失败不能伪造成功缓存");
                assertEquals(List.of(A), new ArrayList<>(h.batch.sample(List.of(A)).keySet()));
                assertEquals(2, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void incompleteBackendBatchPublishesNothingAndRetries() {
        AtomicInteger calls = new AtomicInteger();
        Store store = new Store();
        Harness h = new Harness(keys -> calls.incrementAndGet() == 1
                ? chunks(List.of(A)) : chunks(keys), store, () -> 5_000);

        IllegalStateException fault = assertThrows(IllegalStateException.class,
                () -> h.batch.sample(List.of(A, B)));
        assertTrue(fault.getMessage().contains("Missing accurate height chunk"));
        assertTrue(h.values.isEmpty());
        assertEquals(0, store.saves.get(), "缺一块时不能发布或持久化前半批");
        assertEquals(List.of(A, B), new ArrayList<>(h.batch.sample(List.of(A, B)).keySet()));
        assertEquals(2, calls.get());
    }

    @Test
    void persistenceFailureLeavesBatchRetryable() {
        Store store = new Store();
        store.failSave.set(true);
        AtomicInteger calls = new AtomicInteger();
        Harness h = new Harness(keys -> { calls.incrementAndGet(); return chunks(keys); }, store, () -> 5_000);

        assertThrows(IllegalStateException.class, () -> h.batch.sample(List.of(A)));
        assertTrue(h.values.isEmpty());
        store.failSave.set(false);
        assertEquals(List.of(A), new ArrayList<>(h.batch.sample(List.of(A)).keySet()));
        assertEquals(2, calls.get());
        assertEquals(2, store.saves.get());
    }

    @Test
    void rechecksMemoryAfterAnotherOwnerFinishesDuringStoredLookup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch storedSnapshotTaken = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Store store = new Store();
        Harness h = new Harness(keys -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(releaseOwner);
            }
            return chunks(keys);
        }, store, () -> 5_000);
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(entered);
                // 模拟读盘拿到旧的空快照后停顿，此时原所有者才完成、发布缓存并释放认领。
                store.onLoad = () -> { storedSnapshotTaken.countDown(); await(releaseLookup); };
                Future<Map<Long, AccurateHeightChunk>> follower =
                        pool.submit(() -> h.batch.sample(List.of(A)));
                await(storedSnapshotTaken);
                releaseOwner.countDown();
                owner.get(5, TimeUnit.SECONDS);
                releaseLookup.countDown();
                assertEquals(List.of(A), new ArrayList<>(follower.get(5, TimeUnit.SECONDS).keySet()));
                assertEquals(1, calls.get(), "旧读盘快照不能让已完成的区块被再次认领计算");
            } finally {
                releaseOwner.countDown();
                releaseLookup.countDown();
            }
        }
    }

    @Test
    void closingFacadeWakesWaiterAndRejectsLateBackendResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Store store = new Store();
        // 真实门面用于守住 clear 与调度器的接线，而非只验证测试专用 Cache。
        AccurateHeightSampler sampler = new AccurateHeightSampler(null, backend(keys -> {
            entered.countDown();
            await(release);
            return chunks(keys);
        }), store);
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> sampler.sampleChunks(List.of(A)));
            try {
                await(entered);
                // 存储端口只在第二次缺失检查时打点；关闭后不再允许调用进入后端。
                store.onLoad = joining::countDown;
                Future<?> waiter = pool.submit(() -> sampler.sampleChunks(List.of(A)));
                await(joining);
                sampler.clear();
                assertInstanceOf(IllegalStateException.class, failure(waiter));
                release.countDown();
                assertInstanceOf(IllegalStateException.class, failure(owner));
                assertEquals(0, store.saves.get());
                assertEquals(0, sampler.cachedChunkCount());
                assertThrows(IllegalStateException.class, () -> sampler.sampleChunks(List.of(B)));
                sampler.clear();
                assertEquals(1, store.closes.get(), "重复关闭不能重复关闭资源");
            } finally {
                release.countDown();
                sampler.clear();
            }
        }
    }

    @Test
    void closingBatchImmediatelyFailsAnAlreadyRegisteredWaiter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Store store = new Store();
        Harness h = new Harness(keys -> {
            entered.countDown();
            await(release);
            return chunks(keys);
        }, store, () -> { joining.countDown(); return 30_000; });
        IllegalStateException closed = new IllegalStateException("controlled world close");
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.batch.sample(List.of(A)));
            try {
                await(entered);
                Future<?> waiter = pool.submit(() -> h.batch.sample(List.of(A)));
                // 超时供给器在读取到共享 Future 后调用，能证明本用例确实走进等待路径。
                await(joining);
                h.closed.set(true);
                h.batch.close(closed);
                assertSame(closed, failure(waiter), "无需等所有者返回或等待超时，关闭必须主动唤醒");
                release.countDown();
                assertInstanceOf(IllegalStateException.class, failure(owner));
                assertEquals(0, store.saves.get());
                assertTrue(h.values.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void recursiveBatchDoesNotLeakEarlierPartialClaims() {
        AtomicReference<Harness> ref = new AtomicReference<>();
        AtomicBoolean recurse = new AtomicBoolean(true);
        Harness h = new Harness(keys -> {
            if (recurse.getAndSet(false)) {
                return ref.get().batch.sample(List.of(B, A));
            }
            return chunks(keys);
        }, new Store(), () -> { throw new AssertionError("重入失败不应留下需等待的认领"); });
        ref.set(h);

        IllegalStateException fault = assertThrows(IllegalStateException.class,
                () -> h.batch.sample(List.of(A)));
        assertTrue(fault.getMessage().contains("recursive accurate chunk request"));
        assertEquals(List.of(B), new ArrayList<>(h.batch.sample(List.of(B)).keySet()),
                "检测重入之前不得为前面的键留下无人完成的认领");
    }

    @Test
    void diskOnlyChunkFillPublishesTheSharedColumnTileVersion() {
        Store store = new Store();
        store.stored.put(A, chunk(A));
        AccurateSamplePublications publications = new AccurateSamplePublications();
        Map<Long, AccurateHeightChunk> values = new ConcurrentHashMap<>();
        var cache = new AccurateChunkBatch.Cache() {
            public AccurateHeightChunk get(long key) { return values.get(key); }
            public void put(long key, AccurateHeightChunk chunk) { values.put(key, chunk); }
            public void ensureOpen() {}
        };
        var batch = new AccurateChunkBatch(backend(keys -> { throw new AssertionError("unexpected backend"); }),
                store, cache, () -> 5_000, publications);
        long before = publications.version();
        assertEquals(List.of(A), new ArrayList<>(batch.sample(List.of(A)).keySet()));
        long tile = SamplerStatics.tileKey(ChunkPos.getX(A) * 16, ChunkPos.getZ(A) * 16);
        assertTrue(publications.changedSince(List.of(tile), before),
                "整块只查盘也要通知列/网格，不能只在后端计算后发布");
        assertFalse(publications.changedSince(List.of(tile), publications.version()));
        assertEquals(0, store.saves.get());
    }

    private static AccurateHeightBackend backend(Function<Collection<Long>, Map<Long, AccurateHeightChunk>> fn) {
        return new AccurateHeightBackend() {
            @Override
            public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) { return fn.apply(keys); }
            @Override
            public String backendName() { return "controlled"; }
        };
    }

    private static Map<Long, AccurateHeightChunk> chunks(Collection<Long> keys) {
        Map<Long, AccurateHeightChunk> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, chunk(key)));
        return result;
    }

    private static AccurateHeightChunk chunk(long key) {
        int[] values = new int[AccurateHeightChunk.COLUMN_COUNT];
        java.util.Arrays.fill(values, 72);
        return new AccurateHeightChunk(ChunkPos.getX(key), ChunkPos.getZ(key), values, values.clone(), values.clone());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("test backend interrupted");
        }
    }

    private static Throwable failure(Future<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
    }

    /** 调度器直接测试可注入短超时，不改生产配置，也不依赖真实世界生成耗时。 */
    private static final class Harness implements AccurateChunkBatch.Cache {
        final Map<Long, AccurateHeightChunk> values = new ConcurrentHashMap<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AccurateChunkBatch batch;

        Harness(Function<Collection<Long>, Map<Long, AccurateHeightChunk>> fn, Store store, LongSupplier timeout) {
            batch = new AccurateChunkBatch(backend(fn), store, this, timeout);
        }

        @Override
        public AccurateHeightChunk get(long key) { return values.get(key); }
        @Override
        public void put(long key, AccurateHeightChunk chunk) { values.put(key, chunk); }
        @Override
        public void ensureOpen() {
            if (closed.get()) throw new IllegalStateException("controlled cache closed");
        }
    }

    /** 读写次数与失败可观察，防止内存通过却把持久化失败当成功。 */
    private static final class Store implements AccurateSampleStore {
        final Map<Long, AccurateHeightChunk> stored = new ConcurrentHashMap<>();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicBoolean failSave = new AtomicBoolean();
        volatile List<Long> lastSaved = List.of();
        volatile Runnable onLoad = () -> {};

        @Override
        public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) {
            Map<Long, AccurateHeightChunk> result = new LinkedHashMap<>();
            keys.forEach(key -> { if (stored.containsKey(key)) result.put(key, stored.get(key)); });
            onLoad.run();
            return result;
        }
        @Override
        public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) { return Map.of(); }
        @Override
        public void saveChunks(Map<Long, AccurateHeightChunk> chunks) {
            saves.incrementAndGet();
            if (failSave.get()) throw new IllegalStateException("injected save failure");
            lastSaved = new ArrayList<>(chunks.keySet());
            stored.putAll(chunks);
        }
        @Override
        public void saveSamples(Map<Long, AccurateHeightSample> samples) {}
        @Override
        public void close() { closes.incrementAndGet(); }
    }
}
