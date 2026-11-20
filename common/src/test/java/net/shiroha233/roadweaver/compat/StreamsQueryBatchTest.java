package net.shiroha233.roadweaver.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真正启动线程和请求，证明没有预取、每次独立超时及正常/异常都释放lane。 */
class StreamsQueryBatchTest {
    @Test void preservesDemandOrderAndSubmitsOneTaskPer64Queries() {
        var submissions = new AtomicInteger();
        var executor = executor(submissions);
        try {
            var calls = new ArrayList<Integer>();
            int total = StreamsQueryBatch.run(executor, 1000, () -> {
                int sum = 0;
                for (int i = 0; i < 130; i++) {
                    final int value = i;
                    sum += (int) query(() -> { calls.add(value); return value; });
                    assertEquals(i + 1, calls.size(), "调用方未请求的列不得预取");
                }
                return sum;
            });
            assertEquals(8385, total);
            assertEquals(3, submissions.get());
            assertEquals(java.util.stream.IntStream.range(0, 130).boxed().toList(), calls);
            assertEquals(9, executor.submit(() -> 9).get(1, TimeUnit.SECONDS));
            assertFalse(StreamsQueryBatch.active());
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { executor.shutdownNow(); }
    }

    @Test void cacheOnlyScopeDoesNotSubmitAndNestedScopeKeepsOuterOwnership() {
        var submissions = new AtomicInteger();
        var executor = executor(submissions);
        try {
            assertEquals(7, StreamsQueryBatch.run(executor, 1000, () -> 7));
            assertEquals(0, submissions.get());
            assertEquals(2, StreamsQueryBatch.run(executor, 1000, () ->
                    StreamsQueryBatch.run(executor, 1000, () -> query(() -> 2))));
            assertEquals(1, submissions.get());
        } finally { executor.shutdownNow(); }
    }

    @Test void failedRequestsCountTowardTheWorkerLimitAndLaterRequestsStillRun() {
        var submissions = new AtomicInteger();
        var executor = executor(submissions);
        try {
            StreamsQueryBatch.run(executor, 1000, () -> {
                for (int i = 0; i < 64; i++)
                    assertThrows(ExecutionException.class, () -> StreamsQueryBatch.query(() -> {
                        throw new IllegalArgumentException("外部形状故障");
                    }));
                assertEquals(4, query(() -> 4));
                return null;
            });
            assertEquals(2, submissions.get());
        } finally { executor.shutdownNow(); }
    }

    @Test void timeoutInterruptsTheActualRequestAndDoesNotPoisonNextScope() throws Exception {
        var executor = executor(new AtomicInteger());
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            StreamsQueryBatch.run(executor, 100, () -> {
                assertThrows(TimeoutException.class, () -> StreamsQueryBatch.query(() -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
                    return 0;
                }));
                return null;
            });
            assertEquals(0, entered.getCount());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(3, StreamsQueryBatch.run(executor, 1000, () -> query(() -> 3)));
        } finally { executor.shutdownNow(); }
    }

    @Test void callerInterruptionIsPreservedAndWorkerIsCancelled() throws Exception {
        var executor = executor(new AtomicInteger());
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var preserved = new CompletableFuture<Boolean>();
        var caller = new Thread(() -> {
            try {
                StreamsQueryBatch.run(executor, 10000, () -> query(() -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    finally { released.countDown(); }
                    return 0;
                }));
                preserved.complete(false);
            } catch (RuntimeException failure) { preserved.complete(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(preserved.get(1, TimeUnit.SECONDS));
            assertTrue(released.await(1, TimeUnit.SECONDS));
        } finally { caller.interrupt(); caller.join(1000); executor.shutdownNow(); }
    }

    @Test void validationFailureAndExecutorRejectionReleaseScopeOwnership() throws Exception {
        var executor = executor(new AtomicInteger());
        try {
            var failure = new IllegalStateException("校验拒绝");
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    StreamsQueryBatch.run(executor, 1000, () -> {
                        assertEquals(1, query(() -> 1));
                        throw failure;
                    })));
            assertEquals(8, executor.submit(() -> 8).get(1, TimeUnit.SECONDS));
            assertFalse(StreamsQueryBatch.active());
            executor.shutdownNow();
            assertThrows(CompletionException.class, () ->
                    StreamsQueryBatch.run(executor, 1000, () -> query(() -> 1)));
            assertFalse(StreamsQueryBatch.active());
        } finally { executor.shutdownNow(); }
    }

    @Test void idleBatchYieldsToQueuedPeersThenResumesWithoutLosingARequest() {
        var executor = executor(new AtomicInteger());
        try {
            StreamsQueryBatch.run(executor, 1000, () -> {
                assertEquals(1, query(() -> 1));
                var peer = executor.submit(() -> 9);
                try { assertEquals(9, peer.get(1, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new CompletionException(failure); }
                assertEquals(2, query(() -> 2), "旧工作任务退让后新请求必须重新认领");
                return null;
            });
        } finally { executor.shutdownNow(); }
    }

    @Test void queuedPeerRunsBetweenContinuousQueriesInsteadOfAfter64SlowCalls() {
        var executor = executor(new AtomicInteger());
        var events = new ArrayList<String>();
        try {
            StreamsQueryBatch.run(executor, 1000, () -> {
                assertEquals(1, query(() -> {
                    executor.submit(() -> events.add("peer"));
                    events.add("first");
                    return 1;
                }));
                assertEquals(2, query(() -> { events.add("second"); return 2; }));
                assertEquals(List.of("first", "peer", "second"), events);
                return null;
            });
        } finally { executor.shutdownNow(); }
    }

    private static Object query(Callable<Object> call) {
        try { return StreamsQueryBatch.query(call); }
        catch (Exception failure) { throw new CompletionException(failure); }
    }

    private static ThreadPoolExecutor executor(AtomicInteger submissions) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable action) {
                submissions.incrementAndGet();
                super.execute(action);
            }
        };
    }
}
