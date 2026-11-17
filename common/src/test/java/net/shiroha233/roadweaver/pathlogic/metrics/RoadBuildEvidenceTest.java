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

    private static RoadData road() {
        BlockPos a = new BlockPos(0, 64, 0), b = new BlockPos(1, 64, 0);
        return new RoadData(5, 0, List.of(), List.of(), List.of(
                new RoadSegmentPlacement(a, List.of(a, b)), new RoadSegmentPlacement(b, List.of(b))),
                List.of(new RoadSpan(a, b, SpanType.BRIDGE), new RoadSpan(a, b, SpanType.TUNNEL),
                        new RoadSpan(a, b, SpanType.NETWORK_REUSE), new RoadSpan(a, b, SpanType.PROTECTED_GAP)),
                List.of(64, 64), RouteEvidenceId.endpoint(-17, 32), RouteEvidenceId.endpoint(101, -1));
    }
}
