/* 文件职责：存储复用冻结分站时仍隔离调用方工作数据，并保留非法空列表的旧归一规则。 */
package net.shiroha233.roadweaver.persistence.chunk;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoadSnapshotReuseTest {
    @Test void previewNeverOpensAnUnloadedStore(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var path=root.resolve("roads");var store=new RoadChunkStore(path);
        assertNull(store.peekSnapshot());assertFalse(java.nio.file.Files.exists(path));
        store.preload();var snapshot=store.peekSnapshot();assertNotNull(snapshot);assertTrue(snapshot.isEmpty());
        assertSame(snapshot,store.peekSnapshot());store.close();
    }
    @Test void snapshotReusesFrozenStationsWithoutKeepingMutableInputAliases() {
        var cursor=new BlockPos.MutableBlockPos(2,64,3);
        var columns=new ArrayList<BlockPos>(List.of(cursor));
        var station=new RoadSegmentPlacement(cursor,columns);
        var stations=new ArrayList<RoadSegmentPlacement>(List.of(station));
        var source=new RoadData(3,0,List.of(),List.of(),stations,List.of(),List.of(64),1,2);
        var frozen=RoadSnapshot.freezeForStorage(source);
        assertEquals(source,frozen);assertSame(station,frozen.roadSegmentList().getFirst());
        cursor.set(999,0,999);columns.clear();stations.clear();
        assertEquals(List.of(new BlockPos(2,64,3)),frozen.roadSegmentList().getFirst().positions());
        assertThrows(UnsupportedOperationException.class,frozen.roadSegmentList().getFirst().positions()::clear);
        var encoded=RoadData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,frozen).getOrThrow();
        assertEquals(frozen,RoadData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,encoded).getOrThrow());
    }
    @Test void invalidNullColumnListIsStillNormalizedAtStorageBoundary() {
        var station=new RoadSegmentPlacement(new BlockPos(0,64,0),null);
        var source=new RoadData(3,0,List.of(),List.of(),List.of(station),List.of(),List.of(64),1,2);
        var frozen=RoadSnapshot.freezeForStorage(source);
        assertNull(station.positions());assertEquals(List.of(),frozen.roadSegmentList().getFirst().positions());
    }
}
