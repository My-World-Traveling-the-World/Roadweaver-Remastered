/* 文件职责：直接运行生产预热门，防止空等、调度失效和取消后继续轮询。 */
package net.shiroha233.roadweaver.compat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadiness;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrewarmGateWorkTest {
    @Test void publicEntryWithoutInstalledWallReturnsAlreadyCompletedFuture() {
        assertFalse(GreatWallPrewarmCompat.wallPresent(), "离线环境必须确认没有安装长城");
        assertFalse(GreatWallPrewarmCompat.isAvailable());
        var gate = GreatWallPrewarmCompat.awaitPrewarmAsync();
        assertTrue(gate.isDone(), "生产入口不能仍先提交五秒宽限再返回");
        assertFalse(gate.isCompletedExceptionally());
    }

    @Test void registeredProviderStillRequiresWaitingWithoutFabricModInventory() {
        GreatWallReadinessRegistry.register(new GreatWallReadiness() {
            @Override public String ownerId() { return "prewarm-gate-test"; }
            @Override public boolean isReady() { return false; }
            @Override public boolean awaitReady(long timeoutMillis) { throw new AssertionError("只能非阻塞查询"); }
            @Override public String progressSignature() { return null; }
        });
        CompletableFuture<Void> gate = null;
        try {
            gate = GreatWallPrewarmCompat.awaitPrewarmAsync();
            assertFalse(gate.isDone(), "注册的 API 提供方也不能被无模组清单的快路径跳过");
        } finally {
            if (gate != null) gate.cancel(false);
            GreatWallReadinessRegistry.unregister("prewarm-gate-test");
        }
    }

    /** 手动推进一次轮询而不睡眠，确保断言真的跨过生产的时间边界。 */
    private static final class Fixture implements PrewarmGateWork.Probe, PrewarmGateWork.Scheduler {
        boolean required = true;
        boolean ready;
        String signature = "working";
        boolean reject;
        boolean throwOnSchedule;
        int queries;
        long millis;
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<PrewarmGateWork.Resolution> resolutions = new ArrayList<>();
        @Override public boolean required() { return required; }
        @Override public boolean ready() { queries++; return ready; }
        @Override public String signature() { return signature; }
        @Override public boolean schedule(Runnable task, long delayMillis) {
            if (throwOnSchedule) throw new RejectedExecutionException("stopped");
            if (reject) return false;
            queue.add(task);
            return true;
        }
        CompletableFuture<Void> start(long timeout) {
            return GreatWallPrewarmCompat.awaitPrewarmAsync(timeout, this, this,
                    () -> TimeUnit.MILLISECONDS.toNanos(millis), resolutions::add);
        }
        void pollAt(long time) {
            millis = time;
            assertFalse(queue.isEmpty(), "必须真正运行已提交的生产轮询");
            queue.remove().run();
        }
        void assertResolved(CompletableFuture<Void> gate, PrewarmGateWork.Resolution reason) {
            assertTrue(gate.isDone(), "门必须结案，不能只打印警告");
            assertFalse(gate.isCompletedExceptionally());
            assertEquals(List.of(reason), resolutions);
            assertTrue(queue.isEmpty(), "结案后不能继续安排查询");
        }
    }

    @Test void absentWallPassesWithoutSchedulingOrGrace() {
        Fixture f = new Fixture();
        f.required = false;
        f.assertResolved(f.start(0), PrewarmGateWork.Resolution.ABSENT);
        assertEquals(0, f.queries);
    }

    @Test void installedWallKeepsStartupGraceAndWaitsForRealCompletion() {
        Fixture f = new Fixture();
        f.ready = true;
        var gate = f.start(0);
        f.pollAt(0);
        f.pollAt(4999);
        assertFalse(gate.isDone(), "过早 ready 不能绕开长城 request 的启动窗口");
        f.ready = false;
        f.pollAt(5000);
        assertFalse(gate.isDone());
        f.ready = true;
        f.pollAt(5250);
        f.assertResolved(gate, PrewarmGateWork.Resolution.READY);
    }

    @Test void firstSubmissionFailureAlwaysCompletesReturnedFuture() {
        for (boolean throwing : List.of(false, true)) {
            Fixture f = new Fixture();
            f.reject = true;
            f.throwOnSchedule = throwing;
            f.assertResolved(f.start(0), PrewarmGateWork.Resolution.POLLER_UNAVAILABLE);
            assertEquals(0, f.queries);
        }
    }

    @Test void laterSubmissionFailureAlsoCompletesTheGate() {
        Fixture f = new Fixture();
        var gate = f.start(0);
        f.reject = true;
        f.pollAt(0);
        f.assertResolved(gate, PrewarmGateWork.Resolution.POLLER_UNAVAILABLE);
    }

    @Test void cancelStopsQueuedPollBeforeQueryingOrRearming() {
        Fixture f = new Fixture();
        var gate = f.start(0);
        f.pollAt(0);
        assertTrue(gate.cancel(false));
        f.pollAt(250);
        assertEquals(1, f.queries);
        assertTrue(f.queue.isEmpty());
        assertTrue(f.resolutions.isEmpty(), "取消不能伪装成成功就绪");
    }

    @Test void explicitTimeoutHasItsOwnObservableResolution() {
        Fixture f = new Fixture();
        var gate = f.start(1000);
        f.pollAt(999);
        assertFalse(gate.isDone());
        f.pollAt(1000);
        f.assertResolved(gate, PrewarmGateWork.Resolution.TIMEOUT);
    }

    @Test void actualProgressResetsDefaultTenMinuteStallWindow() {
        Fixture f = new Fixture();
        var gate = f.start(0);
        f.pollAt(0);
        f.signature = "next-stage";
        f.pollAt(599999);
        f.pollAt(600000);
        assertFalse(gate.isDone(), "有真实进展不能仍按起点判停滞");
        f.pollAt(1199999);
        f.assertResolved(gate, PrewarmGateWork.Resolution.STALLED);
    }

    @Test void missingProgressSignalUsesAbsoluteCapInsteadOfStall() {
        Fixture f = new Fixture();
        f.signature = null;
        var gate = f.start(0);
        f.pollAt(0);
        f.pollAt(600000);
        f.pollAt(1799999);
        assertFalse(gate.isDone());
        f.pollAt(1800000);
        f.assertResolved(gate, PrewarmGateWork.Resolution.ABSOLUTE_CAP);
    }

    @Test void movingProgressCannotBypassDefaultThirtyMinuteCap() {
        Fixture f = new Fixture();
        var gate = f.start(0);
        f.pollAt(0);
        f.signature = "new-progress";
        f.pollAt(1800000);
        f.assertResolved(gate, PrewarmGateWork.Resolution.ABSOLUTE_CAP);
    }
}
