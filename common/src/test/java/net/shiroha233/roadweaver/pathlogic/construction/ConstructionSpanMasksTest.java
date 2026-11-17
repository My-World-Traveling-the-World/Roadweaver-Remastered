/* 文件职责：冻结原施工掩码语义，避免缓存或共享索引改变桥头、保护缺口与近站选择。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionSpanMasksTest {
    static RoadData road(List<BlockPos> centers,List<RoadSpan> spans) {
        return new RoadData(3,0,List.of(),List.of(),centers.stream()
                .map(p -> new RoadSegmentPlacement(p,List.of(p))).toList(),spans,
                centers.stream().map(BlockPos::getY).toList(),1,2);
    }
    static List<BlockPos> centers(int n) {
        var out=new ArrayList<BlockPos>();for(int i=0;i<n;i++)out.add(new BlockPos(i,60+i%9,-17));return out;
    }
    @Test void frozenAlgorithmMatchesRandomCurvesReverseAndAllSpanTypes() {
        var random=new Random(4911);int marked=0;
        for(int trial=0;trial<100;trial++) {
            var centers=new ArrayList<BlockPos>();int x=-80,z=-80;
            for(int i=0;i<80;i++){x+=random.nextInt(5)-2;z+=random.nextInt(5)-2;centers.add(new BlockPos(x,40+i%20,z));}
            var spans=new ArrayList<RoadSpan>();
            for(var type:SpanType.values())for(int j=0;j<3;j++) {
                var a=centers.get(random.nextInt(80));var b=centers.get(random.nextInt(80));
                if(j==1)a=a.offset(1,12,1);if(j==2)b=b.offset(9,0,9);
                spans.add(new RoadSpan(a,b,type));
            }
            for(var order:List.of(centers,centers.reversed()))for(int ramps:new int[]{-1,0,2,12}) {
                var road=road(order,spans);var expected=FrozenConstructionSpanMasks.build(order,road,ramps);
                var actual=ConstructionSpanMasks.build(order,road,ramps);same(expected,actual);
                for(boolean value:actual.tunnel())if(value)marked++;
            }
        }
        assertTrue(marked>1000,"必须有真实命中，不能用全空掩码对拍");
    }
    @Test void exactMatchUsesLastDuplicateButNearestTieUsesFirstAndFourBlockLimit() {
        var c=List.of(new BlockPos(0,64,0),new BlockPos(2,64,0),new BlockPos(0,75,0),new BlockPos(10,64,0));
        var exact=road(c,List.of(new RoadSpan(c.getFirst(),c.getFirst(),SpanType.TUNNEL)));
        assertArrayEquals(new boolean[]{false,false,true,false},ConstructionSpanMasks.build(c,exact,0).tunnel());
        var tie=road(c,List.of(new RoadSpan(new BlockPos(1,0,0),new BlockPos(1,99,0),SpanType.NETWORK_REUSE)));
        assertArrayEquals(new boolean[]{true,false,false,false},ConstructionSpanMasks.build(c,tie,0).networkReuse());
        var at4=road(c,List.of(new RoadSpan(new BlockPos(14,0,0),new BlockPos(14,0,0),SpanType.PROTECTED_GAP)));
        assertTrue(ConstructionSpanMasks.build(c,at4,0).protectedGap()[3]);
        var at5=road(c,List.of(new RoadSpan(new BlockPos(15,0,0),new BlockPos(15,0,0),SpanType.PROTECTED_GAP)));
        assertArrayEquals(new boolean[4],ConstructionSpanMasks.build(c,at5,0).protectedGap());
    }
    @Test void preserveGradeRequiresExactEndpointsAndUsesClosedRampExpansion() {
        var c=centers(12);var span=new RoadSpan(c.get(7),c.get(4),SpanType.ROAD_CROSSING);
        var masks=ConstructionSpanMasks.build(c,road(c,List.of(span)),2);
        for(int i=0;i<12;i++){assertEquals(i>=2 && i<=9,masks.preserveGrade()[i]);assertEquals(i>=4 && i<=7,masks.directCrossing()[i]);}
        var moved=new RoadSpan(c.get(7).offset(0,0,1),c.get(4),SpanType.ROAD_CROSSING);
        var shifted=ConstructionSpanMasks.build(c,road(c,List.of(moved)),2);
        assertArrayEquals(new boolean[12],shifted.preserveGrade(),"桥头扩张不得偷偷套近站规则");
        // Chebyshev距离下偏一列会与前一站并列，旧近站规则选最早站6。
        assertTrue(shifted.directCrossing()[4]);assertTrue(shifted.directCrossing()[6]);assertFalse(shifted.directCrossing()[7]);
    }
    @Test void emptyNullEndpointsAndExtremeCoordinatesKeepLegacyResults() {
        var c=List.of(new BlockPos(Integer.MIN_VALUE,64,0),new BlockPos(Integer.MAX_VALUE,64,0),new BlockPos(0,64,0));
        var road=road(c,List.of(new RoadSpan(null,c.get(2),SpanType.TUNNEL),
                new RoadSpan(new BlockPos(4,0,0),new BlockPos(4,0,0),SpanType.TUNNEL)));
        same(FrozenConstructionSpanMasks.build(c,road,2),ConstructionSpanMasks.build(c,road,2));
        same(FrozenConstructionSpanMasks.build(List.of(),road(List.of(),List.of()),0),
                ConstructionSpanMasks.build(List.of(),road(List.of(),List.of()),0));
    }
    static void same(FrozenConstructionSpanMasks.Masks expected,ConstructionSpanMasks.Masks actual) {
        assertArrayEquals(expected.preserveGrade(),actual.preserveGrade());assertArrayEquals(expected.directCrossing(),actual.directCrossing());
        assertArrayEquals(expected.networkReuse(),actual.networkReuse());assertArrayEquals(expected.protectedGap(),actual.protectedGap());
        assertArrayEquals(expected.tunnel(),actual.tunnel());
    }
}

