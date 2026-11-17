/* 文件职责：计量完整计划编译和一次归属归一化，不只量辅助方法。 */
package net.shiroha233.roadweaver.worldgen.road;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeStageProbe;
public class CompileStageProbe {
 static volatile Object sink;
 public static void main(String[] args) {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var roads=new ArrayList<RoadData>();for(int i=0;i<8;i++)roads.add(CompilePreparationTest.road(-35,i%4,64+i%2,i+1));
  BridgeStageProbe.compare("compile8roads80stations",()->sink=FrozenRoadChunkPlanCompiler.compile(new ChunkPos(-1,0),roads,false,7,1),()->sink=RoadChunkPlanCompiler.compile(new ChunkPos(-1,0),roads,false,7,1),8);
  BridgeStageProbe.compare("normalize8roads80stations",()->sink=FrozenRoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads),()->sink=RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads),16);
 }
}

