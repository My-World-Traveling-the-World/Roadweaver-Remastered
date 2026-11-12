package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.435 / C1-①：窄水道改涵洞。
 *
 * <p>钉住四件事：①路面被抬到「水面 + 2」而不是填平成坝、也不是留缺口；②两侧按 1 格/站起坡
 * 接回原高度，所以路能走上去；③太宽或太深的水道不硬塞涵洞（那是桥的活）；④没命中时返回同一个
 * 对象，调用方可以据此判断「什么都没改」。
 */
final class CulvertPlannerTest {

    private static final int STATIONS = 24;
    private static final int GROUND = 63;
    private static final int WATER_SURFACE = 64;
    private static final int BED = 63;

    private static RoadData straightRoad(int stations, int surfaceY) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            BlockPos middle = new BlockPos(i, surfaceY, 0);
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
            targetY.add(surfaceY);
        }
        return new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    private static CulvertPlanner.Probe waterBetween(int from, int to, int surfaceY, int bedY) {
        return (x, z) -> x >= from && x <= to
                ? CulvertPlanner.Water.wet(surfaceY, bedY)
                : CulvertPlanner.Water.dry();
    }

    @Test
    void aNarrowStreamIsCarriedOverInsteadOfFilledOrSkipped() {
        RoadData road = straightRoad(STATIONS, GROUND);

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2,
                waterBetween(10, 12, WATER_SURFACE, BED));

        assertTrue(result.changed());
        assertEquals(1, result.runs());
        assertEquals(3, result.stations());
        assertNotSame(road, result.road(), "a changed route must be a new value, never a mutated one");

        List<Integer> heights = result.road().targetY();
        for (int x = 10; x <= 12; x++) {
            assertEquals(WATER_SURFACE + CulvertPlanner.DECK_ABOVE_WATER, heights.get(x),
                    "the deck must clear the water at station " + x);
        }
        assertEquals(65, heights.get(9), "the approach climbs one block per station");
        assertEquals(64, heights.get(8));
        assertEquals(GROUND, heights.get(7), "the ramp stops as soon as it meets the original profile");
        assertEquals(65, heights.get(13));
        assertEquals(64, heights.get(14));
        assertEquals(GROUND, heights.get(15));

        List<RoadSpan> spans = result.road().spans();
        assertEquals(1, spans.size());
        RoadSpan span = spans.getFirst();
        assertEquals(SpanType.CULVERT, span.type());
        assertEquals(new BlockPos(9, GROUND, 0), span.start(),
                "the span covers the raised approaches, so it can never name one station twice");
        assertEquals(new BlockPos(13, GROUND, 0), span.end());
    }

    @Test
    void aOneStationPuddleStillNamesTwoStations() {
        RoadData road = straightRoad(STATIONS, GROUND);

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2,
                waterBetween(10, 10, WATER_SURFACE, BED));

        assertTrue(result.changed());
        assertEquals(1, result.runs());
        assertEquals(WATER_SURFACE + CulvertPlanner.DECK_ABOVE_WATER, result.road().targetY().get(10));
        RoadSpan span = result.road().spans().getFirst();
        assertEquals(new BlockPos(9, GROUND, 0), span.start());
        assertEquals(new BlockPos(11, GROUND, 0), span.end());
    }

    @Test
    void aWideWatercourseStaysABridgeJob() {
        RoadData road = straightRoad(STATIONS, GROUND);

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2,
                waterBetween(8, 16, WATER_SURFACE, BED));

        assertFalse(result.changed());
        assertSame(road, result.road(), "nothing may be rewritten when the run is too wide");
    }

    @Test
    void deepWaterIsLeftToTheBridge() {
        RoadData road = straightRoad(STATIONS, GROUND);

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2,
                waterBetween(10, 12, WATER_SURFACE, WATER_SURFACE - 4));

        assertFalse(result.changed(), "a 5-deep channel needs walls a culvert cannot carry");
        assertSame(road, result.road());
    }

    @Test
    void aDryRouteIsUntouched() {
        RoadData road = straightRoad(STATIONS, GROUND);

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2, (x, z) -> CulvertPlanner.Water.dry());

        assertFalse(result.changed());
        assertSame(road, result.road());
    }
}
