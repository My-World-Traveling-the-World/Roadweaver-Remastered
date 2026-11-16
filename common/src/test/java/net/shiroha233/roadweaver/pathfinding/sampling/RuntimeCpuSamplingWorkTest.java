/* 文件职责：通过生产默认采样出口验证共享预算、前台暂停及取消，不只测试替身执行器。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RuntimeCpuSamplingWorkTest {
    private final List<Thread> workers = new ArrayList<>();
    private List<net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals> savedSources;
    private int savedCap, savedShared, savedDuty;
    private boolean savedGui, savedMap, savedPreflight, savedExclusive;

    @BeforeEach void isolate() throws Exception {
        assertFalse(InitialGenManager.isActive());
        assertFalse(RoadLifecycle.isStopping());
        assertEquals(0, active().get(), "夹具开始时不能已有未释放的采样许可");
        savedSources = RoadLoadSignalsRegistry.sources();
        savedSources.forEach(source -> RoadLoadSignalsRegistry.unregister(source.ownerId()));
        var config = ConfigService.get().performance();
        savedCap = config.gameplayThreadCap(); savedShared = config.sharedWorkerThreads(); savedDuty = config.threadDutyCycle();
        config.setGameplayThreadCap(1); config.setSharedWorkerThreads(0); config.setThreadDutyCycle(100);
        savedGui = RuntimeLoadPolicy.isGuiScreenOpen(); savedMap = RuntimeLoadPolicy.isMapScreenOpen();
        savedPreflight = RuntimeLoadPolicy.isChunkyPreflightActive(); savedExclusive = RuntimeLoadPolicy.isChunkyExclusiveActive();
        RuntimeLoadPolicy.setGuiScreenOpen(false); RuntimeLoadPolicy.setMapScreenOpen(false);
        RuntimeLoadPolicy.setChunkyPreflightActive(false); RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        expirePause();
    }

    @AfterEach void restore() throws Exception {
        for (Thread worker : workers) worker.interrupt();
        for (Thread worker : workers) worker.join(3000);
        setStartup(false);
        RoadLifecycle.beginStarting();
        var config = ConfigService.get().performance();
        config.setGameplayThreadCap(savedCap); config.setSharedWorkerThreads(savedShared); config.setThreadDutyCycle(savedDuty);
        RuntimeLoadPolicy.setGuiScreenOpen(savedGui); RuntimeLoadPolicy.setMapScreenOpen(savedMap);
        RuntimeLoadPolicy.setChunkyPreflightActive(savedPreflight); RuntimeLoadPolicy.setChunkyExclusiveActive(savedExclusive);
        RoadLoadSignalsRegistry.sources().forEach(source -> RoadLoadSignalsRegistry.unregister(source.ownerId()));
        savedSources.forEach(RoadLoadSignalsRegistry::register);
        expirePause();
        // 先记录泄漏再清夹具，负控失败不能把后面的回归全堵在同一计数上。
        int leaked = active().getAndSet(0);
        assertEquals(0, leaked, "读取异常或取消不能泄漏共享许可");
        for (Thread worker : workers) assertFalse(worker.isAlive(), "后台夹具必须真正退出");
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void everyDefaultEntryRunsGameplayUnitsOnCallerAndKeepsChannels(int mode) {
        Thread owner = Thread.currentThread();
        var source = source(mode < 3, () -> {
            assertSame(owner, Thread.currentThread(), "游玩期不能把批次偷偷扔进公用并行池");
            assertEquals(1, activeUnchecked().get(), "真正读世界时必须持有共享许可");
        });
        var backend = CpuAccurateHeightBackend.fromSource(source);
        if (mode == 0) {
            var keys = List.of(ChunkPos.asLong(-2, -1), ChunkPos.asLong(2, 0));
            var result = backend.sampleChunks(keys);
            assertEquals(new HashSet<>(keys), result.keySet());
            for (long key : keys) {
                var chunk = result.get(key);
                for (int i = 0; i < 256; i++) {
                    int x = (ChunkPos.getX(key) << 4) + (i & 15), z = (ChunkPos.getZ(key) << 4) + (i >> 4);
                    assertEquals(CpuAccurateHeightBackendTest.expected(x, z), new AccurateHeightSample(
                            chunk.worldSurfaceWg(x, z), chunk.oceanFloorWg(x, z), chunk.motionBlockingNoLeaves(x, z)));
                }
            }
        } else if (mode == 1 || mode == 3) {
            var result = backend.samplePositions(List.of(new BlockPos(-17, 99, -1), new BlockPos(16, 0, 1)));
            assertEquals(Map.of(AccurateHeightSample.key(-17, -1), CpuAccurateHeightBackendTest.expected(-17, -1),
                    AccurateHeightSample.key(16, 1), CpuAccurateHeightBackendTest.expected(16, 1)), result);
        } else {
            var request = new AccurateHeightGridRequest(-17, -1, 18, 3, 3);
            var result = backend.sampleGrid(request, null);
            assertEquals(54, result.worldSurface().length);
            for (int i = 0; i < 54; i++) assertEquals(CpuAccurateHeightBackendTest.expected(request.blockX(i), request.blockZ(i)),
                    new AccurateHeightSample(result.worldSurface()[i], result.oceanFloor()[i], result.motionBlocking()[i]));
        }
        assertTrue(source.calls.get() > 0, "产物检查前必须真的取样");
    }

    @Test void twoConcurrentRequestsRunButThirdWaitsForSharedBudget() throws Exception {
        ConfigService.get().performance().setGameplayThreadCap(2);
        CountDownLatch firstTwo = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
        var source = source(true, () -> {
            int n = running.incrementAndGet(); peak.accumulateAndGet(n, Math::max);
            firstTwo.countDown();
            try { await(release); } finally { running.decrementAndGet(); }
        });
        var backend = CpuAccurateHeightBackend.fromSource(source);
        var a = fork(() -> backend.sampleChunks(List.of(0L)));
        var b = fork(() -> backend.sampleChunks(List.of(1L)));
        assertTrue(firstTwo.await(3, TimeUnit.SECONDS));
        var c = fork(() -> backend.sampleChunks(List.of(2L)));
        awaitPolicyPark(workers.getLast());
        assertEquals(2, peak.get()); assertEquals(2, running.get());
        release.countDown();
        assertEquals(Set.of(0L), a.get(3, TimeUnit.SECONDS).keySet());
        assertEquals(Set.of(1L), b.get(3, TimeUnit.SECONDS).keySet());
        assertEquals(Set.of(2L), c.get(3, TimeUnit.SECONDS).keySet());
        assertEquals(3, source.calls.get()); assertEquals(2, peak.get());
    }

    @Test void accurateSamplingSharesTheExistingCoarsePermit() throws Exception {
        assertTrue(RuntimeLoadPolicy.acquireCoarseUnit(false));
        boolean held = true;
        try {
            var source = source(true, () -> {});
            var result = fork(() -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L)));
            awaitPolicyPark(workers.getLast());
            assertEquals(0, source.calls.get());
            RuntimeLoadPolicy.releaseCoarseUnit(false);
            held = false;
            assertEquals(Set.of(0L), result.get(3, TimeUnit.SECONDS).keySet());
            assertEquals(1, source.calls.get());
        } finally {
            // 仅在主体尚未执行释放时补清，防止提前断言失败留下夹具持有的许可。
            if (held) RuntimeLoadPolicy.releaseCoarseUnit(false);
        }
    }

    @Test void foregroundPauseIsRecheckedBetweenChunks() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var source = source(true, () -> {
            if (reads.incrementAndGet() == 1) {
                RuntimeLoadPolicy.setGuiScreenOpen(true);
                try { expirePause(); } catch (Exception e) { throw new AssertionError(e); }
            }
        });
        var result = fork(() -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L, 1L, 2L)));
        awaitPolicyPark(workers.getLast());
        assertEquals(1, source.calls.get()); assertFalse(result.isDone());
        RuntimeLoadPolicy.setGuiScreenOpen(false); expirePause();
        assertEquals(Set.of(0L, 1L, 2L), result.get(3, TimeUnit.SECONDS).keySet());
        assertEquals(3, source.calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"interrupt", "epoch", "stop"})
    void cancellationEscapesForegroundWaitWithoutReadingWorld(String kind) throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true); expirePause();
        var source = source(true, () -> {});
        var result = fork(() -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L)));
        Thread worker = workers.getLast(); awaitPolicyPark(worker);
        switch (kind) {
            case "interrupt" -> worker.interrupt();
            case "epoch" -> ThreadPoolManager.invalidateRuntimeEpoch();
            case "stop" -> RoadLifecycle.beginStopping();
            default -> fail("未知取消夹具");
        }
        assertThrows(CancellationException.class, () -> result.get(3, TimeUnit.SECONDS));
        assertTrue(RuntimeLoadPolicy.isGuiScreenOpen(), "必须在前台仍暂停时退出");
        assertEquals(0, source.calls.get());
    }

    @Test void epochChangingDuringLastReadDoesNotReportGridSuccess() {
        var source = source(true, ThreadPoolManager::invalidateRuntimeEpoch);
        List<AccurateSamplingProgress.Batch> progress = new ArrayList<>();
        assertThrows(CancellationException.class, () -> CpuAccurateHeightBackend.fromSource(source)
                .sampleGrid(new AccurateHeightGridRequest(0, 0, 1, 1, 1), progress::add));
        assertEquals(1, source.calls.get()); assertTrue(progress.isEmpty());
    }

    @Test void readFailureReleasesPermitAndNextRequestStillWorks() {
        var fault = new IllegalStateException("read failed");
        var broken = source(true, () -> { throw fault; });
        assertSame(fault, assertThrows(IllegalStateException.class,
                () -> CpuAccurateHeightBackend.fromSource(broken).sampleChunks(List.of(0L))));
        assertEquals(0, activeUnchecked().get());
        var good = source(true, () -> {});
        assertEquals(Set.of(1L), CpuAccurateHeightBackend.fromSource(good).sampleChunks(List.of(1L)).keySet());
        assertEquals(1, good.calls.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mapReadKeepsScreenExemptionButStillWaitsForChunky(boolean chunky) throws Exception {
        RuntimeLoadPolicy.setGuiScreenOpen(true);
        RuntimeLoadPolicy.setChunkyExclusiveActive(chunky);
        expirePause();
        var source = source(true, () -> {});
        var result = fork(() -> withRole(ThreadPoolManager.TaskRole.MAP_READ,
                () -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L))));
        if (chunky) {
            awaitPolicyPark(workers.getLast());
            assertEquals(0, source.calls.get());
            workers.getLast().interrupt();
            assertThrows(CancellationException.class, () -> result.get(3, TimeUnit.SECONDS));
        } else {
            assertEquals(Set.of(0L), result.get(3, TimeUnit.SECONDS).keySet());
            assertEquals(1, source.calls.get());
        }
    }

    @Test void alreadyInterruptedCallerCannotReadAnyColumn() {
        var source = source(false, () -> {});
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> CpuAccurateHeightBackend.fromSource(source)
                    .samplePositions(List.of(BlockPos.ZERO)));
            assertTrue(Thread.currentThread().isInterrupted(), "取消检查不能吞掉中断标记");
            assertEquals(0, source.calls.get());
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void startupAndPreflightKeepParallelExecutionWithoutGameplayPermit(boolean startup) throws Exception {
        if (startup) setStartup(true); else RuntimeLoadPolicy.setChunkyPreflightActive(true);
        RuntimeLoadPolicy.setGuiScreenOpen(true);
        // 先占住唯一游玩许可。预热不能等这个许可，也不能让界面暂停自己。
        active().set(1);
        try {
            CountDownLatch parallelTwo = new CountDownLatch(2);
            Set<Thread> samplingThreads = ConcurrentHashMap.newKeySet();
            var source = source(true, () -> {
                assertEquals(1, activeUnchecked().get());
                samplingThreads.add(Thread.currentThread());
                parallelTwo.countDown();
                await(parallelTwo);
            });
            var result = fork(() -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L, 1L, 2L)));
            assertEquals(Set.of(0L, 1L, 2L), result.get(3, TimeUnit.SECONDS).keySet());
            assertEquals(3, source.calls.get());
            assertTrue(samplingThreads.size() >= 2, "预热必须保留实际并行，而不只是在方法名里叫parallel");
            assertEquals(1, active().get());
        } finally { active().set(0); }
    }

    @Test void singleThreadParentCompletesWithoutWaitingForItsOwnExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var source = source(true, () -> {});
            var result = executor.submit(() -> CpuAccurateHeightBackend.fromSource(source).sampleChunks(List.of(0L, 1L, 2L)));
            assertEquals(Set.of(0L, 1L, 2L), result.get(3, TimeUnit.SECONDS).keySet());
            assertEquals(3, source.calls.get());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    private static CpuAccurateHeightBackendTest.Source source(boolean columns, Runnable read) {
        return new CpuAccurateHeightBackendTest.Source(columns) {
            @Override public AccurateHeightChunk sampleColumns(int x, int z, int[] cols) {
                read.run(); return super.sampleColumns(x, z, cols);
            }
            @Override public AccurateHeightSample sampleBaseHeights(int x, int z) {
                read.run(); return super.sampleBaseHeights(x, z);
            }
        };
    }

    private <T> CompletableFuture<T> fork(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, "sampling-budget-test-" + workers.size());
        workers.add(worker); worker.start(); return result;
    }

    private static void awaitPolicyPark(Thread worker) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (worker.getState() == Thread.State.TIMED_WAITING && Arrays.stream(worker.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(RuntimeLoadPolicy.class.getName()))) return;
            Thread.yield();
        }
        fail("工作线程没有真正进入生产预算等待: " + worker.getState());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("fixture interrupted"); }
    }

    private static AtomicInteger activeUnchecked() {
        try { return active(); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static AtomicInteger active() throws Exception {
        Field field = RuntimeLoadPolicy.class.getDeclaredField("ACTIVE_COARSE_UNITS");
        field.setAccessible(true); return (AtomicInteger) field.get(null);
    }
    private static void expirePause() throws Exception {
        for (String name : List.of("NEXT_REFRESH", "NEXT_COORDINATOR_REFRESH", "RECOVERY_UNTIL")) {
            Field field = RuntimeLoadPolicy.class.getDeclaredField(name);
            field.setAccessible(true); ((AtomicLong) field.get(null)).set(0);
        }
    }
    private static void setStartup(boolean value) throws Exception {
        Field field = InitialGenManager.class.getDeclaredField("active");
        field.setAccessible(true); field.setBoolean(null, value);
    }

    private static <T> T withRole(ThreadPoolManager.TaskRole role, Callable<T> action) throws Exception {
        Field field = ThreadPoolManager.class.getDeclaredField("CURRENT_ROLE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var local = (ThreadLocal<ThreadPoolManager.TaskRole>) field.get(null);
        var saved = local.get(); local.set(role);
        try { return action.call(); } finally { local.set(saved); }
    }
}
