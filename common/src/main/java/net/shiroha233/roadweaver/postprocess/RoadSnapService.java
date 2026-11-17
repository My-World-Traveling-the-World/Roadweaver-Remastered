/* 文件职责：把后建的路接到先建好的路上 —— 且**绝不修改先建的那条**。
 *
 * 「只连不改」是硬约束：先建的路可能已经有玩家建筑贴边、已经有存档引用，
 * 事后改它的段序列会让已有存档的落盘数据和新数据打架。
 */
package net.shiroha233.roadweaver.postprocess;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import static net.shiroha233.roadweaver.postprocess.JunctionTrimOps.*;
import static net.shiroha233.roadweaver.postprocess.EndpointConnectOps.*;

/** 后建道路接入先建道路时保持主路归属；需捐赠路降高的特殊场景由显式写回链处理。 */
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
    /** 隧道路口允许的初始高度偏差有上界，后续接坡仍须校验。 */
    static final int MAX_TUNNEL_JUNCTION_GRADE_DELTA = 8;
    /** 近平行接近允许有限短接后复用，不能只因距离近就吞掉次路。 */
    static final int MAX_APPROACH_CONNECTOR_DISTANCE = SNAP_THRESHOLD;
    /** 足够长且同向的走廊才判复用，避免偶然交叉被当作重复路。 */
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
    /** 弯长共享段按多站多数方向判定，避免单中点采样误拒整段。
 *
     * 0.8.369 (T2b): a long shared corridor bends, so its heading is sampled at
     * several stations and accepted on a majority vote. One midpoint sample made
     * a 216-station overlap fail the parallel test and kept the braid.
     */
    static final int PARALLEL_CORRIDOR_HEADING_SAMPLES = 5;
    /** 共享段允许有限离群站，避免单站偏移否定长段；真正交叉仍有大部分站超距。
 *
     * 0.8.369 (T2b): fraction of stations allowed to fall outside the merge
     * distance before the whole run is rejected. A single outlier used to drop
     * an entire overlap; a genuine crossing still fails, because there almost
     * every station is out of range.
     */
    static final double PARALLEL_CORRIDOR_OUTLIER_FRACTION = 0.1D;
    /** 历史长度比例门已由真实最近站映射替代；保留常量不能误写成现行准入条件。 */
    static final double PARALLEL_CORRIDOR_MIN_LENGTH_RATIO = 0.6D;
    static final long SNAP_DIST2 = (long) SNAP_THRESHOLD * SNAP_THRESHOLD;
    static final long SPLIT_DIST2 = (long) SPLIT_THRESHOLD * SPLIT_THRESHOLD;
    /** 包围盒只提前排除明显超出最大吸附范围的路对，实际连接仍用精确规则。 */
    static final int SPATIAL_REJECT_MARGIN = SNAP_THRESHOLD * 4;
    static final Object RECONCILIATION_LOCK = new Object();
    /** 仅慢协调输出INFO，普通批次留DEBUG，避免路对数放大日志负担。 */
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
                .thenCompose(ignored -> ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.POSTPROCESS, () -> {
                    // 跟踪真正的落地任务，前置读取完成后停服也能结束整条完成链。
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
                }));
    }

    /** 持久道路协调不加载新区块，避免后台合流改变世界加载范围。 */
    public static CompletableFuture<Void> snapAllRoadsAsync(ServerLevel level) {
        if (level == null || RoadLifecycle.isStopping()) return CompletableFuture.completedFuture(null);
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);
        long epoch = ThreadPoolManager.currentEpoch();
        return CompletableFuture.supplyAsync(() -> RoadShardStorage.loadAll(level))
                .thenCompose(roads -> ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.POSTPROCESS, () -> {
                    // 与局部修整共用可终结任务口，不把 JDK 内部阶段交给角色池。
                    if (!ThreadPoolManager.isEpoch(epoch) || RoadLifecycle.isStopping()
                            || roads == null || roads.size() < 2) return;
                    synchronized (RECONCILIATION_LOCK) {
                        snapRoadList(level, roads);
                    }
                }));
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
                        LOGGER.warn("【加载后道路吸附导致入水，撤销改写】 " + "RoadWeaver discarded underwater post-load road snap owners={} -> {} "
                                + "reason={} collision={}",
                                current[i].ownerA2dKey(), current[i].ownerB2dKey(),
                                waterValidation.reason(), waterValidation.collision());
                        continue;
                    }
                    current[i] = gapped;
                    LOGGER.warn("【加载后道路吸附保留河流保护缺口】 " + "RoadWeaver retained post-load river protection gaps owners={} -> {} "
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
                    LOGGER.warn("【精确结构快照不可用，跳过加载后道路吸附】 " + "RoadWeaver skipped post-load road snap because its exact structure snapshot is unavailable "
                                    + "owners={} -> {} reason={}",
                            current[i].ownerA2dKey(), current[i].ownerB2dKey(), unavailable.getMessage());
                    continue;
                }
                if (!structureValidation.safe()) {
                    LOGGER.warn("【加载后道路吸附不安全，撤销改写】 " + "RoadWeaver discarded unsafe post-load road snap owners={} -> {} collision={}",
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
            LOGGER.error("【直接道路路口持久化失败】 " + "RoadWeaver failed to persist direct road junctions", e);
        }
        logSnapTiming(roads.size(), replacements.size(), (System.nanoTime() - snapStartedAt) / 1_000_000L);
    }

    /** 每条路完成都会协调，只有慢过程升INFO以控制日志量。 */
    static void logSnapTiming(int roads, int replaced, long elapsedMs) {
        if (elapsedMs < SNAP_TIMING_LOG_MS) {
            LOGGER.debug("【已存道路协调数量与耗时】 " + "RoadWeaver saved-road reconciliation roads={} replaced={} elapsedMs={}",
                    roads, replaced, elapsedMs);
            return;
        }
        LOGGER.info("【已存道路协调数量与耗时】 " + "RoadWeaver saved-road reconciliation roads={} replaced={} elapsedMs={}",
                roads, replaced, elapsedMs);
    }

    /** 只有能证明安全直连时返回次路替换；不安全返回无候选，不先修改主路。
 *
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

    /** 仅去掉类型及站区间完全相同的重复跨度，真正物理重叠仍交给校验拒绝。
 *
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
        LOGGER.info("【已删除重复的路口跨度记录】 " + "RoadWeaver removed duplicate junction span records owners={} -> {} removed={}",
                road.ownerA2dKey(), road.ownerB2dKey(), road.spans().size() - unique.size());
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), unique, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    /** NETWORK_REUSE已归属主路；删除次路对应物理跨度，避免复制桥面、桥墩或洞体。
 *
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
        LOGGER.info("【既有路网复用段已抑制重复桥隧施工】 " + "RoadWeaver network reuse construction suppressed owners={} -> {} bridges={} tunnels={} crossings={}",
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

    /** 次路起点已在主路且一路同行时保留实际主路片段，避免锚点后残留平行双材质路。
 *
     * A later edge may already start on an earlier road and remain in the same
     * corridor until their shared destination. In that case the whole owner
     * slice is the physical road; retaining a short anchor plus a parallel
     * secondary corridor creates the wide two-material road this service is
     * intended to prevent.
     */
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

    /** 长短差异大时使用首尾单位方向，不能把逐站对应强套不同长度区间。
 *
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

    /** 网格只依赖中心XZ，按RoadData实例复用；外层站序发布后不改，嵌套positions未深拷贝但此网格不读取它。 */
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

    /** 物理水跨度不能作新路口；缺口与已有复用可参与共用中段，锚点本身仍须安全校验。
 * Physical water spans cannot host a new junction; building gaps and
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

    /** 索引读取中心与跨度并按实例缓存；record并不保证嵌套铺装列深不可变，发布后不得改动缓存所依赖输入。 */
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


    /** 坡差所需过渡窗按实际高度扩展且封顶，避免畸形输入造成无限隧道重写。
 *
     * Expands the transition window only as far as the vertical difference
     * requires.  The cap keeps malformed data from turning one junction into
     * an unbounded rewrite of the tunnel.
     */
    static int requiredTunnelAnchorSegments(int primaryY, int secondaryY) {
        int delta = Math.abs(primaryY - secondaryY);
        return Math.min(MAX_TUNNEL_JUNCTION_ANCHOR_SEGMENTS,
                Math.max(JUNCTION_ANCHOR_SEGMENTS, delta + 2));
    }

    /** 隧道接头采用受限坡度预算；普通路保持水平距离规则，不能复用放宽入口。
 *
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

    /** 包围盒只依赖站中心，按实例缓存减少候选路对重复扫描；不能据此声称整份RoadData深不可变。 */
    static final ThreadLocal<IdentityHashMap<RoadData, RoadBounds>> BOUNDS_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** 向捐赠路预筛提供同一中心包围盒，避免另一实现出现边界漂移。 */
    public static RoadBounds roadBounds(RoadData road) {
        if (road == null) return null;
        IdentityHashMap<RoadData, RoadBounds> cache = BOUNDS_CACHE.get();
        if (cache.containsKey(road)) return cache.get(road);
        if (cache.size() >= ROAD_INDEX_CACHE_CAP) cache.clear();
        RoadBounds computed = RoadBounds.of(road);
        cache.put(road, computed);
        return computed;
    }

    /** 跨度掩码向合流只读共享，调用方不能原地修改数组。
 *
     * P0-post B2: read-only view of a road's span mask, shared with the junction
     * service so it no longer rebuilds the xz-&gt;index map per construction-length
     * query. Callers must not mutate the returned array.
     */
    public static boolean[] spanMaskView(RoadData road, SpanType type) {
        if (road == null || type == null) return new boolean[0];
        return roadIndex(road).spanMask(type);
    }

    /** 路界限为站中心轴对齐盒，不等价于完整施工占地。 */
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

        /** 盒间最大范数距离为零仅表示盒相交，不代表真实道路已相交。 */
        public int gap(RoadBounds other) {
            if (other == null) return Integer.MAX_VALUE;
            int dx = Math.max(0, Math.max(minX, other.minX) - Math.min(maxX, other.maxX));
            int dz = Math.max(0, Math.max(minZ, other.minZ) - Math.min(maxZ, other.maxZ));
            return Math.max(dx, dz);
        }
    }
}
