/* 文件职责：连接规划失败后的降级链：逐级放宽约束再试，并把最终降级原因记下来。 */
package net.shiroha233.roadweaver.planning;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 自动连接可用有界拓扑候选；手绘端点不允许偷偷换点，默认是否调用仍受上层开关控制。
 *
 * Bounded topology fallback for automatically planned edges. Manual map
 * endpoints are intentionally never replaced.
 */
public final class ConnectionFallbackService {
    /** 每条失败路只选确定的替代候选，避免多轮换点与并发拓扑竞争。 */
    public static final int MAX_CANDIDATES = 1;
    private static final int SEARCH_RADIUS_BLOCKS = 1024;

    private ConnectionFallbackService() {
    }

    public static List<StructureConnection> candidates(ServerLevel level, StructureConnection failed) {
        return candidates(level, failed, null, false, SEARCH_RADIUS_BLOCKS);
    }

    /** 开局补边只允许接回另一端已有的可用分量，不用待办边或保护缺口证明接通。 */
    public static List<StructureConnection> startupCandidates(ServerLevel level, StructureConnection failed,
            List<StructureConnection> settled, int edgeLengthLimit) {
        return candidates(level, failed, settled, true, Math.min(SEARCH_RADIUS_BLOCKS, edgeLengthLimit));
    }

    private static List<StructureConnection> candidates(ServerLevel level, StructureConnection failed,
            List<StructureConnection> settled, boolean startup, int radius) {
        if (level == null || failed == null || !Level.OVERWORLD.equals(level.dimension())) return List.of();

        StructureFileStorage.StructureSnapshot snapshot = StructureFileStorage.getStructureSnapshot(level);
        Set<Long> manualKeys = StructureFileStorage.manualStructureKeys(level);
        List<StructureConnection> existing = startup ? settled
                : WorldDataProvider.getInstance().getStructureConnections(level);
        BlockPos looseEndpoint = selectLooseEndpoint(failed, existing, startup);
        List<BlockPos> snapshotPositions = new ArrayList<>();
        for (StructureInfo info : snapshot.locations().structureInfos()) {
            if (info != null && info.pos() != null
                    && (StructureInfo.isVillageId(info.structureId()) || isSpawnEndpoint(level, info.pos()))) {
                snapshotPositions.add(info.pos());
            }
        }
        for (BlockPos pos : snapshot.locations().structureLocations()) {
            if (pos != null && isSpawnEndpoint(level, pos)) snapshotPositions.add(pos);
        }

        List<StructureConnection> persisted = new ArrayList<>();
        int minX = looseEndpoint.getX() - SEARCH_RADIUS_BLOCKS;
        int minZ = looseEndpoint.getZ() - SEARCH_RADIUS_BLOCKS;
        int maxX = looseEndpoint.getX() + SEARCH_RADIUS_BLOCKS;
        int maxZ = looseEndpoint.getZ() + SEARCH_RADIUS_BLOCKS;
        for (RoadData road : RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ)) {
            if (road == null || !road.hasOwnerPair()) continue;
            BlockPos from = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
            BlockPos to = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
            if (!same2d(from, to)) {
                persisted.add(new StructureConnection(from, to, road.hasProtectedGap()
                        ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED));
            }
        }
        return rankCandidates(failed, snapshotPositions, manualKeys, existing, persisted, startup, radius,
                c -> !StructureFileStorage.hasFailedFallbackAttempt(level, failed, c)
                        && !StructureFileStorage.isConnectionSuppressed(level, c.from(), c.to()));
    }

    /** 先移除失败边再计算分量，避免替代边被原失败边制造的虚假连通性排除。
 *
     * Pure topology selection used by production and regression tests. The
     * failed edge is removed before component analysis, so an alternate path
     * cannot be turned into a redundant cycle.
     */
    static List<StructureConnection> rankCandidates(
            StructureConnection failed,
            Collection<BlockPos> snapshotPositions,
            Set<Long> manualKeys,
            List<StructureConnection> existing,
            List<StructureConnection> persisted) {
        return rankCandidates(failed, snapshotPositions, manualKeys, existing, persisted, false, SEARCH_RADIUS_BLOCKS);
    }

    /** 纯拓扑判据，两条路径继续共用手绘端点、方向和保留边检查。 */
    static List<StructureConnection> rankCandidates(StructureConnection failed, Collection<BlockPos> snapshotPositions,
            Set<Long> manualKeys, List<StructureConnection> existing, List<StructureConnection> persisted,
            boolean requireAnchorComponent, int radius) {
        return rankCandidates(failed, snapshotPositions, manualKeys, existing, persisted, requireAnchorComponent, radius, c -> true);
    }

    static List<StructureConnection> rankCandidates(StructureConnection failed, Collection<BlockPos> snapshotPositions,
            Set<Long> manualKeys, List<StructureConnection> existing, List<StructureConnection> persisted,
            boolean requireAnchorComponent, int radius, java.util.function.Predicate<StructureConnection> allowed) {
        if (failed == null || failed.from() == null || failed.to() == null) return List.of();
        Set<Long> manual = manualKeys == null ? Set.of() : manualKeys;
        List<StructureConnection> current = existing == null ? List.of() : existing;
        List<StructureConnection> saved = persisted == null ? List.of() : persisted;
        long fromKey = PlanningUtils.pos2dKey(failed.from());
        long toKey = PlanningUtils.pos2dKey(failed.to());
        BlockPos looseEndpoint = selectLooseEndpoint(failed, current, requireAnchorComponent);
        long looseKey = PlanningUtils.pos2dKey(looseEndpoint);

        // Map-created connections register both endpoints as manual. A manual
        // network anchor such as the spawn cabin may still keep an automatic
        // road, but a manual loose endpoint must never be silently replaced.
        if ((manual.contains(fromKey) && manual.contains(toKey)) || manual.contains(looseKey)) {
            return List.of();
        }
        BlockPos anchorEndpoint = same2d(looseEndpoint, failed.from()) ? flat(failed.to()) : flat(failed.from());
        long anchorKey = PlanningUtils.pos2dKey(anchorEndpoint);

        NetworkGraph graph = new NetworkGraph();
        Set<PlannedPathKey> occupied = new HashSet<>();
        addConnections(graph, occupied, current, failed, false, requireAnchorComponent);
        addConnections(graph, occupied, saved, failed, true, requireAnchorComponent);

        Set<Long> looseComponent = graph.component(looseKey);
        Set<Long> anchorComponent = graph.component(anchorKey);
        if (requireAnchorComponent && anchorComponent.contains(looseKey)) return List.of();
        // Endpoints of in-flight/planned edges are reserved. A candidate must
        // never wait on another route's endpoint, otherwise two fallback tasks
        // can form a dependency cycle.
        Set<Long> reservedEndpoints = new HashSet<>();
        for (StructureConnection connection : current) {
            if (connection == null || PlanningUtils.sameEdge(connection, failed)) continue;
            if (connection.status() == ConnectionStatus.GENERATING
                    || connection.status() == ConnectionStatus.PLANNED) {
                reservedEndpoints.add(PlanningUtils.pos2dKey(connection.from()));
                reservedEndpoints.add(PlanningUtils.pos2dKey(connection.to()));
            }
        }
        LinkedHashMap<Long, BlockPos> candidatePositions = new LinkedHashMap<>();
        if (snapshotPositions != null) {
            for (BlockPos position : snapshotPositions) addPosition(candidatePositions, position);
        }
        graph.positions().forEach(candidatePositions::putIfAbsent);

        Candidate best = null;
        for (BlockPos position : candidatePositions.values()) {
            if (position == null) continue;
            BlockPos candidate = flat(position);
            long candidateKey = PlanningUtils.pos2dKey(candidate);
            if (candidateKey == fromKey || candidateKey == toKey || manual.contains(candidateKey)
                    || reservedEndpoints.contains(candidateKey)) continue;
            if (looseComponent.contains(candidateKey)) continue;
            if (requireAnchorComponent && !anchorComponent.contains(candidateKey)) continue;

            int priority = candidatePriority(candidateKey, anchorComponent, graph);
            int sidePriority = directionSidePriority(failed, looseEndpoint, candidate);
            // Never cross to the opposite side of the failed edge. That is
            // where another active route most often creates a dependency cycle.
            if (sidePriority >= 2) continue;
            var replacement = new StructureConnection(looseEndpoint, candidate);
            long distance = horizontalDistanceSquared(looseEndpoint, candidate);
            if (distance > (long) radius * radius || occupied.contains(PlannedPathKey.of(replacement))) continue;
            var next = new Candidate(replacement, priority, distance, sidePriority);
            // 只有有望胜出的候选才查询禁用和失败历史；被排除不消耗名额。
            if ((best == null || ORDER.compare(next, best) < 0) && allowed.test(replacement)) best = next;
        }
        return best == null ? List.of() : List.of(best.connection());
    }

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::sidePriority)
            .thenComparingInt(Candidate::priority).thenComparingLong(Candidate::score)
            .thenComparingLong(c -> PlanningUtils.edgeKey(c.connection().from(), c.connection().to()));

    private static void addConnections(NetworkGraph graph,
                                       Set<PlannedPathKey> occupied,
                                       List<StructureConnection> connections,
                                       StructureConnection failed,
                                       boolean persisted, boolean confirmedOnly) {
        if (connections == null) return;
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            occupied.add(PlannedPathKey.of(connection));
            // A protected gap is deliberately not a traversable network edge.
            // Keep its key occupied, but do not let it alter topology ranking.
            if (PlanningUtils.sameEdge(connection, failed)
                    || connection.status() == ConnectionStatus.FAILED
                    || connection.status() == ConnectionStatus.PROTECTED_GAP) continue;
            if (confirmedOnly && connection.status() != ConnectionStatus.COMPLETED) continue;
            boolean confirmed = persisted || connection.status() == ConnectionStatus.COMPLETED;
            graph.add(connection.from(), connection.to(), confirmed);
        }
    }

    private static void addPosition(LinkedHashMap<Long, BlockPos> positions, BlockPos position) {
        if (position == null) return;
        BlockPos flattened = flat(position);
        positions.putIfAbsent(PlanningUtils.pos2dKey(flattened), flattened);
    }

    private static int candidatePriority(long candidateKey,
                                         Set<Long> anchorComponent,
                                         NetworkGraph graph) {
        if (anchorComponent.contains(candidateKey)) return 0;
        if (graph.confirmedNodes().contains(candidateKey)) return 1;
        if (graph.activeNodes().contains(candidateKey)) return 2;
        return 3;
    }

    /** 候选方向分级区分同侧延续、同侧横向与对侧，保持确定排序。 */
    private static int directionSidePriority(StructureConnection failed,
                                             BlockPos looseEndpoint,
                                             BlockPos candidate) {
        if (failed == null || looseEndpoint == null || candidate == null) return 2;
        BlockPos anchor = same2d(looseEndpoint, failed.from()) ? failed.to() : failed.from();
        // Stay on the side the failed route was trying to reach: from the
        // replaceable endpoint back toward the failed anchor. This makes a
        // 3 -> 1 failure prefer a nearby 2, while rejecting an upper 4.
        long routeDx = (long) anchor.getX() - looseEndpoint.getX();
        long routeDz = (long) anchor.getZ() - looseEndpoint.getZ();
        long candidateDx = (long) candidate.getX() - looseEndpoint.getX();
        long candidateDz = (long) candidate.getZ() - looseEndpoint.getZ();
        long dot = routeDx * candidateDx + routeDz * candidateDz;
        long cross = routeDx * candidateDz - routeDz * candidateDx;
        if (dot > 0 && Math.abs(cross) <= Math.max(1L, Math.abs(dot) / 2L)) return 0;
        if (dot > 0) return 1;
        return 2;
    }

    /** 优先保留已有可行连接更多的一端为网络锚点，降低替换造成的断网风险。 */
    private static BlockPos selectLooseEndpoint(StructureConnection failed, List<StructureConnection> existing,
                                                 boolean confirmedOnly) {
        int fromDegree = viableDegree(failed.from(), failed, existing, confirmedOnly);
        int toDegree = viableDegree(failed.to(), failed, existing, confirmedOnly);
        // Ties use the connection's destination as the replaceable endpoint.
        return toDegree <= fromDegree ? flat(failed.to()) : flat(failed.from());
    }

    private static int viableDegree(BlockPos endpoint,
                                    StructureConnection failed,
                                    List<StructureConnection> existing, boolean confirmedOnly) {
        if (existing == null || endpoint == null) return 0;
        int degree = 0;
        for (StructureConnection connection : existing) {
            if (connection == null || PlanningUtils.sameEdge(connection, failed)
                    || connection.status() == ConnectionStatus.FAILED) continue;
            if (confirmedOnly && connection.status() != ConnectionStatus.COMPLETED) continue;
            if (same2d(endpoint, connection.from()) || same2d(endpoint, connection.to())) degree++;
        }
        return degree;
    }

    private static boolean same2d(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    private static BlockPos flat(BlockPos pos) {
        return new BlockPos(pos.getX(), 0, pos.getZ());
    }

    private static boolean isSpawnEndpoint(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        return spawn != null && spawn.getX() == pos.getX() && spawn.getZ() == pos.getZ();
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private record Candidate(StructureConnection connection, int priority, long score, int sidePriority) {
    }

    private static final class NetworkGraph {
        private final HashMap<Long, Set<Long>> adjacency = new HashMap<>();
        private final LinkedHashMap<Long, BlockPos> positions = new LinkedHashMap<>();
        private final Set<Long> activeNodes = new HashSet<>();
        private final Set<Long> confirmedNodes = new HashSet<>();

        void add(BlockPos from, BlockPos to, boolean confirmed) {
            BlockPos first = flat(from);
            BlockPos second = flat(to);
            long firstKey = PlanningUtils.pos2dKey(first);
            long secondKey = PlanningUtils.pos2dKey(second);
            if (firstKey == secondKey) return;
            positions.putIfAbsent(firstKey, first);
            positions.putIfAbsent(secondKey, second);
            activeNodes.add(firstKey);
            activeNodes.add(secondKey);
            adjacency.computeIfAbsent(firstKey, ignored -> new HashSet<>()).add(secondKey);
            adjacency.computeIfAbsent(secondKey, ignored -> new HashSet<>()).add(firstKey);
            if (confirmed) {
                confirmedNodes.add(firstKey);
                confirmedNodes.add(secondKey);
            }
        }

        Set<Long> component(long start) {
            if (!activeNodes.contains(start)) return Set.of(start);
            HashSet<Long> visited = new HashSet<>();
            ArrayDeque<Long> pending = new ArrayDeque<>();
            visited.add(start);
            pending.add(start);
            while (!pending.isEmpty()) {
                long current = pending.removeFirst();
                for (long neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) pending.addLast(neighbor);
                }
            }
            return Set.copyOf(visited);
        }

        LinkedHashMap<Long, BlockPos> positions() {
            return positions;
        }

        Set<Long> activeNodes() {
            return activeNodes;
        }

        Set<Long> confirmedNodes() {
            return confirmedNodes;
        }
    }
}
