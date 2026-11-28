/* 文件职责：修复结果的接受判据 —— 结构 / 湖 / 河三种，口径各不同。 */
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
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;

// 仍留在其它同包类里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.planningRoadRadius;

/**
 * 修复结果的接受判据。
 *
 * <h2>为什么判据要独立成类</h2>
 * <p>「找到了一条新走线」和「这条新走线可以用」是两件事。判据抽出来之后
 * 可以**各自加单测**：喂一条已知形态的路径，断言它该过还是该拒。</p>
 *
 * <h2>三种接受判据</h2>
 * <ul>
 *   <li>{@code isStructureRepairAccepted} —— 结构修复后：冲突真的消失了吗、
 *       绕的长度在允许范围内吗；</li>
 *   <li>{@code isLakeRepairAccepted} —— 湖修复后：还切不切湖、贴不贴岸；</li>
 *   <li>{@code isAccepted} —— 通用入口，按上面的组合判。</li>
 * </ul>
 *
 * <p>本类是从 1631 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 判据一字未改（含缩进）。</p>
 */
final class RepairAcceptance {
    static boolean isAccepted(List<BlockPos> original, List<BlockPos> candidate) {
        if (candidate == null || candidate.size() < 2 || findFirstViolation(candidate) != null) return false;
        return withinDetourLength(original, candidate);
    }

    /** 纵断面候选先约束长度；实采地形坡度不能替代经挖填后的正式道路坡度。 */
    static boolean withinDetourLength(List<BlockPos> original, List<BlockPos> candidate) {
        if (candidate == null || candidate.size() < 2) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 32.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    static boolean isStructureRepairAccepted(List<BlockPos> original, List<BlockPos> candidate) {
        return isStructureRepairAccepted(original, candidate, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    static boolean isStructureRepairAccepted(List<BlockPos> original, List<BlockPos> candidate,
                                                      int structureClearance) {
        if (candidate == null || candidate.size() < 2
                || findFirstViolation(candidate) != null
                || findFirstStructureConflict(candidate, structureClearance) != null) return false;
        // A building repair must actually change the corridor. Accepting an
        // identical path only records another failed attempt and makes the
        // caller retain a known collision as if it had been repaired.
        if (sameFullPath(original, candidate)) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 64.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    static boolean isLakeRepairAccepted(List<BlockPos> original,
                                                 List<BlockPos> candidate,
                                                 int roadWidth,
                                                 LakeLookup lakes,
                                                 HeightLookup terrain,
                                                 LakeLookup lakeClearance) {
        // Logging-only diagnostics: each rejection reason is reported at debug
        // level in the same short-circuit order the combined condition used.
        if (candidate == null || candidate.size() < 2) {
            LOGGER.debug("【湖泊修复候选为空或过短，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason={} originalNodes={} candidateNodes={}",
                    candidate == null ? "null_candidate" : "path_too_short",
                    original == null ? -1 : original.size(),
                    candidate == null ? -1 : candidate.size());
            return false;
        }
        Violation grade = findFirstViolation(candidate);
        if (grade != null) {
            LOGGER.debug("【湖泊修复候选纵坡超限，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason=grade edge={}..{} rise={} length={} nodes={}",
                    grade.start(), grade.end(), grade.rise(), format(grade.length()), candidate.size());
            return false;
        }
        Violation structure = findFirstStructureConflict(candidate,
                // Task 1: physical road-body radius, voxel-accurate — the
                // legacy 10-block ahead-clearance rejected valid edge-grazing
                // corridors that never touch the structure.
                planningRoadRadius());
        if (structure != null) {
            BlockPos from = candidate.get(Math.max(0, structure.start()));
            BlockPos to = candidate.get(Math.max(0, Math.min(candidate.size() - 1, structure.end())));
            LOGGER.debug("【湖泊修复候选与建筑冲突，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason=structure_conflict edge={}..{} pos={} nodes={}",
                    structure.start(), structure.end(), from != null ? from : to, candidate.size());
            return false;
        }
        Violation lake = findFirstLakeConstraintConflict(candidate, roadWidth, lakes, terrain);
        if (lake != null) {
            LOGGER.debug("【湖泊修复候选仍与湖泊冲突，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason=lake_conflict edge={}..{} nodes={}",
                    lake.start(), lake.end(), candidate.size());
            return false;
        }
        Violation mask = findFirstMaskConflict(candidate, lakeClearance);
        if (mask != null) {
            LOGGER.debug("【湖泊修复候选侵入湖泊净距掩码，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason=lake_clearance_mask edge={}..{} nodes={}",
                    mask.start(), mask.end(), candidate.size());
            return false;
        }
        double originalLength = routeLength(original);
        double candidateLength = routeLength(candidate);
        double maxLength = Math.max(originalLength + 200.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
        if (candidateLength > maxLength) {
            LOGGER.debug("【湖泊修复候选绕行过长，拒绝该候选】 " + "RoadWeaver lake repair candidate rejected: reason=detour_too_long "
                            + "candidateLength={} maxLength={} originalLength={} nodes={}",
                    format(candidateLength), format(maxLength), format(originalLength), candidate.size());
            return false;
        }
        return true;
    }
}
