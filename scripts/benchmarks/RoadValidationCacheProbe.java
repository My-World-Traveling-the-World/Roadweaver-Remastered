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
            Arrays.sort(a);Arrays.sort(b);
            System.out.printf(java.util.Locale.ROOT,"CACHE_PROBE stations=%d contentP50Ns=%.1f weakIdentityP50Ns=%.1f contentP95Ns=%.1f weakIdentityP95Ns=%.1f%n",n,a[4]/(double)repeats,b[4]/(double)repeats,a[8]/(double)repeats,b[8]/(double)repeats);
        }
    }
    private static long measure(Object cache,RoadData road,int repeats) {
        long start=System.nanoTime();
        for(int i=0;i<repeats;i++) sink = cache instanceof Map<?,?> map ? map.get(road) : cacheGetCast(cache).get(road);
        return System.nanoTime()-start;
    }
    @SuppressWarnings("unchecked") private static WeakIdentityCache<RoadData,Object> cacheGetCast(Object cache) { return (WeakIdentityCache<RoadData,Object>)cache; }
}
