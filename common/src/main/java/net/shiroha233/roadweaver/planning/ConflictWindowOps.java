/* 文件职责：冲突采样窗口 —— 找出需要局部重规划的路段并合并相邻窗口。 */
package net.shiroha233.roadweaver.planning;

import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps;
import net.shiroha233.roadweaver.pathlogic.structures.PrepareTiming;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.generation.IdleRoadGenerationService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.planning.impl.KNNPlanner;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.path.SharedEndpointPathReuse;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPipeline;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.network.ServerMapPlanningActivityBridge;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructureIndexService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.util.ComputeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.planning.RoadPlanningService.*;
import static net.shiroha233.roadweaver.planning.AsyncPlanningOps.*;
import static net.shiroha233.roadweaver.planning.PlanningBoundsOps.*;

/**
 * 冲突采样窗口。
 *
 * <h2>它是「局部修复」的上游</h2>
 * <p>整条路重算是最后手段。先找出**冲突集中的小段**（{@code conflictSamplingWindows} /
 * {@code windowForConflictCenter} / {@code pathEdgeConflicts}），
 * 把这些小段作为「采样窗口」交给局部重规划。</p>
 *
 * <h2>★ 相邻窗口必须合并</h2>
 * <p>两个窗口如果只隔几站，分别修会让接缝处出现台阶或断头。
 * {@code mergeNearbyWindows} 把间距小于阈值的窗口并成一个 ——
 * **先合并再修**，这条顺序不能反。</p>
 *
 * <h2>★ 采样边界与窗口边界不是一回事</h2>
 * <p>{@code samplingBoundsForConnection} 给出的是<b>采样范围</b>
 * （要比路径宽一圈才采样得到周边地形），而窗口是<b>要重规划的范围</b>。
 * 混用会导致要么采样不够、要么白算大片。</p>
 *
 * <p>本类是从 2204 行的 {@code RoadPlanningService} 里<b>纯搬迁</b>出来的，
 * 阈值一字未改（含缩进）。</p>
 */
public final class ConflictWindowOps {
    public static List<LocalSamplingWindow> conflictSamplingWindows(ServerLevel level,
                                                                      StructureConnection connection,
                                                                      int padding,
                                                                      PathfindingCostConfig pathConfig) {
        if (level == null || connection == null) return List.of();
        List<LocalSamplingWindow> windows = new ArrayList<>();
        for (BlockPos hintedCenter : consumeProtectedGapHints(connection)) {
            windows.add(windowForConflictCenter(hintedCenter, padding));
        }
        if (!windows.isEmpty()) {
            LOGGER.info("RoadWeaver local refinement using persisted protected-gap hints "
                            + "windows={} radius={} connection={}->{}",
                    windows.size(), padding, connection.from(), connection.to());
        }

        ModConfig config = ConfigService.get();
        List<BlockPos> path = PlannedPathCache.find(
                level, connection,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                pathConfig == null ? config.pathfindingCost() : pathConfig).orElse(List.of());
        if (path.size() < 2) {
            return mergeNearbyWindows(windows, padding);
        }
        int runStart = -1;
        int runEnd = -1;
        // Do not pass the terrain radius as an additional building radius.
        // The snapshot already carries the physical road-width footprint.
        try (StructureAvoidanceService.PlanningScope ignored =
                     StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
            for (int i = 0; i < path.size(); i++) {
                BlockPos point = path.get(i);
                boolean structureConflict = point != null
                        && (StructureAvoidanceService.shouldAvoidPlanning(point)
                        || pathEdgeConflicts(path, i));
                boolean gradeConflict = false;
                if (i > 0 && point != null && path.get(i - 1) != null) {
                    BlockPos previous = path.get(i - 1);
                    double horizontal = Math.hypot(point.getX() - previous.getX(),
                            point.getZ() - previous.getZ());
                    int vertical = Math.abs(point.getY() - previous.getY());
                    // Coarse paths can legitimately climb a few blocks. A
                    // sharp jump is the useful signal for a local height
                    // conflict; it keeps ordinary slopes out of the repair.
                    gradeConflict = vertical >= 4 || (horizontal > 0.0D && vertical > horizontal * 1.5D);
                }
                boolean conflict = structureConflict || gradeConflict;
                if (conflict) {
                    if (runStart < 0) runStart = i;
                    runEnd = i;
                } else if (runStart >= 0) {
                    windows.add(windowForPathRange(path, runStart, runEnd, padding));
                    runStart = -1;
                    runEnd = -1;
                }
            }
        }
        if (runStart >= 0) windows.add(windowForPathRange(path, runStart, runEnd, padding));
        List<LocalSamplingWindow> merged = mergeNearbyWindows(windows, padding);
        if (merged.isEmpty()) {
            // A final accurate construction preflight can discover a deep
            // depression even when the coarse path has no sharp Y jump. The
            // road generator records that exact station; use only that
            // conflict-centered circle instead of silently skipping local
            // repair or rescanning the entire route.
            BlockPos hintedConflict = RoadPathCalculator.consumeConstructionConflictHint(connection);
            if (hintedConflict != null) {
                merged = List.of(windowForConflictCenter(hintedConflict, padding));
                LOGGER.info("RoadWeaver local refinement recovered final construction conflict hint: "
                                + "center={} radius={} connection={}->{}",
                        hintedConflict, padding, connection.from(), connection.to());
            }
        }
        if (merged.isEmpty()) {
            LOGGER.info("RoadWeaver local refinement found no conflict corridor: route={}->{} pathNodes={} "
                            + "action=skip_sampling",
                    connection.from(), connection.to(), path.size());
        }
        return merged;
    }

    public static LocalSamplingWindow windowForConflictCenter(BlockPos center, int padding) {
        int radius = Math.max(1, padding);
        return new LocalSamplingWindow(new int[]{
                saturatingAdd(center.getX(), -radius),
                saturatingAdd(center.getZ(), -radius),
                saturatingAdd(center.getX(), radius),
                saturatingAdd(center.getZ(), radius)}, center, radius);
    }

    /** Checks the swept line between two coarse path nodes for a structure hit. */
    public static boolean pathEdgeConflicts(List<BlockPos> path, int index) {
        if (path == null || index <= 0 || index >= path.size()) return false;
        BlockPos from = path.get(index - 1);
        BlockPos to = path.get(index);
        if (from == null || to == null) return false;
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(
                to.getX() - from.getX(), to.getZ() - from.getZ())));
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            BlockPos probe = new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
            if (StructureAvoidanceService.shouldAvoidPlanning(probe)) return true;
        }
        return false;
    }

    public static LocalSamplingWindow windowForPathRange(List<BlockPos> path, int start, int end, int padding) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = Math.max(0, start); i <= Math.min(end, path.size() - 1); i++) {
            BlockPos point = path.get(i);
            if (point == null) continue;
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }
        if (minX == Integer.MAX_VALUE) return new LocalSamplingWindow(new int[]{0, 0, 0, 0}, null, 0);
        BlockPos first = path.get(Math.max(0, start));
        BlockPos last = path.get(Math.min(end, path.size() - 1));
        BlockPos center = new BlockPos(
                Math.floorDiv(first.getX() + last.getX(), 2),
                Math.floorDiv(first.getY() + last.getY(), 2),
                Math.floorDiv(first.getZ() + last.getZ(), 2));
        return new LocalSamplingWindow(new int[]{
                saturatingAdd(minX, -padding),
                saturatingAdd(minZ, -padding),
                saturatingAdd(maxX, padding),
                saturatingAdd(maxZ, padding)}, center, padding);
    }

    public static List<LocalSamplingWindow> mergeNearbyWindows(List<LocalSamplingWindow> windows, int padding) {
        if (windows == null || windows.isEmpty()) return List.of();
        // Hints from one physical structure commonly contain its start, end,
        // midpoint and adjacent protected columns. Collapse very-near centers;
        // distinct buildings remain separate circles.
        //
        // 0.8.316：合并半径从 padding/4（=25）放大到 localRepairWindowClusterRadiusBlocks
        // （默认 50）。同一条路的相邻冲突中心常相距 30~60 格，圆几乎完全重叠，却各自
        // 跑一遍完整的局部重规划（step=2 圆内 A* + 粗地形预检 + 隧道重路由 + 走廊快照）。
        // 圆内 A* 的节点数由半径决定、与圆里有几个冲突无关，所以合并成更少的圆是
        // "单次成本不变、次数减少"。设 0/负数恢复旧阈值。
        int configuredClusterRadius = 0;
        try {
            configuredClusterRadius = ConfigService.get().planning().localRepairWindowClusterRadiusBlocks();
        } catch (Throwable ignored) {
        }
        int clusterRadius = configuredClusterRadius > 0
                ? configuredClusterRadius
                : Math.max(8, Math.min(32, padding / 4));
        List<LocalSamplingWindow> result = new ArrayList<>();
        for (LocalSamplingWindow current : windows) {
            if (current == null || current.bounds().length < 4) continue;
            boolean merged = false;
            for (int index = 0; index < result.size(); index++) {
                LocalSamplingWindow existing = result.get(index);
                if (existing.center() == null || current.center() == null) continue;
                long dx = (long) existing.center().getX() - current.center().getX();
                long dz = (long) existing.center().getZ() - current.center().getZ();
                if (dx * dx + dz * dz <= (long) clusterRadius * clusterRadius) {
                    BlockPos a = existing.center();
                    BlockPos b = current.center();
                    BlockPos midpoint = new BlockPos(
                            Math.floorDiv(a.getX() + b.getX(), 2),
                            Math.floorDiv(a.getY() + b.getY(), 2),
                            Math.floorDiv(a.getZ() + b.getZ(), 2));
                    result.set(index, windowForConflictCenter(midpoint, padding));
                    merged = true;
                    break;
                }
            }
            if (!merged) result.add(current);
        }
        return List.copyOf(result);
    }

    public static int[] initialSamplingBounds(
            ServerLevel level, List<StructureConnection> connections) {
        if (level == null) return new int[]{0, 0, 0, 0};
        ModConfig config = ConfigService.get();
        int radiusChunks = Math.max(1, config.planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;
        int minX = (cx - radiusChunks) * 16;
        int maxX = (cx + radiusChunks) * 16;
        int minZ = (cz - radiusChunks) * 16;
        int maxZ = (cz + radiusChunks) * 16;
        return boundsIncludingConnections(minX, minZ, maxX, maxZ, connections);
    }

    /** Returns a bounded union rectangle for candidate corridors and detours. */
    public static int[] samplingBoundsForConnections(
            List<StructureConnection> connections, int paddingBlocks) {
        int[] bounds = boundsIncludingConnections(0, 0, 0, 0, connections);
        int padding = Math.max(0, paddingBlocks);
        return new int[]{
                saturatingAdd(bounds[0], -padding),
                saturatingAdd(bounds[1], -padding),
                saturatingAdd(bounds[2], padding),
                saturatingAdd(bounds[3], padding)};
    }

    /** Returns only the endpoint corridor window for one failed route. */
    public static int[] samplingBoundsForConnection(
            StructureConnection connection, int paddingBlocks) {
        if (connection == null) return new int[]{0, 0, 0, 0};
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        if (from != null) {
            minX = Math.min(minX, from.getX());
            minZ = Math.min(minZ, from.getZ());
            maxX = Math.max(maxX, from.getX());
            maxZ = Math.max(maxZ, from.getZ());
        }
        if (to != null) {
            minX = Math.min(minX, to.getX());
            minZ = Math.min(minZ, to.getZ());
            maxX = Math.max(maxX, to.getX());
            maxZ = Math.max(maxZ, to.getZ());
        }
        if (minX == Integer.MAX_VALUE) return new int[]{0, 0, 0, 0};
        int padding = Math.max(0, paddingBlocks);
        return new int[]{
                saturatingAdd(minX, -padding),
                saturatingAdd(minZ, -padding),
                saturatingAdd(maxX, padding),
                saturatingAdd(maxZ, padding)};
    }
}
