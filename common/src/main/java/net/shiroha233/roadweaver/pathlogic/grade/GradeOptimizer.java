/* 文件职责：最优坡度剖面求解 —— 在「土方量」与「偏离原高」之间找最优折中。  *
 * ⚠ 待删（批1）：重制台账「纵断面与坡度」域。已被 pathlogic/profile/ProfileDpSolver 取代。
 *   删除条件（三条全满足才许删，见 REFACTOR.md 的删除判定）：
 *     ① pathlogic/profile 的 DP 内核有独立测试且全绿；
 *     ② 本类零外部引用（全模块扫描，含 fabric/api 与 JSON）；
 *     ③ 删完回归仍全绿。
 *   现状（2026-11-14）：DP 已是默认，本类只剩「显式
 *   -Droadweaver.profileSolver=legacy」与「玩家关掉坡度限制」两条入口。
 */

package net.shiroha233.roadweaver.pathlogic.grade;

import net.minecraft.core.BlockPos;
import static net.shiroha233.roadweaver.pathlogic.grade.GradeOptimizer.*;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.constants.GradeCap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner.*;
import static net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer.*;

/**
 * 最优坡度剖面求解（动态规划）。
 *
 * <h2>它在解什么最优化问题</h2>
 * <p>给定每一站的原始地面高度，求一条满足坡度上限的剖面 y[i]，使总代价最小。
 * 代价 = <b>土方量</b>（挖填） + <b>偏离原高</b>（deviationCost）。</p>
 *
 * <h2>★ 为什么是 DP 而不是逐点贪心</h2>
 * <p>逐点贪心会造出「这里挖深一点、下一站又填回来」的来回折腾；DP 因为看得到
 * 整条窗口，能把连续几站的代价一起摊平。代价函数里 {@code deviationCost}
 * 的权重决定了「贴着地形走」还是「追求平顺」的倾向。</p>
 *
 * <h2>★ 走求的边界来自锚点</h2>
 * <p>{@code findLowerAnchorLeft/Right} 找的是窗口两端「不能低于」的锚点
 * （已有路口、水面、桥头）。DP 只在锚点之间求解 —— 越过锚点就等于
 * 把相邻路段的高程改掉，那是另一条路的事。</p>
 *
 * <p>本类是从 1298 行的 {@code RoadGradePlanner} 里<b>纯搬迁</b>出来的，
 * 代价函数与约束一字未改（含缩进）。</p>
 */
public final class GradeOptimizer {
    public static int[] solveOptimalGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments) {
        return solveOptimalGradeProfile(targetY, splitMask, maxStepPerTwoSegments, null);
    }

    /**
     * @param flatCostMask 洞身站点：偏离期望高度按对称单价计费（上/下同价），
     *                     否则"洞顶高于洞身"会被当成昂贵填方，洞口坡道反而比台阶更贵。
     */
    public static int[] solveOptimalGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments,
                                                 boolean[] flatCostMask) {
        return solveOptimalGradeProfile(targetY, splitMask, maxStepPerTwoSegments, flatCostMask, null);
    }

    /**
     * @param stationSpacing 相邻站真实距离（索引 i 是 i-1→i 的距离；{@code null} = 按站号计）
     */
    public static int[] solveOptimalGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments,
                                                 boolean[] flatCostMask,
                                                 double[] stationSpacing) {
        if (targetY == null || targetY.length == 0) {
            return new int[0];
        }
        int[] result = targetY.clone();
        if (targetY.length < 4) {
            return result;
        }
        int step2 = Math.max(0, Math.min(8, maxStepPerTwoSegments));
        if (step2 == 0) {
            return result;
        }
        int adjacentStep = Math.max(1, (step2 + 1) / 2);
        int index = 0;
        while (index < targetY.length) {
            if (isBridge(splitMask, index)) {
                index++;
                continue;
            }
            int start = index;
            while (index < targetY.length && !isBridge(splitMask, index)) {
                index++;
            }
            solveGradeSegment(targetY, result, start, index - 1, adjacentStep, flatCostMask, stationSpacing);
        }
        return result;
    }

    public static void solveGradeSegment(int[] targetY, int[] out,
                                          int start, int end,
                                          int adjacentStep,
                                          boolean[] flatCostMask,
                                          double[] stationSpacing) {
        int length = end - start + 1;
        if (length < 4) {
            return;
        }
        final int low = -ACCURATE_MAX_CUT;
        final int high = ACCURATE_MAX_FILL;
        final int h = high - low + 1;
        final int inf = Integer.MAX_VALUE / 4;

        int[] previous = new int[h];
        int[] current = new int[h];
        byte[] parent = new byte[length * h];
        java.util.Arrays.fill(previous, inf);
        for (int o = low; o <= high; o++) {
            previous[o - low] = deviationCost(o, isBridge(flatCostMask, start))
                    + ENDPOINT_DEVIATION_WEIGHT * Math.abs(o);
        }

        for (int k = 1; k < length; k++) {
            int targetPrev = targetY[start + k - 1];
            int targetHere = targetY[start + k];
            java.util.Arrays.fill(current, inf);
            for (int c = low; c <= high; c++) {
                int y = targetHere + c;
                int best = inf;
                int bestA = low;
                for (int a = low; a <= high; a++) {
                    int value = previous[a - low];
                    if (value >= inf) {
                        continue;
                    }
                    int step = Math.abs(y - (targetPrev + a));
                    int excess = Math.max(0, step - adjacentStepAt(adjacentStep, stationSpacing, start + k));
                    int cost = value + excess * SLOPE_EXCESS_WEIGHT;
                    if (cost < best) {
                        best = cost;
                        bestA = a;
                    }
                }
                if (best >= inf) {
                    continue;
                }
                int total = best + deviationCost(c, isBridge(flatCostMask, start + k));
                if (total < current[c - low]) {
                    current[c - low] = total;
                    parent[k * h + (c - low)] = (byte) (bestA - low);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        int bestC = low;
        int bestCost = inf;
        for (int c = low; c <= high; c++) {
            int total = previous[c - low] + ENDPOINT_DEVIATION_WEIGHT * Math.abs(c);
            if (total < bestCost) {
                bestCost = total;
                bestC = c;
            }
        }
        if (bestCost >= inf) {
            return;
        }

        int[] offsets = new int[length];
        int cursor = bestC;
        for (int k = length - 1; k >= 0; k--) {
            offsets[k] = cursor;
            if (k == 0) {
                break;
            }
            cursor = (parent[k * h + (cursor - low)] & 0xFF) + low;
        }
        for (int k = 0; k < length; k++) {
            out[start + k] = targetY[start + k] + offsets[k];
        }
    }

    public static int deviationCost(int offset) {
        return deviationCost(offset, false);
    }

    public static int deviationCost(int offset, boolean flat) {
        if (offset == 0) {
            return 0;
        }
        if (flat) {
            // 洞身：上下同价，鼓励用洞口坡道代替台阶。
            return Math.abs(offset) * 2;
        }
        if (offset < 0) {
            int cut = -offset;
            return cut <= 4 ? cut : 4 + (cut - 4) * 5;
        }
        int fill = offset;
        return fill <= 2 ? fill * 3 : 6 + (fill - 2) * 10;
    }

    /**
     * True when the mountain run between the two anchors can be crossed by an
     * ordinary surface cut: the slope-limited terrain-following profile stays
     * within the short-grade cut budget ({@link #TUNNEL_SURFACE_CUT_BUDGET}).
     * The bidirectional slope-clamped propagation mirrors
     * {@code enforceContinuousSlope} so the feasibility check cannot accept a
     * surface profile that the final grade pass would reject.
     */
    public static boolean surfaceCutFeasible(int[] terrain, boolean[] crossing,
                                              int leftAnchor, int rightAnchor,
                                              int maxStepPerTwoSegments,
                                              int tunnelCoreOverburden) {
        int adjacentStep = maxStepPerTwoSegments > 0
                ? Math.max(1, (Math.min(8, maxStepPerTwoSegments) + 1) / 2)
                : Integer.MAX_VALUE / 2;
        int from = Math.max(0, Math.min(leftAnchor, rightAnchor));
        int to = Math.min(terrain.length - 1, Math.max(leftAnchor, rightAnchor));
        int[] forward = new int[terrain.length];
        int y = terrain[from];
        forward[from] = y;
        for (int i = from + 1; i <= to; i++) {
            y = clamp(terrain[i], y - adjacentStep, y + adjacentStep);
            forward[i] = y;
        }
        int[] backward = new int[terrain.length];
        y = terrain[to];
        backward[to] = y;
        for (int i = to - 1; i >= from; i--) {
            y = clamp(terrain[i], y - adjacentStep, y + adjacentStep);
            backward[i] = y;
        }
        int deepest = 0;
        for (int i = from; i <= to; i++) {
            if (isBridge(crossing, i)) continue;
            int upper = Math.min(forward[i], backward[i]);
            deepest = Math.max(deepest, terrain[i] - upper);
            if (terrain[i] - upper > TUNNEL_SURFACE_CUT_BUDGET) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("RoadWeaver tunnel chord justified: surface cut would reach {} blocks at station {} (anchor span {}..{}) overburden threshold {}",
                            terrain[i] - upper, i, from, to, tunnelCoreOverburden);
                }
                return false;
            }
        }
        return true;
    }

    public static int findLowerAnchorLeft(int[] terrain, boolean[] crossing, int index,
                                           int tunnelCoreOverburden) {        int lowerBound = Math.max(0, index - TUNNEL_ANCHOR_SEARCH_STATIONS);
        for (int candidate = index - 1; candidate >= lowerBound; candidate--) {
            if (isBridge(crossing, candidate)
                    || terrain[candidate] + tunnelCoreOverburden > terrain[index]) continue;
            return candidate;
        }
        return -1;
    }

    public static int findLowerAnchorRight(int[] terrain, boolean[] crossing, int index,
                                            int tunnelCoreOverburden) {
        int upperBound = Math.min(terrain.length - 1, index + TUNNEL_ANCHOR_SEARCH_STATIONS);
        for (int candidate = index + 1; candidate <= upperBound; candidate++) {
            if (isBridge(crossing, candidate)
                    || terrain[candidate] + tunnelCoreOverburden > terrain[index]) continue;
            return candidate;
        }
        return -1;
    }

    public static boolean aboveAnchorChord(int[] terrain,
                                            int leftAnchor,
                                            int rightAnchor,
                                            int index,
                                            int tunnelCoreOverburden) {
        double ratio = (index - leftAnchor) / (double) (rightAnchor - leftAnchor);
        double chord = terrain[leftAnchor] + (terrain[rightAnchor] - terrain[leftAnchor]) * ratio;
        return terrain[index] - chord >= tunnelCoreOverburden;
    }

    public static boolean[] mergeMasks(boolean[] first, boolean[] second, int length) {
        boolean[] result = new boolean[Math.max(0, length)];
        for (int i = 0; i < result.length; i++) {
            result[i] = isBridge(first, i) || isBridge(second, i);
        }
        return result;
    }
}
