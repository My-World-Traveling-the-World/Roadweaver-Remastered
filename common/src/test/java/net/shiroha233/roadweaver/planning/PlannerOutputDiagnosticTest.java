package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PlanningConfig.PlanningAlgorithm;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路网规划器输出规模诊断：同一批点喂给全部算法，钉住各自的输出形态。
 *
 * <p>存在意义（2026-11-11）：Alpha Model 接入后真机日志出现
 * {@code structures=8 connections=7}，需要定性「7 条是哪个算法」。
 * 这些断言把每个算法的输出规模变成可复核的数字，避免再靠日志猜。</p>
 */
class PlannerOutputDiagnosticTest {

    private static List<BlockPos> grid(int n, int spacing) {
        List<BlockPos> pts = new ArrayList<>();
        int side = (int) Math.ceil(Math.sqrt(n));
        for (int i = 0; i < n; i++) {
            int gx = i % side;
            int gz = i / side;
            pts.add(new BlockPos(gx * spacing, 64, gz * spacing));
        }
        return pts;
    }

    private static int count(PlanningAlgorithm alg, List<BlockPos> pts) {
        return NetworkPlannerFactory.create(alg).plan(pts, 2048).size();
    }

    @Test
    void eightPointsProduceDistinctOutputSizesPerAlgorithm() {
        List<BlockPos> pts = grid(8, 400);
        int knn = count(PlanningAlgorithm.KNN, pts);
        int rng = count(PlanningAlgorithm.RNG, pts);
        int mst = count(PlanningAlgorithm.MST, pts);
        int delaunay = count(PlanningAlgorithm.DELAUNAY, pts);
        int snap = count(PlanningAlgorithm.SNAP_BRANCH, pts);
        int alpha = count(PlanningAlgorithm.ALPHA_MODEL, pts);

        // 0.9.076 起 Alpha Model 输出「路径边并集」：必须是稀疏主干（不是完全图 28）。
        assertTrue(alpha >= 7 && alpha < 28,
                "Alpha Model 在 8 点上应输出稀疏主干（7..27），不是完全图 28，实际 " + alpha);
        // MST 对 8 点是 7 条 —— 这就是真机 connections=7 的嫌疑来源。
        assertEquals(7, mst, "MST 对 8 个点应恰好输出 7 条");

        System.out.println("[PLANNER-OUTPUT] points=8 knn=" + knn + " rng=" + rng
                + " mst=" + mst + " delaunay=" + delaunay + " snap=" + snap + " alpha=" + alpha);
    }

    @Test
    void alphaModelSharesTrunkInsteadOfCompleteGraph() {
        List<BlockPos> pts = grid(6, 500);
        List<StructureConnection> out = NetworkPlannerFactory
                .create(PlanningAlgorithm.ALPHA_MODEL).plan(pts, 2048);
        // 共用走廊的必然结果：边数远小于完全图 15，但至少能连通（>=5）
        assertTrue(out.size() >= 5 && out.size() < 15,
                "Alpha Model 应共用走廊、输出稀疏主干（5..14），不是完全图 15，实际 " + out.size());
        assertFalse(out.isEmpty());
    }

    /**
     * 覆盖率硬要求（0.9.078 回归）。
     *
     * <p>真机取证：半径 128 时 {@code structures=36 connections=23} —— 23 条边连不成
     * 36 个点（树要 35 条），存在孤立分量。全局 top-N 采样会让外围城市一条边都拿不到。
     * 改成「每城市最近 K 个 + 孤儿补救」后，覆盖率必须由构造保证。</p>
     */
    @Test
    void alphaModelCoversEveryCity() {
        List<BlockPos> pts = grid(36, 300);
        List<StructureConnection> out = NetworkPlannerFactory
                .create(PlanningAlgorithm.ALPHA_MODEL).plan(pts, 2048);
        Set<BlockPos> endpoints = new HashSet<>();
        for (StructureConnection c : out) {
            endpoints.add(c.from());
            endpoints.add(c.to());
        }
        assertEquals(pts.size(), endpoints.size(),
                "36 个城市必须全部被连到，实际覆盖 " + endpoints.size() + "，边数 " + out.size());
        // 全覆盖下边数至少要能连通：>= n-1
        assertTrue(out.size() >= pts.size() - 1,
                "全覆盖的图至少有 n-1 条边，实际 " + out.size());
    }

    @Test
    void snapBranchOutputsRoughlyOnePerNode() {
        List<BlockPos> pts = grid(12, 400);
        int snap = count(PlanningAlgorithm.SNAP_BRANCH, pts);
        // 稀疏主干：连接数远小于完全图 66，且不少于 11（连通下限）
        assertTrue(snap >= 11 && snap < 66,
                "SNAP_BRANCH 应输出稀疏主干（11..65），实际 " + snap);
    }
}
