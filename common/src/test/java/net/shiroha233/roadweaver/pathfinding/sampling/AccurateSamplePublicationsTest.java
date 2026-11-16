package net.shiroha233.roadweaver.pathfinding.sampling;

import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 发布记录只触发重查；固定槽冲突、并发版本倒序及上下文隔离都不能破坏这个边界。 */
final class AccurateSamplePublicationsTest {
    @Test
    void emptyPublicationDoesNotAdvanceAndUnrelatedTileDoesNotInvalidate() {
        var publications = new AccurateSamplePublications();
        long before = publications.version();
        publications.publishedTiles(List.of());
        assertEquals(before, publications.version());
        publications.publishedTiles(List.of(0L));
        assertTrue(publications.changedSince(List.of(0L), before));
        assertFalse(publications.changedSince(List.of(16L | (32L << 32)), before));
        assertFalse(publications.changedSince(List.of(0L), publications.version()));
        assertFalse(publications.changedSince(List.of(), before));
    }

    @Test
    void collisionCanConservativelyInvalidateAnotherTile() {
        var publications = new AccurateSamplePublications();
        long before = publications.version();
        publications.publishedTiles(List.of(0L));
        long collision = -1;
        // 根据实际记录找一个不同键的碰撞，避免把具体哈希函数冻结成外部契约。
        for (long key = 1; key <= 100_000; key++) {
            if (publications.changedSince(List.of(key), before)) { collision = key; break; }
        }
        assertTrue(collision > 0, "固定槽应存在不同键的碰撞，此用例不能空转");
        assertTrue(publications.changedSince(List.of(collision), before));
        assertFalse(publications.changedSince(List.of(collision), publications.version()),
                "碰撞只要求旧观察重查，新的观察不能被永久判过期");
    }

    @Test
    void lateOlderPublicationCannotRegressStripeVersion() throws Exception {
        var publications = new AccurateSamplePublications();
        CountDownLatch oldVersionAllocated = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
        var delayedKeys = new AbstractCollection<Long>() {
            @Override
            public int size() { return 1; }
            @Override
            public Iterator<Long> iterator() {
                oldVersionAllocated.countDown();
                await(releaseOld);
                return List.of(0L).iterator();
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var old = pool.submit(() -> publications.publishedTiles(delayedKeys));
            try {
                await(oldVersionAllocated);
                long observed = publications.version();
                assertEquals(1L, observed);
                publications.publishedTiles(List.of(0L));
                releaseOld.countDown();
                old.get(5, TimeUnit.SECONDS);
                assertTrue(publications.changedSince(List.of(0L), observed), "旧批次迟到不能覆盖新批次的更大版本");
                assertEquals(2L, publications.version());
            } finally { releaseOld.countDown(); }
        }
    }

    @Test
    void separateContextsDoNotSharePublicationHistory() {
        var first = new AccurateSamplePublications();
        var second = new AccurateSamplePublications();
        first.publishedTiles(List.of(0L));
        assertTrue(first.changedSince(List.of(0L), 0));
        assertEquals(0L, second.version());
        assertFalse(second.changedSince(List.of(0L), 0));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
