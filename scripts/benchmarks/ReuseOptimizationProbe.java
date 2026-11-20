package net.shiroha233.roadweaver.worldgen.road;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.shiroha233.roadweaver.core.model.*;

/** 对照显式git基线的真实Preparation路径，不拿更老、尚未缓存的算法冒充基线。 */
public final class ReuseOptimizationProbe {
    private static volatile Object retained;
    private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        String baseline = args.length > 1 ? args[1] : "cf128a0";
        var json = new StringBuilder("{\"baseline\":\"" + baseline + "\",\"unit\":\"milliseconds_per_4_normalizations\",\"allocation_unit\":\"caller_bytes_per_normalization\",\"warmups\":3,\"samples\":9,\"cases\":[");
        String[] names = {"sparse", "dense", "small", "mixed-spans"};
        for (int c = 0; c < names.length; c++) {
            var roads = new ArrayList<RoadData>();
            int size = c == 2 ? 8 : 96;
            for (int i = 0; i < size; i++) {
                var road = RoadReuseCandidatesTest.line(
                        c == 1 || c == 3 ? i % 4 : i * 1024, c == 1 || c == 3 ? i % 7 : -17, i, i % 5 == 0);
                if (c == 3) {
                    // 多类型跨度才会反复查首末站；固定相同几何与高度，比较完整生产归属产物。
                    var spans = new ArrayList<RoadSpan>(road.spans());
                    SpanType[] types = {SpanType.TUNNEL, SpanType.TUNNEL_JUNCTION, SpanType.BRIDGE,
                            SpanType.ROAD_CROSSING, SpanType.PROTECTED_GAP};
                    for (int t = 0; t < types.length; t++) spans.add(new RoadSpan(
                            road.roadSegmentList().get(t * 2).middlePos(),
                            road.roadSegmentList().get(t * 2 + 1).middlePos(), types[t]));
                    road = new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                            road.roadSegmentList(), spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
                }
                roads.add(road);
            }
            if (!PreviousRoadReuse.normalizePhysicalOverlaps(roads)
                    .equals(RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads))) throw new AssertionError("归属产物不等价");
            for (int i = 0; i < 3; i++) { measure(roads, false); measure(roads, true); }
            double[][] before = new double[9][], after = new double[9][];
            for (int i = 0; i < 9; i++) {
                if ((i & 1) == 0) { before[i] = measure(roads, false); after[i] = measure(roads, true); }
                else { after[i] = measure(roads, true); before[i] = measure(roads, false); }
            }
            if (c > 0) json.append(',');
            json.append("{\"name\":\"").append(names[c]).append("\",\"roads\":").append(size)
                    .append(",\"old_ms_p50\":").append(median(before, 0)).append(",\"new_ms_p50\":").append(median(after, 0))
                    .append(",\"old_bytes_p50\":").append(median(before, 1)).append(",\"new_bytes_p50\":").append(median(after, 1)).append('}');
        }
        json.append("]}\n"); Files.writeString(Path.of(args[0]), json); System.out.print(json);
    }
    private static double[] measure(List<RoadData> roads, boolean current) {
        long allocation = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < 4; i++) retained = current
                ? RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(roads) : PreviousRoadReuse.normalizePhysicalOverlaps(roads);
        return new double[]{(System.nanoTime() - start) / 1e6,
                (MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocation) / 4.0};
    }
    private static double median(double[][] values, int column) {
        double[] sorted = Arrays.stream(values).mapToDouble(row -> row[column]).sorted().toArray();
        return sorted[sorted.length / 2];
    }
}
