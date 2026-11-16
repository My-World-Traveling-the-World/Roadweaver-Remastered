/* 文件职责：用可控区块请求验证手绘预加载的进度和票据生命周期。 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RoutePreloadWorkTest {
    @Test
    void slowNearestChunkDoesNotBlockReadyChunksOrRefillingTheWindow() {
        var work = new RoutePreloadWork(List.of(10L, 20L, 30L, 40L), 2, 3);
        var fake = new Fake();
        work.tick(fake);
        assertEquals(List.of(10L, 20L), fake.requested);
        fake.ready(20L);
        work.tick(fake);
        // 10 还没结束：旧整批等待不会落 20，也不会发 30。
        assertEquals(List.of(20L), fake.placed);
        assertEquals(List.of(10L, 20L, 30L), fake.requested);
        assertEquals(Set.of(10L, 30L), fake.tickets);
        assertFalse(work.done());
        fake.ready(30L);
        work.tick(fake);
        fake.ready(10L);
        fake.ready(40L);
        work.tick(fake);
        assertEquals(List.of(20L, 30L, 10L, 40L), fake.placed);
        assertEquals(4, work.processed());
        assertTrue(work.done());
        assertEquals(Set.of(), fake.tickets);
    }

    @Test
    void stopReleasesAllTicketsAndIgnoresLateCompletion() {
        var work = new RoutePreloadWork(List.of(1L, 2L, 3L), 2, 3);
        var fake = new Fake();
        work.tick(fake);
        work.stop();
        assertEquals(Set.of(), fake.tickets);
        // Future 仍属于引擎；成功完成不能触发取消后的落块。
        assertTrue(fake.ready(1L));
        assertTrue(fake.ready(2L));
        work.tick(fake);
        assertEquals(List.of(1L, 2L), fake.requested);
        assertEquals(List.of(), fake.placed);
        assertTrue(work.done());
    }

    @Test
    void failedAndMissingFullChunksBothRetryWithFiniteAttempts() {
        var work = new RoutePreloadWork(List.of(1L, 2L), 2, 3);
        var fake = new Fake();
        work.tick(fake);
        fake.futures.get(1L).completeExceptionally(new IllegalStateException("fixture read failure"));
        // 引擎回了正常结束，但区块没进入 FULL；不能虚报已施工。
        fake.futures.get(2L).complete(null);
        work.tick(fake);
        fake.ready(1L);
        fake.futures.get(2L).complete(null);
        work.tick(fake);
        fake.futures.get(2L).complete(null);
        work.tick(fake);
        assertEquals(List.of(1L, 2L, 1L, 2L, 2L), fake.requested);
        assertEquals(List.of(1L), fake.placed);
        assertEquals(List.of(2L), work.failureKeys());
        assertEquals(1, work.failures());
        assertTrue(work.done());
        assertEquals(Set.of(), fake.tickets);
    }

    @Test
    void failedPlacementCannotReportSuccessOrKeepATicket() {
        var work = new RoutePreloadWork(List.of(7L), 1, 3);
        var fake = new Fake();
        work.tick(fake);
        fake.ready(7L);
        fake.failPlacement = true;
        assertThrows(IllegalStateException.class, () -> work.tick(fake));
        assertEquals(0, work.processed());
        assertEquals(Set.of(), fake.tickets);
    }

    @Test
    void pauseReleasesTicketsWithoutSpendingAttemptsOrForgettingProgress() {
        var work = new RoutePreloadWork(List.of(1L, 2L), 2, 1);
        var fake = new Fake();
        work.tick(fake);
        fake.ready(1L);
        work.tick(fake);
        work.pause();
        assertEquals(Set.of(), fake.tickets);
        assertEquals(List.of(1L), fake.placed);
        assertFalse(work.done());
        work.tick(fake);
        assertEquals(List.of(1L, 2L, 2L), fake.requested);
        fake.ready(2L);
        work.tick(fake);
        assertEquals(List.of(1L, 2L), fake.placed);
        assertEquals(0, work.failures());
    }

    private static final class Fake implements RoutePreloadWork.Access {
        final List<Long> requested = new ArrayList<>();
        final List<Long> placed = new ArrayList<>();
        final Map<Long, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        final Set<Long> tickets = new HashSet<>();
        final Set<Long> loaded = new HashSet<>();
        boolean failPlacement;

        @Override
        public RoutePreloadWork.Request request(long key) {
            assertTrue(tickets.add(key), "重复票据 " + key);
            requested.add(key);
            var future = new CompletableFuture<Void>();
            futures.put(key, future);
            return new RoutePreloadWork.Request(future, () -> assertTrue(tickets.remove(key), "重复释放 " + key));
        }

        @Override
        public boolean loaded(long key) { return loaded.contains(key); }

        @Override
        public void place(List<Long> ready) {
            // 先释放再施工会丢区块；夹具保持与服务端相同的票据契约。
            assertTrue(tickets.containsAll(ready));
            if (failPlacement) throw new IllegalStateException("fixture placement failure");
            placed.addAll(ready);
        }

        @Override
        public void failed(long key, RuntimeException failure, boolean finalAttempt) {}

        boolean ready(long key) {
            loaded.add(key);
            return futures.get(key).complete(null);
        }
    }
}
