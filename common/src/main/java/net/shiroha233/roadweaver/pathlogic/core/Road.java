/* 文件职责：生成单条道路并协调精确地形路径、跨度与附属结构。 */
package net.shiroha233.roadweaver.pathlogic.core;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
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

/**
 * 道路生成器
 */
public final class Road {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** T-21: 短浅滩(ROAD_CROSSING)甲板在水位之上的可涉水余量。 */
    private static final int FORD_DECK_CLEARANCE = 2;
    private final ServerLevel level;
    private final StructureConnection connection;
    private final PathFeatureConfig featureConfig;
    private final RoadGenerationConfig genConfig;

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

    private RoadData generateRoad(int maxSteps, boolean persistImmediately, int finalStructureAttempt) {
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

    private static int getRandomWidth(RandomSource rnd, PathFeatureConfig cfg) {
        return 0;
    }

    private static RoadData forceCrossingAtWaterCollision(RoadData road, int collisionIndex) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().size() < 2
                || collisionIndex < 0 || collisionIndex >= road.roadSegmentList().size()) return road;
        int size = road.roadSegmentList().size();
        int start = Math.max(0, collisionIndex - 1);
        int end = Math.min(size - 1, collisionIndex + 1);
        if (end <= start) {
            start = Math.max(0, Math.min(collisionIndex, size - 2));
            end = start + 1;
        }
        List<RoadSpan> spans = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            int a = indexOfCenter(road.roadSegmentList(), span.start());
            int b = indexOfCenter(road.roadSegmentList(), span.end());
            int lo = a < 0 || b < 0 ? Integer.MAX_VALUE : Math.min(a, b);
            int hi = a < 0 || b < 0 ? Integer.MIN_VALUE : Math.max(a, b);
            if (span.type() == SpanType.TUNNEL || span.type() == SpanType.TUNNEL_JUNCTION
                    || span.type() == SpanType.NETWORK_REUSE || span.type() == SpanType.PROTECTED_GAP) {
                spans.add(span);
                continue;
            }
            if (span.type() == SpanType.ROAD_CROSSING && hi >= start && lo <= end) {
                continue;
            }
            spans.add(span);
        }
        RoadSegmentPlacement first = road.roadSegmentList().get(start);
        RoadSegmentPlacement last = road.roadSegmentList().get(end);
        if (first == null || last == null || first.middlePos() == null || last.middlePos() == null) return road;
        boolean exists = spans.stream().anyMatch(span -> span.type() == SpanType.BRIDGE
                && ((span.start().equals(first.middlePos()) && span.end().equals(last.middlePos()))
                || (span.start().equals(last.middlePos()) && span.end().equals(first.middlePos()))));
        SpanType crossingType = crossingTypeAtDistance(first.middlePos(), last.middlePos());
        if (!exists) spans.add(new RoadSpan(first.middlePos(), last.middlePos(), crossingType));
        RoadData candidate = new RoadData(road.width(), road.roadType(), road.materials(),
                road.slabMaterials(), road.roadSegmentList(), List.copyOf(spans), road.targetY(),
                road.ownerA2dKey(), road.ownerB2dKey());
        return RoadDataValidator.validateFinalRoad(candidate).valid() ? candidate : road;
    }

    private static SpanType crossingTypeAtCollision(RoadData road, int collisionIndex) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().size() < 2) {
            return SpanType.ROAD_CROSSING;
        }
        int size = road.roadSegmentList().size();
        int start = Math.max(0, Math.min(collisionIndex - 1, size - 2));
        int end = Math.min(size - 1, start + 2);
        RoadSegmentPlacement a = road.roadSegmentList().get(start);
        RoadSegmentPlacement b = road.roadSegmentList().get(end);
        return a == null || b == null ? SpanType.ROAD_CROSSING
                : crossingTypeAtDistance(a.middlePos(), b.middlePos());
    }

    private static SpanType crossingTypeAtDistance(BlockPos start, BlockPos end) {
        if (start == null || end == null) return SpanType.ROAD_CROSSING;
        long dx = (long) end.getX() - start.getX();
        long dz = (long) end.getZ() - start.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        return distance <= 12.0D ? SpanType.ROAD_CROSSING : SpanType.BRIDGE;
    }

    private static int indexOfCenter(List<RoadSegmentPlacement> segments, BlockPos point) {
        if (segments == null || point == null) return -1;
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && point.equals(segment.middlePos())) return i;
        }
        return -1;
    }

    static long deterministicRoadSeed(long worldSeed, long ownerA2d, long ownerB2d) {
        return worldSeed ^ PlanningUtils.edgeKey(ownerA2d, ownerB2d) ^ 0x524F41445F534545L;
    }

    private static boolean containsPhysicalCrossing(List<RoadSpan> spans) {
        if (spans == null) return false;
        for (RoadSpan span : spans) {
            if (span != null && (span.type() == SpanType.BRIDGE
                    || span.type() == SpanType.ROAD_CROSSING)) return true;
        }
        return false;
    }

    private RoadData tryRiverRepair(RoadData road,
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
    
    private List<Integer> computeTargetY(ServerLevel level, List<RoadSegmentPlacement> segments,
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
        smoothed = RoadGradePlanner.enforceContinuousSlopePreferCut(
                smoothed, isCrossing, cfg.maxSlopeStepPerTwoSegments(), pinnedMask,
                RoadGradePlanner.configuredStationSpacing(centers));

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
    private static void logEndpointProfile(String stage,
                                           StructureConnection connection,
                                           int[] profile,
                                           int[] terrain,
                                           int stations,
                                           int startBoundary,
                                           int endBoundary) {
        if (profile == null || profile.length == 0 || !LOGGER.isInfoEnabled()) return;
        int last = profile.length - 1;
        int startTerrain = terrain != null && terrain.length > 0 ? terrain[0] : -1;
        int endTerrain = terrain != null && terrain.length > 0 ? terrain[terrain.length - 1] : -1;
        LOGGER.info("RoadWeaver endpoint profile stage={} connection={}->{} stations={} "
                        + "profileStart={} terrainStart={} lockedStart={} startDelta={} "
                        + "profileEnd={} terrainEnd={} lockedEnd={} endDelta={}",
                stage,
                connection == null ? "?" : connection.from(),
                connection == null ? "?" : connection.to(),
                stations,
                profile[0], startTerrain, startBoundary, profile[0] - startTerrain,
                profile[last], endTerrain, endBoundary, profile[last] - endTerrain);
    }

    /**
     * Keeps every route's structure-facing station on the same boundary grade
     * while limiting the approach to one block per station. This is applied
     * after tunnel/profile selection so a low tunnel cannot terminate below a
     * high structure entrance.
     */
    /**
     * Locks one road endpoint to a village-boundary height and ramps toward the
     * interior. Non-village endpoints (spawn, ordinary road junctions) are never
     * locked, so their grade stays on the normal smooth slope instead of being
     * forced into a steep ramp.
     */
    private static void applySharedApproachProfile(int[] profile,
                                                   int endpoint,
                                                   int[] shared) {
        if (profile == null || shared == null || shared.length == 0) return;
        if (endpoint < 0 || endpoint >= profile.length) return;
        int direction = endpoint == 0 ? 1 : -1;
        for (int station = 0; station < shared.length; station++) {
            int index = endpoint + direction * station;
            if (index < 0 || index >= profile.length) break;
            profile[index] = shared[station];
        }
    }

    private static void applySingleBoundaryGrade(int[] profile,
                                                 int endpoint,
                                                 int boundaryHeight) {
        if (profile == null || endpoint < 0 || endpoint >= profile.length) return;
        profile[endpoint] = boundaryHeight;
        int half = Math.max(1, Math.min(96, (profile.length - 1) / 2));
        constrainEndpointRamp(profile, endpoint, endpoint == 0 ? 1 : -1, half);
    }

    /**
     * Unknown-endpoint variant of the boundary lock: pins the endpoint to the
     * agreed height and descends the approach onto it with
     * prefer-cut semantics — the high side is pressed down onto the locked
     * height one block per station, and stations below the ramp line are
     * never raised. When the chosen height is below the structure surface
     * this is exactly "the high side comes down to meet the road".
     */
    private static void applyPreferCutBoundaryGrade(int[] profile,
                                                    int endpoint,
                                                    int boundaryHeight) {
        if (profile == null || endpoint < 0 || endpoint >= profile.length) return;
        profile[endpoint] = boundaryHeight;
        int direction = endpoint == 0 ? 1 : -1;
        for (int distance = 1; ; distance++) {
            int index = endpoint + direction * distance;
            if (index < 0 || index >= profile.length) return;
            int previous = profile[index - direction];
            if (profile[index] > previous + 1) profile[index] = previous + 1;
        }
    }

    /**
     * Returns the real terrain surface height at the boundary crossing point,
     * sampled as a dedicated one-column precise query. The route's bulk
     * footprint batch often reuses a prefetched/interpolated height; this pins
     * the anchor to the actual ground the road meets so it cannot float above
     * or dive below the structure edge.
     */
    private static int crossingPreciseHeight(AccurateHeightSampler accurate,
                                             BlockPos crossing,
                                             int fallback) {
        if (crossing == null || accurate == null) return fallback;
        try {
            Map<Long, AccurateHeightSample> samples =
                    accurate.samplePositions(List.of(crossing));
            AccurateHeightSample sample = samples.get(
                    AccurateHeightSample.key(crossing.getX(), crossing.getZ()));
            if (sample != null) return accurate.surfaceHeight(sample);
        } catch (RuntimeException ignored) {
            // A transient sampling failure falls back to the profile terrain.
        }
        return fallback;
    }

    /**
     * The road↔structure crossing anchor: the route's own first/last station
     * once the path has been trimmed at the structure boundary (village and
     * other known-structure endpoints stop outside the built area), falling
     * back to the connection endpoint when the route still reaches it. The
     * structure center itself must not be used as the height anchor: its
     * motion-blocking column tops out on the building roof, which locked
     * endpoints dozens of blocks above the terrain the road actually joins.
     */
    private static BlockPos boundaryCrossingAnchor(List<BlockPos> centers,
                                                    BlockPos connectionEndpoint,
                                                    boolean start) {
        if (centers == null || centers.isEmpty()) return connectionEndpoint;
        BlockPos station = start ? centers.get(0) : centers.get(centers.size() - 1);
        return station != null ? station : connectionEndpoint;
    }

    /**
     * Bridge depth for this route: a route-level AUTO bridge-first tendency
     * (resolved in {@code RawPathSearch} from the water share) relaxes the
     * threshold to one block for this connection only; explicit profile modes
     * keep the global path. Span extraction still applies the global mode on
     * top, and the two reductions compose correctly.
     */
    private int routeBridgeMinWaterDepth() {
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
    private static boolean isSharedElevationEndpoint(ServerLevel level,
                                                     StructureConnection connection,
                                                     boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        if (isVillageEndpoint(level, connection, from)) return true;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX()
                && spawn.getZ() == endpoint.getZ()) {
            return true;
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot == null || snapshot.locations() == null) return false;
        for (StructureInfo info : snapshot.locations().structureInfos()) {
            if (info == null || info.pos() == null) continue;
            // Unknown endpoints negotiate their height through
            // UnknownEndpointHeightRegistry instead of the surface lock.
            if (!StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX()
                    && info.pos().getZ() == endpoint.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True only for unknown (unnamed) structure nodes: the snapshot lists an
     * endpoint with no known structure id, it is not a village and not the
     * shared spawn. Only these endpoints choose their shared height by
     * construction cost via {@link UnknownEndpointHeightRegistry}; villages
     * and the spawn keep their existing surface-height lock untouched.
     */
    public static boolean isUnknownStructureEndpoint(ServerLevel level,
                                                     StructureConnection connection,
                                                     boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        if (isVillageEndpoint(level, connection, from)) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX()
                && spawn.getZ() == endpoint.getZ()) {
            return false;
        }
        StructureFileStorage.StructureSnapshot snapshot =
                StructureFileStorage.getStructureSnapshot(level);
        if (snapshot != null && snapshot.locations() != null) {
            for (StructureInfo info : snapshot.locations().structureInfos()) {
                if (info == null || info.pos() == null) continue;
                if (info.pos().getX() == endpoint.getX()
                        && info.pos().getZ() == endpoint.getZ()) {
                    return !StructureInfo.isKnownId(info.structureId())
                            && !StructureInfo.isVillageId(info.structureId());
                }
            }
        }
        // Only a node that is definitively not a known structure, not a village,
        // and not the spawn may use negotiated unknown height. A named/village
        // structure must keep its real surface height even when its planning
        // snapshot has not been prepared yet.
        return !isKnownStructureEndpoint(level, connection, from);
    }

    private static void constrainEndpointRamp(int[] profile, int endpoint,
                                              int direction, int limit) {
        int previous = endpoint;
        for (int distance = 1, index = endpoint + direction;
             distance <= limit && index >= 0 && index < profile.length;
             distance++, index += direction) {
            int current = profile[index];
            if (current > profile[previous] + 1) current = profile[previous] + 1;
            else if (current < profile[previous] - 1) current = profile[previous] - 1;
            profile[index] = current;
            previous = index;
        }
    }

    private static List<Integer> toIntegerList(int[] values) {
        if (values == null || values.length == 0) return List.of();
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }

    /** Closes the short boundary where a tunnel ends immediately before or after a water crossing.
     * The two profiles are normally independent: prefer-cut skips crossing stations, so the
     * tunnel-side bank can remain several blocks above the ford/bridge deck. This pass lowers the
     * higher tunnel-side stations toward the crossing endpoint one block per station without ever
     * raising a station or touching the crossing deck itself. */
    private static int[] closeTunnelWaterBoundary(int[] profile, List<BlockPos> centers,
                                                  List<RoadSpan> spans) {
        if (profile == null || profile.length < 2 || centers == null || spans == null) return profile;
        Map<Long, Integer> stationIndex = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos pos = centers.get(i);
            if (pos != null) stationIndex.putIfAbsent(pos.asLong(), i);
        }
        boolean[] elevated = new boolean[profile.length];
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.TUNNEL && span.type() != SpanType.TUNNEL_JUNCTION
                    && span.type() != SpanType.BRIDGE) continue;
            Integer start = stationIndex.get(span.start().asLong());
            Integer end = stationIndex.get(span.end().asLong());
            if (start == null || end == null) continue;
            for (int k = Math.min(start, end); k <= Math.max(start, end); k++) elevated[k] = true;
        }
        int[] result = profile.clone();
        int k = 12;
        int maxBuffer = 2;
        for (RoadSpan span : spans) {
            if (span == null || span.start() == null || span.end() == null) continue;
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING) continue;
            Integer start = stationIndex.get(span.start().asLong());
            Integer end = stationIndex.get(span.end().asLong());
            if (start == null || end == null) continue;
            int lo = Math.min(start, end);
            int hi = Math.max(start, end);
            closeSpanSide(result, elevated, lo, -1, k, maxBuffer);
            closeSpanSide(result, elevated, hi, 1, k, maxBuffer);
        }
        return result;
    }

    /** Lowers elevated tunnel/bridge stations away from a water-span endpoint. `boundary` is the
     * crossing endpoint index; the adjacent side is boundary + direction. A short run of ordinary
     * buffer stations is allowed between the water span and the elevated physical span, but the
     * ordinary buffer itself is only lowered if doing so follows the one-block-per-station grade.
     * Stations are never raised and the crossing deck itself is never changed. */
    private static void closeSpanSide(int[] profile, boolean[] elevated, int boundary,
                                      int direction, int maxDistance, int maxBuffer) {
        int anchor = profile[boundary];
        int buffersSeen = 0;
        boolean sawElevated = false;
        for (int step = 1, i = boundary + direction; step <= maxDistance && i >= 0 && i < profile.length; i += direction, step++) {
            if (elevated[i]) {
                sawElevated = true;
            } else if (sawElevated) {
                break;
            } else {
                buffersSeen++;
                if (buffersSeen > maxBuffer) break;
            }
            int expected = anchor + step;
            if (profile[i] <= expected) break;
            profile[i] = expected;
        }
    }

    private static void applyCrossingProfiles(ServerLevel level,
                                              TerrainSamplingCache cache,
                                              PathTerrainField terrain,
                                              List<RoadSegmentPlacement> segments,
                                              List<BlockPos> centers,
                                              int[] profile,
                                              List<SpanRange> ranges,
                                              RoadGenerationConfig cfg,
                                              StreamsReflowingCompat.SamplingSession streams,
                                              int bridgeMinWaterDepth) {
        int deckClearance = Math.max(1, cfg.bridgeDeckClearance());
        // 0.9.066（B2）：同一连接上相邻 BRIDGE 跨度甲板连续性。
        // crossingRanges 按 spans 顺序添加、不一定按站号排序；先按 start 排序算出每个跨度的
        // 水面/甲板基准，再把 gap <= BRIDGE_SPAN_CONTINUITY_GAP 的相邻 BRIDGE 跨度视为
        // 「同一片水被切成两段」，后跨甲板不得低于前跨 —— 消除两段桥之间的台阶
        // （真机曾见相邻跨度甲板 71 -> 73 这类跳变）。应用阶段保持原 spans 顺序，只改甲板值。
        int nRanges = ranges.size();
        Integer[] order = new Integer[nRanges];
        for (int ri = 0; ri < nRanges; ri++) order[ri] = ri;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(ri -> ranges.get(ri).start()));
        int[] hwByRange = new int[nRanges];
        int[] deckByRange = new int[nRanges];
        for (int ri : order) {
            SpanRange range = ranges.get(ri);
            hwByRange[ri] = PathSpanExtractor.waterSurfaceYForRange(
                    segments, level, cache, terrain, streams, bridgeMinWaterDepth,
                    range.start(), range.end());
            deckByRange[ri] = BridgeDeckProfile.minimumDeckY(
                    hwByRange[ri],
                    centers.get(range.start()).getY(),
                    centers.get(range.end()).getY(),
                    deckClearance);
        }
        SpanType[] spanTypes = new SpanType[nRanges];
        int[] spanStarts = new int[nRanges];
        int[] spanEnds = new int[nRanges];
        for (int ri = 0; ri < nRanges; ri++) {
            spanTypes[ri] = ranges.get(ri).type();
            spanStarts[ri] = ranges.get(ri).start();
            spanEnds[ri] = ranges.get(ri).end();
        }
        BridgeDeckProfile.enforceAdjacentBridgeContinuity(deckByRange, spanTypes, spanStarts, spanEnds);
        for (int ri = 0; ri < nRanges; ri++) {
            SpanRange range = ranges.get(ri);
            throwIfInterrupted();
            // 0.9.058（缺陷 A）：水面 Y 改由**掩码侧同一套逐列采样**给出。注释保留，语义不变。
            int highestWater = hwByRange[ri];
            int minimumDeck = deckByRange[ri];
            org.slf4j.LoggerFactory.getLogger(Road.class).info(
                    "RoadWeaver [ROAD-BRIDGE-DECK] range={}..{} type={} highestWater={} minimumDeck={}"
                            + " stationYStart={} stationYEnd={} clearance={}",
                    range.start(), range.end(), range.type(),
                    highestWater == Integer.MIN_VALUE ? "none" : highestWater,
                    minimumDeck, centers.get(range.start()).getY(), centers.get(range.end()).getY(),
                    deckClearance);
            if (highestWater == Integer.MIN_VALUE && isCanyonRange(centers, range.start(), range.end())) {
                BridgeDeckProfile.applyStableBankRanges(
                        profile, centers.stream().mapToInt(BlockPos::getY).toArray(),
                        List.of(new int[]{range.start(), range.end()}), Integer.MIN_VALUE);
            } else if (range.type() == SpanType.ROAD_CROSSING
                    && PathSpanExtractor.crossingWidthBlocks(centers, range.start(), range.end())
                            <= PathSpanExtractor.ROAD_CROSSING_MAX_WIDTH_BLOCKS) {
                applyShortFordClearance(profile, range, highestWater, minimumDeck, cfg);
            } else {
                BridgeDeckProfile.applyFixedRange(profile, range.start(), range.end(), minimumDeck,
                        cfg.bridgeRampSegments());
            }
        }
    }

    /**
     * Task 5 / T-21: clears only the ford stations that would dip below the
     * water surface, then seats both banks onto the deck. The ford deck target
     * is {@code highestWater + FORD_DECK_CLEARANCE} (a wadable 1..2 blocks
     * above the water) instead of the bridge fixed deck (water + 16): the old
     * +16 deck raised both banks by up to 6 blocks and left the boundary step
     * ((47,-64)y226 -> (48,-65)y232) that prefer-cut could not close because
     * the deck stations themselves are protected from the slope pass. The bank
     * stations directly adjacent to the span are cut down onto the deck
     * (prefer-cut: only ever lowered); the following {@code
     * enforceContinuousSlopePreferCut} pass then closes the remaining bank
     * profile at one block per station, so the span boundary participates in
     * the continuous grade instead of terminating it.
     */
    private static void applyShortFordClearance(int[] profile,
                                                SpanRange range,
                                                int highestWater,
                                                int minimumDeck,
                                                RoadGenerationConfig cfg) {
        if (highestWater == Integer.MIN_VALUE) return;
        // T-21: 浅滩甲板 = 水位 + FORD_DECK_CLEARANCE(可涉水)。
        int required = highestWater + FORD_DECK_CLEARANCE;
        boolean lifted = false;
        for (int i = range.start(); i <= range.end(); i++) {
            if (profile[i] < required) {
                profile[i] = required;
                lifted = true;
            }
        }
        // 高岸直接切到甲板(prefer-cut:只降不升)。岸站由后续
        // enforceContinuousSlopePreferCut 以 1 格/站继续向下收口。
        int left = range.start() - 1;
        int right = range.end() + 1;
        if (left >= 0 && profile[left] > required) {
            profile[left] = required;
            lifted = true;
        }
        if (right < profile.length && profile[right] > required) {
            profile[right] = required;
            lifted = true;
        }
        if (!lifted) return;
        int ramp = Math.max(0, cfg.bridgeRampSegments());
        rampOntoBank(profile, range.start() - 1, -1, ramp);
        rampOntoBank(profile, range.end() + 1, +1, ramp);
    }

    /** Walks away from a lifted span end, raising the bank at most 1 block per station. */
    private static void rampOntoBank(int[] profile, int from, int direction, int ramp) {
        int previousIndex = from - direction;
        if (previousIndex < 0 || previousIndex >= profile.length) return;
        int previous = profile[previousIndex];
        for (int step = 0; step < ramp; step++) {
            int index = from + direction * step;
            if (index < 0 || index >= profile.length) return;
            if (profile[index] >= previous) return;
            profile[index] = Math.min(previous, profile[index] + 1);
            previous = profile[index];
        }
    }

    private static boolean isCanyonRange(List<BlockPos> centers, int start, int end) {
        if (centers == null || centers.size() < 5) return false;
        int left = Math.max(0, start - 2);
        int right = Math.min(centers.size() - 1, end + 2);
        if (left >= start || right <= end) return false;
        int bank = Math.min(centers.get(left).getY(), centers.get(right).getY());
        int lowest = Integer.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            throwIfInterrupted();
            lowest = Math.min(lowest, centers.get(i).getY());
        }
        return bank - lowest >= 10;
    }

    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Road generation interrupted");
        }
    }

    private record SpanRange(int start, int end, SpanType type) {
    }

    /** Compact span breakdown for per-route pipeline diagnostics. */
    private static String summarizeSpans(List<RoadSpan> spans) {
        if (spans == null || spans.isEmpty()) return "tunnel=0 bridge=0 crossing=0 netReuse=0";
        int tunnel = 0;
        int bridge = 0;
        int crossing = 0;
        int netReuse = 0;
        int protectedGap = 0;
        for (RoadSpan span : spans) {
            if (span == null) continue;
            switch (span.type()) {
                case TUNNEL, TUNNEL_JUNCTION -> tunnel++;
                case BRIDGE -> bridge++;
                case ROAD_CROSSING -> crossing++;
                case NETWORK_REUSE -> netReuse++;
                case PROTECTED_GAP -> protectedGap++;
                default -> { }
            }
        }
        return "tunnel=" + tunnel + " bridge=" + bridge + " crossing=" + crossing
                + " netReuse=" + netReuse + " protectedGap=" + protectedGap;
    }

    /**
     * Rebinding stores the route under the precision that the local-repair
     * wave later queries. Using the full-route step here leaves the rebound
     * cache under a different key, which made the following local pass report
     * "missing persisted route path".
     */
    private int localRetryStep() {
        int configured = genConfig == null ? 4 : Math.max(1, genConfig.pathfinding().effectiveAStarStep());
        return RoutePrecisionRetryPolicy.localRepairBatchSteps(configured).get(0);
    }

}
