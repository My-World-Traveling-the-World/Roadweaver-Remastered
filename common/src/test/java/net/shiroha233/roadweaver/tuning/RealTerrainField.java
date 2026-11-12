/* 文件职责：把 RealTerrain 适配为只读量化地形场 PathTerrainField。 */
package net.shiroha233.roadweaver.tuning;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/**
 * {@link PathTerrainField} 的真实地形实现，全部委托给 {@link RealTerrain}。
 *
 * <p>与 {@link SyntheticTerrainField} 同构：{@code biome()} 返回 {@code null}（离线无真实
 * {@code Holder<Biome>}），{@code sampleBundle()} 覆写为直接计算并把水柱标记为 {@code waterBiome=true}，
 * 对齐生产里河流/海洋群系对水柱额外收费的行为。
 */
public final class RealTerrainField implements PathTerrainField {

    private final RealTerrain terrain;
    private final int step;

    public RealTerrainField(RealTerrain terrain, int step) {
        this.terrain = terrain;
        this.step = step;
    }

    @Override
    public int seaLevel() {
        return 64;
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
