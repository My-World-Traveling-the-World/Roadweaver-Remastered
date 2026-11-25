/* 文件职责：钉住倾斜地图的真实高度、缺口、类型和顶点预算。 */
package net.shiroha233.roadweaver.map;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadOverlayGeometryTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static RoadData road(List<RoadSpan> spans){
        var stations=new ArrayList<RoadSegmentPlacement>();var heights=new ArrayList<Integer>();
        for(int i=0;i<9;i++){var p=new BlockPos(i,60,0);stations.add(new RoadSegmentPlacement(p,List.of(p)));heights.add(90+i);}
        return new RoadData(5,0,List.of(),List.of(),stations,spans,heights,1,2);
    }
    private static RoadSpan span(int a,int b,SpanType type){return new RoadSpan(new BlockPos(a,60,0),new BlockPos(b,60,0),type);}
    @Test void readsConstructionHeightInsteadOfFlatOrOriginalTerrain(){
        var lines=RoadOverlayGeometry.convert(road(List.of()));
        assertEquals(1,lines.size());assertEquals(2,lines.getFirst().points().size());
        assertEquals(new Point(0,90,0),lines.getFirst().points().getFirst());
        assertEquals(new Point(8,98,0),lines.getFirst().points().getLast());
    }
    @Test void gapAndNetworkReuseBreakIndependentRuns(){
        for(var type:List.of(SpanType.PROTECTED_GAP,SpanType.NETWORK_REUSE)){
            var lines=RoadOverlayGeometry.convert(road(List.of(span(3,4,type))));
            assertEquals(2,lines.size());
            assertEquals(2,lines.getFirst().points().getLast().x());assertEquals(5,lines.getLast().points().getFirst().x());
        }
    }
    @Test void bridgeCrossingAndTunnelRemainDistinct(){
        for(var item:Map.of(SpanType.BRIDGE,Kind.BRIDGE,SpanType.ROAD_CROSSING,Kind.CROSSING,SpanType.TUNNEL,Kind.TUNNEL).entrySet()){
            var lines=RoadOverlayGeometry.convert(road(List.of(span(2,5,item.getKey()))));
            assertTrue(lines.stream().anyMatch(line->line.kind()==item.getValue()));
            assertEquals(3,lines.size());
        }
    }
    @Test void viewportCutsDoNotJoinUnrelatedEdgesAndBudgetIsExplicit(){
        var points=new ArrayList<Point>();for(int i=0;i<9;i++)points.add(new Point(i,90+i%2,0));
        var source=List.of(List.of(new Line(Kind.ROAD,points)));
        var visible=RoadOverlayGeometry.window(source,3,3,-1,5,1,100);
        assertEquals(State.READY,visible.state());assertFalse(visible.truncated());
        assertEquals(2,visible.lines().getFirst().points().getFirst().x());assertEquals(6,visible.lines().getFirst().points().getLast().x());
        var shortView=RoadOverlayGeometry.window(source,3,-1,-1,9,1,3);
        assertTrue(shortView.truncated());assertEquals(3,shortView.lines().getFirst().points().size());
        assertEquals(State.EMPTY,RoadOverlayGeometry.window(source,3,100,100,101,101,3).state());
    }
    @Test void exactCompressionPreservesSlopeChangesAndTurnBacks(){
        var original=road(List.of());
        var heights=List.of(90,90,90,91,91,91,92,92,92);
        var slope=new RoadData(5,0,List.of(),List.of(),original.roadSegmentList(),List.of(),heights,1,2);
        var line=RoadOverlayGeometry.convert(slope).getFirst();
        for(int i=0;i<8;i++){
            int station=i;
            var segment=java.util.stream.IntStream.range(1,line.points().size())
                    .filter(j->line.points().get(j-1).x()<=station&&line.points().get(j).x()>=station+1).findFirst().orElseThrow();
            var a=line.points().get(segment-1);var b=line.points().get(segment);
            assertEquals((double)heights.get(i),a.y()+(b.y()-a.y())*(i-a.x())/(double)(b.x()-a.x()));
        }
        var backtrack=List.of(List.of(new Line(Kind.ROAD,List.of(new Point(0,90,0),new Point(2,90,0),new Point(1,90,0)))));
        assertEquals(3,RoadOverlayGeometry.window(backtrack,1,-1,-1,3,1,10).lines().getFirst().points().size());
    }
    @Test void exhaustedBudgetDoesNotConvertRemainingRoads(){
        var touched=new java.util.concurrent.atomic.AtomicInteger();
        Iterable<List<Line>> roads=()->java.util.stream.IntStream.range(0,100).mapToObj(i->{
            touched.incrementAndGet();return List.of(new Line(Kind.ROAD,List.of(new Point(0,90,0),new Point(1,90,0))));
        }).iterator();
        var result=RoadOverlayGeometry.window(roads,1,-1,-1,2,1,3);
        assertEquals(1,result.lines().size());assertTrue(result.truncated());assertEquals(2,touched.get());
    }
}
