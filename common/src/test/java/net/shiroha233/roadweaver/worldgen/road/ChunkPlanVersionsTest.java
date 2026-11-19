package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 缓存容量之外，在途版本还必须保留到真正退出，不能靠清表制造 ABA。 */
class ChunkPlanVersionsTest {
    private static final RoadWorldgenPlanCache.PlanConfig CONFIG = new RoadWorldgenPlanCache.PlanConfig(4, true);
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);

    @Test
    void historicalInvalidationsWithoutCachedOrRunningWorkRetainNoKeys() {
        var session = new RoadWorldgenPlanCache.Session();
        var keys = new ArrayList<Long>();
        for (int i = 0; i < 5000; i++) keys.add(ChunkPos.asLong(i * 10, 0));
        session.invalidate(keys);
        assertEquals(0, session.trackedChunks(), "125000 个历史邻区版本不能成为永久驻留项");
    }

    @Test
    void metadataFollowsBothEmptyAndFailedPlanEviction() {
        var session = new RoadWorldgenPlanCache.Session();
        for (int i = 0; i < 17000; i++)
            session.get(new ChunkPos(i, 0), CONFIG, (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
        assertEquals(16384, session.trackedChunks());
        session.invalidate();
        assertEquals(0, session.trackedChunks());
        for (int i = 0; i < 1200; i++)
            session.get(new ChunkPos(i, 0), CONFIG, (chunk, config, revision) ->
                    RoadChunkPlan.unavailable(chunk, 4, revision, RoadChunkPlan.Outcome.RETRYABLE_FAILURE), () -> 0);
        assertEquals(1024, session.trackedChunks());
        session.invalidate();
        assertEquals(0, session.trackedChunks());
    }

    @Test
    void localInvalidationPinsRunningVersionAndRejectsLatePublish() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                if (calls.incrementAndGet() == 1) { entered.countDown(); await(resume); }
                return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                session.invalidate(List.of(CHUNK.toLong()));
                assertEquals(1, session.trackedChunks(), "失效不能丢掉尚未退出的旧认领");
            } finally { resume.countDown(); }
            var result = pending.get(10, TimeUnit.SECONDS);
            assertTrue(result.revision() > 0);
            assertEquals(2, calls.get(), "旧结果必须重新编译，不能以新版本名义发布");
            assertSame(result, session.get(CHUNK, CONFIG, (a,b,c) -> fail("正确结果已经缓存"), () -> 0));
            session.invalidate(List.of(CHUNK.toLong()));
            assertEquals(0, session.trackedChunks());
        }
    }

    @Test
    void globalInvalidationCannotBeUndoneByAnOlderCompiler() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                entered.countDown(); await(resume); return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            RoadChunkPlan current;
            try {
                session.invalidate();
                current = session.get(CHUNK, CONFIG, (chunk, config, revision) ->
                        RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
                assertTrue(current.revision() > 0);
            } finally { resume.countDown(); }
            assertSame(current, old.get(10, TimeUnit.SECONDS));
            assertEquals(1, session.trackedChunks());
        }
    }

    @Test
    void unrelatedInvalidationKeepsHotPlanAndThrownCompileReleasesClaim() {
        var session = new RoadWorldgenPlanCache.Session();
        var hot = session.get(CHUNK, CONFIG, (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0);
        session.invalidate(List.of(ChunkPos.asLong(100, 100)));
        assertSame(hot, session.get(CHUNK, CONFIG, (a,b,c) -> fail("局部失效不能迫使无关热点重编译"), () -> 0));
        assertThrows(IllegalStateException.class, () -> session.get(new ChunkPos(-100, 0), CONFIG,
                (a,b,c) -> { throw new IllegalStateException("非正常退出"); }, () -> 0));
        assertEquals(1, session.trackedChunks(), "失败退出不能泄漏额外认领");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
