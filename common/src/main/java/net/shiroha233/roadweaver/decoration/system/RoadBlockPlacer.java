/* 文件职责：道路方块放置器。 */
package net.shiroha233.roadweaver.decoration.system;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;

import java.util.List;

/**
 * 道路方块放置器
 */
public final class RoadBlockPlacer {
    private RoadBlockPlacer() {}

    public static void placeRoadBlock(WorldGenLevel world,
            BlockState blockBelow,
            BlockPos surfacePos,
            List<BlockState> materials,
            RandomSource random,
            ModConfig cfg) {
        if (!PlacementRules.placeAllowedCheck(blockBelow.getBlock()))
            return;
        BlockState chosen = materials.get(random.nextInt(materials.size()));

        BlockPos below1 = surfacePos.below();
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE, below1.getX(), below1.getY(), below1.getZ()))
            return;
        world.setBlock(below1, chosen, 3);

        AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg);

        BlockPos belowPos1 = surfacePos.below(2);
        BlockState belowState1 = world.getBlockState(belowPos1);
        if (belowState1.is(Blocks.GRASS_BLOCK)) {
            world.setBlock(belowPos1, Blocks.DIRT.defaultBlockState(), 3);
        }
    }

    public static void placeRoadBlock(WorldGenLevel world,
            BlockState blockBelow,
            BlockPos surfacePos,
            List<BlockState> materials,
            RandomSource random,
            ModConfig cfg,
            RoadData road,
            int clearHeight) {
        placeRoadBlock(world, blockBelow, surfacePos, materials, random, cfg, road, clearHeight, false);
    }

    public static void placeRoadBlock(WorldGenLevel world,
            BlockState blockBelow,
            BlockPos surfacePos,
            List<BlockState> materials,
            RandomSource random,
            ModConfig cfg,
            RoadData road,
            int clearHeight,
            boolean stabilizeFallingCap) {
        placeRoadBlock(world, blockBelow, surfacePos, materials, random, cfg, road, clearHeight,
                stabilizeFallingCap, false);
    }

    /**
     * 0.9.065：{@code tunnelBody = true} 的这一列属于隧道<b>洞身段</b> —— 上方岩体本身就是顶板，
     * 所以只清洞身净空，禁止向上清空（见 {@link AboveColumnClearer#clearTunnelBoreHeadroom}）。
     * 洞口明挖段与普通路段一律 {@code tunnelBody = false}，行为与 0.9.064 完全相同。
     */
    public static void placeRoadBlock(WorldGenLevel world,
            BlockState blockBelow,
            BlockPos surfacePos,
            List<BlockState> materials,
            RandomSource random,
            ModConfig cfg,
            RoadData road,
            int clearHeight,
            boolean stabilizeFallingCap,
            boolean tunnelBody) {
        if (!PlacementRules.placeAllowedCheck(blockBelow.getBlock())) return;
        BlockState chosen = materials.get(random.nextInt(materials.size()));
        BlockPos below1 = surfacePos.below();
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.SURFACE, below1.getX(), below1.getY(), below1.getZ()))
            return;
        world.setBlock(below1, chosen, 3);
        if (tunnelBody) {
            // 0.9.065：洞身段 —— 只清洞身净空，绝不把洞顶岩体往上削。
            AboveColumnClearer.clearTunnelBoreHeadroom(world, surfacePos, cfg, road, clearHeight);
        } else {
            // 0.9.005-C：地表铺路要求凸起削平（台地断头路），显式传 true。
            AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg, road, clearHeight,
                    stabilizeFallingCap, true);
        }

        BlockPos belowPos1 = surfacePos.below(2);
        BlockState belowState1 = world.getBlockState(belowPos1);
        if (belowState1.is(Blocks.GRASS_BLOCK)) {
            world.setBlock(belowPos1, Blocks.DIRT.defaultBlockState(), 3);
        }
    }
}
