/* 文件职责：以冻结旧求解器和独立穷举验证线性二站转移的代价、剖面和同价次序。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfilePairTransitionsTest {
    @Test void randomWindowsPinsAndRaggedCostsKeepEveryHeightAndExactCost() {
        var random = new Random(639877);
        int feasible = 0;
        for (int round = 0; round < 800; round++) {
            int n = 1 + random.nextInt(18);
            int[] lo = new int[n], hi = new int[n], pin = new int[n];
            double[][] costs = new double[n][];
            Arrays.fill(pin, -1);
            for (int i = 0; i < n; i++) {
                lo[i] = 58 + random.nextInt(7);
                hi[i] = lo[i] + random.nextInt(9);
                if (random.nextInt(7) == 0) pin[i] = lo[i] + random.nextInt(hi[i] - lo[i] + 1);
                if (random.nextInt(4) == 0) continue;
                costs[i] = new double[random.nextInt(hi[i] - lo[i] + 2)];
                for (int k = 0; k < costs[i].length; k++) costs[i][k] = (random.nextInt(17) - 8) * .5;
            }
            var p = new ProfileDpSolver.Problem(lo, hi, costs, random.nextInt(6),
                    round % 9 == 0 ? null : pin, random.nextInt(7));
            if (compare(p)) feasible++;
        }
        assertTrue(feasible > 80, "夹具不能全靠不可行空解通过");
    }

    @Test void equalCostPlateausPreserveOldParentTieRules() {
        for (int limit = 0; limit <= 9; limit++) for (int step = 0; step <= 6; step++) {
            int[] lo = new int[12], hi = new int[12];
            Arrays.fill(lo, -3); Arrays.fill(hi, 9);
            assertTrue(compare(new ProfileDpSolver.Problem(lo, hi, null, step, null, limit)));
        }
    }

    @Test void dummyFirstPairSingleStationAndImpossiblePinsRemainUnchanged() {
        assertTrue(compare(new ProfileDpSolver.Problem(new int[]{5}, new int[]{9},
                new double[][]{{4, 2, 2, 9, 3}}, 0, new int[]{8}, 0)));
        assertFalse(compare(new ProfileDpSolver.Problem(new int[]{0, 3}, new int[]{0, 3},
                null, 6, null, 1)));
        assertTrue(compare(new ProfileDpSolver.Problem(new int[]{0, 1}, new int[]{0, 1},
                null, 6, null, 1)));
        assertFalse(compare(new ProfileDpSolver.Problem(new int[]{1, 1}, new int[]{4, 4},
                null, 3, new int[]{8, -1}, 3)));
    }

    @Test void nonfiniteAndSignedZeroCostsDoNotCreateReachableFakeParents() {
        double[][] costs = {{Double.NaN, 0, -0.0}, {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0},
                {0, Double.NaN, Double.POSITIVE_INFINITY}, {0, 0, 0}};
        for (int limit = 0; limit < 4; limit++)
            compare(new ProfileDpSolver.Problem(new int[4], new int[]{2, 2, 2, 2}, costs, 2, null, limit));
        assertFalse(compare(new ProfileDpSolver.Problem(new int[]{0, 0}, new int[]{1, 1},
                new double[][]{{Double.NaN, Double.POSITIVE_INFINITY}, {0, 0}}, 1, null, 1)));
    }

    @Test void movingNarrowWindowsTraceParentsInGlobalHeightCoordinates() {
        // 全局状态大于一个字节，但每行窗口很窄；移动窗口迫使路线持续爬升。
        int n = 200;
        var lo = new int[n];
        var hi = new int[n];
        for (int i = 0; i < n; i++) {
            lo[i] = -108 + i / 2;
            hi[i] = lo[i] + 5;
        }
        var p = new ProfileDpSolver.Problem(lo, hi, null, 2, null, 1);
        assertTrue(compare(p));
        int[] h = ProfileDpSolver.solve(p).orElseThrow().profile();
        assertTrue(h[n - 1] - h[0] >= 94);
    }

    @Test void unsignedByteBoundaryAndWiderDeltaKeepExactlyTheSameProfile() {
        // 前一高差位于无符号字节末端；超过边界仍须可解，不能截断父序号。
        int[] lows = {0, 127, 127, 254};
        for (int limit : new int[]{127, 128}) {
            assertTrue(compare(new ProfileDpSolver.Problem(lows, lows, null, 127, null, limit)));
        }
    }

    @Test void smallProblemsAlsoMatchIndependentExhaustiveHeightEnumeration() {
        var random = new Random(129867);
        int feasible = 0;
        for (int round = 0; round < 60; round++) {
            int[] lo = new int[5], hi = new int[]{3, 3, 3, 3, 3};
            double[][] costs = new double[5][4];
            for (var row : costs) for (int i = 0; i < row.length; i++) row[i] = random.nextInt(9);
            var p = new ProfileDpSolver.Problem(lo, hi, costs, random.nextInt(4), null, random.nextInt(4));
            double expected = brute(p, new int[5], 0, 0);
            var actual = ProfileDpSolver.solve(p);
            assertEquals(expected < Double.POSITIVE_INFINITY, actual.isPresent());
            if (actual.isPresent()) {
                assertEquals(expected, actual.get().totalCost());
                feasible++;
            }
        }
        assertEquals(60, feasible);
    }

    private static boolean compare(ProfileDpSolver.Problem p) {
        var old = FrozenProfileDpSolver.solve(new FrozenProfileDpSolver.Problem(p.lo(), p.hi(),
                p.cost(), p.maxStep(), p.pin(), p.maxStepPerTwo()));
        var actual = ProfileDpSolver.solve(p);
        assertEquals(old.isPresent(), actual.isPresent());
        if (old.isEmpty()) return false;
        assertArrayEquals(old.orElseThrow().profile(), actual.orElseThrow().profile());
        assertEquals(Double.doubleToLongBits(old.orElseThrow().totalCost()),
                Double.doubleToLongBits(actual.orElseThrow().totalCost()));
        assertEquals(p.stations(), actual.orElseThrow().profile().length);
        return true;
    }

    private static double brute(ProfileDpSolver.Problem p, int[] heights, int i, double sum) {
        if (i == heights.length) return sum;
        double best = Double.POSITIVE_INFINITY;
        for (int y = p.lo()[i]; y <= p.hi()[i]; y++) {
            // 独立枚举高度序列，保留旧模型首对高差也受L约束的既有契约。
            if (i > 0 && Math.abs(y - heights[i - 1]) > Math.min(p.maxStep(), p.maxStepPerTwo())) continue;
            if (i > 1 && Math.abs(y - heights[i - 2]) > p.maxStepPerTwo()) continue;
            heights[i] = y;
            best = Math.min(best, brute(p, heights, i + 1, sum + p.cost()[i][y - p.lo()[i]]));
        }
        return best;
    }
}
