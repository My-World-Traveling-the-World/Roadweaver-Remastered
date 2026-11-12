package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后处理的特征测试（characterization test）。
 *
 * <h2>为什么要有这个文件</h2>
 * <p>{@code PathPostProcessor} 直接决定道路形状，但此前
 * {@code simplifyPath / relaxPath / generateSplinePoints / straightenBridgeRuns /
 * densifyControlPoints} <b>零测试覆盖</b> —— 改任何一行都没有尺子能证明没改坏。</p>
 *
 * <p>本类的作用是<b>把当前行为钉住</b>（不是断言「理想行为」，而是断言「现在就是这样」），
 * 这样后续拆分类、调整实现时一旦行为漂移会立刻红灯。若某个断言确实需要改，
 * 必须同时在 commit message 里说明「为什么允许行为变化」。</p>
 *
 * <h2>已踩过的坑（写测试时印证）</h2>
 * <ul>
 *   <li>{@code relax} 的位移上限是<b>严格不变量</b>：取整后的实际位移不得超 cap
 *       （0.9.019 修过一次，取整会让 2.0 的 cap 实际跑到 2.24）。</li>
 *   <li>{@code relax} <b>完全不动 Y</b>，纵断面由 RoadGradePlanner 单独负责。</li>
 *   <li>{@code simplifyPath} 的容差跟步长走：{@code clamp(0.5×step, 2, 4)}，
 *       所以 step=2 与 step=8 对同一条锯齿给出不同点数。</li>
 *   <li>{@code densifyControlPoints} 在<b>桥界那条边整段不密化</b>，否则会挪动桥界下标。</li>
 * </ul>
 */
class PathPostProcessorCharacterizationTest {

    // ── 构造工具 ────────────────────────────────────────────────

    /** 沿 +X 方向的等距直线，Y 固定。 */
    private static List<BlockPos> straightLine(int count, int step, int y) {
        List<BlockPos> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(new BlockPos(i * step, y, 0));
        return out;
    }

    /** 沿 +X 走的锯齿：在 Z 轴上 ±amplitude 交替，每 {@code step} 格一个点。 */
    private static List<BlockPos> zigzag(int count, int step, int amplitude, int y) {
        List<BlockPos> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new BlockPos(i * step, y, (i % 2 == 0) ? amplitude : -amplitude));
        }
        return out;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        return Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
    }

    // ── deriveGridStep ─────────────────────────────────────────

    @Test
    void deriveGridStepReadsTheSmallestSegment() {
        assertEquals(8, PathPostProcessor.deriveGridStep(straightLine(5, 8, 64)));
        assertEquals(2, PathPostProcessor.deriveGridStep(straightLine(5, 2, 64)));
        assertEquals(4, PathPostProcessor.deriveGridStep(straightLine(5, 4, 64)));
    }

    @Test
    void deriveGridStepFallsBackToEightOnDegenerateInput() {
        assertEquals(8, PathPostProcessor.deriveGridStep(null));
        assertEquals(8, PathPostProcessor.deriveGridStep(new ArrayList<>()));
        // 单点、全重复点都取不到有效段长
        List<BlockPos> same = new ArrayList<>();
        same.add(new BlockPos(3, 70, 3));
        same.add(new BlockPos(3, 70, 3));
        assertEquals(8, PathPostProcessor.deriveGridStep(same));
    }

    // ── simplifyPath ───────────────────────────────────────────

    @Test
    void simplifyPathCollapsesACollinearRunToItsEndpoints() {
        List<BlockPos> line = straightLine(20, 8, 70);
        List<BlockPos> simplified = PathPostProcessor.simplifyPath(line, 8);
        assertEquals(2, simplified.size(), "共线直线必须塌缩成两个端点");
        assertEquals(line.get(0), simplified.get(0));
        assertEquals(line.get(line.size() - 1), simplified.get(1));
    }

    @Test
    void simplifyPathKeepsBothEndpointsForAnyInput() {
        List<BlockPos> input = zigzag(24, 8, 3, 70);
        List<BlockPos> out = PathPostProcessor.simplifyPath(input, 8);
        assertNotNull(out);
        assertTrue(out.size() >= 2);
        assertEquals(input.get(0), out.get(0), "起点必须保留");
        assertEquals(input.get(input.size() - 1), out.get(out.size() - 1), "终点必须保留");
    }

    @Test
    void simplifyPathToleranceFollowsTheGridStep() {
        // 容差 = clamp(0.5*step, 2, 4)：step=2 时容差 2，step=8 时容差 4
        List<BlockPos> input = zigzag(40, 2, 3, 70);
        int fine = PathPostProcessor.simplifyPath(input, 2).size();
        int coarse = PathPostProcessor.simplifyPath(input, 8).size();
        assertTrue(fine >= coarse,
                "小步长必须保留不少于大步长的点数（容差更紧）：fine=" + fine + " coarse=" + coarse);
    }

    @Test
    void simplifyPathHandlesDegenerateInput() {
        assertEquals(0, PathPostProcessor.simplifyPath(new ArrayList<>(), 8).size());
        assertEquals(1, PathPostProcessor.simplifyPath(
                List.of(new BlockPos(1, 64, 2)), 8).size());
        // 两点输入原样返回（不做简化）
        List<BlockPos> two = List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        assertEquals(2, PathPostProcessor.simplifyPath(two, 8).size());
    }

    // ── relax ──────────────────────────────────────────────────

    @Test
    void relaxLeavesAStraightLineByteIdentical() {
        List<BlockPos> line = straightLine(12, 8, 70);
        List<BlockPos> out = PathPostProcessor.relaxPath(line);
        assertEquals(line, out, "共线直线经过 1-2-1 平滑必须逐位不变");
    }

    @Test
    void relaxNeverChangesY() {
        List<BlockPos> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) input.add(new BlockPos(i * 8, 60 + i, (i % 2 == 0) ? 3 : -3));
        List<BlockPos> out = PathPostProcessor.relaxPath(input);
        assertEquals(input.size(), out.size());
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).getY(), out.get(i).getY(),
                    "relax 不得改 Y（纵断面归 RoadGradePlanner）index=" + i);
        }
    }

    @Test
    void relaxRespectsTheStrictDisplacementCap() {
        List<BlockPos> input = zigzag(30, 8, 8, 70);
        List<BlockPos> out = PathPostProcessor.relaxPath(input);
        assertEquals(input.size(), out.size());
        for (int i = 0; i < input.size(); i++) {
            double moved = horizontalDistance(input.get(i), out.get(i));
            assertTrue(moved <= PathPostProcessor.RELAX_MAX_DISPLACEMENT + 1e-9,
                    "第 " + i + " 点位移 " + moved + " 超出严格上限 "
                            + PathPostProcessor.RELAX_MAX_DISPLACEMENT);
        }
    }

    @Test
    void relaxKeepsEndpointsFrozen() {
        List<BlockPos> input = zigzag(20, 8, 5, 70);
        List<BlockPos> out = PathPostProcessor.relaxPath(input);
        assertEquals(input.get(0), out.get(0), "起点不得移动");
        assertEquals(input.get(input.size() - 1), out.get(out.size() - 1), "终点不得移动");
    }

    @Test
    void relaxFreezesASharpTurnVertex() {
        // 90° 直角：转向角 >= SHARP_TURN_FREEZE_DEG(60)，顶点不得被磨圆
        List<BlockPos> hairpin = new ArrayList<>();
        hairpin.add(new BlockPos(0, 70, 0));
        hairpin.add(new BlockPos(8, 70, 0));
        hairpin.add(new BlockPos(8, 70, 8));
        hairpin.add(new BlockPos(16, 70, 8));
        hairpin.add(new BlockPos(16, 70, 16));
        List<BlockPos> out = PathPostProcessor.relaxPath(hairpin);
        assertEquals(hairpin.size(), out.size());
        for (int i = 1; i < hairpin.size() - 1; i++) {
            assertEquals(hairpin.get(i), out.get(i),
                    "转向角 >= 60 度的顶点必须冻结 index=" + i);
        }
    }

    @Test
    void relaxFreezesBridgeStationsAndNeverTouchesTheirY() {
        List<BlockPos> input = zigzag(16, 8, 4, 70);
        boolean[] mask = new boolean[input.size()];
        for (int i = 4; i <= 10; i++) mask[i] = true;
        List<BlockPos> out = PathPostProcessor.relaxPathSkippingBridge(input, mask);
        assertEquals(input.size(), out.size());
        for (int i = 0; i < input.size(); i++) {
            if (mask[i]) {
                assertEquals(input.get(i), out.get(i), "桥段点必须冻结 index=" + i);
            }
        }
    }

    // ── straightenBridgeRuns ───────────────────────────────────

    @Test
    void straightenBridgeRunsInterpolatesTheBridgeRunBetweenItsNeighbours() {
        List<BlockPos> input = new ArrayList<>();
        input.add(new BlockPos(0, 70, 0));
        input.add(new BlockPos(8, 70, 5));    // 桥段起点（外侧偏 5）
        input.add(new BlockPos(16, 70, -4));
        input.add(new BlockPos(24, 70, 6));
        input.add(new BlockPos(32, 70, 0));   // 桥段出口
        boolean[] mask = new boolean[]{false, true, true, true, false};
        List<BlockPos> out = PathPostProcessor.straightenBridgeRuns(input, mask);
        assertEquals(input.size(), out.size());
        // 入口 (0,0) → 出口 (32,0)，桥段三个点必须落在该直线上（Z=0）
        for (int i = 1; i <= 3; i++) {
            assertEquals(0, out.get(i).getZ(), "桥段第 " + i + " 点必须落在入口-出口直线上");
        }
        // Y 必须原样保留
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).getY(), out.get(i).getY(), "拉直不得改 Y index=" + i);
        }
        // 非桥点不得改变
        assertEquals(input.get(0), out.get(0));
        assertEquals(input.get(4), out.get(4));
    }

    @Test
    void straightenBridgeRunsIsANoOpWithoutAnyBridge() {
        List<BlockPos> input = zigzag(10, 8, 5, 70);
        boolean[] mask = new boolean[input.size()];
        assertEquals(input, PathPostProcessor.straightenBridgeRuns(input, mask),
                "没有桥时必须逐位不变");
    }

    @Test
    void straightenBridgeRunsRejectsMismatchedMask() {
        List<BlockPos> input = straightLine(6, 8, 70);
        assertEquals(input, PathPostProcessor.straightenBridgeRuns(input, new boolean[3]),
                "掩码长度不符时必须原样返回");
        assertEquals(input, PathPostProcessor.straightenBridgeRuns(input, null));
    }

    // ── densifyControlPoints ───────────────────────────────────

    @Test
    void densifyControlPointsSplitsALongSegment() {
        List<BlockPos> ctrl = List.of(new BlockPos(0, 70, 0), new BlockPos(80, 70, 0));
        boolean[] mask = new boolean[]{false, false};
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(ctrl, mask, 8);
        // 80 / 8 = 10 段 ⇒ 中间插 9 个点，合计 11
        assertEquals(11, densified.points().size());
        assertEquals(densified.points().size(), densified.mask().length,
                "密化后掩码长度必须与点数一致");
    }

    @Test
    void densifyControlPointsInsertsOnlyCollinearPoints() {
        List<BlockPos> ctrl = List.of(new BlockPos(0, 70, 0), new BlockPos(80, 70, 0));
        boolean[] mask = new boolean[]{false, false};
        List<BlockPos> points = PathPostProcessor.densifyControlPoints(ctrl, mask, 8).points();
        for (BlockPos p : points) {
            assertEquals(0, p.getZ(), "共线密化只能在同一条直线段内部插点");
            assertEquals(0, p.getX() % 8, "插入点必须等距落在网格上");
        }
        assertEquals(ctrl.get(0), points.get(0));
        assertEquals(ctrl.get(1), points.get(points.size() - 1));
    }

    @Test
    void densifyControlPointsLeavesAlreadyDensePathUntouched() {
        List<BlockPos> ctrl = straightLine(6, 8, 70);
        boolean[] mask = new boolean[6];
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(ctrl, mask, 8);
        assertEquals(ctrl, densified.points(),
                "每段本来就 <= 步距的规则路必须逐位不变");
    }

    @Test
    void densifyControlPointsNeverDensifiesABridgeBoundaryEdge() {
        // 三点，掩码 false -> true -> true：第一条边跨桥界，整段不密化
        List<BlockPos> ctrl = List.of(
                new BlockPos(0, 70, 0),
                new BlockPos(80, 70, 0),
                new BlockPos(160, 70, 0));
        boolean[] mask = new boolean[]{false, true, true};
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(ctrl, mask, 8);
        // 第一条边(0→1)不密化；第二条边(1→2)密化，80/8=10 段插 9 个 ⇒ 3 + 9 = 12
        assertEquals(12, densified.points().size(),
                "桥界那条边必须整段不密化，否则桥界下标会漂");
        assertEquals(new BlockPos(0, 70, 0), densified.points().get(0));
        assertEquals(new BlockPos(80, 70, 0), densified.points().get(1));
    }

    // ── generateSplinePoints ───────────────────────────────────

    @Test
    void generateSplinePointsKeepsACollinearPolylineCollinear() {
        List<BlockPos> ctrl = List.of(
                new BlockPos(0, 70, 0),
                new BlockPos(40, 70, 0),
                new BlockPos(80, 70, 0),
                new BlockPos(120, 70, 0));
        boolean[] mask = new boolean[4];
        List<Vec2d> points = PathPostProcessor.generateSplinePoints(ctrl, mask, CurveMode.CATMULL_ROM);
        assertTrue(points.size() >= ctrl.size(), "样条采样点不得少于控制点");
        for (Vec2d v : points) {
            assertEquals(0.0, v.z(), 1e-6,
                    "共线控制点的 Catmull-Rom 必须仍在那条直线上（共线三点穿过同一直线）");
        }
    }

    @Test
    void generateSplinePointsStartsAndEndsAtTheControlPoints() {
        List<BlockPos> ctrl = List.of(
                new BlockPos(0, 70, 0),
                new BlockPos(32, 70, 16),
                new BlockPos(64, 70, 0));
        boolean[] mask = new boolean[3];
        List<Vec2d> points = PathPostProcessor.generateSplinePoints(ctrl, mask, CurveMode.CATMULL_ROM);
        assertEquals(ctrl.get(0).getX(), points.get(0).x(), 1e-6);
        assertEquals(ctrl.get(0).getZ(), points.get(0).z(), 1e-6);
        Vec2d last = points.get(points.size() - 1);
        assertEquals(ctrl.get(2).getX(), last.x(), 1e-6);
        assertEquals(ctrl.get(2).getZ(), last.z(), 1e-6);
    }
}
