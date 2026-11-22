/* 文件职责：在开局的两遍粗规划之间复用输入相同的 BAND 解。 */
package net.shiroha233.roadweaver.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation.PlanningMask;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.BuildingObstacleMode;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainRegion;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** 只跨一次开局交接。没有证明输入相同就执行原求解。 */
public final class InitialBandSolveReuse {
    private static final int MAX_ENTRIES = 2048;
    private static final ThreadLocal<Pass> ACTIVE = new ThreadLocal<>();
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final IdentityHashMap<CoarseTerrainRegion.SolveTerrain, Boolean> terrainMatches = new IdentityHashMap<>();
    private CoarseTerrainRegion.SolveTerrain replayTerrain;

    public <T> T inPass(boolean replay, CoarseTerrainRegion activeTerrain,
                        CoarseTerrainRegion.SolveTerrain terrain, Supplier<T> action) {
        Pass before = ACTIVE.get();
        ACTIVE.set(new Pass(this, replay, activeTerrain, terrain));
        try {
            return action.get();
        } finally {
            if (before == null) ACTIVE.remove();
            else ACTIVE.set(before);
        }
    }

    static Pass current() {
        return ACTIVE.get();
    }

    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }

    record Key(BlockPos start, BlockPos end, BuildingObstacleMode mode) {}
    // 把会改变度量与平滑的实参一并比较；不从第二遍的全局配置反推第一遍输入。
    record Parameters(int width, int clearance, int step, double water, double land,
                      double gradeWeight, double obstacleWeight, double stiffness) {}
    private record Entry(CoarseTerrainRegion.SolveTerrain terrain, Parameters parameters,
                         PlanningSnapshot structures, PlanningMask trees, PathResult result) {}

    record Pass(InitialBandSolveReuse owner, boolean replay, CoarseTerrainRegion activeTerrain,
                CoarseTerrainRegion.SolveTerrain terrain) {
        PathResult solve(Key key, Parameters parameters, PlanningSnapshot structures,
                         PlanningMask trees, Supplier<PathResult> search) {
            // 缺快照可能在下一遍被补齐，不能把它认成已经确认没有保护列。
            if (terrain == null || structures == null || structures == PlanningSnapshot.EMPTY
                    || Thread.currentThread().isInterrupted()) {
                if (replay) owner.misses.increment();
                return search.get();
            }
            if (replay) {
                Entry saved = owner.entries.get(key);
                if (saved != null && owner.sameTerrain(saved.terrain(), terrain)
                        && parameters.equals(saved.parameters())
                        && structures.sameRoutingObstacles(saved.structures())
                        && trees.sameColumns(saved.trees())) {
                    owner.hits.increment();
                    return saved.result();
                }
                owner.misses.increment();
                return search.get();
            }
            // 不缓存失败或中断结果，且限制大范围预热在交接时持有的路径数。
            PathResult result = search.get();
            if (result.success() && result.hasRawPath() && !Thread.currentThread().isInterrupted()) {
                owner.capture(key, new Entry(terrain, parameters, structures, trees,
                        PathResult.raw(List.copyOf(result.rawPath()))));
            }
            return result;
        }
    }

    private synchronized void capture(Key key, Entry entry) {
        if (entries.size() < MAX_ENTRIES || entries.containsKey(key)) entries.put(key, entry);
    }

    private synchronized boolean sameTerrain(CoarseTerrainRegion.SolveTerrain captured,
                                             CoarseTerrainRegion.SolveTerrain current) {
        // 同一批的路线共享同一份瓦片清单，大范围开局不能让每条路重复比较全部瓦片。
        if (replayTerrain != current) {
            terrainMatches.clear();
            replayTerrain = current;
        }
        return terrainMatches.computeIfAbsent(captured, current::equals);
    }
}
