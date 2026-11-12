package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.9.036：{@link BridgeShareService} 端点反查的收紧兜底。
 *
 * <p><b>背景（真机证据链）</b>：0.9.034 那一局（存档 `202611090942`）初始批只有 4 条路，其中
 * `(32,-64)->(-624,-944)` 的桥 `(205,65,-165)→(270,65,-175)` 与 `(32,-64)->(992,544)` 的桥
 * `(207,48,-164)→(269,52,-175)` 都在 merge（09:53:04）之**前**生成（09:52:42 / 09:51:52），
 * 两座桥 XZ 差约 2 格、航向一致、重叠 60+ 站 ⇒ 「这批路没有两座桥」和「几何判定没过」都该被排除，
 * 但日志是 `bridgeShareCandidates=0`。剩下的唯一可能是：{@code RoadSpan} **只存 BlockPos、没有站
 * 索引**，而 {@code mergeRoads} 会重建站序列，Y 漂一格整座桥就被静默丢掉。</p>
 *
 * <p>兜底的安全性要求「比精确匹配更严」：<b>同一 XZ 上恰好只有一个站</b>且 ΔY ≤ 4 才认。
 * 本测试钉住这条 —— 尤其是<b>同 XZ 多站必须放弃</b>（道路自交/折返时按 XZ 认会错配到另一站，
 * 那会把一座桥的站区间指到完全无关的路段上）。</p>
 */
final class BridgeShareEndpointFallbackTest {

    private static RoadSegmentPlacement at(int x, int y, int z) {
        BlockPos middle = new BlockPos(x, y, z);
        return new RoadSegmentPlacement(middle, List.of(middle));
    }

    private static List<RoadSegmentPlacement> line(int startX, int y, int count) {
        List<RoadSegmentPlacement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(at(startX + i, y, 0));
        return out;
    }

    @Test
    void exactXzWithSmallDriftIsRecovered() {
        List<RoadSegmentPlacement> stations = line(0, 64, 10);
        // 站序列在 y=64，span 端点记的是 y=66（合并重排纵断面漂了 2 格）
        assertEquals(3, BridgeShareService.stationIndexNearXz(stations, new BlockPos(3, 66, 0)));
    }

    @Test
    void driftBeyondThresholdIsRejected() {
        List<RoadSegmentPlacement> stations = line(0, 64, 10);
        assertEquals(-1, BridgeShareService.stationIndexNearXz(stations, new BlockPos(3, 69, 0)));
    }

    @Test
    void ambiguousSameXzIsRejected() {
        // 同一条路在同一個 XZ 上出现两次（自交/折返），哪怕 ΔY=0 也不许认 —— 否则会错配。
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        stations.add(at(5, 64, 0));
        stations.add(at(6, 64, 0));
        stations.add(at(5, 64, 0));
        assertEquals(-1, BridgeShareService.stationIndexNearXz(stations, new BlockPos(5, 64, 0)));
    }

    @Test
    void noXzMatchIsRejected() {
        List<RoadSegmentPlacement> stations = line(0, 64, 10);
        assertEquals(-1, BridgeShareService.stationIndexNearXz(stations, new BlockPos(3, 64, 7)));
    }

    @Test
    void nullSafety() {
        assertEquals(-1, BridgeShareService.stationIndexNearXz(null, new BlockPos(0, 64, 0)));
        assertEquals(-1, BridgeShareService.stationIndexNearXz(line(0, 64, 3), null));
    }

    @Test
    void statsStillReportPreconditions() {
        BridgeShareService.Stats single = BridgeShareService.applyInPlace(
                Arrays.asList((RoadData) null), 24);
        assertEquals(1, single.roads());
        assertEquals(0, single.bridges());
        assertEquals(0, single.spanEndpointsRecovered());
        assertEquals(0, single.candidates());
    }
}
