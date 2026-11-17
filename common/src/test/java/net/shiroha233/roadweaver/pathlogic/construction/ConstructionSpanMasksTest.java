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

    @Test void cachedMasksAreReadOnlyAndDoNotReuseAnotherRoadInstanceOrRampSetting() {
        ConstructionSpanMasks.clear();var c=centers(16);
        var road=road(c,List.of(new RoadSpan(c.get(5),c.get(8),SpanType.ROAD_CROSSING),
                new RoadSpan(c.get(10),c.get(12),SpanType.TUNNEL)));
        var a=ConstructionSpanMasks.of(road,0);assertSame(a,ConstructionSpanMasks.of(road,-1));
        assertFalse(a.preserveGradeAt(3));var wide=ConstructionSpanMasks.of(road,2);
        assertNotSame(a,wide);assertTrue(wide.preserveGradeAt(3));assertSame(wide,ConstructionSpanMasks.of(road,2));
        assertEquals(1,ConstructionSpanMasks.size());
        var exported=wide.tunnel();exported[10]=false;assertTrue(wide.tunnelAt(10));
        assertEquals(3,wide.tunnelCount());assertEquals(0,wide.networkReuseCount());assertEquals(0,wide.protectedGapCount());
        var changed=road(c,List.of(new RoadSpan(c.get(1),c.get(2),SpanType.PROTECTED_GAP)));
        var other=ConstructionSpanMasks.of(changed,2);assertNotSame(wide,other);
        assertFalse(other.tunnelAt(10));assertTrue(other.protectedGapAt(1));assertEquals(2,other.protectedGapCount());
        var reversed=c.reversed();var reversedRoad=road(reversed,road.spans());
        var reversedMasks=ConstructionSpanMasks.of(reversedRoad,2);
        same(FrozenConstructionSpanMasks.build(reversed,reversedRoad,2),reversedMasks);
        ConstructionSpanMasks.clear();assertNotSame(wide,ConstructionSpanMasks.of(road,2));
        same(FrozenConstructionSpanMasks.build(c,road,2),ConstructionSpanMasks.of(road,2));
    }
    @Test void coldComputationBuildsOneIndexRatherThanFive() {
        var c=centers(1024);var spans=new ArrayList<RoadSpan>();
        for(var type:SpanType.values())spans.add(new RoadSpan(c.get(5),c.get(10),type));
        var road=road(c,spans);int[] gets={0};
        List<BlockPos> counted=new AbstractList<>() {
            public int size(){return c.size();}
            public BlockPos get(int i){gets[0]++;return c.get(i);}
        };
        var old=FrozenConstructionSpanMasks.build(counted,road,2);assertEquals(5*c.size(),gets[0]);
        gets[0]=0;var actual=ConstructionSpanMasks.build(counted,road,2);
        assertEquals(c.size(),gets[0],"精确匹配跨度只需要建立一次站号索引");same(old,actual);
    }
    @Test void boundedWeakCacheCanRebuildAfterActualReferenceQueueEviction() throws Exception {
        ConstructionSpanMasks.clear();var c=centers(16);var road=road(c,List.of(new RoadSpan(c.get(2),c.get(5),SpanType.TUNNEL)));
        var first=ConstructionSpanMasks.of(road,2);
        var cacheField=ConstructionSpanMasks.class.getDeclaredField("CACHE");cacheField.setAccessible(true);Object cache=cacheField.get(null);
        var valuesField=cache.getClass().getDeclaredField("values");valuesField.setAccessible(true);
        var values=(Map<?,?>)valuesField.get(cache);assertEquals(1,values.size());
        var reference=(java.lang.ref.Reference<?>)values.keySet().iterator().next();reference.clear();assertTrue(reference.enqueue());
        assertEquals(0,ConstructionSpanMasks.size(),"真实弱引用队列必须驱逐缓存，不依赖GC定时");
        var rebuilt=ConstructionSpanMasks.of(road,2);assertNotSame(first,rebuilt);
        same(FrozenConstructionSpanMasks.build(c,road,2),rebuilt);
        // 强持有夹具，避免GC把容量测试意外变成空表测试。
        var held=new ArrayList<RoadData>();for(int i=0;i<140;i++){var next=road(c,road.spans());held.add(next);ConstructionSpanMasks.of(next,2);}
        assertTrue(ConstructionSpanMasks.size()<=128);assertEquals(140,held.size());
    }
    @Test void concurrentRoadsAndConfigurationVersionsMatchFrozenResults() throws Exception {
        ConstructionSpanMasks.clear();var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        var c=centers(120);var road=road(c,List.of(new RoadSpan(c.get(20),c.get(30),SpanType.ROAD_CROSSING),
                new RoadSpan(c.get(50),c.get(70),SpanType.NETWORK_REUSE)));
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int i=0;i<64;i++){int ramps=i%4;tasks.add(()->{var result=ConstructionSpanMasks.of(road,ramps);
                same(FrozenConstructionSpanMasks.build(c,road,ramps),result);assertEquals(21,result.networkReuseCount());return null;});}
            var results=pool.invokeAll(tasks);assertEquals(64,results.size());for(var result:results)result.get();
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }


    @Test void unmatchedSpanCounterCountsEveryChunkEvenOnCacheHits() throws Exception {
        ConstructionSpanMasks.clear();var c=centers(16);var road=road(c,List.of(
                new RoadSpan(new BlockPos(900,64,0),new BlockPos(950,64,0),SpanType.TUNNEL)));
        var field=ConstructionSpanMasks.class.getDeclaredField("SPAN_MASK_UNMATCHED");field.setAccessible(true);
        var count=(java.util.concurrent.atomic.AtomicLong)field.get(null);long before=count.get();
        var first=ConstructionSpanMasks.of(road,2);assertEquals(before+1,count.get());
        assertSame(first,ConstructionSpanMasks.of(road,2));assertEquals(before+2,count.get());
        var equalButDistinct=road(c,road.spans());assertNotSame(first,ConstructionSpanMasks.of(equalButDistinct,2));
        assertEquals(before+3,count.get(),"弱身份必须区分结构相等的不同道路实例");
    }
    static void same(FrozenConstructionSpanMasks.Masks expected,ConstructionSpanMasks.Masks actual) {
        assertArrayEquals(expected.preserveGrade(),actual.preserveGrade());assertArrayEquals(expected.directCrossing(),actual.directCrossing());
        assertArrayEquals(expected.networkReuse(),actual.networkReuse());assertArrayEquals(expected.protectedGap(),actual.protectedGap());
        assertArrayEquals(expected.tunnel(),actual.tunnel());
    }
}

