/* 文件职责：把质量指标接到「接受 / 拒绝」的决策上，而不是只打日志。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.topology.RoadNode;
import net.shiroha233.roadweaver.topology.TopologyPlan;

/**
 * 质量门。
 *
 * <h2>为什么必须有它</h2>
 * <p>旧实现 {@code RoadQualityMetrics} 的 javadoc 已经写着
 * 「every number on it is a regression guard」，但那些数字<b>只打日志、从不参与决策</b>。
 * 后果是真机实测出来的：{@code fine regenerate}（step=2 整条重跑）把 17 条路里的
 * <b>8 条变长</b>（最多 +42.7%）、<b>9 条 {@code dupXz} 增加</b>（最多 2 → 18），
 * 而它<b>无条件接受</b>了全部结果。</p>
 *
 * <p>质量门的作用就是把「已经算好的指标」接到「接受 / 回退」上：
 * 任何一步产物如果比输入更差，就拒绝它、保留上一版，并打出拒绝原因。</p>
 *
 * <h2>判据</h2>
 * <ul>
 *   <li><b>连通性</b>：不许有孤立节点（除非显式允许）</li>
 *   <li><b>绕行比</b>：加权 {@code Σ测地 / Σ直线} 不得超过阈值</li>
 *   <li><b>曲率</b>：每条边平滑后曲率不得高于平滑前（弹性带不该把路弄更弯）</li>
 *   <li><b>端点</b>：每条曲线的两端必须等于节点坐标（否则接不上）</li>
 * </ul>
 *
 * @see RoadNetworkPlan
 */
public final class QualityGate {

    /** 阈值。每一项都能单独放宽，但默认值来自真机基线。 */
    public record Thresholds(
            double maxDetourRatio,
            boolean allowIsolatedNodes,
            boolean requireCurvatureNonIncreasing) {

        /** 真机基线：旧实现加权绕行比 2.93，目标 ≤1.5。 */
        public static Thresholds defaults() {
            return new Thresholds(1.5, false, true);
        }

        public static Thresholds lenient() {
            return new Thresholds(3.0, true, false);
        }
    }

    /**
     * 检查结果。
     *
     * @param passed   是否全部通过
     * @param failures 未通过项的人类可读原因
     * @param metrics  实测指标（即使通过也返回，供台账记录）
     */
    public record Report(boolean passed,
                         List<String> failures,
                         List<String> metrics) {

        public Report {
            failures = List.copyOf(failures);
            metrics = List.copyOf(metrics);
        }

        /** 便于日志的一行摘要。 */
        public String describe() {
            return (passed ? "PASS" : "FAIL")
                    + " | " + String.join(" ", metrics)
                    + (failures.isEmpty() ? "" : " | 拒绝原因: " + String.join("; ", failures));
        }
    }

    /**
     * 只评估拓扑层 —— 给「不需要曲线」的调用方（2026-11-14，审计 NEW-3）。
     *
     * <p>{@link #evaluate} 的四项判据里有两项依赖几何轮的产物：端点必须等于节点坐标、
     * 平滑后曲率不得上升。只跑拓扑轮的调用方拿不到曲线，硬套 {@link #evaluate} 会得到
     * 一堆「曲线少于 2 点」的<b>假失败</b>（日志满屏 FAIL 却与质量无关）。</p>
     *
     * <p>这里保留<b>能判</b>的两项：连通性（孤立节点）与加权绕行比
     * （Σ测地 / Σ直线，用 {@link TopologyPlan.Edge#distance()} 与实际节点坐标算）。
     * 曲线相关的两项在 metrics 里显式标「几何轮未执行」，而不是假装通过。</p>
     */
    public static Report evaluateTopology(TopologyPlan topology,
                                          List<RoadNode> nodes,
                                          Thresholds thresholds) {
        if (topology == null) throw new IllegalArgumentException("topology 不能为 null");
        if (nodes == null) throw new IllegalArgumentException("nodes 不能为 null");
        Thresholds limits = thresholds == null ? Thresholds.defaults() : thresholds;

        List<String> failures = new ArrayList<>();
        List<String> metrics = new ArrayList<>();

        metrics.add("nodes=" + nodes.size());
        metrics.add("edges=" + topology.edgeCount());
        metrics.add("isolated=" + topology.isolatedNodes().size());

        double sumGeodesic = 0.0;
        double sumStraight = 0.0;
        for (TopologyPlan.Edge edge : topology.edges()) {
            RoadNode from = nodes.get(edge.from());
            RoadNode to = nodes.get(edge.to());
            sumGeodesic += edge.distance();
            sumStraight += Math.hypot((double) to.x() - from.x(), (double) to.z() - from.z());
        }
        double detour = sumStraight <= 1e-9 ? 1.0 : sumGeodesic / sumStraight;
        metrics.add(String.format(java.util.Locale.ROOT, "weightedDetour=%.3f", detour));

        // ① 连通性
        if (!limits.allowIsolatedNodes() && !topology.isolatedNodes().isEmpty()) {
            failures.add("有 " + topology.isolatedNodes().size()
                    + " 个孤立节点（下标 " + topology.isolatedNodes() + "）—— 这些端点连不上");
        }

        // ② 绕行比
        if (detour > limits.maxDetourRatio()) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "加权绕行比 %.3f 超过阈值 %.3f（旧实现真机基线 2.93，目标 ≤1.5）",
                    detour, limits.maxDetourRatio()));
        }

        // ③ 曲线相关的两项在拓扑轮无从判断 —— 显式声明，不静默当作通过
        metrics.add("curveChecks=skipped(topology-only)");

        return new Report(failures.isEmpty(), failures, metrics);
    }

    private QualityGate() {}

    /**
     * 评估一个路网方案。
     */
    public static Report evaluate(RoadNetworkPlan plan, Thresholds thresholds) {
        if (plan == null) throw new IllegalArgumentException("plan 不能为 null");
        Thresholds limits = thresholds == null ? Thresholds.defaults() : thresholds;

        List<String> failures = new ArrayList<>();
        List<String> metrics = new ArrayList<>();

        metrics.add("nodes=" + plan.nodes().size());
        metrics.add("edges=" + plan.edgeCount());
        metrics.add("isolated=" + plan.isolatedNodes().size());
        metrics.add(String.format(java.util.Locale.ROOT,
                "weightedDetour=%.3f", plan.weightedDetourRatio()));

        // ① 连通性
        if (!limits.allowIsolatedNodes() && !plan.isolatedNodes().isEmpty()) {
            failures.add("有 " + plan.isolatedNodes().size()
                    + " 个孤立节点（下标 " + plan.isolatedNodes() + "）—— 这些端点连不上");
        }

        // ② 绕行比
        double detour = plan.weightedDetourRatio();
        if (detour > limits.maxDetourRatio()) {
            failures.add(String.format(java.util.Locale.ROOT,
                    "加权绕行比 %.3f 超过阈值 %.3f（旧实现真机基线 2.93，目标 ≤1.5）",
                    detour, limits.maxDetourRatio()));
        }

        // ③ 每条边：端点、曲率
        double totalBefore = 0.0;
        double totalAfter = 0.0;
        int worstEdge = -1;
        double worstReduction = Double.POSITIVE_INFINITY;

        for (int i = 0; i < plan.edges().size(); i++) {
            RoadNetworkPlan.RoadEdge edge = plan.edges().get(i);

            if (edge.curve().size() < 2) {
                failures.add("边 " + edge.from() + "->" + edge.to() + " 的曲线少于 2 点");
                continue;
            }

            // 端点必须等于节点坐标
            var startNode = plan.nodes().get(edge.from());
            var endNode = plan.nodes().get(edge.to());
            var startPoint = edge.curve().get(0);
            var endPoint = edge.curve().get(edge.curve().size() - 1);
            if (startPoint.x() != startNode.x() || startPoint.z() != startNode.z()) {
                failures.add("边 " + edge.from() + "->" + edge.to()
                        + " 的起点 (" + startPoint.x() + "," + startPoint.z()
                        + ") 与节点 (" + startNode.x() + "," + startNode.z() + ") 不一致");
            }
            if (endPoint.x() != endNode.x() || endPoint.z() != endNode.z()) {
                failures.add("边 " + edge.from() + "->" + edge.to()
                        + " 的终点 (" + endPoint.x() + "," + endPoint.z()
                        + ") 与节点 (" + endNode.x() + "," + endNode.z() + ") 不一致");
            }

            totalBefore += edge.band().curvatureBefore();
            totalAfter += edge.band().curvatureAfter();

            double reduction = edge.band().curvatureReduction();
            if (reduction < worstReduction) {
                worstReduction = reduction;
                worstEdge = i;
            }

            if (limits.requireCurvatureNonIncreasing()
                    && edge.band().curvatureAfter() > edge.band().curvatureBefore() + 1e-6) {
                failures.add("边 " + edge.from() + "->" + edge.to()
                        + " 平滑后曲率反而上升（" + edge.band().curvatureBefore()
                        + " -> " + edge.band().curvatureAfter() + "）");
            }
        }

        if (plan.edgeCount() > 0) {
            metrics.add(String.format(java.util.Locale.ROOT,
                    "curvature=%.1f->%.1f", totalBefore, totalAfter));
            if (worstEdge >= 0) {
                metrics.add(String.format(java.util.Locale.ROOT,
                        "worstReduction=%.1f%%(edge%d)", worstReduction * 100.0, worstEdge));
            }
        }

        return new Report(failures.isEmpty(), failures, metrics);
    }
}
