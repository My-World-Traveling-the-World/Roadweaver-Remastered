/* 文件职责：固定客户端六条失败地形，直接跑新版生产修复与原隧道分类，不依赖可选录制文件。 */
package net.shiroha233.roadweaver.pathlogic.core;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.TunnelFootprintSampler;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ClientProfileRepairTest {
    static Stream<int[]> terrains() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/evidence"))) root = root.getParent();
        assertNotNull(root, "必须找到已提交的客户端证据，不能因工作目录不同而跳过");
        var data = JsonParser.parseString(Files.readString(
                root.resolve("docs/evidence/client-road-profile-failures-20261121.json"))).getAsJsonObject();
        var samples = data.getAsJsonArray("samples");
        assertEquals(6, samples.size());
        var arrays = new ArrayList<int[]>();
        for (var sample : samples) {
            var object = sample.getAsJsonObject();
            var terrain = object.getAsJsonArray("terrain");
            assertEquals(object.get("stations").getAsInt(), terrain.size());
            assertEquals(0, object.get("crossingCount").getAsInt());
            var array = new int[terrain.size()];
            for (int i = 0; i < array.length; i++) array[i] = terrain.get(i).getAsInt();
            arrays.add(array);
        }
        return arrays.stream();
    }

    @ParameterizedTest @MethodSource("terrains")
    void recordedFailuresProduceContinuousProfilesInsideActualTunnelAndCutBudgets(int[] terrain) {
        var centers = new ArrayList<BlockPos>();
        for (int i = 0; i < terrain.length; i++) centers.add(new BlockPos(i, terrain[i], 0));
        var segments = centers.stream().map(p -> new RoadSegmentPlacement(p, List.of(p))).toList();
        var config = new ModConfig();
        var appearance = config.roadAppearance();
        appearance.setSlopeLimitEnabled(true);
        appearance.setMaxSlopeStepPerTwoSegments(1);
        var runtime = net.shiroha233.roadweaver.config.sub.RoadGenerationConfig.from(config);
        var output = assertDoesNotThrow(() -> TargetProfileSolver.solve(null, centers, terrain, terrain, null, null, null,
                terrain[0], terrain[terrain.length - 1], runtime,
                new SurfaceGradeLimits(5, 4, 3, 3, 8, 3), null,
                (i, clearance) -> { throw new AssertionError("六条录制都没有桥，不应读水位"); },
                ProfileDpSource::solve,
                (t, c, cut, fill, step, two, constraints) -> TargetProfileRepair.solve(
                        t, c, cut, fill, step, two, constraints,
                        preview -> RoadSpanProfileOps.excavationMask(centers,
                                PathSpanExtractor.refineTunnelSpans(List.of(), segments, preview, null, null)))));
        var profile = output.orElseThrow();
        assertEquals(terrain.length, profile.length);
        assertEquals(0, ProfileFallback.violations(profile, 1, 1));
        // 用最终剖面重新走原分类；不能只拿预览时自建的 mask 为自己背书。
        var finalSpans = PathSpanExtractor.refineTunnelSpans(List.of(), segments,
                java.util.Arrays.stream(profile).boxed().toList(), null, null);
        var tunnel = RoadSpanProfileOps.excavationMask(centers, finalSpans);
        assertFalse(finalSpans.isEmpty(), "六条现场地形均应实际选出山体隧道，不能空集合空转");
        int maxOpenCut = 0, maxFill = 0;
        for (int i = 0; i < terrain.length; i++) {
            assertTrue(profile[i] <= terrain[i] + 4, "填方不能借修复突破配置，站=" + i);
            if (!tunnel[i]) assertTrue(terrain[i] - profile[i] <= RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH,
                    "非隧道仍超施工挖深，站=" + i);
            if (!tunnel[i]) maxOpenCut = Math.max(maxOpenCut, terrain[i] - profile[i]);
            maxFill = Math.max(maxFill, profile[i] - terrain[i]);
        }
        System.out.printf("CLIENT-PROFILE-REPAIR stations=%d violations=0 maxOpenCut=%d maxFill=%d spans=%d%n",
                terrain.length, maxOpenCut, maxFill, finalSpans.size());
    }

    @Test void noTunnelPermissionCannotTurnAnUnbuildableMountainIntoOrdinaryRoad() {
        int[] terrain = {64, 200, 200, 200, 64};
        assertTrue(TargetProfileRepair.solve(terrain, null, 5, 4, 1, 1,
                ProfileDpSource.Constraints.none(), preview -> new boolean[terrain.length]).isEmpty());
    }

    @Test void aPinnedBridgeConflictingWithTheOtherEndMustRemainInfeasible() {
        int[] terrain = {64, 64, 64};
        var constraints = new ProfileDpSource.Constraints(new int[]{64, -1, 90}, null, 8, null);
        assertTrue(TargetProfileRepair.solve(terrain, null, 5, 4, 1, 1, constraints,
                preview -> { throw new AssertionError("锁高已互斥，不应扩大地形采样"); }).isEmpty());
    }

    @Test void disablingCutCannotBeOverriddenByTheLocalConstructionBudget() {
        int[] terrain = {64, 80, 64};
        assertTrue(TargetProfileRepair.solve(terrain, null, 0, 4, 1, 1,
                ProfileDpSource.Constraints.none(), preview -> new boolean[3]).isEmpty());
        var legal = TargetProfileRepair.solve(terrain, null, 5, 4, 1, 1,
                ProfileDpSource.Constraints.none(), preview -> new boolean[3]).orElseThrow();
        assertEquals(0, ProfileFallback.violations(legal, 1, 1));
        assertTrue(80 - legal[1] > 5 && 80 - legal[1] <= 32, "必须真实触发既有施工端接纳的局部路堑");
    }

    @Test void tunnelMouthsAndShallowStationsNeverReceiveASealedCoreHeightCap() {
        assertArrayEquals(new boolean[]{false, false, true, false, false, false},
                TargetProfileRepair.coveredCore(new int[]{80, 80, 80, 80, 80, 80},
                        new int[]{64, 64, 64, 78, 64, 64},
                        new boolean[]{false, true, true, true, true, false}, 8));
    }

    @Test void missingSurfaceMouthRetainsTheWholeScannedDeepApproach() {
        assertApproach(-1, 0);
    }

    @Test void aBridgeStillCutsOffTheRetainedTunnelApproach() {
        assertApproach(1, 2);
    }

    /** 首端没有地表洞口，过去只保留洞身±1，丢掉0..2的深挖引道；桥站必须仍被排除。 */
    private static void assertApproach(int bridgeStation, int expectedStart) {
        var centers = new ArrayList<BlockPos>();
        for (int i = 0; i < 12; i++) centers.add(new BlockPos(i, i < 9 ? 90 : 64, 0));
        var segments = centers.stream().map(p -> new RoadSegmentPlacement(p, List.of(p))).toList();
        var bridges = new boolean[12];
        if (bridgeStation >= 0) bridges[bridgeStation] = true;
        var spans = new ArrayList<net.shiroha233.roadweaver.core.model.RoadSpan>();
        TunnelFootprintSampler.appendTunnelWithSurfaceMouths(spans, segments, centers,
                java.util.Collections.nCopies(12, 64), bridges, java.util.Map.of(), null, 4, 6,
                new SurfaceGradeLimits(5, 4, 3, 3, 8, 3));
        assertEquals(1, spans.size());
        assertEquals(centers.get(expectedStart), spans.getFirst().start());
        assertEquals(centers.get(9), spans.getFirst().end());
        if (bridgeStation >= 0) assertFalse(RoadSpanProfileOps.excavationMask(centers, spans)[bridgeStation]);
    }
}
