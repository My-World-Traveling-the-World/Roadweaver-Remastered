package net.shiroha233.roadweaver.bridge;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseRegionBounds;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainRegion;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTile;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileKey;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservationTest;
import net.shiroha233.roadweaver.pathlogic.structures.OwnedStructureBox;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class InitialBandSolveReuseTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");
    private static final BlockPos START = new BlockPos(32, 64, 64);
    private static final BlockPos END = new BlockPos(160, 64, 64);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualBandReusesOnlyUnchangedRoutes() {
        var firstTile = tile();
        var first = region(firstTile, 64);
        // 第二次视图是新对象，瓦片却仍是相同的进程内缓存内容。
        var second = region(firstTile, 64);
        var routes = new InitialBandSolveReuse();
        var structures = new AtomicReference<>(snapshot(Set.of(), LongSet.of(1)));
        var tree = new AtomicReference<BlockPos>();
        var solver = new BandPathfinder((level, a, b, c, d, radius) -> tree.get() == null
                ? HugeTreeReservationTest.mask(a, b, c, d, radius)
                : HugeTreeReservationTest.mask(a, b, c, d, radius, tree.get()), structures::get);
        var config = new PathfindingCostConfig();
        BlockPos farStart = START.offset(0, 0, 80), farEnd = END.offset(0, 0, 80);
        var open = run(routes, false, first, solver, START, END, config);
        var other = run(routes, false, first, solver, farStart, farEnd, config);
        assertTrue(open.success() && other.success());
        assertEquals(START, open.rawPath().getFirst());
        assertEquals(END, open.rawPath().getLast());

        // 第二遍只是补齐原路线的结构扫描覆盖，不应该把已经求好的路线作废。
        structures.set(snapshot(Set.of(), LongSet.of(1, 2)));
        tree.set(new BlockPos(96, 64, 64));
        var reused = run(routes, true, second, solver, farStart, farEnd, config);
        var detour = run(routes, true, second, solver, START, END, config);
        assertEquals(other.rawPath(), reused.rawPath());
        assertTrue(detour.success());
        assertNotEquals(open.rawPath(), detour.rawPath(), "新增巨树必须改变实际走线");
        assertEquals(1, routes.hits());
        assertEquals(1, routes.misses());
    }

    @Test
    void changedPlanningProtectionGridOrTerrainNeverUsesOldSolve() {
        var firstTile = tile();
        var first = region(firstTile, 64);
        var memo = new InitialBandSolveReuse();
        var structures = new AtomicReference<>(snapshot(Set.of(), LongSet.of(1)));
        var solver = new BandPathfinder((level, a, b, c, d, radius) ->
                HugeTreeReservationTest.mask(a, b, c, d, radius), structures::get);
        var config = new PathfindingCostConfig();
        assertTrue(run(memo, false, first, solver, START, END, config).success());
        assertTrue(run(memo, true, first, solver, START, END, config).success());
        // 路由等价判据必须区分新增盒，即使两个批次的扫描范围没有变化。
        structures.set(snapshot(Set.of(new OwnedStructureBox(null,
                new BoundingBox(80, 60, 60, 112, 70, 68), null)), LongSet.of(1)));
        assertTrue(run(memo, true, first, solver, START, END, config).success());
        structures.set(snapshot(Set.of(), LongSet.of(1)));
        assertTrue(run(memo, true, first, solver, START, END, config.withPathGridStep(2)).success());
        assertTrue(run(memo, true, region(tile(), 64), solver, START, END, config).success());
        assertTrue(run(memo, true, region(firstTile, 80), solver, START, END, config).success());
        assertEquals(1, memo.hits());
        assertEquals(4, memo.misses());
    }

    private static PathResult run(InitialBandSolveReuse memo, boolean replay, CoarseTerrainRegion terrain,
                                  BandPathfinder solver, BlockPos start, BlockPos end, PathfindingCostConfig config) {
        return memo.inPass(replay, terrain, terrain.solveTerrain(),
                () -> solver.findPath(start, end, 1, null, null, terrain, config));
    }

    private static PlanningSnapshot snapshot(Set<OwnedStructureBox> boxes, LongSet covered) {
        return new PlanningSnapshot(boxes, Map.of(), Set.of(), Set.of(), Set.of(), covered, 3);
    }

    private static CoarseTerrainTile tile() {
        var key = CoarseTerrainTileKey.forBlock(DIMENSION, 0, 0, 8, 32);
        int n = key.sampleWidth() * key.sampleHeight();
        short[] heights = new short[n];
        Arrays.fill(heights, (short) 64);
        return new CoarseTerrainTile(key, 63, key.sampleWidth(), key.sampleHeight(),
                heights, heights.clone(), new byte[n], new int[n]);
    }

    private static CoarseTerrainRegion region(CoarseTerrainTile tile, int left) {
        // 保留两条路径在直廊内可解；变化后的边界仍覆盖两端。
        return new CoarseTerrainRegion(CoarseRegionBounds.aligned(DIMENSION, left - 64, 0, 248, 248, 8),
                63, Map.of(tile.key(), tile));
    }
}
