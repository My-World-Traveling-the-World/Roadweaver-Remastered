/* 文件职责：到达时间场的只读快照，以及沿负梯度回溯出路径。 */
package net.shiroha233.roadweaver.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 到达时间场：从某个源点出发，走到每一点的最小代价。
 *
 * <h2>它能回答什么</h2>
 * <ul>
 *   <li>{@link #at} —— 到某点的最小代价（＝测地距离）</li>
 *   <li>{@link #pathTo} —— 到某点最近采样格的路径（沿 {@code -∇T} 回溯）</li>
 * </ul>
 *
 * <h2>一次求解，多个终点</h2>
 * <p>这是相对旧实现最大的结构优势：旧实现每对端点跑一次 A*，
 * 23 条连接花了 215 秒；这里同一个源点可以问任意多个终点。
 * 拓扑层（{@code topology}）因此可以直接用 N 次求解得到完整的两两距离矩阵。</p>
 *
 * <p>回溯的点都在网格上，曲线平滑和真实端点接线由上层负责。这样高度、障碍和下降方向
 * 读到的是同一组坐标，不会因为查询点离网格有几格就平移整条线。</p>
 *
 * @see FastMarchingSolver
 */
public final class ArrivalTimeField {

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    // null 只表示已验证布局的全不可达常量场，避免为无结果的请求创建网格存储。
    private final FmmValues times;
    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int step;
    private final FinslerMetric metric;

    ArrivalTimeField(double[] times, int minX, int minZ,
                     int width, int depth, int step, FinslerMetric metric) {
        this(times == null ? null : new FmmValues(times), minX, minZ, width, depth, step, metric);
    }

    private ArrivalTimeField(FmmValues times, int minX, int minZ,
                             int width, int depth, int step, FinslerMetric metric) {
        this.times = times;
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.step = Math.max(1, step);
        this.metric = metric;
    }

    /** 每次求解独占 values；后续源点新建工作区，不能清空已返回快照的页。 */
    static ArrivalTimeField snapshot(FmmValues times, int minX, int minZ,
                                     int width, int depth, int step, FinslerMetric metric) {
        return new ArrivalTimeField(times, minX, minZ, width, depth, step, metric);
    }

    /**
     * 全不可达的时间场（2026-11-14，审计 NEW-1）。
     *
     * <p>源点落在硬障碍格或采样区域外时，{@link FastMarchingSolver#solve} 不抛异常，
     * 而是返回这个场：每格 at() 都是 ∞，上层按既有「不可达」路径优雅降级。</p>
     */
    public static ArrivalTimeField unreachable(int minX, int minZ, int width, int depth,
                                               int step, FinslerMetric metric) {
        // 即使源点不可达，也必须先验证布局，不能靠数组长度溢出返回伪快照。
        new GridLayout(minX, minZ, width, depth, Math.max(1, step)).arrayLength();
        return new ArrivalTimeField((double[]) null, minX, minZ, width, depth, step, metric);
    }

    /** 到该点的最小代价；不可达返回 {@link Double#POSITIVE_INFINITY}。 */
    public double at(int x, int z) {
        if (times == null || !contains(x, z)) return Double.POSITIVE_INFINITY;
        return times.get(indexOf(x, z));
    }

    /** 采样步长（格）。 */
    public int step() { return step; }

    /** 是否可达。 */
    public boolean reachable(int x, int z) {
        return Double.isFinite(at(x, z));
    }

    /** 点是否在场内。 */
    public boolean contains(int x, int z) {
        // 半开区间保持原语义，端点乘加升 long 避免靠近 int 边界时误判。
        return x >= minX && x < (long) minX + (long) width * step
                && z >= minZ && z < (long) minZ + (long) depth * step;
    }

    /** 场宽（采样点数）。 */
    public int width() { return width; }

    /** 场高（采样点数）。 */
    public int depth() { return depth; }

    /** 最小 x。 */
    public int minX() { return minX; }

    /** 最小 z。 */
    public int minZ() { return minZ; }

    /**
     * 回溯到指定点的路径。
     *
     * <p>每步在 8 邻域里选<b>单位弧长下降量最大</b>的方向
     * （{@code (T_cur − T_nb) / 邻接弧长}，见 {@link #bestDescentDirection}）——
     * 不是「T 最小」，后者会系统性偏爱对角方向（最大偏差 41.4%，审计 NEW-5）。</p>
     *
     * <p><b>为什么不查 {@code isPassable}</b>（2026-11-14 核实，审计 E）：
     * 硬不可通行的格子在 {@code FastMarchingSolver} 里<b>根本不入队</b>，
     * 因此它们的 T 恒为 +∞，永远不会被选中。再加一次判定是冗余的 ——
     * 原审计条目这一半不成立（Claude 复核时已推翻）。</p>
     *
     * <p>步数上限用 {@code width·depth} 兜底（每步 T 严格下降，正常远小于此值），
     * 该限制属于反向追踪，不是已删除的旧A*扩展步数预算。</p>
     *
     * @return 从源格到目标格的方块坐标（含两格）；不可达或无法回到源格时返回空列表
     */
    public List<int[]> pathTo(int targetX, int targetZ) {
        if (times == null || !contains(targetX, targetZ)) return List.of();
        int index = indexOf(targetX, targetZ);
        if (!Double.isFinite(times.get(index))) return List.of();

        // 世界坐标只参与一次吸附；用网格索引走，禁行边和场值便不会检查不同的线。
        List<int[]> reversed = new ArrayList<>();
        double[] neighbors = new double[DX.length];
        int ix = index % width, iz = index / width;
        for (int guard = times.size(); guard > 0; guard--) {
            int x = minX + ix * step, z = minZ + iz * step;
            reversed.add(new int[]{x, z});
            double current = times.get(ix + iz * width);
            if (current == 0.0) {
                Collections.reverse(reversed);
                return reversed;
            }
            for (int dir = 0; dir < DX.length; dir++) {
                int nx = ix + DX[dir], nz = iz + DZ[dir];
                neighbors[dir] = Double.POSITIVE_INFINITY;
                if (nx < 0 || nx >= width || nz < 0 || nz >= depth) continue;
                double next = times.get(nx + nz * width);
                if (next < current && !metric.blocksEdge(x, z, minX + nx * step, minZ + nz * step)) {
                    neighbors[dir] = next;
                }
            }
            int direction = bestDescentDirection(current, neighbors);
            // 上层会把首格当源格接线，不能把只回溯到半途的路径冒充完整路线。
            if (direction < 0) break;
            ix += DX[direction];
            iz += DZ[direction];
        }
        return List.of();
    }

    /**
     * 在 8 邻域里选「单位弧长下降量」最大的方向。
     *
     * <p>判据是 {@code (T_cur − T_nb) / 邻接弧长} 最大化（轴向弧长 = {@code step}、
     * 对角 = {@code step·√2}），而不是「T 最小」—— 后者会系统性偏爱对角方向
     * （见 {@link #pathTo} 里的说明）。</p>
     *
     * @param neighborTimes 8 邻域的 T，顺序与 {@code DX}/{@code DZ} 一致
     * @return 方向下标；没有任何严格下降的邻居时返回 {@code -1}
     */
    static int bestDescentDirection(double current, double[] neighborTimes) {
        int bestDir = -1;
        double bestRate = 0.0;
        for (int dir = 0; dir < 8; dir++) {
            double nt = neighborTimes[dir];
            if (!(nt < current - 1e-12)) continue;        // 必须严格下降，避免平台死循环
            double arc = (dir < 4) ? 1.0 : Math.sqrt(2.0); // 以 step 为单位的弧长
            double rate = (current - nt) / arc;
            if (rate > bestRate + 1e-15) {
                bestRate = rate;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    /** 度量（供上层计算路径代价）。 */
    public FinslerMetric metric() { return metric; }

    /** 方块坐标 → 场下标（按步长就近吸附到最近的采样点）。 */
    private int indexOf(int x, int z) {
        // 2026-11-14 修复（审计 NEW-2）：这里原来是向下取整 `(x - minX) / step`，
        // 而 GridTerrainField/BandPathfinder/StridedTerrainField 用的是 Math.round
        // （就近吸附）—— 两套口径并存时，step=8 的网格会错位最多 7 格
        // （恰好一个路宽），障碍场与到达时间场在世界坐标上系统性平移。
        // 统一为就近吸附（与障碍场/地形场同口径）。
        int ix = Math.round(((long) x - minX) / (float) step);
        int iz = Math.round(((long) z - minZ) / (float) step);
        ix = Math.max(0, Math.min(width - 1, ix));
        iz = Math.max(0, Math.min(depth - 1, iz));
        return ix + iz * width;
    }
}
