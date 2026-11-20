package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 先证明准入不漏任何旧精配中心，再比较完整归属产物，防止空候选反而全绿。 */
class RoadReuseCandidatesTest {
    @Test void sparseRoadsKeepOnlyLocalPairsAndStillActuallyShare() {
        var roads = new ArrayList<RoadData>();
        for (int i = 0; i < 100; i++) roads.add(line(i * 1024 - 50000, -17, i, false));
        roads.add(line(-50000, -17, 100, false));
        var index = new RoadReuseCandidates(roads, 64);
        long checks = 0;
        for (var road : roads) checks += index.nearby(road).length;
        assertEquals(103, checks, "每条保留自身，另保留实际重合的两个方向");
        var actual = RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads);
        assertEquals(FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads), actual);
        assertTrue(actual.stream().anyMatch(r -> r.spans().stream().anyMatch(s -> s.type() == SpanType.NETWORK_REUSE)));
    }

    @Test void randomDenseSparseSurrenderedAndLayeredProductsMatchFrozen() {
        var random = new Random(93281);
        for (int batch = 0; batch < 20; batch++) {
            var roads = new ArrayList<RoadData>();
            for (int i = 0; i < 24; i++) {
                int offset = batch % 2 == 0 ? random.nextInt(40) : random.nextInt(6000);
                roads.add(line(offset - 3000, random.nextInt(12) - 6, i, (i & 3) == 0));
            }
            assertEquals(FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads),
                    RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads), "批次" + batch);
            var index = new RoadReuseCandidates(roads, 64);
            for (int i = 0; i < roads.size(); i++) for (int j = 0; j < roads.size(); j++) {
                if (hasCloseCenters(roads.get(i), roads.get(j)))
                    assertTrue(Arrays.binarySearch(index.nearby(roads.get(i)), j) >= 0);
            }
        }
    }

    @Test void distanceBoundaryNegativeCoordinatesAndExtremeIntsAreConservative() {
        for (int start : new int[]{-65, -1, 0, 63, Integer.MIN_VALUE, Integer.MAX_VALUE - 80}) {
            var roads = new ArrayList<RoadData>();
            for (int i = 0; i < 16; i++) roads.add(line(start, i * 64, i, false));
            var index = new RoadReuseCandidates(roads, 64);
            assertTrue(Arrays.binarySearch(index.nearby(roads.get(0)), 1) >= 0);
            assertArrayEquals(new int[]{0, 1, 2}, index.nearby(roads.get(1)));
        }
    }

    @Test void smallBatchesRetainOriginalOrderAndOrphanSurrenderIsReclaimed() {
        var road = line(-128, -2, 1, true);
        assertArrayEquals(new int[]{0}, new RoadReuseCandidates(List.of(road), 64).nearby(road));
        var actual = RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(List.of(road));
        assertEquals(FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(List.of(road)), actual);
        assertTrue(actual.getFirst().spans().isEmpty());
    }

    private static boolean hasCloseCenters(RoadData a, RoadData b) {
        for (var x : a.roadSegmentList()) for (var y : b.roadSegmentList())
            if (Math.abs((long) x.middlePos().getX() - y.middlePos().getX()) <= 64
                    && Math.abs((long) x.middlePos().getZ() - y.middlePos().getZ()) <= 64) return true;
        return false;
    }

    static RoadData line(int x, int z, long owner, boolean surrendered) {
        var segments = new ArrayList<RoadSegmentPlacement>();
        var heights = new ArrayList<Integer>();
        for (int i = 0; i < 16; i++) {
            var center = new BlockPos(x + i * 2, 64, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        var spans = surrendered ? List.of(new RoadSpan(segments.getFirst().middlePos(),
                segments.getLast().middlePos(), SpanType.NETWORK_REUSE)) : List.<RoadSpan>of();
        return new RoadData(4, 1, List.of(), List.of(), segments, spans, heights, owner, owner + 1);
    }
}
