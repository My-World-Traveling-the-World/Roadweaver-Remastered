/* 文件职责：对拍分批恢复的站操作、随机和结案事件，暂停/异常/换代绝不能冒报完成。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionStationCursorTest {
    @Test void everyPauseBoundaryKeepsOperationRandomAndFinishOrder() {
        for(int batch=1;batch<=32;batch++) {
            var expected=new ArrayList<String>();var actual=new ArrayList<String>();
            var a=new Random(50);var b=new Random(50);
            for(int i=0;i<32;i++)station(expected,a,i*2);
            var cursor=new ConstructionStationCursor(32,i->i*2);int calls=0;
            while(cursor.state()==ConstructionStationCursor.State.READY){int before=cursor.nextOffset();calls+=cursor.advance(batch,()->true,i->station(actual,b,i));
                assertTrue(cursor.nextOffset()>before);if(cursor.nextOffset()<32)assertNotEquals(ConstructionStationCursor.State.COMPLETE,cursor.state());}
            assertEquals(32,calls);assertEquals(expected,actual);assertEquals(ConstructionStationCursor.State.COMPLETE,cursor.state());
            assertEquals(0,cursor.advance(5,()->true,i->fail("结案不能重复施工")));
        }
    }
    @Test void cancellationAndFailureCannotReplayOrCompletePartiallyWrittenStation() {
        var cursor=new ConstructionStationCursor(5,i->i);var out=new ArrayList<Integer>();cursor.advance(2,()->true,out::add);
        assertEquals(0,cursor.advance(2,()->false,out::add));assertEquals(ConstructionStationCursor.State.CANCELLED,cursor.state());
        assertEquals(List.of(0,1),out);cursor.runAll(i->fail());
        var failed=new ConstructionStationCursor(3,i->i);assertThrows(IllegalStateException.class,()->failed.advance(3,()->true,i->{out.add(i);throw new IllegalStateException("有副作用后失败");}));
        assertEquals(ConstructionStationCursor.State.FAILED,failed.state());failed.runAll(i->fail());assertEquals(0,failed.nextOffset());
    }
    @Test void zeroBudgetPausesAndEmptyInputCompletesWithoutOperations() {
        var cursor=new ConstructionStationCursor(2,i->i);assertEquals(0,cursor.advance(0,()->true,i->fail()));assertEquals(0,cursor.nextOffset());
        assertEquals(ConstructionStationCursor.State.READY,cursor.state());assertEquals(ConstructionStationCursor.State.COMPLETE,new ConstructionStationCursor(0,i->i).state());
    }
    @Test void sliceStateCannotCompleteWhilePausedOrAfterRefusal() {
        var state=new ConstructionSliceState(8,4,i->i*2);assertFalse(state.completed());
        state.cursor().advance(2,()->true,i->{});assertFalse(state.completed());
        state.cursor().runAll(i->{});assertTrue(state.completed());state.refuse(2);assertFalse(state.completed());assertTrue(state.refused(2));assertFalse(state.refused(3));
        var failed=new ConstructionSliceState(8,4,i->i);failed.fail();failed.cursor().runAll(i->{});assertFalse(failed.completed());
        var cancelled=new ConstructionSliceState(8,4,i->i);cancelled.cursor().advance(8,()->false,i->fail());assertFalse(cancelled.completed());
    }
    private static void station(List<String> events,Random random,int i) {
        events.add("write:"+i+":"+random.nextInt());if(i%6==0)events.add("refuse:"+i);events.add("decorate:"+i+":"+random.nextInt());
    }
}

