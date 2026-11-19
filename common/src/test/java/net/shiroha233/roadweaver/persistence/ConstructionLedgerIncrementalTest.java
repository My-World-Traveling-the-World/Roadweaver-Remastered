package net.shiroha233.roadweaver.persistence;

import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 独立按旧比较口径拼完整文件，防止增量合并漏掉负键、同区块多指纹或失败项。 */
class ConstructionLedgerIncrementalTest {
    @TempDir Path root;
    record Pair(long chunk,long fingerprint){}
    @Test void failedIncrementalWriteRetriesWithoutAnyNewCompletion() throws Exception {
        Path file=root.resolve("incremental-retry.txt");var state=RoadConstructionLedger.load(file);
        var a=new ChunkPos(1,2);var b=new ChunkPos(-3,4);
        state.markComplete(a,17);RoadConstructionLedger.flush(state,()->file);
        state.markComplete(b,29);
        Path blocked=Files.writeString(root.resolve("file-blocks-directory"),"保留");
        var attempts=new ArrayList<RoadConstructionLedger.FlushTiming>();
        RoadConstructionLedger.flush(state,()->blocked.resolve("ledger"),attempts::add);
        // 不再markComplete，否则新置脏会掩盖上一轮失败丢失重试标记。
        RoadConstructionLedger.flush(state,()->file,attempts::add);
        assertEquals(List.of(false,true),attempts.stream().map(RoadConstructionLedger.FlushTiming::written).toList());
        var reopened=RoadConstructionLedger.load(file);
        assertTrue(reopened.isComplete(a,17));assertTrue(reopened.isComplete(b,29));
    }
    @Test void pathResolutionFailureKeepsTheWholeDirtySnapshotForRetry() throws Exception {
        Path file=root.resolve("retry.txt");var state=RoadConstructionLedger.load(file);
        var c=new ChunkPos(-4,7);state.markComplete(c,31);
        var cause=new IllegalStateException("路径解析失败");
        assertSame(cause,assertThrows(IllegalStateException.class,
                ()->RoadConstructionLedger.flush(state,()->{throw cause;})));
        RoadConstructionLedger.flush(state,()->file);
        assertTrue(Files.isRegularFile(file));assertTrue(RoadConstructionLedger.load(file).isComplete(c,31));
    }
    @Test void randomBatchesDuplicatesFailureRecoveryAndReopenMatchSchema3Bytes() throws Exception {
        Path file=root.resolve("ledger.txt");var state=RoadConstructionLedger.load(file);
        var expected=new HashSet<Pair>();var random=new Random(78143);
        Path obstacle=Files.writeString(root.resolve("blocked"),"原字节");
        for(int round=0;round<40;round++){
            for(int i=0;i<19;i++){
                var chunk=new ChunkPos(random.nextInt(21)-10,random.nextInt(21)-10);
                long fingerprint=i==0?Long.MIN_VALUE:i==1?Long.MAX_VALUE:random.nextInt(11)-5;
                state.markComplete(chunk,fingerprint);state.markComplete(chunk,fingerprint);
                expected.add(new Pair(chunk.toLong(),fingerprint));
            }
            if(round%7==0){
                var events=new ArrayList<RoadConstructionLedger.FlushTiming>();
                RoadConstructionLedger.flush(state,()->obstacle.resolve("ledger"),events::add);
                assertEquals(1,events.size());assertFalse(events.getFirst().written());
                assertEquals("原字节",Files.readString(obstacle));
                // 失败之后再加项，验证旧pending和新pending都在下一次原子提交中。
                var extra=new ChunkPos(round,-round);state.markComplete(extra,73);expected.add(new Pair(extra.toLong(),73));
            }
            RoadConstructionLedger.flush(state,()->file);
            var order=expected.stream().sorted(Comparator.comparingLong(Pair::chunk).thenComparingLong(Pair::fingerprint)).toList();
            var bytes=new StringBuilder("# RoadWeaver constructed road chunks\nschema=3\n");
            for(var e:order)bytes.append("entry=").append(ChunkPos.getX(e.chunk())).append(',')
                    .append(ChunkPos.getZ(e.chunk())).append(',').append(e.fingerprint()).append('\n');
            assertEquals(bytes.toString(),Files.readString(file),"第"+round+"轮完整字节");
            var reopened=RoadConstructionLedger.load(file);
            assertFalse(expected.isEmpty());
            for(var e:expected)assertTrue(reopened.isComplete(new ChunkPos(e.chunk()),e.fingerprint()));
            if(round%5==0)state=reopened;
        }
    }
}
