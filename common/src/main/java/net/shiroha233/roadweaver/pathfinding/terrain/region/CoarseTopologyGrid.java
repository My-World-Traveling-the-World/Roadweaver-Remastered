/* 文件职责：把缓存的粗地形接入网络测地选边。 */
package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

/** 网络权重需要坡度和水域近似；实际桥位和施工高度由后续走廊确认。 */
public final class CoarseTopologyGrid {
    private CoarseTopologyGrid() {}

    public static AccurateHeightGrid sample(ServerLevel level, AccurateHeightGridRequest request) {
        // 使用正式粗规划的瓦片键和缓存；大域仍只按求解器访问的页申请。
        int last = request.sampleCount() - 1;
        var region = CoarseTerrainRegionSampler.sample(
                level, request.minX(), request.minZ(), request.blockX(last), request.blockZ(last), request.step());
        try {
            return project(request, region);
        } finally {
            region.dispose();
        }
    }

    public static AccurateHeightGrid project(AccurateHeightGridRequest request, PathTerrainField terrain) {
        int count = request.sampleCount();
        var walking = new int[count];
        var floor = new int[count];
        for (int i = 0; i < count; i++) {
            if (i % request.width() == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("已取消拓扑粗地形投影");
            }
            int x = request.blockX(i), z = request.blockZ(i);
            if (!terrain.contains(x, z)) throw new IllegalArgumentException("拓扑粗地形缺列：" + x + "," + z);
            var sample = terrain.sampleBundle(x, z);
            // 水面取粗水深推算的高度，避免把海底起伏当作海面坡度；群系名本身不当作湿列。
            int depth = sample.columnWater() ? sample.waterDepth() : 0;
            walking[i] = depth > 0 ? sample.oceanFloor() + depth : sample.height();
            floor[i] = depth > 0 ? sample.oceanFloor() : walking[i];
        }
        // 这里只借用网格载体和索引；这些代理值不能当成施工精采样存档。
        return new AccurateHeightGrid(request, walking, floor, walking);
    }
}
