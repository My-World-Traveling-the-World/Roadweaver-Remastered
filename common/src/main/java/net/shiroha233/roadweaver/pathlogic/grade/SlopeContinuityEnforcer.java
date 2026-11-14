/* 文件职责：连续坡度强制 —— 消除「隔一站跳一次」的台阶与针尖。  *
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
import static net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer.*;
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
import static net.shiroha233.roadweaver.pathlogic.grade.GradeOptimizer.*;

/**
 * 连续坡度强制。
 *
 * <h2>它解决什么</h2>
 * <p>DP 解出来的剖面在<b>整数高度</b>上仍可能「跳一格、平一格、再跳一格」——
 * 每一站的坡度都在上限内，但**观感上是一串台阶**。本类把这串台阶摊成连续坡。</p>
 *
 * <h2>两个方向：优先挖 / 优先填</h2>
 * <p>{@code enforceContinuousSlopePreferCut} 与不带的版本差别只在「多出来的高度
 * 从哪来」：优先挖是把高点削掉、优先填是把低点垫起来。哪一个更好取决于
 * 这一段是山坡（挖便宜）还是水边（填会灌水）。</p>
 *
 * <h2>★ 针尖单独处理</h2>
 * <p>{@code removeSingleStationSpikes} 处理「只凸起一站」的情况 ——
 * 那种凸起坡度量不出来（相邻两站都合规），但玩家过的时候会颠一下。
 * 这一类必须单独判，否则连续坡算法看不出来。</p>
 *
 * <p>本类是从 1298 行的 {@code RoadGradePlanner} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class SlopeContinuityEnforcer {
    public static int[] enforceContinuousSlope(int[] source,
                                               boolean[] protectedMask,
                                               int maxStepPerTwoSegments) {
        if (source == null || source.length == 0) return new int[0];
        int[] result = source.clone();
        int step2 = Math.max(0, Math.min(8, maxStepPerTwoSegments));
        if (step2 == 0) return result;

        int index = 0;
        while (index < result.length) {
            while (index < result.length && isBridge(protectedMask, index)) index++;
            int start = index;
            while (index < result.length && !isBridge(protectedMask, index)) index++;
            int end = index - 1;
            if (start <= end) constrainRun(result, start, end, step2);
        }
        return result;
    }

    /**
     * Resolves an unavoidable cliff by lowering the high side instead of
     * raising the low side. The lowered sustained section is then eligible
     * for tunnel extraction; ordinary terrain is never turned into sky road.
     */
    public static int[] enforceContinuousSlopePreferCut(int[] source,
                                                        boolean[] protectedMask,
                                                        int maxStepPerTwoSegments) {
        return enforceContinuousSlopePreferCut(source, protectedMask, maxStepPerTwoSegments, null);
    }

    /**
     * 0.8.369 (T2): {@code pinnedMask} names the stations whose height is a hard
     * structure boundary lock. Every pass here only ever <em>lowers</em> a
     * station ({@code Math.min}), so a locked endpoint was silently pulled back
     * down to meet whatever the tunnel chord or terrain clamp wanted.
     *
     * <p>Measured on a real save: a route leaving a mountain village locked
     * {@code targetY[0] = 407} (exactly the sampled village surface) but shipped
     * with {@code 389}. The other endpoint, whose last span did not cover it,
     * matched its lock exactly — the only difference was whether the endpoint
     * fell inside a TUNNEL span. Of 23 connections, 8 ended up 2..25 blocks
     * below their locked surface.</p>
     *
     * <p>Pinned stations are therefore treated as immovable: they are skipped
     * while lowering and they still act as the fixed end of every gradient
     * constraint around them, so the approach keeps its legal one-block ramp
     * and no sky road can appear.</p>
     */
    public static int[] enforceContinuousSlopePreferCut(int[] source,
                                                        boolean[] protectedMask,
                                                        int maxStepPerTwoSegments,
                                                        boolean[] pinnedMask) {
        return enforceContinuousSlopePreferCut(source, protectedMask, maxStepPerTwoSegments, pinnedMask, null);
    }

    /**
     * @param stationSpacing 相邻站真实水平距离（索引 i 是 i-1→i 的距离；{@code null} = 按站号计）。
     *                       斜向一步是 {@code sqrt(2)} 格，按站号限坡会让同一段路在斜向更陡；
     *                       传真实距离后「每两站允许的高差」按实际走过的长度换算，方向不再影响坡度。
     *                       只会放宽长边、绝不收紧短边，因此 {@code null} 与旧行为逐位相同。
     */
    public static int[] enforceContinuousSlopePreferCut(int[] source,
                                                        boolean[] protectedMask,
                                                        int maxStepPerTwoSegments,
                                                        boolean[] pinnedMask,
                                                        double[] stationSpacing) {
        if (source == null || source.length == 0) return new int[0];
        int[] result = source.clone();
        int step2 = Math.max(0, Math.min(8, maxStepPerTwoSegments));
        if (step2 == 0) return result;

        int index = 0;
        while (index < result.length) {
            while (index < result.length && isBridge(protectedMask, index)) index++;
            int start = index;
            while (index < result.length && !isBridge(protectedMask, index)) index++;
            int end = index - 1;
            if (start <= end) lowerRunToSlope(result, start, end, step2, pinnedMask, stationSpacing);
        }
        return result;
    }

    public static void lowerRunToSlope(int[] profile, int start, int end, int step2) {
        lowerRunToSlope(profile, start, end, step2, null, null);
    }

    /**
     * Pinned stations keep their height. They still constrain their neighbours,
     * because the sweep reads {@code profile[i - 1]} / {@code profile[i + 1]} of
     * the live array — skipping only the write leaves the ramp anchored on the
     * lock instead of flattening through it.
     */
    public static void lowerRunToSlope(int[] profile, int start, int end, int step2,
                                        boolean[] pinnedMask) {
        lowerRunToSlope(profile, start, end, step2, pinnedMask, null);
    }

    public static void lowerRunToSlope(int[] profile, int start, int end, int step2,
                                        boolean[] pinnedMask, double[] stationSpacing) {
        int adjacentStep = Math.max(1, (step2 + 1) / 2);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = start + 1; i <= end; i++) {
                if (isPinned(pinnedMask, i)) continue;
                profile[i] = Math.min(profile[i],
                        profile[i - 1] + adjacentStepAt(adjacentStep, stationSpacing, i));
                if (i >= start + 2) {
                    profile[i] = Math.min(profile[i],
                            profile[i - 2] + twoStepAt(step2, stationSpacing, i));
                }
            }
            for (int i = end - 1; i >= start; i--) {
                if (isPinned(pinnedMask, i)) continue;
                profile[i] = Math.min(profile[i],
                        profile[i + 1] + adjacentStepAt(adjacentStep, stationSpacing, i + 1));
                if (i <= end - 2) {
                    profile[i] = Math.min(profile[i],
                            profile[i + 2] + twoStepAt(step2, stationSpacing, i + 2));
                }
            }
        }
    }

    /**
     * {@return the rise allowed between station {@code index - 1} and {@code index}}
     *
     * <p>Only ever relaxes: a longer edge (a diagonal is {@code sqrt(2)} blocks) may rise
     * proportionally more, exactly so the real grade matches the axial one. With no spacing - the
     * default - this is the old constant step, bit for bit.
     */
    public static int adjacentStepAt(int adjacentStep, double[] spacing, int index) {
        if (spacing == null || index <= 0 || index >= spacing.length) return adjacentStep;
        double length = spacing[index];
        if (!(length > 0.0D) || Double.isNaN(length)) return adjacentStep;
        return Math.max(adjacentStep, (int) Math.round(adjacentStep * length));
    }

    /** {@return the rise allowed across two stations, scaled by the real length they span} */
    public static int twoStepAt(int step2, double[] spacing, int index) {
        if (spacing == null || index < 2 || index >= spacing.length) return step2;
        double first = spacing[index];
        double second = spacing[index - 1];
        if (!(first > 0.0D) || !(second > 0.0D)) return step2;
        double average = Math.max(1.0D, Math.min(first, second)) * 0.5D
                + Math.max(1.0D, Math.max(first, second)) * 0.5D;
        return Math.max(step2, (int) Math.round(step2 * average));
    }

    /**
     * {@return the per-station spacing of a route, or {@code null} when the arc-length model is off}
     *
     * <p>Index {@code i} holds the horizontal distance from station {@code i - 1} to station
     * {@code i}; index 0 is the nominal 1.0. Vertical distance is deliberately not included: the
     * limit being modelled is a grade (rise over run), so only run belongs in the denominator.
     */
    public static double[] configuredStationSpacing(java.util.List<BlockPos> positions) {
        try {
            if (!ConfigService.get().slopeByArcLength()) return null;
        } catch (Throwable ignored) {
            return null;
        }
        return stationSpacing(positions);
    }

    /** {@return the nominal spacing array for a route; index 0 is 1.0 by construction} */
    public static double[] stationSpacing(java.util.List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return null;
        int count = positions.size();
        double[] spacing = new double[count];
        spacing[0] = 1.0D;
        for (int i = 1; i < count; i++) {
            BlockPos previous = positions.get(i - 1);
            BlockPos current = positions.get(i);
            double dx = current.getX() - previous.getX();
            double dz = current.getZ() - previous.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            spacing[i] = horizontal > 0.0D ? horizontal : 1.0D;
        }
        return spacing;
    }

    public static void constrainRun(int[] profile, int start, int end, int step2) {
        int halfLow = Math.max(0, step2 / 2);
        int halfHigh = Math.max(0, (step2 + 1) / 2);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = start + 1; i <= end; i++) {
                int adjacentStep = i == start + 1 ? halfLow : halfHigh;
                profile[i] = clamp(profile[i], profile[i - 1] - adjacentStep,
                        profile[i - 1] + adjacentStep);
                if (i >= start + 2) {
                    profile[i] = clamp(profile[i], profile[i - 2] - step2,
                            profile[i - 2] + step2);
                }
            }
            for (int i = end - 1; i >= start; i--) {
                int adjacentStep = i == end - 1 ? halfLow : halfHigh;
                profile[i] = clamp(profile[i], profile[i + 1] - adjacentStep,
                        profile[i + 1] + adjacentStep);
                if (i <= end - 2) {
                    profile[i] = clamp(profile[i], profile[i + 2] - step2,
                            profile[i + 2] + step2);
                }
            }
        }
    }
}
