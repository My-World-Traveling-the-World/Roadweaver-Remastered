package net.shiroha233.roadweaver.pathlogic.structures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.059（①）：{@code terrainSnapshotPrepMs} 子段计时的防重复累加回归。
 *
 * <p>{@code [PLAN-SNAPSHOT-BREAKDOWN]} 一行的四段是<b>串行、互不重叠</b>的，所以
 * "子段之和 ≤ 父段"必须永远成立。这条一旦被违反，说明有人把同一段时间记进了两段，
 * 于是"哪一段最大"这个结论会直接错 —— 那份诊断行就没有意义了。</p>
 *
 * <p>真机一局的数直接拿来当夹具（毫秒）：父段 {@code terrainSnapshotPrepMs=72,670}，
 * 子段实测 {@code resolutionMs=64,709} + 其余若干。</p>
 */
final class PrepareTimingSplitTest {

    private static final long REAL_PARENT_MS = 72_670L;

    @Test
    void segmentsNeverExceedParent() {
        // 真机量级：解析 64,709 + 瓦片预测 6,000 + 起点枚举 1,500 + 写缓存 300 + 前缀 80
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(REAL_PARENT_MS,
                64_709L, 6_000L, 1_500L, 300L, 80L));
        // 边界：正好相等
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(100L, 100L));
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(100L, 60L, 40L));
        // 重复累加 ⇒ 立刻违反
        assertFalse(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(100L, 60L, 60L));
        // 负数段按 0 计（时钟异常不该让断言假红）
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(10L, -5L, 10L));
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(-1L));
    }

    @Test
    void recordedSegmentsStayWithinMeasuredTotal() {
        StructureAvoidanceService.PrepareTiming timing =
                new StructureAvoidanceService.PrepareTiming();
        assertFalse(timing.ran());
        timing.markRan();
        assertTrue(timing.ran());

        // 这里必须注入"纳秒级"的小量：本对象的 totalNanos() 是真实墙钟，
        // 注入毫秒级合成值会**故意**违反"子段之和 ≤ 父段"，那不是被测行为。
        // 五段各记 1 ns，真实墙钟（≥ 一次 System.nanoTime 的开销）必然还更大。
        timing.addServerInit(1L);
        timing.addTileSample(1L);
        timing.addStartKeys(1L);
        timing.addStructureOverlay(1L);
        timing.addCacheWrite(1L);
        timing.setRoutes(4);
        timing.setStarts(4_374);
        timing.setResolvedBoxes(377);
        timing.setColumns(32);
        timing.setObstacleTiles(96);
        timing.observeWorkerThread();

        assertEquals(5L, timing.segmentNanos());
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(
                        timing.totalNanos(), timing.segmentNanos()),
                "五段之和必须 <= 本对象自己的总时长");
        // 同一批数放在真机父段(72,670 ms)下也必须成立
        assertTrue(StructureAvoidanceService.PrepareTiming.segmentsWithinTotal(
                REAL_PARENT_MS * 1_000_000L, timing.segmentNanos()));
    }

    @Test
    void breakdownLineCarriesTheFourRequestedSegmentsAndThreading() {
        StructureAvoidanceService.PrepareTiming timing =
                new StructureAvoidanceService.PrepareTiming();
        timing.addTileSample(1_000_000L);
        timing.addStructureOverlay(2_000_000L);
        timing.addCacheWrite(3_000_000L);
        timing.setColumns(32);
        timing.observeWorkerThread();
        String line = timing.describe();
        assertNotNull(line);
        assertTrue(line.contains("tileSampleMs="), line);
        assertTrue(line.contains("corridorSnapshotMs=0"),
                "走廊快照不在 terrainSnapshotPrep 窗口内，必须如实写 0：" + line);
        assertTrue(line.contains("structureOverlayMs="), line);
        assertTrue(line.contains("cacheWriteMs="), line);
        assertTrue(line.contains("threads="), line);
        assertTrue(line.contains("columns="), line);
        assertTrue(line.contains("segmentsMs="), line);
        assertTrue(line.contains("corridorNote="), line);
    }
}
