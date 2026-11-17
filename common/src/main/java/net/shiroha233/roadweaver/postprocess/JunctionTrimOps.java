/* 文件职责：路口修剪 —— 副线接到主线后，把多余的头尾裁掉、把跨度标记搬对。 */
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
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;

// 仍留在其它同包类里的常量、类型与工具。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;
import static net.shiroha233.roadweaver.postprocess.EndpointConnectOps.*;

/**
 * 路口修剪与连接收尾。
 *
 * <h2>为什么接上之后还要修剪</h2>
 * <p>副线接上主线之后，原本伸出去的那一截头/尾就成了<b>多余的岔路</b>。
 * 不裁掉的话，玩家会在路口看到一小段悬空的断头路。</p>
 *
 * <h2>裁剪必须同时搬跨度标记</h2>
 * <p>{@code RoadSpan} 描述「哪一段是桥 / 隧道 / 普通路」。裁掉站点之后，
 * 跨度区间会跟着错位 —— 所以每次裁剪都要同步重映射 span，
 * 否则会出现「路面是桥、标记说普通路」这种施工错配。</p>
 *
 * <p>本类是从 1832 行的 {@code RoadSnapService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class JunctionTrimOps {
    public static RoadData tryConnect(RoadData primary,
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
            LOGGER.info("【已抑制主次道路并行缠绕】 " + "RoadWeaver braid suppressed primary={} secondary={}",
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
        LOGGER.info("【已选取路口吸附候选】 " + "RoadWeaver selected snap candidate mode={} primary={} secondary={} "
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
    public static final int HEAD_TRIM_TOLERANCE = 2;

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
    public static RoadData trimSecondaryHeadAtJunction(RoadData primary, RoadData candidate) {
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
            LOGGER.warn("【裁切结果无效，保留次路头段】 " + "RoadWeaver kept secondary head: trimmed route failed validation reason={} index={} pos={} detail={} "
                            + "primary={} secondary={} head={}",
                    validation.reason(), validation.index(), validation.position(), validation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), head);
            return null;
        }
        BlockPos startPos = retainedSegments.get(0).middlePos();
        LOGGER.info("【次路头段已裁至路口】 " + "RoadWeaver trimmed secondary head at junction primary={} secondary={} "
                        + "start=({},{}) segments={} -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate),
                startPos.getX(), startPos.getZ(), n, retainedSegments.size());
        return trimmed;
    }

    /** 头部重合判定：{@code (x,z)} 在 primary 任一站点 {@value #HEAD_TRIM_TOLERANCE} 格内。 */
    public static boolean nearPrimaryStation(Set<Long> primaryStations, int x, int z) {
        for (int dx = -HEAD_TRIM_TOLERANCE; dx <= HEAD_TRIM_TOLERANCE; dx++) {
            for (int dz = -HEAD_TRIM_TOLERANCE; dz <= HEAD_TRIM_TOLERANCE; dz++) {
                if (primaryStations.contains(xzKey(new BlockPos(x + dx, 0, z + dz)))) return true;
            }
        }
        return false;
    }

    /** Modes whose junction is an endpoint attachment; reuse-corridor modes keep their tails. */
    public static boolean isEndpointAttachmentMode(String mode) {
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
    public static RoadData trimSecondaryTailAtJunction(RoadData primary, RoadData candidate, String mode) {
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
            LOGGER.warn("【裁切后站数不足，保留次路尾段】 " + "RoadWeaver kept secondary tail: trimming would leave too few segments "
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
                    LOGGER.debug("【路口跨度越过裁切点，保留次路尾段】 " + "RoadWeaver kept secondary tail: junction span {} extends past "
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
            LOGGER.warn("【裁切结果无效，保留次路尾段】 " + "RoadWeaver kept secondary tail: trimmed route failed validation reason={} index={} pos={} detail={} "
                            + "primary={} secondary={} junction={}",
                    trimmedValidation.reason(), trimmedValidation.index(),
                    trimmedValidation.position(), trimmedValidation.detail(),
                    RoadShardStorage.computeFingerprint(primary),
                    RoadShardStorage.computeFingerprint(candidate), junction);
            return null;
        }
        BlockPos junctionPos = retainedSegments.get(retainedSegments.size() - 1).middlePos();
        LOGGER.info("【次路尾段已裁至路口】 " + "RoadWeaver trimmed secondary tail at junction primary={} secondary={} "
                        + "junction=({},{}) segments={} -> {}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(candidate),
                junctionPos.getX(), junctionPos.getZ(), n, retainedSegments.size());
        return trimmed;
    }

    public static void addConnectionOption(List<ConnectionOption> options,
                                            String mode,
                                            RoadData candidate,
                                            RoadData secondary) {
        if (candidate == null || candidate.equals(secondary)) return;
        RoadData normalized = deduplicateExactSpans(candidate);
        RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(normalized);
        if (!validation.valid()) {
            LOGGER.debug("【路口吸附候选数据无效，拒绝该候选】 " + "RoadWeaver rejected snap option mode={} reason={} index={} pos={} detail={}",
                    mode, validation.reason(), validation.index(), validation.position(), validation.detail());
            return;
        }
        double constructionLength = ordinaryConstructionLength(normalized);
        double originalLength = ordinaryConstructionLength(secondary);
        double constructionLimit = originalLength + MAX_SNAP_CONSTRUCTION_OVERHEAD;
        if (constructionLength > constructionLimit) {
            LOGGER.debug("【路口吸附候选施工长度超限，拒绝该候选】 " + "RoadWeaver rejected snap option mode={} constructionLength={} limit={}",
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
    public static void logJunctionConnection(RoadData primary, RoadData secondary,
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
        LOGGER.info("【路口接线位置与道路高度差】 " + "RoadWeaver junction+connect primary={} secondary={} junction=({},{}) "
                        + "idx={}/{} roadY={}->{} delta={} side={}",
                RoadShardStorage.computeFingerprint(primary),
                RoadShardStorage.computeFingerprint(secondary),
                priPos == null ? "?" : priPos.getX() + "," + priPos.getZ(),
                secPos == null ? "?" : secPos.getX() + "," + secPos.getZ(),
                junction.primaryIndex(), junction.secondaryIndex(),
                priY, secY, Math.abs(priY - secY), sharedAtStart ? "start" : "end");
    }

    public static double ordinaryConstructionLength(RoadData road) {
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
    public static boolean[] spanMask(RoadData road, SpanType type) {
        if (road == null || type == null) return new boolean[0];
        return roadIndex(road).spanMask(type);
    }
}
