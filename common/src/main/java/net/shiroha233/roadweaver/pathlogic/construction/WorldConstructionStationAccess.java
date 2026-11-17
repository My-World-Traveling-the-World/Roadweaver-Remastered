/* 文件职责：把单站端口逐项转发到原世界操作，不合并读请求也不提前消费随机。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.decoration.system.DecorationPlanner;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlanner;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlannerNew;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.pathlogic.bridge.BuoyBuilder;
import net.shiroha233.roadweaver.decoration.system.SkippedBridgeBankSignPlanner;
import net.shiroha233.roadweaver.util.Line;
import java.util.*;


/** 当前仅同步使用，不能把持有的世界引用当作跨tick加载或代次许可。 */
public record WorldConstructionStationAccess(WorldGenLevel world, ServerLevel server) implements ConstructionStationAccess {
    @Override public int seaLevel() { return server.getSeaLevel(); }
    @Override public int height(Heightmap.Types type, int x, int z) { return world.getHeight(type,x,z); }
    @Override public void buoy(BlockPos middle, int sea, RandomSource random, ModConfig cfg) { BuoyBuilder.placeBuoy(world,middle,sea,random,cfg); }
    @Override public boolean templateBridge(ChunkPos chunk, RoadData data, Line line, RoadSegmentPlacement seg, BlockPos middle, BlockPos prev, int deck, boolean landing) { return BridgeSegmentPlannerNew.processSegment(world,chunk,data,line,seg,middle,prev,deck,landing); }
    @Override public boolean proceduralBridge(RoadData data, RoadSegmentPlacement seg, BlockPos middle, BlockPos prev, BlockPos next, int width, int actual, int deck, int index, RandomSource random, ModConfig cfg, List<int[]> ranges, int[] profile, int station, BridgeSegmentPlanner.Context context, Line line) { return BridgeSegmentPlanner.processSegment(world,data,seg,middle,prev,next,width,actual,deck,index,random,cfg,ranges,profile,station,context,line); }
    @Override public void tunnel(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, boolean junction, java.util.function.Consumer<net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPlan> consumer) { RoadTunnelBuilder.processSegment(world,data,seg,index,centers,profile,junction,consumer); }
    @Override public int terrain(int x, int z) { return RoadConstructionSafety.terrainHeight(world,x,z); }
    @Override public SegmentPaver.SegmentGrade grade(RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, boolean tunnel, boolean crossing) { return SegmentPaver.planGrade(world,seg,index,centers,profile,tunnel,crossing); }
    @Override public boolean pave(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, SegmentPaver.SegmentGrade grade, int type, List<BlockState> materials, List<BlockState> slabs, RandomSource random, ModConfig cfg, Map<Integer,String> refusals) { return SegmentPaver.paveSegment(world,data,seg,index,centers,profile,grade,type,materials,slabs,random,cfg,refusals); }
    @Override public boolean pave(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, SegmentPaver.SegmentGrade grade, int type, List<BlockState> materials, List<BlockState> slabs, RandomSource random, ModConfig cfg, Map<Integer,String> refusals, boolean portal) { return SegmentPaver.paveSegment(world,data,seg,index,centers,profile,grade,type,materials,slabs,random,cfg,refusals,portal); }
    @Override public void apply(net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPlan plan) { RoadTunnelBuilder.apply(world,plan); }
    @Override public void bankSign(Set<Decoration> decorations, BlockPos averaged, BlockPos next, BlockPos prev, int width, boolean[] skipped, int index) { SkippedBridgeBankSignPlanner.addIfSkippedBridgeBank(world,decorations,averaged,next,prev,width,skipped,index); }
    @Override public void decorate(Set<Decoration> decorations, BlockPos averaged, int index, BlockPos next, BlockPos prev, List<BlockPos> centers, int width, RandomSource random, boolean intervalStation, int interval, DecorationPlanner.Mode mode) { DecorationPlanner.addDecoration(world,decorations,averaged,index,next,prev,centers,width,random,intervalStation,interval,mode); }
}
