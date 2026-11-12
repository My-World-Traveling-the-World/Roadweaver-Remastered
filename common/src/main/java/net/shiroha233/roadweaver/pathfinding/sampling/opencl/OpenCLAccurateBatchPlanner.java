/* 文件职责：根据精采程序规模与设备显存限制确定安全的 chunk 子批大小。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.bridge.OpenCLBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精采样 GPU 批次内存规划器。
 */
public final class OpenCLAccurateBatchPlanner {
    private static final long MIN_DEVICE_BUDGET = 16L * 1024L * 1024L;
    private static final int WATCHDOG_SAFE_MAX_CHUNKS = 128;
    /** Must stay in sync with OpenCLAccurateProgram.HEIGHT_PARALLEL_LANES. */
    private static final int PARALLEL_HEIGHT_LANES = 64;
    /** Upper bound of sample columns a single chunk can request at any step. */
    private static final int MAX_COLUMNS_PER_CHUNK = 256;

    private OpenCLAccurateBatchPlanner() {}

    public static int maxChunks(OpenCLBridge.DeviceInfo device,
                                int nodeCount,
                                int interpolatorCount,
                                int latticePointsPerChunk,
                                int aquiferPointsPerChunk) {
        // The accurate kernel runs the height step with HEIGHT_PARALLEL_LANES
        // work-items per sampled column. The old estimate used the raw lattice
        // point count, which is much smaller than columns*lanes, and therefore
        // let too many chunks into one batch. A single scratch buffer then
        // exceeded CL_DEVICE_MAX_MEM_ALLOC_SIZE and every batch failed with
        // clCreateBuffer -61, forcing precise sampling onto CPU even though
        // the GPU was healthy. Size the per-chunk scratch by the real parallel
        // work-items so the batch keeps its largest single buffer under the
        // device's max allocation.
        long parallelWorkPerChunk = Math.multiplyExact(
                (long) PARALLEL_HEIGHT_LANES, (long) MAX_COLUMNS_PER_CHUNK);
        long scratchPerChunk = Math.multiplyExact(
                (long) Math.max(latticePointsPerChunk,
                        Math.max(aquiferPointsPerChunk, parallelWorkPerChunk)),
                Math.multiplyExact((long) Math.max(1, nodeCount), Double.BYTES));
        long latticePerChunk = Math.multiplyExact(
                Math.multiplyExact((long) Math.max(1, interpolatorCount), (long) latticePointsPerChunk),
                Double.BYTES);
        long aquiferPerChunk = Math.multiplyExact((long) Math.max(1, aquiferPointsPerChunk), 5L * Integer.BYTES);
        long outputPerChunk = 3L * 256L * Integer.BYTES;
        long totalPerChunk = Math.addExact(Math.addExact(scratchPerChunk, latticePerChunk),
                Math.addExact(aquiferPerChunk, outputPerChunk));

        long maxAllocation = positiveOr(device.maxAllocationBytes(), 128L * 1024L * 1024L);
        // A batch allocates many buffers at once (params, chunks, lattice,
        // scratch, outputs). Aiming the largest single buffer exactly at
        // maxAllocation always left no room for the rest, so clCreateBuffer
        // returned -61 and precise sampling fell back to CPU. Keep the largest
        // buffer under a safe fraction and cap the whole batch under the global
        // budget so the request actually fits the device.
        long perBufferCap = Math.max(MIN_DEVICE_BUDGET, (long) (maxAllocation * 0.80D));
        // 0.8.340：全局预算改为"真实可用显存 x 配置占比"。原实现用
        // 总显存 x 80% / 4，完全不知道游戏本体已经占了多少；小显存卡上会
        // 申请到超过实空闲的缓冲区。GpuMemoryProbe 优先读 PDH 实时占用，
        // 读不到时退化为「总显存 x 配置占比」，都不行才回到旧的 80%/4。
        long probed = GpuMemoryProbe.configBudgetBytes(device.globalMemoryBytes());
        long globalBudget = probed > 0L
                ? Math.max(MIN_DEVICE_BUDGET, probed / 4L)
                : Math.max(MIN_DEVICE_BUDGET,
                        (long) (positiveOr(device.globalMemoryBytes(), 512L * 1024L * 1024L) * 0.80D) / 4L);
        long byScratch = perBufferCap / Math.max(1L, scratchPerChunk);
        long byLattice = perBufferCap / Math.max(1L, latticePerChunk);
        long byTotal = globalBudget / Math.max(1L, totalPerChunk);
        long result = Math.min(byScratch, Math.min(byLattice, byTotal));
        if (result <= 0L) {
            return 0;
        }
        // 保持在桌面 GPU 看门狗和大分配拐点内；整个区域仍由一次高优先级设备任务连续提交。
        return (int) Math.min(WATCHDOG_SAFE_MAX_CHUNKS, result);
    }

    static List<List<Long>> spatialBatches(List<Long> chunkKeys, int maxChunks) {
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks must be positive");
        }
        if (chunkKeys.isEmpty()) {
            return List.of();
        }

        int tileSize = Math.max(1, (int) Math.floor(Math.sqrt(maxChunks)));
        Map<Tile, List<Long>> chunksByTile = new LinkedHashMap<>();
        for (long chunkKey : chunkKeys) {
            int tileX = Math.floorDiv(ChunkPos.getX(chunkKey), tileSize);
            int tileZ = Math.floorDiv(ChunkPos.getZ(chunkKey), tileSize);
            chunksByTile.computeIfAbsent(new Tile(tileX, tileZ), ignored -> new ArrayList<>()).add(chunkKey);
        }

        List<List<Long>> batches = new ArrayList<>(chunksByTile.size());
        for (List<Long> tileChunks : chunksByTile.values()) {
            for (int offset = 0; offset < tileChunks.size(); offset += maxChunks) {
                batches.add(List.copyOf(tileChunks.subList(offset, Math.min(tileChunks.size(), offset + maxChunks))));
            }
        }
        return List.copyOf(batches);
    }

    private static long positiveOr(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    private record Tile(int x, int z) {}
}
