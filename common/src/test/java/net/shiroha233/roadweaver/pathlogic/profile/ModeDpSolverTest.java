/* 文件职责：ModeDpSolver 的正确性证明 —— 含与"模式+高度"全枚举的对拍。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModeDpSolver} 的正确性测试。
 *
 * <h2>★ 对拍口径与上一个 DP 不同，必须写清楚</h2>
 * <p>{@code ProfileDpSolver} 的状态只有高度，暴力枚举的是"高度序列"。
 * 这里状态多了模式一维，所以暴力枚举的是<b>"(高度, 模式) 序列"</b> ——
 * 组合数按站数指数增长，只能压到 n ≤ 5 才跑得动。</p>
 *
 * <p>更关键的是：<b>模式转移的合法性</b>（桥不能直接接隧道）与
 * <b>进入建造费只收一次</b>这两个性质，光看 DP 结果对不对是验不出来的 ——
 * 必须让暴力解用完全相同的规则独立算一遍。所以下面的 bruteForce 里
 * 把 allowed / transitionAllowed / perStationCost / entryCost 的语义重写了一遍
 * （不是复用被测代码），两条实现互相对照才有意义。</p>
 */
final class ModeDpSolverTest {

    /** 测试用的小参数：窗口窄、单价小，便于穷举且断言可读。 */
    private static ModeDpSolver.Settings small() {
        return new ModeDpSolver.Settings(
                3, 2, 3, 8, 2,
                2.0, 5.0, 1.5, 2.5,
                new CostModel.Settings(1.0, 0.7, 4, 2.0));
    }

    // ── 语义级断言 ────────────────────────────────────────────

    @Test
    void 平地只会走地面() {
        int[] terrain = new int[]{70, 70, 70, 70, 70};
        Optional<ModeDpSolver.Solution> sol = ModeDpSolver.solve(terrain, 1, small());
        assertTrue(sol.isPresent(), "case=feasible");
        ModeDpSolver.Solution s = sol.get();
        assertEquals(0, s.stationCount(ModeDpSolver.Mode.BRIDGE), "case=no-bridge-on-flat");
        assertEquals(0, s.stationCount(ModeDpSolver.Mode.TUNNEL), "case=no-tunnel-on-flat");
        for (int i = 0; i < terrain.length; i++) {
            assertEquals(70, s.profile()[i], "case=flat-profile-" + i);
        }
    }

    @Test
    void 水面禁用地面时必须架桥() {
        // 谷地：中间 5 站低 12 格，坡度在 6 以内可逐渐下到谷底。
        // 关键在 banned：水面不允许铺路 —— 这才是桥被选中的真实原因。
        int[] terrain = new int[]{70, 70, 64, 58, 58, 58, 58, 58, 64, 70, 70};
        boolean[] banned = new boolean[]{false, false, false, true, true, true, true, true, false, false, false};
        Optional<ModeDpSolver.Solution> sol = ModeDpSolver.solve(terrain, 6, small(), banned);
        assertTrue(sol.isPresent(), "case=feasible");
        ModeDpSolver.Solution s = sol.get();
        assertTrue(s.stationCount(ModeDpSolver.Mode.BRIDGE) >= 5,
                "case=bridge-over-water " + s);
        for (int i = 0; i < banned.length; i++) {
            if (banned[i]) {
                assertTrue(s.modes()[i] != ModeDpSolver.Mode.GROUND,
                        "case=no-ground-where-banned at " + i);
            }
        }
    }

    @Test
    void 保护区内禁用地面时不许走地面() {
        // 山脊 + 保护区。这里**不**断言一定是隧道 —— 第一次写这条断言时它失败了：
        // DP 选了 bridge=3（造价 7.50）而不是 tunnel=3（17.50），因为在本组单价下
        // 「高架桥跨过山脊」确实比「打洞」便宜。DP 没算错，是断言预设错了。
        // 所以这里只钉真正的不变量：禁用地段绝不走地面，且必须给出某种跨越方式。
        int[] terrain = new int[]{70, 74, 78, 82, 82, 82, 78, 74, 70};
        boolean[] banned = new boolean[]{false, false, false, true, true, true, false, false, false};
        Optional<ModeDpSolver.Solution> sol = ModeDpSolver.solve(terrain, 6, small(), banned);
        assertTrue(sol.isPresent(), "case=feasible");
        ModeDpSolver.Solution s = sol.get();
        for (int i = 0; i < banned.length; i++) {
            if (banned[i]) {
                assertTrue(s.modes()[i] != ModeDpSolver.Mode.GROUND,
                        "case=no-ground-where-banned at " + i + " " + s);
            }
        }
        assertTrue(s.stationCount(ModeDpSolver.Mode.BRIDGE) + s.stationCount(ModeDpSolver.Mode.TUNNEL) >= 3,
                "case=some-span-required " + s);
    }

    @Test
    void 打洞比架桥贵时会选桥而反过来会选隧道() {
        int[] terrain = new int[]{70, 74, 78, 82, 82, 82, 78, 74, 70};
        boolean[] banned = new boolean[]{false, false, false, true, true, true, false, false, false};

        // ① 桥很贵、隧道便宜 ⇒ 必须打洞
        ModeDpSolver.Settings cheapTunnel = new ModeDpSolver.Settings(
                3, 2, 3, 8, 2, 80.0, 1.0, 40.0, 0.5, CostModel.Settings.defaults());
        Optional<ModeDpSolver.Solution> viaTunnel = ModeDpSolver.solve(terrain, 6, cheapTunnel, banned);
        assertTrue(viaTunnel.isPresent(), "case=tunnel-feasible");
        assertTrue(viaTunnel.get().stationCount(ModeDpSolver.Mode.TUNNEL) >= 3,
                "case=tunnel-chosen " + viaTunnel.get());

        // ② 隧道很贵、桥便宜 ⇒ 必须架桥
        ModeDpSolver.Settings cheapBridge = new ModeDpSolver.Settings(
                3, 2, 3, 8, 2, 1.0, 80.0, 0.5, 40.0, CostModel.Settings.defaults());
        Optional<ModeDpSolver.Solution> viaBridge = ModeDpSolver.solve(terrain, 6, cheapBridge, banned);
        assertTrue(viaBridge.isPresent(), "case=bridge-feasible");
        assertTrue(viaBridge.get().stationCount(ModeDpSolver.Mode.BRIDGE) >= 3,
                "case=bridge-chosen " + viaBridge.get());
    }

    @Test
    void 桥与隧道不得直接相连() {
        // 这是纯规则，直接测函数比构造场景可靠：
        // 场景测试要同时凑出"桥"和"隧道"相邻才触发，很容易因为地形不可行而测不到。
        assertFalse(ModeDpSolver.transitionAllowed(ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.TUNNEL),
                "case=bridge-to-tunnel");
        assertFalse(ModeDpSolver.transitionAllowed(ModeDpSolver.Mode.TUNNEL, ModeDpSolver.Mode.BRIDGE),
                "case=tunnel-to-bridge");
        assertTrue(ModeDpSolver.transitionAllowed(ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.BRIDGE),
                "case=ground-to-bridge");
        assertTrue(ModeDpSolver.transitionAllowed(ModeDpSolver.Mode.TUNNEL, ModeDpSolver.Mode.GROUND),
                "case=tunnel-to-ground");
        assertTrue(ModeDpSolver.transitionAllowed(ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.GROUND),
                "case=ground-to-ground");
    }

    @Test
    void 参数非法必须立刻抛() {
        assertThrows(IllegalArgumentException.class, () -> new ModeDpSolver.Settings(
                1, 1, 1, 5, 6, 1, 1, 1, 1, CostModel.Settings.defaults()),
                "case=tunnel-window-empty");
    }

    @Test
    void 全站被钉死到不可能的高度时返回空() {
        // 地形每站抬 30 格、maxStep=1、窗口 ±3/±2 ⇒ 不可能
        int[] terrain = new int[6];
        for (int i = 0; i < terrain.length; i++) terrain[i] = 60 + i * 30;
        Optional<ModeDpSolver.Solution> sol = ModeDpSolver.solve(terrain, 1, small());
        assertFalse(sol.isPresent(), "case=infeasible");
    }

    // ── ★ 与全枚举对拍 ───────────────────────────────────────

    @Test
    void 联合最优解与全枚举一致() {
        Random rnd = new Random(20261113L);
        ModeDpSolver.Settings s = small();
        int compared = 0;
        for (int trial = 0; trial < 200; trial++) {
            int n = 2 + rnd.nextInt(4);                 // 2..5 站
            int[] terrain = new int[n];
            int base = 60 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) terrain[i] = base + rnd.nextInt(5) * 3;  // 有起伏
            int maxStep = 2 + rnd.nextInt(5);           // 2..6

            boolean[] banned = new boolean[n];
            for (int i = 0; i < n; i++) banned[i] = rnd.nextInt(4) == 0;   // 约 25% 禁用

            Optional<ModeDpSolver.Solution> dp = ModeDpSolver.solve(terrain, maxStep, s, banned);

            double best = bruteForce(terrain, maxStep, s, banned);
            if (Double.isInfinite(best)) {
                assertFalse(dp.isPresent(), "trial=" + trial + " 暴力无解，DP 却有解");
                continue;
            }
            assertTrue(dp.isPresent(), "trial=" + trial + " 暴力有解，DP 却说无解 terrain="
                    + java.util.Arrays.toString(terrain) + " maxStep=" + maxStep);
            assertEquals(best, dp.get().totalCost(), 1e-9,
                    "trial=" + trial + " terrain=" + java.util.Arrays.toString(terrain)
                            + " maxStep=" + maxStep
                            + " DP=" + dp.get().totalCost() + " 最优=" + best
                            + " DP解=" + dp.get());
            // 解必须自洽：高度在对应模式的窗口内、坡度合法、模式转移合法
            int[] p = dp.get().profile();
            ModeDpSolver.Mode[] ms = dp.get().modes();
            for (int i = 0; i < n; i++) {
                assertTrue(ModeDpSolver.allowed(terrain[i], p[i], ms[i], s, banned[i]),
                        "trial=" + trial + " 第 " + i + " 站越窗 y=" + p[i] + " mode=" + ms[i]
                                + " banned=" + banned[i]);
                if (i > 0) {
                    assertTrue(Math.abs(p[i] - p[i - 1]) <= maxStep,
                            "trial=" + trial + " 第 " + i + " 站超坡");
                    assertTrue(ModeDpSolver.transitionAllowed(ms[i - 1], ms[i]),
                            "trial=" + trial + " 第 " + (i - 1) + "→" + i + " 模式非法");
                }
            }
            compared++;
        }
        assertTrue(compared > 60, "有效对拍组数太少：" + compared);
    }

    // ── 独立实现的暴力解（刻意不复用被测代码）─────────────────

    private static double bruteForce(int[] terrain, int maxStep, ModeDpSolver.Settings s,
                                     boolean[] banned) {
        // 候选模式只看「该模式在任何高度下是否合法」；banned 对 GROUND 是整站禁用，
        // 所以这里用「至少一站允许」的宽松口径，真正的约束在 walk 里逐站判。
        boolean bannedForOptions = false;
        List<int[]> heightOptions = new ArrayList<>();
        List<List<ModeDpSolver.Mode>> modeOptions = new ArrayList<>();
        for (int i = 0; i < terrain.length; i++) {
            int lo = terrain[i] - Math.max(s.maxCut(), s.maxTunnelDepth());
            int hi = terrain[i] + Math.max(s.maxFill(), s.maxBridgeHeight());
            int[] ys = new int[hi - lo + 1];
            for (int k = 0; k < ys.length; k++) ys[k] = lo + k;
            heightOptions.add(ys);
            List<ModeDpSolver.Mode> ok = new ArrayList<>();
            for (ModeDpSolver.Mode m : ModeDpSolver.Mode.values()) {
                // 只要该模式下至少有一个合法高度，就把模式放进候选（高度在下面逐一对齐）
                for (int y : ys) {
                    if (modeAllows(terrain[i], y, m, s, bannedForOptions)) {
                        ok.add(m);
                        break;
                    }
                }
            }
            modeOptions.add(ok);
        }
        double[] best = new double[]{Double.POSITIVE_INFINITY};
        walk(terrain, maxStep, s, 0, new int[terrain.length],
                new ModeDpSolver.Mode[terrain.length], heightOptions, modeOptions, best, banned);
        return best[0];
    }

    private static void walk(int[] terrain, int maxStep, ModeDpSolver.Settings s, int i,
                             int[] curY, ModeDpSolver.Mode[] curM,
                             List<int[]> heights, List<List<ModeDpSolver.Mode>> modes,
                             double[] best, boolean[] banned) {
        if (i == terrain.length) {
            double total = 0;
            for (int k = 0; k < curY.length; k++) {
                total += stationCost(terrain[k], curY[k], curM[k], s);
                if (k == 0) {
                    total += enterCost(curM[k], s);
                } else if (curM[k] != curM[k - 1]) {
                    total += enterCost(curM[k], s);
                }
            }
            if (total < best[0]) best[0] = total;
            return;
        }
        for (ModeDpSolver.Mode m : modes.get(i)) {
            for (int y : heights.get(i)) {
                if (!modeAllows(terrain[i], y, m, s, banned[i])) continue;
                if (i > 0) {
                    if (Math.abs(y - curY[i - 1]) > maxStep) continue;
                    if (!modeTransitionOk(curM[i - 1], m)) continue;
                }
                curY[i] = y;
                curM[i] = m;
                walk(terrain, maxStep, s, i + 1, curY, curM, heights, modes, best, banned);
            }
        }
    }

    private static boolean modeAllows(int terrain, int y, ModeDpSolver.Mode m,
                                      ModeDpSolver.Settings s, boolean banned) {
        if (m == ModeDpSolver.Mode.GROUND) {
            return !banned && y >= terrain - s.maxCut() && y <= terrain + s.maxFill();
        }
        if (m == ModeDpSolver.Mode.BRIDGE) return y >= terrain && y <= terrain + s.maxBridgeHeight();
        return y >= terrain - s.maxTunnelDepth() && y <= terrain - s.minTunnelCover();
    }

    private static boolean modeTransitionOk(ModeDpSolver.Mode from, ModeDpSolver.Mode to) {
        if (from == to) return true;
        return !((from == ModeDpSolver.Mode.BRIDGE && to == ModeDpSolver.Mode.TUNNEL)
                || (from == ModeDpSolver.Mode.TUNNEL && to == ModeDpSolver.Mode.BRIDGE));
    }

    private static double stationCost(int terrain, int y, ModeDpSolver.Mode m, ModeDpSolver.Settings s) {
        if (m == ModeDpSolver.Mode.BRIDGE) return s.bridgePerStation();
        if (m == ModeDpSolver.Mode.TUNNEL) return s.tunnelPerStation();
        int d = y - terrain;
        CostModel.Settings g = s.groundCost();
        if (d == 0) return 0;
        int depth = Math.abs(d);
        int shallow = Math.min(depth, g.deepThreshold());
        int deep = depth - shallow;
        double w = d > 0 ? g.fillWeight() : g.cutWeight();
        return w * shallow + w * g.deepMultiplier() * deep;
    }

    private static double enterCost(ModeDpSolver.Mode m, ModeDpSolver.Settings s) {
        if (m == ModeDpSolver.Mode.BRIDGE) return s.abutmentCost();
        if (m == ModeDpSolver.Mode.TUNNEL) return s.portalCost();
        return 0;
    }
}