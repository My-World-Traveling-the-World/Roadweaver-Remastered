/* 文件职责：真实原子写入逐字对拍冻结实现，分开准备/写盘，不把离线样本当FPS。 */
package net.shiroha233.roadweaver.persistence;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import net.minecraft.world.level.ChunkPos;
final class LedgerIncrementalProbe {
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long tid=Thread.currentThread().threadId();
        Path dir=Files.createTempDirectory("ledger-incremental-");var a=dir.resolve("new");var b=dir.resolve("old");
        try{
            for(int n:new int[]{512,8192,65536}){
                var current=new ArrayList<long[]>();var frozen=new ArrayList<long[]>();
                for(int trial=-3;trial<9;trial++){
                    Files.deleteIfExists(a);Files.deleteIfExists(b);
                    var state=RoadConstructionLedger.load(a);var old=FrozenConstructionLedger.load(b);
                    for(int i=0;i<n;i++){var c=new ChunkPos((i&1)==0?-i-1:i,i*31);state.markComplete(c,i);old.markComplete(c,i);}
                    RoadConstructionLedger.flush(state,()->a);FrozenConstructionLedger.flush(old,()->b);
                    var c=new ChunkPos(-170001,260003);state.markComplete(c,Long.MIN_VALUE);old.markComplete(c,Long.MIN_VALUE);
                    var events=new ArrayList<RoadConstructionLedger.FlushTiming>();var previous=new ArrayList<FrozenConstructionLedger.FlushTiming>();
                    // 交替执行顺序，避免总让一个实现占先热身或先触盘。
                    long newBytes,oldBytes,x;
                    if((trial&1)==0){
                        x=allocation.getThreadAllocatedBytes(tid);RoadConstructionLedger.flush(state,()->a,events::add);newBytes=allocation.getThreadAllocatedBytes(tid)-x;
                        x=allocation.getThreadAllocatedBytes(tid);FrozenConstructionLedger.flush(old,()->b,previous::add);oldBytes=allocation.getThreadAllocatedBytes(tid)-x;
                    }else{
                        x=allocation.getThreadAllocatedBytes(tid);FrozenConstructionLedger.flush(old,()->b,previous::add);oldBytes=allocation.getThreadAllocatedBytes(tid)-x;
                        x=allocation.getThreadAllocatedBytes(tid);RoadConstructionLedger.flush(state,()->a,events::add);newBytes=allocation.getThreadAllocatedBytes(tid)-x;
                    }
                    if(Files.mismatch(a,b)!=-1)throw new AssertionError("schema3字节发生变化");
                    if(!RoadConstructionLedger.load(a).isComplete(c,Long.MIN_VALUE))throw new AssertionError("重开丢失");
                    var v=events.getFirst();var w=previous.getFirst();
                    if(trial>=0){current.add(new long[]{v.gatherNanos()+v.sortNanos(),v.writeNanos(),newBytes});frozen.add(new long[]{w.gatherNanos()+w.sortNanos(),w.writeNanos(),oldBytes});}
                }
                System.out.printf(Locale.ROOT,"LEDGER_INCREMENTAL n=%d oldPrepareUs=%.3f newPrepareUs=%.3f oldFlushBytes=%d newFlushBytes=%d oldWriteUs=%.3f newWriteUs=%.3f retainedEntries=%d%n",n,median(frozen,0)/1000.0,median(current,0)/1000.0,median(frozen,2),median(current,2),median(frozen,1)/1000.0,median(current,1)/1000.0,n+1);
            }
            System.out.println("LEDGER_INCREMENTAL_PASS samples=9 warmup=3 actualAtomicWrites=true byteParity=true");
        }finally{Files.deleteIfExists(a);Files.deleteIfExists(b);Files.delete(dir);}
    }
    static long median(List<long[]> values,int field){return values.stream().mapToLong(v->v[field]).sorted().toArray()[values.size()/2];}
}
