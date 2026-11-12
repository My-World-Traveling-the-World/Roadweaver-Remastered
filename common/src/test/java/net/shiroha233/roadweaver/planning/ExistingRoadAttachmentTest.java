package net.shiroha233.roadweaver.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.079「失败连接就近接入已有道路」的决策边界。
 *
 * <p>真机依据（存档 20261111 1516，半径 128 那局）：23 条连接里 3 条彻底失败，
 * 但它们到最近已有道路的距离是 0 / 16.6 / 49.2 / 55.0 / 64.3 / 872.5 格。
 * 本类把「什么情况下值得改接」逐条钉住，防止以后调参把边界搞坏。</p>
 */
class ExistingRoadAttachmentTest {

    private static final int NEVER = Integer.MAX_VALUE;   // 搜索半径内没有道路站点

    @Test
    void attachesWhenBothEndpointsAreNearExistingRoad() {
        // 真机第 1 条：(288,-1616)->(944,-1184)，两端 16.6 / 49.2 格
        assertTrue(ExistingRoadAttachment.shouldAttach(17, 49, 700, 784),
                "两端都远离道路但都在半径内时应改接");
    }

    @Test
    void attachesWhenOnlyOneEndpointIsNear() {
        // 真机第 3 条：(32,-64)->(-624,-944)，起点已在路上，终点 55 格
        assertTrue(ExistingRoadAttachment.shouldAttach(0, 55, 900, 1098),
                "一端已在路上、另一端可接入时应改接");
    }

    @Test
    void refusesWhenBothEndpointsAreAlreadyOnRoad() {
        // 两端都 <= ROAD_SNAP_THRESHOLD(12)：本质已连通，再生成就是碎路
        assertFalse(ExistingRoadAttachment.shouldAttach(0, 0, 500, 500));
        assertFalse(ExistingRoadAttachment.shouldAttach(12, 12, 500, 500));
    }

    @Test
    void refusesWhenNoRoadStationFoundOnEitherEnd() {
        // 真机第 2 条终点 872.5 格 > 256 半径；如果两端都搜不到就必须保持失败
        assertFalse(ExistingRoadAttachment.shouldAttach(NEVER, NEVER, 700, 1000));
    }

    @Test
    void attachesWithSingleSideAvailable() {
        // 一端搜到、另一端搜不到：仍可只接那一边
        assertTrue(ExistingRoadAttachment.shouldAttach(64, NEVER, 800, 1062));
    }

    @Test
    void refusesWhenAttachSegmentWouldBeFragmentary() {
        // 接入段短于 32 格 → 碎路，不如不修
        assertFalse(ExistingRoadAttachment.shouldAttach(20, 30, 31, 900));
        assertTrue(ExistingRoadAttachment.shouldAttach(20, 30, 32, 900));
    }

    @Test
    void refusesWhenAttachmentMakesRouteLonger() {
        // 改接后反而更长：等于拿两个更远的点去绕
        assertFalse(ExistingRoadAttachment.shouldAttach(100, 120, 1000, 900));
        assertFalse(ExistingRoadAttachment.shouldAttach(100, 120, 900, 900));
        assertTrue(ExistingRoadAttachment.shouldAttach(100, 120, 899, 900));
    }

    @Test
    void realWorldFailuresWouldAllBeRescued() {
        // 三条真机失败连接的实测距离，逐条走一遍决策
        // 1) (288,-1616)->(944,-1184): 16.6 / 49.2, 原长 784
        assertTrue(ExistingRoadAttachment.shouldAttach(17, 49, 784 - 17 - 49, 784));
        // 2) (1744,-2032)->(2800,-2144): 64.3 / 872.5(超半径), 原长 1062
        assertTrue(ExistingRoadAttachment.shouldAttach(64, NEVER, 1062 - 64, 1062));
        // 3) (32,-64)->(-624,-944): 0 / 55, 原长 1098
        assertTrue(ExistingRoadAttachment.shouldAttach(0, 55, 1098 - 55, 1098));
    }
}
