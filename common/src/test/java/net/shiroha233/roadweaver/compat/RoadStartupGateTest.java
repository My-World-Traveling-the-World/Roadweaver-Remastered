/* 文件职责：只验证道路消费河流、长城前置条件，其他模组自身行为由各自测试负责。 */
package net.shiroha233.roadweaver.compat;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadStartupGateTest {
    @AfterEach void reset() {
        RoadStartupGate.cancelPending();
        RiverReadinessApi.reset();
    }

    private static final class Fixture {
        boolean rivers;
        boolean wall;
        int wallQueries;
        int wallWaits;
        final ArrayDeque<Runnable> polls = new ArrayDeque<>();
        final CompletableFuture<Void> wallFuture = new CompletableFuture<>();
        CompletableFuture<Void> start() {
            return RoadStartupGate.await(() -> rivers, () -> { wallQueries++; return wall; },
                    () -> { wallWaits++; return wallFuture; }, (task, delay) -> polls.add(task));
        }
        void poll() {
            assertFalse(polls.isEmpty(), "必须经过实际提交的前置轮询");
            polls.remove().run();
        }
    }

    @Test void withoutWallRoadStillWaitsForRivers() {
        Fixture f = new Fixture();
        f.wall = true; // 长城缺席在道路适配层表现为已满足，不代表河流完成。
        var gate = f.start();
        assertFalse(gate.isDone());
        assertEquals(0, f.wallQueries);
        f.rivers = true;
        f.poll();
        assertTrue(gate.isDone());
        assertFalse(gate.isCompletedExceptionally());
        assertEquals(0, f.wallWaits);
        assertTrue(f.polls.isEmpty());
    }

    @Test void installedWallIsOnlyAwaitedAfterRiversAndRoadWaitsForBoth() {
        Fixture f = new Fixture();
        var gate = f.start();
        assertEquals(0, f.wallWaits);
        f.rivers = true;
        f.poll();
        assertEquals(1, f.wallWaits);
        assertFalse(gate.isDone(), "河流好了但长城没好，道路不能开始");
        f.wall = true;
        f.wallFuture.complete(null);
        assertTrue(gate.isDone());
        assertFalse(gate.isCompletedExceptionally());
    }

    @Test void completedPrerequisitesKeepSynchronousStartupPath() {
        Fixture f = new Fixture();
        f.rivers = true;
        f.wall = true;
        var gate = f.start();
        assertTrue(gate.isDone());
        assertFalse(gate.isCompletedExceptionally());
        assertTrue(f.polls.isEmpty());
    }

    @Test void wallTimeoutReleaseCannotPretendTheWallIsReady() {
        Fixture f = new Fixture();
        f.rivers = true;
        var gate = f.start();
        f.wallFuture.complete(null);
        assertTrue(gate.isCompletedExceptionally(), "旧门的放弃等待不能伪装为就绪");
    }

    @Test void stopCancelsRiverWaitAndLateRiverReadinessCannotAdvance() {
        Fixture f = new Fixture();
        var gate = f.start();
        RoadStartupGate.cancelPending();
        assertTrue(gate.isCancelled());
        f.rivers = true;
        f.poll();
        assertEquals(0, f.wallQueries);
        assertEquals(0, f.wallWaits);
        assertTrue(f.polls.isEmpty());
    }

    @Test void stopCancelsOwnedWallWaitAndNextSessionCanStart() {
        Fixture old = new Fixture();
        old.rivers = true;
        var gate = old.start();
        RoadStartupGate.cancelPending();
        assertTrue(gate.isCancelled());
        assertTrue(old.wallFuture.isCancelled());
        Fixture next = new Fixture();
        next.rivers = true;
        next.wall = true;
        var nextGate = next.start();
        assertTrue(nextGate.isDone());
        assertFalse(nextGate.isCompletedExceptionally());
    }

    @Test void schedulerFailureFailsInsteadOfStartingBeforeRivers() {
        var gate = RoadStartupGate.await(() -> false, () -> fail("不能提前咨询长城"),
                () -> fail("不能提前等长城"), (task, delay) -> { throw new IllegalStateException("stopped"); });
        assertTrue(gate.isCompletedExceptionally());
    }

    @Test void riverFailureAfterWallWaitCannotUseObsoleteReadyBroadcast() {
        Fixture f = new Fixture();
        f.rivers = true;
        var gate = f.start();
        assertEquals(1, f.wallWaits);
        f.rivers = false;
        f.wall = true;
        RiverReadinessApi.markReady("streams");
        f.wallFuture.complete(null);
        assertTrue(gate.isCompletedExceptionally());
        assertEquals(1, f.wallQueries, "河流失效后不能再读取长城成功状态");
    }

    @Test void wallProbeFailureAfterCompletionSettlesRoadFailure() {
        CompletableFuture<Void> wall = new CompletableFuture<>();
        int[] calls = {0};
        var gate = RoadStartupGate.await(() -> true, () -> {
            if (++calls[0] == 1) return false;
            throw new IllegalStateException("probe broke during completion");
        }, () -> wall, (task, delay) -> fail("河流已经就绪，无需重排队"));
        wall.complete(null);
        assertTrue(gate.isCompletedExceptionally());
        assertEquals(2, calls[0]);
    }

    @Test void fabricStartupActuallyUsesOrderedGateAndStopCancelsIt() throws java.io.IOException {
        // 这是道路自己的接线守卫；三模组行为 Fake 只测消费端，不扫描别的仓库冒充覆盖。
        var root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        String entry = java.nio.file.Files.readString(root.resolve(
                "fabric/src/main/java/net/shiroha233/roadweaver/mixin/fabric/MinecraftServerMixin.java"));
        String stop = java.nio.file.Files.readString(root.resolve(
                "fabric/src/main/java/net/shiroha233/roadweaver/planning/fabric/ServerPlanningHooks.java"));
        assertTrue(entry.contains("var prerequisites = RoadStartupGate.await("));
        assertTrue(entry.contains("RoadRiverStartupProbe.forWorld(level, riversInstalled)"));
        assertTrue(entry.contains("prerequisites.isDone() && !prerequisites.isCompletedExceptionally()"));
        assertTrue(entry.contains("if (failure != null)"));
        assertTrue(entry.contains("RoadLifecycle.isStopping()"));
        assertTrue(stop.contains("RoadStartupGate.cancelPending();"));
    }
}
