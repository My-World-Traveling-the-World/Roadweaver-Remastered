/* 文件职责：纵断面的全局最优求解（DP）—— 只做数学，不采样、不判桥隧、不落盘。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;

/**
 * 纵断面（targetY）的全局最优求解器。
 *
 * <h2>为什么要有它</h2>
 * <p>旧版算剖面是一条 <b>15 道 pass 的启发式流水线</b>：
 * 平滑 → 夹坡度 → 修桥头 → 修隧道 → 再平滑 → 再夹 ……
 * 每道 pass 都按自己的口径改一次高度，最后靠
 * {@code repairFinalHeightConflict} 去补救各 pass 之间的冲突。</p>
 *
 * <p>这种结构的根本问题：<b>没有目标函数</b>。所以「更好」无法定义，
 * pass 之间互相打架也只能靠再加一道 pass 去调停 —— 于是 15 道变成 20 道，
 * 每加一道就让前面几道的假设失效一点。</p>
 *
 * <h2>这里换成什么</h2>
 * <p>把整条路的剖面当成一个<b>有约束的最优化问题</b>，一次解出来：</p>
 * <pre>
 *   minimize    Σ_i  cost(i, y_i)                       每站放 y 的代价（挖填方）
 *   subject to  |y_{i+1} − y_i| ≤ maxStep                相邻站坡度上限
 *               lo_i ≤ y_i ≤ hi_i                       允许高度窗口
 *               y_i = pin_i （若该站被钉住）             桥面/隧道/接缝锁定
 * </pre>
 *
 * <p>这是标准的<b>最短路径 DAG 问题</b>：站是时间维、高度是状态维、坡度是转移约束。
 * 用单调队列把转移从 {@code O(H·d)} 降到 {@code O(H)}，整体
 * {@code O(n·H)} —— 一次遍历就得到<b>全局最优</b>，而不是「每道 pass 局部最优」。</p>
 *
 * <h2>为什么它比 15 道 pass 更可维护</h2>
 * <ul>
 *   <li>只有一个地方定义「什么叫好」：{@link CostModel}；</li>
 *   <li>只有一个地方定义「什么叫合法」：约束；</li>
 *   <li>不会出现「pass A 抬高了高度、pass B 又降回去」的互相打架；</li>
 *   <li>不可行时能<b>明确报告</b>，而不是悄悄输出一条看着像样的坏剖面。</li>
 * </ul>
 *
 * <h2>复杂度</h2>
 * <p>{@code O(n · H)}，其中 {@code n} 是站数、{@code H} 是高度窗口宽度。
 * 一条 2000 站、窗口 60 格的路 = 12 万次状态转移，毫秒级。</p>
 *
 * <h2>★ 本类不做的事（刻意划清）</h2>
 * <ul>
 *   <li>不采样地形 —— 地形由调用方给成 {@code cost} 与 `lo/hi`；</li>
 *   <li>不决定桥还是隧道 —— 那是 span 判定的职责，这里只接受「钉住」约束；</li>
 *   <li>不落盘 —— 输出就是 {@code int[]}，谁需要谁存。</li>
 * </ul>
 */
public final class ProfileDpSolver {

    private ProfileDpSolver() {
    }

    /** 一个待求解的剖面问题。全部是普通数组，方便脱离世界构造。 */
    public record Problem(
            /** 每站允许的最低高度（含）。 */
            int[] lo,
            /** 每站允许的最高高度（含）。 */
            int[] hi,
            /** 每站在各个高度上的代价；{@code cost[i][y - lo[i]]}。允许为 null（等价于全 0）。 */
            double[][] cost,
            /** 相邻两站之间允许的最大高度差（格）。 */
            int maxStep,
            /** 每站被钉死的高度；{@code null} 或负值表示该站不钉。 */
            int[] pin) {

        public Problem {
            if (lo == null || hi == null) throw new IllegalArgumentException("lo/hi 不能为 null");
            if (lo.length != hi.length) throw new IllegalArgumentException("lo/hi 长度不一致");
            if (lo.length == 0) throw new IllegalArgumentException("至少需要一站");
            for (int i = 0; i < lo.length; i++) {
                if (lo[i] > hi[i]) {
                    throw new IllegalArgumentException("第 " + i + " 站窗口为空：lo=" + lo[i] + " hi=" + hi[i]);
                }
            }
            if (maxStep < 0) throw new IllegalArgumentException("maxStep 不能为负");
            if (pin != null && pin.length != lo.length) {
                throw new IllegalArgumentException("pin 长度与站数不一致");
            }
            if (cost != null && cost.length != lo.length) {
                throw new IllegalArgumentException("cost 行数与站数不一致");
            }
        }

        public int stations() {
            return lo.length;
        }
    }

    /** 求解结果。 */
    public record Solution(
            /** 每站的最终高度。 */
            int[] profile,
            /** 总代价（含钉住站的代价）。 */
            double totalCost) {

        public Solution {
            profile = profile.clone();
        }

        @Override
        public int[] profile() {
            return profile.clone();
        }
    }

    /**
     * 求解。
     *
     * @return 可行时返回解；任何一站无路可走时返回 {@link Optional#empty()}
     *         —— <b>不可行必须报出来</b>，绝不返回一条「看着还行」的坏剖面
     */
    public static Optional<Solution> solve(Problem p) {
        int n = p.stations();
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            minH = Math.min(minH, p.lo()[i]);
            maxH = Math.max(maxH, p.hi()[i]);
        }
        int width = maxH - minH + 1;

        final double INF = Double.POSITIVE_INFINITY;
        double[] prev = new double[width];
        double[] cur = new double[width];
        // parent[i][k] = 第 i 站在高度 (minH+k) 时，上一站选的是哪个 k
        int[][] parent = new int[n][width];

        // ── 第 0 站 ──
        for (int k = 0; k < width; k++) {
            int y = minH + k;
            if (!allowed(p, 0, y)) {
                prev[k] = INF;
                parent[0][k] = -1;
                continue;
            }
            prev[k] = costAt(p, 0, y);
            parent[0][k] = -1;
        }

        int[] deque = new int[width];
        for (int i = 1; i < n; i++) {
            java.util.Arrays.fill(cur, INF);
            // 单调队列：在滑动窗口 [k-maxStep, k+maxStep] 上维护 prev 的最小值
            int head = 0;
            int tail = 0;
            int next = 0;                       // 下一个待入队的 k'
            for (int k = 0; k < width; k++) {
                int y = minH + k;
                // 把窗口右边界推进到 k+maxStep
                while (next < width && next <= k + p.maxStep()) {
                    while (tail > head && prev[deque[tail - 1]] >= prev[next]) {
                        tail--;
                    }
                    deque[tail++] = next;
                    next++;
                }
                // 把窗口左边界之外的元素弹出
                while (tail > head && deque[head] < k - p.maxStep()) {
                    head++;
                }
                if (!allowed(p, i, y)) continue;
                if (tail == head) continue;     // 窗口内没有可达状态
                double best = prev[deque[head]];
                if (best == INF) continue;
                cur[k] = best + costAt(p, i, y);
                parent[i][k] = deque[head];
            }
            double[] tmp = prev;
            prev = cur;
            cur = tmp;
        }

        // ── 取终态最优 ──
        int bestK = -1;
        double bestCost = INF;
        for (int k = 0; k < width; k++) {
            if (prev[k] < bestCost) {
                bestCost = prev[k];
                bestK = k;
            }
        }
        if (bestK < 0) return Optional.empty();

        int[] profile = new int[n];
        int k = bestK;
        for (int i = n - 1; i >= 0; i--) {
            profile[i] = minH + k;
            k = parent[i][k];
            if (i > 0 && k < 0) {
                // 理论上不可达：可行为什么是靠 parent 串起来的
                return Optional.empty();
            }
        }
        return Optional.of(new Solution(profile, bestCost));
    }

    private static boolean allowed(Problem p, int i, int y) {
        if (y < p.lo()[i] || y > p.hi()[i]) return false;
        int[] pin = p.pin();
        return pin == null || pin[i] < 0 || pin[i] == y;
    }

    private static double costAt(Problem p, int i, int y) {
        double[][] cost = p.cost();
        if (cost == null || cost[i] == null) return 0.0;
        int idx = y - p.lo()[i];
        if (idx < 0 || idx >= cost[i].length) return 0.0;
        return cost[i][idx];
    }
}