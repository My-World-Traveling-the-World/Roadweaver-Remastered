/* 文件职责：纯几何「锯齿/抖动」判据——给后处理平滑类改动提供唯一可断言的验收尺子。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 道路横向「锯齿」判据（0.9.019，O-01）。
 *
 * <h2>为什么必须新写一把尺子</h2>
 * <p>真机缺陷 D1（沿河路段明显波浪状）现有所有度量都抓不到，而且不是「阈值调得不够低」，
 * 是结构性抓不到：</p>
 * <ul>
 *   <li>{@code sharpTurnCount} 的阈值是 {@code SHARP_TURN_DEG = 90.0} 且判据是
 *       <b>严格 {@code >}</b>，而 D1 的两种候选几何的转向角恰好是 45° 与 90°
 *       —— 90 不满足 {@code > 90}，45 更不满足；</li>
 *   <li>{@code hairpinCount}（{@code >150°}）同理；</li>
 *   <li>生产侧 {@code breaks / bridgeJumps} 要求 {@code deltaY > 1} <b>且两端同类</b>，
 *       岸站↔桥面那类边两边都不计；</li>
 *   <li>{@code RouteQualityHarness} 的目标函数是 {@code |sharpTurns - 8|}，
 *       用它验收会<b>反向奖励拉直</b>；</li>
 *   <li>而且 harness 只跑 {@code findRawPath}，全仓对 {@code PathPostProcessor}
 *       只有 {@code rasterizeStations / densifyStationGaps} 两处测试 ——
 *       {@code simplifyPath / relaxPath / generateSplinePoints} <b>零测试</b>。</li>
 * </ul>
 *
 * <h2>判据为什么不能设角度阈值</h2>
 * <p>D1 的「每步约 22°」是<b>相对弦的平均航向偏差</b>（弦 26.57°、腿 0°/45°），
 * 不是转向角。任何角度阈值都会同时命中「真缓弯」与「抖动」。所以这里改用
 * <b>横向速度符号翻转</b>：把点列投影到弦的垂向上，看这个横向坐标的速度反复变号
 * 多少次 —— 变号才是振荡，单调变化是转弯。</p>
 *
 * <h2>三个条件同时成立才算锯齿</h2>
 * <ol>
 *   <li><b>翻转 ≥ {@value #MIN_FLIPS} 次</b>：一次方向变化是转弯，三次以上是振荡；</li>
 *   <li><b>横向峰值 ≤ {@value #AMPLITUDE_FACTOR}×步距</b>：<b>0.9.022 起横向坐标是相对
 *       「局部弦」（前后各 {@value #LOCAL_CHORD_HALF_WINDOW} 站）而不是首末大弦</b>。
 *       用大弦时任何一条真正弯曲的山路偏移天然几十上百格，这一条永远不成立、判据彻底失效；
 *       改用局部弦后，平滑弯道的局部偏离接近 0，只有「原地来回蹭」才会积累出偏离。</li>
 *   <li><b>腿长中位数 ≤ {@value #LEG_FACTOR}×步距</b>：这一条是区分「抖动」与
 *       「真蜿蜒」的关键。<b>没有它，幅 6 格、周期 64 格的正弦（真实山间缓弯）
 *       会被误判成锯齿</b>，因为它的横向峰值只有 6 格，落在第 2 条之内；
 *       它的腿长是 32 格 = 4×步距，正好被这一条挡住。</li>
 * </ol>
 *
 * <p>纯几何、不读世界、不进热路径，可对任意点列跑（控制点、站点、取样点都行）。</p>
 */
public final class SawtoothMetric {
    /** 至少翻转这么多次才算振荡。 */
    public static final int MIN_FLIPS = 3;
    /** 横向峰值上限 = 步距 × 此系数。 */
    public static final double AMPLITUDE_FACTOR = 1.2D;
    /** 腿长中位数上限 = 步距 × 此系数。 */
    public static final double LEG_FACTOR = 2.0D;
    /** 弧长下限 = 步距 × 此系数（太短的点列没有统计意义）。 */
    public static final double MIN_ARC_FACTOR = 3.0D;
    /** 横向速度的死区（格）：小于它的变化当作采样噪声，不计入翻转。 */
    /**
     * 局部弦窗口的半宽（站）。横向坐标改为相对**局部弦**而不是首末大弦 —— 见 {@link #measure}。
     */
    private static final int LOCAL_CHORD_HALF_WINDOW = 16;
    /**
     * 相邻两次「被接受的翻转」之间的最小弧长（相对步距）。
     *
     * <p>0.9.022 修正：真机实测 `final` 侧的站点是 **1 格**间距，任何 ±0.5 的取整抖动都会
     * 造成一次符号翻转 ⇒ `flipsPer100` 与 `legP50` 全被 1 格尺度噪声污染（实测
     * `legP50=1.0`、`flipsPer100` 被抬到 40）。取 0.25×步距（d=8 时 2 格）作为门槛，
     * 1 格间距的噪声翻转被直接丢掉，而真实 8 格腿长的锯齿仍能通过。</p>
     */
    private static final double MIN_LEG_ARC_FACTOR = 0.25D;
    /** 一次被接受的反转，其横向摆幅至少要这么大（格）；低于它的当作噪声。 */
    private static final double MIN_PROMINENCE = 0.75D;

    private SawtoothMetric() {
    }

    /**
     * 判定结果。
     *
     * @param zigzag            三条件是否同时成立
     * @param flips             横向速度符号翻转次数
     * @param flipsPer100Stations 每 100 站的翻转次数（跨点列长度可比）
     * @param amplitudeP50      横向偏移绝对值的中位数
     * @param amplitudeP95      横向偏移绝对值的 95 分位
     * @param maxAmplitude      横向偏移绝对值的最大值（不含首末点，它们恒为 0）
     * @param legLengthP50      相邻极值之间的弧长中位数 —— 区分抖动与缓弯的关键量
     * @param arcLength         点列总弧长（XZ）
     * @param chordLength       首末点直线距离（XZ）
     * @param reason            不成立时的原因短标签，成立时为 {@code "zigzag"}
     */
    public record Result(boolean zigzag,
                         int flips,
                         double flipsPer100Stations,
                         double amplitudeP50,
                         double amplitudeP95,
                         double maxAmplitude,
                         double legLengthP50,
                         double arcLength,
                         double chordLength,
                         String reason) {

        static Result none(String reason, int flips, double arcLength, double chordLength) {
            return new Result(false, flips, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, arcLength, chordLength, reason);
        }
    }

    /** 对站点列测一次；{@code gridStep} 传 A* 的有效步距（{@code PathfindingCostConfig.effectiveAStarStep()}）。 */
    public static Result measureStations(List<RoadSegmentPlacement> stations, double gridStep) {
        if (stations == null) return Result.none("null", 0, 0.0D, 0.0D);
        List<BlockPos> points = new ArrayList<>(stations.size());
        for (RoadSegmentPlacement station : stations) {
            if (station == null || station.middlePos() == null) continue;
            points.add(station.middlePos());
        }
        return measure(points, gridStep);
    }

    /**
     * 对任意 XZ 点列测一次。
     *
     * <p>点列首末点定义弦；横向坐标相对弦计算，所以「整条路是一个大弯」不会被误判
     * （横向偏移植会一路长上去，直接撞破第 2 条）。</p>
     */
    public static Result measure(List<BlockPos> points, double gridStep) {
        if (points == null) {
            return Result.none("null", 0, 0.0D, 0.0D);
        }
        if (points.size() < 4) {
            return Result.none("too-short", 0, 0.0D, 0.0D);
        }
        int n = points.size();
        BlockPos first = points.get(0);
        BlockPos last = points.get(n - 1);
        double dx = last.getX() - first.getX();
        double dz = last.getZ() - first.getZ();
        double chord = Math.hypot(dx, dz);
        double arc = 0.0D;
        for (int i = 1; i < n; i++) {
            BlockPos a = points.get(i - 1);
            BlockPos b = points.get(i);
            arc += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        double step = gridStep > 0.0D ? gridStep : 8.0D;
        if (arc < MIN_ARC_FACTOR * step) {
            return Result.none("arc-too-short", 0, arc, chord);
        }
        // 弦退化（首末几乎重合，例如环线或短弧）时横向坐标没有定义。
        if (chord < 1.0D) {
            return Result.none("chord-degenerate", 0, arc, chord);
        }
        // 0.9.022：横向坐标改用**局部弦**基准，翻转改用**极值 + 双门槛**。
        //
        // 老实现有两处结构性缺陷，被真机数据当场证伪（43 条路 100% 报
        // amplitude-too-large、zigzag 恒 false、final 侧 legP50 恒 1.0）：
        //  ① 用首末大弦做基准 —— 任何一条真正弯曲的山路横向偏移天然是几十上百格，
        //     「幅 ≤1.2×步距」永远不成立 ⇒ zigzag 布尔值彻底失效；
        //  ② 逐点速度变号 —— final 侧站点间距只有 1 格，±0.5 的取整抖动就是一次变号
        //     ⇒ flipsPer100 / legP50 全被 1 格噪声污染。
        // 现在：局部弦把平滑弯道压到接近 0；极值法 + 显著性 + 最小腿长把 1 格噪声滤掉。
        int halfWindow = Math.min(LOCAL_CHORD_HALF_WINDOW, Math.max(2, (n - 1) / 2));
        double[] lateral = new double[n];
        boolean[] lateralValid = new boolean[n];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - halfWindow);
            int to = Math.min(n - 1, i + halfWindow);
            if (to - from < 2) continue;
            BlockPos a = points.get(from);
            BlockPos b = points.get(to);
            double lx = b.getX() - a.getX();
            double lz = b.getZ() - a.getZ();
            double len = Math.hypot(lx, lz);
            if (len < 1.0D) continue;
            double lnx = -lz / len;
            double lnz = lx / len;
            BlockPos p = points.get(i);
            lateral[i] = (p.getX() - a.getX()) * lnx + (p.getZ() - a.getZ()) * lnz;
            lateralValid[i] = true;
        }

        // 弧长前缀和：让「两点之间走了多远」变成 O(1)。
        double[] arcPrefix = new double[n];
        for (int i = 1; i < n; i++) {
            BlockPos a = points.get(i - 1);
            BlockPos b = points.get(i);
            arcPrefix[i] = arcPrefix[i - 1] + Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }

        double minLegArc = Math.max(2.0D, step * MIN_LEG_ARC_FACTOR);
        List<Integer> extremeStations = new ArrayList<>();
        int extremeIndex = -1;
        double extremeValue = 0.0D;
        int direction = 0;
        int lastAccepted = -1;
        for (int i = 0; i < n; i++) {
            if (!lateralValid[i]) continue;
            double value = lateral[i];
            if (direction == 0) {
                direction = 1;
                extremeIndex = i;
                extremeValue = value;
                continue;
            }
            boolean accept;
            if (direction > 0) {
                if (value > extremeValue) {
                    extremeValue = value;
                    extremeIndex = i;
                    continue;
                }
                accept = extremeValue - value >= MIN_PROMINENCE;
            } else {
                if (value < extremeValue) {
                    extremeValue = value;
                    extremeIndex = i;
                    continue;
                }
                accept = value - extremeValue >= MIN_PROMINENCE;
            }
            if (!accept) continue;
            if (lastAccepted < 0 || arcPrefix[extremeIndex] - arcPrefix[lastAccepted] >= minLegArc) {
                extremeStations.add(extremeIndex);
                lastAccepted = extremeIndex;
            }
            direction = -direction;
            extremeValue = value;
            extremeIndex = i;
        }
        int flips = extremeStations.size();

        double[] amplitudes = new double[n];
        int amplitudeCount = 0;
        for (int i = 0; i < n; i++) {
            if (!lateralValid[i]) continue;
            amplitudes[amplitudeCount++] = Math.abs(lateral[i]);
        }
        if (amplitudeCount != amplitudes.length) {
            amplitudes = Arrays.copyOf(amplitudes, amplitudeCount);
        }
        double amplitudeP50 = percentile(amplitudes, 0.50D);
        double amplitudeP95 = percentile(amplitudes, 0.95D);
        double maxAmplitude = percentile(amplitudes, 1.0D);

        double[] legs = new double[Math.max(0, extremeStations.size() - 1)];
        for (int i = 0; i + 1 < extremeStations.size(); i++) {
            legs[i] = arcPrefix[extremeStations.get(i + 1)] - arcPrefix[extremeStations.get(i)];
        }
        double legP50 = percentile(legs, 0.50D);
        double flipsPer100 = n <= 1 ? 0.0D : flips * 100.0D / (n - 1);

        String reason;
        boolean zigzag;
        if (flips < MIN_FLIPS) {
            zigzag = false;
            reason = "few-flips";
        } else if (maxAmplitude > AMPLITUDE_FACTOR * step) {
            zigzag = false;
            reason = "amplitude-too-large";
        } else if (legP50 > LEG_FACTOR * step) {
            // 关键的一条：幅 6 / 周期 64 的真缓弯会落这里（腿长 32 = 4×8）。
            zigzag = false;
            reason = "legs-too-long";
        } else {
            zigzag = true;
            reason = "zigzag";
        }
        return new Result(zigzag, flips, flipsPer100, amplitudeP50, amplitudeP95, maxAmplitude,
                legP50, arc, chord, reason);
    }

    private static double percentile(double[] values, double fraction) {
        if (values == null || values.length == 0) return 0.0D;
        double[] copy = values.clone();
        Arrays.sort(copy);
        double position = fraction * (copy.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return copy[lower];
        double weight = position - lower;
        return copy[lower] * (1.0D - weight) + copy[upper] * weight;
    }
}
