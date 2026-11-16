/* 文件职责：封装原版 NoiseChunk 与非噪声生成器的三通道世界读取。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

final class WorldCpuAccurateHeightSource implements CpuAccurateHeightSource {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final NoiseChunkHeightSampler noiseChunkSampler;

    WorldCpuAccurateHeightSource(ServerLevel level) {
        this.level = level;
        generator = level.getChunkSource().getGenerator();
        randomState = level.getChunkSource().getGeneratorState().randomState();
        // 保留原生成器分流；只搬读取边界，不让测试替身改变实际采样算法。
        noiseChunkSampler = generator instanceof NoiseBasedChunkGenerator noiseGenerator
                ? NoiseChunkHeightSampler.create(level, noiseGenerator, randomState) : null;
    }

    @Override public boolean supportsColumns() { return noiseChunkSampler != null; }

    @Override public AccurateHeightChunk sampleChunk(int chunkX, int chunkZ) {
        if (noiseChunkSampler != null) return noiseChunkSampler.sampleChunk(chunkX, chunkZ);
        int[] worldSurface = new int[AccurateHeightChunk.COLUMN_COUNT];
        int[] oceanFloor = new int[AccurateHeightChunk.COLUMN_COUNT];
        int[] motionBlocking = new int[AccurateHeightChunk.COLUMN_COUNT];
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int index = localX + (localZ << 4);
                int x = minBlockX + localX;
                int z = minBlockZ + localZ;
                worldSurface[index] = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                oceanFloor[index] = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                motionBlocking[index] = generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, randomState);
            }
        }
        return new AccurateHeightChunk(chunkX, chunkZ, worldSurface, oceanFloor, motionBlocking);
    }

    @Override public AccurateHeightChunk sampleColumns(int chunkX, int chunkZ, int[] columns) {
        return noiseChunkSampler.sampleChunkColumns(chunkX, chunkZ, columns);
    }

    @Override public AccurateHeightSample sampleBaseHeights(int x, int z) {
        return new AccurateHeightSample(
                generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState),
                generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState),
                generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, randomState));
    }

    @Override public void close() {
        if (noiseChunkSampler != null) noiseChunkSampler.clear();
    }
}
