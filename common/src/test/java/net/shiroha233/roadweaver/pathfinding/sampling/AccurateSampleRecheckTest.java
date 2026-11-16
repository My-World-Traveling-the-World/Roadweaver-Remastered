package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 暂停已取得的磁盘未命中快照，等另一个线程发布并释放认领后再继续。 */
final class AccurateSampleRecheckTest {
    private static final AccurateHeightGridRequest GRID = new AccurateHeightGridRequest(-2, -2, 2, 2, 1);

    @Test
    void columnsReuseGridPublishedDuringTheirStoreRead() throws Exception { sameFootprint(false, true); }

    @Test
    void gridReusesColumnsPublishedDuringItsStoreRead() throws Exception { sameFootprint(true, false); }

    @Test
    void columnsReuseColumnsPublishedDuringTheirStoreRead() throws Exception { sameFootprint(false, false); }

    @Test
    void gridReusesGridPublishedDuringItsStoreRead() throws Exception { sameFootprint(true, true); }

    private static void sameFootprint(boolean followerGrid, boolean ownerGrid) throws Exception {
        Harness h = new Harness();
        Object result = staleRead(h, () -> followerGrid ? h.grid(GRID) : h.columns(points(GRID)), () -> {
            if (ownerGrid) assertGrid(h.grid(GRID));
            else assertSamples(points(GRID), h.columns(points(GRID)));
        }, () -> {});
        if (followerGrid) assertGrid((AccurateHeightGrid) result);
        else assertEquals(expected(points(GRID)), result);
        assertEquals(ownerGrid ? 1 : 0, h.backend.grids.get());
        assertEquals(ownerGrid ? 0 : 1, h.backend.columns.size(), "取得旧未命中快照不能再次启动高度生成");
        assertEquals(1, h.store.saves.get(), "复用已发布结果不能再保存一次相同样本");
    }

    @Test
    void staleColumnsOnlySampleUnpublishedRemainderAcrossTiles() throws Exception {
        Harness h = new Harness();
        BlockPos first = new BlockPos(-1, 0, -1), remaining = new BlockPos(256, 0, 0);
        var result = staleRead(h, () -> h.columns(List.of(first, remaining, first)),
                () -> assertSamples(List.of(first), h.columns(List.of(first))), () -> {});
        assertSamples(List.of(first, remaining), result);
        assertEquals(List.of(List.of(first), List.of(remaining)), h.backend.columns,
                "跨瓦片批次重查后，一个瓦片可能完全不再需要生成，不能重新补回或空指针");
        assertEquals(List.of(key(first), key(remaining)), List.copyOf(result.keySet()));
    }

    @Test
    void partiallyPublishedGridSamplesOnlyMissingColumns() throws Exception {
        Harness h = new Harness();
        var request = new AccurateHeightGridRequest(255, 0, 3, 1, 1);
        var owner = new AccurateHeightGridRequest(255, 0, 2, 1, 1);
        assertGrid(staleRead(h, () -> h.grid(request), () -> assertGrid(h.grid(owner)), () -> {}));
        assertEquals(1, h.backend.grids.get());
        assertEquals(List.of(List.of(new BlockPos(257, 0, 0))), h.backend.columns,
                "部分新发布应使整网格改为补缺，不得整张重采");
    }

    @Test
    void unrelatedPublicationDoesNotRescanColdGrid() throws Exception {
        Harness h = new Harness();
        var request = new AccurateHeightGridRequest(0, 0, 16, 16, 1);
        BlockPos unrelated = new BlockPos(4096, 0, 8192);
        assertGrid(staleRead(h, () -> h.grid(request), () -> h.columns(List.of(unrelated)), () -> {}));
        assertEquals(257, h.gets.get(), "无关瓦片发布不能给 256 列冷网格增加整批缓存扫描");
        assertEquals(2, h.store.loads.get());
        assertEquals(1, h.backend.grids.get());
        assertEquals(List.of(List.of(unrelated)), h.backend.columns);
    }

    @Test
    void uncontendedColdGridKeepsSingleCacheAndStorePass() {
        Harness h = new Harness();
        assertGrid(h.grid(new AccurateHeightGridRequest(0, 0, 16, 16, 1)));
        assertEquals(256, h.gets.get());
        assertEquals(1, h.store.loads.get());
        assertEquals(1, h.backend.grids.get());
        assertTrue(h.backend.columns.isEmpty());
        assertEquals(1, h.store.saves.get());
    }

    @Test
    void evictedPublicationIsReloadedFromStoreInsteadOfResampled() throws Exception {
        Harness h = new Harness();
        assertGrid(staleRead(h, () -> h.grid(GRID), () -> assertGrid(h.grid(GRID)), h.values::clear));
        assertEquals(1, h.backend.grids.get());
        assertTrue(h.backend.columns.isEmpty());
        assertEquals(1, h.store.saves.get());
        assertEquals(expected(points(GRID)), h.values);
    }

    @Test
    void publicationMarkerDoesNotInventDataWhenBothCachesLostIt() throws Exception {
        Harness h = new Harness();
        assertGrid(staleRead(h, () -> h.grid(GRID), () -> assertGrid(h.grid(GRID)), () -> {
            h.values.clear();
            h.store.samples.clear();
        }));
        assertEquals(2, h.backend.grids.get(), "版本只要求重查，样本确实丢失时仍应合法重算");
        assertEquals(2, h.store.saves.get());
    }

    @Test
    void recheckReadFailureReachesRegisteredWaiterAndFreshRequestRetries() throws Exception {
        CountDownLatch joining = new CountDownLatch(1), rechecking = new CountDownLatch(1), failNow = new CountDownLatch(1);
        Harness h = new Harness(() -> { joining.countDown(); return 5_000; });
        var fault = new IllegalStateException("controlled recheck read fault");
        h.store.pauseNext.set(true);
        try (var pool = Executors.newCachedThreadPool()) {
            var stale = pool.submit(() -> h.columns(points(GRID)));
            Future<?> waiter = null;
            try {
                await(h.store.snapshotTaken);
                assertGrid(h.grid(GRID));
                h.values.clear(); h.store.samples.clear();
                AtomicBoolean failOnce = new AtomicBoolean(true);
                h.store.onLoad = () -> {
                    if (failOnce.compareAndSet(true, false)) { rechecking.countDown(); await(failNow); throw fault; }
                };
                h.store.release.countDown();
                await(rechecking);
                waiter = pool.submit(() -> h.columns(points(GRID)));
                await(joining);
                failNow.countDown();
                assertSame(fault, assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> stale.get(5, TimeUnit.SECONDS)).getCause());
                Future<?> registered = waiter;
                assertSame(fault, assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> registered.get(5, TimeUnit.SECONDS)).getCause(), "持认领重查失败要把原异常传给已登记等待者");
            } finally {
                h.store.release.countDown(); failNow.countDown();
            }
        }
        h.store.onLoad = () -> {};
        assertSamples(points(GRID), h.columns(points(GRID)));
        assertEquals(1, h.backend.columns.size(), "故障后的新请求应能重新认领并成功计算");
    }

    private static <T> T staleRead(Harness h, Callable<T> follower, Runnable producer, Runnable afterPublish)
            throws Exception {
        h.store.pauseNext.set(true);
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<T> task = pool.submit(follower);
            try {
                await(h.store.snapshotTaken);
                assertFalse(task.isDone(), "必须真正停在旧的存储快照，不能仅验证预热后命中");
                producer.run();
                afterPublish.run();
            } finally {
                h.store.release.countDown();
            }
            return task.get(5, TimeUnit.SECONDS);
        }
    }

    private static List<BlockPos> points(AccurateHeightGridRequest request) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < request.sampleCount(); i++) points.add(new BlockPos(request.blockX(i), 0, request.blockZ(i)));
        return points;
    }

    private static long key(BlockPos p) { return AccurateHeightSample.key(p.getX(), p.getZ()); }

    private static AccurateHeightSample value(BlockPos p) {
        int height = 60 + Math.floorMod(p.getX() * 31 + p.getZ() * 17, 40);
        return new AccurateHeightSample(height, height - 20, height + 5);
    }

    private static Map<Long, AccurateHeightSample> expected(Collection<BlockPos> points) {
        Map<Long, AccurateHeightSample> samples = new LinkedHashMap<>();
        points.forEach(p -> samples.put(key(p), value(p)));
        return samples;
    }

    private static AccurateHeightGrid makeGrid(AccurateHeightGridRequest request) {
        int[] surface = new int[request.sampleCount()], floor = surface.clone(), motion = surface.clone();
        for (int i = 0; i < request.sampleCount(); i++) {
            var value = value(new BlockPos(request.blockX(i), 0, request.blockZ(i)));
            surface[i] = value.worldSurfaceWg(); floor[i] = value.oceanFloorWg(); motion[i] = value.motionBlockingNoLeaves();
        }
        return new AccurateHeightGrid(request, surface, floor, motion);
    }

    private static void assertGrid(AccurateHeightGrid grid) {
        assertNotNull(grid);
        assertTrue(grid.request().sampleCount() > 0);
        for (int i = 0; i < grid.request().sampleCount(); i++) {
            var value = value(new BlockPos(grid.request().blockX(i), 0, grid.request().blockZ(i)));
            assertEquals(value.worldSurfaceWg(), grid.worldSurface()[i]);
            assertEquals(value.oceanFloorWg(), grid.oceanFloor()[i]);
            assertEquals(value.motionBlockingNoLeaves(), grid.motionBlocking()[i]);
        }
    }

    private static void assertSamples(List<BlockPos> positions, Map<Long, AccurateHeightSample> samples) {
        assertFalse(positions.isEmpty());
        assertEquals(expected(positions), samples);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "受控交错未按时到达"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static final class Backend implements AccurateHeightBackend {
        final AtomicInteger grids = new AtomicInteger();
        final List<List<BlockPos>> columns = new CopyOnWriteArrayList<>();
        @Override
        public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request, AccurateSamplingProgress progress) {
            grids.incrementAndGet(); return makeGrid(request);
        }
        @Override
        public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions) {
            columns.add(List.copyOf(positions)); return expected(positions);
        }
        @Override
        public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) {
            Map<Long, AccurateHeightChunk> result = new LinkedHashMap<>();
            for (long key : keys) {
                var grid = makeGrid(new AccurateHeightGridRequest(ChunkPos.getX(key) * 16, ChunkPos.getZ(key) * 16, 16, 16, 1));
                result.put(key, new AccurateHeightChunk(ChunkPos.getX(key), ChunkPos.getZ(key),
                        grid.worldSurface(), grid.oceanFloor(), grid.motionBlocking()));
            }
            return result;
        }
        @Override
        public String backendName() { return "stale-snapshot"; }
    }

    private static final class Store implements AccurateSampleStore {
        final Map<Long, AccurateHeightSample> samples = new ConcurrentHashMap<>();
        final AtomicBoolean pauseNext = new AtomicBoolean();
        final CountDownLatch snapshotTaken = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger(), saves = new AtomicInteger();
        volatile Runnable onLoad = () -> {};
        @Override
        public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) {
            loads.incrementAndGet();
            Map<Long, AccurateHeightSample> result = new LinkedHashMap<>();
            keys.forEach(key -> { if (samples.containsKey(key)) result.put(key, samples.get(key)); });
            onLoad.run();
            if (pauseNext.compareAndSet(true, false)) { snapshotTaken.countDown(); await(release); }
            return result;
        }
        @Override
        public void saveSamples(Map<Long, AccurateHeightSample> values) { saves.incrementAndGet(); samples.putAll(values); }
        @Override
        public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) { return Map.of(); }
        @Override
        public void saveChunks(Map<Long, AccurateHeightChunk> chunks) { saves.incrementAndGet(); }
    }

    private static final class Harness implements AccurateColumnBatch.Cache {
        final Backend backend = new Backend();
        final Store store = new Store();
        final Map<Long, AccurateHeightSample> values = new ConcurrentHashMap<>();
        final AtomicInteger gets = new AtomicInteger();
        final AccurateColumnBatch batch;
        Harness() { this(() -> 5_000); }
        Harness(java.util.function.LongSupplier timeout) { batch = new AccurateColumnBatch(backend, store, this, timeout); }
        AccurateHeightGrid grid(AccurateHeightGridRequest request) { return batch.sampleGridInternal(request, AccurateSamplingProgress.NONE, false); }
        Map<Long, AccurateHeightSample> columns(List<BlockPos> points) { return batch.samplePositionsInternal(points, AccurateSamplingProgress.NONE, true, false); }
        @Override
        public AccurateHeightSample get(long key, int x, int z) { gets.incrementAndGet(); return values.get(key); }
        @Override
        public void put(long key, AccurateHeightSample sample) { values.put(key, sample); }
        @Override
        public void ensureOpen() {}
    }
}
