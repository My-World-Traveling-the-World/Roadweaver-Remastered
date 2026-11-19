package net.shiroha233.roadweaver.planning.impl;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** 故障和取消不能变成另一种算法的成功连线；小域和按需大域分别经过采样/求解异常边界。 */
class PipelinePlannerFailureTest {
    @ParameterizedTest
    @CsvSource({"128,false", "128,true", "131000,false", "131000,true"})
    void failedDenseSamplingOrLazySolveKeepsOriginalFailure(int span, boolean cancel) {
        RuntimeException cause = cancel ? new CancellationException("主动取消采样")
                : new IllegalStateException("精确采样故障");
        var calls = new AtomicInteger();
        var planner = new PipelinePlanner(8, () -> request -> { calls.incrementAndGet(); throw cause; });
        var thrown = assertThrows(RuntimeException.class, () -> planner.plan(points(span), 48));
        assertSame(cause, thrown, "调用方必须取得原取消或故障，不能收到直线MST或空集合");
        assertEquals(1, calls.get(), "夹具必须实际进入世界采样边界");
    }

    @Test void samplerCreationFailureKeepsOriginalCause() {
        var cause = new IllegalStateException("世界读取器创建失败");
        var planner = new PipelinePlanner(8, () -> { throw cause; });
        assertSame(cause, assertThrows(IllegalStateException.class, () -> planner.plan(points(128), 48)));
    }

    @Test void absentWorldReaderIsFailureForRealWorkButEmptyInputIsStillEmpty() {
        var planner = new PipelinePlanner(8, null);
        assertTrue(planner.plan(List.of(), 48).isEmpty());
        assertTrue(planner.plan(List.of(new BlockPos(0, 64, 0)), 48).isEmpty());
        assertThrows(IllegalStateException.class, () -> planner.plan(points(128), 48));
    }

    @Test void preexistingInterruptDoesNotStartSamplerOrClearTheBit() {
        var calls = new AtomicInteger();
        var planner = new PipelinePlanner(8, () -> { calls.incrementAndGet(); throw new AssertionError("不能再启动采样"); });
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> planner.plan(points(128), 48));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptionDuringSamplingCannotPublishNormalTopology() {
        var planner = new PipelinePlanner(8, () -> request -> {
            var heights = new int[request.sampleCount()];
            Arrays.fill(heights, 64);
            Thread.currentThread().interrupt();
            return new AccurateHeightGrid(request, heights, heights, heights);
        });
        try {
            assertThrows(CancellationException.class, () -> planner.plan(points(128), 48));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static List<BlockPos> points(int span) {
        return List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 24), new BlockPos(span, 64, span));
    }
}
