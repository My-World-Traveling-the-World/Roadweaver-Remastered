package net.shiroha233.roadweaver.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.features.path.pathlogic.core.CulvertPlanner;
import net.shiroha233.roadweaver.features.path.pathlogic.core.RoadQualityMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * D1 的 GameTest 半边：在**真实服务器进程**里跑路面质量断言。
 *
 * <p>纯 JUnit 已经覆盖了同样的函数（{@code RoadQualityMetricsTest} / {@code CulvertPlannerTest}），
 * 这里多出来的价值只有一个但很关键：这些类在**打了 Fabric 补丁、重映射过的 Minecraft 类**下运行。
 * 一旦哪天指标或涵洞逻辑用到了只在开发环境存在的东西，单测仍然全绿而游戏里直接崩 —— 这个测试
 * 就是那道闸。
 *
 * <p>不写方块、不生成区块、不依赖结构模板：所有输入都是内存里的 {@link RoadData}。
 */
public final class RoadQualityGameTests implements FabricGameTest {

    private static RoadData straightRoad(int stations, int height) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            BlockPos middle = new BlockPos(i, height, 0);
            segments.add(new RoadSegmentPlacement(middle, List.of(
                    middle.offset(0, 0, -1), middle, middle.offset(0, 0, 1))));
            targetY.add(height);
        }
        return new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aCleanRoadMeasuresZero(GameTestHelper helper) {
        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(straightRoad(24, 64));

        if (report.stations() != 24) throw new AssertionError("stations=" + report.stations());
        if (report.holes() != 0) throw new AssertionError("holes=" + report.holes());
        if (report.continuityBreaks() != 0) throw new AssertionError("breaks=" + report.continuityBreaks());
        if (report.gradeP95() != 0.0D) throw new AssertionError("gradeP95=" + report.gradeP95());
        if (report.directionGradeBias() != 0.0D) throw new AssertionError("dirBias=" + report.directionGradeBias());
        if (report.culvertRuns() != 0) throw new AssertionError("culverts=" + report.culvertRuns());
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aStepAndAHoleAreBothReported(GameTestHelper helper) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BlockPos middle = new BlockPos(i, i == 6 ? 68 : 64, 0);
            segments.add(new RoadSegmentPlacement(middle, i == 3 ? List.of() : List.of(middle)));
            targetY.add(middle.getY());
        }
        RoadData road = new RoadData(3, 0, List.of(), List.of(), segments, List.of(), targetY,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);

        RoadQualityMetrics.Report report = RoadQualityMetrics.evaluate(road);

        if (report.holes() != 1) throw new AssertionError("holes=" + report.holes());
        if (report.continuityBreaks() < 2) throw new AssertionError("breaks=" + report.continuityBreaks());
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aNarrowStreamIsCarriedOnACulvertSpan(GameTestHelper helper) {
        RoadData road = straightRoad(20, 63);
        CulvertPlanner.Probe water = (x, z) -> x >= 8 && x <= 10
                ? CulvertPlanner.Water.wet(64, 63)
                : CulvertPlanner.Water.dry();

        CulvertPlanner.Result result = CulvertPlanner.cross(road, 6, 2, water);

        if (!result.changed()) throw new AssertionError("a 3-station stream must be culverted");
        if (result.runs() != 1) throw new AssertionError("runs=" + result.runs());
        int deck = result.road().targetY().get(9);
        if (deck != 64 + CulvertPlanner.DECK_ABOVE_WATER) throw new AssertionError("deck=" + deck);
        boolean hasCulvertSpan = false;
        for (RoadSpan span : result.road().spans()) {
            if (span.type() == SpanType.CULVERT) hasCulvertSpan = true;
        }
        if (!hasCulvertSpan) throw new AssertionError("no CULVERT span was recorded");
        helper.succeed();
    }
}
