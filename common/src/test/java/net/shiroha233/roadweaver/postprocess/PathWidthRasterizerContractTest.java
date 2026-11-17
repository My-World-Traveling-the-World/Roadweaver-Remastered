/* 文件职责：用独立闭区间胶囊判据和精确归属夹具守住路宽、端帽、站序及交叉并列。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PathWidthRasterizerContractTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5, 6})
    void straightRoadMatchesTheClosedCapsuleIncludingNegativeCoordinatesAndCaps(int width) {
        var stations = List.of(p(-8, 60), p(-4, 61), p(0, 62), p(4, 63), p(8, 64));
        var road = PathPostProcessor.rasterizeStations(stations, width);
        assertEquals(stations.size(), road.size(), "剖面重建必须一个站点对应一段");
        var actual = new HashSet<BlockPos>();
        for (int i = 0; i < stations.size(); i++) {
            assertEquals(stations.get(i), road.get(i).middlePos());
            assertFalse(road.get(i).positions().isEmpty());
            actual.addAll(road.get(i).positions());
        }
        // 这份预期不调用生产距离函数：已知轴向直线，投影只需把X夹到[-8,8]。
        var expected = new HashSet<BlockPos>();
        for (int x = -12; x <= 12; x++) for (int z = -6; z <= 6; z++) {
            int dx = x - Math.max(-8, Math.min(8, x));
            if (dx * dx + z * z <= width * width / 4.0) expected.add(new BlockPos(x, 0, z));
        }
        assertFalse(expected.isEmpty());
        assertEquals(expected, actual, "偶数宽度边界列也按原闭区间保留");
        assertTrue(actual.contains(new BlockPos(-8 - width / 2, 0, 0)));
        assertTrue(actual.contains(new BlockPos(8 + width / 2, 0, 0)));
        assertFalse(actual.contains(new BlockPos(0, 0, width / 2 + 1)), "不得侵入紧邻的保护列");
    }

    @Test
    void midpointColumnsStayWithTheLaterStationAtHalfwayAndKeepTargetY() {
        var stations = List.of(p(0, 60), p(4, 64), p(8, 68));
        var road = PathPostProcessor.rasterizeStations(stations, 1);
        assertEquals(3, road.size());
        assertTrue(road.get(1).positions().contains(new BlockPos(2, 0, 0)));
        assertFalse(road.get(0).positions().contains(new BlockPos(2, 0, 0)));
        assertTrue(road.get(2).positions().contains(new BlockPos(6, 0, 0)));
        assertFalse(road.get(1).positions().contains(new BlockPos(6, 0, 0)));
        assertEquals(stations, road.stream().map(s -> s.middlePos()).toList());
        assertEquals(Set.of(0), road.stream().flatMap(s -> s.positions().stream()).map(BlockPos::getY).collect(java.util.stream.Collectors.toSet()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void overlappingPassesUseTheCurrentStationHintForEqualDistances(int hint) {
        var centers = List.of(p(0, 10), p(4, 20), p(0, 30));
        Map<Integer, Set<BlockPos>> blocks = new HashMap<>();
        for (int i = 0; i < 3; i++) blocks.put(i, new HashSet<>());
        PathPostProcessor.assignToNearestCenter(1, 0, centers, hint, blocks);
        assertEquals(Set.of(new BlockPos(1, 0, 0)), blocks.get(hint));
        assertEquals(1, blocks.values().stream().mapToInt(Set::size).sum());
    }

    @Test
    void repeatedStationsKeepOneToOneOutputAndAnAnchorForAnOtherwiseEmptyStation() {
        var stations = List.of(p(0, 60), p(0, 80));
        var road = PathPostProcessor.rasterizeStations(stations, 2);
        assertEquals(2, road.size());
        assertEquals(stations, road.stream().map(s -> s.middlePos()).toList());
        assertEquals(Set.of(new BlockPos(0, 0, 0)), Set.copyOf(road.get(1).positions()));
        assertEquals(Set.of(new BlockPos(-1, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 0, -1), new BlockPos(0, 0, 1), new BlockPos(0, 0, 0)), Set.copyOf(road.get(0).positions()));
    }

    @Test
    void missingOrSingleStationReturnsAnEmptyMutableProduct() {
        assertTrue(PathPostProcessor.rasterizeStations(null, 5).isEmpty());
        assertTrue(PathPostProcessor.rasterizeStations(List.of(), 5).isEmpty());
        var product = PathPostProcessor.rasterizeStations(List.of(p(0, 60)), 5);
        assertTrue(product.isEmpty());
        // 原入口交付ArrayList；等价搬迁不顺带改变调用者可追加的契约。
        assertDoesNotThrow(() -> product.add(new net.shiroha233.roadweaver.core.model.RoadSegmentPlacement(p(0, 60), new ArrayList<>())));
    }

    @Test
    void longRoadAboveIntegerCachePreservesEveryOrderedStationProduct() {
        List<BlockPos> centers = new ArrayList<>();
        List<SplineHelper.Vec2d> points = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            centers.add(p(i - 256, 64 + i % 7));
            points.add(new SplineHelper.Vec2d(i - 256, 0));
            distances.add((double)i);
        }
        var expected = LegacyCurveRasterReference.rasterizeSegments(points, centers, distances, 5);
        var actual = PathWidthRasterizer.rasterizeSegments(points, centers, distances, 5);
        // 短随机路线不会跨过127号站；性能改动必须在真正长路线逐站、按顺序对拍。
        assertEquals(512, expected.size());
        assertEquals(expected, actual);
        assertFalse(actual.get(511).positions().isEmpty());
    }

    private static BlockPos p(int x, int y) { return new BlockPos(x, y, 0); }
}
