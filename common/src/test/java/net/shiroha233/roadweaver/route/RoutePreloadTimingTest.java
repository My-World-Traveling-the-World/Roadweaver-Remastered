package net.shiroha233.roadweaver.route;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * 手绘路线「预加载就绪判定」的**时序仿真回归**（★ 2026-09-26）。
 *
 * <h2>为什么需要这个测试</h2>
 * 0.8.391 引入强制加载后，**同一个位置连出过两次 P1**：
 * <ol>
 *   <li><b>R-1（0.8.391 引入，0.8.392 修）</b>：预加载拿**整条路线**的区块，
 *       却由「任意一条连接完成」触发 ⇒ 第一条连接完成时其余连接的 RoadData
 *       还没落盘 ⇒ 那些区块查到空计划 ⇒ 被回填器**永久**标成"无需施工"。</li>
 *   <li><b>R-1'（0.8.392 引入，本次修）</b>：为防"永不就绪"，加了
 *       「等待轮数 &lt; {@code route.segmentCount()}」的上限。
 *       但预加载的自动触发点**只在连接成功回调里**
 *       （{@code RoadGenerationService} 的 {@code road != null} 分支），
 *       **失败连接不触发**。于是 N 段路线失败 1 段 ⇒ 最多触发 N−1 次 &lt; N
 *       ⇒ 上限**永不可达** ⇒ 整条路线（含成功段的区块）永不预加载。</li>
 * </ol>
 *
 * <p>两次都是**同一个根因类别**：用「触发了多少次」去推断「连接处于什么状态」。
 * 这在这类跨阶段时序里根本不成立。所以本次把判定抽成纯函数
 * {@link RouteChunkPreloader#decideReadiness(boolean, int)}，
 * 并在这里**同时钉住新旧两个缺陷** —— 任何一次回归都会立刻变红。</p>
 *
 * <h2>测试策略</h2>
 * 真机上要复现"恰好某条连接失败"极难，但这个缺陷本质上是
 * **几个整数之间的判定**，完全可以离线穷举。所以这里：
 * <ul>
 *   <li>第一条：证明**旧判据**在"1 段失败"时永不放行（记录缺陷）</li>
 *   <li>第二条：证明**新判据**在该场景必然放行</li>
 *   <li>第三条：证明新判据在"真的还在生成"时**确实会等**（不会过早放行）</li>
 *   <li>第四条：穷举 1~8 段 × 0~N 段失败的**全部组合**，确认新判据无死角</li>
 * </ul>
 */
public final class RoutePreloadTimingTest {

    private RoutePreloadTimingTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testOldBoundIsUnreachableWhenOneSegmentFails();
        testNewDecisionProceedsWhenAllSettledButIncomplete();
        testNewDecisionWaitsWhileStillGenerating();
        testNewDecisionExhaustiveNoDeadCorner();

        System.out.println("RoutePreloadTimingTest: PASS");
    }

    /**
     * **记录旧缺陷**： N 段路线、只有 1 段失败时，旧判据（轮数上限）永不放行。
     *
     * <p>旧代码形如：</p>
     * <pre>
     *   int waits = waited(...);                    // 每触发一次 +1
     *   if (waits &lt; Math.max(1, route.segmentCount())) return 0;   // 继续等
     *   // 超轮 ⇒ 放行
     * </pre>
     * <p>而触发只发生在连接**成功**时 ⇒ 成功 N−1 段 ⇒ {@code waits} 峰值 N−1。
     * 判定条件 {@code waits < N} 恒真 ⇒ **永远走不到放行那一行**。</p>
     *
     * <p>同时就绪判定也不会满足（失败段的 RoadData 永远不会有）⇒ 两条出口都不可达。</p>
     */
    private static void testOldBoundIsUnreachableWhenOneSegmentFails() {
        for (int segments = 2; segments <= 8; segments++) {
            int failedSegments = 1;
            int successfulSegments = segments - failedSegments;
            // 旧实现的"触发次数"：只有成功的连接才会触发预加载
            int maxTriggers = successfulSegments;
            // 旧实现的上限
            int oldBound = Math.max(1, segments);

            boolean oldWouldProceed = maxTriggers >= oldBound;
            require(!oldWouldProceed,
                    "旧判据在本场景下理应【永不放行】（这正是 R-1' 的缺陷），"
                            + "但 segments=" + segments + " 时 maxTriggers=" + maxTriggers
                            + " >= oldBound=" + oldBound);
        }
    }

    /**
     * **新判据**：连接已全部结算（{@code active == 0}）但区块未全部有计划
     * ⇒ 必须 {@code PROCEED_PARTIAL} 放行，把有计划的区块铺掉。
     *
     * <p>这正是"有连接失败"的真实场景：等不到就绪，但也不能永远等。</p>
     */
    private static void testNewDecisionProceedsWhenAllSettledButIncomplete() {
        var r = RouteChunkPreloader.decideReadiness(false, 0);
        require(r == RouteChunkPreloader.Readiness.PROCEED_PARTIAL,
                "连接已全部结算且仍有区块无计划时，应放行（PROCEED_PARTIAL），实得 " + r);

        // 与段数无关 —— 新判据根本不含"段数/轮数"
        for (int anySegments = 1; anySegments <= 16; anySegments++) {
            var rr = RouteChunkPreloader.decideReadiness(false, 0);
            require(rr == RouteChunkPreloader.Readiness.PROCEED_PARTIAL,
                    "放行判定不应依赖段数，segments=" + anySegments + " 实得 " + rr);
        }
    }

    /**
     * **新判据**：只要还有连接未结算（{@code active > 0}）就必须等 ——
     * 否则会重演 0.8.391 的 R-1（过早铺 ⇒ 空计划被永久标记）。
     */
    private static void testNewDecisionWaitsWhileStillGenerating() {
        for (int active = 1; active <= 32; active++) {
            var r = RouteChunkPreloader.decideReadiness(false, active);
            require(r == RouteChunkPreloader.Readiness.WAIT,
                    "还有 " + active + " 条连接在跑时必须等待（WAIT），实得 " + r);
        }
        // 已全部有计划时，即便还有连接在跑也应就绪（保守：以计划为准）
        var ready = RouteChunkPreloader.decideReadiness(true, 3);
        require(ready == RouteChunkPreloader.Readiness.READY,
                "全部区块已有计划时应 READY，实得 " + ready);
    }

    /**
     * **穷举**：1~8 段路线 × 0~段数条失败，确认新判据在**任何**组合下
     * 都不会出现"永不放行"（即：要么 READY，要么终将 PROCEED_PARTIAL）。
     *
     * <p>模拟推进：每条连接依次有结论（成功⇒可能带来计划；失败⇒不会）。
     * 断言在**所有连接都结算**之后，判定必然不是 WAIT。</p>
     */
    private static void testNewDecisionExhaustiveNoDeadCorner() {
        for (int segments = 1; segments <= 8; segments++) {
            // 用位掩码穷举每一种"哪些段失败"的组合
            for (int mask = 0; mask < (1 << segments); mask++) {
                int failed = Integer.bitCount(mask);
                int succeeded = segments - failed;
                // 全部成功 ⇒ 所有区块都有计划（理想情况）
                boolean allHavePlan = (failed == 0);
                int active = 0;   // 全部结算完毕

                var r = RouteChunkPreloader.decideReadiness(allHavePlan, active);
                require(r != RouteChunkPreloader.Readiness.WAIT,
                        "全部连接结算后不应再 WAIT（segments=" + segments
                                + ", failed=" + failed + "），实得 " + r);
                if (failed == 0) {
                    require(r == RouteChunkPreloader.Readiness.READY,
                            "全成功应 READY（segments=" + segments + "），实得 " + r);
                } else {
                    require(r == RouteChunkPreloader.Readiness.PROCEED_PARTIAL,
                            "有失败应 PROCEED_PARTIAL（segments=" + segments
                                    + ", failed=" + failed + "），实得 " + r);
                }
                // 另：中途状态（还有 active）必须等
                if (succeeded > 0 || failed > 0) {
                    var mid = RouteChunkPreloader.decideReadiness(false, 1);
                    require(mid == RouteChunkPreloader.Readiness.WAIT,
                            "中途有连接在跑时必须 WAIT，实得 " + mid);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
