package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 故障恢复穿过正式读端口、完整编译和会话缓存，不只测试手工构造的状态枚举。 */
class RoadPlanFailureRecoveryTest {
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final RoadWorldgenPlanCache.PlanConfig CONFIG = new RoadWorldgenPlanCache.PlanConfig(4, true);

    @Test
    void failedCompilationBacksOffThenRetriesActualRoadInsteadOfCachingEmpty() {
        var session = new RoadWorldgenPlanCache.Session();
        var queries = new AtomicInteger();
        long[] clock = {0};
        var road = road();
        var query = new RoadChunkPlanCompiler.Query(
            (minX, minZ, maxX, maxZ) -> {
                assertEquals(List.of(-32, -32, 47, 47), List.of(minX, minZ, maxX, maxZ));
                if (queries.getAndIncrement() == 0) throw new IllegalStateException("临时读取故障");
                return List.of(road);
            }, chunk -> List.of(road));
        RoadWorldgenPlanCache.Compiler compiler = (chunk, config, revision) ->
                RoadWorldgenPlanCache.compile(chunk, config, revision, query);
        var failed = session.get(CHUNK, CONFIG, compiler, () -> clock[0]);
        assertEquals(RoadChunkPlan.Outcome.RETRYABLE_FAILURE, failed.outcome());
        clock[0] = 4_999_999_999L;
        assertSame(failed, session.get(CHUNK, CONFIG, compiler, () -> clock[0]));
        assertEquals(1, queries.get());
        clock[0]++;
        var restored = session.get(CHUNK, CONFIG, compiler, () -> clock[0]);
        assertEquals(RoadChunkPlan.Outcome.READY, restored.outcome());
        assertFalse(restored.slices().isEmpty(), "M2：真正恢复后的道路必须有施工切片");
        assertEquals(road, restored.slices().getFirst().road());
        assertSame(restored, session.get(CHUNK, CONFIG, compiler, () -> clock[0]));
        assertEquals(2, queries.get());
        session.invalidate(List.of(CHUNK.toLong()));
        var revised = session.get(CHUNK, CONFIG, compiler, () -> clock[0]);
        assertNotEquals(restored.revision(), revised.revision());
        assertEquals(RoadChunkPlan.Outcome.READY, revised.outcome());
        assertEquals(3, queries.get());
    }

    @Test
    void exactShardFallbackAndConfirmedEmptyRemainDifferentFromFailure() {
        var fallback = RoadWorldgenPlanCache.compile(CHUNK, CONFIG, 7, query(List.of(), List.of(road())));
        assertEquals(RoadChunkPlan.Outcome.READY, fallback.outcome());
        assertFalse(fallback.slices().isEmpty());
        var empty = RoadWorldgenPlanCache.compile(CHUNK, CONFIG, 8, query(List.of(), List.of()));
        assertEquals(RoadChunkPlan.Outcome.EMPTY_CONFIRMED, empty.outcome());
        assertTrue(empty.slices().isEmpty());
        var config = new ModConfig();
        config.roadAppearance().setRoadsEnabled(false);
        assertEquals(RoadChunkPlan.Outcome.DISABLED, RoadWorldgenPlanCache.get(null, CHUNK, config).outcome());
    }

    private static RoadChunkPlanCompiler.Query query(List<RoadData> rectangle, List<RoadData> chunk) {
        return new RoadChunkPlanCompiler.Query((x0, z0, x1, z1) -> rectangle, pos -> chunk);
    }

    private static RoadData road() {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for (int x = 2; x < 12; x++) {
            var p = new BlockPos(x, 64, 5);
            stations.add(new RoadSegmentPlacement(p, List.of(p)));
        }
        return new RoadData(1, 0, List.of(), List.of(), stations, List.of(),
                java.util.Collections.nCopies(stations.size(), 64), 11, 13);
    }
}
