package net.shiroha233.roadweaver.worldgen.road;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RoadChunkDensityRasterTest {
    private static final ChunkPos CHUNK = new ChunkPos(-1, -1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void diagonalFromOutsideChunkPreservesItsBankAndInterpolatedHeight() {
        // 起点在区块外，因此用站点而非扩大后的整段包围盒裁剪会漏掉左上角。
        RoadData slope = road(1, 4, -18, -18, 1, 1, 21, 62, 1, null);
        RoadDensityStamp stamp = stamp(slope);
        assertEquals(64, stamp.targetY(0, 0));
        assertEquals(1.0F, stamp.carveLateral(0, 0));
        assertTrue(stamp.density(-16, 65, -16) < 0.0D);
        assertTrue(stamp.density(-16, 63, -16) > 0.0D);
        assertTrue(stamp.fillLateral(0, 5) > 0.0F, "路肩外缘仍要切填");
        assertEquals(0.0F, stamp.carveLateral(0, 5), "路肩外缘只填不挖");
        assertEquals(Integer.MIN_VALUE, stamp.targetY(0, 6), "包围盒内、精确半径外的列不可切填");
    }

    @Test
    void roadCrossingBlocksOtherSegmentsIncludingExactBoundaryWithoutRemovingOccupancy() {
        RoadData ground = road(10, 4, -18, -8, 2, 0, 11, 64, 0, null);
        RoadData crossing = road(20, 4, -8, -10, 0, 2, 3, 72, 0, SpanType.ROAD_CROSSING);
        assertEquals(1.0F, stamp(ground).carveLateral(4, 8), "正控：没有跨路段时这里确实要挖");
        RoadDensityStamp combined = stamp(ground, crossing);
        // 跨路保护是全区块列掩码，要同时挡住其他道路在边界做出的切填。
        assertEquals(Integer.MIN_VALUE, combined.targetY(4, 8));
        assertEquals(0.0F, combined.fillLateral(8, 8));
        assertTrue(combined.isOccupied(8, 8), "禁切填不等于撤销占地");
        assertEquals(64, combined.targetY(3, 8), "保护半径外不受影响");
    }

    @Test
    void culvertKeepsItsConstructionSliceWithoutFillingTheChannel() {
        RoadData ground = road(10, 4, -18, -8, 2, 0, 11, 64, 0, null);
        RoadData culvert = road(20, 4, -8, -10, 0, 2, 3, 72, 0, SpanType.CULVERT);
        // 正控：普通路在同一列确实有填方，涵洞的掩码也必须能挡住另一条路。
        assertTrue(stamp(ground).density(-8, 62, -8) > 0);
        RoadChunkPlan plan = RoadChunkPlanCompiler.compile(CHUNK, List.of(ground, culvert), false, 6, 1L);
        assertTrue(plan.slices().stream().anyMatch(slice -> slice.road().spans().stream()
                .anyMatch(span -> span.type() == SpanType.CULVERT)));
        assertTrue(plan.densityStamp().isOccupied(8, 8));
        assertEquals(0.0D, plan.densityStamp().density(-8, 62, -8));
        assertEquals(0.0D, plan.densityStamp().density(-8, 70, -8));
        assertTrue(plan.densityStamp().density(-15, 62, -8) > 0, "保护范围外的道路仍有填方");
    }

    @Test
    void equalWeightsKeepTheFirstCanonicalRoadEvenWhenInputOrderIsReversed() {
        RoadData low = road(30, 4, -18, -8, 2, 0, 11, 64, 0, null);
        RoadData high = road(40, 4, -18, -8, 2, 0, 11, 77, 0, null);
        long lowKey = RoadFingerprint.compute(low);
        long highKey = RoadFingerprint.compute(high);
        assertNotEquals(lowKey, highKey);
        int expectedY = lowKey < highKey ? 64 : 77;
        for (RoadDensityStamp stamp : List.of(stamp(low, high), stamp(high, low))) {
            // 两条路高度不同，不会被归并；等权时仍按归一化后的顺序选择第一条。
            assertEquals(1.0F, stamp.fillLateral(8, 8));
            assertEquals(expectedY, stamp.targetY(8, 8));
            assertEquals(0.0D, stamp.density(-8, expectedY, -8));
            assertTrue(stamp.density(-8, expectedY + 1, -8) < 0.0D);
        }
    }

    private static RoadDensityStamp stamp(RoadData... roads) {
        RoadChunkPlan plan = RoadChunkPlanCompiler.compile(CHUNK, List.of(roads), false, 6, 1L);
        assertFalse(plan.slices().isEmpty());
        assertFalse(plan.densityStamp().isEmpty());
        return plan.densityStamp();
    }

    private static RoadData road(long id, int width, int x, int z, int dx, int dz, int count,
                                 int y, int rise, SpanType spanType) {
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos position = new BlockPos(x + i * dx, y + i * rise, z + i * dz);
            stations.add(new RoadSegmentPlacement(position, List.of(position)));
            heights.add(position.getY());
        }
        var spans = spanType == null ? List.<RoadSpan>of() : List.of(
                new RoadSpan(stations.getFirst().middlePos(), stations.getLast().middlePos(), spanType));
        return new RoadData(width, 1, List.of(), List.of(), stations, spans, heights, id, id + 1);
    }
}
