/* 文件职责：批 5「几何后处理」的量尺 —— 横向偏离与密度放回的契约 + 锯齿保真度实测。 */
package net.shiroha233.roadweaver.postprocess;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批 5 的量尺：{@code perpendicularDistance} 与 {@code reinstateUntilDense}。
 *
 * <h2>为什么现在必须有这个</h2>
 * <p>2026-11-15 方法级实测：{@code PathPostProcessor} 30 个方法里有 13 个被测试触及，
 * 但<b>生产真正调用的 {@code process(...)} 四个重载全部零测试</b>
 * （它们吃 {@code ServerLevel}，无法离线跑），
 * 而支撑「简化是否把锯齿抹掉」的两个纯函数 ——
 * {@link PathPostProcessor#perpendicularDistance} 与
 * {@link PathPostProcessor#reinstateUntilDense} —— <b>也零覆盖</b>。</p>
 *
 * <p>记忆里有明确结论：D2（之字）的一个真凶是
 * 「DP 容差 4 简化后只剩 2 点 &lt; minPoints ⇒ 重试 tol=2 ⇒ 零简化」，
 * 后来改成「按最大偏离逐点放回」。<b>但那个改动没有直接测过。</b>
 * 本类把它的两个纯函数内核钉住，并给出一组可复算的锯齿保真度数字 ——
 * 这样「后处理到底把路形改了多少」至少有一半（简化/放回这一半）可以离线回答。</p>
 *
 * <h2>它给不了的</h2>
 * <p>{@code process()} 里 {@code relaxPath} / {@code generateSplinePoints} /
 * {@code rasterizeSegments} 的组合效果、以及 band 之后那一次重复平滑，
 * 都需要 {@code ServerLevel} 或真机 {@code -Droadweaver.geometryAb=on} 的
 * {@code [GEOM-AB]} 两阶段数字。本类只是「尺子的一半」。</p>
 */
class PostprocessMeasuringStickTest {

    // ══════════ 一、perpendicularDistance：DP 的几何核心 ══════════

    @Test
    void 点落在直线段上时横向偏离为零() {
        // 线段 (0,0)-(100,0)，点 (50,0) 在线上
        double d = PathPostProcessor.perpendicularDistance(
                new BlockPos(50, 64, 0), new BlockPos(0, 64, 0), new BlockPos(100, 64, 0));
        assertEquals(0.0D, d, 1e-9D, "线上点的横向偏离必须严格为 0");
    }

    @Test
    void 横向偏离等于垂直距离而不是到端点的距离() {
        // 线段 (0,0)-(100,0)，点 (50,7) ⇒ 垂直距离 7（到两端点都是 ~50）
        double d = PathPostProcessor.perpendicularDistance(
                new BlockPos(50, 64, 7), new BlockPos(0, 64, 0), new BlockPos(100, 64, 0));
        assertEquals(7.0D, d, 1e-9D,
                "必须是到直线的垂距 7，不是到端点的 50 —— "
                        + "用成端点距离会让 DP 把长直线上的点全判成「偏离大」而拒绝简化");
    }

    @Test
    void 落在端点外侧时退化为到端点的距离() {
        // 线段 (0,0)-(100,0)，点 (200,0) 在线段外 ⇒ t 被 clamp 到 1，距离 = 100
        double d = PathPostProcessor.perpendicularDistance(
                new BlockPos(200, 64, 0), new BlockPos(0, 64, 0), new BlockPos(100, 64, 0));
        assertEquals(100.0D, d, 1e-9D,
                "投影参数 t 被 clamp 到 [0,1]，线段外点退化为到最近端点的距离");
    }

    @Test
    void 退化成点的线段按欧氏距离处理() {
        // a == b ⇒ lenSq ≈ 0，走 hypot 分支
        double d = PathPostProcessor.perpendicularDistance(
                new BlockPos(3, 64, 4), new BlockPos(0, 64, 0), new BlockPos(0, 64, 0));
        assertEquals(5.0D, d, 1e-9D, "零长度线段必须走欧氏距离分支（3-4-5），否则会除零");
    }

    @Test
    void 只看_XZ_不看_Y() {
        // 两点仅 Y 不同 ⇒ XZ 上重合 ⇒ 偏离 0
        double d = PathPostProcessor.perpendicularDistance(
                new BlockPos(50, 999, 0), new BlockPos(0, -999, 0), new BlockPos(100, 64, 0));
        assertEquals(0.0D, d, 1e-9D,
                "DP 是 XZ 平面判据，Y 不参与 —— 若这里不为 0，说明有人把 Y 算进去了，"
                        + "会把高低起伏误当成横向偏离");
    }

    // ══════════ 二、reinstateUntilDense：D2 的放回机制 ══════════

    @Test
    void 已经够密时原样返回同一个引用() {
        List<BlockPos> nodes = straightLine(10);
        List<BlockPos> kept = new ArrayList<>(List.of(nodes.get(0), nodes.get(9)));
        List<BlockPos> out = PathPostProcessor.reinstateUntilDense(nodes, kept, 2);
        assertSame(kept, out,
                "kept.size() >= minPoints 必须原样返回**同一个对象**（不复制、不重排），"
                        + "否则调用方拿到的点列会与 DP 结果失去同一性");
    }

    @Test
    void null_输入一律返回原_kept() {
        assertTrue(PathPostProcessor.reinstateUntilDense(null, List.of(), 5).isEmpty(),
                "nodes 为 null 时必须原样返回 kept（空表）");
        List<BlockPos> nodes = straightLine(10);
        assertTrue(PathPostProcessor.reinstateUntilDense(nodes, null, 5) == null,
                "kept 为 null 时必须返回 null，不许自己造一个空表");
    }

    @Test
    void kept_里有点不在_nodes_里时放弃猜测() {
        // kept 的第一个点不在 nodes 里 ⇒ 无法建立下标映射 ⇒ 必须原样返回
        List<BlockPos> nodes = straightLine(10);
        List<BlockPos> badKept = new ArrayList<>(List.of(
                new BlockPos(999, 64, 999), nodes.get(9)));
        List<BlockPos> out = PathPostProcessor.reinstateUntilDense(nodes, badKept, 6);
        assertSame(badKept, out,
                "对不上就放弃（绝不猜）—— 若这里返回了 6 个点，说明它开始自行补点了");
    }

    @Test
    void 放回的是偏离最大的点而不是随便某个点() {
        // 构造一条「直线上故意拱起一格」的点列：
        // x=0..10，其中 x=5 的 z 偏了 3 格，其余 z 都是 0。
        List<BlockPos> nodes = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            nodes.add(new BlockPos(x, 64, x == 5 ? 3 : 0));
        }
        List<BlockPos> kept = new ArrayList<>(List.of(nodes.get(0), nodes.get(10)));
        // minPoints=3 ⇒ 只需要再放回 1 个点，那必须是拱起的那一个（偏离 3）
        List<BlockPos> out = PathPostProcessor.reinstateUntilDense(nodes, kept, 3);
        assertEquals(3, out.size(), "放回到 minPoints 就应停止");
        assertTrue(out.contains(new BlockPos(5, 64, 3)),
                "必须优先放回横向偏离最大的点（x=5 的拱起点）；"
                        + "实际放回的是 " + out + " —— 若不是它，放回顺序判据被改了");
    }

    @Test
    void 放回结果始终是原点的有序子序列() {
        List<BlockPos> nodes = zigzag(60, 6.0D, 8);
        List<BlockPos> kept = new ArrayList<>(List.of(nodes.get(0), nodes.get(nodes.size() - 1)));
        List<BlockPos> out = PathPostProcessor.reinstateUntilDense(nodes, kept, 12);
        int cursor = 0;
        for (BlockPos p : out) {
            int found = -1;
            for (int i = cursor; i < nodes.size(); i++) {
                if (nodes.get(i).equals(p)) { found = i; break; }
            }
            assertTrue(found >= 0,
                    "结果点 " + p + " 不在原点的有序子序列里 —— 放了新造坐标或打乱了顺序");
            cursor = found + 1;
        }
    }

    @Test
    void 中间点全部共线时提前停止而不是硬凑点数() {
        // 完全共线的 20 点，kept 只有首末 ⇒ 任何中间点偏离都是 0 ⇒ 无可放回
        List<BlockPos> nodes = straightLine(20);
        List<BlockPos> kept = new ArrayList<>(List.of(nodes.get(0), nodes.get(19)));
        List<BlockPos> out = PathPostProcessor.reinstateUntilDense(nodes, kept, 10);
        assertEquals(2, out.size(),
                "共线时没有任何点的偏离 > 1e-9 ⇒ 必须提前 break，返回 2 点。"
                        + "若硬凑到 10 点，等于给共线直线塞无用控制点");
    }

    // ══════════ 三、★ 实测：锯齿经过 simplifyPath 之后还剩多少起伏 ══════════

    /**
     * 这是一条**度量型**用例（不是断言型）：它把「一条 22.5° 锯齿路经过
     * {@code simplifyPath} 后还剩多少起伏」打印出来，作为批 5 决策的离线数字。
     *
     * <p>断言只钉住方向性三条：①点确实减少了；②密度下限被满足；
     * ③<b>起伏没有被抹平到 0</b>（若被抹平，说明简化把锯齿当噪声删光了，
     * 而锯齿本来是要靠 band 解决的、不该由后处理假装解决）。</p>
     */
    @Test
    void 锯齿经简化后的起伏保真度实测() {
        int stations = 120;
        double amplitude = 6.0D;
        int period = 8;
        List<BlockPos> nodes = zigzag(stations, amplitude, period);
        List<BlockPos> simplified = PathPostProcessor.simplifyPath(nodes, 8);

        double before = maxLateralDeviation(nodes);
        double after = maxLateralDeviation(simplified);
        double totalLength = polylineLength(nodes);
        int minPoints = Math.max(2, (int) Math.ceil(totalLength / 64.0D));

        System.out.println("[POSTPROCESS-STICK] stations=" + stations
                + " ampIn=" + amplitude
                + " totalLen=" + Math.round(totalLength)
                + " minPoints=" + minPoints
                + " kept=" + simplified.size()
                + " ampBefore=" + round2(before)
                + " ampAfter=" + round2(after)
                + " ratio=" + round2(after / before));

        assertTrue(simplified.size() < nodes.size(),
                "简化必须真的删掉点，否则这条量尺没意义（kept=" + simplified.size() + "）");
        assertTrue(simplified.size() >= minPoints,
                "密度下限 " + minPoints + " 必须被满足，实际 " + simplified.size());
        assertTrue(after > 1.0D,
                "起伏不能被抹平到 1 格以内（实际 " + round2(after) + "）—— "
                        + "若这条红了，说明简化把锯齿当噪声全删了，"
                        + "那么「谁是锯齿真凶」的结论要重算");
    }

    // ── 夹具 ────────────────────────────────────────

    /** x=0..n-1 的直线（z=0）。 */
    private static List<BlockPos> straightLine(int n) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(i * 4, 64, 0));
        return out;
    }

    /** 沿 X 前进、Z 按周期正弦起伏的锯齿路（站距 4 格）。 */
    private static List<BlockPos> zigzag(int n, double amplitude, int period) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double z = amplitude * Math.sin(2.0D * Math.PI * i / period);
            out.add(new BlockPos(i * 4, 64, (int) Math.round(z)));
        }
        return out;
    }

    /** 相对首末连线（直线）的最大横向偏离。 */
    private static double maxLateralDeviation(List<BlockPos> points) {
        if (points == null || points.size() < 3) return 0.0D;
        BlockPos a = points.get(0);
        BlockPos b = points.get(points.size() - 1);
        double max = 0.0D;
        for (int i = 1; i < points.size() - 1; i++) {
            max = Math.max(max, PathPostProcessor.perpendicularDistance(points.get(i), a, b));
        }
        return max;
    }

    private static double polylineLength(List<BlockPos> points) {
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            total += Math.hypot(
                    (double) points.get(i).getX() - points.get(i - 1).getX(),
                    (double) points.get(i).getZ() - points.get(i - 1).getZ());
        }
        return total;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}