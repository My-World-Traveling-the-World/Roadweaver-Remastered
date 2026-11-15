package net.shiroha233.roadweaver.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RoadConstructionLedgerTest {
    @TempDir Path directory;

    @Test
    void 完工写入必须能通过新台账读回() throws Exception {
        Path file = directory.resolve("constructed-chunks-v3.txt");
        ChunkPos chunk = new ChunkPos(-3, 7);
        long fingerprint = 123_456_789L;
        var running = RoadConstructionLedger.load(file);
        assertFalse(running.isComplete(chunk, fingerprint));
        running.markComplete(chunk, fingerprint);
        assertTrue(running.isComplete(chunk, fingerprint));
        RoadConstructionLedger.flush(running, () -> file);

        // 重读文件构造独立状态，不能用仍在内存里的对象冒充持久化。
        assertTrue(Files.isRegularFile(file), "M4：新增完工条目应触发写盘");
        String contents = Files.readString(file);
        assertTrue(contents.lines().anyMatch("schema=3"::equals));
        assertTrue(contents.lines().anyMatch("entry=-3,7,123456789"::equals));
        var reopened = RoadConstructionLedger.load(file);
        assertTrue(reopened.isComplete(chunk, fingerprint));
        assertFalse(reopened.isComplete(new ChunkPos(3, 7), fingerprint));
        assertFalse(reopened.isComplete(chunk, fingerprint + 1));
    }

    @Test
    void 重复标记不需要再次解析路径() {
        Path file = directory.resolve("ledger.txt");
        ChunkPos chunk = new ChunkPos(4, -7);
        var state = RoadConstructionLedger.load(file);
        state.markComplete(chunk, 456L);
        RoadConstructionLedger.flush(state, () -> file);
        assertTrue(RoadConstructionLedger.load(file).isComplete(chunk, 456L));
        state.markComplete(chunk, 456L);
        RoadConstructionLedger.flush(state, () -> fail("重复标记不能触发写盘"));
    }
}
