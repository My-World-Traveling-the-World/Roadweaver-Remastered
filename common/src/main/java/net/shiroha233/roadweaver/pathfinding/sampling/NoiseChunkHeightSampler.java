/* 文件职责：按原版 NoiseChunk 语义在 CPU 烘焙单个区块的三类高度图。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 基于原版 NoiseChunk 批量烘焙整块高度图的精确采样器
 */
final class NoiseChunkHeightSampler {
    private static final ZeroBeardifier ZERO_BEARDIFIER = ZeroBeardifier.INSTANCE;

    private final RandomState randomState;
    private final NoiseGeneratorSettings generatorSettings;
    private final NoiseSettings noiseSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final BlockState defaultBlock;
    private NoiseChunkHeightSampler(RandomState randomState,
                                    NoiseGeneratorSettings generatorSettings,
                                    NoiseSettings noiseSettings,
                                    Aquifer.FluidPicker fluidPicker) {
        this.randomState = randomState;
        this.generatorSettings = generatorSettings;
        this.noiseSettings = noiseSettings;
        this.fluidPicker = fluidPicker;
        this.defaultBlock = generatorSettings.defaultBlock();
    }

    static NoiseChunkHeightSampler create(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        return create(level, settings, randomState);
    }

    static NoiseChunkHeightSampler create(LevelHeightAccessor level,
                                          NoiseGeneratorSettings settings,
                                          RandomState randomState) {
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(level);
        return new NoiseChunkHeightSampler(randomState, settings, noiseSettings, createFluidPicker(settings));
    }

    /**
     * 释放缓存。用新实例替换而非 clear()，确保内部 table 数组被 GC 回收。
     */
    void clear() {
        // 当前实现不持有跨区块状态，方法保留给统一后端生命周期。
    }

    AccurateHeightChunk sampleChunk(int chunkX, int chunkZ) {
        return sampleChunkColumns(chunkX, chunkZ, null);
    }

    AccurateHeightChunk sampleChunkColumns(int chunkX, int chunkZ, int[] requestedColumns) {
        int minY = noiseSettings.minY();
        int[] worldSurface = new int[256];
        int[] oceanFloor = new int[256];
        int[] motionBlocking = new int[256];
        Arrays.fill(worldSurface, minY);
        Arrays.fill(oceanFloor, minY);
        Arrays.fill(motionBlocking, minY);

        int cellWidth = noiseSettings.getCellWidth();
        int cellHeight = noiseSettings.getCellHeight();
        int cellCountXZ = 16 / cellWidth;
        int cellCountY = Mth.floorDiv(noiseSettings.height(), cellHeight);
        int cellNoiseMinY = Mth.floorDiv(minY, cellHeight);
        int airSkipMargin = generatorSettings.seaLevel() - minY + 32;
        byte[] unresolvedMasks = new byte[256];
        boolean[] requestedCells = new boolean[cellCountXZ * cellCountXZ];
        int unresolvedColumns = initializeRequestedColumns(
                requestedColumns, unresolvedMasks, requestedCells, cellWidth, cellCountXZ);
        if (unresolvedColumns == 0) {
            return new AccurateHeightChunk(chunkX, chunkZ, worldSurface, oceanFloor, motionBlocking);
        }

        Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        Predicate<BlockState> oceanFloorPredicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        Predicate<BlockState> motionPredicate = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();

        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;

        AccessibleNoiseChunk noiseChunk = new AccessibleNoiseChunk(
                cellCountXZ,
                randomState,
                minBlockX,
                minBlockZ,
                noiseSettings,
                ZERO_BEARDIFIER,
                generatorSettings,
                fluidPicker);

        noiseChunk.initializeForFirstCellX();
        try {
            outer:
            for (int cellX = 0; cellX < cellCountXZ; cellX++) {
                noiseChunk.advanceCellX(cellX);

                for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                    if (!requestedCells[cellX * cellCountXZ + cellZ]) {
                        continue;
                    }
                    int repX = minBlockX + cellX * cellWidth + (cellWidth >> 1);
                    int repZ = minBlockZ + cellZ * cellWidth + (cellWidth >> 1);
                    int startCellY = Mth.floorDiv(preliminarySurface(repX, repZ) + airSkipMargin, cellHeight) - cellNoiseMinY;
                    if (startCellY >= cellCountY) {
                        startCellY = cellCountY - 1;
                    }
                    if (startCellY < 0) {
                        startCellY = 0;
                    }
                    for (int cellY = startCellY; cellY >= 0; cellY--) {
                        noiseChunk.selectCellYZ(cellY, cellZ);

                        for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                            int blockY = (cellNoiseMinY + cellY) * cellHeight + inCellY;
                            noiseChunk.updateForY(blockY, (double) inCellY / cellHeight);

                            for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                                int blockX = minBlockX + cellX * cellWidth + inCellX;
                                int localX = blockX & 15;
                                noiseChunk.updateForX(blockX, (double) inCellX / cellWidth);

                                for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                    int blockZ = minBlockZ + cellZ * cellWidth + inCellZ;
                                    int index = localX + ((blockZ & 15) << 4);
                                    byte mask = unresolvedMasks[index];
                                    if (mask == 0) {
                                        continue;
                                    }
                                    noiseChunk.updateForZ(blockZ, (double) inCellZ / cellWidth);

                                    BlockState state = noiseChunk.sampleInterpolatedState();
                                    if (state == null) {
                                        state = defaultBlock;
                                    }
                                    if (state.isAir()) {
                                        continue;
                                    }

                                    byte nextMask = mask;
                                    if ((nextMask & 0x01) != 0 && worldSurfacePredicate.test(state)) {
                                        worldSurface[index] = blockY + 1;
                                        nextMask &= ~0x01;
                                    }
                                    if ((nextMask & 0x02) != 0 && oceanFloorPredicate.test(state)) {
                                        oceanFloor[index] = blockY + 1;
                                        nextMask &= ~0x02;
                                    }
                                    if ((nextMask & 0x04) != 0 && motionPredicate.test(state)) {
                                        motionBlocking[index] = blockY + 1;
                                        nextMask &= ~0x04;
                                    }

                                    if (nextMask != mask) {
                                        unresolvedMasks[index] = nextMask;
                                        if (nextMask == 0) {
                                            unresolvedColumns--;
                                            if (unresolvedColumns == 0) {
                                                break outer;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return new AccurateHeightChunk(chunkX, chunkZ, worldSurface, oceanFloor, motionBlocking);
    }

    /**
     * 用 initialDensityWithoutJaggedness 二分求某列地表预估（smooth 密度 &gt; 0.390625 的最高 Y）。
     * 相比从世界顶逐格数空气，只做 log2(height) 次密度采样；行走从该值 + airSkipMargin 出发，
     * 余量覆盖水面、格内起伏与锯齿，结果与完整行走一致。
     */
    private int preliminarySurface(int blockX, int blockZ) {
        DensityFunction density = randomState.router().initialDensityWithoutJaggedness();
        int qx = Mth.floorDiv(blockX, 4) * 4;
        int qz = Mth.floorDiv(blockZ, 4) * 4;
        int minY = noiseSettings.minY();
        int top = minY + noiseSettings.height();
        int lo = minY - 1;
        int hi = top;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            double d = density.compute(new DensityFunction.SinglePointContext(qx, mid, qz));
            if (d > 0.390625) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int initializeRequestedColumns(int[] requestedColumns,
                                                  byte[] unresolvedMasks,
                                                  boolean[] requestedCells,
                                                  int cellWidth,
                                                  int cellCountXZ) {
        if (requestedColumns == null) {
            Arrays.fill(unresolvedMasks, (byte) 0x07);
            Arrays.fill(requestedCells, true);
            return unresolvedMasks.length;
        }

        int count = 0;
        for (int column : requestedColumns) {
            if (column < 0 || column >= AccurateHeightChunk.COLUMN_COUNT) {
                throw new IndexOutOfBoundsException("height column index: " + column);
            }
            if (unresolvedMasks[column] != 0) {
                continue;
            }
            unresolvedMasks[column] = 0x07;
            int localX = column & 15;
            int localZ = column >> 4;
            requestedCells[(localX / cellWidth) * cellCountXZ + localZ / cellWidth] = true;
            count++;
        }
        return count;
    }

    static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : defaultFluid;
    }

    private static final class AccessibleNoiseChunk extends NoiseChunk {
        private AccessibleNoiseChunk(int cellCountXZ,
                                     RandomState randomState,
                                     int minBlockX,
                                     int minBlockZ,
                                     NoiseSettings noiseSettings,
                                     DensityFunctions.BeardifierOrMarker beardifier,
                                     NoiseGeneratorSettings generatorSettings,
                                     Aquifer.FluidPicker fluidPicker) {
            super(cellCountXZ, randomState, minBlockX, minBlockZ, noiseSettings, beardifier, generatorSettings, fluidPicker, Blender.empty());
        }

        private BlockState sampleInterpolatedState() {
            return this.getInterpolatedState();
        }
    }

    private enum ZeroBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return 0.0D;
        }

        @Override
        public void fillArray(double[] values, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(values, 0.0D);
        }

        @Override
        public double minValue() {
            return 0.0D;
        }

        @Override
        public double maxValue() {
            return 0.0D;
        }
    }
}
