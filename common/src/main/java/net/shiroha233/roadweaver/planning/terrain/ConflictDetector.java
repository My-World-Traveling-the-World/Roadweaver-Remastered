/* 文件职责：路与结构/湖/河的冲突检测 —— 只回答「哪里冲突、冲突多重」。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;

// 仍留在 AccurateGradeRepairPlanner 里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.planningCollisionBox;

/**
 * 冲突检测：结构 / 湖 / 河。
 *
 * <h2>它只做检测，不修</h2>
 * <p>返回 {@code Violation}（起止站、落差、长度），不修改路径。修是
 * {@code LocalRouteSearch} 与主类的事。这条分工让「判据」可以独立加单测 ——
 * 检测函数是纯的，喂一条假路径就能断言它报不报。</p>
 *
 * <h2>三类冲突的口径不同</h2>
 * <ul>
 *   <li><b>结构</b> —— 看包围盒，且允许「端点豁免」的窗口（{@code structureClusterWindowAllows}）；</li>
 *   <li><b>湖</b> —— 分「约束冲突」（硬边界）与「净空冲突」（太贴岸）两种；</li>
 *   <li><b>河</b> —— 沿边逐段判，且有一个 <b>通道分隔</b>的例外
 *       （{@code channelSeparatesWindow}：河把窗口两侧分开了就不算真的穿越）。</li>
 * </ul>
 *
 * <h2>★ 为什么边界检测要「扩展查找表」</h2>
 * <p>{@code expandedLakeLookup} 把湖的范围向外扩一格再判。原因：路面有宽度，
 * 中心线没碰到湖、但路面边缘会切进岸线。**只判中心线会漏掉贴岸的切边**。</p>
 *
 * <p>本类是从 2720 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 判据、阈值与例外条件一字未改（含缩进）。</p>
 */
final class ConflictDetector {
    static Violation findFirstLakeConstraintConflict(List<BlockPos> path,
                                                     int roadWidth,
                                                     LakeLookup lakes,
                                                     HeightLookup terrain) {
        Violation lake = findFirstLakeConflict(path, roadWidth, lakes);
        Violation shore = findFirstUnstableLakeShoreConflict(path, roadWidth, lakes, terrain);
        if (lake == null) return shore;
        if (shore == null) return lake;
        return lake.start() <= shore.start() ? lake : shore;
    }

    static Violation findFirstLakeClearanceConflict(List<BlockPos> path,
                                                     int roadWidth,
                                                     int clearanceBlocks,
                                                     LakeLookup lakes) {
        if (path == null || path.isEmpty() || lakes == null) return null;
        LakeLookup expanded = expandedLakeLookup(
                lakes, Math.max(1, roadWidth), Math.max(0, clearanceBlocks), new HashMap<>());
        return findFirstMaskConflict(path, expanded);
    }

    static Violation findFirstStructureConflict(List<BlockPos> path) {
        return findFirstStructureConflict(path, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    static Violation findFirstStructureConflict(List<BlockPos> path, int structureClearance) {
        if (path == null || path.isEmpty()) return null;
        int clearance = Math.max(0, structureClearance);
        if (StructureAvoidanceService.shouldAvoidPlanning(path.get(0), clearance)) {
            return new Violation(0, Math.min(1, path.size() - 1), 0, 0.0D);
        }
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (edgeIntersectsProtected(previous, current, clearance)) {
                return new Violation(i - 1, i, Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    /**
     * Nearby real-building collisions are repaired as one local cluster. This
     * prevents a successful detour around one building from being immediately
     * pulled back into another building a few blocks later.
     */
    static Violation expandNearbyStructureCluster(List<BlockPos> path,
                                                           Violation first,
                                                           int structureClearance,
                                                           int localDetourBlocks) {
        if (path == null || first == null || path.size() < 2) return first;
        int start = Math.max(0, first.start());
        int end = Math.min(path.size() - 1, first.end());
        BoundingBox cluster = null;
        for (int index = start; index <= end; index++) {
            BoundingBox box = planningCollisionBox(
                    path.get(index), Math.max(0, structureClearance));
            if (box != null) cluster = cluster == null ? box : cluster.encapsulate(box);
        }
        if (cluster == null) return first;
        // Keep structure clustering tied to the physical road footprint, not
        // to the independent terrain sampling radius.
        int bodyRadius = roadBodyRadius();
        cluster = cluster.inflatedBy(Math.max(1, bodyRadius));
        boolean changed;
        do {
            changed = false;
            for (int index = start; index < path.size(); index++) {
                BlockPos point = path.get(index);
                BoundingBox box = point == null ? null
                        : planningCollisionBox(point, Math.max(0, structureClearance));
                // A path point being inside the current search rectangle is
                // not evidence of another building. Extend only for a real
                // collision box whose expanded footprint touches this cluster.
                boolean joinsCluster = box != null && cluster.intersects(
                        box.inflatedBy(Math.max(1, bodyRadius)));
                if (joinsCluster && index > end) {
                    // Buildings can form a long daisy chain when each 50-block
                    // search margin overlaps the next. Do not merge that into
                    // one giant A* request. The next repair loop will handle
                    // the remaining collision with its own local endpoints.
                    if (!structureClusterWindowAllows(path, start, index)) {
                        continue;
                    }
                    end = index;
                    cluster = cluster.encapsulate(box).inflatedBy(Math.max(1, bodyRadius));
                    changed = true;
                }
            }
        } while (changed);
        if (end == first.end()) return first;
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                routeHorizontalLength(path, start, end));
    }

    static double routeHorizontalLength(List<BlockPos> path, int from, int to) {
        double distance = 0.0D;
        for (int i = Math.max(1, from + 1); i <= to && i < path.size(); i++) {
            distance += horizontalDistance(path.get(i - 1), path.get(i));
        }
        return distance;
    }

    /**
     * Produces a small ordered set of endpoint-preserving windows for one
     * structure conflict. The first window is the normal local context; the
     * others are tighter alternatives for conflicts close to an endpoint or
     * where grid snapping leaves the first entry/exit unusable. None of these
     * windows can grow beyond the caller's local repair radius.
     */
    static List<RepairSlice> structureRepairSlices(List<BlockPos> path,
                                                             Violation conflict,
                                                             BlockPos center,
                                                             int contextBlocks) {
        if (path == null || path.size() < 3 || conflict == null) return List.of();
        int context = Math.max(1, contextBlocks);
        Map<Long, RepairSlice> unique = new LinkedHashMap<>();
        addRepairSlice(unique, path, conflict.start(), conflict.end(), context, center);
        addRepairSlice(unique, path, conflict.start(), conflict.end(), Math.max(8, context / 2), center);
        addRepairSlice(unique, path, conflict.start(), conflict.end(), 8, center);

        // If the conflict is near one end of the route, retain the route's
        // endpoint as an anchor but move the opposite anchor inward. This is
        // the useful "line enters the circle, detour, line exits the circle"
        // case and avoids rejecting a valid bypass as no-interior-entry/exit.
        if (center != null) {
            int entry = nearestIndexBefore(path, conflict.start(), center, context);
            int exit = nearestIndexAfter(path, conflict.end(), center, context);
            if (entry >= 0 && exit > entry) {
                addRepairSlice(unique, path, entry, exit, 0, center);
            }
        }
        return List.copyOf(unique.values());
    }

    static void addRepairSlice(Map<Long, RepairSlice> unique,
                                       List<BlockPos> path,
                                       int conflictStart,
                                       int conflictEnd,
                                       int context,
                                       BlockPos center) {
        int start = context <= 0
                ? Math.max(0, conflictStart)
                : walkContextStart(path, conflictStart, context);
        int end = context <= 0
                ? Math.min(path.size() - 1, conflictEnd)
                : walkContextEnd(path, conflictEnd, context);
        if (end - start < 2) return;
        List<BlockPos> slice = new ArrayList<>(path.subList(start, end + 1));
        long key = (((long) start) << 32) ^ (end & 0xffffffffL);
        unique.putIfAbsent(key, new RepairSlice(start, end, slice, center));
    }

    static int nearestIndexBefore(List<BlockPos> path, int from,
                                          BlockPos center, int maxDistance) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = Math.max(0, from - 1); i >= 0; i--) {
            double distance = horizontalDistance(path.get(i), center);
            if (distance > maxDistance) {
                if (best >= 0) break;
                // The path normally moves monotonically away from a local
                // conflict. Do not jump across an unrelated far-away route
                // section looking for another point in the circle.
                break;
            }
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    static int nearestIndexAfter(List<BlockPos> path, int from,
                                         BlockPos center, int maxDistance) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = Math.min(path.size() - 1, from + 1); i < path.size(); i++) {
            double distance = horizontalDistance(path.get(i), center);
            if (distance > maxDistance) {
                if (best >= 0) break;
                break;
            }
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    static boolean sameHorizontalPath(List<BlockPos> first, List<BlockPos> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            BlockPos a = first.get(i);
            BlockPos b = second.get(i);
            if (a == null || b == null || a.getX() != b.getX() || a.getZ() != b.getZ()) return false;
        }
        return true;
    }

    static boolean sameFullPath(List<BlockPos> first, List<BlockPos> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).equals(second.get(i))) return false;
        }
        return true;
    }

    static boolean structureClusterWindowAllows(List<BlockPos> path, int start, int candidateEnd) {
        return routeHorizontalLength(path, start, candidateEnd)
                <= MAX_STRUCTURE_CLUSTER_SPAN_BLOCKS;
    }

    static Violation findFirstLakeConflict(List<BlockPos> path,
                                           int roadWidth,
                                           LakeLookup lakes) {
        if (path == null || path.isEmpty() || lakes == null) return null;
        boolean[] conflicts = new boolean[Math.max(1, path.size())];
        conflicts[0] = pointIntersectsLake(path.get(0), roadWidth, lakes);
        for (int index = 1; index < path.size(); index++) {
            conflicts[index] = edgeIntersectsLake(
                    path.get(index - 1), path.get(index), roadWidth, lakes);
        }
        int first = -1;
        for (int index = 0; index < conflicts.length; index++) {
            if (conflicts[index]) {
                first = index;
                break;
            }
        }
        if (first < 0) return null;
        int last = first;
        for (int index = first + 1; index < conflicts.length && conflicts[index]; index++) {
            last = index;
        }
        int start = Math.max(0, first - 1);
        int end = Math.min(path.size() - 1, Math.max(first, last));
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                horizontalDistance(path.get(start), path.get(end)));
    }

    static Violation findFirstRiverConflict(List<BlockPos> path,
                                            int roadWidth,
                                            RiverLookup channels) {
        if (path == null || path.isEmpty() || channels == null) return null;
        boolean[] conflicts = new boolean[Math.max(1, path.size())];
        conflicts[0] = pointIntersectsRiver(path.get(0), roadWidth, channels);
        for (int index = 1; index < path.size(); index++) {
            conflicts[index] = edgeIntersectsRiver(
                    path.get(index - 1), path.get(index), roadWidth, channels);
        }
        int first = -1;
        for (int index = 0; index < conflicts.length; index++) {
            if (conflicts[index]) { first = index; break; }
        }
        if (first < 0) return null;
        int last = first;
        for (int index = first + 1; index < conflicts.length && conflicts[index]; index++) last = index;
        int start = Math.max(0, first - 1);
        int end = Math.min(path.size() - 1, Math.max(first, last));
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                horizontalDistance(path.get(start), path.get(end)));
    }

    static boolean isRiverRepairAccepted(List<BlockPos> original,
                                                  List<BlockPos> candidate,
                                                  int roadWidth,
                                                  RiverLookup channels) {
        if (candidate == null || candidate.size() < 2
                || findFirstViolation(candidate) != null
                || findFirstRiverConflict(candidate, roadWidth, channels) != null) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 200.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    static boolean edgeIntersectsRiver(BlockPos from,
                                                BlockPos to,
                                                int roadWidth,
                                                RiverLookup channels) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (channels.isChannel(x, z)) return true;
            }
        }
        return false;
    }

    static boolean pointIntersectsRiver(BlockPos point,
                                                 int roadWidth,
                                                 RiverLookup channels) {
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (channels.isChannel(point.getX() + dx, point.getZ() + dz)) return true;
            }
        }
        return false;
    }

    static Violation findFirstUnstableLakeShoreConflict(List<BlockPos> path,
                                                                 int roadWidth,
                                                                 LakeLookup lakes,
                                                                 HeightLookup terrain) {
        if (path == null || path.size() < 2 || lakes == null || terrain == null) return null;
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            if (edgeIntersectsUnstableLakeShore(previous, current, roadWidth, lakes, terrain)) {
                return new Violation(index - 1, index,
                        Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    static boolean edgeIntersectsLake(BlockPos from,
                                              BlockPos to,
                                              int roadWidth,
                                              LakeLookup lakes) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (lakes.isLake(x, z)) return true;
            }
        }
        return false;
    }

    static Violation findFirstMaskConflict(List<BlockPos> path, LakeLookup mask) {
        if (path == null || path.isEmpty() || mask == null) return null;
        if (mask.isLake(path.getFirst().getX(), path.getFirst().getZ())) {
            return new Violation(0, 0, 0, 0.0D);
        }
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            if (edgeIntersectsMask(previous, current, mask)) {
                return new Violation(index - 1, index,
                        Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    static boolean edgeIntersectsMask(BlockPos from,
                                              BlockPos to,
                                              LakeLookup mask) {
        double horizontal = horizontalDistance(from, to);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (mask.isLake(x, z)) return true;
        }
        return false;
    }

    static LakeLookup expandedLakeLookup(LakeLookup lakes,
                                                 int roadWidth,
                                                 int clearanceBlocks,
                                                 Map<Long, Boolean> cache) {
        int radius = Math.max(0, roadWidth / 2) + Math.max(0, clearanceBlocks);
        return (x, z) -> cache.computeIfAbsent(key(x, z), ignored -> {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (lakes.isLake(x + dx, z + dz)) return true;
                }
            }
            return false;
        });
    }

    static boolean edgeIntersectsUnstableLakeShore(BlockPos from,
                                                            BlockPos to,
                                                            int roadWidth,
                                                            LakeLookup lakes,
                                                            HeightLookup terrain) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        int shoreProbe = halfWidth + LAKE_SHORE_PROBE_BLOCKS;
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            boolean nearLake = false;
            for (int offset = -shoreProbe; offset <= shoreProbe; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (lakes.isLake(x, z)) {
                    nearLake = true;
                    break;
                }
            }
            if (!nearLake) continue;

            int roadY = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                int terrainY = terrain.height(x, z);
                if (terrainY - roadY > MAX_LAKE_SHORE_CUT_DEPTH
                        || roadY - terrainY > MAX_LAKE_SHORE_FILL_DEPTH) return true;
            }
        }
        return false;
    }

    static boolean pointIntersectsLake(BlockPos point,
                                               int roadWidth,
                                               LakeLookup lakes) {
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (lakes.isLake(point.getX() + dx, point.getZ() + dz)) return true;
            }
        }
        return false;
    }

    static boolean edgeIntersectsProtected(BlockPos from, BlockPos to) {
        return edgeIntersectsProtected(from, to, 0);
    }

    static boolean edgeIntersectsProtected(BlockPos from, BlockPos to, int horizontalClearance) {
        double horizontal = horizontalDistance(from, to);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (StructureAvoidanceService.shouldAvoidPlanning(
                    new BlockPos(x, y, z), Math.max(0, horizontalClearance))) return true;
        }
        return false;
    }

    static int walkContextStart(List<BlockPos> path, int index, double blocks) {
        double distance = 0.0D;
        int result = Math.max(0, index);
        while (result > 0 && distance < blocks) {
            distance += horizontalDistance(path.get(result - 1), path.get(result));
            result--;
        }
        return result;
    }

    /**
     * Extends a river repair window across nearby wet runs (bridging short dry
     * banks between them) so one route crossing the same channel several times
     * is rerouted as a single bounded detour. The window never grows beyond
     * {@code maxStations} past the starting index.
     */
    static int extendWindowAcrossNearbyWetRuns(List<BlockPos> path,
                                                       int from,
                                                       int direction,
                                                       RiverLookup channels,
                                                       int maxStations) {
        int size = path.size();
        int index = Math.max(0, Math.min(size - 1, from));
        int result = index;
        int dryRun = 0;
        for (int step = 1; step <= maxStations; step++) {
            int next = index + direction * step;
            if (next < 0 || next >= size) break;
            BlockPos point = path.get(next);
            if (point == null) break;
            if (channels.isChannel(point.getX(), point.getZ())) {
                result = next;
                dryRun = 0;
                continue;
            }
            dryRun++;
            // A longer dry stretch means the next wet run belongs to a
            // different crossing group; stop absorbing.
            if (dryRun > RIVER_RUN_MERGE_MAX_DRY_STATIONS) break;
        }
        return result;
    }

    static int walkContextEnd(List<BlockPos> path, int index, double blocks) {
        double distance = 0.0D;
        int result = Math.min(path.size() - 1, index);
        while (result + 1 < path.size() && distance < blocks) {
            distance += horizontalDistance(path.get(result), path.get(result + 1));
            result++;
        }
        return result;
    }
}
