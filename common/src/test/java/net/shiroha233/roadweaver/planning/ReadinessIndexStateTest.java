/* 文件职责：直接驱动实际 READY 门面的运行态，守住同 tick 准入、暂停和清理接线。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Entry;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Snapshot;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessIndexStateTest {
    @BeforeEach
    @AfterEach
    void clear() { RoadPlanningReadinessIndex.clearRuntime(); }

    @Test
    void sameTickAdmissionSeesGrowthAndCompletionWithoutWaitingForAnotherTick() throws Exception {
        Object world = world(1);
        long tick = RoadPlanningReadinessIndex.currentTickForTest();
        assertFalse(saturated(world));
        Object lastKey = null;
        Entry lastEntry = null;
        for (int i = 0; i < 512; i++) {
            lastKey = key(world, i);
            lastEntry = new Entry(State.QUEUED);
            assertNull(table().putIfAbsent(lastKey, lastEntry));
            collection("QUEUE").add(lastKey);
        }
        assertEquals(tick, RoadPlanningReadinessIndex.currentTickForTest());
        assertTrue(saturated(world), "同一 tick 中新增的 512 个区域必须立即计入，不能复用空队列缓存");
        assertTrue(table().transition(lastKey, lastEntry, State.READY));
        collection("QUEUE").remove(lastKey);
        assertFalse(saturated(world));
        assertTrue(table().transition(lastKey, lastEntry, State.QUEUED));
        collection("QUEUE").add(lastKey);
        assertTrue(saturated(world));
    }

    @Test
    void actualSupplySeparatesWorldsDispatchAndUniqueBacklog() throws Exception {
        Object world = world(1), other = world(2);
        for (int i = 0; i < 512; i++) {
            Object otherKey = key(other, i);
            assertNull(table().putIfAbsent(otherKey, new Entry(State.QUEUED)));
            collection("QUEUE").add(otherKey);
        }
        Object queued = key(world, 0), planning = key(world, 1), ready = key(world, 2), failed = key(world, 3);
        assertNull(table().putIfAbsent(queued, new Entry(State.QUEUED)));
        assertNull(table().putIfAbsent(planning, new Entry(State.PLANNING)));
        assertNull(table().putIfAbsent(ready, new Entry(State.READY)));
        assertNull(table().putIfAbsent(failed, new Entry(State.FAILED)));
        collection("QUEUE").add(queued);
        collection("ACTIVE").add(planning);
        assertEquals(new Snapshot(1, 1, 1, 1), supply(world));
        assertEquals(new Snapshot(512, 0, 0, 0), supply(other));
        assertFalse(saturated(world));
        assertTrue(saturated(other));
        table().markDispatched(ready);
        assertEquals(new Snapshot(1, 1, 0, 1), supply(world));
    }

    @Test
    void queueDuplicatesAndActiveOverlapCannotConsumeExtraAdmissionSlots() throws Exception {
        Object world = world(1);
        for (int i = 0; i < 511; i++) {
            Object key = key(world, i);
            table().putIfAbsent(key, new Entry(State.QUEUED));
            collection("QUEUE").add(key);
            collection("QUEUE").add(key);
            collection("ACTIVE").add(key);
        }
        assertEquals(1_022, collection("QUEUE").size());
        assertEquals(new Snapshot(511, 0, 0, 0), supply(world));
        assertFalse(saturated(world));
        Object last = key(world, 511);
        table().putIfAbsent(last, new Entry(State.PLANNING));
        collection("ACTIVE").add(last);
        assertEquals(new Snapshot(511, 1, 0, 0), supply(world));
        assertTrue(saturated(world));
    }

    @Test
    void actualPauseRequeuesPlanningAndKeepsOtherStatesAndDispatch() throws Exception {
        Object world = world(1), other = world(2);
        Object a = key(world, 0), b = key(world, 1), c = key(world, 2), d = key(world, 3), e = key(other, 0);
        Entry running = new Entry(State.PLANNING), second = new Entry(State.PLANNING);
        CompletableFuture<Void> future = new CompletableFuture<>();
        running.planningFuture = future;
        running.attempts = 2;
        table().putIfAbsent(a, running);
        table().putIfAbsent(b, new Entry(State.QUEUED));
        table().putIfAbsent(c, new Entry(State.READY));
        table().putIfAbsent(d, new Entry(State.FAILED));
        table().putIfAbsent(e, second);
        table().markDispatched(c);
        collection("ACTIVE").add(a);
        collection("ACTIVE").add(e);
        collection("QUEUE").add(b);
        RoadPlanningReadinessIndex.setRuntimePausedForDimension(true);
        assertTrue(future.isCancelled());
        assertNull(running.planningFuture);
        assertEquals(2, running.attempts);
        assertEquals(new Snapshot(2, 0, 0, 1), supply(world));
        assertEquals(new Snapshot(1, 0, 0, 0), supply(other));
        assertEquals(java.util.Set.of(a, b, e), new HashSet<>(collection("QUEUE")));
        assertTrue(collection("ACTIVE").isEmpty());
        assertEquals(3, collection("QUEUE").size());
        RoadPlanningReadinessIndex.setRuntimePausedForDimension(true);
        RoadPlanningReadinessIndex.setRuntimePausedForDimension(false);
        assertEquals(3, collection("QUEUE").size());
        assertEquals(State.READY, table().get(c).state());
        assertTrue(table().get(c).dispatched());
    }

    @Test
    void actualClearCancelsWorkAndLateCompletionCannotChangeNewSession() throws Exception {
        Object world = world(1), key = key(world, 2);
        Entry old = new Entry(State.PLANNING);
        old.planningFuture = new CompletableFuture<>();
        table().putIfAbsent(key, old);
        collection("ACTIVE").add(key);
        CountDownLatch callbackEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var late = old.planningFuture.handleAsync((value, failure) -> {
                callbackEntered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                try { return table().transition(key, old, State.READY); }
                catch (Exception e) { throw new AssertionError(e); }
            }, executor);
            try {
                RoadPlanningReadinessIndex.clearRuntime();
                assertTrue(old.planningFuture.isCancelled());
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, table().size());
                assertTrue(collection("ACTIVE").isEmpty());
                assertTrue(collection("QUEUE").isEmpty());
                Entry fresh = new Entry(State.QUEUED);
                assertNull(table().putIfAbsent(key, fresh));
                release.countDown();
                assertFalse(late.get(5, TimeUnit.SECONDS));
                assertSame(fresh, table().get(key));
                assertEquals(new Snapshot(1, 0, 0, 0), supply(world));
            } finally { release.countDown(); }
        }
    }

    // 只反射运行态边界，实际的饱和/供给/暂停/清理方法照常执行；无需伪造 ServerLevel。
    @SuppressWarnings("unchecked")
    private static ReadinessRegionTable<Object, Object> table() throws Exception {
        var field = RoadPlanningReadinessIndex.class.getDeclaredField("ENTRIES");
        field.setAccessible(true);
        return (ReadinessRegionTable<Object, Object>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> collection(String name) throws Exception {
        var field = RoadPlanningReadinessIndex.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Collection<Object>) field.get(null);
    }

    private static Object world(long seed) throws Exception {
        var constructor = Class.forName(RoadPlanningReadinessIndex.class.getName() + "$WorldKey")
                .getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance("minecraft:overworld", seed);
    }

    private static Object key(Object world, int x) throws Exception {
        var constructor = Class.forName(RoadPlanningReadinessIndex.class.getName() + "$RegionKey")
                .getDeclaredConstructor(world.getClass(), int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(world, x, -1);
    }

    private static Object invoke(String name, Object world) throws Exception {
        var method = RoadPlanningReadinessIndex.class.getDeclaredMethod(name, world.getClass());
        method.setAccessible(true);
        return method.invoke(null, world);
    }
    private static boolean saturated(Object world) throws Exception { return (boolean) invoke("backlogSaturated", world); }
    private static Snapshot supply(Object world) throws Exception { return (Snapshot) invoke("supplySnapshot", world); }
}
