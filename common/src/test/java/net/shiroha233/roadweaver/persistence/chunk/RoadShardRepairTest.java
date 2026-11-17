/* 文件职责：必须检查重开后磁盘分片真的修好，内存从NBT重建可读不能冒充修复成功。 */
package net.shiroha233.roadweaver.persistence.chunk;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoadShardRepairTest {
    @TempDir Path root;
    @Test void sameLengthNonLatestShardChangeIsRepairedDespiteLegacyStampHit() throws Exception {
        var road=seed();var files=shards();assertTrue(files.size()>=2);
        for(var file:files)Files.setLastModifiedTime(file,FileTime.fromMillis(1000));
        Files.setLastModifiedTime(files.getLast(),FileTime.fromMillis(3000));
        // 正常重开建立旧戳；之后改的不是最大mtime文件，三项统计不会变。
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        Path changed=files.getFirst();String original=Files.readString(changed);
        String corrupt=original.replace("\"version\": 2","\"version\": 9");
        assertNotEquals(original,corrupt);assertEquals(original.length(),corrupt.length());
        Files.writeString(changed,corrupt);Files.setLastModifiedTime(changed,FileTime.fromMillis(2000));
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        assertEquals(2,JsonParser.parseString(Files.readString(changed)).getAsJsonObject().get("version").getAsInt(),"NBT可读不能代替磁盘修复");
    }
    @Test void unexpectedShardIsRemovedAndSecondReopenDoesNotRewriteIndex() throws Exception {
        var road=seed();Path extra=root.resolve("chunks/999_999.json");Files.writeString(extra,Files.readString(shards().getFirst()));
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        assertFalse(Files.exists(extra),"派生目录多余的合法分片名必须清理，否则每次重开都重复修复");
        byte[] index=Files.readAllBytes(root.resolve("index.json"));
        Files.setLastModifiedTime(root.resolve("index.json"),FileTime.fromMillis(1000));
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        assertArrayEquals(index,Files.readAllBytes(root.resolve("index.json")));
        assertEquals(1000,Files.getLastModifiedTime(root.resolve("index.json")).toMillis());
    }
    @Test void missingAndWrongReferenceShardsAreRestoredFromRoadAggregate() throws Exception {
        var road=seed();var files=shards();Path missing=files.getFirst(),wrong=files.getLast();
        Files.delete(missing);var json=JsonParser.parseString(Files.readString(wrong)).getAsJsonObject();
        var invalid=new JsonArray();invalid.add(0L);json.add("roads",invalid);Files.writeString(wrong,json.toString());
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        assertTrue(Files.isRegularFile(missing));
        long fingerprint=RoadFingerprint.compute(road);
        for(var file:shards())assertEquals(fingerprint,JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("roads").get(0).getAsLong());
    }
    @Test void lockedShardDoesNotQuarantineOrRepairAnyFileUntilReopen() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var road=seed();var files=shards();Path locked=files.getFirst(),malformed=files.getLast();
        byte[] before=Files.readAllBytes(locked),index=Files.readAllBytes(root.resolve("index.json"));
        Files.writeString(malformed,"{");byte[] broken=Files.readAllBytes(malformed);
        try(var lock=java.nio.channels.FileChannel.open(locked,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            assertThrows(java.io.IOException.class,() -> Files.readAllBytes(locked),"必须先证明文件锁真实阻止读取");
            try(var store=new RoadChunkStore(root)) {
                assertEquals(List.of(road),store.loadAll(),"分片IO失败时完整聚合根仍可读");
                assertThrows(IllegalStateException.class,() -> store.addRoad(otherRoad()));
                assertArrayEquals(index,Files.readAllBytes(root.resolve("index.json")));
                assertArrayEquals(broken,Files.readAllBytes(malformed),"其他解析损坏项也不能在IO故障期间先隔离");
                try(var all=Files.list(root.resolve("chunks"))){assertEquals(0,all.filter(p -> p.getFileName().toString().contains(".corrupt.")).count());}
            }
        }
        assertArrayEquals(before,Files.readAllBytes(locked));
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());store.addRoad(otherRoad());assertEquals(2,store.loadAll().size());}
        try(var all=Files.list(root.resolve("chunks"))){assertEquals(1,all.filter(p -> p.getFileName().toString().contains(".corrupt.")).count());}
        assertEquals(2,JsonParser.parseString(Files.readString(malformed)).getAsJsonObject().get("version").getAsInt());
    }
    @Test void lockedIndexIsNeverQuarantinedOrOverwritten() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var road=seed();Path index=root.resolve("index.json");byte[] original=Files.readAllBytes(index);
        try(var lock=java.nio.channels.FileChannel.open(index,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            assertThrows(java.io.IOException.class,() -> Files.readAllBytes(index));
            try(var store=new RoadChunkStore(root)){
                assertTrue(store.loadAll().isEmpty());assertThrows(IllegalStateException.class,() -> store.addRoad(otherRoad()));
            }
            assertTrue(Files.exists(index));
            try(var all=Files.list(root)){assertEquals(0,all.filter(p -> p.getFileName().toString().startsWith("index.json.corrupt.")).count());}
        }
        assertArrayEquals(original,Files.readAllBytes(index));
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
    }
    @Test void failedStartupRepairIsRetriedByNextMutationBeforePublishing() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var road=seed();Path file=shards().getFirst();
        String broken=Files.readString(file).replace("\"version\": 2","\"version\": 9");Files.writeString(file,broken);
        try(var store=new RoadChunkStore(root)) {
            try(var lock=java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                assertEquals(List.of(road),store.loadAll());
                assertEquals(broken,Files.readString(file),"锁必须真实阻止修复替换");
            }
            store.addRoad(otherRoad());assertEquals(2,store.loadAll().size());
        }
        assertEquals(2,JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt(),"不能因为旧路引用未变而跳过失败的修复");
        try(var store=new RoadChunkStore(root)){assertEquals(2,store.loadAll().size());}
    }
    @Test void interruptedVerificationDoesNotTouchDiskOrClearInterrupt() throws Exception {
        seed();byte[] index=Files.readAllBytes(root.resolve("index.json"));
        try {
            Thread.currentThread().interrupt();
            var result=new RoadShardVerifier(root).verify(RoadSnapshot.empty());
            assertTrue(result.readFailure());assertFalse(result.matches());assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(index,Files.readAllBytes(root.resolve("index.json")));
    }
    @Test void verifierReportsMalformedMismatchAndMissingWithoutChangingFiles() throws Exception {
        var road=seed();var files=shards();int total=files.size();assertTrue(total>=3);
        Files.writeString(files.getFirst(),"{");
        String bad=Files.readString(files.get(1)).replace("\"version\": 2","\"version\": 9");Files.writeString(files.get(1),bad);
        Files.delete(files.getLast());
        var result=new RoadShardVerifier(root).verify(RoadSnapshot.from(List.of(road)));
        assertFalse(result.matches());assertFalse(result.readFailure());assertEquals(total-1,result.checked());
        assertEquals(1,result.malformed());assertEquals(2,result.mismatches());assertEquals(0,result.ioFailures());
        assertEquals(List.of(files.getFirst()),result.corrupt());
        assertEquals("{",Files.readString(files.getFirst()));assertEquals(bad,Files.readString(files.get(1)));
        assertFalse(Files.exists(files.getLast()),"验证器本身只读，不能把自己的修复结果拿来证明输入正确");
    }
    @Test void alternateFilenameForSameChunkIsRemovedAndCanonicalShardSurvives() throws Exception {
        var road=seed();int total=shards().size();Path canonical=root.resolve("chunks/0_0.json");assertTrue(Files.isRegularFile(canonical));
        RoadChunkKey key=RoadChunkKey.parseFileName(canonical.getFileName().toString());
        Path alias=canonical.resolveSibling("0"+key.x()+"_"+key.z()+".json");
        assertNotEquals(canonical,alias);Files.copy(canonical,alias);
        try(var store=new RoadChunkStore(root)){assertEquals(List.of(road),store.loadAll());}
        assertFalse(Files.exists(alias));assertTrue(Files.isRegularFile(canonical));assertEquals(total,shards().size());
    }
    private RoadData otherRoad() {
        var stations=new ArrayList<RoadSegmentPlacement>();for(int i=0;i<8;i++){var p=new BlockPos(i,64,100);stations.add(new RoadSegmentPlacement(p,List.of(p)));}
        var road=new RoadData(3,0,List.of(),List.of(),stations,List.of(),Collections.nCopies(8,64),3,4);
        assertTrue(RoadDataValidator.isValidFinalRoad(road));return road;
    }
    private RoadData seed() throws Exception {
        var stations=new ArrayList<RoadSegmentPlacement>();var heights=new ArrayList<Integer>();
        for(int i=0;i<40;i++){var p=new BlockPos(i,64,0);stations.add(new RoadSegmentPlacement(p,List.of(p)));heights.add(64);}
        var road=new RoadData(3,0,List.of(),List.of(),stations,List.of(),heights,1,2);
        assertTrue(RoadDataValidator.isValidFinalRoad(road));
        try(var store=new RoadChunkStore(root)){store.addRoad(road);assertEquals(List.of(road),store.loadAll());}
        return road;
    }
    private List<Path> shards() throws Exception {
        try(var files=Files.list(root.resolve("chunks"))){return files.filter(p -> RoadChunkKey.parseFileName(p.getFileName().toString())!=null).sorted().toList();}
    }
}
