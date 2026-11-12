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

/**
 * Bounded topology fallback for automatically planned edges. Manual map
 * endpoints are intentionally never replaced.
 */
public final class ConnectionFallbackService {
    /** One deterministic fallback per failed route; later retries caused topology races. */
    public static final int MAX_CANDIDATES = 1;
    private static final int SEARCH_RADIUS_BLOCKS = 1024;

    private ConnectionFallbackService() {
    }

    public static List<StructureConnection> candidates(ServerLevel level, StructureConnection failed) {
        if (level == null || failed == null || !Level.OVERWORLD.equals(level.dimension())) return List.of();

        StructureFileStorage.StructureSnapshot snapshot = StructureFileStorage.getStructureSnapshot(level);
        Set<Long> manualKeys = StructureFileStorage.manualStructureKeys(level);
        List<StructureConnection> existing = WorldDataProvider.getInstance().getStructureConnections(level);
        BlockPos looseEndpoint = selectLooseEndpoint(failed, existing);
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
                persisted.add(new StructureConnection(from, to, ConnectionStatus.COMPLETED));
            }
        }
        List<StructureConnection> ranked = rankCandidates(
                failed, snapshotPositions, manualKeys, existing, persisted);
        if (ranked.isEmpty()) return ranked;
        List<StructureConnection> pending = new ArrayList<>(ranked.size());
        for (StructureConnection candidate : ranked) {
            if (!StructureFileStorage.hasFailedFallbackAttempt(level, failed, candidate)) {
                pending.add(candidate);
            }
        }
        return List.copyOf(pending);
    }

    /**
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
        if (failed == null || failed.from() == null || failed.to() == null) return List.of();
        Set<Long> manual = manualKeys == null ? Set.of() : manualKeys;
        List<StructureConnection> current = existing == null ? List.of() : existing;
        List<StructureConnection> saved = persisted == null ? List.of() : persisted;
        long fromKey = PlanningUtils.pos2dKey(failed.from());
        long toKey = PlanningUtils.pos2dKey(failed.to());
        BlockPos looseEndpoint = selectLooseEndpoint(failed, current);
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
        addConnections(graph, occupied, current, failed, false);
        addConnections(graph, occupied, saved, failed, true);

        Set<Long> looseComponent = graph.component(looseKey);
        Set<Long> anchorComponent = graph.component(anchorKey);
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

        ArrayList<Candidate> ranked = new ArrayList<>();
        for (BlockPos position : candidatePositions.values()) {
            if (position == null) continue;
            BlockPos candidate = flat(position);
            long candidateKey = PlanningUtils.pos2dKey(candidate);
            if (candidateKey == fromKey || candidateKey == toKey || manual.contains(candidateKey)
                    || reservedEndpoints.contains(candidateKey)) continue;
            if (looseComponent.contains(candidateKey)) continue;

            int priority = candidatePriority(candidateKey, anchorComponent, graph);
            int sidePriority = directionSidePriority(failed, looseEndpoint, candidate);
            // Never cross to the opposite side of the failed edge. That is
            // where another active route most often creates a dependency cycle.
            if (sidePriority >= 2) continue;
            addCandidate(ranked, occupied, looseEndpoint, candidate, priority, sidePriority);
        }

        ranked.sort(Comparator.comparingInt(Candidate::sidePriority)
                .thenComparingInt(Candidate::priority)
                .thenComparingLong(Candidate::score)
                .thenComparingLong(candidate -> PlanningUtils.edgeKey(candidate.connection().from(), candidate.connection().to())));
        ArrayList<StructureConnection> out = new ArrayList<>(MAX_CANDIDATES);
        Set<PlannedPathKey> seen = new HashSet<>();
        for (Candidate candidate : ranked) {
            PlannedPathKey key = PlannedPathKey.of(candidate.connection());
            if (seen.add(key)) out.add(candidate.connection());
            if (out.size() == MAX_CANDIDATES) break;
        }
        return List.copyOf(out);
    }

    private static void addConnections(NetworkGraph graph,
                                       Set<PlannedPathKey> occupied,
                                       List<StructureConnection> connections,
                                       StructureConnection failed,
                                       boolean persisted) {
        if (connections == null) return;
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            occupied.add(PlannedPathKey.of(connection));
            // A protected gap is deliberately not a traversable network edge.
            // Keep its key occupied, but do not let it alter topology ranking.
            if (PlanningUtils.sameEdge(connection, failed)
                    || connection.status() == ConnectionStatus.FAILED
                    || connection.status() == ConnectionStatus.PROTECTED_GAP) continue;
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

    private static void addCandidate(List<Candidate> ranked,
                                     Set<PlannedPathKey> occupied,
                                     BlockPos looseEndpoint,
                                     BlockPos candidate,
                                     int priority,
                                     int sidePriority) {
        StructureConnection replacement = new StructureConnection(flat(looseEndpoint), candidate);
        PlannedPathKey key = PlannedPathKey.of(replacement);
        if (occupied.contains(key)) return;

        long displacement = horizontalDistanceSquared(looseEndpoint, candidate);
        if (displacement > (long) SEARCH_RADIUS_BLOCKS * SEARCH_RADIUS_BLOCKS) return;
        ranked.add(new Candidate(replacement, priority, displacement, sidePriority));
    }

    /** 0 = same-side continuation, 1 = same-side lateral, 2 = opposite side. */
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

    /** Keeps the endpoint already attached to more viable edges as the network anchor. */
    private static BlockPos selectLooseEndpoint(StructureConnection failed, List<StructureConnection> existing) {
        int fromDegree = viableDegree(failed.from(), failed, existing);
        int toDegree = viableDegree(failed.to(), failed, existing);
        // Ties use the connection's destination as the replaceable endpoint.
        return toDegree <= fromDegree ? flat(failed.to()) : flat(failed.from());
    }

    private static int viableDegree(BlockPos endpoint,
                                    StructureConnection failed,
                                    List<StructureConnection> existing) {
        if (existing == null || endpoint == null) return 0;
        int degree = 0;
        for (StructureConnection connection : existing) {
            if (connection == null || PlanningUtils.sameEdge(connection, failed)
                    || connection.status() == ConnectionStatus.FAILED) continue;
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
