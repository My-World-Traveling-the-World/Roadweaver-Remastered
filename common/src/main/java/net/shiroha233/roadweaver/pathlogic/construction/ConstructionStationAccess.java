/* 文件职责：单站编排的世界操作边界；测试替换操作实现，不复制桥隧和拒绝分支。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.decoration.system.DecorationPlanner;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlanner;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.util.Line;
import java.util.*;


/** 不包含台账和租约；这些必须在所有站及装饰结案后由外层事务处理。 */
public interface ConstructionStationAccess {
    int seaLevel();
    int height(Heightmap.Types type, int x, int z);
    void buoy(BlockPos middle, int sea, RandomSource random, ModConfig cfg);
    boolean templateBridge(ChunkPos chunk, RoadData data, Line line, RoadSegmentPlacement seg, BlockPos middle, BlockPos prev, int deck, boolean landing);
    boolean proceduralBridge(RoadData data, RoadSegmentPlacement seg, BlockPos middle, BlockPos prev, BlockPos next, int width, int actual, int deck, int index, RandomSource random, ModConfig cfg, List<int[]> ranges, int[] profile, int station, BridgeSegmentPlanner.Context context, Line line);
    void tunnel(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, boolean junction, java.util.function.Consumer<net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPlan> consumer);
    int terrain(int x, int z);
    SegmentPaver.SegmentGrade grade(RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, boolean tunnel, boolean crossing);
    boolean pave(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, SegmentPaver.SegmentGrade grade, int type, List<BlockState> materials, List<BlockState> slabs, RandomSource random, ModConfig cfg, Map<Integer,String> refusals);
    boolean pave(RoadData data, RoadSegmentPlacement seg, int index, List<BlockPos> centers, int[] profile, SegmentPaver.SegmentGrade grade, int type, List<BlockState> materials, List<BlockState> slabs, RandomSource random, ModConfig cfg, Map<Integer,String> refusals, boolean portal);
    void apply(net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPlan plan);
    void bankSign(Set<Decoration> decorations, BlockPos averaged, BlockPos next, BlockPos prev, int width, boolean[] skipped, int index);
    void decorate(Set<Decoration> decorations, BlockPos averaged, int index, BlockPos next, BlockPos prev, List<BlockPos> centers, int width, RandomSource random, boolean intervalStation, int interval, DecorationPlanner.Mode mode);
}
