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

    // ══════════ 二、★ EMPTY 快照：同一个坑的第二半（2026-11-15 修） ══════════
    //
    // 2026-11-13 那次修复只覆盖了 `snapshot == null`，但 activatePlanningSnapshot
    // 在「该路线的快照还没准备好」时兜底写进去的是 PlanningSnapshot.EMPTY —— **非 null**。
    // 于是 EMPTY 这条路径照样完全静默，与「附近真没建筑」同形。
    // 本节把 EMPTY 也钉进可观测范围。

    /** 在当前线程装一个 EMPTY 快照，跑完一定摘掉（ThreadLocal 会污染后续用例）。 */
    private static void withEmptySnapshot(Runnable body) {
        StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(PlanningSnapshot.EMPTY);
        try {
            body.run();
        } finally {
            StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
        }
    }

    @Test
    void EMPTY_快照必须被判为不可用() {
        assertFalse(StructureAvoidanceCache.isUsableSnapshot(null),
                "null 不可用");
        assertFalse(StructureAvoidanceCache.isUsableSnapshot(PlanningSnapshot.EMPTY),
                "★ EMPTY 是**非 null 的空快照**，它同样什么都不挡 —— "
                        + "只看 != null 会把它当成「有快照」，这正是 2026-11-13 漏掉的那一半");
    }

    @Test
    void EMPTY_快照下的查询必须返回_false_且计入失明() {
        StructureAvoidanceCache.resetBlindQueryCount();
        withEmptySnapshot(() -> {
            boolean r = StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(0, 64, 0));
            assertFalse(r,
                    "返回值语义不能变：EMPTY 下仍然是 fail-open 的 false，"
                            + "改成 true 会把整个路网判成处处不可铺");
            assertEquals(1, StructureAvoidanceCache.blindQueryCount(),
                    "★ EMPTY 查询必须计入失明 —— 否则「快照没准备好」与「附近真没建筑」"
                            + "在计数上仍然完全同形");
            StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(16, 64, 0));
            assertEquals(2, StructureAvoidanceCache.blindQueryCount(),
                    "每一次 EMPTY 查询都要累加");
        });
    }

    @Test
    void EMPTY_下带边距重载同样计数() {
        StructureAvoidanceCache.resetBlindQueryCount();
        withEmptySnapshot(() -> {
            assertFalse(StructureAvoidanceCache.shouldAvoidPlanning(new BlockPos(0, 64, 0), 8));
            assertEquals(1, StructureAvoidanceCache.blindQueryCount(),
                    "带边距重载也要覆盖，否则那条调用路径继续隐形");
        });
    }

    @Test
    void EMPTY_下村庄群判定也不挡() {
        withEmptySnapshot(() -> assertFalse(
                ObstacleJudge.blocksVillageCluster(new BlockPos(0, 64, 0), 4),
                "空快照当然找不到村庄群 —— 返回值与改前一致，只是不再白查一次"));
    }

    @Test
    void EMPTY_下碰撞描述必须说没有可用快照() {
        withEmptySnapshot(() -> {
            assertEquals("no-active-snapshot",
                    ObstacleJudge.describePlanningCollision(new BlockPos(0, 64, 0)),
                    "★ 观测层修正：改前 EMPTY 会走到 describeCollision 并返回「没撞」，"
                            + "与「没有可用快照」同形 —— 正是「把没生效误读成没贡献」那句");
            assertEquals("no-active-snapshot",
                    ObstacleJudge.describePlanningCollision(
                            new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                    "跨边距版本同理");
        });
    }

    @Test
    void EMPTY_下碰撞盒为_null() {
        withEmptySnapshot(() -> assertTrue(
                ObstacleJudge.planningCollisionBox(new BlockPos(0, 64, 0), 4) == null,
                "空快照找不到碰撞盒 —— 与改前等价（改前会查两次再返回 null）"));
    }

    @Test
    void 快照存在性查询目前仍把_EMPTY_判成有快照_B方案待做() {
        // 这条是**现状记录**，不是「应该如此」。
        // B 方案（改成 isUsableSnapshot）会让 structureSnapshotAvailable 从 true 变 false，
        // 进而把 ModeDpBridge 的 AB 日志从 snapshot=ok 改成 snapshot=absent（变正确），
        // 但那会改变批 2 既往 AB 结论的解读口径，需要真机数据配合。
        // ★ 谁做了 B 方案，就必须把这条用例改成 assertEquals(false, ...) 并在台账里说明。
        withEmptySnapshot(() -> assertTrue(
                StructureAvoidanceCache.planningSnapshotPresent(),
                "当前实现仍是 `!= null` ⇒ EMPTY 被当成「有快照」。"
                        + "这条红掉说明有人做了 B 方案 —— 请同步更新批 2 的 AB 结论口径"));
    }
}