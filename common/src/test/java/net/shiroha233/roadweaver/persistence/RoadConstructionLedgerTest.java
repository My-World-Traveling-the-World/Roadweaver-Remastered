package net.shiroha233.roadweaver.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.persistence.chunk.RoadChunkStore;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint.ConstructionChange;
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

    @Test
    void 重开后同一身份的剖面跨度和占地变化仍可识别且旧台账不变() throws Exception {
        Path index = directory.resolve("roads");
        Path ledger = directory.resolve("constructed-chunks-v3.txt");
        ChunkPos originalChunk = new ChunkPos(0, 0), newFootprintChunk = new ChunkPos(0, 1);
        var original = road(64, false, false);
        var profileOnly = road(65, false, false);
        var spansOnly = road(64, true, false);
        var columnsOnly = road(64, false, true);
        var replacement = road(65, true, true);
        long fingerprint = RoadFingerprint.compute(original);
        assertNotEquals(0, fingerprint);
        assertEquals(fingerprint, RoadFingerprint.compute(replacement));
        assertTrue(RoadDataValidator.isValidFinalRoad(original));
        assertTrue(RoadDataValidator.isValidFinalRoad(replacement));
        try (var store = new RoadChunkStore(index)) {
            store.addRoad(original);
            assertEquals(1, store.queryChunk(originalChunk).size());
            assertTrue(store.queryChunk(newFootprintChunk).isEmpty());
        }
        var running = RoadConstructionLedger.load(ledger);
        running.markComplete(originalChunk, fingerprint);
        RoadConstructionLedger.flush(running, () -> ledger);
        String ledgerBytes = Files.readString(ledger);

        try (var reopened = new RoadChunkStore(index)) {
            // 和从 NBT 读回的旧内容对比，不能拿新对象和自己做对照。
            RoadData persisted = reopened.loadByFingerprint(fingerprint);
            assertEquals(original, persisted);
            assertEquals(Set.of(), RoadFingerprint.constructionChanges(persisted, road(64, false, false)));
            assertAll(
                    () -> assertEquals(Set.of(ConstructionChange.PROFILE),
                            RoadFingerprint.constructionChanges(persisted, profileOnly)),
                    () -> assertEquals(Set.of(ConstructionChange.SPANS),
                            RoadFingerprint.constructionChanges(persisted, spansOnly)),
                    () -> assertEquals(Set.of(ConstructionChange.STATIONS_AND_COLUMNS),
                            RoadFingerprint.constructionChanges(persisted, columnsOnly)));
            reopened.replaceRoad(fingerprint, replacement);
        }
        try (var reopened = new RoadChunkStore(index)) {
            assertEquals(replacement, reopened.loadByFingerprint(fingerprint));
            assertEquals(List.of(replacement), reopened.queryChunk(newFootprintChunk));
        }

        // 捕获兼容性边界：旧记录确实只识别道路键，影子不清空台账，也不把新邻区报完工。
        var oldLedger = RoadConstructionLedger.load(ledger);
        assertTrue(oldLedger.isComplete(originalChunk, RoadFingerprint.compute(replacement)));
        assertFalse(oldLedger.isComplete(newFootprintChunk, fingerprint));
        assertEquals(ledgerBytes, Files.readString(ledger));
    }

    private static RoadData road(int target, boolean bridge, boolean spill) {
        var stations = new ArrayList<RoadSegmentPlacement>();
        var targets = new ArrayList<Integer>();
        for (int i = 0; i < 8; i++) {
            // 四格路的索引还自动加两格圆盘；把原中心放在 z=13，只有新增的 z=16 会跨区块。
            var center = new BlockPos(i * 2, 64, 13);
            stations.add(new RoadSegmentPlacement(center, spill
                    ? List.of(center, center.south(), center.south(2), center.south(3)) : List.of(center)));
            targets.add(target);
        }
        var spans = bridge ? List.of(new RoadSpan(stations.get(2).middlePos(), stations.get(5).middlePos(),
                SpanType.BRIDGE)) : List.<RoadSpan>of();
        return new RoadData(4, 1, List.of(), List.of(), stations, spans, targets, 1, 2);
    }
}
