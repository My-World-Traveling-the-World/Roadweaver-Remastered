/* 文件职责：对道路路径执行精确高度修正、平滑与桥梁后处理。 */
package net.shiroha233.roadweaver.postprocess;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import java.util.function.BiConsumer;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.isWaterLike;
import static net.shiroha233.roadweaver.postprocess.SplineHelper.*;
import static net.shiroha233.roadweaver.postprocess.BridgeMaskTool.*;

/**
 * 路径后处理器
 */
public final class PathPostProcessor {
    static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("roadweaver");

    /** 记录高度后端拒绝的列，避免把缺样列当作已知陆地。 */
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
        return processWithLandRelaxShadow(rawPath, width, level, cache, terrain, bridgeMinWaterDepth,
                curveMode, accurate, null);
    }

    // 同一条输入、同一份桥掩码和端点高程成两份路面，才能只测陆地 relax 的贡献。
    public static List<RoadSegmentPlacement> processWithLandRelaxShadow(
            List<BlockPos> rawPath, int width, ServerLevel level, TerrainSamplingCache cache,
            PathTerrainField terrain, int bridgeMinWaterDepth, CurveMode curveMode,
            AccurateHeightSampler accurate,
            BiConsumer<List<RoadSegmentPlacement>, List<RoadSegmentPlacement>> observer) {
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
        List<RoadSegmentPlacement> produced = rasterizeCurved(rawPath, rawTargetY, width, curveMode,
                gridStep, bridgeMask, controlPoints);
        logShape("curved", rawPath, produced, gridStep);
        if (observer != null && rawPath.size() <= 1024 && produced.size() <= 2048) {
            try {
                // 松弛本来不动桥段；影子只绕过陆地松弛，桥拉直、样条、加密、落列全走共用链。
                var withoutRelax = controlPoints.equals(straightened) ? produced
                        : rasterizeCurved(rawPath, rawTargetY, width, curveMode, gridStep, bridgeMask, straightened);
                // 观测者拿到只读快照；即便错误修改列也不能污染生产结果。
                observer.accept(freezeProduct(produced), freezeProduct(withoutRelax));
            } catch (RuntimeException failure) {
                LOGGER.warn("【陆地第二次松弛影子计算失败，生产产物保持】 " + "RoadWeaver land-relax shadow failed (rawStations={})", rawPath.size(), failure);
            }
        }
        return produced;
    }

    private static List<RoadSegmentPlacement> rasterizeCurved(List<BlockPos> rawPath, int[] rawTargetY,
            int width, CurveMode mode, int gridStep, boolean[] bridgeMask, List<BlockPos> controlPoints) {
        Densified densified = densifyControlPoints(controlPoints, bridgeMask, gridStep);
        List<Vec2d> splinePoints = generateSplinePoints(densified.points(), densified.mask(), mode);
        List<Double> centerDists = new ArrayList<>();
        List<BlockPos> centers = extractCenters(splinePoints, rawPath, rawTargetY, centerDists);
        return rasterizeSegments(splinePoints, centers, centerDists, width);
    }

    private static List<RoadSegmentPlacement> freezeProduct(List<RoadSegmentPlacement> road) {
        // 观测方只能获得独立外层快照；分站构造器已冻结内层，不能为每次A/B再复制铺装列。
        // 继续拒绝非法null铺装列表，保持原观测边界的输入契约。
        for (var station : road) {
            java.util.Objects.requireNonNull(station.positions());
            for (var column : station.positions()) java.util.Objects.requireNonNull(column);
        }
        return List.copyOf(road);
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
            LOGGER.info("【道路后处理前后的形状指标】 " + "RoadWeaver route SHAPE stage={} step={} "
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
        // 剖面重建仍一站一段；复用同一落列实现，避免门面保存另一套宽度算法。
        return PathWidthRasterizer.rasterizeStations(stations, width);
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

    /** 曲线平滑侵入障碍时保留已接受规划走廊，不把原A*名称当作当前算法。 */
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

    // 保留包内调用入口；几何参数、里程和列归属都原样交给纯计算组件。
    static boolean bridgeTouching(boolean[] bridgeMask, int index) {
        return PathSplineSampler.bridgeTouching(bridgeMask, index);
    }

    static List<Vec2d> generateSplinePoints(List<BlockPos> controls, boolean[] mask, CurveMode mode) {
        return PathSplineSampler.generateSplinePoints(controls, mask, mode);
    }

    static List<BlockPos> extractCenters(List<Vec2d> points, List<BlockPos> raw, int[] targetY, List<Double> distances) {
        return PathSplineSampler.extractCenters(points, raw, targetY, distances);
    }

    static List<RoadSegmentPlacement> rasterizeSegments(List<Vec2d> points, List<BlockPos> centers,
                                                       List<Double> distances, int width) {
        return PathWidthRasterizer.rasterizeSegments(points, centers, distances, width);
    }

    static void assignToNearestCenter(int x, int z, List<BlockPos> centers, int hint,
                                      Map<Integer, Set<BlockPos>> blocks) {
        PathWidthRasterizer.assignToNearestCenter(x, z, centers, hint, blocks);
    }

    // 保留包内入口供现有几何调用方使用；参数和产物完全透传到对应职责的纯计算类。
    static final double SIMPLIFY_TOLERANCE = PathControlSimplifier.SIMPLIFY_TOLERANCE;
    static final double SIMPLIFY_MIN_POINTS_PER_BLOCK = PathControlSimplifier.SIMPLIFY_MIN_POINTS_PER_BLOCK;
    static final int RELAX_PASSES = PathControlRelaxer.RELAX_PASSES;
    static final double RELAX_MAX_DISPLACEMENT = PathControlRelaxer.RELAX_MAX_DISPLACEMENT;
    static final double RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT = PathControlRelaxer.RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT;
    static final int RELAX_BRIDGE_APPROACH_STATIONS = PathControlRelaxer.RELAX_BRIDGE_APPROACH_STATIONS;
    static final double RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT = PathControlRelaxer.RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT;
    static final double SHARP_TURN_FREEZE_DEG = PathControlRelaxer.SHARP_TURN_FREEZE_DEG;

    /** 步距容差保持原口径，不在门面再解释参数。 */
    static double toleranceForStep(int gridStep) {
        return PathControlSimplifier.toleranceForStep(gridStep);
    }

    static List<BlockPos> simplifyPath(List<BlockPos> nodes) {
        return PathControlSimplifier.simplifyPath(nodes);
    }

    static List<BlockPos> simplifyPath(List<BlockPos> nodes, int gridStep) {
        return PathControlSimplifier.simplifyPath(nodes, gridStep);
    }

    static List<BlockPos> simplifyPath(List<BlockPos> nodes, double tolerance) {
        return PathControlSimplifier.simplifyPath(nodes, tolerance);
    }

    /** 密度补点仍只恢复原始点，不能改成插值生成新的高度。 */
    static List<BlockPos> reinstateUntilDense(List<BlockPos> nodes, List<BlockPos> kept, int minPoints) {
        return PathControlSimplifier.reinstateUntilDense(nodes, kept, minPoints);
    }

    static double perpendicularDistance(BlockPos point, BlockPos a, BlockPos b) {
        return PathControlSimplifier.perpendicularDistance(point, a, b);
    }

    static List<BlockPos> relaxPath(List<BlockPos> nodes) {
        return PathControlRelaxer.relaxPath(nodes);
    }

    static List<BlockPos> relaxPathSkippingBridge(List<BlockPos> nodes, boolean[] bridgeMask) {
        return PathControlRelaxer.relaxPathSkippingBridge(nodes, bridgeMask);
    }

    /** 保留生产松弛，不改变影子 A/B 的选择或执行顺序。 */
    static List<BlockPos> relax(List<BlockPos> nodes, boolean[] bridgeMask) {
        return PathControlRelaxer.relax(nodes, bridgeMask);
    }

    static double turningAngleDeg(int ax, int az, int bx, int bz, int cx, int cz) {
        return PathControlRelaxer.turningAngleDeg(ax, az, bx, bz, cx, cz);
    }

    static boolean frozenByBridge(boolean[] bridgeMask, int index) {
        return PathControlRelaxer.frozenByBridge(bridgeMask, index);
    }

    static double bridgeHeadCap(boolean[] bridgeMask, int index) {
        return PathControlRelaxer.bridgeHeadCap(bridgeMask, index);
    }

    static boolean isBridgeAt(boolean[] bridgeMask, int index) {
        return PathControlRelaxer.isBridgeAt(bridgeMask, index);
    }
}
