/* 文件职责：分阶段量施工输入，世界高度用计数Fake，不把内核探针当FPS或真实读块时间。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.pathlogic.pathcalc.HeightProfileService;
public final class ConstructionInputsProbe {
    private static volatile Object sink;
    private static long heightReads;
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        var world=(WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(p,m,a)->{
            if(m.getName().equals("getHeight")){heightReads++;return 64+Math.floorMod((int)a[1]+(int)a[2],13);}throw new AssertionError(m.getName());});
        for(int n:new int[]{32,1024,8192}) {
            var centers=new ArrayList<BlockPos>();for(int i=0;i<n;i++)centers.add(new BlockPos(i,64+i%7,0));
            var spans=new ArrayList<RoadSpan>();for(var type:SpanType.values())for(int i=4;i<n-3;i+=64)
                spans.add(new RoadSpan(centers.get(i),centers.get(Math.min(n-1,i+12)),type));
            var road=new RoadData(3,0,List.of(),List.of(),centers.stream().map(p->new RoadSegmentPlacement(p,List.of(p))).toList(),
                    spans,centers.stream().map(BlockPos::getY).toList(),1,2);
            compareMasks(road,centers,bean);
            var frame=BuildFrame.of(1,road.roadSegmentList(),road.spans(),null);
            for(String stage:List.of("masksFrozen","masksCold","masksHot","targetY","copyForUser","persistedHeight","sampledHeight")) {
                int reps=stage.equals("sampledHeight")?16:128;
                Runnable action=switch(stage){
                    case "masksFrozen" -> ()->sink=FrozenConstructionSpanMasks.build(centers,road,4);
                    case "masksCold" -> ()->sink=ConstructionSpanMasks.build(centers,road,4);
                    case "masksHot" -> ()->sink=ConstructionSpanMasks.of(road,4);
                    case "targetY" -> ()->sink=road.targetY().stream().mapToInt(Integer::intValue).toArray();
                    case "copyForUser" -> ()->sink=frame.copyForUser();
                    case "persistedHeight" -> ()->sink=HeightProfileService.build(world,centers,new ChunkPos(0,0),4,true,1,road.targetY());
                    default -> ()->sink=HeightProfileService.build(world,centers,new ChunkPos(0,0),4,true,1,List.of());};
                for(int w=0;w<3;w++)run(action,reps);
                long[] times=new long[7];heightReads=0;long tid=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(tid);
                for(int r=0;r<7;r++){long start=System.nanoTime();run(action,reps);times[r]=System.nanoTime()-start;}
                long bytes=bean.getThreadAllocatedBytes(tid)-before;Arrays.sort(times);
                System.out.printf(Locale.ROOT,"CONSTRUCTION_INPUT n=%d spans=%d stage=%s p50Us=%.3f bytes=%.1f readsPerCall=%d%n",n,spans.size(),stage,times[3]/(reps*1000.0),bytes/(7.0*reps),heightReads/(7L*reps));
            }
        }
    }
    private static void compareMasks(RoadData road,List<BlockPos> centers,com.sun.management.ThreadMXBean bean) {
        String[] names={"frozen","indexedCold","identityCold","identityHot"};
        Runnable[] actions={()->sink=FrozenConstructionSpanMasks.build(centers,road,4),
            ()->sink=ConstructionSpanMasks.build(centers,road,4),
            ()->{ConstructionSpanMasks.clear();sink=ConstructionSpanMasks.of(road,4);},
            ()->sink=ConstructionSpanMasks.of(road,4)};
        int reps=128;for(int w=0;w<3;w++)for(var action:actions)run(action,reps);
        long[][] times=new long[4][9];double[] bytes=new double[4];long tid=Thread.currentThread().threadId();
        for(int r=0;r<9;r++)for(int j=0;j<4;j++) {
            int k=r%2==0?j:3-j;long before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
            run(actions[k],reps);times[k][r]=System.nanoTime()-start;
            bytes[k]+=bean.getThreadAllocatedBytes(tid)-before;
        }
        for(int k=0;k<4;k++){Arrays.sort(times[k]);System.out.printf(Locale.ROOT,
            "MASK_COMPARE n=%d mode=%s p50Us=%.3f maxBatchUs=%.3f bytes=%.1f%n",centers.size(),names[k],
            times[k][4]/(reps*1000.0),times[k][8]/(reps*1000.0),bytes[k]/(reps*9.0));}
        var expected=FrozenConstructionSpanMasks.build(centers,road,4);var actual=ConstructionSpanMasks.of(road,4);
        if(!Arrays.equals(expected.preserveGrade(),actual.preserveGrade()) || !Arrays.equals(expected.directCrossing(),actual.directCrossing())
            || !Arrays.equals(expected.networkReuse(),actual.networkReuse()) || !Arrays.equals(expected.protectedGap(),actual.protectedGap())
            || !Arrays.equals(expected.tunnel(),actual.tunnel()))throw new AssertionError("五类掩码必须逐项相同");
    }

    private static void run(Runnable action,int n){for(int i=0;i<n;i++)action.run();}
}

