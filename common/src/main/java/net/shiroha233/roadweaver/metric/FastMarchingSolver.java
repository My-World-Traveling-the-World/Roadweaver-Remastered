/* 文件职责：在网格上解 Eikonal 方程 |∇T| = 1/c，得到从源点出发的到达时间场。 */
package net.shiroha233.roadweaver.metric;

import java.util.Arrays;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

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
        // 网格尺寸用**采样点数**而不是方块跨度：步长 > 1 时两者不同。
        // 步长只影响网格间距（等距 1 格关系变成 step 格关系），
        // 对外仍然是逐格定义的地形。
        int w = terrain.columns();
        int d = terrain.rows();
        int step = terrain.step();
        if (!terrain.contains(sourceX, sourceZ)) {
            // 2026-11-14 修复（审计 NEW-1）：源点在区域外时**不抛异常** ——
            // 整条调用链（BandRouteSolver → BandPathfinder → RawPathSearch →
            // CoarseCorridorPlanningAdapter）没有任何一层捕获它，一个落在边界外的
            // 源点会把整批 16 条道路任务全部腰斩。改为返回「全不可达」的时间场：
            // 上层看到 at() 全是 ∞，按既有的不可达路径优雅降级。
            LOGGER.warn("RoadWeaver FMM: 源点 ({},{}) 不在采样区域内 [{},{}]~[{},{}]，返回不可达场",
                    sourceX, sourceZ, minX, minZ, minX + (w - 1) * step, minZ + (d - 1) * step);
            return ArrivalTimeField.unreachable(minX, minZ, w, d, step, metric);
        }
        if (!metric.isPassable(sourceX, sourceZ)) {
            // 2026-11-14 修复（审计 NEW-1）：源点落在硬障碍格时**不抛异常**。
            // 结构与目标点完全对称：目标点不可通行时 FMM 正常输出不可达时间场，
            // BandRouteSolver 返回 unreachable()，RawPathSearch 走降级重搜；
            // 源点也必须走同一条路 —— 否则一个村庄边缘的端点会瘫痪整片区域的规划。
            LOGGER.warn("RoadWeaver FMM: 源点 ({},{}) 是硬不可通行格，返回不可达场（上层将走降级重搜）",
                    sourceX, sourceZ);
            return ArrivalTimeField.unreachable(minX, minZ, w, d, step, metric);
        }

        double[] t = new double[w * d];
        Arrays.fill(t, UNREACHABLE);
        boolean[] frozen = new boolean[w * d];

        int sourceIndex = ((sourceX - minX) / step) + ((sourceZ - minZ) / step) * w;
        t[sourceIndex] = 0.0;

        PriorityQueue<Entry> queue = new PriorityQueue<>();
        queue.add(new Entry(0.0, sourceIndex));

        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            if (frozen[entry.index]) continue;          // 过期条目
            frozen[entry.index] = true;

            int ix = entry.index % w;
            int iz = entry.index / w;
            int x = minX + ix * step;
            int z = minZ + iz * step;

            for (int dir = 0; dir < 4; dir++) {
                int nix = ix + DX[dir];
                int niz = iz + DZ[dir];
                if (nix < 0 || nix >= w || niz < 0 || niz >= d) continue;
                int nIndex = nix + niz * w;
                if (frozen[nIndex]) continue;

                // 硬不可通行的格子**不进队列**，而不是给一个很大的代价 ——
                // 只有这样「不可达」才是真的不可达，拓扑层才能判断
                // 「这个端点根本连不上」而不是看到一个天文数字假装连通。
                //
                // ★ 2026-11-13 修复：这里曾写成 `isPassable(minX + nix, minZ + niz)`，
                // 漏乘了 step —— 网格下标被当成方块坐标，step=8 时硬障碍检查
                // 落在离目标 8 倍近的错误位置，等价于硬约束大面积失明。
                if (!metric.isPassable(minX + nix * step, minZ + niz * step)) continue;

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
                //
                // ★ 2026-11-13：各向异性化 —— 慢度从「每格一个标量」换成
                // 「每格每轴一个」：x 轴用 cost(·, 1, 0)、z 轴用 cost(·, 0, 1)。
                // 这正是 Finsler 度量 c(x,θ̂) 的轴对齐特例（沿 x 走只付 gx² 的坡罚，
                // 沿 z 走只付 gz²）—— 沿等高线走（∇h·θ̂≈0）天然比直冲坡顶便宜。
                // 数值上用对角各向异性 fast marching（每轴一个慢度的 Godunov 格式），
                // 是各向异性 Eikonal 里已验证稳定的离散，不需要 Ordered Upwind。
                // 平地（∇h=0、无障碍）严格退化为原各向同性解。
                int bx = minX + nix * step;
                int bz = minZ + niz * step;
                double hx = Math.max(1e-9, step * metric.cost(bx, bz, 1.0, 0.0));
                double hz = Math.max(1e-9, step * metric.cost(bx, bz, 0.0, 1.0));
                double candidate = solveEikonalAnisotropic(t, w, d, nix, niz, hx, hz);
                if (candidate < t[nIndex]) {
                    t[nIndex] = candidate;
                    queue.add(new Entry(candidate, nIndex));
                }
            }
        }

        return new ArrivalTimeField(t, minX, minZ, w, d, step, metric);
    }

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    /**
     * 各向异性 Godunov 迎风（网格步长各轴分别为 hx/hz）。
     *
     * <p>解 {@code max(T−a,0)²/hx² + max(T−b,0)²/hz² = 1}，
     * 其中 {@code a} 是 x 方向最近邻、{@code b} 是 z 方向最近邻的到达时间，
     * {@code hx/hz} 是「沿该轴走一格的代价」。
     * 平地且 hx=hz 时严格退化为各向同性解。</p>
     */
    private static double solveEikonalAnisotropic(double[] t, int w, int d,
                                                  int ix, int iz, double hx, double hz) {
        double a = Math.min(
                ix > 0 ? t[ix - 1 + iz * w] : UNREACHABLE,
                ix < w - 1 ? t[ix + 1 + iz * w] : UNREACHABLE);
        double b = Math.min(
                iz > 0 ? t[ix + (iz - 1) * w] : UNREACHABLE,
                iz < d - 1 ? t[ix + (iz + 1) * w] : UNREACHABLE);

        if (a == UNREACHABLE && b == UNREACHABLE) return UNREACHABLE;
        if (a == UNREACHABLE) return b + hz;
        if (b == UNREACHABLE) return a + hx;

        // 双轴联合解（T−a）²/hx² + (T−b)²/hz² = 1 的较大根
        double ix2 = 1.0 / (hx * hx);
        double iz2 = 1.0 / (hz * hz);
        double A = ix2 + iz2;
        double B = -2.0 * (a * ix2 + b * iz2);
        double C = a * a * ix2 + b * b * iz2 - 1.0;
        double disc = B * B - 4.0 * A * C;
        if (disc >= 0) {
            double root = (-B + Math.sqrt(disc)) / (2.0 * A);
            // 迎风电验：根必须不小于两个邻居（否则信息不是从邻居流过来的）
            if (root >= a && root >= b) return root;
        }
        // 退化：单轴推进
        return Math.min(a + hx, b + hz);
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
