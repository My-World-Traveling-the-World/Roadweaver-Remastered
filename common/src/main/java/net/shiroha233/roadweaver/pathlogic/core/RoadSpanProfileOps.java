/* 文件职责：路段跨度与纵断面观感 —— 交叉口剖面、隧道口水面闭合、端点坡道约束。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 诊断保留桥面判据字段，中文说明避免将最低桥面误读成实际落块高度。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.shiroha233.roadweaver.pathfinding.SpanConflictResolver;
import net.shiroha233.roadweaver.pathfinding.FillBridgeExtender;
import net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator.PathCalculationResult;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.ConnectionDeletionService;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.CancellationException;
import net.shiroha233.roadweaver.pathlogic.bridge.CulvertPlanner;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.metrics.RoadQualityMetrics;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureRoadOffsetService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadPlanConsistencyService;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.core.Road.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadCrossingOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps.*;

/**
 * 路段跨度与纵断面观感。
 *
 * <h2>它处理什么</h2>
 * <p>这一批是「路的纵断面在特殊位置怎么收口」：过水交叉口的甲板剖面、
 * 隧道口的水面边界、路肩闭合、共用高程端点的坡道约束、以及跨度摘要统计。</p>
 *
 * <h2>★ 共用高程端点为什么单独判</h2>
 * <p>两条路在同一处交汇且高度相同，坡道约束就必须**两头一起看** ——
 * 各自按自己的坡度上限收口，会在交汇点造出一个可察觉的台阶。
 * {@code isSharedElevationEndpoint} 先判「是不是共用高程」，再决定用哪套约束。</p>
 *
 * <h2>★ 未知结构端点要保守</h2>
 * <p>结构类型未知时（模组结构、认不出的 piece），无法判断该不该让坡 ——
 * {@code isUnknownStructureEndpoint} 一律按「可能冲突」处理，宁可坡缓一点。</p>
 *
 * <p>本类是从 1639 行的 {@code Road} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class RoadSpanProfileOps {
    /** 使用当前站序列的精确端点；拒绝靠最近坐标扩大隧道权限。 */
    public static boolean[] excavationMask(List<BlockPos> centers, List<RoadSpan> spans) {
        var positions = new HashMap<BlockPos, Integer>();
        for (int i = 0; i < centers.size(); i++) positions.putIfAbsent(centers.get(i), i);
        var mask = new boolean[centers.size()];
        for (var span : spans) {
            if (span.type() != SpanType.TUNNEL && span.type() != SpanType.TUNNEL_JUNCTION) continue;
            var a = positions.get(span.start());
            var b = positions.get(span.end());
            if (a == null || b == null) throw new IllegalArgumentException("隧道跨度与当前站序列不一致");
            Arrays.fill(mask, Math.min(a, b), Math.max(a, b) + 1, true);
        }
        return mask;
    }
    public static boolean isSharedElevationEndpoint(ServerLevel level,
                                                     StructureConnection connection,
                                                     boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        if (isVillageEndpoint(level, connection, from)) return true;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX()
                && spawn.getZ() == endpoint.getZ()) {
            return true;
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot == null || snapshot.locations() == null) return false;
        for (StructureInfo info : snapshot.locations().structureInfos()) {
            if (info == null || info.pos() == null) continue;
            // Unknown endpoints negotiate their height through
            // UnknownEndpointHeightRegistry instead of the surface lock.
            if (!StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX()
                    && info.pos().getZ() == endpoint.getZ()) {
                return true;
            }
        }
        return false;
    }

    /** 仅未知且非村庄/出生点的端点参与共享高度协商，避免改写既有固定入口。
 *
     * True only for unknown (unnamed) structure nodes: the snapshot lists an
     * endpoint with no known structure id, it is not a village and not the
     * shared spawn. Only these endpoints choose their shared height by
     * construction cost via {@link UnknownEndpointHeightRegistry}; villages
     * and the spawn keep their existing surface-height lock untouched.
     */
    public static boolean isUnknownStructureEndpoint(ServerLevel level,
                                                     StructureConnection connection,
                                                     boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        if (isVillageEndpoint(level, connection, from)) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX()
                && spawn.getZ() == endpoint.getZ()) {
            return false;
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot != null && snapshot.locations() != null) {
            for (StructureInfo info : snapshot.locations().structureInfos()) {
                if (info == null || info.pos() == null) continue;
                if (info.pos().getX() == endpoint.getX()
                        && info.pos().getZ() == endpoint.getZ()) {
                    return !StructureInfo.isKnownId(info.structureId())
                            && !StructureInfo.isVillageId(info.structureId());
                }
            }
        }
        // Only a node that is definitively not a known structure, not a village,
        // and not the spawn may use negotiated unknown height. A named/village
        // structure must keep its real surface height even when its planning
        // snapshot has not been prepared yet.
        return !isKnownStructureEndpoint(level, connection, from);
    }

    public static void constrainEndpointRamp(int[] profile, int endpoint,
                                              int direction, int limit) {
        int previous = endpoint;
        for (int distance = 1, index = endpoint + direction;
             distance <= limit && index >= 0 && index < profile.length;
             distance++, index += direction) {
            int current = profile[index];
            if (current > profile[previous] + 1) current = profile[previous] + 1;
            else if (current < profile[previous] - 1) current = profile[previous] - 1;
            profile[index] = current;
            previous = index;
        }
    }

    public static List<Integer> toIntegerList(int[] values) {
        if (values == null || values.length == 0) return List.of();
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }

    /** 水跨度相邻的隧道侧只降不升接坡，不改跨越甲板本身，避免桥隧交界断阶。
 * Closes the short boundary where a tunnel ends immediately before or after a water crossing.
     * The two profiles are normally independent: prefer-cut skips crossing stations, so the
     * tunnel-side bank can remain several blocks above the ford/bridge deck. This pass lowers the
     * higher tunnel-side stations toward the crossing endpoint one block per station without ever
     * raising a station or touching the crossing deck itself. */
    public static int[] closeTunnelWaterBoundary(int[] profile, List<BlockPos> centers,
                                                  List<RoadSpan> spans) {
        if (profile == null || profile.length < 2 || centers == null || spans == null) return profile;
        Map<Long, Integer> stationIndex = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos pos = centers.get(i);
            if (pos != null) stationIndex.putIfAbsent(pos.asLong(), i);
        }
        boolean[] elevated = new boolean[profile.length];
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.TUNNEL && span.type() != SpanType.TUNNEL_JUNCTION
                    && span.type() != SpanType.BRIDGE) continue;
            Integer start = stationIndex.get(span.start().asLong());
            Integer end = stationIndex.get(span.end().asLong());
            if (start == null || end == null) continue;
            for (int k = Math.min(start, end); k <= Math.max(start, end); k++) elevated[k] = true;
        }
        int[] result = profile.clone();
        int k = 12;
        int maxBuffer = 2;
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING) continue;
            Integer start = stationIndex.get(span.start().asLong());
            Integer end = stationIndex.get(span.end().asLong());
            if (start == null || end == null) continue;
            int lo = Math.min(start, end);
            int hi = Math.max(start, end);
            closeSpanSide(result, elevated, lo, -1, k, maxBuffer);
            closeSpanSide(result, elevated, hi, 1, k, maxBuffer);
        }
        return result;
    }

    /** 从水端点向外压低邻近高跨度；普通缓冲站仅在满足一格坡差时降低。
 * Lowers elevated tunnel/bridge stations away from a water-span endpoint. `boundary` is the
     * crossing endpoint index; the adjacent side is boundary + direction. A short run of ordinary
     * buffer stations is allowed between the water span and the elevated physical span, but the
     * ordinary buffer itself is only lowered if doing so follows the one-block-per-station grade.
     * Stations are never raised and the crossing deck itself is never changed. */
    public static void closeSpanSide(int[] profile, boolean[] elevated, int boundary,
                                      int direction, int maxDistance, int maxBuffer) {
        int anchor = profile[boundary];
        int buffersSeen = 0;
        boolean sawElevated = false;
        for (int step = 1, i = boundary + direction; step <= maxDistance && i >= 0 && i < profile.length; i += direction, step++) {
            if (elevated[i]) {
                sawElevated = true;
            } else if (sawElevated) {
                break;
            } else {
                buffersSeen++;
                if (buffersSeen > maxBuffer) break;
            }
            int expected = anchor + step;
            if (profile[i] <= expected) break;
            profile[i] = expected;
        }
    }

    public static void applyCrossingProfiles(ServerLevel level,
                                              TerrainSamplingCache cache,
                                              PathTerrainField terrain,
                                              List<RoadSegmentPlacement> segments,
                                              List<BlockPos> centers,
                                              int[] profile,
                                              List<SpanRange> ranges,
                                              RoadGenerationConfig cfg,
                                              StreamsReflowingCompat.SamplingSession streams,
                                              int bridgeMinWaterDepth) {
        int deckClearance = Math.max(1, cfg.bridgeDeckClearance());
        // 0.9.066（B2）：同一连接上相邻 BRIDGE 跨度甲板连续性。
        // crossingRanges 按 spans 顺序添加、不一定按站号排序；先按 start 排序算出每个跨度的
        // 水面/甲板基准，再把 gap <= BRIDGE_SPAN_CONTINUITY_GAP 的相邻 BRIDGE 跨度视为
        // 「同一片水被切成两段」，后跨甲板不得低于前跨 —— 消除两段桥之间的台阶
        // （真机曾见相邻跨度甲板 71 -> 73 这类跳变）。应用阶段保持原 spans 顺序，只改甲板值。
        int nRanges = ranges.size();
        Integer[] order = new Integer[nRanges];
        for (int ri = 0; ri < nRanges; ri++) order[ri] = ri;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(ri -> ranges.get(ri).start()));
        int[] hwByRange = new int[nRanges];
        int[] deckByRange = new int[nRanges];
        for (int ri : order) {
            SpanRange range = ranges.get(ri);
            hwByRange[ri] = PathSpanExtractor.waterSurfaceYForRange(
                    segments, level, cache, terrain, streams, bridgeMinWaterDepth,
                    range.start(), range.end());
            deckByRange[ri] = BridgeDeckProfile.minimumDeckY(
                    hwByRange[ri],
                    centers.get(range.start()).getY(),
                    centers.get(range.end()).getY(),
                    deckClearance);
        }
        SpanType[] spanTypes = new SpanType[nRanges];
        int[] spanStarts = new int[nRanges];
        int[] spanEnds = new int[nRanges];
        for (int ri = 0; ri < nRanges; ri++) {
            spanTypes[ri] = ranges.get(ri).type();
            spanStarts[ri] = ranges.get(ri).start();
            spanEnds[ri] = ranges.get(ri).end();
        }
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deckByRange, spanTypes, spanStarts, spanEnds);
        for (int ri = 0; ri < nRanges; ri++) {
            SpanRange range = ranges.get(ri);
            throwIfInterrupted();
            // 0.9.058（缺陷 A）：水面 Y 改由**掩码侧同一套逐列采样**给出。注释保留，语义不变。
            int highestWater = hwByRange[ri];
            int minimumDeck = deckByRange[ri];
            org.slf4j.LoggerFactory.getLogger(Road.class).info(
                    "【桥面高度下界及水位净空判定】 " + "RoadWeaver [ROAD-BRIDGE-DECK] range={}..{} type={} highestWater={} minimumDeck={}"
                            + " stationYStart={} stationYEnd={} clearance={}",
                    range.start(), range.end(), range.type(),
                    highestWater == Integer.MIN_VALUE ? "none" : highestWater,
                    minimumDeck, centers.get(range.start()).getY(), centers.get(range.end()).getY(),
                    deckClearance);
            if (highestWater == Integer.MIN_VALUE && isCanyonRange(centers, range.start(), range.end())) {
                BridgeDeckProfile.applyStableBankRanges(
                        profile, centers.stream().mapToInt(BlockPos::getY).toArray(),
                        List.of(new int[]{range.start(), range.end()}), Integer.MIN_VALUE);
            } else if (range.type() == SpanType.ROAD_CROSSING
                    && PathSpanExtractor.crossingWidthBlocks(centers, range.start(), range.end())
                            <= PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS) {
                applyShortFordClearance(profile, range, highestWater, minimumDeck, cfg);
            } else {
                BridgeDeckProfile.applyFixedRange(profile, range.start(), range.end(), minimumDeck,
                        cfg.bridgeRampSegments());
            }
        }
    }

    /** 涉水甲板按水位与净空确定，邻岸只降不升接入，避免误用高桥甲板把浅滩两岸抬成台阶。
 *
     * Task 5 / T-21: clears only the ford stations that would dip below the
     * water surface, then seats both banks onto the deck. The ford deck target
     * is {@code highestWater + FORD_DECK_CLEARANCE} (a wadable 1..2 blocks
     * above the water) instead of the bridge fixed deck (water + 16): the old
     * +16 deck raised both banks by up to 6 blocks and left the boundary step
     * ((47,-64)y226 -> (48,-65)y232) that prefer-cut could not close because
     * the deck stations themselves are protected from the slope pass. The bank
     * stations directly adjacent to the span are cut down onto the deck
     * (prefer-cut: only ever lowered); the following {@code
     * enforceContinuousSlopePreferCut} pass then closes the remaining bank
     * profile at one block per station, so the span boundary participates in
     * the continuous grade instead of terminating it.
     */
    public static void applyShortFordClearance(int[] profile,
                                                SpanRange range,
                                                int highestWater,
                                                int minimumDeck,
                                                RoadGenerationConfig cfg) {
        if (highestWater == Integer.MIN_VALUE) return;
        // T-21: 浅滩甲板 = 水位 + FORD_DECK_CLEARANCE(可涉水)。
        int required = highestWater + FORD_DECK_CLEARANCE;
        boolean lifted = false;
        for (int i = range.start(); i <= range.end(); i++) {
            if (profile[i] < required) {
                profile[i] = required;
                lifted = true;
            }
        }
        // 高岸直接切到甲板(prefer-cut:只降不升)。岸站由后续
        // enforceContinuousSlopePreferCut 以 1 格/站继续向下收口。
        int left = range.start() - 1;
        int right = range.end() + 1;
        if (left >= 0 && profile[left] > required) {
            profile[left] = required;
            lifted = true;
        }
        if (right < profile.length && profile[right] > required) {
            profile[right] = required;
            lifted = true;
        }
        if (!lifted) return;
        int ramp = Math.max(0, cfg.bridgeRampSegments());
        rampOntoBank(profile, range.start() - 1, -1, ramp);
        rampOntoBank(profile, range.end() + 1, +1, ramp);
    }

    /** 从已抬跨度向陆侧逐站接坡，每站最多一格，避免桥头突然断高。 */
    public static void rampOntoBank(int[] profile, int from, int direction, int ramp) {
        int previousIndex = from - direction;
        if (previousIndex < 0 || previousIndex >= profile.length) return;
        int previous = profile[previousIndex];
        for (int step = 0; step < ramp; step++) {
            int index = from + direction * step;
            if (index < 0 || index >= profile.length) return;
            if (profile[index] >= previous) return;
            profile[index] = Math.min(previous, profile[index] + 1);
            previous = profile[index];
        }
    }

    public static boolean isCanyonRange(List<BlockPos> centers, int start, int end) {
        if (centers == null || centers.size() < 5) return false;
        int left = Math.max(0, start - 2);
        int right = Math.min(centers.size() - 1, end + 2);
        if (left >= start || right <= end) return false;
        int bank = Math.min(centers.get(left).getY(), centers.get(right).getY());
        int lowest = Integer.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            throwIfInterrupted();
            lowest = Math.min(lowest, centers.get(i).getY());
        }
        return bank - lowest >= 10;
    }

    public static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Road generation interrupted");
        }
    }

    public static String summarizeSpans(List<RoadSpan> spans) {
        if (spans == null || spans.isEmpty()) return "tunnel=0 bridge=0 crossing=0 netReuse=0";
        int tunnel = 0;
        int bridge = 0;
        int crossing = 0;
        int netReuse = 0;
        int protectedGap = 0;
        for (RoadSpan span : spans) {
            if (span == null) continue;
            switch (span.type()) {
                case TUNNEL, TUNNEL_JUNCTION -> tunnel++;
                case BRIDGE -> bridge++;
                case ROAD_CROSSING -> crossing++;
                case NETWORK_REUSE -> netReuse++;
                case PROTECTED_GAP -> protectedGap++;
                default -> { }
            }
        }
        return "tunnel=" + tunnel + " bridge=" + bridge + " crossing=" + crossing
                + " netReuse=" + netReuse + " protectedGap=" + protectedGap;
    }
}
