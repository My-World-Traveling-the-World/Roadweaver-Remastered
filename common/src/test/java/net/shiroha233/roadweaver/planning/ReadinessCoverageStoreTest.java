/* 文件职责：使用真实 READY 文件核验原格式、指纹与重开，避免仅测试内存集合。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.sun.nio.file.ExtendedOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessCoverageStoreTest {
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void failedReplacementKeepsMemoryAndFileUnchangedAndRetriesAfterUnlock(@TempDir Path root) throws Exception {
        Path file = root.resolve("ready.txt");
        var store = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertTrue(store.markReady(pack(-4, 7)));
        byte[] before = Files.readAllBytes(file);
        try (var held = FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            Path probe = Files.createTempFile(root, "probe", ".tmp");
            try {
                assertThrows(IOException.class, () -> FileStorageIO.moveIntoPlace(probe, file),
                        "先确认真实目标替换被阻止，而不是只由 Fake 抛错");
            } finally { Files.deleteIfExists(probe); }
            assertThrows(IOException.class, () -> store.markReady(pack(5, -8)));
            assertFalse(store.contains(pack(5, -8)), "没有落盘的区域不得发布 READY");
            assertEquals(1, store.size());
            assertArrayEquals(before, Files.readAllBytes(file));
            var duringFailure = ReadinessCoverageStore.open(file, FINGERPRINT);
            assertEquals(1, duringFailure.size());
            assertTrue(duringFailure.contains(pack(-4, 7)));
            assertFalse(duringFailure.contains(pack(5, -8)));
        }
        assertTrue(store.markReady(pack(5, -8)), "同一个对象必须真正重写，不能被污染的内存集合短路");
        var reopened = ReadinessCoverageStore.open(file, FINGERPRINT);
        assertEquals(2, reopened.size());
        assertTrue(reopened.contains(pack(-4, 7)));
        assertTrue(reopened.contains(pack(5, -8)));
        try (var files = Files.list(root)) {
            assertEquals(java.util.Set.of(file), files.collect(java.util.stream.Collectors.toSet()),
                    "失败原子替换不能遗留临时文件");
        }
    }

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

    @Test
    void stageTimingReportsRealBytesAndDoesNotReportDuplicateWrites(@TempDir Path root) throws Exception {
        Path file = root.resolve("timed-ready.txt");
        var events = new java.util.ArrayList<ReadinessCoverageStore.WriteTiming>();
        // 用非ASCII指纹区分UTF-8字节数与字符数，避免诊断指标本身误报。
        var store = ReadinessCoverageStore.open(file, "世界指纹", FileStorageIO::writeStringAtomic, events::add);
        assertTrue(store.markReady(pack(-7, 9)));
        assertFalse(store.markReady(pack(-7, 9)));
        assertEquals(1, events.size(), "重复READY不能产生虚假写盘样本");
        var event = events.getFirst();
        assertTrue(event.written());
        assertEquals(1, event.regions());
        assertEquals(Files.size(file), event.bytes());
        assertTrue(event.bytes() > Files.readString(file).length(), "夹具确实含多字节字符");
        assertEquals(event.gatherNanos() + event.sortNanos() + event.serializeNanos(), event.buildNanos());
        assertTrue(ReadinessCoverageStore.open(file, "世界指纹").contains(pack(-7, 9)));
    }

    @Test
    void failedWriteTimingRetainsTheExceptionAndNeverPublishesReady(@TempDir Path root) throws Exception {
        Path file = root.resolve("retry-ready.txt");
        var events = new java.util.ArrayList<ReadinessCoverageStore.WriteTiming>();
        var failure = new IOException("模拟替换故障");
        var attempts = new AtomicInteger();
        var store = ReadinessCoverageStore.open(file, FINGERPRINT, (p, contents) -> {
            if (attempts.getAndIncrement() == 0) throw failure;
            FileStorageIO.writeStringAtomic(p, contents);
        }, events::add);
        assertSame(failure, assertThrows(IOException.class, () -> store.markReady(pack(1, -2))));
        assertFalse(store.contains(pack(1, -2)));
        assertFalse(Files.exists(file));
        assertTrue(store.markReady(pack(1, -2)));
        assertEquals(java.util.List.of(false, true), events.stream()
                .map(ReadinessCoverageStore.WriteTiming::written).toList());
        assertEquals(java.util.List.of(1, 1), events.stream().map(ReadinessCoverageStore.WriteTiming::regions).toList());
        assertEquals(events.getFirst().bytes(), events.getLast().bytes());
        assertEquals(Files.size(file), events.getLast().bytes());
        assertTrue(ReadinessCoverageStore.open(file, FINGERPRINT).contains(pack(1, -2)));
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
