package net.shiroha233.roadweaver.generation;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates user-requested removal of non-completed connections. */
public final class ConnectionDeletionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Set<WorldConnectionKey> CANCELLED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<WorldConnectionKey> ACTIVE_ROOT = new ThreadLocal<>();

    private ConnectionDeletionService() {}

    public enum Result {
        DELETED,
        NOT_FOUND,
        COMPLETED_PROTECTED,
        INVALID
    }

    public static Result delete(ServerLevel level, BlockPos from, BlockPos to) {
        if (level == null || from == null || to == null || !Level.OVERWORLD.equals(level.dimension())) {
            return Result.INVALID;
        }
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null || current.isEmpty()) return Result.NOT_FOUND;

        StructureConnection target = null;
        for (StructureConnection connection : current) {
            if (sameEdge2d(connection, from, to)) {
                target = connection;
                break;
            }
        }
        if (target == null) return Result.NOT_FOUND;
        if (target.status() == ConnectionStatus.COMPLETED) return Result.COMPLETED_PROTECTED;
        if (!isDeletable(target.status())) return Result.INVALID;

        WorldConnectionKey key = key(level, target);
        CANCELLED.add(key);
        RoadGenerationService.cancelConnection(target);
        IdleRoadGenerationService.cancelConnection(target);
        StructureFileStorage.suppressConnection(level, target.from(), target.to());

        ArrayList<StructureConnection> retained = new ArrayList<>(current.size() - 1);
        for (StructureConnection connection : current) {
            if (!sameEdge2d(connection, target.from(), target.to())) retained.add(connection);
        }
        provider.setStructureConnections(level, retained);
        PlannedPathCache.discard(level, target);
        discardPlanningSnapshot(level, target);
        // 审计 P1-4 附带项：玩家删除连接时，同步清掉手绘路线的登记项，
        // 否则 RouteBuildRegistry 里会留一条「指向已删连接」的僵尸记录，
        // 进度统计（countForRoute）也会把已删的算进去。
        net.shiroha233.roadweaver.route.RouteBuildRegistry.clear(level, target);
        deletePersistedNonCompletedRoad(level, target);
        MapPatchService.publishConnectionRemoval(level, target);
        LOGGER.info("RoadWeaver user deleted non-completed connection: {} -> {} status={}",
                target.from(), target.to(), target.status());
        return Result.DELETED;
    }

    public static void allow(ServerLevel level, BlockPos from, BlockPos to) {
        if (level == null || from == null || to == null) return;
        StructureFileStorage.allowConnection(level, from, to);
        CANCELLED.remove(key(level, from, to));
    }

    public static boolean isDeletable(ConnectionStatus status) {
        return status == ConnectionStatus.PLANNED
                || status == ConnectionStatus.GENERATING
                || status == ConnectionStatus.FAILED;
    }

    public static boolean shouldAbort(ServerLevel level, StructureConnection connection) {
        if (Thread.currentThread().isInterrupted()) return true;
        if (level == null || connection == null) return false;
        WorldConnectionKey active = ACTIVE_ROOT.get();
        return (active != null && CANCELLED.contains(active))
                || CANCELLED.contains(key(level, connection));
    }

    public static TaskScope beginTask(ServerLevel level, StructureConnection connection) {
        WorldConnectionKey previous = ACTIVE_ROOT.get();
        ACTIVE_ROOT.set(key(level, connection));
        return new TaskScope(previous);
    }

    public static void clearRuntime() {
        CANCELLED.clear();
        ACTIVE_ROOT.remove();
    }

    private static void deletePersistedNonCompletedRoad(ServerLevel level, StructureConnection target) {
        long expected = PlanningUtils.edgeKey(target.from(), target.to());
        for (RoadData road : RoadShardStorage.loadAll(level)) {
            if (road == null || !road.hasOwnerPair()) continue;
            if (PlanningUtils.edgeKey(road.ownerA2dKey(), road.ownerB2dKey()) == expected) {
                RoadShardStorage.deleteRoad(level, RoadShardStorage.computeFingerprint(road));
            }
        }
    }

    private static boolean sameEdge2d(StructureConnection connection, BlockPos from, BlockPos to) {
        if (connection == null || connection.from() == null || connection.to() == null) return false;
        return (same2d(connection.from(), from) && same2d(connection.to(), to))
                || (same2d(connection.from(), to) && same2d(connection.to(), from));
    }

    private static boolean same2d(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    private static WorldConnectionKey key(ServerLevel level, StructureConnection connection) {
        return key(level, connection.from(), connection.to());
    }

    private static WorldConnectionKey key(ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos normalizedFrom = new BlockPos(from.getX(), 0, from.getZ());
        BlockPos normalizedTo = new BlockPos(to.getX(), 0, to.getZ());
        return new WorldConnectionKey(
                level.dimension().location().toString(), level.getSeed(),
                PlannedPathKey.of(normalizedFrom, normalizedTo));
    }

    private record WorldConnectionKey(String dimension, long seed, PlannedPathKey connection) {}

    public static final class TaskScope implements AutoCloseable {
        private final WorldConnectionKey previous;
        private boolean closed;

        private TaskScope(WorldConnectionKey previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) ACTIVE_ROOT.remove();
            else ACTIVE_ROOT.set(previous);
        }
    }
}
