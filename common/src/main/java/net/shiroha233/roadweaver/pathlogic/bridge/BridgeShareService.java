package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;

/**
 * 跨路桥「共享跨越」的**规划后一次性检查处理**（0.9.029 / R-03）。
 *
 * <p>挂在规划全部跑完之后（{@code connectPlannedBatchDetailed} 里、braid 预检之前），
 * 只做三件事：找**离得近 + 重叠够 + 平行**的两座桥 → 定主人 → 让次路那段复用主人甲板。
 *
 * <p>三条安全约束（用户 2026-11-08 反复确认的）：
 * <ol>
 *   <li>只花 <b>O(候选对)</b>：先用 {@link BridgeShareDetector#mayOverlap} 的包围盒预筛，
 *       只有地理邻近的桥才进入精确判定 —— **不扫全部道路**</li>
 *   <li><b>改不动就放弃</b>：{@link BridgeShareAbsorber#blendToDeckY} 返回 null 时原样保留两座桥</li>
 *   <li><b>校验不过就回滚</b>：改完必须过 {@code RoadDataValidator.validateFinalRoad}，
 *       不过就把这条路整体还原 ⇒ 不会让任何一条路变成失败状态</li>
 * </ol>
 */
public final class BridgeShareService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("roadweaver");
    private static final java.util.concurrent.atomic.AtomicLong RECOVERED_ENDPOINTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISSING_ENDPOINT_LOGS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 0.9.036：端点精确匹配失败时，允许按「同 XZ 且 ΔY ≤ 该值」兜底认回站索引。
     *
     * <p><b>为什么需要</b>：0.9.034 真机（存档 `202611090942`）初始那批只有 4 条路，
     * 其中 `(32,-64)->(-624,-944)` 的桥 `(205,65,-165)→(270,65,-175)` 与
     * `(32,-64)->(992,544)` 的桥 `(207,48,-164)→(269,52,-175)` 在 **merge（09:53:04）之前**
     * 就都已生成（09:52:42 / 09:51:52）⇒ 「这批路里没有两座桥」被排除；两座桥 XZ 差约 2 格、
     * 航向一致、重叠 60+ 站 ⇒ 几何判定也该过。剩下的唯一可能：
     * {@code RoadSpan} **只存 BlockPos、没有站索引**，{@code collectBridges} 必须用
     * {@code BlockPos.equals} 去 {@code roadSegmentList().get(i).middlePos()} 里反查，
     * 而 {@code mergeRoads} 会重建站序列（donor 重建），Y 只要漂一格整座桥就被静默丢掉。</p>
     *
     * <p><b>安全性</b>：兜底比精确匹配**严格更严**——要求同一 XZ 上**恰好只有一个站**
     * （多于一个视为歧义，直接放弃），且 ΔY ≤ 4。精确匹配命中的情形完全不受影响；
     * 只有原本被静默丢弃的 span 才可能被认回。阈值 4：合并重排纵断面一般只动 1~3 格。</p>
     */
    private static final int STATION_XZ_FALLBACK_MAX_DY = 4;

    /**
     * 一轮检查的计数，写进 {@code network merge timing} 便于真机核对。
     *
     * @param roads                  参与本次合并的路数
     * @param bridges                收集到的 BRIDGE span 数（&lt; 2 会直接返回）
     * @param spanEndpointsMissing   span 端点在该路站序列里**怎么都找不到**的次数（被丢弃）
     * @param spanEndpointsRecovered 精确匹配失败、但被「同 XZ + ΔY ≤ 4」兜底认回的次数（0.9.036）
     */
    public record Stats(int roads, int bridges, int spanEndpointsMissing, int spanEndpointsRecovered,
                        int candidates,
                        int absorbed, int skippedNoTransition, int skippedInvalid) {}

    private BridgeShareService() {}

    /**
     * 就地改写 {@code roads}：被吸收的那条路，其桥段换成 {@code NETWORK_REUSE} 并把剖面吸附到主人 deckY。
     *
     * @return 计数；{@code absorbed == 0} 时 {@code roads} 一个元素都没被替换
     */
    public static Stats applyInPlace(List<RoadData> roads, int radius) {
        if (roads == null) return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
        if (roads.size() < 2) return new Stats(roads.size(), 0, 0, 0, 0, 0, 0, 0);
        int[] endpointCounters = new int[2]; // [0]=missing, [1]=recovered
        List<BridgeRef> bridges = collectBridges(roads, endpointCounters);
        if (bridges.size() < 2) {
            return new Stats(roads.size(), bridges.size(), endpointCounters[0], endpointCounters[1],
                    0, 0, 0, 0);
        }

        int candidates = 0;
        int absorbed = 0;
        int skippedNoTransition = 0;
        int skippedInvalid = 0;
// 0.9.055：先把自己收进了哪几座桥打出来（真机 candidates=0 时第一件要确认的事）。
        for (BridgeRef ref : bridges) {
            LOGGER.info("[BRIDGE-SHARE] collected road{}#{} st{}..{} n={} head={} tail={}",
                    ref.roadIndex(), ref.spanIndex(), ref.fromStation(), ref.toStation(),
                    ref.stations().size(),
                    ref.stations().isEmpty() ? null : ref.stations().get(0),
                    ref.stations().isEmpty() ? null : ref.stations().get(ref.stations().size() - 1));
        }
        Set<Integer> alreadyAbsorbed = new HashSet<>();

        for (int i = 0; i < bridges.size(); i++) {
            for (int j = i + 1; j < bridges.size(); j++) {
                BridgeRef a = bridges.get(i);
                BridgeRef b = bridges.get(j);
                if (a.roadIndex() == b.roadIndex()) continue;
                // ① 廉价预筛：只对可能挨着的桥做精确判定
                // 0.9.055：把每一对的三道门都打出来 —— 真机 (236,-170) 的两座桥
                // （st299..368 deckY=48 / st296..371 deckY=65）明明 XZ 差 2 格、
                // 航向一致、重叠 60+ 站，却 candidates=0，光看计数分不出是哪道门卡的。
                boolean overlapBox = BridgeShareDetector.mayOverlap(a.stations(), b.stations(), radius);
                BridgeShareDetector.Report pairReport = overlapBox
                        ? BridgeShareDetector.detect(a.stations(), b.stations(), radius,
                                BridgeShareDetector.DEFAULT_MIN_OVERLAP_STATIONS)
                        : BridgeShareDetector.Report.none();
                LOGGER.info("[BRIDGE-SHARE-PAIR] a=road{}#{} st{}..{} n={} b=road{}#{} st{}..{} n={} "
                                + "bbox={} minDist={} overlap={} dot={} shares={}",
                        a.roadIndex(), a.spanIndex(), a.fromStation(), a.toStation(), a.stations().size(),
                        b.roadIndex(), b.spanIndex(), b.fromStation(), b.toStation(), b.stations().size(),
                        overlapBox, pairReport.minDistance(), pairReport.overlapStations(),
                        String.format("%.3f", BridgeShareDetector.parallelDot(a.stations(), b.stations())),
                        pairReport.shares());
                if (!overlapBox) continue;
                if (!pairReport.shares()) {
                    continue;
                }
                candidates++;
                // ② 定主人：站数多的当主人；一样多取路序号小的
                BridgeRef owner = a.stations().size() >= b.stations().size() ? a : b;
                BridgeRef absorbedRef = owner == a ? b : a;
                if (alreadyAbsorbed.contains(absorbedRef.roadIndex())) continue;

                RoadData victim = roads.get(absorbedRef.roadIndex());
                int deckY = owner.stations().get(0).getY();
                int[] blended = BridgeShareAbsorber.blendToDeckY(
                        victim.targetY(), absorbedRef.fromStation(), absorbedRef.toStation(), deckY);
                if (blended == null) {
                    skippedNoTransition++;
                    continue;
                }
                List<Integer> newTargetY = new ArrayList<>(blended.length);
                for (int value : blended) newTargetY.add(value);
                List<RoadSpan> newSpans = BridgeShareAbsorber.retypeToReuse(
                        victim.spans(), absorbedRef.spanIndex(),
                        victim.spans().get(absorbedRef.spanIndex()).start(),
                        victim.spans().get(absorbedRef.spanIndex()).end());
                RoadData candidate = new RoadData(victim.width(), victim.roadType(),
                        victim.materials(), victim.slabMaterials(), victim.roadSegmentList(),
                        newSpans, newTargetY, victim.ownerA2dKey(), victim.ownerB2dKey());
                // ③ 校验不过就什么都不改
                RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(candidate);
                if (validation == null || !validation.valid()) {
                    skippedInvalid++;
                    continue;
                }
                roads.set(absorbedRef.roadIndex(), candidate);
                alreadyAbsorbed.add(absorbedRef.roadIndex());
                absorbed++;
            }
        }
        return new Stats(roads.size(), bridges.size(), endpointCounters[0], endpointCounters[1],
                candidates, absorbed, skippedNoTransition, skippedInvalid);
    }

    /** 一条路上的一个 BRIDGE span，连同它的站区间（-1 表示端点没在站序列里找到，直接排除）。 */
    private record BridgeRef(int roadIndex, int spanIndex, int fromStation, int toStation,
                             List<BlockPos> stations) {}

    private static List<BridgeRef> collectBridges(List<RoadData> roads, int[] endpointCountersOut) {
        List<BridgeRef> out = new ArrayList<>();
        for (int r = 0; r < roads.size(); r++) {
            RoadData road = roads.get(r);
            if (road == null || road.spans() == null || road.roadSegmentList() == null) continue;
            List<RoadSegmentPlacement> stations = road.roadSegmentList();
            for (int s = 0; s < road.spans().size(); s++) {
                RoadSpan span = road.spans().get(s);
                if (span == null || span.type() != SpanType.BRIDGE) continue;
                int from = stationIndexOf(stations, span.start());
                int to = stationIndexOf(stations, span.end());
                // 0.9.036：精确匹配失败时走「同 XZ + ΔY ≤ 4 且同 XZ 只有唯一站」的收紧兜底。
                boolean recovered = false;
                if (from < 0) {
                    from = stationIndexNearXz(stations, span.start());
                    recovered |= from >= 0;
                }
                if (to < 0) {
                    to = stationIndexNearXz(stations, span.end());
                    recovered |= to >= 0;
                }
                if (recovered && from >= 0 && to >= 0) {
                    RECOVERED_ENDPOINTS.incrementAndGet();
                    if (endpointCountersOut != null && endpointCountersOut.length > 1) {
                        endpointCountersOut[1]++;
                    }
                }
                if (from < 0 || to < 0) {
                    if (endpointCountersOut != null && endpointCountersOut.length > 0) {
                        endpointCountersOut[0]++;
                    }
                    logMissingEndpoint(road, s, span, stations);
                    continue;
                }
                int lo = Math.min(from, to);
                int hi = Math.max(from, to);
                if (hi - lo < 2) continue;
                List<BlockPos> slice = new ArrayList<>(hi - lo + 1);
                for (int i = lo; i <= hi; i++) {
                    BlockPos pos = stations.get(i).middlePos();
                    if (pos != null) slice.add(pos);
                }
                if (slice.size() < 3) continue;
                out.add(new BridgeRef(r, s, lo, hi, slice));
            }
        }
        return out;
    }

    /**
     * 0.9.036：诊断——端点连兜底都认不回来时，打前 3 例的实参，供离线判定。
     * 只打 3 例，其余交给计数。
     */
    private static void logMissingEndpoint(RoadData road, int spanIndex, RoadSpan span,
                                           List<RoadSegmentPlacement> stations) {
        long logged = MISSING_ENDPOINT_LOGS.incrementAndGet();
        if (logged > 3) return;
        int nearest = Integer.MAX_VALUE;
        int nearestDy = Integer.MIN_VALUE;
        for (RoadSegmentPlacement placement : stations) {
            if (placement == null || placement.middlePos() == null) continue;
            BlockPos pos = placement.middlePos();
            int dx = Math.abs(pos.getX() - span.start().getX());
            int dz = Math.abs(pos.getZ() - span.start().getZ());
            int cheb = Math.max(dx, dz);
            if (cheb < nearest) {
                nearest = cheb;
                nearestDy = pos.getY() - span.start().getY();
            }
        }
        LOGGER.warn("RoadWeaver bridge-share could not map span endpoint to a station "
                        + "road={} span={} type={} start={} end={} stations={} "
                        + "nearestChebyshevXz={} nearestDy={} (checked={})",
                road.hasOwnerPair() ? road.ownerA2dKey() + "->" + road.ownerB2dKey() : "unknown",
                spanIndex, span.type(), span.start(), span.end(), stations.size(),
                nearest == Integer.MAX_VALUE ? -1 : nearest, nearestDy, logged);
    }

    private static int stationIndexOf(List<RoadSegmentPlacement> stations, BlockPos target) {
        if (stations == null || target == null) return -1;
        for (int i = 0; i < stations.size(); i++) {
            RoadSegmentPlacement placement = stations.get(i);
            if (placement != null && target.equals(placement.middlePos())) return i;
        }
        return -1;
    }

    /**
     * 0.9.036 收紧兜底：**同一 XZ 上恰好只有一个站**，且 {@code |ΔY| ≤ STATION_XZ_FALLBACK_MAX_DY}
     * 才认；同 XZ 出现多个站视为歧义，直接返回 -1（宁可丢，不可错配）。
     */
    static int stationIndexNearXz(List<RoadSegmentPlacement> stations, BlockPos target) {
        if (stations == null || target == null) return -1;
        int hit = -1;
        int hitDy = Integer.MAX_VALUE;
        for (int i = 0; i < stations.size(); i++) {
            RoadSegmentPlacement placement = stations.get(i);
            if (placement == null) continue;
            BlockPos pos = placement.middlePos();
            if (pos == null) continue;
            if (pos.getX() != target.getX() || pos.getZ() != target.getZ()) continue;
            if (hit >= 0) return -1; // 同 XZ 多个站 ⇒ 歧义
            hit = i;
            hitDy = Math.abs(pos.getY() - target.getY());
        }
        if (hit < 0 || hitDy > STATION_XZ_FALLBACK_MAX_DY) return -1;
        return hit;
    }
}
