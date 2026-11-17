/* 文件职责：逐事件验证真实事务执行器的结案、修复重编及资源释放，异常不能写完成或重放。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionChunkTransactionTest {
    @Test void successKeepsDecorationBeforeLedgerAndReleasesExactlyOnce() {
        var fake=new Fake(false,false,false);var tx=new ConstructionChunkTransaction<>(fake);
        assertTrue(tx.run());assertEquals(List.of("load","empty:old","prepare","process:old","repair","decorate","ledger","finish:old:false","release"),fake.events);
        assertEquals(ConstructionChunkTransaction.State.CLOSED,tx.state());
        assertThrows(IllegalStateException.class,tx::run);assertEquals(1,Collections.frequency(fake.events,"release"));
    }
    @Test void repairConsumesFreshPlanOnceAndDoesNotReplayRepairOrPreparation() {
        var fake=new Fake(false,true,false);var tx=new ConstructionChunkTransaction<>(fake);assertTrue(tx.run());
        assertEquals(List.of("load","empty:old","prepare","process:old","repair","refresh","empty:new","reset","process:new","decorate","ledger","finish:new:true","release"),fake.events);
        assertEquals(1,Collections.frequency(fake.events,"repair"));assertEquals(1,Collections.frequency(fake.events,"prepare"));
    }
    @Test void refreshedEmptyRetainsOldEvidenceAndRefusals() {
        var fake=new Fake(false,true,true);assertTrue(new ConstructionChunkTransaction<>(fake).run());
        assertEquals(List.of("load","empty:old","prepare","process:old","repair","refresh","empty:new","decorate","ledger","finish:old:true","release"),fake.events);
        assertFalse(fake.events.contains("reset"));
    }
    @Test void emptyPlanStillReleasesWithoutPreparationOrLedger() {
        var fake=new Fake(true,false,false);var tx=new ConstructionChunkTransaction<>(fake);assertFalse(tx.run());
        assertEquals(List.of("load","empty:old","empty-detail","release"),fake.events);assertEquals(ConstructionChunkTransaction.State.CLOSED,tx.state());
    }
    @Test void everyFailureBoundaryReleasesAndCannotRepeatSideEffects() {
        for(String point:List.of("load","empty:old","prepare","process:old","repair","refresh","empty:new","reset","process:new","decorate","ledger","finish:new:true","release")) {
            var fake=new Fake(false,true,false);fake.fail=point;var tx=new ConstructionChunkTransaction<>(fake);
            assertThrows(IllegalStateException.class,tx::run,point);
            assertEquals(ConstructionChunkTransaction.State.FAILED,tx.state(),point);
            assertEquals(1,Collections.frequency(fake.events,"release"),point);
            var before=List.copyOf(fake.events);assertThrows(IllegalStateException.class,tx::run);assertEquals(before,fake.events);
            if(point.equals("decorate"))assertFalse(fake.events.contains("ledger"),"装饰异常不可提前记台账");
        }
    }
    private static final class Fake implements ConstructionChunkTransaction.Access<String> {
        final List<String> events=new ArrayList<>();final boolean empty,repair,emptyFresh;String fail;
        Fake(boolean empty,boolean repair,boolean emptyFresh){this.empty=empty;this.repair=repair;this.emptyFresh=emptyFresh;}
        void event(String event){events.add(event);if(event.equals(fail))throw new IllegalStateException("模拟事务边界故障:"+event);}
        public String loadInitial(){event("load");return "old";}
        public boolean isEmpty(String p){event("empty:"+p);return p.equals("old")?empty:emptyFresh;}
        public void emptyPlan(){event("empty-detail");}
        public void prepare(String p){event("prepare");}
        public void process(String p){event("process:"+p);}
        public boolean repairRefusals(){event("repair");return repair;}
        public String loadRefreshed(){event("refresh");return "new";}
        public void resetRefusals(){event("reset");}
        public void decorate(){event("decorate");}
        public void commitCompleted(){event("ledger");}
        public boolean finish(String p,boolean repaired){event("finish:"+p+":"+repaired);return true;}
        public void release(){event("release");}
    }
}

