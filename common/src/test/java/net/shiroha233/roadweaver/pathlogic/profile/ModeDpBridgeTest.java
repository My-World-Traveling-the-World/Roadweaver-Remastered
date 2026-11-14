/* 文件职责：钉住桥隧 AB 观测桥的换算与统计 —— 它产出的数字是「召回率」唯一的证据来源。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥隧 AB 观测桥测试（2026-11-14）。
 *
 * <h2>为什么这个类必须有测试</h2>
 * <p>批 2（桥隧判定）的验收标准是「旧判定判出的每座必须的桥，新解也判了
 * —— 召回率 100%」。这个结论<b>只能由 {@code [SPAN-AB]} 日志支撑</b>，
 * 而日志里的数字就是这个类算出来的。算错了 ⇒ 召回率结论错 ⇒ 可能把
 * 判错的桥隧方案放进正式路径。</p>
 *
 * <p>所以这里不测「桥隧判得对不对」（那要真机数据），只测
 * <b>「给定 modes / spans，统计出来的数字对不对」</b> —— 这是纯函数，可以钉死。</p>
 */
class ModeDpBridgeTest {

    @AfterEach
    void clearCache() {
        // mode() 有进程内缓存，测试之间必须清掉，否则 @Test 顺序会影响结果。
        System.clearProperty("roadweaver.modeDp");
        ModeDpBridge.resetForTest();
    }

    private static List<BlockPos> line(int n) {
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new BlockPos(i * 4, 64, 0));
        return out;
    }

    // ── 开关 ─────────────────────────────────────────────────────────────

    @Test
    void 默认开关是观测模式() {
        assertEquals(ModeDpBridge.Mode.AB, ModeDpBridge.mode(),
                "默认必须是 AB（观测），绝不能默认 ON —— 没真机证据就替换等于开无验收的门");
    }

    @Test
    void 开关能识别off与on() {
        System.setProperty("roadweaver.modeDp", "off");
        ModeDpBridge.resetForTest();
        assertEquals(ModeDpBridge.Mode.OFF, ModeDpBridge.mode());

        System.setProperty("roadweaver.modeDp", "on");
        ModeDpBridge.resetForTest();
        assertEquals(ModeDpBridge.Mode.ON, ModeDpBridge.mode());
    }

    @Test
    void 非法开关值回落观测而不是关闭() {
        System.setProperty("roadweaver.modeDp", "乱写的值");
        ModeDpBridge.resetForTest();
        assertEquals(ModeDpBridge.Mode.AB, ModeDpBridge.mode(),
                "非法值回落到 AB 而不是 OFF —— 观测是批 2 唯一的证据来源，不该被一个拼写错误关掉");
    }

    // ── 逐站模式 → 跨度 ───────────────────────────────────────────────────

    @Test
    void 连续同模式合并成一段_桥与隧道各自成段() {
        var modes = new ModeDpSolver.Mode[]{
                ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE,
                ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.TUNNEL, ModeDpSolver.Mode.TUNNEL, ModeDpSolver.Mode.GROUND};
        List<BlockPos> centers = line(9);

        List<RoadSpan> spans = ModeDpBridge.spansFrom(modes, centers, 9);

        assertEquals(2, spans.size(), "应得 1 段桥 + 1 段隧道，实际：" + spans);
        assertEquals(SpanType.BRIDGE, spans.get(0).type());
        assertEquals(centers.get(2), spans.get(0).start(), "桥段起点应是第一个 BRIDGE 站");
        assertEquals(centers.get(4), spans.get(0).end(), "桥段终点应是最后一个 BRIDGE 站");
        assertEquals(SpanType.TUNNEL, spans.get(1).type());
        assertEquals(centers.get(6), spans.get(1).start());
        assertEquals(centers.get(7), spans.get(1).end());
    }

    @Test
    void 全地面时没有任何跨度() {
        var modes = new ModeDpSolver.Mode[]{ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.GROUND,
                ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.GROUND};

        List<RoadSpan> spans = ModeDpBridge.spansFrom(modes, line(5), 5);

        assertTrue(spans.isEmpty(), "全 GROUND 不该产生跨度，实际：" + spans);
    }

    @Test
    void 交替模式会拆成多段而不是合并() {
        var modes = new ModeDpSolver.Mode[]{
                ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.TUNNEL};
        List<RoadSpan> spans = ModeDpBridge.spansFrom(modes, line(5), 5);

        assertEquals(3, spans.size(), "交替模式必须拆成 3 段（桥/桥/隧道），实际：" + spans);
        assertEquals(SpanType.BRIDGE, spans.get(0).type());
        assertEquals(SpanType.BRIDGE, spans.get(1).type());
        assertEquals(SpanType.TUNNEL, spans.get(2).type());
    }

    @Test
    void 单站桥也能成段且起止相同() {
        var modes = new ModeDpSolver.Mode[]{ModeDpSolver.Mode.GROUND, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.GROUND};
        List<BlockPos> centers = line(3);

        List<RoadSpan> spans = ModeDpBridge.spansFrom(modes, centers, 3);

        assertEquals(1, spans.size());
        assertEquals(spans.get(0).start(), spans.get(0).end(), "单站桥的起止必须是同一站");
    }

    @Test
    void 长度不一致时按最短对齐而不是抛异常() {
        var modes = new ModeDpSolver.Mode[]{ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE, ModeDpSolver.Mode.BRIDGE};

        // 只给 3 个坐标，modes 有 5 个 ⇒ 只应算出前 3 站那一段
        List<RoadSpan> spans = ModeDpBridge.spansFrom(modes, line(3), 5);

        assertEquals(1, spans.size());
        assertEquals(line(3).get(2), spans.get(0).end(), "越界部分必须被截断，不能取到 null");
    }

    // ── 参数翻译 ─────────────────────────────────────────────────────────

    @Test
    void 施工窗口必须与旧链路同源() {
        // 挖 3 / 填 2 / 覆土 5 的自定义窗口
        var limits = new SurfaceGradeLimits(3, 2, 4, 2, 5, 3);

        var s = ModeDpBridge.settingsFor(limits);

        assertEquals(3, s.maxCut(), "挖方上限必须取自 SurfaceGradeLimits，不能自己拍");
        assertEquals(2, s.maxFill(), "填方上限同理");
        assertEquals(5, s.minTunnelCover(), "隧道覆土同理 —— 这是「宁可挖深也要保住覆土」的硬契约");
    }

    @Test
    void 覆土超过隧深上限时被夹到安全范围() {
        // 覆土 99 > defaults 的 maxTunnelDepth(40)：必须夹住，否则 Settings 的
        // 构造断言会抛 IllegalArgumentException（maxTunnelDepth ≥ minTunnelCover）
        var limits = new SurfaceGradeLimits(3, 2, 4, 2, 99, 3);

        var s = ModeDpBridge.settingsFor(limits);

        assertTrue(s.maxTunnelDepth() >= s.minTunnelCover(),
                "夹取后仍须满足 maxTunnelDepth ≥ minTunnelCover，否则求解器直接抛错");
        assertEquals(40, s.minTunnelCover(), "99 应被夹到 defaults 的隧深上限 40");
    }

    @Test
    void 窗口为null时用默认值而不是崩() {
        var s = ModeDpBridge.settingsFor(null);
        assertNotNull(s);
        assertEquals(ModeDpSolver.Settings.defaults().maxCut(), s.maxCut());
    }

    // ── observe 的生产安全性 ──────────────────────────────────────────────

    @Test
    void 观测遇到空输入不抛异常() {
        // 生产里 terrainY 理论上不会空，但观测层绝不能成为新的崩溃点
        ModeDpBridge.observe(null, null, null, null, null, null, "t");
        ModeDpBridge.observe(new int[0], null, null, null, null, null, "t");
        ModeDpBridge.observe(new int[]{64}, null, null, null, null, null, "t");
        // 走到这里没抛异常即通过
        assertFalse(false);
    }

    @Test
    void off模式直接返回不做任何计算() {
        System.setProperty("roadweaver.modeDp", "off");
        ModeDpBridge.resetForTest();

        // 给一份**会**让求解器难受的输入（长度 1、无 centers），
        // 若 off 没生效则会走到 DP 并可能抛错 —— 这里要求它安静返回。
        ModeDpBridge.observe(new int[]{64}, new boolean[]{true}, null, null,
                new ArrayList<>(), new ArrayList<>(), "off-test");

        assertEquals(ModeDpBridge.Mode.OFF, ModeDpBridge.mode());
    }

    // ── 与真实求解器的联动 ────────────────────────────────────────────────

    @Test
    void 真实求解器的解能被转成跨度且不超界() {
        // 一条中间被水淹掉的地形：水面段 groundBanned ⇒ 求解器应选桥或隧道
        int n = 24;
        int[] terrain = new int[n];
        boolean[] banned = new boolean[n];
        for (int i = 0; i < n; i++) {
            terrain[i] = 64;
            if (i >= 10 && i < 16) banned[i] = true;   // 中间 6 站是水
        }
        var settings = ModeDpBridge.settingsFor(new SurfaceGradeLimits(8, 4, 8, 4, 6, 3));
        var solved = ModeDpSolver.solve(terrain, 2, settings, banned);

        assertTrue(solved.isPresent(), "这种地形应当可解（水面有桥/隧道可选）");
        List<RoadSpan> spans = ModeDpBridge.spansFrom(solved.get().modes(), line(n), n);

        assertFalse(spans.isEmpty(), "水面段必须被判成桥或隧道，实际 spans 为空");
        for (RoadSpan sp : spans) {
            assertNotNull(sp.start());
            assertNotNull(sp.end());
            assertTrue(sp.start().getX() >= 0 && sp.start().getX() <= (n - 1) * 4,
                    "跨度起点必须落在路线范围内：" + sp);
            assertTrue(sp.end().getX() >= 0 && sp.end().getX() <= (n - 1) * 4,
                    "跨度终点必须落在路线范围内：" + sp);
        }
    }
}