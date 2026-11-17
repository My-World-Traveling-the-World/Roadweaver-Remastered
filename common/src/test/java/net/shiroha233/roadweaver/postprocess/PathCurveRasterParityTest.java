/* 文件职责：逐点、逐站和逐列对拍冻结算法，并独立约束桥头、急弯及交叉路线高度。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class PathCurveRasterParityTest {
    @ParameterizedTest
    @EnumSource(CurveMode.class)
    void seededCurvesPreserveEverySampleCenterAndOrderedColumn(CurveMode mode) {
        Random random = new Random(420621L);
        // 固定60条非空路线，包含负坐标、重合点、起伏和短桥掩码；不能靠空循环通过。
        for (int trial = 0; trial < 60; trial++) {
            var controls = new ArrayList<BlockPos>();
            int count = 2 + random.nextInt(7), x = -24, z = -12;
            for (int i = 0; i < count; i++) {
                x += random.nextInt(9); z += random.nextInt(13) - 6;
                controls.add(new BlockPos(x, 50 + random.nextInt(8), z));
            }
            boolean[] mask = trial % 5 == 0 ? null : new boolean[trial % 5 == 1 ? count - 1 : count];
            if (mask != null) for (int i = 0; i < mask.length; i++) mask[i] = random.nextBoolean();
            var before = List.copyOf(controls);
            boolean[] oldMask = mask == null ? null : mask.clone();
            var expected = LegacyCurveRasterReference.generateSplinePoints(controls, mask, mode);
            var actual = PathPostProcessor.generateSplinePoints(controls, mask, mode);
            assertFalse(expected.isEmpty());
            assertEquals(expected, actual, "样条浮点与点序逐项一致 trial=" + trial);
            int[] targets = controls.stream().mapToInt(BlockPos::getY).toArray();
            var expectedDistances = new ArrayList<Double>();
            var actualDistances = new ArrayList<Double>();
            var expectedCenters = LegacyCurveRasterReference.extractCenters(expected, controls, targets, expectedDistances);
            var actualCenters = PathPostProcessor.extractCenters(actual, controls, targets, actualDistances);
            assertFalse(expectedCenters.isEmpty());
            assertEquals(expectedCenters, actualCenters);
            assertEquals(expectedDistances, actualDistances, "站点里程也是分段归属输入，不能只比X/Z");
            int width = new int[]{1, 2, 5}[trial % 3];
            var oldRoad = LegacyCurveRasterReference.rasterizeSegments(expected, expectedCenters, expectedDistances, width);
            var road = PathPostProcessor.rasterizeSegments(actual, actualCenters, actualDistances, width);
            assertEquals(expectedCenters.size(), road.size());
            assertEquals(oldRoad, road, "每站中心、列归属及列表顺序均不得漂移 trial=" + trial);
            assertEquals(before, controls);
            assertArrayEquals(oldMask, mask);
            assertArrayEquals(before.stream().mapToInt(BlockPos::getY).toArray(), targets);
        }
    }

    @Test
    void gentleLandCurveActuallyUsesTheRequestedCurveMode() {
        var controls = List.of(p(0, 0), p(8, 1), p(16, 5), p(24, 5));
        var linear = PathPostProcessor.generateSplinePoints(controls, null, CurveMode.LINEAR);
        var catmull = PathPostProcessor.generateSplinePoints(controls, null, CurveMode.CATMULL_ROM);
        var bezier = PathPostProcessor.generateSplinePoints(controls, null, CurveMode.BEZIER_CASTELJAU);
        assertTrue(linear.size() > controls.size());
        assertEquals(linear.size(), catmull.size());
        assertEquals(linear.size(), bezier.size());
        assertNotEquals(linear, catmull, "有缓弯的陆地必须真的走样条，不能只断言采样非空");
        assertNotEquals(linear, bezier);
        for (int i = 0; i < catmull.size(); i++) {
            assertEquals(catmull.get(i).x(), bezier.get(i).x(), 1e-12);
            assertEquals(catmull.get(i).z(), bezier.get(i).z(), 1e-12);
        }
    }

    @Test
    void bridgeApproachOnBothSidesRemainsLinearWithoutFlatteningAllLand() {
        var controls = List.of(p(0, 0), p(8, 1), p(16, 3), p(24, 6),
                p(32, 8), p(40, 9), p(48, 9), p(56, 7));
        var mask = new boolean[]{false, false, false, true, false, false, false, false};
        var curved = PathPostProcessor.generateSplinePoints(controls, mask, CurveMode.CATMULL_ROM);
        var linear = PathPostProcessor.generateSplinePoints(controls, mask, CurveMode.LINEAR);
        assertEquals(linear.size(), curved.size());
        int offset = 0, alignedSamples = 0;
        for (int leg = 0; leg < controls.size() - 1; leg++) {
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(controls.get(leg).distSqr(controls.get(leg + 1))) * 4.0));
            if (leg >= 1 && leg <= 4) {
                for (int s = 0; s < steps; s++) assertEquals(linear.get(offset + s), curved.get(offset + s));
                alignedSamples += steps;
            }
            offset += steps;
        }
        assertTrue(alignedSamples > 100, "实际核验两条桥腿及前后各一条岸腿");
        assertNotEquals(linear, curved, "远处陆地仍保留缓弯");
        assertEquals(new Vec2d(56, 7), curved.getLast());
    }

    @Test
    void hairpinHasActualSamplesAndNeverLeavesTheTwoStraightLegs() {
        var controls = List.of(p(0, 0), p(10, 10), p(0, 20));
        var points = PathPostProcessor.generateSplinePoints(controls, null, CurveMode.CATMULL_ROM);
        assertTrue(points.size() > 100);
        assertEquals(new Vec2d(0, 0), points.getFirst());
        assertEquals(new Vec2d(0, 20), points.getLast());
        for (var point : points) {
            assertTrue(point.x() >= 0 && point.x() <= 10);
            assertEquals(point.x(), Math.min(point.z(), 20 - point.z()), 1e-12);
        }
    }

    @Test
    void elevatedCrossingKeepsTheHeightOfTheCurrentPass() {
        var raw = List.of(new BlockPos(0, 10, 0), new BlockPos(8, 18, 8),
                new BlockPos(0, 30, 8), new BlockPos(8, 38, 0));
        var samples = List.of(new Vec2d(0, 0), new Vec2d(4, 4), new Vec2d(8, 8),
                new Vec2d(0, 8), new Vec2d(8, 0), new Vec2d(4, 4));
        var distances = new ArrayList<Double>();
        var centers = PathPostProcessor.extractCenters(samples, raw, new int[]{10, 18, 30, 38}, distances);
        assertEquals(6, centers.size());
        assertEquals(centers.size(), distances.size());
        assertEquals(new BlockPos(4, 14, 4), centers.get(1));
        assertEquals(new BlockPos(4, 34, 4), centers.getLast(), "相同X/Z第二次经过不能投回低处的第一条腿");
    }

    @Test
    void samplingDensityStillUsesThreeDimensionalControlDistance() {
        var controls = List.of(new BlockPos(0, 60, 0), new BlockPos(1, 68, 0));
        var points = PathPostProcessor.generateSplinePoints(controls, null, CurveMode.LINEAR);
        // 搬迁保留原3D步数；改成水平距离会从34点掉到5点，是另一个需要单独验收的行为改动。
        assertEquals(34, points.size());
        assertEquals(new Vec2d(0, 0), points.getFirst());
        assertEquals(new Vec2d(1, 0), points.getLast());
    }

    @Test
    void repeatedRoundedCentersAreSkippedButTheLastDistinctEndpointRemains() {
        var samples = List.of(new Vec2d(0, 0), new Vec2d(0.1, 0),
                new Vec2d(0.2, 0), new Vec2d(1, 0), new Vec2d(2, 0));
        var distances = new ArrayList<Double>();
        var centers = PathPostProcessor.extractCenters(samples, List.of(p(0, 0), p(2, 0)), new int[]{64, 64}, distances);
        assertEquals(List.of(p(0, 0), p(1, 0), p(2, 0)), centers);
        assertEquals(List.of(0.0, 1.0, 2.0), distances);
    }

    private static BlockPos p(int x, int z) { return new BlockPos(x, 64, z); }
}
