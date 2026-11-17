/* 文件职责：组织普通道路的精确量化寻路与最终路径段生成。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair.ExactStructureRepairOutcome;
import net.shiroha233.roadweaver.planning.terrain.LakeRiverRepair;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccuratePathHeightResolver;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPipeline;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;
import net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicy;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.snapToGrid;
import net.shiroha233.roadweaver.postprocess.SplineHelper;
import static net.shiroha233.roadweaver.pathlogic.pathcalc.PathFinalizeOps.*;
import static net.shiroha233.roadweaver.pathlogic.pathcalc.PathMetricsOps.*;

/**
 * 道路路径计算器
 */
public final class RoadPathCalculator {
    static final RoadTerrainPlanningPipeline TERRAIN_PLANNING = new RoadTerrainPlanningPipeline();
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    static final int[] LOCAL_LAKE_DETOUR_BLOCKS = RoutePrecisionRetryPolicy.lakeDetourBlocks();
    static final int[] LOCAL_STRUCTURE_DETOUR_BLOCKS =
            RoutePrecisionRetryPolicy.structureDetourBlocks();
    static final int RIVER_LOCAL_DETOUR_BLOCKS = 50;
    static final int MAX_EXACT_CONSTRAINT_CYCLES = 4;
    /** 深凹地连续性修复使用独立阈值，不能把所有普通填方都当峡谷。 */
    static final int MAX_CONSTRUCTION_FILL_BEFORE_REPAIR = RoadConstructionSafety.MAX_FILL_DEPTH;
    /**
     * 中心线<b>挖方</b>的可建造上限。0.9.087：与施工端同源。
     *
     * <p>真机依据：5 条 initial connection 失败里，2 条是「路要穿过比它高 19 / 25 格的山脊」
     * （{@code seg=816 cut=19}、{@code seg=2200 cut=25}）。旧值 8 把它们当「canyon」整条丢弃，
     * 而施工端 {@code MAX_LOCAL_CENTER_CUT_DEPTH} 现在能建 32 格的<b>路堑</b>。
     * 两处必须同源，否则规划期放过了、施工期照样建不出来。</p>
     */
    static final int MAX_BUILDABLE_CENTER_CUT_DEPTH =
            RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH;

    /**
     * 中心线<b>填方</b>的可建造上限。
     *
     * <p>填方必须比挖方严得多：填 8 格以上就是高堤，路面悬在填土堆上、
     * 两侧陡坡，视觉突兀且一列没填实就是一个洞。挖方则是山体开槽（路堑），
     * 路面本身仍平顺 —— 两者不可用同一个数。</p>
     */
    static final int MAX_BUILDABLE_CENTER_FILL_DEPTH =
            RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH;
    /** 合流后长隧道链不重跑全路局部A*，避免主线程重复昂贵搜索。 */
    static final int NETWORK_MERGE_LONG_ROUTE_SEGMENTS = 512;
    static final int NETWORK_MERGE_LONG_TUNNEL_SEGMENTS = 64;
    static final int NETWORK_MERGE_INTERFACE_WINDOW = 24;
    /** 靠近端点的冲突允许在既定局部预算内升级半径，不放大成全路搜索。 */
    static final int ENDPOINT_APPROACH_STATIONS = 24;    /**
     * 0.8.340：建筑本地绕行的**累计长度上限**（相对进入修复时的原始长度）。
     *
     * <p>真机取证（20260911 09_02，连接 {@code (1888,-1344)->(2880,-2064)}）：进入修复时
     * 路长 624 站，修复后暴涨到 3225 站，再由 {@code landedAtStructureBoundary} 裁剪到 3042 站；
     * 随后对 3042 站重算坡度、验证 47563 列地形。直线距离约 1230 格，却绕行了约 2400 格，
     * 远超 {@code structureDetourRadiusMaxBlocks=400} 的语义。</p>
     *
     * <p><b>0.8.342 修正成因描述</b>（独立审计指出原描述与日志不符，已核对属实）：
     * 原注释写「逐级放大半径累积 624→3225→3042」是**不准确**的 —— 日志实际显示
     * 该连接**只用过 {@code radius=50} 一轮且被 accepted**，并未出现 100/150/200… 的逐级放大；
     * 即<b>单轮 radius=50 就足以从 624 暴涨到 3225</b>，真正的放大点是
     * {@code landedAtStructureBoundary} 的裁剪/重建，而非多轮半径累积。
     * 循环里 {@code working = List.copyOf(exact)} 的累积效应确实存在（多轮时会继续变长），
     * 但不是本 case 的主因。</p>
     *
     * <p><b>0.8.342 同时修正收益量级</b>：原注释称「整段白费 272 秒」**无法在日志中对应**
     * —— 该连接 worker 段约 9 秒（{@code finalValidationMs=4926} 级）；
     * 日志里唯一约 274 s 的数字是**另一阶段的粗地形加载** {@code wallMs=274334}，
     * 属不同子系统，不应混为一谈。本闸门的真实收益是省下
     * 「3042 站坡度重算 + 47563 列全列校验」（秒级到十秒级），而非 272 秒。</p>
     *
     * <p>闸门语义：某轮修复结果若超过「进入修复时的原长 × 本值」，就不再接受该结果、
     * 也不再继续放大半径 —— 直接保留原路 + 建筑保护缺口返回（这是既有的兜底路径，
     * 不会杀掉路线）。取 1.6 属经验值；注：{@code planning.detourForGradeMaxLengthRatio}
     * 是另一个配置项，本常量并不读取它，两者仅量级相近。</p>
     */
    static final double LOCAL_DETOUR_MAX_LENGTH_RATIO = 1.6D;
    /** 0.8.323：重建成段前允许的最大相邻站点横向间距，与 RoadDataValidator.MAX_CENTER_STEP 对齐。 */
    static final int REBUILD_MAX_CENTER_STEP = 2;
    /** 0.8.324：未平滑版本最多允许比平滑版本长多少倍（超过就保留平滑版）。 */
    static final double UNSMOOTHED_MAX_LENGTH_RATIO = 1.15D;
    static final ConcurrentHashMap<net.shiroha233.roadweaver.planning.path.PlannedPathKey, BlockPos>
            CONSTRUCTION_CONFLICT_HINTS = new ConcurrentHashMap<>();

    record ConstructionContinuity(boolean requiresRepair,
                                          int maxFillDepth,
                                          int maxCutDepth,
                                          int conflictSegment,
                                          int sampledColumns,
                                          int[] minimumTerrainBySegment) {
    }

    private RoadPathCalculator() {}

    /** 长桥隧合流只归一局部路口坡度并终验，短普通路仍保留完整修坡能力。
 *
     * Long tunnel/bridge networks were already height-checked before they
     * entered the merge pass. Re-running the 100-block 3D repair after every
     * junction rewrite can monopolize the server thread for minutes. Such
     * routes only need junction-grade normalization and final validation; a
     * short ordinary route still receives the full height repair.
     */
    public static boolean shouldSkipExpensiveNetworkMergeHeightRepair(RoadData road) {
        if (road == null || road.roadSegmentList() == null) return false;
        if (road.roadSegmentList().size() >= NETWORK_MERGE_LONG_ROUTE_SEGMENTS) return true;
        if (road.spans() == null) return false;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.putIfAbsent(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null
                    || (span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.BRIDGE)) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start != null && end != null
                    && Math.abs(start - end) >= NETWORK_MERGE_LONG_TUNNEL_SEGMENTS) return true;
        }
        return false;
    }

    public static RoadData repairAfterNetworkMerge(RoadData road,
                                                    ServerLevel level,
                                                    TerrainSamplingCache cache,
                                                    StructureConnection connection) {
        // 0.9.027（N-06）：真机上 merge refine 里单条路的 repairMs=74,563 ms（2292 站 → 2292 站，
        // 块数一格没变）吃掉了 refineMs 的 94%，但日志只有总数、说不出钱花在哪。
        // 这里把一条链的三段拆开：短路径判定 / 连续性扫描 / 真正的高度修复。
        // changed= 是最关键的一列 —— 为 0 就说明这 74 秒确实是白跑。
        long startedAt = System.nanoTime();
        boolean shortcut = shouldSkipExpensiveNetworkMergeHeightRepair(road);
        long shortcutMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (shortcut) {
            // Long routes may skip the full A* pass only when their complete
            // footprint is still construction-safe.  A junction rewrite can
            // change targetY outside the interface window; blindly skipping
            // this check allowed 20-30 block fills to reach worldgen as an
            // ordinary road, which then became a canyon when the paver refused
            // the unsafe stations.  The continuity scan is linear and cheap
            // compared with a second route search, and escalates only when a
            // real deep cut/fill is present.
            long continuityStartedAt = System.nanoTime();
            ConstructionContinuity continuity = inspectConstructionContinuity(road, level, cache);
            long continuityMs = (System.nanoTime() - continuityStartedAt) / 1_000_000L;
            if (continuity.requiresRepair()) {
                LOGGER.warn("【高度冲突未解决，跳过长路网合并捷径并继续修复】 " + "RoadWeaver overriding long-network merge shortcut for unresolved height conflict "
                                + "segments={} maxFillDepth={} maxCutDepth={} conflictSegment={} owners={} -> {}",
                        road.roadSegmentList() == null ? 0 : road.roadSegmentList().size(),
                        continuity.maxFillDepth(), continuity.maxCutDepth(), continuity.conflictSegment(),
                        road.ownerA2dKey(), road.ownerB2dKey());
                long repairStartedAt = System.nanoTime();
                RoadData repaired = repairFinalHeightConflict(road, level, cache, connection);
                logMergeRepairTiming("conflict", road, repaired, shortcutMs, continuityMs,
                        (System.nanoTime() - repairStartedAt) / 1_000_000L,
                        (System.nanoTime() - startedAt) / 1_000_000L);
                return repaired;
            }
            long interfaceStartedAt = System.nanoTime();
            RoadData changed = repairNetworkMergeInterfaces(road, level, cache, connection);
            logMergeRepairTiming("interfaces", road, changed, shortcutMs, continuityMs,
                    (System.nanoTime() - interfaceStartedAt) / 1_000_000L,
                    (System.nanoTime() - startedAt) / 1_000_000L);
            return changed;
        }
        long repairStartedAt = System.nanoTime();
        RoadData repaired = repairFinalHeightConflict(road, level, cache, connection);
        logMergeRepairTiming("final-conflict", road, repaired, shortcutMs, -1L,
                (System.nanoTime() - repairStartedAt) / 1_000_000L,
                (System.nanoTime() - startedAt) / 1_000_000L);
        return repaired;
    }

    /**
     * N-06 的分段计时行。{@code changed} 是 {@code targetY} 真正变了的站数 ——
     * 这是「这趟到底有没有干活」唯一的直接证据（2292 → 2292 但 changed 未知时无法判断）。
     * 纯日志 + 一次线性 diff，不参与任何决策。
     */
    static void logMergeRepairTiming(String path, RoadData before, RoadData after,
                                             long shortcutMs, long continuityMs,
                                             long repairMs, long totalMs) {
        int nodesBefore = before == null || before.targetY() == null ? 0 : before.targetY().size();
        int nodesAfter = after == null || after.targetY() == null ? 0 : after.targetY().size();
        int changed = 0;
        if (before != null && after != null && before.targetY() != null && after.targetY() != null) {
            int n = Math.min(nodesBefore, nodesAfter);
            for (int i = 0; i < n; i++) {
                if (!java.util.Objects.equals(before.targetY().get(i), after.targetY().get(i))) changed++;
            }
        }
        LOGGER.info("【合并路线修复各阶段耗时】 " + "RoadWeaver merge repair timing path={} nodes={} -> {} changed={} "
                        + "shortcutMs={} continuityMs={} repairMs={} totalMs={}",
                path, nodesBefore, nodesAfter, changed, shortcutMs, continuityMs, repairMs, totalMs);
    }

    /** 只检查合流及共享端点窗口，已保存长隧道主体不再做第二次全路高度搜索。
 *
     * Checks only the small windows where a merged route touches another road
     * or a shared endpoint. The long tunnel body is already persisted and must
     * not be sent through a second full-route height A* on the server thread.
     */
    static RoadData repairNetworkMergeInterfaces(RoadData road,
                                                          ServerLevel level,
                                                          TerrainSamplingCache cache,
                                                          StructureConnection connection) {
        if (road == null || level == null || cache == null
                || road.roadSegmentList() == null || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()) return road;
        int size = road.roadSegmentList().size();
        Set<Integer> interfaces = new HashSet<>();
        interfaces.add(0);
        interfaces.add(size - 1);
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < size; i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.putIfAbsent(xzKey(segment.middlePos()), i);
        }
        if (road.spans() != null) {
            for (RoadSpan span : road.spans()) {
                if (span == null || span.start() == null || span.end() == null) continue;
                if (span.type() != net.shiroha233.roadweaver.core.model.SpanType.NETWORK_REUSE
                        && span.type() != net.shiroha233.roadweaver.core.model.SpanType.JUNCTION_ANCHOR) continue;
                Integer start = indices.get(xzKey(span.start()));
                Integer end = indices.get(xzKey(span.end()));
                if (start != null) interfaces.add(start);
                if (end != null) interfaces.add(end);
            }
        }

        List<Integer> ordered = new ArrayList<>(interfaces);
        ordered.sort(Integer::compareTo);
        List<Integer> workingHeights = new ArrayList<>(road.targetY());
        boolean changed = false;
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        for (int junctionIndex : ordered) {
            int from = Math.max(0, junctionIndex - NETWORK_MERGE_INTERFACE_WINDOW);
            int to = Math.min(size - 1, junctionIndex + NETWORK_MERGE_INTERFACE_WINDOW);
            List<BlockPos> local = new ArrayList<>(to - from + 1);
            for (int i = from; i <= to; i++) {
                RoadSegmentPlacement segment = road.roadSegmentList().get(i);
                if (segment != null && segment.middlePos() != null) {
                    local.add(new BlockPos(segment.middlePos().getX(), workingHeights.get(i),
                            segment.middlePos().getZ()));
                }
            }
            if (local.size() < 3) continue;
            List<BlockPos> repaired = AccurateGradeRepairPlanner.repair(
                    local, level, accurate,
                    RoadGenerationConfig.from(ConfigService.get()).pathfinding().snapshot(), connection);
            if (repaired == null || repaired.size() != local.size()) continue;
            for (int offset = 0; offset < repaired.size(); offset++) {
                int index = from + offset;
                int y = repaired.get(offset).getY();
                if (workingHeights.get(index) != y) {
                    workingHeights.set(index, y);
                    changed = true;
                }
            }
        }
        if (!changed) {
            LOGGER.info("【合并路线接口局部检查完成】 " + "RoadWeaver checked merged route interfaces locally: interfaces={} window={} segments={} owners={} -> {}",
                    ordered.size(), NETWORK_MERGE_INTERFACE_WINDOW, size,
                    road.ownerA2dKey(), road.ownerB2dKey());
            return road;
        }
        List<BlockPos> profile = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos center = road.roadSegmentList().get(i).middlePos();
            profile.add(new BlockPos(center.getX(), workingHeights.get(i), center.getZ()));
        }
        RoadData rebuilt = rebuildRoadDataFromProfile(road, profile, level, cache, connection);
        LOGGER.info("【合并路线接口局部修复完成】 " + "RoadWeaver repaired merged route interfaces locally: interfaces={} window={} segments={} owners={} -> {}",
                ordered.size(), NETWORK_MERGE_INTERFACE_WINDOW, size,
                road.ownerA2dKey(), road.ownerB2dKey());
        return rebuilt == null ? road : rebuilt;
    }


    /** 记录修复后实际端高以跟踪历史锁高问题；不把旧故障描述为当前必现。 */
    static void logRepairEndpoint(String stage, RoadData road, StructureConnection connection) {
        if (road == null || road.targetY() == null || road.targetY().isEmpty()
                || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()
                || !LOGGER.isInfoEnabled()) return;
        int last = road.targetY().size() - 1;
        BlockPos head = road.roadSegmentList().get(0) == null
                ? null : road.roadSegmentList().get(0).middlePos();
        BlockPos tail = road.roadSegmentList().get(last) == null
                ? null : road.roadSegmentList().get(last).middlePos();
        LOGGER.info("【端点修复后的纵断面诊断】 " + "RoadWeaver endpoint repair profile stage={} connection={}->{} stations={} "
                        + "profileStart={} startPos=({},{}) profileEnd={} endPos=({},{})",
                stage,
                connection == null ? "?" : connection.from(),
                connection == null ? "?" : connection.to(),
                road.targetY().size(),
                road.targetY().get(0),
                head == null ? -1 : head.getX(), head == null ? -1 : head.getZ(),
                road.targetY().get(last),
                tail == null ? -1 : tail.getX(), tail == null ? -1 : tail.getZ());
    }

    public static RoadData repairFinalHeightConflict(RoadData road,
                                                       ServerLevel level,
                                                       TerrainSamplingCache cache,
                                                       StructureConnection connection) {
        RoadData result = repairFinalHeightConflictInternal(road, level, cache, connection);
        // 0.8.368 (T2 diagnosis): a route whose endpoint sits inside a TUNNEL
        // span logged {@code startY=407} but carried targetY[0]=389, while the
        // other endpoint matched its lock exactly. This wrapper reports the real
        // profile ends before and after the repair so one run shows whether the
        // boundary lock is lost inside this method or elsewhere. Diagnosis only.
        logRepairEndpoint("exit", result == null ? road : result, connection);
        return result;
    }

    static RoadData repairFinalHeightConflictInternal(RoadData road,
                                                       ServerLevel level,
                                                       TerrainSamplingCache cache,
                                                       StructureConnection connection) {
        if (road == null || level == null || cache == null || connection == null
                || road.roadSegmentList() == null || road.targetY() == null
                || road.targetY().size() != road.roadSegmentList().size()) return road;
        ConstructionContinuity continuity = inspectConstructionContinuity(road, level, cache);
        if (RoadDataValidator.validateFinalRoad(road).valid() && !continuity.requiresRepair()) return road;

        RoadGenerationConfig cfg = RoadGenerationConfig.from(ConfigService.get());
        PathfindingCostConfig fine = cfg.pathfinding().snapshot();
        fine.setAStarStep(1);
        fine.sanitize();
        List<BlockPos> profile = new ArrayList<>(road.roadSegmentList().size());
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            Integer y = road.targetY().get(i);
            if (segment == null || segment.middlePos() == null || y == null) return road;
            BlockPos p = segment.middlePos();
            profile.add(new BlockPos(p.getX(), y, p.getZ()));
        }
        List<BlockPos> originalProfile = List.copyOf(profile);

        // Construction continuity is the first hard height gate.  If the
        // footprint sample finds a deep cut/fill, reroute the local X/Z
        // corridor before falling back to a Y-only profile adjustment.
        boolean[] physicalBypass = physicalBypassMask(road);
        boolean conflictIsPhysicalSpan = continuity.conflictSegment() >= 0
                && continuity.conflictSegment() < physicalBypass.length
                && physicalBypass[continuity.conflictSegment()];
        if (continuity.requiresRepair() && continuity.conflictSegment() >= 0
                && !conflictIsPhysicalSpan) {
            List<BlockPos> rerouted = AccurateGradeRepairPlanner.rerouteConstructionHeightConflict(
                    originalProfile, continuity.conflictSegment(), level,
                    cache.getAccurateSampler(level), fine, connection);
            if (rerouted != null && !rerouted.equals(originalProfile)) {
                RoadData reroutedRoad = rebuildRoadDataFromProfile(road, rerouted, level, cache, connection);
                if (reroutedRoad != null) {
                    ConstructionContinuity reroutedContinuity = inspectConstructionContinuity(
                            reroutedRoad, level, cache);
                    if (!reroutedContinuity.requiresRepair()) {
                        CONSTRUCTION_CONFLICT_HINTS.remove(
                                net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection));
                        LOGGER.info("【施工前高度绕行已接受】 " + "RoadWeaver accepted pre-construction height reroute "
                                        + "maxFillDepth={} maxCutDepth={} conflictSegment={}",
                                continuity.maxFillDepth(), continuity.maxCutDepth(), continuity.conflictSegment());
                        return reroutedRoad;
                    }
                    LOGGER.warn("【施工前高度绕行仍不安全】 " + "RoadWeaver pre-construction height reroute still unsafe "
                                    + "maxFillDepth={} maxCutDepth={} conflictSegment={}",
                            reroutedContinuity.maxFillDepth(), reroutedContinuity.maxCutDepth(),
                            reroutedContinuity.conflictSegment());
                } else {
                    // Logging-only diagnostic: the detour profile could not be
                    // rebuilt into road segments.
                    LOGGER.warn("【高度绕行后的道路重建失败】 " + "RoadWeaver height reroute rebuild failed conflictSegment={} "
                                    + "nodes={} reason={} owners={} -> {}",
                            continuity.conflictSegment(), rerouted.size(),
                            consumeRebuildFailureReason(),
                            road.ownerA2dKey(), road.ownerB2dKey());
                }
            } else {
                // Logging-only diagnostic: reroute returned null or kept the
                // original corridor unchanged.
                LOGGER.warn("【高度绕行未产生可用替代走廊】 " + "RoadWeaver height reroute unavailable conflictSegment={} "
                                + "reason={} owners={} -> {}",
                        continuity.conflictSegment(),
                        rerouted == null ? "null_reroute" : "unchanged_corridor",
                        road.ownerA2dKey(), road.ownerB2dKey());
            }
        } else if (continuity.requiresRepair() && conflictIsPhysicalSpan) {
            // Logging-only diagnostic: the conflict sits on a physical span
            // (bridge/tunnel/reuse/gap), so the height reroute is skipped.
            LOGGER.info("【冲突位于物理跨度上，跳过高度绕行】 " + "RoadWeaver height reroute skipped: conflict lies on a physical span "
                            + "conflictSegment={} maxFillDepth={} maxCutDepth={} owners={} -> {}",
                    continuity.conflictSegment(), continuity.maxFillDepth(), continuity.maxCutDepth(),
                    road.ownerA2dKey(), road.ownerB2dKey());
        }

        List<BlockPos> continuityProfile = originalProfile;
        if (continuity.requiresRepair()) {
            boolean[] repairBypass = physicalBypassMask(road);
            continuityProfile = continuity.maxCutDepth() > continuity.maxFillDepth()
                    ? raiseProfileToSafeCut(originalProfile, continuity.minimumTerrainBySegment(), repairBypass)
                    : lowerProfileToSafeFill(originalProfile, continuity.minimumTerrainBySegment(), repairBypass);
        }
            if (continuity.requiresRepair()) {
                BlockPos conflict = conflictPosition(road, continuity.conflictSegment());
                int target = conflict == null || continuity.conflictSegment() < 0
                        || continuity.conflictSegment() >= road.targetY().size()
                        ? Integer.MIN_VALUE : road.targetY().get(continuity.conflictSegment());
                int terrain = continuity.conflictSegment() < 0
                        || continuity.conflictSegment() >= continuity.minimumTerrainBySegment().length
                        ? Integer.MAX_VALUE
                        : continuity.minimumTerrainBySegment()[continuity.conflictSegment()];
                FillConflictWidth width = conflictWidth(road, continuity);
                LOGGER.info("【施工连续性预检的挖填与冲突位置】 " + "RoadWeaver continuity preflight: sampledColumns={} maxFillDepth={} maxCutDepth={} "
                            + "conflictSegment={} conflictPos={} targetY={} terrainY={} unsafeFillRange={} "
                            + "unsafeWidthBlocks={} owners={} -> {}",
                        continuity.sampledColumns(), continuity.maxFillDepth(), continuity.maxCutDepth(),
                        continuity.conflictSegment(), conflict, target, terrain,
                        width.start(), width.end(), formatWidth(width.widthBlocks()),
                        road.ownerA2dKey(), road.ownerB2dKey());
            }
        List<BlockPos> repaired = AccurateGradeRepairPlanner.repair(
                continuityProfile, level, cache.getAccurateSampler(level), fine, connection);
        if (repaired == null || repaired.size() < 2) repaired = continuityProfile;
        if (repaired.equals(originalProfile)) {
            if (continuity.requiresRepair()) {
                if (isBuildableContinuity(continuity)) {
                    CONSTRUCTION_CONFLICT_HINTS.remove(
                            net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection));
                    BlockPos bConflict = conflictPosition(road, continuity.conflictSegment());
                    LOGGER.info("【已接受可施工的局部中心纵坡】 " + "RoadWeaver accepted buildable local centre grade owners={} -> {} "
                                    + "maxCutDepth={} maxFillDepth={} conflictSegment={} conflictPos=({},{})",
                            road.ownerA2dKey(), road.ownerB2dKey(), continuity.maxCutDepth(),
                            continuity.maxFillDepth(), continuity.conflictSegment(),
                            bConflict == null ? "?" : bConflict.getX(),
                            bConflict == null ? "?" : bConflict.getZ());
                    return road;
                }
                BlockPos conflict = conflictPosition(road, continuity.conflictSegment());
                LOGGER.warn("【施工连续性冲突未解决，拒绝路线】 " + "RoadWeaver rejected route with unresolved construction continuity conflict "
                                + "owners={} -> {} maxFillDepth={} conflictSegment={} conflictPos={}",
                        road.ownerA2dKey(), road.ownerB2dKey(), continuity.maxFillDepth(),
                        continuity.conflictSegment(), conflict);
                return null;
            }
            return road;
        }

        RoadData candidate = rebuildRoadDataFromProfile(road, repaired, level, cache, connection);
        if (candidate == null) {
            // Logging-only diagnostic: the repaired profile could not be
            // rebuilt into road segments; the original road is returned.
            LOGGER.warn("【连续性修复后的道路重建失败】 " + "RoadWeaver continuity repair rebuild failed owners={} -> {} conflictSegment={} "
                            + "nodes={} reason={}",
                    road.ownerA2dKey(), road.ownerB2dKey(), continuity.conflictSegment(), repaired.size(),
                    consumeRebuildFailureReason());
            return road;
        }
        List<RoadSegmentPlacement> rebuilt = candidate.roadSegmentList();
        candidate = RoadDataValidator.repairFinalHeightSteps(
                RoadDataValidator.normalizeDuplicateJunctions(candidate));
        RoadDataValidator.ValidationResult candidateValidation =
                RoadDataValidator.validateFinalRoad(candidate);
        if (!candidateValidation.valid()) {
            // Logging-only diagnostic: the repaired candidate failed final
            // structural validation.
            LOGGER.warn("【连续性修复候选数据无效】 " + "RoadWeaver continuity repair candidate invalid reason={} index={} owners={} -> {}",
                    candidateValidation.reason(), candidateValidation.index(),
                    road.ownerA2dKey(), road.ownerB2dKey());
            return continuity.requiresRepair() ? null : road;
        }
        ConstructionContinuity repairedContinuity = inspectConstructionContinuity(candidate, level, cache);
        // 0.9.087（第 1 层：修复合约）：**修复只许变好，不许变差**。
        //
        // 真机取证（存档 2026-11-11，半径 128）：5 条 initial connection 因
        // construction_continuity_repair_failed 被丢弃，其中 3 条
        // （conflictSegment=983 / 1265 / 2063）没有 continuity preflight 日志 ——
        // 即进来时 requiresRepair 就是 false，剖面本来就合格。
        // 但下面 AccurateGradeRepairPlanner.repair() 是**无条件执行**的，
        // 它把合格的剖面改坏（cut 越过上限），然后这条链因为「修完还是不达标」
        // 把整条路 return null 丢掉。
        //
        // 契约：修复前能建、修复后不能建 ⇒ 判定为修复本身有害，退回原始剖面。
        if (repairedContinuity.requiresRepair()
                && !isBuildableContinuity(repairedContinuity)
                && isBuildableContinuity(continuity)) {
            LOGGER.info("【修复使原可施工路线失效，保留原纵断面】 " + "RoadWeaver kept original profile because repair made a buildable route unbuildable "
                            + "owners={} -> {} before(cut={},fill={}) after(cut={},fill={}) conflictSegment={}",
                    road.ownerA2dKey(), road.ownerB2dKey(),
                    continuity.maxCutDepth(), continuity.maxFillDepth(),
                    repairedContinuity.maxCutDepth(), repairedContinuity.maxFillDepth(),
                    repairedContinuity.conflictSegment());
            return road;
        }
        if (repairedContinuity.requiresRepair()) {
            if (isBuildableContinuity(repairedContinuity)) {
                CONSTRUCTION_CONFLICT_HINTS.remove(
                        net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection));
                BlockPos bConflict = conflictPosition(road, repairedContinuity.conflictSegment());
                LOGGER.info("【剩余局部中心纵坡可施工，接受该修复】 " + "RoadWeaver accepted buildable residual local centre grade owners={} -> {} "
                                + "maxCutDepth={} maxFillDepth={} conflictSegment={} conflictPos=({},{}) nodes={} -> {}",
                        road.ownerA2dKey(), road.ownerB2dKey(),
                        repairedContinuity.maxCutDepth(), repairedContinuity.maxFillDepth(),
                        repairedContinuity.conflictSegment(),
                        bConflict == null ? "?" : bConflict.getX(),
                        bConflict == null ? "?" : bConflict.getZ(),
                        road.roadSegmentList().size(), rebuilt.size());
                return candidate;
            }
            BlockPos conflict = conflictPosition(road, repairedContinuity.conflictSegment());
            // 0.9.087：**必须同时打 maxCutDepth**。旧日志只打 maxFillDepth ——
            // 真机 5 条拒绝的 fill 全是 2~3（远低于上限），真正越界的是 cut（19 / 25），
            // 而那个字段根本没打出来，排查时完全看不见。
            LOGGER.warn("【连续性修复不完整且仍超挖填限制，拒绝路线】 " + "RoadWeaver rejected route after incomplete construction continuity repair "
                            + "owners={} -> {} maxCutDepth={} (limit={}) maxFillDepth={} (limit={}) "
                            + "conflictSegment={} conflictPos={}",
                    road.ownerA2dKey(), road.ownerB2dKey(),
                    repairedContinuity.maxCutDepth(), MAX_BUILDABLE_CENTER_CUT_DEPTH,
                    repairedContinuity.maxFillDepth(), MAX_BUILDABLE_CENTER_FILL_DEPTH,
                    repairedContinuity.conflictSegment(), conflict);
            return null;
        }
        CONSTRUCTION_CONFLICT_HINTS.remove(
                net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection));
        LOGGER.info("【局部连续性和高度修复已接受】 " + "RoadWeaver accepted local continuity/height repair owners={} -> {} nodes={} -> {}",
                road.ownerA2dKey(), road.ownerB2dKey(), road.roadSegmentList().size(), rebuilt.size());
        return candidate;
    }

    /** 最终结构修复循环复用剖面重建入口，避免另写一套目标高度计算。 */
    public static RoadData rebuildRoadDataFromProfile(RoadData road,
                                                       List<BlockPos> profile,
                                                       ServerLevel level,
                                                       TerrainSamplingCache cache,
                                                       StructureConnection connection) {
        return rebuildRoadDataFromProfileInternal(road, profile, level, cache, connection);
    }

    /**
     * 0.8.318：重建失败原因（纯观测）。真机每局出现 11+11 次
     * {@code height reroute rebuild failed} / {@code continuity repair rebuild failed}，
     * 但日志只有 owners，看不出是"成段被拒"还是"段数对不上"。这里在失败点记录原因，
     * 调用方紧跟着打出来并清空。线程本地，不跨线程、不参与任何判定。
     */
    static final ThreadLocal<String> LAST_REBUILD_FAILURE = new ThreadLocal<>();

    static String consumeRebuildFailureReason() {
        String reason = LAST_REBUILD_FAILURE.get();
        LAST_REBUILD_FAILURE.remove();
        return reason == null ? "unknown" : reason;
    }

    /** 保存最近精确施工冲突位置，供下一次局部重试使用。 */
    public static void recordConstructionConflictHint(StructureConnection connection, BlockPos position) {
        if (connection == null || position == null) return;
        CONSTRUCTION_CONFLICT_HINTS.put(
                net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection), position);
    }

    static RoadData rebuildRoadDataFromProfileInternal(RoadData road,
                                                       List<BlockPos> profile,
                                                       ServerLevel level,
                                                       TerrainSamplingCache cache,
                                                       StructureConnection connection) {
        if (road == null || profile == null || profile.size() < 2
                || level == null || cache == null) {
            LAST_REBUILD_FAILURE.set("null_input");
            return null;
        }
        // 0.8.323：profile 里存在相邻 3 格的跳变（端点吸附 / 高度重路由造成），
        // 1:1 成段会把它原样带进 RoadData，被 RoadDataValidator 的 MAX_CENTER_STEP=2
        // 判成 invalid-center-step、整条路失败。先在重建前补插中间站再栅格化：
        // 段数依旧与（补点后的）站点序列 1:1，形状与两端位置不变。
        List<BlockPos> stations = PathPostProcessor.densifyStationGaps(profile,
                REBUILD_MAX_CENTER_STEP);
        List<RoadSegmentPlacement> rebuilt = PathPostProcessor.rasterizeStations(stations, road.width());
        if (rebuilt == null || rebuilt.size() != stations.size()) {
            LAST_REBUILD_FAILURE.set(rebuilt == null
                    ? "segments_rejected:" + consumeSegmentFailureReason()
                    : "segment_count_mismatch profile=" + profile.size()
                            + " densified=" + stations.size() + " rebuilt=" + rebuilt.size());
            return null;
        }

        Map<Long, BlockPos> centersByXZ = new HashMap<>();
        for (RoadSegmentPlacement segment : rebuilt) {
            if (segment != null && segment.middlePos() != null) {
                BlockPos center = segment.middlePos();
                centersByXZ.putIfAbsent(xzKey(center), center);
            }
        }
        List<RoadSpan> remappedSpans = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            BlockPos start = centersByXZ.get(xzKey(span.start()));
            BlockPos end = centersByXZ.get(xzKey(span.end()));
            if (start != null && end != null) remappedSpans.add(new RoadSpan(start, end, span.type()));
        }
        List<Integer> targetY = new ArrayList<>(stations.size());
        for (BlockPos p : stations) targetY.add(p.getY());
        return new RoadData(road.width(), road.roadType(), road.materials(),
                road.slabMaterials(), rebuilt, remappedSpans, targetY,
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    /** 读取并消费最后冲突点，避免粗路平坦时无法定位真实宽路面深沟。
 *
     * Returns and consumes the last precise construction-conflict location for
     * a route. Local repair uses this when the coarse path itself looked flat
     * but the final accurate fill preflight found a deep depression.
     */
    public static BlockPos consumeConstructionConflictHint(StructureConnection connection) {
        if (connection == null) return null;
        return CONSTRUCTION_CONFLICT_HINTS.remove(
                net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection));
    }

    public static void clearRuntimeHints() {
        CONSTRUCTION_CONFLICT_HINTS.clear();
    }

    /** 完整占地一次连续性扫描同时返回地形最低值，避免修剖面重复采相同列。
 *
     * One bounded continuity scan for the complete road footprint. The result
     * carries per-segment terrain minima so profile repair never samples the
     * same road columns a second time.
     */
    static ConstructionContinuity inspectConstructionContinuity(RoadData road,
                                                                         ServerLevel level,
                                                                         TerrainSamplingCache cache) {
        int segmentCount = road == null || road.roadSegmentList() == null
                ? 0 : road.roadSegmentList().size();
        int[] minimumTerrain = new int[segmentCount];
        Arrays.fill(minimumTerrain, Integer.MAX_VALUE);
        if (segmentCount == 0 || level == null || cache == null) {
            return new ConstructionContinuity(false, 0, 0, -1, 0, minimumTerrain);
        }

        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            // Continuity is a route-grade property: only the centre station of
            // each segment matters, not the full cross-section. Sampling the
            // whole width at step=1 inflated the precise sample set (7036
            // columns) and re-sampled the entire road footprint on every pass.
            // The prefetch already loaded these centre heights, so this mostly
            // reuses the cache instead of re-sampling the whole road.
            if (segment == null || segment.middlePos() == null) continue;
            BlockPos centre = segment.middlePos();
            unique.putIfAbsent(AccurateHeightSample.key(centre.getX(), centre.getZ()), centre);
        }
        if (unique.isEmpty()) {
            return new ConstructionContinuity(false, 0, 0, -1, 0, minimumTerrain);
        }
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        Map<Long, AccurateHeightSample> samples = accurate.samplePositions(unique.values());
        int maxFill = 0;
        int maxCut = 0;
        int conflictSegment = -1;
        boolean[] bypass = physicalBypassMask(road);
        for (int index = 0; index < segmentCount; index++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(index);
            Integer targetY = road.targetY().get(index);
            if (segment == null || targetY == null || segment.middlePos() == null) continue;
            BlockPos centre = segment.middlePos();
            AccurateHeightSample sample = samples.get(
                    AccurateHeightSample.key(centre.getX(), centre.getZ()));
            if (sample == null) continue;
            int terrainY = accurate.surfaceHeight(sample);
            minimumTerrain[index] = Math.min(minimumTerrain[index], terrainY);
            if (bypass[index]) continue;
            int fillDepth = Math.max(0, targetY - terrainY);
            if (fillDepth > maxFill) {
                maxFill = fillDepth;
                conflictSegment = index;
            }
            int cutDepth = Math.max(0, terrainY - targetY);
            if (cutDepth > maxCut) {
                maxCut = cutDepth;
                if (cutDepth > maxFill) conflictSegment = index;
            }
        }
        boolean requiresRepair = maxFill > MAX_CONSTRUCTION_FILL_BEFORE_REPAIR || maxCut > RoadConstructionSafety.MAX_CUT_DEPTH;
        if (requiresRepair && road.hasOwnerPair() && conflictSegment >= 0
                && conflictSegment < road.roadSegmentList().size()) {
            BlockPos conflict = road.roadSegmentList().get(conflictSegment).middlePos();
            if (conflict != null) {
                CONSTRUCTION_CONFLICT_HINTS.put(
                        net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(
                                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerB2dKey())),
                        conflict);
            }
        }
        return new ConstructionContinuity(
                requiresRepair, maxFill, maxCut, conflictSegment, unique.size(), minimumTerrain);
    }

    static boolean[] physicalBypassMask(RoadData road) {
        int size = road == null || road.roadSegmentList() == null ? 0 : road.roadSegmentList().size();
        boolean[] mask = new boolean[size];
        if (size == 0 || road.spans() == null) return mask;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < size; i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.putIfAbsent(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || (span.type() != net.shiroha233.roadweaver.core.model.SpanType.BRIDGE
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.ROAD_CROSSING
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.TUNNEL_JUNCTION
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.NETWORK_REUSE
                    && span.type() != net.shiroha233.roadweaver.core.model.SpanType.PROTECTED_GAP)) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) mask[i] = true;
        }
        return mask;
    }

    static BlockPos conflictPosition(RoadData road, int segment) {
        if (road == null || road.roadSegmentList() == null
                || segment < 0 || segment >= road.roadSegmentList().size()) return null;
        RoadSegmentPlacement placement = road.roadSegmentList().get(segment);
        return placement == null ? null : placement.middlePos();
    }

    /** 剩余小挖填由施工短坡承担；超预算深谷仍必须桥接或改线。
 *
     * True when the residual cut/fill is small enough that the construction
     * paver can still build it as a local centre grade (6..8 blocks) instead of
     * dropping the whole route. Deeper conflicts remain true canyons.
     */
    static boolean isBuildableContinuity(ConstructionContinuity continuity) {
        return continuity != null
                && continuity.maxCutDepth() <= MAX_BUILDABLE_CENTER_CUT_DEPTH
                && continuity.maxFillDepth() <= MAX_BUILDABLE_CENTER_FILL_DEPTH;
    }

    static FillConflictWidth conflictWidth(RoadData road, ConstructionContinuity continuity) {
        if (road == null || continuity == null || road.roadSegmentList() == null
                || continuity.minimumTerrainBySegment() == null
                || road.targetY() == null) return FillConflictWidth.EMPTY;
        int conflict = continuity.conflictSegment();
        int size = Math.min(road.roadSegmentList().size(), road.targetY().size());
        if (conflict < 0 || conflict >= size) return FillConflictWidth.EMPTY;
        int start = conflict;
        int end = conflict;
        while (start > 0 && isUnsafeFillStation(road, continuity, start - 1)) start--;
        while (end + 1 < size && isUnsafeFillStation(road, continuity, end + 1)) end++;
        double distance = 0.0D;
        for (int index = start; index < end; index++) {
            BlockPos a = conflictPosition(road, index);
            BlockPos b = conflictPosition(road, index + 1);
            if (a != null && b != null) {
                distance += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            }
        }
        if (start == end) {
            BlockPos center = conflictPosition(road, conflict);
            BlockPos before = conflict > 0 ? conflictPosition(road, conflict - 1) : null;
            BlockPos after = conflict + 1 < size ? conflictPosition(road, conflict + 1) : null;
            if (center != null && before != null) {
                distance += Math.hypot(center.getX() - before.getX(), center.getZ() - before.getZ()) * 0.5D;
            }
            if (center != null && after != null) {
                distance += Math.hypot(after.getX() - center.getX(), after.getZ() - center.getZ()) * 0.5D;
            }
        }
        return new FillConflictWidth(start, end, Math.max(1.0D, distance));
    }

    static boolean isUnsafeFillStation(RoadData road,
                                               ConstructionContinuity continuity,
                                               int index) {
        if (index < 0 || index >= road.targetY().size()
                || index >= continuity.minimumTerrainBySegment().length) return false;
        int terrain = continuity.minimumTerrainBySegment()[index];
        return terrain != Integer.MAX_VALUE
                && road.targetY().get(index) - terrain >= MAX_CONSTRUCTION_FILL_BEFORE_REPAIR;
    }

    static String formatWidth(double width) {
        return String.format(java.util.Locale.ROOT, "%.1f", width);
    }

    record FillConflictWidth(int start, int end, double widthBlocks) {
        private static final FillConflictWidth EMPTY = new FillConflictWidth(-1, -1, 0.0D);
    }

    /** 深凹地站只降到实地再限坡，不用高填土强行补连通。
 *
     * Lowers only deep-depression stations to the local ground surface (no
     * fill) and projects the bounded profile to a continuous grade.
     */
    record DirectRouteConnections(StructureConnection planningConnection,
                                  StructureConnection ownerConnection) {}


    /** 相同连接且路径未变时复用已完成快照Future，避免每次等待重新预测结构。 */
    record SnapshotWaitEntry(int pathFingerprint, CompletableFuture<Void> future) {}

    static final java.util.concurrent.ConcurrentHashMap<PlannedPathKey, SnapshotWaitEntry>
            SNAPSHOT_WAIT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public record PathCalculationResult(List<RoadSegmentPlacement> segments, PathTerrainField terrain) {
        public static PathCalculationResult failure() {
            return new PathCalculationResult(null, null);
        }
    }
}
