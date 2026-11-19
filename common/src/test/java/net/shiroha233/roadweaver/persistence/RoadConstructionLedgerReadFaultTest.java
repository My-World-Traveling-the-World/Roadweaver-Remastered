package net.shiroha233.roadweaver.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真实读失败后文件不能缩成新完成项，未知状态也不能放行施工或假报未完成。 */
class RoadConstructionLedgerReadFaultTest {
    @TempDir Path directory;
    private final ChunkPos first = new ChunkPos(-3, 7), second = new ChunkPos(9, -2), third = new ChunkPos(1, 1);

    @Test
    void lockedLedgerBlocksQueriesWritesAndRecoversAllEntries() throws Exception {
        Path path = prepared();
        byte[] before = Files.readAllBytes(path);
        RoadConstructionLedger.State failed;
        try (var lock = FileChannel.open(path, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            assertThrows(IOException.class, () -> Files.readAllBytes(path), "先证明文件锁真的阻止读取");
            failed = RoadConstructionLedger.load(path);
            assertThrows(IllegalStateException.class, () -> failed.isComplete(first, 101));
            assertThrows(IllegalStateException.class, () -> failed.markComplete(third, 303));
            RoadConstructionLedger.flush(failed, () -> fail("降级状态不得解析写路径"));
            assertFalse(RoadConstructionLedger.ensureAvailable(failed, () -> path,
                    System.nanoTime() + 10_000_000_000L), "文件仍锁住时重试不能放行");
        }
        assertArrayEquals(before, Files.readAllBytes(path));
        assertTrue(RoadConstructionLedger.ensureAvailable(failed, () -> path,
                System.nanoTime() + 30_000_000_000L));
        assertRecoveredAndAppend(failed, path);
    }

    @Test
    void malformedRowBlocksWholeSnapshotUntilValidFileReturns() throws Exception {
        Path path = prepared();
        String valid = Files.readString(path);
        Files.writeString(path, valid + "entry=bad,0,77\n");
        byte[] broken = Files.readAllBytes(path);
        var failed = RoadConstructionLedger.load(path);
        assertThrows(IllegalStateException.class, () -> failed.isComplete(second, 202));
        assertThrows(IllegalStateException.class, () -> failed.markComplete(third, 303));
        RoadConstructionLedger.flush(failed, () -> path);
        assertArrayEquals(broken, Files.readAllBytes(path), "不能用已解析的合法前缀覆盖未知原文件");
        assertFalse(RoadConstructionLedger.ensureAvailable(failed, () -> fail("五秒内不能热循环读盘"),
                System.nanoTime()));
        Files.writeString(path, valid);
        assertTrue(RoadConstructionLedger.ensureAvailable(failed, () -> path,
                System.nanoTime() + 10_000_000_000L));
        assertRecoveredAndAppend(failed, path);
    }

    @Test
    void incompleteRowAndUnreadablePathAreNotMissingLedger() throws Exception {
        Path path = prepared();
        Files.writeString(path, Files.readString(path) + "entry=1,2\n");
        var failed = RoadConstructionLedger.load(path);
        assertThrows(IllegalStateException.class, () -> failed.isComplete(first, 101));
        var directoryInsteadOfFile = RoadConstructionLedger.load(directory);
        assertThrows(IllegalStateException.class, () -> directoryInsteadOfFile.markComplete(first, 101));
        var missing = RoadConstructionLedger.load(directory.resolve("missing.txt"));
        assertFalse(missing.isComplete(first, 101), "只有确实不存在才是正常空台账");
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradedWorldStopsReplayAndRecoveryBeforeAnyWorldAccess() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var level = (net.minecraft.server.level.ServerLevel) unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        var dimension = net.minecraft.world.level.Level.class.getDeclaredField("dimension");
        dimension.setAccessible(true);
        dimension.set(level, net.minecraft.world.level.Level.OVERWORLD);
        var statesField = RoadConstructionLedger.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        var states = (java.util.Map<net.minecraft.server.level.ServerLevel, RoadConstructionLedger.State>) statesField.get(null);
        states.put(level, RoadConstructionLedger.load(directory));
        try {
            // 这里只提供维度身份，任何查区块/读存档/拿种子都会失败，证明守卫位于世界副作用之前。
            assertFalse(RoadConstructionLedger.isAvailable(level));
            assertEquals(net.shiroha233.roadweaver.pathlogic.PathFeature.LoadedPlacement.DEFERRED,
                    net.shiroha233.roadweaver.pathlogic.PathFeature.replayLoadedChunk(level, first, 17));
            assertDoesNotThrow(() -> net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService.recover(level));
        } finally {
            states.remove(level);
            net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService.clear(level);
        }
    }

    private Path prepared() {
        Path path = directory.resolve("constructed-chunks-v3.txt");
        var state = RoadConstructionLedger.load(path);
        state.markComplete(first, 101);
        state.markComplete(second, 202);
        RoadConstructionLedger.flush(state, () -> path);
        var reopened = RoadConstructionLedger.load(path);
        assertTrue(reopened.isComplete(first, 101));
        assertTrue(reopened.isComplete(second, 202));
        return path;
    }

    private void assertRecoveredAndAppend(RoadConstructionLedger.State state, Path path) {
        assertTrue(state.isComplete(first, 101), "完整重读后原项必须恢复，不能只开放写入");
        assertTrue(state.isComplete(second, 202));
        state.markComplete(third, 303);
        RoadConstructionLedger.flush(state, () -> path);
        var reopened = RoadConstructionLedger.load(path);
        assertTrue(reopened.isComplete(first, 101));
        assertTrue(reopened.isComplete(second, 202));
        assertTrue(reopened.isComplete(third, 303));
    }
}
