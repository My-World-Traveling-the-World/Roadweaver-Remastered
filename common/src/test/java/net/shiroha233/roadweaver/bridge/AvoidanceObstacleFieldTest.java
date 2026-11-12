/* 文件职责：验证避让判据被正确采样成势场，且硬挡真的能挡住求解器。 */
package net.shiroha233.roadweaver.bridge;

import java.util.List;

import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pipeline.QualityGate;
import net.shiroha233.roadweaver.pipeline.RoadNetworkPlan;
import net.shiroha233.roadweaver.pipeline.RoadPipeline;
import net.shiroha233.roadweaver.topology.RoadNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 避让判据 → 势场适配器。
 *
 * <h2>这一层是「判据」与「算法」的接缝</h2>
 * <p>接错的表现极其隐蔽：势场只是<b>稍微</b>偏了，路照样能生成，
 * 只是偶尔穿过一栋房子。所以这里逐格断言「判据说挡 ⇒ 势场说不可通行」、
 * 「判据说挡 ⇒ 势为 1」，以及「判据挡满一排 ⇒ 求解器真的过不去」。</p>
 *
 * @see AvoidanceObstacleField
 */
class AvoidanceObstacleFieldTest {

    private static TerrainField flat(int size) {
        return GridTerrainField.fromFunction(0, 0, size, size, (x, z) -> 64);
    }

    // ── 1. 掩码与不可通行必须逐格一一对应 ─────────────────────

    @Test
    void 判据说挡的格子必须不可通行且势为1() {
        // 一个 3x3 的建筑，判据只在 y ∈ [62,70] 且 x,z 都在 20..22 时挡
        int size = 41;
        TerrainField terrain = flat(size);
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, size, size, 2, 8.0,
                (x, y, z) -> x >= 20 && x <= 22 && z >= 20 && z <= 22 && y == 64);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                boolean predicateBlocks = x >= 20 && x <= 22 && z >= 20 && z <= 22;
                assertEquals(predicateBlocks, field.impassable(x, z),
                        "(" + x + "," + z + ") 的不可通行标记与判据不一致");
                if (predicateBlocks) {
                    assertEquals(1.0, field.potential(x, z), 1e-12,
                            "被挡格 (" + x + "," + z + ") 的势必须为 1");
                }
            }
        }
    }

    @Test
    void 势必须随距离衰减且判据不改动势的取法() {
        int size = 41;
        TerrainField terrain = flat(size);
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, size, size, 0, 4.0,
                (x, y, z) -> x == 20 && z == 20);

        assertEquals(1.0, field.potential(20, 20), 1e-12, "障碍格势为 1");
        assertEquals(0.75, field.potential(21, 20), 1e-12, "距离 1、半径 4 → 0.75");
        assertEquals(0.5, field.potential(22, 20), 1e-12, "距离 2 → 0.5");
        assertEquals(0.0, field.potential(24, 20), 1e-12, "距离 4 = 半径 → 0");
        assertFalse(field.impassable(21, 20), "障碍旁边一格不该是不可通行");
    }

    // ── 2. 硬挡必须真的挡住求解器（fail-safe）────────────────

    @Test
    void 判据挡满一列时求解器必须过不去() {
        int size = 41;
        TerrainField terrain = flat(size);
        // x=20 一整列被挡
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, size, size, 0, 4.0,
                (x, y, z) -> x == 20);

        FinslerMetric metric = new FinslerMetric(terrain, field, 0.0, 8.0);
        List<RoadNode> nodes = List.of(
                new RoadNode("west", 5, 20),
                new RoadNode("east", 35, 20));
        RoadNetworkPlan plan = new RoadPipeline(metric, null).run(nodes);

        assertFalse(plan.isolatedNodes().isEmpty(),
                "整列被硬挡时对岸必须不可达 —— 这正是旧实现 PROTECTED_GAP 缺的那条 fail-safe");
        assertEquals(0, plan.edgeCount(), "不可达时不该凭空产出一条边");

        QualityGate.Report report = QualityGate.evaluate(plan, QualityGate.Thresholds.defaults());
        assertFalse(report.passed(), "存在不可达端点时必须被质量门拒绝");
    }

    @Test
    void 判据只挡一段时求解器必须从空隙绕过去() {
        int size = 41;
        TerrainField terrain = flat(size);
        // x=20 这一列只在 z ∈ [10,30] 挡，上下留缺口
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, size, size, 0, 4.0,
                (x, y, z) -> x == 20 && z >= 10 && z <= 30);

        FinslerMetric metric = new FinslerMetric(terrain, field, 0.0, 8.0);
        List<RoadNode> nodes = List.of(
                new RoadNode("west", 5, 20),
                new RoadNode("east", 35, 20));
        RoadNetworkPlan plan = new RoadPipeline(metric, null).run(nodes);

        assertTrue(plan.isolatedNodes().isEmpty(), "有缺口时两端必须可达");
        assertEquals(1, plan.edgeCount(), "应产出一条边");

        // 曲线不得穿过被挡格
        var curve = plan.edges().get(0).curve();
        for (var point : curve) {
            assertFalse(field.impassable(point.x(), point.z()),
                    "曲线穿过了被挡格 (" + point.x() + "," + point.z() + ")");
        }

        // 绕行比应明显大于 1（必须绕）
        assertTrue(plan.weightedDetourRatio() > 1.1,
                "绕缺口应比直线长，实际绕行比 " + plan.weightedDetourRatio());
    }

    // ── 3. 退化输入 ───────────────────────────────────────────

    @Test
    void 没有障碍时势全为零且处处可通行() {
        int size = 21;
        TerrainField terrain = flat(size);
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, size, size, 0, 8.0, (x, y, z) -> false);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                assertEquals(0.0, field.potential(x, z), 1e-12, "无障碍时势应为 0");
                assertFalse(field.impassable(x, z), "无障碍时处处可通行");
            }
        }
    }

    @Test
    void 区域外必须视为不可通行() {
        TerrainField terrain = flat(21);
        ObstacleField field = AvoidanceObstacleField.sample(
                terrain, 0, 0, 21, 21, 0, 8.0, (x, y, z) -> false);

        assertTrue(field.impassable(-1, 0), "区域外必须不可通行");
        assertTrue(field.impassable(21, 0), "区域外必须不可通行");
        assertEquals(1.0, field.potential(-1, 0), 1e-12, "区域外势为 1");
    }
}
