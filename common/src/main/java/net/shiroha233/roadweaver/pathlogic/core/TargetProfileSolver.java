/* 文件职责：组装高程硬约束并编排 DP 与约束回落，世界采样经最小端口注入。 */
package net.shiroha233.roadweaver.pathlogic.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileSolverMode;
import java.util.List;
import java.util.Optional;
import static net.shiroha233.roadweaver.pathlogic.core.Road.LOGGER;

/** 约束先于求解构造，确保异常路径也带着端点钉高、覆土与桥面下界。 */
final class TargetProfileSolver {
    private TargetProfileSolver() {}

    /** 只返回本次桥面下界；不在此层跨世界缓存水位。 */
    @FunctionalInterface
    interface DeckFloorSampler {
        int sample(int station, int clearance);
    }

    /** 测试可注入不可行或异常，生产仍直接调用同一个 DP 内核。 */
    @FunctionalInterface
    interface Solver {
        Optional<int[]> solve(int[] terrain, boolean[] crossing, int cut, int fill,
                              int step, int perTwo, ProfileDpSource.Constraints constraints);
    }

    static Optional<int[]> applyProfileSolver(
            ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing,
            boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary, RoadGenerationConfig cfg,
            SurfaceGradeLimits gradeLimits, StructureConnection connection) {
        return solve(level, centers, smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
                startBoundary, endBoundary, cfg, gradeLimits, connection,
                (station, clearance) -> RoadConstructionSafety.bridgeDeckSurfaceY(
                        level, centers, station, clearance, Integer.MIN_VALUE), ProfileDpSource::solve);
    }

    /**
     * 已钉住的站数（{@code pin} 用 {@code -1} 表示「不钉」）。
     *
     * <p>注意它与 {@link #countDeckFloors(int[])} 的哨兵值<b>不同</b>：
     * 两个数组语义不一样，混用一个统计函数会把日志写错 —— 而说谎的日志
     * 比没有日志更糟（本项目已因「静默降级 + 日志不实」吃过多次亏）。</p>
     */
    private static int countPinned(int[] pin) {
        if (pin == null) return 0;
        int c = 0;
        for (int v : pin) {
            if (v >= 0) c++;
        }
        return c;
    }

    /** 已给定桥面下界的站数（{@code deckFloor} 用 {@link Integer#MIN_VALUE} 表示「无」）。 */
    private static int countDeckFloors(int[] deckFloor) {
        if (deckFloor == null) return 0;
        int c = 0;
        for (int v : deckFloor) {
            if (v != Integer.MIN_VALUE) c++;
        }
        return c;
    }

    /** DP 不适用时返回空；不可行或异常时用同一组硬约束产生新版回落剖面。 */
    static java.util.Optional<int[]> solve(
            ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing,
            boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary,
            RoadGenerationConfig cfg,
            SurfaceGradeLimits gradeLimits,
            StructureConnection connection, DeckFloorSampler deckSampler, Solver solver) {
        if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) {
            return java.util.Optional.empty();
        }
        // 用户关掉坡度限制时，旧流水线也不夹坡。DP 若仍强制 |Δ|≤N 就是两套语义打架。
        if (!cfg.slopeLimitEnabled()) {
            return java.util.Optional.empty();
        }
        int n = smoothed.length;
        // ★ 2026-11-15（独立复核修复）：三条硬约束在 try **之前**构造。
        //
        // 原来 pin / minCover / deckFloor 全在 try 内，DP 一旦抛异常，catch 只能传
        // null ⇒ 回落解丢掉「结构端点钉高」和「桥面水位下界」这两条硬契约，
        // 而它们正是本方法最重要的保证。现在移出去，两条回落路径参数一致。
        int[] pin = new int[n];
        java.util.Arrays.fill(pin, -1);
        if (pinnedMask != null) {
            if (pinnedMask.length > 0 && pinnedMask[0]) pin[0] = startBoundary;
            if (pinnedMask.length > n - 1 && pinnedMask[n - 1]) pin[n - 1] = endBoundary;
        }
        int minCover = Math.max(1, gradeLimits.tunnelCoreOverburden());
        // mustBeCovered 用隧道掩码（覆土是隧道的要求）；crossing 用桥掩码（桥面钉直线）。
        // 这两个是不同的事实，曾经被同一个 tunnelMask 顶替 —— 后果是隧道被当成桥钉直、
        // 而真正的桥面甲板高度被 DP 整条覆盖掉。
        // ★ 桥面水位下界 —— 这是「重构」而不是「补丁」的关键点。
        //
        // 旧版：computeTargetY 算完剖面之后，由 applyFixedRange 把桥面整段抬到
        //       「最高水位 + bridgeDeckClearance」。那是事后修正，抬完之后
        //       坡度约束、1-2-1 平滑、相邻桥连续性全部失效，只能再贴一轮补丁。
        //
        // 新版：在求解**之前**就把这个下界算出来喂给 DP。DP 在满足坡度上限的
        //       前提下自己找最优过渡 —— 桥面该平的段落自然平（同一水位下界相同），
        //       过渡段由 DP 统一安排，不需要事后贴坡。
        //
        // 下界来源是旧版的权威算法 RoadConstructionSafety.bridgeDeckSurfaceY：
        // 逐站查最高水位，并向两侧 WATERCOURSE_HALF_WIDTH(=3) 取样取最高，
        // 再加 clearance。水位不可用时它返回 fallback，这里传 Integer.MIN_VALUE
        // 表示「无下界」。
        int[] deckFloor = new int[n];
        java.util.Arrays.fill(deckFloor, Integer.MIN_VALUE);
        // deckFloor 要读世界水位，这一步本身可能抛；单独兜一层 ——
        // 采样失败时退化成「无下界」，而不是让整条 DP 路径失败。
        try {
            if (isCrossing != null && centers != null && centers.size() == n) {
                int clearance = Math.max(1, cfg.bridgeDeckClearance());
                for (int i = 0; i < n; i++) {
                    if (!isCrossing[i]) continue;
                    deckFloor[i] = deckSampler.sample(i, clearance);
                }
            }
        } catch (Throwable deckFailure) {
            java.util.Arrays.fill(deckFloor, Integer.MIN_VALUE);
            LOGGER.warn("RoadWeaver profile deckFloor sampling failed, using no lower bound: {}",
                    deckFailure.toString());
        }
        try {
            var constraints = new ProfileDpSource.Constraints(pin, tunnelMask, minCover, deckFloor);
            int perTwo = Math.max(1, cfg.maxSlopeStepPerTwoSegments());
            // 一阶上限 = 把「每两站 N 格」折算成每站（向上取整）。它只是兜底，
            // 真正绑定的是二阶约束 |d_i + d_{i−1}| ≤ perTwo。
            int maxStep = Math.max(1, (perTwo + 1) / 2);
            // 窗口取施工端的实时上限 —— 与旧流水线同一套，DP 才不会挖出施工端拒收的深度。
            int maxCut = Math.max(0, gradeLimits.plainCut());
            int maxFill = Math.max(0, gradeLimits.plainFill());

            var solved = solver.solve(terrainY, isCrossing,
                    maxCut, maxFill, maxStep, perTwo, constraints);
            if (solved.isEmpty()) {
                // 2026-11-14（缺陷 C 收尾）：DP 不可行 ⇒ 用无状态回落解，
                // **不再跑旧 15 道 pass**。
                //
                // 这是「旧 pass 退出生产路径」的关键一步：改动前 DP 不可行会
                // 返回 empty，调用方就去跑旧 pass；现在它返回 ProfileFallback 的解，
                // 调用方直接采用。于是 empty 只剩两种「DP 不适用」的情形：
                //   · profileSolver 不是 DP（显式 -Droadweaver.profileSolver=legacy）
                //   · 玩家关掉了坡度限制（旧 pass 也不夹坡，DP 的硬约束会打架）
                // 那两种情形本来就该走旧链路，语义没问题。
                //
                // 回落解取的是同一套硬约束（窗口 / 一阶二阶坡度 / 站钉 / 桥面下界 /
                // 隧道覆土），所以结果一定合规 —— 它只是不做全局最优，不像旧 pass
                // 那样有一堆互相打架的事后修正。
                LOGGER.warn("RoadWeaver profile solver=dp infeasible, using constraint fallback"
                                + " stations={} cut={} fill={} perTwo={} pins={} decks={}"
                                + " connection={}->{}",
                        n, maxCut, maxFill, perTwo, countPinned(pin), countDeckFloors(deckFloor),
                        connection == null ? "?" : connection.from(),
                        connection == null ? "?" : connection.to());
                return java.util.Optional.of(ProfileFallback.terrainFollowing(
                        terrainY, maxCut, maxFill, maxStep, perTwo,
                        pin, tunnelMask, minCover, deckFloor));
            }
            LOGGER.info("RoadWeaver profile solver=dp stations={} perTwo={} maxStep={} cut={} fill={} minCover={} connection={}->{}",
                    n, perTwo, maxStep, maxCut, maxFill, minCover,
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to());
            return java.util.Optional.of(solved.get());
        } catch (Throwable t) {
            // 实验性求解器绝不能让一条路算不出来。
            // 2026-11-15（独立复核修复）：回落参数与「不可行」分支**完全一致** ——
            // pin / tunnelMask / minCover / deckFloor 都在 try 外构造好了，
            // 不再像原来那样在异常路径把 pin 与 deckFloor 传 null（那会丢掉
            // 结构端点钉高与桥面水位下界，而它们正是硬契约）。
            LOGGER.warn("RoadWeaver profile solver=dp failed, using constraint fallback"
                            + " stations={} pins={} decks={}: {}",
                    n, countPinned(pin), countDeckFloors(deckFloor), t.toString());
            return java.util.Optional.of(ProfileFallback.terrainFollowing(
                    terrainY, Math.max(0, gradeLimits.plainCut()),
                    Math.max(0, gradeLimits.plainFill()),
                    Math.max(1, (Math.max(1, cfg.maxSlopeStepPerTwoSegments()) + 1) / 2),
                    Math.max(1, cfg.maxSlopeStepPerTwoSegments()),
                    pin, tunnelMask, minCover, deckFloor));
        }
    }

}
