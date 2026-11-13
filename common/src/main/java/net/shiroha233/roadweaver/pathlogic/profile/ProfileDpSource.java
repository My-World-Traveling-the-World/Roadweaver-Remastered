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

    /**
     * 硬契约：DP 必须无条件遵守的三件事。
     *
     * <h2>为什么这三条必须是「约束」而不是「代价」</h2>
     * <p>它们都来自旧版 {@code computeTargetY} 里已经存在的硬契约，
     * 玩家能直接看到违反的后果：</p>
     *
     * <table>
     *   <tr><th>契约</th><th>违反的后果</th></tr>
     *   <tr><td>{@code pin} 结构边界锁定（村庄 / 出生点 / 命名结构端点）</td>
     *       <td>路的入口悬在屋顶上方或埋进地里 —— 玩家一眼就看出来</td></tr>
     *   <tr><td>{@code pin} 未知端点共享高度</td>
     *       <td>从两个方向来的路在同一结构口落成两个不同高度，接缝处出现台阶</td></tr>
     *   <tr><td>{@code mustBeCovered} 隧道覆土</td>
     *       <td>隧道顶离地表太近，挖穿成露天沟</td></tr>
     * </table>
     *
     * <p>把它们做成代价项是错的 —— 代价可以被"更划算的方案"打败，
     * 而这三条没有"更划算"的余地。</p>
     *
     * @param pin            每站钉死的高度；{@code null} 或负值表示不钉
     * @param mustBeCovered  每站是否必须保持覆土（隧道段）；{@code null} 表示没有
     * @param minCover       覆土最小厚度（格）
     */
    public record Constraints(int[] pin, boolean[] mustBeCovered, int minCover) {

        /** 无约束。 */
        public static Constraints none() {
            return new Constraints(null, null, 0);
        }

        public Constraints {
            if (minCover < 0) throw new IllegalArgumentException("minCover 不能为负");
            if (pin != null && mustBeCovered != null && pin.length != mustBeCovered.length) {
                throw new IllegalArgumentException("pin 与 mustBeCovered 长度必须一致");
            }
        }

        /** 某站是否被钉住。 */
        public boolean pinned(int i) {
            return pin != null && i < pin.length && pin[i] >= 0;
        }

        /** 某站是否必须有覆土。 */
        public boolean covered(int i) {
            return mustBeCovered != null && i < mustBeCovered.length && mustBeCovered[i];
        }
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
        return prepare(terrainY, crossing, maxCut, maxFill, maxStep, Constraints.none());
    }

    /** 带硬契约的翻译。 */
    public static Prepared prepare(int[] terrainY, boolean[] crossing,
                                   int maxCut, int maxFill, int maxStep,
                                   Constraints constraints) {
        return prepare(terrainY, crossing, maxCut, maxFill, maxStep, -1, constraints);
    }

    /**
     * 带硬契约 + <b>二阶坡度约束</b>的翻译。
     *
     * @param maxStepPerTwo 「任意<b>相隔两站</b>的高度差上限」，即配置
     *                      {@code maxSlopeStepPerTwoSegments} 的真实语义；
     *                      {@code < 0} 表示退回只看 {@code maxStep} 的一阶模型。
     *
     * <h2>★ 为什么必须单独给这个值</h2>
     * <p>一阶的 {@code maxStep} 上限是「每站」，而生产配置说的是「每两站」。
     * 用半值（{@code ceil(perTwo/2)}）去近似是错的 —— 它允许「连续每站 +1」，
     * 三站就升 3 格，正好比配置允许的多一倍。2026-11-13 的 AB 实测就栽在这里。</p>
     */
    public static Prepared prepare(int[] terrainY, boolean[] crossing,
                                   int maxCut, int maxFill, int maxStep,
                                   int maxStepPerTwo, Constraints constraints) {
        int n = terrainY.length;
        if (n == 0) throw new IllegalArgumentException("至少一站");
        if (constraints == null) constraints = Constraints.none();
        int[] lo = new int[n];
        int[] hi = new int[n];
        int[] pin = new int[n];
        for (int i = 0; i < n; i++) {
            pin[i] = -1;
            lo[i] = terrainY[i] - maxCut;
            hi[i] = terrainY[i] + maxFill;
        }
        // ── 契约 ③：隧道覆土 ──
        // 覆土是硬要求，所以当它与「最大挖方」冲突时，让步的是挖方：
        // 宁可挖得比 maxCut 更深，也不能让隧道顶离地表太近（那会挖穿成露天沟）。
        for (int i = 0; i < n; i++) {
            if (!constraints.covered(i)) continue;
            int cap = terrainY[i] - constraints.minCover();
            if (cap < hi[i]) hi[i] = cap;
            if (lo[i] > hi[i]) lo[i] = hi[i];
        }
        // ── 契约 ①②：钉住（结构边界 / 未知端点共享高度）──
        for (int i = 0; i < n; i++) {
            if (!constraints.pinned(i)) continue;
            int v = constraints.pin()[i];
            if (constraints.covered(i) && v > terrainY[i] - constraints.minCover()) {
                throw new IllegalArgumentException("第 " + i + " 站既被钉在 " + v
                        + " 又要求覆土 ≥ " + constraints.minCover() + "（地表 " + terrainY[i]
                        + "）—— 这两个契约互相矛盾，调用方接线错了");
            }
            pin[i] = v;
            lo[i] = v;
            hi[i] = v;
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
                // ★ 契约 ①②（结构边界锁定 / 未知端点共享高度）优先于「桥段钉成直线」。
                //
                // 这一段曾经无条件写 pin/lo/hi，把前面刚写好的 pin 顶掉 ——
                // 2026-11-13 独立审计实测：`terrain=70, tunnel=0..2, pin[0]=62` 时
                // 输出是 `[70,70,70,70,70]`，锁定点被悄悄丢弃；pin 值若再高一点还会
                // 触发构造期的「钉住 vs 覆土」矛盾异常，被上层 catch 吞掉后整条路静默回落。
                // 这正是旧版 0.8.369 记录过的「锁定 407、实际 389」那类接缝错位。
                if (constraints.pinned(k)) continue;
                int chord = len == 0 ? a : a + Math.round((b - a) * (k - start) / (float) len);
                pin[k] = chord;
                lo[k] = chord;
                hi[k] = chord;
            }
        }
        double[][] cost = CostModel.fromTerrain(terrainY, lo, hi, CostModel.Settings.defaults());
        return new Prepared(new ProfileDpSolver.Problem(lo, hi, cost, maxStep, pin, maxStepPerTwo),
                terrainY, crossing);
    }

    /**
     * 直接求解。
     *
     * @return 不可行时返回 {@link Optional#empty()} —— 调用方据此判断「DP 这条路走不通」，
     *         而不是拿到一条坏剖面
     */
    public static Optional<int[]> solve(int[] terrainY, boolean[] crossing,
                                        int maxCut, int maxFill, int maxStep) {
        return solve(terrainY, crossing, maxCut, maxFill, maxStep, Constraints.none());
    }

    /**
     * 带硬契约的求解。
     *
     * <h2>钉住的站会如何影响整条剖面</h2>
     * <p>钉住 + {@code maxStep} 合起来<b>自动生成合法引道</b>：
     * DP 必须从那个高度出发、每站最多变 maxStep，于是它会在钉住站两侧
     * 铺出一条满足坡度上限的过渡 —— 这正是旧版 {@code applySingleBoundaryGrade}
     * 手工在做的事（从锁定点向内铺一格一格的坡），只是 DP 顺带就得到了。</p>
     *
     * <p><b>不可行的情形</b>：钉住的高度离地形太远、且站数不足以在坡度上限内过渡到时，
     * 返回 {@link Optional#empty()}。调用方必须把这种情况当作真实失败处理
     * （旧版是靠 {@code applySingleBoundaryGrade} 硬铺坡，可能铺出超过坡度上限的陡坡）。</p>
     */
    public static Optional<int[]> solve(int[] terrainY, boolean[] crossing,
                                        int maxCut, int maxFill, int maxStep,
                                        Constraints constraints) {
        return solve(terrainY, crossing, maxCut, maxFill, maxStep, -1, constraints);
    }

    /**
     * 带二阶坡度约束的求解 —— <b>生产路径应当用这个</b>。
     *
     * @param maxStepPerTwo 「任意相隔两站的高度差上限」= 配置
     *                      {@code maxSlopeStepPerTwoSegments} 的原始值（不要再除以 2）
     */
    public static Optional<int[]> solve(int[] terrainY, boolean[] crossing,
                                        int maxCut, int maxFill, int maxStep,
                                        int maxStepPerTwo, Constraints constraints) {
        Prepared p = prepare(terrainY, crossing, maxCut, maxFill, maxStep, maxStepPerTwo, constraints);
        return ProfileDpSolver.solve(p.problem()).map(ProfileDpSolver.Solution::profile);
    }
}