/* 文件职责：实现基于地形场读取的双向 A* 普通道路寻路。 */
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

import java.util.*;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.*;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.core.constants.PathfindingConstants;

/**
 * 双向 A* 寻路
 */
public final class BidirectionalAStarPathfinder implements Pathfinder {

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

        PriorityQueue<Node> openF = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        PriorityQueue<Node> openB = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Node> nodesF = new HashMap<>();
        Map<BlockPos, Node> nodesB = new HashMap<>();
        Set<BlockPos> closedF = new HashSet<>();
        Set<BlockPos> closedB = new HashSet<>();

        Node startNode = new Node(start, null, 0.0, heuristic(start, end, cfg));
        Node endNode = new Node(end, null, 0.0, heuristic(end, start, cfg));
        openF.add(startNode);
        nodesF.put(start, startNode);
        openB.add(endNode);
        nodesB.put(end, endNode);

        int stepsBudget = Math.max(1, maxSteps);
        ThrottleHelper.resetThrottle();
        try {
            while (stepsBudget > 0) {
                if (Thread.currentThread().isInterrupted()) return null;

                Node peekF = peekActiveOpenNode(openF, nodesF, closedF);
                Node peekB = peekActiveOpenNode(openB, nodesB, closedB);
                if (peekF == null || peekB == null) break;

                stepsBudget--;
                ThrottleHelper.throttle();
                if (Thread.currentThread().isInterrupted()) return null;

                boolean expandForward;
                expandForward = peekF.f <= peekB.f;

                Meet meet;
                if (expandForward) {
                    meet = expandOneSide(openF, nodesF, closedF, nodesB, closedB,
                            true, start, end, level, terrain, offsets, d, cfg);
                } else {
                    meet = expandOneSide(openB, nodesB, closedB, nodesF, closedF,
                            false, end, start, level, terrain, offsets, d, cfg);
                }

                if (meet != null) {
                    return reconstructRawPath(meet.forward, meet.backward);
                }
            }
            return null;
        } finally {
            openF.clear(); openB.clear();
            nodesF.clear(); nodesB.clear();
            closedF.clear(); closedB.clear();
            ThrottleHelper.clearThrottle();
        }
    }

    private Meet expandOneSide(PriorityQueue<Node> open,
                               Map<BlockPos, Node> nodesThis, Set<BlockPos> closedThis,
                               Map<BlockPos, Node> nodesOther, Set<BlockPos> closedOther,
                               boolean isForward, BlockPos from, BlockPos to,
                               ServerLevel level,
                               PathTerrainField terrain,
                               int[][] offsets, int d, PathfindingCostConfig cfg) {
        Node current = pollActiveOpenNode(open, nodesThis, closedThis);
        if (current == null) return null;
        closedThis.add(current.pos);

        for (int[] off : offsets) {
            if (Thread.currentThread().isInterrupted()) return null;
            BlockPos nxz = current.pos.offset(off[0], 0, off[1]);
            if (!terrain.contains(nxz.getX(), nxz.getZ())) continue;
            // 0.9.018：禁行走廊（D2 平行双桥「方案 C」改道）。null 时零开销。
            if (cfg.corridorBan() != null
                    && cfg.corridorBan().blocks(nxz.getX(), nxz.getZ())) continue;
            int y = heightSampler(terrain, nxz.getX(), nxz.getZ());
            BlockPos np = new BlockPos(nxz.getX(), y, nxz.getZ());
            // The THT compatibility hook registers exact wood columns before
            // placement.  Reject them here so the route bends around a normal
            // huge tree instead of becoming a construction-time road gap.
            // 0.9.020：硬障碍口径统一（同 PotentialFieldPathfinder 的说明）。
            if (StructureAvoidanceService.buildingObstacleMode()
                    != StructureAvoidanceService.BuildingObstacleMode.PENALTY
                    && StructureAvoidanceService.blocksPlanningObstacle(
                    np, StructureAvoidanceService.planningRoadRadius())) continue;
            if (HugeTreeReservation.isReserved(level, np)
                    || StructureAvoidanceService.shouldAvoidPlanning(
                    np, StructureAvoidanceService.planningRoadRadius())
                    || edgeIntersectsPlanningObstacle(
                    current.pos, np, StructureAvoidanceService.planningRoadRadius(), d)) continue;
            if (closedThis.contains(np)) continue;

            double tentativeG = current.g + computeMoveCost(current.pos, np, nxz, off, d,
                    from, to, terrain, cfg)
                    + headingCost(current, off, d, cfg);

            Node existing = nodesThis.get(np);
            if (existing != null && tentativeG >= existing.g) continue;

            double h = heuristic(np, to, cfg);
            double f = tentativeG + (1.0 + PathfindingConstants.HEURISTIC_EPSILON) * h;
            Node next = new Node(np, current, tentativeG, f);
            nodesThis.put(np, next);
            open.add(next);

            Node other = nodesOther.get(np);
            if (other == null && closedOther.contains(np)) other = nodesOther.get(np);
            if (other != null) {
                return isForward ? new Meet(next, other) : new Meet(other, next);
            }
        }
        return null;
    }

    private double computeMoveCost(BlockPos current, BlockPos np, BlockPos nxz, int[] off, int d,
                                   BlockPos from, BlockPos to, PathTerrainField terrain,
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
        int proximityCost = nearWater
                ? (terrain.isNearWater(nxz.getX(), nxz.getZ(), Math.min(2, Math.max(1, d)))
                        ? cfg.waterProximityCost()
                        : (int) Math.round(cfg.waterProximityCost() * 0.4D))
                : 0;
        double deviation = deviation2d(np, from, to);
        double deviationCost = deviation * cfg.deviationWeight() / Math.max(1.0, d);

        return stepCost
                + elevation * cfg.elevationWeight()
                + biomeCost * cfg.biomeWeight()
                + stabilityCost * cfg.stabilityWeight()
                + waterDepthCost
                + nearWaterCost
                + proximityCost
                + deviationCost;
    }

    /** 0.9.030 / Z-2：航向连续性代价（双向 A* 侧）。默认全 0 ⇒ 恒 0。 */
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

    private List<RoadSegmentPlacement> reconstructPath(List<BlockPos> rawPath,
                                                       int width, ServerLevel level,
                                                       TerrainSamplingCache cache,
                                                       boolean needsRefinement) {
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        if (needsRefinement) {
            rawPath = accurate.samplePathHeights(rawPath, 0);
        }
        return PathPostProcessor.process(rawPath, width, level, cache,
                PathPostProcessor.configuredBridgeMinWaterDepth(), accurate);
    }

    private List<BlockPos> reconstructRawPath(Node meetForward, Node meetBackward) {
        List<BlockPos> rawPath = new ArrayList<>();
        Node cur = meetForward;
        while (cur != null) { rawPath.add(cur.pos); cur = cur.parent; }
        Collections.reverse(rawPath);

        Node backStart = (meetBackward != null && meetBackward.pos.equals(meetForward.pos))
                ? meetBackward.parent : meetBackward;
        cur = backStart;
        while (cur != null) { rawPath.add(cur.pos); cur = cur.parent; }
        return rawPath;
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

    private static boolean isActiveOpenNode(Node current, Map<BlockPos, Node> nodesThis, Set<BlockPos> closedThis) {
        return current != null
                && !closedThis.contains(current.pos)
                && nodesThis.get(current.pos) == current;
    }

    private static Node peekActiveOpenNode(PriorityQueue<Node> open,
                                           Map<BlockPos, Node> nodesThis,
                                           Set<BlockPos> closedThis) {
        while (!open.isEmpty()) {
            Node current = open.peek();
            if (isActiveOpenNode(current, nodesThis, closedThis)) {
                return current;
            }
            open.poll();
        }
        return null;
    }

    private static Node pollActiveOpenNode(PriorityQueue<Node> open,
                                           Map<BlockPos, Node> nodesThis,
                                           Set<BlockPos> closedThis) {
        while (!open.isEmpty()) {
            Node current = open.poll();
            if (isActiveOpenNode(current, nodesThis, closedThis)) {
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

    private static final class Meet {
        final Node forward;
        final Node backward;
        Meet(Node forward, Node backward) {
            this.forward = forward; this.backward = backward;
        }
    }
}
