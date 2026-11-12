/* 文件职责：剖面的可比较度量 —— AB 双跑时唯一被拿来对比的数字。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;

/**
 * 剖面度量：把「这条剖面好不好」压成几个可比较的数字。
 *
 * <h2>为什么需要它</h2>
 * <p>AB 双跑最容易变成「GPU 里看一眼觉得还行」。要把「新解更好」变成可复核的判断，
 * 就必须先把「好」量化。这里选的四项各自对应一类真实抱怨：</p>
 *
 * <table>
 *   <tr><th>指标</th><th>对应的抱怨</th></tr>
 *   <tr><td>{@code p95Grade} 坡度 95 分位</td><td>「这条路太陡了」</td></tr>
 *   <tr><td>{@code maxStep} 单站最大落差</td><td>「路面一格一格像楼梯」</td></tr>
 *   <tr><td>{@code cut} 挖方总格数</td><td>「路像被切进山体里」</td></tr>
 *   <tr><td>{@code fill} 填方总格数</td><td>「路像浮在空中」</td></tr>
 *   <tr><td>{@code steps} 超过 1 格的相邻落差处数</td><td>0.9.027「桥头逐列阶梯」</td></tr>
 * </table>
 *
 * <p><b>单位约定</b>：坡度的「格/站」。旧版实测站距 = 1.0 格/站
 * （{@code RoadConstants.MEASURED_STATION_SPACING_BLOCKS}），所以格/站 ≈ 百分比坡度。</p>
 */
public record ProfileMetrics(
        /** 坡度 95 分位（格/站）。 */
        double p95Grade,
        /** 单站最大落差（格）。 */
        int maxStep,
        /** 挖方总格数。 */
        long cut,
        /** 填方总格数。 */
        long fill,
        /** 超过 1 格的相邻落差处数（桥段内不计）。 */
        int steps) {

    /** 计算某个剖面的度量。 */
    public static ProfileMetrics of(int[] profile, int[] terrain, boolean[] crossing) {
        int n = profile.length;
        if (n == 0) return new ProfileMetrics(0, 0, 0, 0, 0);
        int[] deltas = new int[Math.max(0, n - 1)];
        int maxStep = 0;
        int steps = 0;
        for (int i = 1; i < n; i++) {
            int d = Math.abs(profile[i] - profile[i - 1]);
            deltas[i - 1] = d;
            maxStep = Math.max(maxStep, d);
            boolean inCrossing = crossing != null && crossing[i] && crossing[i - 1];
            if (d > 1 && !inCrossing) steps++;
        }
        Arrays.sort(deltas);
        double p95 = deltas.length == 0 ? 0
                : deltas[Math.min(deltas.length - 1, (int) Math.floor(deltas.length * 0.95))];
        long cut = 0;
        long fill = 0;
        if (terrain != null) {
            for (int i = 0; i < n && i < terrain.length; i++) {
                int d = profile[i] - terrain[i];
                if (d > 0) fill += d;
                else if (d < 0) cut += -d;
            }
        }
        return new ProfileMetrics(p95, maxStep, cut, fill, steps);
    }

    /** 人类可读的一行（日志用）。 */
    public String line() {
        return "p95=" + String.format("%.2f", p95Grade)
                + " maxStep=" + maxStep
                + " cut=" + cut
                + " fill=" + fill
                + " steps=" + steps;
    }

    /**
     * 与另一个度量做胜负数统计。
     *
     * <p>口径：坡度和挖填方<b>越小越好</b>，台阶数越小越好。
     * 这里只做计数，不做加权总分 —— 权重是产品决策，不该藏进代码里。</p>
     */
    public String versus(ProfileMetrics other) {
        int win = 0;
        int lose = 0;
        if (p95Grade < other.p95Grade) win++; else if (p95Grade > other.p95Grade) lose++;
        if (maxStep < other.maxStep) win++; else if (maxStep > other.maxStep) lose++;
        if (cut + fill < other.cut + other.fill) win++; else if (cut + fill > other.cut + other.fill) lose++;
        if (steps < other.steps) win++; else if (steps > other.steps) lose++;
        return "win=" + win + " lose=" + lose;
    }
}