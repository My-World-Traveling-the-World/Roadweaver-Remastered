/* 文件职责：直接验证 CPU 生产入口的分块、请求列和三通道结果，不用空集合冒充正确。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class CpuAccurateHeightBackendTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fullChunksKeepAllKeysAndAllChannels(boolean columns) {
        Source source = new Source(columns);
        Work work = new Work();
        var backend = new CpuAccurateHeightBackend(source, work);
        List<Long> keys = List.of(ChunkPos.asLong(-2, 1), ChunkPos.asLong(0, -1), ChunkPos.asLong(3, 2));
        var actual = backend.sampleChunks(keys);
        assertEquals(new HashSet<>(keys), actual.keySet());
        assertEquals(1, work.parallelCalls);
        assertEquals(3, source.calls.get());
        for (long key : keys) {
            AccurateHeightChunk chunk = actual.get(key);
            assertEquals(key, chunk.chunkKey());
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int bx = (ChunkPos.getX(key) << 4) + x;
                int bz = (ChunkPos.getZ(key) << 4) + z;
                assertEquals(expected(bx, bz), new AccurateHeightSample(
                        chunk.worldSurfaceWg(bx, bz), chunk.oceanFloorWg(bx, bz), chunk.motionBlockingNoLeaves(bx, bz)));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void positionsDeduplicateXZAndOnlyReadRequestedColumns(boolean columns) {
        Source source = new Source(columns);
        Work work = new Work();
        var backend = new CpuAccurateHeightBackend(source, work);
        List<BlockPos> points = Arrays.asList(new BlockPos(-17, 5, -1), null,
                new BlockPos(-17, 99, -1), new BlockPos(-16, 4, -1), new BlockPos(17, 3, 16));
        var actual = backend.samplePositions(points);
        Map<Long, AccurateHeightSample> expected = new HashMap<>();
        for (BlockPos point : points) if (point != null)
            expected.put(AccurateHeightSample.key(point.getX(), point.getZ()), expected(point.getX(), point.getZ()));
        assertEquals(3, actual.size());
        assertEquals(expected, actual);
        assertEquals(columns ? 1 : 0, work.parallelCalls);
        assertEquals(columns ? 0 : 1, work.sequentialCalls);
        if (columns) assertEquals(expected.keySet(), source.requestedColumns);
        assertEquals(3, source.calls.get());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void gridKeepsSignedCoordinatesAndReportsCompletionOnce(boolean columns) {
        Source source = new Source(columns);
        Work work = new Work();
        var backend = new CpuAccurateHeightBackend(source, work);
        var request = new AccurateHeightGridRequest(-19, -7, 9, 6, 5);
        List<AccurateSamplingProgress.Batch> progress = new ArrayList<>();
        var actual = backend.sampleGrid(request, progress::add);
        assertEquals(request, actual.request());
        assertEquals(54, actual.worldSurface().length);
        Set<Long> expectedKeys = new HashSet<>();
        for (int index = 0; index < 54; index++) {
            int x = -19 + (index % 9) * 5;
            int z = -7 + (index / 9) * 5;
            expectedKeys.add(AccurateHeightSample.key(x, z));
            assertEquals(expected(x, z), new AccurateHeightSample(
                    actual.worldSurface()[index], actual.oceanFloor()[index], actual.motionBlocking()[index]));
        }
        if (columns) assertEquals(expectedKeys, source.requestedColumns);
        assertEquals(columns ? 1 : 0, work.parallelCalls);
        assertEquals(columns ? 0 : 1, work.sequentialCalls);
        assertEquals(1, progress.size());
    }

    @Test void emptyInputsDoNotReadWorldOrExecuteWork() {
        Source source = new Source(true);
        Work work = new Work();
        var backend = new CpuAccurateHeightBackend(source, work);
        assertEquals(Map.of(), backend.sampleChunks(null));
        assertEquals(Map.of(), backend.sampleChunks(List.of()));
        assertEquals(Map.of(), backend.samplePositions(null));
        assertEquals(Map.of(), backend.samplePositions(Collections.singletonList(null)));
        assertEquals(0, source.calls.get());
        assertEquals(0, work.parallelCalls + work.sequentialCalls);
    }

    @Test void failureIsNotTurnedIntoPartialSuccessAndDoesNotPublishProgress() {
        IllegalStateException fault = new IllegalStateException("height read failed");
        Source source = new Source(true) {
            @Override public AccurateHeightChunk sampleColumns(int x, int z, int[] columns) { throw fault; }
        };
        // 同步执行替身固定异常顺序，断言门面原样传播而不是并行框架的包装细节。
        Work work = new Work() {
            @Override public void parallel(int count, IntConsumer action) { sequential(count, action); }
        };
        var backend = new CpuAccurateHeightBackend(source, work);
        List<AccurateSamplingProgress.Batch> progress = new ArrayList<>();
        assertSame(fault, assertThrows(IllegalStateException.class,
                () -> backend.sampleGrid(new AccurateHeightGridRequest(0, 0, 2, 2, 1), progress::add)));
        assertTrue(progress.isEmpty());
    }

    @Test void closeReleasesWorldSampler() {
        Source source = new Source(true);
        new CpuAccurateHeightBackend(source, new Work()).close();
        assertTrue(source.closed);
    }

    static AccurateHeightSample expected(int x, int z) {
        int value = x * 31 + z * 7;
        return new AccurateHeightSample(value + 3, value - 19, value + 41);
    }

    static class Work implements CpuSamplingWork {
        int parallelCalls;
        int sequentialCalls;
        @Override public void parallel(int count, IntConsumer action) {
            parallelCalls++;
            CpuSamplingWork.UNRESTRICTED.parallel(count, action);
        }
        @Override public void sequential(int count, IntConsumer action) {
            sequentialCalls++;
            CpuSamplingWork.UNRESTRICTED.sequential(count, action);
        }
    }

    static class Source implements CpuAccurateHeightSource {
        final boolean columns;
        final AtomicInteger calls = new AtomicInteger();
        final Set<Long> requestedColumns = ConcurrentHashMap.newKeySet();
        boolean closed;
        Source(boolean columns) { this.columns = columns; }
        @Override public boolean supportsColumns() { return columns; }
        @Override public AccurateHeightChunk sampleChunk(int x, int z) {
            int[] columns = java.util.stream.IntStream.range(0, 256).toArray();
            return sampleColumns(x, z, columns);
        }
        @Override public AccurateHeightChunk sampleColumns(int x, int z, int[] columns) {
            calls.incrementAndGet();
            // 未请求列填哨兵；生产若读错列，即使结果数正确也会失败。
            int[] a = new int[256], b = new int[256], c = new int[256];
            Arrays.fill(a, Integer.MIN_VALUE);
            Arrays.fill(b, Integer.MIN_VALUE);
            Arrays.fill(c, Integer.MIN_VALUE);
            for (int column : columns) {
                int bx = (x << 4) + (column & 15), bz = (z << 4) + (column >> 4);
                requestedColumns.add(AccurateHeightSample.key(bx, bz));
                var sample = expected(bx, bz);
                a[column] = sample.worldSurfaceWg(); b[column] = sample.oceanFloorWg(); c[column] = sample.motionBlockingNoLeaves();
            }
            return new AccurateHeightChunk(x, z, a, b, c);
        }
        @Override public AccurateHeightSample sampleBaseHeights(int x, int z) {
            calls.incrementAndGet();
            return expected(x, z);
        }
        @Override public void close() { closed = true; }
    }
}
