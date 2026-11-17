/* 文件职责：量共桥宽阶段含建树/排序的总成本；真实吸收产物另由冻结实现逐站对拍。 */
package net.shiroha233.roadweaver.pathlogic.bridge;
import java.util.*;
public final class BridgePairProbe {
    private static volatile long sink;
    public static void main(String[] args) {
        for (int n : new int[]{32,128,512,1024}) for(String scene:List.of("sparse","clustered","dense","long")) {
            var boxes = boxes(n,scene);
            long expected = enumerate(boxes,null);
            if(indexed(boxes)!=expected || selected(boxes)!=expected) throw new AssertionError("候选/顺序校验失败");
            int reps = n==1024 ? 8 : 80;
            for(int w=0;w<3;w++){ measure(boxes,0,reps);measure(boxes,1,reps);measure(boxes,2,reps); }
            long[] old=new long[9],now=new long[9],chosen=new long[9];
            for(int i=0;i<9;i++) {
                if(i%2==0){old[i]=measure(boxes,0,reps);now[i]=measure(boxes,1,reps);}
                else {now[i]=measure(boxes,1,reps);old[i]=measure(boxes,0,reps);}
                chosen[i]=measure(boxes,2,reps);
            }
            var index=new BridgePairIndex(boxes);long[] checks={0};long pairs=0;
            for(int i=0;i<n;i++) pairs+=index.after(i,24,checks).size();
            Arrays.sort(chosen);Arrays.sort(old);Arrays.sort(now);
            System.out.printf(Locale.ROOT,"PAIR_PROBE n=%d scene=%s pairs=%d allChecks=%d indexChecks=%d directP50Us=%.2f indexP50Us=%.2f directP95Us=%.2f indexP95Us=%.2f selectedP50Us=%.2f%n",
                n,scene,pairs,(long)n*(n-1)/2,checks[0],old[4]/(1000.0*reps),now[4]/(1000.0*reps),old[8]/(1000.0*reps),now[8]/(1000.0*reps),chosen[4]/(1000.0*reps));
        }
    }
    private static List<BridgeShareDetector.Bounds> boxes(int n,String scene) {
        var boxes=new ArrayList<BridgeShareDetector.Bounds>();var random=new Random(471);
        for(int i=0;i<n;i++){
            int x,z,length=40;
            switch(scene){
                case "sparse" -> {x=i*1000;z=i%5*100;}
                case "clustered" -> {x=(i/8)*1000+i%8;z=i%8;}
                case "dense" -> {x=i%8;z=i%8;}
                default -> {x=random.nextInt(10000);z=i%4;length=20000;}
            }
            boxes.add(new BridgeShareDetector.Bounds(x,z,x+length,z+5));
        }return boxes;
    }
    // 三种策略共用同一枚举内核，避免不同调用点的JIT版本被误当成算法差异。
    private static long enumerate(List<BridgeShareDetector.Bounds> boxes,BridgePairIndex index){
        long hash=0;
        for(int i=0;i<boxes.size();i++){
            List<Integer> nearby=index==null?null:index.after(i,24);
            int count=nearby==null?boxes.size()-i-1:nearby.size();
            for(int p=0;p<count;p++){
                int j=nearby==null?i+p+1:nearby.get(p);
                if(BridgeShareDetector.overlapsPrepared(boxes.get(i),boxes.get(j),24))hash=31*hash+(long)i*boxes.size()+j;
            }
        }return hash;
    }
    private static long selected(List<BridgeShareDetector.Bounds> boxes){return enumerate(boxes,BridgePairIndex.sparseBatch(boxes,24));}
    private static long indexed(List<BridgeShareDetector.Bounds> boxes){return enumerate(boxes,new BridgePairIndex(boxes));}
    private static long measure(List<BridgeShareDetector.Bounds> boxes,int strategy,int repeats){
        long start=System.nanoTime();for(int r=0;r<repeats;r++)sink=switch(strategy){case 0 -> enumerate(boxes,null);case 1 -> indexed(boxes);default -> selected(boxes);};return System.nanoTime()-start;
    }
}
