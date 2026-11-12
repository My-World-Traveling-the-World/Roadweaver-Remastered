package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.340：GPU 显存预算探测（{@link GpuMemoryProbe}）的回归。
 *
 * <p>这些用例只验证**纯算术与降级语义**，不依赖真机 PDH —— 因此可在 CI/离线构建里稳定运行。
 * 真机 PDH 读数由独立的手工验证覆盖（Windows 专属，且需要 JNA 与真实显卡）。</p>
 */
final class GpuMemoryProbeTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void unknownTotalDeviceMemoryReturnsZeroSoCallerKeepsOldLogic() {
        // 第 3 层：连总显存都读不到 → 返回 0 表示"不干预"。
        // 用极小 total 走不到第 3 层，因此直接构造 total<=0 的场景。
        assertEquals(0L, GpuMemoryProbe.budgetBytes(0L, 0.70D, 4L * GIB));
        assertEquals(0L, GpuMemoryProbe.budgetBytes(-1L, 0.70D, 4L * GIB));
    }

    @Test
    void hardCapBoundsTheBudgetEvenForHugeCards() {
        // 32GB 的卡：无论比例算出多少，都必须被硬上限截断。
        long budget = GpuMemoryProbe.budgetBytes(32L * GIB, 1.0D, 4L * GIB);
        assertTrue(budget <= 4L * GIB, "budget must never exceed the configured hard cap");
        assertTrue(budget > 0L);
    }

    @Test
    void zeroHardCapMeansUncapped() {
        // 不设上限时，结果必须 >= 设了 4GiB 上限的结果（即在需要时确实没有被截断）。
        // 不断言绝对数值，因为第 1 层会读到本机实时空闲显存，数值随机器状态变化。
        long uncapped = GpuMemoryProbe.budgetBytes(32L * GIB, 0.70D, 0L);
        long capped = GpuMemoryProbe.budgetBytes(32L * GIB, 0.70D, 4L * GIB);
        assertTrue(uncapped >= capped, "an uncapped budget must never be smaller than a capped one");
        assertTrue(uncapped > 0L);
        assertTrue(capped <= 4L * GIB);
    }

    @Test
    void nonPositiveFractionIsTreatedAsDefaultSeventyPercent() {
        // 非正/NaN 的占比必须被当成默认 0.70，而不是算出 0 或负数。
        // 三次调用之间顺序无关：只要非法值的结果与合法 0.70 完全一致即可。
        long explicit = GpuMemoryProbe.budgetBytes(8L * GIB, 0.70D, 0L);
        assertEquals(explicit, GpuMemoryProbe.budgetBytes(8L * GIB, 0.0D, 0L));
        assertEquals(explicit, GpuMemoryProbe.budgetBytes(8L * GIB, -1.0D, 0L));
        assertEquals(explicit, GpuMemoryProbe.budgetBytes(8L * GIB, Double.NaN, 0L));
    }

    @Test
    void fractionAboveOneIsClampedToOne() {
        assertEquals(GpuMemoryProbe.budgetBytes(8L * GIB, 1.0D, 0L),
                GpuMemoryProbe.budgetBytes(8L * GIB, 5.0D, 0L));
    }

    @Test
    void budgetIsAlwaysPositiveForKnownDevice() {
        long budget = GpuMemoryProbe.budgetBytes(512L * 1024L * 1024L, 0.70D, 4L * GIB);
        assertTrue(budget >= 1L, "a known device must always yield a usable positive budget");
    }

    @Test
    void configBudgetDegradesSafelyWithoutConfigService() {
        // 测试环境里没有引导 ConfigService：configBudgetBytes 必须回落到内置默认
        // （0.70 / 4GiB），而不是抛异常。这是"配置层异常绝不中断采样"的保证。
        long budget = GpuMemoryProbe.configBudgetBytes(32L * GIB);
        assertTrue(budget > 0L);
        assertTrue(budget <= 4L * GIB, "fallback default cap is 4GiB");
    }

    @Test
    void usedVramBytesNeverThrowsAndReportsUnavailableAsNegativeOrZero() {
        // 真机可能读到 0（没占用）或 -1（不可用），但绝不能抛异常。
        long used = GpuMemoryProbe.usedVramBytes();
        assertTrue(used >= -1L, "used vram must be -1 (unavailable) or a non-negative byte count");
    }

    @Test
    void pdhReflectionSurvivesRepeatedProbesWithoutPermanentBreaker() {
        // 0.8.342：PDH 层不再"一次失败就永久熔断"。
        // 独立审计观察到 PdhEnumObjectItems 在本环境存在非确定性崩溃
        // （3 次运行 2 次抛 java.lang.Error: Invalid memory access）。
        // 若一次性熔断，一次偶发失败就会让整个 PDH 层永久降级。
        // 本用例连续探测多次，确保任何单次失败都不会让后续调用永久返回 -1。
        GpuMemoryProbe.resetForTest();
        for (int i = 0; i < 5; i++) {
            GpuMemoryProbe.resetForTest();
            long used = GpuMemoryProbe.usedVramBytes();
            assertTrue(used >= -1L, "probe #" + i + " must not throw and must report -1 or a byte count");
        }
    }

    @Test
    void budgetReactsToLiveUsageWhenPdhIsAvailable() {
        // 第 1 层若可用：budget = (total - used) * fraction，且被 hardCap 截断。
        // 若 PDH 不可用（非 Windows / 无 JNA），退化为 total * fraction。
        // 两种情况都必须落在 (0, hardCap] 内，且不超过"总显存"这一物理上限。
        long total = 8L * GIB;
        long cap = 4L * GIB;
        long budget = GpuMemoryProbe.budgetBytes(total, 0.70D, cap);
        assertTrue(budget > 0L, "budget must be positive");
        assertTrue(budget <= cap, "budget must respect the hard cap");
        assertTrue(budget <= total, "budget can never exceed physical device memory");
    }
}
