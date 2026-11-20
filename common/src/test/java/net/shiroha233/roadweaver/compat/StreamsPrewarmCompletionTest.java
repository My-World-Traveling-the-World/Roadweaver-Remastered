/* 文件职责：复现真实预热协调器的即时失败、入表后失败、拒绝和代次取消。 */
package net.shiroha233.roadweaver.compat;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 注入执行器控制完成相对入表的先后，不靠多跑几次碰竞争窗口。 */
class StreamsPrewarmCompletionTest {
    @Test void immediateFailureIsEvictedAfterInsertionAndCanRetry() {
        var builds = new AtomicInteger();
        var coordinator = create(Runnable::run, builds, true, generation());
        var first = schedule(coordinator).getFirst();
        assertTrue(first.isCompletedExceptionally());
        var retry = schedule(coordinator).getFirst();
        assertNotSame(first, retry);
        assertDoesNotThrow(retry::join);
        assertSame(retry, schedule(coordinator).getFirst(), "成功仍共享，不重做构建");
        assertEquals(2, builds.get());
    }

    @Test void queuedFailureIsSharedWhilePendingThenEvicted() {
        var tasks = new ArrayList<Runnable>();
        var builds = new AtomicInteger();
        var coordinator = create(tasks::add, builds, true, generation());
        var first = schedule(coordinator).getFirst();
        assertSame(first, schedule(coordinator).getFirst());
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertTrue(first.isCompletedExceptionally());
        var retry = schedule(coordinator).getFirst();
        assertNotSame(first, retry);
        assertFalse(retry.isDone());
        tasks.removeFirst().run();
        assertDoesNotThrow(retry::join);
        assertEquals(2, builds.get());
    }

    @Test void rejectedSubmissionDoesNotPoisonNextAttempt() {
        var attempts = new AtomicInteger();
        var builds = new AtomicInteger();
        var coordinator = create(task -> {
            if (attempts.incrementAndGet() == 1) throw new RejectedExecutionException("队列拒绝");
            task.run();
        }, builds, false, generation());
        assertThrows(RejectedExecutionException.class, () -> schedule(coordinator));
        assertDoesNotThrow(schedule(coordinator).getFirst()::join);
        assertEquals(2, attempts.get());
        assertEquals(1, builds.get());
    }

    @Test void cancellationAndObsoleteGenerationDoNotBuildOrRemoveFreshEntry() throws Exception {
        var tasks = new ArrayList<Runnable>();
        var builds = new AtomicInteger();
        var coordinator = create(tasks::add, builds, false, generation());
        var old = schedule(coordinator).getFirst();
        var clear = coordinator.getClass().getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(coordinator);
        assertTrue(old.isCancelled());
        var fresh = schedule(coordinator).getFirst();
        tasks.removeFirst().run();
        tasks.removeFirst().run();
        assertDoesNotThrow(fresh::join);
        assertSame(fresh, schedule(coordinator).getFirst());
        assertEquals(1, builds.get());
        var obsolete = create(Runnable::run, builds, false, generation() - 1);
        assertDoesNotThrow(schedule(obsolete).getFirst()::join);
        assertEquals(1, builds.get(), "旧代次不得进入提供方");
    }

    private static long generation() {
        try {
            var field = StreamsReflowingCompat.class.getDeclaredField("PREWARM_GENERATION");
            field.setAccessible(true);
            return ((AtomicLong) field.get(null)).get();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object create(Executor executor, AtomicInteger builds, boolean failFirst, long generation) {
        var access = new StreamsPlanAccess() {
            public Object open(net.minecraft.server.level.ServerLevel level) { return null; }
            public StreamsReflowingCompat.RiverColumn column(Object session, int x, int z) { throw new AssertionError(); }
            public StreamsReflowingCompat.LakeColumn lake(Object session, int x, int z) { throw new AssertionError(); }
            public List<?> platesNear(Object session, double x, double z, double radius) { throw new AssertionError(); }
            public void prewarm(Object session, Object plate) {
                assertEquals("板块", plate);
                if (builds.incrementAndGet() == 1 && failFirst) throw new IllegalStateException("首次构建失败");
            }
        };
        try {
            var type = Class.forName(StreamsReflowingCompat.class.getName() + "$PrewarmCoordinator");
            var constructor = type.getDeclaredConstructor(StreamsPlanAccess.class, Object.class, long.class, Executor.class);
            constructor.setAccessible(true);
            return constructor.newInstance(access, new Object(), generation, executor);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static List<CompletableFuture<Void>> schedule(Object coordinator) {
        try {
            var method = coordinator.getClass().getDeclaredMethod("schedule", Iterable.class);
            method.setAccessible(true);
            return (List<CompletableFuture<Void>>) method.invoke(coordinator, List.of("板块"));
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
