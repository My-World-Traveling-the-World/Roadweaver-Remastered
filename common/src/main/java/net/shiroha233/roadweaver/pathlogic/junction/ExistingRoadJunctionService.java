/* 文件职责：新路到达已连通网络时复用既有路 —— 避免在已有路上再铺一条。
 *
 * 合并会**重建站序列**，所以任何依赖「站索引稳定」的下游都必须重新解析
 * （这正是 RoadSpan 只存 BlockPos 会导致桥被静默丢掉的根源，见 spec/RoadSpec）。
 */
package net.shiroha233.roadweaver.pathlogic.junction;

import net.shiroha233.roadweaver.postprocess.JunctionConnector;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotUnavailableException;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
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
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
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
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareService;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;
import static net.shiroha233.roadweaver.pathlogic.junction.ParallelOverlapResolver.*;

/** Reuses a completed road when a new road reaches its already-connected network. */
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
                    return CompletableFuture.supplyAsync(
                            () -> refineMergedCandidate(level, item),
                            ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.POSTPROCESS));
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

    /** Final merged batch plus the fingerprints of roads already continuity-repaired during refinement. */
    public record MergeBatchResult(List<RoadData> roads, Set<Long> refinedRoadFingerprints) {
    }

    /**
     * Connects the final successful routes before the initial batch is
     * persisted. Nearby persisted roads and earlier routes in this batch may
     * supply the shared corridor; bridge/tunnel segments are never selected as
     * the junction point.
     */
    public static List<RoadData> connectPlannedBatch(List<RoadData> roads) {
        return connectPlannedBatch(null, roads);
    }

    /**
     * Forms the final road network before any new road in the batch is
     * persisted. Persisted roads and earlier batch entries are immutable;
     * only the later route may be shortened or redirected onto them.
     */
    public static List<RoadData> connectPlannedBatch(ServerLevel level, List<RoadData> roads) {
        return connectPlannedBatchDetailed(level, roads).roads();
    }

    public static MergeBatchResult connectPlannedBatchDetailed(ServerLevel level, List<RoadData> roads) {
        return connectPlannedBatchWithAccess(level == null ? null : new WorldBatchAccess(level), roads);
    }

    static MergeBatchResult connectPlannedBatchWithAccess(BatchAccess access, List<RoadData> roads) {
        if (roads == null || roads.isEmpty()) {
            return new MergeBatchResult(roads == null ? List.of() : List.copyOf(roads), Set.of());
        }
        if (roads.size() < 2 && access == null) {
            return new MergeBatchResult(List.copyOf(roads), Set.of());
        }
        List<RoadData> current = new ArrayList<>();
        for (RoadData road : roads) {
            if (road != null && road.roadSegmentList() != null && road.roadSegmentList().size() >= 5) {
                current.add(road);
            }
        }
        List<RoadData> persisted = access == null ? List.of() : loadNearbyPersisted(access, current);
        BatchRefinement refinement = access == null ? null : access.openRefinement();
        InitialGenerationProgressTracker.setConstructionChunkPlan(
                Math.max(1, current.size()), "merging_final_network");
        Set<Long> refinedRoadFingerprints = new HashSet<>();
        long mergeStartedAt = System.nanoTime();
        int mergedCount = 0;
        try {
            // 0.9.020（R-09）：改道的顺序改成「**先让它合并，合并不成的才改道**」。
            //
            // 0.9.018 把预检放在贪心选主之前 —— 那是反的：改道先把「能合并」的前提
            // 拆掉了（本来可以并到走廊上的路被推远），于是能合并的机会被自己损失掉。
            // 现在先跑完单向贪心选主，只对**没被合并**的路做并排重叠预检与改道。
            // Junction ownership is order-dependent: select donors and update
            // the in-memory route list sequentially first. No world sampling
            // or expensive height A* is done in this phase.
            // P0-post B3: grow one owner list instead of rebuilding
            // persisted + subList(0, i) for every route.
            List<MergeWork> work = new ArrayList<>();
            List<RoadData> owners = new ArrayList<>(persisted.size() + current.size());
            owners.addAll(persisted);
            for (int secondaryIndex = 0; secondaryIndex < current.size(); secondaryIndex++) {
                RoadData secondary = current.get(secondaryIndex);
                JunctionSelection selection = selectSingleNetworkJunction(owners, secondary);
                RoadData best = selection.road();
                current.set(secondaryIndex, best);
                owners.add(best);
                if (best != secondary && refinement != null) {
                    work.add(new MergeWork(secondaryIndex, secondary, selection));
                }
            }
            long donorSelectMs = elapsedMs(mergeStartedAt);
            mergedCount = work.size();

            // 0.9.029（R-03 共享跨越）：规划完成后先把「离得近 + 重叠够 + 平行」的两座桥并成一座，
            // 再跑并排预检 —— 顺序不能反，否则刚共享完又被 braid 判成并排。
            // 内部有包围盒预筛（只精确扫邻近的桥）+ 过渡不下就放弃 + 校验不过就回滚。
            BridgeShareService.Stats bridgeShare =
                    BridgeShareService.applyInPlace(current,
                            net.shiroha233.roadweaver.config.ConfigService.get().bridgeShareRadius());
            // R-09 收窄（0.9.027）：只有「整条被 donor 完全吸收」的路才可以跳过并排重叠预检。
            // 0.9.020 的写法是「凡是进了 work 的都跳过」（只要 donor 变了就记 true），但合并是
            // 单向贪心，被合并的路常常还留着独立路段；把它整个排除在预检之外，一旦出现
            // 「一条已合并 + 一条未合并」在河上并排，D2 的平行双桥就会漏检
            // （真机 V-8，(205,66,-162) 两条并排木桥，而 braidPairs=0）。
            // 判据用「合并结果就是 donor 本身」—— 那才叫整条被吃掉。
            boolean[] mergedIndices = new boolean[current.size()];
            int braidSkipAbsorbed = 0;
            for (MergeWork item : work) {
                if (item.index() < 0 || item.index() >= mergedIndices.length) continue;
                JunctionSelection selection = item.selection();
                if (selection != null && selection.donor() != null && selection.road() == selection.donor()) {
                    mergedIndices[item.index()] = true;
                    braidSkipAbsorbed++;
                }
            }
            BraidPrecheck braid = access == null ? BraidPrecheck.none() : access.resolveOverlaps(current, persisted, mergedIndices);

            // Expensive tunnel/profile/interface work is independent once the
            // donor selection snapshot is fixed. Run it on the post-process
            // pool with one private sampling cache per route.
            long refineStartedAt = System.nanoTime();
            List<CompletableFuture<MergeResult>> futures = new ArrayList<>(work.size());
            for (MergeWork item : work) {
                futures.add(refinement.refine(item));
            }
            List<MergeResult> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                MergeWork item = work.get(i);
                try {
                    // ★ 审计 P2-3：改为有界等待。
                    //
                    // 这里的子任务提交到 TaskRole.POSTPROCESS，而 POSTPROCESS 在
                    // executorFor() 里落到 `default -> sharedExecutor()` ——
                    // 与等待方**同一个池**。目前调用点只有 InitialGenManager
                    // （开局期，暂停闸对所有 role 豁免）所以还没出事，
                    // 但这是一条"一旦有人在运行期复用就自锁"的脆弱路径。
                    //
                    // 加超时把这个隐患彻底堵死：超时按既有语义「保留原 candidate」
                    // （下面的 catch 分支已经是这么做的），不会引入新行为。
                    results.add(futures.get(i).get(
                            junctionRefineTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS));
                } catch (java.util.concurrent.TimeoutException timedOut) {
                    LOGGER.warn("RoadWeaver merged interface check 超时（{}ms）index={} owners={} -> {}; retaining original",
                            junctionRefineTimeoutMs(), item.index(),
                            item.secondary().ownerA2dKey(), item.secondary().ownerB2dKey());
                    futures.get(i).cancel(true);
                    results.add(new MergeResult(item.index(), item.secondary(), item.secondary(), item.selection().donor()));
                } catch (Throwable failure) {
                    LOGGER.warn("RoadWeaver parallel merged interface check failed index={} owners={} -> {}; retaining original",
                            item.index(), item.secondary().ownerA2dKey(), item.secondary().ownerB2dKey(), failure);
                    results.add(new MergeResult(item.index(), item.secondary(), item.secondary(), item.selection().donor()));
                }
            }
            long refineMs = elapsedMs(refineStartedAt);
            long commitStartedAt = System.nanoTime();
            results.sort(Comparator.comparingInt(MergeResult::index));
            for (MergeResult result : results) {
                RoadData best = result.candidate();
                RoadData secondary = result.secondary();
                best = RoadDataValidator.repairFinalHeightSteps(best);
                RoadDataValidator.ValidationResult dataValidation = RoadDataValidator.validateFinalRoad(best);
                FinalRoadStructureValidator.ValidationResult structureValidation = dataValidation.valid()
                        ? refinement.validate(best)
                        : null;
                if (!dataValidation.valid() || structureValidation == null || !structureValidation.safe()) {
                    // 修 B-2（0.9.019）：这里原本一律退到 secondary，而 secondary 是
                    // **改道之后**的路（work 是在预检之后才建的）。校验不过时最安全的
                    // 兜底是改道**之前**那条已经成功生成过的路，不是改道产物本身。
                    RoadData preReroute = braid.originalAt(result.index());
                    RoadData fallback = preReroute != null ? preReroute : secondary;
                    LOGGER.warn("RoadWeaver discarded unsafe in-memory junction owners={} -> {} dataReason={} collision={} "
                                    + "fallback={}",
                            fallback.ownerA2dKey(), fallback.ownerB2dKey(), dataValidation.reason(),
                            structureValidation == null ? null : structureValidation.collision(),
                            preReroute != null ? "pre-reroute" : "rerouted");
                    best = fallback;
                } else if (result.donor() != null) {
                    RoadData loweredDonor = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(result.donor(), best);
                    if (loweredDonor != result.donor()) {
                        for (int j = 0; j < result.index(); j++) {
                            if (current.get(j) == result.donor() || current.get(j).equals(result.donor())) {
                                current.set(j, loweredDonor);
                                break;
                            }
                        }
                    }
                }
                if (best != secondary) {
                    refinedRoadFingerprints.add(RoadShardStorage.computeFingerprint(best));
                }
                current.set(result.index(), best);
            }
            for (int secondaryIndex = 0; secondaryIndex < current.size(); secondaryIndex++) {
                InitialGenerationProgressTracker.recordConstructionChunksDone(secondaryIndex + 1);
            }
            LOGGER.info("RoadWeaver network merge timing: routes={} persisted={} merged={} donorSelectMs={} "
                            + "refineMs={} commitMs={} totalMs={} braidPairs={} braidRerouted={} "
                            + "braidRejected={} braidScanMs={} braidRerouteMs={} "
                            + "braidSkipAbsorbed={} braidSkipTotal={} "
                            + "bridgeShareRoads={} bridgeShareBridges={} bridgeShareMissingEndpoints={} "
                            + "bridgeShareRecoveredEndpoints={} "
                            + "bridgeShareCandidates={} bridgeShared={} bridgeShareNoTransition={} bridgeShareInvalid={}",
                    current.size(), persisted.size(), mergedCount, donorSelectMs,
                    refineMs, elapsedMs(commitStartedAt), elapsedMs(mergeStartedAt),
                    braid.pairs(), braid.rerouted(), braid.rejected(), braid.scanMs(), braid.rerouteMs(),
                    braidSkipAbsorbed, mergedCount,
                    bridgeShare.roads(), bridgeShare.bridges(), bridgeShare.spanEndpointsMissing(),
                    bridgeShare.spanEndpointsRecovered(),
                    bridgeShare.candidates(), bridgeShare.absorbed(),
                    bridgeShare.skippedNoTransition(), bridgeShare.skippedInvalid());
        } finally {
            if (refinement != null) refinement.close();
        }
        return new MergeBatchResult(List.copyOf(current), refinedRoadFingerprints);
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

    /**
     * 0.9.018（D2 平行双桥「方案 C：后者整体改道」）：全批次两两预检并让后者改道。
     *
     * <h2>为什么不能复用已有的麻花判定</h2>
     * <p>{@code RoadSnapService.detectsBraid} 只在 {@code tryConnect} 里跑，而
     * {@code tryConnect} 的前提是先有一条路的端点被另一条吸引过来。两条各走各的
     * 平行路永远走不到那段判定（真机 49 条路只 {@code merged=12}），于是各修各的桥。
     * 这里改成不依赖端点吸引的无条件两两扫描。</p>
     *
     * <h2>顺序与不可变性</h2>
     * <p>批次内的路按列表顺序处理：下标靠后的是「后者」，可以改；下标靠前的是
     * 「前者」，本次不动。<b>持久化过的路一律不动</b>——它们已经写进存档，改它们
     * 等于改写历史地形。所以 {@code persisted} 只作为「前者」参与判定。</p>
     *
     * <h2>为什么先包围盒预筛</h2>
     * <p>逐站判定是 O(站数 × 桶内站数)。一个批次几十条路、每条千级站，
     * 无预筛的两两扫描要上亿次比较；而两条路的包围盒差得远超站距上限时根本不可能
     * 并排，用 {@code RoadBounds.gap} 一行就能剔掉绝大多数组合。</p>
     */
    /** Connects one runtime-planned road to the completed network before saving it. */
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

    /**
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
            LOGGER.info("RoadWeaver selected single planned network junction secondary={} donor={} constructionLength={} -> {}",
                    RoadShardStorage.computeFingerprint(secondary),
                    RoadShardStorage.computeFingerprint(donor), originalCost, bestCost);
        }
        return new JunctionSelection(best, donor);
    }

    private static List<RoadData> loadNearbyPersisted(RoadQuery query, List<RoadData> roads) {
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

    /** Per-donor prefilter: keep only donors whose bounding box lies within
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
        boolean[] primarySpecial = lateralJunctionBlockedMask(primary);
        boolean[] primarySearchExcluded = lateralIntersectionSearchExcludedMask(primary);
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
                "RoadWeaver shared-endpoint intersection stop primary={} secondary={} side={} cutIndex={} segments={}->{}",
                RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                joinsEnd ? "end" : "start", intersection, secondarySegments.size(), retained.size());
        return trimmed;
    }

    /**
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

    static boolean[] specialSpanMask(RoadData road) {
        return specialSpanMaskForSegments(road.roadSegmentList(), road.spans());
    }

    /** Bridges, water crossings, and tunnel sidewalls cannot accept a lateral junction. */
    static boolean[] lateralJunctionBlockedMask(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] blocked = new boolean[segments.size()];
        boolean[] physicalCrossing = new boolean[segments.size()];
        boolean[] explicitTunnelJunction = new boolean[segments.size()];
        if (road.spans() == null || road.spans().isEmpty()) return blocked;
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) index.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = index.get(xzKey(span.start()));
            Integer end = index.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (span.type() == SpanType.BRIDGE || span.type() == SpanType.ROAD_CROSSING
                    || span.type() == SpanType.PROTECTED_GAP) {
                // Keep the bridge/crossing interior isolated, but leave both
                // stable shore endpoints available for another road to join.
                // This lets two routes sharing a beach endpoint reuse one
                // bridge head instead of producing intersecting bridges.
                int blockedFrom = span.type() == SpanType.PROTECTED_GAP ? from : from + 1;
                int blockedTo = span.type() == SpanType.PROTECTED_GAP ? to : to - 1;
                for (int i = blockedFrom; i <= blockedTo; i++) {
                    blocked[i] = true;
                    physicalCrossing[i] = true;
                }
            } else if (span.type() == SpanType.TUNNEL) {
                for (int i = from + 1; i < to; i++) blocked[i] = true;
            } else if (span.type() == SpanType.TUNNEL_JUNCTION) {
                for (int i = from; i <= to; i++) explicitTunnelJunction[i] = true;
            }
        }
        for (int i = 0; i < blocked.length; i++) {
            if (explicitTunnelJunction[i] && !physicalCrossing[i]) blocked[i] = false;
        }
        return blocked;
    }

    static boolean[] lateralIntersectionSearchExcludedMask(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] blocked = new boolean[segments.size()];
        if (road.spans() == null || road.spans().isEmpty()) return blocked;
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) index.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() == SpanType.TUNNEL || span.type() == SpanType.TUNNEL_JUNCTION) continue;
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING) continue;
            Integer start = index.get(xzKey(span.start()));
            Integer end = index.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.min(start, end) + 1;
            int to = Math.max(start, end) - 1;
            for (int i = from; i <= to; i++) blocked[i] = true;
        }
        return blocked;
    }

    static boolean[] specialSpanMaskForSegments(
            List<RoadSegmentPlacement> segments, List<RoadSpan> spans) {
        boolean[] special = new boolean[segments.size()];
        if (spans == null || spans.isEmpty()) return special;
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) index.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING && span.type() != SpanType.TUNNEL
                    && span.type() != SpanType.PROTECTED_GAP)) continue;
            Integer start = index.get(xzKey(span.start()));
            Integer end = index.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) special[i] = true;
        }
        return special;
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static boolean isAcceptableCandidate(RoadData current, RoadData candidate, double currentCost) {
        if (candidate == null || candidate.equals(current) || !RoadDataValidator.isValidFinalRoad(candidate)) return false;
        double candidateCost = constructionLength(candidate);
        double limit = currentCost + MAX_JUNCTION_CONSTRUCTION_OVERHEAD;
        if (candidateCost > limit) {
            LOGGER.debug("RoadWeaver rejected junction cost owners={} -> {} constructionCost={} limit={}",
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
