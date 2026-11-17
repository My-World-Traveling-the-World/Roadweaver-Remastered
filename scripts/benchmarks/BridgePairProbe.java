/* 文件职责：量共桥宽阶段含建树/排序的总成本；真实吸收产物另由冻结实现逐站对拍。 */
package net.shiroha233.roadweaver.pathlogic.bridge;
import java.util.*;
public final class BridgePairProbe {
    private static volatile long sink;
    public static void main(String[] args) {
        for (int n : new int[]{32,128,512,1024}) for(String scene:List.of("sparse","clustered","dense","long")) {
            var boxes = boxes(n,scene);
            long expected = direct(boxes);
            if(indexed(boxes)!=expected || selected(boxes)!=expected) throw new AssertionError("候选/顺序校验失败");
            int reps = n==1024 ? 8 : 80;
            for(int w=0;w<3;w++){ measure(boxes,false,reps);measure(boxes,true,reps);for(int r=0;r<reps;r++)sink=selected(boxes); }
            long[] old=new long[9],now=new long[9],chosen=new long[9];
            for(int i=0;i<9;i++) {
                if(i%2==0){old[i]=measure(boxes,false,reps);now[i]=measure(boxes,true,reps);}
                else {now[i]=measure(boxes,true,reps);old[i]=measure(boxes,false,reps);}
                long start=System.nanoTime();for(int r=0;r<reps;r++)sink=selected(boxes);chosen[i]=System.nanoTime()-start;
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
    private static long direct(List<BridgeShareDetector.Bounds> boxes){
        long hash=0;for(int i=0;i<boxes.size();i++)for(int j=i+1;j<boxes.size();j++)
            if(BridgeShareDetector.overlapsPrepared(boxes.get(i),boxes.get(j),24))hash=31*hash+(long)i*boxes.size()+j;
        return hash;
    }
    private static long selected(List<BridgeShareDetector.Bounds> boxes){
        var index=BridgePairIndex.sparseBatch(boxes,24);
        if(index==null)return direct(boxes);
        long hash=0;for(int i=0;i<boxes.size();i++)for(int j:index.after(i,24))hash=31*hash+(long)i*boxes.size()+j;
        return hash;
    }
    private static long indexed(List<BridgeShareDetector.Bounds> boxes){
        var index=new BridgePairIndex(boxes);long hash=0;
        for(int i=0;i<boxes.size();i++)for(int j:index.after(i,24))hash=31*hash+(long)i*boxes.size()+j;
        return hash;
    }
    private static long measure(List<BridgeShareDetector.Bounds> boxes,boolean useIndex,int repeats){
        long start=System.nanoTime();for(int r=0;r<repeats;r++)sink=useIndex?indexed(boxes):direct(boxes);return System.nanoTime()-start;
    }
}
