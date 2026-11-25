package net.shiroha233.roadweaver.worldgen.road;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 预览只显示可施工站的顶面几何，故障和未知缓存都不能伪装成正常空道路。 */
class RoadPreviewProviderTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void previewUsesStandableHeightAndClipsToOwningChunk() {
        var chunk=new ChunkPos(-1,0);var center=new BlockPos(-1,72,2);
        var road=new RoadData(3,0,List.of(Blocks.STONE_BRICKS.defaultBlockState()),List.of(),
                List.of(new RoadSegmentPlacement(center,List.of(center,new BlockPos(0,72,2)))),List.of(),List.of(80),1L,2L);
        var slice=new RoadChunkSlice(road,chunk,new int[]{0},null);
        var plan=RoadChunkPlan.of(chunk,List.of(slice),RoadDensityStamp.empty(chunk,4),12);
        var output=RoadPreviewProvider.convert(plan);
        assertEquals(WorldgenPreviewApi.Fidelity.GEOMETRY,output.fidelity());
        assertEquals(List.of(new WorldgenPreviewApi.Run(15,2,79,80,"minecraft:stone_bricks")),output.runs());
    }
    @Test void unavailablePlanIsNotAnEmptySuccess() {
        var chunk=new ChunkPos(0,0);
        assertEquals(WorldgenPreviewApi.State.UNAVAILABLE,RoadPreviewProvider.convert(RoadChunkPlan.unavailable(chunk,4,1,RoadChunkPlan.Outcome.RETRYABLE_FAILURE)).state());
        assertEquals(WorldgenPreviewApi.State.EMPTY,RoadPreviewProvider.convert(RoadChunkPlan.empty(chunk,4,1)).state());
        var session=new RoadWorldgenPlanCache.Session();var config=new RoadWorldgenPlanCache.PlanConfig(4,true);
        assertNull(session.peek(chunk,config));
        var published=session.get(chunk,config,(pos,cfg,revision)->RoadChunkPlan.empty(pos,4,revision),()->0);
        assertSame(published,session.peek(chunk,config));
        session.invalidate(List.of(chunk.toLong()));assertNull(session.peek(chunk,config));
    }
}
