package net.shiroha233.roadweaver.pathfinding.sampling;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 认领协议单独验收，避免批次缓存恰好命中而把认领错误隐藏起来。 */
final class AccurateTileClaimsTest {
    @Test
    void conflictDoesNotClaimFreeSubsetAndOwnedKeysKeepInputOrder() throws Exception {
        AccurateTileClaims claims = new AccurateTileClaims(() -> {}, () -> 5_000);
        try (AccurateTileClaims.Lease owner = claims.claim(List.of(7L), false);
             ExecutorService pool = Executors.newCachedThreadPool()) {
            assertTrue(owner.isOwner());
            assertFalse(pool.submit(() -> {
                try (AccurateTileClaims.Lease conflict = claims.claim(List.of(9L, 7L), false)) {
                    return conflict.isOwner();
                }
            }).get(5, TimeUnit.SECONDS));
            // 冲突批次不能暗中占住空闲的 9，否则独立批次会无故被它阻塞。
            try (AccurateTileClaims.Lease free = claims.claim(List.of(9L, 11L), false)) {
                assertTrue(free.isOwner());
                assertEquals(List.of(9L, 11L), new ArrayList<>(free.keys()));
                free.succeed();
            }
            owner.succeed();
        }
    }

    @Test
    void reentryChecksWholeBatchBeforeClaimingAnyFreeKey() {
        AccurateTileClaims claims = new AccurateTileClaims(() -> {}, () -> 5_000);
        try (AccurateTileClaims.Lease owner = claims.claim(List.of(7L), false)) {
            assertThrows(IllegalStateException.class, () -> claims.claim(List.of(9L, 7L), false));
            try (AccurateTileClaims.Lease free = claims.claim(List.of(9L), false)) {
                assertTrue(free.isOwner());
                free.succeed();
            }
            owner.succeed();
        }
    }

    @Test
    void closeWakesRegisteredWaiterAndRejectsClaimsEvenIfCacheStillLooksOpen() throws Exception {
        CountDownLatch joining = new CountDownLatch(1);
        AtomicInteger openChecks = new AtomicInteger();
        AccurateTileClaims claims = new AccurateTileClaims(openChecks::incrementAndGet,
                () -> { joining.countDown(); return 30_000; });
        IllegalStateException fault = new IllegalStateException("controlled claims close");
        try (AccurateTileClaims.Lease owner = claims.claim(List.of(7L), false);
             ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> waiter = pool.submit(() -> {
                try (AccurateTileClaims.Lease waiting = claims.claim(List.of(7L), false)) { waiting.await(); }
            });
            try {
                await(joining);
                claims.close(fault);
                assertSame(fault, assertThrows(ExecutionException.class,
                        () -> waiter.get(5, TimeUnit.SECONDS)).getCause());
                int checks = openChecks.get();
                assertSame(fault, assertThrows(IllegalStateException.class, () -> claims.claim(List.of(9L), false)));
                assertSame(fault, assertThrows(IllegalStateException.class, () -> claims.claim(List.of(9L), true)));
                assertEquals(checks, openChecks.get(), "自身已关闭时不能继续依赖外部缓存端口给出许可");
            } finally {
                owner.fail(fault);
                claims.close(fault);
            }
        }
    }

    @Test
    void lateOwnerReleaseCannotEraseReplacementAfterTimeout() throws Exception {
        AccurateTileClaims claims = new AccurateTileClaims(() -> {}, () -> 25);
        CountDownLatch replacementReady = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AccurateTileClaims.Lease old = claims.claim(List.of(7L), false);
             ExecutorService pool = Executors.newCachedThreadPool()) {
            Future<?> replacement = pool.submit(() -> {
                try (AccurateTileClaims.Lease waiting = claims.claim(List.of(7L), false)) {
                    assertFalse(waiting.isOwner());
                    waiting.await();
                }
                try (AccurateTileClaims.Lease next = claims.claim(List.of(7L), false)) {
                    assertTrue(next.isOwner(), "真实超时后必须可以接管");
                    replacementReady.countDown();
                    await(release);
                    next.succeed();
                }
            });
            try {
                await(replacementReady);
                old.succeed();
                old.close();
                assertFalse(pool.submit(() -> {
                    try (AccurateTileClaims.Lease observer = claims.claim(List.of(7L), false)) {
                        return observer.isOwner();
                    }
                }).get(5, TimeUnit.SECONDS), "旧所有者清理不能移除新所有者的不同 Future");
            } finally {
                release.countDown();
                replacement.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void modeLanesAreIndependentButSameThreadCannotReenterAcrossModes() throws Exception {
        AccurateTileClaims claims = new AccurateTileClaims(() -> {}, () -> 5_000);
        try (AccurateTileClaims.Lease cpu = claims.claim(List.of(7L), false);
             ExecutorService pool = Executors.newCachedThreadPool()) {
            assertTrue(cpu.isOwner());
            assertTrue(pool.submit(() -> {
                try (AccurateTileClaims.Lease accelerated = claims.claim(List.of(7L), true)) {
                    boolean owned = accelerated.isOwner();
                    accelerated.succeed();
                    return owned;
                }
            }).get(5, TimeUnit.SECONDS), "模式各有在途表，不能强迫专用后端等待另一种求值模式");
            assertThrows(IllegalStateException.class, () -> claims.claim(List.of(7L), true));
            cpu.succeed();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException fault) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", fault);
        }
    }
}
