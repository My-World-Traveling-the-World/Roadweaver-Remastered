package net.shiroha233.roadweaver.persistence.files;

import com.sun.nio.file.ExtendedOpenOption;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.pathfinding.sampling.*;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实文件和 Windows 共享锁，区分正常缺失、读失败及替换失败；不以 Fake 返回代替落盘。 */
final class AccurateSampleStoreFileTest {
    private static final WorldgenFingerprint FINGERPRINT = new WorldgenFingerprint("a".repeat(64), 1, 3955);
    private static final long OLD = AccurateHeightSample.key(2, 3);
    private static final long NEW = AccurateHeightSample.key(18, 19);
    private static final AccurateHeightSample OLD_SAMPLE = new AccurateHeightSample(70, 54, 73);
    private static final AccurateHeightSample NEW_SAMPLE = new AccurateHeightSample(81, 62, 85);

    @Test
    void chunkColumnAndCrossTileGridSurviveReopen(@TempDir Path root) {
        AccurateHeightGridRequest request = new AccurateHeightGridRequest(-1, -1, 3, 3, 256);
        int[] surface = new int[9], floor = new int[9], motion = new int[9];
        for (int i = 0; i < 9; i++) {
            surface[i] = 70 + i;
            floor[i] = 40 + i;
            motion[i] = 90 + i;
        }
        AccurateHeightChunk chunk = chunk(-2, -2);
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            store.saveChunks(Map.of(ChunkPos.asLong(-2, -2), chunk));
            store.saveSamples(Map.of(OLD, OLD_SAMPLE));
            store.saveGrid(new AccurateHeightGrid(request, surface, floor, motion));
        }
        try (var reopened = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            AccurateHeightChunk loaded = reopened.loadChunks(List.of(ChunkPos.asLong(-2, -2))).get(ChunkPos.asLong(-2, -2));
            assertNotNull(loaded, "必须完整恢复整块，不能让空结果跳过逐列断言");
            for (int i = 0; i < 256; i++) {
                assertEquals(chunk.worldSurfaceWgAt(i), loaded.worldSurfaceWgAt(i));
                assertEquals(chunk.oceanFloorWgAt(i), loaded.oceanFloorWgAt(i));
                assertEquals(chunk.motionBlockingNoLeavesAt(i), loaded.motionBlockingNoLeavesAt(i));
            }
            assertEquals(Map.of(OLD, OLD_SAMPLE), reopened.loadSamples(List.of(OLD)));
            for (int i = 0; i < 9; i++) {
                long key = AccurateHeightSample.key(request.blockX(i), request.blockZ(i));
                assertEquals(Map.of(key, new AccurateHeightSample(surface[i], floor[i], motion[i])),
                        reopened.loadSamples(List.of(key)), "负坐标、跨瓦片和三通道必须按原坐标恢复");
            }
        }
    }

    @Test
    void unreadableTileIsNeverReplacedAndSameInstanceRetriesAfterUnlock(@TempDir Path root) throws Exception {
        seed(root);
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            Path path = store.tilePath(0, 0);
            byte[] before = Files.readAllBytes(path);
            try (var held = FileChannel.open(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(path), "先确认真实读故障已注入");
                assertEquals(Map.of(), store.loadSamples(List.of(OLD)));
                assertEquals(Map.of(), store.loadChunks(List.of(ChunkPos.asLong(0, 0))));
                // 三种写入口都不能把读不到的旧瓦片当空白瓦片覆盖。
                store.saveSamples(Map.of(NEW, NEW_SAMPLE));
                store.saveChunks(Map.of(ChunkPos.asLong(1, 1), chunk(1, 1)));
                store.saveGrid(grid());
            }
            assertArrayEquals(before, Files.readAllBytes(path));
            assertEquals(Map.of(OLD, OLD_SAMPLE), store.loadSamples(List.of(OLD, NEW)),
                    "读失败不能作为永久未命中缓存，解锁后同实例必须重读");
            store.saveSamples(Map.of(NEW, NEW_SAMPLE));
        }
        try (var reopened = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            assertEquals(Map.of(OLD, OLD_SAMPLE, NEW, NEW_SAMPLE), reopened.loadSamples(List.of(OLD, NEW)));
        }
    }

    @Test
    void failedWarmColumnWriteEvictsUnpersistedMutationAndCanRetry(@TempDir Path root) throws Exception {
        verifyFailedWrite(root, store -> store.saveSamples(Map.of(NEW, NEW_SAMPLE)));
    }

    @Test
    void failedWarmChunkWriteEvictsUnpersistedMutationAndCanRetry(@TempDir Path root) throws Exception {
        verifyFailedWrite(root, store -> store.saveChunks(Map.of(ChunkPos.asLong(1, 1), chunk(1, 1))));
    }

    @Test
    void failedWarmGridWriteEvictsUnpersistedMutationAndCanRetry(@TempDir Path root) throws Exception {
        verifyFailedWrite(root, store -> store.saveGrid(grid()));
    }

    private static void verifyFailedWrite(Path root, java.util.function.Consumer<FileBackedAccurateSampleStore> write)
            throws Exception {
        seed(root);
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            Path path = store.tilePath(0, 0);
            byte[] before = Files.readAllBytes(path);
            assertEquals(Map.of(OLD, OLD_SAMPLE), store.loadSamples(List.of(OLD)), "必须先命中内存瓦片再模拟替换失败");
            try (var held = FileChannel.open(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
                // 不锁读，只锁目标替换；冷读和写失败必须是两个独立故障面。
                assertArrayEquals(before, Files.readAllBytes(path));
                Path probe = Files.createTempFile(path.getParent(), "probe", ".tmp");
                try {
                    assertThrows(IOException.class, () -> FileStorageIO.moveIntoPlace(probe, path));
                } finally {
                    Files.deleteIfExists(probe);
                }
                assertDoesNotThrow(() -> write.accept(store), "可再生成缓存的 IO 错误只告警，不能中断整路采样");
                assertArrayEquals(before, Files.readAllBytes(path));
                assertEquals(Map.of(OLD, OLD_SAMPLE), store.loadSamples(List.of(OLD, NEW)),
                        "写失败必须驱逐被原地修改的内存瓦片，不能伪装为已保存");
            }
            write.accept(store);
            try (var reopened = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
                assertEquals(Map.of(OLD, OLD_SAMPLE, NEW, NEW_SAMPLE), reopened.loadSamples(List.of(OLD, NEW)),
                        "解锁后重试必须真实落盘并合并旧列");
            }
            try (var files = Files.list(path.getParent())) {
                assertEquals(0L, files.filter(p -> p.toString().endsWith(".tmp")).count(), "失败不能遗留临时文件");
            }
        }
    }

    @Test
    void damagedTileIsQuarantinedAndRebuilt(@TempDir Path root) throws Exception {
        seed(root);
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            Path path = store.tilePath(0, 0);
            byte[] damaged = Files.readAllBytes(path);
            damaged[0] ^= 1;
            Files.write(path, damaged);
            assertEquals(Map.of(), store.loadSamples(List.of(OLD)));
            assertFalse(Files.exists(path));
            try (var files = Files.list(path.getParent())) {
                List<Path> quarantine = files.filter(p -> p.getFileName().toString().contains(".corrupt.")).toList();
                assertEquals(1, quarantine.size());
                assertArrayEquals(damaged, Files.readAllBytes(quarantine.getFirst()));
            }
            store.saveSamples(Map.of(NEW, NEW_SAMPLE));
        }
        try (var reopened = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            assertEquals(Map.of(NEW, NEW_SAMPLE), reopened.loadSamples(List.of(OLD, NEW)));
        }
    }

    @Test
    void namespaceSwitchDoesNotMixSamplesAndReturnHitsOldVersion(@TempDir Path root) {
        seed(root);
        var other = new WorldgenFingerprint("b".repeat(64), 1, 3955);
        try (var store = new FileBackedAccurateSampleStore(root, other)) {
            assertEquals(Map.of(), store.loadSamples(List.of(OLD)));
            store.saveSamples(Map.of(OLD, NEW_SAMPLE));
        }
        try (var original = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            assertEquals(Map.of(OLD, OLD_SAMPLE), original.loadSamples(List.of(OLD)));
        }
    }

    @Test
    void everyFileApiRejectsAfterCloseIncludingEmptyRequests(@TempDir Path root) throws Exception {
        var store = new FileBackedAccurateSampleStore(root, FINGERPRINT);
        store.saveSamples(Map.of(OLD, OLD_SAMPLE));
        Path path = store.tilePath(0, 0);
        byte[] before = Files.readAllBytes(path);
        store.close();
        store.close();
        assertThrows(IllegalStateException.class, () -> store.loadSamples(List.of(OLD)));
        assertThrows(IllegalStateException.class, () -> store.loadChunks(List.of(0L)));
        assertThrows(IllegalStateException.class, () -> store.saveSamples(Map.of(NEW, NEW_SAMPLE)));
        assertThrows(IllegalStateException.class, () -> store.saveChunks(Map.of(ChunkPos.asLong(1, 1), chunk(1, 1))));
        assertThrows(IllegalStateException.class, () -> store.saveGrid(grid()));
        assertThrows(IllegalStateException.class, () -> store.loadSamples(List.of()));
        assertThrows(IllegalStateException.class, () -> store.loadChunks(List.of()));
        assertThrows(IllegalStateException.class, () -> store.saveSamples(Map.of()));
        assertThrows(IllegalStateException.class, () -> store.saveChunks(Map.of()));
        assertThrows(IllegalStateException.class, () -> store.saveGrid(null));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    private static void seed(Path root) {
        try (var store = new FileBackedAccurateSampleStore(root, FINGERPRINT)) {
            store.saveSamples(Map.of(OLD, OLD_SAMPLE));
        }
    }

    private static AccurateHeightChunk chunk(int x, int z) {
        int[] surface = new int[256], floor = new int[256], motion = new int[256];
        Arrays.fill(surface, NEW_SAMPLE.worldSurfaceWg());
        Arrays.fill(floor, NEW_SAMPLE.oceanFloorWg());
        Arrays.fill(motion, NEW_SAMPLE.motionBlockingNoLeaves());
        return new AccurateHeightChunk(x, z, surface, floor, motion);
    }

    private static AccurateHeightGrid grid() {
        return new AccurateHeightGrid(new AccurateHeightGridRequest(18, 19, 1, 1, 1),
                new int[]{81}, new int[]{62}, new int[]{85});
    }
}
