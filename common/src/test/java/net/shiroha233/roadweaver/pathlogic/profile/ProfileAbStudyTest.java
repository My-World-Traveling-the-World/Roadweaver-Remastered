/* 文件职责：离线 AB 研究 —— 合成地形上把旧剖面 pass 与新 DP 并排对比，不启动游戏。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剖面 AB 的<b>离线研究</b>：不用 Minecraft，就能拿到「新解 vs 旧解」的第一批数字。
 *
 * <h2>为什么可以在离线做</h2>
 * <p>旧流水线里真正决定剖面形状的那几道 pass（{@code stabilizeSurfaceProfile /
 * constrainProfile / applyProactiveTunnelProfile / removeSingleStationSpikes}）
 * 都是 <b>int[] → int[] 的纯函数</b>，只吃地形与掩码。新 DP 更是纯函数。
 * 所以两者都能在没有世界的情况下并排跑。</p>
 *
 * <h2>★ 必须说清的局限（别把这个当最终结论）</h2>
 * <ul>
 *   <li>这里只跑了 15 道 pass 里的 <b>4 道</b> —— 真实链路还有世界采样、桥隧判定、
 *       边界锁定、端点共享高度等，都会再改一遍剖面；</li>
 *   <li>地形是<b>合成</b>的，不是真实存档的采样；</li>
 *   <li>所以本研究的定位是「早期信号」，不是「能否切默认」的依据。
 *       后者仍然要真实会话里的 {@code [PROFILE-AB-SUMMARY]}（见 REFACTOR.md）。</li>
 * </ul>
 *
 * <h2>它能回答什么</h2>
 * <p>如果连在纯函数层、在温和地形上旧 pass 都已经明显不如 DP，那这就是强信号；
 * 反过来如果旧 pass 在纯函数层反而更好，那说明 DP 的代价函数还没调对 ——
 * 两种情况都比"看代码猜"有用。</p>
 */
final class ProfileAbStudyTest {

    /** 一个研究场景。 */
    private record Case(String name, int[] terrain, boolean[] crossing) {
    }

    private static Case simple(String name, int[] terrain) {
        return new Case(name, terrain, new boolean[terrain.length]);
    }

    private static List<Case> cases() {
        List<Case> out = new ArrayList<>(8);
        int n = 40;

        int[] flat = new int[n];
        for (int i = 0; i < n; i++) flat[i] = 64;
        out.add(simple("平地", flat));

        int[] gentle = new int[n];
        for (int i = 0; i < n; i++) gentle[i] = 64 + i / 4;
        out.add(simple("缓坡上升", gentle));

        int[] steep = new int[n];
        for (int i = 0; i < n; i++) steep[i] = 64 + i * 4;
        out.add(simple("陡坡上升", steep));

        int[] valley = new int[n];
        for (int i = 0; i < n; i++) valley[i] = Math.abs(i - n / 2) < 8 ? 56 : 66;
        out.add(simple("宽谷", valley));

        int[] ridge = new int[n];
        for (int i = 0; i < n; i++) ridge[i] = (i >= 14 && i <= 26) ? 80 : 66;
        out.add(simple("山脊", ridge));

        int[] stairs = new int[n];
        for (int i = 0; i < n; i++) stairs[i] = 64 + (i / 5) * 2;
        out.add(simple("阶梯", stairs));

        int[] noisy = new int[n];
        java.util.Random rnd = new java.util.Random(20261113L);
        for (int i = 0; i < n; i++) noisy[i] = 64 + rnd.nextInt(5) - 2;
        out.add(simple("噪声", noisy));

        int[] bumpy = new int[n];
        for (int i = 0; i < n; i++) {
            bumpy[i] = 64 + i / 3 + ((i % 7 == 0) ? 3 : 0);
        }
        out.add(simple("长缓坡带凸起", bumpy));

        return out;
    }

    /** 旧流水线里跑得动的纯 pass 子集（4 道，见类注释的局限说明）。 */
    private static int[] legacyPipeline(int[] terrain, boolean[] crossing) {
        int[] footprint = new int[terrain.length];
        System.arraycopy(terrain, 0, footprint, 0, terrain.length);
        int[] smoothed = RoadGradePlanner.stabilizeSurfaceProfile(
                terrain.clone(), terrain, footprint, crossing, true, 2);
        int[] constrained = RoadGradePlanner.constrainProfile(smoothed, terrain, crossing);
        int[] tunnelled = RoadGradePlanner.applyProactiveTunnelProfile(
                constrained, terrain, crossing, true, 2, new boolean[terrain.length]);
        return RoadGradePlanner.removeSingleStationSpikes(tunnelled, new boolean[terrain.length]);
    }

    @Test
    void 离线对比旧pass与新DP() {
        int feasible = 0;
        int infeasible = 0;
        int dpWins = 0;
        int legacyWins = 0;
        int ties = 0;
        StringBuilder table = new StringBuilder();
        table.append("\n  场景            | 旧 pass                          | 新 DP                            | 胜负\n");
        table.append("  ----------------|----------------------------------|----------------------------------|------\n");

        for (Case c : cases()) {
            int[] legacy = legacyPipeline(c.terrain(), c.crossing());
            Optional<int[]> dp = ProfileDpSource.solve(c.terrain(), c.crossing(), 24, 12, 1);
            if (dp.isEmpty()) {
                // ★ DP 报不可行时，仍要打印旧解的实际数字 —— 否则看不出「旧解是怎么"解决"的」。
                // 实测：每站抬 4 格的陡坡上，旧 pass 会挖出一条上百格深的沟然后照样输出剖面；
                // DP 则明确说「这个坡度下不存在满足约束的地面剖面」。
                ProfileMetrics bad = ProfileMetrics.of(legacy, c.terrain(), c.crossing());
                table.append(String.format("  %-14s | %-32s | %-32s | DP 不可行%n",
                        c.name(), bad.line(), "(不存在满足约束的剖面)"));
                infeasible++;
                continue;
            }
            feasible++;
            ProfileMetrics a = ProfileMetrics.of(legacy, c.terrain(), c.crossing());
            ProfileMetrics b = ProfileMetrics.of(dp.get(), c.terrain(), c.crossing());
            int aw = a.betterCount(b);
            int bw = b.betterCount(a);
            String verdict = bw > aw ? "DP 胜" : aw > bw ? "旧胜" : "平";
            if (bw > aw) dpWins++;
            else if (aw > bw) legacyWins++;
            else ties++;
            table.append(String.format("  %-14s | %-32s | %-32s | %s (%d:%d)%n",
                    c.name(), a.line(), b.line(), verdict, bw, aw));
        }
        System.out.println("[PROFILE-STUDY] 离线 AB 研究（合成地形，旧链路 4/15 道 pass）" + table);
        System.out.println("[PROFILE-STUDY] 汇总：场景=" + cases().size() + " DP可行=" + feasible
                + " DP不可行=" + infeasible
                + " DP胜=" + dpWins + " 旧胜=" + legacyWins + " 平=" + ties);

        assertTrue(feasible >= 5,
                "DP 在合成地形上大多可行才有研究价值，实际可行 " + feasible + "/" + cases().size());
        // 「DP 会拒绝不可行的坡度」本身是设计目标之一：旧实现无论多陡都会输出一条剖面
        // （代价是挖穿山体）。这里钉住这个差异确实被观测到。
        assertTrue(infeasible >= 1,
                "研究里应当至少出现一个 DP 判定不可行的场景（用来对照旧实现的「总能输出」）");
        assertTrue(dpWins + legacyWins + ties == feasible, "胜负计数必须覆盖全部可行场景");
    }
}