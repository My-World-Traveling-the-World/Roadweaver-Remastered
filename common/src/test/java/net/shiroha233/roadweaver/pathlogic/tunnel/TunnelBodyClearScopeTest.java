package net.shiroha233.roadweaver.pathlogic.tunnel;

import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;

/**
 * 0.9.065：隧道<b>洞身段</b>禁止向上清空的回归。
 *
 * <p>背景（真机症状「隧道没顶」）：洞身站的岩体本身就是顶，不该往上削。但施工端洞身站与普通路面
 * <b>走的是同一条路径</b> —— {@code SegmentPaver.paveSegment} → {@code SurfacePlacementUtil.placeOnSurface}
 * → {@code RoadBlockPlacer.placeRoadBlock} → {@code AboveColumnClearer.clearAboveColumn(..., flattenBumps=true)}，
 * 其中「凸起削平」会把洞顶以上覆土削到地表，随后 {@code RoadTunnelBuilder} 量到覆土 &lt; 1 就放弃封顶。</p>
 *
 * <p>本测试钉住三档处置（纯函数 + 台账，不依赖 WorldGenLevel）：</p>
 * <ol>
 *   <li>洞身站：净空契约只到洞身顶部，{@code clearsAboveBore=false}；台账 {@code skippedClear} 随列数 +1/+n，
 *       {@code bodyStations=1}；</li>
 *   <li>普通路段站：契约与改前一致（{@code clearsAboveBore=true}、最高格 = clearHeight-1），不进隧道台账；</li>
 *   <li>洞口明挖段：{@code tunnelBody=false}、{@code clearsAboveBore=true}（照旧削平洞口上方地形），
 *       台账记 {@code portalCleared} 而不是 {@code skippedClear}。</li>
 * </ol>
 */
class TunnelBodyClearScopeTest {

    /** 与 {@code RoadConstants.DEFAULT_TUNNEL_CLEAR_HEIGHT} 一致的默认洞身净空。 */
    private static final int CLEAR = 5;

    // ── ① 洞身站：禁止向上清空 ───────────────────────────────────────────────

    @Test
    void bodyStationSkipsUpwardClear() {
        AboveColumnClearer.ClearExtent bore = AboveColumnClearer.clearExtent(true, CLEAR);
        assertFalse(bore.clearsAboveBore(),
                "洞身站禁止向上清空（凸起削平 / 落方块封顶 / 顶层杂物一律不跑）");
        assertEquals(CLEAR - 1, bore.topDy(),
                "洞身站只能清 [pos, pos+clearHeight) 这一段，最高一格是 clearHeight-1");
        // 拱顶线 = clearHeight + 1（RoadTunnelBuilder.roofOffset），净空上限必须严格低于它。
        assertTrue(bore.topDy() < CLEAR + 1,
                "净空上限 " + bore.topDy() + " 必须严格低于拱顶线 " + (CLEAR + 1));

        assertTrue(SegmentPaver.tunnelBodyStation(true, false), "隧道站且不是明挖段 ⇒ 洞身站");

        TunnelClearLedger.reset();
        TunnelClearLedger.Scope scope = new TunnelClearLedger.Scope(10, 20);
        TunnelClearLedger.noteBodyStation(scope);
        TunnelClearLedger.noteSkippedClear(scope, 1);
        assertEquals(1L, TunnelClearLedger.bodyStations(scope), "洞身站计数 +1");
        assertEquals(1L, TunnelClearLedger.skippedClear(scope), "被跳过的向上清空列计数 +1");
        assertEquals(0L, TunnelClearLedger.portalCleared(scope), "洞身站不得记成明挖列");
        assertEquals("[TUNNEL-CLEAR] span=10..20 bodyStations=1 skippedClear=1 portalCleared=0",
                TunnelClearLedger.line(scope), "诊断行格式必须可 grep");
        TunnelClearLedger.reset();
    }

    // ── ② 普通路段站：行为与改前一致（仍会向上清空） ─────────────────────────

    @Test
    void ordinaryStationStillClearsUpward() {
        AboveColumnClearer.ClearExtent surface = AboveColumnClearer.clearExtent(false, 4);
        assertTrue(surface.clearsAboveBore(), "普通路段必须照旧向上清空（凸起削平）");
        assertEquals(3, surface.topDy(), "普通路段的净空上限仍是 clearHeight-1");

        assertFalse(SegmentPaver.tunnelBodyStation(false, false), "非隧道站永远不是洞身站");
        assertFalse(SegmentPaver.tunnelBodyStation(false, true), "非隧道站即使带明挖标记也不是洞身站");

        // 普通路段没有隧道 span ⇒ 台账一个数都不该动。
        TunnelClearLedger.reset();
        TunnelClearLedger.Scope none = new TunnelClearLedger.Scope(0, 0);
        assertEquals(0L, TunnelClearLedger.bodyStations(none));
        assertEquals(0L, TunnelClearLedger.skippedClear(none));
        assertEquals(0L, TunnelClearLedger.portalCleared(none));
        TunnelClearLedger.reset();
    }

    // ── ③ 洞口明挖段：行为不变 ───────────────────────────────────────────────

    @Test
    void portalOpenCutKeepsUpwardClear() {
        assertFalse(SegmentPaver.tunnelBodyStation(true, true),
                "洞口明挖段不是洞身站（岩体不是它的顶，明挖本来就要削到地表）");

        AboveColumnClearer.ClearExtent portal = AboveColumnClearer.clearExtent(false, CLEAR);
        assertTrue(portal.clearsAboveBore(), "洞口明挖段照旧向上削平洞口上方地形");
        assertEquals(CLEAR - 1, portal.topDy(), "洞口段净空上限与改前一致");

        TunnelClearLedger.reset();
        TunnelClearLedger.Scope scope = new TunnelClearLedger.Scope(40, 60);
        TunnelClearLedger.notePortalClear(scope, 15);
        assertEquals(15L, TunnelClearLedger.portalCleared(scope), "明挖列记 portalCleared");
        assertEquals(0L, TunnelClearLedger.skippedClear(scope), "明挖段不算「跳过向上清空」");
        assertEquals(0L, TunnelClearLedger.bodyStations(scope), "明挖段不计洞身站");
        assertEquals("[TUNNEL-CLEAR] span=40..60 bodyStations=0 skippedClear=0 portalCleared=15",
                TunnelClearLedger.line(scope), "明挖段一行里 portalCleared 必须有数");
        TunnelClearLedger.reset();
    }

    // ── 边界：净空高度夹取 ───────────────────────────────────────────────────

    @Test
    void clearHeightIsClampedAndScopeNeverGoesNegative() {
        assertEquals(15, AboveColumnClearer.clearExtent(true, 99).topDy(), "净空上限夹到 16 格");
        assertEquals(0, AboveColumnClearer.clearExtent(true, 0).topDy(), "净空下限夹到 1 格");
        assertFalse(AboveColumnClearer.clearExtent(true, 16).clearsAboveBore(),
                "净空开到 16 格时洞身段仍禁止向上清空");
    }
}
