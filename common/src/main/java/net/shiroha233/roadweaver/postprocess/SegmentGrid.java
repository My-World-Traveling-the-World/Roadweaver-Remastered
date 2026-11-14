/* 文件职责：路面段的网格索引 —— 把「附近有哪些段」的暴力查找换成网格查表。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 仍留在 RoadSnapService 里的常量与工具（包内可见）。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;

/**
 * 路面段的网格索引。
 *
 * <p>吸附与合流判定要反复问「我附近有哪些既有路面段」。线性扫描在长路网下是
 * O(段数) 每次；本类把段按固定网格分桶，查询只取覆盖范围内的桶。</p>
 *
 * <p>纯几何结构，不改任何判定结果 —— 从 3710 行的 {@code RoadSnapService}
 * 里纯搬迁出来，逻辑与判据一字未改。</p>
 */
class SegmentGrid {
    private final Map<Long, List<IndexedPos>> cells = new HashMap<>();

    void add(long key, int segmentIndex, BlockPos pos) {
        cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new IndexedPos(segmentIndex, pos));
    }

    NearestResult findNearest(BlockPos target) {
        int gridX = target.getX() >> GRID_SHIFT;
        int gridZ = target.getZ() >> GRID_SHIFT;
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -GRID_SEARCH_RADIUS; dx <= GRID_SEARCH_RADIUS; dx++) {
            for (int dz = -GRID_SEARCH_RADIUS; dz <= GRID_SEARCH_RADIUS; dz++) {
                List<IndexedPos> cell = cells.get(gridKey(gridX + dx, gridZ + dz));
                if (cell == null) continue;
                for (IndexedPos indexed : cell) {
                    long distance = dist2XZ(target, indexed.pos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = indexed.index();
                    }
                }
            }
        }
        return bestIndex >= 0 ? new NearestResult(bestIndex, bestDistance) : null;
    }

    private record IndexedPos(int index, BlockPos pos) {
    }
}
