package net.shiroha233.roadweaver.worldgen.road;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 闩锁控制真实会话交错，证明减少实际编译，不能只检查在途表里“有一项”。 */
class ChunkPlanFlightTest {
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final RoadWorldgenPlanCache.PlanConfig CONFIG = new RoadWorldgenPlanCache.PlanConfig(4, true);

    @Test void concurrentSameVersionCompilesOnlyOnce() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(8)) {
            RoadWorldgenPlanCache.Compiler compiler = (chunk, config, revision) -> {
                calls.incrementAndGet(); entered.countDown(); await(release);
                return RoadChunkPlan.empty(chunk, config.clearHeight(), revision);
            };
            var owner = workers.submit(() -> session.get(CHUNK, CONFIG, compiler, () -> 0));
            await(entered);
            var others = new java.util.ArrayList<Future<RoadChunkPlan>>();
            try {
                for (int i = 0; i < 7; i++)
                    others.add(workers.submit(() -> session.get(CHUNK, CONFIG, compiler, () -> 0)));
                until(() -> session.waitingCompilations() == 7);
                assertEquals(1, calls.get(), "同版本七个等待者不能再次执行编译正文");
            } finally { release.countDown(); }
            var result = owner.get(10, TimeUnit.SECONDS);
            for (var other : others) assertSame(result, other.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, session.runningCompilations());
        }
    }

    @Test void otherChunksAndConfigurationsNeverWaitBehindSlowCompile() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var slow = workers.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                entered.countDown(); await(release); return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            await(entered);
            try {
                var different = new RoadWorldgenPlanCache.PlanConfig(8, false);
                var configResult = workers.submit(() -> session.get(CHUNK, different,
                        (chunk, config, revision) -> {
                            assertEquals(different, config, "不同配置必须实际执行自己的编译");
                            return RoadChunkPlan.empty(chunk, config.clearHeight(), revision);
                        }, () -> 0));
                assertTrue(configResult.get(10, TimeUnit.SECONDS).isAvailable());
                var otherChunk = workers.submit(() -> session.get(new ChunkPos(7, -3), CONFIG,
                        (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0));
                assertEquals(new ChunkPos(7, -3), otherChunk.get(10, TimeUnit.SECONDS).chunkPos());
            } finally { release.countDown(); }
            slow.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void invalidationStartsNewVersionWithoutWaitingForOldCompiler() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var old = workers.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                entered.countDown(); await(release); return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            await(entered);
            var waiting = workers.submit(() -> session.get(CHUNK, CONFIG,
                    (a,b,c) -> fail("等待者不能编译旧版本"), () -> 0));
            until(() -> session.waitingCompilations() == 1);
            RoadChunkPlan current;
            try {
                session.invalidate(List.of(CHUNK.toLong()));
                current = workers.submit(() -> session.get(CHUNK, CONFIG,
                        (chunk, config, revision) -> RoadChunkPlan.empty(chunk, 4, revision), () -> 0))
                        .get(10, TimeUnit.SECONDS);
                assertTrue(current.revision() > 0);
            } finally { release.countDown(); }
            assertSame(current, old.get(10, TimeUnit.SECONDS));
            assertSame(current, waiting.get(10, TimeUnit.SECONDS));
            assertEquals(0, session.runningCompilations());
        }
    }

    @Test void ownerFailureWakesWaitersAndDoesNotPoisonRetry() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> session.get(CHUNK, CONFIG, (a,b,c) -> {
                entered.countDown(); await(release); throw new IllegalStateException("受控编译失败");
            }, () -> 0));
            await(entered);
            var waiting = workers.submit(() -> session.get(CHUNK, CONFIG, (a,b,c) -> fail("不能重复编译"), () -> 0));
            try { until(() -> session.waitingCompilations() == 1); }
            finally { release.countDown(); }
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                    () -> owner.get(10, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                    () -> waiting.get(10, TimeUnit.SECONDS)).getCause());
            assertEquals(0, session.runningCompilations());
            assertEquals(0, session.trackedChunks());
            assertTrue(session.get(CHUNK, CONFIG, (chunk, config, revision) ->
                    RoadChunkPlan.empty(chunk, 4, revision), () -> 0).isAvailable());
        }
    }

    @Test void interruptedWaiterLeavesOwnerAndOtherWaitersIntact() throws Exception {
        var session = new RoadWorldgenPlanCache.Session();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> session.get(CHUNK, CONFIG, (chunk, config, revision) -> {
                entered.countDown(); await(release); return RoadChunkPlan.empty(chunk, 4, revision);
            }, () -> 0));
            await(entered);
            var stopped = new CompletableFuture<Boolean>();
            var thread = new Thread(() -> {
                try {
                    assertThrows(RoadChunkPlan.Deferred.class, () -> session.get(CHUNK, CONFIG,
                            (a,b,c) -> fail("等待者不能执行编译"), () -> 0));
                    stopped.complete(Thread.currentThread().isInterrupted());
                } catch (Throwable error) { stopped.completeExceptionally(error); }
            });
            thread.start();
            try {
                until(() -> session.waitingCompilations() == 1);
                thread.interrupt();
                assertTrue(stopped.get(10, TimeUnit.SECONDS), "等待中断必须保留中断位");
                assertFalse(owner.isDone(), "取消等待者不能取消共享拥有者");
                assertEquals(1, session.runningCompilations());
            } finally { release.countDown(); thread.interrupt(); thread.join(10_000); }
            assertTrue(owner.get(10, TimeUnit.SECONDS).isAvailable());
        }
    }

    @Test void recursiveSameKeyFailsExplicitlyAndReleasesFlight() {
        var session = new RoadWorldgenPlanCache.Session();
        assertThrows(IllegalStateException.class, () -> session.get(CHUNK, CONFIG, (chunk, config, revision) ->
                session.get(CHUNK, CONFIG, (a,b,c) -> fail("不能重复编译"), () -> 0), () -> 0));
        assertEquals(0, session.runningCompilations());
        assertEquals(0, session.trackedChunks());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void until(BooleanSupplier predicate) throws InterruptedException {
        // 有截止时间的让出用于观察已进入的等待，不能用固定睡眠猜测线程调度。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(predicate.getAsBoolean(), "等待预期并发交错超时");
    }
}
