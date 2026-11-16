/* 文件职责：按固定顺序选主、发起批量精修并提交通过校验的路网结果。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.postprocess.JunctionConnector;
import static net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService.*;
import static net.shiroha233.roadweaver.pathlogic.junction.ParallelOverlapResolver.elapsedMs;

/** 批次编排不负责单路几何或世界读取，仍由现有端口提供精修与校验。 */
final class JunctionBatchCoordinator {
    private JunctionBatchCoordinator() {}

    static MergeBatchResult connect(BatchAccess access, List<RoadData> roads) {
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
            // 选主依赖原列表顺序，必须逐路更新；本阶段只处理已生成数据，不触发世界采样。
            // 主路列表随批次增长，避免每条路重新拼接存档道路和此前结果。
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

            // 选主快照固定后，精修任务才相互独立；每条路的采样生命周期由端口独立管理。
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
                    // 有些结构拒绝只有原因、没有碰撞坐标；记录两者才能分辨是哪一道守卫拒绝。
                    LOGGER.warn("RoadWeaver discarded unsafe in-memory junction owners={} -> {} dataReason={} "
                                    + "structureReason={} collision={} fallback={}",
                            fallback.ownerA2dKey(), fallback.ownerB2dKey(), dataValidation.reason(),
                            structureValidation == null ? null : structureValidation.reason(),
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

}
