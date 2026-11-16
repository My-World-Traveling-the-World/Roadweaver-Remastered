/* 文件职责：用真实角色池复现停服时排队和前置闸门中的任务丢失，不能用取消标记代替终态。 */
package net.shiroha233.roadweaver.runtime;

import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.config.ConfigService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class TaskLifecycleTest {
    private final List<Future<?>> results = new ArrayList<>();
    private final List<ThreadPoolExecutor> pools = new ArrayList<>();
    private List<RoadLoadSignals> signals;
    private boolean gui, map, preflight, exclusive;
    private int duty;

    @BeforeEach void isolate() throws Exception {
        RoadLifecycle.beginStarting(); ThreadPoolManager.onServerStopping();
        signals = RoadLoadSignalsRegistry.sources();
        signals.forEach(s -> RoadLoadSignalsRegistry.unregister(s.ownerId()));
        gui = RuntimeLoadPolicy.isGuiScreenOpen(); map = RuntimeLoadPolicy.isMapScreenOpen();
        preflight = RuntimeLoadPolicy.isChunkyPreflightActive(); exclusive = RuntimeLoadPolicy.isChunkyExclusiveActive();
        duty = ConfigService.get().performance().threadDutyCycle();
        ConfigService.get().performance().setThreadDutyCycle(100);
        RuntimeLoadPolicy.setGuiScreenOpen(false); RuntimeLoadPolicy.setMapScreenOpen(false);
        RuntimeLoadPolicy.setChunkyPreflightActive(false); RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        expirePause();
    }

    @AfterEach void restore() throws Exception {
        Thread.interrupted();
        results.forEach(f -> f.cancel(true));
        RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
        for (var pool : pools) assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS), "实际线程必须退出");
        RoadLifecycle.beginStarting();
        ConfigService.get().performance().setThreadDutyCycle(duty);
        RuntimeLoadPolicy.setGuiScreenOpen(gui); RuntimeLoadPolicy.setMapScreenOpen(map);
        RuntimeLoadPolicy.setChunkyPreflightActive(preflight); RuntimeLoadPolicy.setChunkyExclusiveActive(exclusive);
        expirePause();
        RoadLoadSignalsRegistry.sources().forEach(s -> RoadLoadSignalsRegistry.unregister(s.ownerId()));
        signals.forEach(RoadLoadSignalsRegistry::register);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void queuedTaskCompletesWithoutExecutingWorldActionOnStop(int kind) throws Exception {
        ThreadPoolManager.resizeInitialPool(1);
        pools.add(pool("INITIAL_WORKERS"));
        var entered = new CountDownLatch(1);
        var blocker = ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
            entered.countDown(); try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        });
        results.add(blocker); assertTrue(entered.await(3, TimeUnit.SECONDS));
        var calls = new AtomicInteger();
        Future<?> task = submit(kind, ThreadPoolManager.TaskRole.INITIAL, calls);
        assertEquals(1, pools.getFirst().getQueue().size(), "夹具必须真正排队");
        RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
        assertCancelled(task); assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void taskInsideAdmissionGateCompletesWithoutWorldActionOnStop(int kind) throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var calls = new AtomicInteger();
        Future<?> task = submit(kind, ThreadPoolManager.TaskRole.PLANNING, calls);
        ThreadPoolExecutor workers = pool("SHARED_EXEC"); pools.add(workers);
        awaitGate(); assertEquals(0, calls.get(), "夹具必须进入暂停而不是执行动作");
        RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
        assertCancelled(task); assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void cancellationInterruptsCalculationBeforeInvokingCompletionCallbacks(int kind) throws Exception {
        var entered = new CountDownLatch(1); var exited = new CountDownLatch(1);
        Runnable action = () -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            finally { exited.countDown(); }
        };
        CompletableFuture<?> result = kind == 2
                ? ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> { action.run(); return "value"; })
                : ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.INITIAL, action);
        results.add(result); pools.add(pool("INITIAL_WORKERS"));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var interruptedBeforeCallbackReturns = new AtomicBoolean();
        result.whenComplete((value, failure) -> {
            // 取消回调可能关闭采样会话并等实际线程退出；必须先中断计算，再发布取消结果。
            try { interruptedBeforeCallbackReturns.set(exited.await(500, TimeUnit.MILLISECONDS)); }
            catch (InterruptedException unexpected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(result.cancel(true));
        assertTrue(interruptedBeforeCallbackReturns.get());
        assertCancelled(result);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void runningTaskIsCancelledEvenWhenCalculationHasNotExited(int kind) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        Future<?> result = submitAction(kind, ThreadPoolManager.TaskRole.INITIAL, () -> {
            entered.countDown();
            // 模拟不能强停的单个世界计算；终态取消与计算真实退出必须分开验证。
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException expected) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            exited.countDown();
        });
        pools.add(pool("INITIAL_WORKERS")); assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            assertCancelled(result); assertEquals(1, exited.getCount(), "夹具应仍在实际计算");
        } finally { release.countDown(); }
        assertTrue(exited.await(3, TimeUnit.SECONDS)); assertCancelled(result);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void rebuildCancelsOldQueueAndFreshTaskCanStillRun(int kind) throws Exception {
        var release = blockInitial(); var calls = new AtomicInteger();
        Future<?> old = submit(kind, ThreadPoolManager.TaskRole.INITIAL, calls);
        assertEquals(1, pools.getFirst().getQueue().size());
        try {
            ThreadPoolManager.resizeInitialPool(2); pools.add(pool("INITIAL_WORKERS"));
            assertCancelled(old); assertEquals(0, calls.get());
            assertEquals("fresh", ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> "fresh")
                    .get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void cancelWithoutInterruptReleasesAdmissionWorker(int kind) throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var calls = new AtomicInteger();
        Future<?> task = submit(kind, ThreadPoolManager.TaskRole.PLANNING, calls);
        ThreadPoolExecutor workers = pool("SHARED_EXEC"); pools.add(workers); awaitGate();
        assertTrue(task.cancel(false)); assertCancelled(task);
        // 不能只断言结果标记；界面仍开着时，真实 wrapper 也必须走完。
        awaitCondition(() -> workers.getCompletedTaskCount() == 1);
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void changingWorldEpochEscapesAdmissionWithoutWorldAction(int kind) throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var calls = new AtomicInteger();
        Future<?> task = submit(kind, ThreadPoolManager.TaskRole.PLANNING, calls);
        pools.add(pool("SHARED_EXEC")); awaitGate();
        ThreadPoolManager.invalidateRuntimeEpoch();
        assertCancelled(task); assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void submissionAfterStopRejectsSynchronously(int kind) {
        RoadLifecycle.beginStopping();
        var calls = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> submit(kind, ThreadPoolManager.TaskRole.INITIAL, calls));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"INITIAL", "MAP_READ", "STRUCTURE", "SNAPSHOT", "MAP", "PLANNING"})
    void everyPhysicalPoolCancelsBothFutureTypesInItsQueue(String name) throws Exception {
        var role = ThreadPoolManager.TaskRole.valueOf(name);
        // 每条实际映射都入队两种 Future，防止只把 INITIAL 的构造器换成可追踪池。
        assertEquals("warm", ThreadPoolManager.submit(role, () -> "warm").get(3, TimeUnit.SECONDS));
        String field = switch (role) {
            case PLANNING -> "SHARED_EXEC";
            default -> name + "_WORKERS";
        };
        var workers = pool(field); pools.add(workers);
        workers.setCorePoolSize(1); workers.setMaximumPoolSize(1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = ThreadPoolManager.submit(role, () -> {
            entered.countDown(); try { release.await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        }); results.add(blocker);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var calls = new AtomicInteger();
        var standard = submit(0, role, calls); var completion = submit(2, role, calls);
        assertEquals(2, workers.getQueue().size());
        try {
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            assertCancelled(standard); assertCancelled(completion); assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

    @Test void successfulValuesFailuresAndNullResultsKeepTheirOriginalMeaning() throws Exception {
        var role = ThreadPoolManager.TaskRole.INITIAL;
        Object value = new Object(); var failure = new IllegalArgumentException("original");
        var supplied = ThreadPoolManager.supplyAsync(role, () -> value); results.add(supplied);
        pools.add(pool("INITIAL_WORKERS"));
        assertSame(value, supplied.get(3, TimeUnit.SECONDS));
        var failed = ThreadPoolManager.supplyAsync(role, () -> { throw failure; }); results.add(failed);
        assertSame(failure, assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS)).getCause());
        var run = ThreadPoolManager.runAsync(role, () -> {}); results.add(run);
        assertNull(run.get(3, TimeUnit.SECONDS));
        var checked = new java.io.IOException("checked");
        var standard = ThreadPoolManager.submit(role, () -> { throw checked; }); results.add(standard);
        assertSame(checked, assertThrows(ExecutionException.class, () -> standard.get(3, TimeUnit.SECONDS)).getCause());
        var worker = pools.getFirst(); awaitCondition(() -> worker.getCompletedTaskCount() == 4);
        // 两份追踪表都应释放闭包；仅验证任务成功不够，长期游玩不能一直保留旧世界引用。
        for (String name : List.of("created", "accepted")) {
            Field tracked = LifecycleWorkerPool.class.getDeclaredField(name); tracked.setAccessible(true);
            assertEquals(0, ((Set<?>) tracked.get(worker)).size(), name);
        }
    }

    @Test void completingQueuedResultManuallySkipsTheSupplierAndKeepsTheValue() throws Exception {
        var release = blockInitial(); var calls = new AtomicInteger();
        var result = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> calls.incrementAndGet());
        results.add(result); assertTrue(result.complete(87)); release.countDown();
        assertEquals(87, result.get(3, TimeUnit.SECONDS));
        assertEquals("after", ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> "after")
                .get(3, TimeUnit.SECONDS));
        assertEquals(0, calls.get()); assertEquals(87, result.join());
    }

    @Test void stoppingCompletesCleanupChainOutsideTheManagerMonitor() throws Exception {
        var release = blockInitial();
        var result = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> "never"); results.add(result);
        var heldMonitor = new AtomicReference<Boolean>(); var callbacks = new AtomicInteger();
        var cleanup = result.whenComplete((value, failure) -> {
            heldMonitor.set(Thread.holdsLock(ThreadPoolManager.class)); callbacks.incrementAndGet();
        });
        try {
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            assertCancelled(result); assertEquals(1, callbacks.get()); assertEquals(Boolean.FALSE, heldMonitor.get());
            assertInstanceOf(CancellationException.class, assertThrows(CompletionException.class, cleanup::join).getCause());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void opaqueJdkRootSubmissionIsRejectedInsteadOfLeavingAnUntrackedResult(boolean supply) {
        var calls = new AtomicInteger(); var executor = ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL);
        var rejected = assertThrows(RejectedExecutionException.class, () -> {
            if (supply) CompletableFuture.supplyAsync(calls::incrementAndGet, executor);
            else CompletableFuture.runAsync(calls::incrementAndGet, executor);
        });
        assertTrue(rejected.getMessage().contains("ThreadPoolManager.runAsync/supplyAsync")); assertEquals(0, calls.get());
    }

    @Test void opaqueJdkContinuationEndsExceptionallyAndTrackedContinuationStillWorks() throws Exception {
        var calls = new AtomicInteger();
        var rejected = CompletableFuture.completedFuture(3).thenApplyAsync(v -> calls.incrementAndGet(),
                ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL));
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, rejected::join).getCause()); assertEquals(0, calls.get());
        var accepted = CompletableFuture.completedFuture(3).thenCompose(v ->
                ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> v + 4));
        results.add(accepted); pools.add(pool("INITIAL_WORKERS")); assertEquals(7, accepted.get(3, TimeUnit.SECONDS));
    }

    @Test void roleShutdownRemainsANoOpAndNullInputsStillFailImmediately() throws Exception {
        var role = ThreadPoolManager.TaskRole.INITIAL; var executor = ThreadPoolManager.roleExecutor(role);
        executor.shutdown(); assertEquals(List.of(), executor.shutdownNow());
        assertEquals("alive", executor.submit(() -> "alive").get(3, TimeUnit.SECONDS)); pools.add(pool("INITIAL_WORKERS"));
        assertThrows(NullPointerException.class, () -> ThreadPoolManager.supplyAsync(role, null));
        assertThrows(NullPointerException.class, () -> ThreadPoolManager.runAsync(role, null));
        assertThrows(NullPointerException.class, () -> executor.execute(null));
    }

    @Test void priorityFifoAndRoleContextSurviveTheExtractedWrapper() throws Exception {
        var worker = new LifecycleWorkerPool(1, 1, 0, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), r -> new Thread(r, "role-order-test")); pools.add(worker);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = new FutureTask<Void>(() -> { entered.countDown(); release.await(); return null; });
        worker.execute(new PrioritizedTask(ThreadPoolManager.TaskRole.INITIAL, blocker, (role, cancelled) -> {}));
        results.add(blocker); assertTrue(entered.await(3, TimeUnit.SECONDS));
        var order = new CopyOnWriteArrayList<String>(); var done = new CountDownLatch(4);
        addOrdered(worker, ThreadPoolManager.TaskRole.MAP, "map", order, done);
        addOrdered(worker, ThreadPoolManager.TaskRole.COARSE, "coarse-a", order, done);
        addOrdered(worker, ThreadPoolManager.TaskRole.GENERATION, "generation", order, done);
        addOrdered(worker, ThreadPoolManager.TaskRole.COARSE, "coarse-b", order, done);
        release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS));
        worker.shutdown(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(List.of("coarse-a", "coarse-b", "generation", "map"), order);
        // 成功完成后注册表必须清空，否则长时间游玩会保留整个闭包及世界引用。
        Field f = LifecycleWorkerPool.class.getDeclaredField("accepted"); f.setAccessible(true);
        assertEquals(0, ((Set<?>) f.get(worker)).size());
    }

    @Test void admissionFaultStillRunsButCancellationAndLateStopDoNotRun() throws Exception {
        var previous = ThreadPoolManager.enterTaskRole(ThreadPoolManager.TaskRole.MAP_READ);
        try {
            var result = new FutureTask<>(() -> {
                assertEquals(ThreadPoolManager.TaskRole.PLANNING, ThreadPoolManager.currentTaskRole()); return 42;
            });
            new PrioritizedTask(null, result, (role, cancelled) -> { throw new IllegalStateException("gate-fault"); }).run();
            assertEquals(42, result.get(3, TimeUnit.SECONDS));
            assertEquals(ThreadPoolManager.TaskRole.MAP_READ, ThreadPoolManager.currentTaskRole());
            var calls = new AtomicInteger(); var cancelled = new FutureTask<>(calls::incrementAndGet);
            new PrioritizedTask(null, cancelled, (role, expired) -> { throw new CancellationException("gate-cancel"); }).run();
            assertCancelled(cancelled); assertEquals(0, calls.get());
            var late = new FutureTask<>(calls::incrementAndGet);
            new PrioritizedTask(null, late, (role, expired) -> {
                RoadLifecycle.beginStopping(); throw new IllegalStateException("late-stop");
            }).run();
            assertCancelled(late); assertEquals(0, calls.get());
            assertEquals(ThreadPoolManager.TaskRole.MAP_READ, ThreadPoolManager.currentTaskRole());
        } finally { ThreadPoolManager.restoreTaskRole(previous); }
    }

    private static void addOrdered(LifecycleWorkerPool pool, ThreadPoolManager.TaskRole role, String label,
                                   List<String> order, CountDownLatch done) {
        var result = new FutureTask<Void>(() -> {
            assertEquals(role, ThreadPoolManager.currentTaskRole()); order.add(label); done.countDown(); return null;
        });
        pool.execute(new PrioritizedTask(role, result, (ignored, expired) -> {}));
    }

    private CountDownLatch blockInitial() throws Exception {
        ThreadPoolManager.resizeInitialPool(1); pools.add(pool("INITIAL_WORKERS"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        results.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
            entered.countDown(); try { release.await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        }));
        assertTrue(entered.await(3, TimeUnit.SECONDS)); return release;
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "实际工作线程没有离开已取消的任务");
    }

    @Test void completionServiceKeepsItsInnerFutureTerminalWhenQueueIsStopped() throws Exception {
        var release = blockInitial(); var calls = new AtomicInteger();
        var completion = new ExecutorCompletionService<Integer>(ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL));
        var result = completion.submit(calls::incrementAndGet); results.add(result);
        assertEquals(1, pools.getFirst().getQueue().size());
        try {
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            assertCancelled(result); assertSame(result, completion.poll(1, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionServiceInnerFutureIsCancelledWhenWorldChanges(boolean atGate) throws Exception {
        CountDownLatch release = atGate ? null : blockInitial();
        var role = atGate ? ThreadPoolManager.TaskRole.PLANNING : ThreadPoolManager.TaskRole.INITIAL;
        if (atGate) { RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause(); }
        var calls = new AtomicInteger();
        var completion = new ExecutorCompletionService<Integer>(ThreadPoolManager.roleExecutor(role));
        var result = completion.submit(calls::incrementAndGet); results.add(result);
        if (atGate) { pools.add(pool("SHARED_EXEC")); awaitGate(); }
        try {
            ThreadPoolManager.invalidateRuntimeEpoch();
            assertCancelled(result); assertEquals(0, calls.get());
            if (release != null) release.countDown();
            assertSame(result, completion.poll(3, TimeUnit.SECONDS));
        } finally { if (release != null) release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invokeAnyAndInvokeAllFinishWhenTheirQueuedWorkIsStopped(boolean any) throws Exception {
        var release = blockInitial(); var calls = new AtomicInteger();
        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> {
                var executor = ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL);
                List<Callable<Integer>> jobs = List.of(calls::incrementAndGet, calls::incrementAndGet);
                if (any) return executor.invokeAny(jobs);
                var all = executor.invokeAll(jobs);
                assertEquals(2, all.size()); all.forEach(TaskLifecycleTest::assertCancelled); return -1;
            });
            awaitCondition(() -> pools.getFirst().getQueue().size() == 2);
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            if (any) {
                var wrapped = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertInstanceOf(ExecutionException.class, wrapped.getCause());
                assertInstanceOf(CancellationException.class, wrapped.getCause().getCause());
            } else assertEquals(-1, result.get(3, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally {
            release.countDown(); caller.shutdownNow(); assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellingWithoutInterruptKeepsAnAlreadyStartedCalculationRunning() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var result = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            entered.countDown(); try { release.await(); }
            catch (InterruptedException unexpected) { interrupted.set(true); Thread.currentThread().interrupt(); }
            finally { exited.countDown(); } return "late";
        }); results.add(result); pools.add(pool("INITIAL_WORKERS"));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            assertTrue(result.cancel(false)); assertCancelled(result);
            assertEquals(1, exited.getCount()); assertFalse(interrupted.get());
        } finally { release.countDown(); }
        assertTrue(exited.await(3, TimeUnit.SECONDS)); assertFalse(interrupted.get()); assertCancelled(result);
    }

    @Test void physicalRejectionCancelsTheSubmittedFutureAndRemovesTracking() throws Exception {
        var worker = new LifecycleWorkerPool(1, 1, 0, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), r -> new Thread(r, "rejection-test")); pools.add(worker);
        worker.shutdownNow(); var calls = new AtomicInteger(); var task = new FutureTask<>(calls::incrementAndGet);
        assertThrows(RejectedExecutionException.class, () -> worker.execute(task));
        assertCancelled(task); assertEquals(0, calls.get());
        Field f = LifecycleWorkerPool.class.getDeclaredField("accepted"); f.setAccessible(true);
        assertEquals(0, ((Set<?>) f.get(worker)).size());
    }

    @Test void allSevenProductionCompletionSitesUseTrackedFactories() throws Exception {
        // 真实 ServerLevel 留给真机；语法树钉住迁移的七处出口，注释及失效辅助方法不算接线。
        Map<String, Map<String, Integer>> expected = Map.of(
                "pathlogic/junction/ExistingRoadJunctionService.java", Map.of("refine:ThreadPoolManager.supplyAsync", 1),
                "generation/InitialRoutePersistOps.java", Map.of("persistInitialRoadBatchInternal:ThreadPoolManager.supplyAsync", 1),
                "postprocess/RoadSnapService.java", Map.of("snapAroundConnectionAsync:ThreadPoolManager.runAsync", 1,
                        "snapAllRoadsAsync:ThreadPoolManager.runAsync", 1),
                "client/map/MapSnapshotLoading.java", Map.of("scheduleSingleplayer:ComputeService.supplyMapReadAsync", 1),
                "../fabric/src/main/java/net/shiroha233/roadweaver/network/fabric/MapNetworkFabric.java",
                Map.of("registerServerReceivers:ThreadPoolManager.supplyAsync", 2));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            for (var entry : expected.entrySet()) {
                String name = entry.getKey();
                var path = name.startsWith("..") ? java.nio.file.Path.of(name)
                        : java.nio.file.Path.of("src/main/java/net/shiroha233/roadweaver/" + name);
                var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
                var task = (com.sun.source.util.JavacTask) compiler.getTask(null, files, diagnostics,
                        List.of("-proc:none"), null, files.getJavaFileObjects(path));
                var unit = task.parse().iterator().next();
                assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR));
                Map<String, Integer> actual = new HashMap<>(); var opaque = new ArrayList<String>();
                new com.sun.source.util.TreeScanner<Void, String>() {
                    @Override public Void visitMethod(com.sun.source.tree.MethodTree method, String ignored) {
                        return super.visitMethod(method, method.getName().toString());
                    }
                    @Override public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree call, String method) {
                        String select = call.getMethodSelect().toString();
                        if (Set.of("ThreadPoolManager.supplyAsync", "ThreadPoolManager.runAsync",
                                "ComputeService.supplyMapReadAsync").contains(select)) actual.merge(method + ":" + select, 1, Integer::sum);
                        if (select.endsWith(".roleExecutor") || select.equals("ComputeService.mapReadExecutor")) opaque.add(select);
                        return super.visitMethodInvocation(call, method);
                    }
                }.scan(unit, "");
                assertEquals(entry.getValue(), actual, name); assertEquals(List.of(), opaque, name);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"INITIAL", "MAP_READ", "STRUCTURE", "SNAPSHOT", "MAP", "PLANNING"})
    void stopWinsAgainstLazyCreationWaitingForTheManagerMonitor(String name) throws Exception {
        var callerThread = new AtomicReference<Thread>();
        var caller = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "late-create-test"); callerThread.set(t); return t; });
        var calls = new AtomicInteger(); Future<?> request;
        try {
            synchronized (ThreadPoolManager.class) {
                request = caller.submit(() -> ThreadPoolManager.submit(ThreadPoolManager.TaskRole.valueOf(name), calls::incrementAndGet));
                // 锁内构造夹具：提交已通过外层 stopping 检查，确实在等待管理锁。
                awaitCondition(() -> callerThread.get() != null && callerThread.get().getState() == Thread.State.BLOCKED);
                RoadLifecycle.beginStopping();
            }
            var failure = assertThrows(ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertNull(pool(name.equals("PLANNING") ? "SHARED_EXEC" : name + "_WORKERS"));
            assertEquals(0, calls.get());
        } finally { caller.shutdownNow(); assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void stoppingAlsoCancelsAnAlreadyGracefullyShuttingDownPool() throws Exception {
        var release = blockInitial(); var calls = new AtomicInteger();
        var result = submit(0, ThreadPoolManager.TaskRole.INITIAL, calls);
        pools.getFirst().shutdown(); assertFalse(result.isDone());
        try {
            RoadLifecycle.beginStopping(); ThreadPoolManager.onServerStopping();
            assertCancelled(result); assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

    @Test void epochCleanupKeepsFreshTasksCreatedByCancellationCallbacks() throws Exception {
        var release = blockInitial(); var entered = new CountDownLatch(1); var freshRelease = new CountDownLatch(1);
        var fresh = new AtomicReference<CompletableFuture<String>>();
        var old = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> "old"); results.add(old);
        old.whenComplete((value, failure) -> fresh.set(ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> {
            entered.countDown(); try { freshRelease.await(); }
            catch (InterruptedException unexpected) { Thread.currentThread().interrupt(); }
            return "fresh";
        })));
        try {
            ThreadPoolManager.invalidateRuntimeEpoch(); release.countDown();
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertNotNull(fresh.get()); results.add(fresh.get());
            ((LifecycleWorkerPool) pools.getFirst()).cancelExpiredTasks();
            assertFalse(fresh.get().isDone(), "新代次的在途计算不能被旧代次清理误杀");
            freshRelease.countDown(); assertEquals("fresh", fresh.get().get(3, TimeUnit.SECONDS)); assertCancelled(old);
        } finally { release.countDown(); freshRelease.countDown(); }
    }

    @Test void rebuildingPublishesTheNewPoolBeforeCompletingOldResultsOutsideTheMonitor() throws Exception {
        var release = blockInitial(); var held = new AtomicReference<Boolean>();
        var fresh = new AtomicReference<Future<String>>();
        var old = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.INITIAL, () -> "never"); results.add(old);
        old.whenComplete((value, failure) -> {
            held.set(Thread.holdsLock(ThreadPoolManager.class));
            fresh.set(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> "fresh"));
        });
        try {
            ThreadPoolManager.resizeInitialPool(2); pools.add(pool("INITIAL_WORKERS"));
            assertCancelled(old); assertEquals(Boolean.FALSE, held.get()); assertNotNull(fresh.get());
            assertEquals("fresh", fresh.get().get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    private Future<?> submit(int kind, ThreadPoolManager.TaskRole role, AtomicInteger calls) {
        return submitAction(kind, role, calls::incrementAndGet);
    }

    private Future<?> submitAction(int kind, ThreadPoolManager.TaskRole role, Runnable action) {
        Future<?> task = switch (kind) {
            case 0 -> ThreadPoolManager.submit(role, () -> { action.run(); return "value"; });
            case 1 -> ThreadPoolManager.submit(role, action);
            case 2 -> ThreadPoolManager.supplyAsync(role, () -> { action.run(); return "value"; });
            case 3 -> ThreadPoolManager.runAsync(role, action);
            default -> throw new AssertionError(kind);
        };
        results.add(task); return task;
    }

    private static void assertCancelled(Future<?> task) {
        assertThrows(CancellationException.class, () -> task.get(500, TimeUnit.MILLISECONDS));
        assertTrue(task.isDone()); assertTrue(task.isCancelled());
    }

    private static ThreadPoolExecutor pool(String name) throws Exception {
        Field f = ThreadPoolManager.class.getDeclaredField(name); f.setAccessible(true);
        return ((AtomicReference<ThreadPoolExecutor>) f.get(null)).get();
    }

    private static void awaitGate() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                if (!entry.getKey().getName().startsWith("RW-Worker")) continue;
                if (Arrays.stream(entry.getValue()).anyMatch(s -> s.getMethodName().equals("awaitRuntimePermit"))) return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        fail("任务未进入真实前置闸门");
    }

    private static void expirePause() throws Exception {
        Field f = RuntimeLoadPolicy.class.getDeclaredField("NEXT_REFRESH"); f.setAccessible(true);
        ((AtomicLong) f.get(null)).set(0);
    }
}
