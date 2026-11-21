package net.shiroha233.roadweaver.config.sub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PerformanceSchedulingConfigTest {
    @Test
    void zeroThreadCapsSelectAdaptiveMode() {
        PerformanceConfig config = new PerformanceConfig();

        config.setSharedWorkerThreads(0);
        config.sanitize();

        assertEquals(0, config.sharedWorkerThreads());
        // 自适应模式必须跨线程快照保留，不能只测被删除的空限流旋钮。
        assertEquals(0, config.snapshot().sharedWorkerThreads());
    }

    @Test
    void normalAndIdleConcurrencyAreIndependent() {
        PerformanceConfig config = new PerformanceConfig();

        config.setMaxConcurrentGenerations(5);
        config.setIdleConcurrentGenerations(2);
        config.sanitize();

        assertEquals(5, config.maxConcurrentGenerations());
        assertEquals(2, config.idleConcurrentGenerations());
    }

    @Test
    void invalidValuesRemainWithinSchedulingBounds() {
        PerformanceConfig config = new PerformanceConfig();

        config.setSharedWorkerThreads(-1);
        config.setIdleConcurrentGenerations(999);
        config.sanitize();

        assertEquals(0, config.sharedWorkerThreads());
        assertEquals(128, config.idleConcurrentGenerations());
    }

    @Test
    void gameplayGpuConcurrencyCapUsesZeroForAutomaticMode() {
        PerformanceConfig config = new PerformanceConfig();

        config.setOpenclCoarseGpuConcurrencyCap(0);
        config.sanitize();

        assertEquals(0, config.openclCoarseGpuConcurrencyCap());
    }

    @Test
    void gameplayGpuConcurrencyCapAllowsOneAndClampsInvalidValues() {
        PerformanceConfig config = new PerformanceConfig();

        config.setOpenclCoarseGpuConcurrencyCap(1);
        assertEquals(1, config.openclCoarseGpuConcurrencyCap());

        config.setOpenclCoarseGpuConcurrencyCap(99);
        assertEquals(16, config.openclCoarseGpuConcurrencyCap());

        config.setOpenclCoarseGpuConcurrencyCap(-1);
        assertEquals(0, config.openclCoarseGpuConcurrencyCap());
    }

    @Test
    void activeAndIdleGpuIntervalsAreIndependent() {
        // 0.8.341：活跃态与挂机态的提交间隔是**分开的两个键**。
        // 活跃态玩家在操作、对延迟更敏感，通常要比挂机态更保守；
        // 两者必须能各自独立设置，不能互相覆盖。
        PerformanceConfig config = new PerformanceConfig();

        config.setRuntimeCoarseGpuMinIntervalMs(1500);
        config.setRuntimeCoarseGpuActiveMinIntervalMs(3000);
        config.sanitize();

        assertEquals(1500, config.runtimeCoarseGpuMinIntervalMs());
        assertEquals(3000, config.runtimeCoarseGpuActiveMinIntervalMs());
    }

    @Test
    void activeGpuIntervalDefaultsToFifteenHundred() {
        PerformanceConfig config = new PerformanceConfig();
        // 仅 sanitize，不显式设置 → 应保持默认 1500（与挂机态同口径）。
        config.sanitize();
        assertEquals(1500, config.runtimeCoarseGpuActiveMinIntervalMs());
    }

    @Test
    void activeGpuIntervalClampsToValidRange() {
        PerformanceConfig config = new PerformanceConfig();

        config.setRuntimeCoarseGpuActiveMinIntervalMs(-5);
        config.sanitize();
        assertEquals(0, config.runtimeCoarseGpuActiveMinIntervalMs());

        config.setRuntimeCoarseGpuActiveMinIntervalMs(999_999);
        config.sanitize();
        assertEquals(60_000, config.runtimeCoarseGpuActiveMinIntervalMs());
    }

    @Test
    void snapshotCopiesActiveGpuInterval() {
        PerformanceConfig config = new PerformanceConfig();
        config.setRuntimeCoarseGpuActiveMinIntervalMs(4200);

        PerformanceConfig copy = config.snapshot();

        assertEquals(4200, copy.runtimeCoarseGpuActiveMinIntervalMs());
    }
}
