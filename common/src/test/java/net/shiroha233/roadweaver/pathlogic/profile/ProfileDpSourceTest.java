/* 文件职责：DP 输入翻译、剖面度量、AB 观察器的纯测试 —— 不含世界、不含区块。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void boundsOnlyKeepsNegativePinsDeckFloorsAndCoveredWindowsWithoutCost() {
        // 独立手算窗口，避免仅比较同一实现的两个包装入口而漏掉硬约束。
        int[] terrain = {-5, -1, 3, 5, 10, 12, 14};
        boolean[] crossing = {false, true, true, false, false, false, false};
        int[] pins = {-7, 0, 0, 0, 0, 0, 0};
        var constraints = new ProfileDpSource.Constraints(pins,
                new boolean[]{false, false, false, false, true, false, true}, 8,
                new int[]{Integer.MIN_VALUE, 5, 6, Integer.MIN_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE},
                new boolean[]{true, false, false, false, false, false, false});
        var bounds = ProfileDpSource.prepareBounds(terrain, crossing, 4, 3, 2, 3, constraints);
        assertArrayEquals(new int[]{-7, 5, 6, 1, 2, 8, 6}, bounds.lo());
        assertArrayEquals(new int[]{-7, 5, 6, 8, 2, 15, 6}, bounds.hi());
        assertArrayEquals(new int[]{-7, 5, 6, -1, -1, -1, -1}, bounds.pin());
        assertArrayEquals(new boolean[]{true, true, true, false, false, false, false}, bounds.pinnedStations());
        org.junit.jupiter.api.Assertions.assertNull(bounds.cost(), "窗口检查不得分配被丢弃的代价矩阵");
        var prepared = ProfileDpSource.prepare(terrain, crossing, 4, 3, 2, 3, constraints);
        var full = prepared.problem();
        assertArrayEquals(bounds.lo(), full.lo());
        assertArrayEquals(bounds.hi(), full.hi());
        assertArrayEquals(bounds.pin(), full.pin());
        assertArrayEquals(bounds.pinnedStations(), full.pinnedStations());
        assertEquals(2, full.maxStep());
        assertEquals(3, full.maxStepPerTwo());
        assertTrue(prepared.terrain() == terrain && prepared.crossing() == crossing);
        double[][] expected = CostModel.fromTerrain(terrain, bounds.lo(), bounds.hi(), CostModel.Settings.defaults());
        assertEquals(terrain.length, full.cost().length);
        for (int i = 0; i < terrain.length; i++) assertArrayEquals(expected[i], full.cost()[i]);
    }

    @Test
    void boundsOnlyStillRejectsCoveredPinContradiction() {
        // 省去代价计算不能省去契约校验，否则修复会接纳封不住的洞身。
        var constraints = new ProfileDpSource.Constraints(new int[]{0}, new boolean[]{true}, 8,
                null, new boolean[]{true});
        assertThrows(IllegalArgumentException.class, () ->
                ProfileDpSource.prepareBounds(new int[]{4}, null, 4, 3, 1, 1, constraints));
    }

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
    void AB会话总结能给出可决策的结论() {
        ProfileAbObserver.resetStats();
        System.setProperty("roadweaver.profileAb", "on");
        int[] terrain = new int[]{70, 70, 70, 70, 70, 70};

        // 旧解：一路爬升 6 格（明显更差：坡度大、台阶多）
        int[] legacyBad = new int[]{70, 71, 70, 72, 70, 73};
        ProfileAbObserver.observe(legacyBad, terrain, new boolean[6], 8, 4, 1, "bad-legacy");

        String s = ProfileAbObserver.summarize();
        assertTrue(s.contains("dpWin=1"), "case=dp-win-counted: " + s);
        assertTrue(s.contains("新解占优"), "case=verdict: " + s);
        assertEquals("(无)", ProfileAbObserver.worstCases(), "case=no-worst-cases");

        // 再喂一条旧解明显更好的：桥面被钉成直线、而旧解也平 —— 这里只验证计数机制
        ProfileAbObserver.resetStats();
        int[] legacyFlat = new int[]{70, 70, 70, 70, 70, 70};
        ProfileAbObserver.observe(legacyFlat, terrain, new boolean[6], 8, 4, 1, "flat-legacy");
        String s2 = ProfileAbObserver.summarize();
        assertTrue(s2.contains("tie=1") || s2.contains("legacyWin=1"), "case=counted: " + s2);
    }

    @Test
    void 模式观察器默认关闭时不做事() {
        int before = ProfileAbObserver.modeCalls();
        ProfileAbObserver.observeModes(new int[]{70, 70, 70, 70}, new int[]{70, 70, 70, 70},
                new boolean[4], new boolean[4], 1, "test");
        assertEquals(before, ProfileAbObserver.modeCalls(), "case=disabled-no-mode-call");
    }

    @Test
    void 模式观察器开启后只观察不修改输入() {
        System.setProperty("roadweaver.profileAb", "on");
        int[] legacy = new int[]{70, 70, 64, 58, 58, 58, 64, 70};
        int[] snapshot = legacy.clone();
        int[] terrain = new int[]{70, 70, 64, 58, 58, 58, 64, 70};
        boolean[] banned = new boolean[]{false, false, false, true, true, true, false, false};
        boolean[] tunnel = new boolean[8];
        ProfileAbObserver.observeModes(legacy, terrain, banned, tunnel, 6, "test");
        assertArrayEquals(snapshot, legacy, "case=mode-input-untouched");
        assertTrue(ProfileAbObserver.modeCalls() > 0, "case=mode-observed");
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

    // ── 三条硬契约（S7 第 1 级的前置）─────────────────────────

    @Test
    void 钉住的端点必须原样落入剖面并自动生成引道() {
        // 40 站平地（地表 70），第 0 站钉在 73（在 fill 窗口 74 以内）。
        // 这正是「村庄入口必须落在实测地表」的形态。
        int n = 40;
        int[] terrain = new int[n];
        java.util.Arrays.fill(terrain, 70);
        int[] pin = new int[n];
        java.util.Arrays.fill(pin, -1);
        pin[0] = 73;

        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 8, 4, 1,
                new ProfileDpSource.Constraints(pin, null, 0));
        assertTrue(p.isPresent(), "case=feasible");
        int[] prof = p.get();
        assertEquals(73, prof[0], "case=pinned-exact");
        for (int i = 1; i < n; i++) {
            assertTrue(Math.abs(prof[i] - prof[i - 1]) <= 1,
                    "case=legal-ramp 第 " + i + " 站跳了 " + Math.abs(prof[i] - prof[i - 1]));
        }
        // 引道最终必须落回地面
        assertEquals(70, prof[n - 1], "case=ramp-meets-terrain");
    }

    /**
     * 契约边界：钉住高度必须**在坡度可达范围内**。
     *
     * <p>钉在 76 而窗口顶只有 74（地表 70 + maxFill 4）时，引道第 2 站需要 75，
     * 但 75 超出窗口 ⇒ 整体不可行。这是**刻意**的语义：</p>
     *
     * <p>旧版 {@code applySingleBoundaryGrade} 会硬铺一条无视 fill 窗口的坡；
     * 那个行为正是「锁定点周围出现填方超限」的来源。新实现把选择权交给调用方：
     * 要贴住高处的锁定点，就显式把 {@code maxFill} 放宽（下一行就是这么做的），
     * 而不是让求解器偷偷超限。</p>
     */
    @Test
    void 钉住高度超出窗口时_要么放宽窗口要么不可行() {
        int n = 10;
        int[] terrain = new int[n];
        java.util.Arrays.fill(terrain, 70);
        int[] pin = new int[n];
        java.util.Arrays.fill(pin, -1);
        pin[0] = 76;

        // 窗口顶 74（=70+4）⇒ 够不着 76 的引道 ⇒ 不可行
        assertFalse(ProfileDpSource.solve(terrain, null, 8, 4, 1,
                        new ProfileDpSource.Constraints(pin, null, 0)).isPresent(),
                "case=narrow-window-infeasible");

        // 把 fill 放宽到 10（窗口顶 80）⇒ 同一钉住值立刻可行
        Optional<int[]> widened = ProfileDpSource.solve(terrain, null, 8, 10, 1,
                new ProfileDpSource.Constraints(pin, null, 0));
        assertTrue(widened.isPresent(), "case=widened-feasible");
        assertEquals(76, widened.get()[0], "case=pinned-exact-after-widen");
    }

    @Test
    void 钉住但坡度不足以过渡时必须报不可行() {
        int n = 5;
        int[] terrain = new int[n];
        java.util.Arrays.fill(terrain, 70);
        int[] pin = new int[n];
        java.util.Arrays.fill(pin, -1);
        pin[4] = 90;       // 20 格落差，只剩 4 站、maxStep=1 ⇒ 物理不可能
        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 8, 4, 1,
                new ProfileDpSource.Constraints(pin, null, 0));
        assertFalse(p.isPresent(), "case=infeasible-must-be-empty");
    }

    @Test
    void 隧道段必须有覆土_宁可挖得更深() {
        // 20 站，中间 5 站要求覆土 ≥ 6 格。地表 70，maxCut 只有 4 ——
        // 覆土是硬要求，所以允许它挖到 70-6=64（比 maxCut 更深）。
        int n = 20;
        int[] terrain = new int[n];
        java.util.Arrays.fill(terrain, 70);
        boolean[] covered = new boolean[n];
        for (int i = 8; i <= 12; i++) covered[i] = true;

        Optional<int[]> p = ProfileDpSource.solve(terrain, null, 4, 4, 2,
                new ProfileDpSource.Constraints(null, covered, 6));
        assertTrue(p.isPresent(), "case=feasible");
        int[] prof = p.get();
        for (int i = 8; i <= 12; i++) {
            assertTrue(prof[i] <= terrain[i] - 6,
                    "case=cover 第 " + i + " 站高度 " + prof[i] + " 不满足覆土 6");
        }
        // 覆土让步的是挖方上限：允许比 maxCut=4 更深
        assertTrue(prof[10] <= 70 - 6, "case=deeper-than-maxcut");
    }

    @Test
    void 钉住与覆土互相矛盾时必须立刻抛() {
        int n = 3;
        int[] terrain = new int[n];
        java.util.Arrays.fill(terrain, 70);
        int[] pin = new int[n];
        java.util.Arrays.fill(pin, -1);
        pin[1] = 70;                                   // 钉在地表
        boolean[] covered = new boolean[n];
        covered[1] = true;                             // 又要求覆土 ≥6
        assertThrows(IllegalArgumentException.class, () ->
                        ProfileDpSource.solve(terrain, null, 8, 4, 2,
                                new ProfileDpSource.Constraints(pin, covered, 6)),
                "case=contradicting-contracts");
    }

    @Test
    void 无约束等于旧行为() {
        int[] terrain = new int[]{70, 70, 71, 73, 76, 78, 78, 77};
        Optional<int[]> a = ProfileDpSource.solve(terrain, null, 6, 3, 1);
        Optional<int[]> b = ProfileDpSource.solve(terrain, null, 6, 3, 1,
                ProfileDpSource.Constraints.none());
        assertTrue(a.isPresent() && b.isPresent(), "case=both-feasible");
        assertArrayEquals(a.get(), b.get(), "case=none-is-noop");
    }

    // ── 模式开关 ──────────────────────────────────────────────

    /**
     * 模式解析：<b>默认是 DP</b>。
     *
     * <h3>为什么最终选择 DP（2026-11-13 第三次定案）</h3>
     * <p>这一天的结论反复了两次，记下来免得后人再翻：</p>
     * <ol>
     *   <li><b>先切 DP</b>：依据是 DP 土方远优（57 次观测，325,346 → 8,996）。</li>
     *   <li><b>又退回 LEGACY</b>：三份独立只读核查发现 DP 的输入里<b>没有水位</b>
     *       （{@code ProfileDpSource} 全文 {@code water} 出现 0 次），于是桥面从
     *       「水位 + 净空」的平甲板变成两岸地形斜弦 —— 海平面河下沉 2 格贴水，
     *       山间河比水面低 3 格泡在水下。</li>
     *   <li><b>再切回 DP（当前）</b>：正确的修法不是退回旧版 —— 旧版那 15 道 pass
     *       正是「先算剖面、再事后贴补丁」的产物，上限已经到顶。正确做法是
     *       <b>把水位下界作为 DP 的搜索约束喂进去</b>：{@code ProfileDpSource.Constraints}
     *       新增 {@code deckFloor}，{@code TargetYComputation} 用旧版的权威算法
     *       {@code RoadConstructionSafety.bridgeDeckSurfaceY}（逐站查最高水位 +
     *       两侧 3 格取样 + clearance）算出每站下界，DP 在满足坡度上限的前提下
     *       自己安排过渡段。桥面该平的地方自然平，但不再需要事后再贴一遍坡。</li>
     * </ol>
     *
     * <p><b>仍然只在 LEGACY 生效的旧补丁</b>（后续要逐项补进 DP）：浅滩两岸切平、
     * 相邻桥甲板连续性、峡谷稳定岸、河岸跳变、针尖消除、1-2-1 平滑、GradeCap 档位、
     * 山区 cut/fill 分档、共享引道。补一项验证一项。</p>
     */
    @Test
    void 求解器模式默认是DP且可显式回退到LEGACY() {
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse(null), "case=null");
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse(""), "case=blank");
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse("dp2"), "case=typo-回落默认");
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse("true"), "case=wrong-flag-回落默认");
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse("DP"), "case=dp-upper");
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.parse(" dp "), "case=dp-spaces");
        assertEquals(ProfileSolverMode.LEGACY, ProfileSolverMode.parse("legacy"), "case=legacy-显式回退");
        assertEquals(ProfileSolverMode.LEGACY, ProfileSolverMode.parse("CLASSIC"), "case=classic-显式回退");
    }

    @Test
    void 未设属性时读出来是DP且legacy可回退() {
        System.clearProperty(ProfileSolverMode.PROPERTY);
        assertEquals(ProfileSolverMode.DP, ProfileSolverMode.fromProperty(), "case=unset-默认DP");
        System.setProperty(ProfileSolverMode.PROPERTY, "legacy");
        try {
            assertEquals(ProfileSolverMode.LEGACY, ProfileSolverMode.fromProperty(), "case=explicit-legacy");
        } finally {
            System.clearProperty(ProfileSolverMode.PROPERTY);
        }
    }

    // ── 桥面水位下界（deckFloor）────────────────────────────────

    /**
     * 桥面水位下界必须把 bridge 段抬到水面上。
     *
     * <h3>这是「重构」与「补丁」的分界</h3>
     * <p>旧版是「先按地形算剖面 → 事后用 {@code applyFixedRange} 把桥面整段抬到
     * 最高水位 + clearance」。抬完之后坡度约束、平滑、相邻桥连续性全部失效，
     * 只能再贴一轮补丁 —— 那 15 道 pass 就是这么长出来的。</p>
     *
     * <p>新版把「水位 + 净空」变成 DP 的<b>搜索下界</b>：DP 在满足坡度上限的前提下
     * 自己安排过渡段。这个测试钉住的就是「下界真的进了求解」这一件事。</p>
     *
     * <h3>负控</h3>
     * <p>{@link #不传水位下界时桥段会随地形下沉()} 证明：不传 deckFloor 时，
     * 同样的地形 + 同样的 crossing 掩码，桥段会一路沉到地形高度 ——
     * 也就是「没有这个约束就会插水」。两条一起看才知道这个约束不是装饰。</p>
     */
    @Test
    void 桥面水位下界必须把桥段抬到水面上() {
        // 河谷地形：两端高地 70，中间河床 60
        int[] terrain = {70, 60, 60, 60, 60, 70};
        boolean[] crossing = {false, true, true, true, true, false};
        int waterSurface = 63;
        int clearance = 2;
        int deck = waterSurface + clearance;          // = 65
        int[] deckFloor = {Integer.MIN_VALUE, deck, deck, deck, deck, Integer.MIN_VALUE};
        var c = new ProfileDpSource.Constraints(null, null, 0, deckFloor);

        var r = ProfileDpSource.solve(terrain, crossing, 5, 4, 2, 2, c);
        assertTrue(r.isPresent(), "带水位下界时必须可解");
        int[] p = r.get();
        for (int i = 1; i <= 4; i++) {
            assertTrue(p[i] >= deck,
                    "第 " + i + " 站在桥段上，必须 >= 水位+净空 " + deck + "，实际 " + p[i]);
        }
        // 桥面必须平（同一水位段下界相同，DP 没有任何理由在桥上起伏）
        for (int i = 2; i <= 4; i++) {
            assertEquals(p[1], p[i], "桥段应当等高（下界相同）：index=" + i);
        }
    }

    /**
     * 负控：<b>同一地形下不传 {@code deckFloor} 会直接判不可解</b>。
     *
     * <h3>这条负控比「桥段会下沉」更有价值</h3>
     * <p>一开始我把负控写成「不传 deckFloor 时桥段会沉到地形高度」，跑出来是
     * {@code Optional.empty()} —— 因为在真实河谷地形上：</p>
     * <ul>
     *   <li>桥段被钉在<b>河床</b>的弦上（{@code terrain[start]..terrain[end]}
     *       两端都在桥段内部，量的是河床不是岸）；</li>
     *   <li>岸比河床高 10 格，而 {@code maxCut=5} 最多只能挖 5 格；</li>
     *   <li>剩下 5 格落差 &gt; {@code maxStepPerTwo=2} ⇒ <b>无解</b>。</li>
     * </ul>
     *
     * <p>也就是说，<b>没有 {@code deckFloor} 时，真实河谷地形会让 DP 直接判不可解、
     * 整条路静默回退 LEGACY</b>（{@code TargetYComputation:603} 的
     * {@code solved.isEmpty()} 分支只打一行 WARN）。所以 {@code deckFloor} 不只是
     * 「把桥面抬起来」，它同时是<b>让这类地形变得可解</b>的前提 ——
     * 桥面抬到水位 65 之后，岸从 70 挖到 65 只有 5 格落差，坡度约束就满足了。</p>
     *
     * <p>两条测试合起来说明：同一地形，<b>有 {@code deckFloor} 可解且桥面等高，
     * 没有就直接无解</b>。这才是这个约束的真实作用。</p>
     */
    @Test
    void 不传水位下界时真实河谷地形会判不可解() {
        int[] terrain = {70, 60, 60, 60, 60, 70};
        boolean[] crossing = {false, true, true, true, true, false};

        // 不传 deckFloor：桥段钉在河床 60，岸 70 挖不到 60 ⇒ 无解
        var noFloor = ProfileDpSource.solve(terrain, crossing, 5, 4, 2, 2,
                ProfileDpSource.Constraints.none());
        assertTrue(noFloor.isEmpty(),
                "负控失败：没有 deckFloor 时应当判不可解（岸挖不到河床高度），"
                        + "实际却给出了剖面 " + (noFloor.isPresent()
                        ? java.util.Arrays.toString(noFloor.get()) : "-"));

        // 传 deckFloor=65：桥面抬到水位+净空，岸 70 -> 65 只有 5 格落差 ⇒ 可解
        int deck = 65;
        int[] floor = {Integer.MIN_VALUE, deck, deck, deck, deck, Integer.MIN_VALUE};
        var withFloor = ProfileDpSource.solve(terrain, crossing, 5, 4, 2, 2,
                new ProfileDpSource.Constraints(null, null, 0, floor));
        assertTrue(withFloor.isPresent(),
                "带 deckFloor 时应当可解（桥面抬到 " + deck + "，岸只需挖 5 格）");
    }

    /** deckFloor 长度与 pin 不一致时必须构造期拒绝（契约检查）。 */
    @Test
    void 水位下界长度不一致时必须构造期拒绝() {
        int[] pin = new int[4];
        int[] badFloor = new int[3];
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileDpSource.Constraints(pin, null, 0, badFloor),
                "deckFloor 与 pin 长度不一致必须立刻抛，而不是运行时越界");
    }
}
