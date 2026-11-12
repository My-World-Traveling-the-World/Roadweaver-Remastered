/* 文件职责：定义普通道路搜索阶段使用的只读量化地形场接口。 */
package net.shiroha233.roadweaver.pathfinding.terrain;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;

/**
 * 普通道路搜索阶段使用的只读量化地形场。
 */
public interface PathTerrainField {

    /**
     * 寻路用于「群系走向」的路由类别。默认实现只区分水/非水，
     * 量化实现（含合成地形）可覆写为更细粒度（山/林/沼泽/平原/沙漠），
     * 配合 {@link net.shiroha233.roadweaver.config.sub.PathfindingCostConfig#biomeCostFor(BiomeCategory)}
     * 的逐类别代价表，实现按群系偏好的自然蜿蜒走向。
     */
    enum BiomeCategory { WATER, PLAINS, FOREST, MOUNTAIN, SWAMP, DESERT, OTHER }

    int seaLevel();

    int height(int x, int z);

    int oceanFloor(int x, int z);

    boolean isColumnWater(int x, int z);

    boolean isNearWater(int x, int z, int neighborDistance);

    Holder<Biome> biome(int x, int z);

    boolean contains(int x, int z);

    int step();

    /**
     * 当前坐标的高度、水体与生物群系数据是否来自精确采样。
     */
    default boolean hasAccurateSample(int x, int z) {
        return false;
    }

    /**
     * 释放内部大数组，协助 GC 回收。默认空操作。
     * 持有紧凑数组的实现应覆写。
     */
    default void dispose() {}

    default boolean isWaterBiome(int x, int z) {
        if (!contains(x, z)) {
            return false;
        }
        Holder<Biome> biomeHolder = biome(x, z);
        return biomeHolder != null
                && (biomeHolder.is(BiomeTags.IS_RIVER)
                || biomeHolder.is(BiomeTags.IS_OCEAN)
                || biomeHolder.is(BiomeTags.IS_DEEP_OCEAN));
    }

    /**
     * 该坐标的「群系走向」路由类别。默认实现只区分水/非水（与旧行为一致），
     * 非水一律归 {@link BiomeCategory#OTHER}；合成地形与精确地形可覆写为更细粒度。
     */
    default BiomeCategory biomeCategory(int x, int z) {
        return (isColumnWater(x, z) || isWaterBiome(x, z)) ? BiomeCategory.WATER : BiomeCategory.OTHER;
    }

    default int waterDepth(int x, int z) {
        return SurfaceWaterClassifier.waterDepth(
                height(x, z), oceanFloor(x, z), isWaterBiome(x, z), seaLevel(), isColumnWater(x, z));
    }

    default boolean isBridgeWater(int x, int z, int minWaterDepth) {
        return contains(x, z)
                && isColumnWater(x, z)
                && waterDepth(x, z) >= Math.max(1, minWaterDepth);
    }

    /**
     * 单次读出邻居展开所需的所有属性，避免对同一坐标的多次 cache lookup。
     * 默认实现仅组合现有方法，量化实现可折叠为单次数组访问。
     */
    default SampleBundle sampleBundle(int x, int z) {
        int h = height(x, z);
        int ocean = oceanFloor(x, z);
        boolean water = isColumnWater(x, z);
        boolean waterBiome = isWaterBiome(x, z);
        int depth = SurfaceWaterClassifier.waterDepth(h, ocean, waterBiome, seaLevel(), water);
        return new SampleBundle(h, ocean, water, waterBiome, depth);
    }

    record SampleBundle(int height, int oceanFloor, boolean columnWater, boolean waterBiome, int waterDepth) {}
}
