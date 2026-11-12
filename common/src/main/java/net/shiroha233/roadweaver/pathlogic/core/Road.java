/* 文件职责：生成单条道路并协调精确地形路径、跨度与附属结构。 */
package net.shiroha233.roadweaver.pathlogic.core;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
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
                pathResult = RoadPathCalculator.calculateFromPlannedPath(
                        planned.get(), width, level, cache, genConfig, connection);
            } else {
                pathResult = RoadPathCalculator.calculateAStarRoadPathDetailed(
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
            List<RoadSpan> spans = PathSpanExtractor.extractSpans(
                    segments, level, cache, terrain, streams, routeBridgeMinWaterDepth());
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
                spans = new ArrayList<>(spans);
                targetY = computeTargetY(level, segments, spans, cache, terrain, genConfig, streams);
                spans = PathSpanExtractor.refineTunnelSpans(spans, segments, targetY, level, cache);
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
                List<RoadSpan> refreshedSpans = PathSpanExtractor.extractSpans(
                        segments, level, cache, terrain, streams, routeBridgeMinWaterDepth());
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
                spans = new ArrayList<>(spans);
                targetY = computeTargetY(level, segments, spans, cache, terrain, genConfig, streams);
                spans = PathSpanExtractor.refineTunnelSpans(spans, segments, targetY, level, cache);
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
        List<RoadSegmentPlacement> rebuilt = RoadPathCalculator.repairRiverRouteSegments(
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
    
    List<Integer> computeTargetY(ServerLevel level, List<RoadSegmentPlacement> segments,
                                                   List<RoadSpan> spans, TerrainSamplingCache cache,
                                                   PathTerrainField terrain,
                                                   RoadGenerationConfig cfg,
                                                   StreamsReflowingCompat.SamplingSession streams) {
        int n = segments.size();
        throwIfInterrupted();
        List<BlockPos> centers = new ArrayList<>(n);
        for (RoadSegmentPlacement s : segments) {
            throwIfInterrupted();
            centers.add(s.middlePos());
        }

        boolean[] isCrossing = new boolean[n];
        List<SpanRange> crossingRanges = new ArrayList<>();
        if (spans != null && !spans.isEmpty()) {
            Map<Long, Integer> indexMap = new HashMap<>();
            for (int i = 0; i < centers.size(); i++) {
                throwIfInterrupted();
                indexMap.put(centers.get(i).asLong(), i);
            }
            for (RoadSpan sp : spans) {
                throwIfInterrupted();
                if (sp.type() != SpanType.BRIDGE && sp.type() != SpanType.ROAD_CROSSING) continue;
                Integer si = indexMap.get(sp.start().asLong());
                Integer ei = indexMap.get(sp.end().asLong());
                if (si == null || ei == null) continue;
                int a = Math.max(0, Math.min(si, ei));
                int b = Math.min(n - 1, Math.max(si, ei));
                for (int k = a; k <= b; k++) isCrossing[k] = true;
                crossingRanges.add(new SpanRange(a, b, sp.type()));
            }
        }

        int avg = Math.max(0, cfg.averagingRadius());
        int[] terrainY = new int[n];
        int[] footprintSupport = new int[n];
        int[] base = new int[n];
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        List<BlockPos> footprintPositions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            throwIfInterrupted();
            if (segment != null && segment.positions() != null) footprintPositions.addAll(segment.positions());
        }
        // Structure connections keep endpoint Y flattened to zero. Include
        // the actual center columns in the same accurate sampling batch so
        // the grade planner can anchor the profile to terrain at both ends.
        footprintPositions.addAll(centers);
        throwIfInterrupted();
        Map<Long, AccurateHeightSample> footprintSamples = accurate.samplePositions(footprintPositions);
        throwIfInterrupted();
        for (int i = 0; i < n; i++) {
            throwIfInterrupted();
            BlockPos center = centers.get(i);
            AccurateHeightSample centerSample = center == null ? null
                    : footprintSamples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
            RoadSegmentPlacement segment = segments.get(i);
            List<BlockPos> positions = segment == null ? List.of() : segment.positions();
            // The world read is this station's last-resort fallback, only reached when the accurate backend
            // missed the centre sample or the segment carries no positions at all. It used to run for every
            // station unconditionally, which put a live block read (up to 129 getBlockState calls, see
            // RoadConstructionSafety.terrainHeight) inside the planning loop - while the gate parks the
            // server thread without pumping the chunk queue, that read can wait on a chunk nobody generates.
            // Guarding it changes no value: both consumers below are already conditional on exactly these
            // two cases.
            boolean needsWorldTerrain = centerSample == null || positions == null || positions.isEmpty();
            int worldCenterTerrain = !needsWorldTerrain ? 0
                    : center == null ? level.getMinBuildHeight()
                    : RoadConstructionSafety.terrainHeight(level, center.getX(), center.getZ());
            int[] stationTerrain = new int[positions == null ? 0 : positions.size()];
            for (int j = 0; j < stationTerrain.length; j++) {
                throwIfInterrupted();
                BlockPos position = positions.get(j);
                AccurateHeightSample sample = footprintSamples.get(
                        AccurateHeightSample.key(position.getX(), position.getZ()));
                stationTerrain[j] = sample == null
                        ? (centerSample == null ? worldCenterTerrain : accurate.surfaceHeight(centerSample))
                        : accurate.surfaceHeight(sample);
            }
            // Never use the flattened connection Y as a terrain fallback. A
            // width-support sample is safer than treating the terrain as Y=0
            // when a backend temporarily misses one center sample.
            int widthSupport = stationTerrain.length == 0
                    ? worldCenterTerrain
                    : RoadGradePlanner.robustFootprintSupport(stationTerrain);
            terrainY[i] = centerSample == null
                    ? widthSupport
                    : accurate.surfaceHeight(centerSample);
            footprintSupport[i] = stationTerrain.length == 0
                    ? terrainY[i] : RoadGradePlanner.robustFootprintSupport(stationTerrain);
        }
        for (int i = 0; i < n; i++) {
            throwIfInterrupted();
            base[i] = RoadGradePlanner.robustWindowHeight(terrainY, i, avg);
        }

        int[] smoothed = RoadGradePlanner.stabilizeSurfaceProfile(
                base, terrainY, footprintSupport, isCrossing,
                cfg.slopeLimitEnabled(), cfg.maxSlopeStepPerTwoSegments(),
                RouteProfileTendency.effectiveMode(connection));
        // Lock the real structure boundary grades before tunnel selection.
        // Otherwise tunnel anchors are computed against the placeholder/raw
        // endpoint terrain and the later structure-grade rewrite can split the
        // mouth from the tunnel, producing an open ravine or a disconnected
        // entrance.
        boolean structureGradesLocked = false;
        boolean startVillage = false;
        boolean endVillage = false;
        boolean startUnknown = false;
        boolean endUnknown = false;
        int[] startSharedProfile = null;
        int[] endSharedProfile = null;
        int startBoundary = terrainY.length == 0 ? 0 : terrainY[0];
        int endBoundary = terrainY.length == 0 ? 0 : terrainY[terrainY.length - 1];
        if (connection != null && smoothed.length >= 2) {
            // A village, the shared spawn, or a named structure node must meet
            // every incoming road at one surface height. Unknown structure
            // nodes agree on one height by construction cost instead (the
            // registry decides between the structure surface and the height
            // the approaching road can actually reach). Ordinary road-only
            // junctions keep the smooth slope. The lock height is sampled at
            // the route's own crossing station — the boundary point where the
            // (possibly trimmed) road actually meets the structure — because
            // sampling the structure center column reads the building's
            // motion-blocking top (a rooftop) instead of the ground the road
            // joins.
            BlockPos startCross = boundaryCrossingAnchor(centers, connection.from(), true);
            BlockPos endCross = boundaryCrossingAnchor(centers, connection.to(), false);
            startUnknown = isUnknownStructureEndpoint(level, connection, true);
            endUnknown = isUnknownStructureEndpoint(level, connection, false);
            startVillage = isSharedElevationEndpoint(level, connection, true);
            endVillage = isSharedElevationEndpoint(level, connection, false);
            if (startUnknown) {
                startSharedProfile = UnknownEndpointHeightRegistry.lockedProfile(
                        PlanningUtils.pos2dKey(connection.from()));
                if (startSharedProfile != null && startSharedProfile.length > 0) {
                    startBoundary = startSharedProfile[0];
                    applySharedApproachProfile(smoothed, 0, startSharedProfile);
                } else {
                    startBoundary = UnknownEndpointHeightRegistry.lockedHeight(
                            PlanningUtils.pos2dKey(connection.from()),
                            crossingPreciseHeight(accurate, startCross, terrainY[0]),
                            terrainY, smoothed, 0,
                            RoadGradePlanner.SurfaceGradeLimits.capture());
                    applyPreferCutBoundaryGrade(smoothed, 0, startBoundary);
                }
            } else if (startVillage) {
                startBoundary = crossingPreciseHeight(accurate, startCross, terrainY[0]);
                applySingleBoundaryGrade(smoothed, 0, startBoundary);
            }
            if (endUnknown) {
                endSharedProfile = UnknownEndpointHeightRegistry.lockedProfile(
                        PlanningUtils.pos2dKey(connection.to()));
                if (endSharedProfile != null && endSharedProfile.length > 0) {
                    endBoundary = endSharedProfile[0];
                    applySharedApproachProfile(smoothed, smoothed.length - 1, endSharedProfile);
                } else {
                    endBoundary = UnknownEndpointHeightRegistry.lockedHeight(
                            PlanningUtils.pos2dKey(connection.to()),
                            crossingPreciseHeight(accurate, endCross, terrainY[terrainY.length - 1]),
                            terrainY, smoothed, smoothed.length - 1,
                            RoadGradePlanner.SurfaceGradeLimits.capture());
                    applyPreferCutBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
                }
            } else if (endVillage) {
                endBoundary = crossingPreciseHeight(accurate, endCross, terrainY[terrainY.length - 1]);
                applySingleBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
            }
            structureGradesLocked = startVillage || endVillage || startUnknown || endUnknown;
        }
        // 0.8.369 (T2): the structure boundary lock is a hard contract — a
        // village/spawn/named-structure entrance must sit on its real sampled
        // surface. Every later pass only ever lowers a station, so the lock was
        // being flattened back down to meet the tunnel chord: a real route
        // locked targetY[0]=407 and shipped 389, while its other endpoint (whose
        // span did not cover it) matched exactly. Of 23 connections, 8 ended up
        // 2..25 blocks below their locked surface. Mark the locked stations so
        // every slope/tunnel pass treats them as immovable; those passes still
        // read the pinned value as the fixed end of their constraint, so the
        // approach keeps its legal one-block ramp and no sky road appears.
        boolean[] pinnedMask = new boolean[n];
        if (structureGradesLocked) {
            if (startVillage || startUnknown || startSharedProfile != null) pinnedMask[0] = true;
            if (endVillage || endUnknown || endSharedProfile != null) pinnedMask[n - 1] = true;
        }
        int[] tunnelProfile;
        if (crossingRanges.isEmpty()) {
            tunnelProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                    smoothed, terrainY, isCrossing,
                    cfg.slopeLimitEnabled(), cfg.maxSlopeStepPerTwoSegments(), pinnedMask);
        } else {
            // A hillside next to a bridge/road-crossing span must stay a
            // surface climb: pressing it into a tunnel mixes "bridge first,
            // then tunnel" into alternating crossings and tunnels that braid
            // around the river. The guard only protects the tunnel decision;
            // the ordinary grade constraints keep using the strict mask.
            final int riversideTunnelGuard = Math.max(2, cfg.bridgeRampSegments() + 4);
            boolean[] tunnelGuard = isCrossing.clone();
            for (SpanRange range : crossingRanges) {
                int from = Math.max(0, range.start() - riversideTunnelGuard);
                int to = Math.min(n - 1, range.end() + riversideTunnelGuard);
                for (int k = from; k <= to; k++) tunnelGuard[k] = true;
            }
            tunnelProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                    smoothed, terrainY, tunnelGuard,
                    cfg.slopeLimitEnabled(), cfg.maxSlopeStepPerTwoSegments(), pinnedMask);
        }
        if (!Arrays.equals(smoothed, tunnelProfile)) {
            int changed = 0;
            for (int i = 0; i < smoothed.length; i++) {
                if (smoothed[i] != tunnelProfile[i]) changed++;
            }
            LOGGER.info("RoadWeaver proactive tunnel planning selected mountain profile: changedStations={} totalStations={}",
                    changed, smoothed.length);
            smoothed = tunnelProfile;
        }
        if (structureGradesLocked) {
            if (startVillage || startUnknown) {
                if (startSharedProfile != null && startSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, 0, startSharedProfile);
                } else if (startUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, 0, startBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, 0, startBoundary);
                }
            }
            if (endVillage || endUnknown) {
                if (endSharedProfile != null && endSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, smoothed.length - 1, endSharedProfile);
                } else if (endUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
                }
            }
            BlockPos startCross = connection.from();
            BlockPos endCross = connection.to();
            LOGGER.info("RoadWeaver structure boundary grades locked connection={}->{} startY={} endY={} "
                            + "terrainStartY={} terrainEndY={} startCross=({},{}) endCross=({},{}) stations={} "
                            + "startLocked={} endLocked={} startUnknown={} endUnknown={}",
                    connection.from(), connection.to(), startBoundary, endBoundary,
                    terrainY[0], terrainY[terrainY.length - 1],
                    startCross == null ? "?" : startCross.getX(), startCross == null ? "?" : startCross.getZ(),
                    endCross == null ? "?" : endCross.getX(), endCross == null ? "?" : endCross.getZ(),
                    smoothed.length,
                    startVillage, endVillage, startUnknown, endUnknown);
        }
        // 0.8.368 (T2 diagnosis): the boundary log above prints startBoundary /
        // endBoundary — the values the lock *asked* for — not what the profile
        // actually holds. A route whose endpoint sits inside a TUNNEL span came
        // out with targetY[0]=389 against a locked 407 (terrain 407), while the
        // other endpoint landed exactly on its lock. The suspected cause is that
        // "lower, never raise" passes overwrite the lock. These lines print the
        // real profile ends at three points so one run shows whether the lock
        // survives, is dropped here, or is lost later in
        // repairFinalHeightConflict. Diagnosis only; no height is changed.
        logEndpointProfile("after-structure-lock", connection, smoothed, terrainY, n, startBoundary, endBoundary);
        applyCrossingProfiles(level, cache, terrain, segments, centers, smoothed, crossingRanges, cfg,
                streams, routeBridgeMinWaterDepth());
        List<RoadSpan> underseaSpans = UnderseaTunnelPlanner.convertLongOceanBridgesToUnderseaTunnels(
                spans, segments, level, cache, footprintSamples);
        if (underseaSpans != spans && spans != null) {
            try {
                spans.clear();
                spans.addAll(underseaSpans);
            } catch (UnsupportedOperationException ignored) {
                // Provisional immutable span lists contain no bridge ownership;
                // the normal generation path supplies a mutable list.
            }
        }
        smoothed = UnderseaTunnelPlanner.applyUnderseaTunnelProfile(
                toIntegerList(smoothed), spans, segments, level, cache, footprintSamples,
                cfg.tunnelClearHeight())
                .stream().mapToInt(Integer::intValue).toArray();

        // Tunnel chords, structure boundary locks and crossing profiles can
        // each leave a one-block discontinuity behind. Run the final
        // one-block-per-station closure here so late tunnel reclassification
        // and validation always see a walkable grade. Bridges/crossings keep
        // their own deck profile; ordinary and tunnel stations are only
        // lowered, never raised into a sky road.
        smoothed = SlopeContinuityEnforcer.enforceContinuousSlopePreferCut(
                smoothed, isCrossing, cfg.maxSlopeStepPerTwoSegments(), pinnedMask,
                SlopeContinuityEnforcer.configuredStationSpacing(centers));

        smoothed = closeTunnelWaterBoundary(smoothed, centers, spans);

        // Task: remove isolated one-block spikes inside tunnel spans only - a
        // station standing one block above both neighbours of the same tunnel
        // run is seated on them. Normal tunnel grades and ordinary one-block
        // steps are untouched; bridges/crossings keep their own deck profile.
        boolean[] tunnelMask = new boolean[n];
        if (spans != null) {
            Map<Long, Integer> spanStationIndex = new HashMap<>();
            for (int i = 0; i < n; i++) {
                BlockPos centre = centers.get(i);
                if (centre != null) spanStationIndex.putIfAbsent(centre.asLong(), i);
            }
            for (RoadSpan sp : spans) {
                if (sp == null || sp.start() == null || sp.end() == null) continue;
                if (sp.type() != SpanType.TUNNEL && sp.type() != SpanType.TUNNEL_JUNCTION) continue;
                Integer start = spanStationIndex.get(sp.start().asLong());
                Integer end = spanStationIndex.get(sp.end().asLong());
                if (start == null || end == null) continue;
                for (int k = Math.min(start, end); k <= Math.max(start, end); k++) tunnelMask[k] = true;
            }
        }
        smoothed = RoadGradePlanner.removeSingleStationSpikes(smoothed, tunnelMask);

        // 0.8.369 (T2): re-seat the locked boundary after EVERY pass that can
        // move a station — this must run after removeSingleStationSpikes too,
        // and before the profile is copied into the returned list below.
        // Keeping the pinned station immovable is only half the contract: the
        // slope closure sweeps from both ends and would otherwise leave
        // neighbour 1 sitting near the tunnel chord while the pinned station
        // stays on the village surface — an 18-block cliff at the entrance
        // (measured prof-lock = -19 on a real save). Seating the ramp earlier
        // would simply be undone by the passes in between.
        //
        // Re-running the boundary grade rebuilds the legal one-block ramp from
        // the lock inward using the same code path the original lock uses. The
        // ramp is anchored at the real sampled surface and descends from it, so
        // interior stations only move toward the lock height, never above it.
        if (structureGradesLocked) {
            if (pinnedMask[0]) {
                if (startSharedProfile != null && startSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, 0, startSharedProfile);
                } else if (startUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, 0, startBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, 0, startBoundary);
                }
            }
            if (pinnedMask[n - 1]) {
                if (endSharedProfile != null && endSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, n - 1, endSharedProfile);
                } else if (endUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, n - 1, endBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, n - 1, endBoundary);
                }
            }
        }

        List<Integer> out = new ArrayList<>(n);
        for (int v : smoothed) out.add(v);
        // Task 16 diagnostic: flag remaining >1 station height steps and
        // height discontinuities across water-span boundaries. These are the
        // exact signatures behind the "bank suddenly one block higher" and
        // braided-crossing reports; a clean profile stays silent.
        int stepCount = 0;
        for (int i = 1; i < n; i++) {
            int delta = Math.abs(smoothed[i] - smoothed[i - 1]);
            if (delta > 1 && !(isCrossing[i] && isCrossing[i - 1])) {
                if (stepCount < 8) {
                    BlockPos a = centers.get(i - 1);
                    BlockPos b = centers.get(i);
                    LOGGER.info("RoadWeaver profile height step idx={} delta={} pos=({},{})y{}->({},{})y{} inWaterSpan={}",
                            i, delta,
                            a.getX(), a.getZ(), smoothed[i - 1],
                            b.getX(), b.getZ(), smoothed[i],
                            isCrossing[i - 1] && isCrossing[i]);
                }
                stepCount++;
            }
        }
        if (stepCount > 0) {
            LOGGER.info("RoadWeaver profile height steps total={} stations={} connection={}->{}",
                    stepCount, n,
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to());
        }
        for (SpanRange range : crossingRanges) {
            int before = Math.max(0, range.start() - 1);
            int after = Math.min(n - 1, range.end() + 1);
            int yBefore = smoothed[before];
            int yStart = smoothed[range.start()];
            int yEnd = smoothed[range.end()];
            int yAfter = smoothed[after];
            if (Math.abs(yStart - yBefore) > 1 || Math.abs(yAfter - yEnd) > 1) {
                LOGGER.info("RoadWeaver water-span boundary height jump range={}..{} "
                                + "bankY=({}|{}) spanY=({}|{}) terrainY=({}|{}) connection={}->{}",
                        range.start(), range.end(), yBefore, yAfter, yStart, yEnd,
                        terrainY[before] + "|" + terrainY[after],
                        terrainY[range.start()] + "|" + terrainY[range.end()],
                        connection == null ? "?" : connection.from(),
                        connection == null ? "?" : connection.to());
            }
        }
        logEndpointProfile("computeTargetY-exit", connection, smoothed, terrainY, n, startBoundary, endBoundary);
        return out;
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
