/* 文件职责：保存一次规划生命周期内按步长量化的精确地形数据。 */
package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 直接由精确高度采样填充的区域地形场。
 */
public final class AccurateTerrainRegion implements PathTerrainField {
    private static final byte FLAG_COLUMN_WATER = 1;
    private static final byte FLAG_WATER_BIOME = 1 << 1;

    private final AccurateRegionBounds bounds;
    private final int seaLevel;
    private short[] heights;
    private short[] oceanFloors;
    private byte[] flags;
    private Holder<Biome>[] biomes;
    private final BlockPos samplingCenter;
    private final int samplingRadius;
    private final BlockPos routeStart;
    private final BlockPos routeGoal;

    public AccurateTerrainRegion(AccurateRegionBounds bounds,
                                 int seaLevel,
                                 short[] heights,
                                 short[] oceanFloors,
                                 byte[] flags,
                                 Holder<Biome>[] biomes) {
        this(bounds, seaLevel, heights, oceanFloors, flags, biomes, null, 0, null, null);
    }

    public AccurateTerrainRegion(AccurateRegionBounds bounds,
                                 int seaLevel,
                                 short[] heights,
                                 short[] oceanFloors,
                                 byte[] flags,
                                 Holder<Biome>[] biomes,
                                 BlockPos samplingCenter,
                                 int samplingRadius) {
        this(bounds, seaLevel, heights, oceanFloors, flags, biomes,
                samplingCenter, samplingRadius, null, null);
    }

    public AccurateTerrainRegion(AccurateRegionBounds bounds,
                                 int seaLevel,
                                 short[] heights,
                                 short[] oceanFloors,
                                 byte[] flags,
                                 Holder<Biome>[] biomes,
                                 BlockPos samplingCenter,
                                 int samplingRadius,
                                 BlockPos routeStart,
                                 BlockPos routeGoal) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.seaLevel = seaLevel;
        this.heights = Objects.requireNonNull(heights, "heights");
        this.oceanFloors = Objects.requireNonNull(oceanFloors, "oceanFloors");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.biomes = Objects.requireNonNull(biomes, "biomes");
        this.samplingCenter = samplingCenter;
        this.samplingRadius = Math.max(0, samplingRadius);
        this.routeStart = routeStart;
        this.routeGoal = routeGoal;
        int expected = Math.toIntExact(bounds.sampleCount());
        if (heights.length != expected || oceanFloors.length != expected
                || flags.length != expected || biomes.length != expected) {
            throw new IllegalArgumentException("accurate region arrays do not match bounds");
        }
    }

    public AccurateRegionBounds bounds() {
        return bounds;
    }

    @Override
    public int seaLevel() {
        return seaLevel;
    }

    @Override
    public int height(int x, int z) {
        return contains(x, z) ? heights[bounds.indexOfNearest(x, z)] : seaLevel;
    }

    @Override
    public int oceanFloor(int x, int z) {
        return contains(x, z) ? oceanFloors[bounds.indexOfNearest(x, z)] : seaLevel;
    }

    @Override
    public boolean isColumnWater(int x, int z) {
        return contains(x, z) && hasFlag(bounds.indexOfNearest(x, z), FLAG_COLUMN_WATER);
    }

    @Override
    public boolean isNearWater(int x, int z, int neighborDistance) {
        if (!contains(x, z)) {
            return false;
        }
        int alignedDistance = Math.max(step(), alignDistance(neighborDistance));
        return isColumnWater(x, z)
                || isColumnWater(x + alignedDistance, z)
                || isColumnWater(x - alignedDistance, z)
                || isColumnWater(x, z + alignedDistance)
                || isColumnWater(x, z - alignedDistance)
                || isColumnWater(x + alignedDistance, z + alignedDistance)
                || isColumnWater(x + alignedDistance, z - alignedDistance)
                || isColumnWater(x - alignedDistance, z + alignedDistance)
                || isColumnWater(x - alignedDistance, z - alignedDistance);
    }

    @Override
    public Holder<Biome> biome(int x, int z) {
        return contains(x, z) ? biomes[bounds.indexOfNearest(x, z)] : null;
    }

    @Override
    public boolean contains(int x, int z) {
        if (!bounds.contains(x, z)) return false;
        if (samplingCenter == null || samplingRadius <= 0) return true;
        if (isRouteEndpoint(x, z, routeStart) || isRouteEndpoint(x, z, routeGoal)) return true;
        long dx = (long) x - samplingCenter.getX();
        long dz = (long) z - samplingCenter.getZ();
        return dx * dx + dz * dz <= (long) samplingRadius * samplingRadius;
    }

    private static boolean isRouteEndpoint(int x, int z, BlockPos endpoint) {
        return endpoint != null && endpoint.getX() == x && endpoint.getZ() == z;
    }

    @Override
    public int step() {
        return bounds.step();
    }

    @Override
    public boolean hasAccurateSample(int x, int z) {
        return contains(x, z);
    }

    @Override
    public boolean isWaterBiome(int x, int z) {
        return contains(x, z) && hasFlag(bounds.indexOfNearest(x, z), FLAG_WATER_BIOME);
    }

    @Override
    public SampleBundle sampleBundle(int x, int z) {
        if (!contains(x, z)) {
            return new SampleBundle(seaLevel, seaLevel, false, false, 0);
        }
        int index = bounds.indexOfNearest(x, z);
        int oceanFloor = oceanFloors[index];
        boolean columnWater = hasFlag(index, FLAG_COLUMN_WATER);
        boolean waterBiome = hasFlag(index, FLAG_WATER_BIOME);
        int height = heights[index];
        return new SampleBundle(height, oceanFloor, columnWater, waterBiome,
                SurfaceWaterClassifier.waterDepth(height, oceanFloor, waterBiome, seaLevel, columnWater));
    }

    @Override
    public void dispose() {
        heights = null;
        oceanFloors = null;
        flags = null;
        biomes = null;
    }

    public boolean isDisposed() {
        return heights == null;
    }

    static byte flags(boolean columnWater, boolean waterBiome) {
        int value = 0;
        if (columnWater) {
            value |= FLAG_COLUMN_WATER;
        }
        if (waterBiome) {
            value |= FLAG_WATER_BIOME;
        }
        return (byte) value;
    }

    private boolean hasFlag(int index, byte flag) {
        return (flags[index] & flag) != 0;
    }

    private int alignDistance(int distance) {
        int cells = Math.max(1, (int) Math.ceil(Math.max(0, distance) / (double) step()));
        return cells * step();
    }
}
