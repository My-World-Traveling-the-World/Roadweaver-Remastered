package net.shiroha233.roadweaver.pathlogic.bridge;

import net.shiroha233.roadweaver.core.model.RoadData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.9.035：{@link BridgeShareService.Stats} 的计数口径回归。
 *
 * <p>起因：0.9.034 真机 `bridgeShareCandidates=0`，而当时两条路的桥明明并排在同一片水上
 * （`(205,65,-165)→(270,65,-175)` 与 `(207,48,-164)→(269,52,-175)`，XZ 差约 2 格）。只看旧那
 * 4 个计数，**分不清**是「这批路里根本没有两座桥」「桥端点在该路站序列里找不到被丢掉」还是
 * 「几何判定没过」。所以 {@code Stats} 增加了 {@code roads} / {@code bridges} /
 * {@code spanEndpointsMissing} 三个前置计数。</p>
 *
 * <p>本测试钉住前置计数的语义：不足两条路、或收集到的桥不足两座，都必须如实报数，
 * 而不是一律回 0 —— 否则下一局日志还是看不出卡在哪一步。</p>
 */
final class BridgeShareServiceStatsTest {

    @Test
    void nullInputReportsZeroRoads() {
        BridgeShareService.Stats stats = BridgeShareService.applyInPlace(null, 24);
        assertEquals(0, stats.roads());
        assertEquals(0, stats.bridges());
        assertEquals(0, stats.candidates());
    }

    @Test
    void singleRoadStillReportsItsOwnCount() {
        List<RoadData> one = Arrays.asList((RoadData) null);
        BridgeShareService.Stats stats = BridgeShareService.applyInPlace(one, 24);
        assertEquals(1, stats.roads(), "不足两条路也要如实报数，不能回 0");
        assertEquals(0, stats.bridges());
        assertEquals(0, stats.candidates());
    }

    @Test
    void emptyListReportsZero() {
        BridgeShareService.Stats stats = BridgeShareService.applyInPlace(List.of(), 24);
        assertEquals(0, stats.roads());
        assertEquals(0, stats.candidates());
    }

    @Test
    void zeroRadiusIsNotInterpretedAsUnlimited() {
        // radius = 0 时几何判定必然不过，但仍应如实报出 roads/bridges 前置计数。
        List<RoadData> two = Arrays.asList(null, null);
        BridgeShareService.Stats stats = BridgeShareService.applyInPlace(two, 0);
        assertEquals(2, stats.roads());
        assertEquals(0, stats.bridges(), "两条 null 路没有桥");
    }
}
