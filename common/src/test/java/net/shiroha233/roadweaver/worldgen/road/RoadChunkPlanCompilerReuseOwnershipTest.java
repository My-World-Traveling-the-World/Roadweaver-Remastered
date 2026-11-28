package net.shiroha233.roadweaver.worldgen.road;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-15 真机缺陷：村口那条「地图上有蓝线、世界里断一截」的路。
 *
 * <p>NETWORK_REUSE 是规划期写在后一条路上的元数据「这段由主路铺」，匹配的是主路的
 * <b>规划期中心线快照</b>。主路随后可能被删（{@code RoadShardStorage.deleteRoad}）
 * 或在几何处理里被裁短，于是一段路两头都不铺。修复口径是 fail-safe：一次编译里
 * 问一句「本次编译集合里真有别的路铺到这儿吗」，没有就把让出收回来自己铺 ——
 * 多铺一段永远好过留个洞。
 */
final class RoadChunkPlanCompilerReuseOwnershipTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 32 站、每站 2 格间距的一条直线路。 */
    private static RoadData line(int x0, int y, int z, long ownerA, long ownerB, List<RoadSpan> spans) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            BlockPos center = new BlockPos(x0 + i * 2, y, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(y);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments,
                spans == null ? List.of() : spans, heights, ownerA, ownerB);
    }

    /** 把某个站点区间标成 NETWORK_REUSE（让出给主路铺）。 */
    private static RoadData surrendered(RoadData road, int from, int to) {
        BlockPos first = road.roadSegmentList().get(from).middlePos();
        BlockPos last = road.roadSegmentList().get(to).middlePos();
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(first, last, SpanType.NETWORK_REUSE));
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    private static RoadData byOwner(List<RoadData> roads, long ownerA, long ownerB) {
        for (RoadData road : roads) {
            if (road != null && road.ownerA2dKey() == ownerA && road.ownerB2dKey() == ownerB) return road;
        }
        return null;
    }

    private static boolean hasReuse(RoadData road) {
        return road != null && road.spans().stream()
                .anyMatch(span -> span != null && span.type() == SpanType.NETWORK_REUSE);
    }

    /** 该站点是否落在本路自己的 NETWORK_REUSE 区间里（= 本路不会铺它）。 */
    private static boolean covers(RoadData road, int index) {
        BlockPos station = road.roadSegmentList().get(index).middlePos();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.NETWORK_REUSE) continue;
            int from = road.roadSegmentList().indexOf(
                    new RoadSegmentPlacement(span.start(), List.of(span.start())));
            int to = road.roadSegmentList().indexOf(
                    new RoadSegmentPlacement(span.end(), List.of(span.end())));
            if (from < 0 || to < 0) continue;
            int lo = Math.min(from, to);
            int hi = Math.max(from, to);
            for (int i = lo; i <= hi; i++) {
                if (road.roadSegmentList().get(i).middlePos().equals(station)) return true;
            }
        }
        return false;
    }

    @Test
    void aSurrenderNobodyCanHonourIsPavedByItsMarker() {
        RoadData marker = surrendered(line(0, 64, 0, 11L, 12L, null), 4, 12);

        List<RoadData> out = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(marker));
        RoadData result = byOwner(out, 11L, 12L);

        assertNotNull(result, "the road must survive normalization");
        assertFalse(hasReuse(result), "a surrender no other road can honour must be reclaimed");
        assertFalse(covers(result, 4), "the marker must pave its own station again");
        assertFalse(covers(result, 12), "the marker must pave its own station again");
    }

    @Test
    void aDistantRoadDoesNotCountAsTheOwner() {
        RoadData marker = surrendered(line(0, 64, 0, 11L, 12L, null), 4, 12);
        // 30 blocks away: outside PHYSICAL_REUSE_MAX_CENTER_DISTANCE (5).
        RoadData stranger = line(60, 64, 0, 13L, 14L, null);

        List<RoadData> out = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(marker, stranger));

        assertFalse(hasReuse(byOwner(out, 11L, 12L)), "a road that is not on top of the stretch owns nothing");
    }

    @Test
    void aSurrenderItsOwnerCoversStaysSurrendered() {
        RoadData marker = surrendered(line(0, 64, 0, 11L, 12L, null), 4, 12);
        RoadData owner = line(0, 64, 0, 13L, 14L, null); // same stations:真的有人铺

        List<RoadData> out = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(marker, owner));
        RoadData result = byOwner(out, 11L, 12L);

        assertTrue(hasReuse(result), "an honoured surrender must stay single-paved");
        assertTrue(covers(result, 4) && covers(result, 12),
                "no double pavement while the owner really covers the stretch");
    }

    @Test
    void overlappingOrdinaryRoadCannotOwnACulvert() {
        var ordinary = line(0, 64, 0, 11L, 12L, null);
        var culvert = line(0, 64, 0, 13L, 14L, List.of(new RoadSpan(
                new BlockPos(16, 64, 0), new BlockPos(32, 64, 0), SpanType.CULVERT)));
        var normalized = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(ordinary, culvert));
        var result = byOwner(normalized, 13L, 14L);
        var ordinaryResult = byOwner(normalized, 11L, 12L);
        assertNotNull(result);
        assertNotNull(ordinaryResult);
        // 所有者按稳定指纹排序，不能把实物优先级写死成输入的先后顺序。
        assertTrue(normalized.stream().anyMatch(r -> covers(r, 4)), "同线的普通区间仍然参与复用");
        for (int i = 8; i <= 16; i++) {
            assertFalse(covers(result, i), "岸端和水道必须由涵洞自己施工，站号 " + i);
        }
        assertFalse(covers(ordinaryResult, 12), "水道中心距普通岸站超过匹配半径，不能由涵洞替铺");
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == SpanType.CULVERT));
    }

    @Test
    void anOwnerThatStopsShortDoesNotSaveTheSurrender() {
        RoadData marker = surrendered(line(0, 64, 0, 11L, 12L, null), 4, 27);
        RoadData shortOwner = line(0, 64, 0, 13L, 14L, null);
        // Truncate the owner to its first 16 stations, the way a trimmed primary ends up.
        RoadData trimmed = new RoadData(shortOwner.width(), shortOwner.roadType(),
                shortOwner.materials(), shortOwner.slabMaterials(),
                shortOwner.roadSegmentList().subList(0, 16), shortOwner.spans(),
                shortOwner.targetY().subList(0, 16), shortOwner.ownerA2dKey(), shortOwner.ownerB2dKey());

        List<RoadData> out = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(marker, trimmed));
        RoadData result = byOwner(out, 11L, 12L);

        assertTrue(covers(result, 10), "the covered half keeps its surrender");
        assertFalse(covers(result, 27), "the tail the owner never reaches must be paved by the marker");
    }

    @Test
    void ownerSearchIncludesTheSixtyFourBlockBoundaryAcrossNegativeBuckets() {
        RoadData secondary = line(-28, 64, 0, 201L, 202L, null);
        RoadData onLimit = ownerWithExtendedFootprint(-64);
        RoadData outOfRange = ownerWithExtendedFootprint(-65);
        var covered = new boolean[secondary.roadSegmentList().size()];
        var uncovered = new boolean[covered.length];
        // 让足迹相交以单独检验中心距离上限；若只查相邻半径 5，这条正例就会漏掉。
        RoadPhysicalReuseNormalizer.markPhysicalReuse(onLimit, secondary, covered);
        RoadPhysicalReuseNormalizer.markPhysicalReuse(outOfRange, secondary, uncovered);
        for (int i = 0; i < covered.length; i++) {
            assertTrue(covered[i], "64 格边界仍可复用，站 " + i);
            assertFalse(uncovered[i], "65 格即使足迹相交也不能复用，站 " + i);
        }
    }

    @Test
    void equalDistanceAcrossBucketsUsesTheEarlierOwnerIndex() {
        List<RoadSegmentPlacement> primary = new ArrayList<>();
        List<RoadSegmentPlacement> secondary = new ArrayList<>();
        // 先走正 Z 桶，再反向折回负 Z；副路交替「只近上层」与「两边等距」。
        // 若等距改选先查到的负 Z 桶，主路索引会反复跳过连续性上限，整段就无法复用。
        for (int x = 0; x <= 48; x += 2) primary.add(station(x, 1, 0));
        for (int x = 48; x >= 0; x -= 2) primary.add(station(x, -1, 0));
        for (int i = 0; i < 14; i++) secondary.add(station(i * 2, i % 2, i % 2));

        var reused = new boolean[secondary.size()];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(stationsOf(203, primary), stationsOf(205, secondary), reused);
        for (int i = 0; i < reused.length; i++) {
            assertTrue(reused[i], "等距时必须保留站序更早的主路候选，站 " + i);
        }
    }

    private static RoadData ownerWithExtendedFootprint(int z) {
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        for (int i = 0; i < 32; i++) stations.add(station(-28 + i * 2, z, 0));
        return stationsOf(207, stations);
    }

    private static RoadSegmentPlacement station(int x, int z, int footprintZ) {
        BlockPos center = new BlockPos(x, 64, z);
        return new RoadSegmentPlacement(center, List.of(new BlockPos(x, 64, footprintZ)));
    }

    private static RoadData stationsOf(long id, List<RoadSegmentPlacement> stations) {
        return new RoadData(4, 1, List.of(), List.of(), stations, List.of(),
                stations.stream().map(station -> station.middlePos().getY()).toList(), id, id + 1);
    }

    @Test
    void reclaimingASurrenderKeepsTheRoadsOtherSpans() {
        RoadData base = line(0, 64, 0, 11L, 12L, null);
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(base.roadSegmentList().get(20).middlePos(),
                base.roadSegmentList().get(24).middlePos(), SpanType.PROTECTED_GAP));
        spans.add(new RoadSpan(base.roadSegmentList().get(4).middlePos(),
                base.roadSegmentList().get(12).middlePos(), SpanType.NETWORK_REUSE));
        RoadData marker = new RoadData(base.width(), base.roadType(), base.materials(),
                base.slabMaterials(), base.roadSegmentList(), spans, base.targetY(),
                base.ownerA2dKey(), base.ownerB2dKey());

        List<RoadData> out = RoadChunkPlanCompiler.normalizeForPhysicalOwnership(List.of(marker));
        RoadData result = byOwner(out, 11L, 12L);

        assertFalse(hasReuse(result), "the unowned surrender is reclaimed");
        assertTrue(result.hasProtectedGap(), "a protected building gap is not ours to reclaim");
    }
}
