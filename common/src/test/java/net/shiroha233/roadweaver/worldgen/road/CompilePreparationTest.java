/* 文件职责：对拍完整区块计划和物理归属，并实测准备数据复用，不能只比较空结果。 */
package net.shiroha233.roadweaver.worldgen.road;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CompilePreparationTest {
    /** 距离快路必须保留八格边界及远处宽占地后备，不能只把更多路口标成开口。 */
    @Test void externalTunnelJunctionsKeepDistanceBoundaryWideFootprintsAndVerticalBranches() {
        for(int offset:new int[]{8,9,12})for(boolean wide:new boolean[]{false,true}) {
            var stations=new ArrayList<RoadSegmentPlacement>();
            for(int x=-32;x<=-16;x++) {
                var point=new BlockPos(x,64,-8);stations.add(new RoadSegmentPlacement(point,List.of(point)));
            }
            var trunk=new RoadData(1,0,List.of(),List.of(),stations,
                    List.of(new RoadSpan(stations.getFirst().middlePos(),stations.getLast().middlePos(),SpanType.TUNNEL)),
                    Collections.nCopies(stations.size(),64),301,401);
            var markerStations=new ArrayList<RoadSegmentPlacement>();
            for(int x=-26;x<=-22;x+=2) {
                var point=new BlockPos(x,78,-8+offset);
                var footprint=wide?List.of(point,new BlockPos(x,78,-8)):List.of(point);
                markerStations.add(new RoadSegmentPlacement(point,footprint));
            }
            var junction=markerStations.get(1).middlePos();
            var marker=new RoadData(1,0,List.of(),List.of(),markerStations,
                    List.of(new RoadSpan(junction,junction,SpanType.TUNNEL_JUNCTION)),Collections.nCopies(3,78),302,402);
            var roads=List.of(trunk,marker);var chunk=new ChunkPos(-2,-1);
            var expected=FrozenRoadChunkPlanCompiler.compile(chunk,roads,false,7,199);
            var actual=RoadChunkPlanCompiler.compile(chunk,roads,false,7,199);same(expected,actual);
            var slice=actual.slices().stream().filter(v->v.road().roadSegmentList().getFirst().middlePos().getY()==64).findFirst().orElseThrow();
            assertEquals(offset==8||wide,slice.isTunnelJunction(8),"offset="+offset+" wide="+wide);
            if(offset==8||wide) {
                assertTrue(slice.isTunnelJunction(7));assertTrue(slice.isTunnelJunction(9));
            }
            var own=RoadChunkPlanCompiler.compile(chunk,List.of(trunk),false,7,199);
            assertFalse(own.slices().getFirst().isTunnelJunction(8),"没有外路标记不能凭隧道自身创建路口");
        }
    }

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
    @Test void indexedWindowsMatchFrozenPlansAtSplitEdgesFootprintsAndBridges() {
        var stations = new ArrayList<RoadSegmentPlacement>();
        var heights = new ArrayList<Integer>();
        for (int i = 0; i < 387; i++) {
            int x = i < 32 ? -450 + i : 200 + i - 32;
            int y = 68 + i % 3;
            var middle = new BlockPos(x, y, -40);
            var outside = i == 200 ? new BlockPos(-93, y, -75) : middle.offset(0, 0, 2);
            stations.add(new RoadSegmentPlacement(middle, List.of(middle, outside)));
            heights.add(y + i % 2);
        }
        var spans = new ArrayList<RoadSpan>();
        int i = 2;
        for (var type : SpanType.values()) {
            // 跨区块桥模板独立于长边夹具，其他跨度放在长边之后。
            int from = type == SpanType.BRIDGE ? 3 : 64 + 22 * i++;
            spans.add(new RoadSpan(stations.get(from).middlePos(), stations.get(from + 9).middlePos(), type));
        }
        var road = new RoadData(7, 0, List.of(), List.of(), stations, spans, heights, 31, 131);
        var cache = new RoadChunkIntersection.GeometryCache();
        for (boolean bridgeDensities : new boolean[]{false, true}) {
            ChunkPos cross = new ChunkPos(-7, -3), edge = new ChunkPos(-6, -5), bridge = new ChunkPos(-28, -4);
            int nonempty = 0;
            for (var chunk : List.of(cross, edge, bridge, new ChunkPos(-28, -3), new ChunkPos(13, -3),
                    new ChunkPos(16, -3), new ChunkPos(20, -3), new ChunkPos(25, -3), new ChunkPos(34, -3), new ChunkPos(39, -8))) {
                var expected = FrozenRoadChunkPlanCompiler.compile(chunk, List.of(road), bridgeDensities, 7, 519);
                var actual = RoadChunkPlanCompiler.compile(chunk, List.of(road), bridgeDensities, 7, 519, cache);
                same(expected, actual);
                if (!actual.isEmpty()) nonempty++;
                if (chunk.equals(cross)) {
                    var slice = actual.slices().getFirst();
                    var indices = new HashSet<Integer>();
                    for (int n = 0; n < slice.segmentCount(); n++) indices.add(slice.segmentIndexAt(n));
                    assertTrue(indices.containsAll(List.of(31, 32)));
                }
                if (chunk.equals(edge)) assertTrue(actual.densityStamp().isOccupied(3, 5));
                if (chunk.equals(bridge)) assertFalse(actual.slices().isEmpty(), "桥外扩实际跨到相邻块");
            }
            assertTrue(nonempty > 6);
        }
    }

    @Test void onlyLongSpreadOutRoutesSelectRemoteStationsAndNewGeometryDoesNotReuseTheirBounds() {
        var longRoad = rangeRoad(1024, false);
        var cache = new RoadChunkIntersection.GeometryCache();
        var chunk = new ChunkPos(-5, -2);
        int margin = RoadChunkIntersection.queryMargin(3);
        var selection = cache.near(longRoad.roadSegmentList(), chunk, margin);
        assertNotNull(selection);
        assertEquals(1, RoadStationBounds.Selection.count(selection));
        assertEquals(64, RoadStationBounds.Selection.end(selection, 0, 1024)
                - RoadStationBounds.Selection.start(selection, 0));
        var moved = rangeRoad(1024, true);
        assertNull(cache.near(moved.roadSegmentList(), chunk, margin), "不同的紧凑站列不得命中长路索引");
        assertNull(cache.near(rangeRoad(80, false).roadSegmentList(), chunk, margin), "短路无需查索引");
        var noSelection = cache.near(longRoad.roadSegmentList(), new ChunkPos(-500, 30), margin);
        assertEquals(0, RoadStationBounds.Selection.count(noSelection));
    }

    static RoadData rangeRoad(int size, boolean compact) {
        var stations = new ArrayList<RoadSegmentPlacement>();
        var heights = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
            var middle = new BlockPos(compact ? -128 + i % 15 : -128 + i, 64, compact ? -24 + i / 15 % 10 : -24);
            stations.add(new RoadSegmentPlacement(middle, List.of(middle.offset(0, 0, -1), middle, middle.offset(0, 0, 1))));
            heights.add(64);
        }
        return new RoadData(5, 0, List.of(), List.of(), stations, List.of(), heights, 106, 208);
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

