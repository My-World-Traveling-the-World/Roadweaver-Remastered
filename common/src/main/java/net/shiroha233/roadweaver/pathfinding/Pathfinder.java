/* 文件职责：定义普通道路寻路器的统一调用接口。 */
package net.shiroha233.roadweaver.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * BAND生产寻路调用边界；不暴露求解器不会读取的旧扩展步数预算。
 */
public interface Pathfinder {
    PathResult findPath(BlockPos start, BlockPos end, int width,
                        ServerLevel level,
                        TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig config);

    default PathResult findRawPath(BlockPos start, BlockPos end,
                                   ServerLevel level,
                                   TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig config) {
        return findPath(start, end, 1, level, cache, terrain, config);
    }
}
