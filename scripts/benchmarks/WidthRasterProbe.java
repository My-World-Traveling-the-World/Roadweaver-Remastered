/* 文件职责：在同一JVM交替测冻结落列与当前实现，避免把拆类误报成提速。 */
package net.shiroha233.roadweaver.postprocess;

import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;

public final class WidthRasterProbe {
    private static volatile long sink;
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int size : new int[]{128, 512, 2048}) measure(bean, size);
    }
    private static void measure(com.sun.management.ThreadMXBean bean, int size) {
        List<BlockPos> centers = new ArrayList<>();
        List<Vec2d> points = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        // 起点为负坐标且站号跨过Integer缓存；样条密度保持四分之一格。
        for (int i = 0; i < size; i++) {
            centers.add(new BlockPos(i - size / 2, 64 + i % 7, 0));
            distances.add((double)i);
            if (i > 0) for (int j = 1; j < 4; j++) points.add(new Vec2d(i - 1 + j / 4.0 - size / 2, 0));
            points.add(new Vec2d(i - size / 2, 0));
        }
        java.util.function.Supplier<List<RoadSegmentPlacement>> old = () ->
                LegacyCurveRasterReference.rasterizeSegments(points, centers, distances, 5);
        java.util.function.Supplier<List<RoadSegmentPlacement>> now = () ->
                PathWidthRasterizer.rasterizeSegments(points, centers, distances, 5);
        var expected = old.get(); var actual = now.get();
        if (expected.size() != actual.size()) throw new AssertionError("stations");
        for (int i = 0; i < size; i++) if (!expected.get(i).equals(actual.get(i)))
            throw new AssertionError("ordered station product " + i);
        for (int i = 0; i < 30; i++) { consume(old.get()); consume(now.get()); }
        long[][] ns = new long[2][15], bytes = new long[2][15];
        for (int i = 0; i < 15; i++) for (int j = 0; j < 2; j++) {
            int lane = (i + j) % 2;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long began = System.nanoTime();
            consume((lane == 0 ? old : now).get());
            ns[lane][i] = System.nanoTime() - began;
            bytes[lane][i] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        }
        for (int lane = 0; lane < 2; lane++) { Arrays.sort(ns[lane]); Arrays.sort(bytes[lane]); }
        long visits = (long)(points.size() - 1) * 9 * 9;
        System.out.printf(Locale.ROOT, "WIDTH_RESULT stations=%d width=5 boundingVisits=%d baselineNs=%d currentNs=%d baselineBytes=%d currentBytes=%d%n",
                size, visits, ns[0][7], ns[1][7], bytes[0][7], bytes[1][7]);
    }
    private static void consume(List<RoadSegmentPlacement> road) {
        long sum = 0; for (var station : road) sum += station.positions().size(); sink = sum;
    }
}
