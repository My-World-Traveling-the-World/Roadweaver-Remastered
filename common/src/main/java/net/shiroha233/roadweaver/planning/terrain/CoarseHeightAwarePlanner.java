/* 文件职责：粗规划阶段的 3D 高度感知候选寻路。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.shiroha233.roadweaver.core.constants.PathfindingConstants;

/**
 * Coarse planning fallback that expands real Y levels. It can choose either a
 * surface contour or a lowered tunnel chord through a mountain, so a village
 * at the foot of a cliff is not approached by a single impossible drop.
 */
final class CoarseHeightAwarePlanner {
    private static final int COARSE_TUNNEL_VERTICAL_STEP = 2;
    private static final int COARSE_MIN_BUILD_Y = -64;
    /** Cost of one edge crossing a predicted building footprint (task 9). */
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private CoarseHeightAwarePlanner() {
    }

    static List<BlockPos> find(BlockPos start,
                               BlockPos goal,
                               PathTerrainField terrain,
                               PathfindingCostConfig config,
                               int maxExpandedStates) {
        if (start == null || goal == null || terrain == null || config == null
                || !terrain.contains(start.getX(), start.getZ())
                || !terrain.contains(goal.getX(), goal.getZ())) {
            return null;
        }
        int step = Math.max(1, config.effectiveAStarStep());
        int startX = PathfindingHelper.snapToGrid(start.getX(), step);
        int startZ = PathfindingHelper.snapToGrid(start.getZ(), step);
        int goalX = PathfindingHelper.snapToGrid(goal.getX(), step);
        int goalZ = PathfindingHelper.snapToGrid(goal.getZ(), step);
        if (!terrain.contains(startX, startZ) || !terrain.contains(goalX, goalZ)) {
            return null;
        }
        int startY = terrain.height(startX, startZ);
        int goalY = terrain.height(goalX, goalZ);

        Corridor corridor = Corridor.forRoute(startX, startZ, goalX, goalZ, step);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<StateKey, Double> best = new HashMap<>();
        Node first = new Node(startX, startY, startZ, -1, 0.0D,
                heuristic(startX, startZ, goalX, goalZ, config), null);
        open.add(first);
        best.put(first.key(), 0.0D);
        int expanded = 0;
        // Read once per search: the hot expansion loop below must not hit the
        // synchronized ConfigService (nor allocate) for every neighbor.
        int[] verticalOffsets = tunnelVerticalOffsets();
        // Shared grade ceiling (default 0.5): the coarse search must never
        // accept a slope the post-hoc transition check would reject, so a
        // cliff is crossed by ramp/tunnel/bridge instead of a near-vertical
        // shaft that later grade repair cannot fix.
        double maxRisePerBlock = Math.max(0.1D, maxGradeFraction());

        while (!open.isEmpty() && expanded < maxExpandedStates) {
            Node current = open.poll();
            Double known = best.get(current.key());
            if (known == null || current.cost() > known + 0.0001D) continue;
            if (current.x() == goalX && current.z() == goalZ) {
                return reconstruct(current, goalX, goalY, goalZ);
            }
            expanded++;

            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                int nextX = current.x() + DIRECTIONS[direction][0] * step;
                int nextZ = current.z() + DIRECTIONS[direction][1] * step;
                if (!corridor.contains(nextX, nextZ) || !terrain.contains(nextX, nextZ)) continue;

                int terrainY = terrain.height(nextX, nextZ);
                boolean goalColumn = nextX == goalX && nextZ == goalZ;
                boolean startColumn = nextX == startX && nextZ == startZ;
                int candidateCount = goalColumn || startColumn ? 1 : verticalOffsets.length;
                for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                    int nextY = goalColumn ? goalY
                            : startColumn ? startY
                            : Math.max(COARSE_MIN_BUILD_Y, terrainY + verticalOffsets[candidateIndex]);
                    double horizontal = Math.hypot(nextX - current.x(), nextZ - current.z());
                    int rise = Math.abs(nextY - current.y());
                    if (rise > maxRisePerBlock * horizontal) continue;

                    BlockPos currentPos = new BlockPos(current.x(), current.y(), current.z());
                    BlockPos nextPos = new BlockPos(nextX, nextY, nextZ);
                    // Predicted building footprints add a heavy cost instead of
                    // pruning the edge (task 9): the fallback search still
                    // detours, but a corridor with no alternative remains
                    // routable and lets the local repair/gap fallback decide.
                    double obstaclePenalty = PathfindingHelper.edgeIntersectsPlanningObstacle(
                            currentPos, nextPos, planningRoadRadius(), step)
                            ? PathfindingConstants.PLANNING_OBSTACLE_COST : 0.0D;

                    PathTerrainField.SampleBundle sample = terrain.sampleBundle(nextX, nextZ);
                    double waterPenalty = sample.columnWater()
                            ? 2400.0D + sample.waterDepth() * Math.max(1.0D, config.waterDepthWeight())
                            : (terrain.isNearWater(nextX, nextZ, step)
                            ? 120.0D + config.nearWaterCost() : 0.0D);
                    double tunnelPenalty = Math.max(0, terrainY - nextY) * 2.5D;
                    double grade = rise / Math.max(1.0D, horizontal);
                    double turnPenalty = current.direction() < 0 || current.direction() == direction
                            ? 0.0D : 4.0D;
                    double deviation = distanceFromLine(nextX, nextZ, startX, startZ, goalX, goalZ)
                            * config.deviationWeight() / Math.max(1.0D, step);
                    double nextCost = current.cost()
                            + horizontal
                            + rise * 8.0D
                            + tunnelPenalty
                            + grade * grade * 90.0D
                            + waterPenalty
                            + turnPenalty
                            + deviation
                            + obstaclePenalty;

                    StateKey key = new StateKey(nextX, nextY, nextZ, direction);
                    Double old = best.get(key);
                    if (old != null && old <= nextCost) continue;
                    Node next = new Node(nextX, nextY, nextZ, direction, nextCost,
                            nextCost + heuristic(nextX, nextZ, goalX, goalZ, config), current);
                    best.put(key, nextCost);
                    open.add(next);
                }
            }
        }
        return null;
    }

    /** Shared grade ceiling; fallback mirrors the sanitize default 0.5. */
    private static double maxGradeFraction() {
        try {
            return Math.max(0.1D, Math.min(0.9D,
                    ConfigService.get().planning().maxGradeFraction()));
        } catch (Throwable ignored) {
            return 0.5D;
        }
    }

    /**
     * Tunnel candidate offsets below the sampled terrain, read once per
     * search. Level 0 is the surface; each further level drops one coarse
     * vertical step until the configured tunnel depth is covered.
     */
    private static int[] tunnelVerticalOffsets() {
        int tunnelDepth;
        try {
            tunnelDepth = Math.max(4, ConfigService.get().planning().tunnelDepthBlocks());
        } catch (Throwable ignored) {
            tunnelDepth = 12;
        }
        int levels = tunnelDepth / COARSE_TUNNEL_VERTICAL_STEP + 1;
        int[] offsets = new int[levels];
        for (int i = 1; i < levels; i++) {
            offsets[i] = -i * COARSE_TUNNEL_VERTICAL_STEP;
        }
        return offsets;
    }

    private static List<BlockPos> reconstruct(Node goal,
                                              int goalX,
                                              int goalY,
                                              int goalZ) {
        List<BlockPos> path = new ArrayList<>();
        for (Node node = goal; node != null; node = node.parent()) {
            path.add(new BlockPos(node.x(), node.y(), node.z()));
        }
        Collections.reverse(path);
        if (path.isEmpty()) return null;
        path.set(path.size() - 1, new BlockPos(goalX, goalY, goalZ));
        return List.copyOf(path);
    }

    private static double heuristic(int x, int z, int goalX, int goalZ,
                                    PathfindingCostConfig config) {
        double dx = Math.abs(goalX - x);
        double dz = Math.abs(goalZ - z);
        return Math.sqrt(dx * dx + dz * dz) * Math.max(0.5D, config.heuristicWeight());
    }

    private static double distanceFromLine(int x, int z,
                                           int startX, int startZ,
                                           int goalX, int goalZ) {
        double dx = goalX - startX;
        double dz = goalZ - startZ;
        double length = Math.hypot(dx, dz);
        return length < 0.001D
                ? 0.0D
                : Math.abs((x - startX) * dz - (z - startZ) * dx) / length;
    }

    private record StateKey(int x, int y, int z, int direction) {
    }

    private static final class Node {
        private final int x;
        private final int y;
        private final int z;
        private final int direction;
        private final double cost;
        private final double score;
        private final Node parent;

        private Node(int x, int y, int z, int direction, double cost,
                     double score, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.direction = direction;
            this.cost = cost;
            this.score = score;
            this.parent = parent;
        }

        private int x() { return x; }
        private int y() { return y; }
        private int z() { return z; }
        private int direction() { return direction; }
        private double cost() { return cost; }
        private double score() { return score; }
        private Node parent() { return parent; }
        private StateKey key() { return new StateKey(x, y, z, direction); }
    }

    private record Corridor(int startX, int startZ, int goalX, int goalZ,
                            double directLength, double lateralLimit, double endpointPadding) {
        static Corridor forRoute(int startX, int startZ, int goalX, int goalZ, int step) {
            double dx = goalX - startX;
            double dz = goalZ - startZ;
            double direct = Math.max(1.0D, Math.hypot(dx, dz));
            double lateral = Math.min(512.0D, Math.max(step * 18.0D, direct * 0.50D));
            double padding = Math.max(step * 6.0D, Math.min(144.0D, direct * 0.20D));
            return new Corridor(startX, startZ, goalX, goalZ, direct, lateral, padding);
        }

        boolean contains(int x, int z) {
            double dx = goalX - startX;
            double dz = goalZ - startZ;
            double fromX = x - startX;
            double fromZ = z - startZ;
            double projection = fromX * dx + fromZ * dz;
            if (projection < -endpointPadding * directLength
                    || projection > directLength * directLength + endpointPadding * directLength) {
                return false;
            }
            return Math.abs(fromX * dz - fromZ * dx) / directLength <= lateralLimit;
        }
    }
}
