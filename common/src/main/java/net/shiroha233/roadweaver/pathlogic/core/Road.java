/* 文件职责：生成单条道路并协调精确地形路径、跨度与附属结构。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathMetricsOps;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathFinalizeOps;
import net.shiroha233.roadweaver.pathfinding.SpanConflictResolver;
import net.shiroha233.roadweaver.pathfinding.FillBridgeExtender;
import net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner;
import net.shiroha233.roadweaver.planning.terrain.StructureRepair;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.spec.RoadSpec;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator.PathCalculationResult;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;

import net.shiroha233.roadweaver.pathlogic.profile.ModeDpBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.ConnectionDeletionService;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.shiroha233.roadweaver.pathlogic.bridge.CulvertPlanner;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.metrics.RoadQualityMetrics;
import net.shiroha233.roadweaver.pathlogic.metrics.RoadBuildEvidence;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureRoadOffsetService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadPlanConsistencyService;
import static net.shiroha233.roadweaver.pathlogic.core.RoadCrossingOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps.*;

/**
 * 道路生成器
 */
public final class Road {

    /** 数据完整性自检的累计违规数（只用于限流日志）。 */
    private static final AtomicInteger SPEC_VIOLATIONS = new AtomicInteger();
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** T-21: 短浅滩(ROAD_CROSSING)甲板在水位之上的可涉水余量。 */
    static final int FORD_DECK_CLEARANCE = 2;
    final ServerLevel level;
    final StructureConnection connection;
    final PathFeatureConfig featureConfig;
    final RoadGenerationConfig genConfig;

    private final java.util.function.UnaryOperator<RoadData> buildIntent;

    public Road(ServerLevel level, StructureConnection connection, 
                PathFeatureConfig featureConfig, RoadGenerationConfig genConfig) {
        this(level, connection, featureConfig, genConfig, java.util.function.UnaryOperator.identity());
    }

    /** 施工入口交付玩家意图；几何层不依赖编辑器，自动道路使用原结果。 */
    public Road(ServerLevel level, StructureConnection connection, PathFeatureConfig featureConfig,
                RoadGenerationConfig genConfig, java.util.function.UnaryOperator<RoadData> buildIntent) {
        this.buildIntent = buildIntent;
        this.level = level;
        this.connection = connection;
        this.featureConfig = featureConfig;
        this.genConfig = genConfig;
    }

    public RoadData generateRoad() {
        return generateRoad(true);
    }

    public RoadData generateRoad(boolean persistImmediately) {
        return generateRoad(persistImmediately, 0);
    }

    RoadData generateRoad(boolean persistImmediately, int finalStructureAttempt) {
        if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
        long roadStartedAt = System.nanoTime();
        // 跨度回写（批 2 方案 A）：开场清掉上一条路可能残留的解。
        // 关闭时这是空操作；开启时保证「一条路最多消费一次、绝不跨路串味」。
        ModeDpBridge.clearStash();
        // 逐路计时：本线程一条路一份，阶段代码写、finally 打一行。
        DetailedRoadGenerationStats.beginRouteTiming(connection.from() + "->" + connection.to());
        boolean completed = false;
        BlockPos rawStart = connection.from();
        BlockPos rawEnd = connection.to();
        long ownerA2d = PlanningUtils.pos2dKey(rawStart);
        long ownerB2d = PlanningUtils.pos2dKey(rawEnd);
        RandomSource random = RandomSource.create(deterministicRoadSeed(
                level.getSeed(), ownerA2d, ownerB2d));
        int width = genConfig.effectiveRoadWidth(getRandomWidth(random, featureConfig));
        boolean allowA = genConfig.allowArtificial();
        boolean allowN = genConfig.allowNatural();
        if (!allowA && !allowN) return null;

        int type = allowA && allowN ? (random.nextBoolean() ? 0 : 1) : (allowA ? 0 : 1);
        List<BlockState> materials;
        List<BlockState> slabMaterials;
        PresetService.RoadType presetType = (type == 0) ? PresetService.RoadType.ARTIFICIAL : PresetService.RoadType.NATURAL;
        if (presetType == PresetService.RoadType.ARTIFICIAL) {
            PresetService.PresetDef preset = PresetService.choosePreset(random, presetType);
            materials = PresetService.toBlockStatesFromIds(preset.materials());
            slabMaterials = PresetService.toBlockStatesFromIds(preset.slabMaterials());
        } else {
            materials = List.of();
            slabMaterials = List.of();
        }

        TerrainSamplingCache cache = new TerrainSamplingCache();
        PathTerrainField terrain = null;
        long plannedLookupStartedAt = System.nanoTime();
        // 改道必须重搜：原路线的缓存键没有运行期禁令，重用它会直接跳过新约束。
        var corridorBan = genConfig.pathfinding().corridorBan();
        Optional<List<BlockPos>> planned = corridorBan != null && !corridorBan.isEmpty()
                ? Optional.empty()
                : PlannedPathCache.find(level, connection,
                        TerrainSamplingSessions.forLevel(level).effectiveMode(), genConfig.pathfinding());
        DetailedRoadGenerationStats.recordPlannedPathLookup(System.nanoTime() - plannedLookupStartedAt);
        try {
            PathCalculationResult pathResult;
            if (planned.isPresent()) {
                pathResult = PathFinalizeOps.calculateFromPlannedPath(
                        planned.get(), width, level, cache, genConfig, connection);
            } else {
                pathResult = PathMetricsOps.calculateAStarRoadPathDetailed(
                        rawStart, rawEnd, width, level, cache, genConfig);
            }

            List<RoadSegmentPlacement> rawSegments = pathResult.segments();
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            if (rawSegments == null || rawSegments.size() < 5) return null;

            List<RoadSegmentPlacement> segments = StructureRoadOffsetService.trimPathNearStructure(
                    level, rawSegments, rawStart, rawEnd);
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            if (segments == null || segments.size() < 5) return null;
            if (pathResult.terrain() != null) {
                terrain = pathResult.terrain();
            }
            StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            // Startup performs one global, awaited Streams prewarm before any
            // road workers are submitted. Never enqueue a second river prewarm
            // from an RW-Initial road worker: it steals the road lane and can
            // make every route wait behind a duplicate river task.
            if (InitialGenManager.isActive()) {
                InitialGenerationProgressTracker.advanceCurrentRoadTask(420, "road_span_extraction");
            }
            long targetHeightStartedAt = System.nanoTime();
            // 这时已有正式宽度的站序。先复用它定位问题，不能等到存出 RoadData 才允许绕行。
            var routeTerrain = terrain;
            var preparation = RoadProfileRecovery.solve(segments, width,
                    candidate -> solveInitialProfile(candidate, level, cache, routeTerrain, streams),
                    (line, station, conflict, accept) -> StructureRepair.tryProfileSurface(line, station, conflict, width, level,
                            connection, cache.getAccurateSampler(level), genConfig.pathfinding(), accept));
            if (preparation.segments() != segments) {
                segments = preparation.segments();
                PathFinalizeOps.refreshPlanningSnapshot(level, connection,
                        segments.stream().map(RoadSegmentPlacement::middlePos).toList());
                LOGGER.info("【纵断面地表绕行通过，进入原有施工校验】 connection={}->{} 站数={}",
                        rawStart, rawEnd, segments.size());
            }
            List<RoadSpan> spans = preparation.spans();
            List<Integer> targetY = preparation.heights();
            InitialGenerationProgressTracker.advanceCurrentRoadTask(680, "road_target_height_ready");
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            List<RoadSpan> protectedGaps = protectedGapSpans(
                    level, connection, segments);
            if (!protectedGaps.isEmpty()) {
                ArrayList<RoadSpan> withGaps = new ArrayList<>(spans);
                withGaps.addAll(protectedGaps);
                spans = List.copyOf(withGaps);
                LOGGER.warn("【建筑保护缺口已持久化】 " + "RoadWeaver persisted protected building gaps connection={}->{} count={}",
                        connection.from(), connection.to(), protectedGaps.size());
            }
            List<RoadSpan> plannedReuse = PlannedPathCache.plannedReuseSpans(level, connection, segments);
            if (!plannedReuse.isEmpty()) {
                ArrayList<RoadSpan> withReuse = new ArrayList<>(spans);
                withReuse.addAll(plannedReuse);
                spans = List.copyOf(withReuse);
                LOGGER.info("【既有路网复用跨度已持久化】 " + "RoadWeaver persisted planned NETWORK_REUSE spans connection={}->{} count={}",
                        connection.from(), connection.to(), plannedReuse.size());
            }
            DetailedRoadGenerationStats.recordTargetHeight(System.nanoTime() - targetHeightStartedAt);
            // Everything from here to the final validation used to be untimed: it is where the conflict
            // repair, the continuity recompute and the volume checks live, and in the measured session the
            // stage timers summed to 135 s of a 142 s wall with no line explaining the remaining stretch.
            // ── 跨度回写（批 2 收尾，用户 2026-11-15 拍板方案 A）──────────────
            // 把 computeTargetY 内部那次联合 DP 判出的桥隧**合并**进 spans。
            //
            // 三个必须说清的点：
            //   ① **merge 不是 replace**：`mergeByModes` 只替换桥隧段，`PROTECTED_GAP`
            //      （穿建筑的路）与 `NETWORK_REUSE`（防双铺）以及 `ROAD_CROSSING`
            //      （跨另一条路，走路口逻辑不是水上桥）全部原样保留。
            //   ② **DP 无解时保持原样**：`consumeStash()` 返回 null ⇒ 什么都不做，
            //      旧判定继续生效 —— 绝不能因为「DP 没算出来」就把桥删掉。
            //   ③ **默认关闭**：`-Droadweaver.spanWriteback=on` 才进来；
            //      关闭时本块零执行、零行为变化，可直接当回退开关用。
            spans = applySpanWriteback(spans, ownerA2d, ownerB2d, "main");
            long planningRepairStartedAt = System.nanoTime();
            RoadData rd = new RoadData(width, type, materials, slabMaterials, segments, spans, targetY, ownerA2d, ownerB2d);
            RoadData constructionRepaired = RoadPathCalculator.repairFinalHeightConflict(
                    rd, level, cache, connection);
            if (constructionRepaired == null) {
                LOGGER.warn("【持久化前施工连续性修复失败，拒绝路线】 " + "RoadWeaver rejected route before persistence because local construction continuity repair failed connection={}->{}",
                        rawStart, rawEnd);
                LOGGER.warn("【路线被过滤：施工连续性修复失败】 " + "RoadWeaver route filtered: reason=construction_continuity_repair_failed connection={}->{}",
                        rawStart, rawEnd);
                return null;
            }
            if (constructionRepaired != rd) {
                rd = constructionRepaired;
                segments = rd.roadSegmentList();
                spans = rd.spans();
                targetY = rd.targetY();
                // A height reroute can change water and overburden spans. Do
                // not carry the old bridge/tunnel classification by X/Z only;
                // recompute physical spans for the new corridor while
                // retaining metadata that belongs to the original connection.
                List<RoadSpan> refreshedSpans =
                        extractPhysicalSpans(segments, level, cache, terrain, streams);
                ArrayList<RoadSpan> retainedMetadata = new ArrayList<>(refreshedSpans);
                for (RoadSpan span : spans) {
                    if (span == null) continue;
                    if (span.type() == SpanType.PROTECTED_GAP
                            || span.type() == SpanType.NETWORK_REUSE
                            || span.type() == SpanType.JUNCTION_ANCHOR) {
                        retainedMetadata.add(span);
                    }
                }
                spans = List.copyOf(retainedMetadata);
                SettledProfile settled = settleProfile(segments, spans, level, cache, terrain,
                        genConfig, streams);
                spans = settled.spans();
                targetY = settled.targetY();
                // ★ 2026-11-15：这一支会把上面写入的 DP 结果**丢掉** ——
                //   `extractPhysicalSpans` 重建物理跨度时只保留 PROTECTED_GAP /
                //   NETWORK_REUSE / JUNCTION_ANCHOR，DP 判出的 BRIDGE/TUNNEL 不在其中。
                //   而 `settleProfile` 内部又跑了一次 `computeTargetY` ⇒ 快照已经被
                //   更新成「修复后地形」的新解。所以这里**再消费一次并合并**：
                //   用的是修复后的地形，比上面那次更贴合最终走廊。
                //   若没有新解（DP 无解 / 回写关闭），本调用原样返回，不影响旧判定。
                spans = applySpanWriteback(spans, ownerA2d, ownerB2d, "post-repair");
                LOGGER.info("【已接受有界施工高度修复】 " + "RoadWeaver accepted bounded construction-feasibility height repair connection={}->{}",
                        rawStart, rawEnd);
            }
            if (persistImmediately) {
                rd = ExistingRoadJunctionService.connectToExistingNetwork(level, rd, cache);
                if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
                segments = rd.roadSegmentList();
                spans = rd.spans();
                targetY = rd.targetY();
                // Network junction/reuse can rewrite the target profile after
                // the first height pass. Re-run the complete construction
                // feasibility repair on the merged route before validation or
                // persistence; otherwise a valid individual route can become
                // an impossible fill/cut at the junction.
                RoadData mergedConstructionRepaired = RoadPathCalculator.repairFinalHeightConflict(
                        rd, level, cache, connection);
                if (mergedConstructionRepaired == null) {
                    LOGGER.warn("【路口合并后施工连续性修复失败，拒绝路线】 " + "RoadWeaver rejected merged route because post-junction construction continuity repair failed connection={}->{}",
                            rawStart, rawEnd);
                    LOGGER.warn("【路线被过滤：路口合并后施工连续性修复失败】 " + "RoadWeaver route filtered: reason=post_junction_construction_repair_failed connection={}->{}",
                            rawStart, rawEnd);
                    return null;
                }
                if (mergedConstructionRepaired != rd) {
                    rd = mergedConstructionRepaired;
                    segments = rd.roadSegmentList();
                    spans = rd.spans();
                    targetY = rd.targetY();
                    LOGGER.info("【已接受路口合并后的施工高度修复】 " + "RoadWeaver accepted post-junction construction-feasibility height repair connection={}->{}",
                            rawStart, rawEnd);
                }
            }
            RoadData consistencyInput = rd;
            rd = RoadPlanConsistencyService.reconcile(
                    level, rd, cache, terrain, streams, routeBridgeMinWaterDepth());
            if (rd == null) {
                LOGGER.warn("【最终计划一致性协调失败，拒绝路线】 " + "RoadWeaver rejected route because final plan consistency reconciliation failed connection={}->{}",
                        rawStart, rawEnd);
                LOGGER.warn("【路线被过滤：计划一致性协调失败】 " + "RoadWeaver route filtered: reason=plan_consistency_reconcile_failed connection={}->{}",
                        rawStart, rawEnd);
                RoadPlanningService.rebindGeneratedRouteForRetry(
                        level, connection, consistencyInput, localRetryStep());
                return null;
            }
            // The consistency gate rebuilds physical spans from the final
            // profile. A late tunnel chord, crossing deck or endpoint lock can
            // leave one-block discontinuities that only become visible here.
            // Close them one final time so validation observes the same
            // walkable grade the paver will build.
            rd = RoadDataValidator.repairFinalHeightSteps(rd);
            // 水域、结构、路边建筑和存档都必须看到同一条最终道路，包括玩家指定的桥高与隧道。
            rd = buildIntent.apply(rd);
            segments = rd.roadSegmentList();
            spans = rd.spans();
            targetY = rd.targetY();
            RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(rd);
            if (!validation.valid()) {
                LOGGER.error("【最终道路数据无效，拒绝路线】 " + "RoadWeaver rejected invalid finalized road {} -> {} reason={} index={} pos={} detail={}",
                        rawStart, rawEnd, validation.reason(), validation.index(),
                        validation.position(), validation.detail());
                LOGGER.warn("【路线被过滤：最终道路数据无效】 " + "RoadWeaver route filtered: reason=invalid_finalized_road:{} connection={}->{}",
                        validation.reason(), rawStart, rawEnd);
                RoadPlanningService.rebindGeneratedRouteForRetry(
                        level, connection, rd, localRetryStep());
                return null;
            }
            FinalRoadWaterValidator.ValidationResult waterValidation =
                    FinalRoadWaterValidator.validate(level, rd, cache, streams);
            InitialGenerationProgressTracker.advanceCurrentRoadTask(900, "road_water_validation");
            FinalRoadWaterValidator.Collision waterCollision = waterValidation.collision();
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            if (!waterValidation.safe()) {
                if ("ordinary_construction_in_water".equals(waterValidation.reason())
                        && waterCollision != null
                        && waterCollision.source() != null
                        && waterCollision.source().startsWith("streams_channel")) {
                    RoadData crossed = forceCrossingAtWaterCollision(rd, waterCollision.segmentIndex());
                    if (crossed != rd) {
                        try {
                            var settled = settleProfile(crossed.roadSegmentList(), crossed.spans(), level, cache,
                                    terrain, genConfig, streams);
                            // 最后发现入水时也要重新求坡，标签不能代替甲板；手绘高度意图仍由终验核实。
                            crossed = buildIntent.apply(new RoadData(rd.width(), rd.roadType(), rd.materials(),
                                    rd.slabMaterials(), crossed.roadSegmentList(), settled.spans(), settled.targetY(),
                                    rd.ownerA2dKey(), rd.ownerB2dKey()));
                        } catch (ProfileConflict.Rejected rejected) {
                            LOGGER.debug("【临时过水方案不可接坡】 {}", rejected.conflict());
                            crossed = rd;
                        }
                    }
                    if (crossed != rd) {
                        FinalRoadWaterValidator.ValidationResult crossedValidation =
                                FinalRoadWaterValidator.validate(level, crossed, cache, streams);
                        if (crossedValidation.safe()
                                && RoadDataValidator.validateFinalRoad(crossed).valid()) {
                            SpanType promotedType = crossingTypeAtCollision(
                                    crossed, waterCollision.segmentIndex());
                            rd = crossed;
                            segments = rd.roadSegmentList();
                            spans = rd.spans();
                            targetY = rd.targetY();
                            waterValidation = crossedValidation;
                            waterCollision = null;
                            LOGGER.warn("【河流碰撞已晋级为对应过水类型】 " + "RoadWeaver promoted Streams river collision to {} connection={}->{}",
                                    promotedType, rawStart, rawEnd);
                        }
                    }
                }
                if ("ordinary_construction_in_water".equals(waterValidation.reason())) {
                    RoadData rerouted = tryRiverRepair(rd, cache, terrain, streams);
                    if (rerouted != rd) {
                        // 绕河重新求解了高度和跨度，须对新中心线重新施加此分段的手绘意图。
                        rd = buildIntent.apply(rerouted);
                        segments = rd.roadSegmentList();
                        spans = rd.spans();
                        targetY = rd.targetY();
                        waterValidation = FinalRoadWaterValidator.validate(level, rd, cache, streams);
                        waterCollision = waterValidation.collision();
                    }
                }
                if ("ordinary_construction_in_water".equals(waterValidation.reason())
                        && waterCollision != null) {
                    // A narrow, shallow watercourse is neither a bridge job nor a gap: lift the deck just
                    // above the water and let the stream keep flowing under it. Without this the route
                    // either lost a station (protected gap) or filled the bed into a dam.
                    var roadCfg = ConfigService.get();
                    if (roadCfg != null && roadCfg.culvertEnabled()) {
                        CulvertPlanner.Result culvert = CulvertPlanner.cross(rd,
                                roadCfg.culvertMaxStations(), roadCfg.culvertMaxWaterDepth(),
                                FinalRoadWaterValidator.probe(level, cache, streams));
                        if (culvert.changed()) {
                            RoadData culverted = culvert.road();
                            if (RoadDataValidator.validateFinalRoad(culverted).valid()) {
                                rd = culverted;
                                segments = rd.roadSegmentList();
                                spans = rd.spans();
                                targetY = rd.targetY();
                                waterValidation = FinalRoadWaterValidator.validate(level, rd, cache, streams);
                                waterCollision = waterValidation.collision();
                                LOGGER.warn("【窄水域已按涵洞方案处理，后续仍须终态校验】 " + "RoadWeaver culverted narrow water connection={}->{} runs={} "
                                                + "stations={} safe={} reason={}",
                                        rawStart, rawEnd, culvert.runs(), culvert.stations(),
                                        waterValidation.safe(), waterValidation.reason());
                            }
                        }
                    }
                }
                if ("ordinary_construction_in_water".equals(waterValidation.reason())
                        && waterCollision != null) {
                    // 0.8.322：旧兜底只标第一个碰撞点；一条路有多个 1 格水洼时第二格仍会
                    // 让整条路被拒（真机 15.21 存档 617/619 两格）。改成循环标记。
                    RoadData gapped = FinalRoadWaterValidator.retainProtectedGaps(
                            rd, FinalRoadWaterValidator.MAX_PROTECTED_GAP_FALLBACK,
                            candidate -> FinalRoadWaterValidator.validate(level, candidate, cache, streams));
                    if (gapped != null) {
                        rd = gapped;
                        segments = rd.roadSegmentList();
                        spans = rd.spans();
                        targetY = rd.targetY();
                        waterValidation = FinalRoadWaterValidator.validate(level, rd, cache, streams);
                        waterCollision = waterValidation.collision();
                        LOGGER.warn("【保留河流保护缺口和剩余可用路线】 " + "RoadWeaver retained river protection gaps and preserved remaining route "
                                        + "connection={}->{} firstSegment={} pos={} source={}",
                                rawStart, rawEnd, waterCollision == null ? -1 : waterCollision.segmentIndex(),
                                waterCollision == null ? null : waterCollision.position(),
                                waterCollision == null ? null : waterCollision.source());
                    }
                }
            }
            if (RoadBuildEvidence.enabled()) LOGGER.info("【路线最终水域校验证据】 " + "{}", RoadBuildEvidence.validation(
                    level.dimension().location().toString(), level.getSeed(), rd,
                    "final-water", waterValidation.safe(), waterValidation.reason()));
            if (!waterValidation.safe()) {
                LOGGER.error("【最终道路入水，拒绝路线】 " + "RoadWeaver rejected finalized road in water {} -> {} reason={} "
                                + "segment={} pos={} targetY={} waterSurfaceY={} bedY={} span={} source={}",
                        rawStart, rawEnd, waterValidation.reason(),
                        waterCollision == null ? -1 : waterCollision.segmentIndex(),
                        waterCollision == null ? null : waterCollision.position(),
                        waterCollision == null ? null : waterCollision.targetY(),
                        waterCollision == null ? null : waterCollision.waterSurfaceY(),
                        waterCollision == null ? null : waterCollision.bedY(),
                        waterCollision == null ? null : waterCollision.activeSpan(),
                        waterCollision == null ? null : waterCollision.source());
                LOGGER.warn("【路线被过滤：最终道路入水】 " + "RoadWeaver route filtered: reason=finalized_road_in_water:{} connection={}->{}",
                        waterValidation.reason(), rawStart, rawEnd);
                RoadPlanningService.rebindGeneratedRouteForRetry(
                        level, connection, rd, localRetryStep());
                return null;
            }
            // Task 3: bounded local structure-repair loop. A final volume
            // collision no longer discards the sampling cache and re-runs the
            // whole route recursively; the collided building is avoided with
            // one exact-clearance local detour and the route is re-validated.
            DetailedRoadGenerationStats.recordPlanningRepair(System.nanoTime() - planningRepairStartedAt);
            long finalValidationStartedAt = System.nanoTime();
            FinalRoadStructureValidator.ValidationResult structureValidation = null;
            // 绕行无解时原候选仍可能只需保留一个小缺口，不能让失败的绕行吃掉它。
            RoadData beforeStructureDetour = rd;
            for (int structureAttempt = finalStructureAttempt; structureAttempt <= 3; structureAttempt++) {
                structureValidation = FinalRoadStructureValidator.validate(level, connection, rd, cache, streams);
                InitialGenerationProgressTracker.advanceCurrentRoadTask(940, "road_structure_validation");
                if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
                if (structureValidation.safe()) break;
                FinalRoadStructureValidator.Collision collision = structureValidation.collision();
                LOGGER.error("【最终施工体积与保护区域相交，拒绝当前产物】 " + "RoadWeaver rejected finalized construction volume {} -> {} reason={} "
                                + "attempt={} segment={} kind={} pos={} y={}..{} owner={} box={}",
                        rawStart, rawEnd, structureValidation.reason(), structureAttempt + 1,
                        collision == null ? -1 : collision.segmentIndex(),
                        collision == null ? null : collision.kind(),
                        collision == null ? null : collision.position(),
                        collision == null ? null : collision.minY(),
                        collision == null ? null : collision.maxY(),
                        collision == null ? null : collision.owner(),
                        collision == null ? null : collision.componentBox());
                if (collision == null || collision.owner() == null || structureAttempt >= 3) {
                    LOGGER.warn("【不再具备局部修复条件，结束结构修复循环】 " + "RoadWeaver structure repair loop exiting: no further local attempt possible "
                                    + "reason={} attempt={} hasCollision={} hasOwner={} connection={}->{}",
                            structureValidation.reason(), structureAttempt + 1,
                            collision != null, collision != null && collision.owner() != null,
                            rawStart, rawEnd);
                    break;
                }
                StructureAvoidanceService.forceVerticalObstacle(level, connection, collision.owner());
                // Keep the current route available for the later local-repair
                // wave. The old discard made that wave lose its only path and
                // report "missing persisted route path".
                RoadPlanningService.rebindGeneratedRouteForRetry(
                        level, connection, rd, localRetryStep());
                if (collision.position() != null) {
                    RoadPathCalculator.recordConstructionConflictHint(connection, collision.position());
                    LOGGER.info("【已写入局部结构冲突提示】 " + "RoadWeaver structure conflict hint written attempt={} owner={} pos={} connection={}->{}",
                            structureAttempt + 1, collision.owner(), collision.position(), rawStart, rawEnd);
                }
                // Local detour around the collided building only.
                List<BlockPos> centreProfile = new ArrayList<>(segments.size());
                for (int station = 0; station < segments.size(); station++) {
                    BlockPos centre = segments.get(station).middlePos();
                    Integer stationY = station < targetY.size() ? targetY.get(station) : null;
                    if (centre == null || stationY == null) {
                        centreProfile = null;
                        break;
                    }
                    centreProfile.add(new BlockPos(centre.getX(), stationY, centre.getZ()));
                }
                if (centreProfile == null) {
                    LOGGER.warn("【缺少道路中心或目标高度，结束结构修复】 " + "RoadWeaver structure repair loop exiting: centre profile unavailable "
                                    + "reason=missing_station_center_or_target attempt={} stations={} connection={}->{}",
                            structureAttempt + 1, segments.size(), rawStart, rawEnd);
                    break;
                }
                PathfindingCostConfig detourCfg = genConfig.pathfinding().snapshot();
                detourCfg.sanitize();
                List<BlockPos> repaired = StructureRepair.repairStructureIntersectionsAtExactClearance(
                        centreProfile, level, cache.getAccurateSampler(level), detourCfg, connection, 50);
                if (repaired == null || repaired.size() < 2 || repaired.equals(centreProfile)) {
                    LOGGER.warn("【局部绕行不可用，结束结构修复】 " + "RoadWeaver structure repair loop exiting: local detour unavailable "
                                    + "reason={} attempt={} nodes={}->{} connection={}->{}",
                            repaired == null ? "null_repair" : repaired.size() < 2 ? "path_too_short" : "unchanged_path",
                            structureAttempt + 1, centreProfile.size(),
                            repaired == null ? -1 : repaired.size(), rawStart, rawEnd);
                    break;
                }
                RoadData rebuilt = RoadPathCalculator.rebuildRoadDataFromProfile(rd, repaired, level, cache, connection);
                if (rebuilt == null) {
                    LOGGER.warn("【绕行纵断面重建失败，结束结构修复】 " + "RoadWeaver structure repair loop exiting: rebuild from detour profile failed "
                                    + "attempt={} nodes={}->{} connection={}->{}",
                            structureAttempt + 1, centreProfile.size(), repaired.size(), rawStart, rawEnd);
                    break;
                }
                rebuilt = RoadPathCalculator.repairFinalHeightConflict(rebuilt, level, cache, connection);
                if (rebuilt == null) {
                    LOGGER.warn("【绕行后的高度修复失败，结束结构修复】 " + "RoadWeaver structure repair loop exiting: post-detour height repair failed "
                                    + "attempt={} connection={}->{}", structureAttempt + 1, rawStart, rawEnd);
                    break;
                }
                // 结构绕行也重建了全线剖面，水域和保护判定必须使用将被写盘的那一份。
                rebuilt = buildIntent.apply(rebuilt);
                FinalRoadWaterValidator.ValidationResult rebuiltWater =
                        FinalRoadWaterValidator.validate(level, rebuilt, cache, streams);
                if (!rebuiltWater.safe()) {
                    FinalRoadWaterValidator.Collision rebuiltWaterCollision = rebuiltWater.collision();
                    if (rebuiltWaterCollision == null || rebuiltWaterCollision.segmentIndex() < 0) {
                        LOGGER.warn("【绕行后水域校验失败且无碰撞详情，结束结构修复】 " + "RoadWeaver structure repair loop exiting: post-detour water check failed without collision "
                                        + "reason={} attempt={} connection={}->{}",
                                rebuiltWater.reason(), structureAttempt + 1, rawStart, rawEnd);
                        break;
                    }
                    RoadData gappedWater = FinalRoadWaterValidator.retainProtectedGapAt(
                            rebuilt, rebuiltWaterCollision.segmentIndex());
                    if (gappedWater == null
                            || !FinalRoadWaterValidator.validate(level, gappedWater, cache, streams).safe()) {
                        LOGGER.warn("【绕行后无法保留水域保护缺口，结束结构修复】 " + "RoadWeaver structure repair loop exiting: post-detour water gap retention failed "
                                        + "reason={} attempt={} segment={} pos={} connection={}->{}",
                                rebuiltWater.reason(), structureAttempt + 1,
                                rebuiltWaterCollision.segmentIndex(), rebuiltWaterCollision.position(),
                                rawStart, rawEnd);
                        break;
                    }
                    rebuilt = gappedWater;
                }
                rd = rebuilt;
                segments = rd.roadSegmentList();
                spans = rd.spans();
                targetY = rd.targetY();
                LOGGER.info("【最终体积碰撞后的局部结构绕行已接受】 " + "RoadWeaver accepted local structure detour after final volume collision "
                                + "attempt={} connection={}->{}",
                        structureAttempt + 1, rawStart, rawEnd);
            }
            if (structureValidation != null && structureValidation.collision() != null) {
                RoadData gapped = FinalRoadStructureValidator.retainLocalizedSurfaceGaps(
                        level, connection, beforeStructureDetour, cache, streams);
                if (gapped == null) {
                    // 保留原本已支持的实物建筑缺口；新分支只处理中间普通路面。
                    gapped = FinalRoadStructureValidator.retainVerifiedBuildingGaps(
                            level, connection, beforeStructureDetour, cache, streams);
                }
                if (gapped != null && FinalRoadWaterValidator.validate(level, gapped, cache, streams).safe()) {
                    rd = gapped;
                    segments = rd.roadSegmentList();
                    spans = rd.spans();
                    targetY = rd.targetY();
                    structureValidation = FinalRoadStructureValidator.ValidationResult.success();
                    LOGGER.warn("【结构绕行未通过，仅保留实际碰撞的施工缺口】 connection={}->{}", rawStart, rawEnd);
                }
            }
            long finalValidationNanos = System.nanoTime() - finalValidationStartedAt;
            DetailedRoadGenerationStats.recordFinalValidation(finalValidationNanos);
            DetailedRoadGenerationStats.RouteTiming validationTiming = DetailedRoadGenerationStats.routeTiming();
            if (validationTiming != null) validationTiming.addValidation(finalValidationNanos);
            if (RoadBuildEvidence.enabled()) LOGGER.info("【路线最终结构校验证据】 " + "{}", RoadBuildEvidence.validation(
                    level.dimension().location().toString(), level.getSeed(), rd, "final-structure",
                    structureValidation != null && structureValidation.safe(),
                    structureValidation == null ? "validation_not_run" : structureValidation.reason()));
            if (structureValidation == null || !structureValidation.safe()) {
                FinalRoadStructureValidator.Collision finalCollision = structureValidation == null
                        ? null : structureValidation.collision();
                LOGGER.error("【结构修复结束仍未通过，最终拒绝路线】 " + "RoadWeaver route finally rejected after structure repair loop "
                                + "reason={} attemptExhausted=true segment={} pos={} owner={} box={} connection={}->{}",
                        structureValidation == null ? "validation_not_run" : structureValidation.reason(),
                        finalCollision == null ? -1 : finalCollision.segmentIndex(),
                        finalCollision == null ? null : finalCollision.position(),
                        finalCollision == null ? null : finalCollision.owner(),
                        finalCollision == null ? null : finalCollision.componentBox(),
                        rawStart, rawEnd);
                LOGGER.warn("【路线被过滤：施工体积与保护区域相交】 " + "RoadWeaver route filtered: reason=construction_volume_intersection:{} connection={}->{}",
                        structureValidation == null ? "validation_not_run" : structureValidation.reason(),
                        rawStart, rawEnd);
                RoadPlanningService.rebindGeneratedRouteForRetry(
                        level, connection, rd, localRetryStep());
                return null;
            }
            long persistedFingerprint = 0L;
            if (persistImmediately) {
                markRoadCorridorPrepared(segments);
            }
            if (!RoadLifecycle.allowsWrites() || ConnectionDeletionService.shouldAbort(level, connection)) return null;
            if (persistImmediately) {
                persistedFingerprint = RoadShardStorage.computeFingerprint(rd);
                RoadShardStorage.addRoad(level, rd);
                if (ConnectionDeletionService.shouldAbort(level, connection)) {
                    RoadShardStorage.deleteRoad(level, persistedFingerprint);
                    return null;
                }
            }
            PlannedPathCache.discard(level, connection);

            if (persistImmediately) {
                try {
                    long roadsideStructuresStartedAt = System.nanoTime();
                    RoadsideStructurePrecomputer.precomputeStructures(
                            level, segments, spans, width, cache, terrain, random, targetY);
                    if (ConnectionDeletionService.shouldAbort(level, connection)) {
                        RoadShardStorage.deleteRoad(level, persistedFingerprint);
                        return null;
                    }
                    DetailedRoadGenerationStats.recordRoadsideStructure(
                            System.nanoTime() - roadsideStructuresStartedAt);
                    long roadsideVillagesStartedAt = System.nanoTime();
                    RoadsideVillagePrecomputer.precomputeVillages(
                            level, segments, spans, width, cache, terrain, random, targetY);
                    if (ConnectionDeletionService.shouldAbort(level, connection)) {
                        RoadShardStorage.deleteRoad(level, persistedFingerprint);
                        return null;
                    }
                    DetailedRoadGenerationStats.recordRoadsideVillage(
                            System.nanoTime() - roadsideVillagesStartedAt);
                } catch (RuntimeException roadsideFailure) {
                    LOGGER.error("【道路已持久化，失败的沿路内容已跳过】 " + "RoadWeaver persisted road but skipped failed roadside content {} -> {}",
                            rawStart, rawEnd, roadsideFailure);
                }
            }
            completed = true;
            // 规划通过仍未证明区块施工完成，后者由PathFeature单独发布结案。
            if (RoadBuildEvidence.enabled()) LOGGER.info("【路线终态校验通过；尚不代表方块竣工】 " + "{}", RoadBuildEvidence.validation(
                    level.dimension().location().toString(), level.getSeed(), rd, "validated", true, "accepted"));
            LOGGER.info("【道路计划已通过校验】 " + "RoadWeaver route PLANNED_OK connection={}->{} stations={} spans={} {}",
                    rawStart, rawEnd, rd.roadSegmentList().size(), rd.spans().size(), summarizeSpans(rd.spans()));
            // D1: the quality numbers, once per finished road, plus a session rollup every ten roads,
            // so a real session yields the eleven measurements instead of only "it built".
            RoadQualityMetrics.record(rd, rawStart, rawEnd);
            logSpecIntegrity(rd, rawStart, rawEnd);
            return rd;
        } finally {
            if (terrain != null) terrain.dispose();
            cache.clear();
            DetailedRoadGenerationStats.recordRoad(System.nanoTime() - roadStartedAt, completed);
            DetailedRoadGenerationStats.logRouteTiming();
        }
    }

    RoadData tryRiverRepair(RoadData road,
                                    TerrainSamplingCache cache,
                                    PathTerrainField terrain,
                                    StreamsReflowingCompat.SamplingSession streams) {
        if (road == null || road.hasProtectedGap() || road.spans().stream().anyMatch(
                span -> span != null && span.type() == SpanType.NETWORK_REUSE)) return road;
        List<RoadSegmentPlacement> rebuilt = PathFinalizeOps.repairRiverRouteSegments(
                road.roadSegmentList(), road.width(), level, cache, genConfig, connection);
        if (rebuilt == null || rebuilt == road.roadSegmentList()
                || rebuilt.equals(road.roadSegmentList())) return road;

        List<RoadSpan> newSpans = PathSpanExtractor.extractSpans(
                rebuilt, level, cache, terrain, streams, routeBridgeMinWaterDepth());
        List<Integer> newTargetY = computeTargetY(level, rebuilt, newSpans, cache, terrain, genConfig, streams);
        newSpans = PathSpanExtractor.refineTunnelSpans(newSpans, rebuilt, newTargetY, level, cache);
        RoadData candidate = new RoadData(road.width(), road.roadType(), road.materials(),
                road.slabMaterials(), rebuilt, newSpans, newTargetY,
                road.ownerA2dKey(), road.ownerB2dKey());
        candidate = RoadDataValidator.repairFinalHeightSteps(
                RoadDataValidator.normalizeDuplicateJunctions(candidate));
        return RoadDataValidator.validateFinalRoad(candidate).valid() ? candidate : road;
    }

    /** 「按当前 spans 结算剖面 + 隧道区间」的结果。 */
    private record SettledProfile(List<RoadSpan> spans, List<Integer> targetY) {
    }


    /**
     * 把 DP 判出的桥隧合并进 spans —— <b>跨度回写的唯一入口</b>。
     *
     * <h2>为什么抽成一个方法</h2>
     * <p>整条路上有<b>两个</b>必须回写的时机，而且它们必须共用同一套语义：</p>
     * <ol>
     *   <li>{@code generateRoad} 主路径：spans 首次定稿（{@code phase="main"}）；</li>
     *   <li>{@code repairFinalHeightConflict} 改写了走廊之后（{@code phase="post-repair"}）——
     *       那一支会用 {@code extractPhysicalSpans} 重建物理跨度，把上面写回的
     *       BRIDGE/TUNNEL 丢掉；而 {@code settleProfile} 内部又跑了一次
     *       {@code computeTargetY}，快照已被更新成「修复后地形」的新解。
     *       所以必须在那里<b>再合并一次</b>，否则开启回写后 repair 一触发就等于白做。</li>
     * </ol>
     *
     * <h2>三条不可动摇的语义</h2>
     * <ul>
     *   <li><b>回写关闭时零行为变化</b>：直接返回入参，不碰快照、不打日志。</li>
     *   <li><b>没有解就保持原样</b>：{@code consumeStash()} 返回 null（DP 无解 /
     *       本次没求解）⇒ 原样返回。绝不能因为「DP 没算出来」把旧桥删掉。</li>
     *   <li><b>merge 不是 replace</b>：{@code mergeByModes} 只替换桥隧段，
     *       {@code PROTECTED_GAP} / {@code NETWORK_REUSE} / {@code ROAD_CROSSING} /
     *       {@code JUNCTION_ANCHOR} 全部保留。</li>
     * </ul>
     *
     * @param current  当前 spans
     * @param phase    日志用阶段名（{@code main} / {@code post-repair}）
     * @return 合并后的 spans；任何「不该动手」的情形都返回 {@code current} 本身
     */
    private List<RoadSpan> applySpanWriteback(List<RoadSpan> current, long ownerA2d, long ownerB2d, String phase) {
        if (!ModeDpBridge.writebackEnabled()) return current;
        ModeDpBridge.Stash stash = ModeDpBridge.consumeStash();
        if (stash == null) {
            LOGGER.info("【没有暂存跨度解，保留当前跨度】 " + "RoadWeaver [SPAN-WB] phase={} no stashed solution, keeping legacy spans"
                            + " connection={}->{} {}",
                    phase, ownerA2d, ownerB2d, ModeDpBridge.writebackStats());
            return current;
        }
        List<RoadSpan> merged = ModeDpBridge.mergeByModes(
                current, stash.solution().modes(), stash.centers(), stash.stations());
        ModeDpBridge.noteWritebackApplied();
        LOGGER.info("【跨度解已写回道路数据】 " + "RoadWeaver [SPAN-WB] phase={} spans written back connection={}->{}"
                        + " before={} after={} {}",
                phase, ownerA2d, ownerB2d, current.size(), merged.size(), ModeDpBridge.writebackStats());
        return merged;
    }

    /** 统一从最终站点和目标剖面提取物理跨度，避免两处不同输入口径。 */
    private List<RoadSpan> extractPhysicalSpans(List<RoadSegmentPlacement> segments, ServerLevel level,
                                                TerrainSamplingCache cache, PathTerrainField terrain,
                                                StreamsReflowingCompat.SamplingSession streams) {
        return PathSpanExtractor.extractSpans(segments, level, cache, terrain, streams,
                routeBridgeMinWaterDepth());
    }

    /** 初稿和局部改线重新提取水域与隧道许可，不能把旧线的跨度直接贴到新线。 */
    private RoadProfileRecovery.Profile solveInitialProfile(List<RoadSegmentPlacement> segments, ServerLevel level,
            TerrainSamplingCache cache, PathTerrainField terrain, StreamsReflowingCompat.SamplingSession streams) {
        long started = System.nanoTime();
        List<RoadSpan> spans = extractPhysicalSpans(segments, level, cache, terrain, streams);
        long elapsed = System.nanoTime() - started;
        DetailedRoadGenerationStats.recordSpanExtraction(elapsed);
        var timing = DetailedRoadGenerationStats.routeTiming();
        if (timing != null) timing.addSpanExtraction(elapsed);
        LOGGER.info("【路线跨度提取阶段结果】 RoadWeaver route stage=span_extraction connection={}->{} spans={} {}",
                connection.from(), connection.to(), spans.size(), summarizeSpans(spans));
        if (spans.stream().anyMatch(span -> span.type() == SpanType.BRIDGE)) {
            // 只为可走水底的长桥预览地下候选；不能用未建甲板的预览无解否决已选跨越。
            try {
                var shortSpans = spans.stream().filter(span -> span.type() != SpanType.BRIDGE).toList();
                var preview = computeTargetY(level, segments, new ArrayList<>(shortSpans), cache, terrain, genConfig, streams);
                var tunnels = PathSpanExtractor.refineTunnelSpans(shortSpans, segments, preview, level, cache);
                var longBridges = spans.stream().filter(span -> span.type() == SpanType.BRIDGE).toList();
                var retained = new ArrayList<>(shortSpans);
                retained.addAll(SpanConflictResolver.retainVerticallyRelevantCrossings(longBridges, tunnels, segments, preview,
                        level, cache, terrain, streams, genConfig.tunnelClearHeight(), routeBridgeMinWaterDepth()));
                spans = retained;
            } catch (ProfileConflict.Rejected unusableTunnel) {
                LOGGER.debug("【水底方案不可行，继续求解已选跨越】 {}", unusableTunnel.conflict());
            }
        }
        spans = new ArrayList<>(spans);
        var heights = computeTargetY(level, segments, spans, cache, terrain, genConfig, streams);
        spans = PathSpanExtractor.refineTunnelSpans(spans, segments, heights, level, cache);
        // 桥头延伸与最终干谷分类都在恢复边界内，后期冲突也有机会寻找同端点旁路。
        return RoadProfileRecovery.finish(new RoadProfileRecovery.Profile(segments, spans, heights),
                current -> FillBridgeExtender.extendExistingStreamsBridgeApproaches(current, segments, streams),
                current -> {
                    var settled = settleProfile(segments, current, level, cache, terrain, genConfig, streams);
                    return new RoadProfileRecovery.Profile(segments, settled.spans(), settled.targetY());
                },
                current -> FillBridgeExtender.refineFinalFillBridgeSpans(
                        current.spans(), segments, current.heights(), level, cache));
    }

    /**
     * 「按当前 spans 结算剖面 + 隧道区间」的<b>唯一入口</b>。
     *
     * <h2>为什么收成一个</h2>
     * <p>这段三连（复制一份可变列表 → 算剖面 → 按剖面细化隧道区间）出现过两次，
     * 每次参数一模一样。两份拷贝意味着将来接 {@code RoadSpec} 要改两处、
     * 漏一处就出现「有的分支读了旧剖面」这类静默错。</p>
     *
     * <p><b>注意</b>：收敛的是<b>代码形状</b>，不是计算次数 —— 这两个阶段各自的输入
     * （{@code spans}）确实不同，所以仍然要各算一次。真正的「只算一次」要靠
     * S4 把剖面与跨度并进同一张 DP（一次求解同时产出两者），不是在这里省。</p>
     */
    private SettledProfile settleProfile(List<RoadSegmentPlacement> segments, List<RoadSpan> spans,
                                         ServerLevel level, TerrainSamplingCache cache,
                                         PathTerrainField terrain, RoadGenerationConfig genConfig,
                                         StreamsReflowingCompat.SamplingSession streams) {
        List<RoadSpan> mutable = new ArrayList<>(spans);
        List<Integer> profile = computeTargetY(level, segments, mutable, cache, terrain, genConfig, streams);
        List<RoadSpan> refined = PathSpanExtractor.refineTunnelSpans(mutable, segments, profile, level, cache);
        return new SettledProfile(refined, profile);
    }

    List<Integer> computeTargetY(ServerLevel level, List<RoadSegmentPlacement> segments,
                                 List<RoadSpan> spans, TerrainSamplingCache cache,
                                 PathTerrainField terrain,
                                 RoadGenerationConfig cfg,
                                 StreamsReflowingCompat.SamplingSession streams) {
        return TargetYComputation.computeTargetY(level, segments, spans, cache, terrain, cfg, streams,
                connection, routeBridgeMinWaterDepth(),
                DetailedRoadGenerationStats::recordProfileWindow, DetailedRoadGenerationStats::recordProfileDp,
                // 物理选址仍经原入口，剖面内核只消费逐站隧道权限，避免反向依赖采样与几何实现。
                preview -> RoadSpanProfileOps.excavationMask(
                        segments.stream().map(RoadSegmentPlacement::middlePos).toList(),
                        PathSpanExtractor.refineTunnelSpans(spans, segments, preview, level, cache)));
    }


    /** 每条路的AUTO偏桥倾向只放宽该连接水深阈值，不回写全局模式。
 *
     * Bridge depth for this route: a route-level AUTO bridge-first tendency
     * (resolved in {@code RawPathSearch} from the water share) relaxes the
     * threshold to one block for this connection only; explicit profile modes
     * keep the global path. Span extraction still applies the global mode on
     * top, and the two reductions compose correctly.
     */
    int routeBridgeMinWaterDepth() {
        return RouteProfileTendency.effectiveMode(connection)
                == PlanningConfig.TerrainProfileMode.BRIDGE_FIRST
                ? 1
                : genConfig.bridgeMinWaterDepth();
    }

    /** 真实结构与出生点入口共享地表锁高；未知端点另行协商，索引优化须覆盖快照全部变更入口。 */
    record SpanRange(int start, int end, SpanType type) {
    }


    /** 重绑缓存使用局部修复将查询的精度键，避免下一波因键不同丢失已有走廊。
 *
     * 失败时按局部档位保存已有走廊，后续窗口修复能找到它；按首轮档位保存会查不到。
     */
    int localRetryStep() {
        return genConfig.pathfinding().localRepairGridStep();
    }


    /**
     * S1：道路数据的完整性自检 —— 每条路完成时跑一次。
     *
     * <h2>为什么值得接这一下</h2>
     * <p>{@link RoadSpec#from} 做的事是「把三种事实（站序列 / 剖面 / 区间）对齐并校验」。
     * 它对齐区间端点用的是：<b>BlockPos 精确匹配 → 同 XZ 且全序列唯一 → 否则失败</b>。</p>
     *
     * <p>这恰好是 0.9.036 修过的那个「桥被静默丢掉」缺陷的<b>同族解法</b>：
     * 当时 {@code BridgeShareService.collectBridges} 用 {@code BlockPos.equals} 去站序列里
     * 反查区间端点，一旦路网合并重建了站序列（Y 漂一格）就整座桥静默消失，
     * 表现为 {@code bridgeShareCandidates=0}。修法是加了「同 XZ 唯一才认、歧义就放弃」
     * 的收紧兜底。{@code RoadSpec} 把那套判定固化成了不变量。</p>
     *
     * <p>所以这条自检的真正价值不是「多一个日志」，而是：<b>如果某条路的区间端点在
     * 站序列里对不上，这里会立刻说出来</b>，而不是等到施工时发现桥没了再回头查。</p>
     *
     * <h2>成本与安全</h2>
     * <p>复杂度 O(区间数 × 站数)，典型值 5 × 2000 = 一万次坐标比较，微秒级 ——
     * 相对整条路的规划（秒级）可忽略。全程 {@code try/catch}：
     * <b>自检绝不能影响一条路能不能生出来</b>。失败只 warn 且限流（前 3 次 + 每 50 次一次），
     * 避免一片路全对不上时刷屏。</p>
     */
    private static void logSpecIntegrity(RoadData rd, BlockPos from, BlockPos to) {
        try {
            RoadSpec spec = RoadSpec.from(rd);
            LOGGER.info("【道路规格自检通过】 " + "RoadWeaver road spec OK connection={}->{} stations={} sections={} uncovered={}",
                    from, to, spec.stationCount(), spec.sections().size(), spec.uncoveredStations());
        } catch (RoadSpec.SpecViolationException e) {
            int seen = SPEC_VIOLATIONS.incrementAndGet();
            if (seen <= 3 || seen % 50 == 0) {
                LOGGER.warn("RoadWeaver road spec VIOLATION connection={}->{} (累计 {} 条): {}",
                        from, to, seen, e.getMessage());
            }
        } catch (Throwable t) {
            // 自检本身出问题也必须吞掉 —— 它只是诊断
            LOGGER.warn("【道路规格自检失败】 " + "RoadWeaver road spec self-check failed connection={}->{}: {}",
                    from, to, t.toString());
        }
    }
}
