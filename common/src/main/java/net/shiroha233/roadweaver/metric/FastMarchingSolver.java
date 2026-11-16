/* 文件职责：在网格上解 Eikonal 方程 |∇T| = 1/c，得到从源点出发的到达时间场。 */
package net.shiroha233.roadweaver.metric;

import java.util.Arrays;

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
    private static final boolean TRACE = "on".equalsIgnoreCase(System.getProperty("roadweaver.fmmTimings"));

    /** 不可达。 */
    static final double UNREACHABLE = Double.POSITIVE_INFINITY;

    private final FinslerMetric metric;
    // 同一求解器的多源传播是串行的，和慢度数组一样复用存储，避免每个入队项新建对象。
    private final Frontier queue = new Frontier();

    // 小网格保留顺序数组，避免把分页查表成本加到日常短路线；大网格不再先分配整场。
    static final int PAGED_THRESHOLD = 1 << 20;
    private final int pagedThreshold;
    private GridLayout cachedGrid;
    private FmmCostCache cachedCosts;

    public FastMarchingSolver(FinslerMetric metric) {
        this(metric, PAGED_THRESHOLD);
    }

    /** 包内入口让离线对拍能在小夹具强制两种存储；生产不增加配置或改变搜索域。 */
    FastMarchingSolver(FinslerMetric metric, int pagedThreshold) {
        if (metric == null) throw new IllegalArgumentException("metric 不能为 null");
        if (pagedThreshold < 0) throw new IllegalArgumentException("negative paging threshold");
        this.metric = metric;
        this.pagedThreshold = pagedThreshold;
    }

    /**
     * 从源点求解到达时间场（跑到队列耗尽）。
     *
     * @param sourceX 源点 x（方块坐标）
     * @param sourceZ 源点 z（方块坐标）
     * @return 到达时间场（不可变快照）
     */
    public ArrivalTimeField solve(int sourceX, int sourceZ) {
        return solveInternal(sourceX, sourceZ, null);
    }

    /**
     * 只求解到「目标格被冻结」为止（2026-11-14，审计「目标早停」）。
     *
     * <h2>为什么结果与全场求解逐位相同</h2>
     * <p>FMM 按 T 递增弹出并冻结，被冻结的格子其 T 即为最终值。目标格被弹出的那一刻，
     * 所有 {@code t[u] < t_target} 的格都已冻结：{@code t[u]} 是「历次入队键的最小值」，
     * 若它小于 {@code t_target}，对应的队列条目必然先于目标被弹出 ⇒ u 已冻结。</p>
     *
     * <p>而回溯（{@link ArrivalTimeField#pathTo}）只走 {@code nt < bestTime ≤ t_target}
     * 的邻居（严格递减），所以它<b>只会访问已冻结的格</b>，绝不会读到未冻结格的
     * 临时（偏高）值。⇒ 回溯出的路径与全场求解完全相同。</p>
     *
     * <p><b>注意</b>：返回的场里未被冻结的格子保留的是临时值（偏高的上界），
     * 因此只能用于「读目标点 + 回溯」，不要拿 {@link ArrivalTimeField#at} 去读别的点。</p>
     */
    public ArrivalTimeField solve(int sourceX, int sourceZ, int targetX, int targetZ) {
        GridLayout grid = layout(metric.terrain());
        FmmTargets targets = new FmmTargets(grid.arrayLength(), 1);
        targets.add(grid.snapX(targetX) + grid.snapZ(targetZ) * grid.columns());
        return solveInternal(sourceX, sourceZ, targets);
    }

    /**
     * 求解到「给定的全部目标格都冻结」为止（2026-11-14）。
     *
     * <p>给测地距离矩阵用：它只需要矩阵节点位置上的最终 T，不需要把整张场铺完。
     * 若某个目标不可达，它会一直留在未冻结状态，于是队列自然跑空、该目标保持 ∞ ——
     * 与全场求解的结论一致。</p>
     *
     * @param targetsX 目标 x 坐标数组
     * @param targetsZ 目标 z 坐标数组（与 x 等长）
     */
    public ArrivalTimeField solveReachingAll(int sourceX, int sourceZ,
                                             int[] targetsX, int[] targetsZ) {
        if (targetsX == null || targetsZ == null || targetsX.length != targetsZ.length) {
            throw new IllegalArgumentException("目标坐标数组不能为 null 且长度必须相等");
        }
        GridLayout grid = layout(metric.terrain());
        FmmTargets targets = new FmmTargets(grid.arrayLength(), targetsX.length);
        for (int i = 0; i < targetsX.length; i++) {
            targets.add(grid.snapX(targetsX[i]) + grid.snapZ(targetsZ[i]) * grid.columns());
        }
        return solveInternal(sourceX, sourceZ, targets);
    }

    private FmmCostCache costs(GridLayout grid, boolean paged) {
        // 多源串行求解共享同一份不可变度量，页内代价只准备一次；布局也参与复用判据。
        if (!grid.equals(cachedGrid) || cachedCosts == null) {
            cachedCosts = new FmmCostCache(metric, grid, paged);
            cachedGrid = grid;
        }
        return cachedCosts;
    }

    private ArrivalTimeField solveInternal(int sourceX, int sourceZ,
                                           FmmTargets targets) {
        TerrainField terrain = metric.terrain();
        int minX = terrain.minX();
        int minZ = terrain.minZ();
        // 网格尺寸用**采样点数**而不是方块跨度：步长 > 1 时两者不同。
        // 步长只影响网格间距（等距 1 格关系变成 step 格关系），
        // 对外仍然是逐格定义的地形。
        int w = terrain.columns();
        int d = terrain.rows();
        int step = terrain.step();
        // 外部场也必须先过布局检查，不能先调用世界查询或分配缓存再发现溢出。
        int size = layout(terrain).arrayLength();
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

        long began = TRACE ? System.nanoTime() : 0;
        boolean paged = size >= pagedThreshold;
        FmmCostCache costs = costs(layout(terrain), paged);
        long prepared = TRACE ? System.nanoTime() : 0;

        // 2026-11-14 修复（审计 NEW-2）：源点下标原来用向下取整 `(x - minX) / step`，
        // 与障碍场/地形场的 Math.round（就近吸附）口径冲突 —— step=8 时错位最多 7 格，
        // 源点被注入到与它实际位置差一个路宽的格子里。统一为就近吸附 + clamp 兜底。
        GridLayout grid = layout(terrain);
        int sourceIx = grid.snapX(sourceX);
        int sourceIz = grid.snapZ(sourceZ);
        int sourceIndex = sourceIx + sourceIz * w;
        // 真实端点合法不代表吸附格合法；硬禁格不能作为传播起点。
        if (!costs.passable(sourceIndex)) {
            LOGGER.warn("RoadWeaver FMM: 源点 ({},{}) 吸附到硬不可通行格，返回不可达场", sourceX, sourceZ);
            return ArrivalTimeField.unreachable(minX, minZ, w, d, step, metric);
        }

        FmmWorkspace workspace = new FmmWorkspace(size, paged);
        FmmValues t = workspace.values;
        t.set(sourceIndex, 0.0);

        queue.clear();
        queue.offer(0.0, sourceIndex);

        int stopCount = targets == null ? 0 : targets.count();
        int remainingStops = stopCount;
        int frozenCount = 0, offers = 1, peak = 1;

        while (!queue.isEmpty()) {
            int index = queue.poll();
            if (workspace.frozen(index)) continue;          // 过期条目
            workspace.freeze(index);
            if (TRACE) frozenCount++;

            // 目标早停：目标格冻结即为最终值，且回溯只会访问更小的 T（都已冻结）。
            if (targets != null && targets.contains(index)) {
                if (--remainingStops <= 0) break;
            }

            int ix = index % w;
            int iz = index / w;

            int blockedFrom = costs.blockedEdges(index);
            for (int dir = 0; dir < 4; dir++) {
                if ((blockedFrom & (1 << dir)) != 0) continue;
                int nix = ix + DX[dir];
                int niz = iz + DZ[dir];
                if (nix < 0 || nix >= w || niz < 0 || niz >= d) continue;
                int nIndex = nix + niz * w;
                if (workspace.frozen(nIndex)) continue;

                // 硬不可通行的格子**不进队列**，而不是给一个很大的代价 ——
                // 只有这样「不可达」才是真的不可达，拓扑层才能判断
                // 「这个端点根本连不上」而不是看到一个天文数字假装连通。
                //
                // ★ 2026-11-13 修复：这里曾写成 `isPassable(minX + nix, minZ + niz)`，
                // 漏乘了 step —— 网格下标被当成方块坐标，step=8 时硬障碍检查
                // 落在离目标 8 倍近的错误位置，等价于硬约束大面积失明。
                // 同口径缓存通行性；大网格只查询实际遇到的格，不扫描搜索域外的页。
                if (!costs.passable(nIndex)) continue;

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
                costs.prepare(nIndex);
                double hx = costs.costX(nIndex);
                double hz = costs.costZ(nIndex);
                int blockedAt = costs.blockedEdges(nIndex);
                double candidate = solveEikonalAnisotropic(t, w, d, nix, niz, hx, hz, blockedAt);
                if (candidate < t.get(nIndex)) {
                    t.set(nIndex, candidate);
                    queue.offer(candidate, nIndex);
                    if (TRACE) {
                        offers++;
                        peak = Math.max(peak, queue.size);
                    }
                }
            }
        }

        if (TRACE) {
            // 求解耗时在写日志之前截取；多目标和单目标要分组看，不能把工作量变化当提速。
            long totalMicros = (System.nanoTime() - began) / 1000;
            LOGGER.info("[FMM-TIMING] source={}:{} grid={}x{} step={} stops={} remaining={} "
                            + "frozen={} offers={} queuePeak={} prepareUs={} totalUs={}",
                    sourceX, sourceZ, w, d, step, targets == null ? "all" : stopCount, remainingStops,
                    frozenCount, offers, peak, (prepared - began) / 1000, totalMicros);
        }
        return ArrivalTimeField.snapshot(t, minX, minZ, w, d, step, metric);
    }

    private static GridLayout layout(TerrainField terrain) {
        return new GridLayout(terrain.minX(), terrain.minZ(), terrain.columns(), terrain.rows(), terrain.step());
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
    private static double solveEikonalAnisotropic(FmmValues t, int w, int d,
                                                  int ix, int iz, double hx, double hz, int blocked) {
        // 即使当前格是从另一方向到达，也不能从隔着禁行边的邻格偷取更小的到达时间。
        double a = Math.min(
                ix > 0 && (blocked & 2) == 0 ? t.get(ix - 1 + iz * w) : UNREACHABLE,
                ix < w - 1 && (blocked & 1) == 0 ? t.get(ix + 1 + iz * w) : UNREACHABLE);
        double b = Math.min(
                iz > 0 && (blocked & 8) == 0 ? t.get(ix + (iz - 1) * w) : UNREACHABLE,
                iz < d - 1 && (blocked & 4) == 0 ? t.get(ix + (iz + 1) * w) : UNREACHABLE);

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

    // 两个原始数组保存 (到达时间, 格下标)，沿用旧堆的过期条目与相同权重的出队顺序。
    static final class Frontier {
        private double[] times = new double[16];
        private int[] indices = new int[16];
        private int size;

        void clear() { size = 0; }
        boolean isEmpty() { return size == 0; }

        void offer(double time, int index) {
            int hole = size;
            if (hole == times.length) {
                int capacity = hole + (hole >> 1);
                times = Arrays.copyOf(times, capacity);
                indices = Arrays.copyOf(indices, capacity);
            }
            size++;
            while (hole > 0) {
                int parent = (hole - 1) >>> 1;
                // 同权不上浮，与原来 PriorityQueue 的可比较项口径相同。
                if (Double.compare(time, times[parent]) >= 0) break;
                move(parent, hole);
                hole = parent;
            }
            times[hole] = time;
            indices[hole] = index;
        }

        int poll() {
            int result = indices[0];
            int last = --size;
            if (last == 0) return result;
            double time = times[last];
            int index = indices[last];
            int hole = 0;
            while (hole < (size >>> 1)) {
                int child = (hole << 1) + 1;
                int right = child + 1;
                // 同权时保留左子，末元素同权则不再下沉；早停时哪一格被冻结取决于此。
                if (right < size && Double.compare(times[right], times[child]) < 0) child = right;
                if (Double.compare(time, times[child]) <= 0) break;
                move(child, hole);
                hole = child;
            }
            times[hole] = time;
            indices[hole] = index;
            return result;
        }

        private void move(int from, int to) {
            times[to] = times[from];
            indices[to] = indices[from];
        }
    }
}
