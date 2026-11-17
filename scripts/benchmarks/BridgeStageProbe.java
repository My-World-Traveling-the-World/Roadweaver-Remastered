/* 文件职责：同JVM交替量桥曲线构建/查询和共桥候选分配，不代表游戏帧时。 */
package net.shiroha233.roadweaver.pathlogic.bridge;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
public class BridgeStageProbe {
 static volatile Object sink;
 public static void main(String[] args) {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var stations=new ArrayList<RoadSegmentPlacement>();boolean[] mask=new boolean[4096];
  for(int i=0;i<mask.length;i++){var p=new BlockPos(i,64,0);stations.add(new RoadSegmentPlacement(p,List.of(p)));mask[i]=i%64<32;}
  var old=new FrozenBridgeSegment(mask,stations);var now=new BridgeSegment(mask,stations);
  compare("bridgeBuild4096",()->sink=new FrozenBridgeSegment(mask,stations),()->sink=new BridgeSegment(mask,stations),64);
  compare("bridgeQuery4096",()->{for(int i=0;i<4096;i++)sink=old.getLine(i);},()->{for(int i=0;i<4096;i++)sink=now.getLine(i);},128);
  for(int pattern=0;pattern<3;pattern++) {
   var boxes=new ArrayList<BridgeShareDetector.Bounds>();for(int i=0;i<600;i++){int x=pattern==0?i*1000:i<150?i%10:i*70;int z=i<150?i%7:i%9*500;
    boxes.add(new BridgeShareDetector.Bounds(x,z,x+(pattern==2?20000:40),z+10));}
   var a=new FrozenBridgePairIndex(boxes);var b=new BridgePairIndex(boxes);
   compare("candidate600pattern"+pattern,()->{for(int i=0;i<600;i++)sink=a.after(i,24);},()->{for(int i=0;i<600;i++)sink=b.afterInts(i,24);},4);
  }
 }
 public static void compare(String name,Runnable old,Runnable now,int reps) {
  var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
  for(int w=0;w<3;w++){run(old,reps);run(now,reps);}long[][] times=new long[2][7];long[] bytes=new long[2];long tid=Thread.currentThread().threadId();
  for(int r=0;r<7;r++)for(int j=0;j<2;j++){int k=r%2==0?j:1-j;long before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();run(k==0?old:now,reps);times[k][r]=System.nanoTime()-start;bytes[k]+=bean.getThreadAllocatedBytes(tid)-before;}
  Arrays.sort(times[0]);Arrays.sort(times[1]);System.out.printf(Locale.ROOT,"SIX_PROBE name=%s oldUs=%.3f newUs=%.3f oldBytes=%.1f newBytes=%.1f%n",name,times[0][3]/(1000.0*reps),times[1][3]/(1000.0*reps),bytes[0]/(7.0*reps),bytes[1]/(7.0*reps));
 }
 private static void run(Runnable r,int n){for(int i=0;i<n;i++)r.run();}
}

