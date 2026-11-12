/* 文件职责：ProfileDpSolver 的正确性证明 —— 含与暴力枚举的对拍。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileDpSolver} 的正确性测试。
 *
 * <h2>★ 为什么必须有「与暴力枚举对拍」这一条</h2>
 * <p>DP 最容易出的错不是崩，而是<b>算出略微不是最优的解</b> —— 不崩、不报错、
 * 剖面看着也正常，只是每条路多挖了两格土。这种错靠人肉 review 基本发现不了。</p>
 *
 * <p>所以这里在<b>小规模</b>问题上用穷举得到真最优，与 DP 的解逐项对比：
 * 站数 ≤ 6、窗口 ≤ 7、步长 ≤ 3，穷举量最多 7^6 = 117,649，瞬间跑完。
 * 随机 300 组固定种子，任何一组不等就红。</p>
 *
 * <p>单调队列是这里最容易写错的地方（窗口左右边界、相等值弹出），
 * 对拍能在写错的当下就抓住，而不是等玩家反馈「这条路莫名其妙多挖了两格」。</p>
 */
final class ProfileDpSolverTest {

    private static ProfileDpSolver.Problem flat(int n, int y, int maxStep) {
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int i = 0; i < n; i++) {
            lo[i] = y;
            hi[i] = y;
        }
        return new ProfileDpSolver.Problem(lo, hi, null, maxStep, null);
    }

    // ── 基本形状 ──────────────────────────────────────────────

    @Test
    void 平地得到平剖面() {
        Optional<ProfileDpSolver.Solution> sol = ProfileDpSolver.solve(flat(10, 64, 1));
        assertTrue(sol.isPresent(), "case=present");
        int[] p = sol.get().profile();
        assertArrayEquals(new int[]{64, 64, 64, 64, 64, 64, 64, 64, 64, 64}, p, "case=flat-profile");
        assertEquals(0.0, sol.get().totalCost(), 1e-9, "case=zero-cost");
    }

    @Test
    void 坡度约束被严格遵守() {
        int n = 20;
        int[] terrain = new int[n];
        for (int i = 0; i < n; i++) terrain[i] = 64 + i;      // 一路爬升 1 格/站
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int i = 0; i < n; i++) {
            lo[i] = 64;
            hi[i] = 64 + n + 5;
        }
        double[][] cost = CostModel.fromTerrain(terrain, lo, hi, CostModel.Settings.defaults());
        Optional<ProfileDpSolver.Solution> sol =
                ProfileDpSolver.solve(new ProfileDpSolver.Problem(lo, hi, cost, 1, null));
        assertTrue(sol.isPresent(), "case=feasible");
        int[] p = sol.get().profile();
        for (int i = 1; i < n; i++) {
            assertTrue(Math.abs(p[i] - p[i - 1]) <= 1,
                    "case=slope-limit 第 " + i + " 站跳了 " + Math.abs(p[i] - p[i - 1]) + " 格");
        }
    }

    @Test
    void 钉住的高度必须被采纳() {
        int n = 10;
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int i = 0; i < n; i++) {
            lo[i] = 60;
            hi[i] = 80;
        }
        int[] pin = new int[]{-1, -1, -1, -1, 75, -1, -1, -1, -1, -1};
        Optional<ProfileDpSolver.Solution> sol =
                ProfileDpSolver.solve(new ProfileDpSolver.Problem(lo, hi, null, 4, pin));
        assertTrue(sol.isPresent(), "case=feasible");
        assertEquals(75, sol.get().profile()[4], "case=pinned-height");
    }

    @Test
    void 钉住但坡度上不去时必须报不可行() {
        int n = 3;
        int[] lo = new int[]{60, 60, 60};
        int[] hi = new int[]{60, 60, 80};
        int[] pin = new int[]{60, -1, 80};        // 3 站要走 20 格，maxStep=1 做不到
        Optional<ProfileDpSolver.Solution> sol =
                ProfileDpSolver.solve(new ProfileDpSolver.Problem(lo, hi, null, 1, pin));
        assertFalse(sol.isPresent(), "case=infeasible-must-be-empty");
    }

    @Test
    void 窗口为空必须立刻抛而不是静默() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileDpSolver.Problem(new int[]{70}, new int[]{60}, null, 1, null),
                "case=empty-window");
    }

    // ── ★ 与暴力枚举对拍 ─────────────────────────────────────

    @Test
    void DP最优解与暴力枚举一致() {
        Random rnd = new Random(20261113L);
        int compared = 0;
        for (int trial = 0; trial < 300; trial++) {
            int n = 2 + rnd.nextInt(5);              // 2..6 站
            int base = 60 + rnd.nextInt(5);
            int span = 1 + rnd.nextInt(3);           // 窗口宽度 2..4
            int maxStep = rnd.nextInt(4);            // 0..3
            int[] lo = new int[n];
            int[] hi = new int[n];
            for (int i = 0; i < n; i++) {
                lo[i] = base;
                hi[i] = base + span;
            }
            int[] terrain = new int[n];
            for (int i = 0; i < n; i++) terrain[i] = lo[i] + rnd.nextInt(hi[i] - lo[i] + 1);
            double[][] cost = CostModel.fromTerrain(terrain, lo, hi, CostModel.Settings.defaults());

            ProfileDpSolver.Problem problem = new ProfileDpSolver.Problem(lo, hi, cost, maxStep, null);
            Optional<ProfileDpSolver.Solution> dp = ProfileDpSolver.solve(problem);

            double[] best = new double[]{Double.POSITIVE_INFINITY};
            int[] bestProfile = new int[n];
            bruteForce(problem, 0, new int[n], best, bestProfile);

            if (Double.isInfinite(best[0])) {
                assertFalse(dp.isPresent(), "trial=" + trial + " 暴力判定不可行，DP 却给出解");
                continue;
            }
            assertTrue(dp.isPresent(), "trial=" + trial + " 暴力有解，DP 却说不可行");
            assertEquals(best[0], dp.get().totalCost(), 1e-9,
                    "trial=" + trial + " 站数=" + n + " 步长=" + maxStep + " 窗口=" + span
                            + " DP代价=" + dp.get().totalCost() + " 最优代价=" + best[0]
                            + " DP解=" + java.util.Arrays.toString(dp.get().profile())
                            + " 最优解=" + java.util.Arrays.toString(bestProfile));
            // 解本身也必须合法
            int[] p = dp.get().profile();
            for (int i = 0; i < n; i++) {
                assertTrue(p[i] >= lo[i] && p[i] <= hi[i], "trial=" + trial + " 第 " + i + " 站越窗");
            }
            for (int i = 1; i < n; i++) {
                assertTrue(Math.abs(p[i] - p[i - 1]) <= maxStep, "trial=" + trial + " 第 " + i + " 站超坡");
            }
            compared++;
        }
        assertTrue(compared > 100, "有效对拍组数太少：" + compared);
    }

    /** 穷举全部合法剖面。 */
    private static void bruteForce(ProfileDpSolver.Problem p, int i, int[] cur,
                                   double[] best, int[] bestProfile) {
        if (i == p.stations()) {
            double total = 0;
            for (int k = 0; k < cur.length; k++) {
                int idx = cur[k] - p.lo()[k];
                double c = (p.cost() == null || p.cost()[k] == null) ? 0.0 : p.cost()[k][idx];
                total += c;
            }
            if (total < best[0]) {
                best[0] = total;
                System.arraycopy(cur, 0, bestProfile, 0, cur.length);
            }
            return;
        }
        for (int y = p.lo()[i]; y <= p.hi()[i]; y++) {
            if (p.pin() != null && p.pin()[i] >= 0 && p.pin()[i] != y) continue;
            if (i > 0 && Math.abs(y - cur[i - 1]) > p.maxStep()) continue;
            cur[i] = y;
            bruteForce(p, i + 1, cur, best, bestProfile);
        }
    }
}