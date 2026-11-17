/* 文件职责：用随读取变化的Fake对拍高度窗口，保证去装箱不缓存动态世界结果。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HeightProfileWindowTest {
    static WorldGenLevel world(List<String> events) {
        return (WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(p,m,a)->{
            if(m.getName().equals("getHeight")){events.add(a[1]+","+a[2]);return 40+events.size()%31;}throw new AssertionError(m.getName());});
    }
    @Test void dynamicHeightReadsRoundingAndBothSlopePassesMatchFrozen() {
        int readCount=0;
        for(int radius:new int[]{-1,0,1,4,20})for(int step:new int[]{-1,0,1,3,8,20})for(boolean slope:new boolean[]{false,true}) {
            var points=new ArrayList<BlockPos>();for(int i=0;i<50;i++)points.add(new BlockPos(-25+i,70+i%7,-17));
            var a=new ArrayList<String>();var b=new ArrayList<String>();var chunk=new ChunkPos(-1,-2);
            var expected=FrozenHeightProfileService.build(world(a),points,chunk,radius,slope,step,List.of());
            var actual=HeightProfileService.build(world(b),points,chunk,radius,slope,step,List.of());
            assertEquals(a,b);assertArrayEquals(expected.smoothedY(),actual.smoothedY());assertEquals(expected.usePersisted(),actual.usePersisted());readCount+=b.size();
        }
        assertTrue(readCount>1000);
    }
    @Test void persistedHeightNeverReadsWorldAndMissingHeightReturnsIndependentArrays() {
        var p=List.of(new BlockPos(0,64,0),new BlockPos(1,65,0));var events=new ArrayList<String>();var world=world(events);
        var saved=HeightProfileService.build(world,p,new ChunkPos(0,0),4,true,1,List.of(90,91));
        assertTrue(saved.usePersisted());assertNull(saved.smoothedY());assertEquals(0,events.size());
        var a=HeightProfileService.build(world,p,new ChunkPos(0,0),4,true,1,List.of());
        var b=HeightProfileService.build(world,p,new ChunkPos(0,0),4,true,1,List.of());assertNotSame(a.smoothedY(),b.smoothedY());assertTrue(events.size()>0);
    }
}

