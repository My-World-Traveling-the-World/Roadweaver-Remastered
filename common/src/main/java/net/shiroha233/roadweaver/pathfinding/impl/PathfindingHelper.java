/* 文件职责：提供普通道路寻路阶段的公共地形读取与网格工具。 */
package net.shiroha233.roadweaver.pathfinding.impl;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * 寻路公共工具方法
 */
public final class PathfindingHelper {
    private PathfindingHelper() {}

    public static int heightSampler(PathTerrainField terrain, int x, int z) {
        return terrain.height(x, z);
    }

    public static Holder<Biome> biome(PathTerrainField terrain, int x, int z) {
        return terrain.biome(x, z);
    }

    public static boolean isWaterLike(TerrainSamplingCache cache, int x, int z, ServerLevel level) {
        return cache.isWaterLike(level, x, z);
    }

    public static int oceanFloorSampler(PathTerrainField terrain, int x, int z) {
        return terrain.oceanFloor(x, z);
    }

    public static boolean isNearWaterLike(PathTerrainField terrain, int x, int z, int neighborDistance) {
        return terrain.isNearWater(x, z, neighborDistance);
    }

    public static boolean isColumnWater(PathTerrainField terrain, int x, int z) {
        return terrain.isColumnWater(x, z);
    }

    public static int snapToGrid(int v, int gridSize) {
        return Math.floorDiv(v, gridSize) * gridSize;
    }

    /**
     * A coarse A* edge can skip several sampled columns. This checks the swept
     * line at a finer interval so a building cannot hide between two lattice
     * nodes and only appear as a local repair later.
     */
    public static boolean edgeIntersectsPlanningObstacle(BlockPos from, BlockPos to,
                                                         int horizontalClearance,
                                                         int step) {
        if (from == null || to == null) return false;
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (horizontal < 0.001D) return false;
        int safeStep = Math.max(1, Math.min(step, 2));
        int samples = Math.max(1, (int) Math.ceil(horizontal / safeStep));
        for (int sample = 1; sample < samples; sample++) {
            double t = sample / (double) samples;
            BlockPos probe = new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
            if (StructureAvoidanceService.shouldAvoidPlanning(probe, horizontalClearance)) {
                return true;
            }
        }
        return false;
    }

    public static int calculateTerrainStability(PathTerrainField terrain, BlockPos pos, int y, int step) {
        int cost = 0;
        if (neighborDiffersOrMissing(terrain, pos.getX() + step, pos.getZ(), y)) cost++;
        if (neighborDiffersOrMissing(terrain, pos.getX() - step, pos.getZ(), y)) cost++;
        if (neighborDiffersOrMissing(terrain, pos.getX(), pos.getZ() + step, y)) cost++;
        if (neighborDiffersOrMissing(terrain, pos.getX(), pos.getZ() - step, y)) cost++;
        return cost;
    }

    private static boolean neighborDiffersOrMissing(PathTerrainField terrain, int x, int z, int y) {
        if (!terrain.contains(x, z)) {
            return true;
        }
        return Math.abs(heightSampler(terrain, x, z) - y) > 0;
    }
}
