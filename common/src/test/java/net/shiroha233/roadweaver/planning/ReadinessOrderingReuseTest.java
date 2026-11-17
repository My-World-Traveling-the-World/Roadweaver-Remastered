/* 文件职责：把有序快照复用与冻结旧实现逐次对拍，覆盖重开、负坐标、重复和真实写入失败。 */
package net.shiroha233.roadweaver.planning;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessOrderingReuseTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedOrderMatchesFrozenFilesThroughFailuresAndReopen(boolean seeded, @TempDir Path root) throws Exception {
        Path oldPath = root.resolve("old.txt"), newPath = root.resolve("new.txt");
        if (seeded) {
            String seed = "# RoadWeaver Chunky planning coverage\nschema=2\nfingerprint=test\nregion=-3,4\nregion=2,-1\n";
            Files.writeString(oldPath, seed); Files.writeString(newPath, seed);
        }
        AtomicBoolean failWrite = new AtomicBoolean();
        FrozenReadinessCoverageStore.Writer oldWriter = (path, text) -> {
            if (failWrite.get()) throw new IOException("模拟原子替换失败");
            FileStorageIO.writeStringAtomic(path, text);
        };
        ReadinessCoverageStore.Writer newWriter = oldWriter::write;
        var baseline = FrozenReadinessCoverageStore.open(oldPath, "test", oldWriter, null);
        var current = ReadinessCoverageStore.open(newPath, "test", newWriter, null);
        Set<Long> accepted = new HashSet<>();
        if (seeded) accepted.addAll(List.of(pack(-3, 4), pack(2, -1)));
        Random random = new Random(0x4519);
        for (int i = 0; i < 96; i++) {
            long region = i % 9 == 0 ? pack(-3, 4) : pack(random.nextInt(1000) - 500, random.nextInt(1000) - 500);
            if (i % 17 == 3 && !accepted.contains(region)) {
                byte[] before = Files.readAllBytes(newPath);
                failWrite.set(true);
                var oldAttempt = baseline; var newAttempt = current;
                assertThrows(IOException.class, () -> oldAttempt.markReady(region));
                assertThrows(IOException.class, () -> newAttempt.markReady(region));
                assertFalse(current.contains(region));
                assertArrayEquals(before, Files.readAllBytes(newPath));
                assertArrayEquals(Files.readAllBytes(oldPath), Files.readAllBytes(newPath));
                failWrite.set(false);
            }
            assertEquals(baseline.markReady(region), current.markReady(region), "第" + i + "步");
            accepted.add(region);
            assertEquals(accepted.size(), current.size());
            assertArrayEquals(Files.readAllBytes(oldPath), Files.readAllBytes(newPath), "每次提交的字节与冻结实现一致");
            if (i % 11 == 7) {
                current.close(); baseline.close();
                current = ReadinessCoverageStore.open(newPath, "test", newWriter, null);
                baseline = FrozenReadinessCoverageStore.open(oldPath, "test", oldWriter, null);
                assertEquals(accepted.size(), current.size());
                for (long key : accepted) assertTrue(current.contains(key), "重开后丢失区域");
            }
        }
        current.close(); baseline.close();
    }
    private static long pack(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
}
