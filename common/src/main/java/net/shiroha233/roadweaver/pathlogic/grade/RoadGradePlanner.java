/* 文件职责：把持久化剖面约束到真实地形，并且只把「完整、持续的山体开挖」保留为隧道候选。
 *
 * ⚠ 这里是旧版 15 道剖面 pass 的家（夹坡度、平滑、隧道剖面、去尖峰……）。
 * S5 台账标记 REPLACE_BY_DP：这 15 道 pass 会被「一次全局 DP」取代，
 * 因为它们没有共同的目标函数，只能靠互相打补丁收敛。
  *
 * ⚠ 待删（批1）：重制台账「纵断面与坡度」域。已被 pathlogic/profile/ProfileDpSolver + ModeDpSolver 取代。
 *   删除条件（三条全满足才许删，见 REFACTOR.md 的删除判定）：
 *     ① pathlogic/profile 的 DP 内核有独立测试且全绿；
 *     ② 本类零外部引用（全模块扫描，含 fabric/api 与 JSON）；
 *     ③ 删完回归仍全绿。
 *   现状（2026-11-14）：DP 已是默认，本类只剩「显式
 *   -Droadweaver.profileSolver=legacy」与「玩家关掉坡度限制」两条入口。
 */

package net.shiroha233.roadweaver.pathlogic.grade;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.constants.GradeCap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import static net.shiroha233.roadweaver.pathlogic.grade.GradeOptimizer.*;
import static net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer.*;

/**
 * Constrains the persisted road profile to real terrain while preserving only
 * complete, sustained mountain cuts as tunnel candidates.
 */
public final class RoadGradePlanner {
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    // 2026-11-14（S7 第 3 级前置 A）：配置上限快照已抽成顶层类型
    // pathlogic/grade/SurfaceGradeLimits.java —— 因为它被 4 个文件引用，其中
    // TunnelFootprintSampler 与 UnknownEndpointHeightRegistry 都不在旧链路里，
    // 直接删本类会砸掉两个与旧算法无关的调用方。常量随快照搬走，这里保留同名别名：
    // 旧算法内部的引用不用改，批 1 删除时也不会影响外部。
    static final int MAX_SURFACE_FILL = SurfaceGradeLimits.MAX_SURFACE_FILL;
    static final int MAX_SURFACE_CUT = SurfaceGradeLimits.MAX_SURFACE_CUT;
    static final int TUNNEL_CORE_OVERBURDEN = SurfaceGradeLimits.TUNNEL_CORE_OVERBURDEN;
    static final int TUNNEL_MOUTH_OVERBURDEN = 2;   // 旧算法专用（隧道口覆土），不随快照搬走
    static final int TUNNEL_MIN_RUN = SurfaceGradeLimits.TUNNEL_MIN_RUN;
    /**
     * A surface cut this deep or deeper still builds as a short hillside grade
     * (RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH), so a mountain run
     * whose surface-following profile stays within it is NOT converted into a
     * tunnel chord.
     */
    static final int TUNNEL_SURFACE_CUT_BUDGET = 8;
    /** Search far enough to cover long mountain faces without scanning a whole route. */
    static final int TUNNEL_ANCHOR_SEARCH_STATIONS = 128;
    /** Permit a route endpoint inside a mountain to act as the tunnel mouth. */
    static final int ENDPOINT_TUNNEL_ANCHOR_WINDOW = 16;
    /**
     * A run longer than this stops using one flat two-anchor chord and splits
     * into terrain-anchored segments instead, so the bore climbs with the
     * massif instead of pinning roadY while overburden grows past 17.
     */
    static final int TUNNEL_CHORD_SEGMENT_STATIONS = 48;

    private RoadGradePlanner() {
    }


    public static int[] constrainProfile(int[] planned, int[] terrain, boolean[] bridge) {
        if (planned == null || terrain == null || planned.length != terrain.length) {
            return planned == null ? new int[0] : planned.clone();
        }
        return constrainProfile(planned, terrain, bridge, SurfaceGradeLimits.capture());
    }

    static int[] constrainProfile(int[] planned, int[] terrain, boolean[] bridge,
                                          SurfaceGradeLimits limits) {
        int[] result = planned.clone();
        boolean[] tunnel = findTunnelCorridors(result, terrain, bridge, limits.tunnelCoreOverburden());
        for (int i = 0; i < result.length; i++) {
            if (isBridge(bridge, i) || tunnel[i]) {
                continue;
            }
            boolean mountain = !isBridge(bridge, i)
                    && terrain[i] - result[i] >= limits.tunnelCoreOverburden();
            int minimum = terrain[i] - limits.cut(mountain);
            int maximum = terrain[i] + limits.fill(mountain);
            result[i] = Math.max(minimum, Math.min(maximum, result[i]));
        }
        return result;
    }

    /**
     * Returns a robust local terrain height. A narrow ravine or isolated
     * column cannot drag an otherwise level 20-30 block road section down.
     */
    public static int robustWindowHeight(int[] terrain, int index, int radius) {
        if (terrain == null || terrain.length == 0) return 0;
        int safeIndex = Math.max(0, Math.min(terrain.length - 1, index));
        int safeRadius = Math.max(0, radius);
        int start = Math.max(0, safeIndex - safeRadius);
        int end = Math.min(terrain.length - 1, safeIndex + safeRadius);
        int[] window = Arrays.copyOfRange(terrain, start, end + 1);
        Arrays.sort(window);
        int middle = window.length / 2;
        return (window.length & 1) == 0
                ? (int) Math.round((window[middle - 1] + window[middle]) / 2.0D)
                : window[middle];
    }

    /**
     * Returns the low support level of one complete road station while
     * ignoring at most one isolated hole below the pavement footprint.
     */
    public static int robustFootprintSupport(int[] terrain) {
        if (terrain == null || terrain.length == 0) return 0;
        int[] sorted = terrain.clone();
        Arrays.sort(sorted);
        return sorted[ignoredFootprintColumns(sorted.length)];
    }

    static int ignoredFootprintColumns(int width) {
        return width >= 4 ? 1 : 0;
    }

    /**
     * Lowers ordinary road stations that the complete pavement width cannot
     * support. Crossings and already selected tunnel corridors are preserved.
     * The slope pass only lowers neighboring stations, so it cannot recreate
     * an unsupported sky road after the footprint clamp.
     */
    public static int[] constrainProfileToFootprintSupport(int[] planned,
                                                            int[] footprintSupport,
                                                            boolean[] protectedMask,
                                                            int maxStepPerTwoSegments) {
        if (planned == null || footprintSupport == null || planned.length != footprintSupport.length) {
            return planned == null ? new int[0] : planned.clone();
        }
        return constrainProfileToFootprintSupport(
                planned, footprintSupport, protectedMask, maxStepPerTwoSegments,
                SurfaceGradeLimits.capture());
    }

    static int[] constrainProfileToFootprintSupport(int[] planned,
                                                            int[] footprintSupport,
                                                            boolean[] protectedMask,
                                                            int maxStepPerTwoSegments,
                                                            SurfaceGradeLimits limits) {
        int[] result = planned.clone();
        for (int i = 0; i < result.length; i++) {
            if (!isBridge(protectedMask, i)) {
                result[i] = Math.min(result[i], footprintSupport[i] + limits.fill(false));
            }
        }
        return maxStepPerTwoSegments > 0
                ? enforceContinuousSlopePreferCut(result, protectedMask, maxStepPerTwoSegments)
                : result;
    }

    /**
     * Re-evaluates tunnels after footprint and slope constraints until the
     * persisted height profile stops changing. This prevents a late
     * full-width clamp from leaving a new, unclassified deep cut.
     */
    public static int[] stabilizeSurfaceProfile(int[] planned,
                                                int[] centerTerrain,
                                                int[] footprintSupport,
                                                boolean[] crossing,
                                                boolean slopeEnabled,
                                                int maxStepPerTwoSegments) {
        return stabilizeSurfaceProfile(planned, centerTerrain, footprintSupport, crossing,
                slopeEnabled, maxStepPerTwoSegments, SurfaceGradeLimits.capture());
    }

    /**
     * Route-level AUTO tendency support: the caller passes the effective mode
     * (global mode, or the route tendency resolved by
     * {@code RouteProfileTendency.effectiveMode}).
     */
    public static int[] stabilizeSurfaceProfile(int[] planned,
                                                int[] centerTerrain,
                                                int[] footprintSupport,
                                                boolean[] crossing,
                                                boolean slopeEnabled,
                                                int maxStepPerTwoSegments,
                                                PlanningConfig.TerrainProfileMode effectiveMode) {
        return stabilizeSurfaceProfile(planned, centerTerrain, footprintSupport, crossing,
                slopeEnabled, maxStepPerTwoSegments, SurfaceGradeLimits.capture(effectiveMode));
    }

    static int[] stabilizeSurfaceProfile(int[] planned,
                                                 int[] centerTerrain,
                                                 int[] footprintSupport,
                                                 boolean[] crossing,
                                                 boolean slopeEnabled,
                                                 int maxStepPerTwoSegments,
                                                 SurfaceGradeLimits limits) {
        if (planned == null || centerTerrain == null || footprintSupport == null
                || planned.length != centerTerrain.length || planned.length != footprintSupport.length) {
            return planned == null ? new int[0] : planned.clone();
        }
        int[] result = constrainProfile(planned, centerTerrain, crossing, limits);
        for (int pass = 0; pass < 6; pass++) {
            int[] before = result.clone();
            boolean[] tunnel = findTunnelCorridors(result, centerTerrain, crossing,
                    limits.tunnelCoreOverburden());
            boolean[] protectedMask = mergeMasks(crossing, tunnel, result.length);
            result = constrainProfile(result, centerTerrain, protectedMask, limits);
            result = constrainProfileToFootprintSupport(result, footprintSupport, protectedMask,
                    slopeEnabled ? maxStepPerTwoSegments : 0, limits);
            if (Arrays.equals(before, result)) break;
        }
        result = smoothProfileWithinBudget(result, centerTerrain, crossing, limits);
        result = applyConfiguredGradeCap(result, crossing, slopeEnabled);
        return result;
    }

    /**
     * 0.9.061：把剖面压进当前坡度档位，并打一行汇总（每次生成一行）。
     *
     * <p>为什么放在这里：{@link #stabilizeSurfaceProfile} 是<b>真正落到 RoadData.targetY 的那个剖面</b>
     * 的最后一道工序（唯一生产调用点 {@code Road.java:828}），后面只剩结构端点锁定与隧道判定。
     * 放在它之前会被 {@link #smoothProfileWithinBudget} 的「挖填预算」重新抬回地形附近，
     * 放在它之后才是一次真正的硬限坡。</p>
     *
     * <p>隧道/桥面不参与（它们各有自己的弦），所以保护掩码与上一轮
     * {@code constrainProfileToFootprintSupport} 用的同一份（crossing ∪ tunnel）。</p>
     *
     * <p>{@code cappedStations} 的定义：<b>被这一道窗口约束实际压低的站数</b>
     * （比较压前/压后的逐站高度）。这是判断三档怎么选的唯一依据 ——
     * 如果它长期是 0，说明真正绑住坡度的是别的东西，不是这个剖面档位。</p>
     */
    static int[] applyConfiguredGradeCap(int[] profile, boolean[] crossing, boolean slopeEnabled) {
        int total = profile == null ? 0 : profile.length;
        GradeCap.Mode mode = GradeCap.current();
        int cappedStations = 0;
        int[] result = profile;
        if (total > 0 && slopeEnabled) {
            // 保护掩码只用 crossing（桥面/路口）：它们的纵断面由桥的专业逻辑决定。
            // **刻意不把隧道掩码算进来** —— 深挖段本来就会被 findTunnelCorridors 认成隧道，
            // 若一并保护，这道坡度上限在最需要它的山体深挖段上会整段跳过
            // （实测 cappedStations 恒为 0、三档剖面逐位相同）。
            int[] before = profile.clone();
            result = enforceGradeCap(profile, crossing, null, null, mode);
            for (int i = 0; i < total; i++) {
                if (result[i] < before[i]) cappedStations++;
            }
        }
        LOGGER.info("【道路纵坡规划诊断】 {}", GradeCap.summary(mode, cappedStations, total));
        return result;
    }

    /**
     * 0.9.061：把剖面压进「每 N 站抬 M 格」的档位（只降不升）。
     *
     * <p>与 {@link #enforceContinuousSlopePreferCut} 的关系：后者是旧的「每两站 step2 格」硬约束
     * （默认 step2=1 ⇒ 50%），本方法是在它之上再加一道更宽的窗口约束。两者都是 prefer-cut，
     * 取交集即「更严的那个生效」。之所以不把旧约束删掉：相邻站 ≤1 格是<b>可走性</b>
     * （玩家能走上 1 格台阶），不是坡度，删了会造出台阶；
     * 而 {@code RoadDataValidator.MAX_HEIGHT_STEP=1} 与
     * {@code RoadSnapService}/{@code RouteRoadDataPatcher} 的 1 格/站修复也依赖它。</p>
     *
     * <p><b>为什么是「锚定锥」而不是「逐窗口取 min」。</b>剖面高度是整数，{@code maxRise(w)} 在
     * {@code w < N} 时恒为 0；如果对每一个 {@code w = 1..N} 都做一次
     * {@code y[i] <= y[i-w] + maxRise(w)}，等于禁止任何 1 格台阶 —— 整段会被压成一条平路，
     * 连爬坡都没了。正确的整数语义是「<b>从某个低点出发，每走 N 站才允许抬 1 格</b>」：
     * 锚点 {@code anchor} 在每次出现更低的站时下移，站 i 的上限是
     * {@code y[anchor] + floor((i-anchor)/N)}，再叠加「单站最多 1 格」的可走性上限。
     * 这正是 12.5% 在 1 格/站网格上的形态：每 8 站一级台阶，而不是被压平。</p>
     *
     * <p>反向那一遍处理<b>下坡</b>：从末尾的锚点往前，限制「下坡不许比档位更陡」
     * （旧 {@code lowerRunToSlope} 也是靠反向那一遍压低高侧实现的）。两遍都只做 {@code min}，
     * 所以仍然满足 prefer-cut 契约：只会把路压进山里，绝不会造出天桥。
     * 收敛性：每轮只会把某站压得更低，一个 N 站窗的影响每轮至少前推一站，
     * {@code 2N+4} 轮足够（N ≤ 10），且有 {@code changed} 早退。</p>
     *
     * @param stationSpacing 相邻站真实水平距离（{@code null} = 按站号，1.0 格/站）
     */
    public static int[] enforceGradeCap(int[] source,
                                        boolean[] protectedMask,
                                        boolean[] pinnedMask,
                                        double[] stationSpacing,
                                        GradeCap.Mode mode) {
        if (source == null || source.length == 0) return new int[0];
        int[] result = source.clone();
        int window = GradeCap.riseStations(mode);
        if (window <= 1 || GradeCap.riseBlocks(mode) <= 0 || result.length < 3) return result;

        int index = 0;
        while (index < result.length) {
            while (index < result.length && isBridge(protectedMask, index)) index++;
            int start = index;
            while (index < result.length && !isBridge(protectedMask, index)) index++;
            int end = index - 1;
            if (start < end) {
                lowerRunToGradeCap(result, start, end, window, mode, pinnedMask, stationSpacing);
            }
        }
        return result;
    }

    static void lowerRunToGradeCap(int[] profile, int start, int end, int window,
                                           GradeCap.Mode mode, boolean[] pinnedMask, double[] stationSpacing) {
        int passes = Math.min(2 * window + 4, 32);
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            // 正向上坡：锚点 = 到当前为止最低的那一站。每走 N 站才允许抬 1 格。
            int anchor = start;
            for (int i = start + 1; i <= end; i++) {
                if (!isPinned(pinnedMask, i)) {
                    int allowedUp = GradeCap.maxRise(windowRun(stationSpacing, anchor, i), mode);
                    int limit = Math.min(profile[anchor] + allowedUp, profile[i - 1] + 1);
                    if (profile[i] > limit) {
                        profile[i] = limit;
                        changed = true;
                    }
                }
                if (profile[i] < profile[anchor]) anchor = i;
            }
            // 反向下坡：锚点 = 从末尾往前看到的最低站；下坡同样不许比档位更陡。
            int backAnchor = end;
            for (int i = end - 1; i >= start; i--) {
                if (!isPinned(pinnedMask, i)) {
                    int allowedDown = GradeCap.maxRise(windowRun(stationSpacing, i, backAnchor), mode);
                    int limit = Math.min(profile[backAnchor] + allowedDown, profile[i + 1] + 1);
                    if (profile[i] > limit) {
                        profile[i] = limit;
                        changed = true;
                    }
                }
                if (profile[i] < profile[backAnchor]) backAnchor = i;
            }
            if (!changed) break;
        }
    }

    /** {@return 索引 from→to 之间实际走过的水平距离（{@code spacing == null} 时按 1.0 格/站）} */
    static double windowRun(double[] spacing, int from, int to) {
        if (spacing == null) return Math.max(0, to - from);
        double sum = 0.0D;
        for (int i = from + 1; i <= to && i < spacing.length; i++) {
            double step = spacing[i];
            sum += (step > 0.0D && !Double.isNaN(step)) ? step : 1.0D;
        }
        return sum;
    }

    /** 纵断面平滑轮数。 */
    static final int PROFILE_SMOOTH_PASSES = 4;

    /**
     * 在「挖填预算」内把纵断面拉成缓坡（0.9.016）。
     *
     * <p>动机：{@code constrainProfile} 只做钳位、不做平滑 —— 路被限制在
     * {@code [地形-挖, 地形+填]} 的窄带里，就只能逐级跟着地形台阶上下，视觉上
     * 「突然高低」。这里在**完全相同的带宽内**做多轮 1-2-1 加权：每轮平滑后立刻
     * 重新钳回带宽，因此**物理约束逐点保持**（挖填量永不增大），只会把锯齿状的
     * 剖面摊成缓坡。</p>
     *
     * <p>桥 / 隧道 / 交叉一律不动：它们的纵断面必须由各自的专业逻辑决定
     * （桥面平直、隧道口锚点）。端点也不动。</p>
     *
     * <p>等价性边界：输出逐点仍在 {@code [terrain - cut, terrain + fill]} 内且端点不变，
     * 因此不改变起终点高度、不增加任何土方，也不改变「哪条路」这个选线决策。</p>
     */
    static int[] smoothProfileWithinBudget(int[] profile, int[] terrain, boolean[] crossing,
                                                   SurfaceGradeLimits limits) {
        int n = profile == null ? 0 : profile.length;
        if (n < 3 || terrain == null || terrain.length != n) return profile;
        boolean[] tunnel = findTunnelCorridors(profile, terrain, crossing, limits.tunnelCoreOverburden());
        boolean[] frozen = mergeMasks(crossing, tunnel, n);
        int[] minimum = new int[n];
        int[] maximum = new int[n];
        for (int i = 0; i < n; i++) {
            boolean mountain = !isBridge(crossing, i)
                    && terrain[i] - profile[i] >= limits.tunnelCoreOverburden();
            minimum[i] = terrain[i] - limits.cut(mountain);
            maximum[i] = terrain[i] + limits.fill(mountain);
        }
        int[] result = profile.clone();
        for (int pass = 0; pass < PROFILE_SMOOTH_PASSES; pass++) {
            int[] next = result.clone();
            boolean moved = false;
            for (int i = 1; i < n - 1; i++) {
                if (frozen[i]) continue;
                // 0.9.043（D-27）：整数除法向零截断，负坐标剖面会比正坐标偏一格。
                // PathPostProcessor 的同款 1-2-1 平滑早已换成 Math.round，这里漏改了很久。
                int smoothed = (int) Math.round((result[i - 1] + 2 * result[i] + result[i + 1]) / 4.0D);
                if (smoothed < minimum[i]) smoothed = minimum[i];
                else if (smoothed > maximum[i]) smoothed = maximum[i];
                if (smoothed != result[i]) moved = true;
                next[i] = smoothed;
            }
            result = next;
            if (!moved) break;
        }
        return result;
    }

    /**
     * Selects tunnel elevations during planning for a sustained mountain hump.
     * This is deliberately based on the sampled terrain profile, not on a
     * construction failure. A run is eligible only when both sides provide a
     * lower anchor and the terrain remains substantially above the anchor
     * chord for at least three stations. Bridges/crossings are excluded.
     */
    public static int[] applyProactiveTunnelProfile(int[] profile,
                                                    int[] terrain,
                                                    boolean[] crossing,
                                                    boolean slopeEnabled,
                                                    int maxStepPerTwoSegments) {
        return applyProactiveTunnelProfile(profile, terrain, crossing,
                slopeEnabled, maxStepPerTwoSegments, null);
    }

    /**
     * 0.8.369 (T2): {@code pinnedMask} names hard structure boundary locks.
     * A locked station keeps its surface height instead of being pressed down
     * onto the tunnel chord or the {@code terrain - overburden} cap. Because the
     * neighbouring stations still carry the constraint, the result is a legal
     * one-block ramp from the locked entrance into the bore rather than a cliff.
     */
    public static int[] applyProactiveTunnelProfile(int[] profile,
                                                    int[] terrain,
                                                    boolean[] crossing,
                                                    boolean slopeEnabled,
                                                    int maxStepPerTwoSegments,
                                                    boolean[] pinnedMask) {
        if (profile == null || terrain == null || profile.length != terrain.length
                || profile.length < 5) {
            return profile == null ? new int[0] : profile.clone();
        }
        SurfaceGradeLimits limits = SurfaceGradeLimits.capture();
        int overburden = limits.tunnelCoreOverburden();
        int[] result = profile.clone();
        int size = result.length;
        int index = 1;
        while (index < size - 1) {
            if (isBridge(crossing, index)) {
                index++;
                continue;
            }
            int leftAnchor = findLowerAnchorLeft(terrain, crossing, index, overburden);
            int rightAnchor = findLowerAnchorRight(terrain, crossing, index, overburden);
            // The old classifier required a lower anchor on both sides. That
            // fails when the connection itself starts or ends inside a
            // mountain: the endpoint is already the open-side mouth and must
            // be allowed to anchor the tunnel chord. Restrict this fallback
            // to the first/last stations so interior terrain is not silently
            // reclassified as a tunnel.
            if (leftAnchor < 0 && index <= ENDPOINT_TUNNEL_ANCHOR_WINDOW
                    && !isBridge(crossing, 0)) {
                leftAnchor = 0;
            }
            if (rightAnchor < 0 && index >= size - 1 - ENDPOINT_TUNNEL_ANCHOR_WINDOW
                    && !isBridge(crossing, size - 1)) {
                rightAnchor = size - 1;
            }
            if (leftAnchor < 0 || rightAnchor <= leftAnchor + 2) {
                index++;
                continue;
            }
            int runStart = index;
            int runEnd = index;
            while (runEnd + 1 < rightAnchor && !isBridge(crossing, runEnd + 1)
                    && aboveAnchorChord(terrain, leftAnchor, rightAnchor, runEnd + 1, overburden)) {
                runEnd++;
            }
            if (runEnd - runStart + 1 < limits.tunnelMinRun()) {
                index++;
                continue;
            }
            // Ordinary surface-cut feasibility first: when the slope-limited
            // terrain-following profile stays inside the short-grade cut
            // budget, the run keeps its surface profile (or a shallow cut)
            // and is NOT pressed into a tunnel. Only a sustained hump whose
            // surface cut would exceed the budget becomes a tunnel chord
            // (tasks 5/14: cuttable slopes must not become deep tunnels).
            if (surfaceCutFeasible(terrain, crossing, leftAnchor, rightAnchor,
                    maxStepPerTwoSegments, limits.tunnelCoreOverburden())) {
                index = runEnd + 1;
                continue;
            }
            int anchorLeftY = terrain[leftAnchor];
            int anchorRightY = terrain[rightAnchor];
            int runLength = runEnd - runStart + 1;
            if (runLength > TUNNEL_CHORD_SEGMENT_STATIONS) {
                // Task 15: a very long run under one flat two-anchor chord
                // kept roadY pinned (terrain climbed 78->90 while roadY stayed
                // 72, overburden 17+). Anchor the chord at fixed station
                // intervals: each anchor climbs by at most one block per
                // station and is capped at (local terrain - overburden), and
                // the last anchor rejoins the right anchor. The bore follows
                // the massif in gentle steps; min() keeps valleys unpinned.
                int segmentCount = (runLength + TUNNEL_CHORD_SEGMENT_STATIONS - 1)
                        / TUNNEL_CHORD_SEGMENT_STATIONS;
                int[] anchorY = new int[segmentCount + 1];
                anchorY[0] = anchorLeftY;
                for (int k = 1; k <= segmentCount; k++) {
                    int boundary = Math.min(runEnd, runStart + k * TUNNEL_CHORD_SEGMENT_STATIONS);
                    int cap = k == segmentCount
                            ? anchorRightY
                            : terrain[boundary] - limits.tunnelCoreOverburden();
                    anchorY[k] = Math.min(cap, anchorY[k - 1] + TUNNEL_CHORD_SEGMENT_STATIONS);
                }
                for (int j = runStart; j <= runEnd; j++) {
                    int k = (j - runStart) / TUNNEL_CHORD_SEGMENT_STATIONS;
                    int segStart = runStart + k * TUNNEL_CHORD_SEGMENT_STATIONS;
                    int segEnd = Math.min(runEnd, segStart + TUNNEL_CHORD_SEGMENT_STATIONS);
                    double ratio = (j - segStart) / (double) Math.max(1, segEnd - segStart);
                    int tunnelY = (int) Math.round(anchorY[k]
                            + (anchorY[k + 1] - anchorY[k]) * ratio);
                    if (isPinned(pinnedMask, j)) continue;
                    result[j] = Math.min(result[j],
                            Math.min(terrain[j] - limits.tunnelCoreOverburden(), tunnelY));
                }
            } else {
                for (int j = runStart; j <= runEnd; j++) {
                    if (isPinned(pinnedMask, j)) continue;
                    double ratio = (j - leftAnchor) / (double) (rightAnchor - leftAnchor);
                    int tunnelY = (int) Math.round(anchorLeftY
                            + (anchorRightY - anchorLeftY) * ratio);
                    result[j] = Math.min(result[j], tunnelY);
                }
            }
            index = runEnd + 1;
        }
        if (slopeEnabled) {
            result = enforceContinuousSlopePreferCut(result, crossing, maxStepPerTwoSegments);
        }
        return result;
    }

    /**
     * 精采样阶段的纵向坡度规划（"陡壁在规划期就规划好"）。
     *
     * <p>准确高度解析原先把整条路的 Y 直接换成逐列精确地表高度，于是粗采样
     * 阶段算好的坡度约束被丢掉：陡壁变成"路贴着 40° 坡爬"，只能靠
     * {@code AccurateGradeRepairPlanner} 局部修复兜底，修不动就反复触发。
     * 这里对精确高度重跑一次 prefer-cut 纵向坡度约束，把陡壁在规划期直接变成
     * 路堑或隧道：</p>
     * <ul>
     *   <li>坡度约束内可切方的段落 → 路堑（剖面低于地表，但不超过切方预算）；</li>
     *   <li>连续 ≥{@link #TUNNEL_MIN_RUN} 站需要下挖超过隧道判据的段落 → 直接采用
     *       坡度弦，后续按 overburden 认成 TUNNEL，不再走"先切方再修复"的弯路。</li>
     * </ul>
     *
     * @param exactHeights          逐站精确地表高度
     * @param protectedMask         桥/水面/已判定隧道的站点（不参与坡度约束），可为 null
     * @param maxStepPerTwoSegments 每两段允许的高差（0 表示不启用）
     * @param slopeLimitEnabled     是否启用坡度限制
     */
    /**
     * @param targetY     每站的期望高度：地表站是精确地表高度，已判定隧道的站是洞身弦高度。
     *                    求解器会贴着期望高度走，并在两者之间用合法坡度过渡（洞口坡道）。
     * @param splitMask   分段边界（水面/桥面），这些站被钉在 {@code targetY} 上且各自断开求解。
     */
    public static int[] planAccurateGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments,
                                                 boolean slopeLimitEnabled) {
        return planAccurateGradeProfile(targetY, splitMask, maxStepPerTwoSegments, slopeLimitEnabled, null);
    }

    public static int[] planAccurateGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments,
                                                 boolean slopeLimitEnabled,
                                                 boolean[] flatCostMask) {
        return planAccurateGradeProfile(targetY, splitMask, maxStepPerTwoSegments, slopeLimitEnabled,
                flatCostMask, null);
    }

    /**
     * @param stationSpacing 相邻站的真实水平距离（{@code null} = 按站号计，即旧行为）。
     *                       只有 {@code slopeByArcLength} 打开时调用方才会传非 null。
     */
    public static int[] planAccurateGradeProfile(int[] targetY,
                                                 boolean[] splitMask,
                                                 int maxStepPerTwoSegments,
                                                 boolean slopeLimitEnabled,
                                                 boolean[] flatCostMask,
                                                 double[] stationSpacing) {
        if (targetY == null || targetY.length < 3) {
            return targetY == null ? new int[0] : targetY.clone();
        }
        if (!slopeLimitEnabled) {
            return targetY.clone();
        }
        int[] result = solveOptimalGradeProfile(targetY, splitMask, maxStepPerTwoSegments, flatCostMask,
                stationSpacing);
        // 0.9.061：坡度上限档位（每 N 站抬 1 格）。分层 DAG 的软坡度罚只看相邻站
        // （adjacentStep=(step2+1)/2），表达不了「8 站抬 1 格」这种窗口约束，
        // 所以这里再用一次硬窗口把它压进档位。只降不升，与 prefer-cut 的契约一致。
        result = enforceGradeCap(result, splitMask, null, stationSpacing, GradeCap.current());
        int changed = 0;
        int cutStations = 0;
        int fillStations = 0;
        for (int i = 0; i < result.length; i++) {
            if (result[i] == targetY[i]) continue;
            changed++;
            if (result[i] < targetY[i]) {
                cutStations++;
            } else {
                fillStations++;
            }
        }
        if (changed > 0) {
            LOGGER.info("【精确纵断面挖填与修改站数】 " + "RoadWeaver accurate grade profile: stations={} changed={} cutStations={} fillStations={} step2={}",
                    result.length, changed, cutStations, fillStations, maxStepPerTwoSegments);
        }
        return result;
    }

    /**
     * 便捷入口：按当前配置（坡度限制开关 + 每两段允许高差）求解最优坡度剖面。
     * 供修复阶段复用，避免各处重复读取配置。
     */
    public static int[] planConfiguredGradeProfile(int[] targetY, boolean[] splitMask) {
        boolean enabled = true;
        int step2 = 2;
        try {
            enabled = ConfigService.get().slopeLimitEnabled();
            step2 = ConfigService.get().maxSlopeStepPerTwoSegments();
        } catch (Throwable ignored) {
        }
        return planAccurateGradeProfile(targetY, splitMask, step2, enabled);
    }

    /**
     * 精采样坡度剖面的最大下挖/上填。下挖必须不超过施工上限
     * （{@code RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH} = 8），
     * 否则规划出 9~16 格的切方会被施工拒绝成缺口（真机 0.8.303/304 出现过
     * {@code rejected terrain construction gap depth=9..13} 并导致 1 条路失败）。
     * 坡度在 8 格切方内满足不了时，剩余偏差交回局部修复/隧道判定，而不是硬挖。
     */
    static final int ACCURATE_MAX_CUT = 8;
    static final int ACCURATE_MAX_FILL = 6;
    /** 坡度超限的软惩罚（每格）。远大于切方/填方单价，因此除极端地形外坡度约束实际是硬约束。 */
    static final int SLOPE_EXCESS_WEIGHT = 60;
    /** 端点偏离地表的软惩罚（每格）。可行时端点锚定地表，不可行时取最接近的剖面。 */
    static final int ENDPOINT_DEVIATION_WEIGHT = 200;

    /**
     * 纵向坡度的最优剖面：分层 DAG 最短路（经典 Viterbi / 有界坡度剖面）。
     *
     * <p>状态是"当前站相对期望高度的偏移"，代价是下挖/上填的加权深度加坡度超限的软惩罚，
     * 复杂度 {@code O(n · H²)}，H = {@link #ACCURATE_MAX_CUT} + {@link #ACCURATE_MAX_FILL} + 1，
     * 比在每个违反窗口重跑 2D A* 便宜几个数量级，而且**同时处理上坡与下坡**——
     * 旧的双向/单向贪心只会压低高侧，下坡陡壁永远修不好。</p>
     *
     * <p>坡度用软惩罚而不是硬约束，是为了在"坡度 + 切填预算"互相矛盾时仍能给出全局最优的
     * 折中剖面，而不是整段放弃、退回贴地表；残留的超限由后续 span/修复兜底。</p>
     *
     * <p>期望高度 {@code targetY} 不只是地表：已判定隧道的站传洞身弦高度，于是求解器会在
     * 洞口把地表段与洞身弦用合法坡度连起来（洞口坡道），不再留下 1 站跳 11~12 格的台阶。</p>
     *
     * <p>{@code splitMask}（水面/桥面）是硬边界：这些站钉在 {@code targetY} 上并各自断开求解。</p>
     */
    /**
     * Applies the final longitudinal constraint after every terrain clamp.
     * Protected crossings are solved separately by BridgeDeckProfile.
     */
    /**
     * Removes isolated one-block spikes inside tunnel runs: a station that
     * stands exactly one block above both of its neighbours (which are nearly
     * level with each other) is seated on them. Sustained slopes and ordinary
     * one-block grade steps are untouched - only the "suddenly one block
     * higher" anomaly inside a continuous tunnel run is flattened, and the
     * fix applies only when the station and both neighbours belong to the
     * same tunnel run.
     */
    public static int[] removeSingleStationSpikes(int[] profile, boolean[] tunnelMask) {
        if (profile == null || profile.length < 3 || tunnelMask == null
                || tunnelMask.length != profile.length) {
            return profile;
        }
        int[] result = profile.clone();
        for (int i = 1; i < result.length - 1; i++) {
            if (!tunnelMask[i] || !tunnelMask[i - 1] || !tunnelMask[i + 1]) continue;
            int previous = result[i - 1];
            int current = result[i];
            int next = result[i + 1];
            if (current > previous && current > next
                    && current - Math.max(previous, next) == 1
                    && Math.abs(previous - next) <= 2) {
                result[i] = Math.max(previous, next);
            }
        }
        return result;
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static boolean[] findTunnelCorridors(int[] planned, int[] terrain, boolean[] bridge,
                                         int tunnelCoreOverburden) {
        int length = planned == null ? 0 : planned.length;
        boolean[] tunnel = new boolean[length];
        if (terrain == null || terrain.length != length) {
            return tunnel;
        }

        int i = 0;
        while (i < length) {
            if (!isTunnelCore(planned, terrain, bridge, i, tunnelCoreOverburden)) {
                i++;
                continue;
            }
            int start = i;
            while (i + 1 < length && isTunnelCore(planned, terrain, bridge, i + 1, tunnelCoreOverburden)) {
                i++;
            }
            int end = i;
            if (end - start + 1 >= TUNNEL_MIN_RUN) {
                int mouthStart = start;
                while (mouthStart > 0 && !isBridge(bridge, mouthStart - 1)
                        && overburden(planned, terrain, mouthStart - 1) > TUNNEL_MOUTH_OVERBURDEN) {
                    mouthStart--;
                }
                int mouthEnd = end;
                while (mouthEnd + 1 < length && !isBridge(bridge, mouthEnd + 1)
                        && overburden(planned, terrain, mouthEnd + 1) > TUNNEL_MOUTH_OVERBURDEN) {
                    mouthEnd++;
                }
                for (int j = mouthStart; j <= mouthEnd; j++) {
                    tunnel[j] = true;
                }
            }
            i++;
        }
        return tunnel;
    }

    static boolean isTunnelCore(int[] planned, int[] terrain, boolean[] bridge, int index,
                                        int tunnelCoreOverburden) {
        return !isBridge(bridge, index)
                && overburden(planned, terrain, index) >= tunnelCoreOverburden;
    }

    static int overburden(int[] planned, int[] terrain, int index) {
        return terrain[index] - planned[index];
    }

    static boolean isBridge(boolean[] bridge, int index) {
        return bridge != null && index >= 0 && index < bridge.length && bridge[index];
    }

    static boolean isPinned(boolean[] pinnedMask, int index) {
        return pinnedMask != null && index >= 0 && index < pinnedMask.length && pinnedMask[index];
    }
}
