package net.shiroha233.roadweaver.decoration.system;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;

import java.util.List;

/**
 * 表面放置工具
 */
public final class SurfacePlacementUtil {
    private SurfacePlacementUtil() {}

    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material, int roadType, RandomSource random, ModConfig cfg) {
        double naturalBlockChance = 1.0;
        BlockPos surfacePos = placePos;
        BlockPos belowTop = surfacePos.below();
        BlockState blockStateAtPos = world.getBlockState(belowTop);
        boolean doPlace = (roadType == 0) || random.nextDouble() < naturalBlockChance;
        if (doPlace) {
            RoadBlockPlacer.placeRoadBlock(world, blockStateAtPos, surfacePos, material, random, cfg);
        } else {
            AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg);
        }
    }

    public static void placeRoadBlock(WorldGenLevel world, BlockState blockBelow, BlockPos surfacePos, List<BlockState> materials, RandomSource random, ModConfig cfg) {
        RoadBlockPlacer.placeRoadBlock(world, blockBelow, surfacePos, materials, random, cfg);
    }

    public static void clearAboveColumn(WorldGenLevel world, BlockPos surfacePos, ModConfig cfg) {
        AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg);
    }

    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material,
            int roadType, RandomSource random, ModConfig cfg, RoadData road, int clearHeight) {
        placeOnSurface(world, placePos, material, roadType, random, cfg, road, clearHeight, false);
    }

    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material,
            int roadType, RandomSource random, ModConfig cfg, RoadData road, int clearHeight,
            boolean stabilizeFallingCap) {
        placeOnSurface(world, placePos, material, roadType, random, cfg, road, clearHeight,
                stabilizeFallingCap, false);
    }

    /**
     * 0.9.065：{@code tunnelBody = true} 的这一列属于隧道洞身段，只清洞身净空、不做向上清空
     * （口径见 {@link RoadBlockPlacer#placeRoadBlock}）。
     */
    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material,
            int roadType, RandomSource random, ModConfig cfg, RoadData road, int clearHeight,
            boolean stabilizeFallingCap, boolean tunnelBody) {
        BlockPos belowTop = placePos.below();
        BlockState blockStateAtPos = world.getBlockState(belowTop);
        RoadBlockPlacer.placeRoadBlock(world, blockStateAtPos, placePos, material, random, cfg, road, clearHeight,
                stabilizeFallingCap, tunnelBody);
    }
}
