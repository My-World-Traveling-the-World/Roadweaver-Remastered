/* 文件职责：守住真实文件故障、并发提交与关闭边界，防止未提交覆盖冒充 READY。 */
package net.shiroha233.roadweaver.planning;

import com.sun.nio.file.ExtendedOpenOption;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessCoverageFaultTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final long OLD = pack(-4, 7), A = pack(5, -8), B = pack(9, 1);

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void thrownWriteNeverPublishesAndSameInstanceCanRetry(int kind, @TempDir Path root) throws Exception {
        Path file = seed(root);
        byte[] original = Files.readAllBytes(file);
        Exception fault = kind == 0 ? new IOException("write-fault") : new IllegalStateException("writer-fault");
        AtomicInteger calls = new AtomicInteger();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, text) -> {
            if (calls.incrementAndGet() == 1) {
                if (fault instanceof IOException io) throw io;
                throw (RuntimeException) fault;
            }
            FileStorageIO.writeStringAtomic(p, text);
        });
        assertSame(fault, assertThrows(Exception.class, () -> store.markReady(A)));
        assertFalse(store.contains(A));
        assertEquals(1, store.size());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(store.markReady(A));
        assertEquals(2, calls.get());
        assertReopened(file, 2, OLD, A);
    }

    @Test
    void unreadableOldFileCannotBeOverwrittenAndUnlockRetriesTheRead(@TempDir Path root) throws Exception {
        Path file = seed(root);
        byte[] original = Files.readAllBytes(file);
        AtomicInteger writes = new AtomicInteger();
        ReadinessCoverageStore store;
        try (var held = FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            assertThrows(IOException.class, () -> Files.readAllBytes(file));
            store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, text) -> {
                writes.incrementAndGet();
                FileStorageIO.writeStringAtomic(p, text);
            });
            assertFalse(store.contains(OLD));
            assertThrows(IOException.class, () -> store.markReady(A));
            assertEquals(0, writes.get(), "重读也失败时，绝不能尝试替换旧索引");
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(store.markReady(A));
        assertTrue(store.contains(OLD));
        assertEquals(1, writes.get());
        assertReopened(file, 2, OLD, A);
    }

    @Test
    void malformedPrefixCannotBeReplacedUntilTheFileIsRepaired(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        String malformed = header() + "region=-4,7\nregion=broken,1\n";
        Files.writeString(file, malformed);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertThrows(IOException.class, () -> store.markReady(A));
        assertFalse(store.contains(A));
        assertEquals(malformed, Files.readString(file));
        Files.writeString(file, header() + "region=-4,7\n");
        assertTrue(store.markReady(A));
        assertReopened(file, 2, OLD, A);
    }

    @Test
    void nonRegularPathIsAReadFailureAndCanRecoverAfterRepair(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        Files.createDirectory(file);
        AtomicInteger writes = new AtomicInteger();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, text) -> {
            writes.incrementAndGet();
            FileStorageIO.writeStringAtomic(p, text);
        });
        assertThrows(IOException.class, () -> store.markReady(A));
        assertEquals(0, writes.get());
        Files.delete(file);
        Files.writeString(file, header() + "region=-4,7\n");
        assertTrue(store.markReady(A));
        assertReopened(file, 2, OLD, A);
    }

    @Test
    void missingParentFailureDoesNotPublishAndRetryCreatesTheOriginalPath(@TempDir Path root) throws Exception {
        Path parent = root.resolve("parent"), file = parent.resolve("ready.txt");
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        Files.writeString(parent, "blocks directory creation");
        assertThrows(IOException.class, () -> store.markReady(A));
        assertFalse(store.contains(A));
        Files.delete(parent);
        assertTrue(store.markReady(A));
        assertReopened(file, 1, A);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void inFlightWritesAreInvisibleAndConcurrentCommitsAreSerialized(int duplicate, @TempDir Path root) throws Exception {
        Path file = seed(root);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Thread> follower = new AtomicReference<>();
        List<String> commits = new ArrayList<>();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, text) -> {
            if (writes.incrementAndGet() == 1) { entered.countDown(); await(release); }
            FileStorageIO.writeStringAtomic(p, text);
            commits.add(Files.readString(p));
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.markReady(A));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(store.contains(A));
                assertTrue(store.contains(OLD));
                assertEquals(1, store.size());
                var second = executor.submit(() -> {
                    follower.set(Thread.currentThread()); secondEntered.countDown();
                    return store.markReady(duplicate == 1 ? A : B);
                });
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                awaitBlocked(follower.get());
                assertFalse(second.isDone());
                assertEquals(1, writes.get());
                release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertEquals(duplicate == 0, second.get(5, TimeUnit.SECONDS));
                assertEquals(header() + "region=-4,7\nregion=5,-8\n", commits.getFirst());
                if (duplicate == 0) {
                    assertEquals(2, writes.get());
                    assertEquals(header() + "region=-4,7\nregion=5,-8\nregion=9,1\n", commits.get(1));
                    assertReopened(file, 3, OLD, A, B);
                } else {
                    assertEquals(1, writes.get());
                    assertReopened(file, 2, OLD, A);
                }
            } finally { release.countDown(); }
        }
    }

    @Test
    void closedInstanceCannotWriteOrReportReady(@TempDir Path root) throws Exception {
        Path file = seed(root);
        byte[] original = Files.readAllBytes(file);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertTrue(store.contains(OLD));
        store.close();
        store.close();
        assertFalse(store.contains(OLD));
        assertEquals(0, store.size());
        assertThrows(IOException.class, () -> store.markReady(A));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertReopened(file, 1, OLD);
    }

    @Test
    void closeDrainsAnAcceptedWriteBeforeReopenAndSuppressesOldPublication(@TempDir Path root) throws Exception {
        Path file = seed(root);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closing = new CountDownLatch(1);
        AtomicReference<Thread> closer = new AtomicReference<>();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, text) -> {
            entered.countDown(); await(release); FileStorageIO.writeStringAtomic(p, text);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var write = executor.submit(() -> store.markReady(A));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var close = executor.submit(() -> {
                    closer.set(Thread.currentThread()); closing.countDown(); store.close();
                });
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                awaitBlocked(closer.get());
                assertFalse(close.isDone());
                assertFalse(store.contains(OLD));
                release.countDown();
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                        () -> write.get(5, TimeUnit.SECONDS)).getCause());
                close.get(5, TimeUnit.SECONDS);
                assertFalse(store.contains(A));
                // 已接收写入可以完成物理替换；关闭返回后再打开，文件必须稳定且可继续合并。
                var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
                assertEquals(2, reopened.size());
                assertTrue(reopened.contains(OLD));
                assertTrue(reopened.contains(A));
                assertTrue(reopened.markReady(B));
                byte[] after = Files.readAllBytes(file);
                assertThrows(IOException.class, () -> store.markReady(pack(10, 2)));
                assertArrayEquals(after, Files.readAllBytes(file));
                assertReopened(file, 3, OLD, A, B);
            } finally { release.countDown(); }
        }
    }

    private static Path seed(Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        Files.writeString(file, header() + "region=-4,7\n");
        return file;
    }
    private static String header() { return "# RoadWeaver Chunky planning coverage\nschema=2\nfingerprint=" + FINGERPRINT + "\n"; }
    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static void assertReopened(Path file, int count, long... regions) {
        var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(count, reopened.size());
        assertEquals(count, regions.length);
        for (long region : regions) assertTrue(reopened.contains(region));
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test writer release timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.BLOCKED, thread.getState(), "必须看到实际状态表监视器等待，不能按线程创建顺序猜测");
        assertTrue(java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(ReadinessCoverageStore.class.getName())
                        && (frame.getMethodName().equals("markReady") || frame.getMethodName().equals("close"))));
    }
}
