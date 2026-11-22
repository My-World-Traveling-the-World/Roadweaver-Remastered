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
 * 一次遍历就得到<b>全局最优</b>，而不是「每道 pass 局部最优」。</p>
 *
 * <p>二阶状态为(站、高度、前一高差)，D=2N+1。2026-11-21利用可行前驱
 * 必为前缀/后缀这一性质，将高差两两枚举的O(n·H·D²)降为O(n·H·D)；
 * 同价父节点和完整回溯顺序保持旧契约。</p>
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
 * <p>{@code O(n · H · D)}，其中 {@code n} 是站数、{@code H} 是高度窗口宽度，
 * {@code D=2N+1} 是高差状态数。二阶状态是(站i、高度y、高差d)，
 * 每个前一高度用前缀/后缀最小值一次求出所有后继；不再两两枚举高差。</p>
 *
 * <p>本行在 2026-11-15 复核时更正：原来只写 {@code O(n · H)}，与本文件
 * 顶部的「时效性更正」及方法级说明自相矛盾。一阶约束下才退化为 {@code O(n · H)}。</p>
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
            /** 每站被钉死的高度；有无锁高由独立标记决定。 */
            int[] pin,
            /**
             * 二阶约束：<b>任意相隔两站</b>的高度差上限（「每两站最多升降 N 格」）。
             *
             * <p>配置 {@code maxSlopeStepPerTwoSegments} 的真实语义就是这个数。
             * {@code < 0} 表示关闭二阶约束，退回只看 {@link #maxStep} 的一阶模型。</p>
             *
             * <p><b>★ 为什么必须有它</b>：一阶的 {@code maxStep} 允许「连续每站 +1」——
             * 三站能升 3 格，远超「每两站 1 格」。只有二阶约束能正确表达后者。
             * 2026-11-13 的 AB 实测就是因为缺了它，给了 DP 两倍于生产允许的坡度，
             * 于是 DP 拿「更陡」去换「更少土方」—— 那个对比是不公平的。</p>
             */
            int maxStepPerTwo,
            boolean[] pinnedStations) {

        /** 一阶模型的便捷构造（无二阶约束）。 */
        public Problem(int[] lo, int[] hi, double[][] cost, int maxStep, int[] pin) {
            this(lo, hi, cost, maxStep, pin, -1);
        }

        /** 只把正高度夹具的旧写法翻译成实际标记，避免复制求解逻辑。 */
        public Problem(int[] lo, int[] hi, double[][] cost, int maxStep, int[] pin, int maxStepPerTwo) {
            this(lo, hi, cost, maxStep, pin, maxStepPerTwo, ProfileDpSource.nonNegativePins(pin));
        }

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
            if (pinnedStations != null && (pin == null || pinnedStations.length != lo.length)) {
                throw new IllegalArgumentException("锁高标记长度与站数不一致");
            }
            if (cost != null && cost.length != lo.length) {
                throw new IllegalArgumentException("cost 行数与站数不一致");
            }
        }

        public int stations() {
            return lo.length;
        }

        /** 高程可以为负，不能借数值判断该站有没有锁高。 */
        public boolean pinned(int station) {
            return pinnedStations != null && pinnedStations[station];
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

        // 二阶约束（每两站 ≤N 格）走另一条路：状态要多带「上一站高差」。
        if (p.maxStepPerTwo() >= 0) {
            return solveWithTwoStationLimit(p, n, minH, width);
        }

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

    /**
     * 带二阶约束的求解：「任意相隔两站的高度差 ≤ {@code maxStepPerTwo}」。
     *
     * <h2>为什么状态要多一维</h2>
     * <p>一阶模型只需要记住「上一站多高」；而「每两站 ≤N 格」是关于
     * <b>两个相邻高差之和</b>的约束：</p>
     * <pre>
     *   d_i = y_i − y_{i−1}
     *   约束：|d_i + d_{i−1}| ≤ N        ← 这就是「每两站」
     * </pre>
     * <p>所以状态必须是 {@code (站, 当前高度, 上一站高差)}。高差只有
     * {@code [−N, N]} 共 {@code 2N+1} 个取值 —— N=1 时只有 3 个，
     * 整体 {@code O(n·H·(2N+1))}；实际耗时应由具体站数、窗口和机器测量。</p>
     *
     * <h2>★ 与一阶的关键差别</h2>
     * <p>一阶 {@code maxStep=1} 允许 {@code +1,+1,+1}（三站升 3 格）；
     * 二阶 {@code N=1} 禁止任何两个同向 +1 相邻（因为 {@code |d_i + d_{i−1}| ≤ 1}），
     * 所以「连续每站 +1」这类剖面会被<b>明确判不可行</b>，而不是悄悄输出。</p>
     */
    private static Optional<Solution> solveWithTwoStationLimit(Problem p, int n, int minH, int width) {
        final double INF = Double.POSITIVE_INFINITY;
        final int lim = p.maxStepPerTwo();
        final int dCount = 2 * lim + 1;          // 高差 −lim..+lim
        final int zero = lim;                    // 高差 0 的下标

        // 单站：直接挑窗口内代价最小的合法高度
        if (n == 1) {
            int bestK = -1;
            double bestCost = INF;
            for (int k = 0; k < width; k++) {
                int y = minH + k;
                if (!allowed(p, 0, y)) continue;
                double c = costAt(p, 0, y);
                if (c < bestCost) {
                    bestCost = c;
                    bestK = k;
                }
            }
            if (bestK < 0) return Optional.empty();
            return Optional.of(new Solution(new int[]{minH + bestK}, bestCost));
        }

        int states = width * dCount;
        double[] prev = new double[states];
        double[] cur = new double[states];
        // parent[i][k*dCount + dIdx] = 上一站的状态下标
        int[][] parent = new int[n][states];

        // ── 第 0 站：高差未定义，统一放在 zero 下标 ──
        java.util.Arrays.fill(prev, INF);
        for (int k = 0; k < width; k++) {
            int idx = k * dCount + zero;
            parent[0][idx] = -1;
            int y = minH + k;
            if (!allowed(p, 0, y)) continue;
            prev[idx] = costAt(p, 0, y);
        }

        for (int i = 1; i < n; i++) {
            java.util.Arrays.fill(cur, INF);
            // 二站可行前驱是前缀或后缀；线性扫描替代高差两两枚举，父节点同价规则保留。
            ProfilePairTransitions.advance(p, i, minH, width, prev, cur, parent[i]);
            double[] tmp = prev;
            prev = cur;
            cur = tmp;
        }

        // ── 取终态最优 ──
        int bestState = -1;
        double bestCost = INF;
        for (int s = 0; s < states; s++) {
            if (prev[s] < bestCost) {
                bestCost = prev[s];
                bestState = s;
            }
        }
        if (bestState < 0) return Optional.empty();

        // ── 回溯 ──
        int[] stateAt = new int[n];
        int s = bestState;
        for (int i = n - 1; i >= 0; i--) {
            stateAt[i] = s;
            if (i > 0) {
                s = parent[i][s];
                if (s < 0) return Optional.empty();
            }
        }
        int[] profile = new int[n];
        for (int i = 0; i < n; i++) {
            profile[i] = minH + stateAt[i] / dCount;
        }
        return Optional.of(new Solution(profile, bestCost));
    }

    // 转移阶段共用原判据，避免为性能重写一套窗口/钉住语义。
    static boolean allowed(Problem p, int i, int y) {
        if (y < p.lo()[i] || y > p.hi()[i]) return false;
        return !p.pinned(i) || p.pin()[i] == y;
    }

    static double costAt(Problem p, int i, int y) {
        double[][] cost = p.cost();
        if (cost == null || cost[i] == null) return 0.0;
        int idx = y - p.lo()[i];
        if (idx < 0 || idx >= cost[i].length) return 0.0;
        return cost[i][idx];
    }
}
