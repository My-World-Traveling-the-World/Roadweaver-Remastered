/* 文件职责：宽阶段必须逐对等价且有序，不能因候选减少改变桥共享。 */
package net.shiroha233.roadweaver.pathlogic.bridge;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BridgePairIndexTest {
    @Test void indexedPairsMatchAllPairsForRandomDenseLongAndNegativeBoxes() {
        var random=new Random(471);
        for(int trial=0;trial<100;trial++) {
            var boxes=new ArrayList<BridgeShareDetector.Bounds>();
            for(int i=0;i<100;i++) {
                int x=random.nextInt(10000)-5000,z=random.nextInt(10000)-5000;
                if(trial%3==0) { x%=10;z%=10; }
                boxes.add(new BridgeShareDetector.Bounds(x,z,x+(i%9==0?20000:random.nextInt(100)),z+random.nextInt(100)));
            }
            var index=new BridgePairIndex(boxes);
            for(int radius:new int[]{-1,0,1,24,256}) for(int i=0;i<boxes.size();i++) {
                var expected=new ArrayList<Integer>();
                for(int j=i+1;j<boxes.size();j++) if(BridgeShareDetector.overlapsPrepared(boxes.get(i),boxes.get(j),radius)) expected.add(j);
                assertEquals(expected,index.after(i,radius),"闭区间、负坐标、长桥和原顺序都必须保留");
            }
        }
    }
    @Test void extremeIntegerBoundsKeepLegacyOverflowSemantics() {
        var boxes = List.of(new BridgeShareDetector.Bounds(Integer.MIN_VALUE,0,Integer.MIN_VALUE+8,8),
                new BridgeShareDetector.Bounds(0,0,8,8),
                new BridgeShareDetector.Bounds(Integer.MAX_VALUE-8,0,Integer.MAX_VALUE,8));
        var index = new BridgePairIndex(boxes);
        for (int radius : new int[]{0,24,Integer.MAX_VALUE}) for(int i=0;i<boxes.size();i++) {
            var expected = new ArrayList<Integer>();
            for(int j=i+1;j<boxes.size();j++) if(BridgeShareDetector.overlapsPrepared(boxes.get(i),boxes.get(j),radius)) expected.add(j);
            assertEquals(expected,index.after(i,radius));
        }
        assertTrue(new BridgePairIndex(List.of()).after(0,24).isEmpty());
    }
    @Test void sparsePairsReduceChecksAndRetainExactRadiusBoundary() {
        var boxes=new ArrayList<BridgeShareDetector.Bounds>();
        for(int i=0;i<1024;i++) boxes.add(new BridgeShareDetector.Bounds(i*1000,-100,i*1000+20,-80));
        boxes.set(1,new BridgeShareDetector.Bounds(44,-100,64,-80));
        var index=new BridgePairIndex(boxes);long[] checks={0};int pairs=0;
        for(int i=0;i<boxes.size();i++) pairs+=index.after(i,24,checks).size();
        assertEquals(List.of(1),index.after(0,24));assertEquals(1,pairs);
        assertTrue(checks[0]<1024*16L,"稀疏场景不能退回全对检查");
    }
    @Test void denseAndSmallBatchesAvoidUnprofitableTreeButSparseBatchesUseIt() {
        var sparse = new ArrayList<BridgeShareDetector.Bounds>();
        for(int i=0;i<1024;i++) sparse.add(new BridgeShareDetector.Bounds(i*1000,0,i*1000+40,5));
        assertNotNull(BridgePairIndex.sparseBatch(sparse,24));
        assertNull(BridgePairIndex.sparseBatch(sparse.subList(0,128),24));
        assertNull(BridgePairIndex.sparseBatch(Collections.nCopies(1024,sparse.getFirst()),24));
    }
    @Test void liveSharedRoadsAndStatsMatchFrozenAllPairsInOriginalOrder() {
        for(boolean dense:new boolean[]{false,true}) {
            var roads=new ArrayList<RoadData>();
            for(int i=0;i<512;i++) roads.add(road(dense && i<40?i%8:i*1000,64+i%3));
            var expected=new ArrayList<>(roads);var actual=new ArrayList<>(roads);
            var old=FrozenBridgeShareService.applyInPlace(expected,24);
            var now=BridgeShareService.applyInPlace(actual,24);
            assertEquals(old.toString(),now.toString());assertEquals(expected,actual);
            if(dense) assertTrue(now.absorbed()>0,"必须真实进入吸收，不能用空候选证明等价");
            else assertEquals(0,now.candidates());
        }
    }
    private static RoadData road(int z,int y) {
        var stations=new ArrayList<RoadSegmentPlacement>();var heights=new ArrayList<Integer>();
        for(int x=0;x<40;x++) {var p=new BlockPos(x,y,z);stations.add(new RoadSegmentPlacement(p,List.of(p)));heights.add(y);}
        var road=new RoadData(1,0,List.of(),List.of(),stations,List.of(new RoadSpan(stations.get(12).middlePos(),stations.get(25).middlePos(),SpanType.BRIDGE)),heights,11,13);
        assertTrue(net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator.isValidFinalRoad(road));return road;
    }
}
