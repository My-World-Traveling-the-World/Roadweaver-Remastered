/* 文件职责：在真实原子替换路径测READY文件各阶段；重开与文件字节在计时外核验。 */
package net.shiroha233.roadweaver.planning;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;

final class ReadinessWriteProbe {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("roadweaver-ready-probe-");
        try {
            for (int count : new int[]{512, 8192, 65536}) {
                Path file = directory.resolve("ready.txt");
                String fingerprint = "a".repeat(64);
                // 顺序混合负坐标，确保排序有实际工作，不能仅测已排好序的正坐标。
                TreeSet<Long> keys = new TreeSet<>();
                for (int i = 0; i < count; i++) keys.add(pack(i % 2 == 0 ? -i - 1 : i, i * 31));
                String base = contents(fingerprint, keys);
                long prime = pack(count + 16, -2), extra = pack(count + 17, -3);
                keys.add(prime); keys.add(extra);
                String expected = contents(fingerprint, keys);
                var samples = new ArrayList<ReadinessCoverageStore.WriteTiming>();
                var oldSamples = new ArrayList<FrozenReadinessCoverageStore.WriteTiming>();
                for (int trial = -3; trial < 9; trial++) {
                    Files.writeString(file, base, StandardCharsets.UTF_8);
                    Path oldFile = directory.resolve("old-ready.txt");
                    Files.writeString(oldFile, base, StandardCharsets.UTF_8);
                    var events = new ArrayList<ReadinessCoverageStore.WriteTiming>();
                    var oldEvents = new ArrayList<FrozenReadinessCoverageStore.WriteTiming>();
                    var store = ReadinessCoverageStore.open(file, fingerprint, FileStorageIO::writeStringAtomic, events::add);
                    var oldStore = FrozenReadinessCoverageStore.open(oldFile, fingerprint, FileStorageIO::writeStringAtomic, oldEvents::add);
                    if (store.size() != count || !store.markReady(prime) || !oldStore.markReady(prime)) throw new AssertionError("READY首写未执行");
                    events.clear(); oldEvents.clear();
                    // 每次均先提交一项形成真实有序快照；交替新旧提交顺序，不把首写误当复用收益。
                    if ((trial & 1) == 0) { store.markReady(extra); oldStore.markReady(extra); }
                    else { oldStore.markReady(extra); store.markReady(extra); }
                    if (events.size() != 1 || oldEvents.size() != 1) throw new AssertionError("READY复用写入未执行");
                    if (!Files.readString(oldFile).equals(expected)) throw new AssertionError("冻结READY字节不一致");
                    var event = events.getFirst();
                    if (!event.written() || event.regions() != count + 2 || event.bytes() != Files.size(file)
                            || !Files.readString(file).equals(expected)) throw new AssertionError("READY字节或样本不一致");
                    var reopened = ReadinessCoverageStore.open(file, fingerprint);
                    if (reopened.size() != count + 2 || !reopened.contains(extra)) throw new AssertionError("READY重开失败");
                    store.close(); reopened.close(); oldStore.close();
                    if (trial >= 0) { samples.add(event); oldSamples.add(oldEvents.getFirst()); }
                }
                print(count + 2, samples);
                System.out.printf(Locale.ROOT, "READY_FROZEN regions=%d sortP50Ms=%.4f buildP50Ms=%.4f writeP50Ms=%.4f currentBuildP50Ms=%.4f retainedOrderReferences=%d%n", count + 2,
                    percentile(oldSamples.stream().mapToLong(FrozenReadinessCoverageStore.WriteTiming::sortNanos).toArray(), .5),
                    percentile(oldSamples.stream().mapToLong(FrozenReadinessCoverageStore.WriteTiming::buildNanos).toArray(), .5),
                    percentile(oldSamples.stream().mapToLong(FrozenReadinessCoverageStore.WriteTiming::writeNanos).toArray(), .5),
                    percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::buildNanos).toArray(), .5), count + 2);
            }
            // 连续新增区域会重写整份文件，另测真实增长，不能只报最后一次提交。
            Path growing = directory.resolve("growing.txt");
            var events = new ArrayList<ReadinessCoverageStore.WriteTiming>();
            var store = ReadinessCoverageStore.open(growing, "a".repeat(64), FileStorageIO::writeStringAtomic, events::add);
            Path oldGrowing = directory.resolve("old-growing.txt");
            var oldEvents = new ArrayList<FrozenReadinessCoverageStore.WriteTiming>();
            var oldStore = FrozenReadinessCoverageStore.open(oldGrowing, "a".repeat(64), FileStorageIO::writeStringAtomic, oldEvents::add);
            for (int i = 0; i < 1024; i++) {
                if ((i & 1) == 0) { store.markReady(pack(i, -i)); oldStore.markReady(pack(i, -i)); }
                else { oldStore.markReady(pack(i, -i)); store.markReady(pack(i, -i)); }
            }
            if (!Files.readString(oldGrowing).equals(Files.readString(growing))) throw new AssertionError("连续增长产物不等价");
            var reopened = ReadinessCoverageStore.open(growing, "a".repeat(64));
            if (events.size() != 1024 || reopened.size() != 1024) throw new AssertionError("增长写入计数错误");
            long bytes = events.stream().mapToLong(ReadinessCoverageStore.WriteTiming::bytes).sum();
            long build = events.stream().mapToLong(ReadinessCoverageStore.WriteTiming::buildNanos).sum();
            long write = events.stream().mapToLong(ReadinessCoverageStore.WriteTiming::writeNanos).sum();
            System.out.printf(Locale.ROOT, "READY_GROWTH regions=1024 writes=%d cumulativeBytes=%d finalBytes=%d buildMs=%.3f writeMs=%.3f%n", events.size(), bytes, Files.size(growing), build / 1e6, write / 1e6);
            System.out.printf(Locale.ROOT, "READY_GROWTH_FROZEN regions=1024 writes=%d buildMs=%.3f writeMs=%.3f%n", oldEvents.size(),
                oldEvents.stream().mapToLong(FrozenReadinessCoverageStore.WriteTiming::buildNanos).sum() / 1e6,
                oldEvents.stream().mapToLong(FrozenReadinessCoverageStore.WriteTiming::writeNanos).sum() / 1e6);
            store.close(); reopened.close(); oldStore.close();
            System.out.println("READY_WRITE_PROBE_PASS warmup=3 samples=9 realAtomicFiles=true");
        } finally {
            // 只删除本探针创建的两个固定文件，禁止递归清理任意路径。
            Files.deleteIfExists(directory.resolve("ready.txt")); Files.deleteIfExists(directory.resolve("growing.txt"));
            Files.deleteIfExists(directory.resolve("old-ready.txt")); Files.deleteIfExists(directory.resolve("old-growing.txt"));
            Files.delete(directory);
        }
    }
    private static long pack(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static String contents(String fingerprint, Collection<Long> keys) {
        StringBuilder text = new StringBuilder("# RoadWeaver Chunky planning coverage\nschema=2\nfingerprint=").append(fingerprint).append('\n');
        for (long key : keys) text.append("region=").append((int)(key >> 32)).append(',').append((int)key).append('\n');
        return text.toString();
    }
    private static void print(int count, List<ReadinessCoverageStore.WriteTiming> samples) {
        System.out.printf(Locale.ROOT, "READY_WRITE regions=%d bytes=%d gatherP50Ms=%.4f sortP50Ms=%.4f serializeP50Ms=%.4f writeP50Ms=%.4f writeP95Ms=%.4f%n", count, samples.getFirst().bytes(),
                percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::gatherNanos).toArray(), .5),
                percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::sortNanos).toArray(), .5),
                percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::serializeNanos).toArray(), .5),
                percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::writeNanos).toArray(), .5),
                percentile(samples.stream().mapToLong(ReadinessCoverageStore.WriteTiming::writeNanos).toArray(), .95));
    }
    private static double percentile(long[] values, double p) { Arrays.sort(values); return values[(int)Math.ceil(p * values.length) - 1] / 1e6; }
}
