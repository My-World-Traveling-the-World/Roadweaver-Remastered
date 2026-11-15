/* 文件职责：给一次寻路提供量化硬禁区，禁止路点和跨格连线穿越。 */
package net.shiroha233.roadweaver.pathfinding.impl;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.shiroha233.roadweaver.core.constants.PathfindingConstants;

/**
 * 一次寻路的硬禁行区（0.9.018，D2 平行双桥「方案 C：后者整体改道」）。
 *
 * <p>真机缺陷 D2：两条路在同一段走廊里并排各修一座桥，中间只隔几格，地图上是
 * 「平行双桥」。根因是麻花（braid）判定只在 {@code JunctionTrimOps.tryConnect}
 * 里跑，而那条路径的前提是「后一条路的端点贴上了前一条路」；两条各走各的、
 * 全程互不贴靠的平行路根本走不到那段判定，于是各修各的桥。</p>
 *
 * <p>方案 C 的做法是：先做全局两两预检，一旦确认两条路真的在并排重叠，就让
 * <b>后一条路整体重算</b>，重算时把前一条路的那段走廊设成硬禁区——即本类。</p>
 *
 * <h2>为什么按 4 格量化而不是精确格</h2>
 * <p>A* 的邻接偏移恒为 {@code d = effectiveAStarStep()}（当前配置 8）的整数倍，
 * 所以被访问的 (x,z) 只会落在起点的 8 格格点上。既然真正的判据粒度是 8 格，
 * 这里按 {@value #PathfindingConstants.CORRIDOR_QUANT} 格量化就不会漏判，而存储量降为精确栅格的 1/16：
 * 一条 5000 格长的禁行走廊从约 24 万格降到约 1.7 万个 key。查询走排序数组的
 * 二分（{@link Arrays#binarySearch(long[], long)}），零装箱、零分配。</p>
 *
 * <p>量化带来的唯一语义差异：只要一个量化格里 <b>有任何一点</b> 落进禁区半径，
 * 整格都算禁行。这个方向是<b>向外扩</b>而不是向内缩：距离恰好等于 {@code radius}
 * 的点一定落在被禁的格子里（它本身就在圆上），而超出 {@code radius} 的点也可能
 * 因为所在格与圆相交而被禁。所以实际净空恒在
 * {@code radius} 到 {@code radius + 4} 格之间，<b>永远不小于调用方要求的 radius</b>。</p>
 *
 * <p>0.9.019 更正：0.9.018 这段注释把方向写反了（原写「净空是 radius 到
 * radius - 4」）。方向写反会让人误以为要补偿地把 radius 调大，实际后果是
 * 走廊偏宽 8 格左右（18 格要求 ⇒ 40~46 格宽），把绕行推远、并与
 * {@code braidRerouteMaxLengthRatio} 叠加，属于「以为在放宽、其实在收紧」。</p>
 */
public final class CorridorBan {
    /** 空禁区：{@link #blocks(int, int)} 恒 false，调用方据此走零开销分支。 */
    public static final CorridorBan NONE = new CorridorBan(new long[0]);

    /**
     * 当前线程的「这次重算要绕开的走廊」。
     *
     * <p>改道重算要复用整条正式生成链（{@code Road.generateRoad}），而那条链在
     * 内部从 {@code ConfigService} 重新取配置、逐层 {@code snapshot()}，没有可以
     * 插入参数的口子。硬改一整条签名链（Road → RoadGenerationService →
     * PathfindingCostConfig → 三个寻路器 → 各种调用点）风险远大于收益；这里改用
     * 线程作用域的临时约束：改道重算整体跑在调用线程上，其它线程（同批次并行的
     * 其它道路规划）读不到这条禁令，互不污染。</p>
     *
     * <p>取值点是 {@code PathfindingCostConfig.snapshot()}——<b>每次请求一次</b>，
     * 不在 A* 的邻接热循环里，所以不引入任何逐节点开销。</p>
     */
    private static final ThreadLocal<CorridorBan> SCOPED = new ThreadLocal<>();

    /** 当前线程挂着的禁令，无则 {@code null}。 */
    public static CorridorBan current() {
        return SCOPED.get();
    }

    /**
     * 在 {@code body} 执行期间把 {@code ban} 挂到当前线程，结束后恢复原值。
     *
     * <p>用 {@code try/finally} 保证异常路径也会恢复：残留禁令会让同一线程后续
     * 所有规划都绕开那段走廊，静默改变整局道路。</p>
     */
    public static <T> T withScoped(CorridorBan ban, java.util.function.Supplier<T> body) {
        CorridorBan previous = SCOPED.get();
        SCOPED.set(ban);
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


    /** 量化粒度 2 的幂次：4 格。 */

    /** 升序排列的量化格 key（{@code cx} 在高 32 位、{@code cz} 在低 32 位）。 */
    private final long[] cells;

    private CorridorBan(long[] cells) {
        this.cells = cells;
    }

    /**
     * 把 {@code road} 上 {@code [from, to]} 这一段站点半径 {@code radius} 内的
     * 量化格全部设为禁行。
     *
     * <p>只禁出事的那一段而不是整条路：后一条路的重算起点／终点很可能就压在
     * 前一条路的其它位置上（同一个村子出去的两条路），整条禁掉会让 A* 直接
     * 无解，反而退回原位。段内再加一圈富余由调用方通过 {@code from}/{@code to}
     * 外扩若干站实现。</p>
     */
    public static CorridorBan aroundRoadRun(RoadData road, int from, int to, int radius) {
        return aroundRoadRun(road, from, to, radius, null, 0);
    }

    /**
     * 同上，但在 {@code releasePoints} 附近 {@code releaseRadius} 格内**不禁行**（R-05）。
     *
     * <h2>为什么需要「挖洞」</h2>
     * <p>0.9.018 的改道把前一条路的走廊设成硬禁区，但 A* 的终止条件是
     * {@code manhattan < 2×步距 = 16}，而禁区有效半宽是 20~23 格。所以只要重叠段
     * <b>覆盖到了后一条路的端点</b>（两条路首尾同源时必然如此），A* <b>永远到不了终点
     * 附近</b>；起点深陷禁区时连 8 个邻居都被禁、走都走不出去。</p>
     *
     * <p>后果：这种形态**改道成功率 0%**，而且每次都要白付一遍完整生成链
     * （{@code Road.generateRoad} 全流程），只在日志里留一行 rejected。</p>
     *
     * <p>修法就是在端点周围把禁令挖开一个洞 —— 让 A* 至少能进出端点。代价是端点附近
     * 仍可能和原路并排，但那条由改道后的「与原路不再并排」复检兜住：真的还并排就
     * 整体放弃改道、保留原路，比「必然失败」好得多。</p>
     */
    public static CorridorBan aroundRoadRun(RoadData road, int from, int to, int radius,
                                            List<BlockPos> releasePoints, int releaseRadius) {
        if (road == null) return NONE;
        List<RoadSegmentPlacement> stations = road.roadSegmentList();
        if (stations == null || stations.isEmpty()) return NONE;
        int start = Math.max(0, Math.min(from, to));
        int end = Math.min(stations.size() - 1, Math.max(from, to));
        if (start > end) return NONE;
        int reach = Math.max(0, radius);
        int cellReach = (reach + PathfindingConstants.CORRIDOR_QUANT - 1) >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        int releaseReach = releaseRadius <= 0 ? 0 : releaseRadius;
        int releaseCellReach = (releaseReach + PathfindingConstants.CORRIDOR_QUANT - 1) >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        int[] releaseX = new int[0];
        int[] releaseZ = new int[0];
        if (releasePoints != null && releaseReach > 0) {
            List<int[]> usable = new ArrayList<>(releasePoints.size());
            for (BlockPos point : releasePoints) {
                if (point == null) continue;
                usable.add(new int[]{point.getX() >> PathfindingConstants.CORRIDOR_QUANT_SHIFT, point.getZ() >> PathfindingConstants.CORRIDOR_QUANT_SHIFT});
            }
            releaseX = new int[usable.size()];
            releaseZ = new int[usable.size()];
            for (int i = 0; i < usable.size(); i++) {
                releaseX[i] = usable.get(i)[0];
                releaseZ[i] = usable.get(i)[1];
            }
        }
        java.util.HashSet<Long> unique = new java.util.HashSet<>(Math.max(16, (end - start + 1) * 64));
        for (int i = start; i <= end; i++) {
            RoadSegmentPlacement station = stations.get(i);
            if (station == null) continue;
            BlockPos pos = station.middlePos();
            if (pos == null) continue;
            int cx = pos.getX() >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
            int cz = pos.getZ() >> PathfindingConstants.CORRIDOR_QUANT_SHIFT;
            for (int dx = -cellReach; dx <= cellReach; dx++) {
                for (int dz = -cellReach; dz <= cellReach; dz++) {
                    int cellX = cx + dx;
                    int cellZ = cz + dz;
                    if (isReleased(cellX, cellZ, releaseX, releaseZ, releaseCellReach)) continue;
                    unique.add(pack(cellX, cellZ));
                }
            }
        }
        if (unique.isEmpty()) return NONE;
        long[] sorted = new long[unique.size()];
        int cursor = 0;
        for (Long key : unique) {
            sorted[cursor++] = key;
        }
        Arrays.sort(sorted);
        return new CorridorBan(sorted);
    }

    private static boolean isReleased(int cellX, int cellZ, int[] releaseX, int[] releaseZ, int reach) {
        if (reach <= 0 || releaseX.length == 0) return false;
        for (int i = 0; i < releaseX.length; i++) {
            if (Math.abs(cellX - releaseX[i]) <= reach && Math.abs(cellZ - releaseZ[i]) <= reach) {
                return true;
            }
        }
        return false;
    }

    /** True when the quantized cell containing {@code (x, z)} is banned. */
    public boolean blocks(int x, int z) {
        return blocksCell(x >> PathfindingConstants.CORRIDOR_QUANT_SHIFT,
                z >> PathfindingConstants.CORRIDOR_QUANT_SHIFT);
    }

    /** 穿过的量化格都必须检查；两端合法也会夹着一段只有 4 格宽的禁区。 */
    public boolean intersectsSegment(int x0, int z0, int x1, int z1) {
        if (isEmpty()) return false;
        int shift = PathfindingConstants.CORRIDOR_QUANT_SHIFT;
        int width = PathfindingConstants.CORRIDOR_QUANT;
        int x = x0 >> shift, z = z0 >> shift;
        int endX = x1 >> shift, endZ = z1 >> shift;
        int sx = Integer.compare(x1, x0), sz = Integer.compare(z1, z0);
        long dx = Math.abs((long) x1 - x0), dz = Math.abs((long) z1 - z0);
        // 从方块中心出发，使用双倍距离表示半格；乘相反轴的步长比较过界时刻。
        long nextX = sx > 0 ? 2L * (((long) x + 1) * width - x0) - 1
                : 2L * (x0 - (long) x * width) + 1;
        long nextZ = sz > 0 ? 2L * (((long) z + 1) * width - z0) - 1
                : 2L * (z0 - (long) z * width) + 1;
        while (true) {
            if (blocksCell(x, z)) return true;
            if (x == endX && z == endZ) return false;
            int order = x == endX ? 1 : z == endZ ? -1 : Long.compare(nextX * dz, nextZ * dx);
            // 共角时也检查两侧，避免对角从两个硬禁格的角之间切过去。
            if (order == 0 && (blocksCell(x + sx, z) || blocksCell(x, z + sz))) return true;
            if (order <= 0) {
                x += sx;
                nextX += 2L * width;
            }
            if (order >= 0) {
                z += sz;
                nextZ += 2L * width;
            }
        }
    }

    /** 候选重搜和几何平滑必须符合与粗搜相同的硬禁令。 */
    public boolean intersectsPath(List<BlockPos> path) {
        if (isEmpty() || path == null || path.isEmpty()) return false;
        BlockPos prev = path.getFirst();
        if (blocks(prev.getX(), prev.getZ())) return true;
        for (int i = 1; i < path.size(); i++) {
            BlockPos next = path.get(i);
            if (intersectsSegment(prev.getX(), prev.getZ(), next.getX(), next.getZ())) return true;
            prev = next;
        }
        return false;
    }

    private boolean blocksCell(int x, int z) {
        return cells.length != 0 && Arrays.binarySearch(cells, pack(x, z)) >= 0;
    }

    /** 禁用的量化格数，仅用于日志与测试断言。 */
    public int cellCount() {
        return cells.length;
    }

    public boolean isEmpty() {
        return cells.length == 0;
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
