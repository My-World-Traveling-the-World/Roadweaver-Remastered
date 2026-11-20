package net.shiroha233.roadweaver.worldgen.road;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 既钉住精确几何，也验证准备工作确实复用，避免两份空产物相等冒充优化。 */
class RoadReuseGeometryTest {
    @Test void repeatedDirectionQueriesReadEachNeighborhoodOnlyOnce() {
        var first = new CountedStations(List.of(at(0, 0), at(1, 0), at(2, 0)));
        var second = new CountedStations(List.of(at(0, 1), at(1, 1), at(2, 1)));
        var a = new RoadReuseGeometry(first);
        var b = new RoadReuseGeometry(second);
        for (int i = 0; i < 100; i++) assertTrue(a.directionCompatible(1, b, 1));
        assertEquals(2, first.reads);
        assertEquals(2, second.reads);
        // 无方向也是已经计算的结果；不能因null重复扫描相同邻站。
        var flat = new CountedStations(List.of(at(0, 0), at(0, 0)));
        var unknown = new RoadReuseGeometry(flat);
        for (int i = 0; i < 100; i++) assertTrue(unknown.directionCompatible(0, b, 1));
        assertEquals(2, flat.reads);
    }

    @Test void directionArithmeticAndCompatibilityMatchFrozenIncludingIntegerWrap() {
        var cases = List.of(List.of(at(0, 0)), List.of(at(0, 0), at(0, 0)),
                List.of(at(0, 0), at(3, 4)), List.of(at(3, 4), at(0, 0)),
                List.of(at(0, 0), at(0, 5)), List.of(at(0, 0), at(5, 0)),
                List.of(at(Integer.MIN_VALUE, 0), at(Integer.MAX_VALUE, 1)),
                List.of(new RoadSegmentPlacement(null, null), at(0, 0)));
        for (var first : cases) for (var second : cases) {
            var a = new RoadReuseGeometry(first);
            var b = new RoadReuseGeometry(second);
            for (int i = 0; i < first.size(); i++) for (int j = 0; j < second.size(); j++) {
                assertArrayEquals(FrozenRoadPhysicalReuseNormalizer.localDirection(first, i),
                        RoadReuseGeometry.localDirection(first, i));
                assertEquals(FrozenRoadPhysicalReuseNormalizer.physicalDirectionCompatible(first, i, second, j),
                        a.directionCompatible(i, b, j));
            }
        }
    }

    @Test void exactFootprintsMatchFrozenIncludingCentersNullAndExtremeCoordinates() {
        var random = new Random(76491);
        for (int run = 0; run < 500; run++) {
            var first = footprint(random, run);
            var second = footprint(random, run + 1);
            assertEquals(FrozenRoadPhysicalReuseNormalizer.footprintsIntersect(first, second),
                    new RoadReuseGeometry(List.of(first)).intersects(0, second));
            assertEquals(FrozenRoadPhysicalReuseNormalizer.footprintsIntersect(first, second),
                    RoadPhysicalReuseNormalizer.footprintsIntersect(first, second));
        }
        var centerOnly = new RoadSegmentPlacement(new BlockPos(-1, 1, -1), null);
        var differentY = new RoadSegmentPlacement(null, Arrays.asList(null, new BlockPos(-1, 200, -1)));
        assertTrue(new RoadReuseGeometry(List.of(centerOnly)).intersects(0, differentY));
        assertFalse(new RoadReuseGeometry(List.of(at(-1, -1))).intersects(0, at(0, -1)));
    }

    @Test void cachedFootprintReadsItsOwnerOnceAndKeepsNegativeMisses() {
        var stations = new CountedStations(List.of(new RoadSegmentPlacement(null,
                Arrays.asList(null, new BlockPos(0, 1, 0), new BlockPos(-16, 2, -17)))));
        var geometry = new RoadReuseGeometry(stations);
        for (int i = 0; i < 100; i++) {
            assertTrue(geometry.intersects(0, at(0, 0)), "零键必须是正常占地");
            assertTrue(geometry.intersects(0, at(-16, -17)));
            assertFalse(geometry.intersects(0, at(-16, -16)));
        }
        assertEquals(1, stations.reads);
    }

    @Test void realWideBentLayeredAndTunnelProductsMatchFrozenAndActuallyShare() {
        var random = new Random(9183);
        int sharedBatches = 0;
        for (int batch = 0; batch < 30; batch++) {
            var roads = new ArrayList<RoadData>();
            for (int group = 0; group < 8; group++) {
                var segments = new ArrayList<RoadSegmentPlacement>();
                var heights = new ArrayList<Integer>();
                int x = group * 256, z = -30, y = 64 + group;
                for (int i = 0; i < 24; i++) {
                    x++;
                    z += random.nextInt(3) - 1;
                    var center = new BlockPos(x, y, z);
                    segments.add(new RoadSegmentPlacement(center, List.of(center.offset(0, 0, -1),
                            center, center.offset(0, 0, 1))));
                    heights.add(y);
                }
                var spans = group % 3 == 0 ? List.of(new RoadSpan(segments.getFirst().middlePos(),
                        segments.getLast().middlePos(), SpanType.TUNNEL)) : List.<RoadSpan>of();
                var road = new RoadData(3, 1, List.of(), List.of(), segments, spans, heights,
                        group * 2, group * 2 + 1);
                roads.add(road);
                roads.add(new RoadData(3, 1, List.of(), List.of(), segments, spans, heights,
                        group * 2 + 100, group * 2 + 101));
                // 这里只对拍行为等价部分；旧实现误认上下层隧道，独立高度回归负责钉住修正后的契约。
                // 普通道路仍用上下层对照，隧道的同层三路则保留真实共用和确定排序验证。
                roads.add(new RoadData(3, 1, List.of(), List.of(), segments, spans,
                        heights.stream().map(h -> h + (spans.isEmpty() ? 8 : 0)).toList(),
                        group * 2 + 200, group * 2 + 201));
            }
            Collections.shuffle(roads, random);
            var actual = RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads);
            assertEquals(FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads), actual, "批次" + batch);
            if (actual.stream().anyMatch(r -> r.spans().stream().anyMatch(s -> s.type() == SpanType.NETWORK_REUSE)))
                sharedBatches++;
        }
        assertEquals(30, sharedBatches, "每批必须实际产生共用段，不能只对拍两份空结果");
    }

    @Test void productionDirectionRejectsCrossingButAcceptsParallelStations() {
        var east = new ArrayList<RoadSegmentPlacement>();
        var north = new ArrayList<RoadSegmentPlacement>();
        for (int i = 0; i < 24; i++) {
            var center = new BlockPos(i, 64, 0);
            // 合法宽路交叉使足迹确实相交且超过最短共用长度，不能让“站数不足”替方向守卫挡枪。
            east.add(new RoadSegmentPlacement(center, java.util.stream.IntStream.rangeClosed(-24, 24)
                    .mapToObj(z -> center.offset(0, 0, z)).toList()));
            north.add(at(0, i));
        }
        var owner = road(east, false, 1);
        var crossed = new boolean[24];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, road(north, false, 3), crossed);
        assertArrayEquals(new boolean[24], crossed, "相交方向不是物理共用走廊");
        var parallel = new boolean[24];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, road(east, false, 5), parallel);
        var expected = new boolean[24];
        Arrays.fill(expected, true);
        assertArrayEquals(expected, parallel, "同向正例必须实际认领全部站点");
    }

    @Test void productionTunnelRequiresIntersectingFootprintEvenWhenCentersAreClose() {
        var first = new ArrayList<RoadSegmentPlacement>();
        var second = new ArrayList<RoadSegmentPlacement>();
        for (int i = 0; i < 24; i++) { first.add(at(i, 0)); second.add(at(i, 1)); }
        var owner = road(first, true, 1);
        var disjoint = new boolean[24];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, road(second, true, 3), disjoint);
        assertArrayEquals(new boolean[24], disjoint);
        var shared = new boolean[24];
        RoadPhysicalReuseNormalizer.markPhysicalReuse(owner, road(first, true, 5), shared);
        var expected = new boolean[24];
        Arrays.fill(expected, true);
        assertArrayEquals(expected, shared, "同隧道占地正例必须真实复用");
    }

    private static RoadData road(List<RoadSegmentPlacement> stations, boolean tunnel, long owner) {
        var spans = tunnel ? List.of(new RoadSpan(stations.getFirst().middlePos(),
                stations.getLast().middlePos(), SpanType.TUNNEL)) : List.<RoadSpan>of();
        var result = new RoadData(stations.getFirst().positions().size(), 1, List.of(), List.of(), stations, spans,
                Collections.nCopies(stations.size(), 64), owner, owner + 1);
        assertTrue(net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator.isValidFinalRoad(result),
                "生产方向/隧道夹具必须先通过终态校验，不能由非法输入提前拒绝");
        return result;
    }

    private static RoadSegmentPlacement footprint(Random random, int run) {
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < 5; i++) positions.add(i == 0 ? null
                : new BlockPos(random.nextInt(9) - 4, random.nextInt(256), random.nextInt(9) - 4));
        if (run % 7 == 0) positions.add(new BlockPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
        return new RoadSegmentPlacement(run % 3 == 0 ? null : positions.get(1), run % 5 == 0 ? null : positions);
    }

    private static RoadSegmentPlacement at(int x, int z) {
        var pos = new BlockPos(x, 64, z);
        return new RoadSegmentPlacement(pos, List.of(pos));
    }

    private static final class CountedStations extends AbstractList<RoadSegmentPlacement> {
        private final List<RoadSegmentPlacement> values;
        private int reads;
        CountedStations(List<RoadSegmentPlacement> values) { this.values = values; }
        @Override public RoadSegmentPlacement get(int index) { reads++; return values.get(index); }
        @Override public int size() { return values.size(); }
    }
}
