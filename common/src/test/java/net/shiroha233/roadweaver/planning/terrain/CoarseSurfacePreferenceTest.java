package net.shiroha233.roadweaver.planning.terrain;

import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.sampling.AccuratePathHeightResolver;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CoarseSurfacePreferenceTest {
    @Test void accurateRefreshOfSparseCorridorDoesNotFlattenALegalSlope() {
        // 每 16 格升两格，满足默认纵坡档；若按施工站高差最多一格，峰顶会被削低。
        var terrain = new Field((x, z) -> 64 + 2 * Math.min(x / 16, 6 - x / 16), false);
        var route = IntStream.rangeClosed(0, 6).mapToObj(i -> new BlockPos(i * 16, terrain.height(i * 16, 0), 0)).toList();
        assertEquals(route, AccuratePathHeightResolver.resolve(route, terrain, null, true));
        assertTrue(AccuratePathHeightResolver.resolve(route, terrain, null).get(3).getY() < route.get(3).getY());
    }

    @Test void broadHillKeepsSurfaceAtEightBlockSpacing() {
        // 同一座缓山每八格升三格；如果误按一格一站，坡顶会被压入山体。
        var terrain = new Field((x, z) -> 64 + 3 * Math.min(x / 8, 14 - x / 8), false);
        var route = stations(14, terrain);
        var profile = RawPathSearch.applyCoarseTerrainModes(route, terrain, config(), 4);
        assertEquals(route, profile.stations());
        assertFalse(profile.needsTunnel());
    }

    @Test void boundedSurfaceSearchUsesAnOpenPassAndRejectsAnOverlongDetour() {
        // 山脊顶高于坡限，从 z=16 的山口可连通；直走 z=0 则会进入隧道候选。
        var terrain = new Field((x, z) -> x >= 24 && x <= 56 && z < 16 ? 92 : 64, false);
        var direct = stations(10, terrain);
        assertTrue(RawPathSearch.applyCoarseTerrainModes(direct, terrain, config(), 4).needsTunnel());
        var candidate = CoarseHeightAwarePlanner.findSurface(null, direct.getFirst(), direct.getLast(),
                terrain, config(), 5_000, direct, 1.25D);
        assertNotNull(candidate);
        assertEquals(direct.getFirst(), candidate.getFirst());
        assertEquals(direct.getLast(), candidate.getLast());
        assertTrue(candidate.stream().anyMatch(p -> p.getX() == 40 && p.getZ() >= 16));
        for (BlockPos p : candidate) assertEquals(terrain.height(p.getX(), p.getZ()), p.getY());
        assertNull(CoarseHeightAwarePlanner.findSurface(null, direct.getFirst(), direct.getLast(),
                terrain, config(), 5_000, direct, 1.0D));
    }

    @Test void closedRidgeKeepsTunnelHintAndWetColumnsStayProtected() {
        var ridge = new Field((x, z) -> x >= 24 && x <= 56 ? 92 : 64, false);
        var direct = stations(10, ridge);
        var profile = RawPathSearch.applyCoarseTerrainModes(direct, ridge, config(), 4);
        assertTrue(profile.needsTunnel());
        assertEquals(64, profile.stations().get(5).getY());
        assertNull(CoarseHeightAwarePlanner.findSurface(null, direct.getFirst(), direct.getLast(),
                ridge, config(), 5_000, direct, 1.25D));

        var wet = new Field((x, z) -> 64, true);
        var crossing = stations(10, wet);
        var waterProfile = RawPathSearch.applyCoarseTerrainModes(crossing, wet, config(), 4);
        assertTrue(waterProfile.hasWater());
        assertFalse(waterProfile.needsTunnel());
        assertEquals(crossing, waterProfile.stations());
        assertNull(CoarseHeightAwarePlanner.findSurface(null, crossing.getFirst(), crossing.getLast(),
                wet, config(), 5_000, crossing, 1.25D));
    }

    private static PathfindingCostConfig config() {
        // 使用独立配置，避免把默认网格的未来改动带进夹具。
        assertEquals(0.5D, ConfigService.get().planning().maxGradeFraction());
        var config = new PathfindingCostConfig();
        config.setPathGridStep(8);
        return config;
    }

    private static List<BlockPos> stations(int last, Field field) {
        return IntStream.rangeClosed(0, last)
                .mapToObj(i -> new BlockPos(i * 8, field.height(i * 8, 0), 0)).toList();
    }

    private record Field(IntBinaryOperator heights, boolean river) implements PathTerrainField {
        public int seaLevel() { return 63; }
        public int height(int x, int z) { return heights.applyAsInt(x, z); }
        public int oceanFloor(int x, int z) { return height(x, z) - (isColumnWater(x, z) ? 10 : 1); }
        public boolean isColumnWater(int x, int z) { return river && x >= 32 && x <= 48; }
        public boolean isNearWater(int x, int z, int distance) { return isColumnWater(x, z); }
        public Holder<Biome> biome(int x, int z) { return null; }
        public boolean contains(int x, int z) { return x >= 0 && x <= 112 && z >= -16 && z <= 24; }
        public int step() { return 8; }
        public boolean hasAccurateSample(int x, int z) { return contains(x, z); }
        public SampleBundle sampleBundle(int x, int z) {
            boolean water = isColumnWater(x, z);
            return new SampleBundle(height(x, z), oceanFloor(x, z), water, water, water ? 10 : 0);
        }
    }
}
