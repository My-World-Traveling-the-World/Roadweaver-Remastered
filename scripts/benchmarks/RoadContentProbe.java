/* 文件职责：量诊断摘要重复输出与已发布分站再次复制的成本，不外推游戏帧率。 */
package net.shiroha233.roadweaver.pathlogic.metrics;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
public final class RoadContentProbe {
    private static volatile Object sink;
    public static void main(String[] args){
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        for(int n:new int[]{32,1024,8192}){
            var stations=new ArrayList<RoadSegmentPlacement>();var heights=new ArrayList<Integer>();
            for(int x=0;x<n;x++){var columns=new ArrayList<BlockPos>();for(int z=-2;z<=2;z++)columns.add(new BlockPos(x,64,z));
                stations.add(new RoadSegmentPlacement(new BlockPos(x,64,0),columns));heights.add(64);}
            var road=new RoadData(5,0,List.of(),List.of(),stations,List.of(),heights,1,2);
            if(!RoadBuildEvidence.contentUncached(road).equals(RoadBuildEvidence.content(road)))throw new AssertionError();
            int reps=256;
            for(int w=0;w<3;w++){measure(road,false,reps);measure(road,true,reps);}
            long[] old=new long[9],now=new long[9];
            for(int i=0;i<9;i++){if(i%2==0){old[i]=measure(road,false,reps);now[i]=measure(road,true,reps);}
                else{now[i]=measure(road,true,reps);old[i]=measure(road,false,reps);}}
            Arrays.sort(old);Arrays.sort(now);
            System.out.printf(Locale.ROOT,"CONTENT_PROBE stations=%d coldP50Us=%.2f hotP50Us=%.4f coldP95Us=%.2f hotP95Us=%.4f coldBytes=%.1f hotBytes=%.1f%n",n,
                old[4]/(1000.0*reps),now[4]/(1000.0*reps),old[8]/(1000.0*reps),now[8]/(1000.0*reps),bytes(road,false,reps),bytes(road,true,reps));
            for(boolean copy:new boolean[]{true,false}){
                for(int w=0;w<3;w++)placements(road,copy,128);
                var bean=bean();long tid=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
                placements(road,copy,128);long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(tid)-before;
                System.out.printf(Locale.ROOT,"STATION_REUSE n=%d copy=%s usPerRoad=%.2f bytesPerRoad=%.1f%n",n,copy,elapsed/128000.0,allocated/128.0);
            }
        }
    }
    private static void placements(RoadData road,boolean copy,int repeats){
        for(int r=0;r<repeats;r++){var out=new ArrayList<RoadSegmentPlacement>(road.roadSegmentList().size());
            for(var station:road.roadSegmentList())out.add(copy?new RoadSegmentPlacement(station.middlePos(),station.positions()):station);sink=out;}
    }
    private static long measure(RoadData road,boolean cached,int repeats){long start=System.nanoTime();
        for(int r=0;r<repeats;r++)sink=cached?RoadBuildEvidence.content(road):RoadBuildEvidence.contentUncached(road);return System.nanoTime()-start;}
    private static com.sun.management.ThreadMXBean bean(){var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);return bean;}
    private static double bytes(RoadData road,boolean cached,int repeats){var bean=bean();long tid=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(tid);
        measure(road,cached,repeats);return (bean.getThreadAllocatedBytes(tid)-before)/(double)repeats;}
}
