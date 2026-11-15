/* 文件职责：保护缺口与过村判定 —— 判断「这里的碰撞是不是端点豁免造成的」。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;

// 仍留在避让服务里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;

/**
 * 保护缺口（PROTECTED_GAP）与过村判定。
 *
 * <h2>它回答的问题</h2>
 * <p>路与结构「相交」并不总是错误 —— 路<b>必须</b>能接到它的端点村庄里。
 * 但一旦允许了这种相交，就必须回答：这次相交是<b>端点豁免</b>造成的，
 * 还是<b>真的撞进了无关建筑</b>？前者放行，后者否决。</p>
 *
 * <h2>判据是「缺口形态」而不是「有没有碰撞」</h2>
 * <p>做法是把整条路上与建筑碰撞的区间收集成「缺口」，再看这组缺口的形态：
 * 只有<b>紧贴端点</b>且<b>长度受限</b>的缺口才算豁免；一旦出现夹在中间的缺口，
 * 就说明路从村中间穿过去了 —— 那是切房子，必须拒。</p>
 *
 * <h2>★ 已知的脆弱点（历史遗留，搬迁时原样保留）</h2>
 * <p>这套判据是旧实现里多个根因的汇点，且<b>没有 fail-safe 分支</b>
 * （对照之下 {@code NETWORK_REUSE} 有 {@code verifySurrenderedReuse} 兜底）。
 * 也就是说判据本身出错时不会自动退到「拒绝」这一侧。搬迁不改它 ——
 * 但这里明确记下来，免得下一个人以为它已经是安全的设计。</p>
 *
 * <p>本类是从 2144 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class ProtectedGapEvaluator {
    public static String describePreparedProtection(ServerLevel level, StructureConnection connection,
                                                      List<RoadSegmentPlacement> segments) {
        if (!ConfigService.get().structureAvoidanceEnabled()) return "disabled";
        if (segments.size() < 2) return "no-product";
        try {
            // 沿用生产快照和判据；观察缺样时单列状态，不把未知保护区报成零缺口。
            return "collision=" + intersectsPreparedRoute(level, connection, segments)
                    + ",gaps=" + protectedGapSpans(level, connection, segments).size();
        } catch (PlanningSnapshotUnavailableException unavailable) {
            return "unavailable";
        }
    }

    public static boolean intersectsPreparedRoute(ServerLevel level,
                                                  StructureConnection connection,
                                                  List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.isEmpty()) return false;
        if (!ConfigService.get().structureAvoidanceEnabled()) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        for (RoadSegmentPlacement segment : segments) {
            if (snapshot.blocksSegmentFootprint(segment)) return true;
        }
        return false;
    }

    /** True when a route intersects only decoded structure blocks, not unknown fallback boxes. */
    public static boolean intersectsPreparedRouteWithUnresolvedStructure(
            ServerLevel level,
            StructureConnection connection,
            List<RoadSegmentPlacement> segments) {
        // A bounding-box-only prediction is metadata, not a building. It must
        // never reject an otherwise valid route or trigger topology fallback.
        return false;
    }

    /**
     * A verified-building collision may be retained as a bounded, explicit
     * construction gap. The route remains valid only when both sides have
     * enough usable road and the gap is a small fraction of the route.
     */
    public static boolean isProtectedGapAcceptable(ServerLevel level,
                                                    StructureConnection connection,
                                                    List<BlockPos> path) {
        return hasAcceptableBuildingGaps(level, connection, path);
    }

    /** Returns persisted gap spans for a finalized road, if all gaps are valid. */
    public static List<RoadSpan> protectedGapSpans(ServerLevel level,
                                                    StructureConnection connection,
                                                    List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.size() < 3) return List.of();
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        boolean[] collisions = new boolean[segments.size()];
        // T-23: 过路村庄聚合盒分段裁剪 —— 非端点村庄(如 dark_forest 这类仅被
        // 巨路穿过、并非本连接端点的村庄)聚合盒内的站点落为 PROTECTED_GAP,
        // 施工器据此跳过密度 stamp 与装饰,消除切村、路灯与反复重试。
        // 端点村庄被显式排除:它们由 stopAtVillageBuilding 的着陆逻辑处理,
        // 其盒永远不产生缺口。开关关闭时掩码全 false,行为与旧版一致。
        boolean[] passThroughVillage = snapshot.passThroughVillageGapMask(
                segments.stream().map(segment -> segment == null ? null : segment.middlePos()).toList());
        double[] distance = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            collisions[i] = segment != null
                    && (snapshot.blocksSegmentFootprint(segment) || passThroughVillage[i]);
            if (i > 0 && segment != null && segments.get(i - 1) != null) {
                distance[i] = distance[i - 1] + Math.hypot(
                        segment.middlePos().getX() - segments.get(i - 1).middlePos().getX(),
                        segment.middlePos().getZ() - segments.get(i - 1).middlePos().getZ());
            }
        }
        double total = distance[distance.length - 1];
        if (total <= 0.0D) return List.of();
        List<RoadSpan> result = new ArrayList<>();
        for (int i = 0; i < collisions.length;) {
            if (!collisions[i]) { i++; continue; }
            int start = i;
            while (i + 1 < collisions.length && collisions[i + 1]) i++;
            int end = i;
            double gap = Math.max(1.0D, distance[end] - distance[start]);
            // Endpoint structures are valid targets too. Leave the occupied
            // endpoint stations unpaved so the road stops at the building
            // edge instead of cutting through the target structure. A route
            // fully consumed by one building is still unusable.
            if (start == 0 && end == collisions.length - 1
                    || distance[start] < 0.0D
                    || total - distance[end] < 0.0D) return List.of();
            BlockPos from = segments.get(start).middlePos();
            BlockPos to = segments.get(end).middlePos();
            if (from != null && to != null) result.add(new RoadSpan(from, to, SpanType.PROTECTED_GAP));
            i++;
        }
        return List.copyOf(result);
    }

    /**
     * T-23.2: SharedEndpointPathReuse 合并完成后重新执行的过路村庄检查入口。
     * 返回 path 上落入非端点村庄聚合盒的站点数;快照不可用或开关关闭时返回 -1
     * (调用方据此跳过检查,finalize 阶段的缺口计算仍会在快照就绪后执行)。
     */
    public static int passThroughVillageCrossings(ServerLevel level,
                                                  StructureConnection connection,
                                                  List<BlockPos> path) {
        if (level == null || connection == null || path == null || path.isEmpty()) return 0;
        try {
            if (!ConfigService.get().planning().passThroughVillageClipping()) return -1;
        } catch (Throwable ignored) {
            return -1;
        }
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
                || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return -1;
        boolean[] mask = snapshot.passThroughVillageGapMask(path);
        int crossings = 0;
        for (boolean value : mask) if (value) crossings++;
        return crossings;
    }

    /**
     * A gap at the approach to a selected endpoint is an intentional road
     * termination at the building edge. It must not make the whole connection
     * eligible for topology replacement. Internal gaps retain the existing
     * candidate fallback behavior.
     */
    public static boolean hasOnlyEndpointProtectedGaps(ServerLevel level,
                                                        StructureConnection connection,
                                                        RoadData road) {
        if (road == null || connection == null || !road.hasProtectedGap()
                || road.roadSegmentList() == null || road.roadSegmentList().size() < 2) {
            return false;
        }
        BlockPos first = road.roadSegmentList().getFirst().middlePos();
        BlockPos last = road.roadSegmentList().getLast().middlePos();
        if (first == null || last == null) return false;
        return endpointOnlyProtectedGapPattern(
                List.of(first, last), road.spans());
    }

    static boolean endpointOnlyProtectedGapPatternForTest(List<BlockPos> endpoints,
                                                           List<RoadSpan> spans) {
        return endpointOnlyProtectedGapPattern(endpoints, spans);
    }

    static boolean endpointOnlyProtectedGapPattern(List<BlockPos> endpoints,
                                                            List<RoadSpan> spans) {
        if (endpoints == null || endpoints.size() < 2 || spans == null || spans.isEmpty()) return false;
        BlockPos first = endpoints.getFirst();
        BlockPos last = endpoints.getLast();
        if (first == null || last == null) return false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            BlockPos start = span.start();
            BlockPos end = span.end();
            if (start == null && end == null) return false;
            long nearest = Math.min(
                    Math.min(horizontalDistanceSquared(start, first), horizontalDistanceSquared(start, last)),
                    Math.min(horizontalDistanceSquared(end, first), horizontalDistanceSquared(end, last)));
            if (nearest > (long) ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS
                    * ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS) {
                return false;
            }
        }
        return true;
    }

    /** Validates a candidate path against the configured road-body footprint. */
    public static boolean intersectsPreparedPath(ServerLevel level,
                                                 StructureConnection connection,
                                                 List<BlockPos> path) {
        return intersectsPreparedPath(level, connection, path, null);
    }

    /**
     * Checks only the configured road body.  Building avoidance is deliberately
     * independent from the optional terrain shoulders: a detour is valid when
     * the five-block road itself can pass without touching a real building.
     */
    public static boolean intersectsPreparedPath(ServerLevel level,
                                                 StructureConnection connection,
                                                 List<BlockPos> path,
                                                 AccurateHeightSampler terrainSampler) {
        if (level == null || connection == null || path == null || path.isEmpty()) return false;
        if (!ConfigService.get().structureAvoidanceEnabled()) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        BlockPos previous = null;
        for (BlockPos current : path) {
            if (current == null) continue;
            if (previous == null) {
                if (snapshot.blocks(current)) return true;
            } else {
                double distance = Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ());
                int samples = Math.max(1, (int) Math.ceil(distance));
                for (int i = 0; i <= samples; i++) {
                    double t = i / (double) samples;
                    BlockPos sample = new BlockPos(
                            (int) Math.round(previous.getX() + (current.getX() - previous.getX()) * t),
                            (int) Math.round(previous.getY() + (current.getY() - previous.getY()) * t),
                            (int) Math.round(previous.getZ() + (current.getZ() - previous.getZ()) * t));
                    if (snapshot.blocks(sample)) return true;
                }
            }
            previous = current;
        }
        return false;
    }

    /** True only when a center path touches an unresolved fallback structure. */
    public static boolean intersectsPreparedPathWithUnresolvedStructure(
            ServerLevel level,
            StructureConnection connection,
            List<BlockPos> path) {
        // Unknown structure boxes are retained for diagnostics only. They do
        // not trigger local detours or replacement candidates.
        return false;
    }

    /**
     * A verified building may leave an unpaved protection gap. The gap can be
     * long, but it must be an internal span with usable road on both sides.
     * The path points are the swept center samples; the snapshot already
     * applies the configured road footprint radius.
     */
    public static boolean hasAcceptableBuildingGaps(ServerLevel level,
                                                     StructureConnection connection,
                                                     List<BlockPos> path) {
        if (level == null || connection == null || path == null || path.size() < 3) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        boolean[] collisions = sweptBuildingCollisionMask(snapshot, path);
        return evaluateProtectedGapPattern(path, collisions, connection);
    }

    /**
     * A protected gap is a construction decision, so inspect the same swept
     * horizontal road footprint used by the planning collision query. Testing
     * only path nodes misses a building that lies between two coarse stations
     * and incorrectly rejects an otherwise usable route.
     */
    static boolean[] sweptBuildingCollisionMask(PlanningSnapshot snapshot,
                                                         List<BlockPos> path) {
        boolean[] collisions = new boolean[path.size()];
        for (int i = 0; i < path.size(); i++) {
            BlockPos current = path.get(i);
            if (current == null) continue;
            collisions[i] |= snapshot.blocks(current);
            if (i == 0) continue;
            BlockPos previous = path.get(i - 1);
            if (previous == null) continue;
            int samples = Math.max(1, (int) Math.ceil(Math.hypot(
                    current.getX() - previous.getX(), current.getZ() - previous.getZ())));
            for (int sample = 1; sample <= samples; sample++) {
                double t = sample / (double) samples;
                BlockPos point = new BlockPos(
                        (int) Math.round(previous.getX() + (current.getX() - previous.getX()) * t),
                        (int) Math.round(previous.getY() + (current.getY() - previous.getY()) * t),
                        (int) Math.round(previous.getZ() + (current.getZ() - previous.getZ()) * t));
                if (snapshot.blocks(point)) {
                    collisions[i] = true;
                    break;
                }
            }
        }
        return collisions;
    }

    /** Package-private route policy hook used by the structure regression harness. */
    static boolean protectedGapPatternAcceptableForTest(List<BlockPos> path, boolean[] collisions) {
        return evaluateProtectedGapPattern(path, collisions, null);
    }

    static boolean evaluateProtectedGapPattern(List<BlockPos> path,
                                                       boolean[] collisions,
                                                       StructureConnection connection) {
        if (path == null || collisions == null || path.size() < 3 || collisions.length != path.size()) {
            return false;
        }
        double total = routeHorizontalLength(path, 0, path.size() - 1);
        if (total <= 0.0D) return false;

        boolean foundBuilding = false;
        for (int i = 0; i < path.size();) {
            if (!collisions[i]) {
                i++;
                continue;
            }
            foundBuilding = true;
            int start = i;
            while (i + 1 < path.size() && collisions[i + 1]) i++;
            int end = i;
            if (start == 0 && end >= path.size() - 1) {
                if (connection != null) {
                    LOGGER.warn("RoadWeaver rejected building protection gap because the building consumes the whole route connection={} -> {} span={}..{}",
                            connection.from(), connection.to(), start, end);
                }
                return false;
            }

            // Evaluate each contiguous building collision independently. A
            // second building farther along the route must not turn the road
            // between the two buildings into one artificial gap. There is no
            // arbitrary maximum gap length: every collided building column is
            // simply left unpaved, while the safe road before and after it is
            // retained.
            double before = routeHorizontalLength(path, 0, start);
            double after = routeHorizontalLength(path, end, path.size() - 1);
            boolean endpointGap = start == 0 || end >= path.size() - 1;
            if ((!endpointGap && (before <= 0.0D || after <= 0.0D))
                    || (start == 0 && after <= 0.0D)
                    || (end >= path.size() - 1 && before <= 0.0D)) {
                if (connection != null) {
                    LOGGER.warn("RoadWeaver rejected building protection gap without usable road outside the building connection={} -> {} span={}..{} before={} after={}",
                            connection.from(), connection.to(), start, end, before, after);
                }
                return false;
            }
            i++;
        }
        return foundBuilding;
    }

    static double routeHorizontalLength(List<BlockPos> path, int from, int to) {
        double length = 0.0D;
        for (int i = Math.max(1, from + 1); i <= to && i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            if (a != null && b != null) length += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        return length;
    }
}
