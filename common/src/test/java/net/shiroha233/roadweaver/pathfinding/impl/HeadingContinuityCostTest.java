package net.shiroha233.roadweaver.pathfinding.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航向连续性代价（0.9.030 / Z-2「爱平缓盘旋」）的四条不变量。
 *
 * <p>这是「绕远但一路缓弯」的机械定义：
 * 同侧续弯（盘山长弧）便宜，横向速度换向（左-右-左）贵，短腿换向最贵。
 */
class HeadingContinuityCostTest {

    private static final double FLIP = HeadingContinuityCost.DEFAULT_FLIP_COST;
    private static final double CURVE = HeadingContinuityCost.DEFAULT_CURVE_COST;
    private static final double SHORT = HeadingContinuityCost.DEFAULT_SHORT_LEG_BLOCKS;
    private static final double MULT = HeadingContinuityCost.DEFAULT_SHORT_LEG_MULTIPLIER;

    /** 同侧续弯 = 长弧，必须只收很低的曲线价。 */
    @Test
    void sameSideCurveIsCheap() {
        double ab = HeadingContinuityCost.cross(8, 0, 8, 8);   // 先向东，再向东北（向左偏）
        double bc = HeadingContinuityCost.cross(8, 8, 0, 8);   // 再向北（继续向左偏）
        assertTrue(HeadingContinuityCost.isSameSideCurve(ab, bc), "连续两次左偏应是同侧续弯");
        assertFalse(HeadingContinuityCost.isFlip(ab, bc), "同侧续弯不得判为换向");

        double cost = HeadingContinuityCost.cost(ab, bc, 64.0D, FLIP, CURVE, SHORT, MULT);
        assertEquals(CURVE, cost, 1e-9D, "同侧续弯只收 CURVE 价（鼓励长弧）");
    }

    /** 换向 = 抖动，收 FLIP 价；且不得误判为同侧续弯。 */
    @Test
    void flipIsCharged() {
        double ab = HeadingContinuityCost.cross(8, 0, 8, 8);   // 左偏
        double bc = HeadingContinuityCost.cross(8, 8, 8, -8);  // 右偏（换向）
        assertTrue(HeadingContinuityCost.isFlip(ab, bc), "左-右必须是换向");
        assertFalse(HeadingContinuityCost.isSameSideCurve(ab, bc), "换向不得判为同侧续弯");

        double cost = HeadingContinuityCost.cost(ab, bc, 64.0D, FLIP, CURVE, SHORT, MULT);
        assertEquals(FLIP, cost, 1e-9D, "长腿换向收 FLIP 价");
    }

    /** 短腿换向最贵：上一段 < 阈值时乘短腿系数。 */
    @Test
    void shortLegFlipIsMostExpensive() {
        double ab = HeadingContinuityCost.cross(8, 0, 8, 8);
        double bc = HeadingContinuityCost.cross(8, 8, 8, -8);
        double cost = HeadingContinuityCost.cost(ab, bc, 8.0D, FLIP, CURVE, SHORT, MULT);
        assertEquals(FLIP * MULT, cost, 1e-9D, "短腿换向应乘短腿系数");
    }

    /** 直行免费：cross≈0 时不收任何价。 */
    @Test
    void straightIsFree() {
        double ab = HeadingContinuityCost.cross(8, 0, 8, 0);
        double bc = HeadingContinuityCost.cross(8, 0, 8, 0);
        assertEquals(0.0D, HeadingContinuityCost.cost(ab, bc, 64.0D, FLIP, CURVE, SHORT, MULT), 1e-9D,
                "直行必须免费");
    }

    /** 配置全 0 时整族关闭（与旧行为逐位相同）。 */
    @Test
    void zeroWeightsDisableTheWholeFamily() {
        double ab = HeadingContinuityCost.cross(8, 0, 8, 8);
        double bc = HeadingContinuityCost.cross(8, 8, 8, -8);
        assertEquals(0.0D, HeadingContinuityCost.cost(ab, bc, 8.0D, 0.0D, 0.0D, SHORT, MULT), 1e-9D,
                "flipCost=curveCost=0 时不得产生任何航向代价");
    }
}
