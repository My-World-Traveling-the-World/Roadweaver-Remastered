package net.shiroha233.roadweaver.pathlogic.grade;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.435 / C2：坡度按真实弧长归一。
 *
 * <p>站号限坡把「每两站允许的高差」当成常数，而斜向一步是 {@code sqrt(2)} 格：同一段路在斜向
 * 实际走得更远，却只被允许同样多的爬升，于是斜向更陡。这里钉住三件事：
 * ①真实距离数组按水平距离算（斜向 = sqrt(2)）；②传入弧长后长边允许更多爬升、短边不被收紧；
 * ③不传（默认关）时与旧行为逐位相同。
 */
final class RoadGradeArcLengthTest {

    private static double[] unitSpacing(int count) {
        double[] spacing = new double[count];
        java.util.Arrays.fill(spacing, 1.0D);
        return spacing;
    }

    private static double[] diagonalSpacing(int count) {
        double[] spacing = new double[count];
        spacing[0] = 1.0D;
        for (int i = 1; i < count; i++) spacing[i] = Math.sqrt(2.0D);
        return spacing;
    }

    @Test
    void axialAndDiagonalStationSpacingMatchTheirRealDistance() {
        List<BlockPos> axial = new ArrayList<>();
        List<BlockPos> diagonal = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            axial.add(new BlockPos(i, 0, 0));
            diagonal.add(new BlockPos(i, 0, i));
        }

        double[] alongX = RoadGradePlanner.stationSpacing(axial);
        double[] alongDiagonal = RoadGradePlanner.stationSpacing(diagonal);

        assertEquals(1.0D, alongX[0], 1e-9);
        assertEquals(1.0D, alongX[4], 1e-9);
        assertEquals(Math.sqrt(2.0D), alongDiagonal[4], 1e-9);
    }

    @Test
    void aDiagonalIsAllowedTheSameRealGradeAsAnAxialRun() {
        int[] target = {0, 3, 6, 9, 12, 15, 18, 21};
        boolean[] mask = new boolean[target.length];

        int[] axial = RoadGradePlanner.enforceContinuousSlopePreferCut(
                target, mask, 4, null, unitSpacing(target.length));
        int[] diagonal = RoadGradePlanner.enforceContinuousSlopePreferCut(
                target, mask, 4, null, diagonalSpacing(target.length));

        assertEquals(14, axial[axial.length - 1],
                "axial run may only rise 2 blocks per station with step2=4");
        assertEquals(21, diagonal[diagonal.length - 1],
                "a sqrt(2) edge must be allowed sqrt(2) times the rise, which reaches the target");
        for (int i = 0; i < target.length; i++) {
            assertTrue(diagonal[i] >= axial[i], "arc length only ever relaxes the limit at index " + i);
            assertTrue(diagonal[i] <= target[i], "the closure never raises above the target at index " + i);
        }
    }

    @Test
    void noSpacingKeepsTheOldBehaviourBitForBit() {
        int[] target = {0, 2, 5, 9, 14, 20, 27, 35, 44};
        boolean[] mask = new boolean[target.length];

        int[] withoutSpacing = RoadGradePlanner.enforceContinuousSlopePreferCut(target, mask, 4, null);
        int[] withUnitSpacing = RoadGradePlanner.enforceContinuousSlopePreferCut(
                target, mask, 4, null, unitSpacing(target.length));

        assertArrayEquals(withoutSpacing, withUnitSpacing);
    }
}
