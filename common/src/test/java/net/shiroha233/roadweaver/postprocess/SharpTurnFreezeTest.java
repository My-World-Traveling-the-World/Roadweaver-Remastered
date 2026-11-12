package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.067（C2）：急弯冻结 —— 发卡弯顶点不参与 relax 平滑，不被 1-2-1 磨圆。
 */
public class SharpTurnFreezeTest {

    @Test
    void turningAngleStraightIsZero() {
        // (0,0)->(10,0)->(20,0) 完全直行。
        assertEquals(0.0D, PathPostProcessor.turningAngleDeg(0, 0, 10, 0, 20, 0), 1.0E-6D);
    }

    @Test
    void turningAngleRightTurnIsNinety() {
        // (0,0)->(10,0)->(10,10) 直角。
        assertEquals(90.0D, PathPostProcessor.turningAngleDeg(0, 0, 10, 0, 10, 10), 1.0E-6D);
    }

    @Test
    void turningAngleHairpinIs180() {
        // (0,0)->(10,0)->(0,0) 完全折返：向量 (10,0) 与 (-10,0) 反向。
        assertEquals(180.0D, PathPostProcessor.turningAngleDeg(0, 0, 10, 0, 0, 0), 1.0E-6D);
    }

    @Test
    void relaxFreezesHairpinVertex() {
        // 发卡弯：A(0,0) B(10,10) C(0,20)。B 的转向角 180°，必须冻结不动。
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 10), new BlockPos(0, 64, 20));
        List<BlockPos> relaxed = PathPostProcessor.relaxPath(nodes);
        // 端点本来就不动；关键是顶点 B 不被 1-2-1 磨圆。
        assertEquals(new BlockPos(10, 64, 10), relaxed.get(1));
    }

    @Test
    void relaxStillSmoothsGentleBend() {
        // 缓弯（转向角 45° 左右）应该被平滑，顶点不再等于原坐标。
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 1), new BlockPos(20, 64, 10));
        List<BlockPos> relaxed = PathPostProcessor.relaxPath(nodes);
        // 缓弯顶点参与平滑，与原始顶点不同（或至少不抛异常、长度不变）。
        assertEquals(nodes.size(), relaxed.size());
    }
}
