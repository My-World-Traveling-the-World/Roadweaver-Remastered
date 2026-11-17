/* 文件职责：逐站对拍旧桥曲线与桥头，保证直接索引不改变目标Y、投影和长桥正弦。 */
package net.shiroha233.roadweaver.pathlogic.bridge;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BridgeSegmentIndexTest {
    @Test void randomMasksAndTargetProfilesMatchFrozenCurves() {
        Random r=new Random(501);int hits=0;
        for(int trial=0;trial<80;trial++) {
            int n=trial==0?0:300;boolean[] mask=new boolean[n];int[] ys=new int[trial%3==0?n/2:n];
            var stations=new ArrayList<RoadSegmentPlacement>();
            for(int i=0;i<n;i++){var p=new BlockPos(-400+i*2,40+i%13,-30+i%7);stations.add(new RoadSegmentPlacement(p,List.of(p)));mask[i]=r.nextInt(9)!=0;if(i<ys.length)ys[i]=70+i%11;}
            var old=new FrozenBridgeSegment(mask,stations,trial%2==0?ys:null);var now=new BridgeSegment(mask,stations,trial%2==0?ys:null);
            for(int i=-2;i<n+2;i++) {
                assertEquals(old.isLanding(i),now.isLanding(i));var a=old.getLine(i);var b=now.getLine(i);
                if(a==null){assertNull(b);continue;}assertNotNull(b);hits++;
                assertEquals(a.isCurved(),b.isCurved());
                for(double t:new double[]{-1,0,.13,.5,.9,1,2})assertEquals(a.pointAt(t),b.pointAt(t));
                assertEquals(a.mapRoadPosition(stations.get(i).middlePos()),b.mapRoadPosition(stations.get(i).middlePos()));
            }
        }
        assertTrue(hits>1000);
    }
    @Test void exactCurveThresholdLandingAndRunIdentityArePreserved() {
        for(int chord:new int[]{0,95,96,97,240}) {
            var stations=new ArrayList<RoadSegmentPlacement>();for(int i=0;i<=chord;i++){var p=new BlockPos(i,64,0);stations.add(new RoadSegmentPlacement(p,List.of(p)));}
            boolean[] mask=new boolean[stations.size()];Arrays.fill(mask,true);
            var now=new BridgeSegment(mask,stations,new int[stations.size()]);
            assertTrue(now.isLanding(0));assertTrue(now.isLanding(chord));assertEquals(chord>=96,now.getLine(0).isCurved());
            assertSame(now.getLine(0),now.getLine(chord));assertEquals(0,now.getLine(0).pointAt(0).y);
            if(chord>1)assertFalse(now.isLanding(1));assertNull(now.getLine(-1));assertNull(now.getLine(chord+1));
        }
    }
}

