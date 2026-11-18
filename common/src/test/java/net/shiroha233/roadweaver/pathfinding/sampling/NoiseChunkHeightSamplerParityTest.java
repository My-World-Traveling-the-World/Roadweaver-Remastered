package net.shiroha233.roadweaver.pathfinding.sampling;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoiseChunkHeightSamplerParityTest {
    private static final Heightmap.Types[] TYPES = {
            Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    };
    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    private static NoiseBasedChunkGenerator generator;
    private static RandomState random;
    private static NoiseChunkHeightSampler sampler;

    @BeforeAll
    static void createVanillaTerrain() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 用原版密度图及含水层，避免单调替身掩盖跳过高处非空气方块的错误。
        var registries = VanillaRegistries.createLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
        random = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), 42L);
        sampler = NoiseChunkHeightSampler.create(HEIGHT, settings.value(), random);
    }

    @Test
    void fullChunksMatchVanillaTopDownOnAllThreeChannels() {
        boolean wet = false;
        boolean dry = false;
        for (var chunk : new int[][]{{0, 0}, {-9, 7}, {-37, -65}, {124, -81}, {-113, 93}, {256, 250}}) {
            var actual = sampler.sampleChunk(chunk[0], chunk[1]);
            for (int index = 0; index < 256; index++) {
                compareColumn(actual, index);
                wet |= actual.worldSurfaceWgAt(index) > actual.oceanFloorWgAt(index);
                dry |= actual.worldSurfaceWgAt(index) == actual.oceanFloorWgAt(index);
            }
        }
        assertTrue(wet && dry, "固定夹具必须同时覆盖干地和水面，否则无法守住三通道语义");
    }

    @Test
    void sparseRequestsOnlyFillSelectedColumnsIncludingRepeatedIndices() {
        int[] selected = {0, 15, 34, 119, 240, 255, 34};
        var actual = sampler.sampleChunkColumns(-37, -65, selected);
        for (int index : selected) compareColumn(actual, index);
        for (int index = 0; index < 256; index++) {
            final int column = index;
            if (java.util.Arrays.stream(selected).anyMatch(value -> value == column)) continue;
            for (int height : heights(actual, index)) assertEquals(HEIGHT.getMinBuildHeight(), height);
        }
    }

    private static void compareColumn(AccurateHeightChunk actual, int index) {
        int x = (actual.chunkX() << 4) + (index & 15);
        int z = (actual.chunkZ() << 4) + (index >> 4);
        // getBaseColumn 由原版独立完整生成；自世界顶下降，不复用生产预估或扫描循环。
        var vanilla = generator.getBaseColumn(x, z, HEIGHT, random);
        int[] actualHeights = heights(actual, index);
        for (int type = 0; type < TYPES.length; type++) {
            var predicate = TYPES[type].isOpaque();
            int expected = HEIGHT.getMinBuildHeight();
            for (int y = HEIGHT.getMaxBuildHeight() - 1; y >= HEIGHT.getMinBuildHeight(); y--) {
                if (predicate.test(vanilla.getBlock(y))) {
                    expected = y + 1;
                    break;
                }
            }
            assertEquals(expected, actualHeights[type], TYPES[type] + " x=" + x + " z=" + z);
        }
    }

    private static int[] heights(AccurateHeightChunk chunk, int index) {
        return new int[]{chunk.worldSurfaceWgAt(index), chunk.oceanFloorWgAt(index),
                chunk.motionBlockingNoLeavesAt(index)};
    }
}
