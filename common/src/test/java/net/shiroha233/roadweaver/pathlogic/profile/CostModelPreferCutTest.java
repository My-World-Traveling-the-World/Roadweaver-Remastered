/* 文件职责：钉住 DP 代价模型的 prefer-cut 红线 —— 填方不许比挖方便宜。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代价模型测试（2026-11-14，审计 NEW-7）。
 *
 * <h2>这条红线是什么</h2>
 * <p>旧版（oracle）的 prefer-cut 是<b>硬约束</b>：{@code limits.fill()} 默认 0，
 * 剖面只许降不许升 —— {@code RoadGradePlanner:369} 与 {@code RoadSnapService:2224}
 * 的注释都写着「只降不升，绝不会造出天桥」。</p>
 *
 * <p>重构版把这件事交给 DP 的代价函数，于是偏好必须体现在<b>权重次序</b>上：
 * 同一深度下填方必须严格比挖方贵。2026-11-14 之前默认值是
 * {@code (cutWeight=1.0, fillWeight=0.7)} —— 填方比挖方便宜 30%，
 * 语义写反了，DP 会系统性选择「垫土抬高」并吐出长距离人工路堤与天桥。</p>
 *
 * @see CostModel
 */
class CostModelPreferCutTest {

    @Test
    void 默认权重下填方必须比挖方贵() {
        CostModel.Settings s = CostModel.Settings.defaults();

        assertTrue(s.fillWeight() > s.cutWeight(),
                "prefer-cut 要求 fillWeight > cutWeight，实际 cut=" + s.cutWeight()
                        + " fill=" + s.fillWeight() + "（写反了会造天桥路堤）");

        // 逐深度验证：同一深度填方严格更贵
        for (int d = 1; d <= 40; d++) {
            double fill = CostModel.fillCost(d, s);
            double cut = CostModel.cutCost(d, s);
            assertTrue(fill > cut,
                    "深度 " + d + " 格时填方代价必须严格大于挖方：fill=" + fill + " cut=" + cut);
        }
    }

    @Test
    void 深挖惩罚仍按阈值放大() {
        CostModel.Settings s = CostModel.Settings.defaults();
        int threshold = s.deepThreshold();

        // 阈值内：线性
        assertEquals(s.cutWeight() * threshold, CostModel.cutCost(threshold, s), 1e-9,
                "阈值处应恰好是 weight × threshold");
        // 超过阈值：多出的部分乘 deepMultiplier
        double oneMore = CostModel.cutCost(threshold + 1, s);
        assertEquals(s.cutWeight() * threshold + s.cutWeight() * s.deepMultiplier(),
                oneMore, 1e-9,
                "超过阈值 1 格的部分必须按 deepMultiplier 加权");
        assertTrue(oneMore - CostModel.cutCost(threshold, s) > s.cutWeight(),
                "深挖边际代价必须大于浅挖边际代价");
    }

    @Test
    void 不填不挖时代价为零() {
        CostModel.Settings s = CostModel.Settings.defaults();
        assertEquals(0.0, CostModel.fillCost(0, s), 1e-12, "不填不挖代价为 0");
        assertEquals(0.0, CostModel.cutCost(0, s), 1e-12, "不填不挖代价为 0");
        assertEquals(0.0, CostModel.fillCost(-3, s), 1e-12, "负深度按 0 处理");
    }

    @Test
    void 地形矩阵按填挖分别计价() {
        CostModel.Settings s = CostModel.Settings.defaults();
        int[] terrain = {64};
        int[] lo = {62};                       // 允许 62..66
        int[] hi = {66};
        double[][] cost = CostModel.fromTerrain(terrain, lo, hi, s);

        assertEquals(5, cost[0].length, "高度窗口应为 5 格");
        assertEquals(0.0, cost[0][2], 1e-12, "y=64（贴地）代价 0");
        assertEquals(CostModel.fillCost(1, s), cost[0][3], 1e-12, "y=65 是填 1");
        assertEquals(CostModel.fillCost(2, s), cost[0][4], 1e-12, "y=66 是填 2");
        assertEquals(CostModel.cutCost(1, s), cost[0][1], 1e-12, "y=63 是挖 1");
        assertEquals(CostModel.cutCost(2, s), cost[0][0], 1e-12, "y=62 是挖 2");

        // 红线：同样偏离 2 格，填比挖贵
        assertTrue(cost[0][4] > cost[0][0],
                "同样 2 格，填（y=66）必须比挖（y=62）贵 —— prefer-cut");
    }
}