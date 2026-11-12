package net.shiroha233.roadweaver.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.059（②）：{@code candidateBounds} 自适应扩张的等价性回归。
 *
 * <p>钉住三件事（全在纯矩形层面，不需要世界、不需要 Minecraft 运行时）：</p>
 * <ol>
 *   <li><b>admission 等价</b>：结构枚举是纯矩形谓词，所以"初始余量内的结构"在初始档与
 *       全量 6144 档里被枚举到的是<b>同一批</b>——{@link AdaptiveCandidateBounds} 类注释
 *       第 1 条。<b>注意这是"少看结构"唯一可能伤害合规路的途径，它必须永远是空集差。</b></li>
 *   <li><b>合规路不复算</b>：走线落在初始余量内的路，判定结果必须是"不需要扩张"，
 *       于是它不会进入后续任何一轮（等价性第 2 条）。</li>
 *   <li><b>只有越界者才扩张，且扩张有上限</b>：真机 3.63 倍绕行越界约 480 格的那个样例
 *       必须触发一档扩张；扩张到 cap 之后必须停。</li>
 * </ol>
 *
 * <p>真机一局的坐标直接拿来当夹具：{@code coreBounds=[-992,-1088 -> 1056,960]}，
 * 旧 {@code candidateBounds=[-3040,-3136 -> 3104,3008]}（2048 外扩）。</p>
 *
 * <p><b>0.9.060 契约变更</b>：初始余量从「固定 256」换成「相对值」（{@code marginFor}：
 * 核心区对角线 × 0.25，夹在 512..4096，并按面积 cap 守卫）。所以本类里 {@code initial(CORE)}
 * 的期望值从 ±256 变成 ±724 —— 这不是为了让红测试变绿而放宽断言，而是旧期望编码的
 * 恰好是被显式替换掉的那个常量；四档预热规模的守卫行为由
 * {@code AdaptiveCandidateBoundsScaleTest} 单独钉住。</p>
 */
final class AdaptiveCandidateBoundsTest {

    /** 真机一局的核心区。 */
    private static final int[] CORE = {-992, -1088, 1056, 960};
    /** 旧口径的外扩量（{@code RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS}）。 */
    private static final int FULL_MARGIN = 2048;

    private static List<int[]> sampleStructurePoints() {
        List<int[]> points = new ArrayList<>();
        // 核心内
        points.add(new int[]{0, 0});
        points.add(new int[]{-992, -1088});
        points.add(new int[]{1056, 960});
        // 初始余量内、核心外（256 格环带），含四条边界
        points.add(new int[]{-1248, 0});
        points.add(new int[]{1312, 0});
        points.add(new int[]{0, -1344});
        points.add(new int[]{0, 1216});
        points.add(new int[]{1312, 1216});
        // 初始余量外、全量范围内（这正是"被少看掉"的结构，必须只影响越界路）
        points.add(new int[]{1900, 1900});
        points.add(new int[]{-2000, -2000});
        points.add(new int[]{-3040, -3136});
        // 全量范围外
        points.add(new int[]{9000, 9000});
        return points;
    }

    private static List<int[]> admitted(List<int[]> points, AdaptiveCandidateBounds.Rect bounds) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : points) {
            if (bounds.contains(p[0], p[1])) out.add(p);
        }
        return out;
    }

    @Test
    void initialBoundsUseRelativeMarginAndFullBoundsStayAt2048() {
        // 0.9.060：余量不再是固定 256，而是 round(hypot(2049, 2049) × 0.25) = 724（夹在 512..4096）。
        int margin = AdaptiveCandidateBounds.marginFor(CORE);
        assertEquals(724, margin, "真机尺度核心区的相对余量（0.9.060）");

        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(CORE);
        assertEquals(-992 - margin, initial.minX());
        assertEquals(-1088 - margin, initial.minZ());
        assertEquals(1056 + margin, initial.maxX());
        assertEquals(960 + margin, initial.maxZ());

        AdaptiveCandidateBounds.Rect full = AdaptiveCandidateBounds.boundsWithMargin(CORE, FULL_MARGIN);
        // 与真机日志 candidateBounds=[-3040,-3136 -> 3104,3008] 同口径（这里 core 的 z 上下界不同）
        assertEquals(-3040, full.minX());
        assertEquals(3104, full.maxX());

        assertTrue(full.containsAll(initial), "初始档必须是全量档的子集");
        // 0.9.059 的 256 余量在这里是 5 倍以上；0.9.060 的 724 余量把比值压到 3.1 倍，
        // 收窄收益仍在（3 倍面积差），但不能再用 5 倍这个旧阈值。
        assertTrue(full.area() > initial.area() * 3L,
                "旧口径面积应仍为新初始档的 3 倍以上（6145² vs 3497²）");
        assertTrue(initial.containsAll(AdaptiveCandidateBounds.boundsWithMargin(CORE, 0)),
                "红线：初始候选范围必须完整包含 coreBounds");
    }

    @Test
    void structuresInsideInitialBoundsAreIdenticalToFullRangeAdmission() {
        List<int[]> points = sampleStructurePoints();
        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(CORE);
        AdaptiveCandidateBounds.Rect full = AdaptiveCandidateBounds.boundsWithMargin(CORE, FULL_MARGIN);

        List<int[]> fromInitialPass = admitted(points, initial);
        List<int[]> fromFullPassClippedToInitial = new ArrayList<>();
        for (int[] p : admitted(points, full)) {
            if (initial.contains(p[0], p[1])) fromFullPassClippedToInitial.add(p);
        }

        assertEquals(fromFullPassClippedToInitial.size(), fromInitialPass.size());
        for (int i = 0; i < fromInitialPass.size(); i++) {
            assertEquals(fromFullPassClippedToInitial.get(i)[0], fromInitialPass.get(i)[0]);
            assertEquals(fromFullPassClippedToInitial.get(i)[1], fromInitialPass.get(i)[1]);
        }
        // 具体：初始档看到 8 个（核心 3 + 环带 5），全量档看到 11 个，差集正好是 3 个远处村庄。
        assertEquals(8, fromInitialPass.size());
        assertEquals(11, admitted(points, full).size());
    }

    @Test
    void pathStayingInsideInitialMarginNeverTriggersExpansion() {
        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(CORE);
        // 一条落在核心/余量内的路（包围盒完全在内）
        AdaptiveCandidateBounds.PathBounds inside = AdaptiveCandidateBounds.PathBounds.of(
                new int[]{-1200, 240}, new int[]{1100, 400});
        assertNotNull(inside);
        assertTrue(inside.within(initial));
        assertEquals(0, inside.overshoot(initial));
        assertEquals(0, AdaptiveCandidateBounds.roadsOutside(initial, List.of(inside)),
                "合规路必须不触发扩张 —— 它的结果就是第一轮那份走线");

        // 同一条路，若按"只看核心、不留余量"的极端口径，它反而越界 —— 说明余量确实在起作用
        AdaptiveCandidateBounds.Rect coreOnly = AdaptiveCandidateBounds.boundsWithMargin(CORE, 0);
        assertEquals(1, AdaptiveCandidateBounds.roadsOutside(coreOnly, List.of(inside)));
    }

    @Test
    void detourCaseOvershooting480ExpandsExactlyOneStep() {
        AdaptiveCandidateBounds.Rect initial = AdaptiveCandidateBounds.initial(CORE);
        // 真机那条 3.63 倍绕行：包围盒越出终点范围约 480 格（这里造在 +X 方向）
        int overshoot = 480;
        AdaptiveCandidateBounds.PathBounds detour = new AdaptiveCandidateBounds.PathBounds(
                -900, -1000, initial.maxX() + overshoot, 700);
        assertEquals(overshoot, detour.overshoot(initial));
        assertFalse(detour.within(initial));
        assertEquals(1, AdaptiveCandidateBounds.roadsOutside(initial, List.of(detour)));

        // 扩一档（724 → 2048，被 cap 截住）后它就落在范围内，不该再扩第二档
        AdaptiveCandidateBounds.Rect expanded =
                AdaptiveCandidateBounds.boundsWithMargin(CORE,
                        AdaptiveCandidateBounds.nextMargin(AdaptiveCandidateBounds.marginFor(CORE),
                                FULL_MARGIN));
        assertTrue(expanded.containsAll(initial));
        assertTrue(detour.within(expanded));
        assertEquals(0, AdaptiveCandidateBounds.roadsOutside(expanded, List.of(detour)));
    }

    @Test
    void expansionStopsAtCap() {
        int cap = Math.max(AdaptiveCandidateBounds.MARGIN_FLOOR, FULL_MARGIN);
        int margin = AdaptiveCandidateBounds.marginFor(CORE);
        assertEquals(2048, AdaptiveCandidateBounds.nextMargin(margin, cap),
                "724 × 4 = 2896 被 cap 2048 截住");
        assertEquals(1024, AdaptiveCandidateBounds.nextMargin(256, cap), "扩张倍数对任意输入都是 ×4");
        assertEquals(cap, AdaptiveCandidateBounds.nextMargin(cap, cap), "到 cap 之后必须停住");
        assertEquals(cap, AdaptiveCandidateBounds.nextMargin(3000, cap), "超过 cap 的余量必须被截住");
    }

    @Test
    void emptyPathBoundsAreIgnored() {
        assertNull(AdaptiveCandidateBounds.PathBounds.of(new int[0], new int[0]));
        assertNull(AdaptiveCandidateBounds.PathBounds.of((List<int[]>) null));
        assertEquals(0, AdaptiveCandidateBounds.roadsOutside(
                AdaptiveCandidateBounds.initial(CORE), List.of()));
        assertEquals(0, AdaptiveCandidateBounds.maxOvershoot(
                AdaptiveCandidateBounds.initial(CORE), null));
    }
}
