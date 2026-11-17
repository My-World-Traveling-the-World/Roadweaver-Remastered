/* 文件职责：端点剖面 —— 端点日志、共用接近剖面、单侧/优先挖边界坡度。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
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
import net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightRegistry;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.metrics.RoadQualityMetrics;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureRoadOffsetService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadPlanConsistencyService;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathlogic.core.Road.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadCrossingOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps.*;

/**
 * 端点剖面。
 *
 * <h2>它在收什么口</h2>
 * <p>路的两个端点必须落到目标高度上，而落法有两种：**单侧让坡**（只改一侧）
 * 与**共用接近剖面**（两侧一起改，用于交汇）。选错会让端点出现明显折角。</p>
 *
 * <h2>★ 「优先挖」与「优先填」的区别</h2>
 * <p>同样要降 3 格，挖是把高点削掉、填是把低点垫起。山坡上挖便宜，
 * 水边填会灌水，所以两者不能混用。</p>
 *
 * <p>本类是从 1639 行的 {@code Road} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class EndpointProfileOps {
    public static void logEndpointProfile(String stage,
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
        LOGGER.info("【端点纵断面高度与锁高差异】 " + "RoadWeaver endpoint profile stage={} connection={}->{} stations={} "
                        + "profileStart={} terrainStart={} lockedStart={} startDelta={} "
                        + "profileEnd={} terrainEnd={} lockedEnd={} endDelta={}",
                stage,
                connection == null ? "?" : connection.from(),
                connection == null ? "?" : connection.to(),
                stations,
                profile[0], startTerrain, startBoundary, profile[0] - startTerrain,
                profile[last], endTerrain, endBoundary, profile[last] - endTerrain);
    }


    /** 村庄边界固定地面高程；普通道路路口不套用该锁高，以免产生陡坡。
 *
     * Locks one road endpoint to a village-boundary height and ramps toward the
     * interior. Non-village endpoints (spawn, ordinary road junctions) are never
     * locked, so their grade stays on the normal smooth slope instead of being
     * forced into a steep ramp.
     */
    /** 按端点朝路内方向复制已协商的共享剖面，避免每条来路独立定入口坡。 */
    public static void applySharedApproachProfile(int[] profile,
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

    /** 固定入口站并在有限半路窗口内接坡，避免改动另一端入口。 */
    public static void applySingleBoundaryGrade(int[] profile,
                                                 int endpoint,
                                                 int boundaryHeight) {
        if (profile == null || endpoint < 0 || endpoint >= profile.length) return;
        profile[endpoint] = boundaryHeight;
        int half = Math.max(1, Math.min(96, (profile.length - 1) / 2));
        constrainEndpointRamp(profile, endpoint, endpoint == 0 ? 1 : -1, half);
    }

    /** 未知端点按约定高度只降不升接近段，高侧降到入口，避免补出高填方。
 *
     * Unknown-endpoint variant of the boundary lock: pins the endpoint to the
     * agreed height and descends the approach onto it with
     * prefer-cut semantics — the high side is pressed down onto the locked
     * height one block per station, and stations below the ramp line are
     * never raised. When the chosen height is below the structure surface
     * this is exactly "the high side comes down to meet the road".
     */
    public static void applyPreferCutBoundaryGrade(int[] profile,
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

    /** 入口边界使用独立精确列采样，避免预取插值或建筑屋顶高度使入口悬浮。
 *
     * Returns the real terrain surface height at the boundary crossing point,
     * sampled as a dedicated one-column precise query. The route's bulk
     * footprint batch often reuses a prefetched/interpolated height; this pins
     * the anchor to the actual ground the road meets so it cannot float above
     * or dive below the structure edge.
     */
    public static int crossingPreciseHeight(AccurateHeightSampler accurate,
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

    /** 裁切后首尾站才是结构穿越锚点，不可用建筑中心屋顶代替道路接地位置。
 *
     * The road↔structure crossing anchor: the route's own first/last station
     * once the path has been trimmed at the structure boundary (village and
     * other known-structure endpoints stop outside the built area), falling
     * back to the connection endpoint when the route still reaches it. The
     * structure center itself must not be used as the height anchor: its
     * motion-blocking column tops out on the building roof, which locked
     * endpoints dozens of blocks above the terrain the road actually joins.
     */
    public static BlockPos boundaryCrossingAnchor(List<BlockPos> centers,
                                                    BlockPos connectionEndpoint,
                                                    boolean start) {
        if (centers == null || centers.isEmpty()) return connectionEndpoint;
        BlockPos station = start ? centers.get(0) : centers.get(centers.size() - 1);
        return station != null ? station : connectionEndpoint;
    }
}
