package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.core.constants.RoadConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.8.411：势场法（POTENTIAL_FIELD）7 个原硬编码常量晋升为配置项的回归。
 */
final class PathfindingCostConfigTest {

    @Test
    void promotedPotentialFieldConstantsDefaultToRoadConstants() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        assertEquals(RoadConstants.DEFAULT_CONTOUR_DISCOUNT, config.contourDiscount(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, config.softGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_LIMIT, config.hardGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, config.softGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_PENALTY, config.hardGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_GRADIENT_ALIGN_PENALTY, config.gradientAlignPenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_PF_WATER_BASE_PENALTY, config.pfWaterBasePenalty(), 1e-9);
    }

    @Test
    void fractionsClampToZeroOneAndPenaltiesToNonNegative() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        config.setContourDiscount(1.7);
        config.setSoftGradeLimit(-0.5);
        config.setHardGradeLimit(2.0);
        config.setSoftGradePenalty(-1.0);
        config.setHardGradePenalty(-1.0);
        config.setGradientAlignPenalty(-1.0);
        config.setPfWaterBasePenalty(-1.0);
        config.sanitize();

        assertEquals(1.0, config.contourDiscount(), 1e-9);
        assertEquals(0.0, config.softGradeLimit(), 1e-9);
        assertEquals(1.0, config.hardGradeLimit(), 1e-9);
        assertEquals(0.0, config.softGradePenalty(), 1e-9);
        assertEquals(0.0, config.hardGradePenalty(), 1e-9);
        assertEquals(0.0, config.gradientAlignPenalty(), 1e-9);
        assertEquals(0.0, config.pfWaterBasePenalty(), 1e-9);
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

        config.setContourDiscount(Double.NaN);
        config.setSoftGradeLimit(Double.NaN);
        config.setHardGradeLimit(Double.NaN);
        config.setSoftGradePenalty(Double.NaN);
        config.setHardGradePenalty(Double.NaN);
        config.setGradientAlignPenalty(Double.NaN);
        config.setPfWaterBasePenalty(Double.NaN);
        config.sanitize();

        assertEquals(RoadConstants.DEFAULT_CONTOUR_DISCOUNT, config.contourDiscount(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, config.softGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_LIMIT, config.hardGradeLimit(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, config.softGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_HARD_GRADE_PENALTY, config.hardGradePenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_GRADIENT_ALIGN_PENALTY, config.gradientAlignPenalty(), 1e-9);
        assertEquals(RoadConstants.DEFAULT_PF_WATER_BASE_PENALTY, config.pfWaterBasePenalty(), 1e-9);
    }

    @Test
    void snapshotCopiesAllPromotedConstants() {
        PathfindingCostConfig config = new PathfindingCostConfig();
        config.setContourDiscount(0.33);
        config.setSoftGradeLimit(0.05);
        config.setHardGradeLimit(0.20);
        config.setSoftGradePenalty(111.0);
        config.setHardGradePenalty(222.0);
        config.setGradientAlignPenalty(333.0);
        config.setPfWaterBasePenalty(444.0);

        PathfindingCostConfig copy = config.snapshot();

        assertEquals(0.33, copy.contourDiscount(), 1e-9);
        assertEquals(0.05, copy.softGradeLimit(), 1e-9);
        assertEquals(0.20, copy.hardGradeLimit(), 1e-9);
        assertEquals(111.0, copy.softGradePenalty(), 1e-9);
        assertEquals(222.0, copy.hardGradePenalty(), 1e-9);
        assertEquals(333.0, copy.gradientAlignPenalty(), 1e-9);
        assertEquals(444.0, copy.pfWaterBasePenalty(), 1e-9);
    }
}
