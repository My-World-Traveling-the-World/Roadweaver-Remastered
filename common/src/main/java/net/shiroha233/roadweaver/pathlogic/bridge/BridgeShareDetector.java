/* 文件职责：跨路桥「共享跨越」判定（0.9.029 / R-03 核心）。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨路桥「共享跨越」判定（0.9.029 / R-03 核心）。
 *
 * <p>背景（真机 2026-11-08，坐标 `(279,66,-157)`）：**两条不同的路**在同一片水面上各架了一座桥 ——
 * `(32,-64)→(-624,-944)` span=6 deckY **65**，`(32,-64)→(992,544)` span=2 deckY **63**，
 * XZ 几乎完全重合、z 差 4~8 格、**Y 差 2 格**、模板一个 `bridge_1` 一个 `bridge_0`
 * ⇒ 两块甲板并排穿插。规划阶段**没有任何「附近已经有桥了」的检查**，所以谁也不知道对方存在。
 *
 * <p><b>判定纯按距离与重叠里程，不判水体</b>（用户 2026-11-08 明确：「按照范围来算，
 * 一个桥梁距离另外一个多远没事」）。两个条件同时满足才算同一跨越：
 * <ol>
 *   <li>{@code minDistance <= radius}（默认 {@value #DEFAULT_RADIUS} 格）</li>
 *   <li>{@code overlapStations >= minOverlapStations}（默认 {@value #DEFAULT_MIN_OVERLAP_STATIONS} 站）</li>
 * </ol>
 *
 * <p><b>为什么要有第二个条件</b>：只按最短距离会误伤「擦肩而过」—— 两条路各过各的河，
 * 桥头只在一角相遇 1~2 站，也会被并成一座，反而制造不必要的绕行。加重叠里程后：
 * 你截图那两座（重叠 60+ 站）必吸，只在桥头点一下的不动。
 *
 * <p>纯几何、无世界访问，可在纯 JUnit 里跑。{@code stations} 传站的 {@code middlePos()} 序列。
 */
public final class BridgeShareDetector {

    /** 默认吸附半径（格）。两条桥 XZ 最短距离小于它就视为同一跨越。 */
    public static final int DEFAULT_RADIUS = 24;
    /** 默认最小重叠站数。低于它视为「擦肩」，不吸附。 */
    public static final int DEFAULT_MIN_OVERLAP_STATIONS = 6;
    /** 对侧折线的采样上限；另一侧全量扫，避免 O(n·m) 打满。 */
    private static final int MAX_SAMPLES = 64;

    /** 默认最小平行度（|cos|）。两条桥方向夹角 &gt; ~25° 就不算「并行双桥」，不吸附。 */
    public static final double DEFAULT_MIN_PARALLEL_DOT = 0.90D;

    private BridgeShareDetector() {}

    /** 桥共享报告同时保留距离与连续重叠证据，避免单布尔值掩盖拒绝原因。
 *
     * @param shares         是否应吸附成一条桥
     * @param minDistance    两条折线的 XZ 最短距离（格，四舍五入），无站时为 {@link Integer#MAX_VALUE}
     * @param overlapStations 沿路方向的重叠里程（站数）
     */
    public record Report(boolean shares, int minDistance, int overlapStations) {
        static Report none() {
            return new Report(false, Integer.MAX_VALUE, 0);
        }
    }

    public static Report detect(List<BlockPos> a, List<BlockPos> b) {
        return detect(a, b, DEFAULT_RADIUS, DEFAULT_MIN_OVERLAP_STATIONS);
    }

    public static Report detect(List<BlockPos> a, List<BlockPos> b, int radius, int minOverlapStations) {
        if (a == null || b == null || a.size() < 2 || b.size() < 2) return Report.none();
        if (!mayOverlap(a, b, radius)) return Report.none();
        return detectOverlapping(a, b, radius, minOverlapStations);
    }

    // 批合并已经用缓存的包围盒预筛；这里直接精判，避免再次扫描桥站。
    static Report detectOverlapping(List<BlockPos> a, List<BlockPos> b, int radius, int minOverlapStations) {
        int limit = Math.max(1, radius);
        List<BlockPos> bSamples = sample(b, MAX_SAMPLES);
        List<BlockPos> aSamples = sample(a, MAX_SAMPLES);

        // 最近距离本来就要遍历全部站点对，同次收集重叠数可省去第二遍距离计算。
        // 两个方向仍独立采样，避免长桥的稀疏样本改变原来的较小距离与较大重叠口径。
        DistanceScan forward = scanDistances(a, bSamples, limit);
        DistanceScan backward = scanDistances(b, aSamples, limit);
        int minDistance = Math.min(forward.minDistance(), backward.minDistance());
        int overlap = Math.max(forward.overlapStations(), backward.overlapStations());

        boolean shares = minDistance <= limit
                && overlap >= Math.max(1, minOverlapStations)
                // 用户 2026-11-08：只抓「两条离得近的**并行**桥」——
                // 方向差别大的是两条路各自过河/立体交叉，不该并成一座。
                && parallelDot(a, b) >= DEFAULT_MIN_PARALLEL_DOT;
        return new Report(shares, minDistance, overlap);
    }

    /**
     * 两个 span 的总体航向平行度（|cos|，0~1）。按段长加权求和再归一化，
     * 弯曲桥也稳定；只用首末点会被中间弯折骗到。
     */
    public static double parallelDot(List<BlockPos> a, List<BlockPos> b) {
        double[] da = averageHeading(a);
        double[] db = averageHeading(b);
        if (da == null || db == null) return 0.0D;
        return Math.abs(da[0] * db[0] + da[1] * db[1]);
    }

    private static double[] averageHeading(List<BlockPos> stations) {
        if (stations == null || stations.size() < 2) return null;
        double sx = 0.0D;
        double sz = 0.0D;
        for (int i = 1; i < stations.size(); i++) {
            BlockPos p = stations.get(i - 1);
            BlockPos q = stations.get(i);
            if (p == null || q == null) continue;
            sx += q.getX() - p.getX();
            sz += q.getZ() - p.getZ();
        }
        double len = Math.hypot(sx, sz);
        if (len < 1e-9D) return null;
        return new double[]{sx / len, sz / len};
    }

    /**
     * **廉价预筛**（用户 2026-11-08：「规划完成了后扫描只扫描桥梁离的近的，不要全部扫描」）。
     *
     * <p>两盒在任一轴上的间隔大于半径时，精判必然不通过。独立调用计算两盒；
     * 批合并先按跨度计算，再对每对执行常数次比较。
     */
    public static boolean mayOverlap(List<BlockPos> a, List<BlockPos> b, int radius) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return overlapsPrepared(bbox(a), bbox(b), radius);
    }

    // 半径边界也参与精判，不能因改用包围盒缓存漏掉刚好相隔半径的桥。
    static boolean overlapsPrepared(Bounds a, Bounds b, int radius) {
        int expand = Math.max(0, radius);
        return a.minX() - expand <= b.maxX() && b.minX() - expand <= a.maxX()
                && a.minZ() - expand <= b.maxZ() && b.minZ() - expand <= a.maxZ();
    }

    record Bounds(int minX, int minZ, int maxX, int maxZ) {}

    static Bounds bbox(List<BlockPos> stations) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : stations) {
            if (p == null) continue;
            int x = p.getX();
            int z = p.getZ();
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;
        }
        if (minX == Integer.MAX_VALUE) return new Bounds(0, 0, -1, -1);
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    /** 等间隔取至多 {@code max} 个点，首末必取。 */
    static List<BlockPos> sample(List<BlockPos> stations, int max) {
        List<BlockPos> out = new ArrayList<>(Math.min(max, stations.size()));
        if (stations.size() <= max) {
            out.addAll(stations);
            return out;
        }
        for (int i = 0; i < max; i++) {
            int index = (int) Math.round((double) i * (stations.size() - 1) / (max - 1));
            out.add(stations.get(Math.min(stations.size() - 1, index)));
        }
        return out;
    }

    /** 同次扫描的几何统计，不携带世界对象或跨批次缓存。 */
    record DistanceScan(int minDistance, int overlapStations) {}

    /** 每站最近距离与全局最近距离共用一次遍历，保留原开方、四舍五入和闭区间判据。 */
    static DistanceScan scanDistances(List<BlockPos> whole, List<BlockPos> samples, int limit) {
        double best = Double.MAX_VALUE;
        int overlap = 0;
        for (BlockPos p : whole) {
            boolean touches = false;
            for (BlockPos q : samples) {
                double d = xzDistance(p, q);
                if (d < best) best = d;
                if (d <= limit) touches = true;
            }
            if (touches) overlap++;
        }
        int minimum = best == Double.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(best);
        return new DistanceScan(minimum, overlap);
    }

    private static double xzDistance(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dz = (double) a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
