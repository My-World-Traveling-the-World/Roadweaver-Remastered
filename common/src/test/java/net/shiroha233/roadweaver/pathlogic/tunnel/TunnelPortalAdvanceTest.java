package net.shiroha233.roadweaver.pathlogic.tunnel;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.064：洞口段长从固定 14 站改成「按坡度推」的回归。
 *
 * <p>0.9.063 的问题：{@code PORTAL_TRANSITION_SEGMENTS = 14} 是常量 —— 距任一端 &lt; 14 站的每一站都算
 * 洞口 ⇒ 任何短于 29 站的隧道整段都是明挖。判据能判出隧道（{@code TunnelPipelineIntegrationTest} 的
 * 真实产物是 16 站），施工端却把它挖成一条明槽。本测试钉住四件事：</p>
 * <ol>
 *   <li>纯函数：平坦地形 → 短洞口；陡地形（洞口埋得深）→ 长洞口；超过上限一律截到 32 站；</li>
 *   <li>拿不到地形/采样器 → 退回 14 站并带 {@code no-sampler} / {@code no-terrain} 标记；</li>
 *   <li>两端对称：洞身区间 = [span 起点 + portalStart, span 终点 − portalEnd]，洞身为空必须置
 *       {@code openCutOnly}（{@code WARN} 行的 span= / len= 也在本测试里钉住）；</li>
 *   <li><b>负控</b>：0.9.063 管线用例真实产出的 16 站隧道，{@code openCutOnly} 必须为 {@code false}
 *       —— 它必须仍然是一条隧道，不能被挖成沟。</li>
 * </ol>
 */
class TunnelPortalAdvanceTest {

    /** 与 0.9.063 管线用例（{@code TunnelPipelineIntegrationTest}）同一套数据。 */
    private static final int STATIONS = 71;
    private static final int BASE = 64;
    private static final int STATION_BLOCKS = 8;
    /** 名义站距，与 {@code RoadConstants.DEFAULT_ASTAR_STEP} 一致；管线用例的站也是每站 8 格。 */
    private static final double STEP = 8.0D;

    // ── 纯函数：坡度 → 洞口段长 ─────────────────────────────────────────────

    @Test
    void flatTerrainGivesShortPortal() {
        // 路面已经在地表（甚至地表之上）⇒ 没有引道要挖 ⇒ 只要一站让路走进洞身。
        RoadTunnelBuilder.PortalAdvance onSurface =
                RoadTunnelBuilder.portalAdvance((x, z) -> 64, deck(64), STEP);
        assertFalse(onSurface.fallback(), "有地形时必须按坡度推，不得走兜底");
        assertEquals("slope", onSurface.logValue(), "按坡度推出来的必须有 reason=slope");
        assertEquals(1, onSurface.segments(), "平缓地形（覆土 0）洞口段长应为 1 站");

        RoadTunnelBuilder.PortalAdvance aboveSurface =
                RoadTunnelBuilder.portalAdvance((x, z) -> 60, deck(64), STEP);
        assertEquals(1, aboveSurface.segments(), "路面在地表之上时同样取最小 1 站");

        // 覆土 1 格：1 / (0.08 × 8) = 1.5625 ⇒ 2 站，仍然很短。
        RoadTunnelBuilder.PortalAdvance oneDeep =
                RoadTunnelBuilder.portalAdvance((x, z) -> 65, deck(64), STEP);
        assertEquals(2, oneDeep.segments(), "覆土 1 格 ⇒ ceil(1 / 0.64) = 2 站");
    }

    @Test
    void steepTerrainGivesLongPortal() {
        // 洞口埋 5 格（山体在洞口抬得陡）：5 / (0.08 × 8) = 7.8125 ⇒ 8 站。
        RoadTunnelBuilder.PortalAdvance steep =
                RoadTunnelBuilder.portalAdvance((x, z) -> 69, deck(64), STEP);
        assertEquals(8, steep.segments(), "覆土 5 格 ⇒ ceil(5 / 0.64) = 8 站");

        RoadTunnelBuilder.PortalAdvance flat =
                RoadTunnelBuilder.portalAdvance((x, z) -> 64, deck(64), STEP);
        assertTrue(steep.segments() > flat.segments(),
                "洞口越深（地形越陡）洞口段必须更长：steep=" + steep.segments()
                        + " flat=" + flat.segments());

        // 洞口站几列路面里取最深的那一列：最深的列决定引道要开多长。
        List<BlockPos> sloped = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 1), new BlockPos(0, 66, 2));
        RoadTunnelBuilder.PortalAdvance deepest =
                RoadTunnelBuilder.portalAdvance((x, z) -> 70, sloped, STEP);
        assertEquals(10, deepest.segments(), "最深列覆土 6 格 ⇒ ceil(6 / 0.64) = 10 站");
    }

    @Test
    void portalLengthIsCappedAt32() {
        // 覆土 40 格：40 / 0.64 = 62.5 ⇒ 63 站，必须被上限截到 32。
        RoadTunnelBuilder.PortalAdvance deep =
                RoadTunnelBuilder.portalAdvance((x, z) -> 104, deck(64), STEP);
        assertEquals(32, deep.segments(), "超过上限一律截到 32 站（防在山脚无限延伸）");

        // 边界：64 / 0.64 = 100 也一样被截。
        RoadTunnelBuilder.PortalAdvance cliff =
                RoadTunnelBuilder.portalAdvance((x, z) -> 200, deck(64), STEP);
        assertEquals(32, cliff.segments(), "再深也是 32 站");

        // 上限之下不截：覆土 20 格 ⇒ ceil(20 / 0.64) = 32 站（恰好在上限上）。
        RoadTunnelBuilder.PortalAdvance atCap =
                RoadTunnelBuilder.portalAdvance((x, z) -> 84, deck(64), STEP);
        assertEquals(32, atCap.segments(), "覆土 20 格恰好算到上限 32 站");

        // 上限之下一点：覆土 19 格 ⇒ ceil(29.6875) = 30 站，不许被截。
        RoadTunnelBuilder.PortalAdvance belowCap =
                RoadTunnelBuilder.portalAdvance((x, z) -> 83, deck(64), STEP);
        assertEquals(30, belowCap.segments(), "覆土 19 格 ⇒ 30 站，未到上限不得被截");
    }

    // ── 拿不到地形：退回 14 站 + fallback 标记 ───────────────────────────────

    @Test
    void missingTerrainFallsBackToFourteen() {
        RoadTunnelBuilder.PortalAdvance noSampler =
                RoadTunnelBuilder.portalAdvance(null, deck(64), STEP);
        assertTrue(noSampler.fallback(), "拿不到采样器时 fallback 必须为 true");
        assertEquals(14, noSampler.segments(), "拿不到采样器 ⇒ 退回历史常量 14 站");
        assertEquals("no-sampler", noSampler.logValue(), "reason 必须是 no-sampler");

        RoadTunnelBuilder.PortalAdvance noTerrain =
                RoadTunnelBuilder.portalAdvance((x, z) -> null, deck(64), STEP);
        assertTrue(noTerrain.fallback(), "采样拿不到高度时 fallback 必须为 true");
        assertEquals(14, noTerrain.segments(), "采样不到地形 ⇒ 退回 14 站");
        assertEquals("no-terrain", noTerrain.logValue(), "reason 必须是 no-terrain");

        RoadTunnelBuilder.PortalAdvance throwsTerrain =
                RoadTunnelBuilder.portalAdvance((x, z) -> {
                    throw new IllegalStateException("世界生成边界上采样会抛");
                }, deck(64), STEP);
        assertEquals(14, throwsTerrain.segments(), "采样抛异常 ⇒ 退回 14 站，不得否掉整段隧道");
        assertEquals("no-terrain", throwsTerrain.logValue(), "reason 必须是 no-terrain");

        RoadTunnelBuilder.PortalAdvance noRoad =
                RoadTunnelBuilder.portalAdvance((x, z) -> 64, List.of(), STEP);
        assertEquals(14, noRoad.segments(), "洞口站路面列为空 ⇒ 退回 14 站");
        assertEquals("no-terrain", noRoad.logValue(), "reason 必须是 no-terrain");
    }

    // ── 两端对称 + openCutOnly ───────────────────────────────────────────────

    @Test
    void bodyIsSymmetricAndOpenCutOnlyIsFlagged() {
        RoadTunnelBuilder.PortalSplit split = RoadTunnelBuilder.portalSplit(10, 40,
                new RoadTunnelBuilder.PortalAdvance(2, null),
                new RoadTunnelBuilder.PortalAdvance(5, null));
        assertEquals(10, split.spanFirst());
        assertEquals(40, split.spanLast());
        assertEquals(31, split.stations());
        assertEquals(2, split.portalStart(), "起点侧洞口段长原样使用");
        assertEquals(5, split.portalEnd(), "终点侧洞口段长原样使用（两端同一套规则）");
        assertEquals(12, split.bodyStart());
        assertEquals(35, split.bodyEnd());
        assertFalse(split.openCutOnly(), "洞身还有 24 站，不得置 openCutOnly");
        assertNull(split.reason(), "两端都按坡度推出来时 reason 为空");
        assertTrue(split.logLine().startsWith("[TUNNEL-PORTAL] span=10..40 len=31 portalStart=2 portalEnd=5"),
                "诊断行字段顺序： " + split.logLine());

        RoadTunnelBuilder.PortalSplit allMouth = RoadTunnelBuilder.portalSplit(0, 9,
                new RoadTunnelBuilder.PortalAdvance(6, null),
                new RoadTunnelBuilder.PortalAdvance(6, null));
        assertTrue(allMouth.openCutOnly(), "洞身长度 ≤ 0 时必须置 openCutOnly： " + allMouth.logLine());
        assertTrue(allMouth.openCutOnlyLine().contains("span=0..9"),
                "WARN 行必须写明 span=： " + allMouth.openCutOnlyLine());
        assertTrue(allMouth.openCutOnlyLine().contains("len=10"),
                "WARN 行必须写明 len=： " + allMouth.openCutOnlyLine());

        // 兜底 14 站在两端 ⇒ 任何短于 29 站的 span 整段是洞口（这正是 0.9.063 的明槽）。
        RoadTunnelBuilder.PortalSplit fallbackShort = RoadTunnelBuilder.portalSplit(0, 27,
                new RoadTunnelBuilder.PortalAdvance(14, "no-sampler"),
                new RoadTunnelBuilder.PortalAdvance(14, "no-sampler"));
        assertTrue(fallbackShort.openCutOnly(), "28 站 + 兜底 14/14 ⇒ 洞身为空");
        assertEquals("no-sampler", fallbackShort.reason(), "兜底原因必须带出来");
    }

    // ── 负控：0.9.063 管线用例真实产出的 16 站隧道 ──────────────────────────

    /**
     * 0.9.063 的管线用例（71 站、每站 8 格、20..49 的对称帐篷山、峰高 12、路面贴着地表爬）实测产出
     * {@code TunnelPipeline span=27..42 stations=16}。同一个 span 走 0.9.064 的洞口判定，洞身必须还在
     * （{@code openCutOnly=false}）—— 否则「判出隧道、实际挖成沟」这件事就又复发了。
     */
    @Test
    void sixteenStationTunnelFromPipelineIsNotOpenCutOnly() {
        int[] terrain = mountainProfile();
        List<RoadSegmentPlacement> segments = new ArrayList<>(STATIONS);
        List<BlockPos> centers = new ArrayList<>(STATIONS);
        List<Integer> targetY = new ArrayList<>(STATIONS);
        for (int i = 0; i < STATIONS; i++) {
            BlockPos middle = new BlockPos(i * STATION_BLOCKS, terrain[i], 0);
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
            centers.add(middle);
            // 路面贴着地表往上爬（每站只切 1 格）—— 0.9.063 管线用例的那条纵断面。
            targetY.add(terrain[i] - 1);
        }
        List<RoadSpan> spans = PathSpanExtractor.refineTunnelSpans(List.of(), segments, targetY, null, null);
        RoadSpan tunnel = spans.stream()
                .filter(span -> span != null && span.type() == SpanType.TUNNEL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("0.9.063 的判据在这条纵断面上必须判出隧道"));
        int first = tunnel.start().getX() / STATION_BLOCKS;
        int last = tunnel.end().getX() / STATION_BLOCKS;
        assertEquals(16, last - first + 1,
                "0.9.063 管线用例的实测产物是 16 站隧道（实际 span=" + first + ".." + last + "）");

        int[] profile = targetY.stream().mapToInt(Integer::intValue).toArray();
        RoadTunnelBuilder.PortalTerrain sampler = (x, z) -> terrain[Math.floorDiv(x, STATION_BLOCKS)];
        RoadTunnelBuilder.PlannedSection head = RoadTunnelBuilder.plannedSection(
                segments.get(first), first, centers, profile, first);
        RoadTunnelBuilder.PlannedSection tail = RoadTunnelBuilder.plannedSection(
                segments.get(last), last, centers, profile, first);
        assertNotNull(head, "洞口站的路面列必须能算出来");
        assertNotNull(tail, "洞口站的路面列必须能算出来");

        RoadTunnelBuilder.PortalAdvance headAdvance =
                RoadTunnelBuilder.portalAdvance(sampler, head.road(), STATION_BLOCKS);
        RoadTunnelBuilder.PortalAdvance tailAdvance =
                RoadTunnelBuilder.portalAdvance(sampler, tail.road(), STATION_BLOCKS);
        assertFalse(headAdvance.fallback(), "有地形时不得走兜底");
        assertFalse(tailAdvance.fallback(), "有地形时不得走兜底");

        // 走生产同一处日志入口，跑批输出里就能看到真实的 [TUNNEL-PORTAL] 行。
        RoadTunnelBuilder.PortalSplit split = RoadTunnelBuilder.announcePortalSplit(
                RoadTunnelBuilder.portalSplit(first, last, headAdvance, tailAdvance));
        assertNotNull(split);
        System.out.println("16 站隧道实测行: " + split.logLine());

        assertFalse(split.openCutOnly(), "16 站隧道不得被判成整段明挖： " + split.logLine());
        assertTrue(split.portalStart() + split.portalEnd() < split.stations(),
                "洞身必须留下站： " + split.logLine());
        assertEquals(16, split.stations());
        assertTrue(split.portalStart() >= 1 && split.portalStart() <= 7,
                "16 站隧道单端洞口不得超过 7 站（否则洞身为空）： " + split.logLine());
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 与 0.9.063 管线用例同一座对称帐篷山：20 起 30 站，峰高 12。 */
    private static int[] mountainProfile() {
        int[] terrain = new int[STATIONS];
        Arrays.fill(terrain, BASE);
        double center = 20 + (30 - 1) / 2.0D;
        for (int i = 20; i < 50; i++) {
            double distance = Math.abs(i - center);
            double fraction = Math.max(0.0D, 1.0D - distance / (30 / 2.0D));
            terrain[i] = BASE + (int) Math.round(12 * fraction);
        }
        return terrain;
    }

    /** 一个三列的洞口断面，路面都在 {@code deckY}。 */
    private static List<BlockPos> deck(int deckY) {
        return List.of(new BlockPos(0, deckY, 0), new BlockPos(0, deckY, 1), new BlockPos(0, deckY, -1));
    }
}
