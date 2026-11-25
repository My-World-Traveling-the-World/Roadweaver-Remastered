/* 文件职责：把当前缓存道路转为只读远景几何，不启动采样或施工。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi;

/** 交付已缓存路面的几何预览；桥模板与装饰仍由真实施工接收覆盖，不冒充最终方块结果。 */
public final class RoadPreviewProvider {
    private static final net.shiroha233.roadweaver.util.WeakIdentityCache<RoadChunkPlan,WorldgenPreviewApi.Chunk> PROJECTIONS=
            new net.shiroha233.roadweaver.util.WeakIdentityCache<>(128);
    private static final net.shiroha233.roadweaver.util.WeakIdentityCache<net.shiroha233.roadweaver.persistence.chunk.RoadSnapshot,SnapshotView> SNAPSHOTS=
            new net.shiroha233.roadweaver.util.WeakIdentityCache<>(4);
    private static final class SnapshotView {
        final RoadChunkIntersection.GeometryCache geometry=new RoadChunkIntersection.GeometryCache();
        final java.util.LinkedHashMap<Long,WorldgenPreviewApi.Chunk> chunks=new java.util.LinkedHashMap<>(32,.75f,true);
    }
    private RoadPreviewProvider() {}
    public static void register(){WorldgenPreviewApi.register("roads",RoadPreviewProvider::peek);}
    private static WorldgenPreviewApi.Chunk peek(Object world,int x,int z) {
        if(!(world instanceof ServerLevel level))return WorldgenPreviewApi.Chunk.unavailable();
        var plan=RoadWorldgenPlanCache.peek(level,new ChunkPos(x,z),ConfigService.get());
        if(plan!=null){var ready=PROJECTIONS.get(plan);if(ready==null){ready=convert(plan);PROJECTIONS.put(plan,ready);}return ready;}
        var snapshot=net.shiroha233.roadweaver.persistence.files.RoadFileStorage.peekSnapshot(level);
        if(snapshot==null)return WorldgenPreviewApi.Chunk.unavailable();
        // 未加载区块也能预览已规划道路；只从内存倒排索引编译几何，不精采、不加载区块。
        var chunk=new ChunkPos(x,z);
        SnapshotView view;
        synchronized(SNAPSHOTS){view=SNAPSHOTS.get(snapshot);if(view==null){view=new SnapshotView();SNAPSHOTS.put(snapshot,view);}}
        synchronized(view){var ready=view.chunks.get(chunk.toLong());if(ready!=null)return ready;}
        var roads=snapshot.queryChunk(chunk.toLong());
        var ready=convert(RoadChunkPlanCompiler.compile(chunk,roads,true,4,System.identityHashCode(snapshot),view.geometry));
        synchronized(view){view.chunks.put(chunk.toLong(),ready);while(view.chunks.size()>128)view.chunks.remove(view.chunks.keySet().iterator().next());}
        return ready;
    }
    static WorldgenPreviewApi.Chunk convert(RoadChunkPlan plan) {
        if(!plan.isAvailable())return WorldgenPreviewApi.Chunk.unavailable();
        var runs=new ArrayList<WorldgenPreviewApi.Run>();
        for(var slice:plan.slices()) {
            var road=slice.road();var stations=road.roadSegmentList();
            var masks=net.shiroha233.roadweaver.pathlogic.construction.ConstructionSpanMasks.of(road,0);
            String material=BlockStateParser.serialize(road.materials().isEmpty()?Blocks.STONE_BRICKS.defaultBlockState():road.materials().getFirst());
            for(int offset=0;offset<slice.segmentCount();offset++) {
                int index=slice.segmentIndexAt(offset);
                if(masks.protectedGapAt(index)||masks.networkReuseAt(index))continue;
                int y=index<road.targetY().size()?road.targetY().get(index):stations.get(index).middlePos().getY();
                for(var pos:stations.get(index).positions()) {
                    if((pos.getX()>>4)!=plan.chunkPos().x||(pos.getZ()>>4)!=plan.chunkPos().z)continue;
                    // targetY是可站立顶面，正常整砖实际写在下一格；预览不能整条高一格。
                    runs.add(new WorldgenPreviewApi.Run(pos.getX()&15,pos.getZ()&15,y-1,y,material));
                }
            }
        }
        return new WorldgenPreviewApi.Chunk(runs.isEmpty()?WorldgenPreviewApi.State.EMPTY:WorldgenPreviewApi.State.READY,
                WorldgenPreviewApi.Fidelity.GEOMETRY,Long.toString(plan.revision()),runs);
    }
}
