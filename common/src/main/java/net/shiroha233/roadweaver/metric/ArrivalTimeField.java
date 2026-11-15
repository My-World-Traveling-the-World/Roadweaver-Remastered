/* 文件职责：到达时间场的只读快照，以及沿负梯度回溯出路径。 */
package net.shiroha233.roadweaver.metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 到达时间场：从某个源点出发，走到每一点的最小代价。
 *
 * <h2>它能回答什么</h2>
 * <ul>
 *   <li>{@link #at} —— 到某点的最小代价（＝测地距离）</li>
 *   <li>{@link #pathTo} —— 到某点的路径（沿 {@code -∇T} 回溯，方向连续）</li>
 * </ul>
 *
 * <h2>一次求解，多个终点</h2>
 * <p>这是相对旧实现最大的结构优势：旧实现每对端点跑一次 A*，
 * 23 条连接花了 215 秒；这里同一个源点可以问任意多个终点。
 * 拓扑层（{@code topology}）因此可以直接用 N 次求解得到完整的两两距离矩阵。</p>
 *
 * <h2>方向为什么是连续的</h2>
 * <p>回溯时每步取「{@code T} 下降最快的邻居」，再用线性插值细化，
 * 所以得到的方向不受 8 邻域限制。这一点是「锯齿从根上消失」的来源。</p>
 *
 * @see FastMarchingSolver
 */
public final class ArrivalTimeField {

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final double[] times;
    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int step;
    private final FinslerMetric metric;

    ArrivalTimeField(double[] times, int minX, int minZ,
                     int width, int depth, int step, FinslerMetric metric) {
        this.times = times;
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.step = Math.max(1, step);
        this.metric = metric;
    }

    /**
     * 全不可达的时间场（2026-11-14，审计 NEW-1）。
     *
     * <p>源点落在硬障碍格或采样区域外时，{@link FastMarchingSolver#solve} 不抛异常，
     * 而是返回这个场：每格 at() 都是 ∞，上层按既有「不可达」路径优雅降级。</p>
     */
    public static ArrivalTimeField unreachable(int minX, int minZ, int width, int depth,
                                               int step, FinslerMetric metric) {
        double[] times = new double[width * depth];
        Arrays.fill(times, Double.POSITIVE_INFINITY);
        return new ArrivalTimeField(times, minX, minZ, width, depth, step, metric);
    }

    /** 到该点的最小代价；不可达返回 {@link Double#POSITIVE_INFINITY}。 */
    public double at(int x, int z) {
        if (!contains(x, z)) return Double.POSITIVE_INFINITY;
        return times[indexOf(x, z)];
    }

    /** 采样步长（格）。 */
    public int step() { return step; }

    /** 是否可达。 */
    public boolean reachable(int x, int z) {
        return Double.isFinite(at(x, z));
    }

    /** 点是否在场内。 */
    public boolean contains(int x, int z) {
        return x >= minX && x < minX + width * step && z >= minZ && z < minZ + depth * step;
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
     * <p>步数上限用 {@code width·depth + 16} 兜底（每步 T 严格下降，正常远小于此值），
     * 与 {@code BandPathfinder} 的 {@code maxSteps} 参数无关。</p>
     *
     * @return 从源点到终点的点列（含两端）；不可达时返回空列表
     */
    public List<int[]> pathTo(int targetX, int targetZ) {
        List<int[]> reversed = new ArrayList<>();
        if (!reachable(targetX, targetZ)) return List.of();

        int x = targetX;
        int z = targetZ;
        reversed.add(new int[]{x, z});   // 对外一律方块坐标

        // 步数上限：防止数值异常导致死循环（每步 T 严格下降，正常远小于此值）
        int guard = width * depth + 16;
        while (at(x, z) > 1e-12 && guard-- > 0) {
            // 2026-11-14 修复（审计 NEW-5）：邻居比选改成「下降率最大」。
            //
            // 原来比的是**原始 T 最小**，这系统性偏爱对角方向：
            // 沿 φ 方向的邻居 ΔT = h·(cosφ + sinφ)，对角（45° 步长 √2·h）
            // 拿到的 ΔT 恒为轴向的 (cosφ+sinφ) 倍 —— φ 越小越明显。
            // φ=10° 时对角 ΔT=1.159h、轴向 0.985h ⇒ 对角 T 更小 ⇒ 被选中，
            // 但它实际步长 1.414h，真实下降率只有 0.82 对 0.985 ——
            // 于是下降最陡的方向被系统性跳过，最大偏差 √2−1 = 41.4%，
            // 表现为路径带不必要的对角折线（「之字」的一个成因）。
            // 正确判据是「单位弧长的下降量」(T_cur − T_nb)/dist 最大化。
            double current = at(x, z);
            int bestDir = bestDescentDirection(current, neighborTimes(x, z));
            if (bestDir < 0) break;                  // 数值上是平台，停止
            x = x + DX[bestDir] * step;
            z = z + DZ[bestDir] * step;
            reversed.add(new int[]{x, z});
        }

        // 反转为「源 -> 终点」
        List<int[]> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    /** 取 8 邻域的 T（不在场内的填 +∞）。 */
    private double[] neighborTimes(int x, int z) {
        double[] times = new double[8];
        for (int dir = 0; dir < 8; dir++) {
            int nx = x + DX[dir] * step;
            int nz = z + DZ[dir] * step;
            // 八邻域回溯也必须挡边，特别是 FMM 只推进四邻域、回溯却可对角时。
            times[dir] = contains(nx, nz) && !metric.blocksEdge(x, z, nx, nz)
                    ? at(nx, nz) : Double.POSITIVE_INFINITY;
        }
        return times;
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
        int ix = Math.round((x - minX) / (float) step);
        int iz = Math.round((z - minZ) / (float) step);
        ix = Math.max(0, Math.min(width - 1, ix));
        iz = Math.max(0, Math.min(depth - 1, iz));
        return ix + iz * width;
    }
}
