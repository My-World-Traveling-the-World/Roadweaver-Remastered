/* 文件职责：用精确地形高度做「最后一道」有界修线 —— 只在出现物理不可能的坡度或断崖时改线。
 *
 * 粗规划仍然是权威：本类不主动「优化」路线，只在两种硬伤出现时介入
 * （一格高的不可能坡、12 格量级的断崖式升降）。
 *
 * ⚠ S5 台账标记 DELETE_LATER：它是「跑完再补救」的典型 —— 正确做法是让
 * 判定阶段就算对，而不是事后改线。等全局 DP 切默认后删。
 */
package net.shiroha233.roadweaver.planning.terrain;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathFinalizeOps;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 2026-11-15：key / horizontalDistanceSquared 已随 Bounds 搬到 Bounds.java，
// 这里用静态导入保持原有调用点不变（同包跨文件不能直接用简单名）。
import static net.shiroha233.roadweaver.planning.terrain.Bounds.horizontalDistanceSquared;
import static net.shiroha233.roadweaver.planning.terrain.Bounds.key;
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
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.SearchBudgetPolicy.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;

/** 精确地形修复仅针对不可行坡差和突崖；正常粗走廊仍作为权威输入。
 *
 * Final bounded route repair using accurate terrain heights. The coarse
 * planner remains authoritative unless the resolved path contains a physically
 * impossible one-block grade or a cliff-sized 12-block rise/drop.
 */
public final class AccurateGradeRepairPlanner {
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /**
     * 0.9.069（D-11a）：上一趟 searchLocalRoute 是否因**预算耗尽**（open 非空但状态数/墙钟
     * 到上限）退出，而不是 open 空（真无解）。ThreadLocal：搜索都在同一线程内同步完成。
     * 用于闸门区分「陆路真不通」与「陆路没搜完」——后者绝不能 fail-open 去架桥。
     */
    static final ThreadLocal<Boolean> LAST_SEARCH_BUDGET_EXHAUSTED = new ThreadLocal<>();
    static final int LOCAL_WINDOW_BLOCKS = 12;
    /**
     * Height anomalies are repaired in one explicit local 50-block window.
     *
     * <p>⚠️ 2026-11-15 核对：**全仓零引用**，这是**被取代的重复常量**。
     * 实际生效的是 HEIGHT_ANOMALY_DETOUR_BLOCKS（同为 50），
     * 见 :431 的注释与 :433 的「50 → heightAnomalyDetourMaxBlocks 逐级重试」。
     * 两个 50 并存会让人以为它们是两个不同的窗口。</p>
     */
    @SuppressWarnings("unused")
    static final int HEIGHT_REPAIR_CONTEXT_BLOCKS = 50;
    static final int SEARCH_MARGIN_BLOCKS = 80;
    static final int STRUCTURE_CONTEXT_BLOCKS = 96;
    static final int STRUCTURE_SEARCH_MARGIN_BLOCKS = 192;
    static final int STRUCTURE_AHEAD_CLEARANCE_BLOCKS = 10;
    /** 没有世界高度上下文时才用保守下界，运行修复使用真实世界最低高程。 */
    static final int DEFAULT_TUNNEL_DEPTH_BLOCKS = 12;
    static final int LAKE_CONTEXT_BLOCKS = 96;
    static final int LAKE_SHORE_PROBE_BLOCKS = 8;
    static final int LAKE_REROUTE_CLEARANCE_BLOCKS = 10;
    /** 湖岸仅轻微整平，避免绕水修复挖出深沟。 */
    static final int MAX_LAKE_SHORE_CUT_DEPTH = 2;
    static final int MAX_LAKE_SHORE_FILL_DEPTH = 2;
    static final int MAX_LOCAL_REPAIRS = 4;
    /** 普通修坡后仍有异常时才进行一次有界局部改线。 */
    static final int HEIGHT_ANOMALY_DETOUR_BLOCKS = 50;
    /** 每个层级对每个局部冲突修一次，已成功窗口不反复返工。 */
    static final int MAX_STRUCTURE_REPAIRS = 8;
    /** 相邻建筑逐步局部修复，不能把一串建筑合成完整端点间搜索。
 *
     * A chain of nearby buildings must not turn a local 10/20/50-block detour
     * into an endpoint-to-endpoint search. Later buildings are repaired by the
     * next pass after the preceding local repair has been committed.
     */
    static final int MAX_STRUCTURE_CLUSTER_SPAN_BLOCKS = 192;
    static final int MAX_LAKE_REPAIRS = 8;
    static final int MAX_RIVER_REPAIRS = 8;
    /** 同组水跨越允许少量干岸站连接，避免河道被碎分重复绕行。 */
    static final int RIVER_RUN_MERGE_MAX_DRY_STATIONS = 12;
    static final int MAX_EXPANDED_STATES = 90_000;
    static final int MAX_TUNNEL_EXPANDED_STATES = 240_000;
    /** 精确建筑修复有局部预算，避免一栋建筑拖住整路。 */
    static final int MAX_EXACT_BUILDING_EXPANDED_STATES = 24_000;
    /**
     * 0.9.001：单个局部修坡窗口 A* 的墙钟预算兜底值（20 秒）。
     *
     * <p>写法与同包的 {@code MountainSwitchbackPlanner.MAX_SEARCH_NANOS} /
     * {@code LOCAL_MAX_SEARCH_NANOS} 一致：只用一个纳秒 long 常量。正常取值由
     * {@code PlanningConfig.gradeRepairWindowBudgetMillis} 承载（默认同为 20 秒，
     * 改配置即生效，不用重编），本常量只在该配置读不出来时兜底，保证任何情况下
     * 这条 A* 都有墙钟上界。</p>
     */
    static final long DEFAULT_LOCAL_SEARCH_BUDGET_NANOS = 20_000_000_000L;
    static final double MAX_ROUTE_LENGTH_MULTIPLIER = 3.0D;
    static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    /** 互不重叠的修坡窗口可并行；独立池上限不等于各池合计占核上限。 */
    static final ExecutorService GRADE_REPAIR_POOL = Executors.newFixedThreadPool(
            resolvedGradeRepairParallelism(),
            runnable -> {
                Thread thread = new Thread(runnable, "RoadWeaver-GradeRepair");
                thread.setDaemon(true);
                return thread;
            });

    /** 线程池初始化时解析修坡并行度，后续配置变化不能假称立即重建此池。 */
    static int resolvedGradeRepairParallelism() {
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

            PathfindingCostConfig fine = config.withPathGridStep(2);
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
                    LOGGER.warn("【纵坡修复任务失败，跳过当前窗口】 " + "[ROAD-GRADE-POOL] grade repair task failed, window will be skipped: {}",
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

    /** 每个独立修坡窗口使用一格搜索，不对全路重新细化。 */
    static GradePlan computeGradePlan(ServerLevel level,
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
                LOGGER.warn("【精确纵坡修复无法替换陡崖窗口】 " + "RoadWeaver accurate grade repair could not replace cliff window start={} end={} rise={} length={}",
                        violation.start(), violation.end(), violation.rise(), format(violation.length()));
                return null;
            }
            logLocalSearchDiag("grade-accepted");
            LOGGER.info("【精确纵坡修复已接受】 " + "Accepted accurate RoadWeaver grade repair: rise={} over {} blocks, nodes={} -> {}",
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
    static List<BlockPos> solveGradeProfileWindow(List<BlockPos> original,
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

    record GradePlan(int startIndex, int endIndex, List<BlockPos> candidate) {
    }

    /** 0.9.062（④）：{@code [REPAIR-NULL]} 累计次数（只读观测 + 单测断言）。 */
    static final java.util.concurrent.atomic.AtomicLong REPAIR_NULL_SEEN =
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
    static String windowLabel(int startIndex, int endIndex) {
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
            LOGGER.info("【局部修复未产生路径及原路保留状态】 " + "[REPAIR-NULL] #{} window={} reason={} keptOriginal={} keptNodes={} searchedNodes={}",
                    seen, window, reason, previous != null,
                    previous == null ? 0 : previous.size(),
                    searched == null ? 0 : searched.size());
        }
        return previous;
    }

    /** 真实施工高度冲突只在有限窗口改XZ，避免中心坡度合法却接受深挖或高填方。
 *
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
            PathfindingCostConfig fine = config.withPathGridStep(2);
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
                LOGGER.info("【施工前高度局部绕行已接受】 " + "RoadWeaver accepted pre-construction height reroute conflictSegment={} "
                                + "window={}..{} margin={} nodes={} -> {}",
                        conflictSegment, startIndex, endIndex, margin, original.size(), candidate.size());
                return List.copyOf(replaced);
            }
            if (margin >= maxMargin) break;
        }
        logLocalSearchDiag("height-reroute-no-bypass");
        LOGGER.warn("【施工高度绕行未找到旁路，保留原缺口】 " + "RoadWeaver construction-height reroute no bypass; keeping original gap "
                + "connection={}->{} conflictSegment={} rise={} triedMargin={}..{}",
                connection.from(), connection.to(), conflictSegment,
                path.get(conflictSegment).getY(), startMargin, maxMargin);
        return path;
    }

    static int heightAnomalyDetourMaxBlocks() {
        try {
            return ConfigService.get().planning().heightAnomalyDetourMaxBlocks();
        } catch (Throwable ignored) {
            return HEIGHT_ANOMALY_DETOUR_BLOCKS;
        }
    }


    /** 湖泊局部绕行仅由河流引擎的lakeAt判据触发；河道仍优先桥梁跨越。
 *
     * Reroutes only the local window occupied by a Streams lake. River
     * channels remain governed by the existing bridge logic; this predicate
     * is based exclusively on RiverEngine.lakeAt().
     */
    static BlockPos conflictCenter(List<BlockPos> path, Violation conflict) {
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

    /** 同连接的已准备冲突圆窗可复用已完成快照Future，避免每次外扩半径都重复发现结构。
 *
     * Same connection + same conflict circle + already-prepared snapshot →
     * reuse the completed future. Local repair calls this once per detour
     * radius and lake cycle; without the cache every repeat re-ran the
     * circle's structure discovery. Mirrors the path-based dedup in
     * PathFinalizeOps.refreshPlanningSnapshot.
     */
    record CircleSnapshotEntry(long centerKey, int radius,
                                       java.util.concurrent.CompletableFuture<Void> future) {}

    static final java.util.concurrent.ConcurrentHashMap<net.shiroha233.roadweaver.planning.path.PlannedPathKey, CircleSnapshotEntry>
            CIRCLE_SNAPSHOT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 快照等待按采样等待派生且限制在明确范围，避免永久阻塞。 */
    static long snapshotWaitTimeoutMs() {
        try {
            long samplingTimeoutMs = Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
            return Math.max(5_000L, Math.min(120_000L, samplingTimeoutMs * 10L));
        } catch (Throwable ignored) {
            return 30_000L;
        }
    }

    static void awaitLocalStructureCircle(ServerLevel level,
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

    /** 只有已完成圆窗完整覆盖新请求时才命中，不能靠版本号或相同中心自证覆盖。 */
    static boolean circleCoveredBy(CircleSnapshotEntry cached,
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

    static int localWindowRise() {
        return ConfigService.get().planning().heightTunnelThresholdBlocks();
    }

    static boolean riverRepairBypassPrecheck() {
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
    static boolean channelSeparatesWindow(Bounds bounds, RiverLookup channels) {
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

    static int heightRepairContextBlocks() {
        return ConfigService.get().planning().heightRepairContextBlocks();
    }

    static int tunnelDepthBlocks() {
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
    static void logGradeDiagnostics(List<BlockPos> path, Violation violation,
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
        LOGGER.info("【纵坡超限窗口的计划高度与地形高度诊断】 " + "RoadWeaver grade violation diagnostics: rise={} over={} window={}..{} "
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


    /**
     * 0.9.059（③）：{@code [PLAN-LENGTH-CAP]} 诊断行。
     *
     * <p>只在"确实剪过节点"时打 INFO（前 40 次 + 之后每 200 次），否则降级为 DEBUG ——
     * 否则每条路每次局部搜索都打一行会把日志淹掉。{@code pathLen=-1} 表示这一趟没求出解。</p>
     *
     * <p>0.9.062（①）：把当次**真正用的** {@code steep} 与 {@code factor} 一起打出来。
     * 没有这两个字段，验收时无法区分"界放宽了但没剪到"和"界根本没放宽"。</p>
     */
    static List<BlockPos> reconstruct(Node goal) {
        List<BlockPos> result = new ArrayList<>();
        for (Node node = goal; node != null; node = node.parent()) {
            result.add(new BlockPos(node.x(), node.y(), node.z()));
        }
        Collections.reverse(result);
        return result;
    }

    /** 0.9.009：该列是否"水里"（水面高于河床）。 */
    static double heuristic(int x, int z, int goalX, int goalZ) {
        return Math.hypot(goalX - x, goalZ - z);
    }

    static double routeLength(List<BlockPos> path) {
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
    static double routeClimb(List<BlockPos> path) {
        if (path == null || path.size() < 2) return 0.0D;
        double result = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            result += Math.abs(path.get(i).getY() - path.get(i - 1).getY());
        }
        return result;
    }

    /** 竖向 1 格折算成多少「等效水平格」——与 A* 代价里的 {@code rise × 8} 同口径。 */
    static final double VERTICAL_EQUIV_BLOCKS = 8.0D;

    static double horizontalDistance(BlockPos a, BlockPos b) {
        return Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
    }

    static int nearestGrid(int value, int origin, int step) {
        return origin + (int) Math.round((value - origin) / (double) step) * step;
    }

    static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    record Violation(int start, int end, int rise, double length) {
    }

    record RepairSlice(int startIndex, int endIndex,
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
    interface EdgeObstacle {
        EdgeObstacle NONE = (from, to) -> false;

        boolean blocked(BlockPos from, BlockPos to);
    }

    /** 点是否落在冲突盒内（用于"禁止进入建筑盒"的硬绕行重试）。 */
    static boolean pointInsideBox(BoundingBox box, BlockPos pos) {
        if (box == null || pos == null) return false;
        return pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()
                && pos.getY() >= box.minY() && pos.getY() <= box.maxY();
    }

    record StateKey(int x, int y, int z, int direction) {
    }

    /*
     * R7（0.9.003）：{@code length} = 从起点到本节点的**累计水平长度**（与
     * {@code routeLength} 同口径：只累计水平分量，不含垂直），供长度上界剪枝使用。
     */
    /**
     * 0.9.009：{@code wetRun} = 到本节点为止**连续**踩在水里的步数（离开水立刻归零），
     * 用于「过河代价按连续水站数递增」。只加 4 字节/节点，且【不进 StateKey】——
     * 进 key 会让状态数最坏 ×7，不可接受；同状态以不同 wetRun 被去重属可接受近似。
     */
    record Node(int x, int y, int z, int direction, double cost, double length,
                        int wetRun, double score, Node parent) {
        StateKey key() {
            return new StateKey(x, y, z, direction);
        }
    }

}