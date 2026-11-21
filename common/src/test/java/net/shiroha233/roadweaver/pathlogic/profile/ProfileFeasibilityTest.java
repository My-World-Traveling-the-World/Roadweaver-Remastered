/* 文件职责：用独立最短路和精确 DP 对拍包络，防止修复器把无解窗口称为成功。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileFeasibilityTest {
    @Test void envelopeMatchesAllPairsShortestPathsIncludingTighterSkipEdges() {
        var random = new Random(20261121);
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + random.nextInt(14), step = random.nextInt(5), two = random.nextInt(7) - 1;
            var upper = random.ints(n, -20, 40).toArray();
            var distance = new int[n][n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                int d = Math.abs(i - j);
                int one = two >= 0 ? Math.min(step, two) : step;
                distance[i][j] = d == 0 ? 0 : d == 1 ? one : d == 2 && two >= 0 ? two : 10000;
            }
            for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                distance[i][j] = Math.min(distance[i][j], distance[i][k] + distance[k][j]);
            var expected = upper.clone();
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                expected[i] = Math.min(expected[i], upper[j] + distance[i][j]);
            assertArrayEquals(expected, ProfileFeasibility.upperEnvelope(upper, step, two),
                    "独立最短路对拍 trial=" + trial);
        }
    }

    @Test void projectedWindowsAgreeWithExactDpAboutFeasibility() {
        var random = new Random(8831);
        int feasible = 0, infeasible = 0;
        for (int trial = 0; trial < 500; trial++) {
            int n = 2 + random.nextInt(8), step = random.nextInt(4), two = random.nextInt(6) - 1;
            var lower = random.ints(n, 0, 8).toArray();
            var upper = lower.clone();
            for (int i = 0; i < n; i++) upper[i] += random.nextInt(6);
            var terrain = random.ints(n, 0, 14).toArray();
            var expected = ProfileDpSolver.solve(new ProfileDpSolver.Problem(lower, upper, null, step, null, two));
            var actual = ProfileFeasibility.project(terrain, lower, upper, step, two);
            assertEquals(expected.isPresent(), actual.isPresent(), "窗口可行性对拍 trial=" + trial);
            if (actual.isEmpty()) { infeasible++; continue; }
            feasible++;
            var h = actual.orElseThrow();
            for (int i = 0; i < n; i++) {
                assertTrue(h[i] >= lower[i] && h[i] <= upper[i]);
                if (i > 0) assertTrue(Math.abs(h[i] - h[i - 1]) <= step);
                if (i > 1 && two >= 0) assertTrue(Math.abs(h[i] - h[i - 2]) <= two);
            }
        }
        assertTrue(feasible > 20 && infeasible > 20, "两侧分支都必须真实覆盖");
    }

    @Test void fallbackRejectsImpossibleTerrainInsteadOfReturningHeightJumps() {
        assertThrows(IllegalArgumentException.class, () -> ProfileFallback.terrainFollowing(
                new int[]{64, 140}, 5, 4, 1, 1, null, null, 8, null));
    }

    @Test void coverAndPinConflictCannotBeHiddenByReopeningTheWindow() {
        assertThrows(IllegalArgumentException.class, () -> ProfileFallback.terrainFollowing(
                new int[]{80, 80, 80}, 5, 4, 1, 1, new int[]{-1, 80, -1},
                new boolean[]{false, true, false}, 8, null));
    }
}
