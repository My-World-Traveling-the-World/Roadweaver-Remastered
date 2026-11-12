/* 文件职责：实现基于地形场读取的势场普通道路寻路。 */
package net.shiroha233.roadweaver.pathfinding.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.*;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.*;
import static net.shiroha233.roadweaver.pathfinding.impl.TerrainGradientHelper.*;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;

/**
 * 势场法 + 梯度流寻路器
 */
public final class PotentialFieldPathfinder implements Pathfinder {

    private static final int SEARCH_BUFFER = 128;
    /**
     * 0.9.062（②）：{@code [GRADE-PENALTY]} 只打一次。{@code sanitize()} 会被反复调用
     * （每次 {@code withAStarStep} 都建一份快照），这里用一次 CAS 把它压成一行。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean PENALTY_BASIS_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, int width,
                               ServerLevel level, int maxSteps,
                               TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig cfg) {
        List<BlockPos> rawPath = searchRawPath(start, end, level, maxSteps, terrain, cfg);
        if (rawPath == null) return PathResult.failure();
        if (rawPath.isEmpty()) return PathResult.success(Collections.emptyList());
        List<RoadSegmentPlacement> segments = reconstructPath(rawPath, width, level, cache, false);
        return segments != null ? PathResult.success(segments) : PathResult.failure();
    }

    @Override
    public PathResult findRawPath(BlockPos start, BlockPos end,
                                  ServerLevel level, int maxSteps,
                                  TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig cfg) {
        List<BlockPos> rawPath = searchRawPath(start, end, level, maxSteps, terrain, cfg);
        if (rawPath == null) return PathResult.failure();
        if (rawPath.isEmpty()) return PathResult.success(Collections.emptyList());
        return PathResult.raw(rawPath);
    }

    private List<BlockPos> searchRawPath(BlockPos start, BlockPos end,
                                         ServerLevel level, int maxSteps,
                                         PathTerrainField terrain, PathfindingCostConfig cfg) {
        if (start.equals(end)) return Collections.emptyList();

        int d = cfg.effectiveAStarStep();
        int[][] offsets = {
                {d, 0}, {-d, 0}, {0, d}, {0, -d},
                {d, d}, {d, -d}, {-d, d}, {-d, -d}
        };

        int manhattan = manhattan2d(start, end);
        int dynamicBuffer = Math.min(1024, Math.max(SEARCH_BUFFER, manhattan / 3));
        int minX = Math.min(start.getX(), end.getX()) - dynamicBuffer;
        int maxX = Math.max(start.getX(), end.getX()) + dynamicBuffer;
        int minZ = Math.min(start.getZ(), end.getZ()) - dynamicBuffer;
        int maxZ = Math.max(start.getZ(), end.getZ()) + dynamicBuffer;
        // 直线弦（起终点 XZ 投影）：供偏差代价与直线走廊回避使用（0.8.418）。
        Chord chord = Chord.of(start, end);

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(start, null, 0.0, heuristic(start, end, cfg));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int stepsBudget = Math.max(5000, maxSteps * RoadConstants.POTENTIAL_FIELD_STEPS_MULTIPLIER);
        ThrottleHelper.resetThrottle();
        try {
            while (stepsBudget > 0) {
                if (Thread.currentThread().isInterrupted()) return null;

                Node current = pollActiveOpenNode(openSet, allNodes, closed);
                if (current == null) break;

                stepsBudget--;
                ThrottleHelper.throttle();
                if (Thread.currentThread().isInterrupted()) return null;

                if (manhattan2d(current.pos, end) < (int) (d * RoadConstants.POTENTIAL_FIELD_SUCCESS_DISTANCE_FACTOR)) {
                    return reconstructRawPath(current);
                }

                closed.add(current.pos);
                allNodes.remove(current.pos);

                double goalDx = end.getX() - current.pos.getX();
                double goalDz = end.getZ() - current.pos.getZ();
                double[] grad = terrainGradient(terrain, current.pos.getX(), current.pos.getZ(), d);
                double gradMag = gradientMagnitude(grad[0], grad[1]);
                double[] contour = contourDirection(grad[0], grad[1], goalDx, goalDz);

                for (int[] off : offsets) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    BlockPos nxz = current.pos.offset(off[0], 0, off[1]);
                    if (nxz.getX() < minX || nxz.getX() > maxX || nxz.getZ() < minZ || nxz.getZ() > maxZ) continue;
                    if (!terrain.contains(nxz.getX(), nxz.getZ())) continue;
                    // 0.9.018：禁行走廊（D2 平行双桥「方案 C」改道）。禁令为 null 时
                    // 只是一次空指针比较，代价与行为都等于改道之前。
                    // 放在采样高度之前：被禁的格不必再花一次列采样。
                    if (cfg.corridorBan() != null
                            && cfg.corridorBan().blocks(nxz.getX(), nxz.getZ())) continue;

                    int y = heightSampler(terrain, nxz.getX(), nxz.getZ());
                    BlockPos np = new BlockPos(nxz.getX(), y, nxz.getZ());
                    // 0.9.020：硬障碍口径统一。原来 blocksPlanningObstacle 只被
                    // BasicAStarPathfinder 读，而生产跑的是势场法 ⇒「硬拦已确认建筑 /
                    // 村庄群盒」这一档**从未生效过**，路可以穿过建筑、只付一点软罚；
                    // 且 RawPathSearch 的「硬拦失败→降级重搜」两遍结果必然相同，
                    // 等于每条失败路线白跑一整轮 A*。
                    if (StructureAvoidanceService.buildingObstacleMode()
                            != StructureAvoidanceService.BuildingObstacleMode.PENALTY
                            && StructureAvoidanceService.blocksPlanningObstacle(
                            np, StructureAvoidanceService.planningRoadRadius())) continue;
                    if (HugeTreeReservation.isReserved(level, np)
                            || StructureAvoidanceService.shouldAvoidPlanning(
                            np, StructureAvoidanceService.planningRoadRadius())
                            || edgeIntersectsPlanningObstacle(
                            current.pos, np, StructureAvoidanceService.planningRoadRadius(), d)) continue;
                    if (closed.contains(np)) continue;

                    double moveCost = computeMoveCost(
                            current.pos, np, nxz, off, d,
                            grad, gradMag, contour,
                            terrain, cfg, chord)
                            + headingCost(current, off, d, cfg);
                    // 0.9.056：规划期走廊吸引（真机 (236,-170) 平行双桥）。
                    // 只乘**这一步的步进代价**（moveCost），启发项 h 在下面单独算、
                    // 绝不参与乘法 —— 乘了 h 就不再是 A* 的代价、admissibility 也破了。
                    // 命中/未命中由 factorAt 决定：未命中严格 1.0，下面的 != 直接跳过，
                    // 打开吸引之前的行为逐位不变。吸引器为 null 时只是一次空判断。
                    CorridorAttract attract = cfg.corridorAttract();
                    if (attract != null) {
                        double attractFactor = attract.factorAt(nxz.getX(), nxz.getZ());
                        if (attractFactor != 1.0D) {
                            moveCost *= attractFactor;
                        }
                    }
                    // 超出偏差硬上限的步返回 +∞（拒绝该步），不进入开集。
                    if (!Double.isFinite(moveCost)) continue;

                    double tentativeG = current.g + moveCost;
                    Node existing = allNodes.get(np);
                    if (existing != null && tentativeG >= existing.g) continue;

                    double h = heuristic(np, end, cfg);
                    double f = tentativeG + h;
                    Node next = new Node(np, current, tentativeG, f);
                    allNodes.put(np, next);
                    openSet.add(next);
                }
            }
            return null;
        } finally {
            openSet.clear();
            allNodes.clear();
            closed.clear();
            ThrottleHelper.clearThrottle();
        }
    }

    /**
     * 坡度罚（0.9.028 / H-01 定性，0.9.062 / H-01 落点修正）。
     *
     * <p><b>两种计费基准。</b>{@code slopePenaltyByLength=false} 是 0.9.061 及以前的
     * 「按步收」：罚额按 {@code grade = |ΔY| / 步长} 的比率一次性结账。于是同样爬 1 格，
     * 正步与斜步的罚额差一个数量级（默认参数下 7837.5 vs 62.9 —— 斜步的 grade 掉到
     * hard 阈值 0.10 以下，30000 那一档根本不触发），而一个**步骤**的 baseCost 只有
     * 1.0 / 1.414。也就是说 baseCost 正比于长度，坡罚与长度无关：两者不是同一把尺子，
     * 搜索于是奖励「少走步数」而不是「少爬高度」，能斜着横切就斜着横切。
     *
     * <p><b>{@code slopePenaltyByLength=true}（0.9.062 起的默认）—— 按长度收。</b>
     * 设一步水平走 {@code L}、抬 {@code rise}，该步在软/硬档下免罚的爬升额度分别是
     * {@code softGradeLimit × L} 与 {@code hardGradeLimit × L}，超出部分以**竖向格数**计价：
     * <pre>
     *   overSoft = max(0, rise - softGradeLimit × L)      // 格
     *   overHard = max(0, rise - hardGradeLimit × L)      // 格
     *   penalty  = softGradePenalty × overSoft + hardGradePenalty × overHard
     * </pre>
     * 三个性质因此成立：
     * <ol>
     *   <li><b>与 baseCost 同量纲</b>：罚额 = 单价 × 格数，把一步对半分，两半的罚额之和
     *       与原步相同（分段可加），不再随步长漂移；</li>
     *   <li><b>单价与步长无关</b>：每多爬 1 格就是 {@code softGradePenalty}，正步斜步一个价
     *       （按步收在同样条件下是 {@code 600 / (0.08 × L)}，斜步便宜 1/1.414）；</li>
     *   <li><b>免罚额度仍按水平长度给</b>：走得更远本来就更缓，这一项本来就是对的。</li>
     * </ol>
     *
     * <p>0.9.028 的原型分支末尾多了一个 {@code / step}。它是**纯粹的整档缩放**：既不改变
     * 正步/斜步的比值，又让「网格步长」这个与地形无关的参数顺手改变坡罚强度（粗规划
     * step=8、局部修坡 step=1~2，同一段地形在两条链上罚额差 4~8 倍）。0.9.062 删掉它，
     * 单价回到「每超出 1 格竖向爬升 600」这个可解释的口径。
     *
     * <p>包级可见，供 {@code SlopePenaltyBasisTest} / {@code GradePenaltyLengthBasisTest}
     * 直接钉住：旧档逐位不变、新档单价与步长无关、分段可加。
     */
    static double gradePenalty(int rise, double horizDist, PathfindingCostConfig cfg) {
        double distance = Math.max(1e-6D, horizDist);
        if (!cfg.slopePenaltyByLength()) {
            double grade = rise / distance;
            double penalty = 0.0D;
            if (grade > cfg.softGradeLimit()) {
                penalty += cfg.softGradePenalty() * (grade - cfg.softGradeLimit()) / cfg.softGradeLimit();
            }
            if (grade > cfg.hardGradeLimit()) {
                penalty += cfg.hardGradePenalty() * (grade - cfg.hardGradeLimit()) / cfg.hardGradeLimit();
            }
            return penalty;
        }
        // 0.9.077（H-01b）：免罚额度改由「阶梯式阈值」承担方向偏差的消除 ——
        // softGradeLimit 已提到 ≥ 1/最短正交步长（0.13 ≥ 1/8），于是「每步爬 1 格」
        // 在正交与对角两种步长下都落在免罚额度内，锯齿不再有 107 倍的收益。
        // 额度本身仍按本步水平距离给：走得更远本就更缓，这一项是对的。
        double overSoft = Math.max(0.0D, rise - cfg.softGradeLimit() * distance);
        double overHard = Math.max(0.0D, rise - cfg.hardGradeLimit() * distance);
        return cfg.softGradePenalty() * overSoft + cfg.hardGradePenalty() * overHard;
    }

    /**
     * 0.9.061「按步收」公式的手写复刻（只给标定与诊断用，生产路径永远走 {@link #gradePenalty}）。
     *
     * <p>口径：{@code grade = rise / horizDist}，超 soft/hard 阈值的部分各按
     * {@code penalty × (grade - limit) / limit} 结账 —— 这就是「按步收」的全部内容。</p>
     */
    static double legacyGradePenalty(double rise, double horizDist,
                                     double softPenalty, double hardPenalty,
                                     double softLimit, double hardLimit) {
        double distance = Math.max(1e-6D, horizDist);
        double grade = rise / distance;
        double penalty = 0.0D;
        if (grade > softLimit) {
            penalty += softPenalty * (grade - softLimit) / softLimit;
        }
        if (grade > hardLimit) {
            penalty += hardPenalty * (grade - hardLimit) / hardLimit;
        }
        return penalty;
    }

    /**
     * 0.9.062（②）/ 0.9.063（一）：启动期一次性确认坡罚刻度。
     *
     * <p>0.9.063 起这一行同时是<b>标定回执</b>：{@code orthPenalty} / {@code diagPenalty} 是当前
     * 配置下两个参照点（正交爬 1 格 / 对角爬 1 格）的实付罚额；{@code oldOrthPenalty} /
     * {@code oldDiagPenalty} 是同样的两个参照点在 0.9.061「按步收」公式下的罚额 —— 用
     * {@code RoadConstants.LEGACY_SOFT_GRADE_PENALTY} 那一组旧单价<b>重算</b>而非写死，
     * 所以改了 step / limit 之后对照仍然正确。</p>
     *
     * <p>{@code raisedRatio} = min(正交比, 对角比)：&lt;1 就是「比 0.9.061 便宜」，
     * 正是本版要消灭的状态。出厂默认实测 1.0307（正交 8080.8/7837.5、对角 74.0246/62.9126）。</p>
     *
     * <p>只在第一次 {@code sanitize()} 时打一行，之后只付一次 {@code AtomicBoolean} 读。
     * 没有这一行就无法确认跑的是哪套尺子：旧档与新档在平原上几乎同价，只看路形分辨不出来。</p>
     */
    public static void logPenaltyBasisOnce(PathfindingCostConfig cfg) {
        if (cfg == null) return;
        if (PENALTY_BASIS_LOGGED.get()) return;
        if (!PENALTY_BASIS_LOGGED.compareAndSet(false, true)) return;
        double step = cfg.effectiveAStarStep();
        double orthoDist = step;
        double diagDist = step * Math.sqrt(2.0D);
        double orthPenalty = gradePenalty(1, orthoDist, cfg);
        double diagPenalty = gradePenalty(1, diagDist, cfg);
        double oldSoft = net.shiroha233.roadweaver.core.constants.RoadConstants.LEGACY_SOFT_GRADE_PENALTY;
        double oldHard = net.shiroha233.roadweaver.core.constants.RoadConstants.LEGACY_HARD_GRADE_PENALTY;
        double oldOrthPenalty = legacyGradePenalty(1.0D, orthoDist, oldSoft, oldHard,
                cfg.softGradeLimit(), cfg.hardGradeLimit());
        double oldDiagPenalty = legacyGradePenalty(1.0D, diagDist, oldSoft, oldHard,
                cfg.softGradeLimit(), cfg.hardGradeLimit());
        double ratioOrtho = oldOrthPenalty <= 0.0D ? Double.POSITIVE_INFINITY : orthPenalty / oldOrthPenalty;
        double ratioDiag = oldDiagPenalty <= 0.0D ? Double.POSITIVE_INFINITY : diagPenalty / oldDiagPenalty;
        double raisedRatio = Math.min(ratioOrtho, ratioDiag);
        LOGGER.info("[GRADE-PENALTY] soft={} hard={} orthPenalty={} diagPenalty={} "
                        + "oldOrthPenalty={} oldDiagPenalty={} raisedRatio={} "
                        + "step={} normalized={} softLimit={} hardLimit={}",
                String.format(java.util.Locale.ROOT, "%.4f", cfg.softGradePenalty()),
                String.format(java.util.Locale.ROOT, "%.4f", cfg.hardGradePenalty()),
                String.format(java.util.Locale.ROOT, "%.4f", orthPenalty),
                String.format(java.util.Locale.ROOT, "%.4f", diagPenalty),
                String.format(java.util.Locale.ROOT, "%.4f", oldOrthPenalty),
                String.format(java.util.Locale.ROOT, "%.4f", oldDiagPenalty),
                String.format(java.util.Locale.ROOT, "%.4f", raisedRatio),
                cfg.effectiveAStarStep(),
                cfg.slopePenaltyByLength(),
                String.format(java.util.Locale.ROOT, "%.4f", cfg.softGradeLimit()),
                String.format(java.util.Locale.ROOT, "%.4f", cfg.hardGradeLimit()));
    }

    private double computeMoveCost(BlockPos current, BlockPos np, BlockPos nxz,
                                   int[] off, int d,
                                   double[] grad, double gradMag, double[] contour,
                                   PathTerrainField terrain,
                                   PathfindingCostConfig cfg,
                                   Chord chord) {
        double moveX = off[0];
        double moveZ = off[1];
        int offsetSum = Math.abs(off[0]) + Math.abs(off[1]);
        boolean isDiag = (offsetSum == 2 * d);
        double horizDist = isDiag ? d * 1.414 : d;
        double baseCost = isDiag ? cfg.diagStepCost() : cfg.orthoStepCost();

        double alignment = contourAlignment(moveX, moveZ, contour[0], contour[1]);
        double contourDiscount = cfg.contourDiscount();
        double contourFactor = 1.0 - alignment * contourDiscount * Math.min(1.0, gradMag * 2.0 * cfg.contourFollowWeight());
        double adjustedBase = baseCost * Math.max(cfg.contourFactorFloor(), contourFactor);

        PathTerrainField.SampleBundle b = terrain.sampleBundle(np.getX(), np.getZ());
        double biomeCost = cfg.biomeCostFor(terrain.biomeCategory(np.getX(), np.getZ()));
        boolean waterColumn = b.columnWater();
        boolean nearWater = isNearWaterLike(terrain, nxz.getX(), nxz.getZ(), d);
        int waterDepth = b.waterDepth();

        int elevation = Math.abs(np.getY() - current.getY());
        double gradePenalty = gradePenalty(elevation, horizDist, cfg);

        // 竖向代价模型可切换（0.8.418）：默认三段台阶；QUADRATIC 分支让 elevationWeight 复活。
        double elevCost = cfg.elevationSegmentedCost()
                ? segmentedElevationCost(elevation, cfg)
                : (double) elevation * elevation * cfg.elevationWeight() * 0.5;

        double gradAlignPenalty = 0.0;
        if (gradMag > 1e-4) {
            double moveMag = Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (moveMag > 1e-4) {
                double gAlign = Math.abs(moveX * grad[0] + moveZ * grad[1]) / (moveMag * gradMag);
                gradAlignPenalty = gAlign * gradMag * cfg.gradientAlignPenalty() * d;
            }
        }

        int stabilityCost = calculateTerrainStability(terrain, np, np.getY(), d);

        double waterPenalty = 0.0;
        if (waterColumn) {
            waterPenalty = cfg.pfWaterBasePenalty()
                    + waterDepth * (double) waterDepth * cfg.waterDepthWeight() * 2.0;
        }
        double nearWaterPenalty = nearWater ? (cfg.nearWaterCost() * 3.0) : 0.0;
        double proximityPenalty = nearWater ? cfg.waterProximityCost() : 0.0;

        // 0.8.418：把 deviation 家族接进势场法（免罚半径 → 线性/平方 → 硬上限拒绝）。
        double deviationPenalty = deviationCost(chord, np.getX(), np.getZ(), cfg);

        return adjustedBase
                + gradePenalty
                + elevCost
                + gradAlignPenalty
                + stabilityCost * cfg.stabilityWeight()
                + biomeCost * cfg.biomeWeight()
                + waterPenalty
                + nearWaterPenalty
                + proximityPenalty
                + deviationPenalty;
    }

    /**
     * 0.9.030 / Z-2：航向连续性代价（势场法侧）。
     *
     * <p>{@code current.parent} 的方向向量是上一步，{@code off} 是本步；
     * 上上一步沿 parent.parent 回溯一层得到 prevCross（上一步相对它的偏向）。
     * 注意：0.9.031 起 flipCost 默认 16（不是 0），此分支不再恒 0 —— 它每次选线都在生效。
     */
    private static double headingCost(Node current, int[] off, int d,
                                      PathfindingCostConfig cfg) {
        double flipCost = cfg.headingFlipCost();
        double curveCost = cfg.headingCurveCost();
        if (flipCost <= 0.0D && curveCost <= 0.0D) return 0.0D;
        if (current == null || current.parent == null) return 0.0D;
        double nextDx = off[0];
        double nextDz = off[1];
        double prevDx = current.pos.getX() - current.parent.pos.getX();
        double prevDz = current.pos.getZ() - current.parent.pos.getZ();
        if (Math.hypot(prevDx, prevDz) < 1e-9D) return 0.0D;
        double cross = HeadingContinuityCost.cross(prevDx, prevDz, nextDx, nextDz);
        double prevCross = 0.0D;
        Node grandparent = current.parent.parent;
        if (grandparent != null) {
            double gdx = current.parent.pos.getX() - grandparent.pos.getX();
            double gdz = current.parent.pos.getZ() - grandparent.pos.getZ();
            prevCross = HeadingContinuityCost.cross(gdx, gdz, prevDx, prevDz);
        }
        return HeadingContinuityCost.cost(prevCross, cross, (double) d,
                flipCost, curveCost,
                cfg.headingShortLegBlocks(), cfg.headingShortLegMultiplier());
    }

    /**
     * 直线弦：起终点在 XZ 平面上的投影，用于「偏差代价」与「直线走廊回避」。纯几何、零采样。
     */
    private record Chord(double startX, double startZ, double dirX, double dirZ, double length) {
        static Chord of(BlockPos start, BlockPos end) {
            double dx = end.getX() - start.getX();
            double dz = end.getZ() - start.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-6D) return new Chord(start.getX(), start.getZ(), 0.0D, 0.0D, 0.0D);
            return new Chord(start.getX(), start.getZ(), dx / len, dz / len, len);
        }

        /** 点到弦所在直线的垂距（格）。 */
        double perpendicular(int x, int z) {
            if (length <= 0.0D) return 0.0D;
            double vx = x - startX;
            double vz = z - startZ;
            return Math.abs(vx * dirZ - vz * dirX);
        }
    }

    /**
     * 偏差代价（0.8.418 接入势场法）：与 {@code BasicAStarPathfinder} 同一套语义——免罚半径内
     * 为 0，超出后按 {@code deviationSquared} 决定线性/平方，超过硬上限直接拒绝该步（返回 +∞）。
     *
     * <p>{@code deviationWeight<=0} 表示完全关闭（含硬上限），这样「调回 0」能精确回到接线前的
     * 旧行为；势场法原先没有这道闸，硬上限只在玩家显式给出权重时才生效。</p>
     */
    private static double deviationCost(Chord chord, int x, int z, PathfindingCostConfig cfg) {
        double weight = cfg.deviationWeight();
        if (weight <= 0.0D || chord.length() <= 0.0D) return 0.0D;
        double dev = chord.perpendicular(x, z);
        if (dev <= cfg.deviationFreeRadius()) return 0.0D;
        if (dev > cfg.deviationHardCap()) return Double.POSITIVE_INFINITY;
        double excess = dev - cfg.deviationFreeRadius();
        return cfg.deviationSquared() ? excess * excess * weight : excess * weight;
    }

    /**
     * 分段坡度代价（0.8.409 体系，Q1 接入）：温和坡度便宜、陡崖重罚。与
     * BasicAStarPathfinder.segmentedElevationCost 同款三段台阶，作用于每步竖向高差
     * elevation=Δy，替换旧的二次式 {@code elevation²×elevationWeight×0.5}。纯查表/分段，零额外采样。
     */
    private static double segmentedElevationCost(int elevation, PathfindingCostConfig cfg) {
        int gentleMax = cfg.elevationGentleMax();
        int cliffThreshold = cfg.elevationCliffThreshold();
        if (elevation <= gentleMax) {
            return (double) elevation * cfg.elevationGentleWeight();
        }
        if (elevation <= cliffThreshold) {
            return (double) gentleMax * cfg.elevationGentleWeight()
                    + (double) (elevation - gentleMax) * cfg.elevationSteepWeight();
        }
        return (double) gentleMax * cfg.elevationGentleWeight()
                + (double) (cliffThreshold - gentleMax) * cfg.elevationSteepWeight()
                + (double) (elevation - cliffThreshold) * cfg.elevationCliffWeight();
    }

    private List<RoadSegmentPlacement> reconstructPath(List<BlockPos> rawPath, int width,
                                                       ServerLevel level, TerrainSamplingCache cache,
                                                       boolean needsRefinement) {
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        if (needsRefinement) {
            rawPath = accurate.samplePathHeights(rawPath, 0);
        }
        return PathPostProcessor.process(rawPath, width, level, cache,
                RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH, accurate);
    }

    private List<BlockPos> reconstructRawPath(Node endNode) {
        List<BlockPos> rawPath = new ArrayList<>();
        Node c = endNode;
        while (c != null) { rawPath.add(c.pos); c = c.parent; }
        Collections.reverse(rawPath);
        return rawPath;
    }

    private static int manhattan2d(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static double heuristic(BlockPos a, BlockPos b, PathfindingCostConfig cfg) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) * cfg.heuristicWeight() * RoadConstants.POTENTIAL_FIELD_HEURISTIC_DAMPING;
    }

    private static boolean isActiveOpenNode(Node current, Map<BlockPos, Node> allNodes, Set<BlockPos> closed) {
        return current != null
                && !closed.contains(current.pos)
                && allNodes.get(current.pos) == current;
    }

    private static Node pollActiveOpenNode(PriorityQueue<Node> openSet,
                                           Map<BlockPos, Node> allNodes,
                                           Set<BlockPos> closed) {
        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            if (isActiveOpenNode(current, allNodes, closed)) {
                return current;
            }
        }
        return null;
    }

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double f;
        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos; this.parent = parent; this.g = g; this.f = f;
        }
    }
}
