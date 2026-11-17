/* 文件职责：真实文件故障直接驱动生产完成/覆盖复用边界，验证限次重试与清理接线。 */
package net.shiroha233.roadweaver.planning;

import com.sun.nio.file.ExtendedOpenOption;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Entry;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.State;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessPersistenceCompletionTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final long OLD = pack(-4, 7), NEW = pack(5, -8);
    private record Fixture(Object world, Object key, Entry entry, ReadinessCoverageStore store, Path file) { }

    @BeforeEach
    @AfterEach
    void clear() {
        RoadLifecycle.beginStarting();
        RoadPlanningReadinessIndex.clearRuntime();
    }

    @Test
    void realReplacementFailureRequeuesAndUnlockAllowsDurableReady(@TempDir Path root) throws Exception {
        Fixture f = fixture(root, FileStorageIO::writeStringAtomic);
        byte[] before = Files.readAllBytes(f.file);
        try (var held = FileChannel.open(f.file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertFalse(finish(f, ThreadPoolManager.currentEpoch(), null, true));
            assertEquals(State.QUEUED, f.entry.state());
            assertInstanceOf(IOException.class, f.entry.lastFailure);
            assertEquals(1, f.entry.attempts);
            assertNull(f.entry.planningFuture);
            assertEquals(java.util.List.of(f.key), java.util.List.copyOf(collection("QUEUE")));
            assertTrue(collection("ACTIVE").isEmpty());
            assertFalse(f.store.contains(NEW));
            assertArrayEquals(before, Files.readAllBytes(f.file));
        }
        resume(f);
        assertTrue(finish(f, ThreadPoolManager.currentEpoch(), null, true));
        assertEquals(State.READY, f.entry.state());
        assertNull(f.entry.lastFailure);
        assertNull(f.entry.planningFuture);
        assertTrue(collection("QUEUE").isEmpty());
        assertTrue(collection("ACTIVE").isEmpty());
        assertDurable(f.file);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void writeFailureUsesOriginalThreeAttemptLimitAndRetainsExactCause(int attempts, @TempDir Path root) throws Exception {
        IOException failure = new IOException("persist-attempt-" + attempts);
        Fixture f = fixture(root, (p, s) -> { throw failure; });
        f.entry.attempts = attempts;
        assertFalse(finish(f, ThreadPoolManager.currentEpoch(), null, true));
        assertSame(failure, f.entry.lastFailure);
        assertEquals(attempts, f.entry.attempts);
        assertEquals(attempts < 3 ? State.QUEUED : State.FAILED, f.entry.state());
        assertEquals(attempts < 3 ? java.util.List.of(f.key) : java.util.List.of(), java.util.List.copyOf(collection("QUEUE")));
        assertTrue(collection("ACTIVE").isEmpty());
        assertFalse(f.store.contains(NEW));
        assertEquals(1, ReadinessCoverageStore.open(f.file, FINGERPRINT).size());
    }

    @Test
    void unexpectedWriterFailureAlsoBecomesAnIdentifiableRetry(@TempDir Path root) throws Exception {
        RuntimeException failure = new IllegalStateException("writer-runtime-fault");
        Fixture f = fixture(root, (p, s) -> { throw failure; });
        assertFalse(finish(f, ThreadPoolManager.currentEpoch(), null, true));
        assertEquals(State.QUEUED, f.entry.state());
        assertSame(failure, f.entry.lastFailure);
        assertFalse(f.store.contains(NEW));
    }

    @Test
    void adoptionReturnsFalseOnRealWriteFailureAndDoesNotPublishEntry(@TempDir Path root) throws Exception {
        Fixture f = fixture(root, FileStorageIO::writeStringAtomic);
        try (var held = FileChannel.open(f.file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertFalse(adopt(f));
            assertFalse(f.store.contains(NEW));
            assertEquals(State.PLANNING, f.entry.state());
            assertEquals(1, ReadinessCoverageStore.open(f.file, FINGERPRINT).size());
        }
        assertTrue(adopt(f));
        assertEquals(State.READY, f.entry.state());
        assertDurable(f.file);
        assertTrue(adopt(f), "已提交覆盖的复用仍成功，不把重复提交返回 false 当成失败");
        f.store.close();
        assertFalse(adopt(f));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void planningOrFinalPathFailureNeverAttemptsToWriteCoverage(int kind, @TempDir Path root) throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Fixture f = fixture(root, (p, s) -> { writes.incrementAndGet(); throw new IOException("must-not-write"); });
        Throwable failure = kind == 0 ? new IllegalArgumentException("planning-failed") : null;
        assertFalse(finish(f, ThreadPoolManager.currentEpoch(), failure, false));
        assertEquals(State.QUEUED, f.entry.state());
        if (kind == 0) assertSame(failure, f.entry.lastFailure);
        else {
            assertInstanceOf(IllegalStateException.class, f.entry.lastFailure);
            assertEquals("final persisted path verification failed", f.entry.lastFailure.getMessage());
        }
        assertEquals(0, writes.get());
        assertFalse(f.store.contains(NEW));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void stoppingStaleEpochAndReplacedEntryAreIgnoredBeforeWrite(int kind, @TempDir Path root) throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Fixture f = fixture(root, (p, s) -> writes.incrementAndGet());
        long epoch = ThreadPoolManager.currentEpoch();
        Entry current = f.entry;
        if (kind == 0) RoadLifecycle.beginStopping();
        if (kind == 1) epoch--;
        if (kind == 2) {
            table().clear();
            current = new Entry(State.QUEUED);
            table().putIfAbsent(f.key, current);
        }
        assertFalse(finish(f, epoch, null, true));
        assertEquals(0, writes.get());
        assertNotNull(f.entry.planningFuture);
        assertNull(f.entry.lastFailure);
        assertSame(current, table().get(f.key));
        assertEquals(kind == 2 ? State.QUEUED : State.PLANNING, current.state());
        assertFalse(f.store.contains(NEW));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void replacementDuringWriteCannotPublishOrRequeueIntoTheNewEntry(int failWrite, @TempDir Path root) throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        AtomicReference<Entry> replacement = new AtomicReference<>();
        Fixture f = fixture(root, (p, s) -> {
            try {
                table().clear();
                Entry fresh = new Entry(State.QUEUED);
                replacement.set(fresh);
                table().putIfAbsent(ref.get().key, fresh);
            } catch (Exception e) { throw new IOException(e); }
            if (failWrite == 1) throw new IOException("failed-after-replacement");
            FileStorageIO.writeStringAtomic(p, s);
        });
        ref.set(f);
        assertFalse(finish(f, ThreadPoolManager.currentEpoch(), null, true));
        assertSame(replacement.get(), table().get(f.key));
        assertEquals(State.QUEUED, replacement.get().state());
        assertEquals(State.PLANNING, f.entry.state());
        assertNull(f.entry.lastFailure);
        assertTrue(collection("QUEUE").isEmpty());
    }

    @Test
    void actualClearDrainsWorldStoreAndLateFailureCannotRequeueOldKey(@TempDir Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), clearing = new CountDownLatch(1);
        AtomicReference<Thread> clearer = new AtomicReference<>();
        Fixture f = fixture(root, (p, s) -> {
            entered.countDown(); await(release); FileStorageIO.writeStringAtomic(p, s);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var write = executor.submit(() -> finish(f, ThreadPoolManager.currentEpoch(), null, true));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var clear = executor.submit(() -> {
                    clearer.set(Thread.currentThread()); clearing.countDown(); RoadPlanningReadinessIndex.clearRuntime();
                });
                assertTrue(clearing.await(5, TimeUnit.SECONDS));
                awaitBlocked(clearer.get());
                assertFalse(clear.isDone());
                assertFalse(f.store.contains(OLD));
                release.countDown();
                assertFalse(write.get(5, TimeUnit.SECONDS));
                clear.get(5, TimeUnit.SECONDS);
                assertEquals(0, table().size());
                assertTrue(collection("QUEUE").isEmpty());
                assertTrue(collection("ACTIVE").isEmpty());
                assertTrue(worlds().isEmpty());
                assertFalse(f.store.contains(NEW));
                assertThrows(IOException.class, () -> f.store.markReady(pack(9, 1)));
                assertDurable(f.file);
            } finally { release.countDown(); }
        }
    }

    private static Fixture fixture(Path root, ReadinessCoverageStore.Writer writer) throws Exception {
        Path file = root.resolve("chunky-ready-regions-v2.txt");
        Files.writeString(file, "# RoadWeaver Chunky planning coverage\nschema=2\nfingerprint=" + FINGERPRINT + "\nregion=-4,7\n");
        Class<?> worldClass = Class.forName(RoadPlanningReadinessIndex.class.getName() + "$WorldKey");
        var worldConstructor = worldClass.getDeclaredConstructor(String.class, long.class);
        worldConstructor.setAccessible(true);
        Object world = worldConstructor.newInstance("minecraft:overworld", 41L);
        var keyConstructor = Class.forName(RoadPlanningReadinessIndex.class.getName() + "$RegionKey")
                .getDeclaredConstructor(worldClass, int.class, int.class);
        keyConstructor.setAccessible(true);
        Object key = keyConstructor.newInstance(world, 5, -8);
        Entry entry = new Entry(State.PLANNING);
        entry.attempts = 1;
        entry.planningFuture = new CompletableFuture<>();
        assertNull(table().putIfAbsent(key, entry));
        assertTrue(collection("ACTIVE").add(key));
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, writer);
        assertNull(worlds().put(world, store));
        return new Fixture(world, key, entry, store, file);
    }

    private static boolean finish(Fixture f, long epoch, Throwable failure, boolean pathsReady) throws Exception {
        var method = RoadPlanningReadinessIndex.class.getDeclaredMethod("finishPlanning", ReadinessCoverageStore.class,
                f.key.getClass(), Entry.class, long.class, Throwable.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, f.store, f.key, f.entry, epoch, failure, pathsReady);
    }
    private static boolean adopt(Fixture f) throws Exception {
        var method = RoadPlanningReadinessIndex.class.getDeclaredMethod("adoptCoverageReady", ReadinessCoverageStore.class, f.key.getClass());
        method.setAccessible(true);
        return (boolean) method.invoke(null, f.store, f.key);
    }
    private static void resume(Fixture f) throws Exception {
        assertTrue(collection("QUEUE").remove(f.key));
        assertTrue(table().transition(f.key, f.entry, State.PLANNING));
        f.entry.attempts++;
        f.entry.planningFuture = new CompletableFuture<>();
        collection("ACTIVE").add(f.key);
    }
    @SuppressWarnings("unchecked")
    private static ReadinessRegionTable<Object, Object> table() throws Exception { return (ReadinessRegionTable<Object, Object>) field("ENTRIES"); }
    @SuppressWarnings("unchecked")
    private static Collection<Object> collection(String name) throws Exception { return (Collection<Object>) field(name); }
    @SuppressWarnings("unchecked")
    private static Map<Object, ReadinessCoverageStore> worlds() throws Exception { return (Map<Object, ReadinessCoverageStore>) field("WORLDS"); }
    private static Object field(String name) throws Exception {
        var field = RoadPlanningReadinessIndex.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static void assertDurable(Path file) {
        var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(2, reopened.size());
        assertTrue(reopened.contains(OLD));
        assertTrue(reopened.contains(NEW));
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test release timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.BLOCKED, thread.getState());
        assertTrue(java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(ReadinessCoverageStore.class.getName()) && frame.getMethodName().equals("close")));
    }
}
