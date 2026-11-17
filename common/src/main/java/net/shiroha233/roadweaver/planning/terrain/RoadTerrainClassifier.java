/* 文件职责：把粗采样分类成自动剖面所使用的地形标签。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 粗地形分类标签服务AUTO策略与诊断，不代表最终施工跨度。 */
final class RoadTerrainClassifier {
    private RoadTerrainClassifier() {
    }

    static Map<RoadTerrainType, Integer> classifyRoute(List<BlockPos> path,
                                                        PathTerrainField terrain) {
        return classifyRoute(path, terrain, null);
    }

    /** 河流实际改道后仍识别活水道，不能只依赖原生river群系。
 *
     * Route classification with an optional Streams session. A rerouted
     * Streams channel often leaves the vanilla river biome, so routes that
     * cross the live channel must still count as RIVER and resolve the route
     * tendency to BRIDGE_FIRST ("bridge first, then tunnel").
     */
    static Map<RoadTerrainType, Integer> classifyRoute(List<BlockPos> path,
                                                        PathTerrainField terrain,
                                                        StreamsReflowingCompat.SamplingSession streams) {
        Map<RoadTerrainType, Integer> counts = new EnumMap<>(RoadTerrainType.class);
        if (path == null || terrain == null) return counts;
        int overburden = Math.max(2, ConfigService.get().planning().mountainOverburdenBlocks());
        Map<Long, Boolean> channelCache = new HashMap<>();
        for (BlockPos point : path) {
            if (point == null || !terrain.contains(point.getX(), point.getZ())) continue;
            RoadTerrainType type = classify(point, terrain, overburden, streams, channelCache);
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    static RoadTerrainType classify(BlockPos point,
                                    PathTerrainField terrain,
                                    int mountainOverburdenBlocks) {
        return classify(point, terrain, mountainOverburdenBlocks, null, null);
    }

    static RoadTerrainType classify(BlockPos point,
                                    PathTerrainField terrain,
                                    int mountainOverburdenBlocks,
                                    StreamsReflowingCompat.SamplingSession streams,
                                    Map<Long, Boolean> channelCache) {
        if (point == null || terrain == null
                || !terrain.contains(point.getX(), point.getZ())) {
            return RoadTerrainType.PLAIN;
        }
        if (streams != null && streams.isAvailable()
                && inStreamsChannel(point.getX(), point.getZ(), streams, channelCache)) {
            return RoadTerrainType.RIVER;
        }
        PathTerrainField.SampleBundle sample = terrain.sampleBundle(point.getX(), point.getZ());
        int overburden = terrain.height(point.getX(), point.getZ()) - point.getY();
        if (!sample.columnWater() && overburden >= Math.max(2, mountainOverburdenBlocks)) {
            return RoadTerrainType.MOUNTAIN;
        }
        if (!sample.columnWater()) {
            Holder<Biome> biome = terrain.biome(point.getX(), point.getZ());
            if (biome != null && biome.is(BiomeTags.IS_BEACH)) {
                return RoadTerrainType.COAST;
            }
            boolean waterBiome = biome != null
                    && (biome.is(BiomeTags.IS_OCEAN)
                    || biome.is(BiomeTags.IS_DEEP_OCEAN)
                    || biome.is(BiomeTags.IS_RIVER));
            if (waterBiome && terrain.isNearWater(point.getX(), point.getZ(), terrain.step())) {
                return RoadTerrainType.COAST;
            }
            return RoadTerrainType.PLAIN;
        }
        Holder<Biome> waterBiome = terrain.biome(point.getX(), point.getZ());
        if (waterBiome != null && waterBiome.is(BiomeTags.IS_RIVER)) {
            return RoadTerrainType.RIVER;
        }
        if (waterBiome != null
                && (waterBiome.is(BiomeTags.IS_OCEAN) || waterBiome.is(BiomeTags.IS_DEEP_OCEAN))) {
            return RoadTerrainType.OCEAN;
        }
        return hasBeachNeighbor(point, terrain) ? RoadTerrainType.COAST : RoadTerrainType.LAKE;
    }

    private static boolean inStreamsChannel(int x, int z,
                                            StreamsReflowingCompat.SamplingSession streams,
                                            Map<Long, Boolean> channelCache) {
        if (channelCache == null) {
            return streams.sample(x, z).inChannel();
        }
        return channelCache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL),
                ignored -> streams.sample(x, z).inChannel());
    }

    private static boolean hasBeachNeighbor(BlockPos point, PathTerrainField terrain) {
        int distance = Math.max(terrain.step(), 2);
        int[][] offsets = {
                {distance, 0}, {-distance, 0}, {0, distance}, {0, -distance},
                {distance, distance}, {distance, -distance}, {-distance, distance}, {-distance, -distance}
        };
        for (int[] offset : offsets) {
            int x = point.getX() + offset[0];
            int z = point.getZ() + offset[1];
            if (!terrain.contains(x, z)) continue;
            Holder<Biome> biome = terrain.biome(x, z);
            if (biome != null && biome.is(BiomeTags.IS_BEACH)) return true;
        }
        return false;
    }
}
