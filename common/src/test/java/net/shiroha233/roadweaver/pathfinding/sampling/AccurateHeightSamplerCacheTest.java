package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C/D 阶段回归：精确高度采样器的缓存必须留得住"一条路 + 相邻若干条路"的足迹。
 *
 * <p>真机症状：{@code final validation filling missing terrain columns:
 * totalColumns=29333 missingColumns=6439}。根因不是算法，而是按 shard 计算的容量
 * （旧值 1024/16=64 chunk、32768/16=2048 列）小于一条路的足迹，预取完立刻被挤出，
 * 同一批列被反复重采。本测试用一个计数后端把"重采"变成可断言的事实：</p>
 *
 * <ul>
 *   <li>同一批列请求两次，后端只能被真正调用一次；</li>
 *   <li>先取一个目标 chunk，再访问数百个其它 chunk 制造 shard 竞争，目标 chunk
 *       仍然命中缓存（旧容量下会被挤出）；</li>
 *   <li>{@link AccurateHeightSampler#sampleByChunk} 对同一 chunk 的多列只取整块一次，
 *       且每个请求列都能拿到值。</li>
 * </ul>
 */
final class AccurateHeightSamplerCacheTest {

    /** 只统计调用次数并返回确定性高度的后端。 */
    private static final class CountingBackend implements AccurateHeightBackend {
        private final AtomicInteger chunkCalls = new AtomicInteger();
        private final AtomicInteger positionCalls = new AtomicInteger();
        private final AtomicInteger columnsRequested = new AtomicInteger();

        @Override
        public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> chunkKeys) {
            chunkCalls.incrementAndGet();
            Map<Long, AccurateHeightChunk> result = new LinkedHashMap<>();
            for (long key : chunkKeys) {
                int chunkX = ChunkPos.getX(key);
                int chunkZ = ChunkPos.getZ(key);
                int[] worldSurface = new int[AccurateHeightChunk.COLUMN_COUNT];
                int[] oceanFloor = new int[AccurateHeightChunk.COLUMN_COUNT];
                int[] motionBlocking = new int[AccurateHeightChunk.COLUMN_COUNT];
                for (int index = 0; index < AccurateHeightChunk.COLUMN_COUNT; index++) {
                    int value = 64 + Math.floorMod(chunkX * 31 + chunkZ * 17 + index, 24);
                    worldSurface[index] = value;
                    oceanFloor[index] = value - 4;
                    motionBlocking[index] = value;
                }
                result.put(key, new AccurateHeightChunk(chunkX, chunkZ, worldSurface, oceanFloor, motionBlocking));
            }
            return result;
        }

        @Override
        public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions) {
            positionCalls.incrementAndGet();
            columnsRequested.addAndGet(positions == null ? 0 : positions.size());
            Map<Long, AccurateHeightSample> result = new LinkedHashMap<>();
            for (BlockPos position : positions) {
                if (position == null) continue;
                int value = 64 + Math.floorMod(position.getX() * 31 + position.getZ() * 17, 24);
                result.put(AccurateHeightSample.key(position.getX(), position.getZ()),
                        new AccurateHeightSample(value, value - 4, value));
            }
            return result;
        }

        @Override
        public String backendName() {
            return "counting";
        }
    }

    private static AccurateHeightSampler sampler(CountingBackend backend) {
        return new AccurateHeightSampler(null, backend, AccurateSampleStore.noop());
    }

    @Test
    void identicalRequestHitsCacheWithoutResampling() {
        CountingBackend backend = new CountingBackend();
        AccurateHeightSampler sampler = sampler(backend);
        List<BlockPos> positions = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            positions.add(new BlockPos(index * 3, 0, index * 5));
        }

        Map<Long, AccurateHeightSample> first = sampler.samplePositions(positions);
        int callsAfterFirst = backend.chunkCalls.get();
        Map<Long, AccurateHeightSample> second = sampler.samplePositions(positions);

        assertEquals(positions.size(), first.size(), "first request must resolve every column");
        assertEquals(first, second, "second request must return identical samples");
        assertEquals(callsAfterFirst, backend.chunkCalls.get(),
                "second identical request must not resample any chunk");
    }

    @Test
    void targetChunkSurvivesCacheChurnAcrossManyChunks() {
        CountingBackend backend = new CountingBackend();
        AccurateHeightSampler sampler = sampler(backend);
        // 目标 chunk 的一列：先取一次，让它进入缓存。
        BlockPos target = new BlockPos(16, 0, 16);
        sampler.sampleByChunk(List.of(target));
        int callsAfterTarget = backend.chunkCalls.get();
        assertTrue(sampler.cachedSample(target.getX(), target.getZ()) != null,
                "target chunk column must be cached right after the first request");

        // 制造 shard 竞争：访问数百个其它 chunk 的列（每 chunk 2 列，走整块路径）。
        List<BlockPos> churn = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            churn.add(new BlockPos(4096 + index * 16, 0, 4096));
            churn.add(new BlockPos(4096 + index * 16 + 1, 0, 4096));
        }
        sampler.sampleByChunk(churn);
        int callsAfterChurn = backend.chunkCalls.get();
        assertEquals(callsAfterTarget + 1, callsAfterChurn,
                "churn request must be one batch call (no per-column fallback)");

        // 目标 chunk 仍应命中内存缓存：cachedSample 不会触发采样，
        // 缓存被挤出时它会返回 null 而不是重新采样。
        assertTrue(sampler.cachedSample(target.getX(), target.getZ()) != null,
                "target chunk column must stay cached after churn");
        assertEquals(callsAfterChurn, backend.chunkCalls.get(),
                "cache lookup must never trigger a backend call");
        assertTrue(sampler.cachedChunkCount() > 1, "chunk cache must retain multiple chunks");
        assertTrue(sampler.cachedColumnCount() >= 1, "column cache must retain samples");
    }

    @Test
    void sampleByChunkFetchesEachChunkOnceAndResolvesEveryColumn() {
        CountingBackend backend = new CountingBackend();
        AccurateHeightSampler sampler = sampler(backend);
        // 12 个 chunk，每个 chunk 取 6 列：整块路径应当每个 chunk 只调用一次。
        List<BlockPos> positions = new ArrayList<>();
        for (int chunk = 0; chunk < 12; chunk++) {
            int baseX = chunk * 32;
            for (int column = 0; column < 6; column++) {
                positions.add(new BlockPos(baseX + column, 0, 7));
            }
        }

        Map<Long, AccurateHeightSample> samples = sampler.sampleByChunk(positions);

        assertEquals(positions.size(), samples.size(), "every requested column must resolve");
        assertEquals(1, backend.chunkCalls.get(), "all chunks must be fetched in one batch call");
        assertEquals(0, backend.positionCalls.get(), "dense chunks must not use the sparse column path");
        assertEquals(12, sampler.cachedChunkCount(), "each chunk must stay cached");
    }
}
