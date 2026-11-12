/* 文件职责：把「地面/桥/隧道」的判定与高度求解合成一张 DP —— 一次求解同时定两件事。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;
import java.util.Optional;

/**
 * 模式感知的纵断面求解器：<b>一次 DP 同时决定</b>「每站多高」与「这段是地面 / 桥 / 隧道」。
 *
 * <h2>它要解决的那个死结</h2>
 * <p>旧版把两件事分两步做：先由 {@code PathSpanExtractor} 判出桥和隧道（只看水、只看高度差），
 * 再让 {@code TargetYComputation} 在那些区间里按"桥面要平、隧道要够高"去凑剖面。
 * 于是必然出现两类打架：</p>
 * <ul>
 *   <li><b>判定先给了桥，剖面却想往下切</b> —— 桥面被压进地里，或者山坡被切成桥；</li>
 *   <li><b>剖面先压低了，判定才发现该是桥</b> —— 于是补一道 pass 去"提升成桥"，
 *       而那道 pass 又改变高度，前面的坡度约束全部失效。</li>
 * </ul>
 *
 * <p>根因不是哪一步判错了，而是<b>两个决策互相依赖却分两次做</b>。
 * 桥/隧道是否划算，取决于这条路在那个高度要挖多少土；而高度又取决于这段是不是桥。
 * 这个循环只能靠<b>联合求解</b>打破。</p>
 *
 * <h2>模型</h2>
 * <pre>
 *   状态   (站 i, 高度 y, 模式 m)     m ∈ {GROUND, BRIDGE, TUNNEL}
 *   转移   |y_{i+1} − y_i| ≤ maxStep                     坡度上限（与旧版同口径）
 *          模式之间：GROUND ↔ BRIDGE、GROUND ↔ TUNNEL 允许
 *                    BRIDGE ↔ TUNNEL 禁止（必须先落地）
 *   代价   每站：GROUND 按挖填方（复用 CostModel）；BRIDGE/TUNNEL 按每站单价
 *          每进入一次桥/隧道：一次性建造费（桥台 / 洞口）
 *   约束   地面：terrain − maxCut ≤ y ≤ terrain + maxFill
 *          桥  ：terrain ≤ y ≤ terrain + maxBridgeHeight（桥面在地面之上）
 *          隧道：terrain − maxTunnelDepth ≤ y ≤ terrain − minTunnelCover（必须有覆土）
 * </pre>
 *
 * <p>复杂度 {@code O(n · H · 9)}：三个模式各一条单调队列，模式间只有 9 种组合。
 * 2000 站 × 窗口 80 ≈ 144 万次转移，毫秒级。</p>
 *
 * <h2>与旧版的关系</h2>
 * <p>本类<b>不</b>取代 {@code PathSpanExtractor} 的判定 —— 它现在是个纯内核，
 * 只被测试使用。接线时它应当是"候选解生成器"：<br>
 * 旧判定给出硬约束（已有公路必须桥、河面必须桥、保护区不许挖），
 * 作为<b>模式约束</b>钉进来，剩下的自由段交给 DP 自己权衡桥 / 隧道 / 挖填。</p>
 *
 * <h2>★ 不做的事</h2>
 * <ul>
 *   <li>不采样地形、不读世界 —— 全是纯数组；</li>
 *   <li>不判断"这里是不是河" —— 那是地形语义，由调用方通过约束表达；</li>
 *   <li>不落盘。</li>
 * </ul>
 */
public final class ModeDpSolver {

    /** 构造模式。 */
    public enum Mode {
        /** 普通路面：贴地，靠挖填。 */
        GROUND,
        /** 桥：桥面在地面之上。 */
        BRIDGE,
        /** 隧道：必须有覆土。 */
        TUNNEL
    }

    /**
     * 求解参数。
     *
     * <p>单价是<b>产品决策</b>：桥每站比挖方贵多少、隧道又贵多少，
     * 直接决定"遇山打洞还是绕/切"。所以这些值全部外置，不在代码里写死。</p>
     */
    public record Settings(
            /** 地面允许的最大挖方深度（格）。 */
            int maxCut,
            /** 地面允许的最大填方高度（格）。 */
            int maxFill,
            /** 桥面允许高出地面的上限（格）。 */
            int maxBridgeHeight,
            /** 隧道允许的最大埋深（格）。 */
            int maxTunnelDepth,
            /** 隧道必须的最小覆土厚度（格）。 */
            int minTunnelCover,
            /** 桥每站单价。 */
            double bridgePerStation,
            /** 隧道每站单价。 */
            double tunnelPerStation,
            /** 进入桥梁的一次性建造费（桥台）。 */
            double abutmentCost,
            /** 进入隧道的一次性建造费（洞口）。 */
            double portalCost,
            /** 地面挖填方的代价参数。 */
            CostModel.Settings groundCost) {

        public static Settings defaults() {
            // 单价取"桥比深挖便宜、隧道比桥贵得多"的顺序，
            // 这是现实直觉：能架桥就架桥，打洞是最后手段。
            return new Settings(8, 4, 12, 40, 6, 5.0, 14.0, 3.0, 6.0,
                    CostModel.Settings.defaults());
        }

        public Settings {
            if (maxCut < 0 || maxFill < 0 || maxBridgeHeight < 0
                    || maxTunnelDepth < 0 || minTunnelCover < 0) {
                throw new IllegalArgumentException("窗口参数不能为负");
            }
            if (maxTunnelDepth < minTunnelCover) {
                throw new IllegalArgumentException("maxTunnelDepth 必须 ≥ minTunnelCover（否则隧道没有可用高度）");
            }
            if (bridgePerStation < 0 || tunnelPerStation < 0
                    || abutmentCost < 0 || portalCost < 0) {
                throw new IllegalArgumentException("单价不能为负");
            }
            if (groundCost == null) throw new IllegalArgumentException("groundCost 不能为 null");
        }
    }

    /** 解。{@code profile[i]} 与 {@code modes[i]} 一一对应。 */
    public static final class Solution {
        private final int[] profile;
        private final Mode[] modes;
        private final double totalCost;

        Solution(int[] profile, Mode[] modes, double totalCost) {
            this.profile = profile;
            this.modes = modes;
            this.totalCost = totalCost;
        }

        public int[] profile() {
            return profile.clone();
        }

        public Mode[] modes() {
            return modes.clone();
        }

        public double totalCost() {
            return totalCost;
        }

        /** 某种模式覆盖的站数（诊断用）。 */
        public int stationCount(Mode m) {
            int n = 0;
            for (Mode x : modes) {
                if (x == m) n++;
            }
            return n;
        }

        /** 连续区间的个数（例如"桥有几座"）。 */
        public int runCount(Mode m) {
            int runs = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i] == m && (i == 0 || modes[i - 1] != m)) runs++;
            }
            return runs;
        }

        @Override
        public String toString() {
            return "Solution{" + "cost=" + String.format("%.2f", totalCost)
                    + " ground=" + stationCount(Mode.GROUND)
                    + " bridge=" + stationCount(Mode.BRIDGE) + "(" + runCount(Mode.BRIDGE) + "座)"
                    + " tunnel=" + stationCount(Mode.TUNNEL) + "(" + runCount(Mode.TUNNEL) + "条)"
                    + "}";
        }
    }

    private ModeDpSolver() {
    }

    /**
     * 求解。
     *
     * @return 不可行时 {@link Optional#empty()}；<b>绝不返回一条违反约束的剖面</b>
     */
    public static Optional<Solution> solve(int[] terrain, int maxStep, Settings s) {
        return solve(terrain, maxStep, s, null);
    }

    /**
     * 求解（带「地面不可用」掩码）。
     *
     * <h2>★ 为什么必须有这个参数</h2>
     * <p>第一版没有它，结果 DP 永远不选桥也不选隧道 —— 原因很朴素：
     * <b>「跟着地形走」在纯挖填模型里是零成本的</b>。地形往下走，剖面跟着下；
     * 地形往上走，剖面跟着上。既然不要钱，凭什么架桥？</p>
     *
     * <p>桥和隧道真正被需要，不是因为它「更省土方」，而是因为<b>地面本身不可用</b>：
     * 水面（不能在水里铺路）、保护区/遗产地（不许开挖）、以及已经审批过的不可动区域。
     * 这些是<b>物理/法规约束</b>，不是成本偏好，所以必须表达成掩码而不是单价。</p>
     *
     * @param groundBanned 每站地面是否禁止使用；{@code null} 表示全都可以
     */
    public static Optional<Solution> solve(int[] terrain, int maxStep, Settings s,
                                           boolean[] groundBanned) {
        int n = terrain.length;
        if (n == 0) return Optional.empty();
        if (maxStep < 0) throw new IllegalArgumentException("maxStep 不能为负");

        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, terrain[i] - Math.max(s.maxCut(), s.maxTunnelDepth()));
            hi = Math.max(hi, terrain[i] + Math.max(s.maxFill(), s.maxBridgeHeight()));
        }
        int W = hi - lo + 1;
        Mode[] modes = Mode.values();
        int M = modes.length;
        final double INF = Double.POSITIVE_INFINITY;

        double[][] prev = new double[M][W];
        int[][][] parentMode = new int[n][M][W];
        int[][][] parentK = new int[n][M][W];

        // ── 第 0 站 ──
        for (int m = 0; m < M; m++) {
            Arrays.fill(prev[m], INF);
            for (int k = 0; k < W; k++) {
                int y = lo + k;
                parentMode[0][m][k] = -1;
                parentK[0][m][k] = -1;
                if (!allowed(terrain[0], y, modes[m], s, bannedAt(groundBanned, 0))) continue;
                prev[m][k] = perStationCost(terrain[0], y, modes[m], s)
                        + entryCost(modes[m], s);   // 第 0 站就开桥/进洞也要付建造费
            }
        }

        int[] deque = new int[W];
        double[][] windowBest = new double[M][W];
        int[][] windowArg = new int[M][W];

        for (int i = 1; i < n; i++) {
            // ① 每个前一模式，做一次滑动窗口最小
            for (int m = 0; m < M; m++) {
                Arrays.fill(windowBest[m], INF);
                Arrays.fill(windowArg[m], -1);
                int head = 0;
                int tail = 0;
                int next = 0;
                for (int k = 0; k < W; k++) {
                    while (next < W && next <= k + maxStep) {
                        while (tail > head && prev[m][deque[tail - 1]] >= prev[m][next]) tail--;
                        deque[tail++] = next;
                        next++;
                    }
                    while (tail > head && deque[head] < k - maxStep) head++;
                    if (tail == head) continue;
                    windowBest[m][k] = prev[m][deque[head]];
                    windowArg[m][k] = deque[head];
                }
            }

            // ② 组合模式转移
            double[][] cur = new double[M][W];
            for (int m = 0; m < M; m++) {
                Arrays.fill(cur[m], INF);
                for (int k = 0; k < W; k++) {
                    int y = lo + k;
                    if (!allowed(terrain[i], y, modes[m], s, bannedAt(groundBanned, i))) continue;
                    double best = INF;
                    int bestFrom = -1;
                    for (int pm = 0; pm < M; pm++) {
                        if (!transitionAllowed(modes[pm], modes[m])) continue;
                        if (windowBest[pm][k] == INF) continue;
                        double cand = windowBest[pm][k] + transitionCost(modes[pm], modes[m], s);
                        if (cand < best) {
                            best = cand;
                            bestFrom = pm;
                        }
                    }
                    if (bestFrom < 0) continue;
                    cur[m][k] = best + perStationCost(terrain[i], y, modes[m], s);
                    parentMode[i][m][k] = bestFrom;
                    parentK[i][m][k] = windowArg[bestFrom][k];
                }
            }
            prev = cur;
        }

        // ── 取终态最优 ──
        double bestCost = INF;
        int bestM = -1;
        int bestK = -1;
        for (int m = 0; m < M; m++) {
            for (int k = 0; k < W; k++) {
                if (prev[m][k] < bestCost) {
                    bestCost = prev[m][k];
                    bestM = m;
                    bestK = k;
                }
            }
        }
        if (bestM < 0) return Optional.empty();

        int[] profile = new int[n];
        Mode[] chosen = new Mode[n];
        int m = bestM;
        int k = bestK;
        for (int i = n - 1; i >= 0; i--) {
            profile[i] = lo + k;
            chosen[i] = modes[m];
            if (i == 0) break;
            int pm = parentMode[i][m][k];
            int pk = parentK[i][m][k];
            if (pm < 0 || pk < 0) return Optional.empty();
            m = pm;
            k = pk;
        }
        return Optional.of(new Solution(profile, chosen, bestCost));
    }

    private static boolean bannedAt(boolean[] mask, int i) {
        return mask != null && i < mask.length && mask[i];
    }

    /** 该高度在该模式下是否落在允许窗口内。 */
    static boolean allowed(int terrain, int y, Mode m, Settings s) {
        return allowed(terrain, y, m, s, false);
    }

    /** 该高度在该模式下是否落在允许窗口内（带「地面禁用」）。 */
    static boolean allowed(int terrain, int y, Mode m, Settings s, boolean groundBanned) {
        return switch (m) {
            case GROUND -> !groundBanned && y >= terrain - s.maxCut() && y <= terrain + s.maxFill();
            case BRIDGE -> y >= terrain && y <= terrain + s.maxBridgeHeight();
            case TUNNEL -> y >= terrain - s.maxTunnelDepth() && y <= terrain - s.minTunnelCover();
        };
    }

    /** 模式转移是否允许。桥与隧道不能直接相连 —— 必须先落地，否则是"悬空洞口"。 */
    static boolean transitionAllowed(Mode from, Mode to) {
        if (from == to) return true;
        return !((from == Mode.BRIDGE && to == Mode.TUNNEL)
                || (from == Mode.TUNNEL && to == Mode.BRIDGE));
    }

    /** 进入某个模式的一次性建造费；同模式内为 0。 */
    static double transitionCost(Mode from, Mode to, Settings s) {
        if (from == to) return 0.0;
        return entryCost(to, s);
    }

    private static double entryCost(Mode to, Settings s) {
        return switch (to) {
            case GROUND -> 0.0;
            case BRIDGE -> s.abutmentCost();
            case TUNNEL -> s.portalCost();
        };
    }

    private static double perStationCost(int terrain, int y, Mode m, Settings s) {
        return switch (m) {
            case GROUND -> {
                int d = y - terrain;
                yield d >= 0 ? CostModel.fillCost(d, s.groundCost())
                        : CostModel.cutCost(-d, s.groundCost());
            }
            case BRIDGE -> s.bridgePerStation();
            case TUNNEL -> s.tunnelPerStation();
        };
    }
}