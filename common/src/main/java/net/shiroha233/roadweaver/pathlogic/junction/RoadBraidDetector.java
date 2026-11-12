/* 文件职责：全批次两两「并排重叠（麻花）」判定——只读、无世界访问、可单测。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全批次两两「并排重叠」预检（0.9.018，真机缺陷 D2 平行双桥）。
 *
 * <h2>为什么需要这个独立判定</h2>
 * <p>已有的麻花判定 {@code RoadSnapService.detectsBraid} 只在 {@code tryConnect}
 * 内部执行，而 {@code tryConnect} 的前提是「后一条路的端点被前一条路的端点走廊
 * 吸引过来」。于是有两条互不贴靠、各走各的平行路时：</p>
 * <ol>
 *   <li>它们不会互相吸引（端点相距很远），{@code tryConnect} 一次都不执行；</li>
 *   <li>{@code selectsSingleNetworkJunction} 返回自己，{@code merged} 计数不增加；
 *       真机日志 49 条路只 {@code merged=12} 就是这个意思；</li>
 *   <li>两条路各修各的桥 ⇒ 地图上一对平行双桥。</li>
 * </ol>
 *
 * <p>所以 D2 的修复必须在「谁去合并谁」之前做一次 <b>无条件的两两扫描</b>，
 * 不依赖端点吸引。本类只负责判定与给出证据，改道动作由
 * {@link ExistingRoadJunctionService} 执行。</p>
 *
 * <h2>判据（与 {@code RoadSnapService} 保持同一套口径）</h2>
 * <ul>
 *   <li>站距：沿后一条路逐站找前一条路的空间最近站，用 <b>切比雪夫</b> 距离
 *       （与 {@code markSnapTargets}／{@code parallelCorridor} 同口径），
 *       上限 {@value RoadConstants#ROAD_SNAP_THRESHOLD} 格；</li>
 *   <li>连通段：连续 ≥ {@value #MIN_RUN} 站落在上限内才算一段；</li>
 *   <li>方向：在段上等距取 {@value #HEADING_SAMPLES} 个样，算两侧局部走向的
 *       <b>点积绝对值</b>，多数样达标即认为并排。</li>
 * </ul>
 *
 * <h2>与 {@code detectsBraid} 的关键差异：不排除「完全平行」</h2>
 * <p>{@code detectsBraid} 把点积 {@code > braidNearParallelDot(0.90)} 的情况
 * 判为「明确平行、{@code parallelCorridor} 会合并」，于是不报麻花。这个排除
 * 在「已经在尝试合并」的上下文里说得通，但对本类要回答的问题正好相反：
 * 点积越接近 1，两座桥越是并排、越是必须改道。所以这里只在
 * {@code dot ≥ braidMinDirectionDot} 这一侧设下限，不设上限。</p>
 *
 * <p>方向多数票而不是取最小样，是有前车之鉴的：0.8.369 的 T2b 记录里，
 * 长距离山间走廊本身在弯，取单点／最坏点会把真实案例否掉 23 次／局。
 * 这里沿用 {@code parallelCorridor} 的多数票语义。</p>
 */
public final class RoadBraidDetector {
    /** 连续多少站才算「一整段并排」。与 {@code OVERLAP_MERGE_MIN_RUN} 一致。 */
    public static final int MIN_RUN = 6;
    /** 方向采样点数，与 {@code PARALLEL_CORRIDOR_HEADING_SAMPLES} 一致。 */
    public static final int HEADING_SAMPLES = 5;
    /** 站距上限，与 {@code OVERLAP_MERGE_MAX_DISTANCE}／{@code ROAD_SNAP_THRESHOLD} 一致。 */
    public static final int MAX_DISTANCE = RoadConstants.ROAD_SNAP_THRESHOLD;
    /** 默认方向点积下限，与 {@code DEFAULT_BRAID_MIN_DIRECTION_DOT} 一致。 */
    public static final double MIN_DOT = RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT;

    private static final int INDEX_CELL = 16;

    private RoadBraidDetector() {
    }

    /**
     * 一条并排重叠的判定结果。
     *
     * @param braided       是否认定并排重叠（调用方据此决定是否改道）
     * @param primaryFrom   前一条路上重叠段的起始站下标（禁止走廊就取这一段）
     * @param primaryTo     前一条路上重叠段的结束站下标（含）
     * @param secondaryFrom 后一条路上重叠段的起始站下标
     * @param secondaryTo   重叠段的结束站下标（含）
     * @param stations      重叠段站点数
     * @param samples       实际参与方向投票的采样数（0 表示没取到方向）
     * @param parallelVotes 其中点积达标的采样数
     * @param worstDot      采样中的最小点积（诊断用，不参与判定）
     * @param medianDistance 重叠段站距的中位数（诊断用）
     * @param detail        不成立时的原因短标签，成立时为 {@code "braid"}
     */
    public record Report(boolean braided,
                         int primaryFrom,
                         int primaryTo,
                         int secondaryFrom,
                         int secondaryTo,
                         int stations,
                         int samples,
                         int parallelVotes,
                         double worstDot,
                         int medianDistance,
                         String detail) {
        static Report none(String detail) {
            return new Report(false, -1, -1, -1, -1, 0, 0, 0, 1.0D, -1, detail);
        }

        public boolean coversLastStation(int secondaryStationCount) {
            return braided && secondaryTo >= secondaryStationCount - 1;
        }
    }

    /** 用当前配置的 {@code braidMinDirectionDot} 与 {@code ROAD_SNAP_THRESHOLD} 判定。 */
    public static Report compare(RoadData primary, RoadData secondary) {
        double minDot = MIN_DOT;
        int maxDistance = MAX_DISTANCE;
        try {
            PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
            if (cfg != null) {
                minDot = cfg.braidMinDirectionDot();
            }
        } catch (RuntimeException ignored) {
            // 配置服务在纯单测环境里可能未初始化；退回编译期默认值，不改变判定能力。
        }
        return compare(primary, secondary, minDot, maxDistance);
    }

    /**
     * 判定 {@code secondary} 是否在某一整段上与 {@code primary} 并排重叠。
     *
     * <p>只读：两个入参都不会被修改。两个列表都为 {@code null} 或过短时返回
     * {@link Report#none(String)}，调用方不需要额外判空。</p>
     */
    public static Report compare(RoadData primary, RoadData secondary, double minDot, int maxDistance) {
        return compareStations(primary == null ? null : primary.roadSegmentList(),
                secondary == null ? null : secondary.roadSegmentList(), minDot, maxDistance);
    }

    /**
     * 0.9.083：规划期重载 —— 直接吃路径点序列（那时还没有 RoadData）。
     *
     * <p>把每个 {@code BlockPos} 包成一个只带 middlePos 的
     * {@link RoadSegmentPlacement} 再交给同一份判定代码。这样「规划期判」与
     * 「生成期判」用的是<b>同一套空间索引 + 局部走向 + 连续窗口</b>，
     * 不存在两套判据漂移的风险。</p>
     */
    public static Report comparePaths(List<BlockPos> primaryPath, List<BlockPos> secondaryPath) {
        double minDot = MIN_DOT;
        try {
            PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
            if (cfg != null) minDot = cfg.braidMinDirectionDot();
        } catch (RuntimeException ignored) {
            // 纯单测环境里配置服务可能未初始化，退回编译期默认值。
        }
        return compareStations(toStations(primaryPath), toStations(secondaryPath), minDot, MAX_DISTANCE);
    }

    /** 路径点序列 → 只带 middlePos 的站点序列（positions 填单点，判定不读它）。 */
    static List<RoadSegmentPlacement> toStations(List<BlockPos> path) {
        if (path == null) return null;
        List<RoadSegmentPlacement> out = new ArrayList<>(path.size());
        for (BlockPos point : path) {
            out.add(point == null ? null : new RoadSegmentPlacement(point, List.of(point)));
        }
        return out;
    }

    /** 判定主体：入参是两个站点序列，{@code RoadData} 版本与路径版本共用这一份。 */
    public static Report compareStations(List<RoadSegmentPlacement> pri, List<RoadSegmentPlacement> sec,
                                         double minDot, int maxDistance) {
        if (pri == null || sec == null) return Report.none("missing");
        if (pri.size() < MIN_RUN || sec.size() < MIN_RUN) return Report.none("too-short");

        StationIndex index = StationIndex.build(pri);
        if (index.isEmpty()) return Report.none("no-usable-primary-stations");

        int reach = Math.max(1, maxDistance);
        int[] targets = new int[sec.size()];
        Arrays.fill(targets, -1);
        for (int i = 0; i < sec.size(); i++) {
            BlockPos pos = middlePos(sec, i);
            if (pos == null) continue;
            targets[i] = index.nearest(pos.getX(), pos.getZ(), reach);
        }

        Report best = Report.none("no-overlap-run");
        for (int[] run : extractRuns(targets, sec.size())) {
            Report candidate = inspectRun(pri, sec, targets, run[0], run[1], minDot, reach);
            if (candidate.braided() && candidate.stations() > best.stations()) {
                best = candidate;
            } else if (!best.braided() && candidate.stations() > best.stations()) {
                best = candidate;
            }
        }
        return best;
    }

    /** 取出一段连续 {@code targets >= 0} 且长度达标的窗口。 */
    static List<int[]> extractRuns(int[] targets, int size) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < size; i++) {
            boolean inside = targets[i] >= 0;
            if (inside) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (i - start >= MIN_RUN) runs.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        if (start >= 0 && size - start >= MIN_RUN) runs.add(new int[]{start, size - 1});
        return runs;
    }

    private static Report inspectRun(List<RoadSegmentPlacement> pri,
                                     List<RoadSegmentPlacement> sec,
                                     int[] targets,
                                     int from,
                                     int to,
                                     double minDot,
                                     int maxDistance) {
        int count = to - from + 1;
        if (count < MIN_RUN) return Report.none("run-too-short");
        int[] distances = new int[count];
        int primaryFrom = Integer.MAX_VALUE;
        int primaryTo = Integer.MIN_VALUE;
        int stride = Math.max(1, count / HEADING_SAMPLES);
        int samples = 0;
        int parallelVotes = 0;
        double worstDot = 1.0D;
        for (int si = from; si <= to; si++) {
            BlockPos a = middlePos(sec, si);
            int pi = targets[si];
            BlockPos b = middlePos(pri, pi);
            if (pi >= 0) {
                primaryFrom = Math.min(primaryFrom, pi);
                primaryTo = Math.max(primaryTo, pi);
            }
            if (a == null || b == null) {
                distances[si - from] = maxDistance;
            } else {
                distances[si - from] = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
            }
            if ((si - from) % stride != 0) continue;
            double[] secondaryDirection = direction(sec, si);
            double[] primaryDirection = direction(pri, pi);
            if (secondaryDirection == null || primaryDirection == null) continue;
            samples++;
            double dot = Math.abs(secondaryDirection[0] * primaryDirection[0]
                    + secondaryDirection[1] * primaryDirection[1]);
            worstDot = Math.min(worstDot, dot);
            if (dot >= minDot) parallelVotes++;
        }
        if (primaryFrom > primaryTo) {
            primaryFrom = -1;
            primaryTo = -1;
        }
        int medianDistance = median(distances);
        // 方向取不到（几乎没有）时按站距成立——站距本身就是更强的证据。
        boolean braided = samples == 0 || parallelVotes * 2 >= samples;
        return new Report(braided, primaryFrom, primaryTo, from, to, count, samples, parallelVotes,
                worstDot, medianDistance, braided ? "braid" : "not-parallel");
    }

    private static int median(int[] values) {
        if (values.length == 0) return -1;
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static BlockPos middlePos(List<RoadSegmentPlacement> stations, int index) {
        if (stations == null || index < 0 || index >= stations.size()) return null;
        RoadSegmentPlacement station = stations.get(index);
        return station == null ? null : station.middlePos();
    }

    /**
     * 局部走向：与 {@code RoadSnapService.segmentDirection} 同口径（前后各 2 站的连线），
     * 这样同一个路段在两侧算出来的方向可比。
     */
    static double[] direction(List<RoadSegmentPlacement> stations, int index) {
        if (stations == null || stations.isEmpty()) return null;
        int previous = Math.max(0, index - 2);
        int next = Math.min(stations.size() - 1, index + 2);
        if (previous == next) return null;
        BlockPos a = middlePos(stations, previous);
        BlockPos b = middlePos(stations, next);
        if (a == null || b == null) return null;
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0D) return null;
        return new double[]{dx / length, dz / length};
    }

    /**
     * 站点空间索引：按 {@value #INDEX_CELL} 格的桶做「半径内最近站」查询。
     *
     * <p>因为站距上限只有 12 格、桶是 16 格，只需要查所在桶与相邻 8 个桶。
     * 站数在千级、桶数在百级，这一层完全不构成耗时。</p>
     */
    private static final class StationIndex {
        private final Map<Long, List<Integer>> buckets;
        private final List<RoadSegmentPlacement> stations;

        private StationIndex(Map<Long, List<Integer>> buckets, List<RoadSegmentPlacement> stations) {
            this.buckets = buckets;
            this.stations = stations;
        }

        static StationIndex build(List<RoadSegmentPlacement> stations) {
            Map<Long, List<Integer>> buckets = new HashMap<>();
            for (int i = 0; i < stations.size(); i++) {
                BlockPos pos = middlePos(stations, i);
                if (pos == null) continue;
                buckets.computeIfAbsent(key(pos.getX() >> 4, pos.getZ() >> 4),
                        ignored -> new ArrayList<>(4)).add(i);
            }
            return new StationIndex(buckets, stations);
        }

        boolean isEmpty() {
            return buckets.isEmpty();
        }

        /** 返回切比雪夫距离 ≤ {@code reach} 的最近站下标，找不到返回 -1。 */
        int nearest(int x, int z, int reach) {
            int cx = x >> 4;
            int cz = z >> 4;
            int bestIndex = -1;
            int bestDistance = reach + 1;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> bucket = buckets.get(key(cx + dx, cz + dz));
                    if (bucket == null) continue;
                    for (int index : bucket) {
                        BlockPos pos = middlePos(stations, index);
                        if (pos == null) continue;
                        int distance = Math.max(Math.abs(pos.getX() - x), Math.abs(pos.getZ() - z));
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestIndex = index;
                        }
                    }
                }
            }
            return bestDistance <= reach ? bestIndex : -1;
        }

        private static long key(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }
    }
}
