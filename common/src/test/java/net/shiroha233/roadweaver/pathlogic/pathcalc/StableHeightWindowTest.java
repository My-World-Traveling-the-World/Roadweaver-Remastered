package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 稳定纯读口与冻结动态算法在静态世界逐值对拍；动态读次数由原测试继续约束。 */
class StableHeightWindowTest {
    @Test void prefixMeansMatchFrozenAcrossChunksNegativeHeightsAndRadii() {
        var random = new Random(48129);
        for (int trial = 0; trial < 100; trial++) {
            var positions = new ArrayList<BlockPos>();
            for (int i = 0; i < 100; i++) positions.add(new BlockPos(
                    random.nextInt(64) - 40, random.nextInt(120) - 60, random.nextInt(32) - 24));
            var chunk = new ChunkPos(-1, -1);
            for (int radius : new int[]{-1, 0, 1, 4, 30, 120}) {
                var old = FrozenHeightProfileService.build(world(), positions, chunk, radius, false, 1, List.of());
                assertArrayEquals(old.smoothedY(), StableHeightWindow.build(positions, chunk, radius,
                        StableHeightWindowTest::height));
            }
        }
    }

    @Test void repeatedColumnsAreReadOnceAndTheWeightedStationMeanIsPreserved() {
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < 4096; i++) positions.add(new BlockPos(-16 + i % 16, 60, -1));
        var reads = new AtomicInteger();
        int[] actual = StableHeightWindow.build(positions, new ChunkPos(-1, -1), 8,
                (x, z) -> { reads.incrementAndGet(); return height(x, z); });
        var expected = FrozenHeightProfileService.build(world(), positions, new ChunkPos(-1, -1),
                8, false, 1, List.of());
        assertEquals(16, reads.get());
        assertArrayEquals(expected.smoothedY(), actual);
    }

    @Test void outsideChunkAndEmptyInputsNeverReadHeights() {
        var positions = List.of(new BlockPos(16, -60, 0), new BlockPos(17, -59, 0));
        assertArrayEquals(new int[]{-60, -59}, StableHeightWindow.build(positions, new ChunkPos(0, 0), 10,
                (x, z) -> { throw new AssertionError("不能读取邻块"); }));
        assertEquals(0, StableHeightWindow.build(List.of(), new ChunkPos(0, 0), 10,
                (x, z) -> { throw new AssertionError(); }).length);
    }

    @Test void windowsWithoutEligibleNeighborsKeepTheirOriginalStationHeight() {
        var points = List.of(new BlockPos(0, 90, 0), new BlockPos(100, 91, 0), new BlockPos(101, 92, 0));
        assertArrayEquals(new int[]{height(0, 0), 91, 92},
                StableHeightWindow.build(points, new ChunkPos(0, 0), 0, StableHeightWindowTest::height));
    }

    @Test void stableProductionProfileKeepsBothSlopePassesAndClamps() {
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i < 100; i++) positions.add(new BlockPos(-24 + i % 40, i - 80, -1));
        for (int step : new int[]{-1, 0, 1, 3, 8, 20}) for (boolean slope : new boolean[]{false, true}) {
            var expected = FrozenHeightProfileService.build(world(), positions, new ChunkPos(-1, -1),
                    4, slope, step, List.of());
            var actual = HeightProfileService.buildStable(positions, new ChunkPos(-1, -1),
                    4, slope, step, StableHeightWindowTest::height);
            assertFalse(actual.usePersisted());
            assertArrayEquals(expected.smoothedY(), actual.smoothedY());
        }
    }

    private static int height(int x, int z) { return -60 + Math.floorMod(x * 7 + z * 11, 100); }
    private static WorldGenLevel world() {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (p, method, args) -> {
                    if (method.getName().equals("getHeight")) return height((int) args[1], (int) args[2]);
                    throw new AssertionError(method.getName());
                });
    }
}
