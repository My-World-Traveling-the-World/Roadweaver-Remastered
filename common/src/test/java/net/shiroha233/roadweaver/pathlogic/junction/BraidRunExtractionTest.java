/* 文件职责：钉住「哪一段算并排」的连续段切分边界 —— 边界差一站就会漏判或误判整段。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并排连续段切分测试（2026-11-15）。
 *
 * <h2>被测的是什么</h2>
 * <p>{@code RoadBraidDetector.extractRuns} 把「逐站离得够近」的布尔结果
 * （实现里用 {@code targets[i] >= 0} 表示）切成若干<b>连续区间</b>，
 * 只有长度达到 {@code MIN_RUN = 6} 的区间才被当作「一整段并排」。
 * 后续的改道、禁令、报告全部建立在这段区间上。</p>
 *
 * <h2>为什么它的边界值得单独钉</h2>
 * <p>三个边界只要差一站，后果就不是「差一点」：</p>
 * <ul>
 *   <li><b>短了一段本来该算的</b> ⇒ 漏判并排 ⇒ 两条路叠在一起没人管；</li>
 *   <li><b>多算了不该算的</b> ⇒ 误判 ⇒ 白跑一次改道（A* 空跑代价很高，
 *       历史上一次改道空跑实测 7.5 秒）；</li>
 *   <li><b>末尾段漏判</b> ⇒ 最隐蔽 —— 恰好结束在路末端的并排永远不会被发现。</li>
 * </ul>
 * <p>而这三个边界在实现里是<b>两套写法</b>：中间段用 {@code i - start}，
 * 末尾段用 {@code size - start}。两套写法一旦哪天改成不一致，
 * 「末尾并排」就会静默消失。这个测试就是钉住它们等价。</p>
 */
class BraidRunExtractionTest {

    /** 便于构造：{@code true}=够近（targets 里写 0），{@code false}=不够近（写 -1）。 */
    private static int[] targets(boolean... inside) {
        int[] out = new int[inside.length];
        for (int i = 0; i < inside.length; i++) out[i] = inside[i] ? 0 : -1;
        return out;
    }

    private static String describe(List<int[]> runs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(runs.get(i)[0]).append("..").append(runs.get(i)[1]);
        }
        return sb.append(']').toString();
    }

    @Test
    void 恰好达到最小长度的段被接受() {
        // 0..5 共 6 站 = MIN_RUN，刚好达标
        int[] t = targets(true, true, true, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertEquals(1, runs.size(), "6 站恰好等于 MIN_RUN，应被接受，实际 " + describe(runs));
        assertEquals(0, runs.get(0)[0]);
        assertEquals(5, runs.get(0)[1], "区间是闭区间，末站索引 5");
    }

    @Test
    void 差一站未达标的段被丢弃() {
        // 5 站 < MIN_RUN=6
        int[] t = targets(true, true, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertTrue(runs.isEmpty(),
                "5 站短于 MIN_RUN，必须丢弃 —— 短擦碰不是并排。实际 " + describe(runs));
    }

    @Test
    void 末尾段不会被漏判() {
        // 前 4 站不够近，后 6 站够近且一直延续到数组末尾
        int[] t = targets(false, false, false, false, true, true, true, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertEquals(1, runs.size(),
                "结束后什么都没跟的段是最容易漏的边界（循环里没有「下一个不是」来收尾），"
                        + "实际 " + describe(runs));
        assertEquals(4, runs.get(0)[0], "起点应是 4");
        assertEquals(9, runs.get(0)[1], "终点应是最后一个索引 9");
    }

    @Test
    void 末尾发起但不足最小长度的段也被丢弃() {
        // 前面 7 站远，末尾只有 3 站近
        int[] t = targets(false, false, false, false, false, false, false, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertTrue(runs.isEmpty(),
                "末尾段同样要过 MIN_RUN 这一关，不能因为「它在末尾」就放行。实际 " + describe(runs));
    }

    @Test
    void 中间与末尾的达标写法必须等价() {
        // 两段：0..5（中间段，被索引 6 的 false 收尾）与 7..12（末尾段）
        int[] t = targets(true, true, true, true, true, true, false,
                true, true, true, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertEquals(2, runs.size(), "应切出两段，实际 " + describe(runs));
        assertEquals(0, runs.get(0)[0]);
        assertEquals(5, runs.get(0)[1], "中间段 0..5（6 站，达标）");
        assertEquals(7, runs.get(1)[0]);
        assertEquals(12, runs.get(1)[1],
                "末尾段 7..12（6 站，达标）。中间段用 i-start、末尾段用 size-start，"
                        + "两套写法必须给出同样的长度口径");
    }

    @Test
    void 多个短段都不会被合并() {
        // 三个 3 站段，中间被 false 隔开 —— 总站数 9 站够近，但每段都不达标
        int[] t = targets(true, true, true, false, true, true, true, false, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertTrue(runs.isEmpty(),
                "每段只有 3 站，不得因为「加起来够多」就合并 —— "
                        + "合并会让断续擦碰被当成一整段并排。实际 " + describe(runs));
    }

    @Test
    void 全段达标时只切出一段() {
        int[] t = targets(true, true, true, true, true, true, true, true, true, true);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, t.length);

        assertEquals(1, runs.size(), "整条都够近时只该有一段");
        assertEquals(0, runs.get(0)[0]);
        assertEquals(9, runs.get(0)[1]);
    }

    @Test
    void 全段不达标时没有段() {
        int[] t = targets(false, false, false, false, false, false, false);
        assertTrue(RoadBraidDetector.extractRuns(t, t.length).isEmpty(),
                "一个够近的站都没有 ⇒ 没有段");
    }

    @Test
    void size小于数组长度时只看前size个() {
        // 数组实际 10 个元素，但只处理前 6 个（都够近）⇒ 应得到一段 0..5
        int[] t = targets(true, true, true, true, true, true, false, false, false, false);
        List<int[]> runs = RoadBraidDetector.extractRuns(t, 6);

        assertEquals(1, runs.size(), "只处理前 6 站，正好达标，实际 " + describe(runs));
        assertEquals(5, runs.get(0)[1], "终点必须是 size-1=5，不能读到数组后面的元素");
    }
}