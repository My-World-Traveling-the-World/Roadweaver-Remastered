/* 文件职责：把新三层内核接成旧链的 Pathfinder —— 这是 AB 开关真正生效的地方。 */
package net.shiroha233.roadweaver.bridge;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.ObstaclePotential;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;

import net.shiroha233.roadweaver.pipeline.ab.BandRouteSolver;
import net.shiroha233.roadweaver.pipeline.ab.RouteSolver;
import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * 新架构的寻路器实现 —— 旧链 {@link Pathfinder} 策略接口的第 5 个实现。
 *
 * <h2>为什么把它放在 bridge 层</h2>
 * <p>它必须<b>同时认识两边</b>：旧的 {@link Pathfinder} 契约与 {@link PathTerrainField}，
 * 以及新的 {@link FinslerMetric} / {@link FastMarchingSolver} / {@link ElasticBandSolver}。
 * 这正是 bridge 层的定义。旧链只通过 {@code PathfinderFactory} 拿到它，
 * 对求解层的内部结构一无所知。</p>
 *
 * <h2>三个必须说清的实现决定</h2>
 * <ol>
 *   <li><b>用 {@link PathTerrainField} 而不是 {@code AccurateHeightSampler}。</b>
 *       旧链已经把地形预热成量化场了（带缓存、带水体标记），重建一套采样会
 *       把预热成本再付一次，而且两边看到的地形可能不一致。</li>
 *   <li><b>步长取 {@code effectiveAStarStep()}。</b>这不是新引入的妥协 ——
 *       旧 A* 本来就是 8 格粗网格。区别只在于：以前步长既是搜索粒度又是几何粒度，
 *       现在它<b>只是搜索粒度</b>，几何由弹性带在方块坐标系里做。</li>
 *   <li><b>避让判据照旧走 {@code StructureAvoidanceService}。</b>
 *       调用方（旧链）已经在 {@code activatePlanningSnapshot} 作用域内，
 *       本类直接复用那个 ThreadLocal 快照 —— <b>判据一字不改</b>。</li>
 * </ol>
 *
 * <h2>已知边界（第一版）</h2>
 * <ul>
 *   <li>不做纵断面：本类只出中心线，高度交给旧链的 {@code RoadGradePlanner}
 *       （它是被保留的模块，不在重构范围内）。</li>
 *   <li>不做桥隧判定：{@code PathSpanExtractor} 同样保留，吃本类输出的站序列。</li>
 * </ul>
 *
 * @see net.shiroha233.roadweaver.metric.FinslerMetric
 * @see net.shiroha233.roadweaver.pipeline.ab.SolverMode
 */
public final class BandPathfinder implements Pathfinder {

    /** 包围盒在起终点外侧额外留的余量（格）—— 给绕行留空间。 */
    private static final int BOUNDS_PADDING = 64;

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, int width,
                               ServerLevel level, int maxSteps,
                               TerrainSamplingCache cache, PathTerrainField terrain,
                               PathfindingCostConfig config) {
        if (start == null || end == null || terrain == null || config == null) {
            return PathResult.failure();
        }

        int step = Math.max(1, config.effectiveAStarStep());
        int padding = BOUNDS_PADDING;

        int minX = Math.min(start.getX(), end.getX()) - padding;
        int maxX = Math.max(start.getX(), end.getX()) + padding;
        int minZ = Math.min(start.getZ(), end.getZ()) - padding;
        int maxZ = Math.max(start.getZ(), end.getZ()) + padding;

        int blockWidth = maxX - minX + 1;
        int blockDepth = maxZ - minZ + 1;
        int columns = (blockWidth - 1) / step + 1;
        int rows = (blockDepth - 1) / step + 1;

        // ① 采地形（越界列用 0，并被下面标成不可通行）
        int[] heights = new int[columns * rows];
        boolean[] outOfTerrain = new boolean[columns * rows];
        for (int iz = 0; iz < rows; iz++) {
            for (int ix = 0; ix < columns; ix++) {
                int x = minX + ix * step;
                int z = minZ + iz * step;
                if (terrain.contains(x, z)) {
                    heights[ix + iz * columns] = terrain.height(x, z);
                } else {
                    outOfTerrain[ix + iz * columns] = true;
                }
            }
        }
        TerrainField terrainField = StridedTerrainField.of(
                minX, minZ, columns, rows, step, heights);

        // ② 采避让（判据一字不改；调用方已在 PlanningScope 内）
        boolean[] blocked = new boolean[columns * rows];
        int clearance = Math.max(0, width / 2);
        for (int iz = 0; iz < rows; iz++) {
            for (int ix = 0; ix < columns; ix++) {
                int index = ix + iz * columns;
                if (outOfTerrain[index]) continue;
                int x = minX + ix * step;
                int z = minZ + iz * step;
                int y = heights[index];
                blocked[index] = blocksPlanningObstacle(
                        new BlockPos(x, y, z), clearance);
            }
        }
        ObstacleField obstacles = new StridedObstacleField(
                minX, minZ, columns, rows, step, blocked, outOfTerrain);

        // ③ 新内核：度量 → 场求解 → 回溯 → 曲率平滑
        FinslerMetric metric = new FinslerMetric(
                terrainField, obstacles,
                FinslerMetric.DEFAULT_GRADE_WEIGHT,
                FinslerMetric.DEFAULT_OBSTACLE_WEIGHT);

        // 委托给 BandRouteSolver —— 它是「单条路线」的唯一实现。
        //
        // 2026-11-13：这里原本自己写了一遍「场求解 → 回溯 → 弹性带平滑」，
        // 与 BandRouteSolver.solve 是两份独立实现。危险在于它们服务于不同目的：
        //   · 本类（生产路径）决定真机上的路画成什么样；
        //   · BandRouteSolver（AB 对比）决定「新链比旧链好还是差」的结论。
        // 两份实现一旦漂移，AB 就会对着一个真实生产里并不存在的算法做评价 ——
        // 改了一边忘了另一边，报告仍然『绿』，但结论已经与真机无关。
        // 用同一个类，这个隐患从根上没有了。
        BandRouteSolver routeSolver = new BandRouteSolver(metric, ElasticBandSolver.defaults());
        RouteSolver.RouteSolution solution = routeSolver.solve(
                new RoadNode("start", start.getX(), start.getZ()),
                new RoadNode("end", end.getX(), end.getZ()));
        if (!solution.reachable()) {
            return PathResult.failure();
        }

        // ④ 端点必须精确落回起终点（场求解会按步长取整）
        List<BlockPos> path = new ArrayList<>(solution.points().size() + 2);
        for (int[] point : solution.points()) {
            path.add(new BlockPos(point[0], terrain.height(point[0], point[1]), point[1]));
        }
        if (path.isEmpty()) return PathResult.failure();

        int startY = terrain.contains(start.getX(), start.getZ())
                ? terrain.height(start.getX(), start.getZ()) : start.getY();
        int endY = terrain.contains(end.getX(), end.getZ())
                ? terrain.height(end.getX(), end.getZ()) : end.getY();
        path.set(0, new BlockPos(start.getX(), startY, start.getZ()));
        path.set(path.size() - 1, new BlockPos(end.getX(), endY, end.getZ()));

        return PathResult.raw(path);
    }

    /**
     * 带步长的障碍场：软势来自距离变换，硬挡来自避让判据。
     *
     * <p>{@code ObstaclePotential} 只认 1:1 网格，所以这里先按<b>网格坐标</b>
     * 建势场，再把方块坐标吸附进来 —— 与 {@link StridedTerrainField} 的吸附口径一致。</p>
     */
    private record StridedObstacleField(int minX, int minZ, int columns, int rows, int step,
                                        boolean[] blocked, boolean[] outOfTerrain,
                                        ObstaclePotential soft) implements ObstacleField {

        StridedObstacleField(int minX, int minZ, int columns, int rows, int step,
                             boolean[] blocked, boolean[] outOfTerrain) {
            this(minX, minZ, columns, rows, step, blocked, outOfTerrain,
                    ObstaclePotential.fromMask(0, 0, columns, rows, blocked, 3.0));
        }

        @Override
        public double potential(int x, int z) {
            int ix = snapX(x);
            int iz = snapZ(z);
            // 网格坐标直接喂给 1:1 的势场
            return soft.potential(ix, iz);
        }

        @Override
        public boolean impassable(int x, int z) {
            int ix = snapX(x);
            int iz = snapZ(z);
            int index = ix + iz * columns;
            return outOfTerrain[index] || blocked[index];
        }

        private int snapX(int x) {
            int ix = Math.round((x - minX) / (float) step);
            return Math.max(0, Math.min(columns - 1, ix));
        }

        private int snapZ(int z) {
            int iz = Math.round((z - minZ) / (float) step);
            return Math.max(0, Math.min(rows - 1, iz));
        }
    }
}
