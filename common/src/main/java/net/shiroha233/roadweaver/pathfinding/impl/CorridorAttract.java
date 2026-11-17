/* 文件职责：给一次寻路提供“优惠走廊”——A* 邻接扩展时把步进代价打折的格子集合。 */
package net.shiroha233.roadweaver.pathfinding.impl;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import net.shiroha233.roadweaver.core.constants.PathfindingConstants;

/**
 * 一次规划的「优惠走廊」（0.9.056，真机 (236,-170) 平行双桥：规划期就共用一座桥）。
 *
 * <p>真机缺陷：出生点 {@code (32,-64)} 分出两条连接各去一个村庄，规划阶段两条路
 * <b>各自独立跑 A*</b>（{@code CoarseCorridorPlanningAdapter} 把每条连接作为一个
 * 独立任务提交到同一个线程池），在陆地上分叉之后各自看到同一条河就各自架桥，
 * 于是坐标 {@code (236,-170)} 附近出现两座并行的桥：</p>
 * <ul>
 *   <li>{@code (32,-64)->(992,544)} span0 st299..368，甲板 Y=48；</li>
 *   <li>{@code (32,-64)->(-624,-944)} span10 st296..371，甲板 Y=65。</li>
 * </ul>
 * <p>XZ 只差 2 格、重叠 60+ 站，但甲板差 17 格，生成后处理（{@code BridgeShareService}）
 * 已合不回来：{@code BridgeShareAbsorber.blendToDeckY} 返回 {@code null} ⇒
 * {@code skippedNoTransition} ⇒ {@code bridgeShareCandidates=0}。</p>
 *
 * <h2>做法：与 {@link CorridorBan} 对称的「吸引」</h2>
 * <p>本类是 {@code CorridorBan} 的镜像：禁行是<b>硬禁区</b>（命中即 {@code continue}），
 * 吸引是<b>软优惠</b>（命中即把这一步的步进代价乘上 {@code factor}，默认 0.35）。
 * 语义只有一条：<b>先算完的路把中心线登记成优惠走廊，后算的路被吸过去</b>，
 * 于是「陆地上分叉 → 汇到一条桥过河 → 对岸再分开」。</p>
 *
 * <h2>量化粒度与 {@code CorridorBan} 相同</h2>
 * <p>同样是 {@value #PathfindingConstants.CORRIDOR_QUANT} 格量化（A* 邻接偏移恒为步长的整数倍，实际判据粒度远大于
 * 4 格，量化不改变结论），存储与查询走排序数组的二分（{@link Arrays#binarySearch(long[], long)}），
 * 零装箱、零分配。命中判定是<b>向外扩</b>的：只要一个量化格与半径圆相交就整格算命中，
 * 所以实际优惠带净宽恒在 {@code radius} 到 {@code radius + 4} 格之间，<b>永不小于</b>
 * 调用方要求的半径。</p>
 *
 * <h2>为什么可以放进热循环</h2>
 * <p>{@code factorAt} 在 A* 的邻接扩展里每条边调用一次，但：</p>
 * <ul>
 *   <li>折扣系数与半径在<b>构造时</b>就固定进实例（不在展开循环里读配置）；</li>
 *   <li>关掉时（{@code radius=0} 或 {@code factor=1.0}）实例是 {@link #NONE}，
 *       调用方先判 {@code null}/空，连二分都不做 —— 与没有本机制时逐位相同；</li>
 *   <li>未命中恒返回 <b>1.0</b>，调用方据此跳过乘法。</li>
 * </ul>
 */
public final class CorridorAttract {
    /** 空吸引：{@link #factorAt(int, int)} 恒 1.0，调用方据此走零开销分支。 */
    public static final CorridorAttract NONE = new CorridorAttract(new long[0], 0, 1.0D);

    /**
     * 当前线程的「这批规划里已经算完的路」。
     *
     * <p>与 {@code CorridorBan} 同构：改整条签名链（Road → RoadGenerationService →
     * PathfindingCostConfig → 各寻路器）风险远大于收益，所以用线程作用域的临时约束。
     * 取值点是 {@code PathfindingCostConfig.snapshot()}——<b>每次请求一次</b>，
     * 不在 A* 的邻接热循环里读 ThreadLocal。</p>
     */
    private static final ThreadLocal<CorridorAttract> SCOPED = new ThreadLocal<>();

    /** 当前线程挂着的吸引力，无则 {@code null}。 */
    public static CorridorAttract current() {
        return SCOPED.get();
    }

    /**
     * 在 {@code body} 执行期间把 {@code attract} 挂到当前线程，结束后恢复原值。
     *
     * <p>用 {@code try/finally} 保证异常路径也会恢复：残留的优惠走廊会静默改变
     * 同一线程后续所有规划的选线。</p>
     */
    public static <T> T withScoped(CorridorAttract attract, java.util.function.Supplier<T> body) {
        CorridorAttract previous = SCOPED.get();
        SCOPED.set(attract);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SCOPED.remove();
            } else {
                SCOPED.set(previous);
            }
        }
    }



    /** 升序排列的量化格 key（{@code cx} 在高 32 位、{@code cz} 在低 32 位）。 */
    private final long[] cells;
    /** 优惠带半径（格），构造时固定。 */
    private final int radius;
    /** 命中格的折扣系数（代价乘数），构造时固定。 */
    private final double factor;

    private CorridorAttract(long[] cells, int radius, double factor) {
        this.cells = cells;
        this.radius = radius;
        this.factor = factor;
    }

    /**
     * 按配置造一个「还没登记任何路」的批内吸引器；关闭时返回 {@link #NONE}。
     *
     * <p>读的是 {@code PathfindingCostConfig} 的<b>已 sanitize 快照</b>，所以半径与系数
     * 已经落在合法区间里，这里只判开关。</p>
     */
    public static CorridorAttract forPlanning(PathfindingCostConfig cfg) {
        if (cfg == null) return NONE;
        int radius = cfg.corridorAttractRadius();
        double factor = cfg.corridorAttractFactor();
        if (radius <= 0 || !(factor < 1.0D)) return NONE;
        return new CorridorAttract(new long[0], radius, factor);
    }

    /** 机制是否开启（半径 &gt; 0 且系数 &lt; 1.0）。关掉时调用方必须走旧路径。 */
    public boolean enabled() {
        return radius > 0 && factor < 1.0D;
    }

    public boolean isEmpty() {
        return cells.length == 0;
    }

    /** 已登记的量化格数，仅用于日志与测试断言。 */
    public int cellCount() {
        return cells.length;
    }

    public int radius() {
        return radius;
    }

    public double factor() {
        return factor;
    }

    /**
     * True when this cell is discounted: 命中返回折扣系数，未命中<b>严格</b>返回 {@code 1.0D}。
     *
     * <p>未命中必须逐位返回 1.0：调用方用 {@code factor != 1.0D} 决定是否做乘法，
     * 只要漏掉这一条，「关掉机制」就不再等于「回到今天的行为」。</p>
     */
    public double factorAt(int x, int z) {
        if (cells.length == 0) return 1.0D;
        return Arrays.binarySearch(cells, pack(x >> PathfindingConstants.CORRIDOR_QUANT_SHIFT, z >> PathfindingConstants.CORRIDOR_QUANT_SHIFT)) >= 0
                ? factor : 1.0D;
    }

    /** 是否命中优惠带（诊断用；语义等价于 {@code factorAt(...) < 1.0}）。 */
    public boolean hits(int x, int z) {
        if (cells.length == 0) return false;
        return Arrays.binarySearch(cells, pack(x >> PathfindingConstants.CORRIDOR_QUANT_SHIFT, z >> PathfindingConstants.CORRIDOR_QUANT_SHIFT)) >= 0;
    }

    /**
     * 把一条<b>已算完的路径中心线</b>并入走廊，返回新实例（本身不可变）。
     *
     * <p>规划阶段拿到的是 {@code List<BlockPos>} 原始路径（网格步长的节点序列），
     * 与 {@link #aroundRoadRun} 的站点中心线是同一类几何：都是「这条路的中心线」。</p>
     */
    public CorridorAttract withPath(List<BlockPos> path) {
        if (!enabled() || path == null || path.isEmpty()) return this;
        long[] added = cellsAround(path, radius);
        if (added.length == 0) return this;
        return new CorridorAttract(union(cells, added), radius, factor);
    }

    /**
     * 把 {@code road} 上 {@code [from, to]} 这一段站点半径 {@code radius} 内的量化格
     * 设为优惠带（与 {@code CorridorBan.aroundRoadRun} 对称的入口）。
     *
     * <p>{@code radius <= 0} 时取配置值（{@code corridorAttractRadius}）；折扣系数取配置值
     * （{@code corridorAttractFactor}）。配置读不到时退回 {@link RoadConstants} 的默认值，
     * 绝不因为读配置异常而静默退化成「无吸引」或「免费」。</p>
     */
    public static CorridorAttract aroundRoadRun(RoadData road, int from, int to, int radius) {
        if (road == null) return NONE;
        List<RoadSegmentPlacement> stations = road.roadSegmentList();
        if (stations == null || stations.isEmpty()) return NONE;
        int reach = radius > 0 ? radius : configuredRadius();
        double factor = configuredFactor();
        if (reach <= 0 || !(factor < 1.0D)) return NONE;
        int start = Math.max(0, Math.min(from, to));
        int end = Math.min(stations.size() - 1, Math.max(from, to));
        if (start > end) return NONE;
        List<BlockPos> centerline = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            RoadSegmentPlacement station = stations.get(i);
            if (station == null) continue;
            BlockPos pos = station.middlePos();
            if (pos == null) continue;
            centerline.add(pos);
        }
        if (centerline.isEmpty()) return NONE;
        long[] built = cellsAround(centerline, reach);
        if (built.length == 0) return NONE;
        return new CorridorAttract(built, reach, factor);
    }

    /**
     * 0.9.057：把<b>一个点</b>周围 {@code radius} 格内的量化格登记成优惠带。
     *
     * <p>这是「并行 → 协商 → 并行」三段式里第三段（第二遍并行规划）用的入口：中间那段
     * （{@link net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator}）
     * 只产出一个<b>共享过河点的坐标</b>，不产出折线，所以这里收的是坐标而不是路径。</p>
     *
     * <p>展开语义与 {@link #aroundRoadRun} / {@link #withPath} 完全一致（同一份
     * {@link #PathfindingConstants.CORRIDOR_QUANT} 量化与「量化格与半径圆相交即命中」的外扩），因此同一个点用同一个半径
     * 得到的优惠带与「把一条只有一个节点的路径交给 {@link #withPath}」逐格相同。</p>
     *
     * <p>关掉的判定放在这里：{@code radius <= 0} 或 {@code factor >= 1.0} 直接返回
     * {@link #NONE}，调用方据此整条新路径跳过 —— 与今天（0.9.055）逐位相同。</p>
     *
     * @param x      共享过河点 X
     * @param z      共享过河点 Z
     * @param radius 优惠带半径（格），{@code <= 0} 表示关闭
     * @param factor 命中格的步进代价乘数，{@code >= 1.0} 表示关闭
     */
    public static CorridorAttract aroundPoint(int x, int z, int radius, double factor) {
        int reach = Math.max(0, radius);
        if (reach <= 0 || !(factor < 1.0D)) return NONE;
        long[] built = cellsAroundPoint(x, z, reach);
        if (built.length == 0) return NONE;
        return new CorridorAttract(built, reach, factor);
    }

    /** 配置里的优惠半径；读不到配置时退回默认值。 */
    private static int configuredRadius() {
        try {
            PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
            return cfg == null ? RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS : cfg.corridorAttractRadius();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS;
        }
    }

    /** 配置里的折扣系数；读不到配置时退回默认值。 */
    private static double configuredFactor() {
        try {
            PathfindingCostConfig cfg = ConfigService.get().pathfindingCost();
            return cfg == null ? RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR : cfg.corridorAttractFactor();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR;
        }
    }

    /** 把 {@code points} 每条半径 {@code reach} 内的量化格收集成升序去重数组。 */
    private static long[] cellsAround(List<BlockPos> points, int reach) {
        int cellReach = (Math.max(0, reach) + PathfindingConstants.CORRIDOR_QUANT - 1) >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        HashSet<Long> unique = new HashSet<>(Math.max(16, points.size() * 32));
        for (BlockPos point : points) {
            if (point == null) continue;
            addCells(unique, point.getX(), point.getZ(), cellReach);
        }
        return sortedCells(unique);
    }

    /** 单点版本（0.9.057 的共享过河点入口）——与 {@link #cellsAround} 同一个外扩语义。 */
    private static long[] cellsAroundPoint(int x, int z, int reach) {
        int cellReach = (Math.max(0, reach) + PathfindingConstants.CORRIDOR_QUANT - 1) >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        int side = cellReach * 2 + 1;
        HashSet<Long> unique = new HashSet<>(Math.max(16, side * side));
        addCells(unique, x, z, cellReach);
        return sortedCells(unique);
    }

    /** 把一个坐标周围 {@code cellReach} 个量化格（方形块）全部登记进来。 */
    private static void addCells(HashSet<Long> unique, int x, int z, int cellReach) {
        int cx = x >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        int cz = z >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        for (int dx = -cellReach; dx <= cellReach; dx++) {
            for (int dz = -cellReach; dz <= cellReach; dz++) {
                unique.add(pack(cx + dx, cz + dz));
            }
        }
    }

    private static long[] sortedCells(HashSet<Long> unique) {
        if (unique.isEmpty()) return new long[0];
        long[] sorted = new long[unique.size()];
        int cursor = 0;
        for (Long key : unique) {
            sorted[cursor++] = key;
        }
        Arrays.sort(sorted);
        return sorted;
    }

    /** 两段升序数组求并集（仍然升序、去重）。 */
    private static long[] union(long[] a, long[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        HashSet<Long> merged = new HashSet<>(a.length + b.length);
        for (long key : a) merged.add(key);
        for (long key : b) merged.add(key);
        long[] sorted = new long[merged.size()];
        int cursor = 0;
        for (Long key : merged) {
            sorted[cursor++] = key;
        }
        Arrays.sort(sorted);
        return sorted;
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
