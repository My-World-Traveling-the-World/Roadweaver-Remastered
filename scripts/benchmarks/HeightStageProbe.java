/* 文件职责：量高度窗口去装箱，Fake的确定性高度不当真实世界读块成本。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeStageProbe;
public class HeightStageProbe {
 static volatile Object sink;
 public static void main(String[] args){
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var world=(WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(p,m,a)->{if(m.getName().equals("getHeight"))return 64+Math.floorMod((int)a[1],7);throw new AssertionError();});
  for(int n:new int[]{32,8192}) {var points=new ArrayList<BlockPos>();for(int i=0;i<n;i++)points.add(new BlockPos(i,64,0));
   BridgeStageProbe.compare("height"+n,()->sink=FrozenHeightProfileService.build(world,points,new ChunkPos(0,0),4,true,1,List.of()),()->sink=HeightProfileService.build(world,points,new ChunkPos(0,0),4,true,1,List.of()),64);}
 }
}

