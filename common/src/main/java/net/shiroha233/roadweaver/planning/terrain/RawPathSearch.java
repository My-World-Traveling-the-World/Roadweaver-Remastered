/* 文件职责：统一使用量化地形场执行道路原始路径搜索。 */
package net.shiroha233.roadweaver.planning.terrain;

import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.RouteProfileTendency;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.PathfinderFactory;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.snapToGrid;

final class RawPathSearch {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private RawPathSearch() {}

    static List<BlockPos> find(ServerLevel level,
                               StructureConnection connection,
                               PathfindingCostConfig config,
                               TerrainSamplingCache cache,
                               PathTerrainField terrain) {
        return find(level, connection, config, cache, terrain, 0);
    }

    static List<BlockPos> find(ServerLevel level,
                               StructureConnection connection,
                               PathfindingCostConfig config,
                               TerrainSamplingCache cache,
                               PathTerrainField terrain,
                               int additionalRoadRadius) {
        int step = config.effectiveAStarStep();
        int startX = snapToGrid(connection.from().getX(), step);
        int startZ = snapToGrid(connection.from().getZ(), step);
        int endX = snapToGrid(connection.to().getX(), step);
        int endZ = snapToGrid(connection.to().getZ(), step);
        if (!terrain.contains(startX, startZ) || !terrain.contains(endX, endZ)) {
            return null;
        }
        BlockPos start = new BlockPos(startX, terrain.height(startX, startZ), startZ);
        BlockPos end = new BlockPos(endX, terrain.height(endX, endZ), endZ);
        Pathfinder pathfinder = PathfinderFactory.create(config.pathfindingAlgorithm());
        try (StructureAvoidanceService.PlanningScope ignored =
                     StructureAvoidanceService.activatePlanningSnapshot(
                             level, connection, additionalRoadRadius)) {
            PathResult result = searchWithHardBuildingObstacles(
                    pathfinder, start, end, level, config, cache, terrain);
            if (!result.success() || !result.hasRawPath()) return null;
            List<BlockPos> route = MountainSwitchbackPlanner.replaceIfSafer(
                    result.rawPath(), start, end, terrain, config, true);
            List<BlockPos> modeRoute = applyCoarseTerrainModes(route, terrain, config, globalBridgeMinDepth());
            Map<RoadTerrainType, Integer> terrainCounts;
            StreamsReflowingCompat.SamplingSession streams =
                    StreamsReflowingCompat.openSession(level);
            // The live Streams channel is part of the route classification:
            // a rerouted river leaves the vanilla river biome, and without
            // this probe the route resolves as river=0 -> TUNNEL_FIRST and
            // zig-zags across the channel instead of bridging it once.
            terrainCounts = RoadTerrainClassifier.classifyRoute(modeRoute, terrain, streams);
            boolean mountain = terrainCounts.getOrDefault(RoadTerrainType.MOUNTAIN, 0) >= 3;
            PlanningConfig planning = ConfigService.get().planning();
            PlanningConfig.TerrainProfileMode profileMode = planning.terrainProfileMode();
            double mountainFraction = mountainFraction(terrainCounts);
            double waterFraction = waterFraction(terrainCounts);
            // AUTO resolves a route-level tendency from the terrain shares and
            // stores it for this connection only (the exact generation phase
            // reads it for grade clamps and bridge depth). Explicit
            // TUNNEL_FIRST / BRIDGE_FIRST / LIGHT_CUT selections ignore the
            // thresholds entirely; nothing is written back to ConfigService.
            RouteProfileTendency.Tendency tendency = resolveAutoTendency(
                    profileMode, mountainFraction, waterFraction,
                    planning.autoMountainThresholdFraction(),
                    planning.autoWaterThresholdFraction());
            RouteProfileTendency.set(connection, tendency);
            boolean tunnelFirst = profileMode == PlanningConfig.TerrainProfileMode.TUNNEL_FIRST
                    || (profileMode == PlanningConfig.TerrainProfileMode.AUTO
                    && tendency == RouteProfileTendency.Tendency.TUNNEL_FIRST);
            boolean bridgeFirst = profileMode == PlanningConfig.TerrainProfileMode.BRIDGE_FIRST
                    || (profileMode == PlanningConfig.TerrainProfileMode.AUTO
                    && tendency == RouteProfileTendency.Tendency.BRIDGE_FIRST);
            int routeBridgeMinDepth = bridgeFirst
                    ? 1
                    : Math.max(1, ConfigService.get().bridge().minWaterDepth());
            LOGGER.info("RoadWeaver coarse terrain profile: mountain={}({}) plain={} coast={} river={} lake={} ocean={} "
                            + "water=({}) mode={} autoTendency={} step={}",
                    terrainCounts.getOrDefault(RoadTerrainType.MOUNTAIN, 0),
                    String.format("%.2f", mountainFraction),
                    terrainCounts.getOrDefault(RoadTerrainType.PLAIN, 0),
                    terrainCounts.getOrDefault(RoadTerrainType.COAST, 0),
                    terrainCounts.getOrDefault(RoadTerrainType.RIVER, 0),
                    terrainCounts.getOrDefault(RoadTerrainType.LAKE, 0),
                    terrainCounts.getOrDefault(RoadTerrainType.OCEAN, 0),
                    String.format("%.2f", waterFraction),
                    profileMode, tendency, step);
            if (bridgeFirst && routeBridgeMinDepth < globalBridgeMinDepth()) {
                // Rebuild the tunnel-hint precheck with the relaxed depth so
                // shallow water columns stay protected from coarse tunnel
                // conversion, matching what span extraction will later see.
                List<BlockPos> bridged = applyCoarseTerrainModes(
                        route, terrain, config, routeBridgeMinDepth);
                if (bridged != null && bridged.size() >= 2) modeRoute = bridged;
            }
            boolean transitionProblem = hasUnbuildableCoarseTransition(modeRoute, terrain);
            boolean structureProblem = hasCoarseStructureConflict(modeRoute, terrain, step);
            if (wantsHeightAware(
                    planning.coarseHeightAwareEnabled(),
                    transitionProblem || structureProblem, tunnelFirst, mountain)) {
                List<BlockPos> heightAware = CoarseHeightAwarePlanner.find(
                        start, end, terrain, config, planning.heightAwareStateLimit());
                if (heightAware != null && heightAware.size() >= 2) {
                    heightAware = applyCoarseTerrainModes(heightAware, terrain, config, routeBridgeMinDepth);
                    // A straight corridor whose cut/fill (or single span rise)
                    // exceeds the build thresholds must be replaced by a longer
                    // but buildable height-aware reroute. Score preference only
                    // applies when both candidates are buildable, so a cliff is
                    // crossed by ramp/tunnel/bridge instead of a hard profile.
                    // P1-5: modeRoute is unchanged since transitionProblem and
                    // structureProblem were computed above, so reuse them instead
                    // of re-scanning the same corridor twice (the structure scan
                    // is a planning-obstacle lookup per edge).
                    boolean currentBuildable = !transitionProblem;
                    boolean candidateBuildable = !hasUnbuildableCoarseTransition(heightAware, terrain);
                    boolean currentClear = !structureProblem;
                    boolean candidateClear = !hasCoarseStructureConflict(heightAware, terrain, step);
                    // Structure avoidance is front-loaded: if the straight
                    // corridor is blocked by a building but the height-aware
                    // reroute clears it, take the reroute instead of leaving a
                    // protected gap behind an unavoidable building.
                    boolean preferSafer = (!currentBuildable && candidateBuildable)
                            || (!currentClear && candidateClear);
                    if (preferSafer || betterCoarseCandidate(heightAware, modeRoute, terrain, step)) {
                        LOGGER.info("RoadWeaver accepted height-aware coarse reroute: originalNodes={} reroutedNodes={} forced={} step={}",
                                modeRoute.size(), heightAware.size(), preferSafer, step);
                        modeRoute = heightAware;
                    }
                }
            }
            if (hasTunnelHeightHints(route, modeRoute)) {
                PathTerrainField hintedTerrain = new TunnelHintTerrainField(terrain, route, modeRoute, step);
                // 2026-11-14 修复（审计 NEW-8）：这次隧道提示重搜原先在 HARD 作用域之外
                // 直接调 findRawPath —— 此时 BuildingObstacleMode 已回落到 PENALTY，
                // 硬障碍被全部无视。与主搜索同一口径包进 HARD 作用域。
                final PathResult hintedResult;
                try (StructureAvoidanceService.BuildingObstacleScope tunnelScope =
                             withBuildingObstacleMode(
                                     StructureAvoidanceService.BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS)) {
                    hintedResult = pathfinder.findRawPath(
                            start, end, level, config.aStarMaxSteps(), cache, hintedTerrain, config);
                }
                if (hintedResult.success() && hintedResult.hasRawPath()) {
                    List<BlockPos> rerouted = MountainSwitchbackPlanner.replaceIfSafer(
                            hintedResult.rawPath(), start, end, hintedTerrain, config);
                    if (rerouted != null && rerouted.size() >= 2) {
                        LOGGER.info("RoadWeaver coarse tunnel-aware reroute accepted: originalNodes={} reroutedNodes={} step={}",
                                route.size(), rerouted.size(), step);
                        return applyCoarseTerrainModes(rerouted, terrain, config, routeBridgeMinDepth);
                    }
                }
            }
            return modeRoute;
        }
    }

    /** Depth from the global mode alone (before any route-level AUTO tendency). */
    private static int globalBridgeMinDepth() {
        boolean bridgeFirst = ConfigService.get().planning().terrainProfileMode()
                == PlanningConfig.TerrainProfileMode.BRIDGE_FIRST;
        return bridgeFirst ? 1
                : Math.max(1, ConfigService.get().bridge().minWaterDepth());
    }

    /**
     * Route-level AUTO tendency from the classified terrain shares. Only AUTO
     * switches; when both shares exceed their thresholds the larger share
     * wins, otherwise the balanced baseline applies. Explicit profile modes
     * never produce a route-level override.
     */
    static RouteProfileTendency.Tendency resolveAutoTendency(
            PlanningConfig.TerrainProfileMode mode,
            double mountainFraction,
            double waterFraction,
            double mountainThreshold,
            double waterThreshold) {
        if (mode != PlanningConfig.TerrainProfileMode.AUTO) {
            return RouteProfileTendency.Tendency.BALANCED;
        }
        boolean overMountain = mountainFraction >= mountainThreshold;
        boolean overWater = waterFraction >= waterThreshold;
        if (overMountain && overWater) {
            return mountainFraction >= waterFraction
                    ? RouteProfileTendency.Tendency.TUNNEL_FIRST
                    : RouteProfileTendency.Tendency.BRIDGE_FIRST;
        }
        if (overMountain) return RouteProfileTendency.Tendency.TUNNEL_FIRST;
        if (overWater) return RouteProfileTendency.Tendency.BRIDGE_FIRST;
        return RouteProfileTendency.Tendency.BALANCED;
    }

    /** Share of classified stations that are actual water columns (coast is land). */
    static double waterFraction(Map<RoadTerrainType, Integer> counts) {
        return countedFraction(counts,
                RoadTerrainType.RIVER, RoadTerrainType.LAKE, RoadTerrainType.OCEAN);
    }

    static double mountainFraction(Map<RoadTerrainType, Integer> counts) {
        return countedFraction(counts, RoadTerrainType.MOUNTAIN);
    }

    private static double countedFraction(Map<RoadTerrainType, Integer> counts,
                                          RoadTerrainType... selectedTypes) {
        if (counts == null || counts.isEmpty()) return 0.0D;
        int total = 0;
        int selected = 0;
        for (Map.Entry<RoadTerrainType, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            total += entry.getValue();
            for (RoadTerrainType type : selectedTypes) {
                if (entry.getKey() == type) {
                    selected += entry.getValue();
                    break;
                }
            }
        }
        return total == 0 ? 0.0D : selected / (double) total;
    }

    /** Height-aware coarse search trigger, extracted for the profile-mode regression. */
    static boolean wantsHeightAware(boolean coarseHeightAwareEnabled,
                                    boolean transitionProblem,
                                    boolean tunnelFirst,
                                    boolean mountain) {
        return coarseHeightAwareEnabled && (transitionProblem || (tunnelFirst && mountain));
    }

    private static boolean betterCoarseCandidate(List<BlockPos> candidate,
                                                 List<BlockPos> current,
                                                 PathTerrainField terrain,
                                                 int step) {
        if (candidate == null || candidate.size() < 2 || current == null || current.size() < 2) {
            return false;
        }
        return coarseCandidateScore(candidate, terrain, step)
                < coarseCandidateScore(current, terrain, step) - 1.0D;
    }

    private static double coarseCandidateScore(List<BlockPos> path,
                                               PathTerrainField terrain,
                                               int step) {
        if (path == null || path.size() < 2) return Double.POSITIVE_INFINITY;
        double length = 0.0D;
        double maxGrade = 0.0D;
        int conflicts = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (previous == null || current == null) continue;
            double horizontal = Math.hypot(current.getX() - previous.getX(),
                    current.getZ() - previous.getZ());
            if (horizontal < 0.001D) continue;
            length += horizontal;
            double rise = Math.abs(terrain.height(current.getX(), current.getZ())
                    - terrain.height(previous.getX(), previous.getZ()));
            maxGrade = Math.max(maxGrade, rise / horizontal);
            if (PathfindingHelper.edgeIntersectsPlanningObstacle(
                    previous, current, planningRoadRadius(), step)) {
                conflicts++;
            }
        }
        return length + maxGrade * 900.0D + conflicts * 25_000.0D;
    }

    /**
     * 先按"建筑硬障碍"搜索；无解时自动降级为历史的高代价模式重搜一次。
     *
     * <p>硬障碍让搜索真正绕开已验证建筑（用户要求"建筑该绕行还是得绕行"），
     * 但村庄封死整条走廊时硬障碍会无解，所以必须保留降级兜底。</p>
     *
     * <p><b>2026-11-14（审计 A / NEW-8）更正</b>：这里原本写着「降级后的结果与改动前
     * 完全一致」，现在<b>不再成立</b>，而且那个「一致」本身就是缺陷 ——
     * 降级时 {@code BuildingObstacleMode} 回落到 PENALTY，
     * {@code blocksPlanningObstacle} 直接返回 false ⇒ 障碍场全空、软势恒 0，
     * 等于在无障碍地图上找路（穿村穿楼）。现在降级路径仍有真实的软障碍势，
     * 只是不再硬禁；隧道提示重搜也已纳入同一个 HARD 作用域。</p>
     */
    private static PathResult searchWithHardBuildingObstacles(Pathfinder pathfinder,
                                                              BlockPos start,
                                                              BlockPos end,
                                                              ServerLevel level,
                                                              PathfindingCostConfig config,
                                                              TerrainSamplingCache cache,
                                                              PathTerrainField terrain) {
        PathResult hard;
        try (StructureAvoidanceService.BuildingObstacleScope ignored =
                     withBuildingObstacleMode(
                             StructureAvoidanceService.BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS)) {
            hard = pathfinder.findRawPath(start, end, level, config.aStarMaxSteps(), cache, terrain, config);
        }
        if (hard != null && hard.success() && hard.hasRawPath()) return hard;
        LOGGER.info("RoadWeaver relaxed building hard obstacles after no-route step={} start={} end={}",
                config.effectiveAStarStep(), start, end);
        return pathfinder.findRawPath(start, end, level, config.aStarMaxSteps(), cache, terrain, config);
    }

    private static boolean hasUnbuildableCoarseTransition(List<BlockPos> path, PathTerrainField terrain) {
        if (path == null || terrain == null || path.size() < 2) return false;
        // Same configured overburden as RoadGradePlanner's tunnel core and the
        // same grade ceiling as the coarse A* search, so the coarse viability
        // check cannot disagree with either classification or the accepted slope.
        int tunnelOverburden;
        double maxGradeFraction;
        int surfaceCutMax;
        int surfaceFillMax;
        try {
            var planning = ConfigService.get().planning();
            tunnelOverburden = Math.max(2, Math.min(32, planning.mountainOverburdenBlocks()));
            maxGradeFraction = Math.max(0.1D, Math.min(0.9D, planning.maxGradeFraction()));
            surfaceCutMax = planning.surfaceCutMaxBlocks();
            surfaceFillMax = planning.surfaceFillMaxBlocks();
        } catch (Throwable ignored) {
            tunnelOverburden = 7;
            maxGradeFraction = 0.5D;
            surfaceCutMax = 5;
            surfaceFillMax = 2;
        }
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (previous == null || current == null) continue;
            double horizontal = Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ());
            if (horizontal < 0.001D) continue;
            boolean previousTunnel =
                    terrain.height(previous.getX(), previous.getZ()) - previous.getY() >= tunnelOverburden;
            boolean currentTunnel =
                    terrain.height(current.getX(), current.getZ()) - current.getY() >= tunnelOverburden;
            if (previousTunnel && currentTunnel) continue;
            int terrainHere = terrain.height(current.getX(), current.getZ());
            int cut = terrainHere - current.getY();
            int fill = current.getY() - terrainHere;
            if (cut > surfaceCutMax || fill > surfaceFillMax) return true;
            int rise = Math.abs(current.getY() - previous.getY());
            int allowed = Math.max(2, (int) Math.ceil(horizontal * maxGradeFraction));
            if (rise > allowed) return true;
        }
        return false;
    }

    /**
     * True when any coarse transition crosses a protected structure footprint.
     * Reuses the same planning-road radius as the search cost, so a chosen
     * corridor is not silently left with a protected gap behind a building it
     * could have routed around.
     */
    private static boolean hasCoarseStructureConflict(List<BlockPos> path,
                                                       PathTerrainField terrain,
                                                       int step) {
        if (path == null || path.size() < 2) return false;
        int radius = planningRoadRadius();
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (previous == null || current == null) continue;
            if (PathfindingHelper.edgeIntersectsPlanningObstacle(
                    previous, current, radius, step)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTunnelHeightHints(List<BlockPos> original, List<BlockPos> hinted) {
        if (original == null || hinted == null || original.size() != hinted.size()) return false;
        for (int i = 0; i < original.size(); i++) {
            if (hinted.get(i).getY() + 2 < original.get(i).getY()) return true;
        }
        return false;
    }

    /**
     * Carries an inexpensive mountain decision into the persisted coarse
     * corridor. The coarse field already contains sampled heights; use them
     * to lower sustained mountain humps onto a tunnel chord before exact
     * height resolution. Water columns remain crossing-protected and are not
     * converted into mountain tunnels here.
     */
    private static List<BlockPos> applyCoarseTerrainModes(List<BlockPos> path,
                                                           PathTerrainField terrain,
                                                           PathfindingCostConfig config,
                                                           int bridgeMinWaterDepth) {
        if (path == null || path.size() < 5 || terrain == null || config == null) return path;
        int size = path.size();
        int[] sampledTerrain = new int[size];
        boolean[] crossings = new boolean[size];
        try {
            // Depth is resolved by the caller (global mode + route-level AUTO
            // tendency) so the coarse tunnel precheck cannot disagree with
            // what span extraction will later see for the same route.
            for (int i = 0; i < size; i++) {
                BlockPos point = path.get(i);
                sampledTerrain[i] = terrain.height(point.getX(), point.getZ());
                crossings[i] = terrain.isBridgeWater(
                        point.getX(), point.getZ(), bridgeMinWaterDepth);
            }
            int[] tunnelAwareProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                    sampledTerrain.clone(), sampledTerrain, crossings, true, 2);
            int changed = 0;
            List<BlockPos> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int y = tunnelAwareProfile[i];
                if (y != sampledTerrain[i]) changed++;
                BlockPos point = path.get(i);
                result.add(new BlockPos(point.getX(), y, point.getZ()));
            }
            if (changed > 0) {
                LOGGER.info("RoadWeaver coarse terrain mode precheck: tunnelCandidateStations={} totalStations={} step={}",
                        changed, size, config.effectiveAStarStep());
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            LOGGER.debug("RoadWeaver coarse terrain mode precheck skipped", failure);
            return path;
        }
    }

    private static final class TunnelHintTerrainField implements PathTerrainField {
        private final PathTerrainField delegate;
        private final Map<Long, Integer> hints = new HashMap<>();
        private final int step;

        private TunnelHintTerrainField(PathTerrainField delegate,
                                       List<BlockPos> original,
                                       List<BlockPos> hinted,
                                       int step) {
            this.delegate = delegate;
            this.step = Math.max(1, step);
            for (int i = 0; i < original.size(); i++) {
                BlockPos source = original.get(i);
                BlockPos target = hinted.get(i);
                if (target.getY() + 2 < source.getY()) {
                    hints.put(key(source.getX(), source.getZ()), target.getY());
                }
            }
        }

        @Override public int seaLevel() { return delegate.seaLevel(); }

        @Override public int height(int x, int z) {
            Integer exact = hints.get(key(x, z));
            return exact != null ? exact : delegate.height(x, z);
        }

        @Override public int oceanFloor(int x, int z) { return delegate.oceanFloor(x, z); }
        @Override public boolean isColumnWater(int x, int z) { return delegate.isColumnWater(x, z); }
        @Override public boolean isNearWater(int x, int z, int distance) { return delegate.isNearWater(x, z, distance); }
        @Override public Holder<Biome> biome(int x, int z) { return delegate.biome(x, z); }
        @Override public boolean contains(int x, int z) { return delegate.contains(x, z); }
        @Override public int step() { return step; }
        @Override public boolean hasAccurateSample(int x, int z) { return delegate.hasAccurateSample(x, z); }
        @Override public boolean isWaterBiome(int x, int z) { return delegate.isWaterBiome(x, z); }

        @Override public SampleBundle sampleBundle(int x, int z) {
            SampleBundle base = delegate.sampleBundle(x, z);
            int plannedHeight = height(x, z);
            return new SampleBundle(plannedHeight, base.oceanFloor(), base.columnWater(),
                    base.waterBiome(), base.waterDepth());
        }

        @Override public void dispose() { }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }
}
