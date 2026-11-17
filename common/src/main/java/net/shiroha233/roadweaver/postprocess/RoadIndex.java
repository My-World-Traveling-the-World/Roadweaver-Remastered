/* 文件职责：路网索引 —— 既有路线的分段索引与最近点查询。 */
package net.shiroha233.roadweaver.postprocess;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 仍留在 RoadSnapService 里的常量与工具（包内可见）。
import static net.shiroha233.roadweaver.postprocess.RoadSnapService.*;
import static net.shiroha233.roadweaver.postprocess.JunctionTrimOps.*;

/**
 * 既有路网的索引。
 *
 * <p>新路吸附到既有路网时要回答两类问题：「离我最近的既有站是哪一站」与
 * 「这段既有路属于哪条路线」。把站先切片索引起来，比每次遍历全部路线便宜得多。</p>
 *
 * <p>纯只读结构（构建后不再改）。从 3710 行的 {@code RoadSnapService}
 * 里纯搬迁出来，逻辑一字未改。</p>
 */
class RoadIndex {
    private final int segmentCount;
    private final Map<Long, Integer> xzToIndex;
    private final List<RoadSpan> spans;
    private final Map<SpanType, boolean[]> masks = new EnumMap<>(SpanType.class);

    RoadIndex(RoadData road) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        this.segmentCount = segments.size();
        this.spans = road.spans();
        Map<Long, Integer> indices = new HashMap<>(Math.max(16, segmentCount * 2));
        for (int i = 0; i < segmentCount; i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        this.xzToIndex = indices;
    }

    /** 各跨度类型掩码只构建一次，调用方只读以避免相互污染。 */
    boolean[] spanMask(SpanType type) {
        boolean[] cached = masks.get(type);
        if (cached != null) return cached;
        boolean[] mask = new boolean[segmentCount];
        for (RoadSpan span : spans) {
            if (span == null || span.type() != type) continue;
            if (span.start() == null || span.end() == null) continue;
            Integer start = xzToIndex.get(xzKey(span.start()));
            Integer end = xzToIndex.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) mask[i] = true;
        }
        masks.put(type, mask);
        return mask;
    }

    boolean inSpan(int index, SpanType type) {
        if (index < 0 || index >= segmentCount) return false;
        return spanMask(type)[index];
    }
}
