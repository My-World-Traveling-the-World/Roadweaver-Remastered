/* 文件职责：确认观测标识可关联且不碰撞，施工放弃不能被统计成铺路成功。 */
package net.shiroha233.roadweaver.pathlogic.metrics;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.planning.RouteEvidenceId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadBuildEvidenceTest {
    @Test
    void fullEndpointsSurviveReversalNegativeCoordinatesAndWorldChanges() {
        long a = RouteEvidenceId.endpoint(-17, 32), b = RouteEvidenceId.endpoint(101, -1);
        assertEquals("minecraft:overworld@42/-17,32~101,-1", RouteEvidenceId.of("minecraft:overworld", 42, a, b));
        assertEquals(RouteEvidenceId.of("minecraft:overworld", 42, a, b), RouteEvidenceId.of("minecraft:overworld", 42, b, a));
        assertNotEquals(RouteEvidenceId.of("a", 42, a, b), RouteEvidenceId.of("b", 42, a, b));
        assertNotEquals(RouteEvidenceId.of("a", 42, a, b), RouteEvidenceId.of("a", 43, a, b));
        assertNotEquals(RouteEvidenceId.of("a", 42, a, RouteEvidenceId.endpoint(100, 32)),
                RouteEvidenceId.of("a", 42, a, RouteEvidenceId.endpoint(101, 1)));
    }

    @Test
    void productCountsActualEntriesAndSpanKindsWithoutClaimingUniqueColumns() {
        var road = road();
        assertEquals(new RoadBuildEvidence.Product(2, 3, 1, 1, 1, 1), RoadBuildEvidence.product(road));
        var line = RoadBuildEvidence.validation("minecraft:overworld", 42, road, "final-structure", false, "protected-piece");
        assertTrue(line.contains("id=minecraft:overworld@42/-17,32~101,-1"));
        assertTrue(line.contains("safe=false reason=protected-piece"));
        assertTrue(line.contains("construction=not-confirmed"));
    }

    @Test
    void durableAbandonmentAndUntouchedSliceAreNotCompletion() {
        assertEquals(RoadBuildEvidence.ChunkOutcome.COMPLETED, RoadBuildEvidence.outcome(true, false, false));
        assertEquals(RoadBuildEvidence.ChunkOutcome.ABANDONED, RoadBuildEvidence.outcome(false, true, true));
        assertEquals(RoadBuildEvidence.ChunkOutcome.INCOMPLETE, RoadBuildEvidence.outcome(false, true, false));
        assertEquals(RoadBuildEvidence.ChunkOutcome.NOT_ATTEMPTED, RoadBuildEvidence.outcome(false, false, true));
        assertEquals(RoadBuildEvidence.ChunkOutcome.CONFLICT, RoadBuildEvidence.outcome(true, true, false));
    }

    @Test
    void chunkEvidenceLinksToValidationAndRetainsLocalOutcome() {
        var road = road();
        var line = RoadBuildEvidence.chunk("minecraft:overworld", 42, road, 123, -2, 3, 1,
                RoadBuildEvidence.ChunkOutcome.ABANDONED, true);
        assertTrue(line.contains("id=minecraft:overworld@42/-17,32~101,-1 stage=chunk"));
        assertTrue(line.contains("fingerprint=123 chunk=-2,3 localStations=1 outcome=ABANDONED gapRepair=true"));
    }

    @Test
    void contentVersionIncludesEveryConstructionInputAndStationOrder() {
        var r = road();
        var stations = r.roadSegmentList();
        var reversed = new ArrayList<>(stations); Collections.reverse(reversed);
        var changedColumns = List.of(new RoadSegmentPlacement(stations.getFirst().middlePos(),
                List.of(new BlockPos(0, 64, 1))), stations.getLast());
        var variants = List.of(
            new RoadData(6, 0, r.materials(), r.slabMaterials(), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 1, r.materials(), r.slabMaterials(), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, List.of(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()), r.slabMaterials(), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), List.of(net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState()), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), reversed, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), changedColumns, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), stations, List.of(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), stations, r.spans(), List.of(64, 65), r.ownerA2dKey(), r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), stations, r.spans(), r.targetY(), r.ownerA2dKey() + 1, r.ownerB2dKey()),
            new RoadData(5, 0, r.materials(), r.slabMaterials(), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey() + 1));
        assertEquals(10, variants.size());
        String original = RoadBuildEvidence.content(r);
        assertTrue(original.matches("c1-[0-9a-f]{64}"));
        Set<String> versions = new HashSet<>(); versions.add(original);
        for (int i = 0; i < variants.size(); i++)
            assertTrue(versions.add(RoadBuildEvidence.content(variants.get(i))), "施工字段变化必须区分，第" + i + "组");
        // 身份指纹刻意保持旧语义；内容版本必须独立抓到剖面变化，不能拿前者冒充后者。
        assertEquals(net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint.compute(r),
                net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint.compute(variants.get(7)));
    }

    @Test
    void materialPropertyNamesAndCodecRoundTripHaveStableVersions() {
        var r = road();
        var log = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        var otherAxis = log.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                net.minecraft.core.Direction.Axis.X);
        var first = new RoadData(5, 0, List.of(log, otherAxis), List.of(), r.roadSegmentList(), r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey());
        var second = new RoadData(5, 0, List.of(otherAxis, log), List.of(), r.roadSegmentList(), r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey());
        assertNotEquals(RoadBuildEvidence.content(first), RoadBuildEvidence.content(second));
        var encoded = RoadData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, first).getOrThrow();
        var restored = RoadData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(first, restored);
        assertEquals(RoadBuildEvidence.content(first), RoadBuildEvidence.content(restored));
    }

    @Test
    void nestedColumnMutationCannotReuseAnOldDiagnosticVersion() {
        var r = road();
        var columns = new ArrayList<>(r.roadSegmentList().getFirst().positions());
        var data = new RoadData(5, 0, List.of(), List.of(),
                List.of(new RoadSegmentPlacement(new BlockPos(0, 64, 0), columns)), List.of(), List.of(64), 1, 2);
        String first = RoadBuildEvidence.content(data);
        columns.add(new BlockPos(0, 64, 2));
        assertNotEquals(first, RoadBuildEvidence.content(data), "不能按RoadData对象身份缓存可变嵌套列");
    }

    @Test
    void validationAndChunkCarryTheSameExactContentVersion() {
        var data = road();
        String content = RoadBuildEvidence.content(data);
        assertTrue(RoadBuildEvidence.validation("minecraft:overworld", 42, data, "validated", true, "ok")
                .endsWith(" content=" + content));
        assertTrue(RoadBuildEvidence.chunk("minecraft:overworld", 42, data, 123, -2, 3, 1,
                RoadBuildEvidence.ChunkOutcome.COMPLETED, false).endsWith(" content=" + content));
    }

    @Test
    void spanContentDistinguishesStartEndTypeAndOrderWithoutChangingCount() {
        var r = road(); var first = r.spans().getFirst();
        var changed = List.of(new RoadSpan(first.start().offset(1, 0, 0), first.end(), first.type()),
            new RoadSpan(first.start(), first.end().offset(0, 0, 1), first.type()),
            new RoadSpan(first.start(), first.end(), SpanType.ROAD_CROSSING));
        assertEquals(3, changed.size());
        var versions = new HashSet<String>(); versions.add(RoadBuildEvidence.content(r));
        for (var span : changed) {
            var spans = new ArrayList<>(r.spans()); spans.set(0, span);
            var data = new RoadData(5, 0, List.of(), List.of(), r.roadSegmentList(), spans, r.targetY(), r.ownerA2dKey(), r.ownerB2dKey());
            assertTrue(versions.add(RoadBuildEvidence.content(data)), "等长跨度必须区分起止和类型");
        }
        var reversed = new ArrayList<>(r.spans()); Collections.reverse(reversed);
        assertTrue(versions.add(RoadBuildEvidence.content(new RoadData(5, 0, List.of(), List.of(),
                r.roadSegmentList(), reversed, r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()))));
    }

    @Test
    void stationAndColumnCoordinatesAndPartitionBoundariesAllAffectContent() {
        var r = road(); var a = r.roadSegmentList().getFirst(); var b = r.roadSegmentList().getLast();
        var variants = new ArrayList<List<RoadSegmentPlacement>>();
        for (var delta : List.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 0, 1))) {
            variants.add(List.of(new RoadSegmentPlacement(a.middlePos().offset(delta), a.positions()), b));
            var columns = new ArrayList<>(a.positions()); columns.set(0, columns.getFirst().offset(delta));
            variants.add(List.of(new RoadSegmentPlacement(a.middlePos(), columns), b));
        }
        // 扁平列序列相同但分站不同，仍会影响目标高度与施工阶段，不能省略列表长度。
        variants.add(List.of(new RoadSegmentPlacement(a.middlePos(), List.of(a.positions().getFirst())),
            new RoadSegmentPlacement(b.middlePos(), List.of(a.positions().getLast(), b.positions().getFirst()))));
        assertEquals(7, variants.size());
        var versions = new HashSet<String>(); versions.add(RoadBuildEvidence.content(r));
        for (var stations : variants) assertTrue(versions.add(RoadBuildEvidence.content(new RoadData(5, 0,
            List.of(), List.of(), stations, r.spans(), r.targetY(), r.ownerA2dKey(), r.ownerB2dKey()))));
    }

    private static RoadData road() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(1, 64, 0);
        return new RoadData(5, 0, List.of(), List.of(), List.of(
                new RoadSegmentPlacement(a, List.of(a, b)), new RoadSegmentPlacement(b, List.of(b))),
                List.of(new RoadSpan(a, b, SpanType.BRIDGE), new RoadSpan(a, b, SpanType.TUNNEL),
                        new RoadSpan(a, b, SpanType.NETWORK_REUSE), new RoadSpan(a, b, SpanType.PROTECTED_GAP)),
                List.of(64, 64), RouteEvidenceId.endpoint(-17, 32), RouteEvidenceId.endpoint(101, -1));
    }
}
