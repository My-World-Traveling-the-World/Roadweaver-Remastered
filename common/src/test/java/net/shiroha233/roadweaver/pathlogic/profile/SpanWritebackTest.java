/* 文件职责：钉住跨度回写的三道保护（默认关闭 / 生命周期双保险 / 可观测）。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨度回写（批 2 方案 A：ThreadLocal 快照）测试（2026-11-15）。
 *
 * <h2>它守的是什么</h2>
 * <p>回写会让 {@code ModeDpSolver} 判出的桥隧段**真的写回生产 spans** ——
 * 这是整个重构里风险最高的单点之一（改桥隧位置 ⇒ 改施工结果）。
 * 用户在 2026-11-15 拍板方案 A，前提是它必须带<b>三道保护</b>：</p>
 * <ol>
 *   <li><b>默认关闭</b>：{@code -Droadweaver.spanWriteback=on} 才生效。
 *       关掉时零开销、零行为变化，可以直接当回退开关。</li>
 *   <li><b>生命周期双保险</b>：{@code Road.generateRoad} 开场 {@code clearStash()}
 *       防残留；取用 {@code consumeStash()} 即清 ⇒ 一条路最多消费一次，
 *       <b>绝不会把上一条路的解用到下一条路上</b>。</li>
 *   <li><b>可观测</b>：{@code solved/consumed/missed/applied} 四个计数器 + {@code [SPAN-WB]} 日志，
 *       让「没拿到数据」不再静默 —— 本仓库吃过这个亏（{@code shouldAvoidPlanning}
 *       静默失明，第 6 轮才补观测）。</li>
 * </ol>
 *
 * <h2>这个测试<b>不</b>覆盖什么</h2>
 * <ul>
 *   <li><b>合并语义</b>（非桥隧跨度保留、{@code ROAD_CROSSING} 不被覆盖等）——
 *       那部分已有 11 个用例在 {@code ModeDpMergeTest} 里。</li>
 *   <li><b>桥判得对不对</b> —— 实验室无法判定，必须真机。
 *       所以本类只钉「机制不会静默错」，不钉「结果一定好」。</li>
 * </ul>
 */
class SpanWritebackTest {

    private static final String PROP = "roadweaver.spanWriteback";
    private String savedProperty;

    @BeforeEach
    void saveProperty() {
        savedProperty = System.getProperty(PROP);
        System.clearProperty(PROP);
        ModeDpBridge.resetForTest();
    }

    @AfterEach
    void restoreProperty() {
        if (savedProperty == null) System.clearProperty(PROP);
        else System.setProperty(PROP, savedProperty);
        ModeDpBridge.resetForTest();
    }

    private static List<BlockPos> centers(int n) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(0, 64, i));
        return out;
    }

    private static int[] flatTerrain(int n) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, 64);
        return out;
    }

    // ───────────── 保护①：默认关闭 ─────────────

    @Test
    void 默认关闭() {
        assertFalse(ModeDpBridge.writebackEnabled(),
                "回写必须默认关闭 —— 它改变生产 spans，出厂不能是开启状态");
    }

    @Test
    void 只有on或true才开启() {
        System.setProperty(PROP, "off");
        ModeDpBridge.resetForTest();
        assertFalse(ModeDpBridge.writebackEnabled(), "off 必须不开启");

        System.setProperty(PROP, "");
        ModeDpBridge.resetForTest();
        assertFalse(ModeDpBridge.writebackEnabled(), "空值必须不开启");

        System.setProperty(PROP, "on");
        ModeDpBridge.resetForTest();
        assertTrue(ModeDpBridge.writebackEnabled(), "on 必须开启");

        System.setProperty(PROP, "true");
        ModeDpBridge.resetForTest();
        assertTrue(ModeDpBridge.writebackEnabled(), "true 也开启（命令行两种写法都常见）");
    }

    @Test
    void 关闭时求解与取用都是空操作() {
        // 默认关闭状态
        ModeDpBridge.solveAndStash(flatTerrain(20), null, null, null, centers(20));
        assertNull(ModeDpBridge.consumeStash(),
                "关闭时不应留下任何快照");

        ModeDpBridge.clearStash();   // 关闭时是空操作，不应抛
        assertTrue(ModeDpBridge.writebackStats().contains("enabled=false"),
                "状态串必须如实反映关闭，实际：" + ModeDpBridge.writebackStats());
    }

    // ───────────── 保护②：生命周期双保险 ─────────────

    @Test
    void 开启时求解后能取到一次然后就没有了() {
        System.setProperty(PROP, "on");
        ModeDpBridge.resetForTest();

        List<BlockPos> cs = centers(20);
        ModeDpBridge.solveAndStash(flatTerrain(20), null, null, null, cs);

        ModeDpBridge.Stash first = ModeDpBridge.consumeStash();
        assertNotNull(first, "求解成功应该能取到快照");
        assertEquals(20, first.stations());

        assertNull(ModeDpBridge.consumeStash(),
                "取用即清 —— 第二次必须拿不到。这是「一条路最多消费一次」的实现点，"
                        + "若这里能取到，说明快照会跨路串味");
    }

    @Test
    void 开场清理能抹掉残留() {
        System.setProperty(PROP, "on");
        ModeDpBridge.resetForTest();

        ModeDpBridge.solveAndStash(flatTerrain(20), null, null, null, centers(20));
        ModeDpBridge.clearStash();   // 模拟下一条路开场

        assertNull(ModeDpBridge.consumeStash(),
                "开场 clearStash 之后必须取不到上一条路的解");
    }

    @Test
    void DP无解时不留快照() {
        System.setProperty(PROP, "on");
        ModeDpBridge.resetForTest();

        // 空地形 ⇒ solveAndStash 直接返回，不留快照
        ModeDpBridge.solveAndStash(new int[0], null, null, null, List.of());
        assertNull(ModeDpBridge.consumeStash(), "空地形不应产生快照");

        // 极端起伏 + 最小步长 ⇒ DP 大概率无解；无论有解无解，
        // 只要无解就不能留快照（否则消费端会拿到 null modes 去合并）
        ModeDpBridge.solveAndStash(new int[]{0, 100, 0, 100, 0, 100, 0, 100}, null, null, null, centers(8));
        ModeDpBridge.Stash maybe = ModeDpBridge.consumeStash();
        if (maybe != null) {
            assertNotNull(maybe.solution(), "有快照就必须带解，不能是带 null 解的空壳");
            assertNotNull(maybe.solution().modes(), "有快照就必须带 modes");
        }
        // 不强制断言无解 —— 那是 ModeDpSolver 的语义，不是本类的职责
    }

    // ───────────── 保护③：可观测 ─────────────

    @Test
    void 计数器如实反映求解与消费() {
        System.setProperty(PROP, "on");
        ModeDpBridge.resetForTest();

        ModeDpBridge.solveAndStash(flatTerrain(20), null, null, null, centers(20));
        String afterSolve = ModeDpBridge.writebackStats();
        assertTrue(afterSolve.contains("solved=1"), "求解后 solved 应为 1，实际：" + afterSolve);

        ModeDpBridge.consumeStash();
        String afterConsume = ModeDpBridge.writebackStats();
        assertTrue(afterConsume.contains("consumed=1"), "消费后 consumed 应为 1，实际：" + afterConsume);

        // 再消费一次拿不到 ⇒ missed +1
        ModeDpBridge.consumeStash();
        String afterMiss = ModeDpBridge.writebackStats();
        assertTrue(afterMiss.contains("missed=1"), "取不到应记 missed，实际：" + afterMiss);

        ModeDpBridge.noteWritebackApplied();
        assertTrue(ModeDpBridge.writebackStats().contains("applied=1"),
                "应用计数应如实记录");
    }

    @Test
    void 状态串始终包含四个计数器() {
        // 即使从没求解过，状态串也必须是可解析的完整形态 ——
        // 这样真机日志里「missed=3 solved=0」这种信号才一眼可见
        String stats = ModeDpBridge.writebackStats();
        for (String key : new String[]{"enabled=", "solved=", "consumed=", "missed=", "applied="}) {
            assertTrue(stats.contains(key), "状态串缺少 " + key + "，实际：" + stats);
        }
    }
}