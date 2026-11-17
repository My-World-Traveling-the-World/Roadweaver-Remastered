/* 文件职责：按冻结道路复用施工只读跨度掩码，共用索引但保留各类型端点匹配语义。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.util.WeakIdentityCache;

/** 不读世界；值只有位标志和计数，不反向持有道路，区块间不能修改共享掩码。 */
public final class ConstructionSpanMasks {
    private static final WeakIdentityCache<RoadData,Cached> CACHE=new WeakIdentityCache<>(128);
    private static final int PRESERVE=1,DIRECT=2,REUSE=4,GAP=8,TUNNEL=16;
    private static final int SPAN_MASK_FALLBACK_TOLERANCE=4;
    private static final java.util.concurrent.atomic.AtomicLong SPAN_MASK_UNMATCHED=new java.util.concurrent.atomic.AtomicLong();
    private record Cached(int ramps,Masks masks) {}
    private ConstructionSpanMasks() {}
    /** 只留最近一个有效坡道宽度，热改往返不会为同一道路积累无界版本。 */
    public static Masks of(RoadData road,int rampSegments) {
        int ramps=Math.max(0,rampSegments);Cached cached=CACHE.get(road);
        if(cached!=null && cached.ramps()==ramps) {
            if(cached.masks().unmatched!=0)SPAN_MASK_UNMATCHED.addAndGet(cached.masks().unmatched);
            return cached.masks();
        }
        // 冷计算放锁外；并发首次允许重复纯计算，不让长路阻塞其他道路的热读取。
        var centers=road.roadSegmentList().stream().map(RoadSegmentPlacement::middlePos).toList();
        Masks masks=build(centers,road,ramps);CACHE.put(road,new Cached(ramps,masks));return masks;
    }
    /** 单次只建一个XZ索引；近站按类型需要求解，桥头保高仍严格精确匹配。 */
    public static Masks build(List<BlockPos> centers,RoadData road,int rampSegments) {
        byte[] flags=new byte[centers.size()];int unmatched=0;
        if(road==null || road.spans().isEmpty())return new Masks(flags,0);
        Map<Long,Integer> indices=new HashMap<>();
        for(int i=0;i<centers.size();i++)indices.put(xzKey(centers.get(i)),i);
        int ramps=Math.max(0,rampSegments);
        for(var span:road.spans()) {
            if(span==null)continue;
            if(span.type()==SpanType.BRIDGE || span.type()==SpanType.ROAD_CROSSING) {
                Integer first=indices.get(xzKey(span.start())),second=indices.get(xzKey(span.end()));
                if(first!=null && second!=null)mark(flags,Math.max(0,Math.min(first,second)-ramps),
                        Math.min(flags.length-1,Math.max(first,second)+ramps),PRESERVE);
            }
            int bit=span.type()==SpanType.ROAD_CROSSING?DIRECT:span.type()==SpanType.NETWORK_REUSE?REUSE:
                    span.type()==SpanType.PROTECTED_GAP?GAP:span.type()==SpanType.TUNNEL?TUNNEL:0;
            if(bit==0)continue;
            Integer first=resolveStationIndex(indices,centers,span.start()),second=resolveStationIndex(indices,centers,span.end());
            if(first==null || second==null){unmatched++;continue;}
            mark(flags,Math.max(0,Math.min(first,second)),Math.min(flags.length-1,Math.max(first,second)),bit);
        }
        if(unmatched!=0)SPAN_MASK_UNMATCHED.addAndGet(unmatched);
        return new Masks(flags,unmatched);
    }
    private static void mark(byte[] flags,int start,int end,int bit) {
        for(int i=start;i<=end;i++)flags[i]|=(byte)bit;
    }
    /** 同XZ取最后站，近站并列取最早站；保留原int距离口径，避免优化改变极端输入。 */
    private static Integer resolveStationIndex(Map<Long,Integer> indices,List<BlockPos> centers,BlockPos target) {
        if(target==null || centers.isEmpty())return null;
        Integer exact=indices.get(xzKey(target));if(exact!=null)return exact;
        int best=-1,bestDist=Integer.MAX_VALUE;
        for(int i=0;i<centers.size();i++) {
            BlockPos c=centers.get(i);if(c==null)continue;
            int d=Math.max(Math.abs(c.getX()-target.getX()),Math.abs(c.getZ()-target.getZ()));
            if(d<bestDist){bestDist=d;best=i;}
        }
        return best>=0 && bestDist<=SPAN_MASK_FALLBACK_TOLERANCE?best:null;
    }
    private static long xzKey(BlockPos pos) {
        return pos==null?Long.MIN_VALUE:((long)pos.getX()<<32)|(pos.getZ()&0xffffffffL);
    }
    /** 私有位数组不外泄；生产按站只读，显式导出返回副本用于诊断和独立对拍。 */
    public static final class Masks {
        private final byte[] flags;
        private final int unmatched,tunnels,reuses,gaps;
        private Masks(byte[] flags,int unmatched) {
            this.flags=flags;this.unmatched=unmatched;
            int t=0,r=0,g=0;for(byte f:flags){if((f&TUNNEL)!=0)t++;if((f&REUSE)!=0)r++;if((f&GAP)!=0)g++;}
            tunnels=t;reuses=r;gaps=g;
        }
        public int size(){return flags.length;}
        public boolean preserveGradeAt(int i){return (flags[i]&PRESERVE)!=0;}
        public boolean directCrossingAt(int i){return (flags[i]&DIRECT)!=0;}
        public boolean networkReuseAt(int i){return (flags[i]&REUSE)!=0;}
        public boolean protectedGapAt(int i){return (flags[i]&GAP)!=0;}
        public boolean tunnelAt(int i){return (flags[i]&TUNNEL)!=0;}
        public int tunnelCount(){return tunnels;}
        public int networkReuseCount(){return reuses;}
        public int protectedGapCount(){return gaps;}
        public boolean[] preserveGrade(){return copy(PRESERVE);}
        public boolean[] directCrossing(){return copy(DIRECT);}
        public boolean[] networkReuse(){return copy(REUSE);}
        public boolean[] protectedGap(){return copy(GAP);}
        public boolean[] tunnel(){return copy(TUNNEL);}
        private boolean[] copy(int bit){boolean[] result=new boolean[flags.length];for(int i=0;i<flags.length;i++)result[i]=(flags[i]&bit)!=0;return result;}
    }
    /** 弱键回收后下次访问自动排空；显式清表用于诊断重建而非正确性前提。 */
    public static void clear(){CACHE.clear();}
    public static int size(){return CACHE.size();}
}
