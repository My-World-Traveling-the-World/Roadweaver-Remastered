/* 文件职责：盘山路专用度量（0.9.061，纯几何、只读、无世界访问）。 */
package net.shiroha233.roadweaver.pathlogic.metrics;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.shiroha233.roadweaver.postprocess.SawtoothMetric;

/**
 * 盘山路（switchback）专用度量 —— 0.9.061 新增，<b>纯几何、只读</b>。
 *
 * <p><b>为什么需要它。</b>0.9.060 之前「有没有盘山路」只能靠人眼看日志里的
 * {@code route QUALITY}，而那行里的 {@code hairpins}（在 {@code tuning.RouteQualityMetrics} 里，
 * 判据是「方向反转 &gt;150°」）对盘山路根本没有分辨力：一条真正的盘山路，每个回头弯在
 * 逐个中心站的折线上看都是「一格一格转 90°」，方向反转判据抓不到；
 * 反过来 ±1 格的锯齿因为 {@code max|ℓ| <= 1.2d} 之类的条件被排除在外，也说明
 * 「横向幅度」才是这个族的关键坐标。没有尺子就是盲改 —— 所以这里先把尺子做出来。</p>
 *
 * <p><b>判据（发卡弯 hairpin）。</b>以「首站 → 末站」的弦为参照，把每一站投影成
 * <b>带符号的横向位移 ℓ</b>（左正右负，见 {@link #evaluate}）：</p>
 * <ol>
 *   <li>ℓ 在某一站由升转降（或由降转升）⇒ 这是一个<b>横向翻转点</b>（回头弯的弯心）；</li>
 *   <li>该点的横向幅度 {@code |ℓ| > 3 × 路宽} —— 这是把「发卡弯」和「锯齿」分开的那条线，
 *       锯齿的 ℓ 只有 ±1 格；</li>
 *   <li>翻转点两侧的腿各 ≥ {@value #MIN_HAIRPIN_LEG_STATIONS} 站 —— 真实回头线够长，
 *       排除「原地抖一下」。</li>
 * </ol>
 *
 * <p><b>刻意不复用</b> {@code SawtoothMetric} 的 {@code max|ℓ| <= 1.2d} 条件：那条条件把
 * 「横向位移大」定义成病态，而盘山路的横向位移<b>本来就该大</b>（一条回头线要横着铺开
 * 几十格），套上去会把真正的盘山路全部判掉。</p>
 *
 * <p><b>口径来源说明（不粉饰）。</b>{@code 3 × 路宽} 与 {@code 8 站} 这两个阈值
 * <b>是拍的</b>，不是实测标定：3 倍路宽对应「至少横挪三个路幅才算真的折返」，
 * 8 站对应「1 格/站的站距下约 8 格长的腿」。它们的作用是让这一次的三档对比
 * <b>有可比的数字</b>，不是物理定律；后续应拿真机盘的实测分布回来重标。</p>
 */
public final class SwitchbackMetrics {

    /** 回头线的两条腿各至少多少站（1 站 ≈ 1 格）。 */
    public static final int MIN_HAIRPIN_LEG_STATIONS = 8;
    /** 回头弯的横向幅度至少是路宽的多少倍。 */
    public static final double HAIRPIN_LATERAL_ROAD_WIDTHS = 3.0D;
    /** 相邻两段视为「同向共线」的相对叉积上限（≈1.15°）。 */
    private static final double COLLINEAR_TOLERANCE = 0.02D;

    private SwitchbackMetrics() {
    }

    /** 折返报告区分曲率、腿长与坡度，避免一个发卡弯计数替代完整质量评价。
 *
     * @param hairpins        发卡弯数量
     * @param hairpinRadiusP50 弯心曲率半径的中位数（格，三站外接圆半径；退化成直线的样本不计）
     * @param hairpinLegP50   回头线腿长的中位数（站；一个弯的两条腿取平均）
     * @param gradeP50        逐站坡度的中位数（百分比）
     * @param gradeP95        逐站坡度的 95 分位（百分比）
     * @param straightClimbMax 最长「同向共线且持续爬升」的连续步数（步；k 步 = k+1 站）
     */
    public record Report(int hairpins,
                         double hairpinRadiusP50,
                         double hairpinLegP50,
                         double gradeP50,
                         double gradeP95,
                         int straightClimbMax) {

        /** {@code route SWITCHBACK} 那一行的正文。 */
        public String format() {
            return String.format(Locale.ROOT,
                    "hairpins=%d hairpinRadiusP50=%.2f hairpinLegP50=%.1f "
                            + "gradeP50=%.2f gradeP95=%.2f straightClimbMax=%d",
                    hairpins, hairpinRadiusP50, hairpinLegP50, gradeP50, gradeP95, straightClimbMax);
        }
    }

    private static final Report EMPTY = new Report(0, 0.0D, 0.0D, 0.0D, 0.0D, 0);

    /** 适配器：从一条已完成的 {@link RoadData} 取中心站，交给纯几何函数。 */
    public static Report fromRoad(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.targetY() == null) return EMPTY;
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        List<Integer> targetY = road.targetY();
        int n = Math.min(segments.size(), targetY.size());
        if (n < 2) return EMPTY;
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement segment = segments.get(i);
            BlockPos middle = segment == null ? null : segment.middlePos();
            if (middle == null) return EMPTY;
            xs[i] = middle.getX();
            zs[i] = middle.getZ();
            ys[i] = targetY.get(i);
        }
        return evaluate(xs, zs, ys, road.width());
    }

    /**
     * 纯几何入口。<b>不读世界、不读配置</b>，只吃三条坐标数组与路宽，所以能在纯 JUnit 里跑。
     *
     * @param xs   逐站 X
     * @param zs   逐站 Z
     * @param ys   逐站 Y（剖面高度；用于坡度与「持续爬升」判定）
     * @param width 路宽（格），只用于把「3 倍路宽」换算成横向阈值
     */
    public static Report evaluate(double[] xs, double[] zs, double[] ys, int width) {
        if (xs == null || zs == null || ys == null) return EMPTY;
        int n = Math.min(xs.length, Math.min(zs.length, ys.length));
        if (n < 3) return EMPTY;

        // ── 逐站坡度（百分比）──────────────────────────────────────────────
        List<Double> grades = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            double horizontal = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            if (horizontal <= 1e-6D) continue;
            grades.add(100.0D * Math.abs(ys[i] - ys[i - 1]) / horizontal);
        }

        // ── 横向位移 ℓ：以「首站 → 末站」的弦为参照 ─────────────────────────
        double chordX = xs[n - 1] - xs[0];
        double chordZ = zs[n - 1] - zs[0];
        double chordLength = Math.hypot(chordX, chordZ);
        double[] lateral = new double[n];
        if (chordLength > 1e-6D) {
            double ux = chordX / chordLength;
            double uz = chordZ / chordLength;
            // 左法向 = (-uz, ux)。只做基准线投影，**不做去均值**：真实盘山路是
            // 「0 → +A → 0 → +A」的锯齿，去均值会把幅度砍半，反而漏判。
            for (int i = 0; i < n; i++) {
                double dx = xs[i] - xs[0];
                double dz = zs[i] - zs[0];
                lateral[i] = dx * (-uz) + dz * ux;
            }
        }

        // ── 横向翻转点（ℓ 的局部极值，用 ±2 站的趋势判，容忍 1 站平台）────────
        //
        // 为什么不用「相邻两站差值变号」：网格上的回头弯弧线必然有连续两站 x 相同
        //（(11,0)→(12,0)→(12,1)→(11,1) 的 ℓ 是 -11,-12,-12,-11），相邻差值会出现 0，
        // 恰好把弯心漏掉。改成比较 i 与 i±2 的趋势：只要求「左边在涨、右边在跌」。
        List<Integer> candidates = new ArrayList<>();
        for (int i = 2; i < n - 2; i++) {
            double before = lateral[i] - lateral[i - 2];
            double after = lateral[i + 2] - lateral[i];
            if (before * after < 0.0D) candidates.add(i);
        }
        // 同一个弯心常常连着两站都判成极值（弯顶两侧斜率只是略微反号），
        // 不合并的话「腿长」会被算成 1 站，真正的盘山路会被腿长判据全部否掉 ——
        // 实测三角波弯顶必然出现 (11,12) 这样的一对。相隔 <=2 站的候选合并成一个，
        // 保留横向幅度更大的那一站。
        List<Integer> turns = new ArrayList<>(candidates.size());
        for (int candidate : candidates) {
            if (!turns.isEmpty() && candidate - turns.get(turns.size() - 1) <= 2) {
                int last = turns.get(turns.size() - 1);
                if (Math.abs(lateral[candidate]) > Math.abs(lateral[last])) {
                    turns.set(turns.size() - 1, candidate);
                }
                continue;
            }
            turns.add(candidate);
        }

        double lateralThreshold = HAIRPIN_LATERAL_ROAD_WIDTHS * Math.max(1, width);
        int hairpins = 0;
        List<Double> radii = new ArrayList<>(turns.size());
        List<Double> legs = new ArrayList<>(turns.size());
        for (int t = 0; t < turns.size(); t++) {
            int center = turns.get(t);
            if (Math.abs(lateral[center]) <= lateralThreshold) continue;
            int previous = t == 0 ? 0 : turns.get(t - 1);
            int next = t == turns.size() - 1 ? n - 1 : turns.get(t + 1);
            int legBefore = center - previous;
            int legAfter = next - center;
            if (legBefore < MIN_HAIRPIN_LEG_STATIONS || legAfter < MIN_HAIRPIN_LEG_STATIONS) continue;
            hairpins++;
            legs.add((legBefore + legAfter) / 2.0D);
            double radius = circumradius(xs, zs, center - 1, center, center + 1);
            if (!Double.isNaN(radius) && !Double.isInfinite(radius)) radii.add(radius);
        }

        // ── 最长「同向共线 + 持续爬升」的直段 ─────────────────────────────
        int straightClimbMax = 0;
        int run = 0;
        for (int i = 1; i < n; i++) {
            double dx = xs[i] - xs[i - 1];
            double dz = zs[i] - zs[i - 1];
            boolean climb = ys[i] > ys[i - 1];
            boolean straight = true;
            if (i >= 2) {
                double pdx = xs[i - 1] - xs[i - 2];
                double pdz = zs[i - 1] - zs[i - 2];
                double norm = Math.hypot(pdx, pdz) * Math.hypot(dx, dz);
                if (norm > 1e-9D) {
                    double cross = Math.abs(pdx * dz - pdz * dx) / norm;
                    straight = cross <= COLLINEAR_TOLERANCE && (pdx * dx + pdz * dz) > 0.0D;
                }
            }
            if (climb && straight) {
                run++;
                straightClimbMax = Math.max(straightClimbMax, run);
            } else {
                run = 0;
            }
        }

        return new Report(hairpins, percentile(radii, 0.50D), percentile(legs, 0.50D),
                percentile(grades, 0.50D), percentile(grades, 0.95D), straightClimbMax);
    }

    /** {@return 三点在 XZ 平面上的外接圆半径；三点共线时返回 {@code NaN}} */
    static double circumradius(double[] xs, double[] zs, int a, int b, int c) {
        if (a < 0 || b < 0 || c < 0
                || a >= xs.length || b >= xs.length || c >= xs.length
                || a >= zs.length || b >= zs.length || c >= zs.length) return Double.NaN;
        double sideA = Math.hypot(xs[c] - xs[b], zs[c] - zs[b]);
        double sideB = Math.hypot(xs[c] - xs[a], zs[c] - zs[a]);
        double sideC = Math.hypot(xs[b] - xs[a], zs[b] - zs[a]);
        double twiceArea = Math.abs((xs[b] - xs[a]) * (zs[c] - zs[a]) - (xs[c] - xs[a]) * (zs[b] - zs[a]));
        if (twiceArea < 1e-9D) return Double.NaN;
        return sideA * sideB * sideC / (2.0D * twiceArea);
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values == null || values.isEmpty()) return 0.0D;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }
}
