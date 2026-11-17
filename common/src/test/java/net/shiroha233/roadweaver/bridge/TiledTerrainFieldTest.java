/* 文件职责：把瓦片接缝、三通道、驱逐与故障作为真实读入行为逐项验证。 */
package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.metric.GridLayout;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TiledTerrainFieldTest {
    @Test
    void singleCellGradientNeedsNoSamplingButHeightAndWaterStillUseRealChannels() {
        var calls = new AtomicInteger();
        var field = new TiledTerrainField(new GridLayout(-9, -7, 1, 1, 8), request -> {
            calls.incrementAndGet(); return sampled(request);
        });
        assertArrayEquals(new double[]{0.0, 0.0}, field.gradient(-9, -7), 0.0);
        assertEquals(0, calls.get(), "常量零梯度不需要为了快路径额外读入中心列");
        assertEquals(height(-9, -7), field.height(-9, -7));
        assertEquals(7, field.waterProxy(-9, -7));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int scenario) {
        int columns = scenario % 3 == 1 ? 1 : 67;
        int rows = scenario % 3 == 2 ? 1 : 71;
        var layout = new GridLayout(-517, -391, columns, rows, scenario < 3 ? 1 : 8);
        var request = new AccurateHeightGridRequest(layout.minX(), layout.minZ(), columns, rows, layout.step());
        var whole = sampled(request);
        var expected = new GridTerrainField(layout.minX(), layout.minZ(), columns, rows, layout.step(), whole.motionBlocking());
        var calls = new AtomicInteger();
        var actual = new TiledTerrainField(layout, 16, 3, tile -> {
            calls.incrementAndGet();
            assertTrue(tile.width() <= 16 && tile.height() <= 16);
            assertEquals(0, ((long) tile.minX() - layout.minX()) % layout.step());
            assertEquals(0, ((long) tile.minZ() - layout.minZ()) % layout.step());
            assertTrue((long) tile.minX() + (tile.width() - 1L) * tile.step() <= layout.maxX());
            assertTrue((long) tile.minZ() + (tile.height() - 1L) * tile.step() <= layout.maxZ());
            return sampled(tile);
        });
        assertEquals(0, calls.get(), "构造不能触发整场采样");
        assertEquals(expected.minX(), actual.minX()); assertEquals(expected.maxX(), actual.maxX());
        assertEquals(expected.minZ(), actual.minZ()); assertEquals(expected.maxZ(), actual.maxZ());
        for (int iz = 0; iz < rows; iz++) for (int ix = 0; ix < columns; ix++) {
            int x = request.blockX(ix + iz * columns), z = request.blockZ(ix + iz * columns);
            assertEquals(expected.height(x, z), actual.height(x, z));
            assertArrayEquals(expected.gradient(x, z), actual.gradient(x, z), 0.0, "接缝必须用全域邻点");
            int gap = whole.worldSurface()[ix + iz * columns] - whole.oceanFloor()[ix + iz * columns];
            assertEquals(gap > 0 ? gap : -1, actual.waterProxy(x, z));
            // 方块坐标吸附继续使用旧 float 舍入，水证据却只接受精确对齐列。
            int offX = Math.min(layout.maxX(), x + layout.step() / 2);
            int offZ = Math.min(layout.maxZ(), z + layout.step() / 2);
            assertEquals(expected.height(offX, offZ), actual.height(offX, offZ));
            assertArrayEquals(expected.gradient(offX, offZ), actual.gradient(offX, offZ), 0.0);
            if (offX != x || offZ != z) assertEquals(-1, actual.waterProxy(offX, offZ));
        }
        assertTrue(calls.get() > 0, "先确认确实读入过，避免空遍历放过整个适配器");
        int before = calls.get();
        assertThrows(IllegalArgumentException.class, () -> actual.height(layout.minX() - 1, layout.minZ()));
        assertThrows(IllegalArgumentException.class, () -> actual.gradient(layout.maxX() + 1, layout.minZ()));
        assertEquals(-1, actual.waterProxy(layout.minX() - 1, layout.minZ()));
        assertEquals(before, calls.get());
    }

    @Test
    void leastRecentlyUsedTilesAreEvictedAndRereadWithoutChangingHeight() {
        var calls = new ArrayList<Integer>();
        var terrain = new TiledTerrainField(new GridLayout(0, 0, 13, 9, 1), 4, 2, request -> {
            calls.add(request.minX());
            return sampled(request);
        });
        for (int x : new int[]{0, 4, 0, 8, 4}) assertEquals(height(x, 0), terrain.height(x, 0));
        assertEquals(List.of(0, 4, 8, 4), calls, "访问 0 必须续期，4 被逐出后才会重新读入");
        assertEquals(new TiledTerrainField.Stats(4, 4, 64, 2, 2, 384), terrain.stats());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void failedNullAndMismatchedTilesAreNotPublishedAndCanBeRetried(int fault) {
        var calls = new AtomicInteger();
        var field = new TiledTerrainField(new GridLayout(-9, -7, 9, 11, 8), 4, 2, request -> {
            if (calls.incrementAndGet() == 1) {
                if (fault == 0) throw new IllegalStateException("injected sampling fault");
                if (fault == 1) return null;
                return sampled(new AccurateHeightGridRequest(request.minX() + 1, request.minZ(),
                        request.width(), request.height(), request.step()));
            }
            return sampled(request);
        });
        assertThrows(RuntimeException.class, () -> field.height(-9, -7));
        assertEquals(height(-9, -7), field.height(-9, -7));
        assertEquals(height(-1, 1), field.height(-1, 1));
        assertEquals(2, calls.get(), "失败不填零、不标成功，重试成功后同块复用");
        assertEquals(new TiledTerrainField.Stats(2, 1, 16, 0, 1, 192), field.stats());
    }

    @Test
    void sharedTileIsPublishedOnceToConcurrentReaders() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var terrain = new TiledTerrainField(new GridLayout(0, 0, 8, 8, 1), request -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return sampled(request);
        });
        var executor = Executors.newFixedThreadPool(4);
        var secondThread = new AtomicReference<Thread>();
        var thirdThread = new AtomicReference<Thread>();
        try {
            var first = executor.submit(() -> terrain.height(1, 2));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> { secondThread.set(Thread.currentThread()); return terrain.gradient(3, 4); });
            var third = executor.submit(() -> { thirdThread.set(Thread.currentThread()); return terrain.waterProxy(1, 2); });
            // 等到两个读者都进入瓦片调用再放行，不能凭线程创建顺序或立即放行侥幸通过。
            awaitTileWait(secondThread); awaitTileWait(thirdThread);
            assertEquals(1, calls.get(), "并发缺页不得同时启动三个采样批次");
            release.countDown();
            assertEquals(height(1, 2), first.get(5, TimeUnit.SECONDS));
            var reference = new GridTerrainField(0, 0, 8, 8, sampled(new AccurateHeightGridRequest(0, 0, 8, 8, 1)).motionBlocking());
            assertArrayEquals(reference.gradient(3, 4), second.get(5, TimeUnit.SECONDS), 0.0);
            assertTrue(third.get(5, TimeUnit.SECONDS) > 0);
            assertEquals(1, calls.get());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static void awaitTileWait(AtomicReference<Thread> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Thread thread = reference.get();
            if (thread != null && (thread.getState() == Thread.State.BLOCKED
                    || thread.getState() == Thread.State.TIMED_WAITING)
                    && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(TiledTerrainField.class.getName()) && frame.getMethodName().equals("tile"))) return;
            Thread.sleep(1);
        }
        fail("并发读者没有进入真实瓦片等待路径");
    }

    @Test
    void invalidLayoutsAreRejectedBeforeAnySamplerCall() {
        var calls = new AtomicInteger();
        TiledTerrainField.GridSampler sampler = request -> { calls.incrementAndGet(); return sampled(request); };
        assertThrows(IllegalArgumentException.class,
                () -> new TiledTerrainField(new GridLayout(0, 0, 65536, 65536, 1), sampler));
        assertThrows(IllegalArgumentException.class,
                () -> new TiledTerrainField(new GridLayout(0, 0, 2, 2, 1), 0, 1, sampler));
        assertThrows(IllegalArgumentException.class,
                () -> new TiledTerrainField(new GridLayout(0, 0, 2, 2, 1), 1, 0, sampler));
        assertEquals(0, calls.get());
    }

    @Test
    void extremeOriginAndStepKeepWaterAndGradientConsistentWithDenseSurface() {
        var layout = new GridLayout(Integer.MIN_VALUE, Integer.MIN_VALUE, 3, 3, Integer.MAX_VALUE);
        var request = new AccurateHeightGridRequest(layout.minX(), layout.minZ(), 3, 3, layout.step());
        var heights = sampled(request);
        var dense = new SampledTerrainField.SurfaceGrid(new GridTerrainField(layout.minX(), layout.minZ(),
                3, 3, layout.step(), heights.motionBlocking()), heights);
        var tile = new TiledTerrainField(layout, 2, 1, TiledTerrainFieldTest::sampled);
        for (int i = 0; i < 9; i++) {
            int x = request.blockX(i), z = request.blockZ(i);
            assertEquals(dense.terrain().height(x, z), tile.height(x, z));
            assertArrayEquals(dense.terrain().gradient(x, z), tile.gradient(x, z), 0.0);
            assertEquals(dense.waterProxy(x, z), tile.waterProxy(x, z));
        }
    }

    @Test
    void planningFactoryKeepsSmallBatchAndSelectsBoundedLazyTilesAtTheSolverThreshold() {
        var calls = new ArrayList<AccurateHeightGridRequest>();
        TiledTerrainField.GridSampler sampler = request -> { calls.add(request); return sampled(request); };
        var small = SampledTerrainField.planningSurface(new GridLayout(-91, -71, 67, 43, 8), sampler);
        assertInstanceOf(GridTerrainField.class, small.terrain());
        assertEquals(List.of(new AccurateHeightGridRequest(-91, -71, 67, 43, 8)), calls);
        assertEquals(height(-91, -71), small.terrain().height(-91, -71));
        calls.clear();
        var large = SampledTerrainField.planningSurface(new GridLayout(0, 0, 4160, 1024, 8), sampler);
        assertInstanceOf(TiledTerrainField.class, large.terrain());
        assertTrue(calls.isEmpty());
        for (int i = 0; i <= TiledTerrainField.MAX_TILES; i++) {
            int x = i * TiledTerrainField.TILE_SIDE * 8;
            assertEquals(height(x, 0), large.terrain().height(x, 0));
        }
        assertEquals(height(0, 0), large.terrain().height(0, 0));
        assertEquals(66, calls.size(), "默认缓存也必须有界，重新访问第一块必须发生一次重读");
        assertTrue(calls.stream().allMatch(r -> r.sampleCount() <= 4096));
    }

    // 三通道刻意不相等，并包含干列，防止换错高度图仍因平地夹具而过关。
    static AccurateHeightGrid sampled(AccurateHeightGridRequest request) {
        int[] motion = new int[request.sampleCount()], surface = new int[motion.length], floor = new int[motion.length];
        for (int i = 0; i < motion.length; i++) {
            int x = request.blockX(i), z = request.blockZ(i);
            motion[i] = height(x, z);
            floor[i] = motion[i] - 5;
            surface[i] = floor[i] + (Math.floorMod((long) x - z, 5) == 0 ? 0 : 7);
        }
        return new AccurateHeightGrid(request, surface, floor, motion);
    }

    static int height(int x, int z) { return 64 + (int) Math.floorMod(31L * x + 17L * z, 37); }
}
