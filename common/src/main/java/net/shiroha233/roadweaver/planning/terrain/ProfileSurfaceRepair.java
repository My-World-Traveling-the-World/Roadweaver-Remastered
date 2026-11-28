/* 文件职责：为精确纵断面冲突寻找有界地表旁路，候选能否施工由正式纵断面决定。 */
package net.shiroha233.roadweaver.planning.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ProfileSurfaceRepair {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/ProfileSurfaceRepair");

    private ProfileSurfaceRepair() {}

    record Window(int center, int radius, int exclusion) {}

    static boolean trySurface(List<BlockPos> path, int station, ProfileConflict conflict, int width,
            ServerLevel level, StructureConnection connection, AccurateHeightSampler sampler,
            PathfindingCostConfig config, Predicate<List<BlockPos>> accept) {
        int limit = SnapshotOrchestrator.clampCircleProbeRadius(AccurateGradeRepairPlanner.heightAnomalyDetourMaxBlocks());
        int step = config.localRepairGridStep();
        var repairConfig = config.withPathGridStep(step);
        for (Window window : windows(path, station, conflict, width, limit)) {
            BlockPos center = path.get(window.center());
            int radius = window.radius();
            int inside = radius - 3 * step;
            if (inside <= 0) continue;
            // 两端沿原线缩到网格内，给路宽和采样吸附留余量。
            int from = ConflictDetector.walkContextStart(path, window.center(), inside);
            int to = ConflictDetector.walkContextEnd(path, window.center(), inside);
            if (to <= from + 1) continue;
            var original = path.subList(from, to + 1);
            AccurateGradeRepairPlanner.awaitLocalStructureCircle(level, connection, center, radius);
            List<BlockPos> replacement;
            try (var ignored = StructureAvoidanceService.activatePlanningSnapshot(level, connection, width / 2)) {
                var exclude = from < station && station < to ? awayFromColumn(path.get(station), window.exclusion())
                        : AccurateGradeRepairPlanner.EdgeObstacle.NONE;
                replacement = LocalRouteSearch.findLocalRoute(original, sampler, repairConfig,
                        radius, exclude, step, false, 0, null, Integer.MIN_VALUE, center);
            }
            String searchDiag = SearchBudgetPolicy.consumeLocalSearchDiag();
            String verdict = replacement == null ? "no_candidate" : "length_rejected";
            if (candidateLengthAllowed(original, replacement)) {
                var candidate = new ArrayList<BlockPos>(path.size() - original.size() + replacement.size());
                candidate.addAll(path.subList(0, from));
                candidate.addAll(replacement);
                candidate.addAll(path.subList(to + 1, path.size()));
                // 拐出原线不代表可以穿越禁行走廊；正式纵断面再检查固定入口与全线路施工坡度。
                if (RawPathSearch.keepAllowedReplacement(config, path, candidate) != candidate) {
                    verdict = "corridor_rejected";
                } else if (accept.test(List.copyOf(candidate))) {
                    LOGGER.info("【纵断面局部绕行接受】 center={} radius={} window={}..{} {}",
                            center, radius, from, to, searchDiag);
                    return true;
                } else {
                    verdict = "profile_or_geometry_rejected";
                }
            }
            LOGGER.info("【纵断面局部绕行未接受】 center={} radius={} step={} window={}..{} reason={} {}",
                    center, radius, step, from, to, verdict, searchDiag);
        }
        return false;
    }

    /** 常规近绕失败后，第二个圆窗向锁高点挪动，给接坡一侧更多纵深。两个圆窗半径都受原设置约束。 */
    static List<Window> windows(List<BlockPos> path, int station, ProfileConflict conflict, int width, int limit) {
        int narrow = Math.min(48, limit);
        int roadHalfWidth = Math.max(1, width / 2);
        var near = new Window(station, narrow, roadHalfWidth);
        int center = station;
        int exclusion = roadHalfWidth;
        if (conflict.reason() == ProfileConflict.Reason.LOCKED_HEIGHT && conflict.station() != station) {
            int anchor = conflict.station();
            center = anchor < station
                    ? Math.max(anchor, ConflictDetector.walkContextStart(path, station, limit / 4))
                    : Math.min(anchor, ConflictDetector.walkContextEnd(path, station, limit / 4));
            // 严重落差时不再贴着同一个低洼点重试。这里只提候选，不能解除正式高程约束。
            int deficit = conflict.requiredMinY() - conflict.reachableMaxY();
            exclusion = Math.max(roadHalfWidth, Math.min(limit / 4, deficit));
        }
        var approach = new Window(center, limit, exclusion);
        return near.equals(approach) ? List.of(near) : List.of(near, approach);
    }

    /** 原始地表有陡点仍可挖填；只剔除超长候选，让正式求解器决定是否可铺。 */
    static boolean candidateLengthAllowed(List<BlockPos> original, List<BlockPos> replacement) {
        return RepairAcceptance.withinDetourLength(original, replacement);
    }

    /** 粗网格边可能跳过低柱；按边到低柱的距离限制，不只检查网格端点。 */
    private static AccurateGradeRepairPlanner.EdgeObstacle awayFromColumn(BlockPos center, double clearance) {
        return (from, to) -> {
            double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
            double x = center.getX() - from.getX(), z = center.getZ() - from.getZ();
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared == 0 ? 0 : Math.clamp((x * dx + z * dz) / lengthSquared, 0.0D, 1.0D);
            double ex = x - t * dx, ez = z - t * dz;
            return ex * ex + ez * ez < clearance * clearance;
        };
    }
}
