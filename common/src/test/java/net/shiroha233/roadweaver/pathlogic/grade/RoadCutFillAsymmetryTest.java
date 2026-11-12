package net.shiroha233.roadweaver.pathlogic.grade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挖方与填方的上限必须**不对称**（0.9.087）。
 *
 * <h2>真机依据（存档 2026-11-11，半径 128）</h2>
 * <p>5 条 initial connection 因 {@code construction_continuity_repair_failed} 失败。
 * 日志 {@code rejected route after incomplete construction continuity repair}
 * 只打了 {@code maxFillDepth}（全是 2~3，远低于任何上限），真正越界的是
 * <b>maxCutDepth</b>（19 / 25），而那个字段当时没被打出来 —— 于是看起来像"fill 超标"
 * 的假象。</p>
 *
 * <p>两条是真的「路要穿过比它高 19 / 25 格的山脊」：</p>
 * <pre>
 *   seg=816   cut=19  targetY=388  terrainY=407
 *   seg=2200  cut=25  targetY=71   terrainY=96
 * </pre>
 *
 * <h2>为什么挖方可以放宽而填方不行</h2>
 * <ul>
 *   <li><b>挖 = 路堑</b>：山体上开一道槽，路面本身仍平顺连续，两侧是岩壁 ——
 *       现实中山区公路的标准做法（垭口 / 明堑）。</li>
 *   <li><b>填 = 高堤</b>：路面悬在填土堆上，两侧陡坡，视觉突兀，
 *       而且一列没填实就是一个洞。所以必须守在 8 格。</li>
 * </ul>
 */
class RoadCutFillAsymmetryTest {

    @Test
    void deepCutIsNowAnExtendedCentreGrade() {
        // 真机那两个值必须被接受，否则那两条路还是建不出来
        assertTrue(RoadConstructionSafety.isExtendedCenterGrade(19, 3),
                "cut=19（真机 seg=816）必须被视为可建造路堑");
        assertTrue(RoadConstructionSafety.isExtendedCenterGrade(25, 3),
                "cut=25（真机 seg=2200）必须被视为可建造路堑");
    }

    @Test
    void cutBeyondTheLimitIsStillRejected() {
        int limit = RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH;
        assertTrue(RoadConstructionSafety.isExtendedCenterGrade(limit, 0),
                "恰好到上限应当接受");
        assertFalse(RoadConstructionSafety.isExtendedCenterGrade(limit + 1, 0),
                "超过上限仍要拒绝 —— 那才是真的把山挖穿");
    }

    @Test
    void fillStaysCappedAtEight() {
        int fillLimit = RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH;
        assertEquals(8, fillLimit, "填方上限必须保持 8 格：再高就是突兀的高堤");
        assertTrue(RoadConstructionSafety.isExtendedCenterGrade(0, fillLimit));
        assertFalse(RoadConstructionSafety.isExtendedCenterGrade(0, fillLimit + 1));
    }

    @Test
    void cutLimitIsStrictlyWiderThanFillLimit() {
        // 这条不变量就是整个改动的意图：两种土方的观感相反，不能用同一个数
        assertTrue(RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH
                        > RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH,
                "挖方（路堑）上限必须比填方（高堤）宽");
    }

    @Test
    void shallowDepthsKeepTheOriginalBehaviour() {
        // 5 格以内走原来的普通路面路径，不算 extended grade —— 行为零变化
        assertFalse(RoadConstructionSafety.isExtendedCenterGrade(5, 2),
                "cut=5 且 fill=2 都在普通上限内，不应被当成 extended grade");
        assertFalse(RoadConstructionSafety.isExtendedCenterGrade(0, 0));
    }
}
