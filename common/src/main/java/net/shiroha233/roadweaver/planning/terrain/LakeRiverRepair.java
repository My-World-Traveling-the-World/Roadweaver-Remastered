/* 文件职责：湖与河的相交修复 —— 与结构修复同一套回退纪律，但判据不同。 */
package net.shiroha233.roadweaver.planning.terrain;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;

// 仍留在其它同包类里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.Bounds.key;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;

/**
 * 湖 / 河相交修复。
 *
 * <h2>为什么与结构修复分开</h2>
 * <p>结构是<b>硬的</b>（建筑就在那儿），湖和河是<b>软的</b> —— 路可以从浅水过、
 * 可以沿河走一段，只要不切进湖心或横穿主河道。所以判据不是「碰到就不行」，
 * 而是**碰到了之后看具体形态**。</p>
 *
 * <h2>与结构修复共用同一条纪律</h2>
 * <p>修不动就原样退回，不返回半成品。理由同结构修复：
 * 改坏的几何没人看得出来，留着已知的冲突反而安全。</p>
 *
 * <p>本类是从 2193 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class LakeRiverRepair {
    public static List<BlockPos> repairLakeIntersections(List<BlockPos> accuratePath,
                                                          ServerLevel level,
                                                          AccurateHeightSampler sampler,
                                                          PathfindingCostConfig config,
                                                          StructureConnection connection,
                                                          StreamsReflowingCompat.SamplingSession streams,
                                                          int roadWidth,
                                                          int localDetourBlocks) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null
                || streams == null || !streams.isAvailable()) return accuratePath;

        Map<Long, Boolean> lakeCache = new HashMap<>();
        LakeLookup lakes = (x, z) -> lakeCache.computeIfAbsent(
                key(x, z), ignored -> streams.sampleLake(x, z).present());
        Map<Long, Boolean> lakeClearanceCache = new HashMap<>();
        LakeLookup lakeClearance = expandedLakeLookup(
                lakes, Math.max(1, roadWidth), LAKE_REROUTE_CLEARANCE_BLOCKS, lakeClearanceCache);
        HeightLookup terrain = sampler::surfaceHeight;
        int width = Math.max(1, roadWidth);
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        while (repaired < MAX_LAKE_REPAIRS) {
            Violation conflict = findFirstLakeConstraintConflict(working, width, lakes, terrain);
            if (conflict == null) break;

            int startIndex = walkContextStart(working, conflict.start(), LAKE_CONTEXT_BLOCKS);
            int endIndex = walkContextEnd(working, conflict.end(), LAKE_CONTEXT_BLOCKS);
            if (endIndex - startIndex < 2) break;

            List<BlockPos> original = new ArrayList<>(working.subList(startIndex, endIndex + 1));
            List<BlockPos> candidate;
            boolean accepted;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
                candidate = findLocalRoute(original, sampler, config,
                        Math.max(config.effectiveAStarStep(), localDetourBlocks),
                        (from, to) -> edgeIntersectsMask(from, to, lakeClearance));
                accepted = isLakeRepairAccepted(
                        original, candidate, width, lakes, terrain, lakeClearance);
            }
            BlockPos collision = working.get(Math.max(0, Math.min(conflict.start(), working.size() - 1)));
            if (!accepted) {
                logLocalSearchDiag("lake-no-bypass");
                LOGGER.warn("【湖泊局部绕行未避开冲突】 " + "RoadWeaver lake repair could not reroute local conflict detour={} "
                                + "conflictEdge={}..{} pos={} windowNodes={}",
                        localDetourBlocks, conflict.start(), conflict.end(), collision, original.size());
                break;
            }

            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            LOGGER.info("【湖泊局部绕行已接受】 " + "Accepted local RoadWeaver lake reroute: detour={} clearance={} conflictEdge={}..{} "
                            + "pos={} nodes={} -> {}",
                    localDetourBlocks, LAKE_REROUTE_CLEARANCE_BLOCKS,
                    conflict.start(), conflict.end(), collision,
                    original.size(), candidate.size());
        }
        return List.copyOf(working);
    }

    /** 仅在物理跨越无法保留时局部绕河；使用道路宽度与有界窗口，不重搜全端点。
 *
     * Repairs only river-channel conflicts.  A bridge/crossing span is still
     * preferred by the normal span extractor; this pass is the bounded
     * fallback when that physical crossing cannot be retained.  It deliberately
     * uses the road footprint width and a 50-block local window, never a new
     * endpoint-to-endpoint search.
     */
    public static List<BlockPos> repairRiverIntersections(List<BlockPos> accuratePath,
                                                           ServerLevel level,
                                                           AccurateHeightSampler sampler,
                                                           PathfindingCostConfig config,
                                                           StructureConnection connection,
                                                           StreamsReflowingCompat.SamplingSession streams,
                                                           int roadWidth,
                                                           int localDetourBlocks) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null
                || streams == null || !streams.isAvailable()) return accuratePath;

        Map<Long, Boolean> channelCache = new HashMap<>();
        RiverLookup channels = (x, z) -> channelCache.computeIfAbsent(
                key(x, z), ignored -> {
                    StreamsReflowingCompat.RiverColumn column = streams.sample(x, z);
                    return column.isUnknown() || column.inChannel();
                });
        int width = Math.max(1, roadWidth);
        int margin = Math.max(1, localDetourBlocks);
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        while (repaired < MAX_RIVER_REPAIRS) {
            Violation conflict = findFirstRiverConflict(working, width, channels);
            if (conflict == null) return List.copyOf(working);

            int startIndex = walkContextStart(working, conflict.start(), margin);
            int endIndex = walkContextEnd(working, conflict.end(), margin);
            // Merge nearby river wet runs into one repair window: a route that
            // crosses the same channel several times must be rerouted as one
            // bounded detour, not given up segment by segment.
            endIndex = extendWindowAcrossNearbyWetRuns(working, endIndex, 1, channels, margin);
            startIndex = extendWindowAcrossNearbyWetRuns(working, startIndex, -1, channels, margin);
            if (endIndex - startIndex < 2) break;
            // 0.8.316：先判"必然无解"。修复的判据是候选路径不得触碰任何河道列，
            // 因此只有"河道在窗口内能绕过去"才可能成功；河道把窗口一分为二时（窗口
            // 边界上出现 ≥2 段河道，或整圈都是水）任何候选都必然被拒。真机近 4 局
            // 30 次尝试 0 成功，每次都要先付一遍 step=1 的窗口 A*。这里先采样窗口
            // 边界判定，命中就跳过，只留一行 info。
            if (riverRepairBypassPrecheck()) {
                Bounds window = Bounds.around(working.subList(startIndex, endIndex + 1), margin, 1, null);
                if (channelSeparatesWindow(window, channels)) {
                    logLocalSearchDiag("river-skipped");
                    LOGGER.info("【河道横穿整个局部窗口，没有旁路，跳过河流绕行】 " + "RoadWeaver river local repair skipped: channel crosses the whole window "
                                    + "(no bypass possible) conflictEdge={}..{} window=[{},{} -> {},{}] "
                                    + "connection={}->{}",
                            conflict.start(), conflict.end(),
                            window.minX(), window.minZ(), window.maxX(), window.maxZ(),
                            connection.from(), connection.to());
                    return List.copyOf(working);
                }
            }
            List<BlockPos> original = new ArrayList<>(working.subList(startIndex, endIndex + 1));
            PathfindingCostConfig fine = config.withAStarStep(1);
            List<BlockPos> candidate;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
                candidate = findLocalRoute(original, sampler, fine, margin,
                        (from, to) -> edgeIntersectsRiver(from, to, width, channels),
                        1, false, 0, null);
            }
            if (!isRiverRepairAccepted(original, candidate, width, channels)) break;

            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            logLocalSearchDiag("river-accepted");
            LOGGER.info("【河流局部绕行已接受】 " + "Accepted local RoadWeaver river detour: window={} conflictEdge={}..{} nodes={} -> {}",
                    margin, conflict.start(), conflict.end(), original.size(), candidate.size());
        }
        return List.copyOf(working);
    }
}
