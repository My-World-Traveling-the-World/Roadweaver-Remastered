/* 文件职责：并行走廊重叠的消解与改道 —— 只判「并排了没」并给出改道方案。 */
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

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService.*;

/**
 * 并行走廊重叠消解与改道。
 *
 * <h2>它在解决什么</h2>
 * <p>两条路各走各的都合法，但**并排走 20 格以上**就是观感灾难 —— 玩家会看到
 * 两条几乎平行的路。所以要把其中一条改道让开。</p>
 *
 * <h2>★ 判定分三步，缺一不可</h2>
 * <ol>
 *   <li>{@code boxMayOverlap} —— 包围盒预筛（便宜，先排掉大部分）；</li>
 *   <li>{@code resolveParallelOverlaps} —— 真判「并排长度是否超阈值」；</li>
 *   <li>{@code rerouteAvoiding} —— 给被选中的那条算出改道方案。</li>
 * </ol>
 * <p>顺序不能颠倒：先改道再判定等于每题都跑最贵的 A*。</p>
 *
 * <h2>★ 0.9.018 已修的四个问题（别再引入）</h2>
 * <ol>
 *   <li>改道 attempt2 曾拿**自己旧引用**当 owner ⇒ 必然误判并排 + braidPairs 虚高；</li>
 *   <li>提交阶段「不合格退回原路」曾退回**改道后**的路（兜底对象错）；</li>
 *   <li>禁令曾靠 CorridorBan ThreadLocal 传递，链路脆弱；</li>
 *   <li>corridorBan 在 GradientDescentPathfinder 里曾有 0 处引用 ⇒ 算法落到 GD 时
 *       改道**静默失效**（表现为 braidRejected 偏高）。</li>
 * </ol>
 * <p>本类是从 1148 行的 {@code ExistingRoadJunctionService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class ParallelOverlapResolver {
    static BraidPrecheck resolveParallelOverlaps(ServerLevel level,
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
    static boolean boxMayOverlap(RoadData primary, RoadData secondary, int maxDistance) {
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
    static RoadData rerouteAvoiding(ServerLevel level,
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
    static StructureConnection connectionOf(RoadData road) {
        List<RoadSegmentPlacement> stations = road == null ? null : road.roadSegmentList();
        if (stations == null || stations.size() < 2) return null;
        RoadSegmentPlacement first = stations.get(0);
        RoadSegmentPlacement last = stations.get(stations.size() - 1);
        BlockPos from = first == null ? null : first.middlePos();
        BlockPos to = last == null ? null : last.middlePos();
        if (from == null || to == null || from.equals(to)) return null;
        return new StructureConnection(from, to, ConnectionStatus.PLANNED);
    }

    static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    static MergeResult refineMergedCandidate(ServerLevel level, MergeWork item) {
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
}
