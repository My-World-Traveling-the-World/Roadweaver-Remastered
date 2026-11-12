package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「多路汇聚同一村庄」合并判据（0.9.084，端点聚类）。
 *
 * <h2>为什么换判据</h2>
 * <p>0.9.083 用「几何重合 12 格 + 连续 6 站」找末尾并行，真机实测
 * {@code planReplacements} 返回空 —— 因为三条路是<b>各自绕进同一个村</b>，
 * 几何上真正重合只有 9 站，不是并排贴走。那套判据是为平行双桥设计的。</p>
 *
 * <p>现在的判据只看拓扑：同一村庄端点上有几条连接、谁最长。
 * 端点度数真机分布：度 1 = 11、度 2 = 8、度 3 = 3、度 4 = 1。</p>
 *
 * <h2>本类覆盖的形态（对应真机与用户提出的全部情况）</h2>
 * <ol>
 *   <li>两条路去同一村庄</li>
 *   <li>两条路从同一村庄出发</li>
 *   <li>三条路汇聚</li>
 *   <li>四条路汇聚</li>
 *   <li>去不同村庄（必须不碰）</li>
 *   <li>中间交叉、端点各不相同（必须不碰 —— 正常路口）</li>
 *   <li>主干太短，退不到村庄外 64 格（必须不碰）</li>
 *   <li>A、B 先交叉、C 又进来（同一簇）</li>
 * </ol>
 */
class VillageEndpointMergeTest {

    private static List<BlockPos> route(BlockPos from, BlockPos to, int stations) {
        List<BlockPos> path = new ArrayList<>(stations);
        if (stations <= 0) return path;
        if (stations == 1) { path.add(from); return path; }
        for (int i = 0; i < stations; i++) {
            double t = (double) i / (stations - 1);
            path.add(new BlockPos(
                    (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    from.getY(),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t)));
        }
        return path;
    }

    private static Map<StructureConnection, List<BlockPos>> graph(Object... pairs) {
        Map<StructureConnection, List<BlockPos>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((StructureConnection) pairs[i], cast(pairs[i + 1]));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> cast(Object value) {
        return (List<BlockPos>) value;
    }

    /** 核心不变量：每个被合并掉的村庄端点，必须仍被至少一条未改写的连接覆盖。 */
    private static void assertNoVillageOrphaned(Map<StructureConnection, List<BlockPos>> graph,
                                                Map<StructureConnection, StructureConnection> replaced) {
        for (StructureConnection old : replaced.keySet()) {
            StructureConnection now = replaced.get(old);
            BlockPos dropped = old.from().getX() == now.from().getX() && old.from().getZ() == now.from().getZ()
                    ? old.to() : old.from();
            boolean covered = false;
            for (StructureConnection survivor : graph.keySet()) {
                if (replaced.containsKey(survivor)) continue;
                if (same(survivor.from(), dropped) || same(survivor.to(), dropped)) { covered = true; break; }
            }
            assertTrue(covered, "村庄 " + dropped + " 被合并后再没有任何路覆盖它");
        }
    }

    private static boolean same(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    // ───────────── 情况 1：两条路去同一村庄 ─────────────

    @Test
    void twoRoutesIntoSameVillageMergeIntoTheLonger() {
        BlockPos village = new BlockPos(2000, 70, 0);
        StructureConnection longer = new StructureConnection(new BlockPos(0, 70, 0), village);
        StructureConnection shorter = new StructureConnection(new BlockPos(0, 70, 400), village);
        Map<StructureConnection, List<BlockPos>> graph = graph(
                longer, route(longer.from(), longer.to(), 300),
                shorter, route(shorter.from(), shorter.to(), 200));

        Map<StructureConnection, StructureConnection> replaced = RoadOverlapTruncator.planReplacements(graph);

        assertEquals(1, replaced.size(), "两条去同一村庄，较短的应被合并");
        assertFalse(replaced.containsKey(longer), "更长的必须作主干，不能被改");
        StructureConnection merged = replaced.get(shorter);
        assertEquals(shorter.from(), merged.from(), "起点（离开村庄的那头）必须保持不变");
        assertFalse(same(merged.to(), village), "终点必须已经离开村庄");
        assertNoVillageOrphaned(graph, replaced);
    }

    // ───────────── 情况 2：两条路从同一村庄出发 ─────────────

    @Test
    void twoRoutesOutOfSameVillageMergeAtTheStart() {
        BlockPos village = new BlockPos(0, 70, 0);
        StructureConnection longer = new StructureConnection(village, new BlockPos(2400, 70, 0));
        StructureConnection shorter = new StructureConnection(village, new BlockPos(1600, 70, 0));
        Map<StructureConnection, List<BlockPos>> graph = graph(
                longer, route(longer.from(), longer.to(), 300),
                shorter, route(shorter.from(), shorter.to(), 200));

        Map<StructureConnection, StructureConnection> replaced = RoadOverlapTruncator.planReplacements(graph);

        assertEquals(1, replaced.size());
        StructureConnection merged = replaced.get(shorter);
        assertEquals(shorter.to(), merged.to(), "终点必须保持不变");
        assertFalse(same(merged.from(), village), "起点必须已经离开村庄");
        assertNoVillageOrphaned(graph, replaced);
    }

    // ───────────── 情况 3：三条路汇聚 ─────────────

    @Test
    void threeRoutesIntoOneVillageMergeAllButTheLongest() {
        // 真机形态：三条汇聚 (1936,-400)，站数 2441 / 1570 / 557
        BlockPos village = new BlockPos(1936, 70, -400);
        StructureConnection longest = new StructureConnection(new BlockPos(2880, 70, -1152), village);
        StructureConnection middle = new StructureConnection(village, new BlockPos(2464, 70, -672));
        StructureConnection shortest = new StructureConnection(new BlockPos(1728, 70, -544), village);
        Map<StructureConnection, List<BlockPos>> graph = graph(
                longest, route(longest.from(), longest.to(), 2441),
                middle, route(middle.from(), middle.to(), 1570),
                shortest, route(shortest.from(), shortest.to(), 557));

        Map<StructureConnection, StructureConnection> replaced = RoadOverlapTruncator.planReplacements(graph);

        assertEquals(2, replaced.size(), "三路汇聚应合并掉较短的两条");
        assertFalse(replaced.containsKey(longest), "最长的那条必须作主干");
        assertNoVillageOrphaned(graph, replaced);
    }

    // ───────────── 情况 4：四条路汇聚 ─────────────

    @Test
    void fourRoutesIntoOneVillage() {
        BlockPos village = new BlockPos(0, 70, 0);
        StructureConnection a = new StructureConnection(new BlockPos(-2000, 70, 0), village);
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, -2000), village);
        StructureConnection c = new StructureConnection(new BlockPos(1500, 70, 1500), village);
        StructureConnection d = new StructureConnection(new BlockPos(800, 70, 800), village);
        Map<StructureConnection, List<BlockPos>> graph = graph(
                a, route(a.from(), a.to(), 260),
                b, route(b.from(), b.to(), 260),
                c, route(c.from(), c.to(), 220),
                d, route(d.from(), d.to(), 120));

        Map<StructureConnection, StructureConnection> replaced = RoadOverlapTruncator.planReplacements(graph);

        assertEquals(3, replaced.size(), "四条汇聚应合并掉三条，只留最长进村");
        assertNoVillageOrphaned(graph, replaced);
    }

    // ───────────── 情况 5：去不同村庄（必须不碰）─────────────

    @Test
    void differentVillagesAreNeverTouched() {
        StructureConnection a = new StructureConnection(new BlockPos(0, 70, 0), new BlockPos(800, 70, 0));
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, 200), new BlockPos(800, 70, 400));
        assertTrue(RoadOverlapTruncator.planReplacements(graph(
                a, route(a.from(), a.to(), 100),
                b, route(b.from(), b.to(), 100))).isEmpty(),
                "端点各不相同，没有任何村庄被多条路汇聚 —— 不许动");
    }

    // ───────────── 情况 6：中间交叉（必须不碰）─────────────

    @Test
    void crossingInTheMiddleIsNeverTouched() {
        // 两条路在中间交叉，但四个端点互不相同 —— 这是正常路口，不是冗余
        BlockPos crossing = new BlockPos(400, 70, 200);
        StructureConnection a = new StructureConnection(new BlockPos(0, 70, 0), new BlockPos(800, 70, 400));
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, 400), new BlockPos(800, 70, 0));
        List<BlockPos> aPath = route(a.from(), a.to(), 100);
        List<BlockPos> bPath = route(b.from(), b.to(), 100);
        // 确认它们真的在中间交叉（几何上相交）
        assertTrue(aPath.contains(crossing) || Math.abs(aPath.get(50).getZ() - bPath.get(50).getZ()) < 200,
                "构造前提：两条路在中间接近");

        assertTrue(RoadOverlapTruncator.planReplacements(graph(a, aPath, b, bPath)).isEmpty(),
                "中间交叉、端点不同，绝不能动 —— 那是正常路口");
    }

    // ───────────── 情况 7：主干太短 ─────────────

    @Test
    void trunkTooShortToRetreatIsLeftAlone() {
        // 两个端点相距只有 40 格，主干退不到村庄外 64 格
        BlockPos village = new BlockPos(0, 70, 0);
        StructureConnection a = new StructureConnection(new BlockPos(40, 70, 0), village);
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, 40), village);
        assertTrue(RoadOverlapTruncator.planReplacements(graph(
                a, route(a.from(), a.to(), 10),
                b, route(b.from(), b.to(), 10))).isEmpty(),
                "主干不足以退到村庄外 64 格时不动");
    }

    // ───────────── 情况 8：A、B 先交叉、C 又进来 ─────────────

    @Test
    void chainedConvergenceStillLeavesEveryVillageConnected() {
        // A→C、B→C、D→C 三条都进同一个村庄：C 只留一条，但 A/B/D 三个起点都必须还在
        BlockPos village = new BlockPos(1200, 70, 0);
        StructureConnection a = new StructureConnection(new BlockPos(0, 70, 0), village);
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, 600), village);
        StructureConnection d = new StructureConnection(new BlockPos(-600, 70, -600), village);
        Map<StructureConnection, List<BlockPos>> graph = graph(
                a, route(a.from(), a.to(), 160),
                b, route(b.from(), b.to(), 200),
                d, route(d.from(), d.to(), 200));

        Map<StructureConnection, StructureConnection> replaced = RoadOverlapTruncator.planReplacements(graph);
        assertEquals(2, replaced.size());
        assertNoVillageOrphaned(graph, replaced);
        for (StructureConnection merged : replaced.values()) {
            assertNotNull(merged.from());
            assertNotNull(merged.to());
        }
    }

    // ───────────── 退化与幂等 ─────────────

    @Test
    void degenerateInputsAreSafe() {
        assertTrue(RoadOverlapTruncator.planReplacements(null).isEmpty());
        assertTrue(RoadOverlapTruncator.planReplacements(Map.of()).isEmpty());
        StructureConnection only = new StructureConnection(new BlockPos(0, 70, 0), new BlockPos(800, 70, 0));
        assertTrue(RoadOverlapTruncator.planReplacements(graph(only, route(only.from(), only.to(), 100))).isEmpty());
    }

    @Test
    void decisionIsDeterministic() {
        BlockPos village = new BlockPos(1600, 70, 0);
        StructureConnection a = new StructureConnection(new BlockPos(0, 70, 0), village);
        StructureConnection b = new StructureConnection(new BlockPos(0, 70, 400), village);
        Map<StructureConnection, List<BlockPos>> graph = graph(
                a, route(a.from(), a.to(), 200),
                b, route(b.from(), b.to(), 120));
        assertEquals(RoadOverlapTruncator.planReplacements(graph),
                RoadOverlapTruncator.planReplacements(graph),
                "纯函数重复调用必须完全一致");
    }

    @Test
    void mergePointSitsRoughlySixtyFourBlocksFromTheVillage() {
        BlockPos village = new BlockPos(2000, 70, 0);
        StructureConnection longer = new StructureConnection(new BlockPos(0, 70, 0), village);
        List<BlockPos> longerPath = route(longer.from(), longer.to(), 300);   // 8 格/站
        BlockPos merge = RoadOverlapTruncator.pointAtDistanceFrom(
                longerPath, RoadOverlapTruncator.Column.class.cast(
                        new RoadOverlapTruncator.Column(village.getX(), village.getZ())), 64);
        assertNotNull(merge, "300 站 × 8 格远大于 64 格，必须能退出来");
        double distance = Math.hypot(merge.getX() - village.getX(), merge.getZ() - village.getZ());
        assertTrue(distance >= 56 && distance <= 72,
                "合并点应落在离村庄约 64 格处，实际 " + distance);
    }
}
