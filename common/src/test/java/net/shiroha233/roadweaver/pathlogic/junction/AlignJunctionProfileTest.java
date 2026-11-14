/* 文件职责：钉住「路口处纵断面对齐」的行为，并暴露它隐含的前提（路口必须在端点）。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路口纵断面对齐测试（2026-11-15）。
 *
 * <h2>它守的是什么</h2>
 * <p>{@code alignJunctionProfile} 决定「把一条路裁到与另一条路相交处之后，
 * 路口那一带的纵断面怎么接」。它做三件事：</p>
 * <ol>
 *   <li>把路口站<b>强制钉到</b> {@code junctionY}（另一条路的施工高度）；</li>
 *   <li>从路口向外按「<b>每两站抬 1 格</b>」的包络收窄；</li>
 *   <li>再套一层邻接约束（与前一站、前两站各差 ≤1）。</li>
 * </ol>
 * <p>没有这一步时，裁出来的端点会保留旧剖面自己的高度 ⇒ 两条路之间留下
 * 一个「看得见的台阶」（javadoc 原话：a hidden two-road step）。</p>
 *
 * <h2>★ 它隐含一个前提：路口必须在端点</h2>
 * <p>实现里方向只有一支：</p>
 * <pre>
 *   int direction = junctionIndex == 0 ? 1 : -1;
 * </pre>
 * <p>也就是说 —— 路口在<b>第一站</b>时向右铺开，否则一律向<b>左</b>铺开。
 * 这个写法只在「路口恰好在一端」时正确（{@code trimRoadDataToRoad} 把路裁到交点，
 * 交点理论上是保留序列的端点）。</p>
 *
 * <p><b>但如果哪天路口落在中间</b>（例如裁剪逻辑改成保留两侧），
 * 这个实现只会调左半边、右半边原样保留 ⇒ 台阶重新出现，
 * 而且<b>不会报错、不会崩</b>。下面的 {@code 路口在中间时只调左侧} 就是把这个
 * 现状钉住：它不是在认可这个行为，而是让「行为变了」这件事一定被看见 ——
 * 真要支持中间路口，得改成两个方向各扫一遍，并改这条测试。</p>
 */
class AlignJunctionProfileTest {

    private static List<Integer> profile(int... values) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return List.of(boxed);
    }

    @Test
    void 路口站被强制钉到目标高度() {
        List<Integer> source = profile(10, 10, 10, 10, 10);

        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 0, 30);

        assertEquals(30, out.get(0), "路口站必须被钉到 junctionY，哪怕原值是 10");
        assertEquals(source.size(), out.size(), "对齐不能改变站数");
    }

    @Test
    void 路口在首站时向右每两站抬一格() {
        // 全 0 剖面、路口在 0、目标 8 ⇒ 向右按 (distance+1)/2 的包络放开
        List<Integer> source = profile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 0, 8);

        assertEquals(8, out.get(0), "站 0 = 路口 = 8");
        // distance=1 ⇒ permittedDelta=(1+1)/2=1；再受「与前站差≤1」约束
        assertTrue(Math.abs(out.get(1) - out.get(0)) <= 1, "站 1 与站 0 必须相邻（差 ≤1）");
        // 全 0 的原剖面本来就在包络内，所以邻接约束是紧的那个
        assertTrue(out.get(1) <= 9, "站 1 不应超过路口+1");
        // 包络本身：第 d 站离路口最多 (d+1)/2 格
        for (int d = 1; d < out.size(); d++) {
            int permitted = (d + 1) / 2;
            assertTrue(Math.abs(out.get(d) - 8) <= permitted + 1,
                    "第 " + d + " 站离路口不得超过包络 " + permitted + "（+1 容差来自邻接约束），实际 "
                            + Math.abs(out.get(d) - 8));
        }
    }

    @Test
    void 路口在末站时向左铺开() {
        List<Integer> source = profile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 10, 20);

        assertEquals(20, out.get(10), "末站=路口=20");
        assertTrue(Math.abs(out.get(9) - out.get(10)) <= 1, "末站与前一站必须相邻");
        // 方向是向左：越靠左受包络限制越松（但邻接约束仍然把它们拉成斜坡）
        assertTrue(out.get(9) < 20, "前一站应低于路口站（原剖面是 0，包络不允许它一步到位）");
    }

    @Test
    void 路口在中间时只调左侧() {
        // ★ 这条不是认可这个行为，而是把「隐含前提」钉成可观测事实。
        //   若将来改成两侧都调，这条会红 —— 那时应连同 javadoc 一起更新。
        List<Integer> source = profile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 5, 30);

        assertEquals(30, out.get(5), "路口站仍然被钉住");
        // 左侧被调（包络+邻接把它拉成斜坡）
        assertTrue(out.get(4) < 30, "左侧邻站应被约束在路口高度以下");
        // 右侧原样保留 —— 这正是「只朝一个方向」的证据
        assertEquals(0, out.get(6),
                "路口在中间时右侧原样保留（0）。这暴露出实现只扫一个方向；"
                        + "如果裁剪逻辑将来保留两侧，这里就是台阶复发的点");
    }

    @Test
    void 邻接约束保证前两站以内不跳变() {
        // 一个剧烈起伏的原剖面 + 路口要求 100
        List<Integer> source = profile(0, 50, 0, 50, 0, 50, 0, 50);

        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 0, 100);

        for (int i = 1; i < out.size(); i++) {
            assertTrue(Math.abs(out.get(i) - out.get(i - 1)) <= 1,
                    "第 " + i + " 站与前站差必须 ≤1（否则出现台阶），实际 "
                            + Math.abs(out.get(i) - out.get(i - 1)));
        }
    }

    @Test
    void 空输入与越界原样返回() {
        assertTrue(ExistingRoadJunctionService.alignJunctionProfile(null, 0, 10).isEmpty(),
                "null ⇒ 空列表（不是 null，调用方不必判空）");

        List<Integer> source = profile(1, 2, 3);
        assertEquals(source, ExistingRoadJunctionService.alignJunctionProfile(source, -1, 10),
                "负索引 ⇒ 原样返回");
        assertEquals(source, ExistingRoadJunctionService.alignJunctionProfile(source, 3, 10),
                "索引等于长度（越界）⇒ 原样返回");
        assertTrue(ExistingRoadJunctionService.alignJunctionProfile(List.of(), 0, 10).isEmpty(),
                "空列表 ⇒ 空列表");
    }

    @Test
    void 返回的是不可变副本而不是原对象() {
        List<Integer> source = profile(5, 5, 5);
        List<Integer> out = ExistingRoadJunctionService.alignJunctionProfile(source, 0, 9);

        assertEquals(5, source.get(0), "不得就地修改入参 —— 原列表必须还是 5");
        assertEquals(9, out.get(0), "返回的副本里路口站是 9");
    }
}