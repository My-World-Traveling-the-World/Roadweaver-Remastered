package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;

/**
 * Final bounded route repair using accurate terrain heights. The coarse
 * planner remains authoritative unless the resolved path contains a physically
 * impossible one-block grade or a cliff-sized 12-block rise/drop.
 */
public final class AccurateGradeRepairPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /**
     * 0.9.069（D-11a）：上一趟 searchLocalRoute 是否因**预算耗尽**（open 非空但状态数/墙钟
     * 到上限）退出，而不是 open 空（真无解）。ThreadLocal：搜索都在同一线程内同步完成。
     * 用于闸门区分「陆路真不通」与「陆路没搜完」——后者绝不能 fail-open 去架桥。
     */
    private static final ThreadLocal<Boolean> LAST_SEARCH_BUDGET_EXHAUSTED = new ThreadLocal<>();
    private static final int LOCAL_WINDOW_BLOCKS = 12;
    /** Height anomalies are repaired in one explicit local 50-block window. */
    private static final int HEIGHT_REPAIR_CONTEXT_BLOCKS = 50;
    private static final int SEARCH_MARGIN_BLOCKS = 80;
    private static final int STRUCTURE_CONTEXT_BLOCKS = 96;
    private static final int STRUCTURE_SEARCH_MARGIN_BLOCKS = 192;
    private static final int STRUCTURE_AHEAD_CLEARANCE_BLOCKS = 10;
    /** Used only when a caller has no world-height context. Runtime repair uses the world minimum. */
    private static final int DEFAULT_TUNNEL_DEPTH_BLOCKS = 12;
    private static final int LAKE_CONTEXT_BLOCKS = 96;
    private static final int LAKE_SHORE_PROBE_BLOCKS = 8;
    private static final int LAKE_REROUTE_CLEARANCE_BLOCKS = 10;
    /** Lake banks may be lightly leveled, but must not be cut into visible trenches. */
    private static final int MAX_LAKE_SHORE_CUT_DEPTH = 2;
    private static final int MAX_LAKE_SHORE_FILL_DEPTH = 2;
    private static final int MAX_LOCAL_REPAIRS = 4;
    /** One bounded local reroute for a grade anomaly that survives normal repair. */
    private static final int HEIGHT_ANOMALY_DETOUR_BLOCKS = 50;
    /** A tier may fix each local conflict once; successful fixes remain fixed. */
    private static final int MAX_STRUCTURE_REPAIRS = 8;
    /**
     * A chain of nearby buildings must not turn a local 10/20/50-block detour
     * into an endpoint-to-endpoint search. Later buildings are repaired by the
     * next pass after the preceding local repair has been committed.
     */
    private static final int MAX_STRUCTURE_CLUSTER_SPAN_BLOCKS = 192;
    private static final int MAX_LAKE_REPAIRS = 8;
    private static final int MAX_RIVER_REPAIRS = 8;
    /** Dry stations tolerated between two wet runs of one crossing group. */
    private static final int RIVER_RUN_MERGE_MAX_DRY_STATIONS = 12;
    private static final int MAX_EXPANDED_STATES = 90_000;
    private static final int MAX_TUNNEL_EXPANDED_STATES = 240_000;
    /** Exact building repair is intentionally local; it must not stall a whole road. */
    private static final int MAX_EXACT_BUILDING_EXPANDED_STATES = 24_000;
    /**
     * 0.9.001：单个局部修坡窗口 A* 的墙钟预算兜底值（20 秒）。
     *
     * <p>写法与同包的 {@code MountainSwitchbackPlanner.MAX_SEARCH_NANOS} /
     * {@code LOCAL_MAX_SEARCH_NANOS} 一致：只用一个纳秒 long 常量。正常取值由
     * {@code PlanningConfig.gradeRepairWindowBudgetMillis} 承载（默认同为 20 秒，
     * 改配置即生效，不用重编），本常量只在该配置读不出来时兜底，保证任何情况下
     * 这条 A* 都有墙钟上界。</p>
     */
    private static final long DEFAULT_LOCAL_SEARCH_BUDGET_NANOS = 20_000_000_000L;
    private static final double MAX_ROUTE_LENGTH_MULTIPLIER = 3.0D;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    /** Repairs independent (non-overlapping) grade windows in parallel so a
     *  single road can use idle CPU cores instead of fixing one violation at a
     *  time. The pool size is configurable (PlanningConfig.gradeRepairParallelism,
     *  0 = auto); the old fixed 4-thread pool left road workers blocked in
     *  future.get() while most cores idled. Auto never exceeds the CPU count,
     *  so the pool cannot oversubscribe the machine. */
    private static final ExecutorService GRADE_REPAIR_POOL = Executors.newFixedThreadPool(
            resolvedGradeRepairParallelism(),
            runnable -> {
                Thread thread = new Thread(runnable, "RoadWeaver-GradeRepair");
                thread.setDaemon(true);
                return thread;
            });

    /** Resolved once at class init: read the configured parallelism (0 = auto). */
    private static int resolvedGradeRepairParallelism() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int configured;
        try {
            configured = ConfigService.get().planning().gradeRepairParallelism();
        } catch (Throwable ignored) {
            configured = 0;
        }
        if (configured > 0) {
            return Math.max(1, Math.min(processors, configured));
        }
        // Auto: never below 4 (the old floor), scaled to the current
        // road-worker budget, capped by the CPU count to avoid oversubscription.
        int recommended;
        try {
            recommended = Math.max(4, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        } catch (Throwable ignored) {
            recommended = 4;
        }
        return Math.max(4, Math.min(processors, recommended));
    }

    private AccurateGradeRepairPlanner() {
    }

    public static List<BlockPos> repair(List<BlockPos> accuratePath,
                                        ServerLevel level,
                                        AccurateHeightSampler sampler,
                                        PathfindingCostConfig config,
                                        StructureConnection connection) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null) return accuratePath;

        // Fast path: the violation scan is a pure Y comparison (no terrain
        // sampling), so an already-stable profile is detected in O(n) and
        // skips the whole repair-batch machinery — pool submissions, context
        // walks, splices and the defensive copy.
        if (findFirstViolation(accuratePath) == null) {
            return accuratePath;
        }

        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        while (repaired < MAX_LOCAL_REPAIRS) {
            // Collect up to MAX_LOCAL_REPAIRS NON-OVERLAPPING grade windows.
            // They are independent, so their local step=1 repairs can run in
            // parallel to use idle CPU cores instead of one violation at a time.
            List<Violation> batch = new ArrayList<>();
            int cursor = 0;
            Violation unrepaired = null;
            while (batch.size() < MAX_LOCAL_REPAIRS - repaired) {
                List<BlockPos> tail = working.subList(cursor, working.size());
                Violation tailViolation = findFirstViolation(tail);
                if (tailViolation == null) break;
                Violation absolute = new Violation(
                        cursor + tailViolation.start(),
                        cursor + tailViolation.end(),
                        tailViolation.rise(),
                        tailViolation.length());
                int windowStart = walkContextStart(
                        working, absolute.start(), heightRepairContextBlocks());
                int windowEnd = walkContextEnd(
                        working, absolute.end(), heightRepairContextBlocks());
                if (windowEnd - windowStart < 2) {
                    unrepaired = absolute;
                    break;
                }
                batch.add(absolute);
                cursor = windowEnd + 1;
            }
            if (batch.isEmpty()) {
                // 违规存在但连修复窗口都取不出来：这条就是"修不动"的样本，
                // 必须留日志，否则最该看的一类偏差反而没有记录。
                if (unrepaired != null) {
                    logGradeDiagnostics(working, unrepaired, sampler);
                }
                break;
            }

            PathfindingCostConfig fine = config.withAStarStep(2);
            List<Future<GradePlan>> futures = new ArrayList<>();
            for (Violation violation : batch) {
                logGradeDiagnostics(working, violation, sampler);
                int startIndex = walkContextStart(
                        working, violation.start(), heightRepairContextBlocks());
                int endIndex = walkContextEnd(
                        working, violation.end(), heightRepairContextBlocks());
                List<BlockPos> original = new ArrayList<>(
                        working.subList(startIndex, endIndex + 1));
                futures.add(GRADE_REPAIR_POOL.submit(
                        () -> computeGradePlan(level, sampler, fine, connection,
                                startIndex, endIndex, original, violation)));
            }

            List<GradePlan> plans = new ArrayList<>();
            for (Future<GradePlan> future : futures) {
                try {
                    plans.add(future.get());
                } catch (Exception e) {
                    // 0.9.004：池线程（RoadWeaver-GradeRepair）里抛出的任何异常，过去只会变成
                    // 「这个窗口没有 plan」，与「本来就没找到可行绕行」在日志上完全同形 —— 正是
                    // 静默降级的温床（长城 0.9.x 事故的最后一环就是这个形状）。这里只补日志：
                    // 控制流一字不改，仍是 plans.add(null)，该窗口照旧被跳过。
                    // 刻意不加 Thread.currentThread().interrupt()：本方法现有的取消语义是
                    // 「该窗口跳过、其余窗口继续」；恢复中断标志会让同一批次里剩下的 future.get()
                    // 立刻失败从而跳过全部剩余窗口，属改变中止行为，需单独决策。
                    LOGGER.warn("[ROAD-GRADE-POOL] grade repair task failed, window will be skipped: {}",
                            e.toString(), e);
                    plans.add(null);
                }
            }

            // Apply accepted plans in descending start index so earlier
            // windows keep their original path indices after later splices.
            boolean anyApplied = false;
            for (int i = plans.size() - 1; i >= 0; i--) {
                GradePlan plan = plans.get(i);
                if (plan == null) continue;
                List<BlockPos> replaced = new ArrayList<>(
                        working.size()
                                - (plan.endIndex() - plan.startIndex() + 1)
                                + plan.candidate().size());
                replaced.addAll(working.subList(0, plan.startIndex()));
                replaced.addAll(plan.candidate());
                replaced.addAll(working.subList(plan.endIndex() + 1, working.size()));
                working = replaced;
                anyApplied = true;
                repaired++;
            }
            if (!anyApplied) break;
        }
        return List.copyOf(working);
    }

    /** Computes the local step=1 repair for one non-overlapping grade window. */
    private static GradePlan computeGradePlan(ServerLevel level,
                                              AccurateHeightSampler sampler,
                                              PathfindingCostConfig fine,
                                              StructureConnection connection,
                                              int startIndex,
                                              int endIndex,
                                              List<BlockPos> original,
                                              Violation violation) {
        try {
            // 纯纵向修复优先：XZ 不动，只把窗口剖面换成最优坡度剖面（分层 DAG 最短路）。
            // 毫秒级，覆盖绝大多数坡度违反；只有它不成立时才升级到改 XZ 的 2D A*。
            // 0.9.062（④）：局部搜索返回 null 时不许把已经算出来的候选盖掉（旧代码直接赋 null，
            // 于是 solveGradeProfileWindow 的成果被吞，整窗失败时日志里也没有任何证据）。
            List<BlockPos> candidate = solveGradeProfileWindow(original, sampler);
            if (!isAccepted(original, candidate)) {
                List<BlockPos> searched;
                try (StructureAvoidanceService.PlanningScope ignored =
                             StructureAvoidanceService.activatePlanningSnapshot(
                                     level, connection, HEIGHT_ANOMALY_DETOUR_BLOCKS)) {
                    searched = findLocalRoute(original, sampler, fine,
                            HEIGHT_ANOMALY_DETOUR_BLOCKS, EdgeObstacle.NONE, 2, false, 0);
                }
                candidate = keepCandidateOnNull(candidate, searched,
                        windowLabel(startIndex, endIndex), "surface");
            }
            if (!isAccepted(original, candidate)) {
                List<BlockPos> searched;
                try (StructureAvoidanceService.PlanningScope ignored =
                             StructureAvoidanceService.activatePlanningSnapshot(
                                     level, connection, HEIGHT_ANOMALY_DETOUR_BLOCKS)) {
                    searched = findLocalRoute(original, sampler, fine,
                            HEIGHT_ANOMALY_DETOUR_BLOCKS, EdgeObstacle.NONE, 2, true, 0,
                            null, level.getMinBuildHeight());
                }
                candidate = keepCandidateOnNull(candidate, searched,
                        windowLabel(startIndex, endIndex), "tunnel");
            }
            if (!isAccepted(original, candidate)) {
                candidate = forceSmoothHeightWindow(original);
            }
            if (!isAccepted(original, candidate)) {
                logLocalSearchDiag("grade-rejected");
                LOGGER.warn("RoadWeaver accurate grade repair could not replace cliff window start={} end={} rise={} length={}",
                        violation.start(), violation.end(), violation.rise(), format(violation.length()));
                return null;
            }
            logLocalSearchDiag("grade-accepted");
            LOGGER.info("Accepted accurate RoadWeaver grade repair: rise={} over {} blocks, nodes={} -> {}",
                    violation.rise(), format(violation.length()), original.size(), candidate.size());
            return new GradePlan(startIndex, endIndex, candidate);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 窗口内的纯纵向修复：保持每一站的 XZ 不变，只按最优坡度剖面（分层 DAG 最短路）
     * 重算 Y。复杂度 {@code O(n·H²)}，比逐窗口 2D A* 便宜几个数量级；上坡与下坡同样处理。
     */
    private static List<BlockPos> solveGradeProfileWindow(List<BlockPos> original,
                                                          AccurateHeightSampler sampler) {
        if (original == null || original.size() < 4 || sampler == null) {
            return original;
        }
        int[] terrain = new int[original.size()];
        for (int i = 0; i < terrain.length; i++) {
            BlockPos position = original.get(i);
            terrain[i] = sampler.surfaceHeight(position.getX(), position.getZ());
        }
        int[] profile = RoadGradePlanner.planConfiguredGradeProfile(terrain, null);
        List<BlockPos> result = new ArrayList<>(original.size());
        for (int i = 0; i < original.size(); i++) {
            BlockPos position = original.get(i);
            result.add(profile[i] == position.getY()
                    ? position
                    : new BlockPos(position.getX(), profile[i], position.getZ()));
        }
        return result;
    }

    private record GradePlan(int startIndex, int endIndex, List<BlockPos> candidate) {
    }

    /** 0.9.062（④）：{@code [REPAIR-NULL]} 累计次数（只读观测 + 单测断言）。 */
    private static final java.util.concurrent.atomic.AtomicLong REPAIR_NULL_SEEN =
            new java.util.concurrent.atomic.AtomicLong();

    /** 0.9.062（④）：{@code [REPAIR-NULL]} 的累计次数。 */
    static long repairNullCount() {
        return REPAIR_NULL_SEEN.get();
    }

    /**
     * 0.9.062（④）：{@code -Droadweaver.keepNullCandidate=false}（也认 0/off）时回到 0.9.061 的旧行为
     * —— 搜索返回什么就赋什么（可能是 {@code null}），不保留原候选、也不打 {@code [REPAIR-NULL]}。
     * 默认开。
     */
    static boolean keepNullCandidateEnabled() {
        String raw = System.getProperty(RoadConstants.KEEP_NULL_CANDIDATE_PROPERTY);
        if (raw == null) return true;
        String value = raw.trim();
        if (value.isEmpty()) return true;
        return !(value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("off"));
    }

    /** 0.9.062（④）：{@code [REPAIR-NULL]} 的窗口标签。 */
    private static String windowLabel(int startIndex, int endIndex) {
        return "grade:" + startIndex + "-" + endIndex;
    }

    /**
     * 0.9.062（④）：局部搜索返回 {@code null} 时<b>保留调用前的候选</b>，并留一行证据。
     *
     * <p>病灶：{@code candidate = findLocalRoute(...)} 把 {@code null} 直接盖上去，原来那条候选
     * 被静默吞掉；随后只剩 {@code forceSmoothHeightWindow} 兜底，兜不住就整窗 {@code return null}
     * —— 坡度/结构冲突原样留在路上，日志里一个字都没有。玩家看到的是"这条路就是没修好"，
     * 排查时却找不到任何记录，也无从判断到底试过哪几档。</p>
     *
     * <p>这里只做两件事：返回调用前的引用（{@code previous}，<b>同一个对象、逐位不变</b>）、
     * 计数并节流打一行 {@code [REPAIR-NULL]}。前 40 次 + 之后每 200 次打 INFO。</p>
     *
     * @param previous 调用前的候选（可以为 null —— 那表示本来就没有可保留的东西）
     * @param searched 局部搜索的返回（{@code null} 或长度 &lt; 2 都算"没找到路"）
     * @param window   窗口标签，例如 {@code grade:12-40}
     * @param reason   哪一趟搜索（surface / tunnel / lake / river / height-reroute）
     * @return 找到路时返回 {@code searched}；否则原样返回 {@code previous}
     */
    static List<BlockPos> keepCandidateOnNull(List<BlockPos> previous, List<BlockPos> searched,
                                              String window, String reason) {
        // 回退口：关掉即逐位回到 0.9.061（搜索结果直接覆盖，可能是 null，且不计数不打日志）。
        if (!keepNullCandidateEnabled()) return searched;
        if (searched != null && searched.size() >= 2) return searched;
        long seen = REPAIR_NULL_SEEN.incrementAndGet();
        if (seen <= 40L || seen % 200L == 0L) {
            LOGGER.info("[REPAIR-NULL] #{} window={} reason={} keptOriginal={} keptNodes={} searchedNodes={}",
                    seen, window, reason, previous != null,
                    previous == null ? 0 : previous.size(),
                    searched == null ? 0 : searched.size());
        }
        return previous;
    }

    /**
     * Reroutes a construction-time height conflict before the road is
     * persisted. The ordinary grade repair above can only change Y values;
     * this pass changes X/Z inside one bounded conflict-centered window so a
     * road does not accept a deep cut or fill simply because its centerline
     * slope looked acceptable.
     */
    public static List<BlockPos> rerouteConstructionHeightConflict(
            List<BlockPos> path,
            int conflictSegment,
            ServerLevel level,
            AccurateHeightSampler sampler,
            PathfindingCostConfig config,
            StructureConnection connection) {
        if (path == null || path.size() < 3 || conflictSegment < 0
                || conflictSegment >= path.size() || level == null
                || sampler == null || config == null || connection == null) return path;

        // 0.8.316：窗口逐级放大。原实现只按 HEIGHT_ANOMALY_DETOUR_BLOCKS(50) 站试一次，
        // 真机 3 次 "construction-height reroute no bypass"（rise=294/331）都在爬山段，
        // ±50 站窗口里确实没有旁路。这里按 50 → heightAnomalyDetourMaxBlocks 逐级重试，
        // 每级先试可打隧道的 3D 旁路、再试纯地表旁路；任何一级通过就返回。
        int startMargin = HEIGHT_ANOMALY_DETOUR_BLOCKS;
        int maxMargin = Math.max(startMargin, heightAnomalyDetourMaxBlocks());
        for (int margin = startMargin; ; margin = maxMargin) {
            int startIndex = walkContextStart(path, conflictSegment, margin);
            int endIndex = walkContextEnd(path, conflictSegment, margin);
            if (endIndex - startIndex < 2) {
                if (margin >= maxMargin) return path;
                continue;
            }

            List<BlockPos> original = new ArrayList<>(path.subList(startIndex, endIndex + 1));
            BlockPos conflictCenter = path.get(conflictSegment);
            PathfindingCostConfig fine = config.withAStarStep(2);
            List<BlockPos> candidate;

            // A deep fill conflict must try a 3D tunnel-capable detour first. The
            // old surface-first order accepted any slope-valid path, even when its
            // rebuilt footprint still required a 20+ block fill, and only then
            // attempted the tunnel branch too late. That produced a hard failure
            // instead of a tunnel or a wider local bypass.
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection, margin)) {
                candidate = findLocalRoute(original, sampler, fine,
                        margin, EdgeObstacle.NONE, 2,
                        true, 0, null, level.getMinBuildHeight(), conflictCenter);
            }
            if (!isAccepted(original, candidate)) {
                try (StructureAvoidanceService.PlanningScope ignored =
                             StructureAvoidanceService.activatePlanningSnapshot(level, connection, margin)) {
                    candidate = findLocalRoute(original, sampler, fine,
                            margin, EdgeObstacle.NONE, 2,
                            false, 0, null, Integer.MIN_VALUE, conflictCenter);
                }
            }
            if (isAccepted(original, candidate)) {
                ArrayList<BlockPos> replaced = new ArrayList<>(path.size() - original.size() + candidate.size());
                replaced.addAll(path.subList(0, startIndex));
                replaced.addAll(candidate);
                replaced.addAll(path.subList(endIndex + 1, path.size()));
                logLocalSearchDiag("height-reroute-accepted");
                LOGGER.info("RoadWeaver accepted pre-construction height reroute conflictSegment={} "
                                + "window={}..{} margin={} nodes={} -> {}",
                        conflictSegment, startIndex, endIndex, margin, original.size(), candidate.size());
                return List.copyOf(replaced);
            }
            if (margin >= maxMargin) break;
        }
        logLocalSearchDiag("height-reroute-no-bypass");
        LOGGER.warn("RoadWeaver construction-height reroute no bypass; keeping original gap "
                + "connection={}->{} conflictSegment={} rise={} triedMargin={}..{}",
                connection.from(), connection.to(), conflictSegment,
                path.get(conflictSegment).getY(), startMargin, maxMargin);
        return path;
    }

    private static int heightAnomalyDetourMaxBlocks() {
        try {
            return ConfigService.get().planning().heightAnomalyDetourMaxBlocks();
        } catch (Throwable ignored) {
            return HEIGHT_ANOMALY_DETOUR_BLOCKS;
        }
    }

    /**
     * Repairs an accurate route whose nodes or connecting edges enter a
     * protected structure. This runs after coarse planning because resolving
     * real surface heights can change the route's vertical intersection with
     * a structure even when the coarse corridor was valid.
     */
    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection) {
        return repairStructureIntersections(
                accuratePath, level, sampler, config, connection, STRUCTURE_SEARCH_MARGIN_BLOCKS);
    }

    /**
     * Repairs only the window around the first exact structure conflict. The
     * caller controls the lateral detour allowance; this never re-plans the
     * full endpoint-to-endpoint connection.
     */
    public static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks) {
        return repairStructureIntersections(accuratePath, level, sampler, config, connection,
                localDetourBlocks, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    /**
     * Final building-only precision pass.  The search stays local to the
     * conflicting building but permits grazing its real footprint; only the
     * actual road corridor may not overlap decoded template blocks.
     */
    public static List<BlockPos> repairStructureIntersectionsAtExactClearance(
            List<BlockPos> accuratePath,
            ServerLevel level,
            AccurateHeightSampler sampler,
            PathfindingCostConfig config,
            StructureConnection connection,
            int localDetourBlocks) {
        return repairStructureIntersectionsAtExactClearanceDetailed(
                accuratePath, level, sampler, config, connection, localDetourBlocks).path();
    }

    /** Outcome of one exact-clearance building repair pass. */
    public record ExactStructureRepairOutcome(List<BlockPos> path,
                                              BlockPos lastRejectedCenter,
                                              int lastRejectedIndex,
                                              String lastRejectionReason) {
    }

    /**
     * Same as {@link #repairStructureIntersectionsAtExactClearance} but also
     * reports the conflict centre that could not be rerouted, so callers can
     * stop escalating the detour radius when the same conflict already failed
     * at a smaller radius.
     */
    public static ExactStructureRepairOutcome repairStructureIntersectionsAtExactClearanceDetailed(
            List<BlockPos> accuratePath,
            ServerLevel level,
            AccurateHeightSampler sampler,
            PathfindingCostConfig config,
            StructureConnection connection,
            int localDetourBlocks) {
        PathfindingCostConfig exact = config == null ? null : config.withAStarStep(1);
        List<BlockPos> rejectedCenters = new ArrayList<>(1);
        List<Integer> rejectedIndices = new ArrayList<>(1);
        List<BlockPos> path = repairStructureIntersections(accuratePath, level, sampler, exact,
                connection, localDetourBlocks, 0, rejectedCenters, rejectedIndices);
        BlockPos rejectedCenter = rejectedCenters.isEmpty() ? null : rejectedCenters.getLast();
        int rejectedIndex = rejectedIndices.isEmpty() ? -1 : rejectedIndices.getLast();
        return new ExactStructureRepairOutcome(path, rejectedCenter, rejectedIndex,
                rejectedCenter == null ? null : "unresolved");
    }

    private static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks,
                                                                int structureClearance) {
        return repairStructureIntersections(accuratePath, level, sampler, config, connection,
                localDetourBlocks, structureClearance, null, null);
    }

    private static List<BlockPos> repairStructureIntersections(List<BlockPos> accuratePath,
                                                                ServerLevel level,
                                                                AccurateHeightSampler sampler,
                                                                PathfindingCostConfig config,
                                                                StructureConnection connection,
                                                                int localDetourBlocks,
                                                                int structureClearance,
                                                                List<BlockPos> rejectedCenterSink,
                                                                List<Integer> rejectedIndexSink) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null) return accuratePath;

        // Exact building repair still has to reserve the complete road body.
        // A centre-line-only A* result can look clear while its five-block
        // road edge clips the building on a bend.
        int roadBodyRadius = roadBodyRadius();
        int collisionClearance = structureClearance > 0
                ? structureClearance : roadBodyRadius;
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        // The exact-clearance entry point passes zero to avoid adding an
        // artificial collision margin, but the snapshot still has to index
        // structures throughout the complete local detour window.  Without
        // this, a valid bend that crosses into the neighbouring chunk cannot
        // see the same building and is incorrectly accepted or rejected.
        // Snapshot expansion is a physical road-width margin. The local
        // terrain search radius is independent and must not enlarge buildings.
        int snapshotRadius = Math.max(0, roadBodyRadius());
        while (repaired < MAX_STRUCTURE_REPAIRS) {
            Violation conflict;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                conflict = findFirstStructureConflict(working, collisionClearance);
            }
            if (conflict == null) break;

            // The route snapshot may cover the original corridor but not every
            // chunk in this conflict-centered repair circle. Resolve the circle
            // before A* so unknown neighboring chunks do not all look blocked.
            BlockPos localCenter = conflictCenter(working, conflict);
            if (localCenter != null && localDetourBlocks > 0) {
                awaitLocalStructureCircle(level, connection, localCenter, localDetourBlocks);
            }
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                conflict = findFirstStructureConflict(working, collisionClearance);
                if (conflict != null) {
                    conflict = expandNearbyStructureCluster(
                            working, conflict, collisionClearance, localDetourBlocks);
                }
            }
            if (conflict == null) break;

            int repairContext = Math.max(1, Math.min(STRUCTURE_CONTEXT_BLOCKS, localDetourBlocks));
            List<RepairSlice> repairSlices = structureRepairSlices(
                    working, conflict, localCenter, repairContext);
            if (repairSlices.isEmpty()) break;
            RepairSlice selectedSlice = null;
            List<BlockPos> candidate = null;
            boolean accepted;
            boolean tunnelSearchUsed = false;
            String conflictDetail;
            int rerouteStep = 2;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection,
                                 snapshotRadius)) {
                BlockPos conflictPos = conflict.start() >= 0 && conflict.start() < working.size()
                        ? working.get(conflict.start()) : null;
                BlockPos conflictEnd = conflict.end() >= 0 && conflict.end() < working.size()
                        ? working.get(conflict.end()) : conflictPos;
                BlockPos searchCenter = conflictPos == null || conflictEnd == null
                        ? conflictPos
                        : new BlockPos(
                        (conflictPos.getX() + conflictEnd.getX()) / 2,
                        (conflictPos.getY() + conflictEnd.getY()) / 2,
                        (conflictPos.getZ() + conflictEnd.getZ()) / 2);
                conflictDetail = describePlanningCollision(conflictPos, conflictEnd);
                // The 100-block value is only the terrain sampling/search
                // radius. Building collision remains limited to the actual
                // road body width so nearby but non-touching structures do
                // not incorrectly block a valid edge-grazing detour.
                BoundingBox conflictBox = planningCollisionBox(
                        conflictPos, collisionClearance);

                accepted = false;
                // Try several endpoint-preserving slices. A single slice can
                // fail when the conflict is close to a route endpoint or when
                // its snapped grid point falls inside the protected box.
                // Every slice remains bounded by the same local detour radius.
                for (RepairSlice slice : repairSlices) {
                    List<BlockPos> slicePath = slice.path();
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            EdgeObstacle.NONE, 2, false, collisionClearance, conflictBox,
                            Integer.MIN_VALUE, searchCenter);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=surface radius={} roadWidth={} "
                                        + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                                conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=surface radius={} roadWidth={} "
                                    + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} reason={} {} connection={}->{}",
                            localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                            conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            describeStructureCandidateFailure(slicePath, candidate, collisionClearance),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());

                    // 0.8.330：surface 搜索"根本没路"时跳过隧道搜索。真机 20260909 21_51
                    // 存档：18 次隧道尝试全部 no_path、平均 13.2 秒（最长 25.8 秒），
                    // 合计约 240 秒纯浪费；而同批 surface 搜索平均只要 0.7 秒，换一个
                    // slice 再试 surface 往往就成功了。隧道搜索只有在"surface 找得到路
                    // 但过不去"时才值得一试。
                    if (candidate == null || candidate.size() < 2) {
                        LOGGER.info("RoadWeaver structure local tunnel search skipped: surface found no path "
                                        + "conflictEdge={}..{} center={} slice={}..{} connection={}->{}",
                                conflict.start(), conflict.end(), searchCenter,
                                slice.startIndex(), slice.endIndex(), connection.from(), connection.to());
                        continue;
                    }

                    tunnelSearchUsed = true;
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            EdgeObstacle.NONE, rerouteStep, true, collisionClearance, conflictBox,
                            localDetourTunnelFloor(level, searchCenter), searchCenter, true);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=tunnel radius={} roadWidth={} "
                                        + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                                conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=tunnel radius={} roadWidth={} "
                                    + "bodyRadius={} conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} reason={} {} connection={}->{}",
                            localDetourBlocks, ConfigService.get().roadAppearance().roadWidth(), roadBodyRadius,
                            conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            describeStructureCandidateFailure(slicePath, candidate, collisionClearance),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());

                    // ③ 硬绕行重试：把冲突建筑盒设为"禁止进入"的边障碍，强制局部 A*
                    // 从盒外绕过去，而不是贴着或穿过它（用户要求：建筑该绕行还是得绕行）。
                    // 比"四方向盲试"更对症：禁止进入后，A* 会自己从各个方向找出口。
                    EdgeObstacle blockEnteringBox = (from, to) -> pointInsideBox(conflictBox, to);
                    candidate = findLocalRoute(slicePath, sampler, config,
                            Math.max(config.effectiveAStarStep(), localDetourBlocks),
                            blockEnteringBox, 2, false, collisionClearance, conflictBox,
                            Integer.MIN_VALUE, searchCenter);
                    candidate = smoothCandidateGrade(candidate);
                    if (isStructureRepairAccepted(slicePath, candidate, collisionClearance)) {
                        selectedSlice = slice;
                        accepted = true;
                        LOGGER.info("RoadWeaver structure local candidate accepted mode=hard-detour radius={} "
                                        + "conflictEdge={}..{} center={} box={} slice={}..{} nodes={} -> {} {} connection={}->{}",
                                localDetourBlocks, conflict.start(), conflict.end(), searchCenter, conflictBox,
                                slice.startIndex(), slice.endIndex(), slicePath.size(), candidate.size(),
                                consumeLocalSearchDiag(),
                                connection.from(), connection.to());
                        break;
                    }
                    LOGGER.info("RoadWeaver structure local candidate rejected mode=hard-detour radius={} "
                                    + "conflictEdge={}..{} center={} box={} slice={}..{} candidateNodes={} {} connection={}->{}",
                            localDetourBlocks, conflict.start(), conflict.end(), searchCenter, conflictBox,
                            slice.startIndex(), slice.endIndex(), candidate == null ? 0 : candidate.size(),
                            consumeLocalSearchDiag(),
                            connection.from(), connection.to());
                }
            }
            if (!accepted) {
                LOGGER.warn("RoadWeaver exact structure repair could not reroute conflict edge={}..{} windowNodes={} "
                                + "slices={} rerouteStep={} clearance={} tunnelSearch={} {}",
                        conflict.start(), conflict.end(), repairSlices.get(0).path().size(), repairSlices.size(),
                        rerouteStep, collisionClearance, tunnelSearchUsed, conflictDetail);
                if (rejectedCenterSink != null) {
                    BlockPos center = conflictCenter(working, conflict);
                    if (center != null) rejectedCenterSink.add(center);
                }
                if (rejectedIndexSink != null) {
                    rejectedIndexSink.add(Math.max(0, Math.min(working.size() - 1,
                            (conflict.start() + conflict.end()) / 2)));
                }
                break;
            }

            List<BlockPos> original = selectedSlice.path();
            int startIndex = selectedSlice.startIndex();
            int endIndex = selectedSlice.endIndex();
            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            LOGGER.info("Accepted exact local RoadWeaver structure reroute: detour={} clearance={} "
                            + "tunnelSearch={} conflictEdge={}..{} nodes={} -> {}",
                    localDetourBlocks, localDetourBlocks, tunnelSearchUsed,
                    conflict.start(), conflict.end(),
                    original.size(), candidate.size());
        }
        return List.copyOf(working);
    }

    private static String describeStructureCandidateFailure(List<BlockPos> original,
                                                            List<BlockPos> candidate,
                                                            int structureClearance) {
        if (candidate == null) return "no_path";
        if (candidate.size() < 2) return "path_too_short";
        if (sameFullPath(original, candidate)) return "unchanged_path";
        Violation grade = findFirstViolation(candidate);
        if (grade != null) {
            return "grade edge=" + grade.start() + ".." + grade.end()
                    + " rise=" + grade.rise() + " length=" + format(grade.length());
        }
        Violation structure = findFirstStructureConflict(candidate, structureClearance);
        if (structure != null) {
            BlockPos from = candidate.get(Math.max(0, structure.start()));
            BlockPos to = candidate.get(Math.max(0, Math.min(candidate.size() - 1, structure.end())));
            return "structure edge=" + structure.start() + ".." + structure.end() + " "
                    + describePlanningCollision(from, to);
        }
        double originalLength = routeLength(original);
        double candidateLength = routeLength(candidate);
        if (candidateLength > Math.max(originalLength + 64.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER)) {
            return "detour_too_long candidate=" + format(candidateLength)
                    + " original=" + format(originalLength);
        }
        return "rejected_by_acceptance_policy";
    }

    /**
     * Force a local, endpoint-preserving height profile when no alternate X/Z
     * route can be found.  The caller has already bounded the input to the
     * height-repair window; only Y values are changed here.
     */
    private static List<BlockPos> forceSmoothHeightWindow(List<BlockPos> original) {
        if (original == null || original.size() < 3) return null;
        int size = original.size();
        double[] distances = new double[size];
        for (int i = 1; i < size; i++) {
            BlockPos previous = original.get(i - 1);
            BlockPos current = original.get(i);
            if (previous == null || current == null) return null;
            distances[i] = distances[i - 1] + horizontalDistance(previous, current);
        }
        double total = distances[size - 1];
        if (total <= 0.001D) return null;

        int startY = original.get(0).getY();
        int endY = original.get(size - 1).getY();
        int[] heights = new int[size];
        heights[0] = startY;
        heights[size - 1] = endY;
        for (int i = 1; i < size - 1; i++) {
            double ratio = distances[i] / total;
            heights[i] = (int) Math.round(startY + (endY - startY) * ratio);
        }

        // Project the interpolated profile onto the one-block grade limit.
        // Repeating both directions keeps the correction local while
        // preserving the two endpoint heights whenever they are feasible.
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < size - 1; i++) {
                int limit = Math.max(0, (int) Math.floor(
                        horizontalDistance(original.get(i - 1), original.get(i))));
                heights[i] = clamp(heights[i], heights[i - 1] - limit, heights[i - 1] + limit);
            }
            for (int i = size - 2; i >= 1; i--) {
                int limit = Math.max(0, (int) Math.floor(
                        horizontalDistance(original.get(i), original.get(i + 1))));
                heights[i] = clamp(heights[i], heights[i + 1] - limit, heights[i + 1] + limit);
            }
        }

        List<BlockPos> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos source = original.get(i);
            result.add(new BlockPos(source.getX(), heights[i], source.getZ()));
        }
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 绕行候选的接缝高度修复。绕行 A* 只优化 XZ，其返回的候选路径与原
     * 路径的接缝段常出现 11~15 格的相邻高差(实测 1136/1357 次 grade 拒绝
     * 集中在 edge=0..1)，被 findFirstViolation 的坡度检查直接拒掉，导致
     * 绕行"明明绕开了建筑却判失败"、退化成 protected-gap 进入局部修复。
     * 这里仅在候选存在坡度违反时，用已有的 forceSmoothHeightWindow 把它
     * 投影到坡度限制(保留首尾 Y 以维持与原路径的接缝)，平滑后无违反则
     * 采用；否则保持原候选，不改变原有拒绝语义。
     */
    private static List<BlockPos> smoothCandidateGrade(List<BlockPos> candidate) {
        if (candidate == null || candidate.size() < 3) return candidate;
        if (findFirstViolation(candidate) == null) return candidate;
        List<BlockPos> smoothed = forceSmoothHeightWindow(candidate);
        if (smoothed == null || smoothed.size() != candidate.size()) return candidate;
        return findFirstViolation(smoothed) == null ? smoothed : candidate;
    }

    private static int roadBodyRadius() {
        int width = Math.max(1, ConfigService.get().roadAppearance().roadWidth());
        // PathPostProcessor rasterizes the complete width around the centerline.
        // Use the ceiling of the half-width so the far edge of even-width roads
        // is protected as well; this is a collision radius, not extra building
        // padding and remains independent from the 100-block terrain window.
        return Math.max(0, (int) Math.ceil(width / 2.0D));
    }

    /**
     * Reroutes only the local window occupied by a Streams lake. River
     * channels remain governed by the existing bridge logic; this predicate
     * is based exclusively on RiverEngine.lakeAt().
     */
    public static List<BlockPos> repairLakeIntersections(List<BlockPos> accuratePath,
                                                          ServerLevel level,
                                                          AccurateHeightSampler sampler,
                                                          PathfindingCostConfig config,
                                                          StructureConnection connection,
                                                          StreamsReflowingCompat.SamplingSession streams,
                                                          int roadWidth,
                                                          int localDetourBlocks) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null
                || streams == null || !streams.isAvailable()) return accuratePath;

        Map<Long, Boolean> lakeCache = new HashMap<>();
        LakeLookup lakes = (x, z) -> lakeCache.computeIfAbsent(
                key(x, z), ignored -> streams.sampleLake(x, z).present());
        Map<Long, Boolean> lakeClearanceCache = new HashMap<>();
        LakeLookup lakeClearance = expandedLakeLookup(
                lakes, Math.max(1, roadWidth), LAKE_REROUTE_CLEARANCE_BLOCKS, lakeClearanceCache);
        HeightLookup terrain = sampler::surfaceHeight;
        int width = Math.max(1, roadWidth);
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        while (repaired < MAX_LAKE_REPAIRS) {
            Violation conflict = findFirstLakeConstraintConflict(working, width, lakes, terrain);
            if (conflict == null) break;

            int startIndex = walkContextStart(working, conflict.start(), LAKE_CONTEXT_BLOCKS);
            int endIndex = walkContextEnd(working, conflict.end(), LAKE_CONTEXT_BLOCKS);
            if (endIndex - startIndex < 2) break;

            List<BlockPos> original = new ArrayList<>(working.subList(startIndex, endIndex + 1));
            List<BlockPos> candidate;
            boolean accepted;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
                candidate = findLocalRoute(original, sampler, config,
                        Math.max(config.effectiveAStarStep(), localDetourBlocks),
                        (from, to) -> edgeIntersectsMask(from, to, lakeClearance));
                accepted = isLakeRepairAccepted(
                        original, candidate, width, lakes, terrain, lakeClearance);
            }
            BlockPos collision = working.get(Math.max(0, Math.min(conflict.start(), working.size() - 1)));
            if (!accepted) {
                logLocalSearchDiag("lake-no-bypass");
                LOGGER.warn("RoadWeaver lake repair could not reroute local conflict detour={} "
                                + "conflictEdge={}..{} pos={} windowNodes={}",
                        localDetourBlocks, conflict.start(), conflict.end(), collision, original.size());
                break;
            }

            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            LOGGER.info("Accepted local RoadWeaver lake reroute: detour={} clearance={} conflictEdge={}..{} "
                            + "pos={} nodes={} -> {}",
                    localDetourBlocks, LAKE_REROUTE_CLEARANCE_BLOCKS,
                    conflict.start(), conflict.end(), collision,
                    original.size(), candidate.size());
        }
        return List.copyOf(working);
    }

    /**
     * Repairs only river-channel conflicts.  A bridge/crossing span is still
     * preferred by the normal span extractor; this pass is the bounded
     * fallback when that physical crossing cannot be retained.  It deliberately
     * uses the road footprint width and a 50-block local window, never a new
     * endpoint-to-endpoint search.
     */
    public static List<BlockPos> repairRiverIntersections(List<BlockPos> accuratePath,
                                                           ServerLevel level,
                                                           AccurateHeightSampler sampler,
                                                           PathfindingCostConfig config,
                                                           StructureConnection connection,
                                                           StreamsReflowingCompat.SamplingSession streams,
                                                           int roadWidth,
                                                           int localDetourBlocks) {
        if (accuratePath == null || accuratePath.size() < 3 || level == null
                || sampler == null || config == null || connection == null
                || streams == null || !streams.isAvailable()) return accuratePath;

        Map<Long, Boolean> channelCache = new HashMap<>();
        RiverLookup channels = (x, z) -> channelCache.computeIfAbsent(
                key(x, z), ignored -> {
                    StreamsReflowingCompat.RiverColumn column = streams.sample(x, z);
                    return column.isUnknown() || column.inChannel();
                });
        int width = Math.max(1, roadWidth);
        int margin = Math.max(1, localDetourBlocks);
        List<BlockPos> working = new ArrayList<>(accuratePath);
        int repaired = 0;
        while (repaired < MAX_RIVER_REPAIRS) {
            Violation conflict = findFirstRiverConflict(working, width, channels);
            if (conflict == null) return List.copyOf(working);

            int startIndex = walkContextStart(working, conflict.start(), margin);
            int endIndex = walkContextEnd(working, conflict.end(), margin);
            // Merge nearby river wet runs into one repair window: a route that
            // crosses the same channel several times must be rerouted as one
            // bounded detour, not given up segment by segment.
            endIndex = extendWindowAcrossNearbyWetRuns(working, endIndex, 1, channels, margin);
            startIndex = extendWindowAcrossNearbyWetRuns(working, startIndex, -1, channels, margin);
            if (endIndex - startIndex < 2) break;
            // 0.8.316：先判"必然无解"。修复的判据是候选路径不得触碰任何河道列，
            // 因此只有"河道在窗口内能绕过去"才可能成功；河道把窗口一分为二时（窗口
            // 边界上出现 ≥2 段河道，或整圈都是水）任何候选都必然被拒。真机近 4 局
            // 30 次尝试 0 成功，每次都要先付一遍 step=1 的窗口 A*。这里先采样窗口
            // 边界判定，命中就跳过，只留一行 info。
            if (riverRepairBypassPrecheck()) {
                Bounds window = Bounds.around(working.subList(startIndex, endIndex + 1), margin, 1, null);
                if (channelSeparatesWindow(window, channels)) {
                    logLocalSearchDiag("river-skipped");
                    LOGGER.info("RoadWeaver river local repair skipped: channel crosses the whole window "
                                    + "(no bypass possible) conflictEdge={}..{} window=[{},{} -> {},{}] "
                                    + "connection={}->{}",
                            conflict.start(), conflict.end(),
                            window.minX(), window.minZ(), window.maxX(), window.maxZ(),
                            connection.from(), connection.to());
                    return List.copyOf(working);
                }
            }
            List<BlockPos> original = new ArrayList<>(working.subList(startIndex, endIndex + 1));
            PathfindingCostConfig fine = config.withAStarStep(1);
            List<BlockPos> candidate;
            try (StructureAvoidanceService.PlanningScope ignored =
                         StructureAvoidanceService.activatePlanningSnapshot(level, connection)) {
                candidate = findLocalRoute(original, sampler, fine, margin,
                        (from, to) -> edgeIntersectsRiver(from, to, width, channels),
                        1, false, 0, null);
            }
            if (!isRiverRepairAccepted(original, candidate, width, channels)) break;

            ArrayList<BlockPos> replaced = new ArrayList<>(
                    working.size() - original.size() + candidate.size());
            replaced.addAll(working.subList(0, startIndex));
            replaced.addAll(candidate);
            replaced.addAll(working.subList(endIndex + 1, working.size()));
            working = replaced;
            repaired++;
            logLocalSearchDiag("river-accepted");
            LOGGER.info("Accepted local RoadWeaver river detour: window={} conflictEdge={}..{} nodes={} -> {}",
                    margin, conflict.start(), conflict.end(), original.size(), candidate.size());
        }
        return List.copyOf(working);
    }

    private static BlockPos conflictCenter(List<BlockPos> path, Violation conflict) {
        if (path == null || conflict == null || path.isEmpty()) return null;
        int start = Math.max(0, Math.min(path.size() - 1, conflict.start()));
        int end = Math.max(0, Math.min(path.size() - 1, conflict.end()));
        BlockPos first = path.get(start);
        BlockPos last = path.get(end);
        if (first == null) return last;
        if (last == null) return first;
        return new BlockPos(
                Math.floorDiv(first.getX() + last.getX(), 2),
                Math.floorDiv(first.getY() + last.getY(), 2),
                Math.floorDiv(first.getZ() + last.getZ(), 2));
    }

    /**
     * Same connection + same conflict circle + already-prepared snapshot →
     * reuse the completed future. Local repair calls this once per detour
     * radius and lake cycle; without the cache every repeat re-ran the
     * circle's structure discovery. Mirrors the path-based dedup in
     * RoadPathCalculator.refreshPlanningSnapshot.
     */
    private record CircleSnapshotEntry(long centerKey, int radius,
                                       java.util.concurrent.CompletableFuture<Void> future) {}

    private static final java.util.concurrent.ConcurrentHashMap<net.shiroha233.roadweaver.planning.path.PlannedPathKey, CircleSnapshotEntry>
            CIRCLE_SNAPSHOT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Generous bound on one snapshot-prediction wait (10× the sampling wait, 5s..120s). */
    private static long snapshotWaitTimeoutMs() {
        try {
            long samplingTimeoutMs = Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
            return Math.max(5_000L, Math.min(120_000L, samplingTimeoutMs * 10L));
        } catch (Throwable ignored) {
            return 30_000L;
        }
    }

    private static void awaitLocalStructureCircle(ServerLevel level,
                                                   StructureConnection connection,
                                                   BlockPos center,
                                                   int radius) {
        if (connection == null || center == null) return;
        // Cache check happens BEFORE any probe construction so an already
        // prepared circle (exact, or a strictly larger circle covering this
        // one) never re-runs structure discovery — the probe budget must not
        // grow with every detour radius escalation.
        var key = net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection);
        int effectiveRadius = clampCircleProbeRadius(Math.max(1, radius));
        CircleSnapshotEntry cached = CIRCLE_SNAPSHOT_CACHE.get(key);
        if (cached != null && cached.future().isDone()
                && !cached.future().isCompletedExceptionally()
                && circleCoveredBy(cached, center, effectiveRadius)) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture<Void> future =
                    ensurePlanningSnapshotForCircleAsync(
                            level, connection, center, effectiveRadius);
            if (future != null) {
                long centerKey = center.asLong();
                future.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        CIRCLE_SNAPSHOT_CACHE.put(key,
                                new CircleSnapshotEntry(centerKey, effectiveRadius, future));
                    }
                });
                // Bounded wait: predictions run on the dedicated RW-Snapshot
                // pool and always complete, so this only fires if that lane is
                // wedged. Failing the route (topology fallback) beats hanging
                // the road worker forever.
                future.get(snapshotWaitTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new java.util.concurrent.CancellationException(
                    "local structure circle prediction timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException(
                    "interrupted while preparing local structure circle");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("local structure circle preparation failed", cause);
        }
    }

    /** True when the cached completed snapshot circle covers the requested one. */
    private static boolean circleCoveredBy(CircleSnapshotEntry cached,
                                           BlockPos center,
                                           int radius) {
        long dx = BlockPos.getX(cached.centerKey()) - center.getX();
        long dz = BlockPos.getZ(cached.centerKey()) - center.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        // 8 blocks of grid tolerance absorb conflict-centre drift between
        // successive repair iterations on the same corridor.
        return distance + radius <= cached.radius() + 8;
    }

    public static boolean intersectsRiver(List<BlockPos> path,
                                           int roadWidth,
                                           StreamsReflowingCompat.SamplingSession streams) {
        if (path == null || path.isEmpty() || streams == null
                || !streams.isAvailable()) return false;
        Map<Long, Boolean> cache = new HashMap<>();
        RiverLookup channels = (x, z) -> cache.computeIfAbsent(
                key(x, z), ignored -> {
                    StreamsReflowingCompat.RiverColumn column = streams.sample(x, z);
                    return column.isUnknown() || column.inChannel();
                });
        return findFirstRiverConflict(path, Math.max(1, roadWidth), channels) != null;
    }

    public static boolean intersectsLake(List<BlockPos> path,
                                         int roadWidth,
                                         StreamsReflowingCompat.SamplingSession streams) {
        if (path == null || path.isEmpty() || streams == null
                || !streams.isAvailable()) return false;
        Map<Long, Boolean> lakeCache = new HashMap<>();
        LakeLookup lakes = (x, z) -> lakeCache.computeIfAbsent(
                key(x, z), ignored -> {
                    StreamsReflowingCompat.LakeColumn column = streams.sampleLake(x, z);
                    return column.isUnknown() || column.present();
                });
        return findFirstLakeConflict(path, Math.max(1, roadWidth), lakes) != null;
    }

    public static boolean intersectsLakeConstraints(List<BlockPos> path,
                                                     int roadWidth,
                                                     AccurateHeightSampler sampler,
                                                     StreamsReflowingCompat.SamplingSession streams) {
        if (path == null || path.isEmpty() || sampler == null
                || streams == null || !streams.isAvailable()) return false;
        Map<Long, Boolean> lakeCache = new HashMap<>();
        LakeLookup lakes = (x, z) -> lakeCache.computeIfAbsent(
                key(x, z), ignored -> {
                    StreamsReflowingCompat.LakeColumn column = streams.sampleLake(x, z);
                    return column.isUnknown() || column.present();
                });
        return findFirstLakeConstraintConflict(
                path, Math.max(1, roadWidth), lakes, sampler::surfaceHeight) != null;
    }

    private static int localWindowRise() {
        return ConfigService.get().planning().heightTunnelThresholdBlocks();
    }

    private static boolean riverRepairBypassPrecheck() {
        try {
            return ConfigService.get().planning().riverRepairBypassPrecheck();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 0.8.316：河道是否把局部窗口一分为二。
     *
     * <p>做法：沿窗口边界（矩形周长，步长 1）采样河道列，数出边界上的河道段数。
     * 段数 ≥2 说明河道横穿整个窗口；整圈全湿说明窗口泡在水里；两种情况下
     * "候选路径不触碰任何河道列"都不可能成立，修复注定失败。</p>
     *
     * <p>只做判定，不改变任何路径：跳过的是本来就会失败的那次搜索。</p>
     */
    private static boolean channelSeparatesWindow(Bounds bounds, RiverLookup channels) {
        int spanX = bounds.maxX() - bounds.minX();
        int spanZ = bounds.maxZ() - bounds.minZ();
        if (spanX <= 0 || spanZ <= 0) return false;
        List<Boolean> ring = new ArrayList<>(2 * (spanX + spanZ));
        for (int x = bounds.minX(); x < bounds.maxX(); x++) ring.add(channels.isChannel(x, bounds.minZ()));
        for (int z = bounds.minZ(); z < bounds.maxZ(); z++) ring.add(channels.isChannel(bounds.maxX(), z));
        for (int x = bounds.maxX(); x > bounds.minX(); x--) ring.add(channels.isChannel(x, bounds.maxZ()));
        for (int z = bounds.maxZ(); z > bounds.minZ(); z--) ring.add(channels.isChannel(bounds.minX(), z));
        int size = ring.size();
        if (size == 0) return false;
        int wet = 0;
        int runs = 0;
        for (int index = 0; index < size; index++) {
            boolean current = ring.get(index);
            if (current) wet++;
            boolean previous = ring.get((index + size - 1) % size);
            if (current && !previous) runs++;
        }
        if (wet == 0) return false;
        if (wet == size) return true;
        if (runs >= 2) return true;
        // 只剩一段河道：只有它几乎绕满一圈（窗口整个泡在水里）才算无解。
        return wet * 10 >= size * 9;
    }

    private static int heightRepairContextBlocks() {
        return ConfigService.get().planning().heightRepairContextBlocks();
    }

    private static int tunnelDepthBlocks() {
        return ConfigService.get().planning().tunnelDepthBlocks();
    }

    /**
     * 诊断日志：坡度违反窗口里"规划 Y"与"精采样地表高度"的偏差。
     *
     * <p>用来区分两种根因（决定后续修法）：</p>
     * <ul>
     *   <li>偏差小（≤2 格）→ 规划期粗高度精度不足，剖面从源头就偏，值得提高采样精度；</li>
     *   <li>偏差大（≥8 格）→ 真实陡壁 / 隧道桥边界跳变，提高采样精度没用，应把陡壁
     *       主动规划成坡道/隧道。</li>
     * </ul>
     */
    private static void logGradeDiagnostics(List<BlockPos> path, Violation violation,
                                            AccurateHeightSampler sampler) {
        if (path == null || violation == null || sampler == null) return;
        int start = Math.max(0, violation.start() - 2);
        int end = Math.min(path.size() - 1, violation.end() + 2);
        StringBuilder planned = new StringBuilder();
        StringBuilder terrain = new StringBuilder();
        int maxDelta = 0;
        for (int i = start; i <= end; i++) {
            BlockPos pos = path.get(i);
            int surface;
            try {
                surface = sampler.surfaceHeight(pos.getX(), pos.getZ());
            } catch (Throwable unavailable) {
                surface = Integer.MIN_VALUE;
            }
            if (surface != Integer.MIN_VALUE) {
                maxDelta = Math.max(maxDelta, Math.abs(pos.getY() - surface));
            }
            if (planned.length() > 0) {
                planned.append(',');
                terrain.append(',');
            }
            planned.append(pos.getY());
            terrain.append(surface == Integer.MIN_VALUE ? "?" : Integer.toString(surface));
        }
        LOGGER.info("RoadWeaver grade violation diagnostics: rise={} over={} window={}..{} "
                        + "maxDeltaVsTerrain={} plannedY=[{}] terrainY=[{}]",
                violation.rise(), format(violation.length()), violation.start(), violation.end(),
                maxDelta, planned, terrain);
    }

    static Violation findFirstViolation(List<BlockPos> path) {
        if (path == null || path.size() < 2) return null;
        int size = path.size();
        int windowRise = localWindowRise();
        for (int i = 1; i < size; i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            double length = horizontalDistance(previous, current);
            int rise = Math.abs(current.getY() - previous.getY());
            // A vertical change below the cut threshold is a normal terrain
            // cut, not a grade violation. Only a >10-block step requires a
            // tunnel instead of a surface cut.
            if (length > 0.001D && rise > windowRise) {
                return new Violation(i - 1, i, rise, length);
            }
        }
        // F 阶段：O(n) 滑窗。旧实现是两层循环 O(n·W)，被反复调用（真机单条路
        // road_grade_search 卡 95 秒的一部分）。语义必须完全一致：
        // 对每个 start 找"第一个累计水平距离 >= LOCAL_WINDOW_BLOCKS 的 end"，
        // 若 |y[end]-y[start]| > localWindowRise() 就返回 (start, end, rise, length)。
        // 判定只看两个端点，所以内层累加可以整体换成前缀和 + 单调不减的 end 指针
        // （prefix 非减 ⇒ end(start) 随 start 单调不减），每次 O(1) 摊还。
        int[] y = new int[size];
        for (int i = 0; i < size; i++) y[i] = path.get(i).getY();
        double[] prefix = new double[size];
        for (int i = 1; i < size; i++) {
            prefix[i] = prefix[i - 1] + horizontalDistance(path.get(i - 1), path.get(i));
        }
        int end = 1;
        for (int start = 0; start < size - 2; start++) {
            if (end < start + 1) end = start + 1;
            while (end < size && prefix[end] - prefix[start] + 0.001D < LOCAL_WINDOW_BLOCKS) {
                end++;
            }
            if (end >= size) break;
            int rise = Math.abs(y[end] - y[start]);
            if (rise > windowRise) {
                return new Violation(start, end, rise, prefix[end] - prefix[start]);
            }
        }
        return null;
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config) {
        return findLocalRoute(original, sampler, config, SEARCH_MARGIN_BLOCKS, EdgeObstacle.NONE,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, EdgeObstacle.NONE,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle,
                Math.max(1, config.effectiveAStarStep()), false, 0, null, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step, false, 0,
                null, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, null, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, Integer.MIN_VALUE);
    }

    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, null);
    }

    /** Local 3D route search bounded by a circular conflict-centered radius. */
    private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter) {
        return findLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, conflictCenter, false);
    }

    /**
     * 0.8.326：{@code localStructureDetour=true} 时给隧道搜索用"普通搜索预算"
     * （{@link #repairWindowStateLimit()}，默认 9 万，可配置）而不是 24 万的
     * 放大上限。真机 20260909 16.15 存档 {@code (288,-256)->(880,208)} 的局部修复
     * 重生成阶段，三次结构绕行的 tunnel 搜索各花 20~29 秒且全部 {@code no_path}，
     * 白烧约 74 秒/局（该阶段总耗时 91 秒）。
     */
    /**
     * 0.9.050（D-10）：走廊附近有没有"湿列"。
     *
     * <p>只有为真时陆路闸门那一趟 A* 才有意义 —— 无水时它的结果与放开水面完全等价
     * （这是 0.9.011 自己写在注释里的论断）。逐站采样走 {@code sampler} 缓存，
     * 代价 O(站数)，远小于一整趟 A*（单次预算上限 20,000 ms）。</p>
     */
    private static boolean corridorTouchesWater(List<BlockPos> original, AccurateHeightSampler sampler) {
        if (original == null || original.size() < 2 || sampler == null) return true;   // 判不了一律跑，宁慢勿错
        List<BlockPos> requests = new ArrayList<>(original.size());
        for (BlockPos pos : original) {
            if (pos != null) requests.add(pos);
        }
        if (requests.isEmpty()) return true;
        Map<Long, AccurateHeightSample> samples = sampler.samplePositions(requests);
        for (BlockPos pos : requests) {
            if (isWetColumn(samples.get(AccurateHeightSample.key(pos.getX(), pos.getZ())))) return true;
        }
        return false;
    }
private static List<BlockPos> findLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter,
                                                  boolean localStructureDetour) {
        // 0.9.011：陆路闸门。先只走陆路（湿列整列禁行）；只要绕行长度不超过直连距离的
        // landDetourFactor 倍，就直接采用它 —— 这就是「河边走没事，只有实在绕行太远才架桥」。
        // 没有水的时候这一趟的结果与放开水面完全相同，所以不产生额外的搜索代价。
        double gateFactor = 1.6D;
        try {
            gateFactor = Math.max(0.0D, Math.min(10.0D,
                    ConfigService.get().planning().landDetourFactor()));
        } catch (Throwable ignored) { }
        if (gateFactor > 0.0D && corridorTouchesWater(original, sampler)) {
            // 0.9.050（D-10）：只在走廊附近确实有水时才跑这一趟。
            // 上面那段注释自己写着「没有水的时候这一趟的结果与放开水面完全相同，所以不产生
            // 额外的搜索代价」—— 但代码里是无条件跑的，等于每个干窗口白跑一整趟 A*。
            // 真机证据：一局 24 次判定累计 pass1Ms = 116,822 ms，其中绝大多数窗口本来就无水。
            long gateStartedAt = System.nanoTime();
            LAST_SEARCH_BUDGET_EXHAUSTED.set(false);
            List<BlockPos> land = searchLocalRoute(original, sampler, config, searchMarginBlocks,
                    obstacle, step, allowTunnel, structureClearance, conflictBox, tunnelFloorY,
                    conflictCenter, localStructureDetour, true);
            double direct = horizontalDistance(original.get(0), original.get(original.size() - 1));
            // 基准取两支的较松者，缺一不可：
            //  ① 相对直连距离 —— 「绕行」的直观含义，direct 给 8 格下限防退化窗口；
            //  ② 不比原走廊更长（×1.05 容差）—— findLocalRoute 是**局部修复**，原走廊本身
            //     可能就是之字形（长度约 3× 直连）。只看直连会把「完美的陆路修复」误判成
            //     「绕太远」，于是又放开水面、桥照样冒出来，这次改动就白做了。
            double landCap = landDetourCap(direct, routeLength(original), gateFactor);
            long gateNanos = System.nanoTime() - gateStartedAt;
            String verdict;
            double landLength = -1.0D;
            if (land == null) {
                if (Boolean.TRUE.equals(LAST_SEARCH_BUDGET_EXHAUSTED.get())) {
                    // 0.9.069（D-11a）：陆路是「没搜完」（预算耗尽），不是「真不通」。
                    // 放开水域会 fail-open 到架桥（慢机/预算小的时候尤其爱乱架桥）。
                    // 这里直接返回 null，让上层 keepCandidateOnNull 保留原走廊。
                    recordWaterGateDiag("budget-exhausted", -1.0D, direct, gateFactor, gateNanos);
                    return null;
                }
                verdict = "no-land-route";
            } else {
                landLength = routeLength(land);
// 0.9.054（D-15）：把竖向爬升折算进比较，两边用**同一个系数** ——
                // 爬升相近时结果恒等于原来的纯水平比较，只有「陆路要爬的崖明显多于原走廊」
                // 才会被拒。这样既堵住「爬 25 格崖换 4 格水平」的漏洞，又不改常态行为。
                double landMetric = landLength + routeClimb(land) * VERTICAL_EQUIV_BLOCKS;
                double landCapMetric = landCap + routeClimb(original) * VERTICAL_EQUIV_BLOCKS;
                if (landMetric <= landCapMetric) {
                    recordWaterGateDiag("land", landLength, direct, gateFactor, gateNanos);
                    return land;
                }
                verdict = "detour-too-long";
            }
            recordWaterGateDiag(verdict, landLength, direct, gateFactor, gateNanos);
        }
        return searchLocalRoute(original, sampler, config, searchMarginBlocks, obstacle, step,
                allowTunnel, structureClearance, conflictBox, tunnelFloorY, conflictCenter,
                localStructureDetour, false);
    }

    /** 0.9.011：单趟 A* 本体。{@code wetBlocked=true} 时湿列整列禁行，供陆路闸门使用。 */
    private static List<BlockPos> searchLocalRoute(List<BlockPos> original,
                                                  AccurateHeightSampler sampler,
                                                  PathfindingCostConfig config,
                                                  int searchMarginBlocks,
                                                  EdgeObstacle obstacle,
                                                  int step,
                                                  boolean allowTunnel,
                                                  int structureClearance,
                                                  BoundingBox conflictBox,
                                                  int tunnelFloorY,
                                                  BlockPos conflictCenter,
                                                  boolean localStructureDetour,
                                                  boolean wetBlocked) {
        step = Math.max(1, step);
        LAST_LOCAL_SEARCH_DIAG.remove();
        BlockPos start = original.get(0);
        BlockPos goal = original.get(original.size() - 1);
        int radius = Math.max(step, searchMarginBlocks);
        Bounds bounds = conflictCenter == null
                ? Bounds.around(original, radius, step, conflictBox)
                : Bounds.aroundConflict(conflictCenter, radius, step, start, goal, conflictBox);
        Collection<BlockPos> requests = conflictCenter == null
                ? bounds.positions(step)
                : bounds.positionsWithinRadius(step, conflictCenter, radius);
        if (conflictCenter != null) {
            // The local detour must always have usable entry and exit samples.
            // They may lie just outside the circle after grid snapping, but
            // remain part of the original local corridor and must not make a
            // valid red-style bypass fail before A* starts.
            Map<Long, BlockPos> requestMap = new LinkedHashMap<>();
            for (BlockPos request : requests) {
                requestMap.put(key(request.getX(), request.getZ()), request);
            }
            if (bounds.contains(start.getX(), start.getZ())) {
                requestMap.put(key(start.getX(), start.getZ()), start);
            }
            if (bounds.contains(goal.getX(), goal.getZ())) {
                requestMap.put(key(goal.getX(), goal.getZ()), goal);
            }
            requests = requestMap.values();
        }
        if (requests.isEmpty()) return null;
        int expandedStateLimit = expandedStateLimit(requests.size(), allowTunnel, step, localStructureDetour);
        long searchStartedAt = System.nanoTime();
        // 0.9.001：状态数预算之外再加一条墙钟预算。只靠 expandedStateLimit 时，
        // 跨 90 格深谷这种窗口每次都把 90 000 展开跑满、空转 95 秒以上再返回无解
        // （真机 road_grade_search 卡顿主因，三波重试各跑一次）。两者谁先到谁生效。
        long searchDeadlineNanos = searchStartedAt + repairWindowBudgetNanos();
        if (wetBlocked) {
            // 0.9.014：陆路闸门这一趟只负责回答「陆路到底通不通」，不该跑满全预算。
            // 真机实测（低挡位一局）：24 次判定累计 pass1Ms=116,822，其中 12 次
            // verdict=no-land-route 全部把预算跑满才失败（最慢单次 20 s）；而那一局的
            // 最终道路几何与不设闸门时逐字节一致（两局 slice 计数器都是
            // bridge=348 tunnel=45）⇒ 这 117 秒是纯亏。
            // 砍到 1/4：失败的判定 4 倍快地放手；成功的那些 landLen 只有 100~300 格，
            // 本来就用不到预算的零头，路径结果不变。
            // 0.9.051（D-11）：原来这里各套了一个下限 Math.max(2_000, …) / Math.max(50_000_000L, …)，
            // 但它们**永远不可能生效**：
            //   · expandedStateLimit 的下限是 repairWindowStateLimit() 里的 20_000 ⇒ /4 = 5_000 > 2_000；
            //   · repairWindowBudgetNanos() 的下限是 1_000 ms = 1e9 ns ⇒ /4 = 2.5e8 ns > 5e7 ns。
            // 删掉两个死下限，真实有效下限写在这里：**5 000 个展开状态 / 250 ms**。
            expandedStateLimit = expandedStateLimit / 4;
            searchDeadlineNanos = searchStartedAt + repairWindowBudgetNanos() / 4L;
        }
        // 0.9.009 / 0.9.011：临河与过水代价参数（每搜索读一次配置，不在展开循环里读）
        double waterBase = 200.0D;
        double waterPerStation = 150.0D;
        double riverPenalty = 8.0D;
        int riverRadius = 5;
        double culvertPerStation = 2.0D;
        int narrowWaterMax = 20;
        double narrowWaterPer = 20.0D;
        double wideBase = 600.0D;
        double widePer = 80.0D;
        boolean waterGate = true;
        try {
            var plan = ConfigService.get().planning();
// 0.9.051（D-18）：这两个键（baseWaterPenalty / perStationWaterPenalty）在
            // **闸门开启（默认）时完全不参与计价** —— 它们只在 :1515 的 !waterGate 分支里
            // 被赋回 wideBase/widePer。也就是说配置界面里改它们，默认态下没有任何效果。
            waterBase = Math.max(0.0D, Math.min(100_000.0D, plan.baseWaterPenalty()));
            waterPerStation = Math.max(0.0D, Math.min(100_000.0D, plan.perStationWaterPenalty()));
            riverPenalty = Math.max(0.0D, Math.min(100_000.0D, plan.riverProximityPenalty()));
            riverRadius = Math.max(0, Math.min(32, plan.riverProximityRadius()));
            culvertPerStation = Math.max(0.0D, Math.min(100.0D, plan.culvertCostPerStation()));
            narrowWaterMax = Math.max(0, Math.min(256, plan.narrowWaterMaxWidth()));
            narrowWaterPer = Math.max(0.0D, Math.min(100_000.0D, plan.narrowWaterPerStation()));
            wideBase = Math.max(0.0D, Math.min(100_000.0D, plan.wideWaterBase()));
            widePer = Math.max(0.0D, Math.min(100_000.0D, plan.wideWaterPerStation()));
            waterGate = plan.landDetourFactor() > 0.0D;
        } catch (Throwable ignored) { }
        // 闸门关闭 ⇒ 完整回到 0.9.010 口径：宽水用 base/per，窄水档不启用。
        if (!waterGate) {
            wideBase = waterBase;
            widePer = waterPerStation;
            narrowWaterMax = 0;
        }
        int culvertMaxStations = 6;
        int culvertMaxWaterDepth = 2;
        try {
            var look = ConfigService.get().roadAppearance();
culvertMaxStations = Math.max(0, Math.min(16, look.culvertMaxStations()));
            // 0.9.052（D-13）：culvertEnabled=false 时**施工端不建涵洞**（Road.java:353 才读它），
            // 但寻路这一侧从来没读过这个开关 —— 于是 ≤6 站浅水仍按涵洞价（2/站）定价，
            // A* 认为"直接过很便宜"就选穿越，施工阶段却铺普通路面、或被 FinalRoadWaterValidator 打回。
            // 把档位宽度置 0 即彻底关掉涵洞档，价格自然落到窄水/宽水，选线与施工口径重新一致。
            if (!look.culvertEnabled()) {
                culvertMaxStations = 0;
            }
            culvertMaxWaterDepth = Math.max(1, Math.min(4, look.culvertMaxWaterDepth()));
        } catch (Throwable ignored) { }
        // R7（0.9.003，0.9.059 收紧）：长度上界可证明剪枝。
        // 旧界 max(原走廊长+200, 原走廊长×3.0) 比真机那条 3.63 倍绕行还松，等于没剪；
        // 现在换成 max(直连×1.6, 原走廊长×1.05)（见 lengthCapFor 的等价性/剪枝安全性说明）。
        // 任何经过某节点的解，其**返回路径长度**下界为 g_len + hypot(到目标) - 两端网格吸附偏差，
        // 一旦该下界已超过上界，这个解必然被拒 ⇒ 剪掉不改变"≤ cap 的解"的结果集。
        double chord = chordLength(original);
        double originalLength = routeLength(original);
        // 0.9.062（①）：factor / cap 要等窗口网格采完之后才能定 —— steep 判据要用那张网格的高度。
        double lengthBound;
        // 两端偏差各 <= step*sqrt(2)，取 4*step (> 2.83*step) 做安全余量。
        double lengthSlack = 4.0D * step;
        boolean lengthBoundPrune = lengthBoundPruneEnabled();
        int prunedByLength = 0;
        Map<Long, AccurateHeightSample> samples = sampler.samplePositions(requests);
        Map<Long, Integer> heights = new HashMap<>(samples.size() * 2);
        for (BlockPos request : requests) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(request.getX(), request.getZ()));
            if (sample != null) {
                heights.put(key(request.getX(), request.getZ()), sampler.surfaceHeight(sample));
            }
        }
        // 0.9.062（①）：策略感知的长度界。判据只读上面这张**已经采过**的窗口网格（heights map），
        // 不发起任何新的地形查询；弦被圆心窗口截断时采不到足够的点 ⇒ steep=false ⇒ 仍是 1.6。
        boolean steepChord = steepLengthCapEnabled()
                && chordIsSteep(chordProfile(heights, start, goal, bounds.minX(), bounds.minZ(), step),
                chordSampleSpacing(start, goal, step));
        double lengthFactor = detourFactorFor(steepChord);
        lengthBound = lengthCapFor(chord, originalLength, steepChord);
        // 0.9.009：窗口内一次性建「到最近湿列」的距离场（两遍 chamfer，O(窗口面积)），
        // 之后每次邻居展开只做一次数组读 —— 不做逐邻居查河，expanded 不会因此上涨。
        int[] riverDistanceField = riverPenalty > 0.0D && riverRadius > 0
                ? buildRiverDistanceField(bounds, step, samples) : null;
        int riverDistanceGridW = riverDistanceField == null ? 0
                : (bounds.maxX() - bounds.minX()) / step + 1;

        int startX = nearestGrid(start.getX(), bounds.minX(), step);
        int startZ = nearestGrid(start.getZ(), bounds.minZ(), step);
        int goalX = nearestGrid(goal.getX(), bounds.minX(), step);
        int goalZ = nearestGrid(goal.getZ(), bounds.minZ(), step);
        Integer startY = heights.get(key(startX, startZ));
        Integer goalY = heights.get(key(goalX, goalZ));
        if (startY == null || goalY == null) return null;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<StateKey, Double> best = new HashMap<>();
        Node first = new Node(startX, startY, startZ, -1, 0.0D, 0.0D, 0,
                heuristic(startX, startZ, goalX, goalZ), null);
        open.add(first);
        best.put(first.key(), 0.0D);
        int expanded = 0;
        while (!open.isEmpty() && expanded < expandedStateLimit
                && System.nanoTime() < searchDeadlineNanos) {
            Node current = open.poll();
            Double known = best.get(current.key());
            if (known == null || current.cost() > known + 0.0001D) continue;
            if (current.x() == goalX && current.z() == goalZ) {
                List<BlockPos> route = reconstruct(current);
                route.set(0, start);
                route.set(route.size() - 1, goal);
                recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                        localStructureDetour, searchStartedAt, true, false, prunedByLength);
                // 0.9.059（③）：长度上界诊断（cap 与这条路的实际长度、弦长同框）。
                // 0.9.062（①）：steep/factor 是这一趟真正用的那两个数。
                recordLengthCapDiag(lengthBound, lengthFactor, steepChord, chord, originalLength,
                        routeLength(route), prunedByLength, expanded, true);
                return route;
            }
            expanded++;
            if ((expanded & 2047) == 0) {
                // 0.9.001：原循环体内完全没有中断检查 —— 建路 worker 被取消后这条 A*
                // 仍会跑满 90 000 次展开（真机 95 秒）。语义与同文件
                // prepareLocalStructureCircle 的处理一致：立刻转成 CancellationException。
                if (Thread.currentThread().isInterrupted()) {
                    recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                            localStructureDetour, searchStartedAt, false, false, prunedByLength);
                    throw new java.util.concurrent.CancellationException(
                            "interrupted during local grade repair search");
                }
                InitialGenerationProgressTracker.advanceCurrentRoadTask(
                        240 + (int) Math.min(280L, expanded * 280L / expandedStateLimit),
                        "road_grade_search");
            }
            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                int nextX = current.x() + DIRECTIONS[direction][0] * step;
                int nextZ = current.z() + DIRECTIONS[direction][1] * step;
                if (!bounds.contains(nextX, nextZ)) continue;
                if (conflictCenter != null
                        && horizontalDistanceSquared(nextX, nextZ, conflictCenter.getX(), conflictCenter.getZ())
                        > (long) radius * radius) continue;
                Integer terrainY = heights.get(key(nextX, nextZ));
                if (terrainY == null) continue;
                // 0.9.011：陆路闸门那一趟把湿列整列禁行。端点例外 —— 它们本来就在岸上，
                // 而且后面会被强制吸附到 startY / goalY。
                if (wetBlocked
                        && !(nextX == goalX && nextZ == goalZ)
                        && !(nextX == startX && nextZ == startZ)
                        && isWetColumn(samples.get(AccurateHeightSample.key(nextX, nextZ)))) {
                    continue;
                }
                int[] candidateYs = allowTunnel
                        ? tunnelHeights(terrainY, step, tunnelFloorY) : new int[]{terrainY};
                for (int nextY : candidateYs) {
                    if (nextX == goalX && nextZ == goalZ) nextY = goalY;
                    if (nextX == startX && nextZ == startZ) nextY = startY;
                double horizontal = Math.hypot(nextX - current.x(), nextZ - current.z());
                int rise = Math.abs(nextY - current.y());
                if (rise > Math.floor(horizontal)) continue;
                // R7：长度上界剪枝放在最贵的 edgeIntersectsProtected / obstacle.blocked 之前，
                // 只用一个已算好的启发值做比较。能到这里说明垂直与网格都已通过。
                double remaining = heuristic(nextX, nextZ, goalX, goalZ);
                double nextLength = current.length() + horizontal;
                if (lengthBoundPrune && nextLength + remaining - lengthSlack > lengthBound) {
                    prunedByLength++;
                    continue;
                }
                BlockPos currentPos = new BlockPos(current.x(), current.y(), current.z());
                BlockPos nextPos = new BlockPos(nextX, nextY, nextZ);
                if (edgeIntersectsProtected(currentPos, nextPos,
                        Math.max(0, structureClearance))
                        || obstacle.blocked(currentPos, nextPos)) continue;

                double grade = rise / Math.max(1.0D, horizontal);
                AccurateHeightSample nextSample = samples.get(AccurateHeightSample.key(nextX, nextZ));
                boolean wet = isWetColumn(nextSample);
                // (b) 过河代价按「连续水站数」递增，替换原来的固定 1200：3 步 650 / 50 步 7700。
                int nextWetRun = wet ? current.wetRun() + 1 : 0;
                // 过河总价 T(run)：run <= culvertMaxStations 且水深 <= culvertMaxWaterDepth 时
                // = run × culvertCostPerStation（3 站 6、6 站 12）；否则 = base + per × run
                // （7 站 1250、10 站 1700、50 站 7700）。A* 是增量代价，所以按 T 的「边际」收费：
                // 第 k 个湿步收 T(k) - T(k-1) ⇒ 跨过涵洞上限那一步会一次性补上断崖差额。
                int wetDepth = wet
                        ? Math.max(0, nextSample.worldSurfaceWg() - nextSample.oceanFloorWg())
                        : 0;
                double waterPenalty = 0.0D;
                if (wet) {
                    double total = waterCrossingCost(nextWetRun, wetDepth, wideBase, widePer,
                            culvertPerStation, culvertMaxStations, culvertMaxWaterDepth,
                            narrowWaterMax, narrowWaterPer, step);
                    double previous = nextWetRun > 1
                            ? waterCrossingCost(nextWetRun - 1, wetDepth, wideBase, widePer,
                                    culvertPerStation, culvertMaxStations, culvertMaxWaterDepth,
                                    narrowWaterMax, narrowWaterPer, step)
                            : 0.0D;
                    waterPenalty = Math.max(0.0D, total - previous);
                }
                // (a) 临河距离惩罚：距最近湿列越近越贵，在水里加满；O(1) 查距离场。
                double riverCost = riverProximityCost(
                        riverDistanceBlocks(riverDistanceField, riverDistanceGridW, bounds, step,
                                nextX, nextZ),
                        riverPenalty, riverRadius);
                double turnPenalty = current.direction() < 0 || current.direction() == direction ? 0.0D : 4.0D;
                double tunnelPenalty = Math.max(0, terrainY - nextY) * 2.5D;
                double nextCost = current.cost() + horizontal + rise * 8.0D + tunnelPenalty
                        + grade * grade * 90.0D + waterPenalty + riverCost + turnPenalty;
                StateKey state = new StateKey(nextX, nextY, nextZ, direction);
                Double old = best.get(state);
                if (old != null && old <= nextCost) continue;
                Node next = new Node(nextX, nextY, nextZ, direction, nextCost, nextLength,
                        nextWetRun, nextCost + remaining, current);
                best.put(state, nextCost);
                open.add(next);
                }
            }
        }
        // 退出 A* 的三条路径：open 空（确实到不了）、状态数预算耗尽、墙钟预算耗尽。
        // 后两者语义完全相同（一律返回 null，走既有「无解」兜底），只在诊断串里区分，
        // 便于真机验收「预算真的兜住了」而不是又跑满一遍。
        boolean budgetExhausted = expanded < expandedStateLimit && !open.isEmpty();
        LAST_SEARCH_BUDGET_EXHAUSTED.set(budgetExhausted);
        recordLocalSearchDiag(expanded, expandedStateLimit, allowTunnel,
                localStructureDetour, searchStartedAt, false, budgetExhausted, prunedByLength);
        recordLengthCapDiag(lengthBound, lengthFactor, steepChord, chord, originalLength, -1.0D,
                prunedByLength, expanded, false);
        return null;
    }

    /**
     * 0.9.059（③）：{@code [PLAN-LENGTH-CAP]} 诊断行。
     *
     * <p>只在"确实剪过节点"时打 INFO（前 40 次 + 之后每 200 次），否则降级为 DEBUG ——
     * 否则每条路每次局部搜索都打一行会把日志淹掉。{@code pathLen=-1} 表示这一趟没求出解。</p>
     */
    /**
     * 0.9.059（③）：{@code [PLAN-LENGTH-CAP]} 诊断行。
     *
     * <p>只在"确实剪过节点"时打 INFO（前 40 次 + 之后每 200 次），否则降级为 DEBUG ——
     * 否则每条路每次局部搜索都打一行会把日志淹掉。{@code pathLen=-1} 表示这一趟没求出解。</p>
     *
     * <p>0.9.062（①）：把当次**真正用的** {@code steep} 与 {@code factor} 一起打出来。
     * 没有这两个字段，验收时无法区分"界放宽了但没剪到"和"界根本没放宽"。</p>
     */
    private static void recordLengthCapDiag(double cap, double factor, boolean steep,
                                            double chord, double originalLength,
                                            double pathLength, int prunedNodes, int expanded,
                                            boolean found) {
        long seq = LENGTH_CAP_SEEN.incrementAndGet();
        long prunedTotal = LENGTH_CAP_PRUNED.addAndGet(Math.max(0, prunedNodes));
        String verdict = found ? "found" : "no_path";
        String factorText = String.format(java.util.Locale.ROOT, "%.2f", factor);
        if (prunedNodes > 0 && (seq <= 40L || seq % 200L == 0L)) {
            LOGGER.info("[PLAN-LENGTH-CAP] #{} steep={} factor={} cap={} prunedNodes={} pathLen={} chord={} "
                            + "originalLength={} expanded={} prunedTotal={} result={}",
                    seq, steep, factorText, fmt1(cap), prunedNodes,
                    pathLength < 0.0D ? "n/a" : fmt1(pathLength),
                    fmt1(chord), fmt1(originalLength), expanded, prunedTotal, verdict);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[PLAN-LENGTH-CAP] #{} steep={} factor={} cap={} prunedNodes={} pathLen={} chord={} "
                            + "originalLength={} expanded={} prunedTotal={} result={}",
                    seq, steep, factorText, fmt1(cap), prunedNodes,
                    pathLength < 0.0D ? "n/a" : fmt1(pathLength),
                    fmt1(chord), fmt1(originalLength), expanded, prunedTotal, verdict);
        }
    }

    private static String fmt1(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 0.8.326：局部搜索诊断（纯观测）。最后一次局部 A* 的展开数/预算/耗时。 */
    private static final ThreadLocal<String> LAST_LOCAL_SEARCH_DIAG = new ThreadLocal<>();

    /**
     * 0.8.327：局部结构绕行的隧道下界深度。
     *
     * <p>真机 20260909 19.53 存档实测：局部绕行把下界传成 {@code level.getMinBuildHeight()}
     * 时，{@code tunnelHeights} 会为每个邻居展开约 100 层垂直候选，
     * {@code expanded=90000 limit=90000} 仍要 20~28 秒且三次全部 {@code no_path}。
     * 绕建筑不需要挖到基岩，限制在冲突点下方 {@value #LOCAL_DETOUR_TUNNEL_DEPTH} 格内即可，
     * 垂直层数降到约 13 层。</p>
     */
    private static final int LOCAL_DETOUR_TUNNEL_DEPTH = 24;

    private static int localDetourTunnelFloor(ServerLevel level, BlockPos center) {
        int worldFloor = level == null ? 0 : level.getMinBuildHeight();
        int localFloor = (center == null ? 0 : center.getY()) - LOCAL_DETOUR_TUNNEL_DEPTH;
        return Math.max(worldFloor, localFloor);
    }

    private static void recordLocalSearchDiag(int expanded, int limit, boolean tunnel,
                                               boolean localDetour, long startedAt,
                                               boolean found, boolean budgetExhausted,
                                               int prunedByLength) {
        LAST_LOCAL_SEARCH_DIAG.set("expanded=" + expanded + " limit=" + limit
                + (tunnel ? " tunnel" : " surface")
                + (localDetour ? " localDetour" : "")
                + (found ? " found" : " no_path")
                + (budgetExhausted ? " budget=exhausted" : "")
                + " prune=length " + prunedByLength
                + " wallMs=" + (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * R7（0.9.003）：旧长度上界的加性容差常数（格）——<b>0.9.059 起已不参与任何判据</b>。
     *
     * <p>0.9.059（③）把 {@code lengthBoundFor} 的口径从
     * {@code max(原走廊长+200, 原走廊长×3.0)} 换成
     * {@code max(直连×1.6, 原走廊长×1.05)}，因此这个 200 的加性项与
     * {@code MAX_ROUTE_LENGTH_MULTIPLIER}(3.0) 一起从剪枝里退场，只留在
     * {@code isAccepted / isStructureRepairAccepted / isLakeRepairAccepted /
     * isRiverRepairAccepted} 这些<b>接受判据</b>里（它们才是"最终要不要采纳这条修复"的口径）。
     * 保留常量本身是为了让"当年为什么取最松一档"这段推理不丢。</p>
     */
    @SuppressWarnings("unused")
    private static final double LOCAL_REPAIR_ACCEPT_SLACK_BLOCKS = 200.0D;

    /**
     * 0.9.059（③）：长度上界的绕行系数。与 {@code PlanningConfig.landDetourFactor} 的
     * 默认值(1.6)同一口径，但<b>刻意做成常量、不读配置</b>：那个键可以被关成 0，
     * 而这里为 0 会让所有局部修复直接判 {@code no_path}（见 {@link #lengthCapFor}）。
     */
    static final double LENGTH_CAP_DETOUR_FACTOR = 1.6D;

    /**
     * 0.9.062（①）：直连弦"必须翻山"时放宽到的绕行系数。
     *
     * <p>为什么是 2.6：盘山路本身就是 2~3 倍绕远。一条之字路的水平长度约等于
     * 「竖向爬升 ÷ 实际坡度」；本工程实测站距 1.0 格/站、loose 档 14.29%，爬 30 格至少 210 格，
     * 翻一座 100 格弦高的山起步就是 1.5~2 倍。1.6 的界会把这类解在<b>搜索阶段</b>直接剪掉，
     * A* 只能在"硬爬吃罚"和"被剪成 no_path"之间二选一 —— 这就是"路斜着横切"的来源。
     * 2.6 落在"够之字用"与"仍然拒绝 3.63 倍那种乱绕"之间（旧界 3.0 已被 0.9.059 判为过松）。</p>
     */
    static final double LENGTH_CAP_DETOUR_FACTOR_STEEP = 2.6D;

    /** 0.9.062（①）：判「必须翻山」的单窗最大坡度阈值，取值理由见 {@link #chordIsSteep}。 */
    static final double STEEP_CHORD_MAX_GRADE = 0.25D;

    /** 0.9.062（①）：判「必须翻山」的总爬升/弦长阈值，推导见 {@link #chordIsSteep}。 */
    static final double STEEP_CHORD_CLIMB_RATIO = 0.20D;

    /** 0.9.062（①）：坡度判据的窗口宽度（格）—— 与 {@link #LOCAL_WINDOW_BLOCKS} 同口径。 */
    static final int STEEP_CHORD_WINDOW_BLOCKS = LOCAL_WINDOW_BLOCKS;

    /** 0.9.062（①）：弦有效采样点少于这么多个就不判（判不出来就回退 1.6，不许凭空放宽）。 */
    static final int STEEP_CHORD_MIN_SAMPLES = 5;

    /** 0.9.062（①）：弦采样点数上限（纯查表，只是兜底防护）。 */
    static final int STEEP_CHORD_MAX_SAMPLES = 256;

    /** 0.9.059（③）：{@code [PLAN-LENGTH-CAP]} 出现次数（节流用）。 */
    private static final java.util.concurrent.atomic.AtomicLong LENGTH_CAP_SEEN =
            new java.util.concurrent.atomic.AtomicLong();
    /** 0.9.059（③）：被长度上界剪掉的邻居节点累计数（只读观测）。 */
    private static final java.util.concurrent.atomic.AtomicLong LENGTH_CAP_PRUNED =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * R7：与 {@code isAccepted} 等判据同口径的可接受长度上界。
     *
     * <p>0.9.059（③）：口径从 {@code max(原走廊长+200, 原走廊长×3.0)} 收紧为
     * {@code max(直连×1.6, 原走廊长×1.05)}（{@link #lengthCapFor(double, double)}）。
     * 真机一局出现过"弦 1053 格的路走了 3826 格（detour 3.63）"—— 旧界（3 倍原走廊长）
     * 比它还松，A* 于是可以合法地跑出一条 3.63 倍绕行。新界的含义是
     * <b>"绕行不得超过直连的 1.6 倍；原走廊本身已经很长时，再给它 5% 的余量"</b>，
     * 与 {@link #landDetourCap(double, double, double)}（陆路闸门，factor 默认 1.6）同一口径。</p>
     */
    private static double lengthBoundFor(List<BlockPos> original) {
        return lengthCapFor(chordLength(original), routeLength(original));
    }

    /**
     * 0.9.059（③）：长度上界 = {@code max(直连 × factor, 原走廊长 × 1.05)}，{@code factor} 见
     * {@link #detourFactorFor(boolean)}。
     *
     * <p>{@code factor} 用常量而不是读配置 {@code landDetourFactor}：后者可以被关成 0（关闸门），
     * 一旦为 0 这里会把上界算成 0，于是每一次局部修复都会被"必然超界"剪成 {@code no_path} ——
     * 那不是收紧，那是断线。</p>
     *
     * <p>可证的性质：A* 的启发值是直线距离（admissible），所以任何一条总长 ≤ cap 的解，
     * 它路径上每个节点的 {@code g + h} 都 ≤ 该解总长 ≤ cap，<b>不会命中剪枝</b>；
     * 被剪掉的只可能是总长 > cap 的解。</p>
     *
     * <p>0.9.062（①）：{@code factor} 变成策略感知。这个两参版本恒用 1.6，与 0.9.061 逐位相同
     * （既有回归直接钉它）；策略感知的那一支见 {@link #lengthCapFor(double, double, boolean)}。</p>
     */
    static double lengthCapFor(double chord, double originalLength) {
        return landDetourCap(chord, originalLength, LENGTH_CAP_DETOUR_FACTOR);
    }

    /**
     * 0.9.062（①）：策略感知的长度上界。
     * {@code steep=false} 时与两参版本逐位相同（硬约束：判据不成立就必须回到 1.6）。
     */
    static double lengthCapFor(double chord, double originalLength, boolean steep) {
        return landDetourCap(chord, originalLength, detourFactorFor(steep));
    }

    /**
     * 0.9.062（①）：{@code steep} 对应的绕行系数。
     *
     * <p>非陡地形恒为 {@link #LENGTH_CAP_DETOUR_FACTOR}(1.6)；陡地形才用
     * {@link #LENGTH_CAP_DETOUR_FACTOR_STEEP}(2.6)。做成纯函数，是为了让
     * {@code [PLAN-LENGTH-CAP]} 打出来的 factor 与真正参与剪枝的那个数必然同源。</p>
     */
    static double detourFactorFor(boolean steep) {
        return steep ? LENGTH_CAP_DETOUR_FACTOR_STEEP : LENGTH_CAP_DETOUR_FACTOR;
    }

    /** 0.9.059（③）：给定弦长/原走廊长，某个候选走线长度是否落在上界内（单测直接钉这个）。 */
    static boolean withinLengthCap(double chord, double originalLength, double pathLength) {
        return withinLengthCap(chord, originalLength, pathLength, false);
    }

    /** 0.9.062（①）：策略感知版本的接受判据。 */
    static boolean withinLengthCap(double chord, double originalLength, double pathLength,
                                   boolean steep) {
        if (pathLength <= 0.0D) return false;
        return pathLength <= lengthCapFor(chord, originalLength, steep) + 1.0E-6D;
    }

    /** 起点到终点的水平直线距离（"弦"）。与 {@link #routeLength(List)} 同口径，只算水平分量。 */
    static double chordLength(List<BlockPos> path) {
        if (path == null || path.size() < 2) return 0.0D;
        BlockPos first = path.get(0);
        BlockPos last = path.get(path.size() - 1);
        if (first == null || last == null) return 0.0D;
        return Math.hypot(last.getX() - first.getX(), last.getZ() - first.getZ());
    }

    /**
     * 0.9.062（①）：{@code -Droadweaver.steepLengthCap=false}（也认 0/off）时长度界回到
     * 0.9.061 的唯一口径（恒 1.6）。默认开。
     */
    static boolean steepLengthCapEnabled() {
        String raw = System.getProperty(RoadConstants.STEEP_LENGTH_CAP_PROPERTY);
        if (raw == null) return true;
        String value = raw.trim();
        if (value.isEmpty()) return true;
        return !(value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("off"));
    }

    /**
     * 0.9.062（①）：弦的采样点数（按网格步长 {@code step} 走完整条弦）。
     * 超过 {@link #STEEP_CHORD_MAX_SAMPLES} 时截断 —— 只是查表，截断只为兜底。
     */
    private static int chordSamples(double chord, int step) {
        if (!(chord > 0.0D) || step < 1) return 0;
        return Math.max(0, Math.min((int) Math.round(chord / step), STEEP_CHORD_MAX_SAMPLES));
    }

    /** 0.9.062（①）：弦采样点之间的水平间距（格）。采样点不够时返回 0（=判不出来）。 */
    static double chordSampleSpacing(BlockPos start, BlockPos goal, int step) {
        if (start == null || goal == null || step < 1) return 0.0D;
        double chord = Math.hypot(goal.getX() - start.getX(), goal.getZ() - start.getZ());
        int samples = chordSamples(chord, step);
        return samples <= 0 ? 0.0D : chord / samples;
    }

    /**
     * 0.9.062（①）：沿直连弦取一串<b>地形</b>高度，供 {@link #chordIsSteep} 判定。
     *
     * <p><b>不发起任何新的地形查询</b>：只读 {@code heights} —— 也就是
     * {@code sampler.samplePositions(requests)} 已经把整个窗口采回来、并且 A* 展开时一直在用的
     * 那张 {@code (x,z) -> 地表高度} 网格。采样点按 {@code nearestGrid} 吸附到同一张网格上查表；
     * 一旦某个点不在网格里（{@code conflictCenter} 的圆心窗口会把弦截断），就地停下取有效前缀，
     * 不足 {@link #STEEP_CHORD_MIN_SAMPLES} 个点就返回 {@code null} ⇒ 判为"不陡" ⇒ 仍用 1.6。
     * 宁可少放宽，也不许凭半条弦的采样就把界放大 60%。</p>
     */
    static int[] chordProfile(Map<Long, Integer> heights, BlockPos start, BlockPos goal,
                                      int originX, int originZ, int step) {
        if (heights == null || start == null || goal == null || step < 1) return null;
        int dx = goal.getX() - start.getX();
        int dz = goal.getZ() - start.getZ();
        int samples = chordSamples(Math.hypot(dx, dz), step);
        if (samples < 2) return null;
        int[] profile = new int[samples + 1];
        int filled = 0;
        for (int i = 0; i <= samples; i++) {
            double ratio = i / (double) samples;
            int rawX = start.getX() + (int) Math.round(dx * ratio);
            int rawZ = start.getZ() + (int) Math.round(dz * ratio);
            Integer height = heights.get(key(nearestGrid(rawX, originX, step),
                    nearestGrid(rawZ, originZ, step)));
            if (height == null) break;
            profile[filled++] = height;
        }
        if (filled < STEEP_CHORD_MIN_SAMPLES) return null;
        return filled == profile.length ? profile : Arrays.copyOf(profile, filled);
    }

    /**
     * 0.9.062（①）：这条直连弦是不是"必须翻山"。
     *
     * <p>两个判据，任一成立即算陡：</p>
     * <ol>
     *   <li>{@link #STEEP_CHORD_MAX_GRADE}(0.25)：{@link #STEEP_CHORD_WINDOW_BLOCKS}(12) 格窗内的
     *       最大坡度。<b>这个 0.25 不是拍的</b> —— 它等于同包 {@code MountainSwitchbackPlanner} 的
     *       {@code SUSTAINED_GRADE_TRIGGER}(0.25)，也就是这套代码自己"该盘山了"的判据。复用同一个数
     *       意味着"长度界放宽"与"盘山重试"在同一时刻触发，不会出现"该盘山但界没放宽"的半开状态。</li>
     *   <li>{@link #STEEP_CHORD_CLIMB_RATIO}(0.20)：总爬升 / 弦长。<b>这个是推的</b>：loose 档是
     *       每 7 站抬 1 格 ≈ 14.29%，爬 {@code C} 格至少要 {@code 7C} 格水平长度；要把它塞进
     *       1.6 × 弦长就是 {@code C ≤ 1.6L/7 = 0.2286L}。也就是<b>总爬升超过弦长 22.86% 时
     *       1.6 的界在几何上已经不够用</b>。取 0.20 而不是 0.2286，是刻意在拐点之前 12%
     *       就放宽 —— 少剪一次总比剪掉一条正确的之字便宜。</li>
     * </ol>
     *
     * @param profile       沿弦逐点采到的地形高度
     * @param sampleSpacing 采样点间距（格）
     */
    static boolean chordIsSteep(int[] profile, double sampleSpacing) {
        if (profile == null || profile.length < STEEP_CHORD_MIN_SAMPLES) return false;
        if (!(sampleSpacing > 0.0D)) return false;
        double run = (profile.length - 1) * sampleSpacing;
        if (run <= 0.0D) return false;
        int window = Math.max(1, (int) Math.round(STEEP_CHORD_WINDOW_BLOCKS / sampleSpacing));
        window = Math.min(window, profile.length - 1);
        double span = window * sampleSpacing;
        double maxGrade = 0.0D;
        for (int i = 0; i + window < profile.length; i++) {
            maxGrade = Math.max(maxGrade, Math.abs(profile[i + window] - profile[i]) / span);
        }
        long climb = 0L;
        for (int i = 1; i < profile.length; i++) {
            int delta = profile[i] - profile[i - 1];
            if (delta > 0) climb += delta;
        }
        return maxGrade > STEEP_CHORD_MAX_GRADE || (climb / run) > STEEP_CHORD_CLIMB_RATIO;
    }

    /** R7：可回退开关（PlanningConfig.gradeRepairLengthBoundPrune，默认 true）。 */
    private static boolean lengthBoundPruneEnabled() {
        try {
            return ConfigService.get().planning().gradeRepairLengthBoundPrune();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 0.9.010：把本线程最近一次局部搜索的诊断"就地"消费掉，并标上分支名。
     *
     * <p>原来只有结构绕行的 3 个日志点会消费这个 ThreadLocal，于是：
     * ① grade 修坡跑在 RoadWeaver-GradeRepair 池线程上 ⇒ 写进池线程自己的槽位 ⇒ 永不消费；
     * ② construction-height / 湖 / 河 跑在 road worker 上 ⇒ 要等下一次结构候选日志才被读走，
     *    于是出现"连接 A 的湖搜索数值印在连接 B 的结构候选行里"的跨连接错配。
     * 现在每条分支在自己的出口各消费一次，诊断归属于产生它的那次搜索。</p>
     */
    private static void logLocalSearchDiag(String stage) {
        LOGGER.info("RoadWeaver local search diag stage={} {}", stage, consumeLocalSearchDiag());
    }

    private static String consumeLocalSearchDiag() {
        String diag = LAST_LOCAL_SEARCH_DIAG.get();
        LAST_LOCAL_SEARCH_DIAG.remove();
        return diag == null ? "diag=none" : diag;
    }

    private static int[] tunnelHeights(int terrainY, int horizontalStep, int tunnelFloorY) {
        int verticalStep = Math.max(1, Math.min(2, horizontalStep));
        int depth = tunnelFloorY == Integer.MIN_VALUE
                ? tunnelDepthBlocks()
                : Math.max(0, terrainY - tunnelFloorY);
        int levels = depth / verticalStep + 1;
        int[] result = new int[levels];
        for (int i = 0; i < levels; i++) result[i] = terrainY - i * verticalStep;
        return result;
    }

    private static int expandedStateLimit(int sampledColumns, boolean allowTunnel, int horizontalStep) {
        return expandedStateLimit(sampledColumns, allowTunnel, horizontalStep, false);
    }

    private static int expandedStateLimit(int sampledColumns, boolean allowTunnel, int horizontalStep,
                                          boolean localStructureDetour) {
        // Base ceiling is configurable (PlanningConfig.gradeRepairWindowStateLimit,
        // default 90_000 = the historical hard-coded value) so slower machines
        // can trade repair ambition for latency without touching code.
        int base = repairWindowStateLimit();
        if (!allowTunnel) return base;
        if (localStructureDetour) {
            // 0.8.326：局部结构绕行是"建筑挡路"的局部修补，不该用全局隧道搜索
            // 的 24 万放大预算。真机三次 tunnel 尝试 20~29s 全部 no_path。
            return horizontalStep == 1 ? Math.min(MAX_EXACT_BUILDING_EXPANDED_STATES, base) : base;
        }
        int verticalStep = Math.max(1, Math.min(2, horizontalStep));
        int verticalLevels = tunnelDepthBlocks() / verticalStep + 1;
        long requested = Math.max(0L, (long) sampledColumns) * verticalLevels;
        int limit = (int) Math.min(MAX_TUNNEL_EXPANDED_STATES,
                Math.max(base, requested));
        return horizontalStep == 1 ? Math.min(MAX_EXACT_BUILDING_EXPANDED_STATES, limit) : limit;
    }

    private static int repairWindowStateLimit() {
        try {
            return Math.max(20_000, Math.min(500_000,
                    ConfigService.get().planning().gradeRepairWindowStateLimit()));
        } catch (Throwable ignored) {
            return MAX_EXPANDED_STATES;
        }
    }

    /**
     * 0.9.001：单个局部修坡窗口的墙钟预算（纳秒）。
     *
     * <p>与 {@link #repairWindowStateLimit()} 完全同构：每次搜索读一次配置，读不到就
     * 退回 {@link #DEFAULT_LOCAL_SEARCH_BUDGET_NANOS}。刻意不做成静态缓存 ——
     * 配置热改（界面/配置文件）要在下一次窗口生效，且单次读开销远低于一次 A* 展开。</p>
     */
    private static long repairWindowBudgetNanos() {
        try {
            return Math.max(1_000L, Math.min(600_000L,
                    ConfigService.get().planning().gradeRepairWindowBudgetMillis())) * 1_000_000L;
        } catch (Throwable ignored) {
            return DEFAULT_LOCAL_SEARCH_BUDGET_NANOS;
        }
    }

    private static boolean isAccepted(List<BlockPos> original, List<BlockPos> candidate) {
        if (candidate == null || candidate.size() < 2 || findFirstViolation(candidate) != null) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 32.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    private static boolean isStructureRepairAccepted(List<BlockPos> original, List<BlockPos> candidate) {
        return isStructureRepairAccepted(original, candidate, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    private static boolean isStructureRepairAccepted(List<BlockPos> original, List<BlockPos> candidate,
                                                      int structureClearance) {
        if (candidate == null || candidate.size() < 2
                || findFirstViolation(candidate) != null
                || findFirstStructureConflict(candidate, structureClearance) != null) return false;
        // A building repair must actually change the corridor. Accepting an
        // identical path only records another failed attempt and makes the
        // caller retain a known collision as if it had been repaired.
        if (sameFullPath(original, candidate)) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 64.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    private static boolean isLakeRepairAccepted(List<BlockPos> original,
                                                 List<BlockPos> candidate,
                                                 int roadWidth,
                                                 LakeLookup lakes,
                                                 HeightLookup terrain,
                                                 LakeLookup lakeClearance) {
        // Logging-only diagnostics: each rejection reason is reported at debug
        // level in the same short-circuit order the combined condition used.
        if (candidate == null || candidate.size() < 2) {
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason={} originalNodes={} candidateNodes={}",
                    candidate == null ? "null_candidate" : "path_too_short",
                    original == null ? -1 : original.size(),
                    candidate == null ? -1 : candidate.size());
            return false;
        }
        Violation grade = findFirstViolation(candidate);
        if (grade != null) {
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason=grade edge={}..{} rise={} length={} nodes={}",
                    grade.start(), grade.end(), grade.rise(), format(grade.length()), candidate.size());
            return false;
        }
        Violation structure = findFirstStructureConflict(candidate,
                // Task 1: physical road-body radius, voxel-accurate — the
                // legacy 10-block ahead-clearance rejected valid edge-grazing
                // corridors that never touch the structure.
                planningRoadRadius());
        if (structure != null) {
            BlockPos from = candidate.get(Math.max(0, structure.start()));
            BlockPos to = candidate.get(Math.max(0, Math.min(candidate.size() - 1, structure.end())));
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason=structure_conflict edge={}..{} pos={} nodes={}",
                    structure.start(), structure.end(), from != null ? from : to, candidate.size());
            return false;
        }
        Violation lake = findFirstLakeConstraintConflict(candidate, roadWidth, lakes, terrain);
        if (lake != null) {
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason=lake_conflict edge={}..{} nodes={}",
                    lake.start(), lake.end(), candidate.size());
            return false;
        }
        Violation mask = findFirstMaskConflict(candidate, lakeClearance);
        if (mask != null) {
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason=lake_clearance_mask edge={}..{} nodes={}",
                    mask.start(), mask.end(), candidate.size());
            return false;
        }
        double originalLength = routeLength(original);
        double candidateLength = routeLength(candidate);
        double maxLength = Math.max(originalLength + 200.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
        if (candidateLength > maxLength) {
            LOGGER.debug("RoadWeaver lake repair candidate rejected: reason=detour_too_long "
                            + "candidateLength={} maxLength={} originalLength={} nodes={}",
                    format(candidateLength), format(maxLength), format(originalLength), candidate.size());
            return false;
        }
        return true;
    }

    static Violation findFirstLakeConstraintConflict(List<BlockPos> path,
                                                     int roadWidth,
                                                     LakeLookup lakes,
                                                     HeightLookup terrain) {
        Violation lake = findFirstLakeConflict(path, roadWidth, lakes);
        Violation shore = findFirstUnstableLakeShoreConflict(path, roadWidth, lakes, terrain);
        if (lake == null) return shore;
        if (shore == null) return lake;
        return lake.start() <= shore.start() ? lake : shore;
    }

    static Violation findFirstLakeClearanceConflict(List<BlockPos> path,
                                                     int roadWidth,
                                                     int clearanceBlocks,
                                                     LakeLookup lakes) {
        if (path == null || path.isEmpty() || lakes == null) return null;
        LakeLookup expanded = expandedLakeLookup(
                lakes, Math.max(1, roadWidth), Math.max(0, clearanceBlocks), new HashMap<>());
        return findFirstMaskConflict(path, expanded);
    }

    private static Violation findFirstStructureConflict(List<BlockPos> path) {
        return findFirstStructureConflict(path, STRUCTURE_AHEAD_CLEARANCE_BLOCKS);
    }

    private static Violation findFirstStructureConflict(List<BlockPos> path, int structureClearance) {
        if (path == null || path.isEmpty()) return null;
        int clearance = Math.max(0, structureClearance);
        if (StructureAvoidanceService.shouldAvoidPlanning(path.get(0), clearance)) {
            return new Violation(0, Math.min(1, path.size() - 1), 0, 0.0D);
        }
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (edgeIntersectsProtected(previous, current, clearance)) {
                return new Violation(i - 1, i, Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    /**
     * Nearby real-building collisions are repaired as one local cluster. This
     * prevents a successful detour around one building from being immediately
     * pulled back into another building a few blocks later.
     */
    private static Violation expandNearbyStructureCluster(List<BlockPos> path,
                                                           Violation first,
                                                           int structureClearance,
                                                           int localDetourBlocks) {
        if (path == null || first == null || path.size() < 2) return first;
        int start = Math.max(0, first.start());
        int end = Math.min(path.size() - 1, first.end());
        BoundingBox cluster = null;
        for (int index = start; index <= end; index++) {
            BoundingBox box = planningCollisionBox(
                    path.get(index), Math.max(0, structureClearance));
            if (box != null) cluster = cluster == null ? box : cluster.encapsulate(box);
        }
        if (cluster == null) return first;
        // Keep structure clustering tied to the physical road footprint, not
        // to the independent terrain sampling radius.
        int bodyRadius = roadBodyRadius();
        cluster = cluster.inflatedBy(Math.max(1, bodyRadius));
        boolean changed;
        do {
            changed = false;
            for (int index = start; index < path.size(); index++) {
                BlockPos point = path.get(index);
                BoundingBox box = point == null ? null
                        : planningCollisionBox(point, Math.max(0, structureClearance));
                // A path point being inside the current search rectangle is
                // not evidence of another building. Extend only for a real
                // collision box whose expanded footprint touches this cluster.
                boolean joinsCluster = box != null && cluster.intersects(
                        box.inflatedBy(Math.max(1, bodyRadius)));
                if (joinsCluster && index > end) {
                    // Buildings can form a long daisy chain when each 50-block
                    // search margin overlaps the next. Do not merge that into
                    // one giant A* request. The next repair loop will handle
                    // the remaining collision with its own local endpoints.
                    if (!structureClusterWindowAllows(path, start, index)) {
                        continue;
                    }
                    end = index;
                    cluster = cluster.encapsulate(box).inflatedBy(Math.max(1, bodyRadius));
                    changed = true;
                }
            }
        } while (changed);
        if (end == first.end()) return first;
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                routeHorizontalLength(path, start, end));
    }

    private static double routeHorizontalLength(List<BlockPos> path, int from, int to) {
        double distance = 0.0D;
        for (int i = Math.max(1, from + 1); i <= to && i < path.size(); i++) {
            distance += horizontalDistance(path.get(i - 1), path.get(i));
        }
        return distance;
    }

    /**
     * Produces a small ordered set of endpoint-preserving windows for one
     * structure conflict. The first window is the normal local context; the
     * others are tighter alternatives for conflicts close to an endpoint or
     * where grid snapping leaves the first entry/exit unusable. None of these
     * windows can grow beyond the caller's local repair radius.
     */
    private static List<RepairSlice> structureRepairSlices(List<BlockPos> path,
                                                             Violation conflict,
                                                             BlockPos center,
                                                             int contextBlocks) {
        if (path == null || path.size() < 3 || conflict == null) return List.of();
        int context = Math.max(1, contextBlocks);
        Map<Long, RepairSlice> unique = new LinkedHashMap<>();
        addRepairSlice(unique, path, conflict.start(), conflict.end(), context, center);
        addRepairSlice(unique, path, conflict.start(), conflict.end(), Math.max(8, context / 2), center);
        addRepairSlice(unique, path, conflict.start(), conflict.end(), 8, center);

        // If the conflict is near one end of the route, retain the route's
        // endpoint as an anchor but move the opposite anchor inward. This is
        // the useful "line enters the circle, detour, line exits the circle"
        // case and avoids rejecting a valid bypass as no-interior-entry/exit.
        if (center != null) {
            int entry = nearestIndexBefore(path, conflict.start(), center, context);
            int exit = nearestIndexAfter(path, conflict.end(), center, context);
            if (entry >= 0 && exit > entry) {
                addRepairSlice(unique, path, entry, exit, 0, center);
            }
        }
        return List.copyOf(unique.values());
    }

    private static void addRepairSlice(Map<Long, RepairSlice> unique,
                                       List<BlockPos> path,
                                       int conflictStart,
                                       int conflictEnd,
                                       int context,
                                       BlockPos center) {
        int start = context <= 0
                ? Math.max(0, conflictStart)
                : walkContextStart(path, conflictStart, context);
        int end = context <= 0
                ? Math.min(path.size() - 1, conflictEnd)
                : walkContextEnd(path, conflictEnd, context);
        if (end - start < 2) return;
        List<BlockPos> slice = new ArrayList<>(path.subList(start, end + 1));
        long key = (((long) start) << 32) ^ (end & 0xffffffffL);
        unique.putIfAbsent(key, new RepairSlice(start, end, slice, center));
    }

    private static int nearestIndexBefore(List<BlockPos> path, int from,
                                          BlockPos center, int maxDistance) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = Math.max(0, from - 1); i >= 0; i--) {
            double distance = horizontalDistance(path.get(i), center);
            if (distance > maxDistance) {
                if (best >= 0) break;
                // The path normally moves monotonically away from a local
                // conflict. Do not jump across an unrelated far-away route
                // section looking for another point in the circle.
                break;
            }
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int nearestIndexAfter(List<BlockPos> path, int from,
                                         BlockPos center, int maxDistance) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = Math.min(path.size() - 1, from + 1); i < path.size(); i++) {
            double distance = horizontalDistance(path.get(i), center);
            if (distance > maxDistance) {
                if (best >= 0) break;
                break;
            }
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean sameHorizontalPath(List<BlockPos> first, List<BlockPos> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            BlockPos a = first.get(i);
            BlockPos b = second.get(i);
            if (a == null || b == null || a.getX() != b.getX() || a.getZ() != b.getZ()) return false;
        }
        return true;
    }

    private static boolean sameFullPath(List<BlockPos> first, List<BlockPos> second) {
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).equals(second.get(i))) return false;
        }
        return true;
    }

    static boolean structureClusterWindowAllows(List<BlockPos> path, int start, int candidateEnd) {
        return routeHorizontalLength(path, start, candidateEnd)
                <= MAX_STRUCTURE_CLUSTER_SPAN_BLOCKS;
    }

    static Violation findFirstLakeConflict(List<BlockPos> path,
                                           int roadWidth,
                                           LakeLookup lakes) {
        if (path == null || path.isEmpty() || lakes == null) return null;
        boolean[] conflicts = new boolean[Math.max(1, path.size())];
        conflicts[0] = pointIntersectsLake(path.get(0), roadWidth, lakes);
        for (int index = 1; index < path.size(); index++) {
            conflicts[index] = edgeIntersectsLake(
                    path.get(index - 1), path.get(index), roadWidth, lakes);
        }
        int first = -1;
        for (int index = 0; index < conflicts.length; index++) {
            if (conflicts[index]) {
                first = index;
                break;
            }
        }
        if (first < 0) return null;
        int last = first;
        for (int index = first + 1; index < conflicts.length && conflicts[index]; index++) {
            last = index;
        }
        int start = Math.max(0, first - 1);
        int end = Math.min(path.size() - 1, Math.max(first, last));
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                horizontalDistance(path.get(start), path.get(end)));
    }

    static Violation findFirstRiverConflict(List<BlockPos> path,
                                            int roadWidth,
                                            RiverLookup channels) {
        if (path == null || path.isEmpty() || channels == null) return null;
        boolean[] conflicts = new boolean[Math.max(1, path.size())];
        conflicts[0] = pointIntersectsRiver(path.get(0), roadWidth, channels);
        for (int index = 1; index < path.size(); index++) {
            conflicts[index] = edgeIntersectsRiver(
                    path.get(index - 1), path.get(index), roadWidth, channels);
        }
        int first = -1;
        for (int index = 0; index < conflicts.length; index++) {
            if (conflicts[index]) { first = index; break; }
        }
        if (first < 0) return null;
        int last = first;
        for (int index = first + 1; index < conflicts.length && conflicts[index]; index++) last = index;
        int start = Math.max(0, first - 1);
        int end = Math.min(path.size() - 1, Math.max(first, last));
        return new Violation(start, end,
                Math.abs(path.get(end).getY() - path.get(start).getY()),
                horizontalDistance(path.get(start), path.get(end)));
    }

    private static boolean isRiverRepairAccepted(List<BlockPos> original,
                                                  List<BlockPos> candidate,
                                                  int roadWidth,
                                                  RiverLookup channels) {
        if (candidate == null || candidate.size() < 2
                || findFirstViolation(candidate) != null
                || findFirstRiverConflict(candidate, roadWidth, channels) != null) return false;
        double originalLength = routeLength(original);
        return routeLength(candidate) <= Math.max(originalLength + 200.0D,
                originalLength * MAX_ROUTE_LENGTH_MULTIPLIER);
    }

    private static boolean edgeIntersectsRiver(BlockPos from,
                                                BlockPos to,
                                                int roadWidth,
                                                RiverLookup channels) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (channels.isChannel(x, z)) return true;
            }
        }
        return false;
    }

    private static boolean pointIntersectsRiver(BlockPos point,
                                                 int roadWidth,
                                                 RiverLookup channels) {
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (channels.isChannel(point.getX() + dx, point.getZ() + dz)) return true;
            }
        }
        return false;
    }

    private static Violation findFirstUnstableLakeShoreConflict(List<BlockPos> path,
                                                                 int roadWidth,
                                                                 LakeLookup lakes,
                                                                 HeightLookup terrain) {
        if (path == null || path.size() < 2 || lakes == null || terrain == null) return null;
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            if (edgeIntersectsUnstableLakeShore(previous, current, roadWidth, lakes, terrain)) {
                return new Violation(index - 1, index,
                        Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    private static boolean edgeIntersectsLake(BlockPos from,
                                              BlockPos to,
                                              int roadWidth,
                                              LakeLookup lakes) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (lakes.isLake(x, z)) return true;
            }
        }
        return false;
    }

    private static Violation findFirstMaskConflict(List<BlockPos> path, LakeLookup mask) {
        if (path == null || path.isEmpty() || mask == null) return null;
        if (mask.isLake(path.getFirst().getX(), path.getFirst().getZ())) {
            return new Violation(0, 0, 0, 0.0D);
        }
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            if (edgeIntersectsMask(previous, current, mask)) {
                return new Violation(index - 1, index,
                        Math.abs(current.getY() - previous.getY()),
                        horizontalDistance(previous, current));
            }
        }
        return null;
    }

    private static boolean edgeIntersectsMask(BlockPos from,
                                              BlockPos to,
                                              LakeLookup mask) {
        double horizontal = horizontalDistance(from, to);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (mask.isLake(x, z)) return true;
        }
        return false;
    }

    private static LakeLookup expandedLakeLookup(LakeLookup lakes,
                                                 int roadWidth,
                                                 int clearanceBlocks,
                                                 Map<Long, Boolean> cache) {
        int radius = Math.max(0, roadWidth / 2) + Math.max(0, clearanceBlocks);
        return (x, z) -> cache.computeIfAbsent(key(x, z), ignored -> {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (lakes.isLake(x + dx, z + dz)) return true;
                }
            }
            return false;
        });
    }

    private static boolean edgeIntersectsUnstableLakeShore(BlockPos from,
                                                            BlockPos to,
                                                            int roadWidth,
                                                            LakeLookup lakes,
                                                            HeightLookup terrain) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        double normalX = horizontal < 0.001D ? 1.0D : -dz / horizontal;
        double normalZ = horizontal < 0.001D ? 0.0D : dx / horizontal;
        int halfWidth = Math.max(0, roadWidth / 2);
        int shoreProbe = halfWidth + LAKE_SHORE_PROBE_BLOCKS;
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double centerX = from.getX() + dx * t;
            double centerZ = from.getZ() + dz * t;
            boolean nearLake = false;
            for (int offset = -shoreProbe; offset <= shoreProbe; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                if (lakes.isLake(x, z)) {
                    nearLake = true;
                    break;
                }
            }
            if (!nearLake) continue;

            int roadY = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = (int) Math.round(centerX + normalX * offset);
                int z = (int) Math.round(centerZ + normalZ * offset);
                int terrainY = terrain.height(x, z);
                if (terrainY - roadY > MAX_LAKE_SHORE_CUT_DEPTH
                        || roadY - terrainY > MAX_LAKE_SHORE_FILL_DEPTH) return true;
            }
        }
        return false;
    }

    private static boolean pointIntersectsLake(BlockPos point,
                                               int roadWidth,
                                               LakeLookup lakes) {
        int halfWidth = Math.max(0, roadWidth / 2);
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (lakes.isLake(point.getX() + dx, point.getZ() + dz)) return true;
            }
        }
        return false;
    }

    private static boolean edgeIntersectsProtected(BlockPos from, BlockPos to) {
        return edgeIntersectsProtected(from, to, 0);
    }

    private static boolean edgeIntersectsProtected(BlockPos from, BlockPos to, int horizontalClearance) {
        double horizontal = horizontalDistance(from, to);
        int samples = Math.max(1, (int) Math.ceil(horizontal));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (StructureAvoidanceService.shouldAvoidPlanning(
                    new BlockPos(x, y, z), Math.max(0, horizontalClearance))) return true;
        }
        return false;
    }

    private static int walkContextStart(List<BlockPos> path, int index, double blocks) {
        double distance = 0.0D;
        int result = Math.max(0, index);
        while (result > 0 && distance < blocks) {
            distance += horizontalDistance(path.get(result - 1), path.get(result));
            result--;
        }
        return result;
    }

    /**
     * Extends a river repair window across nearby wet runs (bridging short dry
     * banks between them) so one route crossing the same channel several times
     * is rerouted as a single bounded detour. The window never grows beyond
     * {@code maxStations} past the starting index.
     */
    private static int extendWindowAcrossNearbyWetRuns(List<BlockPos> path,
                                                       int from,
                                                       int direction,
                                                       RiverLookup channels,
                                                       int maxStations) {
        int size = path.size();
        int index = Math.max(0, Math.min(size - 1, from));
        int result = index;
        int dryRun = 0;
        for (int step = 1; step <= maxStations; step++) {
            int next = index + direction * step;
            if (next < 0 || next >= size) break;
            BlockPos point = path.get(next);
            if (point == null) break;
            if (channels.isChannel(point.getX(), point.getZ())) {
                result = next;
                dryRun = 0;
                continue;
            }
            dryRun++;
            // A longer dry stretch means the next wet run belongs to a
            // different crossing group; stop absorbing.
            if (dryRun > RIVER_RUN_MERGE_MAX_DRY_STATIONS) break;
        }
        return result;
    }

    private static int walkContextEnd(List<BlockPos> path, int index, double blocks) {
        double distance = 0.0D;
        int result = Math.min(path.size() - 1, index);
        while (result + 1 < path.size() && distance < blocks) {
            distance += horizontalDistance(path.get(result), path.get(result + 1));
            result++;
        }
        return result;
    }

    private static List<BlockPos> reconstruct(Node goal) {
        List<BlockPos> result = new ArrayList<>();
        for (Node node = goal; node != null; node = node.parent()) {
            result.add(new BlockPos(node.x(), node.y(), node.z()));
        }
        Collections.reverse(result);
        return result;
    }

    /** 0.9.009：该列是否"水里"（水面高于河床）。 */
    private static boolean isWetColumn(AccurateHeightSample sample) {
        // 0.9.045（D-8）：恢复「水深 > 1 才算湿」。
        //
        // 0.9.009（cb800f5）把这里从 depth > 1 放宽成 depth >= 1，后果三条，第二条致命：
        //   ① 1 格深的水从"免费"直接进最贵档（窄水 run×20）；
        //   ② 同一判据还被陆路闸门的 wetBlocked 用（:1586）⇒ **一个 1 格深的水洼
        //      就能让闸门永久判 no-land-route**，landDetourFactor 那道"能绕就绕"
        //      的硬保证在浅水地形上整条失效；
        //   ③ 贴河走满 13 站就算桥（crossingWidthBlocks 量的是沿路长度）。
        // 恢复 > 1 之后：浅水洼重新可通行、重新免费，闸门重新工作。
        return sample != null
                && sample.worldSurfaceWg() - sample.oceanFloorWg() > 1;
    }

    /**
     * 0.9.011：三档价目（宽度 = 连续湿步数，由 {@code wetRun} 维护）。
     * <ol>
     *   <li>涵洞：{@code run <= culvertMaxStations} 且 {@code depth <= culvertMaxWaterDepth}
     *       → {@code run × culvertPer}</li>
     *   <li>窄水：{@code run <= narrowMaxWidth} → {@code run × narrowPer}（小桥，直接过）</li>
     *   <li>宽水：其余 → {@code base + per × run}（大河，重罚绕行）</li>
     * </ol>
     * {@code narrowMaxWidth <= 0} 时跳过窄水档，语义等同 0.9.010 的两档。
     */
    static double waterCrossingCost(int run, int depth, double base, double per,
                                           double culvertPer, int culvertMaxStations,
                                           int culvertMaxWaterDepth,
                                           int narrowMaxWidth, double narrowPer) {
        // 旧签名 = step 1（此时"步数"与"格数"同量纲），保持既有单测逐位不变。
        return waterCrossingCost(run, depth, base, per, culvertPer, culvertMaxStations,
                culvertMaxWaterDepth, narrowMaxWidth, narrowPer, 1);
    }

    /**
     * 0.9.053（D-12）：{@code narrowMaxWidth} 是**格**（配置项叫 narrowWaterMaxWidth），
     * 而 {@code run} 是**连续湿步数** —— 两者量纲不同。{@code step=2} 的调用点原来拿
     * 「8 步」去比「20 格」，等于把阈值当成 40 格用：同一条 21 格河，河修复路径（step1）
     * 判宽水、修坡路径（step2）判窄水，**同一条河 10.4 倍价差**。
     *
     * <p>本次只在**窄水档的比较**上按格折算（{@code run × step}）；
     * 计价乘数仍用 {@code run}（与 A* 的每步代价同量纲），涵洞档仍按
     * {@code culvertMaxStations}（语义就是"站"）比较。{@code step = 1} 时逐位不变。</p>
     */
    static double waterCrossingCost(int run, int depth, double base, double per,
                                           double culvertPer, int culvertMaxStations,
                                           int culvertMaxWaterDepth,
                                           int narrowMaxWidth, double narrowPer, int step) {
        if (run <= 0) return 0.0D;
        if (culvertMaxStations > 0 && run <= culvertMaxStations && depth <= culvertMaxWaterDepth) {
            return run * culvertPer;
        }
        int runBlocks = run * Math.max(1, step);
        if (narrowMaxWidth > 0 && runBlocks <= narrowMaxWidth) {
            return run * narrowPer;
        }
        return base + per * run;
    }

    /**
     * 0.9.011：陆路闸门的长度上限（格）。抽成纯函数是为了能被单测钉住 ——
     * 尤其是「之字形走廊」那一支：{@code findLocalRoute} 是局部修复，原走廊本身
     * 可能已是一条 3 倍于直连距离的之字路，只看直连会把完美的陆路修复误判成
     * 「绕太远」而放开水面，桥就又会冒出来。
     *
     * @param direct         起点到终点的直线距离
     * @param originalLength 待修复走廊的现有长度
     * @param factor         landDetourFactor
     */
    static double landDetourCap(double direct, double originalLength, double factor) {
        return Math.max(Math.max(direct, 8.0D) * factor, originalLength * 1.05D);
    }

    /** 0.9.011：陆路闸门判定计数（只读观测）。 */
    private static final java.util.concurrent.atomic.AtomicLong WATER_GATE_SEEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong WATER_GATE_LAND =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong WATER_GATE_CROSS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 闸门第一趟（湿列禁行）累计耗时。它是这版唯一可能让修复变贵的地方，必须看得见。 */
    private static final java.util.concurrent.atomic.AtomicLong WATER_GATE_PASS1_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 0.9.011：陆路闸门判定日志。节流（前 40 次 + 之后每 200 次一条）。
     * 带累计 land / cross 计数（一局就能看出「桥到底还建不建」）与
     * 第一趟累计耗时 {@code pass1Ms}（判断这版是否让道路修复变慢的硬指标）。
     */
    private static void recordWaterGateDiag(String verdict, double landLength, double direct,
                                            double factor, long gateNanos) {
        if ("land".equals(verdict)) {
            WATER_GATE_LAND.incrementAndGet();
        } else {
            WATER_GATE_CROSS.incrementAndGet();
        }
        long pass1Nanos = WATER_GATE_PASS1_NANOS.addAndGet(Math.max(0L, gateNanos));
        long seen = WATER_GATE_SEEN.incrementAndGet();
        if (seen <= 40 || seen % 200 == 0) {
            LOGGER.info("[ROAD-WATER-GATE] #{} verdict={} landLen={} direct={} factor={} "
                            // 0.9.049（D-16）：这两个数**是同一个量**（同一个 gateNanos），
                // 一个单次、一个累计。原来叫 gateMs/pass1Ms 会让人以为"闸门额外开销"被分开了，
                // 实际从来没有——闸门的开销就是这一趟 pass1，没有别的部分。改成自解释的名字。
                + "land={} cross={} pass1MsThisCall={} pass1MsTotal={}",
                    seen, verdict,
                    landLength < 0.0D ? "n/a" : String.format("%.1f", landLength),
                    String.format("%.1f", direct),
                    String.format("%.2f", factor),
                    WATER_GATE_LAND.get(), WATER_GATE_CROSS.get(),
                    Math.max(0L, gateNanos) / 1_000_000L, pass1Nanos / 1_000_000L);
        }
    }

    /** 0.9.009：临河距离惩罚。d<=0（在水里）加满；d<=radius 线性衰减；否则 0。 */
    private static double riverProximityCost(int distanceBlocks, double penalty, int radius) {
        if (penalty <= 0.0D || radius <= 0) return 0.0D;
        if (distanceBlocks <= 0) return penalty;
        if (distanceBlocks > radius) return 0.0D;
        return penalty * (radius - distanceBlocks) / (double) radius;
    }

    /**
     * 0.9.009：窗口内「到最近湿列」的距离场（两遍 chamfer，单位=网格步）。
     * 湿列来自已采样的 {@code samples}（worldSurfaceWg > oceanFloorWg），不需要任何额外世界查询。
     * 网格太大（> 2M 格）时返回 null ⇒ 退化为"无临河惩罚"，绝不为了这个惩罚把内存打爆。
     */
    private static int[] buildRiverDistanceField(Bounds bounds, int step,
                                                 Map<Long, AccurateHeightSample> samples) {
        if (bounds == null || samples == null || samples.isEmpty()) return null;
        int gridW = (bounds.maxX() - bounds.minX()) / step + 1;
        int gridH = (bounds.maxZ() - bounds.minZ()) / step + 1;
        if (gridW <= 0 || gridH <= 0 || (long) gridW * gridH > 2_000_000L) return null;
        int inf = 1 << 20;
        int[] dist = new int[gridW * gridH];
        java.util.Arrays.fill(dist, inf);
        for (Map.Entry<Long, AccurateHeightSample> entry : samples.entrySet()) {
            if (!isWetColumn(entry.getValue())) continue;
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            int gx = (x - bounds.minX()) / step;
            int gz = (z - bounds.minZ()) / step;
            if (gx < 0 || gz < 0 || gx >= gridW || gz >= gridH) continue;
            dist[gx + gz * gridW] = 0;
        }
        for (int z = 0; z < gridH; z++) {
            for (int x = 0; x < gridW; x++) {
                int i = x + z * gridW;
                if (dist[i] == 0) continue;
                int best = dist[i];
                if (x > 0) best = Math.min(best, dist[i - 1] + 1);
                if (z > 0) best = Math.min(best, dist[i - gridW] + 1);
                dist[i] = best;
            }
        }
        for (int z = gridH - 1; z >= 0; z--) {
            for (int x = gridW - 1; x >= 0; x--) {
                int i = x + z * gridW;
                if (dist[i] == 0) continue;
                int best = dist[i];
                if (x + 1 < gridW) best = Math.min(best, dist[i + 1] + 1);
                if (z + 1 < gridH) best = Math.min(best, dist[i + gridW] + 1);
                dist[i] = best;
            }
        }
        return dist;
    }

    /** 0.9.009：查距离场，返回格数（步数 × step）；越界或未建场返回 Integer.MAX_VALUE（=无惩罚）。 */
    private static int riverDistanceBlocks(int[] field, int gridW, Bounds bounds, int step, int x, int z) {
        if (field == null || gridW <= 0) return Integer.MAX_VALUE;
        int gx = (x - bounds.minX()) / step;
        int gz = (z - bounds.minZ()) / step;
        if (gx < 0 || gz < 0 || gx >= gridW) return Integer.MAX_VALUE;
        int index = gx + gz * gridW;
        if (index < 0 || index >= field.length) return Integer.MAX_VALUE;
        int steps = field[index];
        return steps >= (1 << 20) ? Integer.MAX_VALUE : steps * step;
    }

    private static double heuristic(int x, int z, int goalX, int goalZ) {
        return Math.hypot(goalX - x, goalZ - z);
    }

    private static double routeLength(List<BlockPos> path) {
        double result = 0.0D;
        for (int i = 1; i < path.size(); i++) result += horizontalDistance(path.get(i - 1), path.get(i));
        return result;
    }

    /**
     * 0.9.054（D-15）：路径的**总竖向爬升**（Σ|ΔY|）。
     *
     * <p>陆路闸门原来只比**水平长度**，而生成这条陆路的 A* 代价函数里竖向是
     * {@code rise × 8 + grade² × 90 + tunnelPenalty}。两套口径不同 ⇒ 闸门可以接受
     * 「水平 104 格、但要爬 25 格崖」的陆路（水平上只比上限多 4 格）。</p>
     */
    private static double routeClimb(List<BlockPos> path) {
        if (path == null || path.size() < 2) return 0.0D;
        double result = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            result += Math.abs(path.get(i).getY() - path.get(i - 1).getY());
        }
        return result;
    }

    /** 竖向 1 格折算成多少「等效水平格」——与 A* 代价里的 {@code rise × 8} 同口径。 */
    private static final double VERTICAL_EQUIV_BLOCKS = 8.0D;

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        return Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
    }

    private static long horizontalDistanceSquared(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static int nearestGrid(int value, int origin, int step) {
        return origin + (int) Math.round((value - origin) / (double) step) * step;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    record Violation(int start, int end, int rise, double length) {
    }

    private record RepairSlice(int startIndex, int endIndex,
                               List<BlockPos> path, BlockPos center) {
    }

    @FunctionalInterface
    interface LakeLookup {
        boolean isLake(int x, int z);
    }

    @FunctionalInterface
    interface RiverLookup {
        boolean isChannel(int x, int z);
    }

    @FunctionalInterface
    interface HeightLookup {
        int height(int x, int z);
    }

    @FunctionalInterface
    private interface EdgeObstacle {
        EdgeObstacle NONE = (from, to) -> false;

        boolean blocked(BlockPos from, BlockPos to);
    }

    /** 点是否落在冲突盒内（用于"禁止进入建筑盒"的硬绕行重试）。 */
    private static boolean pointInsideBox(BoundingBox box, BlockPos pos) {
        if (box == null || pos == null) return false;
        return pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()
                && pos.getY() >= box.minY() && pos.getY() <= box.maxY();
    }

    private record StateKey(int x, int y, int z, int direction) {
    }

    /**
     * R7（0.9.003）：{@code length} = 从起点到本节点的**累计水平长度**（与
     * {@code routeLength} 同口径：只累计水平分量，不含垂直），供长度上界剪枝使用。
     */
    /**
     * 0.9.009：{@code wetRun} = 到本节点为止**连续**踩在水里的步数（离开水立刻归零），
     * 用于「过河代价按连续水站数递增」。只加 4 字节/节点，且【不进 StateKey】——
     * 进 key 会让状态数最坏 ×7，不可接受；同状态以不同 wetRun 被去重属可接受近似。
     */
    private record Node(int x, int y, int z, int direction, double cost, double length,
                        int wetRun, double score, Node parent) {
        StateKey key() {
            return new StateKey(x, y, z, direction);
        }
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
        static Bounds around(List<BlockPos> path, int margin, int step) {
            return around(path, margin, step, null);
        }

        static Bounds around(List<BlockPos> path, int margin, int step, BoundingBox obstacle) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos position : path) {
                minX = Math.min(minX, position.getX());
                minZ = Math.min(minZ, position.getZ());
                maxX = Math.max(maxX, position.getX());
                maxZ = Math.max(maxZ, position.getZ());
            }
            if (obstacle != null) {
                minX = Math.min(minX, obstacle.minX());
                minZ = Math.min(minZ, obstacle.minZ());
                maxX = Math.max(maxX, obstacle.maxX());
                maxZ = Math.max(maxZ, obstacle.maxZ());
            }
            minX = Math.floorDiv(minX - margin, step) * step;
            minZ = Math.floorDiv(minZ - margin, step) * step;
            maxX = Math.floorDiv(maxX + margin + step - 1, step) * step;
            maxZ = Math.floorDiv(maxZ + margin + step - 1, step) * step;
            return new Bounds(minX, minZ, maxX, maxZ);
        }

        static Bounds aroundConflict(BlockPos center, int radius, int step,
                                     BlockPos start, BlockPos goal, BoundingBox obstacle) {
            int minX = center.getX() - radius;
            int minZ = center.getZ() - radius;
            int maxX = center.getX() + radius;
            int maxZ = center.getZ() + radius;
            if (start != null) {
                minX = Math.min(minX, start.getX());
                minZ = Math.min(minZ, start.getZ());
                maxX = Math.max(maxX, start.getX());
                maxZ = Math.max(maxZ, start.getZ());
            }
            if (goal != null) {
                minX = Math.min(minX, goal.getX());
                minZ = Math.min(minZ, goal.getZ());
                maxX = Math.max(maxX, goal.getX());
                maxZ = Math.max(maxZ, goal.getZ());
            }
            if (obstacle != null) {
                minX = Math.min(minX, obstacle.minX());
                minZ = Math.min(minZ, obstacle.minZ());
                maxX = Math.max(maxX, obstacle.maxX());
                maxZ = Math.max(maxZ, obstacle.maxZ());
            }
            minX = Math.floorDiv(minX, step) * step;
            minZ = Math.floorDiv(minZ, step) * step;
            maxX = Math.floorDiv(maxX + step - 1, step) * step;
            maxZ = Math.floorDiv(maxZ + step - 1, step) * step;
            return new Bounds(minX, minZ, maxX, maxZ);
        }

        Collection<BlockPos> positions(int step) {
            Map<Long, BlockPos> unique = new LinkedHashMap<>();
            for (int x = minX; x <= maxX; x += step) {
                for (int z = minZ; z <= maxZ; z += step) {
                    unique.put(key(x, z), new BlockPos(x, 0, z));
                }
            }
            return unique.values();
        }

        Collection<BlockPos> positionsWithinRadius(int step, BlockPos center, int radius) {
            Map<Long, BlockPos> unique = new LinkedHashMap<>();
            long radiusSquared = (long) radius * radius;
            for (int x = minX; x <= maxX; x += step) {
                for (int z = minZ; z <= maxZ; z += step) {
                    if (horizontalDistanceSquared(x, z, center.getX(), center.getZ()) <= radiusSquared) {
                        unique.put(key(x, z), new BlockPos(x, 0, z));
                    }
                }
            }
            return unique.values();
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}
