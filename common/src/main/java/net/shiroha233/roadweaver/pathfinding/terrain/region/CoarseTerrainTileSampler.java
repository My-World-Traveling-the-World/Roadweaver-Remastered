package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.RandomState;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.map.tile.render.HeightShader;
import net.shiroha233.roadweaver.map.tile.render.TerrainTilePalette;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchSamplers;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.RuntimeCoarseGpuBudget;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个粗采样地形瓦片采样器。
 */
public final class CoarseTerrainTileSampler {
    private CoarseTerrainTileSampler() {}

    private static final AtomicLong TIMING_TILES = new AtomicLong();
    private static final AtomicLong TIMING_HEIGHT_BATCH_NANOS = new AtomicLong();
    private static final AtomicLong TIMING_BIOME_NANOS = new AtomicLong();
    private static final AtomicLong TIMING_NEAR_WATER_NANOS = new AtomicLong();
    private static final AtomicLong TIMING_COLOR_NANOS = new AtomicLong();

    public record TimingSnapshot(long tiles,
                                 long heightBatchNanos,
                                 long biomeNanos,
                                 long nearWaterNanos,
                                 long colorNanos) {
        public TimingSnapshot minus(TimingSnapshot before) {
            if (before == null) return this;
            return new TimingSnapshot(
                    Math.max(0L, tiles - before.tiles),
                    Math.max(0L, heightBatchNanos - before.heightBatchNanos),
                    Math.max(0L, biomeNanos - before.biomeNanos),
                    Math.max(0L, nearWaterNanos - before.nearWaterNanos),
                    Math.max(0L, colorNanos - before.colorNanos));
        }
    }

    public static TimingSnapshot timingSnapshot() {
        return new TimingSnapshot(
                TIMING_TILES.get(), TIMING_HEIGHT_BATCH_NANOS.get(), TIMING_BIOME_NANOS.get(),
                TIMING_NEAR_WATER_NANOS.get(), TIMING_COLOR_NANOS.get());
    }

    /** 构造一个高度采样请求（分带时 minBlockZ / sampleHeight 会不同）。 */
    private static CoarseHeightBatchRequest heightRequestOf(int minBlockX,
                                                             int minBlockZ,
                                                             int step,
                                                             int sampleWidth,
                                                             int sampleHeight) {
        return new CoarseHeightBatchRequest(minBlockX, minBlockZ, step, sampleWidth, sampleHeight);
    }

    /**
     * 0.8.350（S1-A）：把一次高度采样按 Z 轴切成若干横带，**每条带之间**检查一次让路闸。
     *
     * <p><b>为什么需要</b>：真机实测单瓦片高度采样约 <b>8.97 秒</b>
     * （{@code heightBatchWorkerMs=278006} / {@code sampled=31}），
     * 而让路闸平时只在**瓦片之间**检查 —— 玩家开界面若落在这 9 秒内，闸门毫无机会反应，
     * 于是表现为"开背包卡 2~3 秒"。分带把闸门响应间隔压到约 9/N 秒。</p>
     *
     * <p><b>为什么正确</b>：内核 {@code roadweaver_coarse_height_sample} 逐样本完全独立 ——
     * {@code index = get_global_id(0)} 唯一决定 {@code sample_x/sample_z} 与
     * {@code scratch_base = index * node_count}，样本之间无任何共享状态；
     * 且 {@code align_to_cpu_grid} 在 step 对齐网格上（本包 step=4、瓦片基址为 16 的倍数）
     * 精确无漂移。因此"切横带 + 平移 minBlockZ"与"一次整块采样"**逐样本结果完全相同**。</p>
     *
     * <p><b>代价</b>：每条带各提交一次 kernel、各占一份 scratch，
     * 总耗时可能上升。带数由 {@code performance.coarseHeightBandCount} 控制（默认 8，1 = 不分带）。</p>
     *
     * @return 整块采样结果；被中断时返回 {@code null}（与旧行为一致，调用方按失败处理）。
     */
    private static int[] sampleHeightsBanded(CoarseHeightBatchSampler heightSampler,
                                             int minBlockX,
                                             int minBlockZ,
                                             int step,
                                             int expandedWidth,
                                             int expandedHeight) {
        // 0.8.352：**开局预热与 Chunky 预热不分带**。
        //
        // 分带的唯一目的是「让路给玩家操作」，而让路闸 beforeBackgroundUnit 对
        // initialGeneration=true 是**直接 return**（开局/Chunky 预热本来就不受游玩限制）。
        // 因此在这两个阶段分带**只付 8 次 kernel 提交开销、拿不到任何让路收益** ——
        // 纯亏。而用户明确说过开局会跑「超大范围预热」，这个开销会实打实拖慢它。
        //
        // 真机取证（20260913 02:20 会话）：游玩期分带后单瓦片采样由约 9.6 秒升到约 30 秒，
        // 机制是每条带都要重走一遍 prepare/kernel 启动/readInts 同步回读。
        // 这个代价换取「玩家操作时不卡」是值得的，但开局预热**不需要**这个交换。
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        int bands = initialGeneration
                ? 1
                : Math.max(1, Math.min(bandCount(), expandedHeight));
        // 间隔闸按「逻辑瓦片」计费一次，而不是每条带各计一次。分带只是把一次
        // 瓦片采样拆成 N 次 kernel 提交；若每条带都刷新 LAST_GPU_SUBMIT，间隔闸
        // 会被带数等比放大（live bands=6 × active 14s ≈ 70s 空等）。因此这里只在
        // 瓦片层计费一次（仅 GPU 路径；CPU 路径不经过 RuntimeCoarseGpuBudget）。
        boolean billTileSubmit = !initialGeneration && heightSampler.isAccelerated();
        if (bands <= 1) {
            if (billTileSubmit) {
                RuntimeCoarseGpuBudget.awaitTileSubmitInterval();
            }
            return heightSampler.sampleHeights(
                    heightRequestOf(minBlockX, minBlockZ, step, expandedWidth, expandedHeight));
        }
        int[] out = new int[Math.multiplyExact(expandedWidth, expandedHeight)];
        // 每带覆盖的 Z 行数（向上取整，最后一带可能更短）。
        int rowsPerBand = ceilDiv(expandedHeight, bands);
        for (int band = 0; band < bands; band++) {
            int firstRow = band * rowsPerBand;
            if (firstRow >= expandedHeight) break;
            int rowCount = Math.min(rowsPerBand, expandedHeight - firstRow);
            // 带间让路：把一次较长的不可打断区间切成 N 段（0.8.350 S1-A）。
            RuntimeLoadPolicy.beforeBackgroundUnit(false);
            if (Thread.currentThread().isInterrupted()) return null;
            // 间隔闸在第一条带提交前计费一次；后续带背靠背提交、不再计费。
            if (band == 0 && billTileSubmit) {
                RuntimeCoarseGpuBudget.awaitTileSubmitInterval();
            }
            int[] bandHeights = heightSampler.sampleHeights(heightRequestOf(
                    minBlockX,
                    minBlockZ + firstRow * step,
                    step,
                    expandedWidth,
                    rowCount));
            if (bandHeights == null) return null;
            System.arraycopy(bandHeights, 0, out, firstRow * expandedWidth, bandHeights.length);
        }
        return out;
    }

    /** 分带数（配置不可用时按 8，与默认一致）。 */
    private static int bandCount() {
        try {
            return ConfigService.get().performance().coarseHeightBandCount();
        } catch (Throwable ignored) {
            return 8;
        }
    }

    public static CoarseTerrainTile sample(ServerLevel level, CoarseTerrainTileKey key) {
        if (level == null || key == null) {
            return null;
        }

        int sampleWidth = key.sampleWidth();
        int sampleHeight = key.sampleHeight();
        int size = Math.multiplyExact(sampleWidth, sampleHeight);
        short[] heights = new short[size];
        short[] oceanFloors = new short[size];
        byte[] flags = new byte[size];
        int[] terrainArgb = new int[size];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] sampledBiomes = (Holder<Biome>[]) new Holder<?>[size];

        int haloSamples = Math.max(1, ceilDiv(RoadConstants.COARSE_TERRAIN_TILE_HALO_BLOCKS, key.step()));
        int expandedWidth = sampleWidth + haloSamples * 2;
        int expandedHeight = sampleHeight + haloSamples * 2;
        byte[] expandedFlags = new byte[Math.multiplyExact(expandedWidth, expandedHeight)];

        int seaLevel = level.getSeaLevel();
        var chunkSource = level.getChunkSource();
        var randomState = chunkSource.getGeneratorState().randomState();
        var biomeSource = chunkSource.getGenerator().getBiomeSource();
        var climateSampler = randomState.sampler();
        int minBlockX = key.minBlockX() - haloSamples * key.step();
        int minBlockZ = key.minBlockZ() - haloSamples * key.step();
        try (CoarseHeightBatchSampler heightSampler = CoarseHeightBatchSamplers.create(level, heightRequestOf(
                minBlockX, minBlockZ, key.step(), expandedWidth, expandedHeight))) {
            long heightStartedAt = System.nanoTime();
            int[] expandedHeights = sampleHeightsBanded(
                    heightSampler, minBlockX, minBlockZ, key.step(), expandedWidth, expandedHeight);
            TIMING_HEIGHT_BATCH_NANOS.addAndGet(System.nanoTime() - heightStartedAt);
            if (expandedHeights == null) return null;

            long biomeStartedAt = System.nanoTime();
            for (int ez = 0; ez < expandedHeight; ez++) {
                if (Thread.currentThread().isInterrupted()) return null;
                int sampleZ = key.minBlockZ() + (ez - haloSamples) * key.step();
                for (int ex = 0; ex < expandedWidth; ex++) {
                    int sampleX = key.minBlockX() + (ex - haloSamples) * key.step();
                    int height = expandedHeights[ez * expandedWidth + ex];
                    int oceanFloor = height;
                    Holder<Biome> biome = biomeSource.getNoiseBiome(
                            sampleX >> 2, 16, sampleZ >> 2, climateSampler);
                    boolean waterBiome = isWaterBiome(biome);
                    boolean columnWater = SurfaceWaterClassifier.isWaterColumn(
                            height, oceanFloor, waterBiome, seaLevel);
                    byte rawFlags = CoarseTerrainTile.flags(columnWater, waterBiome, false);
                    expandedFlags[ez * expandedWidth + ex] = rawFlags;

                    int tx = ex - haloSamples;
                    int tz = ez - haloSamples;
                    if (tx >= 0 && tx < sampleWidth && tz >= 0 && tz < sampleHeight) {
                        int index = tz * sampleWidth + tx;
                        heights[index] = toShort(height);
                        oceanFloors[index] = toShort(oceanFloor);
                        flags[index] = rawFlags;
                        sampledBiomes[index] = biome;
                    }
                }
            }
            TIMING_BIOME_NANOS.addAndGet(System.nanoTime() - biomeStartedAt);
            TerrainSamplingStats.recordCacheMisses((long) expandedWidth * expandedHeight);

            long nearWaterStartedAt = System.nanoTime();
            markNearWater(flags, sampleWidth, sampleHeight, expandedFlags,
                    expandedWidth, expandedHeight, haloSamples);
            TIMING_NEAR_WATER_NANOS.addAndGet(System.nanoTime() - nearWaterStartedAt);
            long colorStartedAt = System.nanoTime();
            refreshTerrainColors(level, key, seaLevel, heights, oceanFloors, flags,
                    terrainArgb, sampledBiomes, sampleWidth, sampleHeight);
            TIMING_COLOR_NANOS.addAndGet(System.nanoTime() - colorStartedAt);
            TIMING_TILES.incrementAndGet();
            return new CoarseTerrainTile(key, seaLevel, sampleWidth, sampleHeight, heights, oceanFloors, flags, terrainArgb);
        }
    }

    private static void markNearWater(byte[] flags,
                                      int sampleWidth,
                                      int sampleHeight,
                                      byte[] expandedFlags,
                                      int expandedWidth,
                                      int expandedHeight,
                                      int haloSamples) {
        int prefixWidth = expandedWidth + 1;
        int[] waterPrefix = new int[Math.multiplyExact(prefixWidth, expandedHeight + 1)];
        for (int z = 0; z < expandedHeight; z++) {
            int rowWater = 0;
            int sourceRow = z * expandedWidth;
            int prefixRow = (z + 1) * prefixWidth;
            int previousPrefixRow = z * prefixWidth;
            for (int x = 0; x < expandedWidth; x++) {
                if (hasColumnWater(expandedFlags[sourceRow + x])) rowWater++;
                waterPrefix[prefixRow + x + 1] = waterPrefix[previousPrefixRow + x + 1] + rowWater;
            }
        }
        for (int z = 0; z < sampleHeight; z++) {
            if (Thread.currentThread().isInterrupted()) return;
            for (int x = 0; x < sampleWidth; x++) {
                int index = z * sampleWidth + x;
                if (hasColumnWaterNearby(waterPrefix, prefixWidth,
                        x + haloSamples, z + haloSamples, haloSamples)) {
                    flags[index] = CoarseTerrainTile.withNearWater(flags[index]);
                }
            }
        }
    }

    private static void refreshTerrainColors(ServerLevel level,
                                             CoarseTerrainTileKey key,
                                             int seaLevel,
                                             short[] heights,
                                             short[] oceanFloors,
                                             byte[] flags,
                                             int[] terrainArgb,
                                             Holder<Biome>[] sampledBiomes,
                                             int sampleWidth,
                                             int sampleHeight) {
        int step = key.step();
        for (int z = 0; z < sampleHeight; z++) {
            if (Thread.currentThread().isInterrupted()) return;
            int sampleZ = key.blockZAt(z);
            for (int x = 0; x < sampleWidth; x++) {
                int sampleX = key.blockXAt(x);
                int index = z * sampleWidth + x;
                Holder<Biome> biome = sampledBiomes[index];
                boolean columnWater = hasColumnWater(flags[index]);
                boolean nearWater = (flags[index] & 4) != 0;
                int baseColor = TerrainTilePalette.colorFor(biome, heights[index], seaLevel, oceanFloors[index], columnWater, nearWater);

                double shade = 1.0;
                if (x > 0 && x < sampleWidth - 1 && z > 0 && z < sampleHeight - 1) {
                    int previousRow = (z - 1) * sampleWidth;
                    int currentRow = z * sampleWidth;
                    int nextRow = (z + 1) * sampleWidth;
                    shade = HeightShader.computeShade(
                            heights[previousRow + x - 1], heights[previousRow + x], heights[previousRow + x + 1],
                            heights[currentRow + x - 1], heights[index], heights[currentRow + x + 1],
                            heights[nextRow + x - 1], heights[nextRow + x], heights[nextRow + x + 1]);
                } else if (x > 0) {
                    shade = HeightShader.simpleShade(heights[index], heights[z * sampleWidth + x - 1], step);
                }

                terrainArgb[index] = HeightShader.multiplyRgb(baseColor, shade);
            }
        }
    }

    private static boolean hasColumnWaterNearby(int[] waterPrefix,
                                                int prefixWidth,
                                                int centerX,
                                                int centerZ,
                                                int radius) {
        int minX = centerX - radius;
        int minZ = centerZ - radius;
        int maxXExclusive = centerX + radius + 1;
        int maxZExclusive = centerZ + radius + 1;
        int count = waterPrefix[maxZExclusive * prefixWidth + maxXExclusive]
                - waterPrefix[minZ * prefixWidth + maxXExclusive]
                - waterPrefix[maxZExclusive * prefixWidth + minX]
                + waterPrefix[minZ * prefixWidth + minX];
        return count > 0;
    }

    private static boolean hasColumnWater(byte flags) {
        return (flags & 1) != 0;
    }

    private static boolean isWaterBiome(Holder<Biome> biome) {
        return biome != null
                && (biome.is(BiomeTags.IS_RIVER)
                || biome.is(BiomeTags.IS_OCEAN)
                || biome.is(BiomeTags.IS_DEEP_OCEAN));
    }

    private static short toShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static int ceilDiv(int value, int divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }
}
