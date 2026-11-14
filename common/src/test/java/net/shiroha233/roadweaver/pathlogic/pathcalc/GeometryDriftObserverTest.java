/* 文件职责：钉住几何漂移度量的数值语义 —— 它是批 5 决策所用的「尺子」。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 几何漂移度量测试（2026-11-15）。
 *
 * <h2>为什么这把尺子必须先测</h2>
 * <p>批 5 要决定「弹性带与几何后处理谁负责平滑」，依据就是这套指标。
 * 尺子本身错了，后面的决策全错 —— 历史上吃过这个亏：
 * 用「转角 &gt; 90°」数急弯时，实际转向角恰好是 45°/90°，判据永远不成立，
 * 却没人发现，于是「急弯数为 0」被当成「路很顺」的证据。</p>
 *
 * <h2>这组测试守什么</h2>
 * <ol>
 *   <li><b>直线必须零摆动</b>：偏直线路上 {@code flipsPer100} 与幅度都应为 0；</li>
 *   <li><b>摆动必须数得出来</b>：构造已知翻转次数的正弦路，验证计数正确；</li>
 *   <li><b>幅度必须跟得上</b>：幅值加倍时 {@code ampP95} 也应该大致加倍；</li>
 *   <li><b>退化输入不崩</b>：null / 空 / 少于 3 点 / 首尾重合都要安全返回。</li>
 * </ol>
 */
class GeometryDriftObserverTest {

    /** 沿 +X 方向的直线路。 */
    private static List<BlockPos> straight(int n, int step) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(i * step, 64, 0));
        return out;
    }

    /** 沿 +X 走、在 Z 方向按正弦摆动的路；{@code cycles} 决定翻转次数。 */
    private static List<BlockPos> sine(int n, int step, double amplitude, double cycles) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            double z = amplitude * Math.sin(2 * Math.PI * cycles * t);
            out.add(new BlockPos(i * step, 64, (int) Math.round(z)));
        }
        return out;
    }

    @Test
    void 直线路的摆动指标必须为零() {
        GeometryDriftObserver.Metrics m = GeometryDriftObserver.measure(straight(40, 4));

        assertEquals(40, m.stations());
        assertEquals(156.0, m.length(), 0.5, "39 段 × 4 格 = 156");
        assertEquals(0.0, m.flipsPer100(), 1e-9,
                "纯直线不该有任何横向符号翻转 —— 若这里非零，说明基准线或符号判据写错了");
        assertEquals(0.0, m.ampP95(), 1e-9, "纯直线横向偏移恒为 0");
        assertTrue(m.maxTurnDeg() < 1e-6, "纯直线没有转角");
    }

    @Test
    void 正弦摆动的翻转次数必须数得出来() {
        // 2 个完整周期 ⇒ 横向偏移符号应翻转 4 次左右
        GeometryDriftObserver.Metrics m = GeometryDriftObserver.measure(sine(81, 4, 3.0, 2.0));

        assertEquals(81, m.stations());
        // 允许 ±1 的边界误差（首尾采样点可能落在零点附近被噪声带滤掉）
        assertTrue(m.flipsPer100() >= 3.0 && m.flipsPer100() <= 7.0,
                "2 周期 / 81 站应为 4 次翻转 ≈ 4.94 次每百站，实测 "
                        + m.flipsPer100() + " —— 若明显偏离说明翻转计数逻辑有问题");
        assertTrue(m.ampP95() >= 2.5,
                "幅值 3 格的正弦，ampP95 应接近 3（实测 " + m.ampP95() + "）");
    }

    @Test
    void 翻转次数随周期数单调增加() {
        double one = GeometryDriftObserver.measure(sine(81, 4, 2.0, 1.0)).flipsPer100();
        double two = GeometryDriftObserver.measure(sine(81, 4, 2.0, 2.0)).flipsPer100();
        double four = GeometryDriftObserver.measure(sine(81, 4, 2.0, 4.0)).flipsPer100();

        assertTrue(two > one, "两个周期的翻转次数必须多于一个周期（" + one + " vs " + two + "）");
        assertTrue(four > two, "四个周期的翻转次数必须多于两个周期（" + two + " vs " + four + "）");
    }

    @Test
    void 幅度加倍时分位数也大致加倍() {
        double small = GeometryDriftObserver.measure(sine(81, 4, 2.0, 2.0)).ampP95();
        double large = GeometryDriftObserver.measure(sine(81, 4, 4.0, 2.0)).ampP95();

        double ratio = large / small;
        assertTrue(ratio > 1.6 && ratio < 2.4,
                "幅值从 2 加到 4，ampP95 应约翻倍（实测比值 " + ratio
                        + "，small=" + small + " large=" + large + "）");
    }

    @Test
    void 少于三个点返回全零指标() {
        assertEquals(0, GeometryDriftObserver.measure(null).stations());
        assertEquals(0, GeometryDriftObserver.measure(List.of()).stations());
        assertEquals(1, GeometryDriftObserver.measure(List.of(new BlockPos(0, 64, 0))).stations());
        assertEquals(2, GeometryDriftObserver.measure(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0))).stations());

        GeometryDriftObserver.Metrics two = GeometryDriftObserver.measure(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)));
        assertEquals(0.0, two.flipsPer100(), 1e-9, "两点无法定义摆动");
    }

    @Test
    void 首尾重合时只报长度不报摆动() {
        // 绕一圈回到原点：弦长为 0，无法定义「横向」方向
        List<BlockPos> loop = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(8, 64, 8),
                new BlockPos(0, 64, 8),
                new BlockPos(0, 64, 0));

        GeometryDriftObserver.Metrics m = GeometryDriftObserver.measure(loop);
        assertEquals(5, m.stations());
        assertEquals(32.0, m.length(), 0.5, "周长 8+8+8+8 = 32");
        assertEquals(0.0, m.flipsPer100(), 1e-9,
                "弦长为零时不应给出摆动数字（那会是拿 0/0 硬算出来的假值）");
    }

    @Test
    void 默认关闭且开启前不做任何计算() {
        // 不设系统属性时应为关闭
        assertTrue(!GeometryDriftObserver.enabled(),
                "默认应为关闭 —— 观测器不允许默认真在跑");
    }

    @Test
    void centersOf对null与空安全() {
        assertTrue(GeometryDriftObserver.centersOf(null).isEmpty());
        assertTrue(GeometryDriftObserver.centersOf(List.of()).isEmpty());
    }
}