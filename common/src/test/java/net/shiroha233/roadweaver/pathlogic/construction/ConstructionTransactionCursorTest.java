/* 文件职责：对照冻结同步事务检查任意暂停的副作用、随机消费、取消与释放次序。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionTransactionCursorTest {
    @Test void everyPauseAndSliceMatchesIndependentFrozenTransaction() {
        int cases = 0;
        for (int flags = 0; flags < 16; flags++) for (int seed = 0; seed < 4; seed++) {
            var baseline = new Fake(flags, seed);
            boolean expected = new FrozenConstructionTransaction<>(baseline).run();
            long randomTail = baseline.random.nextLong();
            for (int cut = 0; cut < 15; cut++) {
                var actual = new Sliced(flags, seed);
                var cursor = new ConstructionTransactionCursor<>(actual);
                if (cut > 0) cursor.advance(cut);
                if (!cursor.terminal()) assertFalse(actual.events.contains("ledger"), "暂停不能记完成");
                assertEquals(expected, cursor.runAll());
                assertEquals(baseline.events, actual.events, "flags=" + flags + " cut=" + cut);
                assertEquals(randomTail, actual.random.nextLong(), "随机消费必须完全相同");
                assertEquals(ConstructionTransactionCursor.Stage.CLOSED, cursor.stage());
                assertEquals(1, Collections.frequency(actual.events, "release"));
                assertEquals(expected, cursor.runAll()); // 查询终态不能重放世界副作用。
                assertEquals(baseline.events, actual.events);
                cases++;
            }
        }
        assertEquals(960, cases);
    }

    @Test void unitBudgetNeverSettlesBeforeAllPaintAndDecoration() {
        var actual = new Sliced(2, 23);
        var cursor = new ConstructionTransactionCursor<>(actual);
        int attempts = 0;
        while (!cursor.terminal()) {
            assertFalse(actual.events.contains("ledger"));
            cursor.advance(1); attempts++;
            assertTrue(attempts < 40, "有限输入必须结案");
        }
        assertTrue(attempts > 10);
        assertEquals(5, actual.oldNext); assertEquals(5, actual.newNext); assertEquals(3, actual.decorNext);
        assertTrue(actual.events.indexOf("decorate:2") < actual.events.indexOf("ledger"));
        assertEquals(1, Collections.frequency(actual.events, "ledger"));
    }

    @Test void ownerLossAtEveryPhaseCancelsAndReleasesWithoutFinishing() {
        int reached = 0;
        for (var target : List.of(ConstructionTransactionCursor.Stage.LOAD, ConstructionTransactionCursor.Stage.PREPARE,
                ConstructionTransactionCursor.Stage.PAINT, ConstructionTransactionCursor.Stage.REPAIR,
                ConstructionTransactionCursor.Stage.REFRESH, ConstructionTransactionCursor.Stage.RESET,
                ConstructionTransactionCursor.Stage.REPAINT, ConstructionTransactionCursor.Stage.DECORATE,
                ConstructionTransactionCursor.Stage.COMMIT)) {
            var actual = new Sliced(2, 1);
            var cursor = new ConstructionTransactionCursor<>(actual);
            for (int limit = 0; cursor.stage() != target && limit < 40; limit++) cursor.advance(1);
            assertEquals(target, cursor.stage());
            var before = new ArrayList<>(actual.events); actual.alive = false;
            cursor.advance(1); before.add("release");
            assertEquals(before, actual.events);
            assertEquals(ConstructionTransactionCursor.Stage.CANCELLED, cursor.stage());
            cursor.cancel(); assertFalse(cursor.runAll()); assertEquals(before, actual.events);
            reached++;
        }
        assertEquals(9, reached);
    }

    @Test void externalRevisionCancelsButOwnRepairAcceptsFreshEmptyAndNonemptyPlan() {
        var actual = new Sliced(0, 1);
        var cursor = new ConstructionTransactionCursor<>(actual);
        cursor.advance(3); // 已写一站，不能再用另一个版本把余下部分拼完。
        assertEquals(1, actual.oldNext);
        actual.revision = "foreign";
        assertFalse(cursor.runAll());
        assertEquals(ConstructionTransactionCursor.Stage.CANCELLED, cursor.stage());
        assertEquals(1, actual.oldNext); assertFalse(actual.events.contains("ledger"));
        for (int flags : List.of(2, 6)) {
            var repaired = new Sliced(flags, 1);
            assertTrue(new ConstructionTransactionCursor<>(repaired).runAll());
            assertTrue(repaired.events.contains(flags == 2 ? "finish:new:true" : "finish:old:true"));
        }
    }

    @Test void everyPortFaultMatchesFrozenFailureAndCannotReplay() {
        for (String failure : List.of("load", "empty:old", "prepare", "process:old", "paint:old:2", "repair",
                "refresh", "empty:new", "reset", "process:new", "paint:new:3", "decorate", "decorate:1",
                "ledger", "finish:new:true", "release")) {
            var baseline = new Fake(2, 9); baseline.failure = failure;
            var expected = assertThrows(IllegalStateException.class, () -> new FrozenConstructionTransaction<>(baseline).run());
            var actual = new Sliced(2, 9); actual.failure = failure;
            var cursor = new ConstructionTransactionCursor<>(actual);
            var observed = assertThrows(IllegalStateException.class, cursor::runAll);
            assertEquals(expected.getMessage(), observed.getMessage());
            assertEquals(baseline.events, actual.events);
            assertEquals(ConstructionTransactionCursor.Stage.FAILED, cursor.stage());
            var before = List.copyOf(actual.events);
            assertThrows(IllegalStateException.class, cursor::runAll);
            assertEquals(before, actual.events);
        }
    }

    @Test void timingWrapperPreservesSliceAndValidityPorts() {
        var actual = new Sliced(2, 9);
        var measured = ConstructionPhaseTiming.wrap(actual, "offline", true, System::nanoTime, ignored -> {});
        var cursor = new ConstructionTransactionCursor<>(measured);
        cursor.advance(3);
        assertEquals(1, actual.oldNext);
        actual.alive = false; cursor.advance(1);
        assertEquals(ConstructionTransactionCursor.Stage.CANCELLED, cursor.stage());
        assertFalse(actual.events.contains("ledger"));
    }

    @Test void wrongThreadAndInvalidBudgetCannotStealOrReplayOwnerResources() throws Exception {
        var actual = new Sliced(0, 1);
        var cursor = new ConstructionTransactionCursor<>(actual);
        assertThrows(IllegalArgumentException.class, () -> cursor.advance(0));
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { cursor.advance(1); } catch (Throwable failure) { observed.set(failure); }
        });
        thread.start(); thread.join(3000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, observed.get());
        assertTrue(actual.events.isEmpty());
        assertEquals(ConstructionTransactionCursor.Stage.LOAD, cursor.stage());
        cursor.cancel();
        assertEquals(List.of("release"), actual.events);
    }

    static class Fake implements ConstructionChunkTransaction.Access<String> {
        final List<String> events = new ArrayList<>();
        final Random random;
        final int flags;
        int oldNext, newNext, decorNext;
        String revision = "old", failure;
        boolean alive = true;
        Fake(int flags, int seed) { this.flags = flags; random = new Random(seed); }
        void event(String text) { events.add(text); if (text.equals(failure)) throw new IllegalStateException(text); }
        void station(String plan, int index) { event("paint:" + plan + ":" + index); event("random:" + random.nextLong()); }
        public String loadInitial() { event("load"); return "old"; }
        public boolean isEmpty(String plan) { event("empty:" + plan); return (flags & (plan.equals("old") ? 1 : 4)) != 0; }
        public void emptyPlan() { event("empty-detail"); }
        public void prepare(String plan) { event("prepare"); }
        public void process(String plan) { event("process:" + plan); for (int i = 0; i < 5; i++) station(plan, i); }
        public boolean repairRefusals() { event("repair"); if ((flags & 2) != 0) revision = "new"; return (flags & 2) != 0; }
        public String loadRefreshed() { event("refresh"); return "new"; }
        public void resetRefusals() { event("reset"); }
        public void decorate() { event("decorate"); for (int i = 0; i < 3; i++) event("decorate:" + i); }
        public void commitCompleted() { event("ledger"); }
        public boolean finish(String evidence, boolean repaired) { event("finish:" + evidence + ":" + repaired); return (flags & 8) == 0; }
        public void release() { event("release"); }
        public boolean ownerAlive() { return alive; }
        public boolean isCurrent(String plan) { return plan.equals(revision); }
    }
    static final class Sliced extends Fake {
        Sliced(int flags, int seed) { super(flags, seed); }
        public boolean processSlice(String plan) {
            int index = plan.equals("old") ? oldNext : newNext;
            if (index == 0) event("process:" + plan);
            station(plan, index);
            if (plan.equals("old")) oldNext++; else newNext++;
            return index == 4;
        }
        public boolean decorateSlice() {
            if (decorNext == 0) event("decorate");
            event("decorate:" + decorNext++);
            return decorNext == 3;
        }
    }
}
