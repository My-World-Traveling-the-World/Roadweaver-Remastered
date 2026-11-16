package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 直接核对过滤产物及整批撤销原因，防止统计不变却误删真实对外道路。 */
class VillageEndpointFilterTest {
    private static final BlockPos VILLAGE = new BlockPos(0, 64, 0);
    private static final BlockPos UNKNOWN = new BlockPos(80, 200, 0);
    private static final BlockPos OUTSIDE = new BlockPos(-160, 64, 0);
    private static final String ID = "test:village_a";
    private static final StructureOwnerKey OWNER = new StructureOwnerKey(ChunkPos.asLong(0, 0), ID);

    private static PlanningSnapshot snapshot(int minX) {
        // 未知点离村庄原点超过旧 24 格；Y 也不同，过滤必须按包围盒 X/Z 判断。
        var box = new OwnedStructureBox(OWNER, new BoundingBox(minX, 50, -4, 100, 90, 4));
        return PlanningSnapshot.forRoute(new StructureConnection(VILLAGE, UNKNOWN),
                List.of(box), Set.of(), Set.of(), Set.of(OWNER));
    }

    private static List<StructureInfo> known() {
        return List.of(new StructureInfo(VILLAGE, ID), new StructureInfo(OUTSIDE, "test:village_b"));
    }

    @Test
    void filtersInternalEdgeInBothDirectionsButPreservesAnotherVillagesRoute() {
        var internal = new StructureConnection(VILLAGE, UNKNOWN);
        var reverse = new StructureConnection(UNKNOWN, VILLAGE);
        var incoming = new StructureConnection(OUTSIDE, UNKNOWN);
        var result = VillageEndpointFilter.filter(List.of(internal, incoming, reverse), known(), c -> snapshot(-4));
        assertEquals(List.of(internal, reverse), result.filtered());
        assertEquals(List.of(incoming), result.kept());
        assertFalse(result.heldBack(), "村内连接不占用对外连通性安全阀");
    }

    @Test
    void unknownAndEmptySnapshotsKeepConnectionsInOriginalOrder() {
        var first = new StructureConnection(VILLAGE, UNKNOWN);
        var second = new StructureConnection(UNKNOWN, OUTSIDE);
        var result = VillageEndpointFilter.filter(List.of(first, second), known(),
                c -> c == first ? null : PlanningSnapshot.EMPTY);
        assertEquals(List.of(first, second), result.kept());
        assertEquals(List.of(), result.filtered());
        assertFalse(result.heldBack());
    }

    @Test
    void fifthCandidateRevertsWholeRoundAndRetainsReason() {
        var edges = new ArrayList<StructureConnection>();
        for (int i = 0; i < 5; i++) edges.add(new StructureConnection(VILLAGE, UNKNOWN.offset(i, 0, 0)));
        var result = VillageEndpointFilter.filter(edges, known(), c -> snapshot(-4));
        assertEquals(edges, result.kept());
        assertEquals(List.of(), result.filtered());
        assertTrue(result.heldBack());
        assertEquals("too_many_per_round", result.heldBackReason());
    }

    @Test
    void fourInternalCandidatesAreAllowedAtThreshold() {
        var edges = new ArrayList<StructureConnection>();
        for (int i = 0; i < 4; i++) edges.add(new StructureConnection(VILLAGE, UNKNOWN.offset(i, 0, 0)));
        var result = VillageEndpointFilter.filter(edges, known(), c -> snapshot(-4));
        assertEquals(edges, result.filtered());
        assertEquals(List.of(), result.kept());
        assertFalse(result.heldBack());
    }

    @Test
    void removingLastExternalConnectionRevertsWholeRound() {
        var edge = new StructureConnection(VILLAGE, UNKNOWN);
        // 村庄原点在聚合盒外，因此本连接不能按“村内道路”绕过孤立端点检查。
        var result = VillageEndpointFilter.filter(List.of(edge), known(), c -> snapshot(40));
        assertEquals(List.of(edge), result.kept());
        assertEquals(List.of(), result.filtered());
        assertTrue(result.heldBack());
        assertEquals("endpoint_left_without_connection", result.heldBackReason());
    }

    @Test
    void externalCandidateCanBeRemovedWhenBothEndpointsKeepOtherConnections() {
        var candidate = new StructureConnection(VILLAGE, UNKNOWN);
        var villageExit = new StructureConnection(VILLAGE, OUTSIDE);
        var unknownExit = new StructureConnection(UNKNOWN, OUTSIDE);
        var result = VillageEndpointFilter.filter(List.of(villageExit, candidate, unknownExit), known(),
                c -> c == candidate ? snapshot(40) : PlanningSnapshot.EMPTY);
        assertEquals(List.of(candidate), result.filtered());
        assertEquals(List.of(villageExit, unknownExit), result.kept());
        assertFalse(result.heldBack());
    }

    @Test
    void endpointOwnershipRequiresExactPositionChunkAndFirstVerifiedId() {
        var duplicate = List.of(new StructureInfo(VILLAGE, "unknown"),
                new StructureInfo(VILLAGE, ID), new StructureInfo(VILLAGE, "test:village_b"));
        assertFalse(VillageEndpointFilter.isUnknownEndpoint(VILLAGE.above(70), duplicate));
        assertTrue(VillageEndpointFilter.endpointMatchesStructure(VILLAGE, OWNER, duplicate));
        assertFalse(VillageEndpointFilter.endpointMatchesStructure(VILLAGE,
                new StructureOwnerKey(OWNER.startChunkKey(), "test:village_b"), duplicate),
                "同 X/Z 多条结构记录仍由第一条有效 ID 决定，不能悄悄改成任意匹配");
        assertFalse(VillageEndpointFilter.endpointMatchesStructure(VILLAGE,
                new StructureOwnerKey(ChunkPos.asLong(1, 0), ID), duplicate));
        assertFalse(VillageEndpointFilter.endpointMatchesStructure(VILLAGE.east(), OWNER, duplicate));
        assertTrue(VillageEndpointFilter.isUnknownEndpoint(UNKNOWN, duplicate));
    }
}
