/* 文件职责：目标高度（纵断面）计算 —— 纯函数，只吃参数、吐每站的目标 Y。 */
package net.shiroha233.roadweaver.pathlogic.core;

import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
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
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileSolverMode;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileTerrainRecorder;

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
                            SurfaceGradeLimits.capture());
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
                            SurfaceGradeLimits.capture());
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
        // ── 2026-11-14（缺陷 C 第二步）：以下两块是**纯输入准备**，与旧 pass 无关，
        //    上移到所有 pass 之前 —— 这样 DP 才可能在跑 pass 之前就拿到全部输入，
        //    从而「DP 成功即跳过旧 pass」（去双跑）。纯移动，无行为变化。
        //
        // 隧道站掩码：只依赖 spans 与 centers，不读 smoothed。
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

        // 施工端的真实挖填上限。DP 与旧流水线必须用同一套窗口，否则 DP 会挖出
        // 施工端直接拒收的深度（审计实测：24/12 实验窗口下约 0.3% 的站被判死）。
        SurfaceGradeLimits gradeLimits =
                SurfaceGradeLimits.capture();

        // ── 2026-11-14（缺陷 C 第三步）：先跑 DP，成功就**跳过整段旧 pass**。
        //
        // 改动前：旧 15 道 pass 无条件跑完 → 再用 DP 结果整条覆盖（双跑）。
        // 改动后：DP 拿到全部输入（含上移的 tunnelMask / gradeLimits）后立刻求解；
        // 成功 ⇒ 旧 pass 一行不跑。不可行 / 不适用 ⇒ empty ⇒ 照旧跑旧 pass，
        // 与改动前逐行相同，只在外面多了一层 if。
        java.util.Optional<int[]> dpProfile = applyProfileSolver(level, centers, smoothed,
                terrainY, isCrossing, tunnelMask, pinnedMask, startBoundary, endBoundary,
                cfg, gradeLimits, connection);
        final int[] legacySnapshot;
        if (dpProfile.isPresent()) {
            // DP 成功：不跑旧 pass。AB 观测器的 legacy 臂因此不存在（见下方 null 守卫）。
            smoothed = dpProfile.get();
            legacySnapshot = null;
        } else {
            // ════════════════════════════════════════════════════════════════
            // 待删（批1）：旧纵断面 pass 编排层。
            //
            // 它在默认配置下**一行都不跑** —— 只有两种「DP 本来就不适用」的情形
            // 会进到这里：
            //   · profileSolver 不是 DP（显式 -Droadweaver.profileSolver=legacy）
            //   · 玩家关掉坡度限制（旧 pass 也不夹坡，DP 的硬约束会打架）
            //
            // 删除条件（与 pathlogic/grade 那三个类同一批，三条全满足）：
            //   ① pathlogic/profile 的 DP 内核有独立测试且全绿；
            //   ② 这两条 legacy 入口被正式移除（或改成抛异常提示已废弃）；
            //   ③ 删完回归全绿。
            //
            // 为什么不现在搬成独立文件：它用了 19 个局部变量 + 本类的
            // applyCrossingProfiles / closeTunnelWaterBoundary 两个私有方法，
            // 搬走要么造 19 字段的参数对象、要么把私有方法也拖出去。
            // 按 REFACTOR.md 的铁律「重制期间旧实现原地保留、打标记、进台账」，
            // 原地标记就够了 —— 搬迁属于可选的结构整理，不改变它的性质。
            // ════════════════════════════════════════════════════════════════
            // ── 旧 pass 区（逐行沿用，仅整体缩进 4 格）──────────────────────
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

            legacySnapshot = smoothed.clone();
        }

        // ── 剖面求解器模式分派（S7 第 1 级）──────────────────────────────
        // ★ 默认 LEGACY；{@code -Droadweaver.profileSolver=legacy} 可显式回退。
        //
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
        // ★ 观测的第一臂必须是**旧流水线的真实输出**（legacySnapshot），不能是 smoothed ——
        // 后者此时已经是 DP 的结果，两条臂都成 DP 就测不出任何东西（审计发现）。
        // 窗口也必须与生产一致：旧代码用的是实验窗口 24/12，会让 DP 挖出施工端拒收的深度。
        //
        // ★ 坡度口径（2026-11-13 修正）：配置 maxSlopeStepPerTwoSegments 的语义是
        // 「每**两**站最多升降 N 格」，必须原样传给二阶约束。曾经用 ceil(N/2) 折算成
        // 「每站」是错的 —— 那允许「连续每站 +1」，三站升 3 格，比配置允许的多一倍，
        // 于是 DP 拿「更陡」去换「更少土方」，AB 对比因此不公平。
        // 2026-11-14（缺陷 C 第三步）：DP 成功时旧 pass 没跑，legacy 臂不存在 ——
        // 此时**不做 AB 观测**（而不是拿 DP 自己的输出当 legacy 臂，那会让数据彻底失真）。
        if (legacySnapshot != null) {
            ProfileAbObserver.observe(legacySnapshot, terrainY, isCrossing,
                    gradeLimits.plainCut(), gradeLimits.plainFill(),
                    Math.max(1, (cfg.maxSlopeStepPerTwoSegments() + 1) / 2),
                    cfg.maxSlopeStepPerTwoSegments(),
                    connection == null ? "?" : connection.from() + "->" + connection.to());
        } else if (ProfileAbObserver.enabled()) {
            LOGGER.info("RoadWeaver profile AB skipped: DP 已跳过旧 pass，无 legacy 臂可对比"
                    + " stations={} connection={}->{}", n,
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to());
        }
        // S4：在同样的物理约束下让联合 DP 自己重新决定桥/隧道/地面。
        // groundBanned 喂的是「这里不能铺地面」这条物理事实（水面 / 已有公路 / 保护区），
        // **不喂**旧版判出来的桥/隧道选择 —— 否则 DP 只是在复现旧决策。
        if (legacySnapshot != null) {
            ProfileAbObserver.observeModes(legacySnapshot, terrainY, isCrossing, tunnelMask,
                    Math.max(1, (cfg.maxSlopeStepPerTwoSegments() + 1) / 2),
                    cfg.maxSlopeStepPerTwoSegments(),
                    connection == null ? "?" : connection.from() + "->" + connection.to());
        }
        // 把真实地形录成一行，供 CI 重放（有录制文件时 ProfileTerrainReplayTest 会自动启用）。
        // 只在地形、不录旧剖面 —— 我们要比的是「同一地形，两种算法各自算出的解」。
        ProfileTerrainRecorder.record(
                connection == null ? "?" : connection.from() + "->" + connection.to(),
                terrainY, isCrossing);
        return out;
    }

    /**
     * 按 {@link ProfileSolverMode} 决定是否用全局 DP 替换旧流水线的剖面。
     *
     * <h2>三条安全约束</h2>
     * <ol>
     *   <li><b>默认路径零改动</b>：{@code LEGACY} 时立刻返回入参，不构造任何对象；</li>
     *   <li><b>DP 失败必须回落</b>：不可行 / 异常一律返回旧剖面并记警告 ——
     *       绝不因为实验性的求解器让一条路算不出来；</li>
     *   <li><b>硬契约必须传进去</b>：结构边界锁定（村庄/出生点/命名结构）钉在
     *       {@code startBoundary}/{@code endBoundary}，隧道段的覆土用
     *       {@code SurfaceGradeLimits.tunnelCoreOverburden()}。
     *       少传任何一条都会让入口高度偏离实测地表 —— 那正是玩家一眼能看出来的错误。</li>
     * </ol>
     *
     * <p>窗口与坡度口径<b>必须与生产一致</b>：窗口用 {@link SurfaceGradeLimits}
     * 的实时上限（施工端同一套），坡度用配置原值传给二阶约束。
     * 曾经用「AB 实验窗口 24/12 + ceil(N/2) 每站步长」，两者都让 DP 比生产允许的更激进 ——
     * 2026-11-13 独立审计发现并修正。</p>
     *
     * @param isCrossing 每站是否跨水/桥。这是 {@link ProfileDpSource} 的「桥段钉成直线」输入，
     *                   <b>不是</b> tunnelMask —— 曾经传错，导致桥面甲板高度被整条覆盖掉
     * @param gradeLimits 本趟规划的施工上限快照（挖/填/覆土同源）
     */
    /**
     * 已钉住的站数（{@code pin} 用 {@code -1} 表示「不钉」）。
     *
     * <p>注意它与 {@link #countDeckFloors(int[])} 的哨兵值<b>不同</b>：
     * 两个数组语义不一样，混用一个统计函数会把日志写错 —— 而说谎的日志
     * 比没有日志更糟（本项目已因「静默降级 + 日志不实」吃过多次亏）。</p>
     */
    private static int countPinned(int[] pin) {
        if (pin == null) return 0;
        int c = 0;
        for (int v : pin) {
            if (v >= 0) c++;
        }
        return c;
    }

    /** 已给定桥面下界的站数（{@code deckFloor} 用 {@link Integer#MIN_VALUE} 表示「无」）。 */
    private static int countDeckFloors(int[] deckFloor) {
        if (deckFloor == null) return 0;
        int c = 0;
        for (int v : deckFloor) {
            if (v != Integer.MIN_VALUE) c++;
        }
        return c;
    }

    /**
     * 尝试用全局 DP 求解剖面。
     *
     * <p><b>2026-11-14（缺陷 C 第二步）</b>：返回值从 {@code int[]} 改成
     * {@link java.util.Optional}，为了让调用方能区分两件事：</p>
     * <ul>
     *   <li><b>empty</b> —— 「本模式不适用」或「不可行」。调用方<b>应当</b>照旧跑
     *       旧 pass 流水线（这正是改动前的行为，零风险）；</li>
     *   <li><b>present</b> —— 已经算出完整剖面。调用方<b>可以跳过</b>旧 pass，
     *       这是「去双跑」的全部意义。</li>
     * </ul>
     *
     * <p>注意「不可行 → empty」是有意选择：那种情况下旧 pass 的结果仍然比
     * {@code ProfileFallback} 好（旧 pass 会做隧道/边界等语义处理），
     * 所以让它回落旧链路而不是回落几何投影。</p>
     */
    private static java.util.Optional<int[]> applyProfileSolver(
            ServerLevel level, List<BlockPos> centers,
            int[] smoothed, int[] terrainY, boolean[] isCrossing,
            boolean[] tunnelMask, boolean[] pinnedMask,
            int startBoundary, int endBoundary,
            RoadGenerationConfig cfg,
            SurfaceGradeLimits gradeLimits,
            StructureConnection connection) {
        if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) {
            return java.util.Optional.empty();
        }
        // 用户关掉坡度限制时，旧流水线也不夹坡。DP 若仍强制 |Δ|≤N 就是两套语义打架。
        if (!cfg.slopeLimitEnabled()) {
            return java.util.Optional.empty();
        }
        int n = smoothed.length;
        try {
            int[] pin = new int[n];
            java.util.Arrays.fill(pin, -1);
            if (pinnedMask != null) {
                if (pinnedMask.length > 0 && pinnedMask[0]) pin[0] = startBoundary;
                if (pinnedMask.length > n - 1 && pinnedMask[n - 1]) pin[n - 1] = endBoundary;
            }
            int minCover = Math.max(1, gradeLimits.tunnelCoreOverburden());
            // mustBeCovered 用隧道掩码（覆土是隧道的要求）；crossing 用桥掩码（桥面钉直线）。
            // 这两个是不同的事实，曾经被同一个 tunnelMask 顶替 —— 后果是隧道被当成桥钉直、
            // 而真正的桥面甲板高度被 DP 整条覆盖掉。
            // ★ 桥面水位下界 —— 这是「重构」而不是「补丁」的关键点。
            //
            // 旧版：computeTargetY 算完剖面之后，由 applyFixedRange 把桥面整段抬到
            //       「最高水位 + bridgeDeckClearance」。那是事后修正，抬完之后
            //       坡度约束、1-2-1 平滑、相邻桥连续性全部失效，只能再贴一轮补丁。
            //
            // 新版：在求解**之前**就把这个下界算出来喂给 DP。DP 在满足坡度上限的
            //       前提下自己找最优过渡 —— 桥面该平的段落自然平（同一水位下界相同），
            //       过渡段由 DP 统一安排，不需要事后贴坡。
            //
            // 下界来源是旧版的权威算法 RoadConstructionSafety.bridgeDeckSurfaceY：
            // 逐站查最高水位，并向两侧 WATERCOURSE_HALF_WIDTH(=3) 取样取最高，
            // 再加 clearance。水位不可用时它返回 fallback，这里传 Integer.MIN_VALUE
            // 表示「无下界」。
            int[] deckFloor = new int[n];
            java.util.Arrays.fill(deckFloor, Integer.MIN_VALUE);
            if (isCrossing != null && centers != null && centers.size() == n) {
                int clearance = Math.max(1, cfg.bridgeDeckClearance());
                for (int i = 0; i < n; i++) {
                    if (!isCrossing[i]) continue;
                    deckFloor[i] = RoadConstructionSafety.bridgeDeckSurfaceY(
                            level, centers, i, clearance, Integer.MIN_VALUE);
                }
            }
            var constraints = new ProfileDpSource.Constraints(pin, tunnelMask, minCover, deckFloor);
            int perTwo = Math.max(1, cfg.maxSlopeStepPerTwoSegments());
            // 一阶上限 = 把「每两站 N 格」折算成每站（向上取整）。它只是兜底，
            // 真正绑定的是二阶约束 |d_i + d_{i−1}| ≤ perTwo。
            int maxStep = Math.max(1, (perTwo + 1) / 2);
            // 窗口取施工端的实时上限 —— 与旧流水线同一套，DP 才不会挖出施工端拒收的深度。
            int maxCut = Math.max(0, gradeLimits.plainCut());
            int maxFill = Math.max(0, gradeLimits.plainFill());

            var solved = ProfileDpSource.solve(terrainY, isCrossing,
                    maxCut, maxFill, maxStep, perTwo, constraints);
            if (solved.isEmpty()) {
                // 2026-11-14（缺陷 C 收尾）：DP 不可行 ⇒ 用无状态回落解，
                // **不再跑旧 15 道 pass**。
                //
                // 这是「旧 pass 退出生产路径」的关键一步：改动前 DP 不可行会
                // 返回 empty，调用方就去跑旧 pass；现在它返回 ProfileFallback 的解，
                // 调用方直接采用。于是 empty 只剩两种「DP 不适用」的情形：
                //   · profileSolver 不是 DP（显式 -Droadweaver.profileSolver=legacy）
                //   · 玩家关掉了坡度限制（旧 pass 也不夹坡，DP 的硬约束会打架）
                // 那两种情形本来就该走旧链路，语义没问题。
                //
                // 回落解取的是同一套硬约束（窗口 / 一阶二阶坡度 / 站钉 / 桥面下界 /
                // 隧道覆土），所以结果一定合规 —— 它只是不做全局最优，不像旧 pass
                // 那样有一堆互相打架的事后修正。
                LOGGER.warn("RoadWeaver profile solver=dp infeasible, using constraint fallback"
                                + " stations={} cut={} fill={} perTwo={} pins={} decks={}"
                                + " connection={}->{}",
                        n, maxCut, maxFill, perTwo, countPinned(pin), countDeckFloors(deckFloor),
                        connection == null ? "?" : connection.from(),
                        connection == null ? "?" : connection.to());
                return java.util.Optional.of(ProfileFallback.terrainFollowing(
                        terrainY, maxCut, maxFill, maxStep, perTwo,
                        pin, tunnelMask, minCover, deckFloor));
            }
            LOGGER.info("RoadWeaver profile solver=dp stations={} perTwo={} maxStep={} cut={} fill={} minCover={} connection={}->{}",
                    n, perTwo, maxStep, maxCut, maxFill, minCover,
                    connection == null ? "?" : connection.from(),
                    connection == null ? "?" : connection.to());
            return java.util.Optional.of(solved.get());
        } catch (Throwable t) {
            // 实验性求解器绝不能让一条路算不出来。
            // 2026-11-14（缺陷 C 第一步）：回落目标从「旧 pass 结果」改成无状态回落解。
            LOGGER.warn("RoadWeaver profile solver=dp failed, using constraint fallback: {}",
                    t.toString());
            return java.util.Optional.of(ProfileFallback.terrainFollowing(
                    terrainY, Math.max(0, gradeLimits.plainCut()),
                    Math.max(0, gradeLimits.plainFill()),
                    Math.max(1, (Math.max(1, cfg.maxSlopeStepPerTwoSegments()) + 1) / 2),
                    Math.max(1, cfg.maxSlopeStepPerTwoSegments()),
                    null, tunnelMask, Math.max(1, gradeLimits.tunnelCoreOverburden()), null));
        }
    }

}
