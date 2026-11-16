/* 文件职责：用无需大数组的边界夹具钉住大范围规划分配前的尺寸与载荷计算。 */
package net.shiroha233.roadweaver.metric;

import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GridLayoutTest {
    @Test
    void radius4096EstimateUsesLongBeforeMultiplicationWithoutAllocatingTheGrid() {
        var grid = GridLayout.coveringBlocks(-65536, -65536, 131072, 131072, 8);
        var work = DenseGridWorkload.estimate(grid, false, false, 30);
        assertEquals(16384, grid.columns());
        assertEquals(268435456L, grid.cellCount());
        assertEquals(3221225472L, work.samplingHeightBytes());
        assertEquals(1073741824L, work.retainedHeightBytes());
        assertEquals(6509559808L, work.solverFixedBytes());
        assertEquals(256L, work.targetBytesUpperBound());
        assertEquals(7583301888L, work.retainedAndSolverPayloadBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 8, 31})
    void coveringKeepsTheOriginalSampleAlignmentAndFloorRule(int step) {
        var grid = GridLayout.coveringBlocks(-103, -79, 131, 67, step);
        assertEquals(130 / step + 1, grid.columns());
        assertEquals(66 / step + 1, grid.rows());
        assertEquals(-103 + (130 / step) * step, grid.maxX());
        assertEquals(-79 + (66 / step) * step, grid.maxZ());
        assertEquals((long) grid.columns() * grid.rows(), grid.arrayLength());
        assertEquals(8L * ((grid.cellCount() + 63) / 64), grid.bitsetPayloadBytes());
    }

    @Test
    void paddingRetainsTheWholeCoreAndDoesNotChangeTheSamplingOrigin() {
        var grid = SampledTerrainField.planningLayout(-65536, -65536, 65536, 65536, 64, 8);
        assertEquals(-65600, grid.minX());
        assertEquals(65600, grid.maxX());
        assertEquals(-65600, grid.minZ());
        assertEquals(65600, grid.maxZ());
        assertEquals(16401, grid.columns());
        assertEquals(268992801L, grid.cellCount());
        assertEquals(DenseGridWorkload.estimate(grid, true, false, 30), SampledTerrainField.estimate(grid, true, 30));
    }

    @Test
    void shadowAndBlockedEdgesAreSeparatePayloadsRatherThanOneHeapPeakClaim() {
        var grid = new GridLayout(0, 0, 65, 1, 1);
        var normal = DenseGridWorkload.estimate(grid, false, false, 0);
        var shadow = DenseGridWorkload.estimate(grid, true, true, 0);
        assertEquals(780, shadow.retainedHeightBytes());
        assertEquals(520, shadow.retainedHeightBytes() - normal.retainedHeightBytes());
        assertEquals(65, shadow.solverFixedBytes() - normal.solverFixedBytes());
        assertEquals(0, normal.targetBytesUpperBound());
        assertThrows(IllegalArgumentException.class, () -> DenseGridWorkload.estimate(grid, false, false, -1));
    }

    @Test
    void negativeOriginAllowsLargeIntermediateProductsWhenFinalCoordinatesAreValid() {
        // 三列跨越零点：末列合法，列偏移本身超过 int；不能把中间溢出当成世界越界。
        var grid = new GridLayout(Integer.MIN_VALUE, Integer.MIN_VALUE, 3, 3, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE - 1, grid.maxX());
        assertEquals(4294967295L, grid.blockWidth());
        assertEquals(9, grid.arrayLength());
        assertEquals(2, grid.snapX(Integer.MAX_VALUE - 1));
        var request = new AccurateHeightGridRequest(Integer.MIN_VALUE, Integer.MIN_VALUE, 3, 3, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE - 1, request.blockX(8));
        assertEquals(Integer.MAX_VALUE - 1, request.blockZ(8));
        assertThrows(IndexOutOfBoundsException.class, () -> request.blockX(9));
    }

    @Test
    void rejectsWorldCoordinateOverflowBeforeSamplingOrAllocating() {
        assertThrows(ArithmeticException.class, () -> new GridLayout(Integer.MAX_VALUE, 0, 2, 1, 1));
        assertThrows(ArithmeticException.class, () -> new GridLayout(0, Integer.MAX_VALUE, 1, 2, 1));
        assertThrows(ArithmeticException.class, () -> GridLayout.aroundBounds(Integer.MIN_VALUE, 0, 10, 10, 64, 8));
        assertThrows(ArithmeticException.class, () -> GridLayout.aroundBounds(0, 0, Integer.MAX_VALUE, 10, 64, 8));
        assertThrows(ArithmeticException.class, () -> GridLayout.aroundBounds(0, Integer.MIN_VALUE, 10, 10, 64, 8));
        assertThrows(ArithmeticException.class, () -> GridLayout.aroundBounds(0, 0, 10, Integer.MAX_VALUE, 64, 8));
        assertThrows(ArithmeticException.class, () -> GridLayout.coveringBlocks(1, 0, Long.MAX_VALUE, 1, 1));
        assertThrows(ArithmeticException.class, () -> new AccurateHeightGridRequest(Integer.MAX_VALUE, 0, 2, 1, 8));
        assertThrows(ArithmeticException.class, () -> new AccurateHeightGridRequest(0, Integer.MAX_VALUE, 1, 2, 8));
    }

    @Test
    void cellCountCannotWrapIntoAnAcceptedEmptyOrTinyHeightArray() {
        var grid = new GridLayout(0, 0, 65536, 65536, 1);
        assertEquals(4294967296L, grid.cellCount());
        var failure = assertThrows(IllegalArgumentException.class, grid::arrayLength);
        assertTrue(failure.getMessage().contains("cells=4294967296"));
        assertThrows(IllegalArgumentException.class, () -> new GridTerrainField(0, 0, 65536, 65536, new int[0]));
        AtomicInteger reads = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> GridTerrainField.fromFunction(0, 0, 65536, 65536,
                (x, z) -> { reads.incrementAndGet(); return 0; }));
        assertEquals(0, reads.get());
        assertThrows(IllegalArgumentException.class, () -> SampledTerrainField.of(0, 0, 65536, 65536,
                (x, z, w, h) -> { reads.incrementAndGet(); return new int[0]; }));
        assertEquals(0, reads.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void allSolverEntrypointsRejectOversizedExternalFieldsBeforeQueryingTheMetric(int mode) {
        AtomicInteger queries = new AtomicInteger();
        TerrainField terrain = new TerrainField() {
            public int minX() { return 0; }
            public int minZ() { return 0; }
            public int maxX() { return 65535; }
            public int maxZ() { return 65535; }
            public int height(int x, int z) { queries.incrementAndGet(); throw new AssertionError("read before validation"); }
        };
        // 世界边界前置检查不只保护高度读取，还要早于硬障碍查询。
        ObstacleField obstacles = new ObstacleField() {
            public double potential(int x, int z) { queries.incrementAndGet(); throw new AssertionError("potential before validation"); }
            public boolean impassable(int x, int z) { queries.incrementAndGet(); throw new AssertionError("obstacle before validation"); }
        };
        var solver = new FastMarchingSolver(FinslerMetric.of(terrain, obstacles));
        assertThrows(IllegalArgumentException.class, () -> {
            if (mode == 0) solver.solve(0, 0);
            else if (mode == 1) solver.solve(0, 0, 1, 1);
            else solver.solveReachingAll(0, 0, new int[]{1}, new int[]{1});
        });
        assertEquals(0, queries.get());
        assertThrows(IllegalArgumentException.class, () -> ArrivalTimeField.unreachable(0, 0, 65536, 65536, 1,
                FinslerMetric.of(terrain, ObstacleField.NONE)));
    }

    @Test
    void smallExtremeCoordinateFieldsStillProduceReachableSnapshots() {
        var terrain = new GridTerrainField(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 2, 3, 3, new int[9]);
        var field = new FastMarchingSolver(FinslerMetric.of(terrain, ObstacleField.NONE))
                .solve(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 2);
        assertTrue(field.reachable(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(3, field.width());
        assertEquals(3, field.depth());
        assertFalse(field.pathTo(Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty());
    }

    @Test
    void invalidDimensionsAndUnrepresentablePayloadsFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new GridLayout(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout(0, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> GridLayout.coveringBlocks(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> GridLayout.aroundBounds(1, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GridLayout.aroundBounds(0, 0, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GridLayout(0, 0, 1, 1, 1).payloadBytes(-1));
        assertThrows(ArithmeticException.class,
                () -> new GridLayout(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1).payloadBytes(8));
    }
}
