package net.shiroha233.roadweaver.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.063（任务二/三）：{@code [TUNNEL-CANDIDATE] why=} 的四个分支 + {@code elected} 至少各一条用例。
 *
 * <p>门顺序（全部是常量，见 {@link TunnelEnvelopeCriterion}）：</p>
 * <ol>
 *   <li>{@code no-prominence}   —— 段内突起度中位数 &lt; 4 格；</li>
 *   <li>{@code overburden-low}  —— promMax − 埋深 7 &lt; 1 格；</li>
 *   <li>{@code portal-too-long} —— 按允许坡度（0.08）爬完 promMax 所需站数 ≤ 山体实际坡面站数
 *       （= 整座山都够当明挖引道），或突起度达到埋深的连续站不足 3 站；</li>
 *   <li>{@code cost-loses-to-detour} —— 前三条都过（隧道会建），但代价比较不占优。
 *       <b>本版只打标签不改选择</b>，所以这一条与 {@code elected=yes} 同时出现；</li>
 *   <li>{@code elected} —— 前三条都过，且代价比较也选隧道。</li>
 * </ol>
 */
class TunnelCandidateWhyBranchTest {

    /** 造一座「对称帐篷山」：{@code from} 起 {@code width} 站，峰高 {@code height}，其余为 {@code base}。 */
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

    /** 取突起度里最长的一段（判据按段评估）。 */
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

    private static TunnelEnvelopeCriterion.Candidate candidateOf(int[] terrain) {
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrain);
        int[] run = longestRun(prominence);
        if (run.length != 2) return null;
        return TunnelEnvelopeCriterion.evaluate(terrain, prominence, run[0], run[1]);
    }


    @Test
    void whyNoProminenceOnAGentleHill() {
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(81, 64, 10, 60, 3));
        assertNotNull(candidate);
        assertFalse(candidate.elected());
        assertEquals(TunnelEnvelopeCriterion.Why.NO_PROMINENCE, candidate.why());
        assertEquals("no-prominence", candidate.why().logValue());
        assertEquals(2, candidate.promMax());
        assertEquals(2, candidate.promP50());
        System.out.println(candidate.logLine());
    }

    @Test
    void whyOverburdenLowOnALowRidge() {
        // 宽 10 站、高 6：突起度够了（p50=4），但 6 − 7 < 1，覆土不足
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(41, 64, 16, 10, 6));
        assertNotNull(candidate);
        assertFalse(candidate.elected());
        assertEquals(TunnelEnvelopeCriterion.Why.OVERBURDEN_LOW, candidate.why());
        assertEquals("overburden-low", candidate.why().logValue());
        assertEquals(4, candidate.promP50());
        assertEquals(5, candidate.promMax());
        assertEquals(-2, candidate.overburdenMax());
        System.out.println(candidate.logLine());
    }

    @Test
    void whyPortalTooLongWhenTheWholeHillIsAnOpenRamp() {
        // 宽 40 站、高 8：按 0.08 坡度爬完 8 格只要 13 站，而山体一侧有 16 站 ⇒ 直接开上去就行
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(101, 64, 20, 40, 8));
        assertNotNull(candidate);
        assertFalse(candidate.elected());
        assertEquals(TunnelEnvelopeCriterion.Why.PORTAL_TOO_LONG, candidate.why());
        assertEquals("portal-too-long", candidate.why().logValue());
        assertEquals(8, candidate.promMax());
        assertEquals(12, candidate.portalLen());
        System.out.println(candidate.logLine());
    }

    @Test
    void whyCostLosesToDetourOnAWideMountain() {
        // 宽 30 站、高 12：三条几何门全过（隧道会建），但绕行比凿洞便宜 ⇒ 只打标签
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(71, 64, 20, 30, 12));
        assertNotNull(candidate);
        assertTrue(candidate.elected(), "几何门全过：隧道仍然会被建出来（本版不按代价改选择）");
        assertEquals(TunnelEnvelopeCriterion.Why.COST_LOSES_TO_DETOUR, candidate.why());
        assertEquals("cost-loses-to-detour", candidate.why().logValue());
        assertEquals(TunnelEnvelopeCriterion.Choice.SWITCHBACK, candidate.chosen());
        assertTrue(candidate.tunnelCost() > candidate.switchbackCost(),
                "隧道代价必须真的输给绕行：" + candidate.tunnelCost() + " vs " + candidate.switchbackCost());
        System.out.println(candidate.logLine());
        System.out.println(candidate.routeChoiceLine());
    }

    @Test
    void whyElectedOnANarrowSteepRidge() {
        // 宽 8 站、高 11：既挑不出明挖引道，凿洞也比绕山便宜
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(61, 64, 26, 8, 12));
        assertNotNull(candidate);
        assertTrue(candidate.elected());
        assertEquals(TunnelEnvelopeCriterion.Why.ELECTED, candidate.why());
        assertEquals("elected", candidate.why().logValue());
        assertEquals(TunnelEnvelopeCriterion.Choice.TUNNEL, candidate.chosen());
        assertTrue(candidate.tunnelCost() < candidate.switchbackCost(),
                "隧道代价必须真的低于绕行：" + candidate.tunnelCost() + " vs " + candidate.switchbackCost());
        assertTrue(candidate.tunnelCost() < candidate.climbCost());
    }

    /** 两条日志行的字段必须齐、单位必须能对上（报告里要逐字贴）。 */
    @Test
    void logLinesCarryTheAgreedFields() {
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(61, 64, 26, 8, 12));
        assertNotNull(candidate);
        String candidateLine = candidate.logLine();
        for (String field : new String[]{"seg=", "stations=", "promP50=", "promMax=", "overburdenMax=",
                "portalLen=", "elected=", "why="}) {
            assertTrue(candidateLine.contains(field), "缺字段 " + field + " in " + candidateLine);
        }
        assertEquals("[TUNNEL-CANDIDATE] seg=26-33 stations=8 promP50=8 promMax=11 overburdenMax=4 "
                + "portalLen=2 elected=yes why=elected", candidateLine);

        String choiceLine = candidate.routeChoiceLine();
        for (String field : new String[]{"connector=", "switchbackCost=", "tunnelCost=", "chosen="}) {
            assertTrue(choiceLine.contains(field), "缺字段 " + field + " in " + choiceLine);
        }
        assertTrue(choiceLine.contains("chosen=tunnel"), choiceLine);
        assertTrue(choiceLine.contains("climbCost="), "硬爬代价也要打出来（chosen=climb 分支要用）");
    }

    /** 三个候选选项都能出现：chosen=climb 在缓丘上（直接翻过去最便宜）。 */
    @Test
    void chosenClimbAppearsOnAGentleHill() {
        TunnelEnvelopeCriterion.Candidate candidate = candidateOf(tent(81, 64, 10, 60, 3));
        assertNotNull(candidate);
        assertEquals(TunnelEnvelopeCriterion.Choice.CLIMB, candidate.chosen());
        assertTrue(candidate.routeChoiceLine().contains("chosen=climb"));
    }
}
