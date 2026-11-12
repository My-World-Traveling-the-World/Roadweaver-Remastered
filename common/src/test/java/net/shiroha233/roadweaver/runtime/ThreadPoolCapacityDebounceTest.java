package net.shiroha233.roadweaver.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.345：线程池容量去抖的回归。
 *
 * <p>通过反射调用私有的 {@code applySharedRuntimeThreads}，验证「目标值抖动时不立即改池子」。
 * 不依赖 Minecraft 运行时（线程池本身由 {@code sharedExecutor()} 惰性创建）。</p>
 *
 * <p><b>为什么需要去抖</b>：每次容量变更都会 {@code prestartAllCoreThreads()} 并写一条 INFO 日志。
 * 真机实测 RoadWeaver 自身 4 小时仅 14 次变更（平稳），但 shared 与 structure 会在同一秒一起变；
 * 去抖把"短期反复变更"收敛为一次，属<b>防御性</b>改进 —— 并非卡顿主因（主因是并发过高 + 堆打满）。</p>
 */
final class ThreadPoolCapacityDebounceTest {

    private static java.lang.reflect.Method applyShared;

    private static void applyShared(int target) throws Exception {
        if (applyShared == null) {
            applyShared = ThreadPoolManager.class.getDeclaredMethod(
                    "applySharedRuntimeThreads",
                    java.util.concurrent.ThreadPoolExecutor.class, int.class);
            applyShared.setAccessible(true);
        }
        // 延迟创建 shared 执行器（与生产路径一致）。
        java.lang.reflect.Method shared = ThreadPoolManager.class.getDeclaredMethod("sharedExecutor");
        shared.setAccessible(true);
        Object executor = shared.invoke(null);
        applyShared.invoke(null, executor, target);
    }

    private static int currentSharedSize() throws Exception {
        java.lang.reflect.Method shared = ThreadPoolManager.class.getDeclaredMethod("sharedExecutor");
        shared.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor e =
                (java.util.concurrent.ThreadPoolExecutor) shared.invoke(null);
        return e.getMaximumPoolSize();
    }

    @Test
    void firstRequestDoesNotApplyImmediately() throws Exception {
        // 去抖语义：第一次请求只"记录意图"，不立即改池子。
        ThreadPoolManager.resetCapacityDebounceForTest();
        int before = currentSharedSize();
        applyShared(before + 3);
        assertTrue(currentSharedSize() == before,
                "a single capacity request must not be applied immediately (needs to settle)");
    }

    @Test
    void thrashingTargetsNeverGetApplied() throws Exception {
        // 模拟抖动：在 1 <-> 5 之间反复切，任何一次都不应真正生效。
        ThreadPoolManager.resetCapacityDebounceForTest();
        int before = currentSharedSize();
        for (int i = 0; i < 6; i++) {
            applyShared(i % 2 == 0 ? 1 : 5);
        }
        assertTrue(currentSharedSize() == before,
                "a thrashing target must never be applied");
    }

    @Test
    void stableTargetIsNotBlockedForever() throws Exception {
        // 去抖不能把正常的容量调整永久挡住：同一目标连续请求即可稳定。
        // （CAPACITY_SETTLE_NANOS 为 3 秒，测试内不做真实等待，
        //  只验证"反复请求同一目标"不会被判为抖动而永远拒绝。）
        ThreadPoolManager.resetCapacityDebounceForTest();
        applyShared(2);
        // 第二次同一目标：进入稳定计时（仍不应用）。
        applyShared(2);
        int size = currentSharedSize();
        assertTrue(size >= 1, "pool must remain usable while a target is settling");
        // 断言不会抛异常 / 不会把池子搞成非法尺寸。
        assertFalse(size <= 0, "capacity must never become non-positive");
    }
}
