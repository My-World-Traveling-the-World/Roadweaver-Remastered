package net.shiroha233.roadweaver.postprocess;

import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotUnavailableException;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;

/** Connects a later road to an earlier completed road without modifying the earlier road. */
public final class RoadSnapService {
    private RoadSnapService() {
    }

    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    static final int SNAP_THRESHOLD = RoadConstants.ROAD_SNAP_THRESHOLD;
    static final double MAX_SNAP_CONSTRUCTION_OVERHEAD =
            Math.ceil(RoadConstants.ROAD_SNAP_THRESHOLD * Math.sqrt(2.0D));
    static final int SPLIT_THRESHOLD = RoadConstants.ROAD_SNAP_SPLIT_THRESHOLD;
    static final int MIN_RUN = RoadConstants.ROAD_SNAP_MIN_RUN_LENGTH;
    static final int GRID_SHIFT = RoadConstants.ROAD_SNAP_GRID_SHIFT;
    static final int GRID_CELL_SIZE = 1 << GRID_SHIFT;
    static final int GRID_SEARCH_RADIUS = Math.max(1,
            (SNAP_THRESHOLD + GRID_CELL_SIZE - 1) / GRID_CELL_SIZE);
    static final int ENDPOINT_RUN_MAX_OFFSET = 12;
    static final int MAX_TARGET_INDEX_STEP = 4;
    static final int MAX_REPEATED_TARGETS = 2;
    static final int JUNCTION_ANCHOR_SEGMENTS = 10;
    static final int MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS = 64;
    /** Maximum vertical offset accepted before a tunnel junction is graded. */
    static final int MAX_TUNNEL_JUNCTION_GRADE_DELTA = 8;
    /** A close parallel approach may receive a real short connector before reuse. */
    static final int MAX_APPROACH_CONNECTOR_DISTANCE = SNAP_THRESHOLD;
    /** A long, nearly parallel run is a shared corridor, not two roads. */
    static final int OVERLAP_MERGE_MIN_RUN = 6;
    /** A building gap or grade transition can split one physical corridor into
     * several valid reuse windows. Keep the pass bounded while allowing the
     * later road to absorb each independent window.
     * T-17 预算:这是每对道路共享走廊扩展的窗口上限;配合
     * {@link #CONSTRUCTION_LENGTH_MIN_IMPROVEMENT} 的"施工长度必须严格缩短"
     * 门控,正常长路合并通常 1-2 个窗口即收敛,8 只是病态重复的硬上限。 */
    static final int MAX_REUSE_WINDOWS_PER_PAIR = 8;
    /** T-17:续窗必须带来的最小施工长度缩短(块)。低于该值的改进视为
     * 去重/取整噪声,立即停止扩展——这是"constructionLength 几乎不缩短仍
     * 完整重算"的直接止血点。取 1.0 块:一个真实复用窗口吸收 ≥
     * PHYSICAL_REUSE_MIN_RUN(4)个站点,正常合并的缩短量远大于该阈值。 */
    static final double CONSTRUCTION_LENGTH_MIN_IMPROVEMENT = 1.0D;
    // A merged middle corridor may start with a short real connector.  Keep
    // its lateral reach equal to the existing safe snap reach rather than
    // rejecting visibly parallel roads merely because their centres differ by
    // more than one road width.
    static final int OVERLAP_MERGE_MAX_DISTANCE = SNAP_THRESHOLD;
    static final double OVERLAP_MERGE_MIN_DIRECTION_DOT = 0.75D;
    /**
     * 0.8.369 (T2b): a long shared corridor bends, so its heading is sampled at
     * several stations and accepted on a majority vote. One midpoint sample made
     * a 216-station overlap fail the parallel test and kept the braid.
     */
    static final int PARALLEL_CORRIDOR_HEADING_SAMPLES = 5;
    /**
     * 0.8.369 (T2b): fraction of stations allowed to fall outside the merge
     * distance before the whole run is rejected. A single outlier used to drop
     * an entire overlap; a genuine crossing still fails, because there almost
     * every station is out of range.
     */
    static final double PARALLEL_CORRIDOR_OUTLIER_FRACTION = 0.1D;
    /**
     * 0.8.369 (T2b): how close the two runs' lengths must be before their
     * stations are put in correspondence. Superseded in 0.8.370 by pairing
     * through the real nearest-neighbour map ({@code targets}), which needs no
     * length-ratio gate because every station is matched to the primary station
     * it actually sits closest to.
     */
    static final double PARALLEL_CORRIDOR_MIN_LENGTH_RATIO = 0.6D;
    static final long SNAP_DIST2 = (long) SNAP_THRESHOLD * SNAP_THRESHOLD;
    static final long SPLIT_DIST2 = (long) SPLIT_THRESHOLD * SPLIT_THRESHOLD;
    /** Generous reach so a pair that could still connect is never skipped. The
     *  real snap reach is ~24 blocks (grid radius + road width); we reject only
     *  roads whose bounding boxes are clearly farther apart than that. */
    static final int SPATIAL_REJECT_MARGIN = SNAP_THRESHOLD * 4;
    static final Object RECONCILIATION_LOCK = new Object();
    /** Only reconciliation passes slower than this are logged at INFO. */
    static final long SNAP_TIMING_LOG_MS = 250L;

    public static void snapAllRoads(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        if (level == null) return;
        synchronized (RECONCILIATION_LOCK) {
            int margin = SPLIT_THRESHOLD;
            List<RoadData> roads = RoadShardStorage.queryRect(level,
                    minX - margin, minZ - margin, maxX + margin, maxZ + margin);
            if (roads == null || roads.size() < 2) return;
            snapRoadList(level, roads);
        }
    }

    public static void snapAroundConnection(ServerLevel level, BlockPos from, BlockPos to) {
        if (level == null || from == null || to == null) return;
        synchronized (RECONCILIATION_LOCK) {
            int minX = Math.min(from.getX(), to.getX());
            int minZ = Math.min(from.getZ(), to.getZ());
            int maxX = Math.max(from.getX(), to.getX());
            int maxZ = Math.max(from.getZ(), to.getZ());
            int margin = SPLIT_THRESHOLD * 2;
            List<RoadData> roads = RoadShardStorage.queryRect(level,
                    minX - margin, minZ - margin, maxX + margin, maxZ + margin);
            if (roads == null || roads.size() < 2) return;
            snapRoadList(level, roads);
        }
    }

    public static CompletableFuture<Void> snapAroundConnectionAsync(ServerLevel level, BlockPos from, BlockPos to) {
        if (level == null || from == null || to == null || RoadLifecycle.isStopping()) {
            return CompletableFuture.completedFuture(null);
        }
        int minX = Math.min(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int margin = SPLIT_THRESHOLD * 2;
        long epoch = ThreadPoolManager.currentEpoch();
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        return RoadShardStorage.queryRectAsync(level,
                minX - margin, minZ - margin, maxX + margin, maxZ + margin)
                .thenAcceptAsync(ignored -> {
                    if (!ThreadPoolManager.isEpoch(epoch) || RoadLifecycle.isStopping()) return;
                    // P0-6a: the whole pass now runs on the postprocess lane. The
                    // reconciliation lock already serialises passes and makes the
                    // result deterministic; RoadShardStorage.replaceRoads is
                    // worker-safe (PathFeature writes from worldgen threads) and
                    // MapPatchService.publishRoads schedules its own broadcast on
                    // the server thread.
                    synchronized (RECONCILIATION_LOCK) {
                        snapAroundConnection(level, from, to);
                    }
                }, ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.POSTPROCESS));
    }

    /** Reconciles all persisted routes once, without loading any chunk. */
    public static CompletableFuture<Void> snapAllRoadsAsync(ServerLevel level) {
        if (level == null || RoadLifecycle.isStopping()) return CompletableFuture.completedFuture(null);
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        long epoch = ThreadPoolManager.currentEpoch();
        return CompletableFuture.supplyAsync(() -> RoadShardStorage.loadAll(level))
                .thenAcceptAsync(roads -> {
                    if (!ThreadPoolManager.isEpoch(epoch) || RoadLifecycle.isStopping()
                            || roads == null || roads.size() < 2) return;
                    synchronized (RECONCILIATION_LOCK) {
                        snapRoadList(level, roads);
                    }
                }, ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.POSTPROCESS));
    }

    static void snapRoadList(ServerLevel level, List<RoadData> roads) {
        long snapStartedAt = System.nanoTime();
        long[] fingerprints = new long[roads.size()];
        for (int i = 0; i < roads.size(); i++) {
            fingerprints[i] = RoadShardStorage.computeFingerprint(roads.get(i));
        }
        RoadBounds[] bounds = new RoadBounds[roads.size()];
        for (int i = 0; i < roads.size(); i++) {
            bounds[i] = roadBounds(roads.get(i));
        }

        boolean[] modified = new boolean[roads.size()];
        RoadData[] current = roads.toArray(new RoadData[0]);

        // Road storage order is the ownership order: an earlier road is read-only,
        // and only a later road may be shortened to join it.
        for (int primaryIdx = 0; primaryIdx < current.length; primaryIdx++) {
            RoadData primary = current[primaryIdx];
            if (!isUsable(primary)) continue;

            for (int secondaryIdx = primaryIdx + 1; secondaryIdx < current.length; secondaryIdx++) {
                RoadData originalSecondary = current[secondaryIdx];
                if (!isUsable(originalSecondary)) continue;
                // Two roads that are clearly far apart can never share a
                // junction/crossing. Skip the pair entirely instead of running
                // the whole grid-aware matching + validateFinalRoad for it.
                // This changes no result, only avoids impossible pairs.
                if (bounds[primaryIdx] != null && bounds[secondaryIdx] != null
                        && bounds[primaryIdx].gap(bounds[secondaryIdx]) > SPATIAL_REJECT_MARGIN) {
                    continue;
                }

                RoadData connected = connectSecondaryToPrimary(primary, originalSecondary);
                if (connected == null) continue;

                RoadData existingBest = current[secondaryIdx];
                // Existing persisted routes may already contain a long reuse
                // corridor. Never replace that with a shorter endpoint-only
                // candidate just because this is the first pass over it.
                if (!preferReuseCandidate(connected, existingBest)) continue;

                RoadData loweredPrimary = lowerPrimaryTunnelGradeAtJunction(primary, connected);
                if (loweredPrimary != primary) {
                    current[primaryIdx] = loweredPrimary;
                    modified[primaryIdx] = true;
                    primary = loweredPrimary;
                }
                current[secondaryIdx] = connected;
                modified[secondaryIdx] = true;
            }
        }

        List<RoadReplacement> replacements = new ArrayList<>();
        TerrainSamplingCache refinementCache = null;
        net.shiroha233.roadweaver.compat.StreamsReflowingCompat.SamplingSession streams =
                net.shiroha233.roadweaver.compat.StreamsReflowingCompat.openSession(level);
        try {
            for (int i = 0; i < current.length; i++) {
                if (!modified[i]) continue;
                if (refinementCache == null) refinementCache = new TerrainSamplingCache();
                current[i] = enforceNetworkReuseConstructionOwnership(
                        PathSpanExtractor.refineRoadTunnels(current[i], level, refinementCache));
                net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator.ValidationResult
                        waterValidation =
                        net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator.validate(
                                level, current[i], refinementCache, streams);
                if (!waterValidation.safe()) {
                    var collision = waterValidation.collision();
                    // 0.8.322：循环标记保护缺口（旧实现只标一格，多个水洼时整条路仍被丢弃）。
                    // lambda 只能捕获 effectively-final 变量，这里先取一个局部副本。
                    final TerrainSamplingCache validationCache = refinementCache;
                    RoadData gapped = collision != null
                            && "ordinary_construction_in_water".equals(waterValidation.reason())
                            ? net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator
                                    .retainProtectedGaps(current[i],
                                            net.shiroha233.roadweaver.pathlogic.validation
                                                    .FinalRoadWaterValidator.MAX_PROTECTED_GAP_FALLBACK,
                                            candidate -> net.shiroha233.roadweaver.pathlogic.validation
                                                    .FinalRoadWaterValidator.validate(
                                                            level, candidate, validationCache, streams))
                            : null;
                    if (gapped == null) {
                        LOGGER.warn("RoadWeaver discarded underwater post-load road snap owners={} -> {} "
                                + "reason={} collision={}",
                                current[i].ownerA2dKey(), current[i].ownerB2dKey(),
                                waterValidation.reason(), waterValidation.collision());
                        continue;
                    }
                    current[i] = gapped;
                    LOGGER.warn("RoadWeaver retained post-load river protection gaps owners={} -> {} "
                                    + "firstSegment={} pos={}", current[i].ownerA2dKey(), current[i].ownerB2dKey(),
                            collision.segmentIndex(), collision.position());
                }
                net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator.ValidationResult
                        structureValidation;
                try {
                    structureValidation =
                            net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator.validate(
                                    level, current[i], refinementCache, streams);
                } catch (net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotUnavailableException unavailable) {
                    LOGGER.warn("RoadWeaver skipped post-load road snap because its exact structure snapshot is unavailable "
                                    + "owners={} -> {} reason={}",
                            current[i].ownerA2dKey(), current[i].ownerB2dKey(), unavailable.getMessage());
                    continue;
                }
                if (!structureValidation.safe()) {
                    LOGGER.warn("RoadWeaver discarded unsafe post-load road snap owners={} -> {} collision={}",
                            current[i].ownerA2dKey(), current[i].ownerB2dKey(),
                            structureValidation.collision());
                    continue;
                }
                replacements.add(new RoadReplacement(fingerprints[i], current[i]));
            }
        } finally {
            if (refinementCache != null) refinementCache.clear();
        }
        if (replacements.isEmpty()) {
            logSnapTiming(roads.size(), 0, (System.nanoTime() - snapStartedAt) / 1_000_000L);
            return;
        }

        try {
            RoadShardStorage.replaceRoads(level, replacements);
            if (RoadLifecycle.allowsWrites()) {
                MapPatchService.publishRoads(level, replacements.stream()
                        .map(RoadReplacement::newRoad)
                        .toList());
            }
        } catch (Exception e) {
            LOGGER.error("RoadWeaver failed to persist direct road junctions", e);
        }
        logSnapTiming(roads.size(), replacements.size(), (System.nanoTime() - snapStartedAt) / 1_000_000L);
    }

    /** Runtime reconciliation runs once per completed road, so only a slow pass
     *  is worth an INFO line; the rest stays at debug level. */
    static void logSnapTiming(int roads, int replaced, long elapsedMs) {
        if (elapsedMs < SNAP_TIMING_LOG_MS) {
            LOGGER.debug("RoadWeaver saved-road reconciliation roads={} replaced={} elapsedMs={}",
                    roads, replaced, elapsedMs);
            return;
        }
        LOGGER.info("RoadWeaver saved-road reconciliation roads={} replaced={} elapsedMs={}",
                roads, replaced, elapsedMs);
    }

    /**
     * Returns a replacement for {@code secondary}, or {@code null} when a safe
     * direct junction cannot be proven. {@code primary} is never modified.
     */
    static void smoothTunnelBoundary(List<Integer> targetY, RoadData road,
                                             int boundary, int direction,
                                             int transitionSegments) {
        if (boundary < 0 || boundary >= targetY.size()) return;
        int previous = boundary;
        for (int distance = 1, index = boundary + direction;
             distance <= Math.max(JUNCTION_ANCHOR_SEGMENTS, transitionSegments)
                     && index >= 0 && index < targetY.size();
             distance++, index += direction) {
            if (!isIndexInSpan(road, index, SpanType.TUNNEL)) break;
            int current = targetY.get(index);
            int previousValue = targetY.get(previous);
            if (current <= previousValue + 1) break;
            targetY.set(index, previousValue + 1);
            previous = index;
        }
    }

    static boolean preferReuseCandidate(RoadData candidate, RoadData existing) {
        if (candidate == null) return false;
        if (existing == null) return true;
        int candidateReuse = networkReuseStationCount(candidate);
        int existingReuse = networkReuseStationCount(existing);
        if (candidateReuse != existingReuse) return candidateReuse > existingReuse;
        return ordinaryConstructionLength(candidate) < ordinaryConstructionLength(existing);
    }

    static List<Integer> repairTunnelGradeJoins(RoadData road, List<Integer> targetY) {
        List<Integer> repaired = new ArrayList<>(targetY);
        for (int i = 1; i < repaired.size(); i++) {
            if (!isIndexInSpan(road, i - 1, SpanType.TUNNEL)
                    || !isIndexInSpan(road, i, SpanType.TUNNEL)) continue;
            int delta = repaired.get(i) - repaired.get(i - 1);
            if (Math.abs(delta) > RoadConstants.MAX_HEIGHT_STEP) {
                repaired.set(i, repaired.get(i - 1) + Integer.signum(delta) * RoadConstants.MAX_HEIGHT_STEP);
            }
        }
        return List.copyOf(repaired);
    }

    /**
     * Junction assembly can inherit a span from the secondary route and then
     * add the same semantic span while marking the new anchor.  The duplicate
     * carries no extra construction information, but the final validator must
     * reject duplicate span records.  Normalize only exact type/index matches;
     * overlapping physical spans remain visible to validation.
     */
    public static RoadData deduplicateExactSpans(RoadData road) {
        if (road == null || road.spans() == null || road.spans().size() < 2
                || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()) return road;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        Set<String> seen = new HashSet<>();
        List<RoadSpan> unique = new ArrayList<>(road.spans().size());
        boolean changed = false;
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null || span.type() == null) {
                unique.add(span);
                continue;
            }
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) {
                unique.add(span);
                continue;
            }
            String key = span.type() + ":" + Math.min(start, end) + ":" + Math.max(start, end);
            if (seen.add(key)) {
                unique.add(span);
            } else {
                changed = true;
            }
        }
        if (!changed) return road;
        LOGGER.info("RoadWeaver removed duplicate junction span records owners={} -> {} removed={}",
                road.ownerA2dKey(), road.ownerB2dKey(), road.spans().size() - unique.size());
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), unique, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    /**
     * A NETWORK_REUSE span copies exact stations from an earlier, already
     * owned road.  It is not a second construction corridor.  Drop secondary
     * bridge/tunnel/crossing spans that intersect it before the road is saved,
     * so later refinement cannot create a second deck, pier, excavation, or
     * material layer over the owner road.
     */
    public static RoadData enforceNetworkReuseConstructionOwnership(RoadData road) {
        return enforceNetworkReuseConstructionOwnership(road, null);
    }

    public static RoadData enforceNetworkReuseConstructionOwnership(RoadData road, RoadData owner) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()
                || road.spans() == null || road.spans().isEmpty()) return road;

        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) {
                indices.put(xzKey(segment.middlePos()), i);
            }
        }
        boolean[] reused = new boolean[road.roadSegmentList().size()];
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.NETWORK_REUSE
                    || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) reused[i] = true;
        }

        List<RoadSpan> retained = new ArrayList<>(road.spans().size());
        int bridges = 0;
        int tunnels = 0;
        int crossings = 0;
        for (RoadSpan span : road.spans()) {
            if (!isSecondaryConstructionSpan(span) || !intersectsReuse(span, indices, reused)) {
                retained.add(span);
                continue;
            }
            if (owner == null || !ownerHasSameTypeSpanAt(owner, span)) {
                retained.add(span);
                continue;
            }
            switch (span.type()) {
                case BRIDGE -> bridges++;
                case TUNNEL -> tunnels++;
                case ROAD_CROSSING -> crossings++;
                default -> { }
            }
        }
        if (retained.size() == road.spans().size()) return road;
        LOGGER.info("RoadWeaver network reuse construction suppressed owners={} -> {} bridges={} tunnels={} crossings={}",
                road.ownerA2dKey(), road.ownerB2dKey(), bridges, tunnels, crossings);
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), List.copyOf(retained), road.targetY(),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    static boolean isSecondaryConstructionSpan(RoadSpan span) {
        if (span == null || span.type() == null) return false;
        return span.type() == SpanType.BRIDGE
                || span.type() == SpanType.TUNNEL
                || span.type() == SpanType.ROAD_CROSSING;
    }

    static boolean intersectsReuse(RoadSpan span, Map<Long, Integer> indices, boolean[] reused) {
        if (span == null || span.start() == null || span.end() == null) return false;
        Integer start = indices.get(xzKey(span.start()));
        Integer end = indices.get(xzKey(span.end()));
        if (start == null || end == null) return false;
        for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
            if (reused[i]) return true;
        }
        return false;
    }

    static RoadData tryConnect(RoadData primary,
                                       SegmentGrid primaryGrid,
                                       boolean[] primarySpecial,
                                       RoadData secondary) {
        List<RoadSegmentPlacement> secSegs = secondary.roadSegmentList();
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        int n = secSegs.size();

        boolean[] secondarySpecial = junctionBlockedSpanMask(secondary);
        int[] snapTarget = markSnapTargets(primary, secondary, secSegs, priSegs, primaryGrid, secondarySpecial);
        List<int[]> runs = extractRuns(snapTarget, n);
        List<int[]> junctionRuns = extractJunctionRuns(primary, secondary, snapTarget);
        long sharedOwner = secondary.sharedOwnerWith(primary);
        List<ConnectionOption> options = new ArrayList<>();

        // 0.8.409 禁麻花：两条路方向近平行（危险区）且无法合并成共享走廊时，
        // 本连接会织成并排重叠的麻花。禁麻花开且检测到麻花时，整段连接跳过，
        // 两条路保持独立（宁可两条路不连，也不织麻花）。丁字/十字（正交）不受影响。
        if (!ConfigService.get().pathfindingCost().braidAllowParallelOverlap()
                && detectsBraid(primary, secondary, snapTarget, runs)) {
            LOGGER.info("RoadWeaver braid suppressed primary={} secondary={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary));
            return null;
        }

        addConnectionOption(options, "endpoint-corridor",
                tryAbsorbEndpointCorridor(primary, secondary, snapTarget, runs), secondary);
        addConnectionOption(options, "two-junctions",
                tryReuseBetweenTwoJunctions(primary, secondary, snapTarget, junctionRuns), secondary);
        if (sharedOwner != RoadData.NO_OWNER_2D) {
            boolean sharedAtStart = secondary.ownerA2dKey() == sharedOwner
                    && secondary.ownerB2dKey() != sharedOwner;
            boolean sharedAtEnd = secondary.ownerB2dKey() == sharedOwner
                    && secondary.ownerA2dKey() != sharedOwner;
            if (sharedAtStart || sharedAtEnd) {
                RoadData sharedEndpointCandidate = tryConnectEndpoint(
                        primary, primarySpecial, secondary, secondarySpecial,
                                snapTarget, runs, sharedAtStart, sharedOwner,
                                sharedAtStart == (primary.ownerA2dKey() == sharedOwner
                                        && primary.ownerB2dKey() != sharedOwner));
                addConnectionOption(options, "shared-endpoint", sharedEndpointCandidate, secondary);
            }
        }

        // A later road may join any earlier completed road when one of its
        // endpoints follows that road closely. Requiring a shared structure
        // owner left parallel duplicate roads even though their geometry was
        // already a safe T-junction candidate.
        RoadData atStart = tryConnectEndpoint(primary, primarySpecial, secondary, secondarySpecial,
                snapTarget, runs, true, RoadData.NO_OWNER_2D, null);
        RoadData atEnd = tryConnectEndpoint(primary, primarySpecial, secondary, secondarySpecial,
                snapTarget, runs, false, RoadData.NO_OWNER_2D, null);
        RoadData overlap = tryMergeOverlappingRun(primary, secondary, snapTarget, runs);
        addConnectionOption(options, "spatial-start", atStart, secondary);
        addConnectionOption(options, "spatial-end", atEnd, secondary);
        addConnectionOption(options, "parallel-overlap", overlap, secondary);
        addConnectionOption(options, "two-approaches",
                tryReuseBetweenTwoApproaches(primary, secondary, snapTarget, runs), secondary);

        ConnectionOption best = null;
        for (ConnectionOption option : options) {
            if (best == null || option.betterThan(best)) best = option;
        }
        if (best == null) return null;
        LOGGER.info("RoadWeaver selected snap candidate mode={} primary={} secondary={} "
                        + "constructionLength={} -> {} reuseStations={} segments={}->{}",
                best.mode(), RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                ordinaryConstructionLength(secondary), best.constructionLength(),
                best.reuseStations(), secondary.roadSegmentList().size(),
                best.road().roadSegmentList().size());
        RoadData trimmed = trimSecondaryTailAtJunction(primary, best.road(), best.mode());
        RoadData result = trimmed == null ? best.road() : trimmed;
        RoadData headTrimmed = trimSecondaryHeadAtJunction(primary, result);
        return headTrimmed == null ? result : headTrimmed;
    }

    /** 0.8.319：头部裁剪的重合容差（格）。真机共享端点那对相差 1~2 格。 */
    static final int HEAD_TRIM_TOLERANCE = 2;

    /**
     * 0.8.319：共享端点合并后，secondary 的<b>头部</b>仍可能有一整段贴在 primary
     * 走廊上，而合并只把"交汇锚点之后"标成 NETWORK_REUSE，于是地图把两段都画出来、
     * 施工也铺两遍（真机 (992,-1024)↔(1425,-1179) 与 (1280,-352)↔(992,-1024)：
     * A 的前 20 站与 B 的尾部只差 1 格，图上就是村口那处交叉扭曲）。
     *
     * <p>做法与既有的 {@link #trimSecondaryTailAtJunction} 对称：从头开始吃掉
     * "落在 primary 走廊内（切比雪夫距离 ≤ {@value #HEAD_TRIM_TOLERANCE}）且不是任何
     * span 端点"的连续站点，剩下的站点、targetY、span 一起重建；裁完必须能通过
     * {@link RoadDataValidator#validateFinalRoad}，否则返回 null 让调用方保留原样。</p>
     *
     * <p>不改变合并方案的选择，只清理已经重合的头部——因此不会影响其它路的合并结果。</p>
     */
    static RoadData trimSecondaryHeadAtJunction(RoadData primary, RoadData candidate) {
        if (primary == null || candidate == null) return null;
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        List<RoadSegmentPlacement> secSegs = candidate.roadSegmentList();
        if (priSegs == null || secSegs == null || secSegs.size() < 7) return null;

        Set<Long> primaryStations = new HashSet<>();
        for (RoadSegmentPlacement segment : priSegs) {
            if (segment != null && segment.middlePos() != null) {
                primaryStations.add(xzKey(segment.middlePos()));
            }
        }
        if (primaryStations.isEmpty()) return null;

        Set<Long> spanEndpoints = new HashSet<>();
        for (RoadSpan span : candidate.spans()) {
            if (span == null) continue;
            if (span.start() != null) spanEndpoints.add(xzKey(span.start()));
            if (span.end() != null) spanEndpoints.add(xzKey(span.end()));
        }

        int n = secSegs.size();
        int head = 0;
        while (head < n) {
            RoadSegmentPlacement station = secSegs.get(head);
            if (station == null || station.middlePos() == null) break;
            BlockPos pos = station.middlePos();
            if (spanEndpoints.contains(xzKey(pos))) break;
            if (!nearPrimaryStation(primaryStations, pos.getX(), pos.getZ())) break;
            head++;
        }
        // 至少 2 站才算"一段重复的头"；裁完还要留够长度。
        if (head < 2 || n - head < 5) return null;

        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement station = secSegs.get(i);
            if (station != null && station.middlePos() != null) {
                indices.putIfAbsent(xzKey(station.middlePos()), i);
            }
        }
        List<RoadSpan> retainedSpans = new ArrayList<>();
        for (RoadSpan span : candidate.spans()) {
            if (span == null) continue;
            Integer start = span.start() == null ? null : indices.get(xzKey(span.start()));
            Integer end = span.end() == null ? null : indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            if (Math.min(start, end) < head) continue;
            retainedSpans.add(span);
        }
        List<RoadSegmentPlacement> retainedSegments = new ArrayList<>(secSegs.subList(head, n));
        List<Integer> retainedTargetY = new ArrayList<>(candidate.targetY().subList(head, n));
        RoadData trimmed = new RoadData(candidate.width(), candidate.roadType(),
                candidate.materials(), candidate.slabMaterials(),
                retainedSegments, List.copyOf(retainedSpans), retainedTargetY,
                candidate.ownerA2dKey(), candidate.ownerB2dKey());
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(trimmed);
        if (!validation.valid()) {
            LOGGER.warn("RoadWeaver kept secondary head: trimmed route failed validation reason={} index={} pos={} detail={} "
                            + "primary={} secondary={} head={}",
                    validation.reason(), validation.index(), validation.position(), validation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), head);
            return null;
        }
        BlockPos startPos = retainedSegments.get(0).middlePos();
        LOGGER.info("RoadWeaver trimmed secondary head at junction primary={} secondary={} "
                        + "start=({},{}) segments={} -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate),
                startPos.getX(), startPos.getZ(), n, retainedSegments.size());
        return trimmed;
    }

    /** 头部重合判定：{@code (x,z)} 在 primary 任一站点 {@value #HEAD_TRIM_TOLERANCE} 格内。 */
    static boolean nearPrimaryStation(Set<Long> primaryStations, int x, int z) {
        for (int dx = -HEAD_TRIM_TOLERANCE; dx <= HEAD_TRIM_TOLERANCE; dx++) {
            for (int dz = -HEAD_TRIM_TOLERANCE; dz <= HEAD_TRIM_TOLERANCE; dz++) {
                if (primaryStations.contains(xzKey(new BlockPos(x + dx, 0, z + dz)))) return true;
            }
        }
        return false;
    }

    /** Modes whose junction is an endpoint attachment; reuse-corridor modes keep their tails. */
    static boolean isEndpointAttachmentMode(String mode) {
        return "spatial-start".equals(mode) || "spatial-end".equals(mode)
                || "shared-endpoint".equals(mode) || "endpoint-corridor".equals(mode);
    }

    /**
     * After a secondary road is attached to a primary, its rebuilt segment list
     * can still end with stations that lie on top of the primary corridor past
     * the junction. Find the trailing run of stations whose x/z sits on the
     * primary, keep everything up to its first station (the junction) and drop
     * the rest — segments, target heights and spans are cut together. Never
     * trims a tail that carries NETWORK_REUSE / JUNCTION_ANCHOR spans, never
     * drops below the minimum route length, and keeps the original candidate
     * (WARN) whenever the trimmed result would not validate.
     */
    static RoadData trimSecondaryTailAtJunction(RoadData primary, RoadData candidate, String mode) {
        if (primary == null || candidate == null || !isEndpointAttachmentMode(mode)) return null;
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        List<RoadSegmentPlacement> secSegs = candidate.roadSegmentList();
        if (priSegs == null || secSegs == null || secSegs.size() < 5) return null;
        Set<Long> primaryStations = new HashSet<>();
        for (RoadSegmentPlacement segment : priSegs) {
            if (segment != null && segment.middlePos() != null) {
                primaryStations.add(xzKey(segment.middlePos()));
            }
        }
        int n = secSegs.size();
        int junction = n;
        while (junction - 1 >= 0) {
            RoadSegmentPlacement station = secSegs.get(junction - 1);
            if (station == null || station.middlePos() == null
                    || !primaryStations.contains(xzKey(station.middlePos()))) {
                break;
            }
            junction--;
        }
        // No terminal overlap on the primary (or a single junction station):
        // nothing pressing on the primary, nothing to trim.
        if (junction >= n - 1 || n - junction < 2) return null;
        if (junction + 1 < 5) {
            LOGGER.warn("RoadWeaver kept secondary tail: trimming would leave too few segments "
                            + "primary={} secondary={} junction={} segments={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), junction, n);
            return null;
        }
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement station = secSegs.get(i);
            if (station != null && station.middlePos() != null) {
                indices.putIfAbsent(xzKey(station.middlePos()), i);
            }
        }
        List<RoadSpan> retainedSpans = new ArrayList<>();
        for (RoadSpan span : candidate.spans()) {
            if (span == null) continue;
            Integer start = span.start() == null ? null : indices.get(xzKey(span.start()));
            Integer end = span.end() == null ? null : indices.get(xzKey(span.end()));
            int maxIndex = Math.max(start == null ? -1 : start, end == null ? -1 : end);
            if (maxIndex > junction) {
                if (span.type() == SpanType.NETWORK_REUSE
                        || span.type() == SpanType.JUNCTION_ANCHOR) {
                    // Junction metadata in the tail: trimming would cut the
                    // anchor the rest of the route depends on. Keep as-is.
                    LOGGER.debug("RoadWeaver kept secondary tail: junction span {} extends past "
                            + "junction {}", span.type(), junction);
                    return null;
                }
                continue;
            }
            retainedSpans.add(span);
        }
        List<RoadSegmentPlacement> retainedSegments = new ArrayList<>(secSegs.subList(0, junction + 1));
        List<Integer> retainedTargetY = new ArrayList<>(candidate.targetY().subList(0, junction + 1));
        RoadData trimmed = new RoadData(candidate.width(), candidate.roadType(),
                candidate.materials(), candidate.slabMaterials(),
                retainedSegments, List.copyOf(retainedSpans), retainedTargetY,
                candidate.ownerA2dKey(), candidate.ownerB2dKey());
        RoadDataValidator.ValidationResult trimmedValidation =
                RoadDataValidator.validateFinalRoad(trimmed);
        if (!trimmedValidation.valid()) {
            LOGGER.warn("RoadWeaver kept secondary tail: trimmed route failed validation reason={} index={} pos={} detail={} "
                            + "primary={} secondary={} junction={}",
                    trimmedValidation.reason(), trimmedValidation.index(),
                    trimmedValidation.position(), trimmedValidation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), junction);
            return null;
        }
        BlockPos junctionPos = retainedSegments.get(retainedSegments.size() - 1).middlePos();
        LOGGER.info("RoadWeaver trimmed secondary tail at junction primary={} secondary={} "
                        + "junction=({},{}) segments={} -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate),
                junctionPos.getX(), junctionPos.getZ(), n, retainedSegments.size());
        return trimmed;
    }

    static void addConnectionOption(List<ConnectionOption> options,
                                            String mode,
                                            RoadData candidate,
                                            RoadData secondary) {
        if (candidate == null || candidate.equals(secondary)) return;
        RoadData normalized = deduplicateExactSpans(candidate);
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(normalized);
        if (!validation.valid()) {
            LOGGER.debug("RoadWeaver rejected snap option mode={} reason={} index={} pos={} detail={}",
                    mode, validation.reason(), validation.index(), validation.position(), validation.detail());
            return;
        }
        double constructionLength = ordinaryConstructionLength(normalized);
        double originalLength = ordinaryConstructionLength(secondary);
        double constructionLimit = originalLength + MAX_SNAP_CONSTRUCTION_OVERHEAD;
        if (constructionLength > constructionLimit) {
            LOGGER.debug("RoadWeaver rejected snap option mode={} constructionLength={} limit={}",
                    mode, constructionLength, constructionLimit);
            return;
        }
        options.add(new ConnectionOption(mode, normalized,
                constructionLength, networkReuseStationCount(normalized)));
    }

    /**
     * Logs the exact X/Z where a secondary road is attached to a primary road
     * and both roads' target heights at that junction. This makes a staircase
     * or height-mismatch at a road crossing directly traceable: a large
     * |roadY delta| plus a short anchor window is what produces the visible
     * step at the intersection.
     */
    static void logJunctionConnection(RoadData primary, RoadData secondary,
                                              JunctionCandidate junction,
                                              boolean sharedAtStart) {
        if (primary == null || secondary == null || junction == null) return;
        BlockPos priPos = null;
        BlockPos secPos = null;
        if (primary.roadSegmentList() != null && junction.primaryIndex() >= 0
                && junction.primaryIndex() < primary.roadSegmentList().size()) {
            RoadSegmentPlacement p = primary.roadSegmentList().get(junction.primaryIndex());
            if (p != null) priPos = p.middlePos();
        }
        if (secondary.roadSegmentList() != null && junction.secondaryIndex() >= 0
                && junction.secondaryIndex() < secondary.roadSegmentList().size()) {
            RoadSegmentPlacement s = secondary.roadSegmentList().get(junction.secondaryIndex());
            if (s != null) secPos = s.middlePos();
        }
        int priY = targetAt(primary, junction.primaryIndex());
        int secY = targetAt(secondary, junction.secondaryIndex());
        LOGGER.info("RoadWeaver junction+connect primary={} secondary={} junction=({},{}) "
                        + "idx={}/{} roadY={}->{} delta={} side={}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                priPos == null ? "?" : priPos.getX() + "," + priPos.getZ(),
                secPos == null ? "?" : secPos.getX() + "," + secPos.getZ(),
                junction.primaryIndex(), junction.secondaryIndex(),
                priY, secY, Math.abs(priY - secY), sharedAtStart ? "start" : "end");
    }

    static double ordinaryConstructionLength(RoadData road) {
        if (road == null || road.roadSegmentList() == null) return Double.POSITIVE_INFINITY;
        boolean[] reused = spanMask(road, SpanType.NETWORK_REUSE);
        double length = 0.0D;
        for (int i = 1; i < road.roadSegmentList().size(); i++) {
            BlockPos previous = road.roadSegmentList().get(i - 1).middlePos();
            BlockPos current = road.roadSegmentList().get(i).middlePos();
            if (previous == null || current == null) return Double.POSITIVE_INFINITY;
            if (reused[i - 1] && reused[i]) continue;
            length += Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ());
        }
        return length;
    }

    public static int networkReuseStationCount(RoadData road) {
        boolean[] reused = spanMask(road, SpanType.NETWORK_REUSE);
        int count = 0;
        for (boolean value : reused) if (value) count++;
        return count;
    }

    /** P0-post B2: delegates to the per-road cached mask; read-only. */
    static boolean[] spanMask(RoadData road, SpanType type) {
        if (road == null || type == null) return new boolean[0];
        return roadIndex(road).spanMask(type);
    }

    /**
     * A later edge may already start on an earlier road and remain in the same
     * corridor until their shared destination. In that case the whole owner
     * slice is the physical road; retaining a short anchor plus a parallel
     * secondary corridor creates the wide two-material road this service is
     * intended to prevent.
     */
    static RoadData tryAbsorbEndpointCorridor(RoadData primary,
                                                      RoadData secondary,
                                                      int[] targets,
                                                      List<int[]> runs) {
        if (primary == null || secondary == null || targets == null || runs == null
                || secondary.roadSegmentList().size() < OVERLAP_MERGE_MIN_RUN) return null;
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        int[] corridor = null;
        for (int[] run : runs) {
            if (run != null && run.length >= 2
                    && run[0] <= ENDPOINT_RUN_MAX_OFFSET
                    && run[1] >= sec.size() - 1 - ENDPOINT_RUN_MAX_OFFSET) {
                corridor = run;
                break;
            }
        }
        if (corridor == null) return null;

        int secFrom = Math.max(0, corridor[0]);
        int secTo = Math.min(sec.size() - 1, corridor[1]);
        int primaryFrom = targets[secFrom];
        int primaryTo = targets[secTo];
        if (primaryFrom < 0 || primaryTo < 0 || primaryFrom == primaryTo
                || !monotonicTargetRun(targets, secFrom, secTo, Integer.signum(primaryTo - primaryFrom))) {
            return null;
        }

        boolean[] primaryBlocked = junctionBlockedSpanMask(primary);
        boolean[] secondaryBlocked = junctionBlockedSpanMask(secondary);
        if (!endpointCorridorBoundaryCompatible(
                primary, primaryFrom, primaryBlocked, secondary, secFrom, secondaryBlocked)
                || !endpointCorridorBoundaryCompatible(
                primary, primaryTo, primaryBlocked, secondary, secTo, secondaryBlocked)) return null;

        int primaryCount = Math.abs(primaryTo - primaryFrom) + 1;
        int secondaryCount = secTo - secFrom + 1;
        if (primaryCount < OVERLAP_MERGE_MIN_RUN
                || primaryCount > secondaryCount * 2 + SNAP_THRESHOLD * 2
                || !corridorCoverageCompatible(primary, primaryFrom, primaryTo, secondary)) return null;

        List<RoadSegmentPlacement> merged = new ArrayList<>();
        List<Integer> mergedY = new ArrayList<>();
        appendRange(secondary, 0, secFrom + 1, merged, mergedY);

        boolean startExact = sameCenter(
                sec.get(secFrom).middlePos(), pri.get(primaryFrom).middlePos());
        if (startExact) {
            merged.removeLast();
            mergedY.removeLast();
        } else if (!appendApproachConnector(merged, mergedY, secondary.width(),
                pri.get(primaryFrom), targetAt(primary, primaryFrom))) {
            return null;
        }

        int reuseStart = merged.size();
        appendDirected(primary, primaryFrom, primaryTo, merged, mergedY);
        int reuseEnd = merged.size() - 1;

        boolean endExact = sameCenter(
                pri.get(primaryTo).middlePos(), sec.get(secTo).middlePos());
        if (endExact) {
            appendRange(secondary, secTo + 1, sec.size(), merged, mergedY);
        } else {
            if (!appendApproachConnector(merged, mergedY, secondary.width(),
                    sec.get(secTo), targetAt(secondary, secTo))) return null;
            appendRange(secondary, secTo, sec.size(), merged, mergedY);
        }
        removeConsecutiveDuplicateCenters(merged, mergedY);
        if (!validMergedSequence(merged, mergedY)) return null;

        List<RoadSpan> spans = new ArrayList<>(
                filterSecondarySpansOutsideRange(secondary, secFrom, secTo, merged));
        addClippedOwnerSpans(spans, secondary, secFrom, secTo, merged);
        int pMin = Math.min(primaryFrom, primaryTo);
        int pMax = Math.max(primaryFrom, primaryTo);
        addClippedOwnerSpans(spans, primary, pMin, pMax, merged);
        addNetworkReuse(spans, merged, reuseStart, reuseEnd);
        if (!startExact && reuseStart > 0) {
            addJunctionMarker(spans, merged, reuseStart - 1, reuseStart, primary, primaryFrom);
        }
        if (!endExact && reuseEnd + 1 < merged.size()) {
            addJunctionMarker(spans, merged, reuseEnd, reuseEnd + 1, primary, primaryTo);
        }
        return new RoadData(secondary.width(), secondary.roadType(), secondary.materials(),
                secondary.slabMaterials(), merged, spans, mergedY,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());
    }

    static boolean endpointCorridorBoundaryCompatible(RoadData primary,
                                                               int primaryIndex,
                                                               boolean[] primaryBlocked,
                                                               RoadData secondary,
                                                               int secondaryIndex,
                                                               boolean[] secondaryBlocked) {
        BlockPos a = primary.roadSegmentList().get(primaryIndex).middlePos();
        BlockPos b = secondary.roadSegmentList().get(secondaryIndex).middlePos();
        if (a == null || b == null) return false;
        int distance = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        int height = Math.abs(targetAt(primary, primaryIndex) - targetAt(secondary, secondaryIndex));
        if (distance > MAX_APPROACH_CONNECTOR_DISTANCE
                || !gradeCompatibleAtJunction(primary, primaryIndex, secondary, secondaryIndex, distance)) {
            return false;
        }
        if (distance == 0) {
            return isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex)
                    && height <= MAX_TUNNEL_JUNCTION_GRADE_DELTA;
        }
        // The connector between two road ends may rise at most one block per two blocks of run, which is the
        // road's own grade limit. It used to allow one block per block - a 45-degree connector - and with a
        // snap radius of twelve that accepted junctions twelve blocks apart in height, so two roads could
        // meet in a cliff and neither side looked wrong on its own.
        return ((height <= Math.max(1, distance / 2))
                || isTunnelJunctionPair(primary, primaryIndex, secondary, secondaryIndex))
                && !isSpecial(primaryBlocked, primaryIndex)
                && !isSpecial(secondaryBlocked, secondaryIndex);
    }

    static boolean monotonicTargetRun(int[] targets, int from, int to, int step) {
        if (step == 0) return false;
        int previous = targets[from];
        if (previous < 0) return false;
        for (int i = from + 1; i <= to; i++) {
            int current = targets[i];
            if (current < 0 || (current != previous && Integer.signum(current - previous) != step)) return false;
            previous = current;
        }
        return true;
    }

    static boolean corridorCoverageCompatible(RoadData primary,
                                                      int primaryFrom,
                                                      int primaryTo,
                                                      RoadData secondary) {
        SegmentGrid secondaryGrid = buildGrid(secondary, null);
        int step = primaryFrom <= primaryTo ? 1 : -1;
        int total = 0;
        int near = 0;
        for (int index = primaryFrom; ; index += step) {
            RoadSegmentPlacement segment = primary.roadSegmentList().get(index);
            NearestResult nearest = segment == null || segment.middlePos() == null
                    ? null : secondaryGrid.findNearest(segment.middlePos());
            if (nearest == null || nearest.dist2() > SPLIT_DIST2) return false;
            if (nearest.dist2() <= SNAP_DIST2) near++;
            total++;
            if (index == primaryTo) break;
        }
        return total > 0 && near * 10 >= total * 9;
    }

    static boolean sameCenter(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    static List<RoadSpan> filterSecondarySpansOutsideRange(RoadData secondary,
                                                                    int replacedFrom,
                                                                    int replacedTo,
                                                                    List<RoadSegmentPlacement> retained) {
        if (secondary.spans() == null || secondary.spans().isEmpty()) return List.of();
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < secondary.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = secondary.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        Set<Long> retainedKeys = new HashSet<>();
        for (RoadSegmentPlacement segment : retained) {
            if (segment != null && segment.middlePos() != null) retainedKeys.add(xzKey(segment.middlePos()));
        }
        List<RoadSpan> spans = new ArrayList<>();
        for (RoadSpan span : secondary.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (to >= replacedFrom && from <= replacedTo) continue;
            if (retainedKeys.contains(xzKey(span.start())) && retainedKeys.contains(xzKey(span.end()))) {
                spans.add(span);
            }
        }
        return spans;
    }

    static RoadData tryConnectEndpoint(RoadData primary,
                                               boolean[] primarySpecial,
                                               RoadData secondary,
                                               boolean[] secondarySpecial,
                                               int[] snapTarget,
                                               List<int[]> runs,
                                               boolean sharedAtStart,
                                               long sharedOwner,
                                               Boolean knownIncreasingTargets) {
        List<RoadSegmentPlacement> secSegs = secondary.roadSegmentList();
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        int[] endpointRun = selectEndpointRun(runs, secSegs.size(), sharedAtStart);
        Boolean increasingTargets = knownIncreasingTargets;
        JunctionCandidate junction = null;
        if (endpointRun != null) {
            increasingTargets = increasingTargets != null
                    ? increasingTargets : targetIndicesIncrease(snapTarget, endpointRun);
            if (increasingTargets != null) {
                endpointRun = stableEndpointRun(snapTarget, endpointRun, increasingTargets, sharedAtStart);
                if (endpointRun != null) {
                    junction = findSafeJunction(
                            primary, primarySpecial, secondary, secondarySpecial,
                            snapTarget, endpointRun, sharedAtStart);
                }
            }
        }
        if (junction == null && sharedOwner != RoadData.NO_OWNER_2D) {
            junction = findSharedEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
            if (junction == null) {
                junction = findSurfaceTunnelEndpointApproach(
                        primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
            }
            if (increasingTargets == null) {
                increasingTargets = sharedAtStart == (primary.ownerA2dKey() == sharedOwner
                        && primary.ownerB2dKey() != sharedOwner);
            }
        }
        if (junction == null) {
            junction = findSurfaceTunnelEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
        }
        if (junction == null) {
            junction = findHeightTransitionEndpointApproach(
                    primary, primarySpecial, secondary, secondarySpecial, sharedAtStart);
        }
        if (junction != null && increasingTargets == null) {
            // A spatial endpoint join has no snap-target monotonicity array.
            // Anchor construction tries both directions, so a neutral default
            // is sufficient and prevents a valid height-normalized junction
            // from being rejected only because it was discovered spatially.
            increasingTargets = Boolean.TRUE;
        }
        if (junction == null || increasingTargets == null) {
            return null;
        }

        logJunctionConnection(primary, secondary, junction, sharedAtStart);

        boolean tunnelGradeJoin = isTunnelJunctionPair(
                primary, junction.primaryIndex(), secondary, junction.secondaryIndex());
        boolean oneSidedTunnelJoin = !tunnelGradeJoin
                && isTunnelGradeTransitionPair(primary, junction.primaryIndex(), secondary,
                junction.secondaryIndex());
        boolean heightTransitionJoin = !tunnelGradeJoin && !oneSidedTunnelJoin;
        boolean gradeJoin = tunnelGradeJoin || oneSidedTunnelJoin || heightTransitionJoin;
        int tunnelGradeLimit = gradeJoin
                ? tunnelGradeLimit(primary, junction.primaryIndex(), secondary, junction.secondaryIndex())
                : MAX_TUNNEL_JUNCTION_GRADE_DELTA;
        int anchorSegmentCount = gradeJoin
                ? requiredTunnelAnchorSegments(
                targetAt(primary, junction.primaryIndex()), targetAt(secondary, junction.secondaryIndex()))
                : JUNCTION_ANCHOR_SEGMENTS;
        AnchorPlan anchor = buildAnchorPlan(
                priSegs, primary.targetY(), primarySpecial,
                junction.primaryIndex(), increasingTargets, sharedAtStart, anchorSegmentCount);
        if (anchor == null) return null;

        List<RoadSegmentPlacement> connectedSegments = new ArrayList<>();
        List<Integer> connectedY = new ArrayList<>();
        int retainedFrom;
        int retainedTo;
        int anchorFrom;
        int anchorTo;

        if (sharedAtStart) {
            connectedSegments.addAll(anchor.segments());
            connectedY.addAll(anchor.targetY());
            retainedFrom = junction.secondaryIndex() + 1;
            retainedTo = secSegs.size();
            if (!appendApproachConnector(connectedSegments, connectedY,
                    secondary.width(), secSegs.get(retainedFrom), targetAt(secondary, retainedFrom),
                    gradeJoin,
                    tunnelGradeLimit)) {
                return null;
            }
            appendSecondaryRange(secondary, retainedFrom, retainedTo, connectedSegments, connectedY);
            anchorFrom = 0;
            anchorTo = anchor.segments().size() - 1;
        } else {
            retainedFrom = 0;
            retainedTo = junction.secondaryIndex();
            appendSecondaryRange(secondary, retainedFrom, retainedTo, connectedSegments, connectedY);
            if (connectedSegments.isEmpty()) return null;
            int connectorAnchorFrom = connectedSegments.size();
            if (!appendApproachConnector(connectedSegments, connectedY,
                    secondary.width(), anchor.segments().getFirst(), anchor.targetY().getFirst(),
                    gradeJoin,
                    tunnelGradeLimit)) {
                return null;
            }
            anchorFrom = connectedSegments.size();
            connectedSegments.addAll(anchor.segments());
            connectedY.addAll(anchor.targetY());
            anchorTo = connectedSegments.size() - 1;
        }

        if (tunnelGradeJoin) {
            // Pick the lower tunnel before the generic anchor alignment. The
            // generic routine is allowed to raise a normal road, but it must
            // never raise the low side of a tunnel junction.
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    anchor.segments().size());
        } else if (oneSidedTunnelJoin) {
            boolean primaryTunnel = isTunnelAt(primary, junction.primaryIndex());
            boolean secondaryTunnel = isTunnelAt(secondary, junction.secondaryIndex());
            if (!primaryTunnel && secondaryTunnel) {
                // Keep the ordinary donor corridor at its surface height and
                // raise the tunnel branch into a portal transition.
                alignSecondaryGradeToAnchor(connectedY, anchorFrom, anchorTo, sharedAtStart);
            } else {
                // A tunnel donor remains authoritative; the existing owner
                // lowering pass will propagate this lower junction grade.
                alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                        anchor.segments().size());
            }
        } else if (heightTransitionJoin) {
            // Ordinary roads with a large elevation mismatch use the same
            // bounded lower-grade transition, but remain a normal junction.
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    anchor.segments().size());
        }
        // Tunnel junction grading is handled by the lower-grade routine below.
        // The generic secondary alignment may raise the low branch while it
        // tries to meet the old high anchor, which defeats the junction rule.
        if (!gradeJoin) {
            alignSecondaryGradeToAnchor(connectedY, anchorFrom, anchorTo, sharedAtStart);
        }
        if (!gradeJoin) {
            alignJunctionToLowerTunnelGrade(connectedY, anchorFrom, anchorTo, sharedAtStart,
                    JUNCTION_ANCHOR_SEGMENTS);
        }

        if (connectedSegments.size() < MIN_RUN
                || !validateJunctionGeometry(connectedSegments, connectedY, anchorFrom, anchorTo, sharedAtStart)) {
            return null;
        }

        List<RoadSpan> connectedSpans = new ArrayList<>(
                filterSpansByRetainedSegments(secondary.spans(), connectedSegments));
        addNetworkReuse(connectedSpans, connectedSegments, anchorFrom, anchorTo);
        int boundaryA = sharedAtStart ? anchorTo : Math.max(0, anchorFrom - 1);
        int boundaryB = sharedAtStart ? Math.min(connectedSegments.size() - 1, anchorTo + 1) : anchorFrom;
        boolean tunnelJunction = tunnelGradeJoin || oneSidedTunnelJoin;
        connectedSpans.add(new RoadSpan(
                connectedSegments.get(boundaryA).middlePos(),
                connectedSegments.get(boundaryB).middlePos(),
                tunnelJunction ? SpanType.TUNNEL_JUNCTION : SpanType.JUNCTION_ANCHOR));

        RoadData connected = new RoadData(
                secondary.width(), secondary.roadType(),
                secondary.materials(), secondary.slabMaterials(),
                connectedSegments, connectedSpans, connectedY,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());

        LOGGER.info("RoadWeaver direct junction primary={} secondary={} mode={} owner={} side={} segments={}->{} cutIndex={} anchorSegments={}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                sharedOwner == RoadData.NO_OWNER_2D ? "spatial" : "shared-owner",
                sharedOwner == RoadData.NO_OWNER_2D ? "none" : Long.toString(sharedOwner),
                sharedAtStart ? "start" : "end",
                secSegs.size(), connectedSegments.size(),
                junction.secondaryIndex(), anchor.segments().size());
        return connected;
    }

    static int[] selectEndpointRun(List<int[]> runs, int size, boolean sharedAtStart) {
        if (runs == null || runs.isEmpty() || size <= 0) return null;
        if (sharedAtStart) {
            for (int[] run : runs) {
                if (run != null && run.length >= 2 && run[0] <= ENDPOINT_RUN_MAX_OFFSET) return run;
            }
            return null;
        }
        for (int i = runs.size() - 1; i >= 0; i--) {
            int[] run = runs.get(i);
            if (run != null && run.length >= 2 && run[1] >= size - 1 - ENDPOINT_RUN_MAX_OFFSET) return run;
        }
        return null;
    }

    static int[] stableEndpointRun(int[] targets,
                                           int[] run,
                                           boolean increasing,
                                           boolean sharedAtStart) {
        if (targets == null || run == null || run.length < 2) return null;
        int index = sharedAtStart ? run[0] : run[1];
        int limit = sharedAtStart ? run[1] : run[0];
        int step = sharedAtStart ? 1 : -1;
        boolean outwardIncreasing = sharedAtStart ? increasing : !increasing;
        int previous = -1;
        int repeated = 0;
        int lastValid = index - step;
        for (; ; index += step) {
            int current = targets[index];
            if (current < 0) break;
            if (previous >= 0) {
                int delta = current - previous;
                if ((outwardIncreasing && delta < 0) || (!outwardIncreasing && delta > 0)
                        || Math.abs(delta) > MAX_TARGET_INDEX_STEP) break;
                if (delta == 0) {
                    repeated++;
                    if (repeated > MAX_REPEATED_TARGETS) break;
                } else {
                    repeated = 0;
                }
            }
            previous = current;
            lastValid = index;
            if (index == limit) break;
        }
        int start = Math.min(sharedAtStart ? run[0] : lastValid, sharedAtStart ? lastValid : run[1]);
        int end = Math.max(sharedAtStart ? run[0] : lastValid, sharedAtStart ? lastValid : run[1]);
        return end - start + 1 >= MIN_RUN ? new int[]{start, end} : null;
    }

    static int[] markSnapTargets(RoadData primaryRoad,
                                         RoadData secondaryRoad,
                                         List<RoadSegmentPlacement> secondarySegments,
                                         List<RoadSegmentPlacement> primarySegments,
                                         SegmentGrid primaryGrid,
                                         boolean[] secondarySpecial) {
        int n = secondarySegments.size();
        int[] snapTarget = new int[n];
        Arrays.fill(snapTarget, -1);
        boolean snapping = false;

        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement secondary = secondarySegments.get(i);
            if (secondary == null || secondary.middlePos() == null) {
                snapping = false;
                continue;
            }
            NearestResult nearest = primaryGrid.findNearest(secondary.middlePos());
            if (nearest == null) {
                snapping = false;
                continue;
            }

            if (!snapping) {
                boolean endpointTunnelJunction = (i == 0 || i == n - 1)
                        && isTunnelJunctionPair(primaryRoad, nearest.index, secondaryRoad, i);
                if (nearest.dist2 <= SNAP_DIST2
                        && (directionCompatible(secondarySegments, i, primarySegments, nearest.index)
                        || endpointTunnelJunction)) {
                    snapping = true;
                    snapTarget[i] = nearest.index;
                }
            } else if (nearest.dist2 > SPLIT_DIST2) {
                snapping = false;
            } else {
                snapTarget[i] = nearest.index;
            }
        }
        return snapTarget;
    }

    static boolean isSpecial(boolean[] mask, int index) {
        return mask != null && index >= 0 && index < mask.length && mask[index];
    }

    static boolean directionCompatible(List<RoadSegmentPlacement> first, int firstIndex,
                                               List<RoadSegmentPlacement> second, int secondIndex) {
        double[] firstDirection = segmentDirection(first, firstIndex);
        double[] secondDirection = segmentDirection(second, secondIndex);
        if (firstDirection == null || secondDirection == null) return true;
        double dot = Math.abs(firstDirection[0] * secondDirection[0] + firstDirection[1] * secondDirection[1]);
        return dot > 0.3D;
    }

    /**
     * 0.8.369 (T2b): unit direction from the run's first to its last station.
     * Used when the two ranges differ too much in length to pair stations
     * individually — the overall travel direction is still meaningful there.
     */
    static double[] chordDirection(List<RoadSegmentPlacement> segments, int from, int to) {
        if (segments == null || from < 0 || to >= segments.size() || from >= to) return null;
        RoadSegmentPlacement a = segments.get(from);
        RoadSegmentPlacement b = segments.get(to);
        if (a == null || b == null || a.middlePos() == null || b.middlePos() == null) return null;
        double dx = b.middlePos().getX() - a.middlePos().getX();
        double dz = b.middlePos().getZ() - a.middlePos().getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[]{dx / length, dz / length};
    }

    static double[] segmentDirection(List<RoadSegmentPlacement> segments, int index) {
        int previous = Math.max(0, index - 2);
        int next = Math.min(segments.size() - 1, index + 2);
        if (previous == next) return null;
        BlockPos a = segments.get(previous).middlePos();
        BlockPos b = segments.get(next).middlePos();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0D) return null;
        return new double[]{dx / length, dz / length};
    }

    static List<int[]> extractRuns(int[] snapTarget, int size) {
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < size; i++) {
            if (snapTarget[i] >= 0) {
                if (runStart < 0) runStart = i;
            } else {
                if (runStart >= 0 && i - runStart >= MIN_RUN) runs.add(new int[]{runStart, i - 1});
                runStart = -1;
            }
        }
        if (runStart >= 0 && size - runStart >= MIN_RUN) runs.add(new int[]{runStart, size - 1});
        return runs;
    }

    static List<int[]> extractJunctionRuns(RoadData primary,
                                                   RoadData secondary,
                                                   int[] targets) {
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        int previousTarget = -1;
        for (int i = 0; i < targets.length; i++) {
            boolean close = junctionNear(primary, secondary, targets, i)
                    && (previousTarget < 0 || Math.abs(targets[i] - previousTarget) <= MAX_TARGET_INDEX_STEP)
                    && directionCompatible(secondary.roadSegmentList(), i,
                    primary.roadSegmentList(), targets[i]);
            if (close) {
                if (runStart < 0) runStart = i;
                previousTarget = targets[i];
            } else if (runStart >= 0) {
                if (i - runStart >= OVERLAP_MERGE_MIN_RUN) runs.add(new int[]{runStart, i - 1});
                runStart = -1;
                previousTarget = -1;
            }
        }
        if (runStart >= 0 && targets.length - runStart >= OVERLAP_MERGE_MIN_RUN) {
            runs.add(new int[]{runStart, targets.length - 1});
        }
        return runs;
    }

    /**
     * P0-2: the segment grid only depends on a road's own XZ layout, and
     * {@link RoadData} is deeply immutable, so one grid per road instance is
     * enough. {@code connectSecondaryToPrimary} replays up to
     * {@link #MAX_REUSE_WINDOWS_PER_PAIR} windows over the same primary, and
     * {@code snapRoadList} reuses that primary for every later secondary; the
     * grid is therefore built O(k) times per pass instead of O(k^2 * W).
     */
    static final ThreadLocal<IdentityHashMap<RoadData, SegmentGrid>> GRID_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    static SegmentGrid buildGrid(RoadData road, boolean[] excluded) {
        if (excluded != null) return buildGridUncached(road, excluded);
        IdentityHashMap<RoadData, SegmentGrid> cache = GRID_CACHE.get();
        SegmentGrid cached = cache.get(road);
        if (cached != null) return cached;
        if (cache.size() >= ROAD_INDEX_CACHE_CAP) cache.clear();
        SegmentGrid built = buildGridUncached(road, null);
        cache.put(road, built);
        return built;
    }

    static SegmentGrid buildGridUncached(RoadData road, boolean[] excluded) {
        SegmentGrid grid = new SegmentGrid();
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        if (segments == null) return grid;
        for (int i = 0; i < segments.size(); i++) {
            if (excluded != null && i < excluded.length && excluded[i]) continue;
            RoadSegmentPlacement segment = segments.get(i);
            if (segment == null || segment.middlePos() == null) continue;
            BlockPos pos = segment.middlePos();
            grid.add(gridKey(pos.getX() >> GRID_SHIFT, pos.getZ() >> GRID_SHIFT), i, pos);
        }
        return grid;
    }

    static boolean[] specialSpanMask(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] special = new boolean[segments.size()];
        if (road.spans() == null || road.spans().isEmpty()) return special;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING
                    && span.type() != SpanType.JUNCTION_ANCHOR
                    && span.type() != SpanType.TUNNEL_JUNCTION
                    && span.type() != SpanType.NETWORK_REUSE
                    && span.type() != SpanType.PROTECTED_GAP) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) special[i] = true;
        }
        return special;
    }

    /** Physical water spans cannot host a new junction; building gaps and
     * existing reuse runs remain continuous evidence for a middle corridor.
     * The actual junction index is still checked separately by
     * {@link #isSafeJunctionIndex(RoadData, int)}. */
    static boolean[] junctionBlockedSpanMask(RoadData road) {
        if (road == null) return new boolean[0];
        // P0-1: reuse the per-road derived masks instead of rebuilding the
        // xz->index map and rescanning every span on each call.
        boolean[] bridge = roadIndex(road).spanMask(SpanType.BRIDGE);
        boolean[] crossing = roadIndex(road).spanMask(SpanType.ROAD_CROSSING);
        boolean[] blocked = new boolean[bridge.length];
        for (int i = 0; i < blocked.length; i++) blocked[i] = bridge[i] || crossing[i];
        return blocked;
    }

    static List<RoadSpan> filterSpansByRetainedSegments(List<RoadSpan> spans,
                                                                 List<RoadSegmentPlacement> retainedSegments) {
        if (spans == null || spans.isEmpty()) return List.of();
        Set<Long> retained = new HashSet<>();
        for (RoadSegmentPlacement segment : retainedSegments) {
            if (segment != null && segment.middlePos() != null) retained.add(xzKey(segment.middlePos()));
        }
        List<RoadSpan> result = new ArrayList<>();
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (retained.contains(xzKey(span.start())) && retained.contains(xzKey(span.end()))) result.add(span);
        }
        return result;
    }

    /**
     * P0-1: derived per-road lookup tables, built at most once per RoadData
     * instance. {@link RoadData} is deeply immutable (its compact constructor
     * copies every list and both element types are records), so caching derived
     * indices by instance identity can never observe a stale road. The previous
     * implementation rebuilt the whole xz-&gt;index map inside every
     * {@code isIndexInSpan} call; {@code gradeCompatibleAtJunction} alone calls
     * that seven times per station, which made a single road pair O(S^2) in the
     * segment count.
     */
    static final int ROAD_INDEX_CACHE_CAP = 512;
    static final ThreadLocal<IdentityHashMap<RoadData, RoadIndex>> ROAD_INDEX_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    static RoadIndex roadIndex(RoadData road) {
        IdentityHashMap<RoadData, RoadIndex> cache = ROAD_INDEX_CACHE.get();
        RoadIndex view = cache.get(road);
        if (view == null) {
            if (cache.size() >= ROAD_INDEX_CACHE_CAP) cache.clear();
            view = new RoadIndex(road);
            cache.put(road, view);
        }
        return view;
    }


    static boolean isIndexInSpan(RoadData road, int index, SpanType type) {
        if (road == null || type == null) return false;
        return roadIndex(road).inSpan(index, type);
    }

    static boolean isTunnelJunctionPair(RoadData first, int firstIndex,
                                                 RoadData second, int secondIndex) {
        return (isIndexInSpan(first, firstIndex, SpanType.TUNNEL)
                || isIndexInSpan(first, firstIndex, SpanType.TUNNEL_JUNCTION))
                && (isIndexInSpan(second, secondIndex, SpanType.TUNNEL)
                || isIndexInSpan(second, secondIndex, SpanType.TUNNEL_JUNCTION));
    }

    static boolean isTunnelGradeTransitionPair(RoadData first, int firstIndex,
                                                         RoadData second, int secondIndex) {
        return isTunnelJunctionPair(first, firstIndex, second, secondIndex)
                || isIndexInSpan(first, firstIndex, SpanType.TUNNEL)
                || isIndexInSpan(first, firstIndex, SpanType.TUNNEL_JUNCTION)
                || isIndexInSpan(second, secondIndex, SpanType.TUNNEL)
                || isIndexInSpan(second, secondIndex, SpanType.TUNNEL_JUNCTION);
    }

    static boolean isTunnelAt(RoadData road, int index) {
        return road != null && (isIndexInSpan(road, index, SpanType.TUNNEL)
                || isIndexInSpan(road, index, SpanType.TUNNEL_JUNCTION));
    }

    static boolean isTunnelBoundaryNear(RoadData road, int index) {
        if (!isTunnelAt(road, index) || road.spans() == null) return false;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.TUNNEL
                    || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            if (Math.min(Math.abs(index - start), Math.abs(index - end)) <= 2) return true;
        }
        return false;
    }


    /**
     * Expands the transition window only as far as the vertical difference
     * requires.  The cap keeps malformed data from turning one junction into
     * an unbounded rewrite of the tunnel.
     */
    static int requiredTunnelAnchorSegments(int primaryY, int secondaryY) {
        int delta = Math.abs(primaryY - secondaryY);
        return Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS,
                Math.max(JUNCTION_ANCHOR_SEGMENTS, delta + 2));
    }

    /**
     * A tunnel connector may use the same dynamically expanded grade budget
     * as its anchor.  Ordinary roads continue to use the horizontal-distance
     * rule and never call this method.
     */
    static int tunnelGradeLimit(RoadData first, int firstIndex,
                                         RoadData second, int secondIndex) {
        int delta = Math.abs(targetAt(first, firstIndex) - targetAt(second, secondIndex));
        return Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS,
                Math.max(MAX_TUNNEL_JUNCTION_GRADE_DELTA, delta));
    }

    static boolean gradeCompatibleAtJunction(RoadData first, int firstIndex,
                                                      RoadData second, int secondIndex,
                                                      int horizontalDistance) {
        int delta = Math.abs(targetAt(first, firstIndex) - targetAt(second, secondIndex));
        if (isTunnelGradeTransitionPair(first, firstIndex, second, secondIndex)) {
            return delta <= Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS,
                    Math.max(horizontalDistance, tunnelGradeLimit(first, firstIndex, second, secondIndex)));
        }
        return delta <= horizontalDistance;
    }

    static boolean isSafeJunctionIndex(RoadData road, int index) {
        if (road == null || index < 0 || index >= road.roadSegmentList().size()) return false;
        if (isIndexInSpan(road, index, SpanType.BRIDGE)
                || isIndexInSpan(road, index, SpanType.ROAD_CROSSING)
                || isIndexInSpan(road, index, SpanType.NETWORK_REUSE)
                || isIndexInSpan(road, index, SpanType.PROTECTED_GAP)) return false;
        if (!isIndexInSpan(road, index, SpanType.TUNNEL)) return true;
        if (isIndexInSpan(road, index, SpanType.TUNNEL_JUNCTION)) return true;

        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.TUNNEL
                    || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start != null && end != null && (index == start || index == end)) return true;
        }
        return false;
    }

    static void addSpansForRetainedSegments(List<RoadSpan> target,
                                                    List<RoadSpan> source,
                                                    List<RoadSegmentPlacement> retainedSegments) {
        if (target == null || source == null || source.isEmpty() || retainedSegments == null) return;
        Set<Long> retained = new HashSet<>();
        for (RoadSegmentPlacement segment : retainedSegments) {
            if (segment != null && segment.middlePos() != null) retained.add(xzKey(segment.middlePos()));
        }
        for (RoadSpan span : source) {
            if (span == null || span.start() == null || span.end() == null
                    || !retained.contains(xzKey(span.start())) || !retained.contains(xzKey(span.end()))) continue;
            boolean duplicate = target.stream().anyMatch(existing -> existing != null
                    && existing.type() == span.type()
                    && xzKey(existing.start()) == xzKey(span.start())
                    && xzKey(existing.end()) == xzKey(span.end()));
            if (!duplicate) target.add(span);
        }
    }

    static void addClippedOwnerSpans(List<RoadSpan> target,
                                             RoadData owner,
                                             int ownerFrom,
                                             int ownerTo,
                                             List<RoadSegmentPlacement> retained) {
        if (target == null || owner == null || owner.spans() == null || retained == null) return;
        List<RoadSegmentPlacement> ownerSegments = owner.roadSegmentList();
        Map<Long, Integer> ownerIndex = new HashMap<>();
        for (int i = 0; i < ownerSegments.size(); i++) {
            RoadSegmentPlacement segment = ownerSegments.get(i);
            if (segment != null && segment.middlePos() != null) ownerIndex.put(xzKey(segment.middlePos()), i);
        }
        Set<Long> retainedKeys = new HashSet<>();
        for (RoadSegmentPlacement segment : retained) {
            if (segment != null && segment.middlePos() != null) retainedKeys.add(xzKey(segment.middlePos()));
        }
        for (RoadSpan span : owner.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = ownerIndex.get(xzKey(span.start()));
            Integer end = ownerIndex.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int overlapFrom = Math.max(Math.min(start, end), ownerFrom);
            int overlapTo = Math.min(Math.max(start, end), ownerTo);
            if (overlapTo - overlapFrom + 1 < 2) continue;
            BlockPos clippedStart = nearestRetained(ownerSegments.get(overlapFrom).middlePos(), retained, retainedKeys);
            BlockPos clippedEnd = nearestRetained(ownerSegments.get(overlapTo).middlePos(), retained, retainedKeys);
            if (clippedStart == null || clippedEnd == null) continue;
            boolean duplicate = target.stream().anyMatch(existing -> existing != null
                    && existing.type() == span.type()
                    && xzKey(existing.start()) == clippedStart.asLong()
                    && xzKey(existing.end()) == clippedEnd.asLong());
            if (!duplicate) target.add(new RoadSpan(clippedStart, clippedEnd, span.type()));
        }
    }

    static BlockPos nearestRetained(BlockPos target,
                                             List<RoadSegmentPlacement> retained,
                                             Set<Long> retainedKeys) {
        if (target == null) return null;
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (RoadSegmentPlacement segment : retained) {
            if (segment == null || segment.middlePos() == null
                    || !retainedKeys.contains(xzKey(segment.middlePos()))) continue;
            long distance = dist2XZ(target, segment.middlePos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment.middlePos();
            }
        }
        return best;
    }

    static long posKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static long gridKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static long dist2XZ(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    record JunctionCandidate(int secondaryIndex, int primaryIndex) {
    }

    record ApproachBoundary(int secondaryIndex, int primaryIndex) {
    }

    record AnchorPlan(List<RoadSegmentPlacement> segments, List<Integer> targetY) {
    }

    record NearestResult(int index, long dist2) {
    }

    record ConnectionOption(String mode,
                                    RoadData road,
                                    double constructionLength,
                                    int reuseStations) {
        private boolean betterThan(ConnectionOption other) {
            int reuseOrder = Integer.compare(reuseStations, other.reuseStations);
            if (reuseOrder != 0) return reuseOrder > 0;
            int constructionOrder = Double.compare(constructionLength, other.constructionLength);
            if (constructionOrder != 0) return constructionOrder < 0;
            int segmentOrder = Integer.compare(road.roadSegmentList().size(),
                    other.road.roadSegmentList().size());
            if (segmentOrder != 0) return segmentOrder < 0;
            return mode.compareTo(other.mode) < 0;
        }
    }


    static boolean segmentsOverlap2D(int ax1, int az1, int ax2, int az2, int bx1, int bz1, int bx2, int bz2) {
        return Math.min(ax1, ax2) <= Math.max(bx1, bx2) && Math.max(ax1, ax2) >= Math.min(bx1, bx2)
                && Math.min(az1, az2) <= Math.max(bz1, bz2) && Math.max(az1, az2) >= Math.min(bz1, bz2);
    }

    static boolean ownerHasSameTypeSpanAt(RoadData owner, RoadSpan secondary) {
        if (owner == null || owner.spans() == null || secondary == null
                || secondary.start() == null || secondary.end() == null) return false;
        int secondaryStart = secondary.start().getX();
        int secondaryEnd = secondary.end().getX();
        int secondaryZStart = secondary.start().getZ();
        int secondaryZEnd = secondary.end().getZ();
        for (RoadSpan ownerSpan : owner.spans()) {
            if (ownerSpan == null || ownerSpan.type() != secondary.type()) continue;
            if (ownerSpan.start() == null || ownerSpan.end() == null) continue;
            if (segmentsOverlap2D(secondaryStart, secondaryZStart, secondaryEnd, secondaryZEnd,
                    ownerSpan.start().getX(), ownerSpan.start().getZ(),
                    ownerSpan.end().getX(), ownerSpan.end().getZ())) return true;
        }
        return false;
    }

    static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /**
     * P0-post B1: the junction donor pre-filter asks for a road's bounding box
     * once per candidate pair, and {@code RoadBounds.of} scans every station.
     * {@link RoadData} is deeply immutable, so one box per instance is enough —
     * this removes the O(n^2 * S) term from the initial network merge.
     */
    static final ThreadLocal<IdentityHashMap<RoadData, RoadBounds>> BOUNDS_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** Public accessor used by the junction donor pre-filter. */
    public static RoadBounds roadBounds(RoadData road) {
        if (road == null) return null;
        IdentityHashMap<RoadData, RoadBounds> cache = BOUNDS_CACHE.get();
        if (cache.containsKey(road)) return cache.get(road);
        if (cache.size() >= ROAD_INDEX_CACHE_CAP) cache.clear();
        RoadBounds computed = RoadBounds.of(road);
        cache.put(road, computed);
        return computed;
    }

    /**
     * P0-post B2: read-only view of a road's span mask, shared with the junction
     * service so it no longer rebuilds the xz-&gt;index map per construction-length
     * query. Callers must not mutate the returned array.
     */
    public static boolean[] spanMaskView(RoadData road, SpanType type) {
        if (road == null || type == null) return new boolean[0];
        return roadIndex(road).spanMask(type);
    }

    /** Axis-aligned bounding box of a road's station centres. */
    public record RoadBounds(int minX, int minZ, int maxX, int maxZ) {
        static RoadBounds of(RoadData road) {
            if (road == null || road.roadSegmentList() == null) return null;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            boolean any = false;
            for (RoadSegmentPlacement segment : road.roadSegmentList()) {
                if (segment == null || segment.middlePos() == null) continue;
                BlockPos position = segment.middlePos();
                minX = Math.min(minX, position.getX());
                maxX = Math.max(maxX, position.getX());
                minZ = Math.min(minZ, position.getZ());
                maxZ = Math.max(maxZ, position.getZ());
                any = true;
            }
            return any ? new RoadBounds(minX, minZ, maxX, maxZ) : null;
        }

        /** Max-norm gap between this box and another; 0 when they overlap. */
        public int gap(RoadBounds other) {
            if (other == null) return Integer.MAX_VALUE;
            int dx = Math.max(0, Math.max(minX, other.minX) - Math.min(maxX, other.maxX));
            int dz = Math.max(0, Math.max(minZ, other.minZ) - Math.min(maxZ, other.maxZ));
            return Math.max(dx, dz);
        }
    }
}
