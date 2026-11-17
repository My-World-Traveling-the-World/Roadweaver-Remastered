/* 文件职责：直接验证缺样回落及世界读边界，防止拆分后重新读取未加载区块。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetTerrainInputsTest {
    @Test
    void completeBatchIncludesCentersAndNeverReadsWorld() {
        var centers = List.of(p(-2), p(0), p(2));
        var segments = centers.stream().map(p -> new RoadSegmentPlacement(p, List.of(p))).toList();
        AtomicInteger calls = new AtomicInteger();
        var input = TargetTerrainInputs.sample(segments, centers, 1, positions -> {
            calls.incrementAndGet();
            assertEquals(List.of(p(-2), p(0), p(2), p(-2), p(0), p(2)), positions);
            return Map.of(key(-2), sample(60), key(0), sample(80), key(2), sample(70));
        }, AccurateHeightSample::worldSurfaceWg, p -> { throw new AssertionError("unexpected world read"); });
        assertEquals(1, calls.get());
        assertArrayEquals(new int[]{60, 80, 70}, input.terrainY());
        assertArrayEquals(new int[]{60, 80, 70}, input.footprintSupport());
        assertArrayEquals(new int[]{70, 70, 75}, input.base());
        assertEquals(3, input.footprintSamples().size());
    }

    @Test
    void missingCenterUsesWidthSupportAndMissingWidthUsesSampledCenter() {
        var segments = List.of(new RoadSegmentPlacement(p(0), List.of(p(-1), p(1))),
                new RoadSegmentPlacement(p(4), List.of(p(3), p(5))));
        List<BlockPos> reads = new ArrayList<>();
        var input = TargetTerrainInputs.sample(segments, List.of(p(0), p(4)), 0,
                ignored -> Map.of(key(-1), sample(72), key(1), sample(74), key(4), sample(81)),
                AccurateHeightSample::worldSurfaceWg, p -> { reads.add(p); return 99; });
        // 中心缺样仍保持原回落读取，但不得用扁平端点Y=0覆盖有效宽度样本。
        assertEquals(List.of(p(0)), reads);
        assertArrayEquals(new int[]{72, 81}, input.terrainY());
        assertArrayEquals(new int[]{72, 81}, input.footprintSupport());
        assertArrayEquals(new int[]{72, 81}, input.base());
    }

    @Test
    void emptyFootprintRetainsSampledCenterAndDoesNotInventWidthSupport() {
        AtomicInteger reads = new AtomicInteger();
        var input = TargetTerrainInputs.sample(List.of(new RoadSegmentPlacement(p(0), List.of())),
                List.of(p(0)), 0, ignored -> Map.of(key(0), sample(81)),
                AccurateHeightSample::worldSurfaceWg, p -> { reads.incrementAndGet(); return 99; });
        assertEquals(1, reads.get());
        assertArrayEquals(new int[]{81}, input.terrainY());
        assertArrayEquals(new int[]{81}, input.footprintSupport());
    }

    @Test
    void interruptionStopsBeforeCallingSamplingOrWorldPorts() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> TargetTerrainInputs.sample(
                    List.of(new RoadSegmentPlacement(p(0), List.of(p(0)))), List.of(p(0)), 0,
                    ignored -> { throw new AssertionError("sampling after cancellation"); },
                    AccurateHeightSample::worldSurfaceWg,
                    ignored -> { throw new AssertionError("world read after cancellation"); }));
        } finally { Thread.interrupted(); }
    }

    private static BlockPos p(int x) { return new BlockPos(x, 0, 0); }
    private static long key(int x) { return AccurateHeightSample.key(x, 0); }
    private static AccurateHeightSample sample(int y) { return new AccurateHeightSample(y, y - 5, y + 3); }
}
