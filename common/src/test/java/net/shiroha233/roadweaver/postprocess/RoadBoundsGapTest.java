/* 文件职责：钉住「并排判定」的几何度量口径 —— 必须是切比雪夫距离，不是欧氏距离。 */
package net.shiroha233.roadweaver.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包围盒间距度量测试（2026-11-15）。
 *
 * <h2>它守的是什么</h2>
 * <p>{@code RoadSnapService.RoadBounds.gap} 是「两条路有没有并排」的<b>判据核心</b>：
 * {@code ParallelOverlapResolver.boxMayOverlap} 直接拿它跟阈值比
 * （{@code a.gap(b) <= maxDistance + BRAID_BOX_MARGIN}），
 * 过了预筛才会进真正的逐站重叠判定。</p>
 *
 * <h2>为什么必须钉「max-norm 而不是欧氏」</h2>
 * <p>两条路在格点世界里「并排」的含义是<b>沿任一轴靠得太近</b>，
 * 不是直线距离近。举例：两个盒在 X 方向差 3、Z 方向差 4 ——</p>
 * <ul>
 *   <li>欧氏距离 = 5</li>
 *   <li><b>切比雪夫（max-norm）= 4</b></li>
 * </ul>
 * <p>如果哪天有人把它「优化」成欧氏距离，判据会变宽松：欧氏远大于实际轴向间距，
 * 于是 3×4 这种实际并排的两条路会被判成「不重叠」而漏掉改道 ——
 * 而且<b>不会报错、不会崩</b>，只是路开始重叠。这个测试就是防这种静默漂移。</p>
 *
 * <h2>另一条边界：null 必须返回 MAX_VALUE</h2>
 * <p>调用方拿到 {@code Integer.MAX_VALUE} 时，任何有限的 {@code maxDistance} 比较都会失败
 * ⇒ 判定「不可能重叠」⇒ 跳过。这与「读不到包围盒时返回 true（宁可多算）」是两回事：
 * 前者是<b>盒存在但对方为 null</b>（调用方应已处理），后者是<b>连自己的盒都读不到</b>。</p>
 */
class RoadBoundsGapTest {

    private static RoadSnapService.RoadBounds box(int minX, int minZ, int maxX, int maxZ) {
        return new RoadSnapService.RoadBounds(minX, minZ, maxX, maxZ);
    }

    @Test
    void 完全重叠时间距为零() {
        RoadSnapService.RoadBounds a = box(0, 0, 100, 100);

        assertEquals(0, a.gap(a), "同一个盒与自己的间距必须是 0");
        assertEquals(0, a.gap(box(50, 50, 60, 60)),
                "一个盒完全包含另一个时，间距也必须是 0");
        assertEquals(0, box(0, 0, 10, 10).gap(box(10, 10, 20, 20)),
                "边界相接（X 与 Z 同时贴着）应算 0 —— max-norm 下相接即重叠");
    }

    @Test
    void 单轴分离时等于该轴间距() {
        RoadSnapService.RoadBounds a = box(0, 0, 10, 10);

        assertEquals(5, a.gap(box(15, 0, 25, 10)), "X 方向差 5 格 ⇒ 间距 5");
        assertEquals(7, a.gap(box(0, 17, 10, 27)), "Z 方向差 7 格 ⇒ 间距 7");
        assertEquals(1, a.gap(box(11, 0, 20, 10)), "只差 1 格也必须如实报 1，不能抹成 0");
    }

    @Test
    void 双轴分离时取最大值而不是欧氏距离() {
        RoadSnapService.RoadBounds a = box(0, 0, 10, 10);
        // X 差 3、Z 差 4
        RoadSnapService.RoadBounds b = box(13, 0, 20, 10);
        RoadSnapService.RoadBounds c = box(0, 14, 10, 20);
        RoadSnapService.RoadBounds d = box(13, 14, 20, 20);

        assertEquals(3, a.gap(b));
        assertEquals(4, a.gap(c));
        // ★ 这条是核心：max(3, 4) = 4，不是 sqrt(3²+4²) = 5
        assertEquals(4, a.gap(d),
                "双轴分离时必须取 max（切比雪夫），不是欧氏距离。"
                        + "取欧氏会让判据变宽松 —— 3×4 这种实际并排的两条路会被漏判，"
                        + "而且不会报错、不会崩，只是路开始重叠");
    }

    @Test
    void 对称性() {
        RoadSnapService.RoadBounds a = box(0, 0, 10, 10);
        RoadSnapService.RoadBounds b = box(13, 14, 20, 20);

        assertEquals(a.gap(b), b.gap(a), "间距必须对称 —— 否则「谁先被判」会影响结果");
    }

    @Test
    void 与null的间距是无穷大() {
        RoadSnapService.RoadBounds a = box(0, 0, 10, 10);

        assertEquals(Integer.MAX_VALUE, a.gap(null),
                "对方为 null 时返回 MAX_VALUE，使任何有限的 maxDistance 比较都失败 "
                        + "⇒ 判定「不可能重叠」⇒ 跳过");
    }

    @Test
    void 负坐标与跨原点都正确() {
        // 世界的 XZ 可以是负数，跨原点的盒很容易写错成「max 比 min 小」
        RoadSnapService.RoadBounds across = box(-50, -50, 50, 50);
        assertEquals(0, across.gap(box(-1, -1, 1, 1)),
                "跨原点的大盒包含小盒 ⇒ 间距 0");
        assertEquals(50, across.gap(box(100, -10, 120, 10)),
                "右侧差 50 格 ⇒ 间距 50");
        // 左侧：across.minX = -50、other.maxX = -60 ⇒ 间距 = -50 - (-60) = 10
        assertEquals(10, across.gap(box(-110, -10, -60, 10)),
                "负坐标相减最容易算错成 60；正确是 -50 - (-60) = 10");
    }

    @Test
    void 单点盒也能算() {
        RoadSnapService.RoadBounds point = box(5, 5, 5, 5);

        assertEquals(0, point.gap(box(0, 0, 10, 10)), "点落在盒内 ⇒ 0");
        assertEquals(5, point.gap(box(10, 5, 10, 5)), "点与点差 5 格 ⇒ 5");
        assertTrue(point.gap(box(100, 100, 100, 100)) > 90);
    }
}