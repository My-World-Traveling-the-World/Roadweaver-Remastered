package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.034：把 R-03「计价档与建造档错位」这条缺陷的**边界**钉住。
 *
 * <p>过水的价目分三档（{@code waterCrossingCost}）：涵洞 → 窄水（{@code run × narrowPer}）→
 * 宽水（{@code base + per × run}）。而桥到底建不建，由
 * {@code PathSpanExtractor.crossingTypeForWidth} 按 {@code ROAD_CROSSING_MAX_WIDTH_BLOCKS}
 * 这个<b>另一个</b>常量决定。</p>
 *
 * <p>两个边界不一致，因此存在一条「错位带」：{@code ROAD_CROSSING_MAX_WIDTH_BLOCKS < run ≤
 * narrowWaterMaxWidth}。落在带内的过水**选线时按窄水小桥计价**（默认 20 × run，13 格只要 260），
 * 但**建造时已经按 BRIDGE 处理**（26~40 倍的实际工程）。0.9.028 那局实测 63 次过水里就有 1 次
 * 落在带内（{@code widthBlocks=18}）。</p>
 *
 * <p>本测试**不修改任何行为**，只把「带的位置」和「带内确实是窄水价」钉成断言：
 * 谁要动这两个阈值中的任何一个，这里会先红，逼迫同时改另一边或显式接受错位带变宽/变窄。</p>
 */
final class WaterTierAlignmentTest {

    private static final double CULVERT_PER = 2.0D;
    private static final int CULVERT_MAX_STATIONS = 6;
    private static final int CULVERT_MAX_DEPTH = 2;
    private static final double WIDE_BASE = 600.0D;
    private static final double WIDE_PER = 80.0D;
    private static final int NARROW_MAX_WIDTH = 20;
    private static final double NARROW_PER = 20.0D;

    private static double cost(int run, int depth) {
        return AccurateGradeRepairPlanner.waterCrossingCost(run, depth, WIDE_BASE, WIDE_PER,
                CULVERT_PER, CULVERT_MAX_STATIONS, CULVERT_MAX_DEPTH, NARROW_MAX_WIDTH, NARROW_PER);
    }

    @Test
    void buildBoundaryIsStillTwelve() {
        // 建造档：width > 12 就建 BRIDGE。这个数字一改，「错位带」的位置就整体平移。
        assertEquals(12, PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS);
    }

    @Test
    void mismatchBandIsExactlyThirteenThroughTwenty() {
        int buildBoundary = PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS;
        assertTrue(NARROW_MAX_WIDTH > buildBoundary, "narrow tier must extend past the build boundary");

        // 带内 (buildBoundary, NARROW_MAX_WIDTH]：计价仍是窄水（run × narrowPer）。
        for (int run = buildBoundary + 1; run <= NARROW_MAX_WIDTH; run++) {
            assertEquals(run * NARROW_PER, cost(run, 1), 1e-9,
                    "run=" + run + " is inside the mismatch band and must still be priced as narrow water");
        }

        // 负控：带外两侧都不该再是窄水价 —— 否则上面的断言就是同义反复。
        assertEquals(CULVERT_MAX_STATIONS * CULVERT_PER, cost(CULVERT_MAX_STATIONS, 1), 1e-9);
        assertTrue(cost(NARROW_MAX_WIDTH + 1, 1) > (NARROW_MAX_WIDTH + 1) * NARROW_PER,
                "run beyond the narrow tier must switch to the wide price, which is far more expensive");
        assertEquals(WIDE_BASE + WIDE_PER * (NARROW_MAX_WIDTH + 1), cost(NARROW_MAX_WIDTH + 1, 1), 1e-9);
    }

    @Test
    void theBandIsWherePricedCheapButBuiltExpensive() {
        // 带内最贵的一格（20 格宽）按窄水只花 400；一出带（21 格）立刻跳到 600 + 80×21 = 2280。
        double insideBand = cost(NARROW_MAX_WIDTH, 1);
        double justOutside = cost(NARROW_MAX_WIDTH + 1, 1);
        assertEquals(400.0D, insideBand, 1e-9);
        assertEquals(2280.0D, justOutside, 1e-9);
        assertTrue(justOutside / insideBand > 5.0D,
                "the 20 -> 21 cliff is what makes the band attractive to the router in the first place");
    }
}
