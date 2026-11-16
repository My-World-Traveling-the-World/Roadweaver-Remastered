/* 文件职责：直接验两轮搜索共用的生产提交器，避免 INITIAL 物理池被当成预热预算豁免。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.runtime.BackgroundCpuWork;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CoarsePathTasksTest {
    private static final List<BlockPos> PATH = List.of(new BlockPos(-16, 63, -8), new BlockPos(-8, 64, 0), new BlockPos(0, 65, 8));
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private ExecutorService workers;
    private int savedCap, savedDuty;
    private boolean savedGui, savedMap, savedPreflight, savedExclusive;
    private List<RoadLoadSignals> sources;

    @BeforeEach void isolate() throws Exception {
        assertEquals(0, active().get());
        sources = RoadLoadSignalsRegistry.sources(); sources.forEach(s -> RoadLoadSignalsRegistry.unregister(s.ownerId()));
        var config = ConfigService.get().performance();
        savedCap = config.gameplayThreadCap(); savedDuty = config.threadDutyCycle();
        config.setGameplayThreadCap(1); config.setThreadDutyCycle(100);
        savedMap = RuntimeLoadPolicy.isMapScreenOpen(); RuntimeLoadPolicy.setMapScreenOpen(false);
        savedGui = RuntimeLoadPolicy.isGuiScreenOpen(); savedPreflight = RuntimeLoadPolicy.isChunkyPreflightActive();
        savedExclusive = RuntimeLoadPolicy.isChunkyExclusiveActive();
        RuntimeLoadPolicy.setGuiScreenOpen(false); RuntimeLoadPolicy.setChunkyPreflightActive(false);
        RuntimeLoadPolicy.setChunkyExclusiveActive(false); expirePause();
        workers = Executors.newFixedThreadPool(2, job -> {
            Thread thread = new Thread(job, "coarse-budget-test-" + threads.size()); threads.add(thread); return thread;
        });
    }

    @AfterEach void restore() throws Exception {
        Thread.interrupted();
        workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        ConfigService.get().performance().setGameplayThreadCap(savedCap);
        ConfigService.get().performance().setThreadDutyCycle(savedDuty);
        RuntimeLoadPolicy.setMapScreenOpen(savedMap);
        RuntimeLoadPolicy.setGuiScreenOpen(savedGui); RuntimeLoadPolicy.setChunkyPreflightActive(savedPreflight);
        RuntimeLoadPolicy.setChunkyExclusiveActive(savedExclusive); expirePause();
        RoadLoadSignalsRegistry.sources().forEach(s -> RoadLoadSignalsRegistry.unregister(s.ownerId()));
        sources.forEach(RoadLoadSignalsRegistry::register);
        int leaked = active().getAndSet(0);
        assertEquals(0, leaked, "失败或取消不能泄漏实际工作许可");
    }

    @Test void pathAndCheckedFailureAreDeliveredWithoutChangingResults() throws Exception {
        var tasks = new CoarsePathTasks(workers);
        assertSame(PATH, tasks.submit(() -> { assertEquals(1, active().get()); return PATH; }).get(3, TimeUnit.SECONDS));
        var fault = new java.io.IOException("search failed");
        var failure = tasks.submit(() -> { throw fault; });
        assertSame(fault, assertThrows(ExecutionException.class, () -> failure.get(3, TimeUnit.SECONDS)).getCause());
        assertEquals(0, active().get());
    }

    @Test void queuedPathsRespectOneSharedWorkerAndAllResultsSurvive() throws Exception {
        var tasks = new CoarsePathTasks(workers);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger();
        List<Future<List<BlockPos>>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) results.add(tasks.submit(() -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max); calls.incrementAndGet(); entered.countDown();
            try { await(release); return PATH; } finally { running.decrementAndGet(); }
        }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        awaitPark(threads.get(1));
        assertEquals(1, calls.get()); assertEquals(1, peak.get());
        release.countDown();
        for (var result : results) assertSame(PATH, result.get(3, TimeUnit.SECONDS));
        assertEquals(8, calls.get()); assertEquals(1, peak.get());
    }

    @Test void foregroundCancellationActuallyFreesTheWorker() throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        AtomicInteger calls = new AtomicInteger();
        var result = new CoarsePathTasks(workers).submit(() -> { calls.incrementAndGet(); return PATH; });
        awaitPark(threads.getFirst());
        assertTrue(result.cancel(true));
        // Future 被标取消还不够；线程池必须真正终止，证明没有遗留暂停循环。
        workers.shutdown();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(0, calls.get()); assertTrue(result.isCancelled());
    }

    @Test void epochIsCapturedBeforeQueueingAndFreshSubmissionCanStillRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) workers.submit(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        AtomicInteger calls = new AtomicInteger();
        var tasks = new CoarsePathTasks(workers);
        var old = tasks.submit(() -> { calls.incrementAndGet(); return PATH; });
        ThreadPoolManager.invalidateRuntimeEpoch(); release.countDown();
        assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                () -> old.get(3, TimeUnit.SECONDS)).getCause());
        assertEquals(0, calls.get());
        assertSame(PATH, tasks.submit(() -> { calls.incrementAndGet(); return PATH; }).get(3, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test void startupExemptionIsCapturedAtSubmissionRatherThanOnWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) workers.submit(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        RuntimeLoadPolicy.setChunkyPreflightActive(true);
        var result = new CoarsePathTasks(workers).submit(() -> { assertEquals(1, active().get()); return PATH; });
        RuntimeLoadPolicy.setChunkyPreflightActive(false); RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        active().set(1);
        try {
            release.countDown();
            assertSame(PATH, result.get(3, TimeUnit.SECONDS));
            assertEquals(1, active().get());
        } finally { active().set(0); }
    }

    @Test void singleWorkerParentDoesNotHoldPermitWhileWaitingForIndependentChild() throws Exception {
        ExecutorService parent = Executors.newSingleThreadExecutor();
        try {
            var result = parent.submit(() -> {
                assertEquals(0, active().get());
                return new CoarsePathTasks(workers).submit(() -> PATH).get(3, TimeUnit.SECONDS);
            });
            assertSame(PATH, result.get(3, TimeUnit.SECONDS));
        } finally { parent.shutdownNow(); assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void productionDefaultUsesInitialPhysicalPoolButStillTakesGameplayPermit() throws Exception {
        assertSame(PATH, CoarsePathTasks.DEFAULT.submit(() -> {
            assertEquals(ThreadPoolManager.TaskRole.INITIAL, ThreadPoolManager.currentTaskRole());
            assertEquals(1, active().get(), "物理 INITIAL 角色不能被误当成开局任务");
            return PATH;
        }).get(3, TimeUnit.SECONDS));
        assertEquals(0, active().get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void initialParentWithOnePhysicalWorkerCanWaitForItsChildren(boolean startup) throws Exception {
        var method = ThreadPoolManager.class.getDeclaredMethod("initialExecutor"); method.setAccessible(true);
        var pool = (ThreadPoolExecutor) method.invoke(null);
        int cores = pool.getCorePoolSize(), maximum = pool.getMaximumPoolSize();
        var activeField = net.shiroha233.roadweaver.generation.InitialGenManager.class.getDeclaredField("active");
        activeField.setAccessible(true); boolean previous = activeField.getBoolean(null);
        AtomicReference<Future<List<BlockPos>>> child = new AtomicReference<>();
        Future<List<BlockPos>> parent = null;
        try {
            pool.setCorePoolSize(1); pool.setMaximumPoolSize(1); activeField.setBoolean(null, startup);
            parent = ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                assertEquals(ThreadPoolManager.TaskRole.INITIAL, ThreadPoolManager.currentTaskRole());
                child.set(CoarsePathTasks.DEFAULT.submit(() -> {
                    assertNull(ThreadPoolManager.currentTaskRole(), "同池父任务的子搜索必须在独立执行边界运行");
                    assertEquals(startup ? 0 : 1, active().get()); return PATH;
                }));
                return child.get().get(3, TimeUnit.SECONDS);
            });
            assertSame(PATH, parent.get(4, TimeUnit.SECONDS));
        } finally {
            if (child.get() != null) child.get().cancel(true);
            if (parent != null) parent.cancel(true);
            activeField.setBoolean(null, previous);
            pool.setMaximumPoolSize(maximum); pool.setCorePoolSize(cores);
        }
    }

    @Test void cancellingCommonPoolChildInterruptsActualSearch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicReference<Future<List<BlockPos>>> child = new AtomicReference<>();
        AtomicReference<Thread> searchThread = new AtomicReference<>();
        var parent = ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
            child.set(CoarsePathTasks.DEFAULT.submit(() -> {
                searchThread.set(Thread.currentThread()); entered.countDown();
                try { new CountDownLatch(1).await(); return PATH; }
                catch (InterruptedException stop) { interrupted.countDown(); throw stop; }
            }));
            return child.get().get();
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(child.get().cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "取消 Future 必须打断实际搜索，而不只是修改 ForkJoinTask 状态");
        } finally {
            // 负控也必须清掉真正的公共池任务，不能留下一个标记 cancelled 却仍持许可的任务。
            if (searchThread.get() != null) searchThread.get().interrupt();
            if (child.get() != null) child.get().cancel(true);
            parent.cancel(true);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (active().get() != 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(0, active().get());
        }
    }

    @Test void submittingMapReadRoleSurvivesDifferentWorkerRole() throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var result = withRole(ThreadPoolManager.TaskRole.MAP_READ, () -> new CoarsePathTasks(workers).submit(() -> {
            assertNull(ThreadPoolManager.currentTaskRole(), "替身工作池没有道路角色，仍须按提交方让路");
            assertEquals(1, active().get()); return PATH;
        }));
        assertSame(PATH, result.get(3, TimeUnit.SECONDS));
    }

    @Test void nestedSynchronousCpuUnitsUseTheSamePermit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertSame(PATH, new CoarsePathTasks(workers).submit(() -> {
            BackgroundCpuWork.sequential(3, index -> {
                try { assertEquals(1, active().get()); } catch (Exception e) { throw new AssertionError(e); }
                calls.incrementAndGet();
            });
            return PATH;
        }).get(3, TimeUnit.SECONDS));
        assertEquals(3, calls.get()); assertEquals(0, active().get());
    }

    @Test void bothProductionPassesUseTheSharedSubmissionBoundary() throws Exception {
        // 只解析语法树，不以注释或一个没被调用的辅助方法冒充生产接线。
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var inputs = files.getJavaFileObjects(java.nio.file.Path.of(
                    "src/main/java/net/shiroha233/roadweaver/planning/terrain/CoarseCorridorPlanningAdapter.java"));
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null, inputs);
            var unit = task.parse().iterator().next();
            Map<String, Integer> calls = new HashMap<>();
            new com.sun.source.util.TreeScanner<Void, String>() {
                @Override public Void visitMethod(com.sun.source.tree.MethodTree method, String ignored) {
                    return super.visitMethod(method, method.getName().toString());
                }
                @Override public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree call, String method) {
                    if (call.getMethodSelect().toString().equals("CoarsePathTasks.DEFAULT.submit")) calls.merge(method, 1, Integer::sum);
                    return super.visitMethodInvocation(call, method);
                }
            }.scan(unit, "");
            assertEquals(Map.of("plan", 1, "rerouteAttracted", 1), calls);
        }
    }

    @Test void waitingForPermitDoesNotSpendSearchTimeout() throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var result = new CoarsePathTasks(workers).submit(() -> PATH);
        awaitPark(threads.getFirst());
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch awaiting = new CountDownLatch(1);
            var wait = waiter.submit(() -> { awaiting.countDown(); CoarsePathTasks.await(result, 10); return true; });
            assertTrue(awaiting.await(3, TimeUnit.SECONDS));
            // 故意跨过十毫秒期限；这是等待语义反例，不是性能基准。
            assertThrows(TimeoutException.class, () -> wait.get(100, TimeUnit.MILLISECONDS));
            RuntimeLoadPolicy.setGuiScreenOpen(false); expirePause();
            assertTrue(wait.get(3, TimeUnit.SECONDS)); assertSame(PATH, result.get());
        } finally { waiter.shutdownNow(); assertTrue(waiter.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void actualSearchStillHasATimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var result = new CoarsePathTasks(workers).submit(() -> { entered.countDown(); await(release); return PATH; });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try { assertThrows(TimeoutException.class, () -> CoarsePathTasks.await(result, 1)); }
        finally { result.cancel(true); release.countDown(); }
    }

    @Test void physicalQueueStillHasATimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) workers.submit(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var result = new CoarsePathTasks(workers).submit(() -> PATH);
        try { assertThrows(TimeoutException.class, () -> CoarsePathTasks.await(result, 1)); }
        finally { result.cancel(true); release.countDown(); }
    }

    @Test void stoppedTaskThatNeverRanDoesNotLeaveAnUnlimitedWaitPending() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) workers.submit(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var result = new CoarsePathTasks(workers).submit(() -> PATH);
        net.shiroha233.roadweaver.runtime.RoadLifecycle.beginStopping();
        try {
            assertThrows(CancellationException.class, () -> CoarsePathTasks.await(result, 0));
            assertTrue(result.isCancelled());
        } finally { net.shiroha233.roadweaver.runtime.RoadLifecycle.beginStarting(); release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wrappedCancellationDoesNotBecomeOrdinaryFailureAndCancelsSiblings(boolean parallel) throws Exception {
        var reason = new CancellationException("world changed");
        var broken = new CompletableFuture<List<BlockPos>>(); broken.completeExceptionally(reason);
        var sibling = new CompletableFuture<List<BlockPos>>();
        // CompletableFuture 会直接抛取消；普通 FutureTask 才有 ExecutionException 包装，两个路径都验。
        FutureTask<List<BlockPos>> wrapped = new FutureTask<>(() -> { throw reason; }); wrapped.run();
        var failure = invokeAwait(parallel, wrapped, List.of(wrapped, sibling));
        assertSame(reason, failure); assertTrue(sibling.isCancelled());
        sibling = new CompletableFuture<>();
        assertInstanceOf(CancellationException.class, invokeAwait(parallel, broken, List.of(broken, sibling)));
        assertTrue(sibling.isCancelled());
        // 并行等待的直接取消沿用旧中断标记契约，不让它污染后面的夹具。
        Thread.interrupted();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryFailureCancelsAllSiblingTasks(boolean parallel) throws Exception {
        var reason = new java.io.IOException("search failed");
        var broken = new CompletableFuture<List<BlockPos>>(); broken.completeExceptionally(reason);
        var sibling = new CompletableFuture<List<BlockPos>>();
        var failure = assertInstanceOf(IllegalStateException.class, invokeAwait(parallel, broken, List.of(broken, sibling)));
        assertSame(reason, failure.getCause()); assertTrue(sibling.isCancelled());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void interruptedParentCancelsAllSiblingTasks(boolean parallel) throws Exception {
        var pending = new CompletableFuture<List<BlockPos>>(); var sibling = new CompletableFuture<List<BlockPos>>();
        Thread.currentThread().interrupt();
        try {
            assertInstanceOf(CancellationException.class, invokeAwait(parallel, pending, List.of(pending, sibling)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled()); assertTrue(sibling.isCancelled());
        } finally { Thread.interrupted(); }
    }

    private static Throwable invokeAwait(boolean parallel, Future<List<BlockPos>> first,
                                          List<Future<List<BlockPos>>> all) throws Exception {
        var method = parallel ? CoarseCorridorPlanningAdapter.class.getDeclaredMethod("awaitAllPathsParallel", List.class)
                : CoarseCorridorPlanningAdapter.class.getDeclaredMethod("awaitOnePath", Future.class, List.class);
        method.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
            if (parallel) method.invoke(null, all); else method.invoke(null, first, all);
        });
        return failure.getCause();
    }

    private static void awaitPark(Thread thread) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (thread.getState() == Thread.State.TIMED_WAITING && Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(RuntimeLoadPolicy.class.getName()))) return;
            Thread.yield();
        }
        fail("子任务未进入实际预算等待: " + thread.getState());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("fixture interrupted"); }
    }
    private static AtomicInteger active() throws Exception {
        Field field = RuntimeLoadPolicy.class.getDeclaredField("ACTIVE_COARSE_UNITS");
        field.setAccessible(true); return (AtomicInteger) field.get(null);
    }
    private static void expirePause() throws Exception {
        for (String name : List.of("NEXT_REFRESH", "NEXT_COORDINATOR_REFRESH", "RECOVERY_UNTIL")) {
            Field field = RuntimeLoadPolicy.class.getDeclaredField(name); field.setAccessible(true); ((AtomicLong) field.get(null)).set(0);
        }
    }
    private static <T> T withRole(ThreadPoolManager.TaskRole role, Callable<T> action) throws Exception {
        Field field = ThreadPoolManager.class.getDeclaredField("CURRENT_ROLE"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var local = (ThreadLocal<ThreadPoolManager.TaskRole>) field.get(null);
        var saved = local.get(); local.set(role);
        try { return action.call(); } finally { local.set(saved); }
    }
}
