package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the real AUTO terrain switching (audit item: AUTO used to be a plain
 * alias of the balanced behavior). The route-level tendency is resolved from
 * the classified terrain shares inside RawPathSearch.find and stored per
 * connection — the global config and the persisted fingerprint never see it,
 * and explicit TUNNEL_FIRST / BRIDGE_FIRST / LIGHT_CUT selections ignore the
 * thresholds entirely.
 */
final class TerrainProfileModeAUTOBehaviorTest {
    private static final double DEFAULT_MOUNTAIN = 0.30D;
    private static final double DEFAULT_WATER = 0.25D;

    private final StructureConnection connection = new StructureConnection(
            new BlockPos(0, 64, 0), new BlockPos(400, 64, 400));

    @AfterEach
    void restoreDefaults() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
        planning.setAutoMountainThresholdFraction(DEFAULT_MOUNTAIN);
        planning.setAutoWaterThresholdFraction(DEFAULT_WATER);
        planning.sanitize();
        RouteProfileTendency.clear(connection);
    }

    private static Map<RoadTerrainType, Integer> counts(Object... pairs) {
        Map<RoadTerrainType, Integer> counts = new EnumMap<>(RoadTerrainType.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            counts.put((RoadTerrainType) pairs[i], (Integer) pairs[i + 1]);
        }
        return counts;
    }

    @Test
    void fractionsCountWaterColumnsButNotCoast() {
        Map<RoadTerrainType, Integer> counts = counts(
                RoadTerrainType.MOUNTAIN, 40, RoadTerrainType.PLAIN, 50, RoadTerrainType.RIVER, 10);
        assertEquals(0.4D, RawPathSearch.mountainFraction(counts), 1e-9);
        assertEquals(0.1D, RawPathSearch.waterFraction(counts), 1e-9);

        Map<RoadTerrainType, Integer> shore = counts(
                RoadTerrainType.COAST, 30, RoadTerrainType.OCEAN, 30, RoadTerrainType.PLAIN, 40);
        assertEquals(0.0D, RawPathSearch.mountainFraction(shore), 1e-9);
        assertEquals(0.3D, RawPathSearch.waterFraction(shore), 1e-9);
    }

    @Test
    void autoSwitchesToTunnelFirstOnMountainShare() {
        assertEquals(RouteProfileTendency.Tendency.TUNNEL_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.4D, 0.1D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void autoSwitchesToBridgeFirstOnWaterShare() {
        assertEquals(RouteProfileTendency.Tendency.BRIDGE_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.1D, 0.3D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void bothOverThresholdPrefersTheLargerShare() {
        assertEquals(RouteProfileTendency.Tendency.TUNNEL_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.4D, 0.3D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
        assertEquals(RouteProfileTendency.Tendency.BRIDGE_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.3D, 0.4D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void belowBothThresholdsStaysBalanced() {
        assertEquals(RouteProfileTendency.Tendency.BALANCED,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.1D, 0.2D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void thresholdIsInclusive() {
        assertEquals(RouteProfileTendency.Tendency.TUNNEL_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.30D, 0.0D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
        assertEquals(RouteProfileTendency.Tendency.BRIDGE_FIRST,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.AUTO,
                        0.0D, 0.25D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void explicitModesNeverResolveARouteTendency() {
        assertEquals(RouteProfileTendency.Tendency.BALANCED,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                        0.9D, 0.9D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
        assertEquals(RouteProfileTendency.Tendency.BALANCED,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST,
                        0.9D, 0.9D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
        assertEquals(RouteProfileTendency.Tendency.BALANCED,
                RawPathSearch.resolveAutoTendency(PlanningConfig.TerrainProfileMode.LIGHT_CUT,
                        0.9D, 0.9D, DEFAULT_MOUNTAIN, DEFAULT_WATER));
    }

    @Test
    void routeTendencyIsVisibleOnlyToAutoGeneration() {
        ConfigService.get().planning().setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
        RouteProfileTendency.set(connection, RouteProfileTendency.Tendency.TUNNEL_FIRST);
        assertEquals(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        RouteProfileTendency.clear(connection);
        assertEquals(PlanningConfig.TerrainProfileMode.AUTO,
                RouteProfileTendency.effectiveMode(connection));
    }

    @Test
    void explicitGlobalModeOverridesAnyStoredTendency() {
        RouteProfileTendency.set(connection, RouteProfileTendency.Tendency.BRIDGE_FIRST);
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST);
        assertEquals(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.LIGHT_CUT);
        assertEquals(PlanningConfig.TerrainProfileMode.LIGHT_CUT,
                RouteProfileTendency.effectiveMode(connection));
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST);
        assertEquals(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST,
                RouteProfileTendency.effectiveMode(connection));
    }

    @Test
    void autoThresholdsClampToDocumentedRange() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setAutoMountainThresholdFraction(5.0D);
        planning.setAutoWaterThresholdFraction(-1.0D);
        planning.sanitize();
        assertEquals(0.9D, planning.autoMountainThresholdFraction(), 1e-9);
        assertEquals(0.1D, planning.autoWaterThresholdFraction(), 1e-9);
    }

    @Test
    void routeTendencyLifecycleSetOverwriteClear() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
        // Planned: resolve and store.
        RouteProfileTendency.set(connection, RouteProfileTendency.Tendency.TUNNEL_FIRST);
        assertEquals(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        // Re-planned: overwritten.
        RouteProfileTendency.set(connection, RouteProfileTendency.Tendency.BRIDGE_FIRST);
        assertEquals(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST,
                RouteProfileTendency.effectiveMode(connection));
        // Discarded/generated: falls back to the AUTO baseline.
        RouteProfileTendency.clear(connection);
        assertEquals(PlanningConfig.TerrainProfileMode.AUTO,
                RouteProfileTendency.effectiveMode(connection));
    }

    @Test
    void clearAllDropsEveryRouteTendency() {
        PlanningConfig planning = ConfigService.get().planning();
        planning.setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
        StructureConnection other = new StructureConnection(
                new BlockPos(-400, 64, -400), new BlockPos(-800, 64, -800));
        RouteProfileTendency.set(connection, RouteProfileTendency.Tendency.TUNNEL_FIRST);
        RouteProfileTendency.set(other, RouteProfileTendency.Tendency.BRIDGE_FIRST);
        RouteProfileTendency.clearAll();
        assertEquals(PlanningConfig.TerrainProfileMode.AUTO,
                RouteProfileTendency.effectiveMode(connection));
        assertEquals(PlanningConfig.TerrainProfileMode.AUTO,
                RouteProfileTendency.effectiveMode(other));
    }
}
