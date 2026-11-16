package net.shiroha233.roadweaver.persistence.files;

import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 在实际文件入口暂停输入遍历，固定访问租约与关闭的顺序，不靠随机并发碰运气。 */
final class AccurateSampleStoreCloseTest {
    private static final WorldgenFingerprint FINGERPRINT = new WorldgenFingerprint("c".repeat(64), 1, 3955);
    private static final AccurateHeightSample SAMPLE = new AccurateHeightSample(81, 62, 85);

    @Test
    void closeDrainsRealWriteAndDoesNotSerializeIndependentAccesses(@TempDir Path root) throws Exception {
        var store = new FileBackedAccurateSampleStore(root, FINGERPRINT);
        CountDownLatch firstEntered = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long firstKey = AccurateHeightSample.key(-1, -1), secondKey = AccurateHeightSample.key(256, 256);
        CompletableFuture<Void> first = new CompletableFuture<>(), second = new CompletableFuture<>(), closed = new CompletableFuture<>();
        Thread a = worker(() -> store.saveSamples(gated(firstKey, firstEntered, release)), first);
        Thread b = worker(() -> store.saveSamples(gated(secondKey, secondEntered, release)), second);
        Thread closer = new Thread(() -> {
            try { store.close(); closed.complete(null); }
            catch (Throwable failure) { closed.completeExceptionally(failure); }
        }, "sample-store-close");
        try {
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS), "共享租约不能把独立瓦片全局串行化");
            closer.start();
            // 确认关闭线程实际等在锁上；若关闭直接返回，线程会结束，此断言必须红。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closer.isAlive() && closer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, closer.getState(), "关闭必须排空已经进入的真实文件访问");
            assertFalse(closed.isDone());
        } finally {
            release.countDown();
            a.join(5_000); b.join(5_000);
            if (closer.getState() != Thread.State.NEW) closer.join(5_000);
            store.close();
        }
        first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS); closed.get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> store.loadSamples(List.of(firstKey, secondKey)));
        try (var reopened = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            assertEquals(Map.of(firstKey, SAMPLE, secondKey, SAMPLE), reopened.loadSamples(List.of(firstKey, secondKey)),
                    "已进入的写入应完成落盘，关闭返回之后禁止再发布");
        }
    }

    @Test
    void sameThreadCloseReentryFailsInsteadOfDeadlockingAndStoreStaysUsable(@TempDir Path root) {
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            Map<Long, AccurateHeightSample> input = new AbstractMap<>() {
                @Override
                public boolean isEmpty() { return false; }
                @Override
                public Set<Entry<Long, AccurateHeightSample>> entrySet() {
                    assertThrows(IllegalStateException.class, store::close, "读锁升级会永久等待，应在升级前拒绝");
                    return Map.of(0L, SAMPLE).entrySet();
                }
            };
            store.saveSamples(input);
            assertEquals(Map.of(0L, SAMPLE), store.loadSamples(List.of(0L)));
        }
    }

    private static Map<Long, AccurateHeightSample> gated(long key, CountDownLatch entered, CountDownLatch release) {
        return new AbstractMap<>() {
            @Override
            public boolean isEmpty() { return false; }
            @Override
            public Set<Entry<Long, AccurateHeightSample>> entrySet() {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return Map.of(key, SAMPLE).entrySet();
            }
        };
    }

    private static Thread worker(Runnable work, CompletableFuture<Void> done) {
        Thread thread = new Thread(() -> {
            try { work.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        }, "sample-file-writer");
        thread.start();
        return thread;
    }
}
