package net.shiroha233.roadweaver.pathfinding.impl;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坡度罚「计费基准」的两条不变量（0.9.028 / H-01 重新定性）。
 *
 * <p>背景：真机与离线尺子都量到「横向振幅小了但来回摆动次数反而更多」（翻转 4.37 → 7.60 / 百站）。
 * 追到代价函数发现第 0 层根因不是「罚太松」，而是**罚的单价与步长成反比**：
 * 罚额按 {@code grade = 爬升 / 步长} 的比率收 ⇒ 同样爬 1 格，斜步（11.31）比正步（8）
 * 便宜约 29% ⇒ 用「多走几个斜步」把爬升摊薄是严格更优的解。
 *
 * <p>本测试钉住两件事：
 * <ol>
 *   <li><b>默认档逐位不变</b> —— {@code slopePenaltyByLength=false} 时结果必须与旧公式完全一致，
 *       保证这次改动在翻默认之前对线上零影响。</li>
 *   <li><b>新档消除斜步折扣</b> —— 同爬 1 格，正步与斜步的罚额必须**相等**。</li>
 * </ol>
 */
class SlopePenaltyBasisTest {

    /** 8 格网格上的正交步与对角步（与 A* 实际使用的一致）。 */
    private static final double ORTHO = 8.0D;
    private static final double DIAG = 8.0D * Math.sqrt(2.0D);

    private static PathfindingCostConfig config(boolean byLength) {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setSlopePenaltyByLength(byLength);
        return cfg;
    }

    /** 旧公式的手写复刻，用来证明默认档「逐位相同」而不是「大致相同」。 */
    private static double legacy(int rise, double horizDist, PathfindingCostConfig cfg) {
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

    @Test
    void defaultBasisIsBitIdenticalToTheLegacyFormula() {
        PathfindingCostConfig cfg = config(false);
        for (int rise = 0; rise <= 12; rise++) {
            assertEquals(legacy(rise, ORTHO, cfg),
                    PotentialFieldPathfinder.gradePenalty(rise, ORTHO, cfg), 1e-12D,
                    "正交步 rise=" + rise + " 必须与旧公式逐位相同");
            assertEquals(legacy(rise, DIAG, cfg),
                    PotentialFieldPathfinder.gradePenalty(rise, DIAG, cfg), 1e-12D,
                    "对角步 rise=" + rise + " 必须与旧公式逐位相同");
        }
    }

    /**
     * 旧基准的**缺陷特征**（characterization test）：同爬 1 格，正交步比对角步更贵。
     * 这条测试不是在肯定这个行为，而是把它钉住 —— 一旦有人「顺手」改掉基准又没跑标定，
     * 这里会先红。
     */
    @Test
    void legacyBasisChargesOrthoStepsMoreForTheSameClimb() {
        PathfindingCostConfig cfg = config(false);
        double ortho = PotentialFieldPathfinder.gradePenalty(1, ORTHO, cfg);
        double diag = PotentialFieldPathfinder.gradePenalty(1, DIAG, cfg);
        assertTrue(ortho > diag,
                "旧基准下正交步应更贵（这正是锯齿的动机）：ortho=" + ortho + " diag=" + diag);
    }

    /** 每多爬 1 格要多付多少（差分掉常数项）。 */
    private static double margin(PathfindingCostConfig cfg, double horizDist) {
        int r = (int) Math.ceil(cfg.softGradeLimit() * horizDist) + 3;
        return PotentialFieldPathfinder.gradePenalty(r + 1, horizDist, cfg)
                - PotentialFieldPathfinder.gradePenalty(r, horizDist, cfg);
    }

    /**
     * **核心不变量**：边际单价（每多爬 1 格多付多少）——
     * 旧基准与步长成反比（这正是锯齿的动机），新基准与步长无关。
     *
     * <p>注意「免罚额度按水平长度给」两档都一样、而且是**对的**（爬同样的高度走更远本来就更缓），
     * 所以不能拿「同爬 1 格两档罚额相等」当判据；真正的病灶是单价 ∝ 1/步长。
     */
    @Test
    void marginalPriceIsStepIndependentOnlyInTheLengthBasis() {
        PathfindingCostConfig legacyCfg = config(false);
        legacyCfg.setHardGradeLimit(10.0D);
        legacyCfg.setHardGradePenalty(0.0D);
        double legacyOrtho = margin(legacyCfg, ORTHO);
        double legacyDiag = margin(legacyCfg, DIAG);
        assertTrue(legacyOrtho > legacyDiag * 1.2D,
                "旧基准的边际单价应与步长成反比：ortho=" + legacyOrtho + " diag=" + legacyDiag);

        PathfindingCostConfig byLength = config(true);
        byLength.setHardGradeLimit(10.0D);
        byLength.setHardGradePenalty(0.0D);
        // 0.9.062（②）：0.9.028 原型分支末尾那个 `/ step` 已被删除。它是**纯粹的整档缩放**
        // （把整档坡罚乘 1/step）：既不改变正步/斜步的比值，又让「网格步长」这个与地形无关的
        // 参数顺手改了坡罚强度（粗规划 step=8、局部修坡 step=1~2，同一段地形在两条链上差 4~8 倍）。
        // 本用例要钉的那条契约没有变 —— **边际单价与步长无关**；变的只是绝对刻度：
        // softGradePenalty/step → softGradePenalty。
        assertEquals(byLength.softGradePenalty(), margin(byLength, ORTHO), 1e-9D,
                "新基准的边际单价应等于 softGradePenalty（0.9.062 起不再除以 step）");
        assertEquals(margin(byLength, ORTHO), margin(byLength, DIAG), 1e-9D,
                "新基准的边际单价必须与步长无关（斜步折扣消失）");
    }

    /** 免罚额度仍然按「水平长度」给：爬升不超出 soft 额度时代价必须是 0（两档都成立）。 */
    @Test
    void riseWithinFreeAllowanceCostsNothingInEitherBasis() {
        for (boolean byLength : new boolean[]{false, true}) {
            PathfindingCostConfig cfg = config(byLength);
            double freeVertical = cfg.softGradeLimit() * ORTHO;
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty(0, ORTHO, cfg), 1e-12D,
                    "零爬升必须零罚 byLength=" + byLength);
            assertTrue(freeVertical > 0.0D, "soft 额度应为正，否则本用例没有意义");
            assertEquals(0.0D, PotentialFieldPathfinder.gradePenalty((int) Math.floor(freeVertical), ORTHO, cfg),
                    1e-12D, "额度内的爬升必须零罚 byLength=" + byLength);
        }
    }
}
