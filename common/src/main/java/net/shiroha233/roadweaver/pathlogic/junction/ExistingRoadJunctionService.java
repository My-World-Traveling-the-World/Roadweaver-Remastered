/* 文件职责：新路到达已连通网络时复用既有路 —— 避免在已有路上再铺一条。
 *
 * 合并会**重建站序列**，所以任何依赖「站索引稳定」的下游都必须重新解析
 * （这正是 RoadSpan 只存 BlockPos 会导致桥被静默丢掉的根源，见 spec/RoadSpec）。
 */
package net.shiroha233.roadweaver.pathlogic.junction;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.postprocess.JunctionConnector;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotUnavailableException;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorBan;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;
import static net.shiroha233.roadweaver.pathlogic.junction.ParallelOverlapResolver.*;

/** 新路接入已完成路网时复用既有走廊，避免重复施工。 */
public final class ExistingRoadJunctionService {
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    static final double MAX_JUNCTION_CONSTRUCTION_OVERHEAD =
            Math.ceil(RoadConstants.ROAD_SNAP_THRESHOLD * Math.sqrt(2.0D));

    /**
     * 合并接口精修的等待上限（毫秒）。
     *
     * <p>审计 P2-3：这些子任务提交到 {@code TaskRole.POSTPROCESS}，
     * 而 POSTPROCESS 在 {@code executorFor()} 里落到 {@code default -> sharedExecutor()}
     * —— 与等待方**同池**。加超时后即使将来有人在运行期复用这条路径，
     * 也不会出现「占住线程等一个排不上队的同池任务」的自锁。</p>
     *
     * <p>取 120 秒：单次精修正常是秒级，120 秒足以区分「慢」与「卡死」。</p>
     */
    static final long JUNCTION_REFINE_TIMEOUT_MS = 120_000L;

    static long junctionRefineTimeoutMs() {
        return JUNCTION_REFINE_TIMEOUT_MS;
    }

    // 单路和批量共用矩形查询口；选主、提交顺序和捐赠路处理仍由本类决定。
    interface RoadQuery {
        List<RoadData> query(int minX, int minZ, int maxX, int maxZ);
    }

    interface RuntimeAccess extends RoadQuery {
        RoadData refineTunnels(RoadData road);
        void replaceDonor(long fingerprint, RoadData donor);
    }

    interface BatchAccess extends RoadQuery {
        BatchRefinement openRefinement();
        BraidPrecheck resolveOverlaps(List<RoadData> current, List<RoadData> persisted, boolean[] skip);
    }

    interface BatchRefinement extends AutoCloseable {
        CompletableFuture<MergeResult> refine(MergeWork item);
        FinalRoadStructureValidator.ValidationResult validate(RoadData road);
        @Override
        void close();
    }

    private record WorldBatchAccess(ServerLevel level) implements BatchAccess {
        public List<RoadData> query(int minX, int minZ, int maxX, int maxZ) {
            return RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
        }

        public BatchRefinement openRefinement() {
            // 缓存和水系会话仍只在批量入口创建；精修子任务继续使用自己的缓存。
            TerrainSamplingCache cache = new TerrainSamplingCache();
            StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
            return new BatchRefinement() {
                public CompletableFuture<MergeResult> refine(MergeWork item) {
                    // 让精修结果在排队/闸门取消时也结束，避免批量 join 永久等待。
                    return ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.POSTPROCESS,
                            () -> refineMergedCandidate(level, item));
                }

                public FinalRoadStructureValidator.ValidationResult validate(RoadData road) {
                    return FinalRoadStructureValidator.validate(level, road, cache, streams);
                }

                public void close() {
                    cache.clear();
                }
            };
        }

        public BraidPrecheck resolveOverlaps(List<RoadData> current, List<RoadData> persisted, boolean[] skip) {
            return resolveParallelOverlaps(level, current, persisted, skip);
        }
    }

    private record WorldRuntimeAccess(ServerLevel level, TerrainSamplingCache cache) implements RuntimeAccess {
        public List<RoadData> query(int minX, int minZ, int maxX, int maxZ) {
            return RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
        }

        public RoadData refineTunnels(RoadData road) {
            return PathSpanExtractor.refineRoadTunnels(road, level, cache);
        }

        public void replaceDonor(long fingerprint, RoadData donor) {
            RoadShardStorage.replaceRoad(level, fingerprint, donor);
        }
    }

    private ExistingRoadJunctionService() {
    }

    record JunctionSelection(RoadData road, RoadData donor) {
    }

    record MergeWork(int index, RoadData secondary, JunctionSelection selection) {
    }

    record MergeResult(int index, RoadData secondary, RoadData candidate, RoadData donor) {
    }

    /** 返回最终合流批次及已精修路标识，避免后续再次对同一路做连续性修复。 */
    public record MergeBatchResult(List<RoadData> roads, Set<Long> refinedRoadFingerprints) {
    }

    /** 初始成功路线落盘前与附近旧路及前序批次道路合流；桥隧主体不能作为新路口锚点。
 *
     * Connects the final successful routes before the initial batch is
     * persisted. Nearby persisted roads and earlier routes in this batch may
     * supply the shared corridor; bridge/tunnel segments are never selected as
     * the junction point.
     */
    public static List<RoadData> connectPlannedBatch(List<RoadData> roads) {
        return connectPlannedBatch(null, roads);
    }

    /** 批次按既定顺序选主；公共端口编排和捐赠路写回须保持原归属，不按测试需要改几何。 */
    public static List<RoadData> connectPlannedBatch(ServerLevel level, List<RoadData> roads) {
        return connectPlannedBatchDetailed(level, roads).roads();
    }

    public static MergeBatchResult connectPlannedBatchDetailed(ServerLevel level, List<RoadData> roads) {
        return connectPlannedBatchWithAccess(level == null ? null : new WorldBatchAccess(level), roads);
    }

    /** 保留既有入口，批次顺序与提交责任交给专用编排器。 */
    static MergeBatchResult connectPlannedBatchWithAccess(BatchAccess access, List<RoadData> roads) {
        return JunctionBatchCoordinator.connect(access, roads);
    }

    /** 重叠段两侧各多禁几站，避免改道只是把重叠往后挪两站。 */
    static final int BRAID_BAN_STATION_MARGIN = 4;
    /** 单条路最多重算几次。 */
    static final int BRAID_REROUTE_ATTEMPTS = RoadConstants.DEFAULT_BRAID_REROUTE_ATTEMPTS;
    /**
     * 改道后允许的最短长度（相对原站数的比例）。
     *
     * <p>0.9.019（O-10）：原来只要求新路 ≥ {@code MIN_RUN}(6) 站，等于「一条千站的
     * 路可以被一个 6 站的短桩替换掉」。0.6 的下界既挡住短桩，又给山区绕行留出
     * 40% 的收缩余地（绕行通常更长而不是更短，所以这条门极少咬）。</p>
     */
    static final double BRAID_REROUTE_MIN_LENGTH_RATIO = 0.6D;
    /** 包围盒预筛富余：两条路的包围盒差得比这还远就不必逐站判定。 */
    static final int BRAID_BOX_MARGIN = RoadBraidDetector.MAX_DISTANCE + 16;

    /**
     * 预检与改道的记账。
     *
     * <p>{@code originals} 是每条路<b>改道之前</b>的原件（没改道过的下标为
     * {@code null}），供提交阶段校验失败时兜底 —— 见修 B-2。</p>
     */
    record BraidPrecheck(int pairs, int rerouted, int rejected, long scanMs, long rerouteMs,
                                 List<RoadData> originals) {
        static BraidPrecheck none() {
            return new BraidPrecheck(0, 0, 0, 0L, 0L, List.of());
        }

        /** 改道前的原路；该下标没改道过时返回 {@code null}。 */
        RoadData originalAt(int index) {
            if (index < 0 || index >= originals.size()) return null;
            return originals.get(index);
        }
    }


    /** 游玩期新路保存前接入已完成网络，避免落盘后才发现重复走廊。 */
    public static RoadData connectToExistingNetwork(ServerLevel level, RoadData road) {
        TerrainSamplingCache cache = new TerrainSamplingCache();
        try {
            return connectToExistingNetwork(level, road, cache);
        } finally {
            cache.clear();
        }
    }

    public static RoadData connectToExistingNetwork(ServerLevel level,
                                                     RoadData road,
                                                     TerrainSamplingCache cache) {
        return connectToExistingNetworkWithAccess(level == null ? null : new WorldRuntimeAccess(level, cache), road);
    }

    static RoadData connectToExistingNetworkWithAccess(RuntimeAccess access, RoadData road) {
        if (access == null || road == null || road.roadSegmentList() == null
                || road.roadSegmentList().size() < 5) return road;
        JunctionSelection selection = selectSingleNetworkJunction(loadNearbyPersisted(access, List.of(road)), road);
        RoadData best = selection.road();
        if (best == road) return road;
        best = RoadSnapService.enforceNetworkReuseConstructionOwnership(
                RoadSnapService.deduplicateExactSpans(
                        access.refineTunnels(best)), selection.donor());
        if (selection.donor() != null) {
            RoadData loweredDonor = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(selection.donor(), best);
            if (loweredDonor != selection.donor()) {
                access.replaceDonor(RoadShardStorage.computeFingerprint(selection.donor()), loweredDonor);
            }
        }
        return best;
    }

    /** 所有捐赠路均对原始候选评价，只提交最佳单一合流，避免逐捐赠路改写产生多重锚点。
 *
     * A road may physically join one existing corridor, not a chain of
     * corridors.  Evaluating later donors against a route that was already
     * shortened by an earlier donor creates several overlapping anchor and
     * reuse spans at a multi-way junction.  Evaluate every donor against the
     * unmodified route and commit only the best single junction instead.
     */
    static JunctionSelection selectSingleNetworkJunction(List<RoadData> owners, RoadData secondary) {
        if (secondary == null || owners == null || owners.isEmpty()) return new JunctionSelection(secondary, null);
        List<RoadData> candidates = new ArrayList<>(owners);
        candidates.removeIf(primary -> primary == null || primary == secondary);
        candidates.sort(Comparator.comparingLong(RoadShardStorage::computeFingerprint));

        RoadData best = secondary;
        RoadData donor = null;
        double originalCost = constructionLength(secondary);
        double bestCost = Double.POSITIVE_INFINITY;
        int bestReuse = JunctionTrimOps.networkReuseStationCount(secondary);
        boolean hasPositiveReuseCandidate = bestReuse > 0;
        for (RoadData primary : candidates) {
            if (!plausibleDonor(primary, secondary, hasPositiveReuseCandidate)) continue;
            RoadData connected = JunctionConnector.connectSecondaryToPrimary(primary, secondary);
            if (connected == null) {
                connected = trimRoadDataToRoad(primary, secondary);
            }
            if (!isAcceptableCandidate(secondary, connected, originalCost)) continue;

            double candidateCost = constructionLength(connected);
            int candidateReuse = JunctionTrimOps.networkReuseStationCount(connected);
            if (candidateReuse > 0) {
                hasPositiveReuseCandidate = true;
            }
            if (candidateReuse > bestReuse
                    || (candidateReuse == bestReuse && candidateCost < bestCost)) {
                best = connected;
                donor = primary;
                bestCost = candidateCost;
                bestReuse = candidateReuse;
            }
        }
        if (best != secondary && donor != null) {
            LOGGER.info("【已选取单个规划路网路口】 " + "RoadWeaver selected single planned network junction secondary={} donor={} constructionLength={} -> {}",
                    RoadShardStorage.computeFingerprint(secondary),
                    RoadShardStorage.computeFingerprint(donor), originalCost, bestCost);
        }
        return new JunctionSelection(best, donor);
    }

    static List<RoadData> loadNearbyPersisted(RoadQuery query, List<RoadData> roads) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (RoadData road : roads) {
            if (road == null || road.roadSegmentList() == null) continue;
            for (RoadSegmentPlacement segment : road.roadSegmentList()) {
                if (segment == null || segment.middlePos() == null) continue;
                BlockPos pos = segment.middlePos();
                minX = Math.min(minX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        if (minX == Integer.MAX_VALUE) return List.of();
        int margin = 32;
        return query.query(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
    }

    static boolean plausibleDonor(RoadData primary, RoadData secondary,
                                             boolean hasPositiveReuseCandidate) {
        if (primary == null || secondary == null) return false;
        RoadSnapService.RoadBounds primaryBounds = RoadSnapService.roadBounds(primary);
        RoadSnapService.RoadBounds secondaryBounds = RoadSnapService.roadBounds(secondary);
        if (primaryBounds == null || secondaryBounds == null) return false;
        if (primaryBounds.gap(secondaryBounds) > RoadConstants.ROAD_SNAP_THRESHOLD) return false;
        if (hasPositiveReuseCandidate && primary.sharedOwnerWith(secondary) == RoadData.NO_OWNER_2D) {
            return false;
        }
        return true;
    }

    /** 只排除包围盒明显超出吸附距离的捐赠路，不改变余下候选次序。
 * Per-donor prefilter: keep only donors whose bounding box lies within
     * snap reach.  A donor outside that reach can never satisfy any junction
     * mode in {@link RoadSnapService#connectSecondaryToPrimary}; this is a
     * pure geometric early-out and does not change candidate ordering. */

    static RoadData trimRoadDataToRoad(RoadData primary, RoadData secondary) {
        if (primary == null || secondary == null || primary.sharedOwnerWith(secondary) == RoadData.NO_OWNER_2D) {
            return null;
        }
        List<RoadSegmentPlacement> primarySegments = primary.roadSegmentList();
        List<RoadSegmentPlacement> secondarySegments = secondary.roadSegmentList();
        if (primarySegments == null || secondarySegments == null
                || primarySegments.size() < 5 || secondarySegments.size() < 6) return null;

        Map<Long, Integer> primaryHeights = new HashMap<>();
        Set<Long> primaryFootprint = new HashSet<>();
        // 两种端点语义共用同一道路快照的站号表，避免重复扫描主路。
        JunctionSpanIndex primaryIndex = new JunctionSpanIndex(primary);
        boolean[] primarySpecial = primaryIndex.blocked();
        boolean[] primarySearchExcluded = primaryIndex.searchExcluded();
        for (int i = 0; i < primarySegments.size(); i++) {
            if (primarySearchExcluded[i] || primarySpecial[i]) continue;
            RoadSegmentPlacement segment = primarySegments.get(i);
            if (segment == null || segment.middlePos() == null) continue;
            putFootprint(segment, targetAt(primary, i), primaryFootprint, primaryHeights);
        }

        boolean joinsStart = primary.containsOwner(secondary.ownerA2dKey());
        boolean joinsEnd = primary.containsOwner(secondary.ownerB2dKey());
        if (!joinsStart && !joinsEnd) return null;
        boolean[] secondarySpecial = lateralJunctionBlockedMask(secondary);
        int intersection = joinsEnd
                ? firstIntersection(secondary, primaryFootprint, primaryHeights, secondarySpecial)
                : lastIntersection(secondary, primaryFootprint, primaryHeights, secondarySpecial);
        if (intersection <= 0 || intersection >= secondarySegments.size() - 1) return null;
        int primaryIntersection = findIntersectingPrimaryIndex(
                primary, secondarySegments.get(intersection), targetAt(secondary, intersection), primarySpecial);
        if (primaryIntersection < 0) return null;

        int from = joinsEnd ? 0 : intersection;
        int toExclusive = joinsEnd ? intersection + 1 : secondarySegments.size();
        if (toExclusive - from < 5) return null;
        List<RoadSegmentPlacement> retained = List.copyOf(secondarySegments.subList(from, toExclusive));
        int retainedJunctionIndex = joinsEnd ? toExclusive - from - 1 : 0;
        int secondaryJunctionY = targetAt(secondary, joinsEnd ? toExclusive - 1 : from);
        int junctionY = Math.min(targetAt(primary, primaryIntersection), secondaryJunctionY);
        List<Integer> retainedTargetY = alignJunctionProfile(
                retainTargetY(secondary, from, toExclusive), retainedJunctionIndex, junctionY);
        List<RoadSpan> retainedSpans = retainSpans(secondary, from, toExclusive, retained);
        retainedSpans = addJunctionMetadata(primary, primaryIntersection, retained, retainedSpans, joinsEnd);
        RoadData trimmed = new RoadData(
                secondary.width(), secondary.roadType(), secondary.materials(), secondary.slabMaterials(),
                retained, retainedSpans, retainedTargetY,
                secondary.ownerA2dKey(), secondary.ownerB2dKey());
        org.slf4j.LoggerFactory.getLogger("roadweaver").info(
                "【共用端点交会处已裁停次路】 " + "RoadWeaver shared-endpoint intersection stop primary={} secondary={} side={} cutIndex={} segments={}->{}",
                RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                joinsEnd ? "end" : "start", intersection, secondarySegments.size(), retained.size());
        return trimmed;
    }

    /** 保守占地截断仍继承捐赠路最终高度，附近逐站接坡后回归原剖面，避免隐藏错台。
 *
     * The legacy footprint stop is retained as a conservative fallback, but
     * its endpoint must still inherit the completed road's final construction
     * height. The widening envelope enforces one vertical block per two road
     * stations near the junction and then naturally rejoins the original
     * profile instead of leaving a hidden two-road step.
     */
    static List<Integer> alignJunctionProfile(List<Integer> source, int junctionIndex, int junctionY) {
        if (source == null || source.isEmpty() || junctionIndex < 0 || junctionIndex >= source.size()) {
            return source == null ? List.of() : List.copyOf(source);
        }
        int[] result = source.stream().mapToInt(Integer::intValue).toArray();
        result[junctionIndex] = junctionY;
        int direction = junctionIndex == 0 ? 1 : -1;
        for (int distance = 1, index = junctionIndex + direction;
             index >= 0 && index < result.length;
             distance++, index += direction) {
            int permittedDelta = (distance + 1) / 2;
            result[index] = Math.max(junctionY - permittedDelta,
                    Math.min(junctionY + permittedDelta, result[index]));

            int previous = index - direction;
            result[index] = Math.max(result[previous] - 1,
                    Math.min(result[previous] + 1, result[index]));
            int previousTwo = index - direction * 2;
            if (previousTwo >= 0 && previousTwo < result.length) {
                result[index] = Math.max(result[previousTwo] - 1,
                        Math.min(result[previousTwo] + 1, result[index]));
            }
        }
        List<Integer> aligned = new ArrayList<>(result.length);
        for (int value : result) aligned.add(value);
        return List.copyOf(aligned);
    }

    static void putFootprint(RoadSegmentPlacement segment,
                                     int y,
                                     Set<Long> footprint,
                                     Map<Long, Integer> heights) {
        long middleKey = key(segment.middlePos().getX(), segment.middlePos().getZ());
        footprint.add(middleKey);
        heights.put(middleKey, y);
        if (segment.positions() == null) return;
        for (BlockPos pos : segment.positions()) {
            if (pos == null) continue;
            long footprintKey = key(pos.getX(), pos.getZ());
            footprint.add(footprintKey);
            heights.put(footprintKey, y);
        }
    }

    static List<Integer> retainTargetY(RoadData road, int from, int toExclusive) {
        List<Integer> source = road.targetY();
        List<Integer> retained = new ArrayList<>(toExclusive - from);
        for (int i = from; i < toExclusive; i++) {
            retained.add(source != null && i < source.size()
                    ? source.get(i) : road.roadSegmentList().get(i).middlePos().getY());
        }
        return List.copyOf(retained);
    }

    static List<RoadSpan> retainSpans(RoadData road,
                                              int from,
                                              int toExclusive,
                                              List<RoadSegmentPlacement> retained) {
        List<RoadSpan> spans = road == null ? List.of() : road.spans();
        if (spans == null || spans.isEmpty()) return List.of();
        List<RoadSegmentPlacement> source = road.roadSegmentList();
        Map<Long, Integer> sourceIndices = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            RoadSegmentPlacement segment = source.get(i);
            if (segment != null && segment.middlePos() != null) sourceIndices.put(xzKey(segment.middlePos()), i);
        }
        List<RoadSpan> result = new ArrayList<>();
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = sourceIndices.get(xzKey(span.start()));
            Integer end = sourceIndices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int overlapFrom = Math.max(Math.min(start, end), from);
            int overlapTo = Math.min(Math.max(start, end), toExclusive - 1);
            if (overlapTo - overlapFrom + 1 < 2) continue;
            if ((span.type() == SpanType.BRIDGE || span.type() == SpanType.ROAD_CROSSING)
                    && (overlapFrom != Math.min(start, end) || overlapTo != Math.max(start, end))) {
                continue;
            }
            result.add(new RoadSpan(source.get(overlapFrom).middlePos(), source.get(overlapTo).middlePos(), span.type()));
        }
        return List.copyOf(result);
    }

    static List<RoadSpan> addJunctionMetadata(RoadData primary,
                                                       int primaryIntersection,
                                                       List<RoadSegmentPlacement> retained,
                                                       List<RoadSpan> spans,
                                                       boolean joinsEnd) {
        if (retained.isEmpty()) return spans;
        List<RoadSpan> result = new ArrayList<>(spans);
        int retainedIndex = joinsEnd ? retained.size() - 1 : 0;
        int otherIndex = joinsEnd ? Math.max(0, retainedIndex - 1) : Math.min(retained.size() - 1, 1);
        BlockPos first = retained.get(retainedIndex).middlePos();
        BlockPos second = retained.get(otherIndex).middlePos();
        if (first == null || second == null) return List.copyOf(result);
        boolean tunnelJunction = isInSpan(primary, primaryIntersection, SpanType.TUNNEL)
                || isInSpan(spans, retained, retainedIndex, SpanType.TUNNEL);
        result.add(new RoadSpan(first, second,
                tunnelJunction ? SpanType.TUNNEL_JUNCTION : SpanType.JUNCTION_ANCHOR));
        return List.copyOf(result);
    }

    static int findIntersectingPrimaryIndex(RoadData primary,
                                                     RoadSegmentPlacement secondary,
                                                     int secondaryY,
                                                     boolean[] excluded) {
        if (primary == null || secondary == null) return -1;
        Set<Long> secondaryFootprint = new HashSet<>();
        if (secondary.middlePos() != null) {
            secondaryFootprint.add(key(secondary.middlePos().getX(), secondary.middlePos().getZ()));
        }
        if (secondary.positions() != null) {
            for (BlockPos pos : secondary.positions()) {
                if (pos != null) secondaryFootprint.add(key(pos.getX(), pos.getZ()));
            }
        }
        for (int i = 0; i < primary.roadSegmentList().size(); i++) {
            if (excluded != null && i < excluded.length && excluded[i]) continue;
            if (Math.abs(targetAt(primary, i) - secondaryY) > 1) continue;
            RoadSegmentPlacement segment = primary.roadSegmentList().get(i);
            if (segment == null || segment.middlePos() == null) continue;
            if (secondaryFootprint.contains(key(segment.middlePos().getX(), segment.middlePos().getZ()))) return i;
            if (segment.positions() == null) continue;
            for (BlockPos pos : segment.positions()) {
                if (pos != null && secondaryFootprint.contains(key(pos.getX(), pos.getZ()))) return i;
            }
        }
        return -1;
    }

    static boolean isInSpan(RoadData road, int index, SpanType type) {
        if (road == null || index < 0 || index >= road.roadSegmentList().size()) return false;
        return isInSpan(road.spans(), road.roadSegmentList(), index, type);
    }

    static boolean isInSpan(List<RoadSpan> spans,
                                    List<RoadSegmentPlacement> segments,
                                    int index,
                                    SpanType type) {
        if (spans == null || segments == null || index < 0 || index >= segments.size()) return false;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : spans) {
            if (span == null || span.type() != type) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start != null && end != null && index >= Math.min(start, end) && index <= Math.max(start, end)) return true;
        }
        return false;
    }

    static int firstIntersection(RoadData road,
                                         Set<Long> footprint,
                                         Map<Long, Integer> heights,
                                         boolean[] excluded) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        for (int i = 0; i < segments.size(); i++) {
            if (excluded != null && i < excluded.length && excluded[i]) continue;
            if (intersects(segments.get(i), targetAt(road, i), footprint, heights)) return i;
        }
        return -1;
    }

    static int lastIntersection(RoadData road,
                                        Set<Long> footprint,
                                        Map<Long, Integer> heights,
                                        boolean[] excluded) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (excluded != null && i < excluded.length && excluded[i]) continue;
            if (intersects(segments.get(i), targetAt(road, i), footprint, heights)) return i;
        }
        return -1;
    }

    static boolean intersects(RoadSegmentPlacement segment,
                                      int y,
                                      Set<Long> footprint,
                                      Map<Long, Integer> heights) {
        if (segment == null || segment.middlePos() == null) return false;
        if (segment.positions() != null) {
            for (BlockPos pos : segment.positions()) {
                if (pos == null || !footprint.contains(key(pos.getX(), pos.getZ()))) continue;
                Integer existingY = heights.get(key(pos.getX(), pos.getZ()));
                if (existingY != null && Math.abs(existingY - y) <= 1) return true;
            }
        }
        Integer existingY = heights.get(key(segment.middlePos().getX(), segment.middlePos().getZ()));
        return existingY != null && Math.abs(existingY - y) <= 1;
    }

    /** 特殊跨度采用完整闭区间，保留原入口供已有调用方使用。 */
    static boolean[] specialSpanMask(RoadData road) {
        return new JunctionSpanIndex(road).special();
    }

    /** 侧向接路规则由纯查询对象维护，编排层不再重复实现跨度解释。 */
    static boolean[] lateralJunctionBlockedMask(RoadData road) {
        return new JunctionSpanIndex(road).blocked();
    }

    /** 交点搜索与接路禁区的端点规则不同，不能合并成一个掩码。 */
    static boolean[] lateralIntersectionSearchExcludedMask(RoadData road) {
        return new JunctionSpanIndex(road).searchExcluded();
    }

    static boolean[] specialSpanMaskForSegments(List<RoadSegmentPlacement> segments, List<RoadSpan> spans) {
        return new JunctionSpanIndex(segments, spans).special();
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static boolean isAcceptableCandidate(RoadData current, RoadData candidate, double currentCost) {
        if (candidate == null || candidate.equals(current) || !RoadDataValidator.isValidFinalRoad(candidate)) return false;
        double candidateCost = constructionLength(candidate);
        double limit = currentCost + MAX_JUNCTION_CONSTRUCTION_OVERHEAD;
        if (candidateCost > limit) {
            LOGGER.debug("【路口施工成本超限，拒绝该候选】 " + "RoadWeaver rejected junction cost owners={} -> {} constructionCost={} limit={}",
                    candidate.ownerA2dKey(), candidate.ownerB2dKey(), candidateCost, limit);
            return false;
        }
        return true;
    }

    static double constructionLength(RoadData road) {
        if (road == null || road.roadSegmentList() == null) return Double.POSITIVE_INFINITY;
        // P0-post B2: reuse the cached span mask instead of rebuilding the
        // xz->index map for every candidate query.
        boolean[] reused = RoadSnapService.spanMaskView(road, SpanType.NETWORK_REUSE);
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

    static int targetAt(RoadData road, int index) {
        List<Integer> targetY = road.targetY();
        return targetY != null && index >= 0 && index < targetY.size() && targetY.get(index) != null
                ? targetY.get(index) : road.roadSegmentList().get(index).middlePos().getY();
    }

    static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
