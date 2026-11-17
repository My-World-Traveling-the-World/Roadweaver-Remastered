/* 文件职责：冻结9fa7b6e施工跨度计算，独立对拍迁出及后续索引优化。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;

/** 只依赖已发布站序和跨度；不读取世界，也不混用区块编译器的不同匹配规则。 */
final class FrozenConstructionSpanMasks {
    private FrozenConstructionSpanMasks() {}
    /** 每区块先按原顺序执行，独立迁出后才能测量和替换重复索引。 */
    public static Masks build(List<BlockPos> centers, RoadData road, int ramps) {
        return new Masks(buildPreserveCrossingGradeMask(centers,road,ramps),
                buildSpanMask(centers,road,SpanType.ROAD_CROSSING),
                buildSpanMask(centers,road,SpanType.NETWORK_REUSE),
                buildSpanMask(centers,road,SpanType.PROTECTED_GAP),
                buildSpanMask(centers,road,SpanType.TUNNEL));
    }
    public record Masks(boolean[] preserveGrade, boolean[] directCrossing, boolean[] networkReuse,
                        boolean[] protectedGap, boolean[] tunnel) {}
    private static boolean[] buildPreserveCrossingGradeMask(List<BlockPos> centers,
                                                             RoadData road,
                                                             int rampSegments) {
        boolean[] result = new boolean[centers.size()];
        if (road == null || road.spans() == null || road.spans().isEmpty()) return result;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) indices.put(xzKey(centers.get(i)), i);
        int ramps = Math.max(0, rampSegments);
        for (var span : road.spans()) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) continue;
            Integer first = indices.get(xzKey(span.start()));
            Integer second = indices.get(xzKey(span.end()));
            if (first == null || second == null) continue;
            int start = Math.max(0, Math.min(first, second) - ramps);
            int end = Math.min(result.length - 1, Math.max(first, second) + ramps);
            for (int i = start; i <= end; i++) result[i] = true;
        }
        return result;
    }

    /**
     * 跨度遮罩。0.9.080：端点坐标失配时退化为「最近的站」，不再静默丢弃整段。
     *
     * <p>原来的精确 XZ 匹配一旦对不上就 {@code continue}，后果是整段 span 不参与任何处理：
     * 隧道遮罩全 false 时 {@code RoadTunnelBuilder.processSegment} 的 findSpan 也找不到 span，
     * 于是隧道一格不挖、连日志都没有。站序列会被 merge/rebuild 重建过，坐标漂 1~2 格是常态。</p>
     */
    private static boolean[] buildSpanMask(List<BlockPos> centers, RoadData road, SpanType type) {
        boolean[] result = new boolean[centers.size()];
        if (road == null || road.spans() == null || road.spans().isEmpty()) return result;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) indices.put(xzKey(centers.get(i)), i);
        for (var span : road.spans()) {
            if (span == null || span.type() != type) continue;
            Integer first = resolveStationIndex(indices, centers, span.start());
            Integer second = resolveStationIndex(indices, centers, span.end());
            if (first == null || second == null) {
                SPAN_MASK_UNMATCHED.incrementAndGet();
                continue;
            }
            int start = Math.max(0, Math.min(first, second));
            int end = Math.min(result.length - 1, Math.max(first, second));
            for (int i = start; i <= end; i++) result[i] = true;
        }
        return result;
    }

    private static final int SPAN_MASK_FALLBACK_TOLERANCE = 4;
    private static final java.util.concurrent.atomic.AtomicLong SPAN_MASK_UNMATCHED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 精确 XZ → 最近站（≤4 格漂移）→ null。 */
    private static Integer resolveStationIndex(Map<Long, Integer> indices, List<BlockPos> centers,
                                               BlockPos target) {
        if (target == null || centers == null || centers.isEmpty()) return null;
        Integer exact = indices.get(xzKey(target));
        if (exact != null) return exact;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos c = centers.get(i);
            if (c == null) continue;
            int d = Math.max(Math.abs(c.getX() - target.getX()), Math.abs(c.getZ() - target.getZ()));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return (best >= 0 && bestDist <= SPAN_MASK_FALLBACK_TOLERANCE) ? best : null;
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
