package net.shiroha233.roadweaver.planning.path;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the batch persistence rewrite of
 * {@link PlannedPathCache#register}: memory entries are authoritative and
 * synchronous, disk writes fan out to a small bounded writer pool, and a
 * failed save never removes the in-memory path.
 */
final class PlannedPathCacheBatchWriteTest {

    private static StructureConnection connection(int x) {
        return new StructureConnection(new BlockPos(x, 64, 0), new BlockPos(x + 100, 64, 400));
    }

    private static List<BlockPos> path(int x) {
        return List.of(new BlockPos(x, 64, 0), new BlockPos(x + 50, 64, 200),
                new BlockPos(x + 100, 64, 400));
    }

    /** Fake store: records writer threads, optionally fails one key, counts saves. */
    private static final class RecordingStore implements PlannedPathStore {
        private final CountDownLatch latch;
        private final boolean failKey;
        private final PlannedPathKey failingKey;
        private final Set<String> writerThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger saves = new AtomicInteger();

        private RecordingStore(int expectedSaves, boolean failKey, PlannedPathKey failingKey) {
            this.latch = new CountDownLatch(expectedSaves);
            this.failKey = failKey;
            this.failingKey = failingKey;
        }

        @Override
        public Optional<List<BlockPos>> load(PlannedPathKey key, String fingerprint) {
            return Optional.empty();
        }

        @Override
        public void save(PlannedPathKey key, String fingerprint, List<BlockPos> path) throws IOException {
            writerThreads.add(Thread.currentThread().getName());
            try {
                saves.incrementAndGet();
                if (failKey && key.equals(failingKey)) {
                    throw new IOException("regression: deliberate persistence failure");
                }
            } finally {
                latch.countDown();
            }
        }

        @Override
        public void delete(PlannedPathKey key) {
        }
    }

    @Test
    void batchRegisterWritesOffThePlanningThreadWithinCapacity() throws Exception {
        int routes = 8;
        Map<StructureConnection, List<BlockPos>> paths = new HashMap<>();
        for (int i = 0; i < routes; i++) {
            paths.put(connection(i * 10), path(i * 10));
        }
        RecordingStore store = new RecordingStore(routes, false, null);
        ConcurrentHashMap<PlannedPathKey, PlannedPathCache.Entry> memory = new ConcurrentHashMap<>();

        PlannedPathCache.registerInternal(memory, store, "fingerprint", paths, null);

        // Memory is authoritative and synchronous: every route is readable the
        // moment register returns, even though disk writes are still in flight.
        assertEquals(routes, memory.size());
        for (StructureConnection connection : paths.keySet()) {
            PlannedPathCache.Entry entry = memory.get(PlannedPathKey.of(connection));
            assertNotNull(entry);
            assertEquals(paths.get(connection), entry.path());
        }

        assertTrue(store.latch.await(10, TimeUnit.SECONDS), "all persistence writes must finish");
        assertEquals(routes, store.saves.get());
        int capacity = PlannedPathCache.writeExecutorCapacity();
        assertTrue(capacity >= 1 && capacity <= 4, "writer pool must be bounded: " + capacity);
        assertTrue(store.writerThreads.size() <= capacity,
                "distinct writer threads " + store.writerThreads.size()
                        + " must not exceed pool capacity " + capacity);
    }

    @Test
    void failedPersistenceKeepsTheMemoryPath() throws Exception {
        Map<StructureConnection, List<BlockPos>> paths = new HashMap<>();
        StructureConnection ok = connection(1000);
        StructureConnection failing = connection(2000);
        paths.put(ok, path(1000));
        paths.put(failing, path(2000));

        RecordingStore store = new RecordingStore(2, true, PlannedPathKey.of(failing));
        ConcurrentHashMap<PlannedPathKey, PlannedPathCache.Entry> memory = new ConcurrentHashMap<>();

        PlannedPathCache.registerInternal(memory, store, "fingerprint", paths, null);

        // The failing route's memory entry exists immediately and survives the
        // failed save.
        assertEquals(path(2000), memory.get(PlannedPathKey.of(failing)).path());
        assertTrue(store.latch.await(10, TimeUnit.SECONDS));
        assertEquals(path(2000), memory.get(PlannedPathKey.of(failing)).path());
        assertEquals(2, store.saves.get());
    }
}
