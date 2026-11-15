/* 文件职责：把新三层内核接成旧链的 Pathfinder —— 这是 AB 开关真正生效的地方。 */
package net.shiroha233.roadweaver.bridge;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.shouldAvoidPlanning;
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

    /** 边级采样的结果位：中间点踩到硬障碍（受 BuildingObstacleMode 门控）。 */
    private static final int EDGE_HARD = 1;
    /** 边级采样的结果位：中间点踩到真实建筑占用（不受模式门控）。 */
    private static final int EDGE_SOFT = 2;

    /**
     * 边级采样的判定端口（2026-11-14，审计 N1）。
     *
     * <p>抽成接口是为了让「一条 step 长的边上到底采了哪几个点」这件事
     * 可以脱离 {@code PlanningSnapshot} 单独验证 —— 那套快照要解析结构，
     * 在单测里搭不起来，而采样几何本身才是修复的核心。</p>
     */
    interface EdgeProbe {
        /** 该点是否命中硬障碍（受 BuildingObstacleMode 门控）。 */
        boolean hard(int x, int y, int z);

        /** 该点是否命中真实建筑占用（不受模式门控）。 */
        boolean soft(int x, int y, int z);
    }

    /**
     * 沿一条网格边采 3 个中间点（1/4、1/2、3/4），返回命中的障碍类型位。
     *
     * <p>2026-11-14（审计 N1）：格点采样会漏掉「宽度小于 step − 2·clearance、
     * 整体夹在两个格点之间」的建筑。相邻采样间隔 step/4 = 2 格（step=8），
     * 因此任何 ≥2 格宽的结构都必然被采到。</p>
     */
    static int sampleEdge(int ax, int ay, int az, int bx, int by, int bz, EdgeProbe probe) {
        int hits = 0;
        for (int k = 1; k <= 3; k++) {
            double t = k / 4.0;
            int px = (int) Math.round(ax + (bx - ax) * t);
            int py = (int) Math.round(ay + (by - ay) * t);
            int pz = (int) Math.round(az + (bz - az) * t);
            if (probe.hard(px, py, pz)) {
                return hits | EDGE_HARD;                      // 硬命中已是最强结论，提前退出
            }
            if (probe.soft(px, py, pz)) {
                hits |= EDGE_SOFT;
            }
        }
        return hits;
    }

    /** 生产用的边级判定：走既有避让判据，口径与格点采样完全一致。 */
    private static EdgeProbe avoidanceProbe(int clearance) {
        return new EdgeProbe() {
            @Override
            public boolean hard(int x, int y, int z) {
                return blocksPlanningObstacle(new BlockPos(x, y, z), clearance);
            }

            @Override
            public boolean soft(int x, int y, int z) {
                return shouldAvoidPlanning(new BlockPos(x, y, z), clearance);
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>关于 {@code maxSteps}（2026-11-14，审计 E）</b>：本实现<b>有意不使用</b>它。
     * 旧 A* 拿它限制「扩展节点数」—— 那是防止启发式失效时爆内存的手段。
     * FMM 的开销由<b>网格点数</b>决定（本身就受包围盒约束），不存在「扩展数爆炸」
     * 这个失败模式；硬要按步数截断反而会把一条合法但较长的路判成失败。
     * 参数保留只为满足 {@link Pathfinder} 接口约定（旧链仍在调用方签名里传它）。</p>
     */
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
        // 改道的 ThreadLocal 与快照显式字段都要兼容；空令不启动边检查或优惠查询。
        var ban = config.corridorBan();
        var attract = config.corridorAttract();
        boolean hasBan = ban != null && !ban.isEmpty();
        boolean hasAttract = attract != null && !attract.isEmpty();

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
        //
        // ★ 2026-11-13 修复：生产链全部走 findRawPath ⇒ 接口 width 恒为 1，
        // 若直接信任它则 clearance=0，硬避让半径归零（路直接穿过村庄建筑）。
        // 这里改用 planningRoadRadius() —— 与四个旧寻路器完全同一口径。
        boolean[] blocked = new boolean[columns * rows];
        // 2026-11-14 修复（审计缺陷 A）：硬掩码与软掩码必须分开。
        //   硬掩码 blocked：走 blocksPlanningObstacle，受 BuildingObstacleMode 门控 ——
        //     HARD 模式硬禁穿建筑；PENALTY 模式不硬禁（这是「搜索失败自动降级」的语义）。
        //   软掩码 penalized：走 shouldAvoidPlanning，**不受模式门控** ——
        //     它只查真实快照，任何模式下都返回真实的建筑占用。
        // 旧实现里软势从 blocked 派生，PENALTY 下 blocked 全 false ⇒ 软势也恒 0，
        // 降级重搜等于在完全无障碍的地图上找路（直接穿村穿楼）。
        // 现在 PENALTY 下降级重搜仍能感知建筑：不硬禁，但软势非零、尽量绕开。
        boolean[] penalized = new boolean[columns * rows];
        int clearance = planningRoadRadius();
        for (int iz = 0; iz < rows; iz++) {
            for (int ix = 0; ix < columns; ix++) {
                int index = ix + iz * columns;
                if (outOfTerrain[index]) continue;
                int x = minX + ix * step;
                int z = minZ + iz * step;
                int y = heights[index];
                BlockPos pos = new BlockPos(x, y, z);
                boolean hard = blocksPlanningObstacle(pos, clearance);
                // 改道禁行与建筑硬转软无关；只将建筑放进软势，避免把禁行带再膨胀一层。
                blocked[index] = hard || (hasBan && ban.blocks(x, z));
                penalized[index] = hard || shouldAvoidPlanning(pos, clearance);
            }
        }

        // ②b 边级障碍采样（2026-11-14 修复，审计 N1）
        //
        // 上面的循环只测**格点**。FMM 的一条边跨 step(=8) 格，宽度小于
        // step − 2·clearance 的建筑可以整个夹在两个格点之间而完全不被发现 ——
        // 路会直接穿过去。旧链有 PathfindingHelper.edgeIntersectsPlanningObstacle
        // 做沿边采样，新链漏了这一步。
        //
        // 这里对每个格点的 +x / +z 两条边各采 3 个中间点（1/4、1/2、3/4 处，
        // 相邻采样间隔 2 格 ⇒ 任何 ≥2 格宽的结构都逃不掉），命中则把**两端格点**
        // 一起标记（保守膨胀）：硬命中进 blocked，软命中只进 penalized。
        // 膨胀方向是「宁可多禁一格」，因为一条 8 格长的边只要中间被挡，
        // 这条路就真的过不去 —— 不允许它「从缝里钻」。
        EdgeProbe probe = avoidanceProbe(clearance);
        for (int iz = 0; iz < rows; iz++) {
            for (int ix = 0; ix < columns; ix++) {
                int index = ix + iz * columns;
                if (outOfTerrain[index]) continue;
                int x = minX + ix * step;
                int z = minZ + iz * step;
                int y = heights[index];
                // +x 边
                if (ix + 1 < columns && !outOfTerrain[index + 1]) {
                    int nx = x + step;
                    int nz = z;
                    int ny = heights[index + 1];
                    int hits = sampleEdge(x, y, z, nx, ny, nz, probe);
                    if ((hits & EDGE_HARD) != 0) {
                        blocked[index] = true;
                        blocked[index + 1] = true;
                        penalized[index] = true;
                        penalized[index + 1] = true;
                    } else if ((hits & EDGE_SOFT) != 0) {
                        penalized[index] = true;
                        penalized[index + 1] = true;
                    }
                }
                // +z 边
                if (iz + 1 < rows) {
                    int nIndex = index + columns;
                    if (!outOfTerrain[nIndex]) {
                        int nx = x;
                        int nz = z + step;
                        int ny = heights[nIndex];
                        int hits = sampleEdge(x, y, z, nx, ny, nz, probe);
                        if ((hits & EDGE_HARD) != 0) {
                            blocked[index] = true;
                            blocked[nIndex] = true;
                            penalized[index] = true;
                            penalized[nIndex] = true;
                        } else if ((hits & EDGE_SOFT) != 0) {
                            penalized[index] = true;
                            penalized[nIndex] = true;
                        }
                    }
                }
            }
        }

        ObstacleField obstacles = new StridedObstacleField(
                minX, minZ, columns, rows, step, blocked, penalized, outOfTerrain);

        // ③ 新内核：度量 → 场求解 → 回溯 → 曲率平滑
        FinslerMetric metric = new FinslerMetric(
                terrainField, obstacles,
                FinslerMetric.DEFAULT_GRADE_WEIGHT,
                FinslerMetric.DEFAULT_OBSTACLE_WEIGHT,
                hasAttract ? attract::factorAt : null, hasBan ? ban::intersectsSegment : null);

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
                new RoadNode("end", end.getX(), end.getZ()),
                // 端点短边可能落在粗障碍格之间，用原地形和实际保护列复检。
                (x, z) -> !terrain.contains(x, z) || probe.hard(x, terrain.height(x, z), z));
        if (!solution.reachable()) {
            return PathResult.failure();
        }

        // 接入真实端点的短边已随曲线验证；本层只恢复地形高度，不修改坐标。
        List<BlockPos> path = new ArrayList<>(solution.points().size());
        for (int[] point : solution.points()) {
            path.add(new BlockPos(point[0], terrain.height(point[0], point[1]), point[1]));
        }
        return PathResult.raw(path);
    }

    /**
     * 带步长的障碍场：软势来自距离变换，硬挡来自避让判据。
     *
     * <p>{@code ObstaclePotential} 只认 1:1 网格，所以这里先按<b>网格坐标</b>
     * 建势场，再把方块坐标吸附进来 —— 与 {@link StridedTerrainField} 的吸附口径一致。</p>
     *
     * <p><b>2026-11-14（审计缺陷 A）</b>：软势从 {@code penalized}（真实障碍判定，
     * 不受模式门控）派生，而不是从 {@code blocked}（受 BuildingObstacleMode 门控）
     * 派生 —— 否则 PENALTY 降级重搜时软势恒 0，等于放弃避障。</p>
     */
    private record StridedObstacleField(int minX, int minZ, int columns, int rows, int step,
                                        boolean[] blocked, boolean[] penalized,
                                        boolean[] outOfTerrain,
                                        ObstaclePotential soft) implements ObstacleField {

        StridedObstacleField(int minX, int minZ, int columns, int rows, int step,
                             boolean[] blocked, boolean[] penalized, boolean[] outOfTerrain) {
            this(minX, minZ, columns, rows, step, blocked, penalized, outOfTerrain,
                    ObstaclePotential.fromMask(0, 0, columns, rows, penalized, 3.0));
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
