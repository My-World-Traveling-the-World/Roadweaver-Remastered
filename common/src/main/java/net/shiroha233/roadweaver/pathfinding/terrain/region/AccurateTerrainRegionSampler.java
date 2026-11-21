/* 文件职责：批量构建规划区域的精确量化地形场，不依赖粗地形瓦片。 */
package net.shiroha233.roadweaver.pathfinding.terrain.region;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingProgress;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 区域级精确地形场构建器。
 */
public final class AccurateTerrainRegionSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private AccurateTerrainRegionSampler() {}

    @SuppressWarnings("unchecked")
    public static AccurateTerrainRegion sample(ServerLevel level,
                                               TerrainSamplingCache cache,
                                               int minBlockX,
                                               int minBlockZ,
                                               int maxBlockX,
                                               int maxBlockZ,
                                               int step) {
        return sampleInternal(level, cache, minBlockX, minBlockZ, maxBlockX, maxBlockZ, step,
                false, AccurateSamplingProgress.NONE, true);
    }

    public static AccurateTerrainRegion sampleAccelerated(ServerLevel level,
                                                          TerrainSamplingCache cache,
                                                          int minBlockX,
                                                          int minBlockZ,
                                                          int maxBlockX,
                                                          int maxBlockZ,
                                                          int step) {
        return sampleInternal(level, cache, minBlockX, minBlockZ, maxBlockX, maxBlockZ, step,
                true, AccurateSamplingProgress.NONE, true);
    }

    /** 局部采样限定在冲突圆窗内，避免修一栋建筑扫描整条路。 */
    public static AccurateTerrainRegion sampleAcceleratedMasked(ServerLevel level,
                                                                TerrainSamplingCache cache,
                                                                int minBlockX,
                                                                int minBlockZ,
                                                                int maxBlockX,
                                                                int maxBlockZ,
                                                                int step,
                                                                BlockPos center,
                                                                int radius,
                                                                BlockPos routeStart,
                                                                BlockPos routeGoal) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cache, "cache");
        AccurateRegionBounds bounds = checkedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, step);
        int safeRadius = Math.max(1, radius);
        ArrayList<BlockPos> positions = new ArrayList<>();
        long radiusSquared = (long) safeRadius * safeRadius;
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += bounds.step()) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += bounds.step()) {
                long dx = (long) x - center.getX();
                long dz = (long) z - center.getZ();
                if (dx * dx + dz * dz <= radiusSquared) positions.add(new BlockPos(x, 0, z));
            }
        }
        if (routeStart != null) positions.add(new BlockPos(routeStart.getX(), 0, routeStart.getZ()));
        if (routeGoal != null) positions.add(new BlockPos(routeGoal.getX(), 0, routeGoal.getZ()));
        AccurateHeightSampler sampler = cache.getAccurateSampler(level);
        Map<Long, net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample> sampled =
                sampler.samplePositions(positions);
        int size = Math.toIntExact(bounds.sampleCount());
        short[] heights = new short[size];
        short[] oceanFloors = new short[size];
        byte[] flags = new byte[size];
        @SuppressWarnings("unchecked") Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[size];
        int seaLevel = level.getSeaLevel();
        for (int index = 0; index < size; index++) {
            int x = bounds.blockXAt(index % bounds.width());
            int z = bounds.blockZAt(index / bounds.width());
            var sample = sampled.get(net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample.key(x, z));
            if (sample == null) {
                heights[index] = (short) seaLevel;
                oceanFloors[index] = (short) seaLevel;
                // The rectangular array is only an internal storage envelope.
                // Do not query biome/terrain metadata outside the circular
                // request mask; those cells remain inert defaults and are
                // rejected by AccurateTerrainRegion.contains().
                biomes[index] = null;
                continue;
            }
            int worldSurface = sample.worldSurfaceWg();
            int motionBlocking = sample.motionBlockingNoLeaves();
            int oceanFloor = sample.oceanFloorWg();
            int height = motionBlocking > seaLevel + 2 ? motionBlocking : worldSurface;
            Holder<Biome> biome = cache.getBiome(level, x, z);
            boolean waterBiome = biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_OCEAN)
                    || biome.is(BiomeTags.IS_DEEP_OCEAN);
            boolean columnWater = SurfaceWaterClassifier.isWaterColumn(worldSurface, oceanFloor, waterBiome, seaLevel);
            heights[index] = (short) height;
            oceanFloors[index] = (short) oceanFloor;
            flags[index] = AccurateTerrainRegion.flags(columnWater, waterBiome);
            biomes[index] = biome;
        }
        LOGGER.info("【圆形局部精确采样完成】 " + "RoadWeaver circular local exact sampling: center=({}, {}) radius={} step={} sampledColumns={} gridColumns={}",
                center.getX(), center.getZ(), safeRadius, bounds.step(), positions.size(), size);
        return new AccurateTerrainRegion(bounds, seaLevel, heights, oceanFloors, flags, biomes,
                center, safeRadius, routeStart, routeGoal);
    }

    public static AccurateTerrainRegion sampleForMap(ServerLevel level,
                                                      TerrainSamplingCache cache,
                                                      int minBlockX,
                                                      int minBlockZ,
                                                      int maxBlockX,
                                                      int maxBlockZ,
                                                      int step,
                                                      boolean acceleratedOnly,
                                                      AccurateSamplingProgress progress) {
        return sampleInternal(level, cache, minBlockX, minBlockZ, maxBlockX, maxBlockZ, step,
                acceleratedOnly, progress, false);
    }

    public static Optional<AccurateTerrainRegion> restoreStored(ServerLevel level,
                                                               TerrainSamplingCache cache,
                                                               int minBlockX,
                                                               int minBlockZ,
                                                               int maxBlockX,
                                                               int maxBlockZ,
                                                               int step) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cache, "cache");
        AccurateRegionBounds bounds = checkedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, step);
        AccurateHeightGridRequest request = new AccurateHeightGridRequest(
                bounds.minX(), bounds.minZ(), bounds.width(), bounds.height(), bounds.step());
        return cache.getAccurateSampler(level)
                .loadStoredGrid(request)
                .map(grid -> buildRegion(level, cache, bounds, grid));
    }

    @SuppressWarnings("unchecked")
    private static AccurateTerrainRegion sampleInternal(ServerLevel level,
                                                        TerrainSamplingCache cache,
                                                        int minBlockX,
                                                        int minBlockZ,
                                                        int maxBlockX,
                                                        int maxBlockZ,
                                                        int step,
                                                        boolean acceleratedOnly,
                                                        AccurateSamplingProgress progress,
                                                        boolean publishInitialGenerationProgress) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cache, "cache");

        AccurateRegionBounds bounds = checkedBounds(minBlockX, minBlockZ, maxBlockX, maxBlockZ, step);
        long sampleCount = bounds.sampleCount();

        if (publishInitialGenerationProgress) {
            InitialGenerationProgressTracker.enterStage(InitialGenerationStage.EXACT_SAMPLING,
                    "sampling_accurate_region");
            InitialGenerationProgressTracker.setExactSamplingPlan(sampleCount, "sampling_accurate_region");
        }

        AccurateHeightGridRequest gridRequest = new AccurateHeightGridRequest(
                bounds.minX(), bounds.minZ(), bounds.width(), bounds.height(), bounds.step());

        AccurateHeightSampler sampler = cache.getAccurateSampler(level);
        AccurateSamplingProgress externalProgress = progress == null
                ? AccurateSamplingProgress.NONE
                : progress;
        AccurateSamplingProgress progressSink = batch -> {
            externalProgress.onBatch(batch);
            if (publishInitialGenerationProgress) {
                InitialGenerationProgressTracker.recordExactSampleBatch(
                        batch.batchColumns(),
                        batch.batchNanos() / 1_000_000L,
                        sampler.backendName(),
                        sampler.deviceName());
            }
        };
        if (publishInitialGenerationProgress) {
            InitialGenerationProgressTracker.setBackend(sampler.backendName(), sampler.deviceName(), "");
        }
        AccurateSamplingStats.BackendSnapshot statsBefore = AccurateSamplingStats.backendSnapshot();
        long heightSamplingStartedAt = System.nanoTime();
        AccurateHeightGrid sampledHeights = acceleratedOnly
                ? sampler.sampleAcceleratedGrid(gridRequest, progressSink)
                : sampler.sampleGrid(gridRequest, progressSink);
        long heightSamplingNanos = System.nanoTime() - heightSamplingStartedAt;
        AccurateSamplingStats.BackendSnapshot statsAfter = AccurateSamplingStats.backendSnapshot();
        long metadataStartedAt = System.nanoTime();
        AccurateTerrainRegion region = buildRegion(level, cache, bounds, sampledHeights);
        long metadataNanos = System.nanoTime() - metadataStartedAt;

        if (publishInitialGenerationProgress) {
            InitialGenerationProgressTracker.completeExactSampling();
        }
        LOGGER.info("精确量化地形区域已完成 dimension={} samples={} step={} backend={} columnsPerSec={} "
                        + "heightMs={} kernelMs={} latticeMs={} preliminaryMs={} aquiferMs={} scanMs={} queueMs={} "
                        + "hybridGpuRequests={} hybridGpuColumns={} hybridCpuDivertedRequests={} "
                        + "hybridCpuDivertedColumns={} metadataMs={}",
                level.dimension().location(), sampleCount, bounds.step(), sampler.backendName(),
                Math.round(sampleCount * 1_000_000_000.0 / Math.max(1L, heightSamplingNanos)),
                heightSamplingNanos / 1_000_000L,
                deltaMillis(statsAfter.gpuKernelNanos(), statsBefore.gpuKernelNanos()),
                deltaMillis(statsAfter.gpuLatticeKernelNanos(), statsBefore.gpuLatticeKernelNanos()),
                deltaMillis(statsAfter.gpuPreliminaryKernelNanos(), statsBefore.gpuPreliminaryKernelNanos()),
                deltaMillis(statsAfter.gpuAquiferKernelNanos(), statsBefore.gpuAquiferKernelNanos()),
                deltaMillis(statsAfter.gpuHeightKernelNanos(), statsBefore.gpuHeightKernelNanos()),
                deltaMillis(statsAfter.gpuQueueWaitNanos(), statsBefore.gpuQueueWaitNanos()),
                statsAfter.hybridGpuRequests() - statsBefore.hybridGpuRequests(),
                statsAfter.hybridGpuColumns() - statsBefore.hybridGpuColumns(),
                statsAfter.hybridCpuDivertedRequests() - statsBefore.hybridCpuDivertedRequests(),
                statsAfter.hybridCpuDivertedColumns() - statsBefore.hybridCpuDivertedColumns(),
                metadataNanos / 1_000_000L);
        return region;
    }

    private static AccurateRegionBounds checkedBounds(int minBlockX,
                                                      int minBlockZ,
                                                      int maxBlockX,
                                                      int maxBlockZ,
                                                      int step) {
        AccurateRegionBounds bounds = AccurateRegionBounds.aligned(
                minBlockX, minBlockZ, maxBlockX, maxBlockZ,
                Math.max(RoadConstants.PATH_GRID_STEP_MIN, step));
        long sampleCount = bounds.sampleCount();
        if (sampleCount > RoadConstants.ACCURATE_REGION_MAX_SAMPLES) {
            throw new AccurateRegionLimitExceededException(
                    sampleCount, RoadConstants.ACCURATE_REGION_MAX_SAMPLES);
        }
        return bounds;
    }

    @SuppressWarnings("unchecked")
    private static AccurateTerrainRegion buildRegion(ServerLevel level,
                                                     TerrainSamplingCache cache,
                                                     AccurateRegionBounds bounds,
                                                     AccurateHeightGrid sampledHeights) {
        AccurateHeightGridRequest gridRequest = sampledHeights.request();
        int size = gridRequest.sampleCount();
        short[] heights = new short[size];
        short[] oceanFloors = new short[size];
        byte[] flags = new byte[size];
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[size];
        int seaLevel = level.getSeaLevel();
        for (int index = 0; index < size; index++) {
            int blockX = gridRequest.blockX(index);
            int blockZ = gridRequest.blockZ(index);
            int worldSurface = sampledHeights.worldSurface()[index];
            int motionBlocking = sampledHeights.motionBlocking()[index];
            int oceanFloor = sampledHeights.oceanFloor()[index];
            int height = motionBlocking > seaLevel + 2 ? motionBlocking : worldSurface;
            Holder<Biome> biome = cache.getBiome(level, blockX, blockZ);
            boolean waterBiome = biome.is(BiomeTags.IS_RIVER)
                    || biome.is(BiomeTags.IS_OCEAN)
                    || biome.is(BiomeTags.IS_DEEP_OCEAN);
            boolean columnWater = SurfaceWaterClassifier.isWaterColumn(
                    worldSurface, oceanFloor, waterBiome, seaLevel);
            heights[index] = (short) height;
            oceanFloors[index] = (short) oceanFloor;
            flags[index] = AccurateTerrainRegion.flags(columnWater, waterBiome);
            biomes[index] = biome;
        }
        return new AccurateTerrainRegion(bounds, seaLevel, heights, oceanFloors, flags, biomes);
    }

    private static long deltaMillis(long after, long before) {
        return Math.max(0L, after - before) / 1_000_000L;
    }
}
