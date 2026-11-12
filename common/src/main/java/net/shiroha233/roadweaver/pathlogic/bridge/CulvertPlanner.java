/* 文件职责：涵洞规划：在路面下方留出过水口，避免道路变成水坝。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;

/**
 * 涵洞（C1-①）：窄水道的路不填成坝、也不留缺口，而是把路面抬到水面之上，让水从路下继续流。
 *
 * <p>现状（0.8.434 之前）：施工期已经有一段「浅窄水道抬成涵洞」的代码
 * （{@code RoadConstructionSafety.prepareCulvertDeck}，条件 ≤2 站、水深 ≤2、净空 ≤6），
 * 但它在**施工排坡**阶段才跑，而 {@code FinalRoadWaterValidator} 在**收尾校验**阶段就已经把
 * 「路面低于水面」的路整条拒掉或打成保护缺口 —— 于是规划期根本活不到施工期。真机看到的就是
 * 「要么断一截、要么水被填平」。
 *
 * <p>这个类把关口前移：在收尾校验之前，把窄水道的路面抬到 {@code 水面 + 2}（1 格过水 + 1 格路面），
 * 两侧按 1 格/站起坡接回原高度，并记一条 {@link SpanType#CULVERT} 跨度。抬升只会**加高**，
 * 不会压低任何站，因此不会造出天路，也不会动桥/隧道/受保护站。
 *
 * <p>纯几何：水面/河床由一个 {@link Probe} 提供，测试里可以喂假数据，不需要世界。
 */
public final class CulvertPlanner {
    /** 路面高出水面的格数：1 格过水 + 1 格路面本体。 */
    public static final int DECK_ABOVE_WATER = 2;

    private CulvertPlanner() {
    }

    /** 一根水柱：是否可用、是否有水、水面与河床高度。 */
    public record Water(boolean available, boolean water, int surfaceY, int bedY) {
        public static Water unavailable() { return new Water(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE); }
        public static Water dry() { return new Water(true, false, Integer.MIN_VALUE, Integer.MIN_VALUE); }
        public static Water wet(int surfaceY, int bedY) { return new Water(true, true, surfaceY, bedY); }
        public int depth() { return water ? Math.max(0, surfaceY - bedY + 1) : 0; }
    }

    /** 按列取水柱信息。 */
    @FunctionalInterface
    public interface Probe {
        Water at(int x, int z);
    }

    /** {@code road} 为改写后的数据（没改动时就是入参本身）。 */
    public record Result(RoadData road, int runs, int stations) {
        public boolean changed() { return runs > 0; }
    }

    /**
     * 把窄水道改成涵洞。
     *
     * @param road           待改写的路
     * @param maxRunStations 一条水道最多几站（更长就该走桥，不硬塞涵洞）
     * @param maxWaterDepth  水深上限（深水涵洞要砌很厚的墙，交给桥）
     * @param probe          水柱采样
     */
    public static Result cross(RoadData road, int maxRunStations, int maxWaterDepth, Probe probe) {
        if (road == null || probe == null || road.roadSegmentList() == null
                || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()
                || road.roadSegmentList().isEmpty()) {
            return new Result(road, 0, 0);
        }
        int size = road.roadSegmentList().size();
        boolean[] blocked = spanMask(road, size);
        int[] heights = new int[size];
        for (int i = 0; i < size; i++) heights[i] = road.targetY().get(i);
        boolean[] raised = new boolean[size];
        List<RoadSpan> spans = new ArrayList<>(road.spans() == null ? List.of() : road.spans());

        int runs = 0;
        int stations = 0;
        int index = 0;
        while (index < size) {
            if (blocked[index]) { index++; continue; }
            RoadSegmentPlacement segment = road.roadSegmentList().get(index);
            if (!isWetStation(segment, probe)) { index++; continue; }
            // First measure the complete contiguous water run, including a deep middle station.
            // Breaking the run at the first non-qualifying station was carving the two shallow
            // banks into two unrelated culverts and leaving the deep middle to a gap.
            int end = index;
            while (end + 1 < size && !blocked[end + 1]
                    && isWetStation(road.roadSegmentList().get(end + 1), probe)) {
                end++;
            }
            boolean allQualify = true;
            for (int i = index; i <= end; i++) {
                if (!isCulvertStation(road.roadSegmentList().get(i), heights[i], maxWaterDepth, probe)) {
                    allQualify = false;
                    break;
                }
            }
            int length = end - index + 1;
            int deck = Integer.MIN_VALUE;
            int deepest = 0;
            for (int i = index; i <= end; i++) {
                RoadSegmentPlacement station = road.roadSegmentList().get(i);
                deck = Math.max(deck, deckAt(station, heights[i], probe));
                deepest = Math.max(deepest, depthAt(station, probe));
            }
            int start = index;
            index = end + 1;
            if (!allQualify || length > maxRunStations || deepest > maxWaterDepth
                    || deck == Integer.MIN_VALUE) continue;
            // The span names two stations, and RoadDataValidator refuses a span that starts and ends on
            // the same station. Widen it over the approaches, which rise with the deck anyway - and skip
            // the run entirely on a one-station road, where no such span exists.
            int spanStart = start > 0 && !blocked[start - 1] ? start - 1 : start;
            int spanEnd = end + 1 < size && !blocked[end + 1] ? end + 1 : end;
            if (spanStart == spanEnd) continue;
            for (int i = start; i <= end; i++) {
                heights[i] = Math.max(heights[i], deck);
                raised[i] = true;
            }
            // Approaches: one block per station back down to the original profile, never below it.
            ramp(heights, raised, start - 1, -1, deck, blocked);
            ramp(heights, raised, end + 1, 1, deck, blocked);
            BlockPos from = road.roadSegmentList().get(spanStart).middlePos();
            BlockPos to = road.roadSegmentList().get(spanEnd).middlePos();
            if (from != null && to != null) {
                spans.add(new RoadSpan(from, to, SpanType.CULVERT));
            }
            runs++;
            stations += length;
        }
        if (runs == 0) return new Result(road, 0, 0);

        List<Integer> targetY = new ArrayList<>(size);
        for (int i = 0; i < size; i++) targetY.add(heights[i]);
        RoadData rewritten = new RoadData(road.width(), road.roadType(), road.materials(),
                road.slabMaterials(), road.roadSegmentList(), List.copyOf(spans),
                targetY, road.ownerA2dKey(), road.ownerB2dKey());
        return new Result(rewritten, runs, stations);
    }

    private static boolean isWetStation(RoadSegmentPlacement segment, Probe probe) {
        Water water = stationWater(segment, probe);
        return water.available() && water.water();
    }

    /** A station qualifies when its own column is water and the planned surface sits inside it. */
    private static boolean isCulvertStation(RoadSegmentPlacement segment, int plannedY,
                                            int maxWaterDepth, Probe probe) {
        Water water = stationWater(segment, probe);
        return water.available() && water.water()
                && water.depth() <= maxWaterDepth
                && plannedY < water.surfaceY();
    }

    private static int deckAt(RoadSegmentPlacement segment, int plannedY, Probe probe) {
        Water water = stationWater(segment, probe);
        int deck = water.available() && water.water() ? water.surfaceY() + DECK_ABOVE_WATER : plannedY;
        return Math.max(plannedY, deck);
    }

    private static int depthAt(RoadSegmentPlacement segment, Probe probe) {
        Water water = stationWater(segment, probe);
        return water.available() && water.water() ? water.depth() : 0;
    }

    /** Deepest water of a station: the centre plus the two blocks either side of the road. */
    private static Water stationWater(RoadSegmentPlacement segment, Probe probe) {
        if (segment == null || segment.middlePos() == null) return Water.unavailable();
        Water worst = probe.at(segment.middlePos().getX(), segment.middlePos().getZ());
        if (segment.positions() == null) return worst;
        for (BlockPos position : segment.positions()) {
            if (position == null || position.equals(segment.middlePos())) continue;
            Water other = probe.at(position.getX(), position.getZ());
            if (!other.available()) continue;
            if (!worst.available() || (other.water() && other.depth() > worst.depth())) worst = other;
        }
        return worst;
    }

    private static void ramp(int[] heights, boolean[] raised, int from, int step, int deck, boolean[] blocked) {
        int height = deck;
        for (int i = from; i >= 0 && i < heights.length; i += step) {
            if (blocked[i]) return;
            height--;
            if (height <= heights[i]) return;
            heights[i] = height;
            raised[i] = true;
        }
    }

    /** Stations covered by a span that owns its own geometry: a culvert must not cut through them. */
    private static boolean[] spanMask(RoadData road, int size) {
        boolean[] mask = new boolean[size];
        if (road.spans() == null || road.spans().isEmpty()) return mask;
        java.util.Map<Long, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < size; i++) {
            BlockPos middle = road.roadSegmentList().get(i).middlePos();
            if (middle != null) indices.put(key(middle), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.TUNNEL
                    && span.type() != SpanType.ROAD_CROSSING && span.type() != SpanType.PROTECTED_GAP
                    && span.type() != SpanType.CULVERT) continue;
            Integer start = indices.get(key(span.start()));
            Integer end = indices.get(key(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) mask[i] = true;
        }
        return mask;
    }

    private static long key(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
