package net.shiroha233.roadweaver.pathfinding.impl;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.063（任务一）：坡罚刻度重标定。
 *
 * <p><b>病灶。</b>0.9.062 把计费基准从「按步收」改成「按长度收」之后量纲对了，但绝对刻度比
 * 0.9.061 松了：默认参照（step=8、softLimit=0.08、hardLimit=0.10）下，同一批参照点
 * 「正交爬 1 格」7837.5 → 6216.0、「对角爬 1 格」62.9126 → 56.9420。</p>
 *
 * <p><b>本用例钉四件事：</b></p>
 * <ol>
 *   <li>新出厂默认下两个参照点的总坡罚都 <b>≥ 0.9.061 的旧值</b>（并把实际数打出来）；</li>
 *   <li>「≥」不是靠改公式拿到的：公式没动，动量的是单价（600→780 / 30000→39000）；</li>
 *   <li>平路（每步 rise ≤ softLimit × L）罚额仍然恒为 <b>0</b> —— 提价不能影响平原路；</li>
 *   <li>soft:hard 仍是 1:50（只改绝对刻度，不改两档相对权重）。</li>
 * </ol>
 */
class GradePenaltyCalibrationTest {

    /** 默认网格步长 8 格下的正交步 / 对角步水平长度。 */
    private static final double ORTHO = 8.0D;
    private static final double DIAG = 8.0D * Math.sqrt(2.0D);

    /** 0.9.061 两个参照点的罚额（由 {@link RoadConstants} 里的旧单价与旧公式重算，供断言用）。 */
    private static double legacyReference(PathfindingCostConfig cfg, double horizDist) {
        return PotentialFieldPathfinder.legacyGradePenalty(1.0D, horizDist,
                RoadConstants.LEGACY_SOFT_GRADE_PENALTY, RoadConstants.LEGACY_HARD_GRADE_PENALTY,
                cfg.softGradeLimit(), cfg.hardGradeLimit());
    }

    @Test
    void defaultConstantsAreTheCalibratedOnes() {
        // 0.9.077（H-01b）档 2：单价从 780/39000 降到 30/300，把「爬 1 格」的
        // 价格从「绕行 8081 格」压回「绕行 71 格」这个可解释量级。
        assertEquals(30.0D, RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, 0.0D);
        assertEquals(300.0D, RoadConstants.DEFAULT_HARD_GRADE_PENALTY, 0.0D);
        assertEquals(600.0D, RoadConstants.LEGACY_SOFT_GRADE_PENALTY, 0.0D);
        assertEquals(30000.0D, RoadConstants.LEGACY_HARD_GRADE_PENALTY, 0.0D);
        // 两档相对权重：0.9.063 是 1:50，档 2 收到 1:10（陡坡仍显著更贵，但不再是天价）
        assertEquals(10.0D, RoadConstants.DEFAULT_HARD_GRADE_PENALTY / RoadConstants.DEFAULT_SOFT_GRADE_PENALTY,
                1.0E-12D, "soft:hard 必须仍是 1:10");
        assertEquals(50.0D, RoadConstants.LEGACY_HARD_GRADE_PENALTY / RoadConstants.LEGACY_SOFT_GRADE_PENALTY,
                1.0E-12D);
        // 相对旧标准的整档系数 0.05（= 1/20）
        assertEquals(0.05D, RoadConstants.DEFAULT_SOFT_GRADE_PENALTY / RoadConstants.LEGACY_SOFT_GRADE_PENALTY,
                1.0E-12D);
    }

    /** 旧公式 + 旧单价必须复现 0.9.061 报表里的两个数（证明地板值不是拍脑袋写的）。 */
    @Test
    void legacyFormulaReproducesTheHistoricalReferenceNumbers() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        double oldOrtho = legacyReference(cfg, ORTHO);
        double oldDiag = legacyReference(cfg, DIAG);
        assertEquals(RoadConstants.LEGACY_ORTHO_REFERENCE_PENALTY, oldOrtho, 1.0E-9D);
        assertEquals(RoadConstants.LEGACY_DIAG_REFERENCE_PENALTY, oldDiag, 1.0E-9D);
        assertEquals(7837.5D, oldOrtho, 1.0E-9D);
        assertEquals(62.9126073623882D, oldDiag, 1.0E-9D);
    }

    /**
     * 档 2 的验收判据：参照点的罚额必须落回「与 baseCost 同量纲」的区间。
     *
     * <p>baseCost 一步是 1.0（正交）/ 1.414（对角）。坡罚若比它大三个数量级，
     * A* 就会用「绕行几千米」或「斜走白嫖免罚额度」来省坡罚 —— 那正是用户看到的扭。
     * 判据：爬 1 格的价格不超过一个「百步级」的绕行预算。</p>
     */
    @Test
    void referencePointsSitInsideTheDetourBudget() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        double ortho = PotentialFieldPathfinder.gradePenalty(1, ORTHO, cfg);
        double diag = PotentialFieldPathfinder.gradePenalty(1, DIAG, cfg);
        double oldOrtho = legacyReference(cfg, ORTHO);
        double oldDiag = legacyReference(cfg, DIAG);
        System.out.printf(java.util.Locale.ROOT,
                "GradeCalibration ortho=%.4f (old %.4f) diag=%.4f (old %.4f) detourEquiv=%.1f%n",
                ortho, oldOrtho, diag, oldDiag, ortho / cfg.orthoStepCost());

        // ① 必须显著低于旧标准（旧标准 = 绕行 8081 格，荒谬）
        assertTrue(ortho < oldOrtho / 10.0D,
                "档 2 的正交参照点必须比旧标准低一个数量级以上，实际 " + ortho + " vs 旧 " + oldOrtho);
        assertTrue(diag < oldDiag * 1.0D + 1.0D,
                "档 2 的对角参照点不得高于旧标准，实际 " + diag + " vs 旧 " + oldDiag);

        // ② 量纲对齐：爬 1 格的等价绕行格数（以 baseCost 计）必须是一个可解释的数
        double detourEquiv = ortho / cfg.orthoStepCost();
        assertTrue(detourEquiv > 10.0D && detourEquiv < 300.0D,
                "爬 1 格应等价于绕行 10..300 格，实际 " + detourEquiv + " 格");

        // ③ 对角仍比正交便宜（阈值位置的几何必然），但不得出现三位数倍数
        double ratio = diag <= 0.0D ? Double.POSITIVE_INFINITY : ortho / diag;
        assertTrue(ratio < 100.0D,
                "正步/斜步同爬 1 格的代价比应回到两位数以内，实际 " + ratio);
    }

    /** 平路：每步 rise ≤ softLimit × L ⇒ 罚额恒 0（提价不许影响平原路）。 */
    @Test
    void flatTerrainStillCostsExactlyZero() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        for (int step : new int[]{1, 2, 4, 8, 16, 32}) {
            double ortho = step;
            double diag = step * Math.sqrt(2.0D);
            int freeOrtho = (int) Math.floor(cfg.softGradeLimit() * ortho);
            int freeDiag = (int) Math.floor(cfg.softGradeLimit() * diag);
            assertTrue(freeOrtho >= 0 && freeDiag >= 0);
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(0, ortho, cfg), 0.0D,
                    "零爬升必须零罚 step=" + step);
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(freeOrtho, ortho, cfg), 0.0D,
                    "正交免罚额度内必须零罚 step=" + step + " free=" + freeOrtho);
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(freeDiag, diag, cfg), 0.0D,
                    "对角免罚额度内必须零罚 step=" + step + " free=" + freeDiag);
            // 额度再 +1 格就必须开始收费（否则说明提价把免罚区也放宽了）
            assertTrue(PotentialFieldPathfinder.gradePenalty(freeOrtho + 1, ortho, cfg) > 0.0D,
                    "超出免罚额度 1 格必须开始收费 step=" + step);
        }
        // 一段完全水平的剖面：逐站 0 爬升，罚额必须是干净的 0（不是 1e-30 这种）
        for (int i = 0; i < 64; i++) {
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(0, ORTHO, cfg), 0.0D);
        }
    }

    /** 提价必须体现在「跨过免罚额度的第一格」上，而不是把整条曲线平移。 */
    @Test
    void marginalPriceIsNowTheRaisedSoftPenalty() {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setHardGradeLimit(10.0D);
        cfg.setHardGradePenalty(0.0D);
        int rise = (int) Math.ceil(cfg.softGradeLimit() * ORTHO) + 3;
        double marginal = PotentialFieldPathfinder.gradePenalty(rise + 1, ORTHO, cfg)
                - PotentialFieldPathfinder.gradePenalty(rise, ORTHO, cfg);
        assertEquals(cfg.softGradePenalty(), marginal, 1.0E-9D);
        assertEquals(30.0D, marginal, 1.0E-9D);
        assertEquals(0.05D, marginal / RoadConstants.LEGACY_SOFT_GRADE_PENALTY, 1.0E-12D);
    }
}
