package net.shiroha233.roadweaver.pathlogic.grade;

import net.shiroha233.roadweaver.core.constants.PathfindingConstants;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「同一物理量只有一个来源」这条不变量。
 *
 * <h2>为什么需要这个测试</h2>
 * <p>2026-11-11 排查存档 {@code 20261111 1704} 的 4 条 initial connection 失败时发现：
 * 规划期与施工前门槛<b>各写了一套填挖上限</b>（{@code RoadGradePlanner.MAX_SURFACE_FILL = 2}
 * 与 {@code RoadConstructionSafety.MAX_FILL_DEPTH = 2}），数值恰好相同所以一直没暴露，
 * 但那是「巧合相同」而不是「同一个来源」。只要有人改动其中一处，两边立刻分叉，
 * 而且是**静默分叉** —— 没有任何日志会告诉你规划按 3、门槛按 2。</p>
 *
 * <p>同类的复制粘贴还有：三个 A* 寻路器各有一份 {@code BIOME_BASE_COST = 12}、
 * 两个 A* 各有一份 {@code HEURISTIC_EPSILON = 0.2}、
 * {@code CoarseHeightAwarePlanner} 与 {@code BasicAStarPathfinder} 各有一份
 * {@code PLANNING_OBSTACLE_COST = 25_000.0}、
 * {@code CorridorBan} 与 {@code CorridorAttract} 各有一份走廊量化位移、
 * {@code RoadDataValidator} 与 {@code RoadSnapService} 各有一份高度步进上限。</p>
 *
 * <p>本测试把这些「必须同源」的关系写成断言。若将来有人又写回一份本地副本，
 * 只要值不同就会红；若值相同则本测试仍然通过 —— 但那时请一并检查是否又出现了
 * 巧合相同的情况（搜索同名常量定义即可）。</p>
 */
class GradeConstantSingleSourceTest {

    @Test
    void gradeSurfaceLimitsComeFromTheConstructionAuthority() {
        // 纵断面的填挖上限必须直接引用施工端的权威常量，不能自己写一份
        assertEquals(RoadConstructionSafety.MAX_FILL_DEPTH, RoadGradePlanner.MAX_SURFACE_FILL,
                "RoadGradePlanner.MAX_SURFACE_FILL 必须等于 RoadConstructionSafety.MAX_FILL_DEPTH");
        assertEquals(RoadConstructionSafety.MAX_CUT_DEPTH, RoadGradePlanner.MAX_SURFACE_CUT,
                "RoadGradePlanner.MAX_SURFACE_CUT 必须等于 RoadConstructionSafety.MAX_CUT_DEPTH");
    }

    @Test
    void constructionAuthorityValuesAreUnchanged() {
        // 这两个数的物理含义：路堤最多填 2 格、路堑最多挖 5 格（中心线）
        assertEquals(2, RoadConstructionSafety.MAX_FILL_DEPTH);
        assertEquals(5, RoadConstructionSafety.MAX_CUT_DEPTH);
        // 挖方的「可建造」上限另有一条更宽的线（路堑），必须严格宽于普通上限
        assertTrue(RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH > RoadConstructionSafety.MAX_CUT_DEPTH,
                "路堑上限必须宽于普通挖方上限");
    }

    @Test
    void pathfindingCostsAreUnchanged() {
        assertEquals(12, PathfindingConstants.WATER_BIOME_BASE_COST,
                "水域群系基础代价改动会直接改变选线偏好");
        assertEquals(0.2D, PathfindingConstants.HEURISTIC_EPSILON, 1e-12);
        assertEquals(25_000.0D, PathfindingConstants.PLANNING_OBSTACLE_COST, 1e-9);
        assertEquals(2, PathfindingConstants.CORRIDOR_QUANT_SHIFT);
        assertEquals(4, PathfindingConstants.CORRIDOR_QUANT,
                "走廊量化 = 1 << shift；4 格量化是向外扩，净空恒落在 [20,23]");
    }

    @Test
    void heightStepLimitsAreUnchanged() {
        assertEquals(2, RoadConstants.MAX_CENTER_STEP);
        assertEquals(1, RoadConstants.MAX_HEIGHT_STEP);
        assertEquals(3, RoadConstants.MAX_HEIGHT_STEP_EXCEPTIONS);
        assertTrue(RoadConstants.MAX_CENTER_STEP > RoadConstants.MAX_HEIGHT_STEP,
                "中心点步进上限必须宽于普通相邻站步进上限");
    }
}
