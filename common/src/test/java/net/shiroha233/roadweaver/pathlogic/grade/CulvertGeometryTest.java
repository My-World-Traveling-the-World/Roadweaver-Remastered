/* 文件职责：钉住涵洞施工期判据的站数上限，防它再被改回与规划期不一致的值。 */
package net.shiroha233.roadweaver.pathlogic.grade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 涵洞几何判据测试（2026-11-15）。
 *
 * <h2>背景：这里曾经有一条会让「规划判涵洞、施工架桥」的口径冲突</h2>
 * <p>{@code CULVERT_MAX_SEGMENTS} 原本是 <b>2</b>，而规划期的 {@code culvertMaxStations} 默认是 <b>6</b>。
 * 这个判据有<b>两个调用点</b>，参数完全不同：</p>
 * <ol>
 *   <li>{@code prepareCulvertDeck} 传单站 ⇒ {@code end-start+1 = 1}，上限恒不生效；</li>
 *   <li><b>{@code promoteRuntimeBridges} 传完整站段</b> ⇒ 上限在这里真正起作用。</li>
 * </ol>
 * <p>于是 3..6 站的窄浅水：规划期判成涵洞、甲板写进 {@code targetY}；
 * 施工期 {@code promoteRuntimeBridges} 判「不是浅涵洞」⇒ 提升为桥
 * ⇒ 走桥体建造分支 ⇒ {@code prepareCulvertDeck} 根本不执行 ⇒ <b>最终架桥</b>。</p>
 *
 * <p>2026-11-15 已把上限对齐到 <b>6</b>（详见 {@code docs/CULVERT-THRESHOLD-ANALYSIS-20261115.md}）。
 * 这个测试钉住「1..6 站算涵洞、7 站不算」，也就是钉住「与规划期默认值一致」这件事。</p>
 *
 * <h2>★ 这个测试能守什么、不能守什么</h2>
 * <p><b>能守</b>：上限被改小（比如有人为了「保守」改回 2）时立刻红 —— 那正是本缺陷的原始形态。</p>
 * <p><b>不能守</b>：</p>
 * <ul>
 *   <li><b>水深</b>那条：规划期 {@code culvertMaxWaterDepth} 可配 1..4，这里仍是硬编码 2
 *       ⇒ 用户配成 3/4 时还会出现同样的冲突。测试无法覆盖「配置值 vs 硬编码」的漂移。</li>
 *   <li><b>净空</b>那条：规划期根本没有这条判据，这里 {@code CULVERT_MAX_OVERHEAD=6} 是单向收紧。
 *       正确修法是给规划期补上，不是放宽这里。</li>
 * </ul>
 */
class CulvertGeometryTest {

    /** 用「恰好合格」的水深与净空，把变量隔离到只看站数。 */
    private static final int OK_DEPTH = 2;
    private static final int OK_OVERHEAD = 6;

    @Test
    void 一到六站都算涵洞几何() {
        for (int segments = 1; segments <= 6; segments++) {
            assertTrue(CulvertJudge.isCulvertGeometry(segments, OK_DEPTH, OK_OVERHEAD),
                    segments + " 站、水深 " + OK_DEPTH + "、净空 " + OK_OVERHEAD
                            + " 必须算涵洞。规划期 culvertMaxStations 默认 6 就是按这个范围设计的 —— "
                            + "若这里返回 false，promoteRuntimeBridges 会把这些段提升成桥");
        }
    }

    @Test
    void 七站不算涵洞几何() {
        assertFalse(CulvertJudge.isCulvertGeometry(7, OK_DEPTH, OK_OVERHEAD),
                "7 站超出 culvertMaxStations 的默认上限 6，应当交给桥/填方处理");
    }

    @Test
    void 站数为零或负数不算() {
        assertFalse(CulvertJudge.isCulvertGeometry(0, OK_DEPTH, OK_OVERHEAD));
        assertFalse(CulvertJudge.isCulvertGeometry(-1, OK_DEPTH, OK_OVERHEAD));
    }

    // ══════════ isCulvertRun：整段最坏值判定（2026-11-15 修的真缺陷）══════════

    @Test
    void 整段都浅才算涵洞() {
        // 6 站，每站水深 1、净空 2 —— 全部合格
        int[] depth = {1, 1, 1, 2, 1, 1};
        int[] overhead = {2, 2, 3, 2, 2, 1};
        assertTrue(CulvertJudge.isCulvertRun(6, depth, overhead),
                "6 站全部水深≤2、净空≤6，应当算涵洞");
    }

    @Test
    void 首站浅但中段深的水道不能算涵洞() {
        // ★ 这就是原实现的缺陷形状：只在首站采样会判「可做涵洞」，
        //   实际第 3 站水深 5 已经超过上限 2。
        int[] depth = {1, 1, 5, 1, 1};     // 第 2 站（下标 2）深 5
        int[] overhead = {2, 2, 2, 2, 2};
        assertFalse(CulvertJudge.isCulvertRun(5, depth, overhead),
                "只要有一站水深超过上限，整段就不能算涵洞 —— 否则 promoteRuntimeBridges "
                        + "不提升为桥，而 prepareCulvertDeck 逐站复检时会在深的那站直接返回，"
                        + "那段路就压在深水上，既不架桥也不抬甲板");
    }

    @Test
    void 首站净空合格但末站超限的水道不能算涵洞() {
        int[] depth = {1, 1, 1, 1};
        int[] overhead = {2, 2, 2, 9};     // 末站净空 9 > 上限 6
        assertFalse(CulvertJudge.isCulvertRun(4, depth, overhead),
                "净空同样要取整段最大值：任一站超限就不能做涵洞");
    }

    @Test
    void 任一站完全没水也不能算涵洞() {
        int[] depth = {1, 0, 1};           // 中间站水深 0
        int[] overhead = {2, 2, 2};
        assertFalse(CulvertJudge.isCulvertRun(3, depth, overhead),
                "水深 0 表示该站不是水，整段不是连续水道 ⇒ 不算涵洞");
    }

    @Test
    void 全是边界值仍算涵洞() {
        int[] depth = {2, 2, 2};           // 正好等于上限
        int[] overhead = {6, 6, 6};        // 正好等于上限
        assertTrue(CulvertJudge.isCulvertRun(3, depth, overhead),
                "两项都恰好在上限上，应当算（判据是 <= 不是 <）");
    }

    @Test
    void 输入不合法时保守判否() {
        assertFalse(CulvertJudge.isCulvertRun(0, new int[0], new int[0]), "0 站不算");
        assertFalse(CulvertJudge.isCulvertRun(3, null, new int[3]), "null 水深不算");
        assertFalse(CulvertJudge.isCulvertRun(3, new int[3], null), "null 净空不算");
        assertFalse(CulvertJudge.isCulvertRun(5, new int[3], new int[5]),
                "数组长度不够 stationCount 时必须判否，不能越界也不能当成 0 处理");
        assertFalse(CulvertJudge.isCulvertRun(5, new int[5], new int[3]),
                "净空数组长度不够同理");
    }

    @Test
    void 站数超过上限时直接判否() {
        int[] depth = new int[7];
        int[] overhead = new int[7];
        java.util.Arrays.fill(depth, 1);
        java.util.Arrays.fill(overhead, 2);
        assertFalse(CulvertJudge.isCulvertRun(7, depth, overhead),
                "7 站超过站数上限（默认 6）");
    }

    // ══════════ 3b：兜底值 / 配置出厂默认 / 配置侧夹取 三者必须一致 ══════════
    //
    // ★ 2026-11-15 自查：这一段原来的两条用例是**假测试**。
    //   原写法：assertTrue(maxSegments() == 6 || maxSegments() > 0, ...)
    //   —— 逻辑上等价于 `> 0`，而 clamp 的下界恒为 1 ⇒ **永远不可能失败**。
    //   负控实证：把 CulvertJudge 的上界从 16 改成 99，**测试照样全绿**，
    //   因为单测里 ConfigService 不可用、走的是兜底 6，压根没测到夹取。
    //   现在改成三条**会真的变红**的断言。

    @Test
    void 兜底值必须等于配置出厂默认() {
        // 单测环境里 ConfigService 通常不可用 ⇒ CulvertJudge 走兜底常量。
        // 兜底值与 RoadAppearanceConfig 的字段初始值必须一致，
        // 否则「配置读不到」时会静默变成另一套口径（正是本缺陷的成因之一）。
        net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig fresh =
                new net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig();
        assertEquals(fresh.culvertMaxStations(), CulvertJudge.DEFAULT_MAX_SEGMENTS,
                "兜底站数必须等于 RoadAppearanceConfig 的出厂默认；"
                        + "两边漂移会导致「配置读不到」时静默换口径");
        assertEquals(fresh.culvertMaxWaterDepth(), CulvertJudge.DEFAULT_MAX_WATER_DEPTH,
                "兜底水深必须等于 RoadAppearanceConfig 的出厂默认");
    }

    @Test
    void 配置侧夹取范围必须写死为规划期口径() {
        // 配置侧夹取是公开 setter，可以直接验。
        // 这条会抓到「有人把 16 改成 64」这类改动（它正是本轮缺陷的原始形态：
        // UI 声明 0..64，而下游只认到 16）。
        net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig c =
                new net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig();
        c.setCulvertMaxStations(9999);
        assertEquals(CulvertJudge.SEGMENTS_MAX, c.culvertMaxStations(),
                "配置侧站数上界必须等于施工期用的 SEGMENTS_MAX（同口径的唯一真值）");
        c.setCulvertMaxStations(-9999);
        assertEquals(CulvertJudge.SEGMENTS_MIN, c.culvertMaxStations(),
                "配置侧站数下界必须等于施工期用的 SEGMENTS_MIN");

        c.setCulvertMaxWaterDepth(9999);
        assertEquals(CulvertJudge.DEPTH_MAX, c.culvertMaxWaterDepth(),
                "配置侧水深上界必须等于施工期用的 DEPTH_MAX");
        c.setCulvertMaxWaterDepth(-9999);
        assertEquals(CulvertJudge.DEPTH_MIN, c.culvertMaxWaterDepth(),
                "配置侧水深下界必须等于施工期用的 DEPTH_MIN");
    }

    @Test
    void 施工期界值必须与配置侧夹取区间完全一致() {
        // ★ 2026-11-15：这条是「把界值提成具名常量」的目的 ——
        //   在此之前 CulvertJudge 的 clamp 用字面量 1/4 与 1/16，
        //   而 RoadAppearanceConfig 的 setter 里另有硬编码的 1/4 与 1/16。
        //   两处独立写死 ⇒ 谁单改一处就脱节（历史缺陷「UI 声明 0..64、下游只认 16」
        //   正是同一个形状）。现在两边共用 CulvertJudge 的常量，
        //   并用公开 setter 反证「配置侧确实用的是同一组界值」。
        net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig c =
                new net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig();

        // 上界：把配置推到远超常量的值，setter 必须夹到同一个常量
        c.setCulvertMaxStations(CulvertJudge.SEGMENTS_MAX * 100);
        assertEquals(CulvertJudge.SEGMENTS_MAX, c.culvertMaxStations(),
                "配置侧站数上界与 CulvertJudge.SEGMENTS_MAX 脱节了 —— "
                        + "这会让「规划判涵洞、施工架桥」那个缺陷以新形态复活");
        c.setCulvertMaxWaterDepth(CulvertJudge.DEPTH_MAX * 100);
        assertEquals(CulvertJudge.DEPTH_MAX, c.culvertMaxWaterDepth(),
                "配置侧水深上界与 CulvertJudge.DEPTH_MAX 脱节了");

        // 下界同理
        c.setCulvertMaxStations(0);
        assertEquals(CulvertJudge.SEGMENTS_MIN, c.culvertMaxStations(),
                "配置侧站数下界与 CulvertJudge.SEGMENTS_MIN 脱节了");
        c.setCulvertMaxWaterDepth(0);
        assertEquals(CulvertJudge.DEPTH_MIN, c.culvertMaxWaterDepth(),
                "配置侧水深下界与 CulvertJudge.DEPTH_MIN 脱节了");
    }

    @Test
    void 施工期可读的站数上限必须落在规划期夹取范围内() {
        // 这条是原先那条「夹在 [1,16]」的有效版本：它断言的是
        // **施工期实际拿到的值**必须落在配置侧夹取区间内 —— 若有人改了
        // CulvertJudge 的 clamp 上界（比如 16→99）而配置侧还是 16，
        // 施工期就可能拿到 17+ 而配置永远给不出，两边脱节。
        net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig c =
                new net.shiroha233.roadweaver.config.sub.RoadAppearanceConfig();
        c.setCulvertMaxStations(999);
        int configured = c.culvertMaxStations();          // = 16，配置侧能给到的最大值
        int effective = CulvertJudge.maxSegments();       // 施工期实际用的
        assertTrue(effective >= 1 && effective <= configured,
                "施工期站数上限 " + effective + " 必须落在配置侧可达范围 [1," + configured + "] 内；"
                        + "若这里红了，说明 CulvertJudge 的 clamp 与 RoadAppearanceConfig 脱节");
    }

    @Test
    void 水深与净空仍各自把关() {
        // 这三条不是本缺陷的一部分，但它们是同一个判据的另两项，
        // 把上限改成 6 时不能顺带把它们放松。
        assertFalse(CulvertJudge.isCulvertGeometry(3, 3, OK_OVERHEAD),
                "水深 3 超过施工期上限 2 ⇒ 不算涵洞（这一条是已知的「配置可配 1..4 但这里硬编码 2」隐患）");
        assertFalse(CulvertJudge.isCulvertGeometry(3, 0, OK_OVERHEAD),
                "没有水（水深 0）⇒ 不算涵洞");
        assertFalse(CulvertJudge.isCulvertGeometry(3, OK_DEPTH, 7),
                "净空 7 超过上限 6 ⇒ 不算涵洞（规划期没有这条判据，是已知的第二处不一致）");
        assertTrue(CulvertJudge.isCulvertGeometry(6, OK_DEPTH, OK_OVERHEAD),
                "边界：6 站 + 水深 2 + 净空 6，三项都在上限上，必须算");
    }
}