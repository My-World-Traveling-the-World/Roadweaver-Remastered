/* 文件职责：组织普通道路的精确量化寻路与最终路径段生成。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
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

/**
 * 道路路径计算器
 */
public final class RoadPathCalculator {
    private static final RoadTerrainPlanningPipeline TERRAIN_PLANNING = new RoadTerrainPlanningPipeline();
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int[] LOCAL_LAKE_DETOUR_BLOCKS = RoutePrecisionRetryPolicy.lakeDetourBlocks();
    private static final int[] LOCAL_STRUCTURE_DETOUR_BLOCKS =
            RoutePrecisionRetryPolicy.structureDetourBlocks();
    private static final int RIVER_LOCAL_DETOUR_BLOCKS = 50;
    private static final int MAX_EXACT_CONSTRAINT_CYCLES = 4;
    /** Deep-depression continuity repair starts above the ordinary surface-fill limit. */
    private static final int MAX_CONSTRUCTION_FILL_BEFORE_REPAIR = RoadConstructionSafety.MAX_FILL_DEPTH;
    /**
     * 中心线<b>挖方</b>的可建造上限。0.9.087：与施工端同源。
     *
     * <p>真机依据：5 条 initial connection 失败里，2 条是「路要穿过比它高 19 / 25 格的山脊」
     * （{@code seg=816 cut=19}、{@code seg=2200 cut=25}）。旧值 8 把它们当「canyon」整条丢弃，
     * 而施工端 {@code MAX_LOCAL_CENTER_CUT_DEPTH} 现在能建 32 格的<b>路堑</b>。
     * 两处必须同源，否则规划期放过了、施工期照样建不出来。</p>
     */
    private static final int MAX_BUILDABLE_CENTER_CUT_DEPTH =
            RoadConstructionSafety.MAX_LOCAL_CENTER_CUT_DEPTH;

    /**
     * 中心线<b>填方</b>的可建造上限。
     *
     * <p>填方必须比挖方严得多：填 8 格以上就是高堤，路面悬在填土堆上、
     * 两侧陡坡，视觉突兀且一列没填实就是一个洞。挖方则是山体开槽（路堑），
     * 路面本身仍平顺 —— 两者不可用同一个数。</p>
     */
    private static final int MAX_BUILDABLE_CENTER_FILL_DEPTH =
            RoadConstructionSafety.MAX_LOCAL_CENTER_GRADE_DEPTH;
    /** Network merge must not re-run a full local A* over a long tunnel chain. */
    private static final int NETWORK_MERGE_LONG_ROUTE_SEGMENTS = 512;
    private static final int NETWORK_MERGE_LONG_TUNNEL_SEGMENTS = 64;
    private static final int NETWORK_MERGE_INTERFACE_WINDOW = 24;
    /** Conflicts within this many stations of a route end may escalate every radius (endpoint approach). */
    private static final int ENDPOINT_APPROACH_STATIONS = 24;    /**
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
    private static final double LOCAL_DETOUR_MAX_LENGTH_RATIO = 1.6D;
    /** 0.8.323：重建成段前允许的最大相邻站点横向间距，与 RoadDataValidator.MAX_CENTER_STEP 对齐。 */
    private static final int REBUILD_MAX_CENTER_STEP = 2;
    /** 0.8.324：未平滑版本最多允许比平滑版本长多少倍（超过就保留平滑版）。 */
    private static final double UNSMOOTHED_MAX_LENGTH_RATIO = 1.15D;
    private static final ConcurrentHashMap<net.shiroha233.roadweaver.planning.path.PlannedPathKey, BlockPos>
            CONSTRUCTION_CONFLICT_HINTS = new ConcurrentHashMap<>();

    private record ConstructionContinuity(boolean requiresRepair,
                                          int maxFillDepth,
                                          int maxCutDepth,
                                          int conflictSegment,
                                          int sampledColumns,
                                          int[] minimumTerrainBySegment) {
    }

    private RoadPathCalculator() {}

    /**
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
                LOGGER.warn("RoadWeaver overriding long-network merge shortcut for unresolved height conflict "
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
    private static void logMergeRepairTiming(String path, RoadData before, RoadData after,
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
        LOGGER.info("RoadWeaver merge repair timing path={} nodes={} -> {} changed={} "
                        + "shortcutMs={} continuityMs={} repairMs={} totalMs={}",
                path, nodesBefore, nodesAfter, changed, shortcutMs, continuityMs, repairMs, totalMs);
    }

    /**
     * Checks only the small windows where a merged route touches another road
     * or a shared endpoint. The long tunnel body is already persisted and must
     * not be sent through a second full-route height A* on the server thread.
     */
    private static RoadData repairNetworkMergeInterfaces(RoadData road,
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
            LOGGER.info("RoadWeaver checked merged route interfaces locally: interfaces={} window={} segments={} owners={} -> {}",
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
        LOGGER.info("RoadWeaver repaired merged route interfaces locally: interfaces={} window={} segments={} owners={} -> {}",
                ordered.size(), NETWORK_MERGE_INTERFACE_WINDOW, size,
                road.ownerA2dKey(), road.ownerB2dKey());
        return rebuilt == null ? road : rebuilt;
    }

    /**
     * Replans only the local height-conflict corridor after network reuse or
     * junction merging changes a persisted target profile. The normal profile
     * smoother remains first; this method is the bounded 50-block step=1
     * fallback and never starts a new endpoint-to-endpoint search.
     */
    /**
     * 0.8.368 (T2 diagnosis): prints the repaired profile's real end heights.
     * Pairs with {@code Road.logEndpointProfile} around computeTargetY. The
     * suspected defect is that {@code enforceContinuousSlopePreferCut} only ever
     * lowers stations, so a locked village boundary grade is pulled back down to
     * meet the tunnel chord; a route ending in a TUNNEL span lost 18 blocks this
     * way while its other endpoint stayed exact. Diagnosis only; no height is
     * changed here.
     */
    private static void logRepairEndpoint(String stage, RoadData road, StructureConnection connection) {
        if (road == null || road.targetY() == null || road.targetY().isEmpty()
                || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()
                || !LOGGER.isInfoEnabled()) return;
        int last = road.targetY().size() - 1;
        BlockPos head = road.roadSegmentList().get(0) == null
                ? null : road.roadSegmentList().get(0).middlePos();
        BlockPos tail = road.roadSegmentList().get(last) == null
                ? null : road.roadSegmentList().get(last).middlePos();
        LOGGER.info("RoadWeaver endpoint repair profile stage={} connection={}->{} stations={} "
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

    private static RoadData repairFinalHeightConflictInternal(RoadData road,
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
                        LOGGER.info("RoadWeaver accepted pre-construction height reroute "
                                        + "maxFillDepth={} maxCutDepth={} conflictSegment={}",
                                continuity.maxFillDepth(), continuity.maxCutDepth(), continuity.conflictSegment());
                        return reroutedRoad;
                    }
                    LOGGER.warn("RoadWeaver pre-construction height reroute still unsafe "
                                    + "maxFillDepth={} maxCutDepth={} conflictSegment={}",
                            reroutedContinuity.maxFillDepth(), reroutedContinuity.maxCutDepth(),
                            reroutedContinuity.conflictSegment());
                } else {
                    // Logging-only diagnostic: the detour profile could not be
                    // rebuilt into road segments.
                    LOGGER.warn("RoadWeaver height reroute rebuild failed conflictSegment={} "
                                    + "nodes={} reason={} owners={} -> {}",
                            continuity.conflictSegment(), rerouted.size(),
                            consumeRebuildFailureReason(),
                            road.ownerA2dKey(), road.ownerB2dKey());
                }
            } else {
                // Logging-only diagnostic: reroute returned null or kept the
                // original corridor unchanged.
                LOGGER.warn("RoadWeaver height reroute unavailable conflictSegment={} "
                                + "reason={} owners={} -> {}",
                        continuity.conflictSegment(),
                        rerouted == null ? "null_reroute" : "unchanged_corridor",
                        road.ownerA2dKey(), road.ownerB2dKey());
            }
        } else if (continuity.requiresRepair() && conflictIsPhysicalSpan) {
            // Logging-only diagnostic: the conflict sits on a physical span
            // (bridge/tunnel/reuse/gap), so the height reroute is skipped.
            LOGGER.info("RoadWeaver height reroute skipped: conflict lies on a physical span "
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
                LOGGER.info("RoadWeaver continuity preflight: sampledColumns={} maxFillDepth={} maxCutDepth={} "
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
                    LOGGER.info("RoadWeaver accepted buildable local centre grade owners={} -> {} "
                                    + "maxCutDepth={} maxFillDepth={} conflictSegment={} conflictPos=({},{})",
                            road.ownerA2dKey(), road.ownerB2dKey(), continuity.maxCutDepth(),
                            continuity.maxFillDepth(), continuity.conflictSegment(),
                            bConflict == null ? "?" : bConflict.getX(),
                            bConflict == null ? "?" : bConflict.getZ());
                    return road;
                }
                BlockPos conflict = conflictPosition(road, continuity.conflictSegment());
                LOGGER.warn("RoadWeaver rejected route with unresolved construction continuity conflict "
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
            LOGGER.warn("RoadWeaver continuity repair rebuild failed owners={} -> {} conflictSegment={} "
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
            LOGGER.warn("RoadWeaver continuity repair candidate invalid reason={} index={} owners={} -> {}",
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
            LOGGER.info("RoadWeaver kept original profile because repair made a buildable route unbuildable "
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
                LOGGER.info("RoadWeaver accepted buildable residual local centre grade owners={} -> {} "
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
            LOGGER.warn("RoadWeaver rejected route after incomplete construction continuity repair "
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
        LOGGER.info("RoadWeaver accepted local continuity/height repair owners={} -> {} nodes={} -> {}",
                road.ownerA2dKey(), road.ownerB2dKey(), road.roadSegmentList().size(), rebuilt.size());
        return candidate;
    }

    /** Task 3: exposes the profile rebuild for the final structure-repair loop. */
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
    private static final ThreadLocal<String> LAST_REBUILD_FAILURE = new ThreadLocal<>();

    static String consumeRebuildFailureReason() {
        String reason = LAST_REBUILD_FAILURE.get();
        LAST_REBUILD_FAILURE.remove();
        return reason == null ? "unknown" : reason;
    }

    /** Task 3: records a final-validation conflict coordinate for later local waves. */
    public static void recordConstructionConflictHint(StructureConnection connection, BlockPos position) {
        if (connection == null || position == null) return;
        CONSTRUCTION_CONFLICT_HINTS.put(
                net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(connection), position);
    }

    private static RoadData rebuildRoadDataFromProfileInternal(RoadData road,
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

    /**
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

    /**
     * One bounded continuity scan for the complete road footprint. The result
     * carries per-segment terrain minima so profile repair never samples the
     * same road columns a second time.
     */
    private static ConstructionContinuity inspectConstructionContinuity(RoadData road,
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

    private static boolean[] physicalBypassMask(RoadData road) {
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

    private static BlockPos conflictPosition(RoadData road, int segment) {
        if (road == null || road.roadSegmentList() == null
                || segment < 0 || segment >= road.roadSegmentList().size()) return null;
        RoadSegmentPlacement placement = road.roadSegmentList().get(segment);
        return placement == null ? null : placement.middlePos();
    }

    /**
     * True when the residual cut/fill is small enough that the construction
     * paver can still build it as a local centre grade (6..8 blocks) instead of
     * dropping the whole route. Deeper conflicts remain true canyons.
     */
    private static boolean isBuildableContinuity(ConstructionContinuity continuity) {
        return continuity != null
                && continuity.maxCutDepth() <= MAX_BUILDABLE_CENTER_CUT_DEPTH
                && continuity.maxFillDepth() <= MAX_BUILDABLE_CENTER_FILL_DEPTH;
    }

    private static FillConflictWidth conflictWidth(RoadData road, ConstructionContinuity continuity) {
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

    private static boolean isUnsafeFillStation(RoadData road,
                                               ConstructionContinuity continuity,
                                               int index) {
        if (index < 0 || index >= road.targetY().size()
                || index >= continuity.minimumTerrainBySegment().length) return false;
        int terrain = continuity.minimumTerrainBySegment()[index];
        return terrain != Integer.MAX_VALUE
                && road.targetY().get(index) - terrain >= MAX_CONSTRUCTION_FILL_BEFORE_REPAIR;
    }

    private static String formatWidth(double width) {
        return String.format(java.util.Locale.ROOT, "%.1f", width);
    }

    private record FillConflictWidth(int start, int end, double widthBlocks) {
        private static final FillConflictWidth EMPTY = new FillConflictWidth(-1, -1, 0.0D);
    }

    /**
     * Lowers only deep-depression stations to the local ground surface (no
     * fill) and projects the bounded profile to a continuous grade.
     */
    private static List<BlockPos> lowerProfileToSafeFill(List<BlockPos> profile, int[] minimumTerrain, boolean[] bypass) {
        if (profile == null || profile.size() < 3 || minimumTerrain == null
                || minimumTerrain.length != profile.size()) return profile;
        int[] heights = new int[profile.size()];
        for (int i = 0; i < profile.size(); i++) heights[i] = profile.get(i).getY();
        for (int i = 1; i < heights.length - 1; i++) {
            if (bypass == null || !bypass[i]) {
                if (minimumTerrain[i] != Integer.MAX_VALUE) {
                    heights[i] = Math.min(heights[i], minimumTerrain[i]);
                }
            }
        }
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < heights.length - 1; i++) {
                int limit = (int) Math.floor(horizontalDistance(profile.get(i - 1), profile.get(i)));
                heights[i] = clamp(heights[i], heights[i - 1] - Math.max(0, limit),
                        heights[i - 1] + Math.max(0, limit));
            }
            for (int i = heights.length - 2; i >= 1; i--) {
                int limit = (int) Math.floor(horizontalDistance(profile.get(i), profile.get(i + 1)));
                heights[i] = clamp(heights[i], heights[i + 1] - Math.max(0, limit),
                        heights[i + 1] + Math.max(0, limit));
            }
        }
        List<BlockPos> result = new ArrayList<>(profile.size());
        for (int i = 0; i < profile.size(); i++) {
            BlockPos source = profile.get(i);
            result.add(new BlockPos(source.getX(), heights[i], source.getZ()));
        }
        return result;
    }

    /** Raises only deep-cut stations to the ordinary five-block cut limit, leaving physical spans intact. */
    private static List<BlockPos> raiseProfileToSafeCut(List<BlockPos> profile, int[] minimumTerrain, boolean[] bypass) {
        if (profile == null || profile.size() < 3 || minimumTerrain == null
                || minimumTerrain.length != profile.size()) return profile;
        List<BlockPos> result = new ArrayList<>(profile.size());
        for (int i = 0; i < profile.size(); i++) {
            BlockPos source = profile.get(i);
            int y = source.getY();
            if (i > 0 && i < profile.size() - 1 && minimumTerrain[i] != Integer.MAX_VALUE
                    && (bypass == null || !bypass[i])) {
                y = Math.max(y, minimumTerrain[i] - RoadConstructionSafety.MAX_CUT_DEPTH);
            }
            result.add(new BlockPos(source.getX(), y, source.getZ()));
        }
        return result;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        if (a == null || b == null) return 0.0D;
        long dx = (long) b.getX() - a.getX();
        long dz = (long) b.getZ() - a.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    public static List<RoadSegmentPlacement> calculateAStarRoadPath(BlockPos startIn,
                                                                    BlockPos endIn,
                                                                    int width,
                                                                    ServerLevel level,
                                                                    int maxSteps,
                                                                    TerrainSamplingCache cache,
                                                                    RoadGenerationConfig cfg) {
        PathCalculationResult result = calculateAStarRoadPathDetailed(startIn, endIn, width, level, maxSteps, cache, cfg);
        try {
            return result.segments();
        } finally {
            if (result.terrain() != null) {
                result.terrain().dispose();
            }
        }
    }

    public static PathCalculationResult calculateAStarRoadPathDetailed(BlockPos startIn,
                                                                       BlockPos endIn,
                                                                       int width,
                                                                       ServerLevel level,
                                                                       int maxSteps,
                                                                       TerrainSamplingCache cache,
                                                                       RoadGenerationConfig cfg) {
        return calculateAStarRoadPathDetailed(startIn, endIn, width, level, maxSteps, cache, cfg, null);
    }

    /**
     * 0.9.018：带硬禁行走廊的单条直连重算（D2 平行双桥「方案 C：后者整体改道」）。
     *
     * <p>{@code corridorBan} 为 {@code null} 时与上一个重载逐字节等价——禁令只在
     * 寻路器的邻接扩展里多一次空判断。非 null 时：起点／终点、宽度、外扩边距、
     * 后续的桥隧 span、纵断面与高度修复全部沿用同一条流水线（
     * {@link #finalizePath} 之后的部分），所以改道产物与正常规划的产物同构，
     * 不需要另写一套收尾。</p>
     *
     * <p>外扩边距（{@code searchMargin}，至少 512 格）足以容纳绕开一条
     * 18 格净空走廊所需的侧向偏移，所以禁令不会把路线逼出地形场。</p>
     */
    public static PathCalculationResult calculateAStarRoadPathDetailed(BlockPos startIn,
                                                                       BlockPos endIn,
                                                                       int width,
                                                                       ServerLevel level,
                                                                       int maxSteps,
                                                                       TerrainSamplingCache cache,
                                                                       RoadGenerationConfig cfg,
                                                                       net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan) {
        if (startIn == null || endIn == null || level == null || cache == null || cfg == null) {
            return PathCalculationResult.failure();
        }
        PathfindingCostConfig pathCfg = cfg.pathfinding().snapshot();
        pathCfg.setAStarMaxSteps(maxSteps);
        pathCfg.sanitize();
        // 禁令必须在 sanitize 之后挂上：sanitize 只动数值旋钮，但把挂载点统一
        // 放在这里，将来若有人给 sanitize 加「重置瞬态」也不会静默丢掉禁令。
        pathCfg.setCorridorBan(corridorBan);
        int dGrid = pathCfg.effectiveAStarStep();
        DirectRouteConnections routeConnections = directRouteConnections(startIn, endIn, dGrid);
        StructureConnection planningConnection = routeConnections.planningConnection();
        StructureConnection ownerConnection = routeConnections.ownerConnection();
        BlockPos start = planningConnection.from();
        BlockPos end = planningConnection.to();

        int margin = searchMargin(start, end);
        RoadTerrainPlanningPort.Result planned;
        try {
            planned = TERRAIN_PLANNING.plan(new RoadTerrainPlanningPort.Request(
                    level,
                    new RoadTerrainPlanningPort.Bounds(
                            Math.min(start.getX(), end.getX()) - margin,
                            Math.min(start.getZ(), end.getZ()) - margin,
                            Math.max(start.getX(), end.getX()) + margin,
                            Math.max(start.getZ(), end.getZ()) + margin),
                    List.of(planningConnection),
                    pathCfg));
        } catch (RuntimeException failure) {
            return PathCalculationResult.failure();
        }
        List<BlockPos> rawPath = planned.paths().get(planningConnection);
        if (rawPath == null || rawPath.isEmpty()) {
            return PathCalculationResult.failure();
        }
        // Bulk planning prepares this exact corridor before it publishes a
        // connection. A bounded topology fallback reaches this direct A* path
        // without that bulk stage, so prepare the replacement corridor here
        // before village-boundary clipping or structure repair reads it.
        try {
            awaitPlanningSnapshot(
                    ensurePlanningSnapshotForPathAsync(level, ownerConnection, rawPath));
        } catch (RuntimeException snapshotFailure) {
            LOGGER.warn("RoadWeaver could not prepare a structure corridor for direct route connection={}->{}",
                    ownerConnection.from(), ownerConnection.to(), snapshotFailure);
            return PathCalculationResult.failure();
        }
        return finalizePath(rawPath, width, level, cache, null, pathCfg, ownerConnection);
    }

    static DirectRouteConnections directRouteConnections(BlockPos startIn, BlockPos endIn, int gridStep) {
        int step = Math.max(1, gridStep);
        BlockPos planningStart = new BlockPos(
                snapToGrid(startIn.getX(), step), startIn.getY(), snapToGrid(startIn.getZ(), step));
        BlockPos planningEnd = new BlockPos(
                snapToGrid(endIn.getX(), step), endIn.getY(), snapToGrid(endIn.getZ(), step));
        return new DirectRouteConnections(
                new StructureConnection(planningStart, planningEnd, ConnectionStatus.PLANNED),
                new StructureConnection(startIn, endIn, ConnectionStatus.PLANNED));
    }

    record DirectRouteConnections(StructureConnection planningConnection,
                                  StructureConnection ownerConnection) {}

    /**
     * 直接后处理规划阶段已经完成的精确路径，不再采样或重复寻路。
     */
    public static PathCalculationResult calculateFromPlannedPath(List<BlockPos> plannedPath,
                                                                  int width,
                                                                  ServerLevel level,
                                                                  TerrainSamplingCache cache,
                                                                  RoadGenerationConfig cfg,
                                                                  StructureConnection connection) {
        if (plannedPath == null || plannedPath.isEmpty()) {
            return PathCalculationResult.failure();
        }
        PathfindingCostConfig pathCfg = cfg != null ? cfg.pathfinding().snapshot() : null;
        if (pathCfg != null) pathCfg.sanitize();
        return finalizePath(plannedPath, width, level, cache, null, pathCfg, connection);
    }

    public static PathCalculationResult calculateFromPlannedPath(List<BlockPos> plannedPath,
                                                                  int width,
                                                                  ServerLevel level,
                                                                  TerrainSamplingCache cache) {
        return calculateFromPlannedPath(plannedPath, width, level, cache, null, null);
    }

    private static PathCalculationResult finalizePath(List<BlockPos> rawPath,
                                                      int width,
                                                      ServerLevel level,
                                                      TerrainSamplingCache cache,
                                                      PathTerrainField terrain,
                                                      PathfindingCostConfig pathCfg,
                                                      StructureConnection connection) {
        if (rawPath == null || rawPath.size() < 2) {
            return PathCalculationResult.failure();
        }
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        InitialGenerationProgressTracker.advanceCurrentRoadTask(50, "road_exact_height");
        long exactHeightStartedAt = System.nanoTime();
        List<BlockPos> upstreamFallbackPath = AccuratePathHeightResolver.resolve(rawPath, terrain, accurate);
        DetailedRoadGenerationStats.recordExactHeight(System.nanoTime() - exactHeightStartedAt);
        InitialGenerationProgressTracker.advanceCurrentRoadTask(220, "road_grade_repair");
        List<BlockPos> finalPath = upstreamFallbackPath;
        if (pathCfg != null) {
            finalPath = repairAndRecordGrade(
                    finalPath, level, accurate, pathCfg, connection);
            finalPath = repairStructureIntersectionsWithCorridorRefresh(
                    finalPath, level, accurate, pathCfg, connection);
            List<BlockPos> beforeWater = finalPath;
            List<BlockPos> afterRiver = repairRiverIntersectionsWithCorridorRefresh(
                    finalPath, width, level, accurate, pathCfg, connection);
            List<BlockPos> afterLake = afterRiver == null ? null
                    : repairLakeIntersectionsWithCorridorRefresh(
                    afterRiver, width, level, accurate, pathCfg, connection);
            finalPath = afterLake;
            // The closing structure pass only matters when a water repair
            // actually moved the path: both water repairs return their input
            // instance when they were no-ops (leaving the snapshot untouched
            // too), and the structure repair is deterministic for an unchanged
            // path + snapshot, so re-running it would return the same result.
            if (finalPath != null && (afterRiver != beforeWater || afterLake != afterRiver)) {
                finalPath = repairStructureIntersectionsWithCorridorRefresh(
                        finalPath, level, accurate, pathCfg, connection);
            }
            if (finalPath == null) {
                LOGGER.warn("RoadWeaver local structure/lake detours exhausted; rejecting connection for topology fallback connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
                return PathCalculationResult.failure();
            }
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(600, "road_segment_build");
        long segmentBuildStartedAt = System.nanoTime();
        List<RoadSegmentPlacement> segments = buildSegments(
                finalPath, width, level, cache, terrain, accurate, connection);
        DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
        if (connection != null && segments != null) {
            segments = stopAtEndpointBuilding(level, connection, segments);
            if (segments == null) return PathCalculationResult.failure();
            segments = stopAtVillageBuilding(level, connection, segments);
            if (segments == null) return PathCalculationResult.failure();
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(760, "road_construction_height");
        if (segments != null && pathCfg != null && segments.size() >= 3) {
            long constructionSetupStartedAt = System.nanoTime();
            List<BlockPos> constructionCenters = new ArrayList<>(segments.size());
            for (RoadSegmentPlacement segment : segments) {
                constructionCenters.add(segment.middlePos());
            }
            DetailedRoadGenerationStats.recordConstructionSetup(System.nanoTime() - constructionSetupStartedAt);
            exactHeightStartedAt = System.nanoTime();
            List<BlockPos> constructionTerrain = AccuratePathHeightResolver.resolve(
                    constructionCenters, null, accurate);
            DetailedRoadGenerationStats.recordExactHeight(System.nanoTime() - exactHeightStartedAt);
            InitialGenerationProgressTracker.advanceCurrentRoadTask(840, "road_construction_repair");
            List<BlockPos> repairedConstruction = repairAndRecordGrade(
                    constructionTerrain, level, accurate, pathCfg, connection);
            repairedConstruction = repairStructureIntersectionsWithCorridorRefresh(
                    repairedConstruction, level, accurate, pathCfg, connection);
            List<BlockPos> beforeConstructionWater = repairedConstruction;
            List<BlockPos> constructionAfterRiver = repairRiverIntersectionsWithCorridorRefresh(
                    repairedConstruction, width, level, accurate, pathCfg, connection);
            List<BlockPos> constructionAfterLake = constructionAfterRiver == null ? null
                    : repairLakeIntersectionsWithCorridorRefresh(
                    constructionAfterRiver, width, level, accurate, pathCfg, connection);
            repairedConstruction = constructionAfterLake;
            // Same no-op short-circuit as the path-level chain above: only
            // re-run the closing structure pass when water actually moved it.
            if (repairedConstruction != null
                    && (constructionAfterRiver != beforeConstructionWater
                    || constructionAfterLake != constructionAfterRiver)) {
                repairedConstruction = repairStructureIntersectionsWithCorridorRefresh(
                        repairedConstruction, level, accurate, pathCfg, connection);
            }
            if (repairedConstruction == null) {
                LOGGER.warn("RoadWeaver rejected construction-center route after exact lake/structure repair connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
                return PathCalculationResult.failure();
            }
            if (repairedConstruction != null && !repairedConstruction.equals(constructionTerrain)) {
                segmentBuildStartedAt = System.nanoTime();
                segments = buildSegments(
                        repairedConstruction, width, level, cache, null, accurate, connection);
                DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
                if (connection != null && segments != null) {
                    segments = stopAtEndpointBuilding(level, connection, segments);
                    if (segments == null) return PathCalculationResult.failure();
                    segments = stopAtVillageBuilding(level, connection, segments);
                    if (segments == null) return PathCalculationResult.failure();
                }
            }
        }
        if (segments == null && upstreamFallbackPath != null && upstreamFallbackPath.size() >= 2
                && connection == null) {
            List<BlockPos> fallback = pathCfg == null
                    ? upstreamFallbackPath
                    : repairAndRecordGrade(
                            upstreamFallbackPath, level, accurate, pathCfg, connection);
            segmentBuildStartedAt = System.nanoTime();
            segments = buildSegments(fallback, width, level, cache, terrain, accurate, null);
            DetailedRoadGenerationStats.recordSegmentBuild(System.nanoTime() - segmentBuildStartedAt);
            if (segments != null) {
                LOGGER.warn("RoadWeaver accepted path-level upstream fallback while retaining current construction safety connection={}->{}",
                        connection == null ? null : connection.from(), connection == null ? null : connection.to());
            }
        }
        if (segments == null) {
            return PathCalculationResult.failure();
        }
        if (connection != null
                && intersectsPreparedRoute(level, connection, segments)
                && intersectsPreparedRouteWithUnresolvedStructure(level, connection, segments)) {
            LOGGER.error("RoadWeaver refused to finalize a route whose exact footprint still intersects a protected structure connection={}->{}",
                    connection.from(), connection.to());
            return PathCalculationResult.failure();
        }
        if (connection != null
                && intersectsPreparedRoute(level, connection, segments)) {
            LOGGER.info("RoadWeaver retained verified structure protection gaps connection={}->{}; construction will skip only overlapping columns",
                    connection.from(), connection.to());
        }
        InitialGenerationProgressTracker.advanceCurrentRoadTask(980, "road_finalizing");
        return new PathCalculationResult(segments, terrain);
    }

    private static List<BlockPos> repairStructureIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null) return path;
        var server = level == null ? null : level.getServer();
        if (server == null) return null;
        if (server.isSameThread()) {
            LOGGER.error("RoadWeaver refused synchronous corridor resolution on the server thread connection={}->{}",
                    connection.from(), connection.to());
            return null;
        }

        // Local repair uses the same player-selected precision as the route.
        // RoutePrecisionRetryPolicy.allowsLocalDetour(step) is true for every
        // sanitized precision, so the old "defer at coarse step" gate here was
        // unreachable and has been removed; local detours always run. The
        // first radius iteration refreshes the snapshot for this path below,
        // so no separate pre-loop refresh is needed (working starts as a copy
        // of path and the corridor cache deduplicates identical content).
        List<BlockPos> working = List.copyOf(path);
        // 0.8.340：长度闸门的基准 —— 进入修复那一刻的原长。后续每一轮都用它比对，
        // 避免「以上一轮绕行结果为基准」导致闸门本身也被逐级放大而失效。
        final int detourBaselineStations = Math.max(1, working.size());
        try {
            BlockPos previousRejectedCenter = null;
            int previousRejectedRadius = -1;
            int identicalConflictStreak = 0;
            for (int localRepairRadius : LOCAL_STRUCTURE_DETOUR_BLOCKS) {
                InitialGenerationProgressTracker.advanceCurrentRoadTask(
                        360, "road_structure_local_detour_" + localRepairRadius);
                LOGGER.info("RoadWeaver starting single building local repair radius={} fine-precision connection={}->{}",
                        localRepairRadius, connection.from(), connection.to());
                refreshPlanningSnapshot(level, connection, working);
                ExactStructureRepairOutcome outcome =
                        StructureRepair.repairStructureIntersectionsAtExactClearanceDetailed(
                                working, level, accurate, pathCfg, connection, localRepairRadius);
                List<BlockPos> exact = outcome.path();
                if (exact != null && !exact.isEmpty()) {
                    // 0.8.340 长度闸门：绕行结果若比进入修复时的原长超出
                    // LOCAL_DETOUR_MAX_LENGTH_RATIO 倍，说明这一轮是"越绕越远"的
                    // 累积膨胀（真机 624 -> 3042 站），继续放大半径只会更长。
                    // 直接停止升级并保留原路 + 保护缺口（既有兜底路径），
                    // 避免后面还要为这条超长路重算坡度、验证数万列地形再判失败。
                    if (exact.size() > detourBaselineStations * LOCAL_DETOUR_MAX_LENGTH_RATIO) {
                        LOGGER.warn("RoadWeaver building local detour stopped early: detour would grow route "
                                        + "beyond {}x baseline ({} -> {} stations, ratio={}) at radius={}; "
                                        + "retaining route with building protection gap connection={}->{}",
                                LOCAL_DETOUR_MAX_LENGTH_RATIO, detourBaselineStations, exact.size(),
                                String.format("%.2f", exact.size() / (double) detourBaselineStations),
                                localRepairRadius, connection.from(), connection.to());
                        break;
                    }
                    refreshPlanningSnapshot(level, connection, exact);
                    if (!intersectsPreparedPath(level, connection, exact, accurate)) {
                        LOGGER.info("RoadWeaver accepted single building local repair radius={} fine-precision connection={}->{}",
                                localRepairRadius, connection.from(), connection.to());
                        return exact;
                    }
                    working = List.copyOf(exact);
                }
                if (!intersectsPreparedPath(level, connection, working, accurate)) {
                    LOGGER.info("RoadWeaver accepted verified structure protection gap after building radius={} connection={}->{}",
                            localRepairRadius, connection.from(), connection.to());
                    return working;
                }
                // Early gap retention: the same conflict centre failed again, so a
                // larger circle will not converge either. Stop escalating instead of
                // hard-trying every radius.
                //
                // Endpoint-approach conflicts (within 24 stations of either route end,
                // e.g. the road spiralling into a village) legitimately need the larger
                // circles to switchback along the structure boundary — but only while
                // the conflict centre keeps MOVING along that boundary. A centre that
                // stays put is a geometric dead end (the endpoint itself sits on the
                // structure); escalating it just burns the whole radius ladder.
                BlockPos rejectedCenter = outcome.lastRejectedCenter();
                int rejectedIndex = outcome.lastRejectedIndex();
                boolean endpointApproach = rejectedIndex >= 0
                        && (rejectedIndex <= ENDPOINT_APPROACH_STATIONS
                        || rejectedIndex >= working.size() - 1 - ENDPOINT_APPROACH_STATIONS);
                if (rejectedCenter != null && previousRejectedCenter != null
                        && rejectedCenter.getX() == previousRejectedCenter.getX()
                        && rejectedCenter.getZ() == previousRejectedCenter.getZ()) {
                    identicalConflictStreak++;
                } else {
                    identicalConflictStreak = 1;
                }
                if (rejectedCenter != null
                        && RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(identicalConflictStreak, endpointApproach)) {
                    LOGGER.warn("RoadWeaver building local detour stopped early: identical conflict center=({},{}) "
                                    + "failed at {} consecutive radius steps (last {} -> {}); endpointApproach={}; "
                                    + "retaining route with building protection gap connection={}->{}",
                            rejectedCenter.getX(), rejectedCenter.getZ(), identicalConflictStreak,
                            previousRejectedRadius, localRepairRadius, endpointApproach,
                            connection.from(), connection.to());
                    break;
                }
                previousRejectedCenter = rejectedCenter;
                previousRejectedRadius = localRepairRadius;
                // Gameplay-pressure yield: escalating to a larger detour circle
                // is low priority while the player is moving; retain the gap
                // instead of competing with chunk loading. Initial generation
                // (loading screen) is unaffected.
                if (!InitialGenManager.isActive()
                        && RuntimeLoadPolicy.isGameplayForegroundProtected()) {
                    LOGGER.info("RoadWeaver deferred building local detour escalation under gameplay pressure "
                                    + "completedRadius={} connection={}->{}",
                            localRepairRadius, connection.from(), connection.to());
                    break;
                }
            }
            // Every detour radius was exhausted. Keep the original path and
            // leave a building protection gap instead of killing the route.
            LOGGER.warn("RoadWeaver building local detours exhausted; retaining route with building protection gap connection={}->{}",
                    connection.from(), connection.to());
            return working;
        } catch (RuntimeException failure) {
            LOGGER.error("RoadWeaver failed to resolve the exact structure corridor connection={}->{}",
                    connection.from(), connection.to(), failure);
            throw failure;
        }
    }

    private static List<BlockPos> repairLakeIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            int width,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null) return path;
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if ((!streams.isAvailable() && !streams.isFailed())
                || !AccurateGradeRepairPlanner.intersectsLakeConstraints(
                path, width, accurate, streams)) return path;

        List<BlockPos> working = List.copyOf(path);
        PathfindingCostConfig finePathCfg = pathCfg.withAStarStep(2);
        Set<Long> routeSignatures = new HashSet<>();
        routeSignatures.add(routeSignature(working));
        for (int detour : LOCAL_LAKE_DETOUR_BLOCKS) {
            for (int cycle = 0; cycle < MAX_EXACT_CONSTRAINT_CYCLES; cycle++) {
                if (!AccurateGradeRepairPlanner.intersectsLakeConstraints(
                        working, width, accurate, streams)) return working;
                List<BlockPos> repaired = LakeRiverRepair.repairLakeIntersections(
                        working, level, accurate, finePathCfg, connection, streams, width, detour);
                if (repaired == null || repaired.isEmpty() || repaired.equals(working)) break;
                long signature = routeSignature(repaired);
                if (!routeSignatures.add(signature)) break;
                working = List.copyOf(repaired);

                refreshPlanningSnapshot(level, connection, working);
                working = repairAndRecordGrade(working, level, accurate, pathCfg, connection);
                working = repairStructureIntersectionsWithCorridorRefresh(
                        working, level, accurate, pathCfg, connection);
                if (working == null || working.isEmpty()) return null;
                signature = routeSignature(working);
                if (!routeSignatures.add(signature)
                        && AccurateGradeRepairPlanner.intersectsLakeConstraints(
                        working, width, accurate, streams)) break;
            }
            if (!AccurateGradeRepairPlanner.intersectsLakeConstraints(
                    working, width, accurate, streams)) return working;
        }
        // Lake detours are exhausted. Bridge first; when no bridge is
        // buildable keep the original corridor and let the final water
        // validator mark the exact wet stations as a protected gap.
        LOGGER.warn("RoadWeaver exhausted local lake detours; retaining route for bridge/water gap fallback connection={}->{}",
                connection.from(), connection.to());
        return working;
    }

    private static List<BlockPos> repairRiverIntersectionsWithCorridorRefresh(
            List<BlockPos> path,
            int width,
            ServerLevel level,
            AccurateHeightSampler accurate,
            PathfindingCostConfig pathCfg,
            StructureConnection connection) {
        if (path == null || path.isEmpty() || connection == null || pathCfg == null) return path;
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if ((!streams.isAvailable() && !streams.isFailed())
                || !AccurateGradeRepairPlanner.intersectsRiver(path, width, streams)) return path;

        LOGGER.info("RoadWeaver starting river local repair window={} connection={}->{}",
                RIVER_LOCAL_DETOUR_BLOCKS, connection.from(), connection.to());
        List<BlockPos> repaired = LakeRiverRepair.repairRiverIntersections(
                path, level, accurate, pathCfg.withAStarStep(1), connection, streams,
                width, RIVER_LOCAL_DETOUR_BLOCKS);
        if (repaired != null && !repaired.equals(path)
                && !AccurateGradeRepairPlanner.intersectsRiver(repaired, width, streams)) {
            refreshPlanningSnapshot(level, connection, repaired);
            LOGGER.info("RoadWeaver accepted river local repair connection={}->{}",
                    connection.from(), connection.to());
            return repaired;
        }
        // Keep the original path. FinalRoadWaterValidator will mark the exact
        // failing station as PROTECTED_GAP instead of rejecting unrelated road
        // sections or forcing an endpoint candidate solely for a short river.
        LOGGER.warn("RoadWeaver river local repair unavailable; retaining route for water protection gap "
                        + "connection={}->{}", connection.from(), connection.to());
        return path;
    }

    /**
     * Late water fallback used after span extraction.  This is intentionally
     * separate from normal path finalization so valid short crossings and long
     * bridges are not proactively rerouted around their rivers.
     */
    public static List<RoadSegmentPlacement> repairRiverRouteSegments(
            List<RoadSegmentPlacement> existing,
            int width,
            ServerLevel level,
            TerrainSamplingCache cache,
            RoadGenerationConfig cfg,
            StructureConnection connection) {
        if (existing == null || existing.size() < 3 || level == null || cache == null
                || cfg == null || connection == null) return existing;
        List<BlockPos> path = new ArrayList<>(existing.size());
        for (RoadSegmentPlacement segment : existing) {
            if (segment == null || segment.middlePos() == null) return existing;
            path.add(segment.middlePos());
        }
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        if (!streams.isAvailable()
                || !AccurateGradeRepairPlanner.intersectsRiver(path, width, streams)) return existing;
        PathfindingCostConfig pathCfg = cfg.pathfinding().snapshot();
        pathCfg.setAStarStep(1);
        pathCfg.sanitize();
        List<BlockPos> repaired = LakeRiverRepair.repairRiverIntersections(
                path, level, cache.getAccurateSampler(level), pathCfg, connection, streams,
                width, RIVER_LOCAL_DETOUR_BLOCKS);
        if (repaired == null || repaired.equals(path)
                || AccurateGradeRepairPlanner.intersectsRiver(repaired, width, streams)) return existing;
        refreshPlanningSnapshot(level, connection, repaired);
        List<RoadSegmentPlacement> rebuilt = buildSegments(
                repaired, width, level, cache, null, cache.getAccurateSampler(level), connection);
        if (rebuilt == null || rebuilt.size() < 3) return existing;
        LOGGER.info("RoadWeaver rebuilt road after river local repair connection={}->{} nodes={} -> {}",
                connection.from(), connection.to(), existing.size(), rebuilt.size());
        return rebuilt;
    }

    private static void refreshPlanningSnapshot(ServerLevel level,
                                                StructureConnection connection,
                                                List<BlockPos> path) {
        long startedAt = System.nanoTime();
        try {
            PlannedPathKey key = PlannedPathKey.of(connection);
            int fingerprint = pathFingerprint(path);
            SnapshotWaitEntry cached = SNAPSHOT_WAIT_CACHE.get(key);
            if (cached != null && cached.pathFingerprint() == fingerprint
                    && cached.future().isDone() && !cached.future().isCompletedExceptionally()) {
                // Same connection, byte-identical path, snapshot already
                // prepared: skip the redundant prediction/sampling round that
                // every detour radius and lake cycle used to repeat.
                return;
            }
            InitialGenerationProgressTracker.reportRoadTaskWaiting("STRUCTURE_SNAPSHOT_FUTURE");
            CompletableFuture<Void> future =
                    ensurePlanningSnapshotForPathAsync(level, connection, path);
            future.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    SNAPSHOT_WAIT_CACHE.put(key, new SnapshotWaitEntry(fingerprint, future));
                }
            });
            awaitPlanningSnapshot(future);
        } finally {
            InitialGenerationProgressTracker.advanceCurrentRoadTask(370, "road_structure_snapshot_ready");
            DetailedRoadGenerationStats.recordStructureCorridor(System.nanoTime() - startedAt);
        }
    }

    /** Per-connection wait cache: unchanged path → reuse the completed snapshot future. */
    private record SnapshotWaitEntry(int pathFingerprint, CompletableFuture<Void> future) {}

    private static final java.util.concurrent.ConcurrentHashMap<PlannedPathKey, SnapshotWaitEntry>
            SNAPSHOT_WAIT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static int pathFingerprint(List<BlockPos> path) {
        return path == null ? 0 : path.hashCode();
    }

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

    private static void awaitPlanningSnapshot(CompletableFuture<Void> future) {
        try {
            // Bounded wait: predictions run on the dedicated RW-Snapshot pool
            // and always complete, so this only fires if that lane is wedged.
            // Failing the route (topology fallback) beats hanging the road
            // worker forever on an unsafe "proceed without snapshot".
            future.get(snapshotWaitTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            future.cancel(true);
            throw new CancellationException("Road structure-corridor prediction timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("Road structure-corridor wait interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Road structure-corridor preparation failed", cause);
        }
    }

    private static List<BlockPos> repairAndRecordGrade(List<BlockPos> path,
                                                       ServerLevel level,
                                                       AccurateHeightSampler accurate,
                                                       PathfindingCostConfig pathCfg,
                                                       StructureConnection connection) {
        long startedAt = System.nanoTime();
        try {
            // During local repair the initial pass already recorded the
            // construction-height conflict. Reroute ONLY that bounded 50-block
            // circle instead of re-running the whole-path grade repair. The
            // reroute merges on success and keeps the original route on failure.
            BlockPos hinted = consumeConstructionConflictHint(connection);
            if (hinted != null && path != null && path.size() >= 3) {
                int segment = nearestSegmentIndex(path, hinted);
                if (segment >= 0) {
                    LOGGER.info("RoadWeaver local-repair conflict-hint consumed connection={}->{} hint={},{} segment={} nodes={}",
                            connection == null ? "?" : connection.from(),
                            connection == null ? "?" : connection.to(),
                            hinted.getX(), hinted.getZ(), segment, path.size());
                    ArrayList<BlockPos> rerouted = new ArrayList<>(
                            AccurateGradeRepairPlanner.rerouteConstructionHeightConflict(
                                    path, segment, level, accurate, pathCfg, connection));
                    if (!rerouted.equals(path)) {
                        LOGGER.info("RoadWeaver local-repair conflict reroute MERGED segment={} nodes={} -> {}",
                                segment, path.size(), rerouted.size());
                        return List.copyOf(rerouted);
                    }
                    LOGGER.warn("RoadWeaver local-repair conflict reroute could not bypass, keeping original gap "
                                    + "connection={}->{} hint={},{} segment={}",
                            connection == null ? "?" : connection.from(),
                            connection == null ? "?" : connection.to(),
                            hinted.getX(), hinted.getZ(), segment);
                }
            }
            if (hinted != null) {
                LOGGER.info("RoadWeaver local-repair hint found but no usable segment, falling back to whole-path repair "
                                + "connection={}->{} hint={},{}",
                        connection == null ? "?" : connection.from(),
                        connection == null ? "?" : connection.to(),
                        hinted.getX(), hinted.getZ());
            }
            LOGGER.info("RoadWeaver whole-path grade repair connection={}->{} nodes={}",
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to(),
                    path == null ? 0 : path.size());
            return AccurateGradeRepairPlanner.repair(path, level, accurate, pathCfg, connection);
        } finally {
            DetailedRoadGenerationStats.recordGradeRepair(System.nanoTime() - startedAt);
        }
    }

    private static int nearestSegmentIndex(List<BlockPos> path, BlockPos target) {
        if (path == null || target == null) return -1;
        int nearest = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos pos = path.get(i);
            if (pos == null) continue;
            long dx = (long) pos.getX() - target.getX();
            long dz = (long) pos.getZ() - target.getZ();
            long dist = dx * dx + dz * dz;
            if (dist < best) {
                best = dist;
                nearest = i;
            }
        }
        return nearest;
    }

    private static long routeSignature(List<BlockPos> path) {
        long hash = 0xcbf29ce484222325L;
        if (path == null) return hash;
        for (BlockPos pos : path) {
            if (pos == null) continue;
            hash ^= pos.asLong();
            hash *= 0x100000001b3L;
        }
        hash ^= path.size();
        return hash;
    }

    /** 0.8.318：成段失败的细分原因（纯观测），见 {@link #consumeRebuildFailureReason()}。 */
    private static final ThreadLocal<String> LAST_SEGMENT_FAILURE = new ThreadLocal<>();

    private static String consumeSegmentFailureReason() {
        String reason = LAST_SEGMENT_FAILURE.get();
        LAST_SEGMENT_FAILURE.remove();
        return reason == null ? "unknown" : reason;
    }

    private static List<RoadSegmentPlacement> buildSegments(List<BlockPos> path,
                                                             int width,
                                                             ServerLevel level,
                                                             TerrainSamplingCache cache,
                                                             PathTerrainField terrain,
                                                             AccurateHeightSampler accurate,
                                                             StructureConnection connection) {
        List<RoadSegmentPlacement> curved = PathPostProcessor.process(
                path,
                width,
                level,
                cache,
                terrain,
                PathPostProcessor.configuredBridgeMinWaterDepth(),
                net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode.CATMULL_ROM,
                accurate);
        if (curved == null || curved.size() < 2) {
            curved = null;
            LAST_SEGMENT_FAILURE.set("curved_null");
        }
        if (curved != null && (connection == null
                || !intersectsPreparedRoute(level, connection, curved))) {
            return curved;
        }
        if (curved != null && connection != null
                && !protectedGapSpans(level, connection, curved).isEmpty()) {
            // 0.8.316：平滑（Catmull-Rom）会把已经绕开建筑的折线推进建筑里——真机
            // "retained route with internal building protection gap" 全部出现在平滑路径上。
            // 旧代码在这里直接保留带缺口的平滑路径，连"不做平滑的直线版本"都没算过。
            // 这里补算一次未平滑版本：只要它的保护缺口更少就换用它（缺口 = 路面被建筑
            // 打断，比路稍显生硬严重得多），从源头减少交给局部修复的缺口数。
            int curvedGaps = protectedGapSpans(level, connection, curved).size();
            if (preferUnsmoothedOnProtectionGap()) {
                List<RoadSegmentPlacement> unsmoothed = PathPostProcessor.processUnrelaxedLinear(
                        path, width, level, cache, terrain,
                        PathPostProcessor.configuredBridgeMinWaterDepth(), accurate);
                if (unsmoothed != null && unsmoothed.size() >= 3) {
                    int unsmoothedGaps = protectedGapSpans(level, connection, unsmoothed).size();
                    if (unsmoothedGaps < curvedGaps) {
                        // 0.8.324：未平滑版是原始 A* 走廊，可能明显更长、更折。真机
                        // 20260909 16.15 存档 (288,-256)->(880,208)：缺口 5->0，但站点
                        // 从 1217 涨到 2067（+70%），8 次局部 splice 的成果全被丢弃，
                        // 路面上留下 61 处来回折角。只有长度增幅不超过阈值才换用它。
                        if (unsmoothed.size() <= Math.round(curved.size() * UNSMOOTHED_MAX_LENGTH_RATIO)) {
                            LOGGER.info("RoadWeaver preferred unsmoothed route: protection gaps {} -> {} "
                                            + "curvedSegments={} unsmoothedSegments={} connection={}->{}",
                                    curvedGaps, unsmoothedGaps, curved.size(), unsmoothed.size(),
                                    connection.from(), connection.to());
                            return unsmoothed;
                        }
                        LOGGER.info("RoadWeaver kept smoothed route: unsmoothed variant too long "
                                        + "protection gaps {} -> {} curvedSegments={} unsmoothedSegments={} "
                                        + "connection={}->{}",
                                curvedGaps, unsmoothedGaps, curved.size(), unsmoothed.size(),
                                connection.from(), connection.to());
                    }
                }
            }
            LOGGER.warn("RoadWeaver retained route with internal building protection gap gaps={} connection={}->{}",
                    curvedGaps, connection.from(), connection.to());
            return curved;
        }

        List<RoadSegmentPlacement> linear = PathPostProcessor.processUnrelaxedLinear(
                path, width, level, cache, terrain, PathPostProcessor.configuredBridgeMinWaterDepth(), accurate);
        if (linear == null || linear.size() < 2) {
            LAST_SEGMENT_FAILURE.set("linear_null");
        }
        if (linear != null && linear.size() >= 2
                && !intersectsPreparedRoute(level, connection, linear)) {
            LOGGER.info("RoadWeaver retained unsmoothed route to preserve a structure connection={}->{}",
                    connection.from(), connection.to());
            return linear;
        }
        if (linear != null && linear.size() >= 3 && connection != null
                && !protectedGapSpans(level, connection, linear).isEmpty()) {
            LOGGER.warn("RoadWeaver retained linear route with internal building protection gap connection={}->{}",
                    connection.from(), connection.to());
            return linear;
        }
        LAST_SEGMENT_FAILURE.set("still_intersects_protected_structure");
        LOGGER.warn("RoadWeaver rejected post-processed route that still intersects a protected structure connection={}->{}",
                connection.from(), connection.to());
        return null;
    }

    /** 0.8.316：平滑路径出现保护缺口时，是否先试未平滑版本并择优。 */
    private static boolean preferUnsmoothedOnProtectionGap() {
        try {
            return ConfigService.get().planning().preferUnsmoothedOnProtectionGap();
        } catch (Throwable ignored) {
            return true;
        }
    }

    static int searchMargin(BlockPos start, BlockPos end) {
        int distance = Math.abs(start.getX() - end.getX()) + Math.abs(start.getZ() - end.getZ());
        return Math.min(configuredPlanMaxEdgeLenBlocks(), Math.max(512, distance));
    }

    /**
     * 0.8.421：搜索外扩上限跟随「主连边长度上限」配置（默认 2048，与出厂常量一致）。
     * 读不到配置（离线/测试环境）时回落到常量，不抛异常。
     */
    private static int configuredPlanMaxEdgeLenBlocks() {
        try {
            return ConfigService.get().planning().planMaxEdgeLenBlocks();
        } catch (Throwable ignored) {
            return RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;
        }
    }

    public record PathCalculationResult(List<RoadSegmentPlacement> segments, PathTerrainField terrain) {
        public static PathCalculationResult failure() {
            return new PathCalculationResult(null, null);
        }
    }
}
