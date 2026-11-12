/* 文件职责：DP 输入翻译、剖面度量、AB 观察器的纯测试 —— 不含世界、不含区块。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileDpSource} / {@link ProfileMetrics} / {@link ProfileAbObserver} 的测试。
 *
 * <h2>测什么、为什么这么测</h2>
 * <ul>
 *   <li><b>窗口必须被尊重</b>：DP 只能在「地形 ± 允许挖填」里选高度；
 *       越界就是挖出悬崖或浮空路。</li>
 *   <li><b>桥段必须是一条直线</b>：这是本翻译层对桥的唯一约定，
 *       也是与旧实现无关的保证（否则 AB 对比就掺了旧解）。</li>
 *   <li><b>不可行必须能被看见</b>：陡地形 + 窄窗口会不可行，
 *       此时必须返回 empty 而不是一条坏剖面。</li>
 *   <li><b>观察器开关默认关闭、且绝不改输入</b>：它跑在主流程里，
 *       一旦改了什么就是拿玩家存档做实验。</li>
 * </ul>
 */
final class ProfileDpSourceTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("roadweaver.profileAb");
    }

    // ── ProfileDpSource ─────────────────────────────────────

    @Test
    void 平地上解就是地面本身() {
        int[] terrain = new int[12];
        for (int i = 0; i < terrain.length; i++) terrain[i] = 70;
        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 8, 4, 1);
        assertTrue(p.isPresent(), "case=feasible");
        assertArrayEquals(terrain, p.get(), "case=flat-follows-terrain");
    }

    @Test
    void 剖面不得越出允许挖填窗口() {
        int[] terrain = new int[]{70, 70, 71, 73, 76, 78, 78, 77, 74, 71, 70, 70};
        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 6, 3, 1);
        assertTrue(p.isPresent(), "case=feasible");
        int[] prof = p.get();
        for (int i = 0; i < prof.length; i++) {
            assertTrue(prof[i] >= terrain[i] - 6 && prof[i] <= terrain[i] + 3,
                    "case=window 第 " + i + " 站 " + prof[i] + " 越出 [" + (terrain[i] - 6)
                            + "," + (terrain[i] + 3) + "]");
        }
        for (int i = 1; i < prof.length; i++) {
            assertTrue(Math.abs(prof[i] - prof[i - 1]) <= 1,
                    "case=step 第 " + i + " 站跳了 " + Math.abs(prof[i] - prof[i - 1]));
        }
    }

    @Test
    void 桥段被钉成两端地形的直线() {
        int[] terrain = new int[]{70, 70, 60, 58, 59, 62, 70, 70};
        boolean[] crossing = new boolean[]{false, false, true, true, true, true, false, false};
        Optional<int[]> p = ProfileDpSource.solve(terrain, crossing, 8, 4, 4);
        assertTrue(p.isPresent(), "case=feasible");
        int[] prof = p.get();
        // 桥段覆盖 2..5，两端地形 60 与 62 ⇒ 直线内插应落在 [60,62] 之间且单调
        for (int i = 2; i <= 5; i++) {
            assertTrue(prof[i] >= 60 && prof[i] <= 62, "case=chord-range 第 " + i + " 站 " + prof[i]);
        }
        assertEquals(60, prof[2], "case=chord-start");
        assertEquals(62, prof[5], "case=chord-end");
    }

    @Test
    void 陡地形加窄窗口必须报不可行而不是硬凑() {
        // 每站抬 10 格，maxStep=1 且窗口只 ±1 ⇒ 物理上不可能
        int[] terrain = new int[8];
        for (int i = 0; i < terrain.length; i++) terrain[i] = 60 + i * 10;
        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 1, 1, 1);
        assertFalse(p.isPresent(), "case=infeasible-must-be-empty");
    }

    // ── ProfileMetrics ──────────────────────────────────────

    @Test
    void 度量口径正确() {
        int[] profile = new int[]{70, 71, 73, 73, 72, 70};
        int[] terrain = new int[]{70, 70, 70, 70, 70, 70};
        ProfileMetrics m = ProfileMetrics.of(profile, terrain, null);
        assertEquals(9L, m.fill(), "case=fill 0+1+3+3+2+0 = 9");
        assertEquals(0L, m.cut(), "case=cut");
        assertEquals(2, m.maxStep(), "case=maxStep");
        assertEquals(2, m.steps(), "case=steps>1 的相邻落差有两处（1→3、3→2）");
        assertTrue(m.p95Grade() >= 1.0, "case=p95");
        assertTrue(m.line().contains("steps=2"), "case=line: " + m.line());
    }

    @Test
    void 桥段内的落差不算台阶() {
        int[] profile = new int[]{70, 70, 74, 74, 70, 70};
        int[] terrain = new int[]{70, 70, 70, 70, 70, 70};
        boolean[] crossing = new boolean[]{false, true, true, true, true, false};
        ProfileMetrics withMask = ProfileMetrics.of(profile, terrain, crossing);
        ProfileMetrics noMask = ProfileMetrics.of(profile, terrain, null);
        assertEquals(0, withMask.steps(), "case=crossing-excluded");
        assertTrue(noMask.steps() > 0, "case=without-mask-counted");
    }

    @Test
    void 胜负计数只看四个指标() {
        ProfileMetrics good = new ProfileMetrics(0.5, 1, 10, 5, 0);
        ProfileMetrics bad = new ProfileMetrics(2.0, 3, 40, 20, 4);
        assertEquals("win=4 lose=0", good.versus(bad), "case=all-better");
        assertEquals("win=0 lose=4", bad.versus(good), "case=all-worse");
    }

    // ── ProfileAbObserver ───────────────────────────────────

    @Test
    void 观察器默认关闭时不做事() {
        int before = ProfileAbObserver.calls();
        int[] terrain = new int[]{70, 70, 70, 70};
        boolean[] crossing = new boolean[4];
        ProfileAbObserver.observe(new int[]{70, 70, 70, 70}, terrain, crossing, 8, 4, 1, "test");
        assertEquals(before, ProfileAbObserver.calls(), "case=disabled-no-call");
    }

    @Test
    void 观察器开启后只观察不修改输入() {
        System.setProperty("roadweaver.profileAb", "on");
        int[] legacy = new int[]{70, 71, 70, 69};
        int[] snapshot = legacy.clone();
        int[] terrain = new int[]{70, 70, 70, 70};
        boolean[] crossing = new boolean[4];
        ProfileAbObserver.observe(legacy, terrain, crossing, 8, 4, 1, "test");
        assertArrayEquals(snapshot, legacy, "case=input-untouched");
        assertTrue(ProfileAbObserver.calls() > 0, "case=observed");
    }
}