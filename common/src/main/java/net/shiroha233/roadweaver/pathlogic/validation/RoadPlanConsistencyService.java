package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.shiroha233.roadweaver.pathlogic.core.Road;

/**
 * Final consistency gate for a road plan. It reclassifies physical spans from
 * the final geometry and height profile after every junction/repair rewrite.
 */
public final class RoadPlanConsistencyService {
    private RoadPlanConsistencyService() {
    }

    public static RoadData reconcile(ServerLevel level,
                                     RoadData road,
                                     TerrainSamplingCache cache,
                                     PathTerrainField terrain,
                                     StreamsReflowingCompat.SamplingSession streams,
                                     int bridgeMinWaterDepth) {
        if (level == null || road == null || cache == null
                || road.roadSegmentList() == null
                || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()) {
            return road;
        }
        if (!hasUsableSegmentFootprints(road)) {
            return null;
        }

        List<RoadSpan> physical = PathSpanExtractor.extractSpans(
                road.roadSegmentList(), level, cache, terrain, streams, bridgeMinWaterDepth);
        physical = PathSpanExtractor.refineTunnelSpans(
                physical, road.roadSegmentList(), road.targetY(), level, cache);
        physical = PathSpanExtractor.refineFinalFillBridgeSpans(
                physical, road.roadSegmentList(), road.targetY(), level, cache);
        physical = PathSpanExtractor.resolvePhysicalSpanOverlaps(
                physical, centersOf(road.roadSegmentList()));

        List<RoadSpan> metadata = new ArrayList<>();
        if (road.spans() != null) {
            for (RoadSpan span : road.spans()) {
                if (span == null) continue;
                if (span.type() == SpanType.PROTECTED_GAP
                        || span.type() == SpanType.NETWORK_REUSE
                        || span.type() == SpanType.JUNCTION_ANCHOR
                        || span.type() == SpanType.TUNNEL_JUNCTION
                        || span.type() == SpanType.BRIDGE
                        || span.type() == SpanType.ROAD_CROSSING) {
                    metadata.add(span);
                }
            }
        }
        int staleSpanCount = 0;
        ArrayList<RoadSpan> merged = new ArrayList<>(physical);
        Map<Long, Integer> stationIndex = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) {
                stationIndex.putIfAbsent(xzKey(segment.middlePos()), i);
            }
        }
        for (RoadSpan span : metadata) {
            // T-26: BRIDGE / ROAD_CROSSING in road.spans() are the *inputs* this
            // service just recomputed into `physical`. Re-adding one whose
            // endpoints merely differ from the merged result resurrects the very
            // duplicate the merge removed — two decks over one crossing, each
            // interpolated separately by Road.applyCrossingProfiles, which shows
            // up in game as two parallel bridge decks with a step between them.
            //
            // Endpoints no longer have to match exactly: a recomputed span that
            // still overlaps a kept span of the same type carries no new
            // information, so it is dropped. Only a span lying wholly outside
            // every kept same-type span is a genuine addition.
            boolean physicalType = span.type() == SpanType.BRIDGE
                    || span.type() == SpanType.ROAD_CROSSING;
            if (physicalType) {
                // 0.9.039（R3）：端点已经不在站序列里的 BRIDGE/ROAD_CROSSING 是**陈旧跨度** ——
                // 合并/改道重建过 centerline 之后，旧 spans() 里的 start/end 再也对不上任何一站。
                // 下面那段 `overlaps()` 只要任一端点解析不到就直接 return false，于是这种跨度
                // 每一轮都被当成「真新增」追加回来，同一个过水点越积越多（真机实测同一条路
                // 2123..2249 与 2214..2317 并存）。而 normalizeTunnelSpans 对解析不到端点的
                // BRIDGE 也只做 passthrough、不参与合并 ⇒ 后续任何归一都清不掉它。
                // 正确做法是在追加这一步就丢弃：端点解析不到 ⇒ 该跨度描述的是已经不存在的站点。
                boolean startKnown = span.start() != null && stationIndex.containsKey(xzKey(span.start()));
                boolean endKnown = span.end() != null && stationIndex.containsKey(xzKey(span.end()));
                if (!startKnown || !endKnown) {
                    staleSpanCount++;
                    continue;
                }
            }
            boolean duplicate = false;
            for (RoadSpan existing : merged) {
                if (existing == null || existing.type() != span.type()
                        || existing.start() == null || existing.end() == null) {
                    continue;
                }
                if (xzKey(existing.start()) == xzKey(span.start())
                        && xzKey(existing.end()) == xzKey(span.end())) {
                    duplicate = true;
                    break;
                }
                if (physicalType && overlaps(existing, span, stationIndex)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) merged.add(span);
        }
        // 0.9.039（R3）：上面这段追加循环的 `overlaps()` 只要**任一**端点解析不到站点就
        // `return false`（见本类 `overlaps`），于是陈旧跨度会被当成「真新增」放行 ——
        // 同一个过水点又留下第二段 BRIDGE。建造期 `Road.applyCrossingProfiles` 按 ranges
        // 顺序逐段插值、后写覆盖前写，游戏里就是「甲板一块块错开的双层桥」。
        //
        // 追加之后再做最后一次 physical 归一。**实际行为**（勿按名字想象）：
        // ① 只把 TUNNEL 与 BRIDGE 拿去合并，ROAD_CROSSING 一律 passthrough（从不合并）；
        // ② 对已经是单条、且与隧道无重叠的跨度是恒等；
        // ③ 但会做三件注释里不再声称"恒等"的事 —— 相邻(1站)合并、
        //    把与 TUNNEL 重叠的 BRIDGE 裁短或整段丢弃、以及把 BRIDGE 挪到列表末尾
        //    （passthrough 先入、再 TUNNEL、再 BRIDGE），而顺序会影响
        //    Road.applyCrossingProfiles 的"后写覆盖前写"。
        // 不动任何高度、不动选线。
        int spanCountBeforeFinalNormalize = merged.size();
        merged = new ArrayList<>(PathSpanExtractor.resolvePhysicalSpanOverlaps(
                merged, centersOf(road.roadSegmentList())));
        if (merged.size() != spanCountBeforeFinalNormalize) {
            org.slf4j.LoggerFactory.getLogger("roadweaver").info(
                    "RoadWeaver [ROAD-SPAN-NORMALIZE] owner={}->{} spans {} -> {}",
                    road.ownerA2dKey(), road.ownerB2dKey(),
                    spanCountBeforeFinalNormalize, merged.size());
        }
        if (staleSpanCount > 0) {
            org.slf4j.LoggerFactory.getLogger("roadweaver").info(
                    "RoadWeaver [ROAD-SPAN-STALE] owner={}->{} dropped {} stale BRIDGE/ROAD_CROSSING spans",
                    road.ownerA2dKey(), road.ownerB2dKey(), staleSpanCount);
        }
        RoadData reconciled = new RoadData(
                road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), merged, road.targetY(),
                road.ownerA2dKey(), road.ownerB2dKey());
        reconciled = RoadDataValidator.normalizeDuplicateJunctions(reconciled);
        return RoadDataValidator.repairFinalHeightSteps(reconciled);
    }

    /**
     * Endpoint and curved stations may contain fewer columns than the full
     * road width. The paver already handles those narrow footprints; only an
     * empty or malformed footprint is invalid here.
     */
    static boolean hasUsableSegmentFootprints(RoadData road) {
        if (road == null || road.roadSegmentList() == null) return false;
        int index = 0;
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            if (segment == null || segment.middlePos() == null
                    || segment.positions() == null || segment.positions().isEmpty()) {
                org.slf4j.LoggerFactory.getLogger("roadweaver").warn(
                        "RoadWeaver consistency rejected empty segment footprint index={} width={}",
                        index, road.width());
                return false;
            }
            index++;
        }
        return true;
    }

    private static java.util.List<net.minecraft.core.BlockPos> centersOf(
            java.util.List<net.shiroha233.roadweaver.core.model.RoadSegmentPlacement> segments) {
        java.util.List<net.minecraft.core.BlockPos> centers = new java.util.ArrayList<>();
        if (segments != null) {
            for (net.shiroha233.roadweaver.core.model.RoadSegmentPlacement segment : segments) {
                centers.add(segment == null ? null : segment.middlePos());
            }
        }
        return centers;
    }

    private static long xzKey(net.minecraft.core.BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /**
     * T-26: true when two spans of the same physical type share at least one
     * station.
     *
     * <p>Compared in <em>station</em> space, not block space: a curved or
     * switchback route can bring two distant route positions close together in
     * x/z, and bounding-box testing would then drop a genuine second bridge.
     * {@code stationIndex} maps each center's xz to its position in the route,
     * the same lookup {@code PathSpanExtractor} uses for its own merges.</p>
     */
    private static boolean overlaps(RoadSpan a, RoadSpan b, Map<Long, Integer> stationIndex) {
        Integer a0 = stationIndex.get(xzKey(a.start()));
        Integer a1 = stationIndex.get(xzKey(a.end()));
        Integer b0 = stationIndex.get(xzKey(b.start()));
        Integer b1 = stationIndex.get(xzKey(b.end()));
        if (a0 == null || a1 == null || b0 == null || b1 == null) return false;
        int aMin = Math.min(a0, a1);
        int aMax = Math.max(a0, a1);
        int bMin = Math.min(b0, b1);
        int bMax = Math.max(b0, b1);
        return aMax >= bMin && aMin <= bMax;
    }
}

