package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.068（C3）：急弯段用直线代替 Catmull-Rom，发卡弯顶点不被样条外冲。
 */
public class SharpTurnSplineTest {

    @Test
    void hairpinSplineStaysWithinControlPoints() {
        // 发卡弯：A(0,0) B(10,10) C(0,20)。B 转向角 90° > 60° ⇒ 相邻段用 lerp，
        // 样条点必须落在 A..C 的包围盒内（不被 CR 外冲甩出）。
        List<BlockPos> ctrl = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 10), new BlockPos(0, 64, 20));
        List<Vec2d> pts = PathPostProcessor.generateSplinePoints(ctrl, null, SplineHelper.CurveMode.CATMULL_ROM);
        double minX = 0, maxX = 10, minZ = 0, maxZ = 20;
        for (Vec2d p : pts) {
            assertTrue(p.x() >= minX - 0.5 && p.x() <= maxX + 0.5, "x out of bounds: " + p.x());
            assertTrue(p.z() >= minZ - 0.5 && p.z() <= maxZ + 0.5, "z out of bounds: " + p.z());
        }
    }

    @Test
    void gentleCurveStillUsesSpline() {
        // 缓弯：转向角 < 60°，仍走 Catmull-Rom（会产生平滑曲线，不崩溃）。
        List<BlockPos> ctrl = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 1), new BlockPos(20, 64, 10));
        List<Vec2d> pts = PathPostProcessor.generateSplinePoints(ctrl, null, SplineHelper.CurveMode.CATMULL_ROM);
        assertTrue(pts.size() > 2);
    }
}
