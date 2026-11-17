/* 文件职责：对拍完整区块计划和物理归属，并实测准备数据复用，不能只比较空结果。 */
package net.shiroha233.roadweaver.worldgen.road;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CompilePreparationTest {
    static RoadData road(int x,int z,int y,int id) {
        var stations=new ArrayList<RoadSegmentPlacement>();var heights=new ArrayList<Integer>();
        for(int i=0;i<80;i++){var p=new BlockPos(x+i,y,z);stations.add(new RoadSegmentPlacement(p,List.of(p,p.offset(0,0,1))));heights.add(y);}
        var spans=new ArrayList<RoadSpan>();int from=4;
        for(var type:SpanType.values()){spans.add(new RoadSpan(stations.get(from).middlePos(),stations.get(from+3).middlePos(),type));from+=8;}
        return new RoadData(3,0,List.of(),List.of(),stations,spans,heights,id,id+100);
    }
    @Test void sixExactMasksBuildOneIndexAndKeepNullAndLastDuplicate() {
        var road=road(-35,-17,64,1);int[] reads={0};var stations=road.roadSegmentList();
        List<RoadSegmentPlacement> counted=new AbstractList<>(){public int size(){return stations.size();}
            public RoadSegmentPlacement get(int i){reads[0]++;return stations.get(i);}};
        var inputs=new RoadChunkPlanCompiler.ExactSpans(counted,road.spans());int found=0;
        for(var type:SpanType.values()) {
            var actual=inputs.mask(type);assertArrayEquals(FrozenRoadChunkPlanCompiler.buildSpanMask(stations,road.spans(),type),actual);
            assertSame(actual,inputs.mask(type));if(actual!=null)found++;
        }
        assertEquals(8,found);assertEquals(stations.size(),reads[0]);
        assertNull(new RoadChunkPlanCompiler.ExactSpans(stations,List.of()).mask(SpanType.TUNNEL));
        var duplicate=List.of(stations.getFirst(),stations.get(1),stations.getFirst());
        var mask=new RoadChunkPlanCompiler.ExactSpans(duplicate,List.of(new RoadSpan(stations.getFirst().middlePos(),stations.getFirst().middlePos(),SpanType.TUNNEL))).mask(SpanType.TUNNEL);
        assertArrayEquals(new boolean[]{false,false,true},mask);
    }
    @Test void completePlansMatchFrozenCompilerForMixedSpansAndNegativeChunks() {
        int nonempty=0;
        for(int seed=0;seed<12;seed++) {
            var roads=List.of(road(-38,-17,64,seed+1),road(-38,-15,64,seed+20),road(-34,-17,75,seed+40));
            for(int x=-3;x<=3;x++)for(boolean include:new boolean[]{false,true}) {
                var chunk=new ChunkPos(x,-2);var a=FrozenRoadChunkPlanCompiler.compile(chunk,roads,include,7,91);
                var b=RoadChunkPlanCompiler.compile(chunk,roads,include,7,91);same(a,b);if(!b.isEmpty())nonempty++;
            }
        }
        assertTrue(nonempty>20);
    }
    @Test void physicalOwnershipMatchesFrozenForNewNormalizedRoadsOrphanReuseAndDirections() {
        int reused=0;
        for(int trial=0;trial<30;trial++) {
            var roads=new ArrayList<RoadData>();for(int i=0;i<5;i++)roads.add(road(-40+i,trial%3+i%2,64+i%2,100+i));
            if(trial%2==0)Collections.reverse(roads);
            var expected=FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads);
            var actual=RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads);assertEquals(expected,actual);
            for(var road:actual)for(var span:road.spans())if(span.type()==SpanType.NETWORK_REUSE)reused++;
        }
        assertTrue(reused>0);
    }
    @Test void preparationUsesIdentityAndKeepsReuseModesSeparate() {
        var road=road(0,0,64,1);var prep=new RoadPhysicalReuseNormalizer.Preparation();
        assertSame(prep.owner(road),prep.owner(road));
        assertSame(prep.secondary(road,true),prep.secondary(road,true));
        assertNotSame(prep.secondary(road,true),prep.secondary(road,false));
        var equal=road(0,0,64,1);assertEquals(road,equal);assertNotSame(prep.owner(road),prep.owner(equal));
    }
    static void same(RoadChunkPlan a,RoadChunkPlan b) {
        assertEquals(a.chunkPos(),b.chunkPos());assertEquals(a.revision(),b.revision());assertEquals(a.slices().size(),b.slices().size());
        for(int k=0;k<a.slices().size();k++){var x=a.slices().get(k);var y=b.slices().get(k);assertEquals(x.road(),y.road());assertEquals(x.segmentCount(),y.segmentCount());
            for(int i=0;i<x.segmentCount();i++)assertEquals(x.segmentIndexAt(i),y.segmentIndexAt(i));
            for(int i=0;i<x.road().roadSegmentList().size();i++)assertEquals(x.isTunnelJunction(i),y.isTunnelJunction(i));}
        var x=a.densityStamp();var y=b.densityStamp();
        for(int z=0;z<16;z++)for(int i=0;i<16;i++){
            assertEquals(x.targetY(i,z),y.targetY(i,z));assertEquals(x.isOccupied(i,z),y.isOccupied(i,z));
            assertEquals(x.fillLateral(i,z),y.fillLateral(i,z));assertEquals(x.carveLateral(i,z),y.carveLateral(i,z));
            for(int h=50;h<90;h++)assertEquals(x.density(a.chunkPos().getMinBlockX()+i,h,a.chunkPos().getMinBlockZ()+z),y.density(b.chunkPos().getMinBlockX()+i,h,b.chunkPos().getMinBlockZ()+z));
        }
    }
}

