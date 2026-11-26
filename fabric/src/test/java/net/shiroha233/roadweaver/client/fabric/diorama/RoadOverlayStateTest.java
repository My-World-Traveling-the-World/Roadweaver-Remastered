package net.shiroha233.roadweaver.client.fabric.diorama;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi;
import static org.junit.jupiter.api.Assertions.*;

/** 同服换世界、关闭重开与请求合并必须实际执行，拒绝旧任务污染新会话。 */
class RoadOverlayStateTest {
    @Test void oldWorldAndClosedScreenResponsesCannotPublish() {
        var state=new RoadOverlayState();var a=new Object();var b=new Object();
        var area=new ViewportBounds(-10,-10,10,10);
        long first=state.begin(a);assertEquals(-1,state.begin(a));
        long second=state.begin(b);var result=new RoadOverlayApi.Snapshot(RoadOverlayApi.State.EMPTY,42,false,List.of());
        state.complete(a,first,result,area);assertEquals(0,state.view().snapshot().revision());
        state.complete(b,second,result,area);assertSame(result,state.view().snapshot());assertEquals(area,state.view().bounds());
        long closed=state.begin(b);state.clear();state.complete(b,closed,result,area);
        assertEquals(RoadOverlayApi.State.UNAVAILABLE,state.view().snapshot().state());assertNull(state.view().bounds());
        assertTrue(state.begin(b)>closed);
    }
}
