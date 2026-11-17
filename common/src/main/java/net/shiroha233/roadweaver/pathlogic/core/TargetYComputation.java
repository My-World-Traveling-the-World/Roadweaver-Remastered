/* 文件职责：协调地形采样、端点高度和跨度处理，产出逐站目标高程。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import java.util.*;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import static net.shiroha233.roadweaver.pathlogic.core.RoadCrossingOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps.*;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.core.Road.*;

import net.shiroha233.roadweaver.pathlogic.profile.ProfileAbObserver;
import net.shiroha233.roadweaver.pathlogic.profile.ExistingRoadProbe;
import net.shiroha233.roadweaver.pathlogic.profile.GroundBanSources;
import net.shiroha233.roadweaver.pathlogic.profile.ModeDpBridge;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileTerrainRecorder;

/**
 * 目标高度编排：读取本次地形与结构输入，协调跨度和剖面处理。
 * 本类会访问世界，并非纯函数；DP 硬约束与回落编排由 TargetProfileSolver 独立承担。
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

        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        // 输入组装只搬读边界；旧跨度crossing及后续模式编排保持原样。
        var inputs = TargetTerrainInputs.sample(segments, centers, Math.max(0, cfg.averagingRadius()),
                accurate::samplePositions, accurate::surfaceHeight,
                center -> center == null ? level.getMinBuildHeight()
                        : RoadConstructionSafety.terrainHeight(level, center.getX(), center.getZ()));
        int[] terrainY = inputs.terrainY(), footprintSupport = inputs.footprintSupport(), base = inputs.base();
        Map<Long, AccurateHeightSample> footprintSamples = inputs.footprintSamples();

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
        java.util.Optional<int[]> dpProfile = TargetProfileSolver.applyProfileSolver(level, centers, smoothed,
                terrainY, isCrossing, tunnelMask, pinnedMask, startBoundary, endBoundary,
                cfg, gradeLimits, connection);
        final int[] legacySnapshot;
        if (dpProfile.isPresent()) {
            // DP 成功：不跑旧 pass。AB 观测器的 legacy 臂因此不存在（见下方 null 守卫）。
            smoothed = dpProfile.get();
            legacySnapshot = null;
        } else {
            // 只对DP明确不适用的入口运行保留模式；DP不可行仍由新版约束回落处理。
            smoothed = LegacyTargetProfile.apply(smoothed,
                    new LegacyTargetProfile.Stations(centers, terrainY, isCrossing, tunnelMask, pinnedMask, crossingRanges),
                    spans, cfg, new LegacyTargetProfile.BoundaryLocks(structureGradesLocked,
                            startVillage, endVillage, startUnknown, endUnknown,
                            startSharedProfile, endSharedProfile, startBoundary, endBoundary), connection,
                    new LegacyTargetProfile.WorldPasses() {
                        @Override
                        public void crossing(int[] profile) {
                            applyCrossingProfiles(level, cache, terrain, segments, centers, profile, crossingRanges,
                                    cfg, streams, bridgeMinWaterDepth);
                        }
                        @Override
                        public int[] undersea(int[] profile) {
                            List<RoadSpan> underseaSpans = UnderseaTunnelPlanner.convertLongOceanBridgesToUnderseaTunnels(
                                    spans, segments, level, cache, footprintSamples);
                            if (underseaSpans != spans && spans != null) {
                                try {
                                    spans.clear();
                                    spans.addAll(underseaSpans);
                                } catch (UnsupportedOperationException ignored) {
                                    // 临时不可变列表不携带桥归属；生产列表可写，保持旧模式的容错语义。
                                }
                            }
                            return UnderseaTunnelPlanner.applyUnderseaTunnelProfile(
                                    toIntegerList(profile), spans, segments, level, cache, footprintSamples,
                                    cfg.tunnelClearHeight()).stream().mapToInt(Integer::intValue).toArray();
                        }
                    });
            legacySnapshot = smoothed.clone();
        }

        // ── 剖面求解器模式分派（S7 第 1 级）──────────────────────────────
        // ★ 默认 DP（2026-11-13 起，见 ProfileSolverMode.parse）；
        //   {@code -Droadweaver.profileSolver=legacy} 才是显式回退。
        //   本行在 2026-11-15 复核时更正：原来写的是「默认 LEGACY」，与实现相反。
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
                    LOGGER.info("【纵断面相邻站点高度突变】 " + "RoadWeaver profile height step idx={} delta={} pos=({},{})y{}->({},{})y{} inWaterSpan={}",
                            i, delta,
                            a.getX(), a.getZ(), smoothed[i - 1],
                            b.getX(), b.getZ(), smoothed[i],
                            isCrossing[i - 1] && isCrossing[i]);
                }
                stepCount++;
            }
        }
        if (stepCount > 0) {
            LOGGER.info("【纵断面高度突变数量汇总】 " + "RoadWeaver profile height steps total={} stations={} connection={}->{}",
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
                LOGGER.info("【水域跨度与岸边存在高度跳变】 " + "RoadWeaver water-span boundary height jump range={}..{} "
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
        // ★ 禁地面掩码只算一次，剖面 AB 与桥隧 AB 两臂共用。
        //   2026-11-15（独立复核）：原先桥隧臂用 GroundBanSources 的独立掩码、
        //   剖面臂仍用 isCrossing（旧判定产物）⇒ 两臂口径不同、数据不可比。
        //   现在统一由 GroundBanSources 提供，它才是唯一的禁地面来源。
        //
        //   第三条来源「已有公路」（2026-11-15 补齐）：探针由 ExistingRoadProbe 构建，
        //   它会按端点 owner 键**排除当前路自身** —— 这一步是必需的，
        //   因为重规划路径（rerouteAvoiding → regenerateRoadWithoutPersist →
        //   computeTargetY）执行时当前路的旧几何已经在存储里，
        //   不排除就会把整条走廊判成「已有公路」⇒ 全站禁地面。
        //   owner 键的算法与 Road.java:115-116 完全一致，都是 pos2dKey(端点)。
        GroundBanSources.RoadProbe existingRoad = null;
        if (connection != null && connection.from() != null && connection.to() != null) {
            long ownerA2d = net.shiroha233.roadweaver.planning.PlanningUtils.pos2dKey(connection.from());
            long ownerB2d = net.shiroha233.roadweaver.planning.PlanningUtils.pos2dKey(connection.to());
            existingRoad = ExistingRoadProbe.forCorridor(
                    level, centers, ownerA2d, ownerB2d);
        }
        GroundBanSources.Result groundBans = GroundBanSources.collect(
                terrain, existingRoad, centers, n, Math.max(1, bridgeMinWaterDepth));
        if (legacySnapshot != null) {
            ProfileAbObserver.observeModes(legacySnapshot, terrainY, groundBans.banned(), tunnelMask,
                    Math.max(1, (cfg.maxSlopeStepPerTwoSegments() + 1) / 2),
                    cfg.maxSlopeStepPerTwoSegments(),
                    connection == null ? "?" : connection.from() + "->" + connection.to());
        }
        // ── 桥隧判定 AB 观测（重制台账批 2 / S7 第 2 级第 3 步）────────────
        // 拿旧判定（PathSpanExtractor）的 spans 当基线，另外跑一次 ModeDpSolver，
        // 把「几座桥 / 几条隧道 / 差异站数」记进日志。**不改任何决策** ——
        // 这一版是纯观测，接错了也只是日志难看。
        //
        // 为什么先观测：S7 第 2 级的通过标准是「旧判定判出的每座必须的桥，
        // 新解也判了（召回率 100%）」，而这个数字只能在真实存档上量。
        // 开关：-Droadweaver.modeDp=ab（默认）/ off。
        // 禁地面来源已改为「水面 + 保护区」两条独立物理事实（见 GroundBanSources），
        // isCrossing 只作对照 —— 这样 DP 判出的桥才不是「复述旧判定」。
        // ── 跨度回写求解（批 2 收尾 / 用户在 2026-11-15 拍板方案 A）──────
        // 与上面的观测**共用同一次求解**：先把解存进 ThreadLocal 快照，
        // `Road.generateRoad` 在 spans 定稿后取用并合并。
        // 回写开关默认关（`-Droadweaver.spanWriteback=on`），关掉时本调用直接返回。
        ModeDpBridge.solveAndStash(terrainY, groundBans, gradeLimits, cfg, centers);
        ModeDpBridge.observe(terrainY, groundBans, isCrossing,
                gradeLimits, cfg, centers, spans,
                connection == null ? "?" : connection.from() + "->" + connection.to());
        // 把真实地形录成一行，供 CI 重放（有录制文件时 ProfileTerrainReplayTest 会自动启用）。
        // 只在地形、不录旧剖面 —— 我们要比的是「同一地形，两种算法各自算出的解」。
        ProfileTerrainRecorder.record(
                connection == null ? "?" : connection.from() + "->" + connection.to(),
                terrainY, isCrossing);
        return out;
    }

}
