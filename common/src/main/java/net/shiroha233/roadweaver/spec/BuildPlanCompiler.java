/* 文件职责：把 RoadSpec + 世界信息翻译成 BuildPlan —— 纯函数，不读世界、不放方块。 */
package net.shiroha233.roadweaver.spec;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

/**
 * {@link BuildPlan} 的编译器：<b>纯函数式</b>地把「路 + 世界信息」翻译成「每列该做什么」。
 *
 * <h2>职责边界（这是整个类最重要的部分）</h2>
 * <pre>
 *   谁负责收集世界信息 ──► 调用方（它知道 world、保护判定、桥隧归属）
 *   谁负责决定每列动作 ──► 本类（纯规则，可单测）
 *   谁负责放方块     ──► 施工侧（将来只按 Plan 执行）
 * </pre>
 *
 * <p>把世界信息挡在外面，是这个类唯一的存在理由 —— 它让「判定」第一次变成
 * 可以在纯 JUnit 里跑、可以在代码里读的东西。</p>
 *
 * <h2>规则（与 SegmentPaver 现状对齐）</h2>
 * <ol>
 *   <li><b>保护优先于一切</b>：列的保护等级 ≥2（地表被保护）⇒ {@code SKIP}，
 *       哪怕它同时是桥面/隧道洞身也不铺；</li>
 *   <li><b>隧道</b>：该站属于隧道段 ⇒ {@code TUNNEL_BORE}；</li>
 *   <li><b>桥</b>：该站属于桥段 ⇒ {@code BRIDGE_DECK}；</li>
 *   <li><b>路基保护</b>：等级 1 ⇒ {@code PAVE_SURFACE_ONLY}（只铺面层）；</li>
 *   <li>否则 {@code PAVE_NORMAL}。</li>
 * </ol>
 *
 * <p>注意顺序：桥/隧道判定在「路基保护」之前、在「地表保护」之后。
 * 这与现状一致 —— 地表被保护的东西，桥也不该从它上面压过去。</p>
 *
 * <h2>★ 尚未接线</h2>
 * <p>本类只被测试使用。接线（S7 第 2 级）时要补的输入见 {@link Inputs}。</p>
 */
public final class BuildPlanCompiler {

    private BuildPlanCompiler() {
    }

    /**
     * 编译输入：<b>世界信息全部在这里，编译器自己不碰世界</b>。
     *
     * @param protectionRank  每站每列的保护等级：0 = 无，1 = 路基保护，2 = 地表保护。
     *                        外层按站、内层按列；为 {@code null} 时全部视为 0。
     *                        <p><b>接线时</b>由调用方用 {@code RoadConstructionSafety.isProtected*} 系列填充。</p>
     * @param tunnelStation   每站是否属于隧道段；{@code null} 表示没有
     * @param bridgeStation   每站是否属于桥段；{@code null} 表示没有
     * @param columnY         每站每列的目标高度；{@code null} 时统一用 {@code RoadSpec.profile()}
     *                        （真实施工里这一层来自 {@code SegmentPaver.planGrade} 的横断面压平，
     *                        所以接线时必须传真实值，否则与本类注释里的「对齐现状」不成立）
     */
    public record Inputs(int[][] protectionRank,
                         boolean[] tunnelStation,
                         boolean[] bridgeStation,
                         int[][] columnY) {

        /** 无保护、无桥隧、用站点高度。 */
        public static Inputs plain() {
            return new Inputs(null, null, null, null);
        }
    }

    /**
     * 编译。
     *
     * @throws IllegalArgumentException 输入数组长度与站数不一致（宁可在编译期炸，
     *                                  也不要生成一份错位的施工计划去铺方块）
     */
    public static BuildPlan compile(RoadSpec spec, Inputs inputs) {
        if (spec == null) throw new IllegalArgumentException("spec 不能为 null");
        if (inputs == null) inputs = Inputs.plain();

        List<RoadSegmentPlacement> stations = spec.stations();
        int n = stations.size();
        checkLength(inputs.protectionRank(), n, "protectionRank");
        checkLength(inputs.tunnelStation(), n, "tunnelStation");
        checkLength(inputs.bridgeStation(), n, "bridgeStation");
        checkLength(inputs.columnY(), n, "columnY");

        List<BuildPlan.Column> columns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement station = stations.get(i);
            if (station == null || station.positions() == null) continue;
            List<BlockPos> positions = station.positions();
            boolean tunnel = inputs.tunnelStation() != null && inputs.tunnelStation()[i];
            boolean bridge = inputs.bridgeStation() != null && inputs.bridgeStation()[i];
            for (int j = 0; j < positions.size(); j++) {
                BlockPos p = positions.get(j);
                if (p == null) continue;
                int rank = protectionRankAt(inputs.protectionRank(), i, j);
                int y = columnYAt(inputs.columnY(), i, j, spec.heightAt(i));
                BuildPlan.Kind kind = decide(rank, tunnel, bridge);
                columns.add(new BuildPlan.Column(i, j, p.getX(), y, p.getZ(), kind));
            }
        }
        return new BuildPlan(columns);
    }

    /** 规则本体（顺序见类注释）。 */
    static BuildPlan.Kind decide(int protectionRank, boolean tunnel, boolean bridge) {
        if (protectionRank >= 2) return BuildPlan.Kind.SKIP;
        if (tunnel) return BuildPlan.Kind.TUNNEL_BORE;
        if (bridge) return BuildPlan.Kind.BRIDGE_DECK;
        if (protectionRank == 1) return BuildPlan.Kind.PAVE_SURFACE_ONLY;
        return BuildPlan.Kind.PAVE_NORMAL;
    }

    private static int protectionRankAt(int[][] ranks, int station, int column) {
        if (ranks == null || station >= ranks.length) return 0;
        int[] row = ranks[station];
        if (row == null || column >= row.length) return 0;
        return Math.max(0, row[column]);
    }

    private static int columnYAt(int[][] columnY, int station, int column, int fallback) {
        if (columnY == null || station >= columnY.length) return fallback;
        int[] row = columnY[station];
        if (row == null || column >= row.length) return fallback;
        return row[column];
    }

    private static void checkLength(Object arr, int expected, String name) {
        if (arr == null) return;
        int actual = java.lang.reflect.Array.getLength(arr);
        if (actual != expected) {
            throw new IllegalArgumentException(name + " 长度 " + actual + " 与站数 " + expected
                    + " 不一致 —— 错位的施工计划会去铺错误的列，必须立刻失败");
        }
    }
}