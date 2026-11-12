/* 文件职责：把 SyntheticTerrain 适配为只读量化地形场 PathTerrainField。 */
package net.shiroha233.roadweaver.tuning;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * {@link PathTerrainField} 的合成实现，全部委托给 {@link SyntheticTerrain}。
 *
 * <p>离线约束：{@code biome()} 返回 {@code null}（合成地形无真实群系），
 * {@code sampleBundle()} 覆写为直接计算，把水柱同时标记为 {@code waterBiome=true}，
 * 以对齐生产里河流/海洋群系对水柱额外收取群系代价的行为。
 */
public final class SyntheticTerrainField implements PathTerrainField {

    private final SyntheticTerrain terrain;
    private final int step;

    public SyntheticTerrainField(SyntheticTerrain terrain, int step) {
        this.terrain = terrain;
        this.step = step;
    }

    @Override
    public int seaLevel() {
        return SyntheticTerrain.SEA_LEVEL;
    }

    @Override
    public int height(int x, int z) {
        return terrain.surfaceY(x, z);
    }

    @Override
    public int oceanFloor(int x, int z) {
        return terrain.floorY(x, z);
    }

    @Override
    public boolean isColumnWater(int x, int z) {
        return terrain.isWater(x, z);
    }

    @Override
    public boolean isNearWater(int x, int z, int neighborDistance) {
        return terrain.isNearWater(x, z, neighborDistance);
    }

    @Override
    public Holder<Biome> biome(int x, int z) {
        return null;
    }

    @Override
    public BiomeCategory biomeCategory(int x, int z) {
        return terrain.biomeCategory(x, z);
    }

    @Override
    public boolean contains(int x, int z) {
        return terrain.contains(x, z);
    }

    @Override
    public int step() {
        return step;
    }

    @Override
    public SampleBundle sampleBundle(int x, int z) {
        boolean water = terrain.isWater(x, z);
        return new SampleBundle(
                terrain.surfaceY(x, z),
                terrain.floorY(x, z),
                water,
                water,
                terrain.waterDepth(x, z));
    }
}
