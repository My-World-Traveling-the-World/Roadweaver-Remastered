/* 文件职责：弹性带求解的输出 —— 平滑后的曲线与收敛诊断。 */
package net.shiroha233.roadweaver.band;

import java.util.List;

/**
 * 弹性带求解结果。
 *
 * <h2>为什么把诊断也返回</h2>
 * <p>「求解器说它收敛了」和「曲线真的变好了」是两件事。旧实现里
 * {@code RoadQualityMetrics} 的 javadoc 写着 every number is a regression guard，
 * 但那些数字只打日志、从不参与决策，于是 {@code fine regenerate} 把 8/17 条路
 * 变长、9 条 {@code dupXz} 增加，全部被无条件接受。</p>
 *
 * <p>所以这里把<b>输入与输出的曲率能量都带回来</b>，让调用方（pipeline 的质量门）
 * 能直接判断「这一步到底有没有变好」，而不是相信求解器。</p>
 *
 * @param points        平滑后的点列（含两端，端点与输入相同）
 * @param iterations    CG 迭代次数
 * @param residual      最终相对残差
 * @param curvatureBefore 输入曲线的离散曲率能量 {@code Σ|γ_{i+1}−2γ_i+γ_{i-1}|²}
 * @param curvatureAfter  输出曲线的离散曲率能量
 */
public record BandResult(List<Point> points,
                         int iterations,
                         double residual,
                         double curvatureBefore,
                         double curvatureAfter) {

    public BandResult {
        points = List.copyOf(points);
    }

    /** 一个二维点（x, z）。 */
    public record Point(int x, int z) {}

    /** 点数。 */
    public int size() { return points.size(); }

    /** 曲率能量是否下降（质量门的最小判据）。 */
    public boolean improved() {
        return curvatureAfter <= curvatureBefore + 1e-9;
    }

    /** 曲率能量下降比例；输入为 0 时返回 0。 */
    public double curvatureReduction() {
        if (curvatureBefore <= 1e-12) return 0.0;
        return 1.0 - curvatureAfter / curvatureBefore;
    }

    /** 便于日志的一行摘要。 */
    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "points=%d iters=%d residual=%.2e curvature %.1f -> %.1f (降 %.1f%%)",
                points.size(), iterations, residual,
                curvatureBefore, curvatureAfter, curvatureReduction() * 100.0);
    }
}
