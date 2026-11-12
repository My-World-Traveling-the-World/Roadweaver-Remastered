package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.011：过水三档价目 + 陆路闸门参数的回归。
 *
 * <p>钉住两件一旦漂移「玩家能看出来、但质量指标完全不报警」的东西：</p>
 * <ol>
 *   <li><b>6 / 20 / ∞ 三档断崖</b>：涵洞 → 窄水小桥 → 宽水重罚。断崖位置错了，
 *       表现就是「小溪也被绕开」或「大河随便过」。</li>
 *   <li><b>闸门关闭即完整回退</b>：{@code narrowMaxWidth = 0} 时必须回到 0.9.010
 *       的两档语义，否则回退开关是假的。</li>
 * </ol>
 */
final class WaterCrossingGateTest {

    private static final double CULVERT_PER = 2.0D;
    private static final int CULVERT_MAX_STATIONS = 6;
    private static final int CULVERT_MAX_DEPTH = 2;
    private static final double WIDE_BASE = 600.0D;
    private static final double WIDE_PER = 80.0D;
    private static final int NARROW_MAX_WIDTH = 20;
    private static final double NARROW_PER = 20.0D;

    private static double cost(int run, int depth) {
        return WaterCostModel.waterCrossingCost(run, depth, WIDE_BASE, WIDE_PER,
                CULVERT_PER, CULVERT_MAX_STATIONS, CULVERT_MAX_DEPTH,
                NARROW_MAX_WIDTH, NARROW_PER);
    }

    @Test
    void culvertTierStillWinsForShallowNarrowStreams() {
        // 5 格宽、水深 1：仍走涵洞桶价 5 × 2 = 10
        assertEquals(5 * CULVERT_PER, cost(5, 1), 1e-9);
        assertEquals(0.0D, cost(0, 1), 1e-9);
    }

    @Test
    void narrowWaterUsesSmallBridgePriceInsteadOfWidePrice() {
        // 10 格宽、水深 1：越过涵洞档但仍在窄水档 ⇒ 10 × 20 = 200
        assertEquals(200.0D, cost(10, 1), 1e-9);
        // 老口径（无窄水档）会是 600 + 80×10 = 1400，差 7 倍 —— 「小溪直接过」就靠这个
        assertTrue(cost(10, 1) < WIDE_BASE + WIDE_PER * 10);
        // 窄水档上界
        assertEquals(400.0D, cost(20, 1), 1e-9);
    }

    @Test
    void wideWaterJumpsToWidePrice() {
        // 21 格：只多一格就进宽水档 ⇒ 600 + 80×21 = 2280
        assertEquals(WIDE_BASE + WIDE_PER * 21, cost(21, 1), 1e-9);
        // 断崖必须真的有崖：20 → 400，21 → 2280（≥5 倍）
        assertTrue(cost(21, 1) > cost(20, 1) * 5.0D);
    }

    @Test
    void deepWaterSkipsCulvertTierEvenWhenNarrow() {
        // 5 格宽但水深 3 > culvertMaxWaterDepth(2) ⇒ 落到窄水档 5 × 20 = 100
        assertEquals(100.0D, cost(5, 3), 1e-9);
    }

    @Test
    void disablingNarrowTierRestoresTwoTierBehaviour() {
        // narrowMaxWidth = 0 ⇒ 无窄水档，语义等同 0.9.010
        assertEquals(WIDE_BASE + WIDE_PER * 10,
                WaterCostModel.waterCrossingCost(10, 1, WIDE_BASE, WIDE_PER,
                        CULVERT_PER, CULVERT_MAX_STATIONS, CULVERT_MAX_DEPTH, 0, NARROW_PER),
                1e-9);
    }

    @Test
    void configDefaultsMatchTheAgreedDesign() {
        PlanningConfig cfg = new PlanningConfig();
        assertEquals(1.6D, cfg.landDetourFactor(), 1e-9);
        assertEquals(20, cfg.narrowWaterMaxWidth());
        assertEquals(20.0D, cfg.narrowWaterPerStation(), 1e-9);
        assertEquals(600.0D, cfg.wideWaterBase(), 1e-9);
        assertEquals(80.0D, cfg.wideWaterPerStation(), 1e-9);
        // 临河惩罚缩到「离水 5 格窄带 + 轻罚 8」，不再把路赶出河谷
        assertEquals(5, cfg.riverProximityRadius());
        assertEquals(8.0D, cfg.riverProximityPenalty(), 1e-9);
    }

    @Test
    void configSanitizeAndSnapshotCarryTheNewFields() {
        PlanningConfig cfg = new PlanningConfig();
        cfg.setLandDetourFactor(-3.0D);
        cfg.setNarrowWaterMaxWidth(9999);
        cfg.setWideWaterPerStation(-1.0D);
        cfg.setWideWaterBase(-5.0D);
        cfg.setNarrowWaterPerStation(-2.0D);
        cfg.sanitize();
        assertEquals(0.0D, cfg.landDetourFactor(), 1e-9);
        assertEquals(256, cfg.narrowWaterMaxWidth());
        assertEquals(0.0D, cfg.wideWaterPerStation(), 1e-9);
        assertEquals(0.0D, cfg.wideWaterBase(), 1e-9);
        assertEquals(0.0D, cfg.narrowWaterPerStation(), 1e-9);

        cfg.setLandDetourFactor(2.4D);
        cfg.setNarrowWaterMaxWidth(30);
        PlanningConfig copy = cfg.snapshot();
        assertEquals(2.4D, copy.landDetourFactor(), 1e-9);
        assertEquals(30, copy.narrowWaterMaxWidth());
        assertEquals(cfg.wideWaterBase(), copy.wideWaterBase(), 1e-9);
    }

    /**
     * 闸门上限必须取「直连 × factor」与「原走廊 × 1.05」的较松者。
     *
     * <p>第二支是复核时发现的真 bug：{@code findLocalRoute} 是<b>局部修复</b>，
     * 原走廊本身可能就是一条 3 倍于直连距离的之字路。只看直连的话，一条
     * 300 长、完全合格的陆路修复会被判成「绕太远」而放开水面 —— 桥又冒出来，
     * 整个改动等于白做。</p>
     */
    @Test
    void landDetourCapUsesTheLooserOfDirectAndExistingCorridor() {
        // 直走廊：直连 100、原路 100、1.6 倍 ⇒ 160
        assertEquals(160.0D,
                WaterCostModel.landDetourCap(100.0D, 100.0D, 1.6D), 1e-9);
        // 之字形走廊：直连仍是 100，但原路已 300 长 ⇒ 上限必须放宽到 315
        assertEquals(315.0D,
                WaterCostModel.landDetourCap(100.0D, 300.0D, 1.6D), 1e-9);
        assertTrue(WaterCostModel.landDetourCap(100.0D, 300.0D, 1.6D) >= 300.0D);
        // 退化窗口：直连接近 0 时不再无条件接受任意陆路（8 格下限 ⇒ 12.8）
        assertEquals(12.8D,
                WaterCostModel.landDetourCap(0.5D, 0.5D, 1.6D), 1e-9);
    }
}
