/* 文件职责：快速高度采样器。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 快速高度采样器
 */
public final class FastHeightSampler {

    private static final double DENSITY_THRESHOLD = 0.390625D;

    private final DensityFunction initialDensity;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();

    private FastHeightSampler(DensityFunction initialDensity, NoiseSettings settings) {
        this.initialDensity = initialDensity;
        this.minY = settings.minY();
        this.maxY = minY + settings.height();
        this.cellHeight = settings.getCellHeight();
    }

    public static FastHeightSampler create(ServerLevel level) {
        var chunkSource = level.getChunkSource();
        RandomState randomState = chunkSource.getGeneratorState().randomState();
        NoiseRouter router = randomState.router();
        NoiseSettings settings = getNoiseSettings(level);
        return new FastHeightSampler(router.initialDensityWithoutJaggedness(), settings);
    }

    public int sampleHeight(int x, int z) {
        int alignedX = (x >> 2) << 2;
        int alignedZ = (z >> 2) << 2;
        long key = packXZ(alignedX, alignedZ);
        Integer cached = heightCache.get(key);
        if (cached != null) return cached;

        int height = computeHeight(alignedX, alignedZ);
        heightCache.put(key, height);
        return height;
    }

    private int computeHeight(int x, int z) {
        // Binary-search the surface instead of walking every cell from the world top.
        // initialDensityWithoutJaggedness is monotonic non-decreasing as y decreases
        // (air above -> solid below), so the first cell whose density crosses the
        // threshold is the surface. Bit-identical to the old top-down walk, but cuts
        // density evaluations from ~cellCountY to ~log2(cellCountY) — a large win on
        // the 2048-tall real world.
        int steps = (maxY - minY) / cellHeight;
        int lo = 0;
        int hi = steps;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (densityAt(x, maxY - mid * cellHeight, z) > DENSITY_THRESHOLD) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        int y = maxY - lo * cellHeight;
        return densityAt(x, y, z) > DENSITY_THRESHOLD ? y : minY;
    }

    private double densityAt(int x, int y, int z) {
        return initialDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static NoiseSettings getNoiseSettings(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        if (generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noiseGen) {
            return noiseGen.generatorSettings().value().noiseSettings();
        }
        return NoiseSettings.create(-64, 384, 1, 2);
    }

    /**
     * 释放缓存。用新实例替换而非 clear()，确保内部 table 数组被 GC 回收。
     */
    public void clearCache() {
        heightCache = new ConcurrentHashMap<>();
    }

    public int getCacheSize() {
        return heightCache.size();
    }
}
