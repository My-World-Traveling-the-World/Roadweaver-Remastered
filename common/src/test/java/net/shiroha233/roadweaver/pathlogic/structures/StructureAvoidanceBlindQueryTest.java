/* 文件职责：钉住「结构避让查询在快照缺失时必须可观测」这条诊断契约。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构避让静默降级的可观测性测试（2026-11-15）。
 *
 * <h2>它守的是「别让失明变成无声」</h2>
 * <p>{@code shouldAvoidPlanning} 返回 false 的两种原因曾经完全同形：</p>
 * <ul>
 *   <li>「这个位置确实没有已验证结构」⇒ 正常，可以铺路；</li>
 *   <li>「本线程根本没装规划快照」⇒ 异常，<b>整条链什么都不会挡</b>。</li>
 * </ul>
 *
 * <p>第二种是真机上出现过的「路穿建筑」病根之一：规划跑在 worker 线程上，
 * 而快照是 {@code ThreadLocal} —— 作用域没铺到就全链失明，
 * 且日志里<b>一个字都没有</b>，排查时只能靠猜。</p>
 *
 * <h2>为什么返回值不改</h2>
 * <p>本测试同时钉住「返回值语义不变」：快照缺失时仍然返回 false。
 * 改成 true 会把整个路网判成处处不可铺 —— 那是把一个诊断问题
 * 换成一场灾难。可观测性改动的边界就在这里。</p>
 */
class StructureAvoidanceBlindQueryTest {

    @Test
    void 无快照时查询返回false且计数增加() {
        StructureAvoidanceCache.resetBlindQueryCount();
        assertEquals(0, StructureAvoidanceCache.blindQueryCount(), "重置后应为 0");

        // 测试线程默认不在规划快照作用域内 —— 正是要覆盖的「失明」场景
        boolean first = StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(0, 64, 0));
        assertFalse(first,
                "快照缺失时必须返回 false —— 返回值语义不能变，"
                        + "否则整个路网会被判成处处不可铺");
        assertEquals(1, StructureAvoidanceCache.blindQueryCount(),
                "失明查询必须被计数 —— 这就是「可见」的来源");

        StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(16, 64, 0));
        assertEquals(2, StructureAvoidanceCache.blindQueryCount(),
                "每一次失明查询都要累加，不能只在首次计一次");
    }

    @Test
    void 带边距的重载同样可观测() {
        StructureAvoidanceCache.resetBlindQueryCount();

        boolean result = StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(0, 64, 0), 8);
        assertFalse(result, "快照缺失时带边距重载也必须返回 false");
        assertEquals(1, StructureAvoidanceCache.blindQueryCount(),
                "两个重载都要计数 —— 只覆盖一个会让另一条调用路径继续隐形");
    }

    @Test
    void 快照存在性查询与失明计数互相独立() {
        StructureAvoidanceCache.resetBlindQueryCount();

        // 只问「有没有快照」不应算作一次失明查询（它本来就是探测用的）
        StructureAvoidanceCache.planningSnapshotPresent();
        assertEquals(0, StructureAvoidanceCache.blindQueryCount(),
                "plottingSnapshotPresent 是探针，不该污染失明计数 —— "
                        + "否则桥隧观测里的探测会让这个数字虚高");
    }

    @Test
    void 重置能同时清掉计数() {
        StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(0, 64, 0));
        assertTrue(StructureAvoidanceCache.blindQueryCount() > 0);

        StructureAvoidanceCache.resetBlindQueryCount();
        assertEquals(0, StructureAvoidanceCache.blindQueryCount(),
                "重置必须归零 —— 每个测试/每次规划会话需要干净的起点");
    }
}