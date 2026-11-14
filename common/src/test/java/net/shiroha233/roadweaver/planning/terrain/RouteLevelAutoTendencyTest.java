package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-level AUTO regression (audit follow-up): drives the real decision
 * chain — {@link RoadTerrainClassifier#classifyRoute} over a controllable
 * {@link PathTerrainField}, the fraction thresholds, the route-level tendency
 * registry and the exact-phase consumers ({@code RoadGradePlanner}
 * cut/fill snapshot, {@code PathSpanExtractor} bridge depth, height-aware
 * trigger) — without needing a ServerLevel. The A* internals of
 * {@code RawPathSearch.find} stay covered by the model regressions.
 *
 * <p>The water route uses null-biome water columns, which the classifier
 * labels LAKE — a real water type that counts toward the water share, so no
 * live biome registry is required.
 */
final class RouteLevelAutoTendencyTest {

    private final StructureConnection connection = new StructureConnection(
            new BlockPos(0, 64, 0), new BlockPos(320, 64, 0));

    @AfterEach
    void restoreDefaults() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
        RouteProfileTendency.clear(connection);
    }

    /** Uniform terrain field: one surface height, optional water column, optional biome. */
    private static PathTerrainField field(int surfaceY, boolean water, Holder<Biome> biome) {
        return new PathTerrainField() {
            @Override public int seaLevel() { return 63; }
            @Override public int height(int x, int z) { return surfaceY; }
            @Override public int oceanFloor(int x, int z) { return water ? surfaceY - 6 : surfaceY - 1; }
            @Override public boolean isColumnWater(int x, int z) { return water; }
            @Override public boolean isNearWater(int x, int z, int neighborDistance) { return water; }
            @Override public Holder<Biome> biome(int x, int z) { return biome; }
            @Override public boolean contains(int x, int z) { return true; }
            @Override public int step() { return 4; }
            @Override public boolean isWaterBiome(int x, int z) { return water; }
            @Override public SampleBundle sampleBundle(int x, int z) {
                return new SampleBundle(surfaceY, water ? surfaceY - 6 : surfaceY - 1,
                        water, water, water ? 6 : 0);
            }
        };
    }

    private static List<BlockPos> route(int y) {
        List<BlockPos> route = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            route.add(new BlockPos(i * 8, y, 0));
        }
        return route;
    }

    /** The exact composition RawPathSearch.find performs after classification. */
    private RouteProfileTendency.Tendency classifyAndStore(List<BlockPos> route,
                                                           PathTerrainField terrain) {
        Map<RoadTerrainType, Integer> counts = RoadTerrainClassifier.classifyRoute(route, terrain);
        RouteProfileTendency.Tendency tendency = RawPathSearch.resolveAutoTendency(
                ConfigService.get().planning().terrainProfileMode(),
                RawPathSearch.mountainFraction(counts),
                RawPathSearch.waterFraction(counts),
                ConfigService.get().planning().autoMountainThresholdFraction(),
                ConfigService.get().planning().autoWaterThresholdFraction());
        RouteProfileTendency.set(connection, tendency);
        return tendency;
    }

    @Test
    void mountainRouteSwitchesToTunnelFirstBehavior() {
        // Surface 40 blocks above the road line: every station is MOUNTAIN.
        List<BlockPos> route = route(60);
        RouteProfileTendency.Tendency tendency =
                classifyAndStore(route, field(100, false, null));
        assertEquals(RouteProfileTendency.Tendency.TUNNEL_FIRST, tendency);
        assertEquals(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        // Cut tightened to 3 and the height-aware search triggers.
        SurfaceGradeLimits limits = SurfaceGradeLimits.capture(
                RouteProfileTendency.effectiveMode(connection));
        assertEquals(3, limits.cut(false));
        assertTrue(RawPathSearch.wantsHeightAware(true, false, true, true));
        // Tunnel-first keeps the configured bridge depth.
        assertEquals(4, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.TUNNEL_FIRST, 4));
    }

    @Test
    void waterRouteSwitchesToBridgeFirstBehavior() {
        // Null-biome water columns classify as LAKE — a real water type.
        List<BlockPos> route = route(63);
        RouteProfileTendency.Tendency tendency =
                classifyAndStore(route, field(62, true, null));
        assertEquals(RouteProfileTendency.Tendency.BRIDGE_FIRST, tendency);
        assertEquals(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        // Fill tightened to 1 and the bridge depth threshold relaxes to 1.
        SurfaceGradeLimits limits = SurfaceGradeLimits.capture(
                RouteProfileTendency.effectiveMode(connection));
        assertEquals(1, limits.fill(false));
        assertEquals(1, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.BRIDGE_FIRST, 4));
        // No height-aware trigger from a flat water route alone.
        assertTrue(!RawPathSearch.wantsHeightAware(true, false, false, false));
    }

    @Test
    void balancedTerrainKeepsTheAutoBaseline() {
        List<BlockPos> route = route(64);
        RouteProfileTendency.Tendency tendency =
                classifyAndStore(route, field(64, false, null));
        assertEquals(RouteProfileTendency.Tendency.BALANCED, tendency);
        assertEquals(PlanningConfig.TerrainProfileMode.AUTO,
                RouteProfileTendency.effectiveMode(connection));
        SurfaceGradeLimits limits = SurfaceGradeLimits.capture(
                RouteProfileTendency.effectiveMode(connection));
        assertEquals(5, limits.cut(false));
        assertEquals(2, limits.fill(false));
        assertEquals(4, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.AUTO, 4));
    }

    @Test
    void explicitGlobalModeWinsOverRouteTerrain() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST);
        RouteProfileTendency.Tendency tendency =
                classifyAndStore(route(63), field(62, true, null));
        // Water route under an explicit mode: no route-level override, global wins.
        assertEquals(RouteProfileTendency.Tendency.BALANCED, tendency);
        assertEquals(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.LIGHT_CUT);
        assertEquals(PlanningConfig.TerrainProfileMode.LIGHT_CUT,
                RouteProfileTendency.effectiveMode(connection));
    }
}
