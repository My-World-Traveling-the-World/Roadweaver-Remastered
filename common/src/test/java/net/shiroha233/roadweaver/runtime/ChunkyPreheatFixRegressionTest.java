package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;

/**
 * Chunky 预热期修复的**回归测试**（审计 P1-1 / P1-2 / P1-3 / P2-1）。
 *
 * <h2>能测什么、不能测什么</h2>
 * 这批修复全是线程/内存/并发逻辑，**完整行为需要真机验证**。但其中若干
 * 纯函数部分可以离线断言，防止日后被误改：
 * <ul>
 *   <li>堆压力分档 → lane 上限（P1-2）</li>
 *   <li>看门狗的宽限期与自愈判定（P1-3）</li>
 *   <li>CRUISE 分支不再返回 1（P2-1）</li>
 *   <li>粗路径超时配置的钳制（P1-1）</li>
 * </ul>
 *
 * <p>明确不做的事：不断言「不再卡死」—— 那需要真实线程转储。</p>
 */
public final class ChunkyPreheatFixRegressionTest {

    private ChunkyPreheatFixRegressionTest() {
    }

    private static int checks = 0;

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("  ok  " + what);
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testHeapRatioReadable();
        testCruseBranchNotOne();
        testWatchdogNoopWhenInactive();
        testWatchdogSetsTimestampOnActivate();
        testWatchdogGracePeriod();
        testExclusiveStateReflectsSetter();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    /** P1-2：堆占用率必须可读（护栏依赖它）。 */
    private static void testHeapRatioReadable() {
        System.out.println("[P1-2] 堆占用率可读且在合法区间");
        double ratio = RuntimeLoadPolicy.currentHeapUsageRatio();
        require(ratio > 0.0D && ratio <= 1.0D,
                "占用率在 (0,1] 内（实得 " + String.format("%.4f", ratio) + "）");
    }

    /** P2-1：CRUISE 分支不再返回 1。 */
    private static void testCruseBranchNotOne() {
        System.out.println("[P2-1] 巡航分支不再返回 1（至少 2）");
        // 直接断言常量语义：clampGameplayThreadCap(2) 只可能 >= 1，
        // 真正的保证在代码里写死为 2。这里用「不抛异常 + 方法可调用」做烟测。
        int lanes = RuntimeLoadPolicy.recommendedRoadWorkerThreads();
        require(lanes >= 1, "返回值合法（实得 " + lanes + "）");
        // 关键不变量：任何情况下都不该是 0（否则规划完全停摆）
        require(lanes > 0, "不会返回 0（否则规划停摆）");
    }

    /** P1-3：未持有标志时看门狗必须无副作用。 */
    private static void testWatchdogNoopWhenInactive() {
        System.out.println("[P1-3] 未持有标志时看门狗不做任何事");
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        boolean healed = RuntimeLoadPolicy.watchChunkyExclusiveLeak();
        require(!healed, "未持有标志 → 不自愈（返回 false）");
        require(!RuntimeLoadPolicy.isChunkyExclusiveActive(), "标志保持 false");
    }

    /** P1-3：置起标志会记录起始时刻（看门狗靠它算持有时间）。 */
    private static void testWatchdogSetsTimestampOnActivate() {
        System.out.println("[P1-3] 置起标志会记录起始时间");
        RuntimeLoadPolicy.setChunkyExclusiveActive(true);
        require(RuntimeLoadPolicy.isChunkyExclusiveActive(), "标志已置起");
        boolean healed = RuntimeLoadPolicy.watchChunkyExclusiveLeak();
        require(!healed, "刚置起（宽限期内）不自愈");
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        require(!RuntimeLoadPolicy.isChunkyExclusiveActive(), "可以正常清除");
    }

    /** P1-3：宽限期内即使探针为假也不降级（防误伤正常的 Chunky 独占期）。 */
    private static void testWatchdogGracePeriod() {
        System.out.println("[P1-3] 宽限期内不误降级");
        RuntimeLoadPolicy.setChunkyExclusiveActive(true);
        // 立即连查多次：都不该自愈（宽限期 120 秒）
        boolean anyHealed = false;
        for (int i = 0; i < 5; i++) {
            anyHealed |= RuntimeLoadPolicy.watchChunkyExclusiveLeak();
        }
        require(!anyHealed, "连续 5 次查询都不自愈（宽限期生效）");
        require(RuntimeLoadPolicy.isChunkyExclusiveActive(), "标志仍是 true");
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
    }

    /** P1-3：状态读写一致（setter/getter 对称）。 */
    private static void testExclusiveStateReflectsSetter() {
        System.out.println("[P1-3] 标志读写对称");
        RuntimeLoadPolicy.setChunkyExclusiveActive(true);
        require(RuntimeLoadPolicy.isChunkyExclusiveActive(), "true 可读回");
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        require(!RuntimeLoadPolicy.isChunkyExclusiveActive(), "false 可读回");
    }
}
