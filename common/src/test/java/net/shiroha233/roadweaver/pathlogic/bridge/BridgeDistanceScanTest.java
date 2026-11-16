/* 文件职责：对拍旧双扫描与新单扫描，验证距离舍入、重叠边界和实际遍历量。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeDistanceScanTest {
    /** 旧实现分两遍读取站点；计数表明新实现确实只访问一次全部点对。 */
    @Test
    void scansEachPairOnceAndKeepsClosedRadiusBoundary() {
        var whole = List.of(new BlockPos(-3, 60, 0), new BlockPos(0, 60, 0), new BlockPos(3, 60, 0));
        var samples = List.of(new BlockPos(0, 80, 4), new BlockPos(30, 80, 40));
        var counted = new Counted(samples);
        assertEquals(new BridgeShareDetector.DistanceScan(4, 3),
                BridgeShareDetector.scanDistances(whole, counted, 5));
        assertEquals(6, counted.reads);
        counted = new Counted(samples);
        assertEquals(new BridgeShareDetector.DistanceScan(4, 3), legacy(whole, counted, 5));
        assertEquals(9, counted.reads);
    }

    /** 独立保留旧双遍历，不以新实现自身作为期望，覆盖长短曲线和负坐标。 */
    @Test
    void agreesWithLegacyForBothDirectionsAndSampling() {
        var random = new Random(835276L);
        int admitted = 0;
        for (int caseId = 0; caseId < 256; caseId++) {
            var a = curve(random, 2 + random.nextInt(160), 0);
            var b = curve(random, 2 + random.nextInt(160), random.nextInt(30));
            int radius = 1 + random.nextInt(48);
            int threshold = 1 + random.nextInt(20);
            var forward = legacy(a, sample(b), radius);
            var backward = legacy(b, sample(a), radius);
            int minimum = Math.min(forward.minDistance(), backward.minDistance());
            int overlap = Math.max(forward.overlapStations(), backward.overlapStations());
            boolean shares = minimum <= radius && overlap >= threshold
                    && BridgeShareDetector.parallelDot(a, b) >= BridgeShareDetector.DEFAULT_MIN_PARALLEL_DOT;
            var actual = BridgeShareDetector.detectOverlapping(a, b, radius, threshold);
            assertEquals(new BridgeShareDetector.Report(shares, minimum, overlap), actual, "case=" + caseId);
            if (shares) admitted++;
        }
        assertTrue(admitted > 0 && admitted < 256, "必须同时覆盖接受与拒绝，不能让对拍空转");
    }

    private static List<BlockPos> curve(Random random, int count, int offset) {
        var points = new ArrayList<BlockPos>();
        int z = -20 + offset;
        for (int i = 0; i < count; i++) {
            z += random.nextInt(3) - 1;
            points.add(new BlockPos(-100 + i, 64, z));
        }
        return points;
    }

    /** 单独复制既有均匀采样，避免生产采样变化与被测循环同时漂移。 */
    private static List<BlockPos> sample(List<BlockPos> source) {
        if (source.size() <= 64) return source;
        var selected = new ArrayList<BlockPos>();
        for (int i = 0; i < 64; i++) selected.add(source.get((int) Math.round((double) i * (source.size() - 1) / 63)));
        return selected;
    }

    private static BridgeShareDetector.DistanceScan legacy(List<BlockPos> whole, List<BlockPos> samples, int limit) {
        double best = Double.MAX_VALUE;
        for (var p : whole) for (var q : samples) best = Math.min(best, distance(p, q));
        int overlap = 0;
        for (var p : whole) for (var q : samples) {
            if (distance(p, q) <= limit) { overlap++; break; }
        }
        return new BridgeShareDetector.DistanceScan(best == Double.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(best), overlap);
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX(), dz = (double) a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 只观测列表访问，不向生产内核增加计数或可变全局状态。 */
    private static final class Counted extends AbstractList<BlockPos> {
        private final List<BlockPos> source;
        private int reads;
        Counted(List<BlockPos> source) { this.source = source; }
        @Override public BlockPos get(int index) { reads++; return source.get(index); }
        @Override public int size() { return source.size(); }
    }
}
