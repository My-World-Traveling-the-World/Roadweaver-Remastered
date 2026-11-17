/* 文件职责：阻止拓扑替换把某个路网连通分量与出生点切断 —— 出生点不可达等于存档废掉。 */
package net.shiroha233.roadweaver.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;

/** 替代连接前保留出生点连通性，避免局部改接切断全网入口。 */
public final class SpawnConnectivityValidator {
    private static final int SPAWN_HORIZONTAL_RADIUS = 128;
    private static final int SPAWN_VERTICAL_RADIUS = 96;

    private SpawnConnectivityValidator() {}

    public static boolean replacementRemainsConnected(
            ServerLevel level, RoadData candidate, RoadData original, List<RoadData> accepted) {
        if (level == null || candidate == null || candidate.roadSegmentList().isEmpty()) return false;
        List<RoadData> roads = new ArrayList<>();
        if (accepted != null) roads.addAll(accepted);
        long originalFingerprint = original == null ? Long.MIN_VALUE
                : RoadShardStorage.computeFingerprint(original);
        roads.removeIf(road -> road == null
                || (original != null && RoadShardStorage.computeFingerprint(road) == originalFingerprint));
        long candidateFingerprint = RoadShardStorage.computeFingerprint(candidate);
        roads.removeIf(road -> road != null
                && RoadShardStorage.computeFingerprint(road) == candidateFingerprint);
        roads.add(candidate);
        return componentContainingSpawn(level, roads).contains(candidate);
    }

    public static boolean routeRemainsConnected(
            ServerLevel level, RoadData route, List<RoadData> accepted) {
        if (level == null || route == null || route.roadSegmentList().isEmpty()) return false;
        List<RoadData> roads = new ArrayList<>();
        if (accepted != null) roads.addAll(accepted);
        long routeFingerprint = RoadShardStorage.computeFingerprint(route);
        roads.removeIf(road -> road == null
                || RoadShardStorage.computeFingerprint(road) == routeFingerprint);
        roads.add(route);
        return componentContainingSpawn(level, roads).contains(route);
    }

    public static boolean routeRemainsConnectedToPersistedNetwork(
            ServerLevel level, RoadData route) {
        if (level == null || route == null) return false;
        List<RoadData> roads = new ArrayList<>(RoadShardStorage.loadAll(level));
        long routeFingerprint = RoadShardStorage.computeFingerprint(route);
        roads.removeIf(road -> road == null
                || RoadShardStorage.computeFingerprint(road) == routeFingerprint);
        roads.add(route);
        return componentContainingSpawn(level, roads).contains(route);
    }

    /** 比较有向路线，反向延续不能当作同向重复边删掉，否则会断开出生点一侧。
 *
     * Compare the directed route vectors. A reversed vector is deliberately
     * different: an endpoint-changing candidate can be the continuation of an
     * original route, and treating that U-turn as "same direction" would
     * replace the spawn-side edge and disconnect the network.
     */
    public static boolean sameOverallDirection(RoadData first, RoadData second) {
        double[] a = direction(first);
        double[] b = direction(second);
        if (a == null || b == null) return false;
        double dot = a[0] * b[0] + a[1] * b[1];
        return dot >= 0.90D;
    }

    private static double[] direction(RoadData road) {
        if (road == null) return null;
        BlockPos first;
        BlockPos last;
        if (road.hasOwnerPair()) {
            first = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
            last = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
        } else if (road.roadSegmentList().size() >= 2) {
            first = road.roadSegmentList().getFirst().middlePos();
            last = road.roadSegmentList().getLast().middlePos();
        } else {
            return null;
        }
        if (first == null || last == null) return null;
        double dx = last.getX() - first.getX();
        double dz = last.getZ() - first.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        return length < 4.0D ? null : new double[] {dx / length, dz / length};
    }

    public static boolean replacementRemainsConnectedToPersistedNetwork(
            ServerLevel level, RoadData candidate, RoadData original) {
        if (level == null || candidate == null) return false;
        List<RoadData> roads = new ArrayList<>(RoadShardStorage.loadAll(level));
        long originalFingerprint = original == null ? Long.MIN_VALUE
                : RoadShardStorage.computeFingerprint(original);
        long candidateFingerprint = RoadShardStorage.computeFingerprint(candidate);
        roads.removeIf(road -> road == null
                || RoadShardStorage.computeFingerprint(road) == originalFingerprint
                || RoadShardStorage.computeFingerprint(road) == candidateFingerprint);
        roads.add(candidate);
        return componentContainingSpawn(level, roads).contains(candidate);
    }

    private static Set<RoadData> componentContainingSpawn(ServerLevel level, List<RoadData> roads) {
        Set<RoadData> reachable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (roads.isEmpty()) return reachable;
        ArrayDeque<RoadData> queue = new ArrayDeque<>();
        for (RoadData road : roads) {
            if (touchesSpawn(level.getSharedSpawnPos(), road)) {
                reachable.add(road);
                queue.add(road);
            }
        }
        while (!queue.isEmpty()) {
            RoadData current = queue.removeFirst();
            for (RoadData other : roads) {
                if (other == null || reachable.contains(other)) continue;
                if (geometricallyConnected(current, other)) {
                    reachable.add(other);
                    queue.addLast(other);
                }
            }
        }
        return reachable;
    }

    private static boolean touchesSpawn(BlockPos spawn, RoadData road) {
        if (spawn == null || road == null) return false;
        if (road.hasOwnerPair()) {
            BlockPos a = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
            BlockPos b = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
            if (near(spawn, a) || near(spawn, b)) return true;
        }
        for (var segment : road.roadSegmentList()) {
            if (segment != null && near(spawn, segment.middlePos())) return true;
        }
        return false;
    }

    private static boolean near(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        long radius = (long) SPAWN_HORIZONTAL_RADIUS * SPAWN_HORIZONTAL_RADIUS;
        return dx * dx + dz * dz <= radius
                && Math.abs(a.getY() - b.getY()) <= SPAWN_VERTICAL_RADIUS;
    }

    private static boolean geometricallyConnected(RoadData first, RoadData second) {
        if (first == null || second == null
                || first.roadSegmentList().isEmpty() || second.roadSegmentList().isEmpty()) return false;
        int horizontal = Math.max(8, (first.width() + second.width()) / 2 + 4);
        long maxHorizontalSq = (long) horizontal * horizontal;
        for (var a : first.roadSegmentList()) {
            if (a == null || a.middlePos() == null) continue;
            BlockPos ap = a.middlePos();
            for (var b : second.roadSegmentList()) {
                if (b == null || b.middlePos() == null) continue;
                BlockPos bp = b.middlePos();
                long dx = (long) ap.getX() - bp.getX();
                long dz = (long) ap.getZ() - bp.getZ();
                if (dx * dx + dz * dz <= maxHorizontalSq
                        && Math.abs(ap.getY() - bp.getY()) <= 8) return true;
            }
        }
        return false;
    }
}
