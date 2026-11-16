package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.pathlogic.PathFeature.LoadedPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

import static net.shiroha233.roadweaver.generation.DeferredChunkQueue.Probe.BUILDABLE;
import static net.shiroha233.roadweaver.generation.DeferredChunkQueue.Probe.SETTLED;
import static net.shiroha233.roadweaver.generation.DeferredChunkQueue.Probe.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredChunkQueueTest {
    @Test
    void loadHintFindsOneReadyChunkInTenThousandAndStillCapsProbes() {
        var queue = pending(10_000);
        List<Long> probes = new ArrayList<>();
        queue.hint(10_001L);
        queue.hint(9_999L);
        queue.hint(9_999L);

        // 未加载的多数，不能因为不占施工名额而触发全表查询。
        var selection = queue.select(recording(probes, Set.of(9_999L)));

        assertEquals(List.of(9_999L), selection.build());
        assertEquals(9_999L, probes.getFirst());
        assertEquals(129, probes.size());
        assertEquals(1, selection.hintSteps());
        assertEquals(128, selection.sweepSteps());
        assertFalse(probes.contains(10_001L));
    }

    @Test
    void alreadyLoadedChunkWithoutEventIsReachedBySuccessiveSweeps() {
        var queue = pending(10_000);
        List<Long> probes = new ArrayList<>();
        List<Long> built = new ArrayList<>();
        for (int round = 0; round < 79 && built.isEmpty(); round++) {
            int before = probes.size();
            built.addAll(queue.select(recording(probes, Set.of(9_999L))).build());
            assertTrue(probes.size() - before <= 128);
        }

        // 重开或先加载后落路时没有新事件。轮询必须跨轮往前，不能一直从队首开始。
        assertEquals(List.of(9_999L), built);
        assertTrue(probes.indexOf(9_999L) < 10_000);
        assertEquals(10_000, new HashSet<>(probes).size());
    }

    @Test
    void steadyLoadHintsCannotConsumeTheOnlySlotNeededByReadyUnhintedWork() {
        var queue = pending(30);
        for (long key = 1; key < 30; key++) queue.hint(key);

        var first = queue.select(key -> BUILDABLE);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 0L), first.build());
        first.build().forEach(queue::remove);
        assertEquals(22, queue.size());
        // 没有无提示区块时，仍能用完 8 个施工名额。
        var second = queue.select(key -> BUILDABLE);
        assertEquals(List.of(8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L), second.build());
    }

    @Test
    void staleHintAndSettledEntryDoNotBuildOrConsumeSlots() {
        var queue = pending(3);
        queue.hint(0);
        queue.hint(1);
        var probes = new ArrayList<Long>();

        // 提示后卸载的区块仍保留；终态键及其提示一起退出，不会重复查或落块。
        var selection = queue.select(key -> {
            probes.add(key);
            if (key == 0) return WAITING;
            if (key == 1) {
                assertTrue(queue.remove(key));
                return SETTLED;
            }
            return BUILDABLE;
        });
        assertEquals(List.of(0L, 1L, 2L), probes);
        assertEquals(1, selection.settled());
        assertEquals(List.of(2L), selection.build());
        assertTrue(queue.remove(2));
        assertEquals(1, queue.size());
        queue.hint(0);
        assertEquals(List.of(0L), queue.select(key -> BUILDABLE).build());
    }

    @Test
    void staleHintStormKeepsBothBudgetsAndDoesNotLoseWaitingWork() {
        var queue = pending(1_000);
        for (long key = 500; key < 1_000; key++) queue.hint(key);
        var probes = new ArrayList<Long>();

        var selection = queue.select(recording(probes, Set.of()));

        assertEquals(32, selection.hintSteps());
        assertEquals(128, selection.sweepSteps());
        assertEquals(160, probes.size());
        assertEquals(160, new HashSet<>(probes).size());
        assertTrue(selection.build().isEmpty());
        assertEquals(1_000, queue.size());
        assertEquals(List.of(532L), queue.select(key -> key == 532L ? BUILDABLE : WAITING).build());
    }

    @Test
    void backfillUsesSelectedFullChunksButRetestsEligibilityBeforeEachWrite() {
        Map<Long, String> selected = Map.of(10L, "full-10", 20L, "full-20",
                30L, "full-30", 40L, "full-40");
        var writes = new ArrayList<String>();
        var eligibility = new ArrayList<Long>();
        var pass = LoadedChunkRoadBackfill.runBackfill(List.of(10L, 20L, 30L, 40L, 50L), 3, selected,
                key -> { throw new AssertionError("本轮已查 FULL，不应重复读世界：" + key); },
                key -> {
                    eligibility.add(key);
                    // 第一块写完后另一块已结案。仅复用区块存在性，不能跳过即时台账判据。
                    return key == 20L && writes.contains("full-10");
                },
                (key, chunk) -> {
                    if (key == 10L) {
                        writes.add(chunk);
                        return LoadedPlacement.PLACED;
                    }
                    return key == 30L ? LoadedPlacement.EMPTY_PLAN : LoadedPlacement.NO_PLACEMENT;
                });
        assertEquals(List.of(10L, 20L, 30L, 40L), eligibility);
        assertEquals(List.of("full-10"), writes);
        assertEquals(new LoadedChunkRoadBackfill.BackfillPass(4, 3, 1, List.of(30L), 0), pass);
    }

    @Test
    void preloadedBackfillRequiresCompleteLedgerEvenForPartiallyPlacedChunk() {
        var verified = new HashSet<Long>();
        var ledger = new HashSet<>(Set.of(10L));
        var pass = LoadedChunkRoadBackfill.runBackfill(List.of(10L, 20L, 30L, 40L, 50L), 5,
                Map.of(10L, "ready", 20L, "ready", 30L, "ready", 40L, "ready", 50L, "ready"),
                key -> { throw new AssertionError("已确认 FULL 不应再次查世界"); }, ledger::contains,
                (key, chunk) -> {
                    assertNotEquals(10L, key, "已结案块不应施工");
                    if (key == 20L) return LoadedPlacement.EMPTY_PLAN;
                    if (key == 30L) ledger.add(key);
                    // 40 只铺成一部分路，50 没铺成；两者都仍待施工。
                    return key == 50L ? LoadedPlacement.NO_PLACEMENT : LoadedPlacement.PLACED;
                }, verified);
        assertEquals(Set.of(10L, 30L), verified);
        assertEquals(List.of(20L), pass.noConstruction());
        assertEquals(2, pass.placed());
        assertEquals(0, pass.fullLookups());
    }

    private static DeferredChunkQueue pending(int count) {
        var queue = new DeferredChunkQueue();
        for (long key = 0; key < count; key++) assertTrue(queue.add(key));
        return queue;
    }

    private static LongFunction<DeferredChunkQueue.Probe> recording(List<Long> probes, Set<Long> loaded) {
        return key -> {
            probes.add(key);
            return loaded.contains(key) ? BUILDABLE : WAITING;
        };
    }
}
