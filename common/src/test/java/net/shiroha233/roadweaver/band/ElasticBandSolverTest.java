/* 文件职责：证明弹性带「直线不动、锯齿变平、端点锁定、曲率只降不升」。 */
package net.shiroha233.roadweaver.band;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹性带的性质测试。
 *
 * <h2>为什么第一条必须是「直线不动」</h2>
 * <p>旧实现的 DP + Catmull-Rom 在「长直段 + 转弯」处<b>先冲出去再折返</b> ——
 * 实测冲出 <b>9 格</b>（L=200）到 <b>53 格</b>（L=800），中心站序列甚至出现倒退
 * （2026-11-07 实证）。根因是 DP 造出非均匀控制点后用了均匀参数化的样条。</p>
 *
 * <p>弹性带用自然边界条件 {@code γ''=0}（镜像虚点实现），所以
 * <b>一条直线输入必须是它的精确极小</b> —— CG 的初始残差直接为 0，一次都不迭代。
 * 这条测试就是那个历史缺陷的钉子。</p>
 *
 * @see ElasticBandSolver
 */
class ElasticBandSolverTest {

    private static List<BandResult.Point> straight(int count, int step) {
        List<BandResult.Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new BandResult.Point(i * step, i * step / 2));
        }
        return points;
    }

    // ── 性质 1：直线必须严格不变（旧实现的 9~53 格外冲钉子）────

    @Test
    void 直线输入必须严格保持不变() {
        List<BandResult.Point> input = straight(60, 8);
        BandResult result = ElasticBandSolver.defaults().smooth(input);

        assertEquals(input.size(), result.size(), "点数必须守恒");
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).x(), result.points().get(i).x(),
                    "第 " + i + " 点 x 变了 —— 直线不该被任何平滑动到");
            assertEquals(input.get(i).z(), result.points().get(i).z(),
                    "第 " + i + " 点 z 变了");
        }
        assertEquals(0.0, result.curvatureBefore(), 1e-9, "直线曲率能量应为 0");
        assertEquals(0.0, result.curvatureAfter(), 1e-9, "直线曲率能量应仍为 0");
        assertEquals(0, result.iterations(), "直线是精确极小，不该需要迭代");
    }

    // ── 性质 2：锯齿必须被显著抹平 ─────────────────────────────

    @Test
    void 锯齿必须被显著抹平() {
        // 幅 4 的交替锯齿（最恶劣的形态：每站都折）。
        // **端点必须放在 0**：若端点也在 ±4，那条「拉直」的极限就是端点高度
        // 本身，振幅永远不会减小 —— 那是用例设计错误，不是求解器的问题。
        int count = 41;
        List<BandResult.Point> input = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int z = (i == 0 || i == count - 1) ? 0 : ((i % 2 == 0) ? -4 : 4);
            input.add(new BandResult.Point(i * 8, z));
        }
        double before = ElasticBandSolver.discreteCurvature(input);

        BandResult result = ElasticBandSolver.defaults().smooth(input);

        assertTrue(result.curvatureAfter() < before * 0.5,
                "曲率能量应至少降一半：前 " + before + " 后 " + result.curvatureAfter());
        assertTrue(result.curvatureReduction() > 0.5,
                "曲率下降比例应超过 50%，实际 " + (result.curvatureReduction() * 100) + "%");

        // 振幅必须明显缩小（输入 ±4）
        double maxAbs = result.points().stream()
                .mapToInt(p -> Math.abs(p.z()))
                .max().orElse(0);
        assertTrue(maxAbs < 3.0, "锯齿振幅应从 4 降到 3 以下，实际 " + maxAbs);
    }

    // ── 性质 3：端点必须锁死 ───────────────────────────────────

    @Test
    void 端点必须保持不变() {
        List<BandResult.Point> input = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            input.add(new BandResult.Point(i * 8, (i % 2 == 0) ? 0 : 6));
        }
        BandResult result = ElasticBandSolver.defaults().smooth(input);

        assertEquals(input.get(0), result.points().get(0),
                "起点必须锁死 —— 它是结构端点的坐标，动了就连不上");
        assertEquals(input.get(input.size() - 1), result.points().get(result.size() - 1),
                "终点必须锁死");
    }

    // ── 性质 4：曲率只降不升 ───────────────────────────────────

    @Test
    void 曲率不可能上升() {
        // 随机折线：30 条，任何一条都不该被平滑弄得更弯
        java.util.Random random = new java.util.Random(20261112L);
        for (int trial = 0; trial < 30; trial++) {
            int count = 6 + random.nextInt(40);
            List<BandResult.Point> input = new ArrayList<>(count);
            int x = 0;
            int z = 0;
            for (int i = 0; i < count; i++) {
                x += 6 + random.nextInt(6);
                z += random.nextInt(21) - 10;
                input.add(new BandResult.Point(x, z));
            }
            BandResult result = ElasticBandSolver.defaults().smooth(input);
            assertTrue(result.curvatureAfter() <= result.curvatureBefore() + 1e-6,
                    "第 " + trial + " 条：曲率不该上升。前 " + result.curvatureBefore()
                            + " 后 " + result.curvatureAfter());
        }
    }

    // ── 性质 5：μ 的语义 ──────────────────────────────────────

    @Test
    void 保形权重越大越贴近输入() {
        int count = 41;
        List<BandResult.Point> input = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int z = (i == 0 || i == count - 1) ? 0 : ((i % 2 == 0) ? -4 : 4);
            input.add(new BandResult.Point(i * 8, z));
        }

        BandResult loose = new ElasticBandSolver(0.01, 2000, 1e-10).smooth(input);
        BandResult stiff = new ElasticBandSolver(100.0, 2000, 1e-10).smooth(input);

        assertTrue(loose.curvatureAfter() < stiff.curvatureAfter(),
                "μ 小（0.01）应该比 μ 大（100）平滑得多："
                        + "loose=" + loose.curvatureAfter() + " stiff=" + stiff.curvatureAfter());

        // μ 很小时应几乎拉直
        double looseAmp = loose.points().stream().mapToInt(p -> Math.abs(p.z())).max().orElse(0);
        assertTrue(looseAmp <= 1, "μ=0.01 应几乎拉直，实际最大振幅 " + looseAmp);

        // μ 很大时应几乎不动
        double stiffAmp = stiff.points().stream().mapToInt(p -> Math.abs(p.z())).max().orElse(0);
        assertTrue(stiffAmp >= 3, "μ=100 应几乎不动（输入振幅 4），实际 " + stiffAmp);
    }

    // ── 性质 6：退化输入 ──────────────────────────────────────

    @Test
    void 少于三个点必须原样返回() {
        List<BandResult.Point> two = List.of(
                new BandResult.Point(0, 0), new BandResult.Point(10, 5));
        BandResult result = ElasticBandSolver.defaults().smooth(two);
        assertEquals(two, result.points(), "两点没有曲率可优化，应原样返回");
        assertEquals(0, result.iterations());

        BandResult empty = ElasticBandSolver.defaults().smooth(List.of());
        assertTrue(empty.points().isEmpty(), "空输入应返回空");
    }

    @Test
    void 保形权重必须为正() {
        assertThrows(IllegalArgumentException.class,
                () -> new ElasticBandSolver(0.0, 100, 1e-9),
                "μ=0 时线性系统退化成求核空间，解不由输入决定，必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ElasticBandSolver(-1.0, 100, 1e-9),
                "负的保形权重必须拒绝");
    }
}
