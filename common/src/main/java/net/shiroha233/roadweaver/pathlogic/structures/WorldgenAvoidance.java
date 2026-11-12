/* 文件职责： */
package net.shiroha233.roadweaver.pathlogic.structures;

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
 * 世界生成期的结构避让查询。
 *
 * <h2>它是谁在问</h2>
 * <p>区块生成（worldgen worker）在铺每一个方块前会问一句「这里要不要避开结构」。
 * 这是<b>最热的调用路径</b>：一局开局要问几十万次，且跑在 C2ME 的多个 worker 线程上。</p>
 *
 * <h2>★ 为什么这里绝对不能调 getChunk</h2>
 * <p>世界生成期的区块状态是「半成品」，从 worker 线程去 {@code ServerChunkCache#getChunk}
 * 会死锁或读到不一致的数据。所以只读<b>当前 worldgen region 已经暴露</b>的区块，
 * 拿不到就当「未知」——宁可少避让一次，也不许把生成线程卡住。</p>
 *
 * <h2>分级放行</h2>
 * <p>{@code shouldAvoid} 三档：已验证建筑（必避）/ 施工带（看配置）/ 已准备（精确）。
 * 分档是为了让最热的路径只付最便宜的那档代价。</p>
 *
 * <p>本类是从 1485 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 逻辑未改（含缩进）。</p>
 */
public final class WorldgenAvoidance {
    public static boolean shouldAvoid(WorldGenLevel world, BlockPos pos) {
        if (!ConfigService.get().structureAvoidanceEnabled()) {
            return false;
        }
        if (world == null || pos == null) return false;

        ServerLevel level = world.getLevel();
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return false;

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.asLong(cx, cz);
        ChunkEntry entry = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);

        if (entry == null || !entry.ready) {
            ChunkEntry resolved = resolveWorldgenEntry(world, cx, cz);
            if (resolved != null) {
                synchronized (StructureAvoidanceCache.CHUNK_BBOX_CACHE) {
                    ChunkEntry prepared = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
                    Set<OwnedStructureBox> merged = new HashSet<>(resolved.boxes);
                    if (prepared != null && prepared.ready) merged.addAll(prepared.boxes);
                    entry = new ChunkEntry(Set.copyOf(merged), true);
                    StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, entry);
                }
            }
        }
        if (entry == null || !entry.ready) {
            requestMainThreadPreload(level, cx, cz, key);
            // Do not modify a column whose structure references cannot be
            // resolved from the current worldgen region.  Returning false here
            // used to let the first feature pass cut a structure before the
            // asynchronous cache fill completed.
            return true;
        }
        if (entry.boxes.isEmpty()) return false;

        for (OwnedStructureBox owned : entry.boxes) {
            if (intersectsConstructionBand(owned, pos.getX(), pos.getZ(), pos.getY() - CONSTRUCTION_BELOW,
                    pos.getY() + CONSTRUCTION_ABOVE)) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldAvoidAny(WorldGenLevel world, Iterable<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (shouldAvoid(world, pos)) {
                return true;
            }
        }
        return false;
    }

    /** Construction-time check using the same endpoint StructureStart ownership as planning. */
    public static boolean shouldAvoidConstruction(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (!ConfigService.get().structureAvoidanceEnabled() || world == null || pos == null) return false;
        ServerLevel level = world.getLevel();
        ChunkEntry entry = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry == null || !entry.ready) return shouldAvoid(world, pos);
        Set<StructureOwnerKey> endpointOwners = endpointOwnersForRoad(level, road);
        for (OwnedStructureBox owned : entry.boxes) {
            if (endpointOwners.contains(owned.owner())) continue;
            if (isVerifiedBuilding(owned)
                    && intersectsConstructionBand(owned, pos.getX(), pos.getZ(),
                    pos.getY() - CONSTRUCTION_BELOW, pos.getY() + CONSTRUCTION_ABOVE)) return true;
        }
        return false;
    }

    /** Structure-only lookup for route-wide tunnel approval; never touches chunks. */
    public static boolean shouldAvoidPrepared(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (!ConfigService.get().structureAvoidanceEnabled() || world == null || pos == null) return false;
        ChunkEntry entry = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry == null || !entry.ready || entry.boxes.isEmpty()) return false;
        Set<StructureOwnerKey> endpointOwners = endpointOwnersForRoad(world.getLevel(), road);
        for (OwnedStructureBox owned : entry.boxes) {
            if (endpointOwners.contains(owned.owner())) continue;
            if (intersectsConstructionBand(owned, pos.getX(), pos.getZ(),
                    pos.getY() - CONSTRUCTION_BELOW, pos.getY() + CONSTRUCTION_ABOVE)) return true;
        }
        return false;
    }

    /** Exact construction-volume lookup with endpoint ownership, without a vertical safety band. */
    public static boolean shouldAvoidPreparedExact(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (!ConfigService.get().structureAvoidanceEnabled() || world == null || pos == null) return false;
        ChunkEntry entry = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (entry == null || !entry.ready || entry.boxes.isEmpty()) return false;
        Set<StructureOwnerKey> endpointOwners = endpointOwnersForRoad(world.getLevel(), road);
        for (OwnedStructureBox owned : entry.boxes) {
            if (endpointOwners.contains(owned.owner())) continue;
            if (isVerifiedBuilding(owned)
                    && owned.intersects(pos.getX(), pos.getZ(), pos.getY(), pos.getY(), 0)) return true;
        }
        return false;
    }

    /**
     * Returns true only when the planning snapshot has decoded actual template
     * blocks at this position.  A fallback bounding box is deliberately not
     * treated as a confirmed building: it must not create a construction gap
     * without a second, exact structure resolution.
     */
    public static boolean isVerifiedPreparedStructureBlock(WorldGenLevel world,
                                                            BlockPos pos,
                                                            RoadData road) {
        if (world == null || pos == null || road == null
                || !ConfigService.get().structureAvoidanceEnabled()) return false;
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(world.getLevel(), road));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) return false;
        OwnedStructureBox owned = snapshot.findBuildingCollision(
                pos.getX(), pos.getZ(), pos.getY(), pos.getY(), 0);
        return owned != null && "template-blocks".equals(owned.occupancyKind());
    }

    static boolean isVerifiedBuilding(OwnedStructureBox owned) {
        return owned != null && "template-blocks".equals(owned.occupancyKind());
    }

    public static void invalidate(ServerLevel level, int cx, int cz) {
        if (level == null) return;
        StructureAvoidanceCache.CHUNK_BBOX_CACHE.remove(ChunkPos.asLong(cx, cz));
    }

    public static void clearAll() {
        StructureAvoidanceCache.CHUNK_BBOX_CACHE.clear();
        StructureAvoidanceCache.PLANNING_SNAPSHOTS.clear();
        StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.clear();
        StructureAvoidanceCache.PREPARED_START_BOXES.clear();
        StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.clear();
        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.clear();
        StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER.clear();
        StructureAvoidanceCache.PREPARED_START_ORDER.clear();
        StructureAvoidanceCache.OBSTACLE_TILE_ORDER.clear();
        StructureAvoidanceCache.IN_FLIGHT_STARTS.values().forEach(f -> f.cancel(true));
        StructureAvoidanceCache.IN_FLIGHT_STARTS.clear();
        StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.values().forEach(f -> f.cancel(true));
        StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.clear();
        StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.values().forEach(f -> f.cancel(true));
        StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.clear();
        StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.values().forEach(f -> f.cancel(true));
        StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.clear();
        StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.set(false);
        StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.set(0);
        ExecutorService nativeExecutor = StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.getAndSet(null);
        if (nativeExecutor != null) nativeExecutor.shutdownNow();
        StructureAvoidanceCache.UNSUPPORTED_PIECE_CLASSES.clear();
        StructureAvoidanceDiagnostics.PENDING_REQUESTS.set(0L);
        StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
        StructureAvoidanceCache.STRUCTURE_SELECTION_RANDOM.remove();
        PieceOccupancy.clearResolveDiagnostics();
    }

    public static void discardPlanningSnapshot(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) return;
        RouteKey key = routeKey(level, connection);
        StructureAvoidanceCache.PLANNING_SNAPSHOTS.remove(key);
        StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER.remove(key);
        StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.remove(key);
        CompletableFuture<Void> inFlight = StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.remove(key);
        if (inFlight != null) inFlight.cancel(true);
    }

    public static void prepareConstructionChunk(WorldGenLevel world, ChunkPos chunkPos) {
        if (world == null || chunkPos == null || !ConfigService.get().structureAvoidanceEnabled()) return;
        if (!Level.OVERWORLD.equals(world.getLevel().dimension())) return;
        ChunkEntry resolved = resolveWorldgenEntry(world, chunkPos.x, chunkPos.z);
        if (resolved == null) return;
        long key = chunkPos.toLong();
        synchronized (StructureAvoidanceCache.CHUNK_BBOX_CACHE) {
            ChunkEntry existing = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
            Set<OwnedStructureBox> merged = new HashSet<>(resolved.boxes);
            if (existing != null && existing.ready) merged.addAll(existing.boxes);
            StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Set.copyOf(merged), true));
        }
    }

    /**
     * Planning has already indexed every known structure box for this route.
     * Mark the exact construction corridor as resolved so feature workers do
     * not treat an ordinary empty chunk as an unresolved structure query.
     */
    public static void markRoadCorridorPrepared(List<RoadSegmentPlacement> segments) {
        if (segments == null) return;
        synchronized (StructureAvoidanceCache.CHUNK_BBOX_CACHE) {
            for (RoadSegmentPlacement segment : segments) {
                if (segment == null) continue;
                markPrepared(segment.middlePos());
                if (segment.positions() != null) for (BlockPos pos : segment.positions()) markPrepared(pos);
            }
        }
    }

    static void markPrepared(BlockPos pos) {
        if (pos == null) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkEntry existing = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
        if (existing == null || !existing.ready) {
            StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Collections.emptySet(), true));
        }
    }

    /** Reads only chunks already exposed by the current worldgen region. */
    static ChunkEntry resolveWorldgenEntry(WorldGenLevel world, int cx, int cz) {
        try {
            if (!(world.getLevel() instanceof ServerLevel level)) return null;
            // This method is reachable from C2ME/worldgen workers. Never call
            // ServerChunkCache#getChunk here: even with generate=false it may
            // join an off-thread chunk future and deadlock against worldgen.
            ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) return null;
            Set<OwnedStructureBox> boxes = new HashSet<>();
            for (Map.Entry<Structure, StructureStart> start : chunk.getAllStarts().entrySet()) {
                collectPieces(world.getLevel(), levelOwner(world.getLevel(), start.getKey(), chunk.getPos().toLong()),
                        start.getValue(), boxes);
            }
            for (Map.Entry<Structure, LongSet> reference : chunk.getAllReferences().entrySet()) {
                for (long startChunkLong : reference.getValue()) {
                    ChunkPos startPos = new ChunkPos(startChunkLong);
                    ChunkAccess startChunk = level.getChunkSource().getChunkNow(
                            startPos.x, startPos.z);
                    if (startChunk == null) return null;
                    collectPieces(world.getLevel(), levelOwner(world.getLevel(), reference.getKey(), startChunkLong),
                            startChunk.getStartForStructure(reference.getKey()), boxes);
                }
            }
            return new ChunkEntry(Set.copyOf(boxes), true);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static void collectPieces(ServerLevel level,
                                      StructureOwnerKey owner,
                                      StructureStart start,
                                      Collection<OwnedStructureBox> boxes) {
        if (start == null || !start.isValid()) return;
        for (StructurePiece piece : start.getPieces()) {
            boxes.add(new OwnedStructureBox(owner, piece.getBoundingBox(), PieceOccupancy.forPiece(level, piece)));
        }
    }
}
