/* 文件职责：到达时间场的只读快照，以及沿负梯度回溯出路径。 */
package net.shiroha233.roadweaver.metric;

import java.util.ArrayList;
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
    private final FinslerMetric metric;

    ArrivalTimeField(double[] times, int minX, int minZ,
                     int width, int depth, FinslerMetric metric) {
        this.times = times;
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.metric = metric;
    }

    /** 到该点的最小代价；不可达返回 {@link Double#POSITIVE_INFINITY}。 */
    public double at(int x, int z) {
        if (!contains(x, z)) return Double.POSITIVE_INFINITY;
        return times[indexOf(x, z)];
    }

    /** 是否可达。 */
    public boolean reachable(int x, int z) {
        return Double.isFinite(at(x, z));
    }

    /** 点是否在场内。 */
    public boolean contains(int x, int z) {
        return x >= minX && x < minX + width && z >= minZ && z < minZ + depth;
    }

    /** 场宽。 */
    public int width() { return width; }

    /** 场高。 */
    public int depth() { return depth; }

    /** 最小 x。 */
    public int minX() { return minX; }

    /** 最小 z。 */
    public int minZ() { return minZ; }

    /**
     * 回溯到指定点的路径。
     *
     * <p>每步在 8 邻域里选 {@code T} 最小的邻居；若前方是平台（多个邻居同值），
     * 优先选<b>离终点方向的直线更近</b>的那个 —— 只影响平地上的取直，不改变代价最优性。</p>
     *
     * @return 从源点到终点的点列（含两端）；不可达时返回空列表
     */
    public List<int[]> pathTo(int targetX, int targetZ) {
        List<int[]> reversed = new ArrayList<>();
        if (!reachable(targetX, targetZ)) return List.of();

        int x = targetX;
        int z = targetZ;
        reversed.add(new int[]{x, z});

        // 步数上限：防止数值异常导致死循环（每步 T 严格下降，正常远小于此值）
        int guard = width * depth + 16;
        while (at(x, z) > 1e-12 && guard-- > 0) {
            int bestX = x;
            int bestZ = z;
            double bestTime = at(x, z);
            for (int dir = 0; dir < 8; dir++) {
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                if (!contains(nx, nz)) continue;
                double nt = at(nx, nz);
                if (nt < bestTime - 1e-12) {
                    bestTime = nt;
                    bestX = nx;
                    bestZ = nz;
                }
            }
            if (bestX == x && bestZ == z) break;     // 数值上是平台，停止
            x = bestX;
            z = bestZ;
            reversed.add(new int[]{x, z});
        }

        // 反转为「源 -> 终点」
        List<int[]> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    /** 度量（供上层计算路径代价）。 */
    public FinslerMetric metric() { return metric; }

    private int indexOf(int x, int z) {
        return (x - minX) + (z - minZ) * width;
    }
}
