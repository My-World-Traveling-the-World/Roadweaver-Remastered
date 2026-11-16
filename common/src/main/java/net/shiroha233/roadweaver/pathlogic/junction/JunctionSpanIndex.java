/* 文件职责：同一道路快照的站号索引与接路掩码；不读世界、不持有跨轮缓存。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

/** 将纯跨度判断从接路编排分离，同一次裁切的两个掩码共用一次索引。 */
final class JunctionSpanIndex {
    private final List<RoadSegmentPlacement> segments;
    private final List<RoadSpan> spans;
    private final Map<Long, Integer> index = new HashMap<>();

    JunctionSpanIndex(RoadData road) {
        this(road.roadSegmentList(), road.spans());
    }

    /** 必须跟随新的道路重建，不能把旧站号用于裁切或重排后的道路。 */
    JunctionSpanIndex(List<RoadSegmentPlacement> segments, List<RoadSpan> spans) {
        this.segments = segments;
        this.spans = spans;
        if (spans == null || spans.isEmpty()) return;
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            // 重复 X/Z 必须保留最后一站，维持历史 HashMap.put 的覆盖顺序。
            if (segment != null && segment.middlePos() != null) index.put(xzKey(segment.middlePos()), i);
        }
    }

    /** 侧向接路禁区：隧道显式接头可放行，实体桥和保护缺口仍优先。 */
    boolean[] blocked() {
        boolean[] blocked = new boolean[segments.size()];
        boolean[] physicalCrossing = new boolean[segments.size()];
        boolean[] explicitTunnelJunction = new boolean[segments.size()];
        if (spans == null || spans.isEmpty()) return blocked;
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            Integer start = index.get(xzKey(span.start()));
            Integer end = index.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (span.type() == SpanType.BRIDGE || span.type() == SpanType.ROAD_CROSSING
                    || span.type() == SpanType.PROTECTED_GAP) {
                // 桥和过水只封闭内部站，保留两侧稳定桥头供相邻道路接入。
                // 保护缺口包含端点，显式隧道路口也不能覆盖这种硬保护。
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

    /** 交点搜索不进入桥身，但必须留下桥头候选。 */
    boolean[] searchExcluded() {
        boolean[] blocked = new boolean[segments.size()];
        if (spans == null || spans.isEmpty()) return blocked;
        for (RoadSpan span : spans) {
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

    /** 特殊跨度的完整闭区间标记，保持与侧向禁区不同的端点语义。 */
    boolean[] special() {
        boolean[] special = new boolean[segments.size()];
        if (spans == null || spans.isEmpty()) return special;
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

    /** 只按水平坐标定位跨度，Y 在路口精修后允许变化。 */
    private static long xzKey(BlockPos position) {
        return ((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL);
    }
}
