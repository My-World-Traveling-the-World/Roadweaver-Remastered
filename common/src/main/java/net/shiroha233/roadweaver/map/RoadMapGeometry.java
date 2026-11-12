/* 文件职责：地图与叠加层使用的只读几何视图。 */
package net.shiroha233.roadweaver.map;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;

/** Read-only geometry view used by the map and overlay paths. */
public final class RoadMapGeometry {
    private RoadMapGeometry() {
    }

    /**
     * Returns only the physical route owned by this road. A NETWORK_REUSE
     * station belongs to the donor road and must not be drawn a second time.
     * Each returned run is an independent polyline; reused intervals are not
     * bridged across. The returned Y coordinate is normalized because the map
     * is horizontal.
     */
    public static List<List<BlockPos>> visiblePolylines(RoadData road) {
        if (road == null || road.roadSegmentList() == null
                || road.roadSegmentList().size() < 2) return List.of();
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] reused = spanMask(segments, road.spans(), SpanType.NETWORK_REUSE);
        ArrayList<List<BlockPos>> result = new ArrayList<>();
        ArrayList<BlockPos> run = new ArrayList<>(segments.size());
        for (int i = 1; i < segments.size(); i++) {
            RoadSegmentPlacement first = segments.get(i - 1);
            RoadSegmentPlacement second = segments.get(i);
            if (first == null || second == null
                    || first.middlePos() == null || second.middlePos() == null) {
                appendRun(result, run);
                continue;
            }
            if ((reused != null && (reused[i - 1] || reused[i]))) {
                // A reused run is rendered by its canonical owner. Endpoints
                // on either side remain visible, so the branch still meets
                // the shared corridor on the map.
                appendRun(result, run);
                continue;
            }
            BlockPos a = first.middlePos();
            BlockPos b = second.middlePos();
            if (run.isEmpty()) run.add(flatten(a));
            else if (!run.get(run.size() - 1).equals(flatten(a))) {
                appendRun(result, run);
                run.add(flatten(a));
            }
            run.add(flatten(b));
        }
        appendRun(result, run);
        return List.copyOf(result);
    }

    private static void appendRun(List<List<BlockPos>> result, List<BlockPos> run) {
        if (run.size() >= 2) result.add(List.copyOf(run));
        run.clear();
    }

    private static BlockPos flatten(BlockPos pos) {
        return new BlockPos(pos.getX(), 0, pos.getZ());
    }

    private static boolean[] spanMask(List<RoadSegmentPlacement> segments,
                                       List<RoadSpan> spans,
                                       SpanType type) {
        if (segments == null || spans == null || spans.isEmpty()) return null;
        Map<Long, Integer> indices = new HashMap<>(segments.size() * 2);
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) {
                indices.putIfAbsent(xzKey(segment.middlePos()), i);
            }
        }
        boolean[] mask = new boolean[segments.size()];
        boolean found = false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) mask[i] = true;
            found = true;
        }
        return found ? mask : null;
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
