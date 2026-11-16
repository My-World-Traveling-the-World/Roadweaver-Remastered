package net.shiroha233.roadweaver.bridge;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandPathfinderWaterCostTest {
    private static final BlockPos START = new BlockPos(-64, 64, 0);
    private static final BlockPos END = new BlockPos(64, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualRouteUsesWaterColumnAndBiomeFlagsToLimitLakeExposure() {
        for (boolean biomeOnly : new boolean[]{false, true}) {
            var terrain = new Terrain(32, biomeOnly, (x, z) -> Math.abs(x) <= 40 && Math.abs(z) <= 8);
            var straight = route(terrain, config(0, 36, 0));
            assertTrue(wetStations(straight, terrain) >= 8, "关权重时正控确实穿湖");
            assertEquals(0, terrain.reads, "权重关时不查水");

            var weighted = route(terrain, config(2, 36, 0));
            assertTrue(wetStations(weighted, terrain) < wetStations(straight, terrain) / 2,
                    "生产路径必须明显减少湿站，不能只改日志或度量参数");
            assertEquals(189, terrain.reads, "在 21×9 个可用采样点各读一次，FMM 循环不查地形水标记");
        }
    }

    @Test
    void cheaperWaterCanAttractRouteAndEqualCostsSkipAllWaterReads() {
        var terrain = new Terrain(32, false, (x, z) -> Math.abs(x) <= 56 && z >= 8 && z <= 24);
        var equal = route(terrain, config(2, 36, 36));
        assertEquals(0, wetStations(equal, terrain));
        assertEquals(0, terrain.reads);
        var preferred = route(terrain, config(2, -12, 36));
        assertTrue(wetStations(preferred, terrain) >= 5, "价差反向时，应从可走的陆地转向近旁便宜的水带");
        assertEquals(preferred, route(terrain, config(2, 8, 56)), "一起平移水陆报价，差价和路径均不变");
    }

    @Test
    void evenExtremeWaterCostKeepsTheOnlyCrossingReachable() {
        var terrain = new Terrain(0, false, (x, z) -> Math.abs(x) <= 16);
        var onlyCrossing = route(terrain, config(1_000, 100_000, -100_000));
        assertTrue(wetStations(onlyCrossing, terrain) >= 4);
        assertTrue(onlyCrossing.stream().allMatch(p -> p.getZ() == 0));
    }

    private static PathfindingCostConfig config(int weight, double wet, double dry) {
        var config = new PathfindingCostConfig();
        config.setAStarStep(8);
        config.setBiomeWeight(weight);
        config.setBiomeCostWater(wet);
        config.setBiomeCostOther(dry);
        return config;
    }

    private static List<BlockPos> route(Terrain terrain, PathfindingCostConfig config) {
        var result = new BandPathfinder().findPath(START, END, 1, null, 1000, null, terrain, config);
        assertTrue(result.success(), "同一夹具必须完成两端间的生产寻路");
        var path = result.rawPath();
        assertFalse(path.isEmpty());
        assertEquals(START, path.getFirst());
        assertEquals(END, path.getLast());
        return path;
    }

    private static long wetStations(List<BlockPos> path, Terrain terrain) {
        return path.stream().filter(p -> terrain.water.test(p.getX(), p.getZ())).count();
    }

    private static final class Terrain implements PathTerrainField {
        private final int zBound;
        private final boolean biomeOnly;
        private final BiPredicate<Integer, Integer> water;
        private int reads;

        Terrain(int zBound, boolean biomeOnly, BiPredicate<Integer, Integer> water) {
            this.zBound = zBound;
            this.biomeOnly = biomeOnly;
            this.water = water;
        }

        @Override public int seaLevel() { return 63; }
        @Override public int height(int x, int z) { return 64; }
        @Override public int oceanFloor(int x, int z) { return 64; }
        @Override public boolean isColumnWater(int x, int z) { return !biomeOnly && water.test(x, z); }
        @Override public boolean isWaterBiome(int x, int z) { return biomeOnly && water.test(x, z); }
        @Override public boolean isRoutingWater(int x, int z) {
            reads++;
            return PathTerrainField.super.isRoutingWater(x, z);
        }
        @Override public boolean isNearWater(int x, int z, int distance) { return water.test(x, z); }
        @Override public Holder<Biome> biome(int x, int z) { throw new AssertionError("水域粗选线不应额外查群系"); }
        @Override public boolean contains(int x, int z) { return Math.abs(x) <= 80 && Math.abs(z) <= zBound; }
        @Override public int step() { return 8; }
    }
}
