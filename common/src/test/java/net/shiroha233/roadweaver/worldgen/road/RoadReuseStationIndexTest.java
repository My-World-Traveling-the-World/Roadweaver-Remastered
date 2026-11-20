package net.shiroha233.roadweaver.worldgen.road;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 首末站语义直接断言，再对拍旧掩码；防止重复XZ在优化后悄悄改变跨度范围。 */
class RoadReuseStationIndexTest {
    @Test void duplicateXZKeepsBothEndpointsIgnoringHeight() {
        var stations = List.of(at(0, 64, 0), at(-1, 64, -1), at(0, 80, 0),
                at(Integer.MIN_VALUE, 64, 0), at(Integer.MAX_VALUE, 64, Integer.MIN_VALUE));
        var index = new RoadReuseStationIndex(stations);
        assertEquals(0, index.first(new BlockPos(0, -60, 0)));
        assertEquals(2, index.last(new BlockPos(0, -60, 0)));
        assertEquals(1, index.first(new BlockPos(-1, 0, -1)));
        assertEquals(3, index.last(new BlockPos(Integer.MIN_VALUE, 0, 0)));
        assertEquals(4, index.first(new BlockPos(Integer.MAX_VALUE, 0, Integer.MIN_VALUE)));
        assertEquals(-1, index.first(new BlockPos(12, 64, 0)));
        assertEquals(-1, index.last(null));
    }

    @Test void oneLazyBuildServesAllHitsAndMisses() {
        var stations = new Counted(List.of(at(0, 64, 0), at(1, 64, 0), at(0, 65, 0)));
        var index = new RoadReuseStationIndex(stations);
        assertEquals(-1, index.first(null));
        assertEquals(0, stations.reads, "没有有效查询时不能提前扫描");
        for (int i = 0; i < 100; i++) {
            assertEquals(0, index.first(BlockPos.ZERO));
            assertEquals(2, index.last(BlockPos.ZERO));
            assertEquals(-1, index.first(new BlockPos(2, 0, 0)));
        }
        assertEquals(3, stations.reads, "命中和未命中均须复用同一张完整索引");
    }

    @Test void interruptedBuildCannotPublishPartialIndex() {
        var stations = new Counted(List.of(at(0, 64, 0), at(1, 64, 0)));
        stations.failOnce = true;
        var index = new RoadReuseStationIndex(stations);
        assertThrows(IllegalStateException.class, () -> index.first(BlockPos.ZERO));
        assertEquals(1, index.last(new BlockPos(1, 80, 0)));
        assertEquals(0, index.first(BlockPos.ZERO));
        assertEquals(4, stations.reads, "失败后的查询须重新完成全表，而非读半张缓存");
    }

    @Test void absentAndNullCentersAreNeverInventedAsOrigin() {
        var index = new RoadReuseStationIndex(Arrays.asList(null, new RoadSegmentPlacement(null, null),
                at(3, 64, 4)));
        assertEquals(-1, index.first(BlockPos.ZERO));
        assertEquals(2, index.first(new BlockPos(3, 90, 4)));
        assertEquals(2, index.last(new BlockPos(3, 90, 4)));
        assertEquals(-1, new RoadReuseStationIndex(List.of()).last(BlockPos.ZERO));
    }

    @Test void realSpanMasksMatchFrozenForMixedTypesRepeatedStationsAndReversedEndpoints() {
        var random = new Random(78120);
        for (int run = 0; run < 200; run++) {
            var stations = new ArrayList<RoadSegmentPlacement>();
            for (int i = 0; i < 48; i++) stations.add(at(random.nextInt(12) - 6, 64 + i, 0));
            var spans = new ArrayList<RoadSpan>();
            for (var type : SpanType.values()) {
                spans.add(new RoadSpan(stations.get(random.nextInt(48)).middlePos(),
                        stations.get(random.nextInt(48)).middlePos(), type));
            }
            spans.add(new RoadSpan(new BlockPos(999, 64, 0), stations.getFirst().middlePos(), SpanType.BRIDGE));
            var road = new RoadData(1, 1, List.of(), List.of(), stations, spans,
                    Collections.nCopies(48, 64), 1, 2);
            // 端点索引只认XZ；这是特意覆盖回访坐标的内部掩码夹具，不冒充最终坡度合法输入。
            for (boolean blockReuse : List.of(false, true)) {
                assertArrayEquals(FrozenRoadPhysicalReuseNormalizer.physicalReuseBlocked(road, blockReuse),
                        RoadPhysicalReuseNormalizer.physicalReuseBlocked(road, blockReuse));
                assertEquals(Arrays.toString(FrozenRoadPhysicalReuseNormalizer.spanKinds(road, blockReuse)),
                        Arrays.toString(RoadPhysicalReuseNormalizer.spanKinds(road, blockReuse)));
            }
        }
    }

    private static RoadSegmentPlacement at(int x, int y, int z) {
        var pos = new BlockPos(x, y, z);
        return new RoadSegmentPlacement(pos, List.of(pos));
    }

    private static final class Counted extends AbstractList<RoadSegmentPlacement> {
        final List<RoadSegmentPlacement> values;
        int reads;
        boolean failOnce;
        Counted(List<RoadSegmentPlacement> values) { this.values = values; }
        @Override public int size() { return values.size(); }
        @Override public RoadSegmentPlacement get(int index) {
            reads++;
            if (index == 1 && failOnce) {
                failOnce = false;
                throw new IllegalStateException("模拟索引读取中断，验证不能发布部分结果");
            }
            return values.get(index);
        }
    }
}
