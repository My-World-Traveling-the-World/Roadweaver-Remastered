/* 文件职责：山体盘山（之字形回头弯）路线的规划。 */
package net.shiroha233.roadweaver.planning.terrain;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** 只有持续坡度实质改善且满足原走廊/长度预算时才采用盘山候选，普通主规划仍权威。
 *
 * Bounded contour retry for a genuinely steep primary road route.
 * The normal RoadWeaver planner remains authoritative; this retry only
 * replaces a route when it materially lowers sustained grade and still fits
 * the original corridor and length budget.
 */
final class MountainSwitchbackPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MAX_EXPANDED_STATES = 180_000;
    private static final long MAX_SEARCH_NANOS = 900_000_000L;
    private static final double STEEP_GRADE_TRIGGER = 0.50D;
    private static final double SUSTAINED_GRADE_TRIGGER = 0.25D;
    private static final int MIN_SUSTAINED_STEPS = 4;
    private static final double MAX_BUILDABLE_SURFACE_GRADE = 0.42D;
    private static final double MAX_ROUTE_LENGTH_MULTIPLIER = 2.35D;
    private static final double ACCEPTED_MAX_GRADE_IMPROVEMENT = 0.08D;
    private static final double LOCAL_RISE_WINDOW_BLOCKS = 12.0D;
    private static final int LOCAL_RISE_TRIGGER_BLOCKS = 10;
    private static final int LOCAL_CONTEXT_BLOCKS = 48;
    private static final int MAX_LOCAL_REPAIRS = 4;
    private static final int LOCAL_MAX_EXPANDED_STATES = 70_000;
    private static final long LOCAL_MAX_SEARCH_NANOS = 400_000_000L;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private MountainSwitchbackPlanner() {}

    static List<BlockPos> replaceIfSafer(List<BlockPos> primaryPath,
                                         BlockPos start,
                                         BlockPos goal,
                                         PathTerrainField terrain,
                                         PathfindingCostConfig config) {
        return replaceIfSafer(primaryPath, start, goal, terrain, config, false);
    }

    /** 粗规划阶段优先避免陡坡，减少后续隧道被迫修补不可达入口。
 *
     * Initial coarse planning uses a stricter grade gate than runtime repair.
     * A coarse route is still cheap to replace, so it should prefer a contour
     * or switchback before a later tunnel pass has to recover an impossible
     * entrance/exit profile.
     */
    static List<BlockPos> replaceIfSafer(List<BlockPos> primaryPath,
                                         BlockPos start,
                                         BlockPos goal,
                                         PathTerrainField terrain,
                                         PathfindingCostConfig config,
                                         boolean coarsePlanning) {
        if (primaryPath == null || primaryPath.size() < 2 || start == null || goal == null
                || terrain == null || config == null) return primaryPath;
        List<BlockPos> workingPath = repairExcessiveRiseWindows(primaryPath, terrain, config);
        GradeStats primary = measureGrade(workingPath, terrain);
        double switchback = switchbackStrength();
        boolean requiresRetry = coarsePlanning
                ? primary.maxGrade() >= Math.max(0.27D, 0.35D - switchback * 0.08D)
                || primary.steepSteps() >= (switchback >= 0.50D ? 1 : 2)
                : primary.requiresRetry();
        if (!requiresRetry) return workingPath;

        SearchResult result = coarsePlanning
                ? findContourRoute(start, goal, terrain, config,
                coarseStateLimit(), coarseTimeBudgetNanos())
                : findContourRoute(start, goal, terrain, config);
        if (result.path() == null || result.path().size() < 2) {
            LOGGER.debug("【盘山折返重试未替换主路线】 " + "RoadWeaver switchback retry kept the primary route: {}", result.reason());
            return workingPath;
        }
        GradeStats candidate = measureGrade(result.path(), terrain);
        double terrainMultiplier = dominantTerrainMultiplier(workingPath, terrain);
        double maxLengthMultiplier = coarsePlanning
                ? (3.0D + switchback) * terrainMultiplier
                : MAX_ROUTE_LENGTH_MULTIPLIER * terrainMultiplier;
        double maxBuildableGrade = coarsePlanning ? 0.38D : MAX_BUILDABLE_SURFACE_GRADE;
        double acceptedImprovement = coarsePlanning ? 0.03D : ACCEPTED_MAX_GRADE_IMPROVEMENT;
        if (candidate.length() > primary.length() * maxLengthMultiplier
                || (primary.maxGrade() > maxBuildableGrade
                && candidate.maxGrade() > maxBuildableGrade)
                || (candidate.maxGrade() > primary.maxGrade() - acceptedImprovement
                && candidate.steepSteps() + 1 > primary.steepSteps())) {
            return workingPath;
        }
        LOGGER.info("【山地盘山折返已接受】 " + "Accepted RoadWeaver {} mountain switchback: maxGrade={} -> {}, steepSteps={} -> {}, length={} -> {}, expanded={}",
                coarsePlanning ? "coarse" : "runtime",
                format(primary.maxGrade()), format(candidate.maxGrade()), primary.steepSteps(), candidate.steepSteps(),
                format(primary.length()), format(candidate.length()), result.expanded());
        return result.path();
    }

    /** 先局部修复短陡崖，再考虑更贵的结构间重试；窗口保留两侧接坡上下文。
 *
     * Repairs short cliff climbs locally before attempting an expensive
     * structure-to-structure retry. A rise greater than ten blocks over a
     * twelve-block route window receives a wider contour search with enough
     * context on both sides to form a natural switchback.
     */
    private static List<BlockPos> repairExcessiveRiseWindows(List<BlockPos> primaryPath,
                                                              PathTerrainField terrain,
                                                              PathfindingCostConfig config) {
        List<BlockPos> working = new ArrayList<>(primaryPath);
        int step = Math.max(1, config.effectiveAStarStep());
        int contextSteps = Math.max(4, (int) Math.ceil(LOCAL_CONTEXT_BLOCKS / (double) step));
        int repaired = 0;
        while (repaired < MAX_LOCAL_REPAIRS) {
            SteepWindow window = findExcessiveRiseWindow(working, terrain);
            if (window == null) break;
            int startIndex = Math.max(0, window.start() - contextSteps);
            int endIndex = Math.min(working.size() - 1, window.end() + contextSteps);
            if (endIndex - startIndex < 8) break;
            BlockPos localStart = working.get(startIndex);
            BlockPos localEnd = working.get(endIndex);
            SearchResult local = findContourRoute(localStart, localEnd, terrain, config,
                    LOCAL_MAX_EXPANDED_STATES, LOCAL_MAX_SEARCH_NANOS);
            if (local.path() == null || local.path().size() < 2) break;
            List<BlockPos> originalSlice = new ArrayList<>(working.subList(startIndex, endIndex + 1));
            GradeStats originalGrade = measureGrade(originalSlice, terrain);
            GradeStats candidateGrade = measureGrade(local.path(), terrain);
            if (candidateGrade.length() > originalGrade.length() * 2.8D
                    || candidateGrade.maxGrade() > MAX_BUILDABLE_SURFACE_GRADE
                    || candidateGrade.maxGrade() > originalGrade.maxGrade() - ACCEPTED_MAX_GRADE_IMPROVEMENT) {
                break;
            }
            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - originalSlice.size() + local.path().size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(local.path());
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            LOGGER.info("【局部山地盘山折返已接受】 " + "Accepted local mountain switchback: rise={} over {} blocks, maxGrade={} -> {}, length={} -> {}, expanded={}",
                    window.rise(), format(window.length()), format(originalGrade.maxGrade()),
                    format(candidateGrade.maxGrade()), format(originalGrade.length()),
                    format(candidateGrade.length()), local.expanded());
        }
        return List.copyOf(working);
    }

    private static SteepWindow findExcessiveRiseWindow(List<BlockPos> path, PathTerrainField terrain) {
        if (path == null || path.size() < 3) return null;
        for (int start = 0; start < path.size() - 2; start++) {
            double length = 0.0D;
            for (int end = start + 1; end < path.size(); end++) {
                BlockPos previous = path.get(end - 1);
                BlockPos current = path.get(end);
                length += horizontalDistance(previous.getX(), previous.getZ(), current.getX(), current.getZ());
                if (length + 0.001D < LOCAL_RISE_WINDOW_BLOCKS) continue;
                int rise = Math.abs(terrain.height(current.getX(), current.getZ())
                        - terrain.height(path.get(start).getX(), path.get(start).getZ()));
                if (rise > LOCAL_RISE_TRIGGER_BLOCKS) {
                    return new SteepWindow(start, end, rise, length);
                }
                break;
            }
        }
        return null;
    }

    private static SearchResult findContourRoute(BlockPos start, BlockPos goal,
                                                  PathTerrainField terrain,
                                                  PathfindingCostConfig config) {
        return findContourRoute(start, goal, terrain, config,
                MAX_EXPANDED_STATES, MAX_SEARCH_NANOS);
    }

    private static SearchResult findContourRoute(BlockPos start, BlockPos goal,
                                                  PathTerrainField terrain,
                                                  PathfindingCostConfig config,
                                                  int maxExpandedStates,
                                                  long maxSearchNanos) {
        int step = Math.max(1, config.effectiveAStarStep());
        double direct = horizontalDistance(start.getX(), start.getZ(), goal.getX(), goal.getZ());
        if (direct < step * 8.0D || !terrain.contains(start.getX(), start.getZ())
                || !terrain.contains(goal.getX(), goal.getZ())) {
            return SearchResult.empty("route too short or outside terrain");
        }
        Corridor corridor = Corridor.forRoute(start, goal, step, direct);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<StateKey, Double> best = new HashMap<>();
        Node first = new Node(start.getX(), start.getZ(), -1, 0.0D,
                heuristic(start.getX(), start.getZ(), goal.getX(), goal.getZ(), config), null);
        open.add(first);
        best.put(first.key(), 0.0D);
        long started = System.nanoTime();
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxExpandedStates) {
            if ((expanded & 255) == 0) {
                if (Thread.currentThread().isInterrupted()) return SearchResult.empty("interrupted");
                if (System.nanoTime() - started > maxSearchNanos) return SearchResult.empty("time limit");
            }
            Node current = open.poll();
            Double known = best.get(current.key());
            if (known == null || current.cost() > known + 0.0001D) continue;
            if (current.x() == goal.getX() && current.z() == goal.getZ()) {
                return new SearchResult(reconstruct(current, terrain), expanded, "accepted");
            }
            expanded++;
            int currentHeight = terrain.height(current.x(), current.z());
            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                int nextX = current.x() + DIRECTIONS[direction][0] * step;
                int nextZ = current.z() + DIRECTIONS[direction][1] * step;
                if (!corridor.contains(nextX, nextZ) || !terrain.contains(nextX, nextZ)) continue;
                int nextHeight = terrain.height(nextX, nextZ);
                BlockPos nextPos = new BlockPos(nextX, nextHeight, nextZ);
                if (StructureAvoidanceService.shouldAvoidPlanning(nextPos)
                        || PathfindingHelper.edgeIntersectsPlanningObstacle(
                        new BlockPos(current.x(), currentHeight, current.z()), nextPos,
                        planningRoadRadius(), step)) continue;
                double nextCost = current.cost() + movementCost(current, nextX, nextZ,
                        currentHeight, nextHeight, direction, start, goal, terrain, config, step);
                StateKey key = new StateKey(nextX, nextZ, direction);
                Double previous = best.get(key);
                if (previous != null && previous <= nextCost) continue;
                Node next = new Node(nextX, nextZ, direction, nextCost,
                        nextCost + heuristic(nextX, nextZ, goal.getX(), goal.getZ(), config), current);
                best.put(key, nextCost);
                open.add(next);
            }
        }
        return SearchResult.empty(expanded >= maxExpandedStates ? "state limit" : "no route");
    }

    private static double movementCost(Node current, int nextX, int nextZ,
                                       int currentHeight, int nextHeight, int direction,
                                       BlockPos start, BlockPos goal, PathTerrainField terrain,
                                       PathfindingCostConfig config, int step) {
        int dx = DIRECTIONS[direction][0] * step;
        int dz = DIRECTIONS[direction][1] * step;
        double horizontal = Math.max(1.0D, Math.hypot(dx, dz));
        int vertical = Math.abs(nextHeight - currentHeight);
        double grade = vertical / horizontal;
        double movement = Math.abs(dx) + Math.abs(dz) == step * 2
                ? config.diagStepCost() : config.orthoStepCost();
        double stability = PathfindingHelper.calculateTerrainStability(
                terrain, new BlockPos(current.x(), currentHeight, current.z()), currentHeight, step)
                * (double) config.stabilityWeight();
        double water = terrain.isColumnWater(nextX, nextZ)
                ? 2_400.0D + terrain.waterDepth(nextX, nextZ) * Math.max(1.0D, config.waterDepthWeight())
                : (terrain.isNearWater(nextX, nextZ, step) ? 160.0D + config.nearWaterCost() : 0.0D);
        double deviation = distanceFromLine(nextX, nextZ, start, goal)
                * config.deviationWeight() / Math.max(1, step);
        double turn = current.direction() < 0 ? 0.0D : turnPenalty(current.direction(), direction);
        // 0.9.030 / Z-2：在既有 turnPenalty 之上叠加「航向连续性」。
        // 上一步方向 = current.direction()，本步方向 = direction；
        // 横向速度符号翻转（左-右-左）= 换向，同号 = 同侧续弯（盘山长弧）。
        if (current.direction() >= 0 && config.headingFlipCost() > 0.0D) {
            double prevDx = DIRECTIONS[current.direction()][0] * step;
            double prevDz = DIRECTIONS[current.direction()][1] * step;
            // 上一步的横向速度：本步相对它的偏向
            double cross = net.shiroha233.roadweaver.pathfinding.impl.HeadingContinuityCost
                    .cross(prevDx, prevDz, (double) dx, (double) dz);
            // 上上一步的横向速度：上一步相对它的偏向（沿 Node 链回溯一层）
            double prevCross = 0.0D;
            Node parent = current.parent();
            if (parent != null && parent.direction() >= 0) {
                double pdx = DIRECTIONS[parent.direction()][0] * step;
                double pdz = DIRECTIONS[parent.direction()][1] * step;
                prevCross = net.shiroha233.roadweaver.pathfinding.impl.HeadingContinuityCost
                        .cross(pdx, pdz, prevDx, prevDz);
            }
            turn += net.shiroha233.roadweaver.pathfinding.impl.HeadingContinuityCost
                    .cost(prevCross, cross, (double) step,
                            config.headingFlipCost(), config.headingCurveCost(),
                            config.headingShortLegBlocks(), config.headingShortLegMultiplier());
        }
        double gradePenalty = grade <= 0.16D ? 0.0D : Math.pow((grade - 0.16D) * 10.0D, 2.0D) * 70.0D;
        return movement + vertical * (double) config.elevationWeight() + stability
                + water + deviation + turn + gradePenalty;
    }

    private static List<BlockPos> reconstruct(Node goal, PathTerrainField terrain) {
        List<BlockPos> result = new ArrayList<>();
        for (Node node = goal; node != null; node = node.parent()) {
            result.add(new BlockPos(node.x(), terrain.height(node.x(), node.z()), node.z()));
        }
        Collections.reverse(result);
        return result;
    }

    private static GradeStats measureGrade(List<BlockPos> path, PathTerrainField terrain) {
        double max = 0.0D;
        double length = 0.0D;
        int steep = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            double horizontal = horizontalDistance(previous.getX(), previous.getZ(), current.getX(), current.getZ());
            if (horizontal < 0.001D) continue;
            double grade = Math.abs(terrain.height(current.getX(), current.getZ())
                    - terrain.height(previous.getX(), previous.getZ())) / horizontal;
            max = Math.max(max, grade);
            if (grade >= SUSTAINED_GRADE_TRIGGER) steep++;
            length += horizontal;
        }
        return new GradeStats(max, steep, length);
    }

    private static double heuristic(int x, int z, int goalX, int goalZ, PathfindingCostConfig config) {
        int dx = Math.abs(goalX - x), dz = Math.abs(goalZ - z);
        return (dx + dz - 0.6D * Math.min(dx, dz)) * config.heuristicWeight();
    }

    private static double turnPenalty(int previous, int next) {
        double denominator = Math.hypot(DIRECTIONS[previous][0], DIRECTIONS[previous][1])
                * Math.hypot(DIRECTIONS[next][0], DIRECTIONS[next][1]);
        double cosine = (DIRECTIONS[previous][0] * DIRECTIONS[next][0]
                + DIRECTIONS[previous][1] * DIRECTIONS[next][1]) / denominator;
        return (1.0D - Math.max(-1.0D, Math.min(1.0D, cosine))) * 3.5D;
    }

    private static double dominantTerrainMultiplier(List<BlockPos> path,
                                                     PathTerrainField terrain) {
        if (path == null || terrain == null) return 1.0D;
        Map<RoadTerrainType, Integer> counts = RoadTerrainClassifier.classifyRoute(path, terrain);
        RoadTerrainType dominant = RoadTerrainType.PLAIN;
        int best = -1;
        for (Map.Entry<RoadTerrainType, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                dominant = entry.getKey();
            }
        }
        var profile = ConfigService.get().terrainProfile();
        return switch (dominant) {
            case MOUNTAIN -> profile.mountainDetourMultiplier();
            case COAST -> profile.coastDetourMultiplier();
            case RIVER -> profile.riverDetourMultiplier();
            case LAKE -> profile.lakeDetourMultiplier();
            case OCEAN -> profile.oceanDetourMultiplier();
            case PLAIN -> 1.0D;
        };
    }

    private static double switchbackStrength() {
        try {
            return Math.max(0.0D, Math.min(1.0D,
                    ConfigService.get().planning().switchbackStrength() / 100.0D));
        } catch (Throwable ignored) {
            return 0.4D;
        }
    }

    /** 盘山展开预算沿用三维高度配置，避免新增无效独立旋钮。 */
    private static int coarseStateLimit() {
        try {
            return Math.max(50_000, Math.min(1_000_000,
                    ConfigService.get().planning().heightAwareStateLimit()));
        } catch (Throwable ignored) {
            return 260_000;
        }
    }

    /** 墙钟预算随状态上限增长且有封顶，避免增加状态预算却始终被固定时限截断；不代表实际耗时保证。
 *
     * Scales the wall-clock budget with the expand budget: ≈1.56 s at the default 260k states, and
     * up to 6 s when {@code heightAwareStateLimit} is raised to its 1M ceiling.
     *
     * <p>The per-state factor is 6000 nanoseconds, not 6. At 6 the product was 1.56 <em>milli</em>seconds
     * at the default and 6 ms even at the 1M ceiling, so {@code max(1.5s, …)} always picked the
     * constant: raising {@code heightAwareStateLimit} bought more expand states but never more
     * wall-clock to spend them in, and the knob did nothing. A bigger budget here is slower and
     * better - the planner gets to finish the switchback it was in the middle of instead of
     * settling for the first path it happened to have expanded when the clock ran out.
     */
    private static long coarseTimeBudgetNanos() {
        return Math.max(1_500_000_000L, coarseStateLimit() * 6_000L);
    }

    private static double distanceFromLine(int x, int z, BlockPos start, BlockPos goal) {
        double dx = goal.getX() - start.getX(), dz = goal.getZ() - start.getZ();
        double length = Math.hypot(dx, dz);
        return length < 0.001D ? 0.0D
                : Math.abs((x - start.getX()) * dz - (z - start.getZ()) * dx) / length;
    }

    private static double horizontalDistance(int x1, int z1, int x2, int z2) {
        return Math.hypot(x2 - x1, z2 - z1);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record StateKey(int x, int z, int direction) {}
    private record Node(int x, int z, int direction, double cost, double score, Node parent) {
        StateKey key() { return new StateKey(x, z, direction); }
    }
    private record SearchResult(List<BlockPos> path, int expanded, String reason) {
        static SearchResult empty(String reason) { return new SearchResult(null, 0, reason); }
    }
    private record SteepWindow(int start, int end, int rise, double length) {}
    private record GradeStats(double maxGrade, int steepSteps, double length) {
        boolean requiresRetry() { return maxGrade >= STEEP_GRADE_TRIGGER || steepSteps >= MIN_SUSTAINED_STEPS; }
    }
    private record Corridor(int startX, int startZ, int goalX, int goalZ,
                            double directLength, double lateralLimit, double endpointPadding) {
        static Corridor forRoute(BlockPos start, BlockPos goal, int step, double direct) {
            double lateral = Math.min(384.0D, Math.max(step * 18.0D, direct * 0.45D));
            double padding = Math.max(step * 6.0D, Math.min(112.0D, direct * 0.18D));
            return new Corridor(start.getX(), start.getZ(), goal.getX(), goal.getZ(), direct, lateral, padding);
        }
        boolean contains(int x, int z) {
            double dx = goalX - startX, dz = goalZ - startZ;
            double fromX = x - startX, fromZ = z - startZ;
            double projection = fromX * dx + fromZ * dz;
            if (projection < -endpointPadding * directLength
                    || projection > directLength * directLength + endpointPadding * directLength) return false;
            return Math.abs(fromX * dz - fromZ * dx) / directLength <= lateralLimit;
        }
    }
}
