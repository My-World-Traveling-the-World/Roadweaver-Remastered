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
    /** 直接守住生产使用的三参入口，避免只测可注入预算却漏掉默认接线。 */
    @Test
    void productionConstructorConsumesAtMostEightReadyChunksPerTick() {
        var keys = java.util.stream.LongStream.rangeClosed(1, 10).boxed().toList();
        var work = new RoutePreloadWork<Full>(keys, 32, 1);
        var fake = new Fake();
        work.tick(fake);
        keys.forEach(fake::ready);
        work.tick(fake);
        assertEquals(keys.subList(0, 8), fake.placed);
        assertEquals(Set.of(9L, 10L), fake.tickets);
        assertEquals(8, work.processed());
        work.tick(fake);
        assertEquals(keys, fake.placed);
        assertEquals(10, work.processed());
        assertTrue(work.done());
        assertEquals(0, work.failures());
    }

    @Test
    void readyBurstKeepsTicketsAndRefreshesHandlesWithoutSpendingAttempts() {
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L, 3L, 4L), 4, 1, 1);
        var fake = new Fake();
        work.tick(fake);
        for (long key = 1; key <= 4; key++) fake.ready(key);
        for (long key = 1; key <= 4; key++) {
            // 模拟下一 tick 的 FULL 句柄变化，禁止跨 tick 缓存第一次确认的对象。
            fake.loaded.put(key, new Full(key));
            work.tick(fake);
            assertEquals((int) key, fake.placed.size());
            assertEquals((int) key, work.processed());
            assertEquals(4 - (int) key, fake.tickets.size());
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), fake.placed);
        assertEquals(fake.placed, fake.requested, "预算等待不得重新请求或消耗重试");
        assertEquals(0, work.failures());
        assertTrue(work.done());
    }

    @Test
    void stoppingAReadyBurstReleasesDeferredTicketsWithoutPlacingThem() {
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L, 3L), 3, 1, 1);
        var fake = new Fake();
        work.tick(fake);
        for (long key = 1; key <= 3; key++) fake.ready(key);
        work.tick(fake);
        assertEquals(Set.of(2L, 3L), fake.tickets);
        work.stop();
        work.tick(fake);
        assertTrue(fake.tickets.isEmpty());
        assertEquals(List.of(1L), fake.placed);
        assertEquals(1, work.processed());
    }

    @Test
    void failedFutureBeyondPlacementBudgetIsReleasedImmediately() {
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L, 3L), 3, 1, 1);
        var fake = new Fake();
        work.tick(fake);
        fake.ready(1L);
        fake.ready(2L);
        fake.futures.get(3L).completeExceptionally(new IllegalStateException("fixture"));
        work.tick(fake);
        assertEquals(List.of(1L), fake.placed);
        assertEquals(Set.of(2L), fake.tickets);
        assertEquals(List.of(3L), work.failureKeys());
        fake.loaded.put(2L, new Full(2L));
        work.tick(fake);
        assertEquals(List.of(1L, 2L), fake.placed);
        assertTrue(work.done());
    }

    @Test
    void slowNearestChunkDoesNotBlockReadyChunksOrRefillingTheWindow() {
        var work = new RoutePreloadWork<Full>(List.of(10L, 20L, 30L, 40L), 2, 3);
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
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L, 3L), 2, 3);
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
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L), 2, 3);
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
        var work = new RoutePreloadWork<Full>(List.of(7L), 1, 3);
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
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L), 2, 1);
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

    @Test
    void loadedButUnsettledChunkRetriesWithoutRepeatingSettledNeighbor() {
        var work = new RoutePreloadWork<Full>(List.of(1L, 2L), 2, 2);
        var fake = new Fake();
        fake.unsettled.add(1L);
        work.tick(fake);
        fake.ready(1L);
        fake.ready(2L);
        var firstHandle = fake.loaded(1L);
        work.tick(fake);
        assertEquals(1, work.processed());
        assertEquals(0, work.failures());
        assertEquals(List.of(1L, 2L, 1L), fake.requested);
        assertEquals(List.of(1L, 2L), fake.placed);
        assertEquals(Set.of(1L), fake.tickets);
        assertFalse(work.done());

        fake.ready(1L);
        assertNotSame(firstHandle, fake.loaded(1L), "重试应使用当次 FULL 句柄");
        work.tick(fake);
        assertTrue(work.done());
        assertEquals(1, work.processed());
        assertEquals(List.of(1L), work.failureKeys());
        assertEquals(List.of(1L, 2L, 1L), fake.placed);
        assertTrue(fake.tickets.isEmpty());
    }

    private record Full(long key) {}

    private static final class Fake implements RoutePreloadWork.Access<Full> {
        final List<Long> requested = new ArrayList<>();
        final List<Long> placed = new ArrayList<>();
        final Map<Long, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        final Set<Long> tickets = new HashSet<>();
        final Map<Long, Full> loaded = new LinkedHashMap<>();
        final Set<Long> unsettled = new HashSet<>();
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
        public Full loaded(long key) { return loaded.get(key); }

        @Override
        public Set<Long> place(Map<Long, Full> ready) {
            // 不能从旧 tick 捞一个已经过期的 FULL 句柄，也不能在施工前撤票。
            assertTrue(tickets.containsAll(ready.keySet()));
            ready.forEach((key, chunk) -> assertSame(loaded.get(key), chunk));
            if (failPlacement) throw new IllegalStateException("fixture placement failure");
            placed.addAll(ready.keySet());
            var finished = new HashSet<>(ready.keySet());
            finished.removeAll(unsettled);
            return finished;
        }

        @Override
        public void failed(long key, RuntimeException failure, boolean finalAttempt) {}

        boolean ready(long key) {
            loaded.put(key, new Full(key));
            return futures.get(key).complete(null);
        }
    }
}
