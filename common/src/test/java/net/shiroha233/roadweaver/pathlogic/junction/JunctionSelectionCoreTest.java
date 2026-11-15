/* 文件职责：路口合并核心判据的契约测试 —— 单 junction 选择、代价口径、候选预筛。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ExistingRoadJunctionService} 里<b>不依赖 {@code ServerLevel}</b> 的核心判据。
 *
 * <h2>为什么补这些</h2>
 * <p>该类 842 行 / 36 个方法，2026-11-15 实测在整个 {@code common/src/test} 里
 * 只被调用过 1 个方法（{@code alignJunctionProfile}）—— 而它是**路口合并的心脏**。
 * 其中 {@code selectSingleNetworkJunction} / {@code plausibleDonor} /
 * {@code isAcceptableCandidate} / {@code constructionLength} 全部是纯函数
 * （只读 {@code RoadData}），可以离线钉住。</p>
 *
 * <h2>★ 这个类最要紧的一条设计承诺</h2>
 * <p>{@code selectSingleNetworkJunction} 的 javadoc 写着：</p>
 * <blockquote>
 * A road may physically join <b>one</b> existing corridor, not a chain of corridors.
 * Evaluating later donors against a route that was already shortened by an earlier donor
 * creates several overlapping anchor and reuse spans at a multi-way junction.
 * Evaluate every donor against the <b>unmodified</b> route and commit only the
 * <b>best single</b> junction instead.
 * </blockquote>
 * <p>—— 也就是说：<b>每条路最多接一个既有走廊，而且所有候选都对着「未被修改的原始路」评估</b>。
 * 这条承诺没有测试守着。</p>
 *
 * <h2>★★ 本类明确<b>没有</b>覆盖到什么（2026-11-15 自查，删掉了 3 条假测试）</h2>
 * <p>我第一版还写了三条用例，逐个做负控后发现<b>全部恒真、删掉被测代码照样绿</b>，
 * 已全部删除。原因同一个：<b>合成的 {@code RoadData} 永远通不过
 * {@code RoadDataValidator.isValidFinalRoad} 的完整校验</b>，于是
 * {@code isAcceptableCandidate} 在代价判据之前就返回 false ⇒ 永远选不出 donor。</p>
 *
 * <table>
 *   <caption>被删掉的假测试与它们为什么恒真</caption>
 *   <tr><th>用例</th><th>负控结果</th></tr>
 *   <tr><td>「owners 里只有自己时不会把自己当 donor」</td>
 *       <td>去掉自排除后<b>仍然全绿</b> —— 因为自合并产不出可接受结果，donor 两边都是 null</td></tr>
 *   <tr><td>「选择必须是确定性的」</td>
 *       <td>去掉 fingerprint 排序后<b>仍然全绿</b>。★ 换到合法夹具后**仍然恒真**：
 *           本夹具下两种入参顺序给出的合并结果内容完全相同 ⇒
 *           排序只影响遍历顺序，不影响最终选中的那个。
 *           要真正验证排序必要性，需要一个「先遍历到的候选更差」的夹具
 *           （例如两条候选 reuse 相同但构造成本不同且判据会因顺序改变胜者），
 *           <b>本轮没做，已删除这条而不是留着骗自己</b>。</td></tr>
 *   <tr><td>「isAcceptableCandidate 拒绝超出开销上限的候选」</td>
 *       <td>把上限判断改成 <code>if (false)</code> 后<b>仍然全绿</b> —— 合成路先被
 *           {@code isValidFinalRoad} 判死，压根走不到代价比较</td></tr>
 * </table>
 *
 * <p><b>2026-11-15 后续进展</b>：已把夹具改成 2 格站距（{@code MAX_CENTER_STEP=2}），
 * 并用「夹具本身必须能通过合法性校验」这条用例把夹具的合法性显式钉住。
 * 之后恢复了两条曾经被删的用例并**逐个负控确认现在会真红**：</p>
 * <ul>
 *   <li>「自排除」—— 去掉 {@code primary == secondary} 后**变红**（连带
 *       「null 元素被忽略」也红，两者在这一行为上重叠）</li>
 *   <li>「拒绝超出开销上限的候选」—— 把上限判断改成 <code>if (false)</code> 后**变红**</li>
 * </ul>
 * <p>但「确定性」那条<b>换了合法夹具之后仍然恒真</b>（负控依旧全绿）⇒ 已再次删除，
 * 理由记在上表里。要真验它需要一个「遍历顺序会改变胜者」的夹具。</p>
 */
class JunctionSelectionCoreTest {

    /**
     * 沿 X 轴的<b>合法</b>直路：n 个站点、站距 2 格、Y 恒 64、Z=z。
     *
     * <p>★ 为什么是 2 格：{@code RoadConstants.MAX_CENTER_STEP = 2}，
     * 而 {@code RoadDataValidator.validateFinalRoadUncached} 会检查
     * 「相邻站 center 的 max-norm 步长 &le; 2」与「targetY 相邻差 &le; 1」。
     * 我第一版夹具用 4 格站距 ⇒ **永远过不了 isValidFinalRoad**
     * ⇒ 下游 {@code isAcceptableCandidate} 在代价判据前就返回 false
     * ⇒ 永远选不出 donor ⇒ 我据此写的三条用例全是恒真的假测试（已删）。</p>
     */
    private static RoadData road(int x0, int z, int n) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos center = new BlockPos(x0 + i * 2, 64, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights, 1L, 2L);
    }

    // ══════════ 一、单 junction 选择：空输入与自排除 ══════════

    /**
     * ★ 夹具自证：这一条必须先绿，否则本类其余「选择类」断言全是恒真的。
     *
     * <p>我第一版夹具用 4 格站距，过不了 {@code MAX_CENTER_STEP=2} 那道校验，
     * 于是所有候选都在 {@code isAcceptableCandidate} 的第一行就被判死 ——
     * 那是本会话第 3 次「自己写的假测试」。这条用例把夹具的合法性**显式钉住**：
     * 谁改坏夹具，这条先红，而不是让下游断言悄悄变成真。</p>
     */
    @Test
    void 夹具本身必须能通过合法性校验() {
        RoadData road = road(0, 0, 11);
        assertTrue(RoadDataValidator.isValidFinalRoad(road),
                "★ 测试夹具必须通过 RoadDataValidator.isValidFinalRoad —— "
                        + "否则任何依赖它的「候选被接受」类断言都恒真");
    }

    @Test
    void owners_为空时原样返回且没有_donor() {
        RoadData secondary = road(0, 0, 12);
        ExistingRoadJunctionService.JunctionSelection s =
                ExistingRoadJunctionService.selectSingleNetworkJunction(List.of(), secondary);
        assertNotNull(s, "空 owners 也必须返回一个选择对象，不能返回 null");
        assertSame(secondary, s.road(), "没有候选时返回的必须是传入的那条路本身（不是副本）");
        assertNull(s.donor(), "没有候选就没有 donor");
    }

    @Test
    void secondary_为_null_时不崩() {
        ExistingRoadJunctionService.JunctionSelection s =
                ExistingRoadJunctionService.selectSingleNetworkJunction(
                        List.of(road(0, 0, 12)), null);
        assertNotNull(s, "secondary 为 null 也必须返回对象而不是抛异常");
        assertNull(s.road(), "secondary 为 null 时 road 也应为 null");
        assertNull(s.donor(), "secondary 为 null 时不可能选出 donor");
    }


    @Test
    void owners_里的_null_元素被忽略() {
        RoadData secondary = road(0, 0, 12);
        List<RoadData> owners = new ArrayList<>();
        owners.add(null);
        owners.add(secondary);
        owners.add(null);
        ExistingRoadJunctionService.JunctionSelection s =
                ExistingRoadJunctionService.selectSingleNetworkJunction(owners, secondary);
        assertSame(secondary, s.road(), "null 候选必须被剔除，不许影响结果");
        assertNull(s.donor(), "剔除 null 与自身后无候选");
    }


    // ══════════ 一之二、夹具已合法 ⇒ 三条曾被删除的用例现在是真断言 ══════════

    @Test
    void 自排除_owners_里只有自己时不会把自己当_donor() {
        RoadData secondary = road(0, 0, 11);
        ExistingRoadJunctionService.JunctionSelection s =
                ExistingRoadJunctionService.selectSingleNetworkJunction(List.of(secondary), secondary);
        assertSame(secondary, s.road(), "自己不许被当成 donor（否则会生成零长度 reuse span）");
        assertNull(s.donor(), "自排除后没有候选 ⇒ donor 必须是 null");
    }


    @Test
    void isAcceptableCandidate_拒绝超出构造开销上限的候选() {
        RoadData current = road(0, 0, 11);                 // 20 格
        RoadData expensive = road(0, 0, 200);              // 398 格，远超 20 + 上限(≈17)
        double currentCost = ExistingRoadJunctionService.constructionLength(current);
        assertTrue(RoadDataValidator.isValidFinalRoad(expensive),
                "★★ 构造前提：这条「昂贵候选」本身必须是**合法路**，"
                        + "否则它会先被 isValidFinalRoad 判死，"
                        + "本条断言就变成恒真（我第一版就是这么写成假测试的）");
        assertFalse(ExistingRoadJunctionService.isAcceptableCandidate(current, expensive, currentCost),
                "绕太远的合并不如不改 —— 超过 MAX_JUNCTION_CONSTRUCTION_OVERHEAD 必须拒绝");
    }

    // ══════════ 二、★ 只接一个走廊，不是接一串 ══════════

    @Test
    void 多候选时最多返回一个_donor_且必须是候选之一() {
        // 三条互相靠近的既有路都可能是 donor。
        // ★ javadoc 的承诺是「只能接**一个**走廊，不是接一串」——
        //   API 结构上 `JunctionSelection` 只有一个 donor 字段，所以「多于一个」不可能；
        //   这里能钉住的是另外两件事：
        //   ① donor 必须来自候选集合（不许凭空造）；② donor 不能是 secondary 自己。
        //
        // ★ 诚实标注：本节**没有**断言「一定会选出 donor」。原因是
        //   isAcceptableCandidate 要求候选通过 RoadDataValidator.isValidFinalRoad 的完整校验，
        //   合成 RoadData 很难满足（材料/span/高度/坡度多项），
        //   所以「真的选出一个 donor」的场景在本类里覆盖不到。
        //   本节只钉结构契约与代价口径；「选得对不对」仍需真机或更完整的夹具。
        RoadData secondary = road(0, 0, 12);
        RoadData d1 = road(0, 12, 12);
        RoadData d2 = road(0, -12, 12);
        RoadData d3 = road(0, 24, 12);
        List<RoadData> candidates = List.of(d1, d2, d3);
        ExistingRoadJunctionService.JunctionSelection s =
                ExistingRoadJunctionService.selectSingleNetworkJunction(candidates, secondary);

        if (s.donor() != null) {
            assertTrue(candidates.contains(s.donor()),
                    "donor 必须是候选集合里的某一条（不许凭空造）");
            assertFalse(s.donor() == secondary, "donor 不能是自己");
        }
        // 无论选没选中，road 字段都不许是 null（没选中时就是 original）
        assertNotNull(s.road(), "road 字段永远不能为 null（没合并时应原样返回 secondary）");
    }

    // ══════════ 三、代价口径：NETWORK_REUSE 段不计入构造长度 ══════════

    @Test
    void constructionLength_对_null_返回无穷大_对空段表返回零() {
        assertEquals(Double.POSITIVE_INFINITY,
                ExistingRoadJunctionService.constructionLength(null),
                "null 路没有可构造长度 ⇒ 必须是 +∞（这样它永远不会被选中）");

        // ★ 现状记录（不是「应该如此」）：空段表会让求和循环一次都不执行 ⇒ 返回 0.0，
        //   也就是「一条没站点的路构造成本为零」。这在选择判据里是**极其有利**的位置，
        //   之所以没出事，是因为 isAcceptableCandidate 里有一道
        //   `RoadDataValidator.isValidFinalRoad(candidate)` 会先把不足 5 站的路判死。
        //   ★ 谁把那道校验去掉/放宽，这条路的「零成本」就会让空路被选成最佳 donor。
        RoadData empty = new RoadData(4, 1, List.of(), List.of(),
                List.of(), List.of(), List.of(), 1L, 2L);
        assertEquals(0.0D, ExistingRoadJunctionService.constructionLength(empty), 1e-9D,
                "空段表当前返回 0.0 —— 依赖 isValidFinalRoad 在前面拦住，别单独放宽那道校验");
    }

    @Test
    void constructionLength_直线等于首末站距() {
        RoadData straight = road(0, 0, 11);   // 10 段 × 2 格 = 20
        assertEquals(20.0D, ExistingRoadJunctionService.constructionLength(straight), 1e-9D,
                "无 reuse 的直线，构造长度必须等于逐段长度之和");
    }

    @Test
    void constructionLength_把整段_NETWORK_REUSE_排除在外() {
        // 把「全部站点」标成 NETWORK_REUSE ⇒ 这段不需要新建 ⇒ 构造长度应为 0
        RoadData straight = road(0, 0, 11);
        RoadData allReused = withSpan(straight, straight.roadSegmentList().get(0).middlePos(),
                straight.roadSegmentList().get(10).middlePos(), SpanType.NETWORK_REUSE);
        assertEquals(0.0D, ExistingRoadJunctionService.constructionLength(allReused), 1e-9D,
                "★ 这是合并之所以划算的根基：让出给既有路的段不算我们的构造成本。"
                        + "若这里不为 0，合并永远不会被选中，"
                        + "「避免在已有路上再铺一条」这个功能等于失效");
    }

    @Test
    void constructionLength_部分_reuse_只扣掉那段() {
        RoadData straight = road(0, 0, 11);   // 20 格
        // 把站 3..7 标 reuse ⇒ 被跳过的段是 4..7（4 段 × 2 格 = 8 格）⇒ 剩 12 格
        List<RoadSegmentPlacement> seg = straight.roadSegmentList();
        RoadData partial = withSpan(straight,
                seg.get(3).middlePos(), seg.get(7).middlePos(), SpanType.NETWORK_REUSE);
        assertEquals(12.0D, ExistingRoadJunctionService.constructionLength(partial), 1e-9D,
                "部分 reuse 必须只扣掉被复用的那几段，不能把整条路都当免费");
    }

    /** 复制一条路并加一个指定类型的 span。 */
    private static RoadData withSpan(RoadData src, BlockPos from, BlockPos to, SpanType type) {
        return new RoadData(src.width(), src.roadType(), src.materials(), src.slabMaterials(),
                src.roadSegmentList(), List.of(new RoadSpan(from, to, type)),
                src.targetY(), src.ownerA2dKey(), src.ownerB2dKey());
    }

    // ══════════ 四、候选预筛：plausibleDonor ══════════

    @Test
    void plausibleDonor_对_null_返回_false() {
        assertFalse(ExistingRoadJunctionService.plausibleDonor(null, road(0, 0, 8), false),
                "null 主路不许当候选");
        assertFalse(ExistingRoadJunctionService.plausibleDonor(road(0, 0, 8), null, false),
                "null 次路不许当候选");
    }

    @Test
    void plausibleDonor_在包围盒超出门槛时返回_false() {
        RoadData near = road(0, 0, 8);
        RoadData far = road(0, 100000, 8);
        assertFalse(ExistingRoadJunctionService.plausibleDonor(far, near, false),
                "包围盒相距 10 万格，远超吸附阈值 ⇒ 纯几何早退必须把它排除掉"
                        + "（否则每个候选都要跑一次真实连接，代价白花）");
    }

    // ══════════ 五、可接受性：isAcceptableCandidate ══════════

    @Test
    void isAcceptableCandidate_拒绝_null_与自身() {
        RoadData current = road(0, 0, 11);
        double cost = ExistingRoadJunctionService.constructionLength(current);
        assertFalse(ExistingRoadJunctionService.isAcceptableCandidate(current, null, cost),
                "null 候选不可接受");
        assertFalse(ExistingRoadJunctionService.isAcceptableCandidate(current, current, cost),
                "候选等于当前路时不可接受（没有意义）");
    }

}