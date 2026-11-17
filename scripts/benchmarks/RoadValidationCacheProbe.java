/* 文件职责：测道路内容键与弱身份键的热命中成本，不推断游戏帧率。 */
package net.shiroha233.roadweaver.pathlogic.validation;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.util.WeakIdentityCache;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class RoadValidationCacheProbe {
    private static volatile Object sink;
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        publicationCost();
        for (int n : new int[]{32,1024,8192}) {
            List<RoadSegmentPlacement> stations = new ArrayList<>();
            List<Integer> y = new ArrayList<>();
            for (int x=0;x<n;x++) {
                List<BlockPos> columns = new ArrayList<>();
                for(int z=-2;z<=2;z++) columns.add(new BlockPos(x,64,z));
                stations.add(new RoadSegmentPlacement(new BlockPos(x,64,0),columns)); y.add(64);
            }
            RoadData road = new RoadData(5,0,List.of(),List.of(),stations,List.of(),y,1,2);
            var content = new ConcurrentHashMap<RoadData,Object>();
            var identity = new WeakIdentityCache<RoadData,Object>(4096);
            Object result = new Object(); content.put(road,result); identity.put(road,result);
            int repeats=10000;
            for(int w=0;w<3;w++) { measure(content,road,repeats); measure(identity,road,repeats); }
            long[] a=new long[9],b=new long[9];
            for(int i=0;i<9;i++) {
                if(i%2==0) { a[i]=measure(content,road,repeats);b[i]=measure(identity,road,repeats); }
                else { b[i]=measure(identity,road,repeats);a[i]=measure(content,road,repeats); }
            }
            var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long tid=Thread.currentThread().threadId(), allocated=bean.getThreadAllocatedBytes(tid);
            measure(content,road,repeats);
            double contentBytes=(bean.getThreadAllocatedBytes(tid)-allocated)/(double)repeats;
            allocated=bean.getThreadAllocatedBytes(tid); measure(identity,road,repeats);
            double weakBytes=(bean.getThreadAllocatedBytes(tid)-allocated)/(double)repeats;
            System.out.printf(java.util.Locale.ROOT,"CACHE_ALLOCATION stations=%d contentBytesPerGet=%.1f weakBytesPerGet=%.1f%n",n,contentBytes,weakBytes);
            Arrays.sort(a);Arrays.sort(b);
            System.out.printf(java.util.Locale.ROOT,"CACHE_PROBE stations=%d contentP50Ns=%.1f weakIdentityP50Ns=%.1f contentP95Ns=%.1f weakIdentityP95Ns=%.1f%n",n,a[4]/(double)repeats,b[4]/(double)repeats,a[8]/(double)repeats,b[8]/(double)repeats);
        }
    }
    private record OldPlacement(BlockPos center,List<BlockPos> columns) {}
    private static void publicationCost() {
        var bean = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        BlockPos center=new BlockPos(0,64,0);
        List<BlockPos> columns=java.util.stream.IntStream.rangeClosed(-2,2).mapToObj(z -> new BlockPos(0,64,z)).toList();
        for(int i=0;i<20000;i++) { sink=new OldPlacement(center,columns);sink=new RoadSegmentPlacement(center,columns); }
        for(boolean frozen : new boolean[]{false,true}) {
            long allocated=bean.getThreadAllocatedBytes(thread), start=System.nanoTime();
            for(int i=0;i<100000;i++) sink=frozen ? new RoadSegmentPlacement(center,columns) : new OldPlacement(center,columns);
            long elapsed=System.nanoTime()-start, bytes=bean.getThreadAllocatedBytes(thread)-allocated;
            System.out.printf(java.util.Locale.ROOT,"PUBLICATION_PROBE frozen=%s columns=5 nsPerStation=%.1f bytesPerStation=%.1f%n",frozen,elapsed/100000.0,bytes/100000.0);
        }
    }

    private static long measure(Object cache,RoadData road,int repeats) {
        long start=System.nanoTime();
        for(int i=0;i<repeats;i++) sink = cache instanceof Map<?,?> map ? map.get(road) : cacheGetCast(cache).get(road);
        return System.nanoTime()-start;
    }
    @SuppressWarnings("unchecked") private static WeakIdentityCache<RoadData,Object> cacheGetCast(Object cache) { return (WeakIdentityCache<RoadData,Object>)cache; }
}
