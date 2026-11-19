package net.shiroha233.roadweaver.pathlogic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlanCompiler;
import org.junit.jupiter.api.Test;

import static net.shiroha233.roadweaver.pathlogic.PathFeature.LoadedPlacement.*;
import static org.junit.jupiter.api.Assertions.*;

final class LoadedChunkReplayTest {
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);

    @Test
    void precheckAndPlacementConsumeOneCompiledPlan() {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for (int x = 2; x < 9; x++) {
            var pos = new BlockPos(x, 64, 4);
            stations.add(new RoadSegmentPlacement(pos, List.of(pos)));
        }
        var road = new RoadData(1, 0, List.of(), List.of(), stations, List.of(),
                java.util.Collections.nCopies(stations.size(), 64), 11L, 13L);
        var first = RoadChunkPlanCompiler.compile(CHUNK, List.of(road), false, 4, 7L);
        assertFalse(first.slices().isEmpty(), "有效道路必须真的编译出切片");

        var reads = new AtomicInteger();
        var usedRevisions = new ArrayList<Long>();
        assertEquals(PLACED, PathFeature.replayPlan(
                () -> reads.getAndIncrement() == 0 ? first : RoadChunkPlan.empty(CHUNK, 4, 8L),
                compiled -> {
                    usedRevisions.add(compiled.revision());
                    assertSame(first, compiled);
                    return true;
                }));
        assertEquals(1, reads.get());
        assertEquals(List.of(7L), usedRevisions);
        assertEquals(NO_PLACEMENT, PathFeature.replayPlan(() -> first, ignored -> false));
    }

    @Test
    void emptyPlanNeverInvokesPlacement() {
        var reads = new AtomicInteger();
        assertEquals(EMPTY_PLAN, PathFeature.replayPlan(() -> {
            reads.incrementAndGet();
            return RoadChunkPlan.empty(CHUNK, 4, 9L);
        }, ignored -> fail("擦边空计划不应该落块或释放预留")));
        assertEquals(1, reads.get());
    }

    @Test
    void disabledAndFailedPlansNeverBecomeTerminalEmpty() {
        for (var status : List.of(RoadChunkPlan.Outcome.DISABLED, RoadChunkPlan.Outcome.RETRYABLE_FAILURE)) {
            var unavailable = RoadChunkPlan.unavailable(CHUNK, 4, 10, status);
            assertEquals(DEFERRED, PathFeature.replayPlan(() -> unavailable,
                    ignored -> fail("暂时不可用不能落块或永久结案")));
            assertThrows(RoadChunkPlan.Deferred.class, unavailable::requireAvailable);
        }
    }

    @Test
    void failureDuringRefreshedPlanIsDeferredRatherThanConstructionAttempt() {
        var pos = new BlockPos(4, 64, 4);
        var road = new RoadData(1, 0, List.of(), List.of(),
                List.of(new RoadSegmentPlacement(pos, List.of(pos)),
                        new RoadSegmentPlacement(pos.east(), List.of(pos.east())),
                        new RoadSegmentPlacement(pos.east(2), List.of(pos.east(2)))),
                List.of(), List.of(64, 64, 64), 11, 13);
        var plan = RoadChunkPlanCompiler.compile(CHUNK, List.of(road), false, 4, 2);
        assertFalse(plan.slices().isEmpty());
        assertEquals(DEFERRED, PathFeature.replayPlan(() -> plan, ignored -> {
            RoadChunkPlan.unavailable(CHUNK, 4, 3, RoadChunkPlan.Outcome.RETRYABLE_FAILURE).requireAvailable();
            return true;
        }));
    }
}
