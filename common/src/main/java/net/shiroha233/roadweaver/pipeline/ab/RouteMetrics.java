/* 文件职责：中立的路由度量 —— 只吃一串点，新旧两套算法用同一把尺子。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 与算法无关的路由度量。
 *
 * <h2>为什么必须中立</h2>
 * <p>旧链输出站序列、新链输出曲线，结构完全不同。要对比就必须有一把
 * <b>两边都能过</b>的尺子。这里选的四项覆盖了历史上真机出过问题的全部形态：</p>
 *
 * <ul>
 *   <li><b>长度</b> —— 直接对应真机基线「加权绕行比 2.93」那个问题
 *       （{@code fine regenerate} 曾把 8/17 条路变长，最多 +42.7%）</li>
 *   <li><b>重复 XZ</b> —— 对应 {@code dupXz} 指标，历史上从 2 涨到 18</li>
 *   <li><b>曲率能量</b> —— 对应锯齿与急弯（{@code Σ|γ_{i+1}−2γ_i+γ_{i−1}|²}）</li>
 *   <li><b>最大转角</b> —— 对应「桥头航向突变 −30.82°/+30.82°」那类问题</li>
 * </ul>
 *
 * <h2>为什么不用平均转角</h2>
 * <p>平均值会把一个 90° 急弯摊薄到看不见。历史上就吃过这个亏：
 * {@code sharpTurnCount} 用 {@code >90.0} 的严格判据，而 D1 的转向角恰好
 * <b>等于</b> 90° ⇒ 结构性抓不到，调阈值也没用。所以这里看<b>最大值</b>。</p>
 *
 * @param points          点数
 * @param length          折线弧长（格）
 * @param duplicateXz     重复 (x,z) 的个数
 * @param curvatureEnergy 离散曲率能量
 * @param maxTurnDegrees  相邻两段的最大转角（度）
 */
public record RouteMetrics(int points,
                           double length,
                           int duplicateXz,
                           double curvatureEnergy,
                           double maxTurnDegrees) {

    /**
     * 从点列计算。
     *
     * @param xs x 坐标序列
     * @param zs z 坐标序列
     */
    public static RouteMetrics from(int[] xs, int[] zs) {
        if (xs == null || zs == null || xs.length != zs.length) {
            throw new IllegalArgumentException("xs 与 zs 必须等长且非空");
        }
        int n = xs.length;
        if (n == 0) return new RouteMetrics(0, 0.0, 0, 0.0, 0.0);

        double length = 0.0;
        double curvature = 0.0;
        double maxTurn = 0.0;

        Set<Long> seen = new HashSet<>();
        int duplicate = 0;
        for (int i = 0; i < n; i++) {
            long key = (((long) xs[i]) << 32) ^ (zs[i] & 0xFFFFFFFFL);
            if (!seen.add(key)) duplicate++;
        }

        for (int i = 1; i < n; i++) {
            length += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }

        for (int i = 1; i < n - 1; i++) {
            double ddx = (xs[i + 1] - 2.0 * xs[i]) + xs[i - 1];
            double ddz = (zs[i + 1] - 2.0 * zs[i]) + zs[i - 1];
            curvature += ddx * ddx + ddz * ddz;
        }

        for (int i = 1; i < n - 1; i++) {
            double ax = xs[i] - xs[i - 1];
            double az = zs[i] - zs[i - 1];
            double bx = xs[i + 1] - xs[i];
            double bz = zs[i + 1] - zs[i];
            double la = Math.hypot(ax, az);
            double lb = Math.hypot(bx, bz);
            if (la < 1e-9 || lb < 1e-9) continue;
            double cos = Math.max(-1.0, Math.min(1.0, (ax * bx + az * bz) / (la * lb)));
            maxTurn = Math.max(maxTurn, Math.toDegrees(Math.acos(cos)));
        }

        return new RouteMetrics(n, length, duplicate, curvature, maxTurn);
    }

    /** 从点列计算（{@code int[]{x,z}} 形式）。 */
    public static RouteMetrics fromPairs(List<int[]> points) {
        int n = points.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i)[0];
            zs[i] = points.get(i)[1];
        }
        return from(xs, zs);
    }

    /** 便于日志。 */
    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "points=%d length=%.1f dupXz=%d curvature=%.1f maxTurn=%.1f°",
                points, length, duplicateXz, curvatureEnergy, maxTurnDegrees);
    }
}
