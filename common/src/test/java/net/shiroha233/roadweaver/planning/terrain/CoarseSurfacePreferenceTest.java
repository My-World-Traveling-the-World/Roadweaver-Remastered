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
        var profile = RawPathSearch.applyCoarseTerrainModes(route, terrain, config(), null);
        assertEquals(route, profile.stations());
        assertFalse(profile.needsTunnel());
    }

    @Test void boundedSurfaceSearchUsesAnOpenPassAndRejectsAnOverlongDetour() {
        // 山脊顶高于坡限，从 z=16 的山口可连通；直走 z=0 则会进入隧道候选。
        var terrain = new Field((x, z) -> x >= 24 && x <= 56 && z < 16 ? 92 : 64, false);
        var direct = stations(10, terrain);
        assertTrue(RawPathSearch.applyCoarseTerrainModes(direct, terrain, config(), null).needsTunnel());
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
        var profile = RawPathSearch.applyCoarseTerrainModes(direct, ridge, config(), null);
        assertTrue(profile.needsTunnel());
        assertEquals(CoarseRouteSketch.TUNNEL, profile.sketch().modes()[5]);
        assertEquals(64, profile.stations().get(5).getY());
        assertNull(CoarseHeightAwarePlanner.findSurface(null, direct.getFirst(), direct.getLast(),
                ridge, config(), 5_000, direct, 1.25D));

        var wet = new Field((x, z) -> 64, true);
        var crossing = stations(10, wet);
        var waterProfile = RawPathSearch.applyCoarseTerrainModes(crossing, wet, config(), null);
        assertTrue(waterProfile.hasWater());
        assertFalse(waterProfile.needsTunnel());
        assertEquals(crossing, waterProfile.stations());
        assertEquals(CoarseRouteSketch.BRIDGE, waterProfile.sketch().modes()[5]);
        assertNull(CoarseHeightAwarePlanner.findSurface(null, crossing.getFirst(), crossing.getLast(),
                wet, config(), 5_000, crossing, 1.25D));
    }

    @Test void mountainContourReachesTheRealEndpointOutsideTheCoarseLattice() {
        // 只有山口方向是缓地；终点在 8 格网格外，仍要原坐标进站。
        var terrain = new Field((x, z) -> x >= 24 && x <= 56 && z < 8 ? 92 : 64, false);
        var start = new BlockPos(0, 64, 0);
        for (var goal : List.of(new BlockPos(80, 64, 0), new BlockPos(83, 64, -3))) {
            var result = MountainSwitchbackPlanner.findContourRoute(start, goal, terrain, config(),
                    4_000, 10_000_000_000L);
            assertNotNull(result.path(), result.reason());
            assertEquals(start, result.path().getFirst());
            assertEquals(goal, result.path().getLast(), "不能以网格站替代真实端点");
            assertTrue(result.path().stream().anyMatch(p -> p.getX() == 40 && p.getZ() >= 8), "必须穿过山口");
            assertTrue(result.path().stream().allMatch(p -> p.getY() == 64), "不能借陡坡通过山脊");
            assertTrue(result.expanded() < 4_000, "有路时不应耗尽状态预算");
        }
    }

    @Test void sketchPrefersTheReachableSideOfAMountainPass() {
        var terrain = new Field((x, z) -> x <= 8 ? 110 : z > 0 ? Math.max(68, 110 - (x - 8) / 2) : 68, false);
        var shortLine = stations(13, terrain);
        var pass = IntStream.rangeClosed(0, 13).mapToObj(i -> {
            int z = i == 0 || i == 13 ? 0 : 8;
            return new BlockPos(i * 8, terrain.height(i * 8, z), z);
        }).toList();
        assertEquals(shortLine.getFirst(), pass.getFirst());
        assertEquals(shortLine.getLast(), pass.getLast());
        var blocked = RawPathSearch.applyCoarseTerrainModes(shortLine, terrain, config(), null);
        var reachable = RawPathSearch.applyCoarseTerrainModes(pass, terrain, config(), null);
        assertTrue(blocked.sketch().gradeDeficit() > 20.0, "短直线存在不可达的落差");
        assertEquals(0.0, reachable.sketch().gradeDeficit(), "缓坡可以保留在地表走廊");
    }

    @Test void narrowWetAndDryCutsUseTheBanksAtDifferentSamplingSteps() {
        for (int step : new int[]{1, 2, 4}) {
            var path = IntStream.rangeClosed(0, 64 / step).mapToObj(i -> new BlockPos(i * step, 100, 0)).toList();
            int[] ground = path.stream().mapToInt(p -> p.getX() >= 28 && p.getX() < 36 ? 10 : 100).toArray();
            int[] wet = ground.clone(), depth = new int[path.size()];
            for (int i = 0; i < depth.length; i++) if (ground[i] == 10) { wet[i] = 52; depth[i] = 42; }
            var drySketch = CoarseRouteSketch.build(path, ground, ground, new int[path.size()], new int[depth.length],
                    5, 4, 8, 0.5, 12, 6, 2, 6);
            var wetSketch = CoarseRouteSketch.build(path, ground, ground, wet, depth,
                    5, 4, 8, 0.5, 12, 6, 2, 6);
            int at = 32 / step;
            assertEquals(CoarseRouteSketch.SHORT_CROSSING, drySketch.modes()[at]);
            assertEquals(CoarseRouteSketch.SHORT_CROSSING, wetSketch.modes()[at]);
            assertEquals(100, drySketch.heights()[at]);
            assertEquals(100, wetSketch.heights()[at]);
            assertEquals(0.0, drySketch.gradeDeficit());
            var groundPath = IntStream.range(0, path.size()).mapToObj(i -> path.get(i).atY(ground[i])).toList();
            var exported = drySketch.seedCrossings(groundPath);
            assertEquals(100, exported.get(at).getY(), "跨越草图必须交给下一阶段");
            assertEquals(groundPath.getFirst(), exported.getFirst());
            assertEquals(groundPath.getLast(), exported.getLast());
        }
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
