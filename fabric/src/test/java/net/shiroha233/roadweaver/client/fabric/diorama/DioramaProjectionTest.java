package net.shiroha233.roadweaver.client.fabric.diorama;
import java.util.List;
import dev.diorama.*;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用实际Diorama发行类，确认高桥不被压成Y=0，也不让旧Drawing按Y=64旋转。 */
class DioramaProjectionTest {
    @Test void actualProjectionKeepsHeightAndRebuildsOnFacingChange(){
        var old=Projection.facing();
        try{
            var lines=List.of(new Line(Kind.BRIDGE,List.of(new Point(10,90,20),new Point(12,91,20))));
            for(var facing:Facing.values()){
                Projection.setFacing(facing);var drawing=DioramaRoadOverlay.project(lines).getFirst();
                assertEquals(Projection.u(10.5,20.5),drawing.points[0],1e-8);
                assertEquals(Projection.v(10.5,90,20.5),drawing.points[1],1e-8);
                assertNotEquals(Projection.v(10.5,0,20.5),drawing.points[1]);
                assertArrayEquals(drawing.points,drawing.shown());
                assertEquals(facing.turns,drawing.turns);
            }
        }finally{Projection.setFacing(old);}
    }
}
