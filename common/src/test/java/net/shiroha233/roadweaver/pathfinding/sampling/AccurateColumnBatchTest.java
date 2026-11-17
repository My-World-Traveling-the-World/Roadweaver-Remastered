package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 混合请求必须交付完整三通道高度；用故障端口和锁存器控制交错，避免空结果自证正确。 */
final class AccurateColumnBatchTest {
    private static final AccurateHeightGridRequest GRID = new AccurateHeightGridRequest(-1, -1, 2, 2, 1);
    private static final BlockPos P = new BlockPos(-1, 0, -1);

    @Test
    void gridSaveFailurePublishesNothingAndNextRequestRetriesPersistence() {
        Harness h = new Harness();
        h.store.failSave.set(true);
        assertThrows(IllegalStateException.class, () -> h.grid(GRID));
        assertTrue(h.values.isEmpty(), "整网格必须像逐列批次一样，持久化成功后才发布缓存");
        h.store.failSave.set(false);
        assertGrid(h.grid(GRID));
        assertEquals(2, h.backend.gridCalls.get());
        assertEquals(2, h.store.saves.get(), "保存失败后的重试不能被内存命中吞掉");
    }

    @Test
    void mismatchedBackendGridIsRejectedBeforeSavingOrPublishing() {
        Harness h = new Harness();
        h.backend.gridFn = request -> grid(new AccurateHeightGridRequest(400, 500, 2, 2, 1));
        IllegalStateException fault = assertThrows(IllegalStateException.class, () -> h.grid(GRID));
        assertTrue(fault.getMessage().contains("request"));
        assertEquals(0, h.store.saves.get());
        assertTrue(h.values.isEmpty());
        h.backend.gridFn = AccurateColumnBatchTest::grid;
        assertGrid(h.grid(GRID));
    }

    @Test
    void gridToGridReentryFailsBeforeWaitingAndLeavesNoClaim() { rejectReentry(true, true); }

    @Test
    void gridToColumnsReentryFailsBeforeWaitingAndLeavesNoClaim() { rejectReentry(true, false); }

    @Test
    void columnsToGridReentryFailsBeforeWaitingAndLeavesNoClaim() { rejectReentry(false, true); }

    private static void rejectReentry(boolean outerGrid, boolean innerGrid) {
        Harness h = new Harness(() -> { throw new AssertionError("同线程重入不得等待自己的 Future"); });
        Runnable recurse = () -> {
            if (innerGrid) h.grid(GRID);
            else h.columns(List.of(P));
        };
        if (outerGrid) h.backend.onGrid = recurse;
        else h.backend.onColumns = recurse;
        IllegalStateException fault = assertThrows(IllegalStateException.class, () -> {
            if (outerGrid) h.grid(GRID);
            else h.columns(List.of(P));
        });
        assertTrue(fault.getMessage().contains("recursive accurate tile request"));
        assertTrue(h.values.isEmpty());
        assertEquals(0, h.store.saves.get());
        h.backend.onGrid = () -> {};
        h.backend.onColumns = () -> {};
        assertGrid(h.grid(GRID));
    }

    @Test
    void storedGridReadRejectsDataArrivingAfterClose() {
        Harness h = new Harness();
        h.store.samples.putAll(samples(positions(GRID)));
        h.store.onLoad = h::close;
        assertThrows(IllegalStateException.class, () -> h.batch.loadStoredGrid(GRID));
        assertTrue(h.values.isEmpty(), "关闭期间到达的读盘结果不能重新填充缓存");
        assertEquals(0, h.backend.gridCalls.get());
    }

    @Test
    void samplingGridRejectsStoredDataArrivingAfterClose() {
        Harness h = new Harness();
        h.store.samples.putAll(samples(positions(GRID)));
        h.store.onLoad = h::close;
        assertThrows(IllegalStateException.class, () -> h.grid(GRID));
        assertTrue(h.values.isEmpty());
        assertEquals(0, h.backend.gridCalls.get());
    }

    @Test
    void partialGridUsesMemoryAndStoreThenSamplesOnlyRemainingColumns() {
        Harness h = new Harness();
        AccurateHeightGridRequest request = new AccurateHeightGridRequest(-1, -1, 3, 1, 1);
        List<BlockPos> points = positions(request);
        h.values.put(key(points.get(0)), sample(points.get(0)));
        h.store.samples.put(key(points.get(1)), sample(points.get(1)));
        assertGrid(h.grid(request));
        assertEquals(List.of(points.get(2)), h.backend.lastColumns);
        assertEquals(1, h.backend.columnCalls.get());
        assertEquals(0, h.backend.gridCalls.get());
        assertEquals(List.of(key(points.get(2))), h.store.lastSaved);
    }

    @Test
    void columnsFollowingGridOnlyComputeMissingRemainder() throws Exception { assertMixed(true); }

    @Test
    void gridFollowingColumnsOnlyComputesMissingRemainder() throws Exception { assertMixed(false); }

    private static void assertMixed(boolean ownerGrid) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Harness h = new Harness(() -> { joining.countDown(); return 5_000; });
        Runnable block = () -> { entered.countDown(); await(release); };
        if (ownerGrid) h.backend.onGrid = block;
        else h.backend.onColumns = block;
        AccurateHeightGridRequest first = new AccurateHeightGridRequest(0, 0, 2, 1, 1);
        AccurateHeightGridRequest next = new AccurateHeightGridRequest(1, 0, 2, 1, 1);
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> {
                if (ownerGrid) h.grid(first);
                else h.columns(positions(first));
            });
            try {
                await(entered);
                Future<?> follower = pool.submit(() -> {
                    if (ownerGrid) assertSamples(positions(next), h.columns(positions(next)));
                    else assertGrid(h.grid(next));
                });
                await(joining);
                assertFalse(owner.isDone(), "必须实际经过在途冲突，不能只验证预热后的缓存");
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
                follower.get(5, TimeUnit.SECONDS);
                assertEquals(List.of(new BlockPos(2, 0, 0)), h.backend.lastColumns);
                assertEquals(ownerGrid ? 1 : 2, h.backend.columnCalls.get());
                assertEquals(ownerGrid ? 1 : 0, h.backend.gridCalls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void facadeGridAndColumnsUseTheSameCacheAndPreserveAllHeights() {
        Backend backend = new Backend();
        AccurateHeightSampler sampler = new AccurateHeightSampler(null, backend, AccurateSampleStore.noop());
        try {
            assertGrid(sampler.sampleGrid(GRID));
            assertSamples(positions(GRID), sampler.samplePositions(positions(GRID)));
            assertEquals(1, backend.gridCalls.get());
            assertEquals(0, backend.columnCalls.get());
            assertEquals(GRID.sampleCount(), sampler.cachedColumnCount());
        } finally {
            sampler.clear();
        }
    }

    @Test
    void missingColumnPublishesNothingAndCompleteRetryPreservesInputOrder() {
        Harness h = new Harness();
        List<BlockPos> points = positions(GRID);
        h.backend.columnFn = requested -> samples(List.of(points.get(0)));
        assertThrows(IllegalStateException.class, () -> h.columns(points));
        assertTrue(h.values.isEmpty());
        assertEquals(0, h.store.saves.get());
        h.backend.columnFn = AccurateColumnBatchTest::samples;
        List<BlockPos> duplicate = new ArrayList<>(points);
        duplicate.add(points.get(0));
        Map<Long, AccurateHeightSample> result = h.columns(duplicate);
        assertSamples(points, result);
        assertEquals(points.stream().map(AccurateColumnBatchTest::key).toList(), new ArrayList<>(result.keySet()));
        assertEquals(2, h.backend.columnCalls.get());
    }

    @Test
    void progressCancellationDoesNotPublishAndNextRequestCanRetry() {
        Harness h = new Harness();
        assertThrows(CancellationException.class, () -> h.batch.samplePositionsInternal(positions(GRID),
                batch -> { throw new CancellationException("controlled progress cancellation"); }, true, false));
        assertTrue(h.values.isEmpty());
        assertEquals(0, h.store.saves.get());
        assertSamples(positions(GRID), h.columns(positions(GRID)));
        assertEquals(2, h.backend.columnCalls.get());
        assertEquals(1, h.store.saves.get());
    }

    @Test
    void timedOutGridOwnerCanBeTakenOverWithoutDroppingColumns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Harness h = new Harness(() -> 25);
        AtomicBoolean first = new AtomicBoolean(true);
        h.backend.onGrid = () -> {
            if (first.getAndSet(false)) { entered.countDown(); await(release); }
        };
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.grid(GRID));
            try {
                await(entered);
                AccurateHeightGrid taken = pool.submit(() -> h.grid(GRID)).get(5, TimeUnit.SECONDS);
                assertGrid(taken);
                assertEquals(2, h.backend.gridCalls.get());
                assertFalse(owner.isDone());
            } finally {
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closingGridWakesRegisteredColumnWaiterAndRejectsLatePublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Harness h = new Harness(() -> { joining.countDown(); return 30_000; });
        h.backend.onGrid = () -> { entered.countDown(); await(release); };
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.grid(GRID));
            try {
                await(entered);
                Future<?> waiter = pool.submit(() -> h.columns(List.of(P)));
                await(joining);
                h.close();
                assertInstanceOf(IllegalStateException.class, failure(waiter));
                release.countDown();
                assertInstanceOf(IllegalStateException.class, failure(owner));
                assertTrue(h.values.isEmpty());
                assertEquals(0, h.store.saves.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closingDuringColumnSaveCannotRepublishCache() { assertCloseDuringSave(false); }

    @Test
    void closingDuringGridSaveCannotRepublishCache() { assertCloseDuringSave(true); }

    private static void assertCloseDuringSave(boolean wholeGrid) {
        Harness h = new Harness();
        h.store.onSave = h::close;
        assertThrows(IllegalStateException.class, () -> {
            if (wholeGrid) h.grid(GRID);
            else h.columns(positions(GRID));
        });
        assertEquals(1, h.store.saves.get(), "必须真实进入保存路径再触发关闭");
        assertTrue(h.values.isEmpty(), "已经进入的保存可以结案，关闭后不得再复活内存缓存");
    }

    @Test
    void gridFailureReachesWaitingColumnsAndFreshRequestRetries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Harness h = new Harness(() -> { joining.countDown(); return 1_000; });
        IllegalStateException fault = new IllegalStateException("controlled grid failure");
        h.backend.onGrid = () -> { entered.countDown(); await(release); throw fault; };
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.grid(GRID));
            try {
                await(entered);
                Future<?> waiter = pool.submit(() -> h.columns(List.of(P)));
                await(joining);
                release.countDown();
                assertSame(fault, failure(owner));
                assertSame(fault, failure(waiter), "等待者必须收到原故障，不能把失败偷偷转成超时重算成功");
                assertEquals(0, h.store.saves.get());
                assertTrue(h.values.isEmpty());
                h.backend.onGrid = () -> {};
                assertGrid(h.grid(GRID));
            } finally {
                release.countDown();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void diskOnlyFillDuringStaleMissDoesNotRecompute(int fillMode) {
        Harness h = new Harness();
        List<BlockPos> points = positions(GRID);
        h.store.onLoad = () -> {
            h.store.onLoad = () -> {};
            // 外层已经拿到空磁盘快照；另一个请求只查盘并填入缓存，没有运行后端。
            h.store.samples.putAll(samples(points));
            if (fillMode == 0) assertSamples(points, h.columns(points));
            else if (fillMode == 1) assertGrid(h.grid(GRID));
            else assertGrid(h.batch.loadStoredGrid(GRID).orElseThrow());
        };
        assertGrid(h.grid(GRID));
        assertEquals(0, h.backend.gridCalls.get(), "读盘填充也必须使旧网格未命中快照过期");
        assertEquals(0, h.backend.columnCalls.get());
        assertEquals(0, h.store.saves.get(), "纯读盘命中不应变成重复计算后再写盘");
    }

    @Test
    void repeatedMemoryEvictionRestoresEveryHeightChannelFromStorage() {
        Harness h = new Harness();
        List<BlockPos> points = positions(GRID);
        assertGrid(h.grid(GRID));
        assertEquals(1, h.backend.gridCalls.get());
        // 强制驱逐全部一级缓存，连续重载验证版本记录不是假命中，也不代替真正样本。
        for (int round = 0; round < 64; round++) {
            h.values.clear();
            if ((round & 1) == 0) assertSamples(points, h.columns(points));
            else assertGrid(h.grid(GRID));
        }
        assertEquals(1, h.backend.gridCalls.get());
        assertEquals(0, h.backend.columnCalls.get());
        assertEquals(1, h.store.saves.get());
    }

    @Test
    void closeDuringActiveGridWakesColumnWaiterBeforeOwnerReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1);
        Harness h = new Harness(() -> { joining.countDown(); return 30_000; });
        h.backend.onGrid = () -> { entered.countDown(); await(release); };
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> owner = pool.submit(() -> h.grid(GRID));
            try {
                await(entered);
                Future<?> waiter = pool.submit(() -> h.columns(List.of(P)));
                await(joining);
                h.close();
                assertInstanceOf(IllegalStateException.class, failure(waiter));
                assertFalse(owner.isDone(), "等待者必须由关闭唤醒，不能等后端自己结束才算通过");
                release.countDown();
                assertInstanceOf(IllegalStateException.class, failure(owner));
                assertEquals(0, h.store.saves.get());
                assertTrue(h.values.isEmpty());
            } finally { release.countDown(); h.close(); }
        }
    }

    private static void assertGrid(AccurateHeightGrid grid) {
        assertTrue(grid.request().sampleCount() > 0);
        for (int i = 0; i < grid.request().sampleCount(); i++) {
            AccurateHeightSample expected = sample(new BlockPos(grid.request().blockX(i), 0, grid.request().blockZ(i)));
            assertEquals(expected.worldSurfaceWg(), grid.worldSurface()[i]);
            assertEquals(expected.oceanFloorWg(), grid.oceanFloor()[i]);
            assertEquals(expected.motionBlockingNoLeaves(), grid.motionBlocking()[i]);
        }
    }

    private static void assertSamples(List<BlockPos> points, Map<Long, AccurateHeightSample> actual) {
        assertFalse(points.isEmpty());
        assertEquals(points.size(), actual.size());
        for (BlockPos p : points) assertEquals(sample(p), actual.get(key(p)));
    }

    private static AccurateHeightSample sample(BlockPos p) {
        int y = 68 + Math.floorMod(p.getX() * 31 + p.getZ() * 17, 20);
        return new AccurateHeightSample(y, y - 5, y + 2);
    }

    private static long key(BlockPos p) { return AccurateHeightSample.key(p.getX(), p.getZ()); }

    private static List<BlockPos> positions(AccurateHeightGridRequest request) {
        List<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < request.sampleCount(); i++) result.add(new BlockPos(request.blockX(i), 0, request.blockZ(i)));
        return result;
    }

    private static Map<Long, AccurateHeightSample> samples(Collection<BlockPos> points) {
        Map<Long, AccurateHeightSample> result = new LinkedHashMap<>();
        points.forEach(p -> result.put(key(p), sample(p)));
        return result;
    }

    private static AccurateHeightGrid grid(AccurateHeightGridRequest request) {
        int[] ws = new int[request.sampleCount()], of = ws.clone(), mb = ws.clone();
        for (int i = 0; i < request.sampleCount(); i++) {
            AccurateHeightSample value = sample(new BlockPos(request.blockX(i), 0, request.blockZ(i)));
            ws[i] = value.worldSurfaceWg(); of[i] = value.oceanFloorWg(); mb[i] = value.motionBlockingNoLeaves();
        }
        return new AccurateHeightGrid(request, ws, of, mb);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("controlled test interrupted");
        }
    }

    private static Throwable failure(Future<?> task) {
        return assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS)).getCause();
    }

    /** 后端回调可触发混合重入或暂停；三张高度不同，避免通道写错仍通过。 */
    private static final class Backend implements AccurateHeightBackend {
        final AtomicInteger gridCalls = new AtomicInteger(), columnCalls = new AtomicInteger();
        volatile Runnable onGrid = () -> {}, onColumns = () -> {};
        Function<AccurateHeightGridRequest, AccurateHeightGrid> gridFn = AccurateColumnBatchTest::grid;
        Function<Collection<BlockPos>, Map<Long, AccurateHeightSample>> columnFn = AccurateColumnBatchTest::samples;
        volatile List<BlockPos> lastColumns = List.of();
        @Override
        public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request, AccurateSamplingProgress progress) {
            gridCalls.incrementAndGet(); onGrid.run(); return gridFn.apply(request);
        }
        @Override
        public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> points) {
            columnCalls.incrementAndGet(); lastColumns = List.copyOf(points); onColumns.run(); return columnFn.apply(points);
        }
        @Override
        public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) { throw new AssertionError("unexpected whole chunk path"); }
        @Override
        public String backendName() { return "controlled-columns"; }
    }

    /** 先取持久化快照再回调，模拟磁盘读取期间世界关闭或其他请求完成。 */
    private static final class Store implements AccurateSampleStore {
        final Map<Long, AccurateHeightSample> samples = new ConcurrentHashMap<>();
        final AtomicBoolean failSave = new AtomicBoolean();
        final AtomicInteger saves = new AtomicInteger();
        volatile Runnable onLoad = () -> {};
        volatile Runnable onSave = () -> {};
        volatile List<Long> lastSaved = List.of();
        @Override
        public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) {
            Map<Long, AccurateHeightSample> found = new LinkedHashMap<>();
            keys.forEach(key -> { if (samples.containsKey(key)) found.put(key, samples.get(key)); });
            onLoad.run(); return found;
        }
        @Override
        public void saveSamples(Map<Long, AccurateHeightSample> values) {
            saves.incrementAndGet();
            if (failSave.get()) throw new IllegalStateException("controlled persistence failure");
            lastSaved = List.copyOf(values.keySet()); samples.putAll(values); onSave.run();
        }
        @Override
        public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) { return Map.of(); }
        @Override
        public void saveChunks(Map<Long, AccurateHeightChunk> values) { throw new AssertionError("unexpected chunk save"); }
    }

    private static final class Harness implements AccurateColumnBatch.Cache {
        final Backend backend = new Backend();
        final Store store = new Store();
        final Map<Long, AccurateHeightSample> values = new ConcurrentHashMap<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AccurateColumnBatch batch;
        Harness() { this(() -> 5_000); }
        Harness(LongSupplier timeout) { batch = new AccurateColumnBatch(backend, store, this, timeout); }
        AccurateHeightGrid grid(AccurateHeightGridRequest request) { return batch.sampleGridInternal(request, AccurateSamplingProgress.NONE, false); }
        Map<Long, AccurateHeightSample> columns(List<BlockPos> positions) { return batch.samplePositionsInternal(positions, AccurateSamplingProgress.NONE, true, false); }
        void close() { closed.set(true); values.clear(); batch.close(new IllegalStateException("controlled close")); }
        @Override
        public AccurateHeightSample get(long key, int x, int z) { return values.get(key); }
        @Override
        public void put(long key, AccurateHeightSample sample) { values.put(key, sample); }
        @Override
        public void ensureOpen() { if (closed.get()) throw new IllegalStateException("controlled closed cache"); }
    }
}
