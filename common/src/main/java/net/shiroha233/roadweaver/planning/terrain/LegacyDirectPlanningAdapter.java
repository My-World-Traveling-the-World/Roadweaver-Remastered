/* 文件职责：按旧版行为逐条连接读取缓存地形并直接执行道路原始寻路。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.LinkedHashMap;
import java.util.List;

final class LegacyDirectPlanningAdapter implements RoadTerrainPlanningPort {
    @Override
    public Result plan(Request request) {
        if (request.connections().isEmpty()) {
            return Result.empty(TerrainSamplingMode.LEGACY_DIRECT);
        }

        TerrainSamplingCache cache = new TerrainSamplingCache();
        PathTerrainField terrain = new CacheBackedTerrainField(request.level(), cache,
                request.pathfinding().effectiveAStarStep());
        LinkedHashMap<StructureConnection, List<net.minecraft.core.BlockPos>> paths = new LinkedHashMap<>();
        // 0.9.057：① 第一遍（本适配器本来就是逐条串行，保持原样）—— 只收路径，
        // 不再把前面的路的中心线累积成优惠走廊（0.9.056 那样做会引入顺序依赖）。
        try {
            for (StructureConnection connection : request.connections()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("direct planning interrupted");
                }
                List<net.minecraft.core.BlockPos> path = RawPathSearch.find(
                        request.level(), connection, request.pathfinding(), cache, terrain);
                if (path != null && !path.isEmpty()) {
                    paths.put(connection, path);
                }
            }
            // ② 协商 → ③ 第二遍：只重算拿到共享过河点的路。
            // 关掉时 agree() 返回 null，第二遍不跑、日志不打，行为与 0.9.055 逐位相同。
            CrossingNegotiation.Agreement agreement = CrossingNegotiation.agree(
                    request.connections(), paths, terrain, request.pathfinding());
            if (agreement != null) {
                CrossingNegotiation.rerouteSerially(request.connections(), agreement,
                        (connection, attract) -> {
                            PathfindingCostConfig routeConfig = request.pathfinding().snapshot();
                            routeConfig.setCorridorAttract(attract);
                            return CorridorAttract.withScoped(attract, () -> RawPathSearch.find(
                                    request.level(), connection, routeConfig, cache, terrain));
                        }, paths, terrain.step());
                CoarseCorridorPlanningAdapter.logPathReuse(
                        CrossingNegotiation.stats(agreement, paths));
            }
            return new Result(TerrainSamplingMode.LEGACY_DIRECT, paths);
        } finally {
            cache.clear();
        }
    }

    private static final class CacheBackedTerrainField implements PathTerrainField {
        private final ServerLevel level;
        private final TerrainSamplingCache cache;
        private final int step;

        private CacheBackedTerrainField(ServerLevel level, TerrainSamplingCache cache, int step) {
            this.level = level;
            this.cache = cache;
            this.step = step;
        }

        @Override
        public int seaLevel() {
            return level.getSeaLevel();
        }

        @Override
        public int height(int x, int z) {
            return cache.height(level, x, z);
        }

        @Override
        public int oceanFloor(int x, int z) {
            return cache.oceanFloor(level, x, z);
        }

        @Override
        public boolean isColumnWater(int x, int z) {
            return cache.isColumnWater(level, x, z);
        }

        @Override
        public boolean isNearWater(int x, int z, int neighborDistance) {
            return cache.isNearWaterLike(level, x, z, neighborDistance);
        }

        @Override
        public Holder<Biome> biome(int x, int z) {
            return cache.getBiome(level, x, z);
        }

        @Override
        public boolean contains(int x, int z) {
            return true;
        }

        @Override
        public int step() {
            return step;
        }

        @Override
        public SampleBundle sampleBundle(int x, int z) {
            int surface = height(x, z);
            int oceanFloor = oceanFloor(x, z);
            boolean columnWater = isColumnWater(x, z);
            Holder<Biome> biome = biome(x, z);
            boolean waterBiome = biome != null && (biome.is(BiomeTags.IS_RIVER)
                    || biome.is(BiomeTags.IS_OCEAN)
                    || biome.is(BiomeTags.IS_DEEP_OCEAN));
            return new SampleBundle(surface, oceanFloor, columnWater, waterBiome,
                    SurfaceWaterClassifier.waterDepth(
                            surface, oceanFloor, waterBiome, seaLevel(), columnWater));
        }
    }
}
