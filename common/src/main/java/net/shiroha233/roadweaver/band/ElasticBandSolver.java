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
}
