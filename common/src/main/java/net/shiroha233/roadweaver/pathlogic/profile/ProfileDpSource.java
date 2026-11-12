/* 文件职责：由「地形 + 桥段 + 坡度上限」构造 DP 问题 —— 与旧版剖面实现完全独立的输入翻译。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;

/**
 * 把「一条路的地形与跨度」翻译成 {@link ProfileDpSolver.Problem}。
 *
 * <h2>为什么必须和旧实现无关</h2>
 * <p>AB 双跑的目的是判断新解是否更好。如果新解的输入里掺了旧实现的输出
 * （例如借用旧剖面在桥两端的锚点），那对比就失去意义 —— 新解只是在复现旧解。
 * 所以这里的桥段处理只用<b>地形</b>信息：桥段取两端地形的直线内插。</p>
 *
 * <h2>窗口口径</h2>
 * <ul>
 *   <li>普通站：{@code [terrain − maxCut, terrain + maxFill]} —— 允许的挖填方上限；</li>
 *   <li>桥面站：钉死在两端地形的直线内插值（桥面本来就该是一条直坡或平线）；</li>
 *   <li>隧道站：暂不特殊处理（隧道属于 S4「桥隧并入同一 DAG」的范畴）。</li>
 * </ul>
 *
 * <p>把桥段钉成直线内插是刻意的简化：它让 DP 在桥段退化成「只求解两端之间的
 * 过渡」，从而把对比聚焦在<b>普通路段的剖面质量</b>上，而不是考我桥该怎么设计。</p>
 */
public final class ProfileDpSource {

    private ProfileDpSource() {
    }

    /** 一次翻译的产物：问题 + 用于回溯的边界信息。 */
    public record Prepared(ProfileDpSolver.Problem problem, int[] terrain, boolean[] crossing) {
    }

    /**
     * 构造问题。
     *
     * @param terrainY  每站地面高度
     * @param crossing  每站是否位于桥/跨水上（决定是否钉成直线）
     * @param maxCut    允许的最大挖方深度（格）
     * @param maxFill   允许的最大填方高度（格）
     * @param maxStep   相邻站最大高度差（格）
     */
    public static Prepared prepare(int[] terrainY, boolean[] crossing,
                                   int maxCut, int maxFill, int maxStep) {
        int n = terrainY.length;
        if (n == 0) throw new IllegalArgumentException("至少一站");
        int[] lo = new int[n];
        int[] hi = new int[n];
        int[] pin = new int[n];
        for (int i = 0; i < n; i++) {
            pin[i] = -1;
            lo[i] = terrainY[i] - maxCut;
            hi[i] = terrainY[i] + maxFill;
        }
        // 桥段钉成两端地形直线内插
        int i = 0;
        while (i < n) {
            if (crossing == null || !crossing[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && crossing[i]) i++;
            int end = i - 1;
            int a = terrainY[start];
            int b = terrainY[end];
            int len = end - start;
            for (int k = start; k <= end; k++) {
                int chord = len == 0 ? a : a + Math.round((b - a) * (k - start) / (float) len);
                pin[k] = chord;
                lo[k] = chord;
                hi[k] = chord;
            }
        }
        double[][] cost = CostModel.fromTerrain(terrainY, lo, hi, CostModel.Settings.defaults());
        return new Prepared(new ProfileDpSolver.Problem(lo, hi, cost, maxStep, pin), terrainY, crossing);
    }

    /**
     * 直接求解。
     *
     * @return 不可行时返回 {@link Optional#empty()} —— 调用方据此判断「DP 这条路走不通」，
     *         而不是拿到一条坏剖面
     */
    public static Optional<int[]> solve(int[] terrainY, boolean[] crossing,
                                        int maxCut, int maxFill, int maxStep) {
        Prepared p = prepare(terrainY, crossing, maxCut, maxFill, maxStep);
        return ProfileDpSolver.solve(p.problem()).map(ProfileDpSolver.Solution::profile);
    }
}