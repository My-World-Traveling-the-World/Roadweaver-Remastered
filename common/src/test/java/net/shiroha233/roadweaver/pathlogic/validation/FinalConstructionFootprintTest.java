package net.shiroha233.roadweaver.pathlogic.validation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner;
import net.shiroha233.roadweaver.pathlogic.structures.FootprintStructureFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FinalConstructionFootprintTest {
    private static final BlockPos OUTER_COLUMN = new BlockPos(-2, 64, -4);
    private static final RoadConstructionVolumePlanner.Context FLAT =
            new RoadConstructionVolumePlanner.Context(null, (x, z) -> 64, (x, z) -> false, null);

    @Test
    void structureAtOuterColumnIsRejectedUnlessStationIsAnExplicitGap() {
        var road = footprint(null);
        var snapshot = FootprintStructureFixture.snapshot(OUTER_COLUMN);
        var rejected = FinalRoadStructureValidator.validate(snapshot, road, FLAT);
        assertFalse(rejected.safe());
        assertEquals("protected-structure-intersection", rejected.reason());
        assertEquals(RoadConstructionVolumePlanner.Kind.ROAD, rejected.collision().kind());
        assertEquals(2, rejected.collision().segmentIndex());
        assertEquals(OUTER_COLUMN.getX(), rejected.collision().position().getX());
        assertEquals(OUTER_COLUMN.getZ(), rejected.collision().position().getZ());
        assertTrue(FinalRoadStructureValidator.validate(snapshot, footprint(SpanType.PROTECTED_GAP), FLAT).safe());
        assertTrue(FinalRoadStructureValidator.validate(snapshot, footprint(SpanType.NETWORK_REUSE), FLAT).safe(),
                "共用路段不再由此道路写入");
    }

    @Test
    void observesTreeReservationAtFinalOuterColumnWithoutChangingTheRoadDecision() {
        var snapshot = FootprintStructureFixture.snapshot(null);
        var lookup = (FinalRoadStructureValidator.ReservationLookup)
                (x, z) -> x == OUTER_COLUMN.getX() && z == OUTER_COLUMN.getZ();
        var hit = new FinalRoadStructureValidator.FootprintAudit(lookup);
        var unchanged = FinalRoadStructureValidator.validate(snapshot, footprint(null), FLAT, hit);
        assertTrue(unchanged.safe(), "影子核查不能自行改变规划成功或失败");
        assertNotNull(hit.firstHit());
        assertEquals(2, hit.firstHit().segmentIndex());
        assertEquals(OUTER_COLUMN.getZ(), hit.firstHit().z());
        for (SpanType exempt : List.of(SpanType.PROTECTED_GAP, SpanType.NETWORK_REUSE)) {
            var skipped = new FinalRoadStructureValidator.FootprintAudit(lookup);
            assertTrue(FinalRoadStructureValidator.validate(snapshot, footprint(exempt), FLAT, skipped).safe());
            assertNull(skipped.firstHit());
        }
    }

    private static RoadData footprint(SpanType exempt) {
        var centers = List.of(new BlockPos(-5, 64, -1), new BlockPos(-4, 64, -2),
                new BlockPos(-2, 64, -2), new BlockPos(0, 64, -1), new BlockPos(1, 64, 0));
        var stations = new ArrayList<RoadSegmentPlacement>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            stations.add(new RoadSegmentPlacement(center, i == 2
                    ? List.of(OUTER_COLUMN, center.north(), center, center.south(), center.south(2))
                    : List.of(center.north(2), center.north(), center, center.south(), center.south(2))));
        }
        var spans = exempt == null ? List.<RoadSpan>of()
                : List.of(new RoadSpan(centers.get(2),
                        centers.get(exempt == SpanType.NETWORK_REUSE ? 3 : 2), exempt));
        var road = new RoadData(5, 1, List.of(), List.of(), stations, spans, List.of(64, 64, 64, 64, 64),
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
        // 中心线始终没撞保护列，且站距合法；只验证最终加宽后的施工列。
        var valid = RoadDataValidator.validateFinalRoad(road);
        assertTrue(valid.valid(), () -> "夹具必须是候选前置校验能接受的最终路: " + valid);
        assertFalse(centers.contains(OUTER_COLUMN));
        return road;
    }

    @Test
    void duplicatesOfFootprintColumnsMustNotHideAWholeChunkPrefetch() {
        var columns = List.of(new BlockPos(-5, 68, -2), new BlockPos(-4, 68, -2),
                new BlockPos(-3, 68, -2), new BlockPos(-2, 68, -2), new BlockPos(-1, 68, -2));
        // 施工体积已提供路幅；同列的路面换了 Y，仍只算一次精确地形取样。
        var station = new RoadSegmentPlacement(columns.getFirst().above(), List.of(columns.getLast().below()));
        var unique = FinalRoadStructureValidator.validationColumns(columns, List.of(station));
        assertEquals(columns, unique);
        assertTrue(FinalRoadStructureValidator.useWholeChunk(3, unique.size()));
        assertFalse(FinalRoadStructureValidator.useWholeChunk(2, unique.size()));

        // 补充只出现在站点描述的列，防止去重实现误删整个路幅来源。
        var extra = new BlockPos(0, 68, -2);
        var beside = new RoadSegmentPlacement(extra, List.of(extra.above(), columns.getLast()));
        var expanded = FinalRoadStructureValidator.validationColumns(columns, List.of(station, beside));
        assertEquals(6, expanded.size());
        assertEquals(extra, expanded.getLast());
    }
}
