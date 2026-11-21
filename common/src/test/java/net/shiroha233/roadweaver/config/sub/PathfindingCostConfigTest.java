package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 桥隧经济判据仍读取这四项；清理旧寻路器不能删除真实计价能力。
 */
final class PathfindingCostConfigTest {

    @Test
    void tunnelEconomicConstantsDefaultToRoadConstants() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, config.softGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_LIMIT, config.hardGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, config.softGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_PENALTY, config.hardGradePenalty(), 1e-9);
    }

    @Test
    void fractionsClampToZeroOneAndPenaltiesToNonNegative() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        config.setSoftGradeLimit(-0.5);
        config.setHardGradeLimit(2.0);
        config.setSoftGradePenalty(-1.0);
        config.setHardGradePenalty(-1.0);
        config.sanitize();

        assertEquals(0.0, config.softGradeLimit(), 1e-9);
        assertEquals(1.0, config.hardGradeLimit(), 1e-9);
        assertEquals(0.0, config.softGradePenalty(), 1e-9);
        assertEquals(0.0, config.hardGradePenalty(), 1e-9);
    }

    @Test
    void hardGradeLimitNeverDropsBelowSoftGradeLimit() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        config.setSoftGradeLimit(0.9);
        config.setHardGradeLimit(0.2);
        config.sanitize();

        assertEquals(0.9, config.hardGradeLimit(), 1e-9);
    }

    @Test
    void nanValuesFallBackToDefaults() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        config.setSoftGradeLimit(Double.NaN);
        config.setHardGradeLimit(Double.NaN);
        config.setSoftGradePenalty(Double.NaN);
        config.setHardGradePenalty(Double.NaN);
        config.sanitize();

        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, config.softGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_LIMIT, config.hardGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, config.softGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_PENALTY, config.hardGradePenalty(), 1e-9);
    }

    @Test
    void snapshotCopiesAllTunnelEconomicConstants() {
        PathfindingCostConfig config = new PathfindingCostConfig();
        config.setSoftGradeLimit(0.05);
        config.setHardGradeLimit(0.20);
        config.setSoftGradePenalty(111.0);
        config.setHardGradePenalty(222.0);

        PathfindingCostConfig copy = config.snapshot();

        assertEquals(0.05, copy.softGradeLimit(), 1e-9);
        assertEquals(0.20, copy.hardGradeLimit(), 1e-9);
        assertEquals(111.0, copy.softGradePenalty(), 1e-9);
        assertEquals(222.0, copy.hardGradePenalty(), 1e-9);
    }
}
