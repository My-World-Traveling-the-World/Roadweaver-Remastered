/* 文件职责：通过原版 NoiseChunk 或 ChunkGenerator 生成 CPU 精确高度图。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 精确采样 CPU 基准实现，也是 OpenCL 的正确性回退源。
 */
public final class CpuAccurateHeightBackend implements AccurateHeightBackend {
    private final CpuAccurateHeightSource source;
    private final CpuSamplingWork work;

    // 世界读取与执行方式分开注入，才能在离线回归中核对真正返回的三通道产物。
    CpuAccurateHeightBackend(CpuAccurateHeightSource source, CpuSamplingWork work) {
        this.source = java.util.Objects.requireNonNull(source);
        this.work = java.util.Objects.requireNonNull(work);
    }

    public static CpuAccurateHeightBackend create(ServerLevel level) {
        return fromSource(new WorldCpuAccurateHeightSource(level));
    }

    // 公开工厂与离线测试共用这一出口，防止测试已限流而实际世界仍接无限制执行器。
    static CpuAccurateHeightBackend fromSource(CpuAccurateHeightSource source) {
        return new CpuAccurateHeightBackend(source, CpuSamplingWork.RUNTIME);
    }

    @Override
    public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return Map.of();
        }
        long startedAt = System.nanoTime();
        List<Long> keys = new ArrayList<>(chunkKeys);
        ConcurrentHashMap<Long, AccurateHeightChunk> result = new ConcurrentHashMap<>();
        work.parallel(keys.size(), index -> {
            long key = keys.get(index);
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            result.put(key, source.sampleChunk(chunkX, chunkZ));
        });
        AccurateSamplingStats.recordCpuBatch(result.size(), System.nanoTime() - startedAt);
        return result;
    }

    @Override
    public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos position : positions) {
            if (position != null) {
                unique.putIfAbsent(AccurateHeightSample.key(position.getX(), position.getZ()), position);
            }
        }
        if (unique.isEmpty()) {
            return Map.of();
        }

        long startedAt = System.nanoTime();
        LinkedHashMap<Long, AccurateHeightSample> result = new LinkedHashMap<>(unique.size());
        LinkedHashMap<Long, List<Map.Entry<Long, BlockPos>>> byChunk = groupPositionsByChunk(unique);
        if (source.supportsColumns()) {
            // 每块仍只取请求列；执行端口不参与坐标、列选择或高度计算。
            ConcurrentHashMap<Long, AccurateHeightSample> parallel = new ConcurrentHashMap<>();
            var chunks = new ArrayList<>(byChunk.entrySet());
            work.parallel(chunks.size(), chunkIndex -> {
                var chunkEntry = chunks.get(chunkIndex);
                long chunkKey = chunkEntry.getKey();
                List<Map.Entry<Long, BlockPos>> chunkPositions = chunkEntry.getValue();
                int[] columns = new int[chunkPositions.size()];
                for (int index = 0; index < chunkPositions.size(); index++) {
                    BlockPos position = chunkPositions.get(index).getValue();
                    columns[index] = localIndex(position.getX(), position.getZ());
                }
                AccurateHeightChunk chunk = source.sampleColumns(
                        ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), columns);
                for (Map.Entry<Long, BlockPos> entry : chunkPositions) {
                    BlockPos position = entry.getValue();
                    parallel.put(entry.getKey(), sampleFromChunk(chunk, position.getX(), position.getZ()));
                }
            });
            result.putAll(parallel);
        } else {
            var entries = new ArrayList<>(unique.entrySet());
            work.sequential(entries.size(), index -> {
                var entry = entries.get(index);
                BlockPos position = entry.getValue();
                result.put(entry.getKey(), source.sampleBaseHeights(position.getX(), position.getZ()));
            });
        }
        AccurateSamplingStats.recordCpuBatch(byChunk.size(), System.nanoTime() - startedAt);
        return result;
    }

    @Override
    public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request,
                                         AccurateSamplingProgress progress) {
        long startedAt = System.nanoTime();
        int sampleCount = request.sampleCount();
        int[] worldSurface = new int[sampleCount];
        int[] oceanFloor = new int[sampleCount];
        int[] motionBlocking = new int[sampleCount];
        LinkedHashMap<Long, IntList> indexesByChunk = groupGridIndexesByChunk(request);

        if (source.supportsColumns()) {
            var chunks = new ArrayList<>(indexesByChunk.entrySet());
            work.parallel(chunks.size(), chunkIndex -> {
                var entry = chunks.get(chunkIndex);
                long chunkKey = entry.getKey();
                IntList gridIndexes = entry.getValue();
                int[] columns = new int[gridIndexes.size()];
                for (int offset = 0; offset < gridIndexes.size(); offset++) {
                    int gridIndex = gridIndexes.get(offset);
                    columns[offset] = localIndex(request.blockX(gridIndex), request.blockZ(gridIndex));
                }
                AccurateHeightChunk chunk = source.sampleColumns(
                        ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), columns);
                for (int offset = 0; offset < gridIndexes.size(); offset++) {
                    int gridIndex = gridIndexes.get(offset);
                    int blockX = request.blockX(gridIndex);
                    int blockZ = request.blockZ(gridIndex);
                    worldSurface[gridIndex] = chunk.worldSurfaceWg(blockX, blockZ);
                    oceanFloor[gridIndex] = chunk.oceanFloorWg(blockX, blockZ);
                    motionBlocking[gridIndex] = chunk.motionBlockingNoLeaves(blockX, blockZ);
                }
            });
        } else {
            work.sequential(sampleCount, index -> {
                AccurateHeightSample sample = source.sampleBaseHeights(request.blockX(index), request.blockZ(index));
                worldSurface[index] = sample.worldSurfaceWg();
                oceanFloor[index] = sample.oceanFloorWg();
                motionBlocking[index] = sample.motionBlockingNoLeaves();
            });
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        AccurateSamplingStats.recordCpuBatch(indexesByChunk.size(), elapsedNanos);
        AccurateSamplingProgress sink = progress == null ? AccurateSamplingProgress.NONE : progress;
        sink.onBatch(new AccurateSamplingProgress.Batch(
                sampleCount, sampleCount, sampleCount, elapsedNanos, elapsedNanos, 0L));
        return new AccurateHeightGrid(request, worldSurface, oceanFloor, motionBlocking);
    }

    private static AccurateHeightSample sampleFromChunk(AccurateHeightChunk chunk, int x, int z) {
        return new AccurateHeightSample(
                chunk.worldSurfaceWg(x, z),
                chunk.oceanFloorWg(x, z),
                chunk.motionBlockingNoLeaves(x, z));
    }

    private static int localIndex(int blockX, int blockZ) {
        return (blockX & 15) + ((blockZ & 15) << 4);
    }

    private static LinkedHashMap<Long, List<Map.Entry<Long, BlockPos>>> groupPositionsByChunk(
            LinkedHashMap<Long, BlockPos> positions) {
        LinkedHashMap<Long, List<Map.Entry<Long, BlockPos>>> grouped = new LinkedHashMap<>();
        for (Map.Entry<Long, BlockPos> entry : positions.entrySet()) {
            BlockPos position = entry.getValue();
            long chunkKey = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
            grouped.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static LinkedHashMap<Long, IntList> groupGridIndexesByChunk(AccurateHeightGridRequest request) {
        LinkedHashMap<Long, IntList> grouped = new LinkedHashMap<>();
        for (int index = 0; index < request.sampleCount(); index++) {
            long chunkKey = ChunkPos.asLong(request.blockX(index) >> 4, request.blockZ(index) >> 4);
            grouped.computeIfAbsent(chunkKey, ignored -> new IntList()).add(index);
        }
        return grouped;
    }

    private static final class IntList {
        private int[] values = new int[4];
        private int size;

        private void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        private int get(int index) {
            return values[index];
        }

        private int size() {
            return size;
        }
    }

    @Override
    public String backendName() {
        return "CPU";
    }

    @Override
    public void close() {
        source.close();
    }
}
