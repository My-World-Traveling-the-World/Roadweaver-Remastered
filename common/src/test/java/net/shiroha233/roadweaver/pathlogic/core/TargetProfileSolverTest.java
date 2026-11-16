package net.shiroha233.roadweaver.pathlogic.core;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileSolverMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 直接测生产编排的输入与产物，尤其异常分支不能丢掉已采集的硬约束。 */
class TargetProfileSolverTest {
    private String oldMode;
    private static final SurfaceGradeLimits LIMITS = new SurfaceGradeLimits(3, 5, 8, 9, 4, 3);
    private static final int[] TERRAIN = {70, 70, 70, 74, 74, 74, 70, 70, 70};
    private static final boolean[] CROSSING = {false, true, true, false, false, false, false, false, false};
    private static final boolean[] TUNNEL = {false, false, false, false, true, false, false, false, false};
    private static final boolean[] PINNED = {true, false, false, false, false, false, false, false, true};

    @BeforeEach
    void chooseDp() {
        oldMode = System.getProperty(ProfileSolverMode.PROPERTY);
        System.setProperty(ProfileSolverMode.PROPERTY, "dp");
    }

    @AfterEach
    void restoreMode() {
        if (oldMode == null) System.clearProperty(ProfileSolverMode.PROPERTY);
        else System.setProperty(ProfileSolverMode.PROPERTY, oldMode);
    }

    private static RoadGenerationConfig config(boolean slope) {
        var config = new ModConfig();
        config.roadAppearance().setMaxSlopeStepPerTwoSegments(4);
        config.roadAppearance().setSlopeLimitEnabled(slope);
        return RoadGenerationConfig.from(config);
    }

    private static List<BlockPos> centers() {
        var centers = new ArrayList<BlockPos>();
        for (int i = 0; i < TERRAIN.length; i++) centers.add(new BlockPos(i * 2, 0, 0));
        return centers;
    }

    private static Optional<int[]> solve(TargetProfileSolver.DeckFloorSampler sampler,
                                         TargetProfileSolver.Solver solver) {
        // 旧平滑值故意与地形分离，防止编排误把旧产物作为 DP 的地形证据。
        var old = new int[TERRAIN.length];
        Arrays.fill(old, 99);
        return TargetProfileSolver.solve(null, centers(), old, TERRAIN.clone(), CROSSING.clone(),
                TUNNEL.clone(), PINNED.clone(), 71, 71, config(true), LIMITS, null, sampler, solver);
    }

    private record Call(int[] terrain, boolean[] crossing, int cut, int fill, int step, int perTwo,
                        ProfileDpSource.Constraints constraints) {}

    @Test
    void passesSeparateBridgeTunnelPinsAndProductionLimitsThenReturnsSolvedProfile() {
        var calls = new AtomicReference<Call>();
        var samples = new ArrayList<Integer>();
        int[] answer = {71, 72, 72, 71, 70, 70, 70, 70, 71};
        var result = solve((station, clearance) -> {
            samples.add(station);
            return 72;
        }, (terrain, crossing, cut, fill, step, two, constraints) -> {
            calls.set(new Call(terrain, crossing, cut, fill, step, two, constraints));
            return Optional.of(answer);
        });
        // 断言放在端口回调外，避免 AssertionError 被生产的异常回落捕获而掩盖。
        assertSame(answer, result.orElseThrow());
        assertEquals(List.of(1, 2), samples);
        var call = calls.get();
        assertNotNull(call);
        assertArrayEquals(TERRAIN, call.terrain());
        assertArrayEquals(CROSSING, call.crossing());
        assertEquals(3, call.cut());
        assertEquals(5, call.fill());
        assertEquals(2, call.step());
        assertEquals(4, call.perTwo());
        assertArrayEquals(new int[]{71, -1, -1, -1, -1, -1, -1, -1, 71}, call.constraints().pin());
        assertArrayEquals(TUNNEL, call.constraints().mustBeCovered());
        assertEquals(4, call.constraints().minCover());
        int none = Integer.MIN_VALUE;
        assertArrayEquals(new int[]{none, 72, 72, none, none, none, none, none, none},
                call.constraints().deckFloor());
    }

    private static void assertFallbackContracts(int[] result) {
        assertEquals(TERRAIN.length, result.length);
        assertEquals(71, result[0], "起点钉高不能在回落时丢失");
        assertEquals(71, result[8], "终点钉高不能在回落时丢失");
        assertTrue(result[1] >= 72 && result[2] >= 72, "桥面下界必须保留");
        assertTrue(result[4] <= 70, "隧道覆土是上界");
        for (int i = 1; i < result.length; i++) assertTrue(Math.abs(result[i] - result[i - 1]) <= 2);
    }

    @Test
    void infeasibleDpProducesNewConstraintFallback() {
        var samples = new ArrayList<Integer>();
        var result = solve((station, clearance) -> { samples.add(station); return 72; },
                (terrain, crossing, cut, fill, step, two, constraints) -> Optional.empty());
        assertEquals(List.of(1, 2), samples);
        assertFallbackContracts(result.orElseThrow());
    }

    @Test
    void throwingDpRetainsExactlyTheSameFallbackConstraints() {
        var result = solve((station, clearance) -> 72,
                (terrain, crossing, cut, fill, step, two, constraints) -> {
                    throw new IllegalStateException("故障注入：求解异常");
                });
        assertFallbackContracts(result.orElseThrow());
    }

    @Test
    void samplingFailureClearsPartialDecksButPreservesOtherConstraints() {
        var calls = new AtomicReference<ProfileDpSource.Constraints>();
        var stations = new ArrayList<Integer>();
        var result = solve((station, clearance) -> {
            stations.add(station);
            if (station == 2) throw new IllegalStateException("故障注入：第二个水位采样失败");
            return 72;
        }, (terrain, crossing, cut, fill, step, two, constraints) -> {
            calls.set(constraints);
            return Optional.empty();
        }).orElseThrow();
        assertEquals(List.of(1, 2), stations);
        assertNotNull(calls.get());
        int[] none = new int[TERRAIN.length];
        Arrays.fill(none, Integer.MIN_VALUE);
        assertArrayEquals(none, calls.get().deckFloor(), "保持既有全批水位作废语义，不能残留前半段");
        assertEquals(71, result[0]);
        assertEquals(71, result[8]);
        assertTrue(result[4] <= 70);
    }

    @Test
    void explicitLegacyAndDisabledSlopeNeverSampleOrInvokeDp() {
        var calls = new int[1];
        TargetProfileSolver.DeckFloorSampler sampler = (station, clearance) -> { calls[0]++; return 72; };
        TargetProfileSolver.Solver solver = (terrain, crossing, cut, fill, step, two, constraints) -> {
            calls[0]++;
            return Optional.empty();
        };
        System.setProperty(ProfileSolverMode.PROPERTY, "legacy");
        assertTrue(solve(sampler, solver).isEmpty());
        System.setProperty(ProfileSolverMode.PROPERTY, "dp");
        assertTrue(TargetProfileSolver.solve(null, centers(), TERRAIN, TERRAIN, CROSSING,
                TUNNEL, PINNED, 71, 71, config(false), LIMITS, null, sampler, solver).isEmpty());
        assertEquals(0, calls[0]);
    }

    @Test
    void productionAdapterUsesActualDpRatherThanUnconditionallyFallingBack() {
        int[] ignoredOld = new int[TERRAIN.length];
        Arrays.fill(ignoredOld, 99);
        var random = new Random(2402);
        int discriminatingCases = 0;
        for (int attempt = 0; attempt < 64; attempt++) {
            int[] terrain = new int[TERRAIN.length];
            for (int i = 0; i < terrain.length; i++) terrain[i] = 70 + random.nextInt(7);
            terrain[0] = terrain[8] = 70;
            int[] pin = {70, -1, -1, -1, -1, -1, -1, -1, 70};
            var expected = ProfileDpSource.solve(terrain, new boolean[terrain.length], 3, 5, 2, 4,
                    new ProfileDpSource.Constraints(pin, null, 4, null));
            if (expected.isEmpty()) continue;
            var fallback = ProfileFallback.terrainFollowing(terrain, 3, 5, 2, 4, pin, null, 4, null);
            if (Arrays.equals(expected.get(), fallback)) continue;
            // 平地上 DP 与回落完全相同，无法分辨错误接线；只选两者产物不同的有效夹具。
            discriminatingCases++;
            var result = TargetProfileSolver.applyProfileSolver(null, centers(), ignoredOld, terrain,
                    new boolean[terrain.length], null, PINNED, 70, 70, config(true), LIMITS, null);
            assertArrayEquals(expected.get(), result.orElseThrow());
        }
        assertTrue(discriminatingCases > 0, "必须实际遇到能分辨 DP 与回落的夹具，禁止空转");
    }
}
