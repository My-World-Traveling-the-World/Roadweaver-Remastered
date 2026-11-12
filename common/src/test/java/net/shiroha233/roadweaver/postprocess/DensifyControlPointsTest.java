package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.019（O-03）：样条前的控制点弧长密化。
 *
 * <p>这一条是 0.9.016 自己引入缺陷的收尾：DP 让控制点疏密不均，而 Catmull-Rom 按
 * 「每段 t∈[0,1]」均匀采样，于是长直段接转弯处会先冲出去再折返（分册实测
 * 9 格 @L=200 / 53 格 @L=800）。密化只插共线点，所以折线几何必须逐位不变。</p>
 */
final class DensifyControlPointsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 原本就是 8 格密的规则路：必须一条判断都不触发、返回同一个列表实例。 */
    @Test
    void alreadyDensePathIsUntouched() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k <= 20; k++) {
            points.add(new BlockPos(k * 8, 64, 0));
        }
        boolean[] mask = new boolean[points.size()];
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(points, mask, 8);

        assertSame(points, densified.points(), "规则路必须原样返回，不得重建列表");
        assertSame(mask, densified.mask(), "规则路必须原样返回掩码");
    }

    /** 长直段被插点，且插入点必须严格落在原折线上（共线）。 */
    @Test
    void longLegIsDensifiedCollinearly() {
        List<BlockPos> points = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(80, 64, 0),
                new BlockPos(80, 64, 80));
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(points, null, 8);

        assertTrue(densified.points().size() > points.size(), "80 格的边必须被插点");
        assertEquals(points.get(0), densified.points().get(0), "起点不变");
        assertEquals(points.get(points.size() - 1),
                densified.points().get(densified.points().size() - 1), "终点不变");
        for (BlockPos p : densified.points()) {
            // 原折线是「x 轴 0..80」再接「z 轴 0..80」，所有点都必须落在这两条线上。
            boolean onFirstLeg = p.getX() >= 0 && p.getX() <= 80 && p.getZ() == 0;
            boolean onSecondLeg = p.getX() == 80 && p.getZ() >= 0 && p.getZ() <= 80;
            assertTrue(onFirstLeg || onSecondLeg, "插入点必须落在原折线上，实测 " + p);
        }
    }

    /** 每段长度都不超过步距时不插点。 */
    @Test
    void legsWithinStepAreKept() {
        List<BlockPos> points = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(12, 64, 4));
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(points, null, 8);
        assertSame(points, densified.points());
    }

    /**
     * 桥与岸的<b>分界边</b>整段不密化，且扩写后的掩码必须逐点对齐。
     *
     * <p>桥段<b>内部</b>可以密化（桥面是直的，插共线点几何无害，而且通常正是最长的那条边），
     * 关键是分界必须仍然落在控制点上 —— 否则「哪一段是桥面」会整体漂移。</p>
     */
    @Test
    void bridgeBoundaryIsNeverDensified() {
        List<BlockPos> points = new ArrayList<>();
        for (int k = 0; k <= 6; k++) {
            points.add(new BlockPos(k * 80, 64, 0));
        }
        boolean[] mask = new boolean[points.size()];
        mask[3] = true;
        mask[4] = true;
        PathPostProcessor.Densified densified = PathPostProcessor.densifyControlPoints(points, mask, 8);

        int bridgeFirst = indexOf(densified.points(), points.get(3));
        int bridgeLast = indexOf(densified.points(), points.get(4));
        assertTrue(bridgeFirst > 0 && bridgeLast > bridgeFirst, "桥段两端必须仍然存在且有序");
        for (int i = bridgeFirst; i <= bridgeLast; i++) {
            assertTrue(densified.mask()[i], "桥段内第 " + i + " 点的掩码必须为 true（扩写必须对齐）");
        }
        assertTrue(!densified.mask()[bridgeFirst - 1], "桥段前一站必须是岸（分界不得漂移）");
        assertTrue(!densified.mask()[bridgeLast + 1], "桥段后一站必须是岸（分界不得漂移）");
        assertTrue(densified.points().size() > points.size(), "长边仍应被密化");
    }

    private static int indexOf(List<BlockPos> points, BlockPos target) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).equals(target)) return i;
        }
        return -1;
    }

    /** A* 原始路径的最小相邻距离就是步距。 */
    @Test
    void deriveGridStepReadsTheSmallestAStarStep() {
        List<BlockPos> path = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(16, 64, 8),
                new BlockPos(24, 64, 16));
        assertEquals(8, PathPostProcessor.deriveGridStep(path));

        List<BlockPos> four = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 4));
        assertEquals(4, PathPostProcessor.deriveGridStep(four));
        assertEquals(8, PathPostProcessor.deriveGridStep(null));
        assertEquals(8, PathPostProcessor.deriveGridStep(List.of()));
    }
}
