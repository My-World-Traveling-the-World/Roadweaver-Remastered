/* 文件职责：搜索预算与长度闸门 —— 只回答「还能不能继续搜」「这条路绕得太远没有」。 */
package net.shiroha233.roadweaver.planning.terrain;

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

// 仍留在其它同包类里的常量、代价函数与工具（包内可见）。
import static net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner.*;
import static net.shiroha233.roadweaver.planning.terrain.Bounds.key;
import static net.shiroha233.roadweaver.planning.terrain.LocalRouteSearch.*;
import static net.shiroha233.roadweaver.planning.terrain.ConflictDetector.*;
import static net.shiroha233.roadweaver.planning.terrain.StructureRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair.*;
import static net.shiroha233.roadweaver.planning.terrain.WaterCostModel.*;
import static net.shiroha233.roadweaver.planning.terrain.RepairAcceptance.*;

/**
 * 搜索预算与长度闸门。
 *
 * <h2>两道闸，管的是不同的事</h2>
 * <ul>
 *   <li><b>预算闸</b>（{@code expandedStateLimit} / {@code repairWindowBudgetNanos}）：
 *       局部 A* 最多展开多少状态、最多跑多久。防止一段难修的路把整局拖死。</li>
 *   <li><b>长度闸</b>（{@code lengthCapFor} / {@code withinLengthCap}）：
 *       修出来的路不能比原路长太多。防止「一修就绕远」。</li>
 * </ul>
 *
 * <h2>★ 长度闸为什么相对原长而不是绝对</h2>
 * <p>{@code landDetourCap} 那次的教训（0.9.012 的 P1 真 bug）：闸门上限原来只取
 * 「两点的直线距离」，但局部修复的输入本身就是一条可能已经很绕的路 ——
 * 用直连距离当上限，会判掉本来合格的修复，于是桥又冒出来。
 * 现在的口径是 **max(直连×系数, 原走廊长×1.05)** 两支取较松。</p>
 *
 * <h2>★ 弦采样为什么按弧长而不是按站数</h2>
 * <p>{@code chordProfile} 要判断「沿这条弦走，地形陡不陡」。按站数采样会让
 * 长短路段的采样密度完全不同 —— 长路漏掉陡坡。按弧长采样保证每 {@code chordSampleSpacing}
 * 格一个采样点，**与路长无关**。</p>
 *
 * <p>本类是从 1631 行的 {@code AccurateGradeRepairPlanner} 里<b>纯搬迁</b>出来的，
 * 阈值与算法一字未改（含缩进）。</p>
 */
final class SearchBudgetPolicy {
    static void recordLengthCapDiag(double cap, double factor, boolean steep,
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

    static String fmt1(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 0.8.326：局部搜索诊断（纯观测）。最后一次局部 A* 的展开数/预算/耗时。 */
    static final ThreadLocal<String> LAST_LOCAL_SEARCH_DIAG = new ThreadLocal<>();

    /**
     * 0.8.327：局部结构绕行的隧道下界深度。
     *
     * <p>真机 20260909 19.53 存档实测：局部绕行把下界传成 {@code level.getMinBuildHeight()}
     * 时，{@code tunnelHeights} 会为每个邻居展开约 100 层垂直候选，
     * {@code expanded=90000 limit=90000} 仍要 20~28 秒且三次全部 {@code no_path}。
     * 绕建筑不需要挖到基岩，限制在冲突点下方 {@value #LOCAL_DETOUR_TUNNEL_DEPTH} 格内即可，
     * 垂直层数降到约 13 层。</p>
     */
    static final int LOCAL_DETOUR_TUNNEL_DEPTH = 24;

    static int localDetourTunnelFloor(ServerLevel level, BlockPos center) {
        int worldFloor = level == null ? 0 : level.getMinBuildHeight();
        int localFloor = (center == null ? 0 : center.getY()) - LOCAL_DETOUR_TUNNEL_DEPTH;
        return Math.max(worldFloor, localFloor);
    }

    static void recordLocalSearchDiag(int expanded, int limit, boolean tunnel,
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
    static final double LOCAL_REPAIR_ACCEPT_SLACK_BLOCKS = 200.0D;

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
    static final java.util.concurrent.atomic.AtomicLong LENGTH_CAP_SEEN =
            new java.util.concurrent.atomic.AtomicLong();
    /** 0.9.059（③）：被长度上界剪掉的邻居节点累计数（只读观测）。 */
    static final java.util.concurrent.atomic.AtomicLong LENGTH_CAP_PRUNED =
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
    static double lengthBoundFor(List<BlockPos> original) {
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
    static int chordSamples(double chord, int step) {
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
    static boolean lengthBoundPruneEnabled() {
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
    static void logLocalSearchDiag(String stage) {
        LOGGER.info("RoadWeaver local search diag stage={} {}", stage, consumeLocalSearchDiag());
    }

    static String consumeLocalSearchDiag() {
        String diag = LAST_LOCAL_SEARCH_DIAG.get();
        LAST_LOCAL_SEARCH_DIAG.remove();
        return diag == null ? "diag=none" : diag;
    }

    static int[] tunnelHeights(int terrainY, int horizontalStep, int tunnelFloorY) {
        int verticalStep = Math.max(1, Math.min(2, horizontalStep));
        int depth = tunnelFloorY == Integer.MIN_VALUE
                ? tunnelDepthBlocks()
                : Math.max(0, terrainY - tunnelFloorY);
        int levels = depth / verticalStep + 1;
        int[] result = new int[levels];
        for (int i = 0; i < levels; i++) result[i] = terrainY - i * verticalStep;
        return result;
    }

    static int expandedStateLimit(int sampledColumns, boolean allowTunnel, int horizontalStep) {
        return expandedStateLimit(sampledColumns, allowTunnel, horizontalStep, false);
    }

    static int expandedStateLimit(int sampledColumns, boolean allowTunnel, int horizontalStep,
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

    static int repairWindowStateLimit() {
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
    static long repairWindowBudgetNanos() {
        try {
            return Math.max(1_000L, Math.min(600_000L,
                    ConfigService.get().planning().gradeRepairWindowBudgetMillis())) * 1_000_000L;
        } catch (Throwable ignored) {
            return DEFAULT_LOCAL_SEARCH_BUDGET_NANOS;
        }
    }
}
