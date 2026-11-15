/* 文件职责：路径障碍判定：三种严格度的「这一格能不能过」（已验证建筑 / 规划障碍 / 村庄群），以及诊断用的碰撞盒与描述。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;

// 仍留在其它同包类里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;

/**
 * 规划期的障碍判定：这一列会不会挡住路。
 *
 * <h2>三种口径，对应三档严格度</h2>
 * <ul>
 *   <li>{@code blocksVerifiedBuilding} —— 已验证建筑，最硬；</li>
 *   <li>{@code blocksPlanningObstacle} —— 按 {@code BuildingObstacleMode} 分档：
 *       PENALTY 只罚不禁、HARD_BUILDINGS 硬禁单体、HARD_BUILDINGS_AND_CLUSTERS
 *       连村庄群盒一起硬禁；</li>
 *   <li>{@code blocksVillageCluster} —— 群盒口径，最松但覆盖面最大。</li>
 * </ul>
 *
 * <h2>★ 一个必须记住的历史事实</h2>
 * <p>{@code blocksPlanningObstacle} 在旧实现里<b>只被 BasicAStarPathfinder 读</b>，
 * 势场 / 梯度下降 / 双向 A* / 山地之字全部无条件硬跳过 —— 也就是说
 * {@code BuildingObstacleMode} 在生产配置下曾是<b>失效</b>的。
 * 新仓已补进三个寻路器（见各 pathfinder 的调用点），但这段历史要留着，
 * 免得有人以为「配置了就一定生效」。</p>
 *
 * <p>本类是从 1485 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 判据与阈值一字未改（含缩进）。</p>
 */
public final class ObstacleJudge {
    public static BuildingObstacleMode buildingObstacleMode() {
        return BUILDING_OBSTACLE_MODE.get();
    }

    public static BuildingObstacleScope withBuildingObstacleMode(BuildingObstacleMode mode) {
        BuildingObstacleMode previous = BUILDING_OBSTACLE_MODE.get();
        BUILDING_OBSTACLE_MODE.set(mode == null ? BuildingObstacleMode.PENALTY : mode);
        return () -> BUILDING_OBSTACLE_MODE.set(previous);
    }

    /**
     * 硬障碍查询：该位置是否落在"已验证建筑"的施工带内（端点结构照旧豁免）。
     * 仅在 {@link #buildingObstacleMode()} 非 PENALTY 时有意义。
     * 与 {@link #shouldAvoidPlanning(BlockPos, int)} 同口径（都是严格建筑判定）。
     */
    public static boolean blocksVerifiedBuilding(BlockPos pos, int horizontalClearance) {
        return shouldAvoidPlanning(pos, horizontalClearance);
    }

    /**
     * 硬障碍总判定（供 A* 直接 `continue` 用）：按当前模式检查已验证建筑
     * （HARD_BUILDINGS 及以上）与村庄群聚合盒（HARD_BUILDINGS_AND_CLUSTERS）。
     */
    public static boolean blocksPlanningObstacle(BlockPos pos, int horizontalClearance) {
        if (pos == null) return false;
        BuildingObstacleMode mode = BUILDING_OBSTACLE_MODE.get();
        if (mode == BuildingObstacleMode.PENALTY) return false;
        if (shouldAvoidPlanning(pos, horizontalClearance)) return true;
        return mode == BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS
                && blocksVillageCluster(pos, horizontalClearance);
    }

    /**
     * 村庄群聚合盒判定：同一个结构 owner 的**多栋**已验证建筑合并成一个包围盒。
     * 只用于村庄群（单栋建筑仍走精确盒），端点结构照旧豁免。
     */
    public static boolean blocksVillageCluster(BlockPos pos, int horizontalClearance) {
        if (pos == null) return false;
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        // 2026-11-15：判据从 `== null` 放宽到「null 或 EMPTY」（A 方案）。
        // 返回值不变 —— EMPTY 快照的 blocksCluster 本来就恒 false ——
        // 但省掉一次无意义的空快照查询，也让「没有可用快照」这个意图在代码里显式。
        if (!StructureAvoidanceCache.isUsableSnapshot(snapshot)) return false;
        return snapshot.blocksCluster(pos.getX(), pos.getZ(),
                pos.getY() - CONSTRUCTION_BELOW, pos.getY() + CONSTRUCTION_ABOVE,
                Math.max(0, horizontalClearance));
    }

    /** 至少这么多栋已验证建筑才算"村庄群"，避免把单体结构误当整片。 */
    static final int VILLAGE_CLUSTER_MIN_PIECES = 3;
    static final int CLUSTER_BOX_CACHE_CAP = 16;
    static final ThreadLocal<IdentityHashMap<PlanningSnapshot, Map<StructureOwnerKey, BoundingBox>>>
            CLUSTER_BOX_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);

    /** 按 snapshot 缓存的"村庄群聚合盒"。快照不可变，所以按实例缓存永不失效。 */
    static Map<StructureOwnerKey, BoundingBox> clusterBoxes(PlanningSnapshot snapshot) {
        if (snapshot == null) return Map.of();
        IdentityHashMap<PlanningSnapshot, Map<StructureOwnerKey, BoundingBox>> cache = CLUSTER_BOX_CACHE.get();
        Map<StructureOwnerKey, BoundingBox> cached = cache.get(snapshot);
        if (cached != null) return cached;
        if (cache.size() >= CLUSTER_BOX_CACHE_CAP) cache.clear();
        Map<StructureOwnerKey, BoundingBox> built = buildClusterBoxes(snapshot);
        cache.put(snapshot, built);
        return built;
    }

    static Map<StructureOwnerKey, BoundingBox> buildClusterBoxes(PlanningSnapshot snapshot) {
        // 按 owner 收集**去重后的**建筑盒（同一盒子会出现在多个 chunk 桶里）。
        Map<StructureOwnerKey, Set<OwnedStructureBox>> pieces = new HashMap<>();
        for (List<OwnedStructureBox> list : snapshot.boxesByChunk().values()) {
            if (list == null) continue;
            for (OwnedStructureBox owned : list) {
                if (owned == null || !isVerifiedBuilding(owned)) continue;
                StructureOwnerKey owner = owned.owner();
                if (owner == null || snapshot.ownsEndpoint(owner)) continue;
                if (owned.box() == null) continue;
                pieces.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(owned);
            }
        }
        Map<StructureOwnerKey, BoundingBox> clusters = new HashMap<>();
        for (Map.Entry<StructureOwnerKey, Set<OwnedStructureBox>> entry : pieces.entrySet()) {
            if (entry.getValue().size() < VILLAGE_CLUSTER_MIN_PIECES) continue;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (OwnedStructureBox owned : entry.getValue()) {
                BoundingBox box = owned.box();
                minX = Math.min(minX, box.minX());
                minY = Math.min(minY, box.minY());
                minZ = Math.min(minZ, box.minZ());
                maxX = Math.max(maxX, box.maxX());
                maxY = Math.max(maxY, box.maxY());
                maxZ = Math.max(maxZ, box.maxZ());
            }
            clusters.put(entry.getKey(), new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
        }
        return clusters;
    }

    /** Returns the protected piece rejecting a planning sample without changing planning behavior. */
    public static String describePlanningCollision(BlockPos pos) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        // 2026-11-15（A 方案）：EMPTY 也归入「没有可用快照」。
        // 观测层修正 —— 在此之前 EMPTY 会走到 describeCollision 并返回「没撞」，
        // 与「没有可用快照」同形，正是病根 C 的那句「把没生效误读成没贡献」。
        return StructureAvoidanceCache.isUsableSnapshot(snapshot)
                ? snapshot.describeCollision(pos)
                : "no-active-snapshot";
    }

    /** Describes the first protected sample across a planning edge. */
    public static String describePlanningCollision(BlockPos from, BlockPos to) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        return StructureAvoidanceCache.isUsableSnapshot(snapshot)
                ? snapshot.describeCollision(from, to)
                : "no-active-snapshot";
    }

    /** Returns the protected structure box at a planning conflict, if any. */
    public static BoundingBox planningCollisionBox(BlockPos pos, int horizontalClearance) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        // 2026-11-15（A 方案）：EMPTY 直接返回 null —— 与改前等价（空快照本来就找不到碰撞盒），
        // 但省掉两次空查询，并让「没有可用快照」在代码里显式。
        if (!StructureAvoidanceCache.isUsableSnapshot(snapshot) || pos == null) return null;
        int minY = pos.getY() - CONSTRUCTION_BELOW;
        int maxY = pos.getY() + CONSTRUCTION_ABOVE;
        int radius = Math.max(0, horizontalClearance);
        OwnedStructureBox owned = snapshot.findBuildingCollision(
                pos.getX(), pos.getZ(), minY, maxY, radius);
        if (owned == null) {
            // 修复绕行 no_path：冲突检测走 shouldAvoidPlanning → findCollision
            // (宽泛:含未验证结构/forcedVertical)，而本方法原先只走
            // findBuildingCollision(严格:仅 verified building)。两者不一致
            // 时"检测到冲突却拿不到包围盒"，null box 传给绕行 A* 使搜索失效，
            // 实测 987/1179 次 no_path 由此产生。这里回退到宽泛判定取盒，
            // 保证冲突检测与盒查询口径一致。
            owned = snapshot.findCollision(pos.getX(), pos.getZ(), minY, maxY, radius);
        }
        return owned == null ? null : owned.collisionBox();
    }

    /**
     * Lets endpoint roads enter village open space, then stops immediately
     * before their exact footprint would touch a real building block.
     */
    public static List<RoadSegmentPlacement> stopAtVillageBuilding(ServerLevel level,
                                                                   StructureConnection connection,
                                                                   List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.size() < 3) return segments;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        return snapshot.stopAtVillageBuilding(connection, segments);
    }

    /** Radius used by route search before a concrete RoadData width exists. */
    public static int planningRoadRadius() {
        int width = Math.max(1, ConfigService.get().roadAppearance().roadWidth());
        return Math.max(0, (int) Math.ceil(width / 2.0D));
    }

    /**
     * Structure-to-structure roads are allowed to enter their endpoint
     * structures. If the final construction volume reaches the real building,
     * stop at the last safe station instead of rejecting the whole connection.
     */
    public static List<RoadSegmentPlacement> stopAtEndpointBuilding(ServerLevel level,
                                                                    StructureConnection connection,
                                                                    List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.size() < 3) return segments;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        int firstSafe = 0;
        while (firstSafe < segments.size()
                && snapshot.blocksEndpointBuildingSegmentFootprint(segments.get(firstSafe))) firstSafe++;
        int lastSafe = segments.size() - 1;
        while (lastSafe >= firstSafe
                && snapshot.blocksEndpointBuildingSegmentFootprint(segments.get(lastSafe))) lastSafe--;
        if (firstSafe == 0 && lastSafe == segments.size() - 1) return segments;
        if (firstSafe > lastSafe || lastSafe - firstSafe + 1 < 2) {
            LOGGER.warn("RoadWeaver endpoint building consumed the complete route connection={}->{}",
                    connection.from(), connection.to());
            return null;
        }
        List<RoadSegmentPlacement> clipped = List.copyOf(segments.subList(firstSafe, lastSafe + 1));
        LOGGER.info("RoadWeaver stopped structure endpoint approach at last safe station connection={}->{} "
                        + "segments={} -> {} firstTrim={} lastTrim={}",
                connection.from(), connection.to(), segments.size(), clipped.size(),
                firstSafe, segments.size() - 1 - lastSafe);
        return clipped;
    }
}
