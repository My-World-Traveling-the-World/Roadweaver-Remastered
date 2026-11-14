/* 文件职责：钉住涵洞施工期判据的站数上限，防它再被改回与规划期不一致的值。 */
package net.shiroha233.roadweaver.pathlogic.grade;

import org.junit.jupiter.api.Test;

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
            assertTrue(RoadConstructionSafety.isCulvertGeometry(segments, OK_DEPTH, OK_OVERHEAD),
                    segments + " 站、水深 " + OK_DEPTH + "、净空 " + OK_OVERHEAD
                            + " 必须算涵洞。规划期 culvertMaxStations 默认 6 就是按这个范围设计的 —— "
                            + "若这里返回 false，promoteRuntimeBridges 会把这些段提升成桥");
        }
    }

    @Test
    void 七站不算涵洞几何() {
        assertFalse(RoadConstructionSafety.isCulvertGeometry(7, OK_DEPTH, OK_OVERHEAD),
                "7 站超出 culvertMaxStations 的默认上限 6，应当交给桥/填方处理");
    }

    @Test
    void 站数为零或负数不算() {
        assertFalse(RoadConstructionSafety.isCulvertGeometry(0, OK_DEPTH, OK_OVERHEAD));
        assertFalse(RoadConstructionSafety.isCulvertGeometry(-1, OK_DEPTH, OK_OVERHEAD));
    }

    @Test
    void 水深与净空仍各自把关() {
        // 这三条不是本缺陷的一部分，但它们是同一个判据的另两项，
        // 把上限改成 6 时不能顺带把它们放松。
        assertFalse(RoadConstructionSafety.isCulvertGeometry(3, 3, OK_OVERHEAD),
                "水深 3 超过施工期上限 2 ⇒ 不算涵洞（这一条是已知的「配置可配 1..4 但这里硬编码 2」隐患）");
        assertFalse(RoadConstructionSafety.isCulvertGeometry(3, 0, OK_OVERHEAD),
                "没有水（水深 0）⇒ 不算涵洞");
        assertFalse(RoadConstructionSafety.isCulvertGeometry(3, OK_DEPTH, 7),
                "净空 7 超过上限 6 ⇒ 不算涵洞（规划期没有这条判据，是已知的第二处不一致）");
        assertTrue(RoadConstructionSafety.isCulvertGeometry(6, OK_DEPTH, OK_OVERHEAD),
                "边界：6 站 + 水深 2 + 净空 6，三项都在上限上，必须算");
    }
}