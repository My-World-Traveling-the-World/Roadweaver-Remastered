/* 文件职责：组装高程硬约束并编排 DP 与约束回落，世界采样经最小端口注入。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileSolverMode;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFailureSnapshot;
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

    /** 纯编排测试和无世界调用共用严格投影；实际路线另外注入物理隧道选择端口。 */
    static Optional<int[]> applyProfileSolver(ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing, boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary, RoadGenerationConfig cfg,
            SurfaceGradeLimits gradeLimits, StructureConnection connection) {
        return applyProfileSolver(level, centers, smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
                startBoundary, endBoundary, cfg, gradeLimits, connection,
                (t, c, cut, fill, step, two, constraints) -> Optional.of(ProfileFallback.terrainFollowing(
                        t, cut, fill, step, two, constraints)));
    }

    static Optional<int[]> applyProfileSolver(
            ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing,
            boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary, RoadGenerationConfig cfg,
            SurfaceGradeLimits gradeLimits, StructureConnection connection, Solver repair) {
        return solve(level, centers, smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
                startBoundary, endBoundary, cfg, gradeLimits, connection,
                (station, clearance) -> RoadConstructionSafety.bridgeDeckSurfaceY(
                        level, centers, station, clearance, Integer.MIN_VALUE), ProfileDpSource::solve, repair);
    }

    /**
     * 已钉住的站数按标记统计，不能把零高度以下的结构端点漏掉。
     *
     * <p>注意它与 {@link #countDeckFloors(int[])} 的哨兵值<b>不同</b>：
     * 两个数组语义不一样，混用一个统计函数会把日志写错 —— 而说谎的日志
     * 比没有日志更糟（本项目已因「静默降级 + 日志不实」吃过多次亏）。</p>
     */
    private static int countPinned(boolean[] stations) {
        int c = 0;
        for (boolean pinned : stations) {
            if (pinned) c++;
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
        return solve(level, centers, smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
                startBoundary, endBoundary, cfg, gradeLimits, connection, deckSampler, solver,
                (t, c, cut, fill, step, two, constraints) -> Optional.of(ProfileFallback.terrainFollowing(
                        t, cut, fill, step, two, constraints)));
    }

    /** 世界边界只提供修复输入；优化器失败后仍须经过可行性判定，不能发布带跳变的数组。 */
    static Optional<int[]> solve(ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing, boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary, RoadGenerationConfig cfg, SurfaceGradeLimits gradeLimits,
            StructureConnection connection, DeckFloorSampler deckSampler, Solver solver, Solver repair) {
        return solve(level,centers,smoothed,terrainY,isCrossing,tunnelMask,pinnedMask,startBoundary,endBoundary,cfg,
                gradeLimits,connection,deckSampler,solver,repair,
                level==null?null:snapshot->ProfileFailureFiles.record(level,snapshot));
    }

    /** 诊断消费端可离线注入；成功路线不创建快照，不执行文件IO。 */
    static Optional<int[]> solve(ServerLevel level,List<BlockPos> centers,
            int[] smoothed,int[] terrainY,boolean[] isCrossing,boolean[] tunnelMask,boolean[] pinnedMask,
            int startBoundary,int endBoundary,RoadGenerationConfig cfg,SurfaceGradeLimits gradeLimits,
            StructureConnection connection,DeckFloorSampler deckSampler,Solver solver,Solver repair,
            java.util.function.Consumer<ProfileFailureSnapshot> rejected) {
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
        boolean[] fixed = new boolean[n];
        java.util.Arrays.fill(pin, -1);
        if (pinnedMask != null) {
            // 端点有无锁高由调用方给定，不以高度符号推断。
            if (pinnedMask.length > 0 && pinnedMask[0]) {
                pin[0] = startBoundary;
                fixed[0] = true;
            }
            if (pinnedMask.length > n - 1 && pinnedMask[n - 1]) {
                pin[n - 1] = endBoundary;
                fixed[n - 1] = true;
            }
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
            LOGGER.warn("【桥面下界采样失败，本次不施加该下界】 " + "RoadWeaver profile deckFloor sampling failed, using no lower bound: {}",
                    deckFailure.toString());
        }
        var constraints = new ProfileDpSource.Constraints(pin, tunnelMask, minCover, deckFloor, fixed);
        int perTwo = Math.max(1, cfg.maxSlopeStepPerTwoSegments());
        int maxStep = Math.max(1, (perTwo + 1) / 2);
        int maxCut = Math.max(0, gradeLimits.plainCut());
        int maxFill = Math.max(0, gradeLimits.plainFill());
        Optional<int[]> solved;
        try {
            solved = solver.solve(terrainY, isCrossing, maxCut, maxFill, maxStep, perTwo, constraints);
        } catch (RuntimeException failure) {
            // 不捕获虚拟机错误；数学窗口修复放到 catch 外，真正无解不能再被当作求解器异常兜一遍。
            LOGGER.warn("【纵断面DP执行失败，转入施工窗口复核】 stations={} cause={}", n, failure.toString());
            solved = Optional.empty();
        }
        if (solved.isPresent()) {
            LOGGER.info("【纵断面DP求解完成】 RoadWeaver profile solver=dp stations={} perTwo={} maxStep={} cut={} fill={} minCover={} connection={}->{}",
                    n, perTwo, maxStep, maxCut, maxFill, minCover,
                    connection == null ? "?" : connection.from(), connection == null ? "?" : connection.to());
            return solved;
        }
        LOGGER.warn("【纵断面地表窗口不可行，复核已选隧道与局部施工预算】 stations={} cut={} fill={} perTwo={} pins={} decks={} connection={}->{}",
                n, maxCut, maxFill, perTwo, countPinned(fixed), countDeckFloors(deckFloor),
                connection == null ? "?" : connection.from(), connection == null ? "?" : connection.to());
        Optional<int[]> repaired;
        try {repaired=repair.solve(terrainY,isCrossing,maxCut,maxFill,maxStep,perTwo,constraints);}
        catch(RuntimeException failure) {
            var witness=TargetProfileRepair.failureWitness(failure);
            if(witness!=null)recordFailure(level,connection,centers,terrainY,isCrossing,constraints,maxCut,maxFill,maxStep,perTwo,witness,rejected);
            throw failure;
        }
        if (repaired.isEmpty()) {
            recordFailure(level,connection,centers,terrainY,isCrossing,constraints,maxCut,maxFill,maxStep,perTwo,
                    new ProfileFailureSnapshot.Conflict("NO_FEASIBLE_PROFILE",-1,-1,0,0),rejected);
            throw new IllegalArgumentException("纵断面在桥面锁高、已选隧道和施工预算下仍无解，拒绝发布，stations=" + n);
        }
        return repaired;
    }

    private static void recordFailure(ServerLevel level,StructureConnection connection,List<BlockPos> centers,
            int[] terrain,boolean[] crossing,ProfileDpSource.Constraints constraints,int cut,int fill,int step,int two,
            ProfileFailureSnapshot.Conflict conflict,java.util.function.Consumer<ProfileFailureSnapshot> output) {
        if(output==null)return;
        try {
            var coordinates=new long[centers.size()];
            for(int i=0;i<coordinates.length;i++)coordinates[i]=(long)centers.get(i).getX()<<32|centers.get(i).getZ()&0xffffffffL;
            output.accept(new ProfileFailureSnapshot(1,level==null?"offline":level.dimension().location().toString(),
                    connection==null?"unidentified":connection.from()+"->"+connection.to(),coordinates,terrain,
                    crossing==null?new boolean[terrain.length]:crossing,constraints.pin(),constraints.pinnedStations(),
                    constraints.mustBeCovered()==null?new boolean[terrain.length]:constraints.mustBeCovered(),constraints.minCover(),
                    constraints.deckFloor(),cut,fill,step,two,conflict));
        } catch(RuntimeException diagnosticFailure) {
            // 诊断是观察者；包括目录解析、序列化和测试端口在内，均不能替换真正的施工拒绝。
            LOGGER.warn("【纵断面失败快照组装或交付失败】 conflict={} cause={}",conflict,diagnosticFailure.toString());
        }
    }
}
