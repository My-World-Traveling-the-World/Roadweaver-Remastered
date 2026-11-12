package net.shiroha233.roadweaver.pathlogic.metrics;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路面质量指标（D1 的「指标」半边）。
 *
 * <p>这些数是「一条路好不好」的客观断言，全部只读 {@link RoadData}，不碰世界 —— 所以它们能在纯
 * JUnit 里跑，也能在 GameTest 里对着真实生成结果跑。之所以先做指标再做 GameTest：一个没有指标的
 * GameTest 只能断言「没抛异常」，挡不住「路悄悄变窄/变抖/出现台阶」这类退化。
 *
 * <p>每一项都对应一类真机见过的缺陷：
 * <ul>
 *   <li>{@code widthVariation} —— 斜向足迹比轴向宽（C2 之前的胶囊足迹）</li>
 *   <li>{@code holesPerThousand} —— 某一站整段没有方块（保护缺口/编译失败留下的洞）</li>
 *   <li>{@code continuityBreaks} —— 相邻站高差 &gt; 1（不可走，桥/隧道另算）</li>
 *   <li>{@code gradeP95} / {@code directionGradeBias} —— 坡度毛刺与「斜向更陡」</li>
 *   <li>{@code reuseShare} —— 平行走廊去重到底有没有生效</li>
 *   <li>{@code bridgeDeckJumps} / {@code junctionGradeSteps} —— 桥面台阶、路口一格一台阶</li>
 *   <li>{@code spacingRegularity} —— 站间距是否被拉成长短不一</li>
 * </ul>
 */
public final class RoadQualityMetrics {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("roadweaver");
    /** Roads measured this session, and the running sums the rollup line reports. */
    private static final java.util.concurrent.atomic.AtomicInteger ROADS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_BREAKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_HOLES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_CULVERTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.DoubleAdder WIDTH_VARIATION =
            new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.DoubleAdder GRADE_P95 =
            new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.DoubleAdder DIRECTION_BIAS =
            new java.util.concurrent.atomic.DoubleAdder();
    private static final java.util.concurrent.atomic.DoubleAdder REUSE_SHARE =
            new java.util.concurrent.atomic.DoubleAdder();

    private RoadQualityMetrics() {
    }

    /**
     * 0.9.027：把每个跨度的世界坐标打出来。
     *
     * <p>起因是真机上「某处有座并排双桥」根本没法从日志反查 —— 实测
     * {@code (lines where center= && bridge).count = 0}：所有桥相关行只有连接端点加
     * {@code spans=N tunnel=N bridge=N} 计数，而隧道那边是有 {@code center=x,z} 的。
     * 于是「给个坐标 → 这是哪条路的哪一段」这条路在桥上完全断掉。
     *
     * <p>这里一次把「这条路的每个跨度」摊开：类型、站号区间、起止世界坐标、路宽、桥面高度。
     * 桥用 {@code [ROAD-BRIDGE-SPAN]}，其余跨度用 {@code [ROAD-SPAN-LOC]}，方便直接 grep。
     * 纯日志、只读 {@link RoadData}，不碰世界。
     */
    private static void logSpanLocations(RoadData road, Object from, Object to) {
        List<RoadSpan> spans = road.spans();
        if (spans == null || spans.isEmpty()) return;
        List<RoadSegmentPlacement> stations = road.roadSegmentList();
        int index = 0;
        for (RoadSpan span : spans) {
            if (span == null) {
                index++;
                continue;
            }
            SpanType type = span.type();
            boolean interesting = type == SpanType.BRIDGE || type == SpanType.TUNNEL
                    || type == SpanType.TUNNEL_JUNCTION || type == SpanType.PROTECTED_GAP
                    || type == SpanType.CULVERT || type == SpanType.ROAD_CROSSING;
            if (!interesting) {
                index++;
                continue;
            }
            String tag = type == SpanType.BRIDGE ? "[ROAD-BRIDGE-SPAN]" : "[ROAD-SPAN-LOC]";
            int fromStation = stationIndexOf(stations, span.start());
            int toStation = stationIndexOf(stations, span.end());
            int deckY = span.start() == null ? 0 : span.start().getY();
            LOGGER.info("RoadWeaver {} connection={}->{} span={} type={} stations={}..{} "
                            + "start={} end={} width={} deckY={}",
                    tag, from, to, index, type, fromStation, toStation,
                    pos(span.start()), pos(span.end()), road.width(), deckY);
            index++;
        }
    }

    /**
     * 0.9.061：盘山路度量（{@link SwitchbackMetrics}，纯几何只读）。
     *
     * <p>单独一行 {@code route SWITCHBACK}，紧跟在 {@code route QUALITY} 之后：
     * 两行对照就能回答「这条路的坡度是多少 / 它到底是盘上去的还是斜着硬爬上去的」。
     * 度量任何异常都吞掉 —— 取证日志绝不能影响生成。</p>
     */
    private static void logSwitchback(RoadData road, Object from, Object to) {
        try {
            LOGGER.info("RoadWeaver route SWITCHBACK connection={}->{} {}",
                    from, to, SwitchbackMetrics.fromRoad(road).format());
        } catch (RuntimeException ignored) {
            // 只读度量，失败就少一行日志。
        }
    }

    /** 线性找站号；找不到返回 -1（跨度端点被裁短过时不猜）。 */
    private static int stationIndexOf(List<RoadSegmentPlacement> stations, BlockPos target) {
        if (stations == null || target == null) return -1;
        for (int i = 0; i < stations.size(); i++) {
            RoadSegmentPlacement placement = stations.get(i);
            if (placement != null && target.equals(placement.middlePos())) return i;
        }
        return -1;
    }

    private static String pos(BlockPos pos) {
        return pos == null ? "(none)" : "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    /**
     * Measures one finished road, logs its numbers, and every tenth road logs the session rollup.
     *
     * <p>The per-road line answers "is this road right"; the rollup answers "is the generator drifting
     * across a session", which a single road cannot show. Both are read-only.
     */
    public static Report record(RoadData road, Object from, Object to) {
        Report report = evaluate(road);
        LOGGER.info("RoadWeaver route QUALITY connection={}->{} {}", from, to, report.format());
        logSwitchback(road, from, to);
        logSpanLocations(road, from, to);
        int roads = ROADS.incrementAndGet();
        TOTAL_BREAKS.addAndGet(report.continuityBreaks());
        TOTAL_HOLES.addAndGet(report.holes());
        TOTAL_CULVERTS.addAndGet(report.culvertRuns());
        WIDTH_VARIATION.add(report.widthVariation());
        GRADE_P95.add(report.gradeP95());
        DIRECTION_BIAS.add(report.directionGradeBias());
        REUSE_SHARE.add(report.reuseShare());
        if (roads % 10 == 0) {
            LOGGER.info("RoadWeaver quality ROLLUP roads={} avgWidthVar={} totalBreaks={} totalHoles={} "
                            + "avgGradeP95={} avgDirBias={} avgReuse={} culverts={}",
                    roads,
                    String.format(java.util.Locale.ROOT, "%.2f", WIDTH_VARIATION.sum() / roads),
                    TOTAL_BREAKS.get(), TOTAL_HOLES.get(),
                    String.format(java.util.Locale.ROOT, "%.3f", GRADE_P95.sum() / roads),
                    String.format(java.util.Locale.ROOT, "%.3f", DIRECTION_BIAS.sum() / roads),
                    String.format(java.util.Locale.ROOT, "%.3f", REUSE_SHARE.sum() / roads),
                    TOTAL_CULVERTS.get());
        }
        return report;
    }

    /**
     * @param widthVariation     每站足迹宽度（沿 x/z 的最大跨度 + 1）的标准差，0 = 宽窄完全一致
     * @param holes              没有任何放置位置的站数
     * @param continuityBreaks   相邻普通站之间高差 &gt; 1 的次数（桥/隧道/路口跨度不计）
     * @param gradeP95           坡度绝对值的 95 分位（|ΔY| / 水平距离）
     * @param directionGradeBias 轴向平均坡度与斜向平均坡度之差（米/米），C2 要消灭的就是它
     * @param reuseShare         被 NETWORK_REUSE 覆盖的站占比
     * @param bridgeDeckJumps    相邻桥面站之间高差 &gt; 1 的次数
     * @param junctionGradeSteps 路口跨度内高差 &gt; 1 的相邻对数量
     * @param spacingRegularity  站间距 p95 / 最小间距（1.0 = 完全均匀）
     * @param culvertRuns        CULVERT 跨度数量
     * @param tunnelShare        被 TUNNEL 覆盖的站占比
     * @param seamSteps          台阶正好落在**特殊跨度接缝**上的次数（0.9.027 / R-10）。
     *                           判据是「相邻两站里有一站在桥上/洞里/路口上、另一站不在」且 |ΔY|&gt;1。
     *                           这类台阶以前既不算进 {@code breaks}（特殊跨度被排除），
     *                           也因为两端都被掩码盖住而看不见 —— 正是「桥口/洞口的接缝台阶」。
     * @param gradeP95Land       只统计**两端都不是特殊跨度**的相邻对的坡度 p95（0.9.027 / R-10）。
     *                           注意 {@code gradeP95} 本来就不排除特殊跨度，也就是说它**已经是
     *                           台账里说的 "gradeP95All"**；缺的是「陆地路面本身有多陡」这一半。
     */
    public record Report(int stations,
                         double widthVariation,
                         int holes,
                         int continuityBreaks,
                         double gradeP95,
                         double directionGradeBias,
                         double reuseShare,
                         int bridgeDeckJumps,
                         int junctionGradeSteps,
                         double spacingRegularity,
                         int culvertRuns,
                         double tunnelShare,
                         double bridgeShare,
                         double crossingShare,
                         double spanLengthP95,
                         int spanGaps,
                         int unboundSpans,
                         int duplicateXzStations,
                         int seamSteps,
                         double gradeP95Land,
                         String spanDetail) {

        /** A single line for the log: every number on it is a regression guard. */
        public String format() {
            return String.format(java.util.Locale.ROOT,
                    "stations=%d widthVar=%.2f holes=%d breaks=%d seamSteps=%d gradeP95=%.3f gradeP95Land=%.3f "
                            + "dirBias=%.3f "
                            + "reuse=%.3f bridgeJumps=%d junctionSteps=%d spacing=%.2f culverts=%d tunnels=%.3f "
                            + "bridges=%.3f crossings=%.3f spanLenP95=%.1f spanGaps=%d unboundSpans=%d dupXz=%d "
                            + "spanDetail=%s",
                    stations, widthVariation, holes, continuityBreaks, seamSteps, gradeP95, gradeP95Land,
                    directionGradeBias,
                    reuseShare, bridgeDeckJumps, junctionGradeSteps, spacingRegularity, culvertRuns, tunnelShare,
                    bridgeShare, crossingShare, spanLengthP95, spanGaps, unboundSpans, duplicateXzStations,
                    spanDetail);
        }
    }

    public static Report evaluate(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()) {
            return new Report(0, 0.0D, 0, 0, 0.0D, 0.0D, 0.0D, 0, 0, 1.0D, 0, 0.0D,
                    0.0D, 0.0D, 0.0D, 0, 0, 0, 0, 0.0D, "[]");
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int size = segments.size();
        Bound bound = masks(road, size);
        Map<SpanType, boolean[]> masks = bound.masks();
        boolean[] bridge = masks.get(SpanType.BRIDGE);
        boolean[] tunnel = masks.get(SpanType.TUNNEL);
        boolean[] crossing = masks.get(SpanType.ROAD_CROSSING);
        boolean[] reuse = masks.get(SpanType.NETWORK_REUSE);

        int[] widths = new int[size];
        int holes = 0;
        for (int i = 0; i < size; i++) {
            widths[i] = width(segments.get(i));
            if (widths[i] <= 0) holes++;
        }
        double widthVariation = stdDev(widths);

        int breaks = 0;
        int bridgeJumps = 0;
        int junctionSteps = 0;
        List<Double> grades = new ArrayList<>();
        double axialSum = 0.0D;
        int axialCount = 0;
        double diagonalSum = 0.0D;
        int diagonalCount = 0;
        List<Double> spacings = new ArrayList<>();
        int seamSteps = 0;
        List<Double> landGrades = new ArrayList<>();
        for (int i = 1; i < size; i++) {
            BlockPos previous = segments.get(i - 1).middlePos();
            BlockPos current = segments.get(i).middlePos();
            if (previous == null || current == null) continue;
            int deltaY = Math.abs(road.targetY().get(i) - road.targetY().get(i - 1));
            double horizontal = horizontal(previous, current);
            boolean special = bridge[i] || tunnel[i] || crossing[i] || bridge[i - 1] || tunnel[i - 1];
            // R-10（0.9.027）：接缝台阶专门核一次 —— 相邻两站里「一站在特殊跨度上、另一站不在」，
            // 且高差 > 1。这类台阶原来既不算 breaks（特殊跨度被排除）又两边都被掩码盖住，看不见。
            boolean specialHere = bridge[i] || tunnel[i] || crossing[i];
            boolean specialPrev = bridge[i - 1] || tunnel[i - 1] || crossing[i - 1];
            if (specialHere != specialPrev && deltaY > 1) seamSteps++;
            if (!special && deltaY > 1) breaks++;
            if (bridge[i] && bridge[i - 1] && deltaY > 1) bridgeJumps++;
            if (crossing[i] && crossing[i - 1] && deltaY > 1) junctionSteps++;
            if (horizontal > 0.0D) {
                double grade = deltaY / horizontal;
                grades.add(grade);
                // gradeP95 不排除特殊跨度（已经是「全量」口径）；这里再给一份只算陆地路面的。
                if (!specialHere && !specialPrev) landGrades.add(grade);
                spacings.add(horizontal);
                boolean diagonal = Math.abs(current.getX() - previous.getX()) > 0
                        && Math.abs(current.getZ() - previous.getZ()) > 0;
                if (diagonal) {
                    diagonalSum += grade;
                    diagonalCount++;
                } else {
                    axialSum += grade;
                    axialCount++;
                }
            }
        }
        double bias = 0.0D;
        if (axialCount > 0 && diagonalCount > 0) {
            bias = Math.abs(axialSum / axialCount - diagonalSum / diagonalCount);
        }
        int reuseStations = 0;
        int tunnelStations = 0;
        int bridgeStations = 0;
        int crossingStations = 0;
        for (int i = 0; i < size; i++) {
            if (reuse[i]) reuseStations++;
            if (tunnel[i]) tunnelStations++;
            if (bridge[i]) bridgeStations++;
            if (crossing[i]) crossingStations++;
        }
        int culverts = 0;
        if (road.spans() != null) {
            for (RoadSpan span : road.spans()) {
                if (span != null && span.type() == SpanType.CULVERT) culverts++;
            }
        }
        List<Double> spanLengths = new ArrayList<>();
        for (Integer length : bound.spanLengths()) spanLengths.add(length.doubleValue());
        return new Report(size, widthVariation, holes, breaks, percentile(grades, 0.95D), bias,
                size == 0 ? 0.0D : (double) reuseStations / size, bridgeJumps, junctionSteps,
                spacingRegularity(spacings), culverts, size == 0 ? 0.0D : (double) tunnelStations / size,
                size == 0 ? 0.0D : (double) bridgeStations / size,
                size == 0 ? 0.0D : (double) crossingStations / size,
                percentile(spanLengths, 0.95D), bound.spanGaps(), bound.unboundSpans(),
                bound.duplicateXzStations(), seamSteps, percentile(landGrades, 0.95D), spanDetail(bound));
    }

    /** {@return "[BRIDGE n=2 p95=2.0 max=2 gaps=1 TUNNEL n=1 p95=3.0 max=3 gaps=0]"} */
    private static String spanDetail(Bound bound) {
        if (bound.lengthsByType().isEmpty()) return "[]";
        StringBuilder text = new StringBuilder("[");
        for (Map.Entry<SpanType, List<Integer>> entry : bound.lengthsByType().entrySet()) {
            List<Double> lengths = new ArrayList<>();
            int max = 0;
            for (Integer length : entry.getValue()) {
                lengths.add(length.doubleValue());
                max = Math.max(max, length);
            }
            if (text.length() > 1) text.append(' ');
            text.append(entry.getKey().name())
                    .append(" n=").append(lengths.size())
                    .append(String.format(java.util.Locale.ROOT, " p95=%.1f", percentile(lengths, 0.95D)))
                    .append(" max=").append(max)
                    .append(" gaps=").append(bound.gapsByType().getOrDefault(entry.getKey(), 0));
        }
        return text.append(']').toString();
    }

    /** Span masks plus what could not be bound to a station, so the report can say so. */
    private record Bound(Map<SpanType, boolean[]> masks, int unboundSpans, int duplicateXzStations,
                         List<Integer> spanLengths, int spanGaps,
                         Map<SpanType, List<Integer>> lengthsByType, Map<SpanType, Integer> gapsByType) { }

    /** One station's exact position; the XZ-only key a junction collides on is kept separately. */
    private record StationKey(int x, int y, int z) { }

    private static Bound masks(RoadData road, int size) {
        Map<SpanType, boolean[]> masks = new java.util.EnumMap<>(SpanType.class);
        for (SpanType type : SpanType.values()) masks.put(type, new boolean[size]);
        if (road.spans() == null || road.spans().isEmpty() || size == 0) {
            return new Bound(masks, 0, 0, List.of(), 0, Map.of(), Map.of());
        }
        // Exact (x,y,z) first, XZ as the fallback: a junction puts two stations on one XZ at
        // different heights, and an XZ-only table silently rebinds the span to the wrong deck.
        Map<StationKey, Integer> exact = new java.util.HashMap<>();
        Map<Long, Integer> xz = new java.util.HashMap<>();
        int duplicates = 0;
        for (int i = 0; i < size; i++) {
            BlockPos middle = road.roadSegmentList().get(i).middlePos();
            if (middle == null) continue;
            exact.put(new StationKey(middle.getX(), middle.getY(), middle.getZ()), i);
            if (xz.put(key(middle), i) != null) duplicates++;
        }
        int unbound = 0;
        List<Integer> lengths = new ArrayList<>();
        Map<SpanType, List<int[]>> byType = new java.util.EnumMap<>(SpanType.class);
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() == null || span.start() == null || span.end() == null) continue;
            Integer start = station(exact, xz, span.start());
            Integer end = station(exact, xz, span.end());
            if (start == null || end == null) {
                // A span the station table cannot bind used to be dropped in silence: the mask stayed
                // false and nothing said a bridge existed. Counting it is what makes the gap visible.
                unbound++;
                continue;
            }
            boolean[] mask = masks.get(span.type());
            int lo = Math.min(start, end);
            int hi = Math.max(start, end);
            for (int i = lo; i <= hi; i++) mask[i] = true;
            lengths.add(hi - lo + 1);
            byType.computeIfAbsent(span.type(), ignored -> new ArrayList<>()).add(new int[]{lo, hi});
        }
        // A gap of exactly one station between two spans of the same kind is the signature of a bridge
        // that was cut in two: the road reads as connected but the deck has a hole in it.
        int gaps = 0;
        Map<SpanType, Integer> gapsByType = new java.util.EnumMap<>(SpanType.class);
        Map<SpanType, List<Integer>> lengthsByType = new java.util.EnumMap<>(SpanType.class);
        for (Map.Entry<SpanType, List<int[]>> entry : byType.entrySet()) {
            List<int[]> spans = entry.getValue();
            spans.sort((a, b) -> Integer.compare(a[0], b[0]));
            int typeGaps = 0;
            List<Integer> typeLengths = new ArrayList<>(spans.size());
            for (int i = 0; i < spans.size(); i++) {
                typeLengths.add(spans.get(i)[1] - spans.get(i)[0] + 1);
                if (i > 0 && spans.get(i)[0] - spans.get(i - 1)[1] == 2) typeGaps++;
            }
            gaps += typeGaps;
            gapsByType.put(entry.getKey(), typeGaps);
            lengthsByType.put(entry.getKey(), typeLengths);
        }
        return new Bound(masks, unbound, duplicates, lengths, gaps, lengthsByType, gapsByType);
    }

    /** {@return the station index of a span endpoint: exact (x,y,z) first, XZ as the fallback} */
    private static Integer station(Map<StationKey, Integer> exact, Map<Long, Integer> xz, BlockPos position) {
        Integer hit = exact.get(new StationKey(position.getX(), position.getY(), position.getZ()));
        return hit != null ? hit : xz.get(key(position));
    }

    private static int width(RoadSegmentPlacement segment) {
        if (segment == null || segment.positions() == null || segment.positions().isEmpty()) return 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos position : segment.positions()) {
            if (position == null) continue;
            minX = Math.min(minX, position.getX());
            maxX = Math.max(maxX, position.getX());
            minZ = Math.min(minZ, position.getZ());
            maxZ = Math.max(maxZ, position.getZ());
        }
        if (minX == Integer.MAX_VALUE) return 0;
        return Math.max(maxX - minX, maxZ - minZ) + 1;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double stdDev(int[] values) {
        if (values.length == 0) return 0.0D;
        double mean = 0.0D;
        for (int value : values) mean += value;
        mean /= values.length;
        double sum = 0.0D;
        for (int value : values) {
            double delta = value - mean;
            sum += delta * delta;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return 0.0D;
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static double spacingRegularity(List<Double> spacings) {
        if (spacings.isEmpty()) return 1.0D;
        double min = Double.MAX_VALUE;
        for (double spacing : spacings) min = Math.min(min, spacing);
        if (min <= 0.0D) return 1.0D;
        return percentile(spacings, 0.95D) / min;
    }

    private static long key(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
