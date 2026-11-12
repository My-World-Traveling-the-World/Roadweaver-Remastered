package net.shiroha233.roadweaver.pathfinding.impl;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.062（②）：坡罚「按长度收」。
 *
 * <p><b>病灶。</b>{@code baseCost} 正比于长度（正交 1.0 / 对角 1.414，即每格 0.125），而 0.9.061 的
 * 坡罚<b>与长度无关</b>（一步一次性结账）。两者不是同一把尺子 ⇒ 同样爬 1 格，正步与斜步的罚额差一个
 * 数量级 ⇒ 搜索奖励"少走步数"（=多走斜步）而不是"少爬高度" ⇒ 路斜着横切、不盘山。</p>
 *
 * <p><b>本用例钉四件事：</b>①默认档已经是「按长度收」；②边际单价（每多爬 1 格多付多少）恒为
 * {@code softGradePenalty}，与步长无关；③新式 = 旧式 × 该步的免罚爬升额度
 * （{@code softGradeLimit × 水平长度}），这条等式就是"按长度收"的数学定义；
 * ④baseCost 本身的量纲没有被顺手改掉（正步/斜步每格同价）。</p>
 *
 * <p><b>同时诚实记录一条没修的东西：</b>默认参数下 hard 阈值(0.10)正好卡在正步 grade(0.125) 与
 * 斜步 grade(0.0884) 之间，{@code hardGradePenalty}(30000) 那一档只对正步触发，所以正步/斜步的
 * 总罚额比仍然在 100 倍量级上（旧 124.6 → 新 109.2）。②只负责量纲，不负责那条悬崖；
 * {@link #fullDefaultRatioStillCarriesTheHardCliff()} 把它钉住，避免有人误以为这一版顺手解决了它。</p>
 */
class GradePenaltyLengthBasisTest {

    /** 默认网格步长（{@code RoadConstants.DEFAULT_ASTAR_STEP}）下的正交步与对角步水平长度。 */
    private static final double ORTHO = 8.0D;
    private static final double DIAG = 8.0D * Math.sqrt(2.0D);

    private static PathfindingCostConfig byLength() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setSlopePenaltyByLength(true);
        return cfg;
    }

    private static PathfindingCostConfig legacy() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setSlopePenaltyByLength(false);
        return cfg;
    }

    /** 只留 soft 档，把 hard 悬崖摘掉，专门看量纲。 */
    private static PathfindingCostConfig softOnly(PathfindingCostConfig cfg) {
        cfg.setHardGradeLimit(10.0D);
        cfg.setHardGradePenalty(0.0D);
        return cfg;
    }

    /** 旧公式的手写复刻（证明「关掉即回旧行为」是逐位相同，不是"大致相同"）。 */
    private static double legacyPenalty(int rise, double horizDist, PathfindingCostConfig cfg) {
        double grade = rise / Math.max(1e-6D, horizDist);
        double penalty = 0.0D;
        if (grade > cfg.softGradeLimit()) {
            penalty += cfg.softGradePenalty() * (grade - cfg.softGradeLimit()) / cfg.softGradeLimit();
        }
        if (grade > cfg.hardGradeLimit()) {
            penalty += cfg.hardGradePenalty() * (grade - cfg.hardGradeLimit()) / cfg.hardGradeLimit();
        }
        return penalty;
    }

    /** 每多爬 1 格多付多少（差分掉与爬升无关的常数项）。 */
    private static double marginalPrice(PathfindingCostConfig cfg, double horizDist) {
        int r = (int) Math.ceil(cfg.hardGradeLimit() * horizDist) + 3;
        return PotentialFieldPathfinder.gradePenalty(r + 1, horizDist, cfg)
                - PotentialFieldPathfinder.gradePenalty(r, horizDist, cfg);
    }

    @Test
    void defaultBasisIsLengthNormalised() {
        assertTrue(new PathfindingCostConfig().slopePenaltyByLength(),
                "0.9.062 起默认必须按长度收（否则此项改动在线上不生效）");
        assertTrue(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY > 0.0D);
        assertTrue(RoadConstants.DEFAULT_HARD_GRADE_PENALTY > 0.0D);
    }

    @Test
    void marginalPriceIsPerExtraBlockOfClimbAndStepIndependent() {
        PathfindingCostConfig cfg = softOnly(byLength());
        double ortho = marginalPrice(cfg, ORTHO);
        double diag = marginalPrice(cfg, DIAG);
        assertEquals(cfg.softGradePenalty(), ortho, 1.0E-9D,
                "每多爬 1 格的单价必须就是 softGradePenalty（不再是 / step）");
        assertEquals(ortho, diag, 1.0E-9D,
                "单价必须与步长无关 —— 这正是 0.9.061「按步收」缺的性质");
        // 旧口径下单价 = softGradePenalty / (softGradeLimit × 步长) ∝ 1/步长
        PathfindingCostConfig old = softOnly(legacy());
        double oldOrtho = marginalPrice(old, ORTHO);
        double oldDiag = marginalPrice(old, DIAG);
        assertEquals(oldOrtho, oldDiag * Math.sqrt(2.0D), 1.0E-6D,
                "旧口径的单价应当与步长成反比（斜步便宜 1/1.414）");
        assertTrue(oldOrtho / oldDiag > 1.2D, "旧口径的斜步折扣必须真的存在，否则本用例没有对照");
    }

    @Test
    void perLengthChargeEqualsLegacyTimesTheFreeAllowance() {
        // 「按长度收」的数学定义：新式 = 旧式 × 该步的免罚爬升额度 (softGradeLimit × L)
        PathfindingCostConfig cfg = softOnly(byLength());
        PathfindingCostConfig old = softOnly(legacy());
        for (double dist : new double[]{ORTHO, DIAG, 4.0D, 16.0D}) {
            for (int rise = 1; rise <= 12; rise++) {
                if (rise <= cfg.softGradeLimit() * dist) continue; // 免罚额度内两边都是 0
                double expected = legacyPenalty(rise, dist, old) * old.softGradeLimit() * dist;
                assertEquals(expected, PotentialFieldPathfinder.gradePenalty(rise, dist, cfg),
                        1.0E-6D,
                        "rise=" + rise + " L=" + dist);
            }
        }
        // 免罚额度仍然按水平长度给：额度内的爬升零罚（两档都一样，这一项本来就是对的）
        for (boolean basis : new boolean[]{false, true}) {
            PathfindingCostConfig c = new PathfindingCostConfig();
            c.setSlopePenaltyByLength(basis);
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(0, ORTHO, c), 0.0D,
                    "零爬升必须零罚 basis=" + basis);
        }
    }

    @Test
    void orthoVersusDiagonalRatioShrinksByExactlyTheStepLengthRatio() {
        // 把 hard 悬崖摘掉之后，正步/斜步总罚额的比只由量纲决定：
        //   旧式 ratio = 5.3646（正步 337.5 / 斜步 62.91）
        //   新式 ratio = 3.7931（正步 216.0 / 斜步 56.94）= 旧式 ratio / 1.4142
        PathfindingCostConfig cfg = softOnly(byLength());
        PathfindingCostConfig old = softOnly(legacy());
        // 0.9.063（任务一）：坡罚单价整体 ×1.30（600/30000 → 780/39000）。下面四个绝对数
        // 是 0.9.062 的实测值，本版起统一乘 SCALE —— 比值（本用例真正要钉的东西）不变。
        double scale = RoadConstants.DEFAULT_SOFT_GRADE_PENALTY / RoadConstants.LEGACY_SOFT_GRADE_PENALTY;
        assertEquals(216.0D * scale, PotentialFieldPathfinder.gradePenalty(1, ORTHO, cfg), 1.0E-6D);
        assertEquals(56.94199204873148D * scale, PotentialFieldPathfinder.gradePenalty(1, DIAG, cfg), 1.0E-6D);
        assertEquals(337.5D * scale, PotentialFieldPathfinder.gradePenalty(1, ORTHO, old), 1.0E-6D);
        assertEquals(62.91260736238837D * scale, PotentialFieldPathfinder.gradePenalty(1, DIAG, old), 1.0E-6D);

        double newRatio = PotentialFieldPathfinder.gradePenalty(1, ORTHO, cfg)
                / PotentialFieldPathfinder.gradePenalty(1, DIAG, cfg);
        double oldRatio = PotentialFieldPathfinder.gradePenalty(1, ORTHO, old)
                / PotentialFieldPathfinder.gradePenalty(1, DIAG, old);
        assertEquals(oldRatio / Math.sqrt(2.0D), newRatio, 1.0E-9D,
                "正步/斜步的比值必须恰好按步长比(√2)缩小 —— 这就是「按长度收」的直接后果");
        assertTrue(newRatio < 5.0D, "同爬 1 格的正步/斜步代价比必须回到个位数，实际 " + newRatio);
    }

    /**
     * 硬悬崖仍在，但不再是三位数倍数。
     *
     * <p>0.9.063 的 1:50 单价下「正步/斜步」同爬 1 格差 109 倍，因为 hard 阈值 0.10 正好卡在
     * 正步 0.125 与斜步 0.0884 之间。档 2 把单价收到 1:10 之后：悬崖依然存在（正步仍更贵，
     * 这是阈值位置的几何必然，不做处理），但倍数回到两位数，锯齿不再是压倒性的最优解。</p>
     *
     * <p>本用例只钉<b>性质</b>，不钉绝对数 —— 绝对数属于标定记录，改单价就该跟着改，
     * 钉死只会让以后每次调参都红一片。</p>
     */
    @Test
    void hardCliffSurvivesButStopsBeingThreeDigits() {
        PathfindingCostConfig byLen = byLength();
        PathfindingCostConfig stepBased = legacy();
        double byLenOrtho = PotentialFieldPathfinder.gradePenalty(1, ORTHO, byLen);
        double byLenDiag = PotentialFieldPathfinder.gradePenalty(1, DIAG, byLen);
        double stepOrtho = PotentialFieldPathfinder.gradePenalty(1, ORTHO, stepBased);
        double stepDiag = PotentialFieldPathfinder.gradePenalty(1, DIAG, stepBased);

        // ① 两种基准下正步都比斜步贵 —— 阈值卡在中间造成的悬崖，本条不处理它
        assertTrue(byLenOrtho > byLenDiag,
                "按长度收：正步应仍比斜步贵（阈值位置） ortho=" + byLenOrtho + " diag=" + byLenDiag);
        assertTrue(stepOrtho > stepDiag,
                "按步收：正步应仍比斜步贵 ortho=" + stepOrtho + " diag=" + stepDiag);

        // ② 但倍数必须回到两位数（档 2 的验收判据）
        double byLenRatio = byLenOrtho / byLenDiag;
        double stepRatio = stepOrtho / stepDiag;
        assertTrue(byLenRatio < 100.0D, "按长度收的正/斜倍数应 <100，实际 " + byLenRatio);
        assertTrue(stepRatio < 100.0D, "按步收的正/斜倍数应 <100，实际 " + stepRatio);

        // ③ 按长度收的免罚额度是 softLimit×L，比按步收宽，所以正步罚额必须更低
        assertTrue(byLenOrtho < stepOrtho,
                "按长度收的正步罚额应低于按步收 byLen=" + byLenOrtho + " step=" + stepOrtho);
    }

    @Test
    void baseCostKeepsItsPerLengthDimension() {
        // ②只碰坡罚。baseCost 的量纲必须原样：正交 1.0 / 对角 1.414 ⇒ 每格同价 0.125。
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        assertEquals(RoadConstants.DEFAULT_ORTHO_STEP_COST, cfg.orthoStepCost(), 0.0D);
        assertEquals(RoadConstants.DEFAULT_DIAG_STEP_COST, cfg.diagStepCost(), 0.0D);
        double orthoPerBlock = cfg.orthoStepCost() / ORTHO;
        double diagPerBlock = cfg.diagStepCost() / DIAG;
        assertEquals(0.125D, orthoPerBlock, 1.0E-12D);
        assertEquals(orthoPerBlock, diagPerBlock, 1.0E-4D,
                "baseCost 必须正比于长度（否则「同一把尺子」这个前提就没了）");
        // 换步长不改变每格单价（baseCost 是配置常量，不是 step 的函数）
        for (int step : new int[]{1, 2, 4, 16}) {
            PathfindingCostConfig scaled = new PathfindingCostConfig();
            scaled.setAStarStep(step);
            assertEquals(cfg.orthoStepCost(), scaled.orthoStepCost(), 0.0D,
                    "A* 步长不许改变 baseCost 本身");
        }
    }

    @Test
    void switchingTheBasisOffRestoresTheLegacyFormulaBitForBit() {
        PathfindingCostConfig cfg = legacy();
        for (int rise = 0; rise <= 12; rise++) {
            assertEquals(legacyPenalty(rise, ORTHO, cfg),
                    PotentialFieldPathfinder.gradePenalty(rise, ORTHO, cfg), 0.0D,
                    "关掉之后正交步 rise=" + rise + " 必须与 0.9.061 逐位相同");
            assertEquals(legacyPenalty(rise, DIAG, cfg),
                    PotentialFieldPathfinder.gradePenalty(rise, DIAG, cfg), 0.0D,
                    "关掉之后对角步 rise=" + rise + " 必须与 0.9.061 逐位相同");
        }
        // 回退链：setSlopePenaltyByLength(false) 之后 withAStarStep(...) 也不许把它改回 true
        assertFalse(cfg.withAStarStep(1).slopePenaltyByLength(),
                "回退开关必须能穿过 withAStarStep 的快照");
        assertTrue(byLength().withAStarStep(1).slopePenaltyByLength());
    }

    @Test
    void systemPropertySelectsTheBasis() {
        String key = RoadConstants.SLOPE_PENALTY_BASIS_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "legacy");
            assertFalse(new PathfindingCostConfig().slopePenaltyByLength(), "legacy = 旧口径");
            System.setProperty(key, "per-step");
            assertFalse(new PathfindingCostConfig().slopePenaltyByLength(), "per-step = 旧口径");
            System.setProperty(key, "length");
            assertTrue(new PathfindingCostConfig().slopePenaltyByLength());
            System.setProperty(key, "");
            assertTrue(new PathfindingCostConfig().slopePenaltyByLength(), "空串 = 默认按长度收");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
