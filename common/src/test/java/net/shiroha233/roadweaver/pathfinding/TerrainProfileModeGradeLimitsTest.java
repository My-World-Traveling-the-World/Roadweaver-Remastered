package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the grading semantics of every TerrainProfileMode. Defaults asserted
 * here match TerrainProfileConfig (plain cut/fill 5/2, mountain 3/1) and
 * PlanningConfig (overburden 8, tunnelMinRun 3) so any drift in the
 * mode-to-decision wiring fails loudly.
 *
 * <p>Expected matrix (audit item: AUTO/BALANCED were merged into AUTO):
 * <ul>
 *   <li>AUTO = balanced baseline (5/2 plain, 3/1 mountain)</li>
 *   <li>TUNNEL_FIRST: cut min(3, x), fill unchanged</li>
 *   <li>BRIDGE_FIRST: fill min(1, x), cut unchanged, bridge depth ignored</li>
 *   <li>LIGHT_CUT: cut min(3, x) AND fill min(1, x), no search change</li>
 * </ul>
 */
final class TerrainProfileModeGradeLimitsTest {

    private static PlanningConfig planning() {
        return ConfigService.get().planning();
    }

    @AfterEach
    void restoreDefaultMode() {
        planning().setTerrainProfileMode(PlanningConfig.TerrainProfileMode.AUTO);
    }

    private static SurfaceGradeLimits capture(PlanningConfig.TerrainProfileMode mode) {
        planning().setTerrainProfileMode(mode);
        return SurfaceGradeLimits.capture();
    }

    @Test
    void autoIsTheBalancedBaseline() {
        SurfaceGradeLimits limits = capture(PlanningConfig.TerrainProfileMode.AUTO);
        assertEquals(5, limits.cut(false));
        assertEquals(2, limits.fill(false));
        assertEquals(3, limits.cut(true));
        assertEquals(1, limits.fill(true));
        assertEquals(8, limits.tunnelCoreOverburden());
        assertEquals(3, limits.tunnelMinRun());
    }

    @Test
    void tunnelFirstTightensCutOnly() {
        SurfaceGradeLimits limits = capture(PlanningConfig.TerrainProfileMode.TUNNEL_FIRST);
        assertEquals(3, limits.cut(false));
        assertEquals(3, limits.cut(true));
        assertEquals(2, limits.fill(false));
        assertEquals(1, limits.fill(true));
    }

    @Test
    void bridgeFirstTightensFillOnly() {
        SurfaceGradeLimits limits = capture(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST);
        assertEquals(5, limits.cut(false));
        assertEquals(3, limits.cut(true));
        assertEquals(1, limits.fill(false));
        assertEquals(1, limits.fill(true));
    }

    @Test
    void lightCutTightensBothCutAndFill() {
        SurfaceGradeLimits limits = capture(PlanningConfig.TerrainProfileMode.LIGHT_CUT);
        assertEquals(3, limits.cut(false));
        assertEquals(3, limits.cut(true));
        assertEquals(1, limits.fill(false));
        assertEquals(1, limits.fill(true));
    }

    @Test
    void bridgeFirstIgnoresConfiguredBridgeDepth() {
        assertEquals(1, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.BRIDGE_FIRST, 3));
    }

    @Test
    void otherModesKeepConfiguredBridgeDepth() {
        assertEquals(3, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.AUTO, 3));
        assertEquals(3, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.TUNNEL_FIRST, 3));
        assertEquals(3, PathSpanExtractor.effectiveBridgeMinDepth(
                PlanningConfig.TerrainProfileMode.LIGHT_CUT, 3));
    }

    @Test
    void tunnelClassificationInputsAreModeIndependent() {
        for (PlanningConfig.TerrainProfileMode mode : PlanningConfig.TerrainProfileMode.values()) {
            SurfaceGradeLimits limits = capture(mode);
            assertEquals(8, limits.tunnelCoreOverburden(), mode.name());
            assertEquals(3, limits.tunnelMinRun(), mode.name());
        }
    }

    /**
     * 0.9.019（O-07）：减配必须**相对**，不能把玩家配的填方压成常量 1。
     *
     * <p>回归前：{@code fill(false) = min(1, 4) = 1}（玩家把 plainSurfaceFillMaxBlocks 从 2
     * 调到 4 完全无效）。回归后：{@code max(1, 4-1) = 3}。</p>
     */
    @Test
    void reducedFillFollowsTheConfiguredValueInsteadOfBeingPinnedToOne() {
        var terrain = ConfigService.get().terrainProfile();
        int previousPlain = terrain.plainSurfaceFillMaxBlocks();
        int previousMountain = terrain.mountainSurfaceFillMaxBlocks();
        try {
            terrain.setPlainSurfaceFillMaxBlocks(4);
            terrain.setMountainSurfaceFillMaxBlocks(3);
            SurfaceGradeLimits limits =
                    capture(PlanningConfig.TerrainProfileMode.BRIDGE_FIRST);
            assertEquals(3, limits.fill(false),
                    "配 4 格填方在 BRIDGE_FIRST 下必须拿到 3，不能再被压成 1");
            assertEquals(2, limits.fill(true), "配 3 格山地填方在 BRIDGE_FIRST 下必须拿到 2");
        } finally {
            terrain.setPlainSurfaceFillMaxBlocks(previousPlain);
            terrain.setMountainSurfaceFillMaxBlocks(previousMountain);
        }
    }
}
