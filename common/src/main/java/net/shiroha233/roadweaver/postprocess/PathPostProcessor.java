/* 文件职责：对道路路径执行精确高度修正、平滑与桥梁后处理。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.*;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.isWaterLike;
import static net.shiroha233.roadweaver.postprocess.SplineHelper.*;
import static net.shiroha233.roadweaver.postprocess.BridgeMaskTool.*;

/**
 * 路径后处理器
 */
public final class PathPostProcessor {
    static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("roadweaver");
    /**
     * How far the planned road may stand above the sampled ground before the span counts as a viaduct.
     *
     * <p>Sixteen matches the depth the span extractor already uses to force a dry depression into a
     * bridge, so the post-processor and the span classifier agree on what a gorge is.
     */
    /** Columns the height backend refused; see the guard in detectBridgeMask. */
    static final java.util.concurrent.atomic.AtomicLong MISSING_HEIGHTS =
            new java.util.concurrent.atomic.AtomicLong();

    static final int DRY_VIADUCT_MIN_DROP = 16;

    private PathPostProcessor() {}

    public static List<RoadSegmentPlacement> process(List<BlockPos> rawPath, int width,
                                                     ServerLevel level, TerrainSamplingCache cache,
                                                     int bridgeMinWaterDepth) {
        return process(rawPath, width, level, cache, bridgeMinWaterDepth, CurveMode.CATMULL_ROM, null);
    }

    public static List<RoadSegmentPlacement> process(List<BlockPos> rawPath, int width,
                                                     ServerLevel level, TerrainSamplingCache cache,
                                                     int bridgeMinWaterDepth,
                                                     AccurateHeightSampler accurate) {
        return process(rawPath, width, level, cache, null, bridgeMinWaterDepth, CurveMode.CATMULL_ROM, accurate);
    }

    public static List<RoadSegmentPlacement> process(List<BlockPos> rawPath, int width,
                                                     ServerLevel level, TerrainSamplingCache cache,
                                                     int bridgeMinWaterDepth,
                                                     CurveMode curveMode,
                                                     AccurateHeightSampler accurate) {
        return process(rawPath, width, level, cache, null, bridgeMinWaterDepth, curveMode, accurate);
    }

    public static List<RoadSegmentPlacement> process(List<BlockPos> rawPath, int width,
                                                     ServerLevel level, TerrainSamplingCache cache,
                                                     PathTerrainField terrain,
                                                     int bridgeMinWaterDepth,
                                                     CurveMode curveMode,
                                                     AccurateHeightSampler accurate) {
        if (rawPath == null || rawPath.size() < 2) return new ArrayList<>();

        int[] rawTargetY = new int[rawPath.size()];
        for (int i = 0; i < rawPath.size(); i++) {
            rawTargetY[i] = rawPath.get(i).getY();
        }

        AccurateHeightSampler sampler = accurate != null
                ? accurate
                : (cache != null ? cache.getAccurateSampler(level) : AccurateHeightSampler.create(level));

        int gridStep = deriveGridStep(rawPath);
        List<BlockPos> simplified = simplifyPath(rawPath, gridStep);
        boolean[] bridgeMask = detectBridgeMask(simplified, level, cache, terrain, bridgeMinWaterDepth, sampler);
        List<BlockPos> straightened = straightenBridgeRuns(simplified, bridgeMask);
        List<BlockPos> controlPoints = relaxPathSkippingBridge(straightened, bridgeMask);
        if (controlPoints.size() < 2) return new ArrayList<>();

        // 0.9.019（O-03）：样条之前把控制点按 ≤ 步距密化。
        //
        // 0.9.016 把 simplifyPath 换成 Douglas-Peucker 之后，控制点变成**非均匀**
        // 疏密（长直段只剩两端两点、弯道处密集），而 Catmull-Rom 是按「每段参数
        // t∈[0,1]」均匀采样的 —— 结点切向取 (P_{i+1} − P_{i-1})/2，在「长直段接
        // 转弯」处被长段主导，于是**先冲出去再折返**：分册实测冲出 9 格(L=200) /
        // 53 格(L=800)，中心站序列甚至出现倒退。
        //
        // 密化只会在**同一条直线段内部**插共线点，所以折线几何逐位不变
        // （Catmull-Rom 穿过共线三点仍是那条直线），变的只是参数化均匀性 ——
        // 而这正是修复点。原本就是 8 格密的规则路一条判断都不触发，逐位不变。
        // 而 8 格密规则路的概率正是本方法要修的缺陷所在 —— 见下方 densifyControlPoints。
        Densified densified = densifyControlPoints(controlPoints, bridgeMask, gridStep);
        controlPoints = densified.points();
        bridgeMask = densified.mask();

        List<Vec2d> splinePoints = generateSplinePoints(controlPoints, bridgeMask, curveMode);
        List<Double> centerDists = new ArrayList<>();
        List<BlockPos> centers = extractCenters(splinePoints, rawPath, rawTargetY, centerDists);
        List<RoadSegmentPlacement> produced = rasterizeSegments(splinePoints, centers, centerDists, width);
        logShape("curved", rawPath, produced, gridStep);
        return produced;
    }

    /**
     * 形状取证日志（0.9.021，O-09 / R-01 / R-10 的最小实现）。
     *
     * <p>把「原始 A* 折线」与「后处理产物」各测一次 {@link SawtoothMetric}，写成一行。
     * 这是<b>抖动第一次变成可离线判定</b>的地方 —— 在此之前全仓没有任何生产日志字段在量
     * 横向摆动（{@code route QUALITY} 的 {@code holes/widthVar/gradeP95/spacing} 都看不到），
     * 所以「抖动有没有好转」只能靠人眼。</p>
     *
     * <p>一行里同时给出两个关键判据：</p>
     * <ul>
     *   <li>{@code flipsPer100} 前后对比 ⇒ 平滑改动到底削掉了多少振荡；</li>
     *   <li>{@code legP50} ⇒ <b>顺便把 R-01 的形态定案做掉</b>：接近步距(8) 是「楼梯型」，
     *       明显大于 2×步距(16) 是「缓弯型」。这正是 01 与 02 两路分析争执的那个前提。</li>
     * </ul>
     *
     * <p>开关：{@code -Droadweaver.shapeDiag=false} 关掉（默认开，一条路一行）。</p>
     */
    static void logShape(String stage, List<BlockPos> rawPath,
                         List<RoadSegmentPlacement> produced, int gridStep) {
        if (!shapeDiagEnabled()) return;
        if (produced == null || produced.isEmpty()) return;
        try {
            SawtoothMetric.Result after = SawtoothMetric.measureStations(produced, gridStep);
            SawtoothMetric.Result before = SawtoothMetric.measure(rawPath, gridStep);
            BlockPos from = produced.get(0).middlePos();
            BlockPos to = produced.get(produced.size() - 1).middlePos();
            LOGGER.info("RoadWeaver route SHAPE stage={} step={} "
                            + "raw=[stations={} zigzag={} flipsPer100={} ampP50={} ampP95={} legP50={}] "
                            + "final=[stations={} zigzag={} flipsPer100={} ampP50={} ampP95={} legP50={} reason={}] "
                            + "from={} to={}",
                    stage, gridStep,
                    rawPath == null ? 0 : rawPath.size(), before.zigzag(),
                    fmt(before.flipsPer100Stations()), fmt(before.amplitudeP50()),
                    fmt(before.amplitudeP95()), fmt(before.legLengthP50()),
                    produced.size(), after.zigzag(), fmt(after.flipsPer100Stations()),
                    fmt(after.amplitudeP50()), fmt(after.amplitudeP95()), fmt(after.legLengthP50()),
                    after.reason(),
                    from == null ? "?" : from.toShortString(),
                    to == null ? "?" : to.toShortString());
        } catch (RuntimeException ignored) {
            // 取证日志绝不能影响生成：任何测量异常都直接吞掉。
        }
    }

    static String fmt(double value) {
        return String.format("%.1f", value);
    }

    static boolean shapeDiagEnabled() {
        String raw = System.getProperty("roadweaver.shapeDiag");
        return raw == null || !raw.equalsIgnoreCase("false");
    }

    /** 密化后的控制点与同步扩写的桥掩码。 */
    record Densified(List<BlockPos> points, boolean[] mask) {
    }

    /**
     * 玩家配置的「桥 / 涵洞最小水深」（0.9.024 / O-11）。
     *
     * <p>真机症状：**在配置里改 `bridge.minWaterDepth` 完全无效**。原因是
     * {@code PathPostProcessor.process(...)} 的 {@code bridgeMinWaterDepth} 参数在
     * <b>全部 6 个调用点</b>（`RoadPathCalculator` ×3、三个寻路器各 ×1）都被传成了编译期常量
     * {@code RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH = 1}，而配置链路
     * （`BridgeConfig.minWaterDepth()` → `RoadGenerationConfig.bridgeMinWaterDepth()`）虽然通着，
     * 却从来没被这一层读过。
     *
     * <p>那 6 处的调用上下文只有 {@code PathfindingCostConfig}，拿不到
     * {@code RoadGenerationConfig}，所以这里直接读配置并做与 {@code RoadGenerationConfig}
     * 完全一致的钳位（同一对 MIN/MAX 常量），保证两条路径给出同一个数。</p>
     */
    public static int configuredBridgeMinWaterDepth() {
        try {
            int configured = net.shiroha233.roadweaver.config.ConfigService.get()
                    .bridge().minWaterDepth();
            return Math.max(net.shiroha233.roadweaver.core.constants.RoadConstants.BRIDGE_MIN_WATER_DEPTH_MIN,
                    Math.min(net.shiroha233.roadweaver.core.constants.RoadConstants.BRIDGE_MIN_WATER_DEPTH_MAX,
                            configured));
        } catch (Throwable ignored) {
            return net.shiroha233.roadweaver.core.constants.RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH;
        }
    }

    /**
     * 从 A* 原始路径推断步距。
     *
     * <p>A* 的邻接偏移恒为 {@code d = effectiveAStarStep()} 的整数倍，所以原始路径的
     * 相邻距离只可能是 {@code d} 或 {@code d√2}。<b>取最小相邻距离</b>即得 {@code d}
     * （纯对角路径会得到 {@code d√2}，这是可接受的保守值 —— 密化只是少插几个点，
     * 不会插错位置）。读不到时退回 8（出厂默认步距）。</p>
     */
    static int deriveGridStep(List<BlockPos> rawPath) {
        if (rawPath == null || rawPath.size() < 2) return 8;
        double smallest = Double.MAX_VALUE;
        for (int i = 1; i < rawPath.size(); i++) {
            BlockPos a = rawPath.get(i - 1);
            BlockPos b = rawPath.get(i);
            if (a == null || b == null) continue;
            double distance = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            if (distance > 0.5D && distance < smallest) smallest = distance;
        }
        if (smallest == Double.MAX_VALUE) return 8;
        int step = (int) Math.round(smallest);
        return Math.max(1, Math.min(32, step));
    }

    /**
     * 把相邻控制点之间长于 {@code gridStep} 的直线段按等距插点补齐（O-03）。
     *
     * <p><b>只在同一条直线段内部插共线点</b>，所以折线形状逐位不变；插入点的 Y 按
     * 线性插值取。桥掩码同步扩写，且<b>两端掩码不同的一条边整段不密化</b> ——
     * 那正是桥与岸的分界，密化会挪动桥界下标，进而改变「哪一段是桥面」。</p>
     *
     * <p>原本每段长度就 ≤ 步距的规则路：本方法返回原列表与原掩码，逐位不变。</p>
     */
    static Densified densifyControlPoints(List<BlockPos> controlPoints, boolean[] bridgeMask, int gridStep) {
        if (controlPoints == null || controlPoints.size() < 2 || gridStep <= 0) {
            return new Densified(controlPoints, bridgeMask);
        }
        List<BlockPos> points = new ArrayList<>(controlPoints.size() * 2);
        List<Boolean> flags = new ArrayList<>(controlPoints.size() * 2);
        boolean changed = false;
        for (int i = 0; i < controlPoints.size(); i++) {
            BlockPos current = controlPoints.get(i);
            points.add(current);
            flags.add(bridgeMask != null && i < bridgeMask.length && bridgeMask[i]);
            if (i == 0) continue;
            BlockPos previous = controlPoints.get(i - 1);
            if (previous == null || current == null) continue;
            boolean previousBridge = bridgeMask != null && i - 1 < bridgeMask.length && bridgeMask[i - 1];
            boolean currentBridge = bridgeMask != null && i < bridgeMask.length && bridgeMask[i];
            if (previousBridge != currentBridge) continue;   // 桥界：保持锐利，不密化
            int dx = current.getX() - previous.getX();
            int dy = current.getY() - previous.getY();
            int dz = current.getZ() - previous.getZ();
            double length = Math.hypot(dx, dz);
            int parts = (int) Math.ceil(length / gridStep);
            if (parts <= 1) continue;
            changed = true;
            // 把插入点放在「本段最后一个点之前」，这样桥掩码按下标顺序仍然对齐。
            int insertAt = points.size() - 1;
            List<BlockPos> inserted = new ArrayList<>(parts - 1);
            for (int part = 1; part < parts; part++) {
                double ratio = (double) part / parts;
                inserted.add(new BlockPos(
                        (int) Math.round(previous.getX() + dx * ratio),
                        (int) Math.round(previous.getY() + dy * ratio),
                        (int) Math.round(previous.getZ() + dz * ratio)));
            }
            points.addAll(insertAt, inserted);
            for (int part = 0; part < inserted.size(); part++) {
                flags.add(insertAt, previousBridge);
            }
        }
        if (!changed) {
            return new Densified(controlPoints, bridgeMask);
        }
        boolean[] mask = new boolean[flags.size()];
        for (int i = 0; i < flags.size(); i++) {
            mask[i] = flags.get(i);
        }
        return new Densified(points, mask);
    }

    /**
     * 0.8.320：按给定站点序列直接成段，**不做简化/平滑/重采样**，保证"一个站点一段"。
     *
     * <p>供 {@code rebuildRoadDataFromProfile} 使用：重建时的输入 profile 已经是这条路
     * 现有的站点序列（本身已经平滑过），再跑一遍平滑只会改变段数，导致
     * {@code rebuilt.size() != profile.size()} 判失败、白跑一次 A*（真机每局 22 次、
     * 原因全是 {@code segment_count_mismatch}）。直接按站点栅格化后，段数与坐标都与
     * 输入 1:1，调用方按 xz 重映射 span 也变得精确。</p>
     *
     * <p>路面覆盖范围不变：栅格化仍是"多段折线 ± 半宽"的并集，只是分段归属按站点算。</p>
     */
    public static List<RoadSegmentPlacement> rasterizeStations(List<BlockPos> stations, int width) {
        if (stations == null || stations.size() < 2) return new ArrayList<>();
        List<Vec2d> points = new ArrayList<>(stations.size());
        List<Double> centerDists = new ArrayList<>(stations.size());
        double distance = 0.0D;
        for (int index = 0; index < stations.size(); index++) {
            BlockPos station = stations.get(index);
            points.add(new Vec2d(station.getX(), station.getZ()));
            if (index > 0) distance += points.get(index).dist(points.get(index - 1));
            centerDists.add(distance);
        }
        return rasterizeSegments(points, stations, centerDists, width);
    }

    /**
     * 0.8.323：把相邻站点横向间距大于 {@code maxStep} 的缺口补插中间站。
     *
     * <p>重建路径改用 {@link #rasterizeStations} 保持"一个站点一段"的 1:1 身份后，
     * profile 里原本会被重采样抹平的 3 格跳变原样保留了下来，撞上
     * {@code RoadDataValidator} 的 {@code MAX_CENTER_STEP = 2}，整条路被判失败
     * （真机 20260909 16.15 存档：(0,0)-&gt;(97,281) 与 (-1104,304)-&gt;(-813,716)
     * 两条自 0.8.320 起再也规划不出来）。
     *
     * <p>只补点，不移动、不删除任何原站点：高度按比例插值，补点后每个子段的横向间距
     * 不超过 {@code maxStep}、高差不超过原高差，因此路线形状与两端位置完全不变。
     */
    public static List<BlockPos> densifyStationGaps(List<BlockPos> stations, int maxStep) {
        if (stations == null) return new ArrayList<>();
        if (stations.size() < 2 || maxStep < 1) return new ArrayList<>(stations);
        List<BlockPos> dense = new ArrayList<>(stations.size());
        dense.add(stations.get(0));
        for (int index = 1; index < stations.size(); index++) {
            BlockPos previous = stations.get(index - 1);
            BlockPos current = stations.get(index);
            if (previous == null || current == null) {
                dense.add(current);
                continue;
            }
            int dx = current.getX() - previous.getX();
            int dy = current.getY() - previous.getY();
            int dz = current.getZ() - previous.getZ();
            int step = Math.max(Math.abs(dx), Math.abs(dz));
            if (step <= maxStep) {
                dense.add(current);
                continue;
            }
            int parts = (step + maxStep - 1) / maxStep;
            for (int part = 1; part < parts; part++) {
                dense.add(new BlockPos(
                        previous.getX() + Math.round((float) dx * part / parts),
                        previous.getY() + Math.round((float) dy * part / parts),
                        previous.getZ() + Math.round((float) dz * part / parts)));
            }
            dense.add(current);
        }
        return dense;
    }

    /** Preserves the accepted A* corridor when curved smoothing would cut through an obstacle. */
    public static List<RoadSegmentPlacement> processUnrelaxedLinear(List<BlockPos> rawPath,
                                                                    int width,
                                                                    ServerLevel level,
                                                                    TerrainSamplingCache cache,
                                                                    PathTerrainField terrain,
                                                                    int bridgeMinWaterDepth,
                                                                    AccurateHeightSampler accurate) {
        if (rawPath == null || rawPath.size() < 2) return new ArrayList<>();
        int[] rawTargetY = new int[rawPath.size()];
        for (int i = 0; i < rawPath.size(); i++) rawTargetY[i] = rawPath.get(i).getY();
        AccurateHeightSampler sampler = accurate != null
                ? accurate
                : (cache != null ? cache.getAccurateSampler(level) : AccurateHeightSampler.create(level));
        boolean[] bridgeMask = detectBridgeMask(rawPath, level, cache, terrain, bridgeMinWaterDepth, sampler);
        List<Vec2d> splinePoints = generateSplinePoints(rawPath, bridgeMask, CurveMode.LINEAR);
        List<Double> centerDists = new ArrayList<>();
        List<BlockPos> centers = extractCenters(splinePoints, rawPath, rawTargetY, centerDists);
        List<RoadSegmentPlacement> produced = rasterizeSegments(splinePoints, centers, centerDists, width);
        // 这条是「曲线平滑会切穿障碍时的退化通道」—— 它被触发本身就是一条质量信号。
        logShape("linear-no-relax", rawPath, produced, deriveGridStep(rawPath));
        return produced;
    }

    /** 第 {@code i} 条腿（控制点 i → i+1）是否属于桥面（任一端点在桥上）。 */
    static boolean bridgeTouching(boolean[] bridgeMask, int i) {
        if (bridgeMask == null || i < 0 || i >= bridgeMask.length - 1) return false;
        return bridgeMask[i] || bridgeMask[i + 1];
    }

    static List<Vec2d> generateSplinePoints(List<BlockPos> controlPoints,
                                                     boolean[] bridgeMask, CurveMode curveMode) {
        List<BlockPos> ext = new ArrayList<>();
        ext.add(controlPoints.get(0));
        ext.addAll(controlPoints);
        ext.add(controlPoints.get(controlPoints.size() - 1));

        // 0.9.068（C3）：急弯段用直线代替 Catmull-Rom，避免锐角（发卡弯）外冲。
        // C2 只保护了 relax 阶段；这里在样条阶段对转向角 >= 阈值的控制点两端改用 lerp，
        // 发卡弯顶点不再被 CR 的切线 (P_{i+1}-P_{i-1})/2 甩出去。
        int nCtrl = controlPoints.size();
        boolean[] sharpAt = new boolean[nCtrl];
        for (int k = 1; k < nCtrl - 1; k++) {
            BlockPos ca = controlPoints.get(k - 1), cb = controlPoints.get(k), cc = controlPoints.get(k + 1);
            sharpAt[k] = turningAngleDeg(ca.getX(), ca.getZ(), cb.getX(), cb.getZ(), cc.getX(), cc.getZ())
                    >= SHARP_TURN_FREEZE_DEG;
        }

        List<Vec2d> splinePoints = new ArrayList<>();
        for (int i = 0; i < controlPoints.size() - 1; i++) {
            BlockPos p0 = ext.get(i), p1 = ext.get(i + 1), p2 = ext.get(i + 2), p3 = ext.get(i + 3);
            double dist = Math.sqrt(p1.distSqr(p2));
            int steps = Math.max(1, (int) Math.ceil(dist * 4.0));
            boolean bridgeSeg = bridgeTouching(bridgeMask, i);
            // 0.9.019（O-04）：桥头切线对齐。
            //
            // 桥段用 lerp（直线）、桥外仍用 Catmull-Rom，于是桥界两侧的**切向不连续**：
            // CR 在结点处的切向取 (P_{i+1} − P_{i-1})/2，桥面离开结点时沿
            // (P_{i+1} − P_i)，两者之差就是 (P_i − P_{i-1}) —— 分册实测桥头外侧
            // 航向突变 −30.82°/+30.82°。修法：把桥段**前后各一条腿**也用直线，
            // 这样两边都是直线、切向自然一致。密化之后每条腿 ≤ 步距，
            // 所以每个桥头最多只直 8~16 格，桥面本身一格不动。
            boolean tangentAligned = bridgeSeg
                    || bridgeTouching(bridgeMask, i - 1)
                    || bridgeTouching(bridgeMask, i + 1);
            // 0.9.068（C3）：急弯段（任一端点转向角超阈值）用直线，不跑 CR。
            boolean sharpSeg = sharpAt[i] || sharpAt[i + 1];

            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                Vec2d pt;
                if (tangentAligned || sharpSeg || curveMode == CurveMode.LINEAR) {
                    pt = lerp2d(p1.getX(), p1.getZ(), p2.getX(), p2.getZ(), t);
                } else if (curveMode == CurveMode.BEZIER_CASTELJAU) {
                    pt = catmullRomBezierCasteljau(p0.getX(), p0.getZ(), p1.getX(), p1.getZ(),
                            p2.getX(), p2.getZ(), p3.getX(), p3.getZ(), t);
                } else {
                    pt = catmullRomSpline(p0.getX(), p0.getZ(), p1.getX(), p1.getZ(),
                            p2.getX(), p2.getZ(), p3.getX(), p3.getZ(), t);
                }
                splinePoints.add(pt);
            }
        }
        BlockPos last = controlPoints.get(controlPoints.size() - 1);
        splinePoints.add(new Vec2d(last.getX(), last.getZ()));
        return splinePoints;
    }

    static List<BlockPos> extractCenters(List<Vec2d> splinePoints,
                                                  List<BlockPos> rawPath, int[] rawTargetY,
                                                  List<Double> outCenterDists) {
        List<BlockPos> centers = new ArrayList<>();
        double currentDist = 0;
        double nextCenterDist = 0;
        int rawSegmentHint = 0;

        for (int i = 0; i < splinePoints.size(); i++) {
            Vec2d p = splinePoints.get(i);
            if (i > 0) currentDist += p.dist(splinePoints.get(i - 1));

            if (currentDist + 0.5 >= nextCenterDist || i == splinePoints.size() - 1) {
                int cx = (int) Math.round(p.x());
                int cz = (int) Math.round(p.z());
                HeightProjection projection = interpolateYNearPathOrder(
                        cx, cz, rawPath, rawTargetY, rawSegmentHint);
                int y = projection.y();
                rawSegmentHint = projection.segmentIndex();
                BlockPos centerPos = new BlockPos(cx, y, cz);
                if (centers.isEmpty() || !centers.get(centers.size() - 1).equals(centerPos)) {
                    centers.add(centerPos);
                    outCenterDists.add(currentDist);
                    nextCenterDist = currentDist + 1.0;
                }
            }
        }
        return centers;
    }

    static HeightProjection interpolateYNearPathOrder(int cx,
                                                               int cz,
                                                               List<BlockPos> rawPath,
                                                               int[] rawTargetY,
                                                               int hint) {
        double bestDistSq = Double.MAX_VALUE;
        double bestT = 0;
        int bestSeg = Math.max(0, Math.min(rawPath.size() - 2, hint));
        int searchStart = bestSeg;
        int searchEnd = Math.min(rawPath.size() - 2, bestSeg + 32);

        for (int i = searchStart; i <= searchEnd; i++) {
            BlockPos a = rawPath.get(i);
            BlockPos b = rawPath.get(i + 1);
            double ax = a.getX(), az = a.getZ();
            double dx = b.getX() - ax, dz = b.getZ() - az;
            double lenSq = dx * dx + dz * dz;
            double t = (lenSq < 1e-9) ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((cx - ax) * dx + (cz - az) * dz) / lenSq));
            double projX = ax + t * dx, projZ = az + t * dz;
            double dSq = (cx - projX) * (cx - projX) + (cz - projZ) * (cz - projZ);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestT = t;
                bestSeg = i;
            }
        }
        int y0 = rawTargetY[bestSeg];
        int y1 = rawTargetY[Math.min(bestSeg + 1, rawTargetY.length - 1)];
        return new HeightProjection(
                (int) Math.round(y0 + (y1 - y0) * bestT), bestSeg);
    }

    private record HeightProjection(int y, int segmentIndex) {
    }

    static List<RoadSegmentPlacement> rasterizeSegments(List<Vec2d> splinePoints,
                                                                 List<BlockPos> centers,
                                                                 List<Double> centerDists,
                                                                 int width) {
        Map<Integer, Set<BlockPos>> segmentedBlocks = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) segmentedBlocks.put(i, new HashSet<>());

        double halfWidth = width / 2.0;
        double halfWidthSq = halfWidth * halfWidth;
        double pathDist = 0;
        int currentCenterIdx = 0;

        for (int i = 0; i < splinePoints.size() - 1; i++) {
            Vec2d pStart = splinePoints.get(i), pEnd = splinePoints.get(i + 1);
            pathDist += pStart.dist(pEnd);
            while (currentCenterIdx < centerDists.size() - 1 && centerDists.get(currentCenterIdx + 1) < pathDist) {
                currentCenterIdx++;
            }

            int minX = (int) Math.floor(Math.min(pStart.x(), pEnd.x()) - halfWidth - 1);
            int maxX = (int) Math.ceil(Math.max(pStart.x(), pEnd.x()) + halfWidth + 1);
            int minZ = (int) Math.floor(Math.min(pStart.z(), pEnd.z()) - halfWidth - 1);
            int maxZ = (int) Math.ceil(Math.max(pStart.z(), pEnd.z()) + halfWidth + 1);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (distToSegmentSq(x, z, pStart, pEnd) <= halfWidthSq) {
                        assignToNearestCenter(x, z, centers, currentCenterIdx, segmentedBlocks);
                    }
                }
            }
        }

        List<RoadSegmentPlacement> out = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            Set<BlockPos> blocks = segmentedBlocks.get(i);
            if (blocks.isEmpty()) blocks.add(new BlockPos(center.getX(), 0, center.getZ()));
            out.add(new RoadSegmentPlacement(center, new ArrayList<>(blocks)));
        }
        return out;
    }

    static void assignToNearestCenter(int x, int z, List<BlockPos> centers,
                                               int hint, Map<Integer, Set<BlockPos>> segmentedBlocks) {
        int bestIdx = hint;
        double bestDistSq = Double.MAX_VALUE;
        int searchStart = Math.max(0, hint - 2);
        int searchEnd = Math.min(centers.size() - 2, hint + 2);

        for (int seg = searchStart; seg <= searchEnd; seg++) {
            BlockPos a = centers.get(seg), b = centers.get(seg + 1);
            double ax = a.getX(), az = a.getZ(), dx = b.getX() - ax, dz = b.getZ() - az;
            double lenSq = dx * dx + dz * dz;
            double t = (lenSq < 1e-9) ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / lenSq));
            double projX = ax + t * dx, projZ = az + t * dz;
            double dSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
            int candidateIndex = (t < 0.5) ? seg : Math.min(seg + 1, centers.size() - 1);
            int currentDistance = Math.abs(bestIdx - hint);
            int candidateDistance = Math.abs(candidateIndex - hint);
            if (dSq < bestDistSq - 1.0E-9D
                    || (Math.abs(dSq - bestDistSq) <= 1.0E-9D && candidateDistance < currentDistance)) {
                bestDistSq = dSq;
                bestIdx = candidateIndex;
            }
        }
        segmentedBlocks.get(bestIdx).add(new BlockPos(x, 0, z));
    }

    /**
     * Douglas-Peucker 垂直距离容差（格）。
     *
     * <p>取 4 = {@code RoadConstants.DEFAULT_ASTAR_STEP / 2}，即 A* 网格量化误差的上限：
     * 被删掉的点偏离保留折线不超过半步长，与「网格离散化本身的不确定度」不可区分，
     * 因此不会削掉真实的地形弯曲（沿山谷/绕山脊的弯曲振幅是几十格量级）。</p>
     *
     * <p>0.9.016 之前这里用的是叉积阈值 16，与步长强耦合：步长 8 时 45° 转弯叉积约 45、
     * 90° 为 128，全部大于阈值 ⇒ 几乎不删点 ⇒ A* 的 8 格锯齿原样留在控制点里，
     * 而下游 Catmull-Rom 样条「穿过」每个控制点，锯齿就被画成波浪。步长 1~2 时
     * 反而会全删只剩起终点。换 DP 后阈值与步长解耦。</p>
     */
    static final double SIMPLIFY_TOLERANCE = 4.0D;

    /** 控制点密度下限：每这么多格路径长度至少保留一个控制点，防止把真实弯道拉成直线。 */
    static final double SIMPLIFY_MIN_POINTS_PER_BLOCK = 64.0D;

    /** 容差跟步长走：{@code clamp(0.5×step, 2, SIMPLIFY_TOLERANCE)}（O-13）。 */
    static double toleranceForStep(int gridStep) {
        double half = Math.max(1, gridStep) * 0.5D;
        return Math.max(2.0D, Math.min(SIMPLIFY_TOLERANCE, half));
    }

    static List<BlockPos> simplifyPath(List<BlockPos> nodes) {
        return simplifyPath(nodes, 8);
    }

    /**
     * 步距感知版本（O-13）：容差跟步长走，{@code clamp(0.5×step, 2, 4)}。
     *
     * <p>写死 4 时，一旦有人把 {@code aStarStep} 调到 2（局部修复档就是 2），容差 4
     * 会把 2 格网格上的**所有转角**一起抹平 —— 因为 DP 只看「偏离保留折线多远」。
     * 而默认档 {@code step=8} 时 {@code 0.5×8 = 4} 与原常量完全相同，所以
     * <b>默认档逐位不变</b>。</p>
     */
    static List<BlockPos> simplifyPath(List<BlockPos> nodes, int gridStep) {
        if (nodes == null || nodes.size() < 3) return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        double totalLength = 0.0D;
        for (int i = 1; i < nodes.size(); i++) {
            totalLength += Math.hypot((double) nodes.get(i).getX() - nodes.get(i - 1).getX(),
                    (double) nodes.get(i).getZ() - nodes.get(i - 1).getZ());
        }
        int minPoints = Math.max(2, (int) Math.ceil(totalLength / SIMPLIFY_MIN_POINTS_PER_BLOCK));
        double tolerance = toleranceForStep(gridStep);
        List<BlockPos> result = simplifyPath(nodes, tolerance);
        if (result.size() >= minPoints) return result;
        // 0.9.019（O-02）：密度下限不满足时，**不再把容差对半砍**。
        //
        // 0.9.016 的写法是「收紧容差重试」，后果是把整个修复自我否决掉：一条
        // 22.5° 锯齿路在容差 4 下只剩 2 个控制点（< minPoints），重试到容差 2 又
        // 退回 61 个点 —— **等于零简化**，锯齿原样进 Catmull-Rom 被画成波浪。
        // 现在改成「带密度约束的 DP」：保持容差 4，按「偏离当前折线最大」的次序
        // 把被删掉的原始点**逐点放回来**，直到达到密度下限。这样既保住容差与步长
        // 解耦的设计，又不会因为一次极端判断把整条路的简化全部作废。
        return reinstateUntilDense(nodes, result, minPoints);
    }

    /**
     * 把偏离当前折线最大的原始点逐点放回，直到点数达到 {@code minPoints}（O-02）。
     *
     * <p>只放回**原始点本身**（XZ 与 Y 都是原值），不移动、不新建任何坐标，所以
     * 结果始终是原始点列的一个子序列 —— 与 DP 的语义一致，几何变化仍有严格上界。</p>
     */
    static List<BlockPos> reinstateUntilDense(List<BlockPos> nodes, List<BlockPos> kept, int minPoints) {
        if (nodes == null || kept == null || kept.size() >= minPoints) return kept;
        int n = nodes.size();
        boolean[] keptFlag = new boolean[n];
        int cursor = 0;
        for (BlockPos point : kept) {
            int found = -1;
            for (int i = cursor; i < n; i++) {
                if (nodes.get(i).equals(point)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return kept;   // 对不上就放弃，绝不猜
            keptFlag[found] = true;
            cursor = found + 1;
        }
        int keptCount = kept.size();
        while (keptCount < minPoints) {
            int bestIndex = -1;
            double bestDeviation = -1.0D;
            int previousKept = -1;
            for (int i = 0; i < n; i++) {
                if (!keptFlag[i]) continue;
                if (previousKept >= 0 && i - previousKept > 1) {
                    BlockPos a = nodes.get(previousKept);
                    BlockPos b = nodes.get(i);
                    for (int k = previousKept + 1; k < i; k++) {
                        double deviation = perpendicularDistance(nodes.get(k), a, b);
                        if (deviation > bestDeviation) {
                            bestDeviation = deviation;
                            bestIndex = k;
                        }
                    }
                }
                previousKept = i;
            }
            if (bestIndex < 0 || bestDeviation <= 1.0E-9D) break;   // 已无可放回的点
            keptFlag[bestIndex] = true;
            keptCount++;
        }
        List<BlockPos> out = new ArrayList<>(keptCount);
        for (int i = 0; i < n; i++) {
            if (keptFlag[i]) out.add(nodes.get(i));
        }
        return out;
    }

    static double perpendicularDistance(BlockPos point, BlockPos a, BlockPos b) {
        double ax = a.getX();
        double az = a.getZ();
        double dx = b.getX() - ax;
        double dz = b.getZ() - az;
        double lenSq = dx * dx + dz * dz;
        double x = point.getX();
        double z = point.getZ();
        if (lenSq < 1.0E-9D) {
            return Math.hypot(x - ax, z - az);
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lenSq));
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    /**
     * Douglas-Peucker（迭代栈实现，避免深递归）。
     *
     * <p>只删「到保留折线的垂直距离 ≤ tolerance」的点，保留点的 XZ 与 Y 都是原始值，
     * 因此在容差内的几何变化有严格上界。</p>
     */
    static List<BlockPos> simplifyPath(List<BlockPos> nodes, double tolerance) {
        int n = nodes.size();
        if (n < 3) return new ArrayList<>(nodes);
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] span = stack.pop();
            int from = span[0], to = span[1];
            if (to - from < 2) continue;
            double ax = nodes.get(from).getX(), az = nodes.get(from).getZ();
            double dx = (double) nodes.get(to).getX() - ax;
            double dz = (double) nodes.get(to).getZ() - az;
            double length2 = dx * dx + dz * dz;
            double worst = -1.0D;
            int worstIndex = -1;
            for (int i = from + 1; i < to; i++) {
                double px = (double) nodes.get(i).getX() - ax;
                double pz = (double) nodes.get(i).getZ() - az;
                double distance;
                if (length2 <= 1.0E-9D) {
                    distance = Math.hypot(px, pz);
                } else {
                    double t = (px * dx + pz * dz) / length2;
                    if (t < 0.0D) t = 0.0D; else if (t > 1.0D) t = 1.0D;
                    distance = Math.hypot(px - t * dx, pz - t * dz);
                }
                if (distance > worst) {
                    worst = distance;
                    worstIndex = i;
                }
            }
            if (worstIndex >= 0 && worst > tolerance) {
                keep[worstIndex] = true;
                stack.push(new int[]{worstIndex, to});
                stack.push(new int[]{from, worstIndex});
            }
        }
        List<BlockPos> simplified = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) simplified.add(nodes.get(i));
        }
        return simplified;
    }

    /** 平滑迭代轮数。单轮 1-2-1 加权对 8 格锯齿只能削掉约 1/4 幅度，三轮才看得出「顺」。 */
    static final int RELAX_PASSES = 3;

    /**
     * 普通点的累计横向位移上限（格）。防止把真实弯道一路拉直。
     *
     * <p>0.9.025（H-02 的安全一半）：**2.0 → 3.0**。</p>
     *
     * <p>闭式关系是「残余 ≈ 锯齿幅 − cap」：锯齿峰 8 / 谷 0 时，cap 2.0 把峰 8→6、谷 0→2，
     * 净残余 **±2 格** —— 这正是真机 D1 里「沿河还有些抖」的那个 2。放到 3.0 后峰 8→5、谷 0→3，
     * 净残余 **±1 格**。</p>
     *
     * <p><b>为什么只到 3.0、不到 {@code d/2 = 4}</b>：3.0 仍然严格小于半个网格步长，
     * 「被挪动的幅度与网格离散化本身的不确定度同量级」这条不变量还成立；到 4.0 就跨过了
     * 半步长，等于允许把点挪到一个可能属于相邻网格单元的位置，会开始改路面走向（那是 H-02
     * 的激进一半，用户未批）。</p>
     *
     * <p><b>为什么桥不受影响</b>：桥段本身由 {@code straightenBridgeRuns} 拉直、且
     * {@code frozenByBridge} 让桥段与紧邻桥的点<b>完全不参与平滑</b>；桥头外侧 2~3 点走
     * 独立的 {@link #RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT} = 1.0。改这个常量<strong>只影响
     * 非桥、非桥头的普通点</strong>。</p>
     *
     * <p>端点是硬契约（{@code relax} 循环从 1 到 n-2），一寸都不动。</p>
     */
    static final double RELAX_MAX_DISPLACEMENT = 3.0D;

    /**
     * 桥头点的累计横向位移上限（格）。
     *
     * <p>桥面本身由 {@code straightenBridgeRuns} 拉直、桥段与紧邻桥的点完全不参与平滑
     * （否则桥面端点会和路面错位）；这里只放宽到「桥头外侧 2~3 个点」做过渡，
     * 并用更严的 1 格上限保证接缝不错位。</p>
     */
    static final double RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT = 1.0D;

    /**
     * 桥头"引道带"的站数半径（0.9.027 / V-4 甲）。
     *
     * <p>0.9.025 把全局上限 2.0 提到 3.0 时，桥段冻结、桥头 ±2/±3 仍是 1.0，但**4 站以外**
     * 的引道一下多挪了 1 格 —— 真机上就是桥头接缝处路面明显错位（V-4）。现在按距离分三档：
     * ±2/±3 = 1.0、±4..±8 = 2.0（0.9.025 之前的值）、更远 = 3.0。
     */
    static final int RELAX_BRIDGE_APPROACH_STATIONS = 8;

    /** 桥头引道带（±4..±8 站）的位移上限。用户 2026-11-08 选定「桥头 ±8 站回 2.0」。 */
    static final double RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT = 2.0D;

    /**
     * 0.9.067（C2）：急弯冻结阈值（度）。发卡弯顶点 / 90° 直角弯的转向角超过它就
     * 完全不参与 1-2-1 平滑 —— 否则会把弯心磨圆（盘山路的回头线被当成抖动拉直）。
     * 缓弯照常平滑。纯几何，与桥掩码正交。
     */
    static final double SHARP_TURN_FREEZE_DEG = 60.0D;

    static List<BlockPos> relaxPath(List<BlockPos> nodes) {
        return relax(nodes, null);
    }

    static List<BlockPos> relaxPathSkippingBridge(List<BlockPos> nodes, boolean[] bridgeMask) {
        return relax(nodes, bridgeMask);
    }

    /**
     * 多轮 1-2-1 加权平滑，带逐点累计位移上限。
     *
     * <p>端点、桥段点、以及紧邻桥的点一律不动（保持桥面直线与端点对齐）；桥头外侧
     * 2~3 个点参与平滑但上限收紧到 {@link #RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT}。
     * Y 完全不动 —— 纵断面由 {@code RoadGradePlanner} 单独负责。</p>
     */
    /** 包级可见：供 {@code RelaxCapInvariantTest} 直接断言位移上限是严格不变量。 */
    static List<BlockPos> relax(List<BlockPos> nodes, boolean[] bridgeMask) {
        int n = nodes == null ? 0 : nodes.size();
        if (n < 3) return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);

        int[] x = new int[n], y = new int[n], z = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = nodes.get(i);
            x[i] = p.getX();
            y[i] = p.getY();
            z[i] = p.getZ();
        }
        int[] originX = x.clone(), originZ = z.clone();

        // 0.9.067（C2）：急弯冻结。发卡弯顶点（转向角 > SHARP_TURN_FREEZE_DEG）不参与平滑，
        // 否则 1-2-1 会把回头线的弯心磨圆。只在首轮前算一次（originX/Z 已固定，几何不变）。
        boolean[] sharpTurn = new boolean[n];
        for (int i = 1; i < n - 1; i++) {
            if (frozenByBridge(bridgeMask, i)) continue;
            sharpTurn[i] = turningAngleDeg(x[i - 1], z[i - 1], x[i], z[i], x[i + 1], z[i + 1])
                    >= SHARP_TURN_FREEZE_DEG;
        }

        for (int pass = 0; pass < RELAX_PASSES; pass++) {
            int[] nextX = x.clone(), nextZ = z.clone();
            boolean moved = false;
            for (int i = 1; i < n - 1; i++) {
                if (frozenByBridge(bridgeMask, i)) continue;
                if (sharpTurn[i]) continue;   // 0.9.067（C2）：急弯冻结
                // 0.9.019（O-05）：本行原来是整数除法 `(a + 2b + c) / 4`。Java 的 /
                // 是「向零截断」而不是向下取整，负坐标下会系统性偏正（-1/4 得 0、
                // 向下取整应得 -1）⇒ 整条西/北向的路会整体偏一格。改成真正的四舍五入。
                int smoothedX = (int) Math.round((x[i - 1] + 2.0D * x[i] + x[i + 1]) / 4.0D);
                int smoothedZ = (int) Math.round((z[i - 1] + 2.0D * z[i] + z[i + 1]) / 4.0D);
                double cap = bridgeHeadCap(bridgeMask, i);
                double dx = (double) smoothedX - originX[i];
                double dz = (double) smoothedZ - originZ[i];
                double distance = Math.hypot(dx, dz);
                if (distance > cap) {
                    double scale = cap / distance;
                    smoothedX = (int) Math.round(originX[i] + dx * scale);
                    smoothedZ = (int) Math.round(originZ[i] + dz * scale);
                }
                // 0.9.019（O-05）：上面的 Math.round 每个轴都可能多走 0.5 格，
                // 于是取整后的实际位移可以超出 cap（实测 2.24 / 1.24 格，而 cap 是
                // 2.0 / 1.0）。这里把 cap 变成**严格不变量**：超了就沿位移较大的
                // 那个轴一次退回一格，最多退 2 步就必进界（每步减少 ≥0.5 格）。
                double roundedDx = (double) smoothedX - originX[i];
                double roundedDz = (double) smoothedZ - originZ[i];
                for (int retract = 0; retract < 2
                        && Math.hypot(roundedDx, roundedDz) > cap; retract++) {
                    if (Math.abs(roundedDx) >= Math.abs(roundedDz)) {
                        smoothedX -= Integer.signum(smoothedX - originX[i]);
                    } else {
                        smoothedZ -= Integer.signum(smoothedZ - originZ[i]);
                    }
                    roundedDx = (double) smoothedX - originX[i];
                    roundedDz = (double) smoothedZ - originZ[i];
                }
                // `moved` 比的是本轮的输入（x/z 尚未写回），语义正确，本批不动。
                if (smoothedX != x[i] || smoothedZ != z[i]) moved = true;
                nextX[i] = smoothedX;
                nextZ[i] = smoothedZ;
            }
            x = nextX;
            z = nextZ;
            if (!moved) break;
        }

        List<BlockPos> relaxed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            relaxed.add(new BlockPos(x[i], y[i], z[i]));
        }
        return relaxed;
    }

    /**
     * 0.9.067（C2）：三点转向角（度）。0=直行、180=完全折返。
     * 纯几何，无世界访问；三点共线或相邻点重合时返回 0（不判急弯）。
     */
    static double turningAngleDeg(int ax, int az, int bx, int bz, int cx, int cz) {
        double v1x = bx - ax, v1z = bz - az;
        double v2x = cx - bx, v2z = cz - bz;
        double len1 = Math.hypot(v1x, v1z);
        double len2 = Math.hypot(v2x, v2z);
        if (len1 < 1.0E-9D || len2 < 1.0E-9D) return 0.0D;
        double cos = (v1x * v2x + v1z * v2z) / (len1 * len2);
        cos = Math.max(-1.0D, Math.min(1.0D, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    /** 桥段本身与紧邻桥的点完全不参与平滑，保证桥面直线与端点对齐。 */
    static boolean frozenByBridge(boolean[] bridgeMask, int index) {
        if (bridgeMask == null) return false;
        return isBridgeAt(bridgeMask, index)
                || isBridgeAt(bridgeMask, index - 1)
                || isBridgeAt(bridgeMask, index + 1);
    }

    /** 桥头外侧 2~3 格：参与平滑，但位移上限更严。包级可见供上限不变量测试核验。 */
    static double bridgeHeadCap(boolean[] bridgeMask, int index) {
        if (bridgeMask == null) return RELAX_MAX_DISPLACEMENT;
        if (isBridgeAt(bridgeMask, index - 2) || isBridgeAt(bridgeMask, index + 2)
                || isBridgeAt(bridgeMask, index - 3) || isBridgeAt(bridgeMask, index + 3)) {
            return RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT;
        }
        // V-4 甲（0.9.027）：桥头 ±4..±8 站的引道回到 0.9.025 之前的 2.0。
        for (int d = 4; d <= RELAX_BRIDGE_APPROACH_STATIONS; d++) {
            if (isBridgeAt(bridgeMask, index - d) || isBridgeAt(bridgeMask, index + d)) {
                return RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT;
            }
        }
        return RELAX_MAX_DISPLACEMENT;
    }

    static boolean isBridgeAt(boolean[] bridgeMask, int index) {
        return index >= 0 && index < bridgeMask.length && bridgeMask[index];
    }
}
