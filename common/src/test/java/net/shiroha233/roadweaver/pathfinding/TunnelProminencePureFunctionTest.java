package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.063（任务二）：隧道突起度判据的**纯函数**用例。
 *
 * <p>判据 = 「地形高度 − 独立下包络」，包络是沿线 ±24 站的 10% 分位。它<b>不读规划路面</b>，
 * 所以「纵断面沿山头爬上去把覆土抹平」这条失效路径从结构上就不存在了。</p>
 *
 * <p>三个必须成立的地形：宽 30 站的对称山（必须判出）、缓丘（不得判出）、宽 8 站的窄脊
 * （必须判出 —— 生产里那条「地形 − 规划路面 Y」的旧规则在这里会被爬山的路面抹平）。</p>
 */
class TunnelProminencePureFunctionTest {

    /**
     * 造一座「对称帐篷山」：{@code from} 起 {@code width} 站，峰高 {@code height}，其余为 {@code base}。
     * 用整数四舍五入（{@code Math.round}），山顶会天然出现一小段等高平台 —— 判据必须能处理它。
     */
    private static int[] tent(int length, int base, int from, int width, int height) {
        int[] terrain = new int[length];
        java.util.Arrays.fill(terrain, base);
        double center = from + (width - 1) / 2.0D;
        for (int i = from; i < from + width; i++) {
            double distance = Math.abs(i - center);
            double fraction = Math.max(0.0D, 1.0D - distance / (width / 2.0D));
            terrain[i] = base + (int) Math.round(height * fraction);
        }
        return terrain;
    }

    /** 取突起度里最长的那一段（判据按段评估；测试只关心主峰）。 */
    private static int[] longestRun(int[] prominence) {
        int[] runs = TunnelEnvelopeCriterion.runs(prominence, TunnelEnvelopeCriterion.SEED_PROMINENCE_BLOCKS);
        int best = -1;
        int bestLength = 0;
        for (int i = 0; i + 1 < runs.length; i += 2) {
            int length = runs[i + 1] - runs[i] + 1;
            if (length > bestLength) {
                bestLength = length;
                best = i;
            }
        }
        return best < 0 ? new int[0] : new int[]{runs[best], runs[best + 1]};
    }

    private static TunnelEnvelopeCriterion.Candidate mainCandidate(int[] terrain) {
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrain);
        int[] run = longestRun(prominence);
        if (run.length != 2) return null;
        return TunnelEnvelopeCriterion.evaluate(terrain, prominence, run[0], run[1]);
    }


    /** 宽 30 站、高 12 的对称山：突起度必须 > 0（旧中值基准会把它大部分抹平）。 */
    @Test
    void wideThirtyStationMountainCarriesPositiveProminence() {
        int[] terrain = tent(71, 64, 20, 30, 12);
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrain);
        int promMax = 0;
        for (int value : prominence) promMax = Math.max(promMax, value);
        assertTrue(promMax > 0, "宽 30 站的山必须判出突起度，实际 promMax=" + promMax);
        assertEquals(12, promMax);

        // 「自我抵消」的直接量化：同一座山，按 ±8 站中值基准只有 3 格（高地自己成了基准）
        int medianBaselineMax = medianBaselineMax(terrain, 8);
        assertEquals(3, medianBaselineMax);
        assertTrue(promMax > medianBaselineMax * 3,
                "下包络必须显著高于中值基准，实际 " + promMax + " vs " + medianBaselineMax);

        TunnelEnvelopeCriterion.Candidate candidate = mainCandidate(terrain);
        assertNotNull(candidate);
        assertEquals(12, candidate.promMax());
        assertTrue(candidate.elected(), "宽 30 站、高 12 的山必须被选中（why=" + candidate.why().logValue() + "）");
    }

    /** 缓丘（宽 60 站、高 3）：不得判成隧道候选。 */
    @Test
    void gentleHillIsNeverATunnelCandidate() {
        int[] terrain = tent(81, 64, 10, 60, 3);
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrain);
        int promMax = 0;
        for (int value : prominence) promMax = Math.max(promMax, value);
        assertTrue(promMax <= TunnelEnvelopeCriterion.MIN_PROMINENCE_BLOCKS,
                "缓丘的突起度必须低于候选门槛，实际 " + promMax);

        TunnelEnvelopeCriterion.Candidate candidate = mainCandidate(terrain);
        assertNotNull(candidate, "缓丘仍然会切出一个突起段（种子=2），但必须被判为不合格");
        assertFalse(candidate.elected(), "缓丘不得判成隧道");
        assertEquals(TunnelEnvelopeCriterion.Why.NO_PROMINENCE, candidate.why());
        assertEquals("no-prominence", candidate.why().logValue());
    }

    /** 宽 8 站、高 11 的窄脊：新判据**必须**能判出。 */
    @Test
    void narrowEightStationRidgeIsDetected() {
        int[] terrain = tent(61, 64, 26, 8, 12);
        TunnelEnvelopeCriterion.Candidate candidate = mainCandidate(terrain);
        assertNotNull(candidate);
        assertEquals(8, candidate.stations());
        assertEquals(11, candidate.promMax());
        assertTrue(candidate.elected(),
                "宽 8 站的窄脊必须能判出隧道（why=" + candidate.why().logValue() + "）");
        System.out.println(candidate.logLine());
        System.out.println(candidate.routeChoiceLine());
    }

    /**
     * 负证据：旧的**生产**规则（{@code 地形 − 规划路面 Y ≥ tunnelCoreOverburden}）在窄脊上一定判不出来。
     *
     * <p>规划路面会沿着窄脊爬上去（这条路本来就是这么生成的），于是「地形 − 路面」在脊顶只剩 1 格，
     * 远小于 8 的覆土门槛。新判据换成包络之后与路面无关，所以同一座脊仍然判得出。</p>
     */
    @Test
    void legacyPlannedProfileRuleErasesTheNarrowRidge() {
        int[] terrain = tent(61, 64, 26, 8, 12);
        int threshold = 8;                     // RoadGradePlanner.TUNNEL_CORE_OVERBURDEN 的出厂值
        int[] plannedY = new int[terrain.length];
        for (int i = 0; i < terrain.length; i++) {
            plannedY[i] = terrain[i] - 1;      // 纵断面贴着地表爬上去（平滑后高 1 格以内）
        }
        int maxOverburden = Integer.MIN_VALUE;
        for (int i = 0; i < terrain.length; i++) {
            maxOverburden = Math.max(maxOverburden, terrain[i] - plannedY[i]);
        }
        assertEquals(1, maxOverburden);
        assertTrue(maxOverburden < threshold,
                "旧规则在这座脊上必须判不出来（覆土 " + maxOverburden + " < 门槛 " + threshold + "）");

        TunnelEnvelopeCriterion.Candidate candidate = mainCandidate(terrain);
        assertNotNull(candidate);
        assertTrue(candidate.elected(), "同一条脊，新判据必须判得出");
        assertEquals(11, candidate.promMax());
    }

    /** 包络是「下」包络：谷地突起度恒 0，且突起度只由地形决定（与规划路面无关）。 */
    @Test
    void envelopeIsALowerEnvelopeAndIgnoresTheRoadProfile() {
        int[] terrain = new int[60];
        java.util.Arrays.fill(terrain, 64);
        terrain[30] = 50;   // 一个深坑
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrain);
        assertEquals(0, prominence[30], "谷地/坑底突起度必须是 0");
        for (int value : prominence) {
            assertTrue(value >= 0, "突起度不允许为负");
        }
        // 同一份地形跑两次必须逐位相同（纯函数，没有隐藏状态）
        int[] again = TunnelEnvelopeCriterion.prominence(terrain.clone());
        assertArrayEqualsInts(prominence, again);
    }

    /** ±window 站「中值基准」的手写复刻，只用来量化「自我抵消」，不参与判据。 */
    private static int medianBaselineMax(int[] terrain, int halfWindow) {
        int n = terrain.length;
        int best = 0;
        for (int i = 0; i < n; i++) {
            int back = median(terrain, Math.max(0, i - halfWindow), i - 1);
            int forward = median(terrain, i + 1, Math.min(n - 1, i + halfWindow));
            if (back == Integer.MIN_VALUE || forward == Integer.MIN_VALUE) continue;
            best = Math.max(best, terrain[i] - Math.max(back, forward));
        }
        return Math.max(0, best);
    }

    private static int median(int[] values, int from, int to) {
        if (from > to) return Integer.MIN_VALUE;
        int[] copy = java.util.Arrays.copyOfRange(values, from, to + 1);
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static void assertArrayEqualsInts(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
