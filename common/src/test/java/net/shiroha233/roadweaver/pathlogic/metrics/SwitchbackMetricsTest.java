package net.shiroha233.roadweaver.pathlogic.metrics;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.061：盘山路专用度量。三条人工构造的样例各自钉住一条判据：
 *
 * <ul>
 *   <li>「之字盘山路」→ {@code hairpins >= 2}（横向幅度够大 + 两条腿够长）；</li>
 *   <li>「直上直下」→ {@code hairpins == 0} 且 {@code straightClimbMax} 很大
 *       （这正是「斜着硬爬」的直接证据）；</li>
 *   <li>「±1 格锯齿」→ 绝不能被判成发卡弯；</li>
 *   <li>额外两条：横向幅度只有 5 格（&lt; 3×路宽）的大波浪不算；横向幅度 30 格但腿只有 5 站
 *       的也不算 —— 分别隔离「幅度」与「腿长」两条判据。</li>
 * </ul>
 */
final class SwitchbackMetricsTest {

    /**
     * 三角波「之字盘山路」：横向位移在 {@code legStations} 站内线性涨到 {@code amplitude}，
     * 再线性回到 0，重复 {@code ramps} 次；Z 一直前进，Y 缓坡（坡度不是本组测试的判据）。
     *
     * @return {@code {xs, zs, ys}}
     */
    private static double[][] snake(int legStations, double amplitude, int ramps) {
        List<double[]> points = new ArrayList<>();
        double z = 0;
        for (int r = 0; r < ramps; r++) {
            for (int i = 0; i < legStations; i++) {
                points.add(new double[]{amplitude * i / legStations, z++});
            }
            for (int i = 0; i < legStations; i++) {
                points.add(new double[]{amplitude * (legStations - i) / (double) legStations, z++});
            }
        }
        int n = points.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points.get(i)[0];
            zs[i] = points.get(i)[1];
            ys[i] = 64 + i / 8;
        }
        return new double[][]{xs, zs, ys};
    }

    @Test
    void aZigzagMountainRoadCountsAtLeastTwoHairpins() {
        double[][] road = snake(12, 12, 2);
        SwitchbackMetrics.Report report =
                SwitchbackMetrics.evaluate(road[0], road[1], road[2], 3);

        assertTrue(report.hairpinRadiusP50() > 0.0D && Double.isFinite(report.hairpinRadiusP50()),
                "发卡弯要有有限的三站外接圆半径，实际 " + report.hairpinRadiusP50());
        assertTrue(report.hairpinLegP50() >= SwitchbackMetrics.MIN_HAIRPIN_LEG_STATIONS,
                "回头线腿长应当在 8 站以上，实际 " + report.hairpinLegP50());
        assertTrue(report.format().contains("hairpins="), report.format());
        assertTrue(report.format().contains("straightClimbMax="), report.format());
    }

    @Test
    void aStraightClimbHasNoHairpinAndALongStraightClimb() {
        int stations = 60;
        double[] xs = new double[stations];
        double[] zs = new double[stations];
        double[] ys = new double[stations];
        for (int i = 0; i < stations; i++) {
            xs[i] = 0;
            zs[i] = i;
            ys[i] = 64 + i;      // 每站抬 1 格：一条没有横向位移的直坡
        }

        SwitchbackMetrics.Report report = SwitchbackMetrics.evaluate(xs, zs, ys, 3);

        assertEquals(0, report.hairpins(), "没有横向翻转就不该有发卡弯");
        assertEquals(stations - 1, report.straightClimbMax(),
                "直上直下必须把「斜着硬爬」的长度量出来（59 步）");
        assertEquals(100.0D, report.gradeP50(), 1e-9, "1 格/1 格 = 100%");
        assertEquals(100.0D, report.gradeP95(), 1e-9);
    }

    @Test
    void aOneBlockSawtoothIsNeverAHairpin() {
        double[][] road = snake(1, 1.0D, 10);   // x = 0,1,0,1,... 横向幅度只有 1 格
        SwitchbackMetrics.Report report =
                SwitchbackMetrics.evaluate(road[0], road[1], road[2], 3);

        assertEquals(0, report.hairpins(),
                "±1 格锯齿的横向幅度远小于 3 倍路宽，绝不能被判成盘山路，实际 "
                        + report.hairpins());
        assertTrue(report.straightClimbMax() <= 2,
                "锯齿的直段应当非常短，实际 " + report.straightClimbMax());
    }

    @Test
    void aShallowWaveIsRejectedByTheLateralAmplitudeAlone() {
        // 腿够长（15 站 >= 8），但横向只挪 5 格 < 3×路宽=9：只有「幅度」这一条能挡下它。
        double[][] road = snake(15, 5.0D, 2);
        SwitchbackMetrics.Report report =
                SwitchbackMetrics.evaluate(road[0], road[1], road[2], 3);

        assertEquals(0, report.hairpins(),
                "横向幅度不足 3 倍路宽的缓弯不是发卡弯，实际 " + report.hairpins());
    }

    @Test
    void aWideTurnWithShortLegsIsRejectedByTheLegLengthAlone() {
        // 横向挪 30 格（幅度判据通过），但两条腿只有 5 站 < 8：只有「腿长」这一条能挡下它。
        double[][] road = snake(5, 30.0D, 2);
        SwitchbackMetrics.Report report =
                SwitchbackMetrics.evaluate(road[0], road[1], road[2], 3);

        assertEquals(0, report.hairpins(),
                "两条腿各 5 站的原地折返太短，不算回头线，实际 " + report.hairpins());
    }

    @Test
    void circumradiusMatchesTheThreePointFormulaAndCollinearIsNaN() {
        double[] xs = {0.0D, 1.0D, 0.0D};
        double[] zs = {0.0D, 0.0D, 1.0D};
        assertEquals(Math.sqrt(2.0D) / 2.0D, SwitchbackMetrics.circumradius(xs, zs, 0, 1, 2), 1e-9D,
                "直角三角形 (0,0)-(1,0)-(0,1) 的外接圆半径 = sqrt(2)/2");

        double[] coX = {0.0D, 1.0D, 2.0D};
        double[] coZ = {0.0D, 0.0D, 0.0D};
        assertTrue(Double.isNaN(SwitchbackMetrics.circumradius(coX, coZ, 0, 1, 2)),
                "三点共线没有外接圆，必须返回 NaN（不能当成 0 半径去污染 P50）");
        assertTrue(Double.isNaN(SwitchbackMetrics.circumradius(coX, coZ, 0, 1, 9)),
                "越界下标也必须返回 NaN 而不是抛异常");
    }

    @Test
    void theRoadDataAdapterProducesTheSameNumbersAsThePureFunction() {
        double[][] road = snake(12, 12, 2);
        int n = road[0].length;
        List<RoadSegmentPlacement> segments = new ArrayList<>(n);
        List<Integer> targetY = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos middle = new BlockPos((int) Math.round(road[0][i]), (int) road[2][i],
                    (int) Math.round(road[1][i]));
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
            targetY.add((int) road[2][i]);
        }
        RoadData data = new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        SwitchbackMetrics.Report viaRoad = SwitchbackMetrics.fromRoad(data);
        SwitchbackMetrics.Report viaArrays =
                SwitchbackMetrics.evaluate(road[0], road[1], road[2], 3);

        assertEquals(viaArrays.hairpins(), viaRoad.hairpins(), "适配器不许改变判据");
        assertEquals(viaArrays.straightClimbMax(), viaRoad.straightClimbMax());
        assertEquals(viaArrays.hairpinLegP50(), viaRoad.hairpinLegP50(), 1e-9D);
    }

    @Test
    void aBrokenOrEmptyRoadIsNotACrash() {
        assertEquals(0, SwitchbackMetrics.evaluate(null, null, null, 3).hairpins());
        assertEquals(0, SwitchbackMetrics.evaluate(new double[]{0}, new double[]{0}, new double[]{0}, 3)
                .hairpins());
        assertEquals(0, SwitchbackMetrics.fromRoad(null).hairpins());
        assertEquals(0.0D, SwitchbackMetrics.evaluate(new double[]{0}, new double[]{0},
                new double[]{0}, 3).gradeP50(), 1e-9D);
    }

    /** {@code route QUALITY} 旁边那一行必须真的发出来，且字段名与用户口径一致。 */
    @Test
    void theQualityLineEmitsTheSwitchbackSummary() {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BlockPos middle = new BlockPos(i, 64 + i, 0);
            segments.add(new RoadSegmentPlacement(middle, List.of(middle)));
            targetY.add(middle.getY());
        }
        RoadData data = new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report quality = RoadQualityMetrics.record(data, "(0,0,0)", "(7,0,0)");
        SwitchbackMetrics.Report switchback = SwitchbackMetrics.fromRoad(data);

        assertEquals(8, quality.stations());
        assertEquals(0, switchback.hairpins());
        assertTrue(switchback.format().contains("hairpinRadiusP50="), switchback.format());
        assertTrue(switchback.format().contains("gradeP95="), switchback.format());
        assertTrue(switchback.format().contains("straightClimbMax=7"), switchback.format());
    }
}
