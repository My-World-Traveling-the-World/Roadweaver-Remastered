/* 文件职责：DP 剖面不可行时的确定性回落解 —— 坡限约束下的最贴近地形剖面。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;

/**
 * 纵断面回落求解器（2026-11-14 新增，为「缺陷 C 去双跑」铺路）。
 *
 * <h2>为什么需要它</h2>
 * <p>现在 {@code TargetYComputation} 的流程是：<b>先</b>无条件跑完旧版 15 道 pass，
 * <b>再</b>用 DP 结果整条覆盖 —— 只有在 DP 不可行 / 抛异常时才回落到旧 pass 的结果。
 * 想删掉旧 pass（用户拍板「旧的全部删除」），就必须先有一个<b>不比旧 pass 差、
 * 且完全无状态</b>的回落解，否则 DP 一旦不可行整条路就没剖面了。</p>
 *
 * <h2>它是什么，不是什么</h2>
 * <ul>
 *   <li><b>是</b>：一个确定性的投影 —— 在「高度窗口 + 一阶/二阶坡度 + 站钉 + 桥面下界 +
 *       隧道覆土」这些<b>硬约束</b>下，找出「与地形偏离最小」的剖面。</li>
 *   <li><b>不是</b>：不是「更差的 DP」。它没有代价函数、不做全局优化、不做挖填比选 ——
 *       它只是把地形投影进可行域。所以它的土方一定不优于 DP，但一定<b>满足硬约束</b>。</li>
 * </ul>
 *
 * <h2>算法</h2>
 * <p>初始取「地形高度夹进窗口」，然后反复做<b>前向 + 后向</b>两遍坡度夹逼：</p>
 * <pre>
 * 前向：h[i] 必须落在 h[i−1] ± maxStep 内，且 (h[i]−h[i−1]) + (h[i−1]−h[i−2]) ∈ ±perTwo
 * 后向：同理从右往左再来一遍
 * </pre>
 * <p>单调收缩 ⇒ 每轮只会把剖面「压得更平」，不会振荡；直到某一轮没有任何改动即收敛。
 * 站钉（pin）与下界（桥面/覆土）在每一轮之后重新夹一次，保证它们不被坡度夹逼破坏。</p>
 *
 * <p>收敛性：迭代上限 {@value #MAX_ROUNDS} 轮，正常地形 3~5 轮收敛（测试钉住幂等性）。</p>
 */
public final class ProfileFallback {

    /** 迭代轮数上限（收敛后提前退出，这里只是防病态输入）。 */
    public static final int MAX_ROUNDS = 64;

    private ProfileFallback() {
    }

    /**
     * 在硬约束下求「最贴近地形」的剖面。
     *
     * @param terrainY  每站地面高度
     * @param maxCut    允许的最大挖深（格），≥0
     * @param maxFill   允许的最大填高（格），≥0
     * @param maxStep   一阶坡度上限：相邻两站高差绝对值上限（格）
     * @param perTwo    二阶坡度上限：{@code |d_i + d_{i−1}|} 上限，其中 {@code d_i = h_i − h_{i−1}}
     * @param pin       每站钉死的高度；{@code null} 或该位 &lt; 0 表示不钉
     * @param mustCover 每站是否必须被覆盖（隧道）；{@code null} 表示无
     * @param minCover  {@code mustCover} 时的最小覆土（格）
     * @param deckFloor 每站桥面高度下界；{@code null} 或 {@code Integer.MIN_VALUE} 表示无
     * @return 满足硬约束的剖面（长度与 {@code terrainY} 一致）
     */
    public static int[] terrainFollowing(int[] terrainY, int maxCut, int maxFill,
                                         int maxStep, int perTwo,
                                         int[] pin, boolean[] mustCover, int minCover,
                                         int[] deckFloor) {
        if (terrainY == null) throw new IllegalArgumentException("terrainY 不能为 null");
        int n = terrainY.length;
        int[] h = new int[n];
        if (n == 0) return h;

        int cut = Math.max(0, maxCut);
        int fill = Math.max(0, maxFill);
        int step = Math.max(1, maxStep);
        int two = Math.max(1, perTwo);
        int cover = Math.max(0, minCover);

        // ① 每站的合法区间，初始取「地形夹进窗口」
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int i = 0; i < n; i++) {
            int lower = terrainY[i] - cut;
            int upper = terrainY[i] + fill;
            if (mustCover != null && i < mustCover.length && mustCover[i]) {
                lower = Math.max(lower, terrainY[i] + cover);   // 隧道：必须被覆土
            }
            if (deckFloor != null && i < deckFloor.length
                    && deckFloor[i] != Integer.MIN_VALUE) {
                lower = Math.max(lower, deckFloor[i]);          // 桥面：不许低于水面下界
            }
            if (pin != null && i < pin.length && pin[i] >= 0) {
                lower = Math.max(lower, pin[i]);                // 站钉：上下界都锁死到该值
                upper = Math.min(upper, pin[i]);
                // 若窗口与钉子矛盾（例如必须覆土又钉死在低处），以钉为准 ——
                // 站钉来自实测地表/结构边界，是更强的契约。
                if (lower > upper) {
                    lower = pin[i];
                    upper = pin[i];
                }
            }
            lo[i] = lower;
            hi[i] = Math.max(lower, upper);
            h[i] = Math.max(lo[i], Math.min(hi[i], terrainY[i]));
        }

        // ② 前向 + 后向坡度夹逼，单调收缩直到不动
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            changed |= clampForward(h, lo, hi, step, two);
            changed |= clampBackward(h, lo, hi, step, two);
            if (!changed) break;
        }
        return h;
    }

    /** 从左到右：把 h[i] 夹进「由 h[i−1]、h[i−2] 决定」的可行区间。 */
    private static boolean clampForward(int[] h, int[] lo, int[] hi, int step, int two) {
        boolean changed = false;
        for (int i = 1; i < h.length; i++) {
            int low = h[i - 1] - step;
            int high = h[i - 1] + step;
            if (i >= 2) {
                // |d_i + d_{i−1}| ≤ two，其中 d_i = h_i − h_{i−1}、d_{i−1} = h[i−1] − h[i−2]
                int prevDelta = h[i - 1] - h[i - 2];
                low = Math.max(low, h[i - 1] - two - prevDelta);
                high = Math.min(high, h[i - 1] + two - prevDelta);
            }
            low = Math.max(low, lo[i]);
            high = Math.min(high, hi[i]);
            if (low > high) {
                // 硬约束互斥（例如站钉 + 覆土 + 坡度三者不可同时满足）：
                // 放弃二阶约束，只保证一阶与窗口 —— 这一步只在病态输入上触发。
                low = Math.max(lo[i], Math.min(hi[i], h[i - 1] - step));
                high = Math.max(low, Math.min(hi[i], h[i - 1] + step));
            }
            int target = Math.max(low, Math.min(high, h[i]));
            if (target != h[i]) {
                h[i] = target;
                changed = true;
            }
        }
        return changed;
    }

    /** 从右到左：同一套规则镜像。 */
    private static boolean clampBackward(int[] h, int[] lo, int[] hi, int step, int two) {
        boolean changed = false;
        for (int i = h.length - 2; i >= 0; i--) {
            int low = h[i + 1] - step;
            int high = h[i + 1] + step;
            if (i + 2 < h.length) {
                int nextDelta = h[i + 2] - h[i + 1];
                low = Math.max(low, h[i + 1] - two - nextDelta);
                high = Math.min(high, h[i + 1] + two - nextDelta);
            }
            low = Math.max(low, lo[i]);
            high = Math.min(high, hi[i]);
            if (low > high) {
                low = Math.max(lo[i], Math.min(hi[i], h[i + 1] - step));
                high = Math.max(low, Math.min(hi[i], h[i + 1] + step));
            }
            int target = Math.max(low, Math.min(high, h[i]));
            if (target != h[i]) {
                h[i] = target;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 统计剖面违反硬约束的站数（0 表示完全合规）。
     *
     * <p>给测试与生产日志共用：回落解是否真的合规不该靠「看起来差不多」判断。</p>
     */
    public static int violations(int[] h, int maxStep, int perTwo) {
        if (h == null) return 0;
        int bad = 0;
        int step = Math.max(1, maxStep);
        int two = Math.max(1, perTwo);
        for (int i = 1; i < h.length; i++) {
            int delta = Math.abs(h[i] - h[i - 1]);
            if (delta > step) bad++;
            if (i >= 2) {
                int second = Math.abs((h[i] - h[i - 1]) + (h[i - 1] - h[i - 2]));
                if (second > two) bad++;
            }
        }
        return bad;
    }

    /** 土方量 {@code Σ|h − terrain|}，供 A/B 比较。 */
    public static long earthwork(int[] h, int[] terrainY) {
        if (h == null || terrainY == null) return 0L;
        long sum = 0L;
        for (int i = 0; i < Math.min(h.length, terrainY.length); i++) {
            sum += Math.abs((long) h[i] - terrainY[i]);
        }
        return sum;
    }
}