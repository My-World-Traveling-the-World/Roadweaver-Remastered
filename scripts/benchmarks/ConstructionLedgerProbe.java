/* 文件职责：使用真实施工台账收集、排序、编码和原子替换，单独量写盘阶段。 */
package net.shiroha233.roadweaver.persistence;

import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.ChunkPos;

final class ConstructionLedgerProbe {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("roadweaver-ledger-probe-");
        Path file = directory.resolve("ledger.txt");
        try {
            for (int count : new int[]{512, 8192, 65536}) {
                var samples = new ArrayList<RoadConstructionLedger.FlushTiming>();
                for (int trial = -3; trial < 9; trial++) {
                    Files.deleteIfExists(file);
                    var state = RoadConstructionLedger.load(file);
                    for (int i = 0; i < count; i++) state.markComplete(new ChunkPos(i % 2 == 0 ? -i - 1 : i, i * 31), 0x100000000L + i);
                    var events = new ArrayList<RoadConstructionLedger.FlushTiming>();
                    RoadConstructionLedger.flush(state, () -> file, events::add);
                    if (events.size() != 1) throw new AssertionError("台账未执行写入");
                    var event = events.getFirst();
                    if (!event.written() || event.entries() != count || event.bytes() != Files.size(file)) throw new AssertionError("台账写入样本错误");
                    var reopened = RoadConstructionLedger.load(file);
                    // 逐条重开验证，不用单个首尾命中掩盖中间条目丢失。
                    for (int i = 0; i < count; i++) if (!reopened.isComplete(new ChunkPos(i % 2 == 0 ? -i - 1 : i, i * 31), 0x100000000L + i)) throw new AssertionError("重开条目丢失：" + i);
                    RoadConstructionLedger.flush(state, () -> { throw new AssertionError("干净台账重复写盘"); }, events::add);
                    if (events.size() != 1) throw new AssertionError("干净台账产生虚假样本");
                    if (trial >= 0) samples.add(event);
                }
                System.out.printf(Locale.ROOT, "LEDGER_WRITE entries=%d bytes=%d gatherP50Ms=%.4f sortP50Ms=%.4f serializeP50Ms=%.4f writeP50Ms=%.4f writeP95Ms=%.4f%n", count, samples.getFirst().bytes(),
                    percentile(samples.stream().mapToLong(RoadConstructionLedger.FlushTiming::gatherNanos).toArray(), .5),
                    percentile(samples.stream().mapToLong(RoadConstructionLedger.FlushTiming::sortNanos).toArray(), .5),
                    percentile(samples.stream().mapToLong(RoadConstructionLedger.FlushTiming::serializeNanos).toArray(), .5),
                    percentile(samples.stream().mapToLong(RoadConstructionLedger.FlushTiming::writeNanos).toArray(), .5),
                    percentile(samples.stream().mapToLong(RoadConstructionLedger.FlushTiming::writeNanos).toArray(), .95));
            }
            System.out.println("LEDGER_WRITE_PROBE_PASS warmup=3 samples=9 realAtomicFiles=true");
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
    }
    private static double percentile(long[] values, double p) { Arrays.sort(values); return values[(int)Math.ceil(p * values.length) - 1] / 1e6; }
}
