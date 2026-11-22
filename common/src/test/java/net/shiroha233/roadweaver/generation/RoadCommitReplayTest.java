/* 文件职责：用临时世界目录走真实存储门面，核对换线后施工能否再次选中原来标空的区块。 */
package net.shiroha233.roadweaver.generation;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.chunk.RoadChunkStore;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoadCommitReplayTest {
    // 两条路中心线完全相同且贴区块边；独有的侧列分居两端，能检出遗漏旧占地。
    private static RoadData road(boolean east) {
        var segments = new ArrayList<RoadSegmentPlacement>();
        for (int x = 2; x <= 13; x++) {
            var p = new BlockPos(x, 64, -8);
            var side = east ? (x == 13 ? p.east(3) : p) : (x == 2 ? p.west(3) : p);
            segments.add(new RoadSegmentPlacement(p, List.of(p, side)));
        }
        return new RoadData(3, 1, List.of(), List.of(), segments, List.of(), Collections.nCopies(segments.size(),64), 11, 20);
    }

    @Test void sameFingerprintBatchReturnsOldAndNewCoverageButOnlyDeliversFinalRoad(@TempDir Path root) {
        var old = road(false); var next = road(true);
        long left = ChunkPos.asLong(-1,-1), mid = ChunkPos.asLong(0,-1), right = ChunkPos.asLong(1,-1);
        try (var store = new RoadChunkStore(root)) {
            var initial = store.addRoads(List.of(old));
            assertEquals(Set.of(left,mid), initial.delivered());
            var overwrite = store.addRoads(List.of(old,next));
            assertEquals(Set.of(left,mid,right), overwrite.changed());
            assertEquals(Set.of(mid,right), overwrite.delivered());
            var entry = store.storedEntries().iterator().next();
            var unchanged = store.addRoads(List.of(next));
            assertTrue(unchanged.changed().isEmpty());
            assertEquals(overwrite.delivered(), unchanged.delivered());
            assertSame(entry.footprint(), store.storedEntries().iterator().next().footprint());
        }
    }

    @Test void committedReplacementRequeuesPreviouslyEmptyChunkInSameWorld(@TempDir Path root) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // 只提供真实存档目录和维度；测试直接选施工队列，不以启动整台游戏代替验证。
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) f.get(null);
        var server = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
        var level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        try (var access = LevelStorageSource.createDefault(root).createAccess("round114")) {
            set(MinecraftServer.class,server,"storageSource",access);
            set(ServerLevel.class,level,"server",server);
            set(Level.class,level,"dimension",Level.OVERWORLD);
            try {
                var old = road(false);
                long right = ChunkPos.asLong(1,-1), far = ChunkPos.asLong(-8,20);
                RoadShardStorage.addRoad(level,old);
                DeferredInitialRoadConstructionService.markNoConstructionRequired(level,List.of(right,far));
                assertTrue(DeferredInitialRoadConstructionService.isMarkedNoConstructionRequired(level,right));
                RoadShardStorage.replaceRoads(level,List.of(new RoadReplacement(RoadShardStorage.computeFingerprint(old),road(true))));
                assertEquals(1,RoadShardStorage.queryChunk(level,1,-1).size());
                assertFalse(DeferredInitialRoadConstructionService.isMarkedNoConstructionRequired(level,right));
                assertTrue(DeferredInitialRoadConstructionService.isMarkedNoConstructionRequired(level,far));
                var field = DeferredInitialRoadConstructionService.class.getDeclaredField("STATES");
                field.setAccessible(true);
                Object state = ((Map<?,?>)field.get(null)).get(level);
                assertNotNull(state);
                var pending = state.getClass().getDeclaredField("pending");
                pending.setAccessible(true);
                assertEquals(List.of(right),((DeferredChunkQueue)pending.get(state)).select(key -> DeferredChunkQueue.Probe.BUILDABLE).build());
            } finally {
                DeferredInitialRoadConstructionService.clear(level);
                RoadShardStorage.closeConnection(level);
            }
        }
    }

    private static void set(Class<?> type,Object obj,String name,Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj,value);
    }
}
