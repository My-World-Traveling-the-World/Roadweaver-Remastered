/* 文件职责：DP 异常时的确定性可行性投影；互斥窗口必须报告失败。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;

/** 修复器只接纳可行窗口；失败必须显式报告，不能让高度跳变混入后续跨度与施工。 */
public final class ProfileFallback {

    private ProfileFallback() {
    }

    /**
     * 在硬约束下构造可行剖面；土方最优仍由 DP 求解，不把投影称为全局最优。
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
        return terrainFollowing(terrainY, maxCut, maxFill, maxStep, perTwo,
                new ProfileDpSource.Constraints(pin, mustCover, Math.max(0, minCover), deckFloor));
    }

    /** 正式编排复用同一份锁高标记，异常恢复也不能丢掉地下入口。 */
    public static int[] terrainFollowing(int[] terrainY, int maxCut, int maxFill,
                                         int maxStep, int perTwo, ProfileDpSource.Constraints constraints) {
        if (terrainY == null) throw new IllegalArgumentException("terrainY 不能为 null");
        int n = terrainY.length;
        if (n == 0) return new int[0];

        int cut = Math.max(0, maxCut);
        int fill = Math.max(0, maxFill);
        int step = Math.max(1, maxStep);
        int two = Math.max(1, perTwo);
        int cover = constraints.minCover();
        int[] pin = constraints.pin();
        int[] deckFloor = constraints.deckFloor();

        // 覆土只限定上界；洞身下界采用本路段的有限高度域，不能被 maxCut 钉死成贴山起伏。
        int tunnelFloor = Arrays.stream(terrainY).min().orElse(0) - Math.max(cut, cover);
        int[] lo = new int[n];
        int[] hi = new int[n];
        for (int i = 0; i < n; i++) {
            boolean covered = constraints.covered(i);
            lo[i] = covered ? tunnelFloor : terrainY[i] - cut;
            hi[i] = covered ? terrainY[i] - cover : terrainY[i] + fill;
            if (deckFloor != null && i < deckFloor.length && deckFloor[i] != Integer.MIN_VALUE) {
                lo[i] = Math.max(lo[i], deckFloor[i]);
            }
            if (constraints.pinned(i)) {
                if (pin[i] < lo[i] && deckFloor != null && i < deckFloor.length && pin[i] < deckFloor[i]
                        || covered && pin[i] > hi[i]) {
                    throw new IllegalArgumentException("站钉与桥面下界或隧道覆土互斥，站=" + i);
                }
                lo[i] = pin[i];
                hi[i] = pin[i];
            }
        }
        return ProfileFeasibility.project(terrainY, lo, hi, step, two)
                .orElseThrow(() -> new IllegalArgumentException("纵断面窗口与坡度约束互斥，不能发布坏剖面"));
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
