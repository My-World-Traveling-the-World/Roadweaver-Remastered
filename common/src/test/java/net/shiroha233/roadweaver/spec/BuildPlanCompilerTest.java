/* 文件职责：BuildPlan 编译器的规则测试 —— 判定第一次能在纯 JUnit 里被钉住。 */
package net.shiroha233.roadweaver.spec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildPlanCompiler} 的规则测试。
 *
 * <h2>这批测试的价值不在"覆盖代码"，而在"判定第一次可被钉住"</h2>
 * <p>旧版里「这一列该不该铺」只能在运行时靠日志猜。现在它是纯函数，
 * 于是「地表保护必须优先于桥/隧道」这种容易被后来人改错的顺序，
 * 可以变成一条断言。</p>
 */
final class BuildPlanCompilerTest {

    /** 造一条路：n 站，每站 width 列（x 沿站递增，z 展开宽度）。 */
    private static RoadSpec road(int n, int width) {
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<BlockPos> cols = new ArrayList<>();
            for (int j = 0; j < width; j++) {
                cols.add(new BlockPos(i, 70, j));
            }
            stations.add(new RoadSegmentPlacement(new BlockPos(i, 70, width / 2), cols));
        }
        List<Integer> profile = new ArrayList<>();
        for (int i = 0; i < n; i++) profile.add(70);
        return RoadSpec.of(stations, profile, List.of(),
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    @Test
    void 平地全部正常铺装() {
        BuildPlan plan = BuildPlanCompiler.compile(road(4, 3), BuildPlanCompiler.Inputs.plain());
        assertEquals(12, plan.size(), "case=columns");
        assertEquals(12, plan.count(BuildPlan.Kind.PAVE_NORMAL), "case=all-normal");
        assertEquals(12, plan.placeable(), "case=placeable");
        assertEquals(0, plan.count(BuildPlan.Kind.SKIP), "case=no-skip");
    }

    @Test
    void 地表保护优先于桥与隧道() {
        // ★ 这是最容易改错的顺序：地表被保护的东西，桥也不该从它上面压过去。
        int n = 3;
        int[][] ranks = new int[n][];
        ranks[0] = new int[]{0, 2, 0};      // 中间列地表被保护
        ranks[1] = new int[]{0, 0, 0};
        ranks[2] = new int[]{0, 0, 0};
        boolean[] tunnel = {true, false, false};   // 第 0 站同时是隧道
        boolean[] bridge = {false, true, false};   // 第 1 站是桥

        BuildPlan plan = BuildPlanCompiler.compile(road(n, 3),
                new BuildPlanCompiler.Inputs(ranks, tunnel, bridge, null));

        // 第 0 站第 1 列：地表保护 ⇒ SKIP（哪怕它是隧道）
        BuildPlan.Column c = plan.columns().get(1);
        assertEquals(BuildPlan.Kind.SKIP, c.kind(), "case=protection-wins-over-tunnel");
        // 第 0 站其余列 ⇒ 隧道洞身
        assertEquals(BuildPlan.Kind.TUNNEL_BORE, plan.columns().get(0).kind(), "case=tunnel-kept");
        // 第 1 站 ⇒ 桥面
        assertEquals(BuildPlan.Kind.BRIDGE_DECK, plan.columns().get(4).kind(), "case=bridge-kept");
        assertEquals(1, plan.count(BuildPlan.Kind.SKIP), "case=one-skip");
        assertEquals(2, plan.count(BuildPlan.Kind.TUNNEL_BORE), "case=tunnel-count");
        assertEquals(3, plan.count(BuildPlan.Kind.BRIDGE_DECK), "case=bridge-count");
    }

    @Test
    void 路基保护退化成只铺面层() {
        int n = 2;
        int[][] ranks = new int[n][];
        ranks[0] = new int[]{0, 1, 1};
        ranks[1] = new int[]{0, 0, 0};
        BuildPlan plan = BuildPlanCompiler.compile(road(n, 3),
                new BuildPlanCompiler.Inputs(ranks, null, null, null));
        assertEquals(2, plan.count(BuildPlan.Kind.PAVE_SURFACE_ONLY), "case=surface-only");
        assertEquals(4, plan.count(BuildPlan.Kind.PAVE_NORMAL), "case=rest-normal");
    }

    @Test
    void 逐列高度覆盖生效() {
        int n = 2;
        int[][] columnY = new int[n][];
        columnY[0] = new int[]{71, 72, 73};     // 横断面被压平/错层
        columnY[1] = new int[]{68, 68, 68};
        BuildPlan plan = BuildPlanCompiler.compile(road(n, 3),
                new BuildPlanCompiler.Inputs(null, null, null, columnY));
        assertEquals(71, plan.columns().get(0).y(), "case=y0");
        assertEquals(73, plan.columns().get(2).y(), "case=y2");
        assertEquals(68, plan.columns().get(3).y(), "case=y3");
    }

    @Test
    void 输入长度与站数不一致必须立刻失败() {
        int[][] ranks = new int[][]{{0, 0, 0}};     // 只有 1 站，但路有 3 站
        assertThrows(IllegalArgumentException.class,
                () -> BuildPlanCompiler.compile(road(3, 3),
                        new BuildPlanCompiler.Inputs(ranks, null, null, null)),
                "case=length-mismatch-must-fail");
    }

    @Test
    void 按站分组与汇总可用() {
        BuildPlan plan = BuildPlanCompiler.compile(road(3, 2), BuildPlanCompiler.Inputs.plain());
        List<List<BuildPlan.Column>> byStation = plan.byStation();
        assertEquals(3, byStation.size(), "case=station-groups");
        assertEquals(2, byStation.get(1).size(), "case=cols-per-station");
        assertTrue(plan.summary().contains("cols=6"), "case=summary: " + plan.summary());
    }
}