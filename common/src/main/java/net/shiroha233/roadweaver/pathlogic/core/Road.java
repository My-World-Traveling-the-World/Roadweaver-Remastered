/* 文件职责：生成单条道路并协调精确地形路径、跨度与附属结构。 */
package net.shiroha233.roadweaver.pathlogic.core;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathMetricsOps;
import net.shiroha233.roadweaver.pathlogic.pathcalc.PathFinalizeOps;
import net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer;
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
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator.PathCalculationResult;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlanner;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicy;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.ConnectionDeletionService;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CancellationException;
import net.shiroha233.roadweaver.pathlogic.bridge.CulvertPlanner;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.metrics.RoadQualityMetrics;
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
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** T-21: 短浅滩(ROAD_CROSSING)甲板在水位之上的可涉水余量。 */
    static final int FORD_DECK_CLEARANCE = 2;
    final ServerLevel level;
    final StructureConnection connection;
    final PathFeatureConfig featureConfig;
    final RoadGenerationConfig genConfig;

    public Road(ServerLevel level, StructureConnection connection, 
                PathFeatureConfig featureConfig, RoadGenerationConfig genConfig) {
        this.level = level;
        this.connection = connection;
        this.featureConfig = featureConfig;
        this.genConfig = genConfig;
    }
    
    public RoadData generateRoad(int maxSteps) {
        return generateRoad(maxSteps, true);
    }

    public RoadData generateRoad(int maxSteps, boolean persistImmediately) {
        return generateRoad(maxSteps, persistImmediately, 0);
    }

    RoadData generateRoad(int maxSteps, boolean persistImmediately, int finalStructureAttempt) {
        if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
        long roadStartedAt = System.nanoTime();
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
        Optional<List<BlockPos>> planned = PlannedPathCache.find(
                level,
                connection,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                genConfig.pathfinding());
        DetailedRoadGenerationStats.recordPlannedPathLookup(System.nanoTime() - plannedLookupStartedAt);
        try {
            PathCalculationResult pathResult;
            if (planned.isPresent()) {
                pathResult = PathFinalizeOps.calculateFromPlannedPath(
                        planned.get(), width, level, cache, genConfig, connection);
            } else {
                pathResult = PathMetricsOps.calculateAStarRoadPathDetailed(
                        rawStart, rawEnd, width, level, maxSteps, cache, genConfig);
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
            long spansStartedAt = System.nanoTime();
            List<RoadSpan> spans = extractPhysicalSpans(segments, level, cache, terrain, streams);
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            long spanExtractionNanos = System.nanoTime() - spansStartedAt;
            DetailedRoadGenerationStats.recordSpanExtraction(spanExtractionNanos);
            DetailedRoadGenerationStats.RouteTiming spanTiming = DetailedRoadGenerationStats.routeTiming();
            if (spanTiming != null) spanTiming.addSpanExtraction(spanExtractionNanos);
            LOGGER.info("RoadWeaver route stage=span_extraction connection={}->{} spans={} {}",
                    rawStart, rawEnd, spans.size(), summarizeSpans(spans));
            long targetHeightStartedAt = System.nanoTime();
            if (containsPhysicalCrossing(spans)) {
                List<Integer> provisionalTargetY = computeTargetY(
                        level, segments, List.of(), cache, terrain, genConfig, streams);
                List<RoadSpan> provisionalTunnels = PathSpanExtractor.refineTunnelSpans(
                        List.of(), segments, provisionalTargetY, level, cache);
                spans = SpanConflictResolver.retainVerticallyRelevantCrossings(
                        spans, provisionalTunnels, segments, provisionalTargetY,
                        level, cache, terrain, streams, genConfig.tunnelClearHeight(),
                        routeBridgeMinWaterDepth());
            }
            spans = new ArrayList<>(spans);
            List<Integer> targetY = computeTargetY(level, segments, spans, cache, terrain, genConfig, streams);
            InitialGenerationProgressTracker.advanceCurrentRoadTask(680, "road_target_height_ready");
            if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
            spans = PathSpanExtractor.refineTunnelSpans(spans, segments, targetY, level, cache);
            // A bridge profile can expose a low bank only after its first
            // height pass.  Settle the bounded bridge/fill/tunnel relationship
            // before persisting RoadData so chunk generation never has to skip
            // an unsafe bridge approach column.
            for (int bridgeRefinement = 0; bridgeRefinement < 3; bridgeRefinement++) {
                if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
                List<RoadSpan> refinedSpans = FillBridgeExtender.refineFinalFillBridgeSpans(
                        spans, segments, targetY, level, cache);
                refinedSpans = FillBridgeExtender.extendExistingStreamsBridgeApproaches(
                        refinedSpans, segments, streams);
                if (refinedSpans.equals(spans)) break;
                spans = refinedSpans;
                SettledProfile settled = settleProfile(segments, spans, level, cache, terrain,
                        genConfig, streams);
                spans = settled.spans();
                targetY = settled.targetY();
            }
            List<RoadSpan> protectedGaps = protectedGapSpans(
                    level, connection, segments);
            if (!protectedGaps.isEmpty()) {
                ArrayList<RoadSpan> withGaps = new ArrayList<>(spans);
                withGaps.addAll(protectedGaps);
                spans = List.copyOf(withGaps);
                LOGGER.warn("RoadWeaver persisted protected building gaps connection={}->{} count={}",
                        connection.from(), connection.to(), protectedGaps.size());
            }
            List<RoadSpan> plannedReuse = PlannedPathCache.plannedReuseSpans(level, connection, segments);
            if (!plannedReuse.isEmpty()) {
                ArrayList<RoadSpan> withReuse = new ArrayList<>(spans);
                withReuse.addAll(plannedReuse);
                spans = List.copyOf(withReuse);
                LOGGER.info("RoadWeaver persisted planned NETWORK_REUSE spans connection={}->{} count={}",
                        connection.from(), connection.to(), plannedReuse.size());
            }
            DetailedRoadGenerationStats.recordTargetHeight(System.nanoTime() - targetHeightStartedAt);
            // Everything from here to the final validation used to be untimed: it is where the conflict
            // repair, the continuity recompute and the volume checks live, and in the measured session the
            // stage timers summed to 135 s of a 142 s wall with no line explaining the remaining stretch.
            long planningRepairStartedAt = System.nanoTime();
            RoadData rd = new RoadData(width, type, materials, slabMaterials, segments, spans, targetY, ownerA2d, ownerB2d);
            RoadData constructionRepaired = RoadPathCalculator.repairFinalHeightConflict(
                    rd, level, cache, connection);
            if (constructionRepaired == null) {
                LOGGER.warn("RoadWeaver rejected route before persistence because local construction continuity repair failed connection={}->{}",
                        rawStart, rawEnd);
                LOGGER.warn("RoadWeaver route filtered: reason=construction_continuity_repair_failed connection={}->{}",
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
                LOGGER.info("RoadWeaver accepted bounded construction-feasibility height repair connection={}->{}",
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
                    LOGGER.warn("RoadWeaver rejected merged route because post-junction construction continuity repair failed connection={}->{}",
                            rawStart, rawEnd);
                    LOGGER.warn("RoadWeaver route filtered: reason=post_junction_construction_repair_failed connection={}->{}",
                            rawStart, rawEnd);
                    return null;
                }
                if (mergedConstructionRepaired != rd) {
                    rd = mergedConstructionRepaired;
                    segments = rd.roadSegmentList();
                    spans = rd.spans();
                    targetY = rd.targetY();
                    LOGGER.info("RoadWeaver accepted post-junction construction-feasibility height repair connection={}->{}",
                            rawStart, rawEnd);
                }
            }
            RoadData consistencyInput = rd;
            rd = RoadPlanConsistencyService.reconcile(
                    level, rd, cache, terrain, streams, routeBridgeMinWaterDepth());
            if (rd == null) {
                LOGGER.warn("RoadWeaver rejected route because final plan consistency reconciliation failed connection={}->{}",
                        rawStart, rawEnd);
                LOGGER.warn("RoadWeaver route filtered: reason=plan_consistency_reconcile_failed connection={}->{}",
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
            RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(rd);
            if (!validation.valid()) {
                LOGGER.error("RoadWeaver rejected invalid finalized road {} -> {} reason={} index={} pos={} detail={}",
                        rawStart, rawEnd, validation.reason(), validation.index(),
                        validation.position(), validation.detail());
                LOGGER.warn("RoadWeaver route filtered: reason=invalid_finalized_road:{} connection={}->{}",
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
                            LOGGER.warn("RoadWeaver promoted Streams river collision to {} connection={}->{}",
                                    promotedType, rawStart, rawEnd);
                        }
                    }
                }
                if ("ordinary_construction_in_water".equals(waterValidation.reason())) {
                    RoadData rerouted = tryRiverRepair(rd, cache, terrain, streams);
                    if (rerouted != rd) {
                        rd = rerouted;
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
                                LOGGER.warn("RoadWeaver culverted narrow water connection={}->{} runs={} "
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
                        LOGGER.warn("RoadWeaver retained river protection gaps and preserved remaining route "
                                        + "connection={}->{} firstSegment={} pos={} source={}",
                                rawStart, rawEnd, waterCollision == null ? -1 : waterCollision.segmentIndex(),
                                waterCollision == null ? null : waterCollision.position(),
                                waterCollision == null ? null : waterCollision.source());
                    }
                }
            }
            if (!waterValidation.safe()) {
                LOGGER.error("RoadWeaver rejected finalized road in water {} -> {} reason={} "
                                + "segment={} pos={} targetY={} waterSurfaceY={} bedY={} span={} source={}",
                        rawStart, rawEnd, waterValidation.reason(),
                        waterCollision == null ? -1 : waterCollision.segmentIndex(),
                        waterCollision == null ? null : waterCollision.position(),
                        waterCollision == null ? null : waterCollision.targetY(),
                        waterCollision == null ? null : waterCollision.waterSurfaceY(),
                        waterCollision == null ? null : waterCollision.bedY(),
                        waterCollision == null ? null : waterCollision.activeSpan(),
                        waterCollision == null ? null : waterCollision.source());
                LOGGER.warn("RoadWeaver route filtered: reason=finalized_road_in_water:{} connection={}->{}",
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
            for (int structureAttempt = finalStructureAttempt; structureAttempt <= 3; structureAttempt++) {
                structureValidation = FinalRoadStructureValidator.validate(level, connection, rd, cache, streams);
                InitialGenerationProgressTracker.advanceCurrentRoadTask(940, "road_structure_validation");
                if (ConnectionDeletionService.shouldAbort(level, connection)) return null;
                if (structureValidation.safe()) break;
                RoadData gapped = FinalRoadStructureValidator.retainVerifiedBuildingGaps(
                        level, connection, rd, cache, streams);
                if (gapped != null) {
                    FinalRoadStructureValidator.ValidationResult gapStructureValidation =
                            FinalRoadStructureValidator.validate(level, connection, gapped, cache, streams);
                    if (gapStructureValidation.safe()) {
                        rd = gapped;
                        segments = rd.roadSegmentList();
                        spans = rd.spans();
                        targetY = rd.targetY();
                        structureValidation = gapStructureValidation;
                        LOGGER.warn("RoadWeaver retained all internal verified-building protection gaps after final volume collision "
                                        + "connection={}->{}", rawStart, rawEnd);
                        break;
                    }
                }
                FinalRoadStructureValidator.Collision collision = structureValidation.collision();
                LOGGER.error("RoadWeaver rejected finalized construction volume {} -> {} reason={} "
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
                    LOGGER.warn("RoadWeaver structure repair loop exiting: no further local attempt possible "
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
                    LOGGER.info("RoadWeaver structure conflict hint written attempt={} owner={} pos={} connection={}->{}",
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
                    LOGGER.warn("RoadWeaver structure repair loop exiting: centre profile unavailable "
                                    + "reason=missing_station_center_or_target attempt={} stations={} connection={}->{}",
                            structureAttempt + 1, segments.size(), rawStart, rawEnd);
                    break;
                }
                PathfindingCostConfig detourCfg = genConfig.pathfinding().snapshot();
                detourCfg.sanitize();
                List<BlockPos> repaired = StructureRepair.repairStructureIntersectionsAtExactClearance(
                        centreProfile, level, cache.getAccurateSampler(level), detourCfg, connection, 50);
                if (repaired == null || repaired.size() < 2 || repaired.equals(centreProfile)) {
                    LOGGER.warn("RoadWeaver structure repair loop exiting: local detour unavailable "
                                    + "reason={} attempt={} nodes={}->{} connection={}->{}",
                            repaired == null ? "null_repair" : repaired.size() < 2 ? "path_too_short" : "unchanged_path",
                            structureAttempt + 1, centreProfile.size(),
                            repaired == null ? -1 : repaired.size(), rawStart, rawEnd);
                    break;
                }
                RoadData rebuilt = RoadPathCalculator.rebuildRoadDataFromProfile(rd, repaired, level, cache, connection);
                if (rebuilt == null) {
                    LOGGER.warn("RoadWeaver structure repair loop exiting: rebuild from detour profile failed "
                                    + "attempt={} nodes={}->{} connection={}->{}",
                            structureAttempt + 1, centreProfile.size(), repaired.size(), rawStart, rawEnd);
                    break;
                }
                rebuilt = RoadPathCalculator.repairFinalHeightConflict(rebuilt, level, cache, connection);
                if (rebuilt == null) {
                    LOGGER.warn("RoadWeaver structure repair loop exiting: post-detour height repair failed "
                                    + "attempt={} connection={}->{}", structureAttempt + 1, rawStart, rawEnd);
                    break;
                }
                FinalRoadWaterValidator.ValidationResult rebuiltWater =
                        FinalRoadWaterValidator.validate(level, rebuilt, cache, streams);
                if (!rebuiltWater.safe()) {
                    FinalRoadWaterValidator.Collision rebuiltWaterCollision = rebuiltWater.collision();
                    if (rebuiltWaterCollision == null || rebuiltWaterCollision.segmentIndex() < 0) {
                        LOGGER.warn("RoadWeaver structure repair loop exiting: post-detour water check failed without collision "
                                        + "reason={} attempt={} connection={}->{}",
                                rebuiltWater.reason(), structureAttempt + 1, rawStart, rawEnd);
                        break;
                    }
                    RoadData gappedWater = FinalRoadWaterValidator.retainProtectedGapAt(
                            rebuilt, rebuiltWaterCollision.segmentIndex());
                    if (gappedWater == null
                            || !FinalRoadWaterValidator.validate(level, gappedWater, cache, streams).safe()) {
                        LOGGER.warn("RoadWeaver structure repair loop exiting: post-detour water gap retention failed "
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
                LOGGER.info("RoadWeaver accepted local structure detour after final volume collision "
                                + "attempt={} connection={}->{}",
                        structureAttempt + 1, rawStart, rawEnd);
            }
            long finalValidationNanos = System.nanoTime() - finalValidationStartedAt;
            DetailedRoadGenerationStats.recordFinalValidation(finalValidationNanos);
            DetailedRoadGenerationStats.RouteTiming validationTiming = DetailedRoadGenerationStats.routeTiming();
            if (validationTiming != null) validationTiming.addValidation(finalValidationNanos);
            if (structureValidation == null || !structureValidation.safe()) {
                FinalRoadStructureValidator.Collision finalCollision = structureValidation == null
                        ? null : structureValidation.collision();
                LOGGER.error("RoadWeaver route finally rejected after structure repair loop "
                                + "reason={} attemptExhausted=true segment={} pos={} owner={} box={} connection={}->{}",
                        structureValidation == null ? "validation_not_run" : structureValidation.reason(),
                        finalCollision == null ? -1 : finalCollision.segmentIndex(),
                        finalCollision == null ? null : finalCollision.position(),
                        finalCollision == null ? null : finalCollision.owner(),
                        finalCollision == null ? null : finalCollision.componentBox(),
                        rawStart, rawEnd);
                LOGGER.warn("RoadWeaver route filtered: reason=construction_volume_intersection:{} connection={}->{}",
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
                    LOGGER.error("RoadWeaver persisted road but skipped failed roadside content {} -> {}",
                            rawStart, rawEnd, roadsideFailure);
                }
            }
            completed = true;
            LOGGER.info("RoadWeaver route PLANNED_OK connection={}->{} stations={} spans={} {}",
                    rawStart, rawEnd, rd.roadSegmentList().size(), rd.spans().size(), summarizeSpans(rd.spans()));
            // D1: the quality numbers, once per finished road, plus a session rollup every ten roads,
            // so a real session yields the eleven measurements instead of only "it built".
            RoadQualityMetrics.record(rd, rawStart, rawEnd);
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
     * 物理区间提取的<b>唯一入口</b>。
     *
     * <p>原先有两处调用，参数完全相同（{@code segments, level, cache, terrain, streams,
     * routeBridgeMinWaterDepth()}）—— 收成一处，将来接 {@code RoadSpec} 时只改这里。</p>
     */
    private List<RoadSpan> extractPhysicalSpans(List<RoadSegmentPlacement> segments, ServerLevel level,
                                                TerrainSamplingCache cache, PathTerrainField terrain,
                                                StreamsReflowingCompat.SamplingSession streams) {
        return PathSpanExtractor.extractSpans(segments, level, cache, terrain, streams,
                routeBridgeMinWaterDepth());
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
                connection, routeBridgeMinWaterDepth());
    }

    /**
     * 0.8.368 (T2 diagnosis): prints the profile's real end heights against the
     * terrain and the boundary lock. {@code structure boundary grades locked}
     * reports the lock's intent, so a route can log {@code startY=407} while its
     * profile actually holds 389. Comparing the three call sites of this helper
     * shows whether the lock survives the profile passes, or is only lost later
     * inside {@code repairFinalHeightConflict}. Diagnosis only.
     */
    /**
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

    /**
     * True when a road endpoint is a structure-facing node that must meet every
     * incoming road at one surface height. Villages keep their existing
     * boundary lock; the shared spawn and named structure nodes gain the same
     * lock, while an ordinary road-only junction stays smooth. Unknown
     * (unnamed) structure nodes are excluded here — they negotiate their
     * shared height through {@link UnknownEndpointHeightRegistry} instead.
     *
     * <p>Performance note (audit P2-13, optional): this scans the structure
     * snapshot list linearly (~2 calls per route). An X/Z index is only safe
     * with invalidation hooks at every mutation site of the snapshot list;
     * revisit only if profiling shows this scan matters.
     */
    record SpanRange(int start, int end, SpanType type) {
    }

    /** Compact span breakdown for per-route pipeline diagnostics. */
    /**
     * Rebinding stores the route under the precision that the local-repair
     * wave later queries. Using the full-route step here leaves the rebound
     * cache under a different key, which made the following local pass report
     * "missing persisted route path".
     */
    int localRetryStep() {
        int configured = genConfig == null ? 4 : Math.max(1, genConfig.pathfinding().effectiveAStarStep());
        return RoutePrecisionRetryPolicy.localRepairBatchSteps(configured).get(0);
    }

}
