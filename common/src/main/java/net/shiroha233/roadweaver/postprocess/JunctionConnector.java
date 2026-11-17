/* 文件职责：把副线接到主线上（含复用走廊接入）—— 只做连接，不做纵断面平滑。 */
package net.shiroha233.roadweaver.postprocess;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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

// 仍留在其它同包类里的常量、类型与工具。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionTrimOps.*;

/**
 * 副线接入主线。
 *
 * <h2>它负责的边界</h2>
 * <p>「端点在哪接入」由 {@code EndpointApproachConnector} 找；
 * 「两条路能不能共用走廊」由 {@code NetworkReuseMerger} 判。
 * 本类只做中间那一步：<b>把副线的站序列真的接到主线上</b>。</p>
 *
 * <h2>四种接法</h2>
 * <ul>
 *   <li>直接连（{@code connectSecondaryToPrimaryOnce}）—— 一次性接上，最常见；</li>
 *   <li>路口延展（{@code extendSecondaryReuseAtJunction}）—— 副线需向前延伸才够得着；</li>
 *   <li>走廊复用（{@code createSecondaryReuseAtCorridor}）—— 直接借用主线已有路面；</li>
 *   <li>非实体跨度继承（{@code carryCompatibleNonPhysicalSpans}）—— 隧道/桥这类
 *       非实体跨度要跟着站序列一起搬，否则接上之后跨度标记会丢。</li>
 * </ul>
 *
 * <h2>★ 为什么每种都要有「失败回退」</h2>
 * <p>接不上时<b>不能留下半截修改</b>：副线已经动过站点，回头又没连上，
 * 玩家看到的就是一条断头路。所以每个分支都先在副本上试、失败就不提交。
 * 这条纪律比判据本身更重要 —— 判据错了顶多不合并，回退漏了就是坏路。</p>
 *
 * <p>本类是从 2317 行的 {@code RoadSnapService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class JunctionConnector {
    public static RoadData connectSecondaryToPrimary(RoadData primary, RoadData secondary) {
        if (!isUsable(primary) || !isUsable(secondary)) return null;
        RoadData best = connectSecondaryToPrimaryOnce(primary, secondary);
        if (best == null) return null;

        int reused = networkReuseStationCount(best);
        double constructionLength = ordinaryConstructionLength(best);
        for (int window = 1; window < MAX_REUSE_WINDOWS_PER_PAIR; window++) {
            RoadData next = connectSecondaryToPrimaryOnce(primary, best);
            if (next == null) break;
            int nextReused = networkReuseStationCount(next);
            // Do not let a second endpoint-only snap rewrite an already
            // merged corridor. Each continuation must add real shared road.
            if (nextReused <= reused) break;
            // T-17:每个续窗都是一次完整重算(重建 grid + 全部候选择优)。
            // 只有"复用站点增加且总施工长度确实缩短"才值得继续;construction
            // length 停止改善时立即终止该路对的走廊扩展,不再为几乎不变的
            // 结果重复整个候选选择。
            double nextConstructionLength = ordinaryConstructionLength(next);
            if (nextConstructionLength > constructionLength - CONSTRUCTION_LENGTH_MIN_IMPROVEMENT) break;
            LOGGER.info("【共用走廊已延长，减少次路重复施工】 " + "RoadWeaver extended shared corridor primary={} secondary={} window={} "
                            + "reuseStations={} -> {} constructionLength={} -> {}",
                    RoadShardStorage.computeFingerprint(primary), RoadShardStorage.computeFingerprint(secondary),
                    window + 1, reused, nextReused, constructionLength, nextConstructionLength);
            best = next;
            reused = nextReused;
            constructionLength = nextConstructionLength;
        }
        return extendSecondaryReuseAtJunction(primary, best);
    }

    /** 0.8.324：交汇锚点之后仍贴在 primary 走廊上的站点，允许的最大切比雪夫距离。 */
    public static final int REUSE_EXTEND_TOLERANCE = 2;
    /** 0.8.324：至少这么多连续站才算"一整段重复走廊"，避免误吞单站擦边。 */
    public static final int REUSE_EXTEND_MIN_RUN = 3;
    /** 0.8.324：只复用同一路面高度（targetY 差）的走廊，立交/隧道（高差大）自动排除。 */
    public static final int REUSE_EXTEND_MAX_HEIGHT_DELTA = 1;

    /**
     * 0.8.324：交汇锚点<b>之后</b> secondary 仍有一整段贴在 primary 走廊上的复用扩展。
     *
     * <p>真机 20260909 16.15 存档村口那对路：A=(992,-1024)↔(1425,-1179) 的第 44~66 站
     * 与 B=(1280,-352)↔(992,-1024) 的第 1392~1413 站水平只差 1~2 格、targetY 差 ≤1、
     * 方向相反，但 A 的 NETWORK_REUSE 只标到交汇锚点 (1077,-981) 就停了，于是 A 在锚点
     * 之后又铺了一遍 B 的路面 —— 地图上就是那处"来回交叉的麻花"。
     *
     * <p>做法：从已有的 NETWORK_REUSE 段末尾往后扫，把连续落在 primary 走廊内
     * （切比雪夫 ≤ {@value #REUSE_EXTEND_TOLERANCE}）且 targetY 差 ≤
     * {@value #REUSE_EXTEND_MAX_HEIGHT_DELTA}、且不是其它 span 端点的站点追加标成
     * NETWORK_REUSE。站点一个都不删（连续性不变），只是不再铺路面；扩展后必须能通过
     * {@link RoadDataValidator#validateFinalRoad}，否则原样返回。</p>
     */
    public static RoadData extendSecondaryReuseAtJunction(RoadData primary, RoadData candidate) {
        if (primary == null || candidate == null) return candidate;
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        List<RoadSegmentPlacement> secSegs = candidate.roadSegmentList();
        List<Integer> priY = primary.targetY();
        List<Integer> secY = candidate.targetY();
        if (priSegs == null || secSegs == null || priY == null || secY == null
                || priY.size() != priSegs.size() || secY.size() != secSegs.size()
                || secSegs.size() < 7) {
            return candidate;
        }

        Map<Long, Integer> primaryHeights = new HashMap<>();
        for (int i = 0; i < priSegs.size(); i++) {
            RoadSegmentPlacement segment = priSegs.get(i);
            if (segment == null || segment.middlePos() == null) continue;
            primaryHeights.putIfAbsent(xzKey(segment.middlePos()), priY.get(i));
        }
        if (primaryHeights.isEmpty()) return candidate;

        boolean[] reused = spanMaskView(candidate, SpanType.NETWORK_REUSE);
        int lastReuse = -1;
        for (int i = 0; i < reused.length; i++) {
            if (reused[i]) lastReuse = i;
        }
        if (lastReuse < 0 || lastReuse >= secSegs.size() - 3) return candidate;

        Set<Long> spanEndpoints = new HashSet<>();
        for (RoadSpan span : candidate.spans()) {
            if (span == null) continue;
            if (span.start() != null) spanEndpoints.add(xzKey(span.start()));
            if (span.end() != null) spanEndpoints.add(xzKey(span.end()));
        }

        // 0.8.366 (M2): collect every qualifying corridor run instead of only
        // the first one. The previous forward scan `break`ed on the first
        // station that left the primary corridor, so a route that re-enters it
        // later (the braided "weave in and out" geometry seen on the map) kept
        // its later duplicates. Stations are never removed — each run only adds
        // one NETWORK_REUSE span, so continuity is unchanged; the whole result
        // still has to pass validateFinalRoad below or the candidate is
        // returned untouched.
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        int runEnd = -1;
        for (int i = lastReuse + 1; i < secSegs.size(); i++) {
            RoadSegmentPlacement segment = secSegs.get(i);
            if (segment == null || segment.middlePos() == null) {
                if (runStart >= 0 && runEnd - runStart + 1 >= REUSE_EXTEND_MIN_RUN) {
                    runs.add(new int[]{runStart, runEnd});
                }
                runStart = -1;
                continue;
            }
            BlockPos pos = segment.middlePos();
            boolean usable = !spanEndpoints.contains(xzKey(pos))
                    && matchesPrimaryCorridor(primaryHeights, pos, secY.get(i));
            if (!usable) {
                if (runStart >= 0 && runEnd - runStart + 1 >= REUSE_EXTEND_MIN_RUN) {
                    runs.add(new int[]{runStart, runEnd});
                }
                runStart = -1;
                continue;
            }
            if (runStart < 0) runStart = i;
            runEnd = i;
        }
        if (runStart >= 0 && runEnd - runStart + 1 >= REUSE_EXTEND_MIN_RUN) {
            runs.add(new int[]{runStart, runEnd});
        }
        if (runs.isEmpty()) return candidate;

        List<RoadSpan> spans = new ArrayList<>(candidate.spans());
        for (int[] range : runs) {
            spans.add(new RoadSpan(secSegs.get(range[0]).middlePos(),
                    secSegs.get(range[1]).middlePos(), SpanType.NETWORK_REUSE));
        }
        RoadData extended = new RoadData(candidate.width(), candidate.roadType(),
                candidate.materials(), candidate.slabMaterials(),
                candidate.roadSegmentList(), List.copyOf(spans), candidate.targetY(),
                candidate.ownerA2dKey(), candidate.ownerB2dKey());
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(extended);
        if (!validation.valid()) {
            LOGGER.warn("【延长复用段未通过校验，保留次路原复用范围】 " + "RoadWeaver kept secondary reuse as-is: extended reuse failed validation reason={} "
                            + "index={} pos={} detail={} primary={} secondary={} runs={} first={}..{}",
                    validation.reason(), validation.index(), validation.position(), validation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), runs.size(),
                    runs.get(0)[0], runs.get(runs.size() - 1)[1]);
            return candidate;
        }
        int firstRun = runs.get(0)[0];
        int lastRun = runs.get(runs.size() - 1)[1];
        BlockPos runFrom = secSegs.get(firstRun).middlePos();
        BlockPos runTo = secSegs.get(lastRun).middlePos();
        LOGGER.info("【次路复用跨度已延长越过路口】 " + "RoadWeaver extended secondary reuse past junction primary={} secondary={} "
                        + "run={}..{} runs={} from=({},{}) to=({},{}) reuseStations={} -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate), firstRun, lastRun, runs.size(),
                runFrom.getX(), runFrom.getZ(), runTo.getX(), runTo.getZ(),
                networkReuseStationCount(candidate), networkReuseStationCount(extended));
        return extended;
    }

    /** 该站是否落在 primary 走廊内（切比雪夫 ≤ 容差）且路面高度一致。 */
    public static boolean matchesPrimaryCorridor(Map<Long, Integer> primaryHeights,
                                                  BlockPos pos, int targetY) {
        for (int dx = -REUSE_EXTEND_TOLERANCE; dx <= REUSE_EXTEND_TOLERANCE; dx++) {
            for (int dz = -REUSE_EXTEND_TOLERANCE; dz <= REUSE_EXTEND_TOLERANCE; dz++) {
                Integer donorY = primaryHeights.get(
                        xzKey(new BlockPos(pos.getX() + dx, 0, pos.getZ() + dz)));
                if (donorY != null && Math.abs(donorY - targetY) <= REUSE_EXTEND_MAX_HEIGHT_DELTA) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 0.8.328：secondary 完全没有 NETWORK_REUSE 段时，从"最长的连续贴走廊段"新建一条。
     *
     * <p>真机 20260909 21.06 存档：两条通往同一村庄的路（owners 0→416611827993 与
     * -2061584301600→416611827993）共用 94 站走廊、最近 0 格。合并逻辑算出了正确结果
     * （1138 站、228 复用站），但紧接着的"合并后接口修复"失败（repairMs=8705、返回 null），
     * 于是 `refineMergedCandidate` 整体退回合并前的 1184 站版本 —— 复用标记一起没了，
     * 两条路各铺一遍，地图上就是两条并排的路。</p>
     *
     * <p>这里在"退回合并前版本"之后补一层：把 secondary 连续贴 primary 走廊（切比雪夫
     * ≤ {@value #REUSE_EXTEND_TOLERANCE}、targetY 差 ≤ {@value #REUSE_EXTEND_MAX_HEIGHT_DELTA}、
     * 不是其它 span 端点）最长的一段标成 NETWORK_REUSE，让施工只铺一次。站点一个都不删，
     * 标完必须通过 {@link RoadDataValidator#validateFinalRoad}，否则原样返回。</p>
     */
    public static RoadData createSecondaryReuseAtCorridor(RoadData primary, RoadData candidate) {
        if (primary == null || candidate == null) return candidate;
        List<RoadSegmentPlacement> priSegs = primary.roadSegmentList();
        List<RoadSegmentPlacement> secSegs = candidate.roadSegmentList();
        List<Integer> priY = primary.targetY();
        List<Integer> secY = candidate.targetY();
        if (priSegs == null || secSegs == null || priY == null || secY == null
                || priY.size() != priSegs.size() || secY.size() != secSegs.size()
                || secSegs.size() < 7) {
            return candidate;
        }
        if (spanMaskView(candidate, SpanType.NETWORK_REUSE).length > 0
                && networkReuseStationCount(candidate) > 0) {
            return extendSecondaryReuseAtJunction(primary, candidate);
        }

        Map<Long, Integer> primaryHeights = new HashMap<>();
        for (int i = 0; i < priSegs.size(); i++) {
            RoadSegmentPlacement segment = priSegs.get(i);
            if (segment == null || segment.middlePos() == null) continue;
            primaryHeights.putIfAbsent(xzKey(segment.middlePos()), priY.get(i));
        }
        if (primaryHeights.isEmpty()) return candidate;

        Set<Long> spanEndpoints = new HashSet<>();
        for (RoadSpan span : candidate.spans()) {
            if (span == null) continue;
            if (span.start() != null) spanEndpoints.add(xzKey(span.start()));
            if (span.end() != null) spanEndpoints.add(xzKey(span.end()));
        }

        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;
        for (int i = 0; i <= secSegs.size(); i++) {
            boolean inRun = false;
            if (i < secSegs.size()) {
                RoadSegmentPlacement segment = secSegs.get(i);
                inRun = segment != null && segment.middlePos() != null
                        && !spanEndpoints.contains(xzKey(segment.middlePos()))
                        && matchesPrimaryCorridor(primaryHeights, segment.middlePos(), secY.get(i));
            }
            if (inRun) {
                if (runStart < 0) runStart = i;
            } else if (runStart >= 0) {
                if (i - runStart >= REUSE_EXTEND_MIN_RUN && i - runStart > bestEnd - bestStart) {
                    bestStart = runStart;
                    bestEnd = i - 1;
                }
                runStart = -1;
            }
        }
        if (bestStart < 0) return candidate;

        List<RoadSpan> spans = new ArrayList<>(candidate.spans());
        spans.add(new RoadSpan(secSegs.get(bestStart).middlePos(),
                secSegs.get(bestEnd).middlePos(), SpanType.NETWORK_REUSE));
        RoadData created = new RoadData(candidate.width(), candidate.roadType(),
                candidate.materials(), candidate.slabMaterials(),
                candidate.roadSegmentList(), List.copyOf(spans), candidate.targetY(),
                candidate.ownerA2dKey(), candidate.ownerB2dKey());
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(created);
        if (!validation.valid()) {
            LOGGER.warn("【新复用跨度未通过校验，保留次路原走廊】 " + "RoadWeaver kept secondary corridor as-is: new reuse span failed validation reason={} "
                            + "index={} pos={} detail={} primary={} secondary={} run={}..{}",
                    validation.reason(), validation.index(), validation.position(), validation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), bestStart, bestEnd);
            return candidate;
        }
        BlockPos from = secSegs.get(bestStart).middlePos();
        BlockPos to = secSegs.get(bestEnd).middlePos();
        LOGGER.info("【已为共用走廊建立次路复用跨度】 " + "RoadWeaver created secondary reuse for shared corridor primary={} secondary={} "
                        + "run={}..{} from=({},{}) to=({},{}) reuseStations=0 -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate), bestStart, bestEnd,
                from.getX(), from.getZ(), to.getX(), to.getZ(),
                networkReuseStationCount(created));
        return created;
    }

    /** 单次只提交一个安全共用替换，外层可继续吸收后续独立窗口而不一次越过保护缺口。
 * Performs one safe reuse replacement. The public wrapper may invoke it
     * again to absorb a later corridor window after a protected building gap
     * or a locally smoothed height transition. */
    public static RoadData connectSecondaryToPrimaryOnce(RoadData primary, RoadData secondary) {
        boolean[] primarySpecial = junctionBlockedSpanMask(primary);
        // Special spans may be part of the shared approach from the common
        // owner. They are valid continuity evidence even though the actual
        // junction must still be placed on an ordinary road segment.
        SegmentGrid primaryGrid = buildGrid(primary, null);
        RoadData connected = tryConnect(primary, primaryGrid, primarySpecial, secondary);
        if (connected == null) return null;
        connected = carryCompatibleNonPhysicalSpans(secondary, connected);
        connected = enforceNetworkReuseConstructionOwnership(deduplicateExactSpans(connected), primary);
        connected = RoadDataValidator.repairFinalHeightSteps(connected);
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(connected);
        if (!validation.valid()) {
            LOGGER.warn("【路口候选道路数据无效，拒绝该候选】 " + "RoadWeaver rejected invalid junction candidate owners={} -> {} reason={} index={} pos={} detail={}",
                    secondary.ownerA2dKey(), secondary.ownerB2dKey(), validation.reason(),
                    validation.index(), validation.position(), validation.detail());
            return null;
        }
        return connected;
    }

    /** 替换次路走廊时保留建筑缺口语义；桥与跨越几何必须由终态重新提取。
 * Keeps building-gap metadata when a detoured secondary route is replaced
     * by the donor corridor. Physical bridge and crossing spans are excluded;
     * they must be regenerated only from the final geometry. */
    public static RoadData carryCompatibleNonPhysicalSpans(RoadData source, RoadData target) {
        if (source == null || target == null || source.spans() == null || source.spans().isEmpty()) return target;
        List<RoadSegmentPlacement> retained = target.roadSegmentList();
        List<RoadSpan> spans = new ArrayList<>(target.spans() == null ? List.of() : target.spans());
        for (RoadSpan span : source.spans()) {
            if (span == null || span.start() == null || span.end() == null
                    || span.type() == SpanType.BRIDGE || span.type() == SpanType.ROAD_CROSSING
                    || span.type() == SpanType.TUNNEL) continue;
            BlockPos start = nearestWithin(span.start(), retained, SNAP_THRESHOLD);
            BlockPos end = nearestWithin(span.end(), retained, SNAP_THRESHOLD);
            if (start == null || end == null || start.equals(end)) continue;
            if (spans.stream().noneMatch(existing -> existing != null && existing.type() == span.type()
                    && xzKey(existing.start()) == start.asLong() && xzKey(existing.end()) == end.asLong())) {
                spans.add(new RoadSpan(start, end, span.type()));
            }
        }
        if (spans.size() == target.spans().size()) return target;
        return new RoadData(target.width(), target.roadType(), target.materials(), target.slabMaterials(),
                target.roadSegmentList(), spans, target.targetY(), target.ownerA2dKey(), target.ownerB2dKey());
    }

    public static BlockPos nearestWithin(BlockPos target, List<RoadSegmentPlacement> retained, int maxDistance) {
        BlockPos best = null;
        long bestDist = (long) maxDistance * maxDistance;
        for (RoadSegmentPlacement segment : retained) {
            if (segment == null || segment.middlePos() == null) continue;
            long distance = dist2XZ(target, segment.middlePos());
            if (distance <= bestDist) {
                bestDist = distance;
                best = segment.middlePos();
            }
        }
        return best;
    }

    /** 复制锚点后若隧道降高须同步写回真正所有者，避免地图连上而实体主隧道仍在旧高度。
 *
     * The direct-junction result owns the copied anchor stations, while the
     * earlier road still owns the physical tunnel.  When the copied anchor
     * was lowered to the branch's grade, write the same lower profile back to
     * the tunnel owner.  Otherwise NETWORK_REUSE would only make the map look
     * joined and the persisted primary tunnel would remain at the old height.
     */
    public static RoadData lowerPrimaryTunnelGradeAtJunction(RoadData primary, RoadData connected) {
        if (!isUsable(primary) || !isUsable(connected)
                || primary.targetY() == null || primary.targetY().size() != primary.roadSegmentList().size()
                || connected.targetY() == null || connected.targetY().size() != connected.roadSegmentList().size()) {
            return primary;
        }

        boolean[] reused = spanMask(connected, SpanType.NETWORK_REUSE);
        boolean hasReuse = false;
        for (boolean value : reused) {
            if (value) {
                hasReuse = true;
                break;
            }
        }
        if (!hasReuse) return primary;

        Map<Long, Integer> primaryByXZ = new HashMap<>();
        for (int i = 0; i < primary.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = primary.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) {
                primaryByXZ.putIfAbsent(posKey(segment.middlePos().getX(), segment.middlePos().getZ()), i);
            }
        }

        Set<Long> junctionKeys = junctionEndpointKeys(connected);
        List<Integer> changed = new ArrayList<>();
        boolean[] changedTunnel = new boolean[primary.roadSegmentList().size()];
        List<Integer> result = new ArrayList<>(primary.targetY());
        for (int i = 0; i < connected.roadSegmentList().size(); i++) {
            if (!reused[i]) continue;
            RoadSegmentPlacement segment = connected.roadSegmentList().get(i);
            if (segment == null || segment.middlePos() == null) continue;
            Integer primaryIndex = primaryByXZ.get(posKey(segment.middlePos().getX(), segment.middlePos().getZ()));
            if (primaryIndex == null) continue;
            boolean tunnelOwner = isIndexInSpan(primary, primaryIndex, SpanType.TUNNEL);
            boolean explicitJunction = junctionKeys.contains(posKey(segment.middlePos().getX(), segment.middlePos().getZ()));
            if (!tunnelOwner && !explicitJunction) continue;
            int oldY = result.get(primaryIndex);
            int requestedY = connected.targetY().get(i);
            if (requestedY < oldY) {
                result.set(primaryIndex, requestedY);
                changed.add(primaryIndex);
                changedTunnel[primaryIndex] = tunnelOwner;
            }
        }
        if (changed.isEmpty()) return primary;

        // Extend the lower grade only from the two ends of the reused anchor.
        // The transition stops as soon as the original profile is reachable;
        // it must not flatten an unrelated stretch of the tunnel.
        int changedFrom = changed.stream().min(Integer::compareTo).orElse(-1);
        int changedTo = changed.stream().max(Integer::compareTo).orElse(-1);
        int transitionSegments = requiredTunnelAnchorSegments(
                primary.targetY().get(changedFrom), result.get(changedFrom));
        for (int index : changed) {
            transitionSegments = Math.max(transitionSegments,
                    requiredTunnelAnchorSegments(primary.targetY().get(index), result.get(index)));
        }
        if (changedTunnel[changedFrom]) {
            smoothTunnelBoundary(result, primary, changedFrom, -1, transitionSegments);
        } else {
            smoothJunctionBoundary(result, changedFrom, -1, transitionSegments);
        }
        if (changedTunnel[changedTo]) {
            smoothTunnelBoundary(result, primary, changedTo, 1, transitionSegments);
        } else {
            smoothJunctionBoundary(result, changedTo, 1, transitionSegments);
        }

        // The anchor itself came from the connected route, but repair a
        // one-block join defensively before validating the owner replacement.
        int junctionIndex = changed.get(changed.size() / 2);
        int lower = result.get(junctionIndex);
        for (RoadSpan span : connected.spans()) {
            if (span == null || span.type() != SpanType.TUNNEL_JUNCTION) continue;
            Integer candidate = primaryByXZ.get(posKey(span.start().getX(), span.start().getZ()));
            if (candidate == null) candidate = primaryByXZ.get(posKey(span.end().getX(), span.end().getZ()));
            if (candidate != null && changed.contains(candidate)) {
                junctionIndex = candidate;
                lower = result.get(candidate);
                break;
            }
        }
        result = new ArrayList<>(changedTunnel[junctionIndex]
                ? repairTunnelGradeJoins(primary, result)
                : repairLocalHeightJoinsCopy(result));
        if (result.equals(primary.targetY())) return primary;
        RoadData lowered = new RoadData(primary.width(), primary.roadType(), primary.materials(),
                primary.slabMaterials(), primary.roadSegmentList(), primary.spans(),
                result, primary.ownerA2dKey(), primary.ownerB2dKey());
        if (!RoadDataValidator.validateFinalRoad(lowered).valid()) {
            LOGGER.warn("【局部统一纵断面无效，保留主路原路口高度】 " + "RoadWeaver kept primary junction grade because the local unified profile is invalid "
                            + "owners={} -> {} index={} grade={}",
                    primary.ownerA2dKey(), primary.ownerB2dKey(), junctionIndex, lower);
            return primary;
        }
        BlockPos jPos = null;
        if (junctionIndex >= 0 && junctionIndex < primary.roadSegmentList().size()) {
            RoadSegmentPlacement jSeg = primary.roadSegmentList().get(junctionIndex);
            if (jSeg != null) jPos = jSeg.middlePos();
        }
        LOGGER.info("【主路路口高度已统一】 " + "RoadWeaver unified primary junction height owners={} -> {} pos=({},{}) "
                        + "index={} grade={}",
                primary.ownerA2dKey(), primary.ownerB2dKey(),
                jPos == null ? "?" : jPos.getX(), jPos == null ? "?" : jPos.getZ(),
                junctionIndex, lower);
        return lowered;
    }

    public static Set<Long> junctionEndpointKeys(RoadData road) {
        Set<Long> result = new HashSet<>();
        if (road == null || road.spans() == null) return result;
        for (RoadSpan span : road.spans()) {
            if (span == null || (span.type() != SpanType.JUNCTION_ANCHOR
                    && span.type() != SpanType.TUNNEL_JUNCTION)) continue;
            if (span.start() != null) result.add(posKey(span.start().getX(), span.start().getZ()));
            if (span.end() != null) result.add(posKey(span.end().getX(), span.end().getZ()));
        }
        return result;
    }

    public static void smoothJunctionBoundary(List<Integer> targetY,
                                               int boundary,
                                               int direction,
                                               int transitionSegments) {
        if (targetY == null || boundary < 0 || boundary >= targetY.size()) return;
        int previous = boundary;
        for (int distance = 1, index = boundary + direction;
             distance <= Math.max(JUNCTION_ANCHOR_SEGMENTS, transitionSegments)
                     && index >= 0 && index < targetY.size();
             distance++, index += direction) {
            int current = targetY.get(index);
            int previousValue = targetY.get(previous);
            if (current <= previousValue + 1 && current >= previousValue - 1) {
                previous = index;
                continue;
            }
            targetY.set(index, current > previousValue
                    ? previousValue + 1 : previousValue - 1);
            previous = index;
        }
    }

    public static List<Integer> repairLocalHeightJoinsCopy(List<Integer> values) {
        if (values == null || values.size() < 2) return values == null ? List.of() : List.copyOf(values);
        List<Integer> result = new ArrayList<>(values);
        repairLocalHeightJoins(result);
        return List.copyOf(result);
    }
}
