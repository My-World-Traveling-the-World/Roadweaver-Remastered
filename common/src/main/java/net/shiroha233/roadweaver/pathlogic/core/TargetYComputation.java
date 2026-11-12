/* 文件职责：目标高度（纵断面）计算 —— 纯函数，只吃参数、吐每站的目标 Y。 */
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

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.core.Road.*;

import net.shiroha233.roadweaver.pathlogic.profile.ProfileAbObserver;

/**
 * 目标高度（纵断面）计算。
 *
 * <h2>它算什么</h2>
 * <p>给定沿线的地形采样、跨度划分与坡度配置，算出**每一站的目标 Y**。
 * 这是整条路的纵断面骨架 —— 后面所有「填/挖/桥/隧道」都读这张表。</p>
 *
 * <h2>★ 为什么能做成纯函数</h2>
 * <p>它原来的签名就已经把所有输入都收齐了（level / segments / spans / cache /
 * terrain / cfg / streams），**方法体里一处 this 都没有**。唯一的例外是一次
 * {@code routeBridgeMinWaterDepth()} 调用 —— 那是单个配置值，本轮改成显式参数
 * {@code bridgeMinWaterDepth} 传入，语义完全不变。</p>
 *
 * <p>这条「先把隐式依赖摊成参数、再搬」的顺序很关键：先搬再补参数会变成
 * 边搬边改逻辑，就不是等价的纯搬迁了。</p>
 *
 * <p>本类是从 1110 行的 {@code Road} 里<b>搬出来</b>的，方法体一字未改
 * （除了把一次配置调用换成同名参数），含缩进。</p>
 */
public final class TargetYComputation {
    /** 剖面 AB 实验窗口：允许挖多深。实验参数，非生产值。 */
    private static final int PROFILE_AB_MAX_CUT = 24;
    /** 剖面 AB 实验窗口：允许填多高。实验参数，非生产值。 */
    private static final int PROFILE_AB_MAX_FILL = 12;

    public static List<Integer> computeTargetY(ServerLevel level, List<RoadSegmentPlacement> segments,
                                                   List<RoadSpan> spans, TerrainSamplingCache cache,
                                                   PathTerrainField terrain,
                                                   RoadGenerationConfig cfg,
                                                   StreamsReflowingCompat.SamplingSession streams,
                                     StructureConnection connection,
                                     int bridgeMinWaterDepth) {
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
                streams, bridgeMinWaterDepth);
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
        // ── 剖面 AB 双跑（只观察，不改变返回结果）──────────────────────────
        // 旧流水线已经算出 smoothed。这里另外跑一次全局 DP 并把两者度量并排记下来，
        // 用于回答「新解到底更好还是更差」——在此之前不允许切默认。
        // 开关：-Droadweaver.profileAb=on（默认关闭，关闭时一次布尔判断就返回）。
        //
        // 窗口参数是**实验参数**，不是生产值：故意放宽（挖 24 / 填 12）是为了让 DP
        // 在陡地形上也可行，把对比聚焦在「坡度平顺 vs 土方量」，而不是让不可行掩盖一切。
        // maxStep 取「每两站 M 格」折算成的每站步长（向上取整），与旧版约束口径一致。
        ProfileAbObserver.observe(smoothed, terrainY, isCrossing,
                PROFILE_AB_MAX_CUT, PROFILE_AB_MAX_FILL,
                Math.max(1, (cfg.maxSlopeStepPerTwoSegments() + 1) / 2),
                connection == null ? "?" : connection.from() + "->" + connection.to());
        return out;
    }
}
