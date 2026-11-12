/* 文件职责：在网格上解 Eikonal 方程 |∇T| = 1/c，得到从源点出发的到达时间场。 */
package net.shiroha233.roadweaver.metric;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 快速行进法（Fast Marching）求解器。
 *
 * <h2>为什么不用 A*</h2>
 * <p>旧实现为 23 条连接<b>各自独立</b>跑 A*，实测 <b>215 秒</b>；而它们共享同一张地形。
 * Fast Marching 一次求解得到<b>整张到达时间场</b>，任意终点沿负梯度回溯即得路径 ——
 * 24 个结构节点各跑一次即可得到完整的两两距离矩阵，拓扑优化变成免费。</p>
 *
 * <p>更重要的一点：A* 在 8 邻域上只能走 8 个方向（45° 量化），<b>锯齿是结构性内生</b>的；
 * Fast Marching 的方向由连续的 {@code ∇T} 决定，<b>没有 45° 量化</b>。</p>
 *
 * <h2>离散格式（Godunov upwind）</h2>
 * <p>把 {@code |∇T| = s}（{@code s = 1/c} 是局部慢度）用迎风差分离散。
 * 取四个邻居中的两个最小者 {@code a}、{@code b}，解
 * {@code max(T−a,0)² + max(T−b,0)² = s²}：</p>
 * <pre>
 * 若 |a − b| ≥ s :   T = min(a, b) + s
 * 否则           :   T = ( a + b + √(2s² − (a−b)²) ) / 2
 * </pre>
 * <p>这个格式保证单调、迎风、且解唯一。</p>
 *
 * <h2>复杂度</h2>
 * <p>{@code O(n log n)}，{@code n} 是网格点数。堆按 T 递增弹出（Dijkstra 结构）。</p>
 *
 * @see FinslerMetric
 * @see ArrivalTimeField
 */
public final class FastMarchingSolver {

    /** 不可达。 */
    static final double UNREACHABLE = Double.POSITIVE_INFINITY;

    private final FinslerMetric metric;

    public FastMarchingSolver(FinslerMetric metric) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        this.metric = metric;
    }

    /**
     * 从源点求解到达时间场。
     *
     * @param sourceX 源点 x（方块坐标）
     * @param sourceZ 源点 z（方块坐标）
     * @return 到达时间场（不可变快照）
     */
    public ArrivalTimeField solve(int sourceX, int sourceZ) {
        TerrainField terrain = metric.terrain();
        int minX = terrain.minX();
        int minZ = terrain.minZ();
        int w = terrain.width();
        int d = terrain.depth();
        if (!terrain.contains(sourceX, sourceZ)) {
            throw new IllegalArgumentException(
                    "源点 (" + sourceX + "," + sourceZ + ") 不在可行区域内");
        }
        if (!metric.isPassable(sourceX, sourceZ)) {
            throw new IllegalArgumentException(
                    "源点 (" + sourceX + "," + sourceZ + ") 是硬不可通行格");
        }

        double[] t = new double[w * d];
        Arrays.fill(t, UNREACHABLE);
        boolean[] frozen = new boolean[w * d];

        int sourceIndex = (sourceX - minX) + (sourceZ - minZ) * w;
        t[sourceIndex] = 0.0;

        PriorityQueue<Entry> queue = new PriorityQueue<>();
        queue.add(new Entry(0.0, sourceIndex));

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            if (frozen[entry.index]) continue;          // 过期条目
            frozen[entry.index] = true;

            int ix = entry.index % w;
            int iz = entry.index / w;
            int x = minX + ix;
            int z = minZ + iz;

            for (int dir = 0; dir < 4; dir++) {
                int nix = ix + DX[dir];
                int niz = iz + DZ[dir];
                if (nix < 0 || nix >= w || niz < 0 || niz >= d) continue;
                int nIndex = nix + niz * w;
                if (frozen[nIndex]) continue;

                // 硬不可通行的格子**不进队列**，而不是给一个很大的代价 ——
                // 只有这样「不可达」才是真的不可达，拓扑层才能判断
                // 「这个端点根本连不上」而不是看到一个天文数字假装连通。
                if (!metric.isPassable(minX + nix, minZ + niz)) continue;

                // **慢度必须取「正在求解的那一格」的，不能取「已冻结的那一格」的。**
                //
                // 这是迎风格式的要求，也是两个真实 bug 的坟场：
                //
                // 1) 方向反了：FinslerMetric.isotropicCost 返回的是「每单位长度的代价」
                //    （1 = 基础代价，越大越难走），而 Eikonal 是 |∇T| = c，c 是
                //    「每单位长度花的时间」。所以慢度**就是**代价本身，不是倒数。
                //    早期写成 1/cost ⇒ 高代价格子变成「走得快」⇒ 路径直接穿障碍。
                //
                // 2) 取错格：如果用在冻结格 (x,z) 的慢度去更新邻居，那么「墙后」
                //    那些便宜格子会把值倒灌回墙格 —— 因为从便宜格走到墙格时用的是
                //    便宜格的代价，墙的代价根本没被收。实测表现为「一道 9 倍代价的
                //    墙完全透明，T(30,20) 恰好等于直线 10」。
                //    正确写法是求 (nix,niz) 自己那一格的慢度。
                double slowness = Math.max(1e-9,
                        metric.isotropicCost(minX + nix, minZ + niz));
                double candidate = solveEikonal(t, w, d, nix, niz, slowness);
                if (candidate < t[nIndex]) {
                    t[nIndex] = candidate;
                    queue.add(new Entry(candidate, nIndex));
                }
            }
        }

        return new ArrivalTimeField(t, minX, minZ, w, d, metric);
    }

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    /**
     * Godunov upwind 一阶格式（网格步长 h = 1）。
     *
     * @param slowness 局部慢度 {@code s = 1/c}
     */
    private static double solveEikonal(double[] t, int w, int d,
                                       int ix, int iz, double slowness) {
        double a = Math.min(
                ix > 0 ? t[ix - 1 + iz * w] : UNREACHABLE,
                ix < w - 1 ? t[ix + 1 + iz * w] : UNREACHABLE);
        double b = Math.min(
                iz > 0 ? t[ix + (iz - 1) * w] : UNREACHABLE,
                iz < d - 1 ? t[ix + (iz + 1) * w] : UNREACHABLE);

        if (a == UNREACHABLE && b == UNREACHABLE) return UNREACHABLE;
        if (a == UNREACHABLE) return b + slowness;
        if (b == UNREACHABLE) return a + slowness;

        if (Math.abs(a - b) >= slowness) {
            return Math.min(a, b) + slowness;
        }
        double discriminant = 2.0 * slowness * slowness - (a - b) * (a - b);
        if (discriminant <= 0) return Math.min(a, b) + slowness;
        return (a + b + Math.sqrt(discriminant)) / 2.0;
    }

    /** 优先队列条目，按到达时间升序。 */
    private static final class Entry implements Comparable<Entry> {
        final double time;
        final int index;

        Entry(double time, int index) {
            this.time = time;
            this.index = index;
        }

        @Override
        public int compareTo(Entry other) {
            return Double.compare(this.time, other.time);
        }
    }
}
