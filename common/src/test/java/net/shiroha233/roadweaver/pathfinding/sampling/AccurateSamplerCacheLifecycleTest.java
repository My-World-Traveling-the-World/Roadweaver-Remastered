package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 缓存查询也能触发读盘和发布，不能让只读名称绕过关闭协议。 */
final class AccurateSamplerCacheLifecycleTest {
    private static final BlockPos POSITION = new BlockPos(-1, 0, 257);
    private static final long KEY = AccurateHeightSample.key(-1, 257);
    private static final AccurateHeightSample SAMPLE = new AccurateHeightSample(80, 59, 84);

    @Test
    void closedCacheApisRejectBeforeReadingStoreIncludingEmptyBatch() {
        var store = new ReadStore();
        var sampler = sampler(store);
        sampler.clear();
        assertThrows(IllegalStateException.class, () -> sampler.cachedSample(-1, 257));
        assertThrows(IllegalStateException.class, () -> sampler.cachedSamples(List.of(POSITION)));
        assertThrows(IllegalStateException.class, () -> sampler.cachedSamples(List.of()));
        assertEquals(0, store.reads.get());
        assertEquals(0, sampler.cachedColumnCount());
    }

    @Test
    void singleStoredHitArrivingAfterCloseCannotRepublish() { closesDuringRead(false, true); }

    @Test
    void singleStoredMissArrivingAfterCloseIsRejected() { closesDuringRead(false, false); }

    @Test
    void batchStoredHitArrivingAfterCloseCannotRepublish() { closesDuringRead(true, true); }

    @Test
    void batchStoredMissArrivingAfterCloseIsRejected() { closesDuringRead(true, false); }

    private static void closesDuringRead(boolean batch, boolean hit) {
        var store = new ReadStore();
        store.values = hit ? Map.of(KEY, SAMPLE) : Map.of();
        var sampler = sampler(store);
        store.onRead = sampler::clear;
        try {
            assertThrows(IllegalStateException.class, () -> {
                if (batch) sampler.cachedSamples(List.of(POSITION));
                else sampler.cachedSample(-1, 257);
            });
            assertEquals(1, store.reads.get(), "必须实际进入读存储，而非前置判据拒绝夹具");
            assertEquals(0, sampler.cachedColumnCount(), "关闭后的存储结果不得重新填充逐列缓存");
            assertEquals(0, sampler.cachedChunkCount());
        } finally { sampler.clear(); }
    }

    @Test
    void lateColumnAndChunkPublicationAreRejectedAtShardLock() {
        var sampler = sampler(new ReadStore());
        sampler.putColumnCached(KEY, SAMPLE);
        assertEquals(1, sampler.cachedColumnCount());
        sampler.clear();
        // 直接模拟“生产者已过外层检查、清空后才拿到桶锁”，不能只测公开入口拒绝。
        assertThrows(IllegalStateException.class, () -> sampler.putColumnCached(KEY, SAMPLE));
        int[] heights = new int[256];
        var chunk = new AccurateHeightChunk(0, 0, heights, heights.clone(), heights.clone());
        assertThrows(IllegalStateException.class, () -> sampler.putCached(0L, chunk));
        assertThrows(IllegalStateException.class, () -> sampler.getCached(0L));
        assertEquals(0, sampler.cachedColumnCount());
        assertEquals(0, sampler.cachedChunkCount());
    }

    @Test
    void liveReadOnlyQueryPreservesOrderAndCachesHitsWithoutSampling() {
        var store = new ReadStore();
        long second = AccurateHeightSample.key(256, -1);
        store.values = Map.of(KEY, SAMPLE, second, new AccurateHeightSample(90, 61, 95));
        var sampler = sampler(store);
        try {
            var result = sampler.cachedSamples(List.of(new BlockPos(256, 0, -1), POSITION, POSITION));
            assertEquals(List.of(second, KEY), List.copyOf(result.keySet()));
            assertEquals(store.values, result);
            assertEquals(SAMPLE, sampler.cachedSample(-1, 257));
            assertEquals(1, store.reads.get(), "重复查询应命中逐列缓存");
            assertEquals(2, sampler.cachedColumnCount());
        } finally { sampler.clear(); }
    }

    private static AccurateHeightSampler sampler(ReadStore store) {
        AccurateHeightBackend backend = new AccurateHeightBackend() {
            @Override
            public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) {
                throw new AssertionError("只读缓存查询不得调用生成后端");
            }
            @Override
            public String backendName() { return "must-not-sample"; }
        };
        return new AccurateHeightSampler(null, backend, store);
    }

    private static final class ReadStore implements AccurateSampleStore {
        final AtomicInteger reads = new AtomicInteger();
        Map<Long, AccurateHeightSample> values = Map.of(KEY, SAMPLE);
        Runnable onRead = () -> {};
        @Override
        public Map<Long, AccurateHeightSample> loadSamples(Collection<Long> keys) {
            reads.incrementAndGet();
            onRead.run();
            return values;
        }
        @Override
        public Map<Long, AccurateHeightChunk> loadChunks(Collection<Long> keys) { throw new AssertionError(); }
        @Override
        public void saveChunks(Map<Long, AccurateHeightChunk> chunks) { throw new AssertionError(); }
        @Override
        public void saveSamples(Map<Long, AccurateHeightSample> samples) { throw new AssertionError(); }
    }
}
