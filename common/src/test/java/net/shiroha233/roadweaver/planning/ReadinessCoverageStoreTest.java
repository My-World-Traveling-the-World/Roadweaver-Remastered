/* 文件职责：使用真实 READY 文件核验原格式、指纹与重开，避免仅测试内存集合。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessCoverageStoreTest {
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void missingFileIsEmptyAndDoesNotWriteAnything(@TempDir Path root) throws Exception {
        Path file = root.resolve("not-yet-created/ready.txt");
        AtomicInteger writes = new AtomicInteger();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, s) -> writes.incrementAndGet());
        assertEquals(0, store.size());
        assertFalse(store.contains(pack(0, 0)));
        assertEquals(0, writes.get());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void goldenSchemaTwoBytesAndNegativeCoordinatesSurviveReopen(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        AtomicInteger writes = new AtomicInteger();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, s) -> {
            writes.incrementAndGet();
            FileStorageIO.writeStringAtomic(p, s);
        });
        int[][] regions = {{1, -2}, {-3, 4}, {Integer.MAX_VALUE, Integer.MIN_VALUE},
                {0, 0}, {Integer.MIN_VALUE, Integer.MAX_VALUE}};
        for (int[] region : regions) assertTrue(store.markReady(pack(region[0], region[1])));
        String expected = "# RoadWeaver Chunky planning coverage\nschema=2\nfingerprint=" + FINGERPRINT + "\n"
                + "region=-2147483648,2147483647\nregion=-3,4\nregion=0,0\nregion=1,-2\nregion=2147483647,-2147483648\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        assertEquals(5, store.size());
        assertFalse(store.markReady(pack(-3, 4)));
        assertEquals(5, writes.get(), "已落盘区域不能重复写整份索引");
        var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(5, reopened.size());
        for (int[] region : regions) assertTrue(reopened.contains(pack(region[0], region[1])));
        assertFalse(reopened.contains(pack(4, -3)));
        assertFalse(reopened.markReady(pack(1, -2)));
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    }

    @Test
    void legacyWhitespaceCommentsAndLineEndingsKeepOriginalMeaning(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        String fixture = " # old comment\r schema=2 \r\nfingerprint=" + FINGERPRINT
                + "\nunknown=future-key\rregion= -7 , 8 \nregion=-7,8\rregion=incomplete\n";
        Files.writeString(file, fixture);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(1, store.size());
        assertTrue(store.contains(pack(-7, 8)));
        assertEquals(fixture, Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void staleSchemaIsIgnoredWithoutEagerlyRewritingTheFile(int schema, @TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        String fixture = "schema=" + schema + "\nfingerprint=" + FINGERPRINT + "\nregion=3,4\n";
        Files.writeString(file, fixture);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(0, store.size());
        assertFalse(store.contains(pack(3, 4)));
        assertEquals(fixture, Files.readString(file));
        assertTrue(store.markReady(pack(9, -2)));
        var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(1, reopened.size());
        assertTrue(reopened.contains(pack(9, -2)));
        assertFalse(reopened.contains(pack(3, 4)));
    }

    @Test
    void differentFingerprintDoesNotAdoptOldCoverage(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        String fixture = "schema=2\nfingerprint=" + "b".repeat(64) + "\nregion=3,4\n";
        Files.writeString(file, fixture);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(0, store.size());
        assertFalse(store.contains(pack(3, 4)));
        assertEquals(fixture, Files.readString(file));
    }

    @Test
    void malformedFileNeverPublishesItsValidPrefix(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        String fixture = "schema=2\nfingerprint=" + FINGERPRINT + "\nregion=3,4\nregion=bad,5\n";
        Files.writeString(file, fixture);
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(0, store.size());
        assertFalse(store.contains(pack(3, 4)));
        assertEquals(fixture, Files.readString(file));
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
