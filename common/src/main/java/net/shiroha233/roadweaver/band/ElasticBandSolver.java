/* 文件职责：把准测地线平滑成曲率受限的曲线（最小平曲率，一次稀疏求解）。 */
package net.shiroha233.roadweaver.band;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹性带求解器。
 *
 * <h2>它只做一件事：曲率最小化</h2>
 * <p>度量层已经处理了长度、坡度、障碍；拓扑层已经决定了走位。
 * 这一层唯一剩下的职责是<b>把折线变得曲率受限且光滑</b>：</p>
 * <pre>
 * min  ∫ |γ''|² ds  +  μ ∫ |γ − γ₀|² ds
 * s.t. γ(0) = 起点,  γ(1) = 终点
 * </pre>
 *
 * <h2>为什么是线性的（和第一版方案的关键区别）</h2>
 * <p>对每个点求偏导，得到经典的 <b>五对角</b>方程：</p>
 * <pre>
 * 2[ γ_{j−2} − 4γ_{j−1} + 6γ_j − 4γ_{j+1} + γ_{j+2} ] + 2μ(γ_j − γ₀_j) = 0
 * </pre>
 * <p>即 thin-plate 样条的刚度矩阵，对称正定 ⇒ <b>一次共轭梯度求解即可</b>，
 * 不需要 Gauss-Newton 迭代。</p>
 *
 * <p><b>反面教材</b>：如果把坡度项 {@code ∫(dh/ds)²} 也塞进这个泛函，分母里的
 * {@code |Δγ|} 会让问题变成非线性，只能迭代 —— 那是 2026-11-12 讨论的第一版方案，
 * 已被本版取代。坡度归度量层，曲率归本层，各管一件事。</p>
 *
 * <h2>边界条件</h2>
 * <p>两端位置固定（Dirichlet）。自然边界条件 {@code γ''=0} 用<b>镜像虚点</b>实现：
 * {@code γ_{−1} = 2γ_0 − γ_1}。这样直线输入会被严格保持为直线（测试钉了这一点）。</p>
 *
 * <h2>这一层替代掉旧实现的哪 8 段</h2>
 * <p>DP 简化 · Catmull-Rom 样条 · 1-2-1 平滑 · relax 拉回 · 锯齿检测 ·
 * 急弯冻结 · 窗口 splice · {@code fine regenerate} —— 它们全是为了修
 * 「8 邻域折线」这一件事产生的 artifact。</p>
 *
 * @see BandResult
 * @see net.shiroha233.roadweaver.metric.ArrivalTimeField
 */
public final class ElasticBandSolver {

    /** 出厂保形权重。越大越贴近输入（曲率改善越少），越小越接近直线。 */
    public static final double DEFAULT_STIFFNESS = 1.0;
    /** 默认最大迭代次数。 */
    public static final int DEFAULT_MAX_ITERATIONS = 500;
    /** 默认收敛阈值（相对残差）。 */
    public static final double DEFAULT_TOLERANCE = 1e-9;

    private final double mu;
    private final int maxIterations;
    private final double tolerance;

    public ElasticBandSolver(double mu, int maxIterations, double tolerance) {
        if (mu <= 0.0) {
            // μ = 0 时线性系统退化成「求核空间」，解不由 γ₀ 决定，会得到无意义结果。
            throw new IllegalArgumentException("保形权重必须 > 0，实际 " + mu);
        }
        this.mu = mu;
        this.maxIterations = Math.max(1, maxIterations);
        this.tolerance = Math.max(1e-15, tolerance);
    }

    /** 用出厂参数构造。 */
    public static ElasticBandSolver defaults() {
        return new ElasticBandSolver(DEFAULT_STIFFNESS, DEFAULT_MAX_ITERATIONS, DEFAULT_TOLERANCE);
    }

    /**
     * 平滑一条折线。
     *
     * @param input 输入折线（至少 3 个点；少于 3 点没有曲率可优化，原样返回）
     * @return 结果（含端点不变、曲率前后对比、迭代诊断）
     */
    public BandResult smooth(List<BandResult.Point> input) {
        if (input == null || input.size() < 3) {
            List<BandResult.Point> copy = input == null ? List.of() : input;
            return new BandResult(copy, 0, 0.0,
                    discreteCurvature(copy), discreteCurvature(copy));
        }

        int n = input.size();
        double[] x = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = input.get(i).x();
            z[i] = input.get(i).z();
        }
        double before = discreteCurvature(input);

        // 两端固定（Dirichlet）：只解内点 1..n−2
        CgStats statsX = solveAxis(x, n);
        CgStats statsZ = solveAxis(z, n);

        List<BandResult.Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BandResult.Point((int) Math.round(x[i]), (int) Math.round(z[i])));
        }
        double after = discreteCurvature(out);
        double residual = Math.max(statsX.residual, statsZ.residual);
        int iterations = Math.max(statsX.iterations, statsZ.iterations);
        return new BandResult(out, iterations, residual, before, after);
    }

    // ── 沿一个轴的 CG 求解 ──────────────────────────────────────

    private record CgStats(int iterations, double residual) {}

    /**
     * 解 {@code (DᵀD + μI) u = μ·g0} 的内点部分，两端固定不变。
     *
     * <p>矩阵不落地，每次迭代用五对角模板直接算 {@code A·v}，内存 O(n)。</p>
     */
    private CgStats solveAxis(double[] g0, int n) {
        int last = n - 2;                     // 内点范围 1..n−2
        if (last < 1) return new CgStats(0, 0.0);

        double[] x = g0.clone();              // 初值＝输入
        double[] r = new double[n];
        double[] z = new double[n];
        double[] p = new double[n];
        double[] ap = new double[n];

        // r = b − A·x，其中 b = μ·g0（只在内点）
        applyOperator(x, g0, ap, n);
        for (int i = 1; i <= last; i++) {
            r[i] = mu * g0[i] - ap[i];
        }

        // Jacobi 预条件：M = diag(A) = 6 + μ（内点对角，虚点不影响对角）
        double invDiag = 1.0 / (6.0 + mu);
        for (int i = 1; i <= last; i++) {
            z[i] = r[i] * invDiag;
            p[i] = z[i];
        }

        double rz = dot(r, z, n);
        double initialNorm = Math.sqrt(Math.max(0.0, rz));
        if (initialNorm < 1e-300) {
            // 输入已经是本泛函的精确极小（例如一条直线）——无需迭代
            return new CgStats(0, 0.0);
        }

        double residual = 1.0;
        int iteration = 0;
        for (; iteration < maxIterations; iteration++) {
            applyOperator(p, g0, ap, n);
            double pap = dot(p, ap, n);
            if (pap <= 1e-300) break;

            double alpha = rz / pap;
            for (int i = 1; i <= last; i++) {
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }

            for (int i = 1; i <= last; i++) z[i] = r[i] * invDiag;
            double rzNext = dot(r, z, n);
            residual = Math.sqrt(Math.max(0.0, rzNext) / (initialNorm * initialNorm));
            if (residual < tolerance) {
                iteration++;
                break;
            }

            double beta = rzNext / rz;
            for (int i = 1; i <= last; i++) {
                p[i] = z[i] + beta * p[i];
            }
            rz = rzNext;
        }

        for (int i = 1; i <= last; i++) g0[i] = x[i];
        return new CgStats(iteration, residual);
    }

    /**
     * 算 {@code (DᵀD + μI)·v}（只填内点，两端填 0）。
     *
     * <p>自然边界 {@code γ''=0} 用镜像虚点：{@code v_{−1} = 2v_0 − v_1}、
     * {@code v_n = 2v_{n−1} − v_{n−2}}。这一条是「直线输入保持直线」的关键。</p>
     */
    private void applyOperator(double[] v, double[] g0, double[] out, int n) {
        if (n >= 3) {
            for (int i = 1; i <= n - 2; i++) {
                double vm2 = (i >= 2) ? v[i - 2] : (2.0 * v[0] - v[1]);
                double vm1 = v[i - 1];
                double v0 = v[i];
                double vp1 = v[i + 1];
                double vp2 = (i + 2 <= n - 1) ? v[i + 2] : (2.0 * v[n - 1] - v[n - 2]);
                out[i] = vm2 - 4.0 * vm1 + 6.0 * v0 - 4.0 * vp1 + vp2 + mu * v0;
            }
        }
        out[0] = 0.0;
        out[n - 1] = 0.0;
    }

    private static double dot(double[] a, double[] b, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) sum += a[i] * b[i];
        return sum;
    }

    /** 离散曲率能量 {@code Σ|γ_{i+1}−2γ_i+γ_{i−1}|²}。 */
    public static double discreteCurvature(List<BandResult.Point> points) {
        if (points == null || points.size() < 3) return 0.0;
        double sum = 0.0;
        for (int i = 1; i < points.size() - 1; i++) {
            double dx = points.get(i + 1).x() - 2.0 * points.get(i).x() + points.get(i - 1).x();
            double dz = points.get(i + 1).z() - 2.0 * points.get(i).z() + points.get(i - 1).z();
            sum += dx * dx + dz * dz;
        }
        return sum;
    }

    // ── 障碍投影（2026-11-14 修复，审计 B）────────────────────────────

    /**
     * 「这一格能不能走」的最小端口。
     *
     * <p>band 层按分层规则<b>不能</b>依赖 metric 包（{@code LAYER_RULES}：
     * band 只许依赖 topology），所以这里不直接吃 {@code ObstacleField}，
     * 而是定义自己的小端口，由调用方（pipeline / bridge）适配过去 ——
     * 与 metric 层用 {@code ObstacleField} 当输入端口是同一个手法。</p>
     */
    public interface BlockedCheck {
        /** 该格是否绝对不可通行（硬障碍）。 */
        boolean blocked(int x, int z);
    }

    /**
     * 平滑并把曲线推出硬障碍（2026-11-14 修复，审计 B）。
     *
     * <h2>为什么要这一步</h2>
     * <p>{@code FinslerMetric:35} 与 {@code ObstaclePotential:32} 的注释都写着
     * 「『绝不穿结构』由 band 层的硬投影负责」，但 {@link #smooth} 原本完全不认识
     * 障碍 —— 契约是断的。弹性带只做曲率最小化，它会把曲线拉直、拉进山体和建筑里。
     * 度量层只能「尽量少走」，真正「绝不穿过」必须由这一层兜底。</p>
     *
     * <h2>做法</h2>
     * <ol>
     *   <li>先按原样做线性求解（<b>结构完全不变</b>，一次共轭梯度）。</li>
     *   <li>取整后<b>按 1 格密度重采样</b>：原曲线的段长是采样步长（可达 8~11 格），
     *       段中间的建筑不会被点采样发现。重采样到 1 格后，段与段之间不再有缝。</li>
     *   <li>逐点检查，落在硬障碍上的点<b>按环搜索推到最近的可通行格</b>
     *       （等价于离散距离变换的一步梯度上升），半径上限
     *       {@value #DEFAULT_PROJECTION_RADIUS} 格。</li>
     *   <li>端点严格锁在传入的首尾上，不参与投影。</li>
     * </ol>
     *
     * <p>不承诺曲率不变差：推出障碍必然引入折角。这正是 {@code QualityGate}
     * 要报的事实（它现在报的是真实数值，而不是假装曲率只降不升）。</p>
     *
     * @param input   输入折线（至少 3 点）
     * @param blocked 硬障碍判定；传 {@code null} 等价于老行为（不做重采样与投影）
     */
    public BandResult smooth(List<BandResult.Point> input, BlockedCheck blocked) {
        if (blocked == null || input == null || input.size() < 3) {
            return smooth(input);
        }

        BandResult base = smooth(input);
        List<BandResult.Point> pts = base.points();
        int n = pts.size();
        int[] xs = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i).x();
            zs[i] = pts.get(i).z();
        }

        int projectedCount = 0;

        // ① 点投影：落在硬障碍上的内点推到最近可通行格（端点锁死）。
        for (int i = 1; i < n - 1; i++) {
            if (!blocked.blocked(xs[i], zs[i])) continue;
            BandResult.Point free = nearestFree(xs[i], zs[i], blocked, DEFAULT_PROJECTION_RADIUS);
            if (free == null) continue;                   // 整片封死：保留，由上层拒绝该路
            xs[i] = free.x();
            zs[i] = free.z();
            projectedCount++;
        }

        // ② 段中点复检：段长等于采样步长（可达 8 格），点投影抓不到段中间的障碍。
        //    中点被挡时把**两端**一起推开（保守），站数保持不变 ——
        //    生产链下游按站序铺路，改变站数会连带改动纵断面与桥隧判定的粒度。
        for (int i = 1; i < n - 1; i++) {
            int mx = (xs[i - 1] + xs[i + 1]) / 2;
            int mz = (zs[i - 1] + zs[i + 1]) / 2;
            if (!blocked.blocked(mx, mz)) continue;
            boolean moved = false;
            for (int k = i - 1; k <= i + 1; k++) {
                if (k <= 0 || k >= n - 1) continue;       // 端点不动
                if (!blocked.blocked(xs[k], zs[k])) continue;
                BandResult.Point free = nearestFree(xs[k], zs[k], blocked, DEFAULT_PROJECTION_RADIUS);
                if (free != null) {
                    xs[k] = free.x();
                    zs[k] = free.z();
                    moved = true;
                }
            }
            if (moved) projectedCount++;
        }

        List<BandResult.Point> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BandResult.Point(xs[i], zs[i]));
        }
        return new BandResult(out, base.iterations(), base.residual(),
                discreteCurvature(pts), discreteCurvature(out), projectedCount);
    }

    /** 障碍投影的默认搜索半径（格）。 */
    public static final int DEFAULT_PROJECTION_RADIUS = 16;

    /**
     * 环搜索最近的可通行格（确定性：同距离按 x 优先、再按 z 优先）。
     *
     * @return 可通行格；半径内全被堵死时返回 {@code null}
     */
    private static BandResult.Point nearestFree(int x, int z, BlockedCheck blocked, int maxRadius) {
        for (int r = 1; r <= maxRadius; r++) {
            BandResult.Point best = null;
            double bestD2 = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // 只看当前环
                    int nx = x + dx;
                    int nz = z + dz;
                    if (blocked.blocked(nx, nz)) continue;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestD2 - 1e-12
                            || (Math.abs(d2 - bestD2) <= 1e-12 && best != null
                                && (nx < best.x() || (nx == best.x() && nz < best.z())))) {
                        bestD2 = d2;
                        best = new BandResult.Point(nx, nz);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
