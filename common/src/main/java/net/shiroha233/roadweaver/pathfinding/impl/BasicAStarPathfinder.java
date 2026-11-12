/* 文件职责：实现基于地形场读取的基础 A* 普通道路寻路。 */
package net.shiroha233.roadweaver.pathfinding.impl;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
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

import java.util.*;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.*;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.core.constants.PathfindingConstants;

/**
 * 基础 A* 寻路实现
 */
public final class BasicAStarPathfinder implements Pathfinder {
    /**
     * Planning cost of one station (or edge) crossing a predicted building
     * footprint. Matches the structure-conflict weight used by the coarse
     * candidate scoring so both planners rank detours over crossings.
     */
    /** 偏差硬上限的拒绝哨兵：代价返回此值时，邻居被直接拒绝（偏离直线过远）。 */
    private static final double DOUBLE_REJECT_SENTINEL = Double.POSITIVE_INFINITY;

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, int width,
                               ServerLevel level, int maxSteps,
                               TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig cfg) {
        List<BlockPos> rawPath = searchRawPath(start, end, level, maxSteps, terrain, cfg);
        if (rawPath == null || rawPath.isEmpty()) {
            return PathResult.failure();
        }
        List<RoadSegmentPlacement> segments = reconstructPath(rawPath, width, level, cache, false);
        return segments != null ? PathResult.success(segments) : PathResult.failure();
    }

    @Override
    public PathResult findRawPath(BlockPos start, BlockPos end,
                                  ServerLevel level, int maxSteps,
                                  TerrainSamplingCache cache, PathTerrainField terrain, PathfindingCostConfig cfg) {
        List<BlockPos> rawPath = searchRawPath(start, end, level, maxSteps, terrain, cfg);
        return rawPath == null || rawPath.isEmpty() ? PathResult.failure() : PathResult.raw(rawPath);
    }

    private List<BlockPos> searchRawPath(BlockPos start, BlockPos end,
                                         ServerLevel level, int maxSteps,
                                         PathTerrainField terrain, PathfindingCostConfig cfg) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(start, null, 0.0, heuristic(start, end, cfg), 0, 0);
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int d = cfg.effectiveAStarStep();
        int[][] offsets = {
                {d, 0}, {-d, 0}, {0, d}, {0, -d},
                {d, d}, {d, -d}, {-d, d}, {-d, -d}
        };

        int stepsBudget = Math.max(1, maxSteps);
        ThrottleHelper.resetThrottle();
        try {
            while (stepsBudget > 0) {
                if (Thread.currentThread().isInterrupted()) return null;

                Node current = pollActiveOpenNode(openSet, allNodes, closed);
                if (current == null) break;

                stepsBudget--;
                ThrottleHelper.throttle();
                if (Thread.currentThread().isInterrupted()) return null;

                if (manhattan2d(current.pos, end) < d * 2) {
                    return reconstructRawPath(current);
                }

                closed.add(current.pos);
                allNodes.remove(current.pos);

                for (int[] off : offsets) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    BlockPos nxz = current.pos.offset(off[0], 0, off[1]);
                    if (!terrain.contains(nxz.getX(), nxz.getZ())) continue;
                    // 0.9.018：禁行走廊（D2 平行双桥「方案 C」改道）。null 时零开销。
                    if (cfg.corridorBan() != null
                            && cfg.corridorBan().blocks(nxz.getX(), nxz.getZ())) continue;
                    int y = heightSampler(terrain, nxz.getX(), nxz.getZ());
                    BlockPos np = new BlockPos(nxz.getX(), y, nxz.getZ());
                    if (HugeTreeReservation.isReserved(level, np)) continue;
                    // Predicted building footprints are a high cost, not a hard
                    // wall (task 9): the search still detours around them, but
                    // a corridor with no alternative keeps its route instead of
                    // failing outright; the local building repair and the gap
                    // fallback handle the residual crossing.
                    double obstaclePenalty = 0.0D;
                    int obstacleRadius = planningRoadRadius();
                    // 硬障碍模式：已验证建筑（含村庄群盒）不可穿过。调用方
                    // （RawPathSearch）在搜索失败时会自动降级回高代价模式重搜，
                    // 所以这里直接 continue 不会让路线无解。
                    if (buildingObstacleMode()
                            != StructureAvoidanceService.BuildingObstacleMode.PENALTY
                            && blocksPlanningObstacle(np, obstacleRadius)) {
                        continue;
                    }
                    if (StructureAvoidanceService.shouldAvoidPlanning(np, obstacleRadius)) {
                        obstaclePenalty += PathfindingConstants.PLANNING_OBSTACLE_COST;
                    }
                    if (edgeIntersectsPlanningObstacle(current.pos, np, obstacleRadius, d)) {
                        obstaclePenalty += PathfindingConstants.PLANNING_OBSTACLE_COST;
                    }
                    if (closed.contains(np)) continue;

                    double moveCost = computeMoveCost(current.pos, np, nxz, off, d,
                            start, end, terrain, cfg);
                    if (moveCost == DOUBLE_REJECT_SENTINEL) continue; // 偏差硬上限：拒绝

                    // 累计坡度：与上一步同向爬坡时，按连续爬升量累加惩罚；窗口限制饱和上限。
                    int deltaY = np.getY() - current.pos.getY();
                    int climbRun;
                    if (deltaY != 0 && Integer.signum(deltaY) == Integer.signum(current.lastDelta)) {
                        climbRun = current.climbRun + Math.abs(deltaY);
                    } else {
                        climbRun = Math.abs(deltaY);
                    }
                    int climbCap = cfg.accumulatedSlopeWindow() * d;
                    if (climbRun > climbCap) climbRun = climbCap;
                    double accumulatedCost = climbRun * cfg.accumulatedSlopeWeight();

                    double tentativeG = current.g + moveCost + obstaclePenalty + accumulatedCost;

                    Node n = allNodes.get(np);
                    if (n == null || tentativeG < n.g) {
                        double h = heuristic(np, end, cfg);
                        double f = tentativeG + (1.0 + PathfindingConstants.HEURISTIC_EPSILON) * h;
                        n = new Node(np, current, tentativeG, f, deltaY, climbRun);
                        allNodes.put(np, n);
                        openSet.add(n);
                    }
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

    private double computeMoveCost(BlockPos current, BlockPos np, BlockPos nxz, int[] off, int d,
                                   BlockPos start, BlockPos end, PathTerrainField terrain,
                                   PathfindingCostConfig cfg) {
        PathTerrainField.SampleBundle b = terrain.sampleBundle(np.getX(), np.getZ());
        int waterDepth = b.waterDepth();
        int biomeCost = b.waterBiome() ? PathfindingConstants.WATER_BIOME_BASE_COST : 0;
        int elevation = Math.abs(np.getY() - current.getY());
        int offsetSum = Math.abs(off[0]) + Math.abs(off[1]);
        double stepCost = (offsetSum == 2 * d) ? cfg.diagStepCost() : cfg.orthoStepCost();
        int stabilityCost = calculateTerrainStability(terrain, np, np.getY(), d);
        boolean nearWater = isNearWaterLike(terrain, nxz.getX(), nxz.getZ(), d);
        int waterDepthCost = b.columnWater() ? (int) (waterDepth * cfg.waterDepthWeight()) : 0;
        int nearWaterCost = nearWater ? cfg.nearWaterCost() : 0;
        int proximityCost = gradedWaterProximityCost(nearWater, nxz.getX(), nxz.getZ(), d, terrain, cfg);
        double deviation = deviation2d(np, start, end);

        // ── 0.8.409 分段/软惩罚（最细粒度奖励惩罚体系）────────────────────────
        double elevationCost = segmentedElevationCost(elevation, cfg);
        double segmentedDeviationCost = segmentedDeviationCost(deviation, d, cfg);
        int contourBonus = elevation == 0 ? cfg.contourBonus() : 0;
        int nearWaterDecay = decayedNearWaterCost(nearWater, nxz.getX(), nxz.getZ(), d, terrain, cfg);

        return stepCost
                + elevationCost
                + biomeCost * cfg.biomeWeight()
                + stabilityCost * cfg.stabilityWeight()
                + waterDepthCost
                + nearWaterCost
                + proximityCost
                + nearWaterDecay
                + contourBonus
                + segmentedDeviationCost;
    }

    /** 分段坡度代价：温和坡度便宜、陡崖重罚。纯查表/分段，零额外采样。 */
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

    /** 分段偏差代价：免罚半径内免费，超出部分（可选平方）增长，超过硬上限返回拒绝哨兵。 */
    private static double segmentedDeviationCost(double deviation, int d, PathfindingCostConfig cfg) {
        double dev = deviation / Math.max(1.0, d);
        if (dev <= cfg.deviationFreeRadius()) return 0.0D;
        double excess = dev - cfg.deviationFreeRadius();
        if (dev > cfg.deviationHardCap()) return DOUBLE_REJECT_SENTINEL;
        if (cfg.deviationSquared()) return excess * excess * cfg.deviationWeight();
        return excess * cfg.deviationWeight();
    }

    /**
     * 分档的「离水距离」代价（0.9.016）。
     *
     * <p>旧实现是 {@code nearWater ? waterProximityCost : 0} 的二值台阶，而 {@code nearWater}
     * 的判定半径就是 A* 步长（默认 8 格）⇒ 每走一步要么跨进、要么跨出这个半径，代价在
     * 0 / 30 之间跳变，A* 就在河岸边界上反复横跳（用户截图里沿岸段抖动的成因之一）。</p>
     *
     * <p>这里改成两档：水面 2 格内给满额，2~{@code d} 格给 40%，更远为 0 ⇒ 变成
     * 0 / 0.4× / 1× 的三段梯度，路会自然稳在离水几格的位置，而不是死贴岸线。
     * 代价是每步多一次 {@code isNearWater} 查询（半径 2），只在水边邻域发生。</p>
     */
    private static int gradedWaterProximityCost(boolean nearWater, int x, int z, int d,
                                                PathTerrainField terrain, PathfindingCostConfig cfg) {
        if (!nearWater) return 0;
        int full = cfg.waterProximityCost();
        if (full <= 0) return 0;
        int closeRadius = Math.min(2, Math.max(1, d));
        if (terrain.isNearWater(x, z, closeRadius)) return full;
        return (int) Math.round(full * 0.4D);
    }

    /**
     * 近水软衰减：离水越近惩罚越重，按距离每格衰减（0.9.016 修正）。
     *
     * <p>旧实现有两个必死的短路，导致这段代码在默认配置下**从未执行过**：
     * ①{@code if (radius <= d) return 0} —— {@code nearWaterDecayRadius} 默认 8、{@code d}
     *   就是 A* 步长 8，条件恒真；②即使绕过它，{@code cfg.nearWaterCost()} 默认 0，
     *   乘积也是 0。</p>
     *
     * <p>其中 ②是 0.8.418「蜿蜒优先」的有意默认（允许贴水岸走、反而多弯），保留；
     * 但改为在函数入口一次性短路，语义清晰。真正的 bug ① 去掉：改为按实际半径衰减
     * {@code base × rate^radius}，这样把 {@code nearWaterCost} 调成非 0 时它真的会生效。</p>
     */
    private static int decayedNearWaterCost(boolean nearWater, int x, int z, int d,
                                            PathTerrainField terrain, PathfindingCostConfig cfg) {
        int base = cfg.nearWaterCost();
        if (base <= 0) return 0;
        if (nearWater) return 0;
        int radius = cfg.nearWaterDecayRadius();
        if (radius <= 1) return 0;
        // 0.9.048（D-22）：这个函数**没有任何"距离"输入**，所以返回值和"离水多远"无关，
        // 是一个台阶 base×rate^radius，不是逐格衰减 —— 上面的 javadoc 说它会"按实际半径
        // 衰减…调成非 0 就真的生效"是错的。
        // 更关键：nearWater 是用搜索步长 d 判的。只要 radius <= d，
        //   ① nearWater 为真的列已经在 :261 提前返回；
        //   ② nearWater 为假的列必然也在 isNearWater(radius) 之外 ⇒ 这里再返回 0。
        // ⇒ 整个函数**结构性恒 0**。而默认 nearWaterDecayRadius=8 正好等于默认 aStarStep=8，
        //    所以它从 0.9.017 修好"恒真短路"之后，就变成了"恒假"，一直没生效过。
        if (radius <= d) {
            warnDecayRadiusDeadOnce(radius, d);
            return 0;
        }
        if (!terrain.isNearWater(x, z, radius)) return 0;
        double rate = cfg.nearWaterDecayRate();
        return (int) Math.round(base * Math.pow(rate, radius));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean DECAY_DEAD_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 只在第一次命中时提示一次：否则每个节点一行会刷爆日志。 */
    private static void warnDecayRadiusDeadOnce(int radius, int step) {
        if (!DECAY_DEAD_WARNED.compareAndSet(false, true)) return;
        org.slf4j.LoggerFactory.getLogger("roadweaver").warn("RoadWeaver nearWaterDecayRadius={} <= aStarStep={} ⇒ nearWater 衰减代价结构性恒 0，"
                + "把 nearWaterCost 调成非 0 也不会生效。要让衰减有意义，需要 radius > aStarStep，"
                + "并且给这个函数补一个真正的「距离」输入（当前实现是台阶不是衰减）。", radius, step);
    }

    private List<RoadSegmentPlacement> reconstructPath(List<BlockPos> rawPath, int width,
                                                       ServerLevel level, TerrainSamplingCache cache,
                                                       boolean needsRefinement) {
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        if (needsRefinement) {
            rawPath = accurate.samplePathHeights(rawPath, 0);
        }
        return PathPostProcessor.process(rawPath, width, level, cache,
                PathPostProcessor.configuredBridgeMinWaterDepth(), accurate);
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
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        double approx = Math.abs(dx) + Math.abs(dz) - 0.6 * Math.min(Math.abs(dx), Math.abs(dz));
        return approx * cfg.heuristicWeight();
    }

    private static double deviation2d(BlockPos p, BlockPos a, BlockPos b) {
        double ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ();
        double num = Math.abs((bz - az) * p.getX() - (bx - ax) * p.getZ() + bx * az - bz * ax);
        double den = Math.hypot(bx - ax, bz - az);
        return den <= 0.0 ? 0.0 : num / den;
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
        /** 上一步的高度差（当前节点相对父节点），用于累计坡度惩罚。 */
        final int lastDelta;
        /** 连续同向爬坡/下坡的累计高度（绝对值），用于累计坡度惩罚。 */
        final int climbRun;
        Node(BlockPos pos, Node parent, double g, double f, int lastDelta, int climbRun) {
            this.pos = pos; this.parent = parent; this.g = g; this.f = f;
            this.lastDelta = lastDelta; this.climbRun = climbRun;
        }
    }
}
