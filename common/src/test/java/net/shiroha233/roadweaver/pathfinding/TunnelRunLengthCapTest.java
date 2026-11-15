package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隧道 run 的最大长度与天窗（0.9.081）。
 *
 * <p>真机取证：2026-11-11 那局出现过 <b>1012 站 = 8128 格 ≈ 8 公里</b>的单条隧道，
 * 占所在道路的 44%。根因是 {@code runs()} 只在「突起度局部低谷」切分，
 * 连绵山区上千站没有低谷就整段连成一条。现实里没有 8 公里连续隧道。</p>
 *
 * <p>本类钉住：超长 run 必须按 256 站切分、段间留 8 站天窗、短 run 原样不动。</p>
 */
class TunnelRunLengthCapTest {

    private static int[] plateau(int stations, int prominence) {
        int[] values = new int[stations];
        Arrays.fill(values, prominence);
        return values;
    }

    @Test
    void shortRunIsNotSplit() {
        int[] runs = TunnelEnvelopeCriterion.runs(plateau(100, 20), 2);
        assertArrayEquals(new int[]{0, 99}, runs);
    }

    @Test
    void runExactlyAtCapIsNotSplit() {
        int cap = TunnelEnvelopeCriterion.MAX_TUNNEL_RUN_STATIONS;
        int[] runs = TunnelEnvelopeCriterion.runs(plateau(cap, 20), 2);
        assertArrayEquals(new int[]{0, cap - 1}, runs);
    }

    @Test
    void splitOnlyWhenTheTailSurvivesTheWindow() {
        // 切一刀要付「天窗 + 最小洞身」，剩余不够就宁可让这一段稍长也不切 ——
        // 否则 257 站会被切成 256 + 天窗，把队尾那一站整段吞掉。
        int cap = TunnelEnvelopeCriterion.MAX_TUNNEL_RUN_STATIONS;
        int window = TunnelEnvelopeCriterion.TUNNEL_WINDOW_STATIONS;
        int core = TunnelEnvelopeCriterion.MIN_SEALED_CORE_STATIONS;

        int justOver = cap + 1;
        assertArrayEquals(new int[]{0, justOver - 1},
                TunnelEnvelopeCriterion.runs(plateau(justOver, 20), 2),
                "仅超 1 站不值得切：切完队尾仅剩 1 站，会被天窗吞掉");

        int longEnough = cap + window + core + 1;
        int[] runs = TunnelEnvelopeCriterion.runs(plateau(longEnough, 20), 2);
        assertEquals(4, runs.length, "留得下天窗+最小洞身时必须真的切");
        assertEquals(0, runs[0]);
        assertEquals(cap - 1, runs[1]);
        assertEquals(cap + window, runs[2], "第二段应从第一段末 + 天窗之后开始");
        assertEquals(longEnough - 1, runs[3]);
    }

    @Test
    void eightKilometreRunIsBrokenIntoLegalPieces() {
        // 真机那个 1012 站的实际形态：连续高突起度、中间没有低谷
        int stations = 1012;
        int cap = TunnelEnvelopeCriterion.MAX_TUNNEL_RUN_STATIONS;
        int window = TunnelEnvelopeCriterion.TUNNEL_WINDOW_STATIONS;
        int[] runs = TunnelEnvelopeCriterion.runs(plateau(stations, 20), 2);
        assertTrue(runs.length >= 6, "1012 站至少切成 4 段，实际段数 " + (runs.length / 2));
        for (int i = 0; i < runs.length; i += 2) {
            int length = runs[i + 1] - runs[i] + 1;
            assertTrue(length <= cap, "每段不得超过 " + cap + " 站，实际 " + length);
        }
        // 段间必须留出完整天窗，否则视觉上仍是连续隧道
        for (int i = 2; i < runs.length; i += 2) {
            int gap = runs[i] - runs[i - 2 + 1] - 1;
            assertEquals(window, gap, "段间天窗应为 " + window + " 站，实际 " + gap);
        }
        // 覆盖必须连续：第一段从 0 开始，最后一段到 stations-1 结束
        assertEquals(0, runs[0]);
        assertEquals(stations - 1, runs[runs.length - 1]);
    }

    @Test
    void lowProminenceStaysOutsideAnyRun() {
        int[] values = plateau(600, 20);
        // 中间插一段低突起度：那里本来就应该切开
        for (int i = 300; i < 320; i++) {
            values[i] = 0;
        }
        int[] runs = TunnelEnvelopeCriterion.runs(values, 2);
        // ★ 2026-11-15 补：下面那个循环在 runs 为空时一次都不执行 ⇒ 测试会**真空转绿**。
        //   负控实证：把 plateau(600, 20) 改成 plateau(600, 0)（突起度归零 ⇒ runs 为空），
        //   本条仍然 PASS。所以必须先钉住「这里确实产生了 run」。
        assertTrue(runs.length > 0,
                "构造前提：突起度 20 的 600 站平台上必须至少产生一个 run，"
                        + "否则下面的循环空转，测试什么也没验证");
        for (int i = 0; i < runs.length; i += 2) {
            assertTrue(runs[i + 1] < 300 || runs[i] > 319,
                    "任何 run 都不得跨过 300..319 的低突起度谷");
        }
    }

    @Test
    void onlyTheLengthCapChangedInThisRound() {
        // 0.9.081 只新增了长度上限与天窗；突起度与洞身最小站数经实测复核后维持原值
        // （抬 MIN_PROMINENCE_BLOCKS 会连「中间高两侧缓」的宽山一起判掉；
        //   抬 MIN_SEALED_CORE_STATIONS 会让 8 站窄脊判不出隧道 → 整道脊被明挖削平）
        assertEquals(4, TunnelEnvelopeCriterion.MIN_PROMINENCE_BLOCKS,
                "维持 4：抬到 8 会砍掉中间高两侧缓的宽山");
        assertEquals(3, TunnelEnvelopeCriterion.MIN_SEALED_CORE_STATIONS,
                "维持 3：抬到 6 会让 8 站窄脊判不出隧道，整道脊被削平");
        assertEquals(256, TunnelEnvelopeCriterion.MAX_TUNNEL_RUN_STATIONS);
        assertEquals(8, TunnelEnvelopeCriterion.TUNNEL_WINDOW_STATIONS);
    }
}
