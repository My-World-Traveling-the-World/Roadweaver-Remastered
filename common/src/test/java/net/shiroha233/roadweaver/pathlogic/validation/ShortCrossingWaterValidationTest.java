/* 验证短跨必须真的留出通水净空，不允许只凭跨度标签放行。 */
package net.shiroha233.roadweaver.pathlogic.validation;

import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ShortCrossingWaterValidationTest {
    @Test void finalWaterCheckNeedsADryDeckNotJustASpanLabel() {
        var centers = IntStream.range(0, 7).mapToObj(i -> new BlockPos(i, 70, 0)).toList();
        var placements = centers.stream().map(p -> new RoadSegmentPlacement(p, List.of(p))).toList();
        for (var type : List.of(SpanType.CULVERT, SpanType.ROAD_CROSSING)) {
            var spans = List.of(new RoadSpan(centers.get(2), centers.get(4), type));
            var unsafe = new RoadData(3, 0, List.of(), List.of(), placements, spans,
                    List.of(70, 70, 70, 70, 70, 70, 70), 1, 2);
            var raised = new RoadData(3, 0, List.of(), List.of(), placements, spans,
                    List.of(70, 70, 71, 72, 72, 71, 71), 1, 2);
            FinalRoadWaterValidator.ColumnSource probe = (x, z) -> x == 3
                    ? FinalRoadWaterValidator.WaterColumn.wet(70, 20, "test") : FinalRoadWaterValidator.WaterColumn.dry();
            var failed = FinalRoadWaterValidator.validate(unsafe, 3, probe);
            assertFalse(failed.safe());
            assertEquals(type, failed.collision().activeSpan());
            assertTrue(FinalRoadWaterValidator.validate(raised, 3, probe).safe());
        }
    }

}
