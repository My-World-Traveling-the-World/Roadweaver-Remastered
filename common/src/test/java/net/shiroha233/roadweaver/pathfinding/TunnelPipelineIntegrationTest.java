package net.shiroha233.roadweaver.pathfinding;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.063（任务二）**管线级**证据：判据重写之后，生产入口
 * {@link PathSpanExtractor#refineTunnelSpans} 在「路面贴着地表爬过一座山」这条最典型的失效场景里，
 * 真的会吐出一条 {@link SpanType#TUNNEL}。
 *
 * <p>离线可跑：{@code level == null}、{@code cache == null} 时提取器退回
 * {@code center.getY()} 当中心站地形高度（见 {@code centerTerrainHeights}），因此只要把
 * {@code middlePos.y} 填成**地形高度**、{@code targetY} 填成**规划路面高度**，就能复现真机的判据输入。
 * 这与真机唯一的差别是 footprint 采样被中心列代替，不影响判据本身。</p>
 */
class TunnelPipelineIntegrationTest {

    private static final int STATIONS = 71;
    private static final int BASE = 64;

    /** 对称帐篷山：20 起 30 站，峰高 12。 */
    private static int[] mountainProfile() {
        int[] terrain = new int[STATIONS];
        java.util.Arrays.fill(terrain, BASE);
        double center = 20 + (30 - 1) / 2.0D;
        for (int i = 20; i < 50; i++) {
            double distance = Math.abs(i - center);
            double fraction = Math.max(0.0D, 1.0D - distance / (30 / 2.0D));
            terrain[i] = BASE + (int) Math.round(12 * fraction);
        }
        return terrain;
    }

    private static List<RoadSegmentPlacement> segments(int[] terrain) {
        List<RoadSegmentPlacement> segments = new ArrayList<>(STATIONS);
        for (int i = 0; i < STATIONS; i++) {
            BlockPos middle = new BlockPos(i * 8, terrain[i], 0);
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
        }
        return segments;
    }

    /** 路面贴着地表往上爬（每站只切 1 格）—— 这正是把旧判据「覆土」抹平的那种纵断面。 */
    private static List<Integer> surfaceProfile(int[] terrain) {
        List<Integer> targetY = new ArrayList<>(STATIONS);
        for (int value : terrain) {
            targetY.add(value - 1);
        }
        return targetY;
    }

    @Test
    void mountainWithSurfaceHuggingProfileNowYieldsATunnel() {
        int[] terrain = mountainProfile();
        List<RoadSegmentPlacement> segments = segments(terrain);
        List<Integer> targetY = surfaceProfile(terrain);

        // 旧判据的输入：覆土 = 地形 − 规划路面 = 1，恒小于 tunnelCoreOverburden(8) ⇒ 一座山也判不出
        int maxLegacyOverburden = Integer.MIN_VALUE;
        for (int i = 0; i < STATIONS; i++) {
            maxLegacyOverburden = Math.max(maxLegacyOverburden, terrain[i] - targetY.get(i));
        }
        assertEquals(1, maxLegacyOverburden);
        assertTrue(maxLegacyOverburden < 8, "旧判据在这条纵断面上必须判不出隧道");

        List<RoadSpan> spans = PathSpanExtractor.refineTunnelSpans(List.of(), segments, targetY, null, null);
        long tunnels = spans.stream().filter(span -> span != null && span.type() == SpanType.TUNNEL).count();
        assertTrue(tunnels >= 1, "判据重写之后必须真的产出隧道 span，实际 " + tunnels
                + "（spans=" + spans.size() + "）");

        RoadSpan tunnel = spans.stream()
                .filter(span -> span != null && span.type() == SpanType.TUNNEL)
                .findFirst().orElseThrow();
        int startIndex = tunnel.start().getX() / 8;
        int endIndex = tunnel.end().getX() / 8;
        assertTrue(endIndex - startIndex + 1 >= 3,
                "隧道至少 3 站，实际 " + (endIndex - startIndex + 1));
        System.out.println("TunnelPipeline span=" + startIndex + ".." + endIndex
                + " stations=" + (endIndex - startIndex + 1));
    }

    /** 反向对照：同一套代码，地形换成缓丘就不能产出隧道。 */
    @Test
    void gentleHillStillProducesNoTunnel() {
        int[] terrain = new int[STATIONS];
        java.util.Arrays.fill(terrain, BASE);
        double center = 10 + (60 - 1) / 2.0D;
        int[] gentle = new int[STATIONS];
        java.util.Arrays.fill(gentle, BASE);
        for (int i = 10; i < 70; i++) {
            double distance = Math.abs(i - center);
            double fraction = Math.max(0.0D, 1.0D - distance / (60 / 2.0D));
            gentle[i] = BASE + (int) Math.round(3 * fraction);
        }
        List<RoadSegmentPlacement> segments = segments(gentle);
        List<Integer> targetY = surfaceProfile(gentle);
        List<RoadSpan> spans = PathSpanExtractor.refineTunnelSpans(List.of(), segments, targetY, null, null);
        long tunnels = spans.stream().filter(span -> span != null && span.type() == SpanType.TUNNEL).count();
        assertEquals(0, tunnels, "缓丘不得产出隧道");
        assertFalse(spans.stream().anyMatch(span -> span != null && span.type() == SpanType.TUNNEL));
    }
}
