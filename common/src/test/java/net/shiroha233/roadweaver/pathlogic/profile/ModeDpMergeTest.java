/* 文件职责：钉住「桥隧回写只替换桥隧段、其余跨度必须保留」这条安全边界。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥隧回写合并测试（2026-11-15）。
 *
 * <h2>它守的是「不许丢功能」这条底线</h2>
 * <p>把 DP 判出的桥/隧道回写成生产跨度时，最容易犯的错是<b>整个列表替换</b>。
 * 那样会连带删掉 DP 根本不认识的五种跨度：</p>
 * <pre>
 *   ROAD_CROSSING / JUNCTION_ANCHOR / TUNNEL_JUNCTION /
 *   NETWORK_REUSE / PROTECTED_GAP
 * </pre>
 * <p>这五种来自路网复用、保护缺口、路口锚点等完全独立的判定逻辑。
 * 删掉它们的后果不是「路难看」，而是<b>保护缺口消失（路穿建筑）、
 * 路网复用失效（同一段被铺两遍）</b>—— 属于静默的功能丢失。</p>
 *
 * <h2>另一个容易搞错的点</h2>
 * <p>{@code ROAD_CROSSING} 字面上是桥（路跨路要架桥），但它的语义是
 * 「跨的是另一条路」，施工侧据此走路口接驳而不是普通过水桥。
 * DP 的 modes 里没有这个区分，所以<b>它必须被保留而不是被 DP 的 BRIDGE 覆盖</b>。</p>
 */
class ModeDpMergeTest {

    private static List<BlockPos> line(int n, int step) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(i * step, 64, 0));
        return out;
    }

    private static RoadSpan span(int fromIndex, int toIndex, int step, SpanType type) {
        return new RoadSpan(new BlockPos(fromIndex * step, 64, 0),
                new BlockPos(toIndex * step, 64, 0), type);
    }

    /** DP 模式序列：ground 若干 + 中间一段 bridge。 */
    private static ModeDpSolver.Mode[] bridgeAt(int n, int from, int to) {
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[n];
        for (int i = 0; i < n; i++) {
            modes[i] = (i >= from && i <= to)
                    ? ModeDpSolver.Mode.BRIDGE
                    : ModeDpSolver.Mode.GROUND;
        }
        return modes;
    }

    @Test
    void 非桥隧跨度必须原样保留() {
        int n = 10, step = 4;
        List<BlockPos> centers = line(n, step);
        List<RoadSpan> legacy = List.of(
                span(1, 2, step, SpanType.PROTECTED_GAP),
                span(4, 5, step, SpanType.NETWORK_REUSE),
                span(7, 7, step, SpanType.JUNCTION_ANCHOR));

        // DP 认为全程都是地面 —— 桥隧段全被清掉，但上面三种必须还在
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[n];
        java.util.Arrays.fill(modes, ModeDpSolver.Mode.GROUND);

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(legacy, modes, centers, n);

        assertNotNull(merged);
        assertEquals(3, merged.size(),
                "DP 全判地面时，旧判定的三种非桥隧跨度必须全部保留，实际得到 " + merged);
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.PROTECTED_GAP),
                "PROTECTED_GAP 丢了 ⇒ 路会穿过受保护区域");
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.NETWORK_REUSE),
                "NETWORK_REUSE 丢了 ⇒ 同一段可能被两条路各铺一遍");
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.JUNCTION_ANCHOR),
                "JUNCTION_ANCHOR 丢了 ⇒ 路口锚点接线失效");
    }

    @Test
    void 路跨路跨度不能被DP的桥覆盖() {
        int n = 10, step = 4;
        List<BlockPos> centers = line(n, step);
        List<RoadSpan> legacy = List.of(span(3, 4, step, SpanType.ROAD_CROSSING));

        // DP 恰好在同一段判成 BRIDGE
        List<RoadSpan> merged = ModeDpBridge.mergeByModes(
                legacy, bridgeAt(n, 3, 4), centers, n);

        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.ROAD_CROSSING),
                "ROAD_CROSSING 被 DP 的 BRIDGE 覆盖了 —— 它携带「跨的是另一条路」的语义，"
                        + "施工侧据此走路口接驳而不是过水桥，不能丢");
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.BRIDGE),
                "DP 判出的 BRIDGE 也应该在（两者语义不同，允许并存）");
    }

    @Test
    void 旧桥隧跨度被DP结果替换() {
        int n = 10, step = 4;
        List<BlockPos> centers = line(n, step);
        // 旧判定说 1..3 是桥、6..8 是隧道
        List<RoadSpan> legacy = List.of(
                span(1, 3, step, SpanType.BRIDGE),
                span(6, 8, step, SpanType.TUNNEL));

        // DP 认为只有 5..6 是桥，其余都是地面
        List<RoadSpan> merged = ModeDpBridge.mergeByModes(
                legacy, bridgeAt(n, 5, 6), centers, n);

        List<RoadSpan> bridges = merged.stream()
                .filter(s -> s.type() == SpanType.BRIDGE).toList();
        assertEquals(1, bridges.size(),
                "旧的两段桥隧必须被替换掉，只留 DP 判出的那一段，实际 " + merged);
        assertTrue(merged.stream().noneMatch(s -> s.type() == SpanType.TUNNEL),
                "DP 没判隧道，旧判定的隧道必须消失（否则会挖掉不该挖的洞）");
        // DP 判的桥应当落在 5..6
        RoadSpan b = bridges.get(0);
        assertEquals(5 * step, b.start().getX(), "桥起点应为第 5 站");
        assertEquals(6 * step, b.end().getX(), "桥终点应为第 6 站");
    }

    @Test
    void 输出按起点站索引升序且确定() {
        int n = 10, step = 4;
        List<BlockPos> centers = line(n, step);
        List<RoadSpan> legacy = new ArrayList<>();
        // 故意乱序放入
        legacy.add(span(8, 8, step, SpanType.PROTECTED_GAP));
        legacy.add(span(2, 2, step, SpanType.NETWORK_REUSE));
        legacy.add(span(5, 5, step, SpanType.JUNCTION_ANCHOR));

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(
                legacy, new ModeDpSolver.Mode[n], centers, n);

        assertEquals(3, merged.size());
        for (int i = 1; i < merged.size(); i++) {
            assertTrue(merged.get(i - 1).start().getX() <= merged.get(i).start().getX(),
                    "跨度必须按起点升序 —— 下游多处按顺序读，顺序不稳会引入偶发差异。"
                            + "第 " + (i - 1) + " 个=" + merged.get(i - 1).start().getX()
                            + " 第 " + i + " 个=" + merged.get(i).start().getX());
        }
    }

    @Test
    void DP模式为空时原样返回旧跨度() {
        int n = 6, step = 4;
        List<BlockPos> centers = line(n, step);
        List<RoadSpan> legacy = List.of(
                span(1, 2, step, SpanType.BRIDGE),
                span(3, 3, step, SpanType.PROTECTED_GAP));

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(legacy, null, centers, n);

        assertEquals(2, merged.size(), "modes 为 null 时必须退化为原样返回旧跨度");
    }

    @Test
    void 旧跨度为空时只返回DP结果() {
        int n = 8, step = 4;
        List<BlockPos> centers = line(n, step);

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(
                null, bridgeAt(n, 2, 3), centers, n);

        assertEquals(1, merged.size());
        assertEquals(SpanType.BRIDGE, merged.get(0).type());
    }

    @Test
    void 空输入返回空列表而不是null() {
        List<RoadSpan> merged = ModeDpBridge.mergeByModes(null, null, null, 0);
        assertNotNull(merged, "永不返回 null —— 调用方不该被迫判空");
        assertTrue(merged.isEmpty());
    }

    @Test
    void 模式数组里的null不能被当成隧道() {
        int n = 5, step = 4;
        List<BlockPos> centers = line(n, step);
        // 全 null 数组（DP 未填或半途失败可能留 null）
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[n];

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(List.of(), modes, centers, n);

        assertTrue(merged.isEmpty(),
                "null 模式必须被当成「无模式」跳过。落进区间收集会因 null != BRIDGE "
                        + "被定成 TUNNEL，凭空多出一条假隧道 —— 这条路径是回写生产跨度的入口，"
                        + "多一条隧道就是多挖一整段山。实际得到：" + merged);
    }

    @Test
    void 部分null的部分桥仍能正确区分() {
        int n = 6, step = 4;
        List<BlockPos> centers = line(n, step);
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[]{
                null, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE,
                null, null, ModeDpSolver.Mode.TUNNEL};

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(List.of(), modes, centers, n);

        assertEquals(2, merged.size(), "null 跳过、桥一段、隧道一段，实际 " + merged);
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.BRIDGE));
        assertTrue(merged.stream().anyMatch(s -> s.type() == SpanType.TUNNEL));
    }

    @Test
    void 全程隧道也能正确生成() {
        int n = 5, step = 4;
        List<BlockPos> centers = line(n, step);
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[n];
        java.util.Arrays.fill(modes, ModeDpSolver.Mode.TUNNEL);

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(List.of(), modes, centers, n);

        assertEquals(1, merged.size(), "连续隧道应合成一个跨度而不是每站一个");
        assertEquals(SpanType.TUNNEL, merged.get(0).type());
        assertEquals(0, merged.get(0).start().getX());
        assertEquals((n - 1) * step, merged.get(0).end().getX());
    }

    @Test
    void 桥隧交替时各段独立且不合并() {
        int n = 7, step = 4;
        List<BlockPos> centers = line(n, step);
        ModeDpSolver.Mode[] modes = new ModeDpSolver.Mode[]{
                ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE,
                ModeDpSolver.Mode.GROUND,
                ModeDpSolver.Mode.TUNNEL,
                ModeDpSolver.Mode.GROUND,
                ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE};

        List<RoadSpan> merged = ModeDpBridge.mergeByModes(List.of(), modes, centers, n);

        assertEquals(3, merged.size(), "桥/地/隧/地/桥应产出 3 个独立跨度，实际 " + merged);
        long bridgeCount = merged.stream().filter(s -> s.type() == SpanType.BRIDGE).count();
        assertEquals(2, bridgeCount, "两段桥不该被合并成一段");
        assertFalse(merged.stream().anyMatch(s -> s.type() == SpanType.TUNNEL
                        && s.start().getX() > s.end().getX()),
                "跨度方向不应颠倒");
    }
}