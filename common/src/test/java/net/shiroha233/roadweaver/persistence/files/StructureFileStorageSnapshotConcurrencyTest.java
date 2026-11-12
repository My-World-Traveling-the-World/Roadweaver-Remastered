/* 文件职责：StructureFileStorage 只读快照缓存（S1）的并发/隔离确定性回归。 */
package net.shiroha233.roadweaver.persistence.files;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.StructureLocationData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无 ServerLevel 的确定性并发回归，覆盖 0.8.248 S1 只读快照缓存：
 * <ul>
 *   <li>并发 get 命中同一版本时内容一致，且 loader 只执行一次（读盘去重）；</li>
 *   <li>save → bump 后下一次 get 读到新状态；</li>
 *   <li>mutation 后旧快照不会被返回（版本失效）；</li>
 *   <li>共享的不可变快照被多线程读不会抛 ConcurrentModificationException。</li>
 * </ul>
 */
final class StructureFileStorageSnapshotConcurrencyTest {

    private static final int THREADS = 8;

    private static StructureInfo infoAt(int x, int z) {
        return new StructureInfo(new BlockPos(x, 64, z), "test:village");
    }

    private static StructureFileStorage.StructureSnapshot snapshotOf(List<StructureInfo> infos) {
        return new StructureFileStorage.StructureSnapshot(
                new StructureLocationData(new ArrayList<>(), new ArrayList<>(infos)),
                Map.of());
    }

    @Test
    void concurrentGetReturnsConsistentSnapshot() throws Exception {
        AtomicReference<List<StructureInfo>> current =
                new AtomicReference<>(List.of(infoAt(1, 2), infoAt(3, 4)));
        AtomicInteger loads = new AtomicInteger();
        VersionedSnapshotCache<String, StructureFileStorage.StructureSnapshot> cache =
                new VersionedSnapshotCache<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CyclicBarrier barrier = new CyclicBarrier(THREADS);
            List<Future<StructureFileStorage.StructureSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return cache.getOrLoad("world", () -> {
                        loads.incrementAndGet();
                        return snapshotOf(current.get());
                    });
                }));
            }
            StructureFileStorage.StructureSnapshot first = futures.get(0).get(5, TimeUnit.SECONDS);
            assertEquals(2, first.locations().structureInfos().size());
            for (Future<StructureFileStorage.StructureSnapshot> future : futures) {
                StructureFileStorage.StructureSnapshot snapshot = future.get(5, TimeUnit.SECONDS);
                // 内容一致即可；对象身份允许共享，这里断言内容逐点一致。
                assertEquals(2, snapshot.locations().structureInfos().size());
                assertEquals(first.locations().structureInfos().get(0).pos(),
                        snapshot.locations().structureInfos().get(0).pos());
                assertEquals(first.locations().structureInfos().get(1).pos(),
                        snapshot.locations().structureInfos().get(1).pos());
            }
            assertEquals(1, loads.get(), "同一版本的并发 get 只允许一次读盘(loader)");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void saveBumpsVersionAndNextGetSeesFresh() {
        AtomicReference<List<StructureInfo>> current =
                new AtomicReference<>(List.of(infoAt(1, 2)));
        AtomicInteger loads = new AtomicInteger();
        VersionedSnapshotCache<String, StructureFileStorage.StructureSnapshot> cache =
                new VersionedSnapshotCache<>();

        StructureFileStorage.StructureSnapshot before =
                cache.getOrLoad("world", () -> {
                    loads.incrementAndGet();
                    return snapshotOf(current.get());
                });
        assertEquals(1, before.locations().structureInfos().size());
        assertEquals(new BlockPos(1, 64, 2), before.locations().structureInfos().get(0).pos());

        // 模拟 save：写新状态后 bump 版本号。
        current.set(List.of(infoAt(9, 9)));
        cache.bump("world");

        StructureFileStorage.StructureSnapshot after =
                cache.getOrLoad("world", () -> {
                    loads.incrementAndGet();
                    return snapshotOf(current.get());
                });
        assertEquals(1, after.locations().structureInfos().size());
        assertEquals(new BlockPos(9, 64, 9), after.locations().structureInfos().get(0).pos());
        assertEquals(2, loads.get(), "bump 之后必须重新读盘(loader)");
    }

    @Test
    void noStaleLeakAcrossMutation() {
        AtomicReference<List<StructureInfo>> current =
                new AtomicReference<>(List.of(infoAt(1, 2)));
        VersionedSnapshotCache<String, StructureFileStorage.StructureSnapshot> cache =
                new VersionedSnapshotCache<>();

        StructureFileStorage.StructureSnapshot stale =
                cache.getOrLoad("world", () -> snapshotOf(current.get()));
        assertTrue(stale.locations().structureInfos().get(0).pos().equals(new BlockPos(1, 64, 2)));

        current.set(List.of(infoAt(7, 7)));
        cache.bump("world");

        StructureFileStorage.StructureSnapshot fresh =
                cache.getOrLoad("world", () -> snapshotOf(current.get()));
        assertNotSame(stale, fresh, "mutation 后旧缓存实例不得被返回");
        assertEquals(new BlockPos(7, 64, 7), fresh.locations().structureInfos().get(0).pos());
    }

    @Test
    void immutableSnapshotSharedAcrossThreads() throws Exception {
        List<StructureInfo> infos = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            infos.add(infoAt(i, i * 3));
        }
        StructureFileStorage.StructureSnapshot shared =
                new StructureFileStorage.StructureSnapshot(
                        new StructureLocationData(new ArrayList<>(), infos),
                        Map.of());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    int count = 0;
                    // 并发只读迭代共享的不可变列表，不得抛 ConcurrentModificationException。
                    for (StructureInfo info : shared.locations().structureInfos()) {
                        if (info != null && info.pos() != null) count++;
                    }
                    return count;
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(200, future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertFalse(shared.locations().structureInfos().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> shared.locations().structureInfos().add(infoAt(1, 1)),
                "不可变快照的内部列表必须拒绝外部修改");
        assertThrows(UnsupportedOperationException.class,
                () -> shared.locations().structureLocations().add(new BlockPos(0, 0, 0)),
                "不可变快照的位置列表必须拒绝外部修改");
        assertThrows(UnsupportedOperationException.class,
                () -> shared.sources().put(1L, 1),
                "不可变快照的 sources 必须拒绝外部修改");
    }
}
