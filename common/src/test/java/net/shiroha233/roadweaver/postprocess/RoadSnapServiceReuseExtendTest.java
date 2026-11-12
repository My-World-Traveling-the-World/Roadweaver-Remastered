package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.324：交汇锚点之后 secondary 仍贴在同一条走廊上的复用扩展。
 *
 * <p>复刻真机 20260909 16.15 存档村口那对路：A 的前 8 站在 primary 上（已标 NETWORK_REUSE），
 * 交汇锚点之后又有 9 站只差 2 格、targetY 完全相同 —— 旧逻辑只标到锚点为止，这 9 站会被
 * 再铺一遍，地图上就是"来回交叉的麻花"。
 */
final class RoadSnapServiceReuseExtendTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadData road(List<int[]> stations, List<RoadSpan> spans) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int[] station : stations) {
            BlockPos center = new BlockPos(station[0], station[1], station[2]);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(station[1]);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments,
                spans == null ? List.of() : List.copyOf(spans), heights,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    /** primary：x=100、z=0..-40、y=64 的一条直路。 */
    private static RoadData primary() {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            stations.add(new int[]{100, 64, -i});
        }
        return road(stations, null);
    }

    /**
     * secondary：idx 0..7 落在 primary 上（复用段），idx 8 是交汇锚点另一端，
     * idx 9..17 在 primary 东侧 2 格、同高（应当被扩展成复用），idx 18..27 远去。
     */
    private static RoadData secondary(int corridorY, int corridorStations) {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            stations.add(new int[]{100, 64, -20 + i});
        }
        stations.add(new int[]{101, 64, -12});
        for (int i = 0; i < corridorStations; i++) {
            stations.add(new int[]{102, corridorY, -11 + i});
        }
        for (int i = 0; i < 6; i++) {
            stations.add(new int[]{104 + i * 2, corridorY, -2 + i});
        }
        return road(stations, null);
    }

    private static RoadData withJunctionSpans(RoadData secondary) {
        List<RoadSegmentPlacement> segments = secondary.roadSegmentList();
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(segments.get(0).middlePos(), segments.get(7).middlePos(),
                SpanType.NETWORK_REUSE));
        spans.add(new RoadSpan(segments.get(7).middlePos(), segments.get(8).middlePos(),
                SpanType.JUNCTION_ANCHOR));
        List<int[]> stations = new ArrayList<>();
        List<Integer> heights = new ArrayList<>(secondary.targetY());
        for (RoadSegmentPlacement segment : segments) {
            BlockPos p = segment.middlePos();
            stations.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        List<RoadSegmentPlacement> rebuilt = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            int[] station = stations.get(i);
            BlockPos center = new BlockPos(station[0], heights.get(i), station[2]);
            rebuilt.add(new RoadSegmentPlacement(center, List.of(center)));
        }
        return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                secondary.slabMaterials(), rebuilt, List.copyOf(spans), heights,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());
    }

    private static RoadSpan reuseSpan(RoadData road, int index) {
        BlockPos pos = road.roadSegmentList().get(index).middlePos();
        for (RoadSpan span : road.spans()) {
            if (span.type() == SpanType.NETWORK_REUSE && span.start().equals(pos)) return span;
        }
        return null;
    }

    @Test
    void corridorRunPastTheJunctionIsMarkedReuseWithoutMovingStations() {
        RoadData candidate = withJunctionSpans(secondary(64, 9));
        RoadData extended = RoadSnapService.extendSecondaryReuseAtJunction(primary(), candidate);

        assertEquals(candidate.roadSegmentList().size(), extended.roadSegmentList().size(),
                "复用扩展只能加 span，不能动站点");
        assertEquals(candidate.targetY(), extended.targetY(), "targetY 不变");

        RoadSpan added = reuseSpan(extended, 9);
        assertTrue(added != null, "锚点之后贴走廊的那段必须被标成 NETWORK_REUSE");
        assertEquals(candidate.roadSegmentList().get(9).middlePos(), added.start());
        assertEquals(candidate.roadSegmentList().get(17).middlePos(), added.end());

        assertTrue(RoadSnapService.networkReuseStationCount(extended)
                        > RoadSnapService.networkReuseStationCount(candidate),
                "复用站点数必须增加");
        assertFalse(RoadSnapService.spanMaskView(extended, SpanType.NETWORK_REUSE)[18],
                "远离 primary 的站点不得被标成复用");
    }

    @Test
    void corridorRunWithDifferentHeightIsNotReused() {
        RoadData candidate = withJunctionSpans(secondary(70, 9));
        assertSame(candidate, RoadSnapService.extendSecondaryReuseAtJunction(primary(), candidate),
                "高差 6 格属于立交/上下层，不能当成同一条走廊复用");
    }

    @Test
    void shortCorridorRunIsNotReused() {
        RoadData candidate = withJunctionSpans(secondary(64, 2));
        assertSame(candidate, RoadSnapService.extendSecondaryReuseAtJunction(primary(), candidate),
                "只贴 2 站算擦边，不足以判定重复走廊");
    }

    @Test
    void roadWithoutExistingReuseSpanIsUntouched() {
        RoadData candidate = secondary(64, 9);
        assertSame(candidate, RoadSnapService.extendSecondaryReuseAtJunction(primary(), candidate),
                "没有已存在的复用段时不该凭空标注");
    }

    /**
     * 0.8.328：合并结果被接口修复丢弃后，退回的"合并前版本"完全没有复用段，
     * 这时也要把最长的一段共用走廊标成 NETWORK_REUSE，否则两条路各铺一遍。
     */
    @Test
    void sharedCorridorWithoutAnyReuseSpanGetsOneCreated() {
        RoadData candidate = secondary(64, 9);
        RoadData created = RoadSnapService.createSecondaryReuseAtCorridor(primary(), candidate);

        assertEquals(candidate.roadSegmentList().size(), created.roadSegmentList().size(),
                "新建复用段只能加 span，不能动站点");
        assertEquals(candidate.targetY(), created.targetY(), "targetY 不变");
        RoadSpan span = reuseSpan(created, 0);
        assertTrue(span != null, "整段贴走廊的路线必须被标成 NETWORK_REUSE");
        assertEquals(candidate.roadSegmentList().get(0).middlePos(), span.start());
        assertTrue(RoadSnapService.networkReuseStationCount(created) >= 9,
                "复用站点数必须覆盖走廊段");
    }

    /** 高度对不上（上下层）时不得新建复用段。 */
    @Test
    void sharedCorridorWithDifferentHeightIsNotReusedFromScratch() {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            stations.add(new int[]{100, 70, -20 + i});
        }
        stations.add(new int[]{101, 70, -12});
        for (int i = 0; i < 9; i++) {
            stations.add(new int[]{102, 70, -11 + i});
        }
        for (int i = 0; i < 6; i++) {
            stations.add(new int[]{104 + i * 2, 70, -2 + i});
        }
        RoadData elevated = road(stations, null);
        assertSame(elevated, RoadSnapService.createSecondaryReuseAtCorridor(primary(), elevated),
                "整条路比 primary 高 6 格，属于上下层，不能当成同一条走廊复用");
    }
}
