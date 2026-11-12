/* 文件职责：真实地形重放测试 —— 有录制文件就重放对比，没有就跳过。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实地形重放：把录制下来的真机地形喂给「旧 pass vs 新 DP」，在 CI 里跑。
 *
 * <h2>它补的正是离线研究最大的那个洞</h2>
 * <p>{@code ProfileAbStudyTest} 用的是<b>合成</b>地形 —— 山坡是等差数列、断崖是阶跃。
 * 真实地形有噪声、有河岸、有台地，单靠想象凑场景永远不够。</p>
 *
 * <p>所以有了 {@link ProfileTerrainRecorder}：玩家开一次游戏、跑一批路线，
 * 地形就被录成几十行文本。把那个文件放进
 * {@code common/src/test/resources/profile/terrain-samples.tsv}，
 * 这组测试就会自动开始重放 —— <b>之后不需要再开游戏</b>。</p>
 *
 * <h2>没有录制文件时</h2>
 * <p>用 {@code Assumptions.assumeTrue} 跳过，而不是失败。
 * 「还没录制」不是错误，不该让 CI 红。</p>
 */
final class ProfileTerrainReplayTest {

    /** 录制文件位置（相对模块根，测试工作目录是 common）。 */
    private static final Path SAMPLES =
            Paths.get("src", "test", "resources", "profile", "terrain-samples.tsv");

    /** 旧流水线里跑得动的纯 pass 子集（与离线研究同一口径，便于横向比较）。 */
    private static int[] legacyPipeline(int[] terrain, boolean[] crossing) {
        int[] footprint = terrain.clone();
        int[] smoothed = RoadGradePlanner.stabilizeSurfaceProfile(
                terrain.clone(), terrain, footprint, crossing, true, 2);
        int[] constrained = RoadGradePlanner.constrainProfile(smoothed, terrain, crossing);
        int[] tunnelled = RoadGradePlanner.applyProactiveTunnelProfile(
                constrained, terrain, crossing, true, 2, new boolean[terrain.length]);
        return RoadGradePlanner.removeSingleStationSpikes(tunnelled, new boolean[terrain.length]);
    }

    @Test
    void 有真实录制就重放对比() throws IOException {
        List<ProfileTerrainRecorder.Sample> samples = ProfileTerrainRecorder.readAll(SAMPLES);
        Assumptions.assumeTrue(!samples.isEmpty(),
                "还没有真实地形录制（跑一次 -Droadweaver.profileAb=on，把 logs/roadweaver-terrain-ab.tsv "
                        + "放到 " + SAMPLES + "）—— 跳过");

        int feasible = 0;
        int dpWins = 0;
        int legacyWins = 0;
        int ties = 0;
        long legacyCut = 0;
        long dpCut = 0;
        StringBuilder rows = new StringBuilder();

        for (ProfileTerrainRecorder.Sample s : samples) {
            int[] terrain = s.terrain();
            boolean[] crossing = s.crossing();
            int[] legacy = legacyPipeline(terrain, crossing);
            Optional<int[]> dp = ProfileDpSource.solve(terrain, crossing, 24, 12, 1);
            ProfileMetrics a = ProfileMetrics.of(legacy, terrain, crossing);
            legacyCut += a.cut() + a.fill();
            if (dp.isEmpty()) {
                rows.append(String.format("  %-26s | 旧 %-30s | DP 不可行%n", s.tag(), a.line()));
                continue;
            }
            feasible++;
            ProfileMetrics b = ProfileMetrics.of(dp.get(), terrain, crossing);
            dpCut += b.cut() + b.fill();
            int aw = a.betterCount(b);
            int bw = b.betterCount(a);
            if (bw > aw) dpWins++;
            else if (aw > bw) legacyWins++;
            else ties++;
            rows.append(String.format("  %-26s | 旧 %-30s | DP %-30s | %s%n",
                    s.tag(), a.line(), b.line(), bw > aw ? "DP胜" : aw > bw ? "旧胜" : "平"));
        }

        System.out.println("[PROFILE-REPLAY] 真实地形重放：样本=" + samples.size() + rows);
        System.out.println("[PROFILE-REPLAY] 汇总：DP可行=" + feasible + " DP胜=" + dpWins
                + " 旧胜=" + legacyWins + " 平=" + ties
                + " 土方合计 旧=" + legacyCut + " DP=" + dpCut);

        assertTrue(feasible > 0, "重放里至少要有一条 DP 可行的样本才有意义");
        assertTrue(dpCut <= legacyCut,
                "在真实地形上 DP 的土方合计不应高于旧 pass：" + dpCut + " vs " + legacyCut);
    }

    @Test
    void 录制格式可以原样往返(@TempDir Path tmp) throws IOException {
        ProfileTerrainRecorder.resetForTest();
        System.setProperty("roadweaver.profileAb", "on");
        try {
            Path f = tmp.resolve("samples.tsv");
            int[] terrain = {64, 65, 66, 70, 70, 62};
            boolean[] crossing = {false, false, false, true, true, false};
            ProfileTerrainRecorder.recordTo(f, "(0,0)->(6,0)", terrain, crossing);

            List<ProfileTerrainRecorder.Sample> back = ProfileTerrainRecorder.readAll(f);
            assertEquals(1, back.size(), "case=one-line");
            ProfileTerrainRecorder.Sample s = back.get(0);
            assertEquals("(0,0)->(6,0)", s.tag(), "case=tag");
            assertEquals(6, s.terrain().length, "case=length");
            assertEquals(70, s.terrain()[3], "case=terrain-value");
            assertTrue(s.crossing()[4], "case=crossing-true");
            assertTrue(!s.crossing()[0], "case=crossing-false");

            // 坏行必须被跳过而不是炸掉整个重放
            Files.writeString(f, "broken-line-without-tabs\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            assertEquals(1, ProfileTerrainRecorder.readAll(f).size(), "case=bad-line-skipped");
        } finally {
            System.clearProperty("roadweaver.profileAb");
            ProfileTerrainRecorder.resetForTest();
        }
    }

    @Test
    void 关闭时一条都不写(@TempDir Path tmp) throws IOException {
        ProfileTerrainRecorder.resetForTest();
        System.clearProperty("roadweaver.profileAb");
        Path f = tmp.resolve("off.tsv");
        ProfileTerrainRecorder.recordTo(f, "x", new int[]{64, 64}, new boolean[2]);
        assertTrue(!Files.exists(f), "case=disabled-writes-nothing");
        assertEquals(0, ProfileTerrainRecorder.recordedCount(), "case=no-count");
    }
}