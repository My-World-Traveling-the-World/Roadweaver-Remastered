/* 文件职责：找一条已完工的自动路作为后续自动连接的端点（刻意做成生成器：每次只产出一个候选）。 */
package net.shiroha233.roadweaver.planning;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** 已有自动路仅作为生成时短接候选；短支路成功后才持久化端点替换，不能先改拓扑再盲建。
 *
 * Finds a completed automatic road that can be used as the endpoint of a
 * later automatic connection. This is intentionally a generation-time
 * candidate: the replacement is persisted only after its short branch is
 * generated successfully.
 */
public final class ExistingRoadEndpointReuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /**
     * 接入点搜索半径（格）。
     *
     * <p><b>0.9.082：12 → 48。</b>12 来自 {@code RoadConstants.ROAD_SNAP_THRESHOLD}，
     * 那个值是给「路已经建好、只差几格接上」用的。这里的场景完全不同 ——
     * <b>两条同时规划的路互相找交汇点</b>。真机取证（存档 20261111 1704）：
     * 三条连接 {@code (2880,-1152)->(1936,-400)}、{@code (1728,-544)->(1936,-400)}、
     * {@code (1936,-400)->(2464,-672)} 汇聚到同一村庄，但它们各自的路径在村口
     * 几十格内并不重合（可复用站只有 9 个），12 格结构性找不到接入点。</p>
     */
    static final int APPROACH_THRESHOLD = 48;

    private ExistingRoadEndpointReuse() {
    }

    public static StructureConnection findCandidate(ServerLevel level, StructureConnection original) {
        if (level == null || original == null || original.from() == null || original.to() == null
                || original.status() == ConnectionStatus.COMPLETED
                || original.status() == ConnectionStatus.FAILED) {
            return null;
        }
        if (isManualEndpoint(level, original.from()) || isManualEndpoint(level, original.to())) return null;

        ModConfig config = ConfigService.get();
        Optional<List<BlockPos>> planned = PlannedPathCache.find(
                level,
                original,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                config.pathfindingCost());
        if (planned.isEmpty() || planned.get().size() < 4) return null;

        List<StructureConnection> connections = WorldDataProvider.getInstance().getStructureConnections(level);
        if (connections == null || connections.isEmpty()) return null;
        Candidate best = null;
        for (StructureConnection existing : connections) {
            if (existing == null || !isReusableStatus(existing.status())
                    || PlanningUtils.sameEdge(existing, original)) continue;
            // 0.9.082：existing 还没建完时，必须确认它确实有规划路径 ——
            // 否则它一旦最终失败，被改接的 original 就会变成断头路。
            if (!isSettled(existing.status()) && !hasPlannedPath(level, existing, config)) continue;

            SharedEndpoint shared = sharedEndpoint(existing, original);
            if (shared == null || isManualEndpoint(level, shared.replacementEndpoint())) continue;
            StructureConnection replacement = new StructureConnection(
                    shared.replacementEndpoint(), shared.otherOriginalEndpoint(), ConnectionStatus.PLANNED);
            if (same2d(replacement.from(), replacement.to()) || hasEdge(connections, replacement)) continue;

            int routeIndex = nearestInteriorIndex(planned.get(), shared.replacementEndpoint());
            if (routeIndex < 0) continue;
            int distance = horizontalDistance(planned.get().get(routeIndex), shared.replacementEndpoint());
            Candidate candidate = new Candidate(replacement, distance, routeIndex,
                    PlannedPathKey.of(existing));
            if (best == null || candidate.betterThan(best)) best = candidate;
        }
        if (best == null) return null;
        LOGGER.info("【既有道路端点复用候选】 " + "RoadWeaver existing-road endpoint candidate original={} -> {} replacement={} -> {} "
                        + "approachDistance={} routeIndex={} existingEdge={}",
                original.from(), original.to(), best.connection().from(), best.connection().to(),
                best.distance(), best.routeIndex(), best.existingEdge());
        return best.connection();
    }

    /**
     * 哪条既有连接可以被复用。
     *
     * <p><b>0.9.082：从「只认 COMPLETED」放宽到「同批次也行」。</b>
     * 原判据 {@code status() != COMPLETED} 的后果是：24 条路并行生成，
     * 处理第 2 条时第 1 条还是 PLANNED —— 这个机制在**同一批初始规划里从未触发过**
     * （真机日志 {@code existing-road endpoint candidate} 恰好 0 次）。</p>
     *
     * <p>{@code PROTECTED_GAP} 也算可复用：它是有路但穿村处留了保护缺口的连接，
     * 路的本体已经存在，接上去是合法的。</p>
     */
    static boolean isReusableStatus(ConnectionStatus status) {
        if (status == null) return false;
        return switch (status) {
            case COMPLETED, PROTECTED_GAP, PLANNED, GENERATING -> true;
            case FAILED, FILTERED -> false;
        };
    }

    /** 这条连接的路是否已经落地（不需要再验证规划路径）。 */
    private static boolean isSettled(ConnectionStatus status) {
        return status == ConnectionStatus.COMPLETED || status == ConnectionStatus.PROTECTED_GAP;
    }

    /** 这条连接是否已经有可用规划路径。 */
    private static boolean hasPlannedPath(ServerLevel level, StructureConnection connection, ModConfig config) {
        try {
            Optional<List<BlockPos>> path = PlannedPathCache.find(
                    level, connection,
                    TerrainSamplingSessions.forLevel(level).effectiveMode(),
                    config.pathfindingCost());
            return path.isPresent() && path.get().size() >= 4;
        } catch (Throwable couldNotRead) {
            return false;
        }
    }

    private static SharedEndpoint sharedEndpoint(StructureConnection existing,
                                                 StructureConnection original) {
        if (same2d(existing.from(), original.from())) {
            return new SharedEndpoint(existing.to(), original.to());
        }
        if (same2d(existing.from(), original.to())) {
            return new SharedEndpoint(existing.to(), original.from());
        }
        if (same2d(existing.to(), original.from())) {
            return new SharedEndpoint(existing.from(), original.to());
        }
        if (same2d(existing.to(), original.to())) {
            return new SharedEndpoint(existing.from(), original.from());
        }
        return null;
    }

    private static int nearestInteriorIndex(List<BlockPos> path, BlockPos target) {
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos position = path.get(index);
            int distance = horizontalDistance(position, target);
            if (distance <= APPROACH_THRESHOLD && distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static boolean hasEdge(List<StructureConnection> connections, StructureConnection candidate) {
        for (StructureConnection connection : connections) {
            if (connection != null && PlanningUtils.sameEdge(connection, candidate)) return true;
        }
        return false;
    }

    private static boolean isManualEndpoint(ServerLevel level, BlockPos position) {
        return StructureFileStorage.getStructureSnapshot(level).sourceAt(position)
                == StructureFileStorage.SOURCE_MANUAL;
    }

    private static boolean same2d(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        if (first == null || second == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getZ() - second.getZ()));
    }

    private record SharedEndpoint(BlockPos replacementEndpoint, BlockPos otherOriginalEndpoint) {
    }

    private record Candidate(StructureConnection connection, int distance, int routeIndex,
                             PlannedPathKey existingEdge) {
        private boolean betterThan(Candidate other) {
            if (distance != other.distance) return distance < other.distance;
            return routeIndex < other.routeIndex;
        }
    }
}
