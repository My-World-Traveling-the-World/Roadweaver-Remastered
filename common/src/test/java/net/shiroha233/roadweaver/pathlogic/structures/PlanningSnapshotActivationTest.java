/* 文件职责：验证嵌套恢复、临时扩宽及线程隔离，防止一个搜索污染后续道路。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningSnapshotActivationTest {
    @Test
    void nestedExpansionRestoresTheExactOuterSnapshotEvenOnFailure() {
        var previous = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        var original = new PlanningSnapshot(Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), LongSets.emptySet(), 2);
        try {
            StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
            try (var outer = PlanningSnapshotActivation.activate(original, 0)) {
                assertSame(original, StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get());
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var inner = PlanningSnapshotActivation.activate(original, 3)) {
                        assertEquals(5, StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get().roadRadius());
                        assertEquals(2, original.roadRadius());
                        throw new IllegalArgumentException("controlled search failure");
                    }
                });
                assertSame(original, StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get());
            }
            assertNull(StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get());
        } finally { restore(previous); }
    }

    @Test
    void publicMissingRouteScopeRemainsEmptyAndCannotLeakAcrossThreads() throws Exception {
        var previous = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        try (var pool = Executors.newSingleThreadExecutor()) {
            StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
            try (var scope = StructureAvoidanceService.activatePlanningSnapshot(null, null)) {
                assertSame(PlanningSnapshot.EMPTY, StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get());
                assertNull(pool.submit(() -> StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get()).get(5, TimeUnit.SECONDS));
            }
            assertNull(StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get());
        } finally { restore(previous); }
    }

    private static void restore(PlanningSnapshot previous) {
        // 用例自身也恢复原线程上下文，不能让顺序执行掩盖泄漏。
        if (previous == null) StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
        else StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(previous);
    }
}
