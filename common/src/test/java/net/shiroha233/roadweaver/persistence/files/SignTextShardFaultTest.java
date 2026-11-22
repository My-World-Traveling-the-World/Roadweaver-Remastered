/* 文件职责：真实分片锁不许清空或隔离原件，解锁后验证原顺序与更新。 */
package net.shiroha233.roadweaver.persistence.files;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SignTextShardFaultTest {
    @Test void lockedShardRetainsTextAndUnlockedMergePreservesOrder(@TempDir Path root) throws Exception {
        Path file = root.resolve("0.json");
        var a = new SignTextFileStorage.PendingSignWrite(BlockPos.ZERO, 0, "原甲");
        var b = new SignTextFileStorage.PendingSignWrite(BlockPos.ZERO.east(), 0, "乙");
        var update = new SignTextFileStorage.PendingSignWrite(BlockPos.ZERO, 0, "新甲");
        SignTextFileStorage.mergeChunk(file, List.of(a,b));
        byte[] before = Files.readAllBytes(file);
        try (var lock = FileChannel.open(file, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            assertThrows(java.io.UncheckedIOException.class, () -> SignTextFileStorage.loadChunk(file));
            assertThrows(java.io.UncheckedIOException.class, () -> SignTextFileStorage.mergeChunk(file, List.of(update)));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
        try (var files = Files.list(root)) { assertEquals(List.of(file), files.toList()); }
        SignTextFileStorage.mergeChunk(file, List.of(update));
        assertEquals(List.of("乙", "新甲"), SignTextFileStorage.loadChunk(file).stream()
                .map(SignTextFileStorage.PendingSignText::payload).toList());
    }

    @Test void invalidJsonIsQuarantinedWithoutDiscardingItsBytes(@TempDir Path root) throws Exception {
        Path file = root.resolve("broken.json");
        Files.writeString(file, "{broken");
        assertTrue(SignTextFileStorage.loadChunk(file).isEmpty());
        assertFalse(Files.exists(file));
        try (var files = Files.list(root)) {
            var copies = files.toList();
            assertEquals(1, copies.size());
            assertEquals("{broken", Files.readString(copies.getFirst()));
        }
    }
}
