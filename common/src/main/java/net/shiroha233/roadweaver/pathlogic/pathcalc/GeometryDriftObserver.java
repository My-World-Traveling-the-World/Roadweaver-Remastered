/* 文件职责：几何漂移观测 —— 量化「弹性带平滑」与「后处理平滑」各改动了多少路形。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 几何漂移观测器（2026-11-15 新增，为批 5「谁负责平滑」提供数据）。
 *
 * <h2>它要回答的问题</h2>
 * <p>单路选线现在有<b>两层平滑</b>叠加：</p>
 * <ol>
 *   <li><b>弹性带</b>：{@code BandPathfinder} → {@code BandRouteSolver.solve} →
 *       {@code ElasticBandSolver.smooth(raw, !isPassable)}；</li>
 *   <li><b>几何后处理</b>：{@code PathMetricsOps.buildSegments} 里紧接着调
 *       {@code PathPostProcessor.process(...)} 做简化 / 样条 / 松弛。</li>
 * </ol>
 * <p>两层都在改路形，但没人量过「谁改了多少」。批 5 要决定谁负责平滑、
 * 哪些旧步骤该退场，就必须先有这个数字 —— 否则只能拍脑袋。</p>
 *
 * <h2>为什么用「横向偏移符号翻转」而不是角度阈值</h2>
 * <p>历史教训（旧仓库实证）：用「转角 &gt; 90°」数急弯是<b>结构性抓不到</b>的 ——
 * 实际转向角恰好是 45°/90°，严格大于 90 的判据永远不成立，调阈值也没用。
 * 真正能刻画「锯齿/摆动」的是<b>横向偏移的符号反复翻转</b>：
 * 沿着路径走，点相对基准线的有符号垂直距离 ℓ 在正负之间来回换 → 就是摆动。</p>
 *
 * <p>所以本类输出四个量：</p>
 * <ul>
 *   <li>{@code flipsPer100} —— 每 100 站的 ℓ 符号翻转次数（摆动强度）；</li>
 *   <li>{@code ampP50} / {@code ampP95} —— |ℓ| 的中位/95 分位（摆动幅度）；</li>
 *   <li>{@code length} / {@code stations} —— 弧长与站数（平滑常常顺手缩短路径）；</li>
 *   <li>{@code maxTurnDeg} —— 最大转角，仅作参考（不参与判定）。</li>
 * </ul>
 *
 * <h2>开关与开销</h2>
 * <p>{@code -Droadweaver.geometryAb=on} 才启用，默认关闭 —— 关闭时每次调用
 * 只是一次布尔判断就返回。开启后每条路多算两遍 O(站数) 的纯算术，
 * 相对规划本身可忽略。</p>
 */
public final class GeometryDriftObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String PROPERTY = "roadweaver.geometryAb";

    private static final AtomicLong CALLS = new AtomicLong();

    private GeometryDriftObserver() {
    }

    /** 是否启用（默认 off）。 */
    public static boolean enabled() {
        return "on".equalsIgnoreCase(System.getProperty(PROPERTY, "off").trim());
    }

    /**
     * 一次几何快照。
     *
     * @param stations   站数
     * @param length     沿路径累计的水平弧长
     * @param flipsPer100 每 100 站的横向符号翻转次数
     * @param ampP50     |横向偏移| 的中位数
     * @param ampP95     |横向偏移| 的 95 分位
     * @param maxTurnDeg 最大相邻段转角（度），仅参考
     */
    public record Metrics(int stations,
                          double length,
                          double flipsPer100,
                          double ampP50,
                          double ampP95,
                          double maxTurnDeg) {
    }

    /**
     * 计算一组路径点的几何指标。**纯函数、无副作用**，可单测。
     *
     * <p>基准线取「首点→末点」的弦。横向偏移 ℓ 用叉积算有符号距离 ——
     * 符号代表在弦的哪一侧，翻转次数就是「左右换边」的次数。</p>
     *
     * <p>退化输入（少于 3 点、首尾重合）返回全零指标，不抛异常：
     * 观测器绝不允许影响生产。</p>
     */
    public static Metrics measure(List<BlockPos> path) {
        if (path == null || path.size() < 3) {
            return new Metrics(path == null ? 0 : path.size(), 0, 0, 0, 0, 0);
        }
        int n = path.size();
        BlockPos first = path.get(0);
        BlockPos last = path.get(n - 1);
        if (first == null || last == null) {
            return new Metrics(n, 0, 0, 0, 0, 0);
        }

        // ── 弧长与最大转角 ──
        double length = 0;
        double maxTurn = 0;
        double prevDx = Double.NaN;
        double prevDz = Double.NaN;
        for (int i = 1; i < n; i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            if (a == null || b == null) continue;
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double seg = Math.hypot(dx, dz);
            length += seg;
            if (seg > 0) {
                if (!Double.isNaN(prevDx)) {
                    double cross = prevDx * dz - prevDz * dx;
                    double dot = prevDx * dx + prevDz * dz;
                    double turn = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
                    if (turn > maxTurn) maxTurn = turn;
                }
                prevDx = dx;
                prevDz = dz;
            }
        }

        // ── 相对首尾弦的有符号横向偏移 ──
        double ax = first.getX();
        double az = first.getZ();
        double bx = last.getX() - ax;
        double bz = last.getZ() - az;
        double chordLength = Math.hypot(bx, bz);
        if (chordLength <= 1e-9) {
            // 首尾重合（闭环 / 原地打转）：无法定义基准线，只报长度
            return new Metrics(n, length, 0, 0, 0, maxTurn);
        }
        double[] lateral = new double[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = path.get(i);
            if (p == null) {
                lateral[i] = 0;
                continue;
            }
            double px = p.getX() - ax;
            double pz = p.getZ() - az;
            // 二维叉积 / 弦长 = 有符号垂直距离
            lateral[i] = (bx * pz - bz * px) / chordLength;
        }

        // ── 符号翻转次数：忽略 0 与噪声带内的抖动 ──
        // 噪声带取 0.5 格：低于半格的偏移属于格点量化误差，不算「换边」。
        int flips = 0;
        int lastSign = 0;
        for (double l : lateral) {
            if (Math.abs(l) < 0.5) continue;
            int sign = l > 0 ? 1 : -1;
            if (lastSign != 0 && sign != lastSign) flips++;
            lastSign = sign;
        }
        double flipsPer100 = n > 0 ? flips * 100.0 / n : 0;

        // ── 幅度分位数 ──
        double[] amps = new double[n];
        for (int i = 0; i < n; i++) amps[i] = Math.abs(lateral[i]);
        Arrays.sort(amps);
        double p50 = amps[(int) Math.min(amps.length - 1, Math.round(0.50 * (amps.length - 1)))];
        double p95 = amps[(int) Math.min(amps.length - 1, Math.round(0.95 * (amps.length - 1)))];

        return new Metrics(n, length, flipsPer100, p50, p95, maxTurn);
    }

    /** 把 {@code List<RoadSegmentPlacement>} 转成观测用的点列（取每站中心）。 */
    public static List<BlockPos> centersOf(List<net.shiroha233.roadweaver.core.model.RoadSegmentPlacement> segments) {
        if (segments == null) return List.of();
        List<BlockPos> out = new ArrayList<>(segments.size());
        for (var segment : segments) {
            if (segment != null && segment.middlePos() != null) out.add(segment.middlePos());
        }
        return out;
    }

    /**
     * 观测一次并打日志。
     *
     * @param tag   连接标识（与其它 AB 日志同口径，便于对账）
     * @param stage 阶段名（建议 "band"=弹性带输出 / "post"=后处理输出）
     * @param path  该阶段的路径点
     */
    public static void observe(String tag, String stage, List<BlockPos> path) {
        if (!enabled()) return;
        try {
            Metrics m = measure(path);
            long call = CALLS.incrementAndGet();
            LOGGER.info("[GEOM-AB] tag={} stage={} n={} len={} flips/100={} ampP50={} ampP95={} maxTurn={} (call={})",
                    tag, stage, m.stations(),
                    String.format("%.1f", m.length()),
                    String.format("%.2f", m.flipsPer100()),
                    String.format("%.2f", m.ampP50()),
                    String.format("%.2f", m.ampP95()),
                    String.format("%.1f", m.maxTurnDeg()),
                    call);
        } catch (Throwable t) {
            // 观测器不允许影响生产：任何异常都只记一句警告。
            LOGGER.warn("[GEOM-AB] tag={} stage={} measure failed: {}", tag, stage, t.toString());
        }
    }

    /** 累计调用次数（诊断用）。 */
    public static long calls() {
        return CALLS.get();
    }

    /** 把两个阶段的指标并排打一行，直接回答「谁改了多少」。 */
    public static void report(String tag, Metrics band, Metrics post) {
        if (!enabled() || band == null || post == null) return;
        try {
            LOGGER.info("[GEOM-AB-SUMMARY] tag={} "
                            + "len {}->{} ({}) "
                            + "flips/100 {}->{} ({}) "
                            + "ampP95 {}->{} ({})",
                    tag,
                    String.format("%.1f", band.length()), String.format("%.1f", post.length()),
                    delta(band.length(), post.length()),
                    String.format("%.2f", band.flipsPer100()), String.format("%.2f", post.flipsPer100()),
                    delta(band.flipsPer100(), post.flipsPer100()),
                    String.format("%.2f", band.ampP95()), String.format("%.2f", post.ampP95()),
                    delta(band.ampP95(), post.ampP95()));
        } catch (Throwable ignored) {
            // 同上：摘要失败不影响生产。
        }
    }

    private static String delta(double before, double after) {
        double d = after - before;
        return String.format("%+.2f", d);
    }
}