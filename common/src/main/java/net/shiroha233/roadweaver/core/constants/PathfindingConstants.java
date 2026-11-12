/* 文件职责：寻路算法共享常量。 */
package net.shiroha233.roadweaver.core.constants;

/**
 * 寻路算法共享常量。
 *
 * <h2>为什么要有这个类</h2>
 * <p>下面这几个值原先在多个寻路器里<b>各自复制一份</b>（同值、同义）。同值同义的常量
 * 复制两份之后，只要有人改了其中一份，选线行为就会在「生产用的势场法」与
 * 「出厂默认的梯度下降」之间悄悄分叉，而且不会有任何日志。</p>
 *
 * <p>2026-11-12 结构重构时把它们收成单一来源：三个 A* 寻路器
 * （BasicAStar / BidirectionalAStar / GradientDescent）原本各有一份
 * {@code BIOME_BASE_COST = 12}，{@code CoarseHeightAwarePlanner} 与
 * {@code BasicAStarPathfinder} 各有一份 {@code PLANNING_OBSTACLE_COST = 25_000.0}，
 * {@code CorridorBan} 与 {@code CorridorAttract} 各有一份量化位移。</p>
 *
 * <p><b>注意倍率差异是真实存在的</b>：{@code GradientDescentPathfinder} 对水域用
 * {@code WATER_BIOME_BASE_COST * 4}，另两个寻路器用 {@code WATER_BIOME_BASE_COST}。
 * 统一的是<b>基数</b>，倍率保持各自就地写死 —— 那是刻意的行为差异，不要顺手抹平。</p>
 */
public final class PathfindingConstants {

    private PathfindingConstants() {}

    /** 水域群系的单边基础代价。GradientDescentPathfinder 在此基础上额外乘 4。 */
    public static final int WATER_BIOME_BASE_COST = 12;

    /** A* 启发式放大系数（轻微不可采纳换速度，0 = 精确可采纳）。 */
    public static final double HEURISTIC_EPSILON = 0.2D;

    /** 一条边穿过「预测建筑足迹」的代价（三个寻路器共用同一口径）。 */
    public static final double PLANNING_OBSTACLE_COST = 25_000.0D;

    /**
     * 规划期走廊（{@link net.shiroha233.roadweaver.pathfinding.impl.CorridorBan} /
     * {@code CorridorAttract}）的格点量化位移。
     *
     * <p>4 格量化 = 「向外扩」而不是向内缩，所以净空恒落在 [20,23]、
     * 永远不会小于设定的 18 格。改这个值会同时改变禁令与吸引两边的粒度。</p>
     */
    public static final int CORRIDOR_QUANT_SHIFT = 2;

    /** {@code 1 << CORRIDOR_QUANT_SHIFT} = 4 格粒度。 */
    public static final int CORRIDOR_QUANT = 1 << CORRIDOR_QUANT_SHIFT;
}
