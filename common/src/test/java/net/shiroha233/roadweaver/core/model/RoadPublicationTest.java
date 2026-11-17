/* 文件职责：守住道路发布后的坐标与铺装列契约。 */
package net.shiroha233.roadweaver.core.model;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoadPublicationTest {
    @Test void callerCannotMovePublishedStationsFootprintsOrSpanEndpoints() {
        var cursor = new BlockPos.MutableBlockPos(2,64,0);
        var footprint = new ArrayList<BlockPos>(List.of(cursor));
        var station = new RoadSegmentPlacement(cursor, footprint);
        var span = new RoadSpan(cursor, cursor, SpanType.PROTECTED_GAP);
        var segments = new ArrayList<RoadSegmentPlacement>();
        for (int x=0;x<5;x++) segments.add(x==2 ? station :
            new RoadSegmentPlacement(new BlockPos(x,64,0), List.of(new BlockPos(x,64,0))));
        var road = new RoadData(3,0,List.of(),List.of(),segments,List.of(span),List.of(64,64,64,64,64),1,2);
        assertTrue(RoadDataValidator.validateFinalRoad(road).valid());
        int before = road.hashCode();
        cursor.set(999, -99, 999); footprint.clear();
        assertEquals(new BlockPos(2,64,0), station.middlePos());
        assertEquals(List.of(new BlockPos(2,64,0)), station.positions());
        assertEquals(station.middlePos(), span.start());
        assertEquals(station.middlePos(), span.end());
        assertEquals(before, road.hashCode());
        assertTrue(RoadDataValidator.validateFinalRoad(road).valid());
        assertThrows(UnsupportedOperationException.class, () -> station.positions().clear());
    }
    @Test void invalidNullInputsRemainAvailableForDiagnosticValidation() {
        var station = new RoadSegmentPlacement(null, Arrays.asList((BlockPos)null));
        assertNull(station.middlePos()); assertEquals(1, station.positions().size());
        assertNull(station.positions().getFirst());
        assertNull(new RoadSegmentPlacement(null,null).positions());
        assertNull(new RoadSpan(null,null,null).start());
    }
}
