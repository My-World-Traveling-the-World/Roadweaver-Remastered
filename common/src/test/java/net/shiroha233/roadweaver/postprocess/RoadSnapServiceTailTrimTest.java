package net.shiroha233.roadweaver.postprocess;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secondary-tail trimming after a junction snap: a rebuilt secondary whose
 * trailing stations still lie on the primary corridor is cut back to the
 * junction (segments, target heights and spans together); everything else —
 * no overlap, junction metadata in the tail, too-short results, non-endpoint
 * modes — keeps the candidate unchanged.
 */
final class RoadSnapServiceTailTrimTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadData road(List<int[]> stations, List<RoadSpan> spans) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int[] station : stations) {
            BlockPos center = new BlockPos(station[0], station[1], station[2]);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(station[1]);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments,
                spans == null ? List.of() : List.of(spans.toArray(new RoadSpan[0])), heights,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    private static RoadData primary() {
        List<int[]> stations = new ArrayList<>();
        for (int x = 0; x <= 9; x++) {
            stations.add(new int[]{x, 64, 0});
        }
        return road(stations, null);
    }

    /** 16 approach stations at y=64 (uniform 2-block steps), then a 3-station tail on the primary. */
    private static RoadData secondaryWithTail(int tailX, int tailZ) {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            stations.add(new int[]{40 - i * 2, 64, 0});
        }
        stations.add(new int[]{8, 64, 0});
        stations.add(new int[]{6, 64, 0});
        stations.add(new int[]{4, 64, 0});
        return road(stations, null);
    }

    @Test
    void tailOverlappingPrimaryIsTrimmedToTheJunction() {
        RoadData primary = primary();
        RoadData candidate = secondaryWithTail(8, 0);
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(candidate.roadSegmentList().get(2).middlePos(),
                candidate.roadSegmentList().get(5).middlePos(), SpanType.BRIDGE));
        spans.add(new RoadSpan(candidate.roadSegmentList().get(17).middlePos(),
                candidate.roadSegmentList().get(18).middlePos(), SpanType.BRIDGE));
        List<int[]> stations = new ArrayList<>();
        for (RoadSegmentPlacement segment : candidate.roadSegmentList()) {
            BlockPos p = segment.middlePos();
            stations.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        RoadData withSpans = road(stations, spans);

        RoadData trimmed = RoadSnapService.trimSecondaryTailAtJunction(primary, withSpans, "spatial-end");
        assertNotNull(trimmed, "the pressing tail must be trimmed");
        // Junction = first tail station on the primary (index 16): keep 0..16.
        assertEquals(17, trimmed.roadSegmentList().size(), "keep up to the junction station");
        assertEquals(17, trimmed.targetY().size(), "target heights are cut together");
        assertEquals(8, trimmed.roadSegmentList().get(16).middlePos().getX(),
                "the junction station is the first tail station on the primary");
        // No retained station beyond the junction lies on the primary corridor:
        // the junction (last retained, on the primary) is the only overlap.
        Set<Long> primaryKeys = new HashSet<>();
        for (RoadSegmentPlacement segment : primary.roadSegmentList()) {
            BlockPos p = segment.middlePos();
            primaryKeys.add(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL));
        }
        int overlaps = 0;
        for (RoadSegmentPlacement segment : trimmed.roadSegmentList()) {
            BlockPos p = segment.middlePos();
            long key = ((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL);
            if (primaryKeys.contains(key)) overlaps++;
        }
        assertEquals(1, overlaps, "only the junction station may remain on the primary");
        BlockPos last = trimmed.roadSegmentList().get(trimmed.roadSegmentList().size() - 1).middlePos();
        long lastKey = ((long) last.getX() << 32) ^ (last.getZ() & 0xffffffffL);
        assertTrue(primaryKeys.contains(lastKey), "the junction is the final retained station");
        // The span inside the retained route survives; the tail span is gone.
        assertEquals(1, trimmed.spans().size());
        assertEquals(SpanType.BRIDGE, trimmed.spans().get(0).type());
        assertEquals(candidate.roadSegmentList().get(5).middlePos(),
                trimmed.spans().get(0).end());
    }

    @Test
    void tailWithoutPrimaryOverlapIsKept() {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            stations.add(new int[]{100 - i * 4, 64, 200});
        }
        stations.add(new int[]{208, 64, 208});
        stations.add(new int[]{212, 64, 212});
        stations.add(new int[]{216, 64, 216});
        RoadData detached = road(stations, null);
        assertNull(RoadSnapService.trimSecondaryTailAtJunction(primary(), detached, "spatial-end"));
    }

    @Test
    void junctionMetadataInTheTailSuppressesTrimming() {
        RoadData candidate = secondaryWithTail(8, 0);
        List<int[]> stations = new ArrayList<>();
        for (RoadSegmentPlacement segment : candidate.roadSegmentList()) {
            BlockPos p = segment.middlePos();
            stations.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        List<RoadSpan> spans = new ArrayList<>();
        spans.add(new RoadSpan(candidate.roadSegmentList().get(17).middlePos(),
                candidate.roadSegmentList().get(18).middlePos(), SpanType.NETWORK_REUSE));
        RoadData withReuse = road(stations, spans);
        assertNull(RoadSnapService.trimSecondaryTailAtJunction(primary(), withReuse, "shared-endpoint"));
    }

    @Test
    void trimThatWouldLeaveTooFewSegmentsIsRefused() {
        List<int[]> stations = new ArrayList<>();
        stations.add(new int[]{200, 64, 200});
        // Five trailing stations on the primary: junction would be index 1.
        stations.add(new int[]{9, 64, 0});
        stations.add(new int[]{7, 64, 0});
        stations.add(new int[]{5, 64, 0});
        stations.add(new int[]{3, 64, 0});
        stations.add(new int[]{1, 64, 0});
        RoadData shortTail = road(stations, null);
        assertNull(RoadSnapService.trimSecondaryTailAtJunction(primary(), shortTail, "endpoint-corridor"));
    }

    @Test
    void reuseCorridorModesAreNeverTailTrimmed() {
        RoadData candidate = secondaryWithTail(8, 0);
        assertNull(RoadSnapService.trimSecondaryTailAtJunction(primary(), candidate, "parallel-overlap"));
        assertNull(RoadSnapService.trimSecondaryTailAtJunction(primary(), candidate, "two-junctions"));
    }
}
