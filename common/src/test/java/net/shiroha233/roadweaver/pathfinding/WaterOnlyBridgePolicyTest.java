package net.shiroha233.roadweaver.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.List;

/**
 * 干洼地的跨越策略：窄的用法（ROAD_CROSSING，过水路面），宽的架桥；
 * 浅到不需要跨越的干洼地**不能**产生任何 span（否则就是「路上莫名多出一座桥」）。
 *
 * <h2>★ 2026-09-25 修正（第三轮审计 R3-P0 的复查结果）</h2>
 * 本测试原先用「64 表面 / 59 洼底 = 深度 5」的数据，却断言应当产出
 * {@code ROAD_CROSSING}。而实现的门槛是 {@code dryDepressionBridgeDepth}
 * （默认 6）：
 * <pre>
 *   unsafeFill[i] = targetY[i] - 地表[i] &gt;= 门槛
 *   // 深度 5 &gt;= 6 == false  →  unsafeFill 全 false  →  不产生任何 span
 * </pre>
 * 于是「窄洼地」与「宽洼地」两条断言**在默认配置下永不可能成立**。
 *
 * <p>已核实这不是回归：{@code PathSpanExtractor} 里干洼地的判定逻辑自
 * {@code 7fbe1cc}（本测试引入的同一个提交）起逐字符未变，门槛默认值也一直是 6，
 * 而本测试文件自引入以来从未被修改 —— 也就是说它**从来没绿过**，
 * 不是被谁的改动弄红的（我把 T-26 的 diff 与该判定逐行比对过，两者不相交）。</p>
 *
 * <p>修正方式：用**实际生效的门槛**构造数据，让它真正测到它声称要测的两件事 ——
 * ①「宽度决定跨越类型」（窄→ROAD_CROSSING、宽→BRIDGE）；
 * ②「浅于门槛的洼地不产生 span」（这条正是用户抱怨的「路上莫名多出一座桥」）。
 * 实现本身未改动。</p>
 */
public final class WaterOnlyBridgePolicyTest {
    private WaterOnlyBridgePolicyTest() {}

    /** 表面高度（centers 与 targetY 的基准）。 */
    private static final int SURFACE = 64;

    /** 实际生效的干洼地架桥门槛（默认 6，玩家可在配置里改到 2..16）。 */
    private static int depthThreshold() {
        try {
            return Math.max(1, net.shiroha233.roadweaver.config.ConfigService.get()
                    .planning().dryDepressionBridgeDepth());
        } catch (Throwable t) {
            return 6;
        }
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int t = depthThreshold();
        System.out.println("WaterOnlyBridgePolicyTest: dryDepressionBridgeDepth=" + t);

        assertShallowNarrowDepressionStaysOrdinary(t);
        assertNarrowDepressionUsesDirectCrossing(t);
        assertWideDepressionUsesBridge(t);
        assertFlatTerrainRemainsOrdinary(t);

        System.out.println("WaterOnlyBridgePolicyTest: PASS");
    }

    /**
     * ⑥ 浅于门槛的干洼地**不产生任何 span**（正是「路上莫名多出一座桥」的反面）。
     *
     * <p>深度 = 门槛 − 1，宽度仍是很窄的 10 格 —— 实现应当把它交给普通填方
     * 直接压平走过去，而不是判成桥。这条断言在修正前是**恒真**的
     * （因为原数据深度 5 恰好低于默认门槛 6，意外"通过"了？不 —— 原断言方向相反，
     * 它要求产出 ROAD_CROSSING，所以恒假）。现在明确把它钉住。</p>
     */
    private static void assertShallowNarrowDepressionStaysOrdinary(int threshold) {
        int floor = SURFACE - Math.max(1, threshold - 1);
        List<BlockPos> centers = line(12);
        List<RoadSpan> result = PathSpanExtractor.refineFinalFillBridgeSpans(
                List.of(), centers, endpointHeights(12, SURFACE + 1, SURFACE - 2),
                dryDepression(12, 1, 10, floor));
        if (!result.isEmpty()) {
            throw new AssertionError("a dry depression of depth " + (threshold - 1)
                    + " (below the " + threshold + " threshold) produced "
                    + result.size() + " span(s) instead of being graded: " + result);
        }
    }

    /** ① 达到门槛的**窄**干洼地 → 过水路面（ROAD_CROSSING），且不得判成桥。 */
    private static void assertNarrowDepressionUsesDirectCrossing(int threshold) {
        int floor = SURFACE - threshold;
        List<BlockPos> centers = line(12);
        List<RoadSpan> result = PathSpanExtractor.refineFinalFillBridgeSpans(
                List.of(), centers, endpointHeights(12, SURFACE + 1, SURFACE - 2),
                dryDepression(12, 1, 10, floor));
        if (result.stream().noneMatch(span -> span.type() == SpanType.ROAD_CROSSING)
                || result.stream().anyMatch(span -> span.type() == SpanType.BRIDGE)) {
            throw new AssertionError("narrow (10-block) depression at the depth threshold"
                    + " was not kept as a direct crossing: " + result);
        }
    }

    /** ② 达到门槛的**宽**干洼地 → 桥。 */
    private static void assertWideDepressionUsesBridge(int threshold) {
        int floor = SURFACE - threshold;
        List<BlockPos> centers = line(21);
        List<RoadSpan> result = PathSpanExtractor.refineFinalFillBridgeSpans(
                List.of(), centers, endpointHeights(21, SURFACE + 1, SURFACE - 2),
                dryDepression(21, 1, 19, floor));
        if (result.stream().noneMatch(span -> span.type() == SpanType.BRIDGE)) {
            throw new AssertionError("wide (18-block) dry depression did not create a bridge span: "
                    + result);
        }
    }

    /** ③ 平坦地形不得产生任何跨越 span。 */
    private static void assertFlatTerrainRemainsOrdinary(int threshold) {
        List<BlockPos> centers = line(8);
        List<RoadSpan> result = PathSpanExtractor.refineFinalFillBridgeSpans(
                List.of(), centers, constantHeights(8, SURFACE), constantTerrain(8, SURFACE));
        if (!result.isEmpty()) {
            throw new AssertionError("flat terrain created a crossing span: " + result);
        }
    }

    private static List<BlockPos> line(int count) {
        java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>(count);
        for (int x = 0; x < count; x++) result.add(new BlockPos(x, SURFACE, 0));
        return result;
    }

    private static List<Integer> constantHeights(int count, int height) {
        return java.util.Collections.nCopies(count, height);
    }

    private static List<Integer> endpointHeights(int count, int start, int end) {
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>(
                constantHeights(count, SURFACE));
        result.set(0, start);
        result.set(count - 1, end);
        return result;
    }

    private static int[] constantTerrain(int count, int height) {
        int[] result = new int[count];
        java.util.Arrays.fill(result, height);
        return result;
    }

    private static int[] dryDepression(int count, int start, int end, int floor) {
        int[] result = constantTerrain(count, SURFACE);
        for (int i = start; i <= end; i++) result[i] = floor;
        return result;
    }
}
