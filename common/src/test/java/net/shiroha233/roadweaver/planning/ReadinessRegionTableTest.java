/* 文件职责：以独立全量模型对拍区域状态，不让空集合或仅检查非负掩盖转换丢失。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Entry;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Snapshot;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessRegionTableTest {
    private record Key(int world, int region) { }
    private static final class Model {
        final Entry entry;
        State state;
        boolean dispatched;
        Model(Entry entry, State state) { this.entry = entry; this.state = state; }
    }

    @ParameterizedTest
    @EnumSource(State.class)
    void everyStateAndDispatchOrderMatchesIndependentFullScan(State initial) {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        Map<Key, Model> expected = new HashMap<>();
        for (int world = 0; world < 3; world++) {
            Key key = new Key(world, 17);
            Entry entry = new Entry(initial);
            assertNull(table.putIfAbsent(key, entry));
            expected.put(key, new Model(entry, initial));
        }
        assertModel(table, expected);
        for (State next : State.values()) {
            for (var item : expected.entrySet()) {
                assertTrue(table.transition(item.getKey(), item.getValue().entry, next));
                item.getValue().state = next;
                assertModel(table, expected);
                // 通知允许先于 READY 到达；重复通知和重复转换不能重复扣减。
                table.markDispatched(item.getKey());
                table.markDispatched(item.getKey());
                item.getValue().dispatched = true;
                assertTrue(table.transition(item.getKey(), item.getValue().entry, next));
                assertModel(table, expected);
            }
        }
        assertEquals(3, table.size());
        assertEquals(new Snapshot(0, 0, 0, 0), table.snapshot(99));
    }

    @Test
    void retriesPauseResumeAndFailureUseTheSameStateAccounting() {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        Key a = new Key(1, 0), b = new Key(1, 1), c = new Key(2, 0);
        Entry first = new Entry(State.QUEUED), other = new Entry(State.READY);
        assertNull(table.putIfAbsent(a, first));
        assertNull(table.putIfAbsent(b, other));
        assertNull(table.putIfAbsent(c, new Entry(State.FAILED)));
        assertSame(first, table.putIfAbsent(a, new Entry(State.READY)));
        assertEquals(new Snapshot(1, 0, 1, 0), table.snapshot(1));
        assertTrue(table.transition(a, first, State.PLANNING));
        assertEquals(new Snapshot(0, 1, 1, 0), table.snapshot(1));
        // 暂停只把在途规划放回队列；恢复、重试和最终失败沿用同一个条目。
        assertTrue(table.transition(a, first, State.QUEUED));
        assertEquals(new Snapshot(1, 0, 1, 0), table.snapshot(1));
        assertTrue(table.transition(a, first, State.PLANNING));
        assertTrue(table.transition(a, first, State.FAILED));
        assertEquals(new Snapshot(0, 0, 1, 1), table.snapshot(1));
        assertTrue(table.transition(a, first, State.QUEUED));
        assertTrue(table.transition(a, first, State.READY));
        assertEquals(new Snapshot(0, 0, 2, 0), table.snapshot(1));
        table.markDispatched(a);
        assertEquals(new Snapshot(0, 0, 1, 0), table.snapshot(1));
        assertEquals(new Snapshot(0, 0, 0, 1), table.snapshot(2));
    }

    @Test
    void randomizedMutationsMatchFullScanAfterEveryOperation() {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        Map<Key, Model> expected = new HashMap<>();
        Random random = new Random(0x40_20261117L);
        EnumSet<State> visited = EnumSet.noneOf(State.class);
        int transitions = 0, duplicates = 0, notifications = 0, clears = 0;
        for (int i = 0; i < 12_000; i++) {
            Key key = new Key(random.nextInt(3), random.nextInt(31));
            State state = State.values()[random.nextInt(State.values().length)];
            visited.add(state);
            Model old = expected.get(key);
            switch (random.nextInt(5)) {
                case 0 -> {
                    Entry fresh = new Entry(state);
                    assertSame(old == null ? null : old.entry, table.putIfAbsent(key, fresh));
                    if (old == null) expected.put(key, new Model(fresh, state));
                    else duplicates++;
                }
                case 1, 2 -> {
                    Entry target = old == null ? new Entry(State.QUEUED) : old.entry;
                    assertEquals(old != null, table.transition(key, target, state));
                    if (old != null) { old.state = state; transitions++; }
                }
                case 3 -> {
                    table.markDispatched(key);
                    if (old != null) { old.dispatched = true; notifications++; }
                }
                default -> {
                    if (i % 197 == 0) { table.clear(); expected.clear(); clears++; }
                }
            }
            assertModel(table, expected);
        }
        // 数量下界只证明夹具真的触发过每类路径；正确性由逐步精确对拍判断。
        assertEquals(EnumSet.allOf(State.class), visited);
        assertTrue(transitions > 1_000);
        assertTrue(duplicates > 100);
        assertTrue(notifications > 100);
        assertTrue(clears > 0);
    }

    @Test
    void clearedEntriesCannotChangeReplacementAndSnapshotsStayImmutable() {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        Key key = new Key(1, -7);
        Entry old = new Entry(State.PLANNING);
        assertNull(table.putIfAbsent(key, old));
        Snapshot before = table.snapshot(1);
        var captured = table.values();
        table.clear();
        assertEquals(0, table.size());
        assertEquals(new Snapshot(0, 0, 0, 0), table.snapshot(1));
        Entry fresh = new Entry(State.QUEUED);
        assertNull(table.putIfAbsent(key, fresh));
        assertFalse(table.transition(key, old, State.READY));
        assertFalse(table.transition(new Key(2, 77), old, State.FAILED));
        assertSame(fresh, table.get(key));
        assertEquals(State.QUEUED, fresh.state());
        assertEquals(new Snapshot(1, 0, 0, 0), table.snapshot(1));
        assertEquals(new Snapshot(0, 1, 0, 0), before);
        assertEquals(java.util.List.of(old), captured);
        assertThrows(UnsupportedOperationException.class, captured::clear);
    }

    @Test
    void simultaneousTransitionsKeepWholeSnapshotsAndWorldIsolation() throws Exception {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        int workers = 6, perWorker = 100;
        for (int i = 0; i < workers * perWorker; i++) {
            assertNull(table.putIfAbsent(new Key(1, i), new Entry(State.QUEUED)));
        }
        assertNull(table.putIfAbsent(new Key(2, 0), new Entry(State.FAILED)));
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(workers);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < workers; worker++) {
                int first = worker * perWorker;
                futures.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int i = first; i < first + perWorker; i++) {
                            Key key = new Key(1, i);
                            Entry entry = table.get(key);
                            assertTrue(table.transition(key, entry, State.PLANNING));
                            assertTrue(table.transition(key, entry, State.READY));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } finally { done.countDown(); }
                }));
            }
            start.countDown();
            int reads = 0;
            do {
                Snapshot sample = table.snapshot(1);
                assertEquals(workers * perWorker, sample.pending() + sample.readyAhead());
                assertEquals(0, sample.failed());
                reads++;
            } while (done.getCount() != 0 && reads < 20_000);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(new Snapshot(0, 0, 600, 0), table.snapshot(1));
            assertEquals(new Snapshot(0, 0, 0, 1), table.snapshot(2));
        }
    }

    @Test
    void repeatedSnapshotsDoNotRevisitAnyHistoricalEntry() {
        java.util.concurrent.atomic.AtomicInteger worldReads = new java.util.concurrent.atomic.AtomicInteger();
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(key -> {
            worldReads.incrementAndGet();
            return key.world;
        });
        for (int i = 0; i < 20_000; i++) {
            assertNull(table.putIfAbsent(new Key(i % 3, i), new Entry(State.READY)));
        }
        assertNull(table.putIfAbsent(new Key(0, -1), new Entry(State.QUEUED)));
        assertTrue(worldReads.get() >= 20_001);
        worldReads.set(0);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(new Snapshot(1, 0, 6_667, 0), table.snapshot(0));
            assertEquals(new Snapshot(0, 0, 6_667, 0), table.snapshot(1));
            assertEquals(new Snapshot(0, 0, 6_666, 0), table.snapshot(2));
        }
        // 行为对拍已独立验证数值；这里精确约束重复读取的工作量不随历史区域增长。
        assertEquals(0, worldReads.get());
    }

    @Test
    void cancellationCallbacksCanUseTheTableOutsideItsLifecycleSnapshot() throws Exception {
        ReadinessRegionTable<Key, Integer> table = new ReadinessRegionTable<>(Key::world);
        Key key = new Key(1, 3);
        Entry entry = new Entry(State.PLANNING);
        entry.planningFuture = new CompletableFuture<>();
        assertNull(table.putIfAbsent(key, entry));
        try (var executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Snapshot> callbackRead = new CompletableFuture<>();
            entry.planningFuture.whenComplete((v, error) -> {
                try { callbackRead.complete(executor.submit(() -> table.snapshot(1)).get(3, TimeUnit.SECONDS)); }
                catch (Exception e) { callbackRead.completeExceptionally(e); }
            });
            for (Entry current : table.values()) current.planningFuture.cancel(true);
            assertTrue(entry.planningFuture.isCancelled());
            // 必须读到回调内部的结果，不能把依赖 Future 的取消异常当成查询成功。
            assertEquals(new Snapshot(0, 1, 0, 0), callbackRead.get(5, TimeUnit.SECONDS));
        }
    }

    private static void assertModel(ReadinessRegionTable<Key, Integer> table, Map<Key, Model> expected) {
        assertEquals(expected.size(), table.size());
        for (int world = 0; world < 3; world++) {
            int queued = 0, planning = 0, ahead = 0, failed = 0;
            for (var item : expected.entrySet()) {
                Model model = item.getValue();
                assertSame(model.entry, table.get(item.getKey()));
                assertEquals(model.state, model.entry.state());
                assertEquals(model.dispatched, model.entry.dispatched());
                if (item.getKey().world != world) continue;
                if (model.state == State.QUEUED) queued++;
                if (model.state == State.PLANNING) planning++;
                if (model.state == State.READY && !model.dispatched) ahead++;
                if (model.state == State.FAILED) failed++;
            }
            Snapshot actual = table.snapshot(world);
            assertEquals(new Snapshot(queued, planning, ahead, failed), actual);
            assertEquals(queued + planning, actual.pending());
        }
    }
}
