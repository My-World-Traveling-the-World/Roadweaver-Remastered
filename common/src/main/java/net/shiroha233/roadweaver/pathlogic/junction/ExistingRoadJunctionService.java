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

/** Reuses a completed road when a new road reaches its already-connected network. */
public final class ExistingRoadJunctionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final double MAX_JUNCTION_CONSTRUCTION_OVERHEAD =
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
    private static final long JUNCTION_REFINE_TIMEOUT_MS = 120_000L;

    private static long junctionRefineTimeoutMs() {
        return JUNCTION_REFINE_TIMEOUT_MS;
    }

    private ExistingRoadJunctionService() {
    }

    private record JunctionSelection(RoadData road, RoadData donor) {
    }

    private record MergeWork(int index, RoadData secondary, JunctionSelection selection) {
    }

    private record MergeResult(int index, RoadData secondary, RoadData candidate, RoadData donor) {
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
        if (roads == null || roads.isEmpty()) {
            return new MergeBatchResult(roads == null ? List.of() : List.copyOf(roads), Set.of());
        }
        if (roads.size() < 2 && level == null) {
            return new MergeBatchResult(List.copyOf(roads), Set.of());
        }
        List<RoadData> current = new ArrayList<>();
        for (RoadData road : roads) {
            if (road != null && road.roadSegmentList() != null && road.roadSegmentList().size() >= 5) {
                current.add(road);
            }
        }
        List<RoadData> persisted = level == null ? List.of() : loadNearbyPersisted(level, current);
        TerrainSamplingCache refinementCache = level == null ? null : new TerrainSamplingCache();
        StreamsReflowingCompat.SamplingSession streams = level == null
                ? null : StreamsReflowingCompat.openSession(level);
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
                if (best != secondary && refinementCache != null) {
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
            BraidPrecheck braid = resolveParallelOverlaps(level, current, persisted, mergedIndices);

            // Expensive tunnel/profile/interface work is independent once the
            // donor selection snapshot is fixed. Run it on the post-process
            // pool with one private sampling cache per route.
            long refineStartedAt = System.nanoTime();
            List<CompletableFuture<MergeResult>> futures = new ArrayList<>(work.size());
            for (MergeWork item : work) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> refineMergedCandidate(level, item),
                        ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.POSTPROCESS)));
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
                TerrainSamplingCache validationCache = refinementCache;
                best = RoadDataValidator.repairFinalHeightSteps(best);
                RoadDataValidator.ValidationResult dataValidation = RoadDataValidator.validateFinalRoad(best);
                FinalRoadStructureValidator.ValidationResult structureValidation = dataValidation.valid()
                        ? FinalRoadStructureValidator.validate(level, best, validationCache, streams)
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
            if (refinementCache != null) refinementCache.clear();
        }
        return new MergeBatchResult(List.copyOf(current), refinedRoadFingerprints);
    }

    /** 重叠段两侧各多禁几站，避免改道只是把重叠往后挪两站。 */
    private static final int BRAID_BAN_STATION_MARGIN = 4;
    /** 单条路最多重算几次。 */
    private static final int BRAID_REROUTE_ATTEMPTS = RoadConstants.DEFAULT_BRAID_REROUTE_ATTEMPTS;
    /**
     * 改道后允许的最短长度（相对原站数的比例）。
     *
     * <p>0.9.019（O-10）：原来只要求新路 ≥ {@code MIN_RUN}(6) 站，等于「一条千站的
     * 路可以被一个 6 站的短桩替换掉」。0.6 的下界既挡住短桩，又给山区绕行留出
     * 40% 的收缩余地（绕行通常更长而不是更短，所以这条门极少咬）。</p>
     */
    private static final double BRAID_REROUTE_MIN_LENGTH_RATIO = 0.6D;
    /** 包围盒预筛富余：两条路的包围盒差得比这还远就不必逐站判定。 */
    private static final int BRAID_BOX_MARGIN = RoadBraidDetector.MAX_DISTANCE + 16;

    /**
     * 预检与改道的记账。
     *
     * <p>{@code originals} 是每条路<b>改道之前</b>的原件（没改道过的下标为
     * {@code null}），供提交阶段校验失败时兜底 —— 见修 B-2。</p>
     */
    private record BraidPrecheck(int pairs, int rerouted, int rejected, long scanMs, long rerouteMs,
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
    private static BraidPrecheck resolveParallelOverlaps(ServerLevel level,
                                                         List<RoadData> batch,
                                                         List<RoadData> persisted,
                                                         boolean[] skip) {
        if (level == null || batch == null || batch.size() < 2) return BraidPrecheck.none();
        PathfindingCostConfig cfg;
        try {
            cfg = ConfigService.get().pathfindingCost();
        } catch (RuntimeException unavailable) {
            return BraidPrecheck.none();
        }
        if (cfg == null || !cfg.braidRerouteOnOverlap()) return BraidPrecheck.none();
        double minDot = cfg.braidMinDirectionDot();
        int radius = cfg.braidRerouteRadius();
        double maxLengthRatio = cfg.braidRerouteMaxLengthRatio();
        int maxDistance = RoadBraidDetector.MAX_DISTANCE;

        long scanStartedAt = System.nanoTime();
        long rerouteMs = 0L;
        int pairs = 0;
        int rerouted = 0;
        int rejected = 0;
        List<RoadData> originals = new ArrayList<>(
                java.util.Collections.nCopies(batch.size(), (RoadData) null));
        List<RoadData> owners = new ArrayList<>(persisted.size() + batch.size());
        owners.addAll(persisted);
        for (int index = 0; index < batch.size(); index++) {
            // 0.9.049（D-23）：必须在 skip 之前就把"原件"快照下来。
            //
            // commit 阶段（:222）用 `braid.originalAt(result.index())` 取"改道**之前**那条
            // 已经成功生成过的路"做兜底。但 `results` 里的 MergeResult 恰恰是**已合并/已改道**
            // 的那批，而被 `skip[index]` 跳过的正是这批下标 —— 原来唯一写入 `originals`
            // 的 :382 位于 skip 之后 ⇒ 对这批下标**永远不执行** ⇒ `originalAt()` 恒 null
            // ⇒ 兜底永不生效，日志还恒打 `fallback=rerouted`（实际退的是 secondary）。
            //
            // 这里预先把 batch 原件填进去：对改道过的路，值与 :382 记录的完全相同
            // （attempt 0 的 secondary 就是 batch.get(index)）；对被 skip 的路，这是第一次
            // 有值，兜底终于可用。:382 之后自动变成 no-op，无需改动。
            originals.set(index, batch.get(index));
            if (skip != null && index < skip.length && skip[index]) continue;   // 已合并，不必改道
            RoadData secondary = batch.get(index);
            owners.add(secondary);
            if (owners.size() < 2) continue;
            for (int attempt = 0; attempt < BRAID_REROUTE_ATTEMPTS; attempt++) {
                RoadData braidedWith = null;
                RoadBraidDetector.Report report = null;
                for (RoadData owner : owners) {
                    if (owner == secondary) continue;
                    if (!boxMayOverlap(owner, secondary, maxDistance)) continue;
                    RoadBraidDetector.Report candidate =
                            RoadBraidDetector.compare(owner, secondary, minDot, maxDistance);
                    if (!candidate.braided()) continue;
                    if (report == null || candidate.stations() > report.stations()) {
                        report = candidate;
                        braidedWith = owner;
                    }
                }
                if (braidedWith == null) break;
                pairs++;
                LOGGER.info("RoadWeaver braid detected attempt={} primary={} secondary={} "
                                + "secondaryRange={}..{} primaryRange={}..{} stations={} "
                                + "votes={}/{} worstDot={} medianDistance={}",
                        attempt, RoadShardStorage.computeFingerprint(braidedWith),
                        RoadShardStorage.computeFingerprint(secondary),
                        report.secondaryFrom(), report.secondaryTo(),
                        report.primaryFrom(), report.primaryTo(), report.stations(),
                        report.parallelVotes(), report.samples(),
                        String.format("%.3f", report.worstDot()), report.medianDistance());
                long rerouteStartedAt = System.nanoTime();
                // 只记第一次改道前的原件：第二次 attempt 的起点已经是改道产物，
                // 兜底要的是「生成链原本产出的那条成功路」。
                if (originals.get(index) == null) originals.set(index, secondary);
                RoadData candidate = rerouteAvoiding(level, braidedWith, secondary, report,
                        radius, maxLengthRatio, minDot, maxDistance);
                rerouteMs += elapsedMs(rerouteStartedAt);
                if (candidate == null) {
                    rejected++;
                    LOGGER.info("RoadWeaver braid reroute rejected primary={} secondary={} "
                                    + "stations={} -> kept original",
                            RoadShardStorage.computeFingerprint(braidedWith),
                            RoadShardStorage.computeFingerprint(secondary), report.stations());
                    break;
                }
                LOGGER.info("RoadWeaver braid rerouted primary={} secondary={} stations={} -> {} "
                                + "banCells={} secondaryRange={}..{}",
                        RoadShardStorage.computeFingerprint(braidedWith),
                        RoadShardStorage.computeFingerprint(secondary),
                        secondary.roadSegmentList().size(), candidate.roadSegmentList().size(),
                        CorridorBan.aroundRoadRun(braidedWith,
                                report.primaryFrom() - BRAID_BAN_STATION_MARGIN,
                                report.primaryTo() + BRAID_BAN_STATION_MARGIN, radius).cellCount(),
                        report.secondaryFrom(), report.secondaryTo());
                batch.set(index, candidate);
                secondary = candidate;
                // 修 B-1（0.9.019）：owners 里这一位还是改道**前**的旧引用。
                // 不换掉的话，下一轮 attempt 会拿新路去比自己的旧版本 —— 两者在
                // 共同端点附近必然贴得很近 ⇒ 必然再判一次并排 ⇒ braidPairs 虚高，
                // 而且每次都要白跑一遍完整生成链（Road.generateRoad 全流程）。
                owners.set(owners.size() - 1, candidate);
                rerouted++;
            }
        }
        // 0.9.033：scanMs 原来是把「检测 + 改道 A*」整段包住的墙钟，而 rerouteMs 又单独报了
        // 一次 ⇒ 两个数字重叠，读日志的人会以为「预检扫描很贵」。真机实测
        // braidScanMs=7481 / braidRerouteMs=7468 ⇒ 扫描本身只有 13 ms，7.5 秒全在那次
        // 返回 null 的改道 A* 上。这里把 reroute 从 scan 里扣掉，两个字段各自独立可加。
        long scanOnlyMs = Math.max(0L, elapsedMs(scanStartedAt) - rerouteMs);
        return new BraidPrecheck(pairs, rerouted, rejected, scanOnlyMs, rerouteMs,
                java.util.Collections.unmodifiableList(originals));
    }

    /**
     * 包围盒预筛：两个包围盒的间隔必须小于站距上限才有并排的可能。
     *
     * <p>读不到包围盒时返回 {@code true}（宁可多算一次，不可漏判）。</p>
     */
    private static boolean boxMayOverlap(RoadData primary, RoadData secondary, int maxDistance) {
        try {
            RoadSnapService.RoadBounds a = RoadSnapService.roadBounds(primary);
            RoadSnapService.RoadBounds b = RoadSnapService.roadBounds(secondary);
            if (a == null || b == null) return true;
            return a.gap(b) <= maxDistance + BRAID_BOX_MARGIN;
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    /**
     * 让 {@code secondary} 绕开 {@code primary} 的重叠段重算一遍，返回新路；不可行时返回
     * {@code null}。
     *
     * <p>四道门，任一不过就保留原路：</p>
     * <ol>
     *   <li>重算成功且站点数没有塌缩（{@code >= MIN_RUN}）；</li>
     *   <li>长度不超过原路的 {@code braidRerouteMaxLengthRatio} 倍（绕太远不如不改）；</li>
     *   <li>{@code RoadDataValidator.validateFinalRoad} 通过；</li>
     *   <li>与原来的那条路<b>仍然不再并排</b>（改完还是并排就说明禁令不够，改道无意义）。</li>
     * </ol>
     */
    private static RoadData rerouteAvoiding(ServerLevel level,
                                            RoadData primary,
                                            RoadData secondary,
                                            RoadBraidDetector.Report report,
                                            int radius,
                                            double maxLengthRatio,
                                            double minDot,
                                            int maxDistance) {
        if (report.primaryFrom() < 0 || report.primaryTo() < 0) return null;
        StructureConnection connection = connectionOf(secondary);
        if (connection == null) return null;
        List<RoadSegmentPlacement> originalStations = secondary.roadSegmentList();
        if (originalStations == null || originalStations.isEmpty()) return null;
        CorridorBan ban = CorridorBan.aroundRoadRun(primary,
                report.primaryFrom() - BRAID_BAN_STATION_MARGIN,
                report.primaryTo() + BRAID_BAN_STATION_MARGIN, radius,
                // R-05：在次路两个端点周围把禁令挖开（半径取 24 与 1.33×radius 的较大者）。
                // 不挖洞的话，重叠段一旦覆盖到端点，A* 的终止条件（manhattan < 2×步距=16）
                // 落在禁区（有效半宽 20~23）里面 ⇒ 必然无解 ⇒ 该形态改道成功率 0%，
                // 而且每次白付一遍完整生成链。
                List.of(connection.from(), connection.to()),
                Math.max(24, (int) Math.ceil(radius * 1.33D)));
        if (ban.isEmpty()) return null;
        RoadData candidate;
        try {
            candidate = CorridorBan.withScoped(ban,
                    () -> RoadGenerationService.regenerateRoadWithoutPersist(level, connection));
        } catch (RuntimeException failure) {
            LOGGER.warn("RoadWeaver braid reroute failed primary={} secondary={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary), failure);
            return null;
        }
        if (candidate == null || candidate.roadSegmentList() == null) {
            // 0.9.028：这一支原来是**静默**返回 null，调用方只会打一句
            // `braid reroute rejected … kept original`，看不出是「A* 无解」还是别的门拦的。
            LOGGER.info("RoadWeaver braid reroute null-candidate primary={} secondary={} "
                            + "banCells={} radius={} primaryRange={}..{}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary),
                    ban.cellCount(), radius, report.primaryFrom(), report.primaryTo());
            return null;
        }
        // O-10（0.9.019）：原来只要求新路 ≥ MIN_RUN(6) 站 —— 一条千站的路理论上
        // 可以被一个 6 站的短桩「替换掉」，那不是改道而是把路删了。下界取
        // max(12, 0.6×原站数)：既挡住短桩，也给山区绕行留 40% 的收缩余地。
        int minimumStations = Math.max(12,
                (int) Math.floor(originalStations.size() * BRAID_REROUTE_MIN_LENGTH_RATIO));
        if (candidate.roadSegmentList().size() < minimumStations) {
            LOGGER.info("RoadWeaver braid reroute too short primary={} secondary={} stations={} minimum={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary),
                    candidate.roadSegmentList().size(), minimumStations);
            return null;
        }
        int limit = (int) Math.floor(originalStations.size() * maxLengthRatio);
        if (candidate.roadSegmentList().size() > limit) {
            LOGGER.info("RoadWeaver braid reroute too long primary={} secondary={} stations={} limit={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary),
                    candidate.roadSegmentList().size(), limit);
            return null;
        }
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(candidate);
        if (validation == null || !validation.valid()) {
            LOGGER.info("RoadWeaver braid reroute invalid primary={} secondary={} reason={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary),
                    validation == null ? null : validation.reason());
            return null;
        }
        RoadBraidDetector.Report afterReroute = RoadBraidDetector.compare(primary, candidate, minDot, maxDistance);
        if (afterReroute.braided()) {
            // 0.9.028：这一支原本静默 —— 是「改道了但还是并排」这一档。
            LOGGER.info("RoadWeaver braid reroute still-parallel primary={} secondary={} stations={}..{} "
                            + "medianDistance={} worstDot={}",
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(secondary),
                    afterReroute.secondaryFrom(), afterReroute.secondaryTo(),
                    afterReroute.medianDistance(), afterReroute.worstDot());            return null;
        }
        return candidate;
    }

    /** 用一条路的首末站点还原它的连接（供重算使用）。 */
    private static StructureConnection connectionOf(RoadData road) {
        List<RoadSegmentPlacement> stations = road == null ? null : road.roadSegmentList();
        if (stations == null || stations.size() < 2) return null;
        RoadSegmentPlacement first = stations.get(0);
        RoadSegmentPlacement last = stations.get(stations.size() - 1);
        BlockPos from = first == null ? null : first.middlePos();
        BlockPos to = last == null ? null : last.middlePos();
        if (from == null || to == null || from.equals(to)) return null;
        return new StructureConnection(from, to, ConnectionStatus.PLANNED);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static MergeResult refineMergedCandidate(ServerLevel level, MergeWork item) {
        RoadData secondary = item.secondary();
        RoadData best = item.selection().road();
        if (level == null || best == null || secondary == null || best == secondary) {
            return new MergeResult(item.index(), secondary, secondary, item.selection().donor());
        }
        TerrainSamplingCache cache = new TerrainSamplingCache();
        try {
            // 0.8.318：分段计时。真机 refineMs=118s（6 条路并行，后处理 134s 的 88%），
            // 但不知道 tunnel/dedup/repair 哪段贵。纯观测，不改行为。
            long tunnelStartedAt = System.nanoTime();
            best = PathSpanExtractor.refineRoadTunnels(best, level, cache);
            long tunnelMs = elapsedMs(tunnelStartedAt);
            long dedupStartedAt = System.nanoTime();
            best = RoadSnapService.enforceNetworkReuseConstructionOwnership(
                    RoadSnapService.deduplicateExactSpans(best), item.selection().donor());
            long dedupMs = elapsedMs(dedupStartedAt);
            int nodesBeforeRepair = best.roadSegmentList() == null ? 0 : best.roadSegmentList().size();
            long repairStartedAt = System.nanoTime();
            RoadData refinedHeight = RoadPathCalculator.repairAfterNetworkMerge(
                    best, level, cache,
                    new net.shiroha233.roadweaver.core.model.StructureConnection(
                            PlanningUtils.posFrom2dKey(best.ownerA2dKey()),
                            PlanningUtils.posFrom2dKey(best.ownerB2dKey()),
                            net.shiroha233.roadweaver.core.model.ConnectionStatus.GENERATING));
            long repairMs = elapsedMs(repairStartedAt);
            LOGGER.info("RoadWeaver merge refine timing: index={} owners={} -> {} tunnelMs={} dedupMs={} "
                            + "repairMs={} nodes={} -> {}",
                    item.index(), secondary.ownerA2dKey(), secondary.ownerB2dKey(),
                    tunnelMs, dedupMs, repairMs, nodesBeforeRepair,
                    refinedHeight == null || refinedHeight.roadSegmentList() == null
                            ? -1 : refinedHeight.roadSegmentList().size());
            if (refinedHeight == null) {
                LOGGER.warn("RoadWeaver discarded parallel junction rewrite because local interface repair failed owners={} -> {}",
                        best.ownerA2dKey(), best.ownerB2dKey());
                // 0.8.328：合并结果整体丢弃时，至少保留"共用走廊"的复用标记——否则
                // secondary 会连同复用段一起退回合并前版本，两条路把同一段走廊各铺一遍
                // （真机 20260909 21.06 存档村口那对路：repairMs=8705 失败后退回 1184 站，
                // 94 站共用走廊上出现两条并排的路）。
                //
                // 0.8.338：兜底结果必须**再过一遍** enforceNetworkReuseConstructionOwnership。
                // 上面的第 ② 步只在"合并后版本"(best) 上抑制了与复用段重叠的
                // bridge/tunnel/road_crossing span；而这里兜底用的是 item.secondary()
                // —— **合并前版本**，它从未经过第 ② 步，于是新补的 NETWORK_REUSE 段
                // 被 secondary 自带的同名 span 整个压住，施工照旧按隧道挖，复用失效。
                //
                // 真机取证（存档 20260911 00_08，端点 (97,281)）：
                //   merge refine timing: index=9 owners=-2061584301600 -> 416611827993
                //                        repairMs=9149 nodes=1138 -> -1   (refinedHeight == null)
                //   discarded parallel junction rewrite ...
                //   兜底确实补上了复用：NETWORK_REUSE (69,109,253)->(95,95,280) idx 1132..1182
                //   但同一区间还压着 TUNNEL (69,110,252)->(96,94,280) idx 1131..1183，
                //   两者几乎逐格重叠 —— 该 TUNNEL 在最终存档里仍在，复用被它盖住。
                //   而 donor 5168200556913239270 在同一位置**确有**同类型 TUNNEL span，
                //   即 ownerHasSameTypeSpanAt 条件成立，过一遍抑制就会把它删掉。
                RoadData reused = JunctionConnector.createSecondaryReuseAtCorridor(
                        item.selection().donor(), secondary);
                best = RoadSnapService.enforceNetworkReuseConstructionOwnership(
                        reused, item.selection().donor());
            } else {
                best = refinedHeight;
            }
            return new MergeResult(item.index(), secondary, best, item.selection().donor());
        } catch (PlanningSnapshotUnavailableException unavailable) {
            LOGGER.warn("RoadWeaver retained original road because parallel junction structure snapshot is unavailable owners={} -> {} reason={}",
                    secondary.ownerA2dKey(), secondary.ownerB2dKey(), unavailable.getMessage());
            return new MergeResult(item.index(), secondary, secondary, item.selection().donor());
        } finally {
            cache.clear();
        }
    }

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
        if (level == null || road == null || road.roadSegmentList() == null
                || road.roadSegmentList().size() < 5) return road;
        JunctionSelection selection = selectSingleNetworkJunction(loadNearbyPersisted(level, List.of(road)), road);
        RoadData best = selection.road();
        if (best == road) return road;
        best = RoadSnapService.enforceNetworkReuseConstructionOwnership(
                RoadSnapService.deduplicateExactSpans(
                        PathSpanExtractor.refineRoadTunnels(best, level, cache)), selection.donor());
        if (selection.donor() != null) {
            RoadData loweredDonor = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(selection.donor(), best);
            if (loweredDonor != selection.donor()) {
                RoadShardStorage.replaceRoad(level,
                        RoadShardStorage.computeFingerprint(selection.donor()), loweredDonor);
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
    private static JunctionSelection selectSingleNetworkJunction(List<RoadData> owners, RoadData secondary) {
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

    private static List<RoadData> loadNearbyPersisted(ServerLevel level, List<RoadData> roads) {
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
        return RoadShardStorage.queryRect(level,
                minX - margin, minZ - margin, maxX + margin, maxZ + margin);
    }

    private static boolean plausibleDonor(RoadData primary, RoadData secondary,
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

    private static RoadData trimRoadDataToRoad(RoadData primary, RoadData secondary) {
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

    private static void putFootprint(RoadSegmentPlacement segment,
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

    private static List<Integer> retainTargetY(RoadData road, int from, int toExclusive) {
        List<Integer> source = road.targetY();
        List<Integer> retained = new ArrayList<>(toExclusive - from);
        for (int i = from; i < toExclusive; i++) {
            retained.add(source != null && i < source.size()
                    ? source.get(i) : road.roadSegmentList().get(i).middlePos().getY());
        }
        return List.copyOf(retained);
    }

    private static List<RoadSpan> retainSpans(RoadData road,
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

    private static List<RoadSpan> addJunctionMetadata(RoadData primary,
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

    private static int findIntersectingPrimaryIndex(RoadData primary,
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

    private static boolean isInSpan(RoadData road, int index, SpanType type) {
        if (road == null || index < 0 || index >= road.roadSegmentList().size()) return false;
        return isInSpan(road.spans(), road.roadSegmentList(), index, type);
    }

    private static boolean isInSpan(List<RoadSpan> spans,
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

    private static int firstIntersection(RoadData road,
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

    private static int lastIntersection(RoadData road,
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

    private static boolean intersects(RoadSegmentPlacement segment,
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

    private static boolean[] specialSpanMask(RoadData road) {
        return specialSpanMaskForSegments(road.roadSegmentList(), road.spans());
    }

    /** Bridges, water crossings, and tunnel sidewalls cannot accept a lateral junction. */
    private static boolean[] lateralJunctionBlockedMask(RoadData road) {
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

    private static boolean[] lateralIntersectionSearchExcludedMask(RoadData road) {
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

    private static boolean[] specialSpanMaskForSegments(
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

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static boolean isAcceptableCandidate(RoadData current, RoadData candidate, double currentCost) {
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

    private static double constructionLength(RoadData road) {
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

    private static int targetAt(RoadData road, int index) {
        List<Integer> targetY = road.targetY();
        return targetY != null && index >= 0 && index < targetY.size() && targetY.get(index) != null
                ? targetY.get(index) : road.roadSegmentList().get(index).middlePos().getY();
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
