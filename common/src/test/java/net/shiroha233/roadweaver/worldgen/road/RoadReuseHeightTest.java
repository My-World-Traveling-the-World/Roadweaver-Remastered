package net.shiroha233.roadweaver.worldgen.road;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.pathlogic.construction.ConstructionSpanMasks;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 占地只描述XZ，不能据此让上下两层隧道只剩一层施工；断言检查生产编译后的跳过掩码。 */
class RoadReuseHeightTest {
    @Test void separatedTunnelsKeepBothConstructionLayers() {
        for (var kind : List.of(SpanType.TUNNEL, SpanType.TUNNEL_JUNCTION)) {
            for (int delta : List.of(-8, -2, 2, 8)) {
                var lower = tunnel(64, Collections.nCopies(24, 64), kind, 1, false);
                var upper = tunnel(64, Collections.nCopies(24, 64 + delta), kind, 3, false);
                var plan = RoadChunkPlanCompiler.compile(new ChunkPos(0, 0), List.of(lower, upper), true, 5, 1);
                assertEquals(2, plan.slices().size(), "两层均须进入实际区块切片");
                for (var slice : plan.slices()) {
                    assertEquals(0, ConstructionSpanMasks.of(slice.road(), 0).networkReuseCount(),
                            "目标Y相差" + delta + "时不得因XZ相同跳过整层：" + kind);
                }
            }
        }
    }

    @Test void sameLayerStillSharesInBothDirectionsAndAtHeightTolerance() {
        for (int delta : List.of(-1, 0, 1)) for (boolean reverse : List.of(false, true)) {
            var owner = tunnel(64, Collections.nCopies(24, 64), SpanType.TUNNEL, 1, false);
            var candidate = tunnel(120, Collections.nCopies(24, 64 + delta), SpanType.TUNNEL, 3, reverse);
            var reused = new boolean[24];
            RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, candidate, reused);
            var expected = new boolean[24];
            Arrays.fill(expected, true);
            assertArrayEquals(expected, reused, "持久targetY优先于中心Y，同层反向道路也应复用");
        }
    }

    @Test void staleSurrenderToAnotherLayerIsReclaimedWithoutLosingTunnelSpans() {
        var owner = tunnel(64, Collections.nCopies(24, 64), SpanType.TUNNEL, 1, false);
        var original = tunnel(64, Collections.nCopies(24, 80), SpanType.TUNNEL, 3, false);
        var spans = new ArrayList<>(original.spans());
        spans.add(new RoadSpan(original.roadSegmentList().getFirst().middlePos(),
                original.roadSegmentList().getLast().middlePos(), SpanType.NETWORK_REUSE));
        var stale = new RoadData(original.width(), original.roadType(), original.materials(),
                original.slabMaterials(), original.roadSegmentList(), spans, original.targetY(), 3, 4);
        var normalized = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(owner, stale));
        assertEquals(2, normalized.size());
        for (var road : normalized) {
            var masks = ConstructionSpanMasks.of(road, 0);
            assertEquals(0, masks.networkReuseCount(), "旧让出不能由另一高度的主路虚假兑现");
            assertEquals(24, masks.tunnelCount(), "撤销让出仍须按原隧道施工");
        }
    }

    @Test void onlySameHeightRunsAreClaimedWhenGradesSeparateAndRejoin() {
        var owner = tunnel(64, Collections.nCopies(24, 64), SpanType.TUNNEL, 1, false);
        var heights = new ArrayList<>(Collections.nCopies(24, 64));
        for (int i = 8; i < 16; i++) heights.set(i, 72);
        var candidate = tunnel(64, heights, SpanType.TUNNEL, 3, false);
        var reused = new boolean[24];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, candidate, reused);
        var expected = new boolean[24];
        for (int i = 0; i < 24; i++) expected[i] = i < 8 || i >= 16;
        assertArrayEquals(expected, reused, "高差分离段不能把前后同层共用连成一整段");
    }

    @Test void absentPersistedHeightsUseCenterHeightAndSparseBatchesObeySameRule() {
        var lower = tunnel(40, List.of(), SpanType.TUNNEL, 1, false);
        var upper = tunnel(60, List.of(), SpanType.TUNNEL, 3, false);
        var roads = new ArrayList<>(List.of(lower, upper));
        for (int i = 1; i <= 16; i++) {
            var far = tunnel(64, Collections.nCopies(24, 64), SpanType.TUNNEL, 10 + i * 2, false);
            var shifted = far.roadSegmentList().stream().map(s -> {
                var pos = s.middlePos().offset(iOffset(far), 0, 0);
                return new RoadSegmentPlacement(pos, List.of(pos));
            }).toList();
            roads.add(new RoadData(1, 1, List.of(), List.of(), shifted,
                    List.of(new RoadSpan(shifted.getFirst().middlePos(), shifted.getLast().middlePos(),
                            SpanType.TUNNEL)), far.targetY(), far.ownerA2dKey(), far.ownerB2dKey()));
        }
        assertFalse(new RoadReuseCandidates(roads, 64).usesDirectEnumeration(),
                "此例必须实际进入稀疏候选，而非靠小批/密集分支重复同一验证");
        var actual = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(roads);
        assertEquals(18, actual.size());
        assertEquals(0, actual.stream().mapToInt(r -> ConstructionSpanMasks.of(r, 0).networkReuseCount()).sum());
    }

    private static int iOffset(RoadData road) { return (int) road.ownerA2dKey() * 256; }

    private static RoadData tunnel(int centerY, List<Integer> heights, SpanType kind, long owner, boolean reverse) {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for (int i = 0; i < 24; i++) {
            var pos = new BlockPos(i, centerY, 0);
            stations.add(new RoadSegmentPlacement(pos, List.of(pos)));
        }
        if (reverse) Collections.reverse(stations);
        var road = new RoadData(1, 1, List.of(), List.of(), stations,
                List.of(new RoadSpan(stations.getFirst().middlePos(), stations.getLast().middlePos(), kind)),
                heights, owner, owner + 1);
        // 先验证真实终态准入，防止非法夹具被前置条件拒绝而让负例空转。
        if (heights.isEmpty()) {
            // 编译器兼容旧资料缺高度时的中心Y回退；此例明确不冒充最终落盘的合法道路。
            assertFalse(RoadDataValidator.isValidFinalRoad(road));
        } else {
            assertTrue(RoadDataValidator.isValidFinalRoad(road), "隧道夹具必须通过终态校验");
        }
        return road;
    }
}
