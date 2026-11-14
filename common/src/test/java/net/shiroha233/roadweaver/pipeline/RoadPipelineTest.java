/* 文件职责：端到端验证「度量 → 拓扑 → 几何」三层串起来能产出可用的路网。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.List;

import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.topology.RoadNode;
import net.shiroha233.roadweaver.topology.TopologyPlan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端流水线测试。
 *
 * <h2>为什么这一层最重要</h2>
 * <p>三层各自的单元测试全绿、串起来不工作，是重构里最典型的事故。
 * 这里跑的是<b>完整链路</b>：地形 → 度量 → N 次场求解 → MST → 每条边回溯 →
 * 弹性带平滑 → 质量门，断言的是<b>最终产物</b>（边数、端点、曲率、绕行比）。</p>
 *
 * @see RoadPipeline
 * @see QualityGate
 */
class RoadPipelineTest {

    /** 81×81 平地。 */
    private static GridTerrainField flat(int size) {
        return GridTerrainField.fromFunction(0, 0, size, size, (x, z) -> 0);
    }

    private static List<RoadNode> squareCorners() {
        return List.of(
                new RoadNode("sw", 10, 10),
                new RoadNode("se", 50, 10),
                new RoadNode("ne", 50, 50),
                new RoadNode("nw", 10, 50));
    }

    // ── 1. 完整链路：正方形四角 ────────────────────────────────

    @Test
    void 正方形四角应产出三条边且全部连通() {
        FinslerMetric metric = new FinslerMetric(flat(61), ObstacleField.NONE, 0.0, 0.0);
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults())
                .run(squareCorners());

        assertEquals(4, plan.nodes().size(), "应有 4 个节点");
        assertEquals(3, plan.edgeCount(), "4 个连通节点应有 3 条边");
        assertTrue(plan.fullyConnected(), "应完全连通");
        assertTrue(plan.isolatedNodes().isEmpty(), "平地上不该有孤立节点");

        // 加权绕行比：MST 会挑三条边长（40 格）而不是对角线（56.6），所以约为 1
        assertEquals(1.0, plan.weightedDetourRatio(), 0.05,
                "平地上 MST 走直角边，绕行比应约等于 1");
    }

    @Test
    void 每条曲线的端点必须精确等于节点坐标() {
        FinslerMetric metric = new FinslerMetric(flat(61), ObstacleField.NONE, 0.0, 0.0);
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults())
                .run(squareCorners());

        for (RoadNetworkPlan.RoadEdge edge : plan.edges()) {
            RoadNode from = plan.nodes().get(edge.from());
            RoadNode to = plan.nodes().get(edge.to());

            var start = edge.curve().get(0);
            var end = edge.curve().get(edge.curve().size() - 1);

            assertEquals(from.x(), start.x(), "边 " + edge.from() + "->" + edge.to() + " 起点 x");
            assertEquals(from.z(), start.z(), "边 " + edge.from() + "->" + edge.to() + " 起点 z");
            assertEquals(to.x(), end.x(), "边 " + edge.from() + "->" + edge.to() + " 终点 x");
            assertEquals(to.z(), end.z(), "边 " + edge.from() + "->" + edge.to() + " 终点 z");
        }
    }

    @Test
    void 每条边平滑后曲率不得上升() {
        // 用一张有起伏的地形，让回溯出的路径本来就有折角，平滑才有事可做
        GridTerrainField hilly = GridTerrainField.fromFunction(0, 0, 61, 61, (x, z) -> {
            int h = (int) Math.round(3.0 * Math.sin(x / 5.0) * Math.cos(z / 7.0));
            return h;
        });
        FinslerMetric metric = new FinslerMetric(hilly, ObstacleField.NONE, 1.0, 0.0);
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults())
                .run(squareCorners());

        assertTrue(plan.edgeCount() >= 3, "应有至少 3 条边");
        for (RoadNetworkPlan.RoadEdge edge : plan.edges()) {
            assertTrue(edge.band().curvatureAfter() <= edge.band().curvatureBefore() + 1e-6,
                    "边 " + edge.from() + "->" + edge.to() + " 平滑后曲率上升了："
                            + edge.band().curvatureBefore() + " -> " + edge.band().curvatureAfter());
        }
    }

    // ── 2. 质量门 ──────────────────────────────────────────────

    @Test
    void 正常路网必须通过质量门() {
        FinslerMetric metric = new FinslerMetric(flat(61), ObstacleField.NONE, 0.0, 0.0);
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults())
                .run(squareCorners());

        QualityGate.Report report = QualityGate.evaluate(plan, QualityGate.Thresholds.defaults());
        assertTrue(report.passed(), "正常路网应通过质量门，实际: " + report.describe());
        assertTrue(report.metrics().stream().anyMatch(m -> m.startsWith("weightedDetour=")),
                "报告必须包含绕行比指标（指标不是装饰）");
        assertTrue(report.metrics().stream().anyMatch(m -> m.startsWith("curvature=")),
                "报告必须包含曲率前后对比");
    }

    @Test
    void 有孤立节点时质量门必须拒绝() {
        // 用硬不可通行围一圈，把 (30,30) 完全封死
        GridTerrainField terrain = flat(61);
        ObstacleField ring = new ObstacleField() {
            @Override public double potential(int x, int z) {
                return impassable(x, z) ? 1.0 : 0.0;
            }

            @Override public boolean impassable(int x, int z) {
                int dx = Math.abs(x - 30);
                int dz = Math.abs(z - 30);
                return dx <= 1 && dz <= 1 && (dx == 1 || dz == 1);
            }
        };
        FinslerMetric metric = new FinslerMetric(terrain, ring, 0.0, 8.0);

        List<RoadNode> nodes = List.of(
                new RoadNode("inside", 30, 30),
                new RoadNode("a", 10, 10),
                new RoadNode("b", 50, 10),
                new RoadNode("c", 30, 55));
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults()).run(nodes);

        assertFalse(plan.isolatedNodes().isEmpty(), "应检测到孤立节点");

        QualityGate.Report strict = QualityGate.evaluate(plan, QualityGate.Thresholds.defaults());
        assertFalse(strict.passed(), "有孤立节点时默认阈值必须拒绝");
        assertTrue(strict.failures().stream().anyMatch(f -> f.contains("孤立节点")),
                "拒绝原因必须说明是孤立节点，实际: " + strict.failures());

        QualityGate.Report lenient = QualityGate.evaluate(plan, QualityGate.Thresholds.lenient());
        // 放宽后不再因孤立节点拒绝（可能因其他项拒绝，所以只断言该条原因消失）
        assertFalse(lenient.failures().stream().anyMatch(f -> f.contains("孤立节点")),
                "放宽阈值后不应再因孤立节点拒绝");
    }

    @Test
    void 绕行比超标时质量门必须拒绝() {
        // 造一个必须绕行的地形：一条纵贯山脊，只在两端留缺口
        GridTerrainField terrain = GridTerrainField.fromFunction(0, 0, 61, 61, (x, z) -> {
            if (x >= 29 && x <= 31 && z >= 8 && z <= 52) return 40;
            return 0;
        });
        FinslerMetric metric = new FinslerMetric(terrain, ObstacleField.NONE, 2.0, 0.0);

        List<RoadNode> nodes = List.of(
                new RoadNode("west", 10, 30),
                new RoadNode("east", 50, 30));
        RoadNetworkPlan plan = new RoadPipeline(metric, ElasticBandSolver.defaults()).run(nodes);

        assertEquals(1, plan.edgeCount(), "两个节点一条边");
        double detour = plan.weightedDetourRatio();
        assertTrue(detour > 1.5,
                "必须绕行山脊，绕行比应明显大于 1.5，实际 " + detour);

        QualityGate.Report report = QualityGate.evaluate(plan, QualityGate.Thresholds.defaults());
        assertFalse(report.passed(), "绕行比超标必须被拒绝，实际: " + report.describe());
        assertTrue(report.failures().stream().anyMatch(f -> f.contains("绕行比")),
                "拒绝原因必须说明是绕行比，实际: " + report.failures());
    }

    // ── 3. 确定性 ──────────────────────────────────────────────

    @Test
    void 相同输入必须得到相同路网() {
        FinslerMetric metric = new FinslerMetric(flat(61), ObstacleField.NONE, 0.0, 0.0);
        RoadPipeline pipeline = new RoadPipeline(metric, ElasticBandSolver.defaults());

        RoadNetworkPlan first = pipeline.run(squareCorners());
        RoadNetworkPlan second = pipeline.run(squareCorners());

        assertEquals(first.edgeCount(), second.edgeCount(), "边数必须一致");
        assertEquals(first.totalGeodesicDistance(), second.totalGeodesicDistance(), 1e-9,
                "总测地距离必须一致（流水线不能有随机性）");
        for (int i = 0; i < first.edgeCount(); i++) {
            assertEquals(first.edges().get(i).from(), second.edges().get(i).from(),
                    "第 " + i + " 条边起点必须一致");
            assertEquals(first.edges().get(i).to(), second.edges().get(i).to(),
                    "第 " + i + " 条边终点必须一致");
            assertEquals(first.edges().get(i).curve(), second.edges().get(i).curve(),
                    "第 " + i + " 条边曲线必须逐点一致");
        }
    }

    // ── NEW-3 等价性钉子：只跑拓扑必须与完整流水线产出同一组边 ──────────────

    /**
     * {@link RoadPipeline#planTopology} 与 {@link RoadPipeline#run} 的拓扑轮必须完全等价。
     *
     * <p>审计 NEW-3 的修法是「PipelinePlanner 跳过第二轮几何」—— 这是纯提速，
     * 前提是<b>边集一模一样</b>。这里逐边钉住：起点、终点、测地距离、总距离、
     * 孤立节点集合全部相等。若哪天拓扑轮与几何轮分叉（比如给几何轮加了额外的
     * 剪枝），这个测试会立刻炸。</p>
     */
    @Test
    void 只跑拓扑与完整流水线必须产出同一组边() {
        FinslerMetric metric = new FinslerMetric(flat(61), ObstacleField.NONE, 0.0, 0.0);
        RoadPipeline pipeline = new RoadPipeline(metric, ElasticBandSolver.defaults());

        TopologyPlan topology = pipeline.planTopology(squareCorners());
        RoadNetworkPlan full = pipeline.run(squareCorners());

        assertEquals(full.nodes().size(), topology.nodes().size(), "节点数必须一致");
        assertEquals(full.edgeCount(), topology.edgeCount(), "边数必须一致");
        assertEquals(full.isolatedNodes(), topology.isolatedNodes(), "孤立节点集合必须一致");

        for (int i = 0; i < topology.edgeCount(); i++) {
            RoadNetworkPlan.RoadEdge fullEdge = full.edges().get(i);
            TopologyPlan.Edge topoEdge = topology.edges().get(i);
            assertEquals(fullEdge.from(), topoEdge.from(), "第 " + i + " 条边起点必须一致");
            assertEquals(fullEdge.to(), topoEdge.to(), "第 " + i + " 条边终点必须一致");
            assertEquals(fullEdge.geodesicDistance(), topoEdge.distance(), 1e-9,
                    "第 " + i + " 条边的测地距离必须一致");
        }
    }
}
