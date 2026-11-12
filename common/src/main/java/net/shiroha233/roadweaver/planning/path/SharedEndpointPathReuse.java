package net.shiroha233.roadweaver.planning.path;

import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes paths sharing a structure endpoint reuse a common tail before any
 * detailed height, bridge, tunnel, or construction work begins.
 */
public final class SharedEndpointPathReuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MIN_REUSABLE_STATIONS = 4;

    private SharedEndpointPathReuse() {
    }

    public static RoadTerrainPlanningPort.Result reuseSharedEndpointTails(
            RoadTerrainPlanningPort.Result result, int pathStep) {
        return reuseSharedEndpointTails(null, result, pathStep);
    }

    public static RoadTerrainPlanningPort.Result reuseSharedEndpointTails(
            ServerLevel level, RoadTerrainPlanningPort.Result result, int pathStep) {
        if (result == null || result.paths().size() < 2) return result;

        Map<StructureConnection, List<BlockPos>> rewritten = new LinkedHashMap<>(result.paths());
        Map<Long, List<StructureConnection>> byEndpoint = new HashMap<>();
        for (StructureConnection connection : result.paths().keySet()) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            byEndpoint.computeIfAbsent(key(connection.from()), ignored -> new ArrayList<>()).add(connection);
            byEndpoint.computeIfAbsent(key(connection.to()), ignored -> new ArrayList<>()).add(connection);
        }

        // The production coarse planner normally uses a four-block step. Tying
        // the reuse radius to two steps silently reduced the configured
        // twelve-block road snap reach to eight blocks, leaving long parallel
        // approaches that the later junction pass could only partially trim.
        int reuseDistance = RoadConstants.ROAD_SNAP_THRESHOLD;
        int reused = 0;
        List<Long> endpointKeys = new ArrayList<>(byEndpoint.keySet());
        endpointKeys.sort(Long::compare);
        // Unknown shared endpoints (multiple incoming roads, no named/village
        // structure at the junction) agree on ONE height before any trunk
        // reuse: the minimum reachable height across all incoming routes.
        // Without this the primary trunk keeps its own (possibly high) height
        // and a low-side approach must "bridge up from flat ground" to join
        // it. Villages, the spawn and named structures keep their surface
        // locks and never enter this pass.
        Map<Long, Integer> unknownEndpointHeights = new HashMap<>();
        if (level != null) {
            for (long endpointKey : endpointKeys) {
                List<StructureConnection> connections = byEndpoint.get(endpointKey);
                if (connections == null || connections.size() < 2) continue;
                StructureConnection probe = connections.get(0);
                boolean from = probe.from() != null && key(probe.from()) == endpointKey;
                if (!RoadSpanProfileOps.isUnknownStructureEndpoint(level, probe, from)) continue;
                int unified = Integer.MAX_VALUE;
                for (StructureConnection connection : connections) {
                    List<BlockPos> path = rewritten.get(connection);
                    BlockPos endpoint = endpointForKey(connection, endpointKey);
                    if (path == null || path.isEmpty() || endpoint == null) continue;
                    int reachable = endpointApproachReachableY(path, endpoint);
                    if (reachable != Integer.MAX_VALUE) {
                        unified = unified == Integer.MAX_VALUE ? reachable : Math.max(unified, reachable);
                    }
                }
                if (unified != Integer.MAX_VALUE) {
                    unknownEndpointHeights.put(endpointKey, unified);
                }
            }
        }
        for (long endpointKey : endpointKeys) {
            List<StructureConnection> connections = byEndpoint.get(endpointKey);
            if (connections == null || connections.size() < 2) continue;
            connections.sort(Comparator.comparing(SharedEndpointPathReuse::connectionKey));
            BlockPos endpoint = endpointForKey(connections.get(0), endpointKey);
            if (endpoint == null) continue;
            // Apply the agreed height to every incoming route (primary and
            // secondaries alike) so the trunk merge starts from one height.
            Integer unifiedY = unknownEndpointHeights.get(endpointKey);
            if (unifiedY != null && unifiedY < endpoint.getY()) {
                for (StructureConnection connection : connections) {
                    List<BlockPos> path = rewritten.get(connection);
                    if (path == null || path.isEmpty()) continue;
                    List<BlockPos> lowered = lowerEndpointApproach(path, endpoint, unifiedY);
                    if (lowered != null) rewritten.put(connection, lowered);
                }
                endpoint = new BlockPos(endpoint.getX(), unifiedY, endpoint.getZ());
                LOGGER.info("RoadWeaver shared-endpoint unknown height unified endpoint=({},{}) "
                                + "height={} routes={}",
                        endpoint.getX(), endpoint.getZ(), unifiedY, connections.size());
            }

            PrimaryChoice choice = choosePrimary(connections, rewritten, endpoint, reuseDistance);
            if (choice == null) continue;
            StructureConnection primaryConnection = choice.connection();
            List<BlockPos> primary = choice.path();
            for (StructureConnection secondaryConnection : connections) {
                if (secondaryConnection.equals(primaryConnection)) continue;
                List<BlockPos> secondary = orientedTowardEndpoint(rewritten.get(secondaryConnection), endpoint);
                List<BlockPos> merged = mergeIntoSharedTrunk(primary, secondary,
                        choice.joins().get(secondaryConnection), reuseDistance, pathStep);
                if (merged == null) continue;
                rewritten.put(secondaryConnection, orientLikeConnection(merged, secondaryConnection));
                SpatialJoin join = choice.joins().get(secondaryConnection);
                if (level != null && join != null && !primary.isEmpty()) {
                    // 0.8.368 (T1): record the primary route's identity. Filing
                    // two primary-path coordinates under the secondary key could
                    // never match — the two routes only share their first few
                    // stations — and every recorded range was therefore dropped
                    // before construction. 0.8.369 additionally snapshots the
                    // primary's coordinates here, because reading them back from
                    // the cache later failed for 4 of 9 ranges on a real save
                    // (the primary generated and discarded its entry first).
                    PlannedPathCache.recordReuse(level, secondaryConnection,
                            PlannedPathKey.of(primaryConnection), primary);
                }
                reused++;
                LOGGER.info("RoadWeaver pre-detail shared-endpoint reuse endpoint={} primary={} secondary={} stations={}->{}",
                        endpoint, PlannedPathKey.of(primaryConnection), PlannedPathKey.of(secondaryConnection),
                        secondary.size(), merged.size());
            }
        }
        int spatialReused = 0;
        List<StructureConnection> ordered = new ArrayList<>(rewritten.keySet());
        for (int secondaryIndex = 0; secondaryIndex < ordered.size(); secondaryIndex++) {
            StructureConnection secondaryConnection = ordered.get(secondaryIndex);
            List<BlockPos> secondary = rewritten.get(secondaryConnection);
            if (secondary == null || secondary.size() < MIN_REUSABLE_STATIONS) continue;
            for (int primaryIndex = 0; primaryIndex < secondaryIndex; primaryIndex++) {
                StructureConnection primaryConnection = ordered.get(primaryIndex);
                List<BlockPos> primary = rewritten.get(primaryConnection);
                if (sharesEndpoint(primaryConnection, secondaryConnection)) continue;
                SpatialJoin join = findSpatialJoin(primary, secondary, reuseDistance, true);
                if (join == null) continue;
                List<BlockPos> merged = mergeSpatialOverlap(primary, secondary, join, pathStep);
                if (merged == null || merged.equals(secondary)) continue;
                rewritten.put(secondaryConnection, merged);
                if (level != null) {
                    // 0.8.368 (T1): same fix as the shared-endpoint branch — the
                    // record names the primary route, not its coordinates.
                    // 0.8.369 (T1b): snapshot those coordinates too, so the match
                    // cannot fail when the primary's cache entry is gone.
                    PlannedPathCache.recordReuse(level, secondaryConnection,
                            PlannedPathKey.of(primaryConnection), primary);
                }
                spatialReused++;
                LOGGER.info("RoadWeaver pre-detail spatial road reuse primary={} secondary={} "
                                + "primaryStations={}..{} secondaryStations={}..{}",
                        PlannedPathKey.of(primaryConnection), PlannedPathKey.of(secondaryConnection),
                        join.primaryStart(), join.primaryEnd(), join.secondaryStart(), join.secondaryEnd());
                break;
            }
        }
        if (spatialReused > 0) {
            LOGGER.info("RoadWeaver pre-detail spatial road reuse completed reused={} joinDistance={}",
                    spatialReused, reuseDistance);
        }
        // T-23.2: 合并完成后重新执行一次过路村庄裁剪检查。合并是巨路尾段被
        // 拼进两村走廊、穿过 dark_forest 聚合盒的直接原因;若某条 secondary
        // 的合并"引入"了原路径没有的过路村庄穿越,回退该条合并(保留原路径
        // 与其端点着陆)。快照未就绪(-1)时跳过检查,finalize 阶段的缺口计算
        // 会在快照就绪后照常执行。
        if (level != null && (reused > 0 || spatialReused > 0)) {
            for (Map.Entry<StructureConnection, List<BlockPos>> entry : rewritten.entrySet()) {
                List<BlockPos> originalPath = result.paths().get(entry.getKey());
                List<BlockPos> mergedPath = entry.getValue();
                if (originalPath == null || mergedPath == null || originalPath.equals(mergedPath)) continue;
                int crossingsBefore = passThroughVillageCrossings(
                        level, entry.getKey(), originalPath);
                if (crossingsBefore < 0) continue;
                int crossingsAfter = passThroughVillageCrossings(
                        level, entry.getKey(), mergedPath);
                if (crossingsAfter > crossingsBefore) {
                    rewritten.put(entry.getKey(), originalPath);
                    LOGGER.info("RoadWeaver reverted shared-endpoint merge that introduced a pass-through village "
                                    + "crossing connection={} -> {} crossings={} -> {}",
                            entry.getKey().from(), entry.getKey().to(), crossingsBefore, crossingsAfter);
                }
            }
        }
        if (reused > 0 || spatialReused > 0) {
            LOGGER.info("RoadWeaver pre-detail shared-endpoint reuse completed paths={} endpointReused={} spatialReused={} joinDistance={}",
                    result.paths().size(), reused, spatialReused, reuseDistance);
            return new RoadTerrainPlanningPort.Result(result.mode(), rewritten);
        }
        return result;
    }

    /**
     * @param requireForward 0.9.046（D-1）：是否只接受**正向**跑动。
     *       空间复用段传 {@code true}（掐死 U 型回头）；共享端点主干打分传 {@code false}
     *       —— 0.9.040 把这条约束写死在函数内部，连带改掉了 choosePrimary 的主干选择，
     *       还杀掉了「两条路反向经过同一走廊」这种合法去重（后处理侧 :3147/:2150 至今
     *       支持反向）。这里把两者分开，各自保持原语义。
     */
    private static SpatialJoin findSpatialJoin(List<BlockPos> primary,
                                               List<BlockPos> secondary,
                                               int reuseDistance,
                                               boolean requireForward) {
        if (primary == null || secondary == null
                || primary.size() < MIN_REUSABLE_STATIONS
                || secondary.size() < MIN_REUSABLE_STATIONS) return null;
        List<Match> matches = new ArrayList<>();
        for (int secondaryIndex = 1; secondaryIndex < secondary.size() - 1; secondaryIndex++) {
            BlockPos secondaryPos = secondary.get(secondaryIndex);
            int bestPrimary = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int primaryIndex = 1; primaryIndex < primary.size() - 1; primaryIndex++) {
                int distance = horizontalChebyshevDistance(secondaryPos, primary.get(primaryIndex));
                if (distance <= reuseDistance && distance < bestDistance) {
                    bestDistance = distance;
                    bestPrimary = primaryIndex;
                }
            }
            if (bestPrimary >= 0) matches.add(new Match(secondaryIndex, bestPrimary));
        }
        SpatialJoin best = null;
        int runStart = 0;
        int direction = 0;
        for (int index = 1; index <= matches.size(); index++) {
            boolean extendsRun = index < matches.size()
                    && compatibleSpatialMatch(matches.get(index - 1), matches.get(index), direction);
            if (extendsRun) {
                int delta = matches.get(index).primaryIndex() - matches.get(index - 1).primaryIndex();
                if (direction == 0 && delta != 0) direction = Integer.signum(delta);
                continue;
            }
            if (index - runStart >= 2) {
                Match left = matches.get(runStart);
                Match right = matches.get(index - 1);
                int secondarySpan = right.secondaryIndex() - left.secondaryIndex();
                int primarySpan = Math.abs(right.primaryIndex() - left.primaryIndex());
                // 0.9.040（R4）：只接受**正向**跑动（primary 下标递增）的复用段。
                //
                // 修复前这里没有方向约束：`compatibleSpatialMatch` 只在两段方向相互矛盾时才
                // 拒绝，所以 primary 下标**递减**（反平行）的匹配段会被整段接受；而下面组 join
                // 时又把两端排序成 `start <= end`，方向信息在这一行被销毁，`appendPrimaryRange`
                // 于是永远"从头往尾"拼回（它的倒序分支 `:283` 因此是死代码）。
                // 结果：secondary 走到远端 → appendConnector 用直线跳回近端 → 再把 primary
                // 正向走一遍 = 一次全长往返。`appendConnector` 的插值只看 pathStep、
                // 没有长度上限，所以这条"跳回"的直弦可以任意长。
                // 这正是真机看到的 U 型回头 / 闭环（弧长 2542 vs 弦 784，55% 的站有 ≥90° 转角）。
                //
                // 反平行段本来就不是"可复用的走廊"，直接放弃这一段：
                // 几何会退回到不复用（更差但不会出环），而不是拼出一条往返。
                boolean forwardProgress = !requireForward || right.primaryIndex() > left.primaryIndex();
                if (forwardProgress
                        && secondarySpan >= MIN_REUSABLE_STATIONS && primarySpan >= MIN_REUSABLE_STATIONS
                        && (best == null || Math.min(secondarySpan, primarySpan)
                        > Math.min(best.secondaryEnd() - best.secondaryStart(),
                        best.primaryEnd() - best.primaryStart()))) {
                    best = new SpatialJoin(left.primaryIndex(), right.primaryIndex(),
                            left.secondaryIndex(), right.secondaryIndex());
                }
            }
            runStart = index;
            direction = 0;
        }
        return best;
    }

    private static boolean compatibleSpatialMatch(Match previous, Match current, int direction) {
        int secondaryDelta = current.secondaryIndex() - previous.secondaryIndex();
        int primaryDelta = current.primaryIndex() - previous.primaryIndex();
        if (secondaryDelta <= 0 || secondaryDelta > 2 || Math.abs(primaryDelta) > 3) return false;
        int currentDirection = Integer.signum(primaryDelta);
        return direction == 0 || currentDirection == 0 || currentDirection == direction;
    }

    private static List<BlockPos> mergeSpatialOverlap(List<BlockPos> primary,
                                                       List<BlockPos> secondary,
                                                       SpatialJoin join,
                                                       int pathStep) {
        ArrayList<BlockPos> merged = new ArrayList<>(secondary.size() + join.primaryEnd() - join.primaryStart() + 8);
        appendRange(merged, secondary, 0, join.secondaryStart() + 1);
        appendConnector(merged, secondary.get(join.secondaryStart()),
                primary.get(join.primaryStart()), pathStep);
        appendPrimaryRange(merged, primary, join.primaryStart(), join.primaryEnd());
        appendConnector(merged, primary.get(join.primaryEnd()),
                secondary.get(join.secondaryEnd()), pathStep);
        appendRange(merged, secondary, join.secondaryEnd(), secondary.size());
        removeConsecutiveDuplicates(merged);
        return merged.size() >= MIN_REUSABLE_STATIONS ? List.copyOf(merged) : null;
    }

    private static void appendPrimaryRange(List<BlockPos> out, List<BlockPos> path, int from, int to) {
        if (from <= to) appendRange(out, path, from, to + 1);
        else for (int index = from; index >= to; index--) out.add(path.get(index));
    }

    private record Match(int secondaryIndex, int primaryIndex) {}

    private record SpatialJoin(int primaryStart, int primaryEnd, int secondaryStart, int secondaryEnd) {}

    /** Keep one shared trunk, then preserve the secondary route's own endpoint. */
    static List<BlockPos> mergeIntoSharedTrunk(List<BlockPos> primary,
                                               List<BlockPos> secondary,
                                               SpatialJoin precomputedJoin,
                                               int reuseDistance,
                                               int pathStep) {
        if (primary == null || secondary == null
                || primary.size() < MIN_REUSABLE_STATIONS || secondary.size() < MIN_REUSABLE_STATIONS) {
            return null;
        }
        SpatialJoin join = precomputedJoin == null
                ? findSharedTrunkJoin(primary, secondary, reuseDistance) : precomputedJoin;
        if (join == null) return null;

        // The retained secondary tail can begin by still hugging the primary
        // trunk corridor (the join run ended because of spacing, not because
        // the secondary left the trunk). Those stations duplicate the shared
        // trunk and are exactly the "extra segment pressing on the main road"
        // seen at shared endpoints. Walk past them and connect the trunk to the
        // first station that genuinely leaves the corridor.
        int tailStart = join.secondaryEnd() + 1;
        while (tailStart < secondary.size()
                && withinTrunkCorridor(secondary.get(tailStart), primary,
                join.primaryEnd(), reuseDistance)) {
            tailStart++;
        }
        if (tailStart < secondary.size() && tailStart > join.secondaryEnd() + 1) {
            LOGGER.info("RoadWeaver shared-endpoint trunk trim removed secondary stub overlapping "
                            + "trunk secondary={}..{} joinPrimaryEnd={}",
                    join.secondaryEnd() + 1, tailStart - 1, join.primaryEnd());
        }
        List<BlockPos> merged = new ArrayList<>(secondary.size() + primary.size());
        appendRange(merged, primary, 0, join.primaryEnd() + 1);
        if (tailStart >= secondary.size()) {
            // The whole secondary tail stays inside the primary trunk corridor.
            // Merging would swallow the secondary's own far endpoint, breaking
            // connectivity. Keep the historical behavior (preserve the tail);
            // the later post-process trim may still shorten it.
            LOGGER.warn("RoadWeaver shared-endpoint merge kept overlapping tail: secondary never "
                            + "leaves the trunk corridor; preserving its endpoint tail");
            appendConnector(merged, primary.get(join.primaryEnd()),
                    secondary.get(join.secondaryEnd()), pathStep);
            appendRange(merged, secondary, join.secondaryEnd() + 1, secondary.size());
        } else {
            appendConnector(merged, primary.get(join.primaryEnd()),
                    secondary.get(tailStart), pathStep);
            appendRange(merged, secondary, tailStart, secondary.size());
        }
        removeConsecutiveDuplicates(merged);
        return merged.size() >= MIN_REUSABLE_STATIONS ? List.copyOf(merged) : null;
    }

    /** True when {@code position} lies within {@code reuseDistance} of any primary trunk station. */
    private static boolean withinTrunkCorridor(BlockPos position,
                                               List<BlockPos> primary,
                                               int primaryEndInclusive,
                                               int reuseDistance) {
        if (position == null || primary == null) return false;
        int upper = Math.min(primary.size() - 1, Math.max(0, primaryEndInclusive));
        for (int index = 0; index <= upper; index++) {
            BlockPos station = primary.get(index);
            if (station != null && horizontalChebyshevDistance(position, station) <= reuseDistance) {
                return true;
            }
        }
        return false;
    }

    private static PrimaryChoice choosePrimary(List<StructureConnection> connections,
                                               Map<StructureConnection, List<BlockPos>> paths,
                                               BlockPos endpoint,
                                               int reuseDistance) {
        if (connections == null || connections.isEmpty()) return null;
        PrimaryChoice best = null;
        for (StructureConnection candidate : connections) {
            List<BlockPos> primary = orientedTowardEndpoint(paths.get(candidate), endpoint);
            if (primary == null) continue;
            Map<StructureConnection, SpatialJoin> joins = new HashMap<>();
            int joinable = 0;
            long reusableStations = 0L;
            for (StructureConnection other : connections) {
                if (other.equals(candidate)) continue;
                List<BlockPos> secondary = orientedTowardEndpoint(paths.get(other), endpoint);
                SpatialJoin join = findSharedTrunkJoin(primary, secondary, reuseDistance);
                if (join == null) continue;
                joins.put(other, join);
                joinable++;
                reusableStations += Math.min(join.primaryEnd() - join.primaryStart(),
                        join.secondaryEnd() - join.secondaryStart());
            }
            PrimaryChoice current = new PrimaryChoice(candidate, primary, Map.copyOf(joins),
                    joinable, reusableStations);
            if (best == null || current.betterThan(best)) best = current;
        }
        if (best != null && !best.connection().equals(connections.get(0))) {
            LOGGER.info("RoadWeaver selected shared-endpoint trunk endpoint={} primary={} routes={} "
                            + "joinable={} reusableStations={}",
                    endpoint, PlannedPathKey.of(best.connection()), connections.size(),
                    best.joinable(), best.reusableStations());
        }
        return best;
    }

    private static SpatialJoin findSharedTrunkJoin(List<BlockPos> primary,
                                                   List<BlockPos> secondary,
                                                   int reuseDistance) {
        // Require a stable nearby run. One accidental close point is not
        // enough to rewrite a route; the run becomes the one shared trunk and
        // its end is the only split point.
        return findSpatialJoin(primary, secondary, reuseDistance, false);
    }

    private static List<BlockPos> orientedTowardEndpoint(List<BlockPos> path, BlockPos endpoint) {
        if (path == null || path.size() < MIN_REUSABLE_STATIONS || endpoint == null) return null;
        BlockPos first = path.get(0);
        BlockPos last = path.get(path.size() - 1);
        // The shared endpoint must be the first station. The previous '<'
        // condition reversed paths whose first station was already farther
        // from the endpoint, leaving the common endpoint at the end.
        if (horizontalChebyshevDistance(first, endpoint) > horizontalChebyshevDistance(last, endpoint)) {
            ArrayList<BlockPos> reversed = new ArrayList<>(path);
            java.util.Collections.reverse(reversed);
            return reversed;
        }
        return path;
    }

    /** Stations scanned from the endpoint toward the far side when picking the reachable height. */
    private static final int UNKNOWN_ENDPOINT_WINDOW_STATIONS = 16;
    /** Never tunnel a shared unknown endpoint deeper than this below its surface. */
    private static final int MAX_UNKNOWN_ENDPOINT_DROP = 8;

    /**
     * Lowest Y over the endpoint station plus up to {@value
     * UNKNOWN_ENDPOINT_WINDOW_STATIONS} stations toward the far side. This is
     * the height one incoming route can genuinely reach without an impossible
     * climb; the shared unknown endpoint takes the minimum across routes.
     */
    private static int endpointApproachReachableY(List<BlockPos> path, BlockPos endpoint) {
        if (path == null || path.isEmpty() || endpoint == null) return Integer.MAX_VALUE;
        int endIndex = -1;
        long endpointKey = key(endpoint);
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) != null && key(path.get(i)) == endpointKey) {
                endIndex = i;
                break;
            }
        }
        if (endIndex < 0) return Integer.MAX_VALUE;
        int minimum = endpoint.getY();
        for (int offset = 1; offset <= UNKNOWN_ENDPOINT_WINDOW_STATIONS; offset++) {
            int forward = endIndex + offset < path.size() ? path.get(endIndex + offset).getY() : Integer.MAX_VALUE;
            int backward = endIndex - offset >= 0 ? path.get(endIndex - offset).getY() : Integer.MAX_VALUE;
            int station = Math.min(forward, backward);
            if (station == Integer.MAX_VALUE) break;
            minimum = Math.min(minimum, station);
        }
        // Cap how deep the shared endpoint may tunnel to reach one low approach.
        return Math.max(endpoint.getY() - MAX_UNKNOWN_ENDPOINT_DROP, minimum);
    }
    /**
     * Lowers the endpoint station of one incoming route onto the agreed
     * {@code unifiedY} (prefer-cut) and ramps the approach so no station is
     * more than one block above the previous one toward the endpoint. Stations
     * already at or below the ramp line keep their profile; nothing is ever
     * raised. Returns null when there is nothing to lower.
     */
    private static List<BlockPos> lowerEndpointApproach(List<BlockPos> path,
                                                        BlockPos endpoint,
                                                        int unifiedY) {
        if (path == null || path.size() < 2 || endpoint == null) return null;
        int endIndex = -1;
        long endpointKey = key(endpoint);
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) != null && key(path.get(i)) == endpointKey) {
                endIndex = i;
                break;
            }
        }
        if (endIndex < 0 || path.get(endIndex).getY() <= unifiedY) return null;
        List<BlockPos> lowered = new ArrayList<>(path);
        BlockPos old = lowered.get(endIndex);
        lowered.set(endIndex, new BlockPos(old.getX(), unifiedY, old.getZ()));
        // Ramp both directions away from the endpoint, one block per station.
        for (int direction = -1; direction <= 1; direction += 2) {
            for (int distance = 1; ; distance++) {
                int index = endIndex + direction * distance;
                if (index < 0 || index >= lowered.size()) break;
                BlockPos position = lowered.get(index);
                if (position == null) break;
                int cap = unifiedY + distance;
                if (position.getY() <= cap) continue;
                lowered.set(index, new BlockPos(position.getX(), cap, position.getZ()));
            }
        }
        return lowered;
    }

    private static List<BlockPos> orientLikeConnection(List<BlockPos> path, StructureConnection connection) {
        if (path == null || path.size() < 2 || connection == null || connection.from() == null) return path;
        BlockPos first = path.getFirst();
        BlockPos last = path.getLast();
        if (horizontalChebyshevDistance(first, connection.from())
                <= horizontalChebyshevDistance(last, connection.from())) {
            return List.copyOf(path);
        }
        ArrayList<BlockPos> reversed = new ArrayList<>(path);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static void appendConnector(List<BlockPos> out, BlockPos from, BlockPos to, int pathStep) {
        if (from == null || to == null) return;
        int distance = horizontalChebyshevDistance(from, to);
        int increments = Math.max(1, (distance + Math.max(1, pathStep) - 1) / Math.max(1, pathStep));
        for (int step = 1; step <= increments; step++) {
            double fraction = (double) step / increments;
            out.add(new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * fraction),
                    from.getY(),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * fraction)));
        }
    }

    private static void appendRange(List<BlockPos> out, List<BlockPos> source, int from, int toExclusive) {
        for (int index = Math.max(0, from); index < Math.min(source.size(), toExclusive); index++) {
            out.add(source.get(index));
        }
    }

    private static void removeConsecutiveDuplicates(List<BlockPos> path) {
        for (int index = path.size() - 1; index > 0; index--) {
            BlockPos current = path.get(index);
            BlockPos previous = path.get(index - 1);
            if (current != null && current.equals(previous)) path.remove(index);
        }
    }

    private static BlockPos endpointForKey(StructureConnection connection, long endpointKey) {
        if (connection == null) return null;
        if (key(connection.from()) == endpointKey) return connection.from();
        return key(connection.to()) == endpointKey ? connection.to() : null;
    }

    private static String connectionKey(StructureConnection connection) {
        return PlannedPathKey.of(connection).toString();
    }

    private static boolean sharesEndpoint(StructureConnection first, StructureConnection second) {
        if (first == null || second == null) return false;
        return key(first.from()) == key(second.from())
                || key(first.from()) == key(second.to())
                || key(first.to()) == key(second.from())
                || key(first.to()) == key(second.to());
    }

    private static long key(BlockPos position) {
        return position == null ? Long.MIN_VALUE : (((long) position.getX()) << 32) ^ (position.getZ() & 0xffffffffL);
    }

    /** Test seam for {@link #endpointApproachReachableY}. */
    static int endpointApproachReachableYForTest(List<BlockPos> path, BlockPos endpoint) {
        return endpointApproachReachableY(path, endpoint);
    }

    /** Test seam for {@link #lowerEndpointApproach}. */
    static List<BlockPos> lowerEndpointApproachForTest(List<BlockPos> path, BlockPos endpoint, int unifiedY) {
        return lowerEndpointApproach(path, endpoint, unifiedY);
    }

    private static int horizontalChebyshevDistance(BlockPos first, BlockPos second) {
        if (first == null || second == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getZ() - second.getZ()));
    }

    private record PrimaryChoice(StructureConnection connection,
                                 List<BlockPos> path,
                                 Map<StructureConnection, SpatialJoin> joins,
                                 int joinable,
                                 long reusableStations) {
        private boolean betterThan(PrimaryChoice other) {
            int joinableOrder = Integer.compare(joinable, other.joinable);
            if (joinableOrder != 0) return joinableOrder > 0;
            int reuseOrder = Long.compare(reusableStations, other.reusableStations);
            if (reuseOrder != 0) return reuseOrder > 0;
            return connectionKey(connection).compareTo(connectionKey(other.connection)) < 0;
        }
    }
}
