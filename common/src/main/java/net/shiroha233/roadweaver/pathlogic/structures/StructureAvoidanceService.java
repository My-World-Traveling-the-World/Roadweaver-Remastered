/* 文件职责：缓存已加载区块的结构边界，让路面铺设 worker 线程无需同步触发主线程 chunk 加载。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
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
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import net.shiroha233.roadweaver.search.StructurePredictor;
import net.shiroha233.roadweaver.compat.SinglePoolElementTemplateAccess;
import net.shiroha233.roadweaver.compat.StructureTemplatePaletteAccess;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.shiroha233.roadweaver.pathlogic.core.Road;

public final class StructureAvoidanceService {
    private StructureAvoidanceService() {}

    /**
     * Buildings belonging to the selected village endpoint can be emitted as
     * separate verified pieces. Keep the endpoint protection group bounded so
     * those pieces do not become ordinary mid-route obstacles.
     */
    private static final int ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS = 96;
    private static final int ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS = 32;

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int CONSTRUCTION_BELOW = 2;
    private static final int CONSTRUCTION_ABOVE = 6;
    /**
     * T-35: 过路村庄掩码的 Y 相交窗口下方容忍。村庄聚合盒的 minY 是最低建筑
     * 底部,不是村庄地面;山坡村庄的空地比最低建筑低可达十余格。道路穿过这些
     * 空地时施工带([y-2, y+6])与聚合盒不相交,掩码不点亮,道路整片穿过村庄。
     * 放宽下方容忍使"修在村庄 XZ 内且未深埋"的站点全部成缺口。
     */
    private static final int VILLAGE_GAP_BELOW_TOLERANCE = 16;
    private static final int PLANNING_CORRIDOR_RADIUS = 192;
    /** 路径走廊结构 start 采集半径（chunk）。与 PLANNING_CORRIDOR_RADIUS
     *  (192 格 = 12 chunk) 对齐：旧值 8（+margin=9 chunk=144 格）小于规划走廊，
     *  U 型弯路绕开的沿途村庄 structure start chunk 距路径 >9 chunk 时被漏采，
     *  导致 T-23 过路村庄避让失效、道路切村。 */
    private static final int STRUCTURE_START_REACH_CHUNKS = 11;
    private static final int PATH_CORRIDOR_MARGIN_CHUNKS = 1;
    private static final int AVOIDANCE_PREDICTION_TILE_CHUNKS = 16;
    private static final int STRUCTURE_START_BATCH_SIZE = 16;
    /** Runtime keeps a small window; startup may use the prepared structure lanes. */
    private static final int STRUCTURE_START_BATCH_WINDOW = 2;
    private static final int MAX_PENDING_REQUESTS = 32;
    private static final TicketType<Unit> STRUCTURE_SNAPSHOT_TICKET =
            TicketType.create("roadweaver_structure_snapshot", (left, right) -> 0);
    private static final int PLANNING_SNAPSHOT_CACHE_CAPACITY = 2048;
    private static final int PREPARED_START_CACHE_CAPACITY = 32768;
    /** Preserve the old cached world area after reducing prediction tiles from 64x64 to 16x16. */
    private static final int OBSTACLE_TILE_CACHE_CAPACITY = 8192;
    /** Maximum displacement between a selected structure origin and its actual replacement start. */
    private static final int ENDPOINT_REPLACEMENT_MATCH_BLOCKS = 24;
    /**
     * A generated template piece can sit several blocks inside the selected
     * structure origin. Keep this local and require a unique verified owner;
     * this is not the broad candidate/replacement radius.
     */
    private static final int ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS = 16;
    /** 关掉探针的口子（默认开）：探针每起点多一次群系取样，量完可以关。 */
    private static final String BIOME_PROBE_PROPERTY = "roadweaver.biomePrefilterProbe";

    static final class ChunkEntry {
        final Set<OwnedStructureBox> boxes;
        final boolean ready;
        ChunkEntry(Set<OwnedStructureBox> boxes, boolean ready) {
            this.boxes = boxes;
            this.ready = ready;
        }
        static final ChunkEntry UNREADY = new ChunkEntry(Collections.emptySet(), false);
    }

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

    private static boolean isVerifiedBuilding(OwnedStructureBox owned) {
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

    private static void markPrepared(BlockPos pos) {
        if (pos == null) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkEntry existing = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
        if (existing == null || !existing.ready) {
            StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Collections.emptySet(), true));
        }
    }

    /** Reads only chunks already exposed by the current worldgen region. */
    private static ChunkEntry resolveWorldgenEntry(WorldGenLevel world, int cx, int cz) {
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

    private static void collectPieces(ServerLevel level,
                                      StructureOwnerKey owner,
                                      StructureStart start,
                                      Collection<OwnedStructureBox> boxes) {
        if (start == null || !start.isValid()) return;
        for (StructurePiece piece : start.getPieces()) {
            boxes.add(new OwnedStructureBox(owner, piece.getBoundingBox(), PieceOccupancy.forPiece(level, piece)));
        }
    }

    /**
     * 0.9.059（①）：{@code terrainSnapshotPrepMs} 的子段计时（纯观测，<b>零行为</b>）。
     *
     * <p>真机一局 {@code terrainSnapshotPrepMs=72,670}（占 terrainPlanning 的 61%，也是
     * async planning 的最大单项），而它内部一直只有一个总数。这里把同一个时间窗口按
     * <b>串行、互不重叠</b>的段落拆开，逐段累加到本对象，最后由
     * {@code RoadPlanningService} 打一行 {@code [PLAN-SNAPSHOT-BREAKDOWN]}。</p>
     *
     * <h2>四段的真实归属（照代码写，不按名字猜）</h2>
     * <ul>
     *   <li>{@code tileSampleMs} —— {@code predictObstacleCandidates(...)}（本文件 2252 行）。
     *       它按 {@code AVOIDANCE_PREDICTION_TILE_CHUNKS=16} 个区块为一格瓦片，把走廊
     *       覆盖到的瓦片一次性预测出结构候选（{@code StructurePredictor.predictAvoidanceStructuresInRect}）。
     *       <b>注意口径</b>：这是"结构预测瓦片"，不是地形采样；地形采样在
     *       {@code terrainCoreMs} 里（{@code TERRAIN_PLANNING.plan}）。</li>
     *   <li>{@code structureOverlayMs} —— {@code resolvePreparedStartsAsync(...)} 整段
     *       （逐起点解析精确结构盒 = 结构占用叠加），也就是真机
     *       {@code resolutionMs=64,709} 的那一段。</li>
     *   <li>{@code cacheWriteMs} —— 解析结果并进 {@code StructureAvoidanceCache.PLANNING_SNAPSHOTS} +
     *       {@code indexConstructionBoxes} + {@code putBounded} 的收尾段。</li>
     *   <li>{@code corridorSnapshotMs} —— <b>本窗口恒为 0</b>：真机口径的
     *       {@code preparePathCorridorSnapshotsNow}(833 行) <b>不在这条调用路径上</b>，
     *       它由 {@code preparePlanningSnapshotsForPathsAsync} 在地形规划之后调用，
     *       记在 {@code structureSnapshotsMs}(18,167) 里。这里如实写 0，不硬塞。</li>
     *   <li>{@code serverInitMs} / {@code startKeyMs} —— 额外两段：前者是服务器线程上
     *       取结构快照与计数器的前缀，后者是逐连接枚举候选起点的循环。</li>
     * </ul>
     *
     * <p>{@code threads=} 是这段窗口里真实观察到的 worker 线程名个数，{@code columns=} 是
     * 结构起点解析提交的并发列宽（{@code STRUCTURE_START_BATCH_SIZE × windowBatches}）——
     * 两个数一起用来判断"是不是单线程在扛"。</p>
     */
    public static final class PrepareTiming {
        private final long startedAt = System.nanoTime();
        private final java.util.Set<String> workerThreads =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile boolean ran;
        private volatile long serverInitNanos;
        private volatile long tileSampleNanos;
        private volatile long startKeyNanos;
        private volatile long structureOverlayNanos;
        private volatile long cacheWriteNanos;
        private volatile int routes;
        private volatile int starts;
        private volatile int resolvedBoxes;
        private volatile int columns;
        private volatile int obstacleTiles;

        public boolean ran() {
            return ran;
        }

        void markRan() {
            ran = true;
        }

        void addServerInit(long nanos) {
            serverInitNanos += Math.max(0L, nanos);
        }

        void addTileSample(long nanos) {
            tileSampleNanos += Math.max(0L, nanos);
        }

        void addStartKeys(long nanos) {
            startKeyNanos += Math.max(0L, nanos);
        }

        void addStructureOverlay(long nanos) {
            structureOverlayNanos += Math.max(0L, nanos);
        }

        void addCacheWrite(long nanos) {
            cacheWriteNanos += Math.max(0L, nanos);
        }

        void setRoutes(int value) {
            routes = value;
        }

        void setStarts(int value) {
            starts = value;
        }

        void setResolvedBoxes(int value) {
            resolvedBoxes = value;
        }

        void setColumns(int value) {
            columns = value;
        }

        void setObstacleTiles(int value) {
            obstacleTiles = value;
        }

        void observeWorkerThread() {
            workerThreads.add(Thread.currentThread().getName());
        }

        /** 四段（+两段附加）之和，纳秒。用于自证"没有重复累加"。 */
        public long segmentNanos() {
            return serverInitNanos + tileSampleNanos + startKeyNanos
                    + structureOverlayNanos + cacheWriteNanos;
        }

        /** 窗口总时长（纳秒），自本对象创建起算。 */
        public long totalNanos() {
            return Math.max(0L, System.nanoTime() - startedAt);
        }

        /**
         * 子段之和 ≤ 父段。纯函数，单测直接钉 —— 计时重复累加会立刻违反它。
         */
        public static boolean segmentsWithinTotal(long parentNanos, long... segments) {
            if (segments == null) return parentNanos >= 0L;
            long sum = 0L;
            for (long segment : segments) {
                sum += Math.max(0L, segment);
            }
            return sum <= Math.max(0L, parentNanos);
        }

        /** {@code [PLAN-SNAPSHOT-BREAKDOWN]} 一行的字段串（不含前缀）。 */
        public String describe() {
            long total = totalNanos();
            return "totalMs=" + total / 1_000_000L
                    + " tileSampleMs=" + tileSampleNanos / 1_000_000L
                    + " corridorSnapshotMs=0"
                    + " structureOverlayMs=" + structureOverlayNanos / 1_000_000L
                    + " cacheWriteMs=" + cacheWriteNanos / 1_000_000L
                    + " serverInitMs=" + serverInitNanos / 1_000_000L
                    + " startKeyMs=" + startKeyNanos / 1_000_000L
                    + " segmentsMs=" + segmentNanos() / 1_000_000L
                    + " threads=" + workerThreads.size()
                    + " columns=" + columns
                    + " routes=" + routes
                    + " starts=" + starts
                    + " resolvedBoxes=" + resolvedBoxes
                    + " obstacleTiles=" + obstacleTiles
                    + " corridorNote=corridor-snapshot-not-on-this-path";
        }
    }

    /**
     * Captures real structure-piece boxes on the server thread before a route
     * is searched by workers. Selected structures remain connection nodes;
     * every other same-dimension structure candidate near the route is only
     * an obstacle. Starts load no further than STRUCTURE_STARTS, so workers
     * never schedule a server task or request a chunk while pathfinding.
     */
    public static CompletableFuture<Void> preparePlanningSnapshotsAsync(
            ServerLevel level, Collection<StructureConnection> connections) {
        return preparePlanningSnapshotsAsync(level, connections, null);
    }

    /** 0.9.059（①）：带子段计时的同名入口；{@code timing} 为 null 时行为与旧入口逐位相同。 */
    public static CompletableFuture<Void> preparePlanningSnapshotsAsync(
            ServerLevel level, Collection<StructureConnection> connections, PrepareTiming timing) {
        if (level == null || connections == null || connections.isEmpty()
                || !Level.OVERWORLD.equals(level.dimension())
                || !ConfigService.get().structureAvoidanceEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        List<StructureConnection> connectionSnapshot = List.copyOf(connections);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                preparePlanningSnapshotsOnServerThread(level, connectionSnapshot, timing)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) result.complete(null);
                            else result.completeExceptionally(failure);
                        });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (server.isSameThread()) task.run();
        else server.execute(task);
        return result;
    }

    /**
     * Resolves exact structure starts only for chunks that can reach the
     * already-computed road paths. Terrain pathfinding therefore remains
     * unchanged and no endpoint-to-endpoint scan is performed.
     */
    public static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level, Map<StructureConnection, List<BlockPos>> paths) {
        return preparePlanningSnapshotsForPathsAsync(level, paths, true, null);
    }

    public static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            ThreadPoolManager.TaskRole requestedRole) {
        return preparePlanningSnapshotsForPathsAsync(level, paths, true, requestedRole);
    }

    /** Incremental corridor refresh used after grade or structure rerouting. */
    public static CompletableFuture<Void> ensurePlanningSnapshotForPathAsync(
            ServerLevel level, StructureConnection connection, List<BlockPos> path) {
        if (connection == null || path == null || path.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return preparePlanningSnapshotsForPathsAsync(
                level, Map.of(connection, List.copyOf(path)), false,
                ThreadPoolManager.TaskRole.SNAPSHOT);
    }

    /**
     * Loads structure starts covering a local conflict circle. The synthetic
     * probe path is used only to enumerate chunks; it is never persisted as a
     * road path. This keeps local building discovery independent from the
     * terrain sampling mask and guarantees that every chunk intersecting the
     * radius-100 repair area is inspected before rerouting.
     */
    /**
     * Upper bound on probe chunks for one conflict-circle snapshot prediction.
     * A radius-200 circle probes ~490 chunks and monopolized the snapshot lane
     * when local detours escalated through every radius step.
     */
    public static final int MAX_CIRCLE_PROBE_CHUNKS = 256;

    /**
     * Largest radius whose circle probe set stays within
     * {@link #MAX_CIRCLE_PROBE_CHUNKS} (probes ≈ π·r²/256, matching the chunk
     * centre enumeration below).
     */
    public static int clampCircleProbeRadius(int requestedRadius) {
        int radius = Math.max(1, requestedRadius);
        while (radius > 16
                && (long) (Math.PI * radius * radius / 256.0D) > MAX_CIRCLE_PROBE_CHUNKS) {
            radius -= 16;
        }
        return radius;
    }

    public static CompletableFuture<Void> ensurePlanningSnapshotForCircleAsync(
            ServerLevel level,
            StructureConnection connection,
            BlockPos center,
            int radius) {
        if (level == null || connection == null || center == null || radius <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        int safeRadius = Math.max(1, radius);
        int effectiveRadius = clampCircleProbeRadius(safeRadius);
        if (effectiveRadius < safeRadius) {
            LOGGER.info("RoadWeaver circle snapshot radius clamped by probe budget: requested={} effective={} maxProbeChunks={}",
                    safeRadius, effectiveRadius, MAX_CIRCLE_PROBE_CHUNKS);
        }
        int minChunkX = Math.floorDiv(center.getX() - effectiveRadius, 16);
        int maxChunkX = Math.floorDiv(center.getX() + effectiveRadius, 16);
        int minChunkZ = Math.floorDiv(center.getZ() - effectiveRadius, 16);
        int maxChunkZ = Math.floorDiv(center.getZ() + effectiveRadius, 16);
        long radiusSquared = (long) effectiveRadius * effectiveRadius;
        List<BlockPos> probes = new ArrayList<>();
        probes.add(center);
        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                int x = cx * 16 + 8;
                int z = cz * 16 + 8;
                long dx = (long) x - center.getX();
                long dz = (long) z - center.getZ();
                if (dx * dx + dz * dz <= radiusSquared + 256L) {
                    probes.add(new BlockPos(x, center.getY(), z));
                }
            }
        }
        LOGGER.info("RoadWeaver local structure circle snapshot: center=({}, {}) radius={} probeChunks={}",
                center.getX(), center.getZ(), effectiveRadius, probes.size());
        return preparePlanningSnapshotsForPathsAsync(
                level, Map.of(connection, List.copyOf(probes)), false,
                ThreadPoolManager.TaskRole.SNAPSHOT);
    }

    private static CompletableFuture<Void> preparePlanningSnapshotsForPathsAsync(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (level == null || paths == null || paths.isEmpty()
                || !Level.OVERWORLD.equals(level.dimension())
                || !ConfigService.get().structureAvoidanceEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        var server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        Map<StructureConnection, List<BlockPos>> pathSnapshot = new LinkedHashMap<>();
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            pathSnapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        if (pathSnapshot.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            CompletableFuture<Void> source = preparePathCorridorSnapshots(
                    level,
                    pathSnapshot,
                    trackInitialProgress && InitialGenManager.isActive(),
                    requestedRole);
            source.whenComplete((ignored, failure) -> {
                        if (failure == null) result.complete(null);
                        else result.completeExceptionally(failure);
                    });
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) source.cancel(true);
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static CompletableFuture<Void> preparePathCorridorSnapshots(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (trackInitialProgress) {
            return preparePathCorridorSnapshotsNow(level, paths, true, requestedRole);
        }
        List<CompletableFuture<Void>> routeFutures = new ArrayList<>(paths.size());
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            StructureConnection connection = entry.getKey();
            List<BlockPos> path = entry.getValue();
            if (connection == null || path == null || path.isEmpty()) continue;
            RouteKey routeKey = routeKey(level, connection);
            AtomicReference<CompletableFuture<Void>> createdRef = new AtomicReference<>();
            StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.compute(routeKey, (ignored, existing) -> {
                CompletableFuture<Void> predecessor = existing == null
                        ? CompletableFuture.completedFuture(null)
                        : existing.handle((completed, failure) -> null);
                CompletableFuture<Void> created = predecessor.thenCompose(completed ->
                        preparePathCorridorSnapshotWithPermit(
                                level,
                                Map.of(connection, List.copyOf(path)),
                                trackInitialProgress,
                                requestedRole));
                createdRef.set(created);
                return created;
            });
            CompletableFuture<Void> created = createdRef.get();
            created.whenComplete((completed, failure) ->
                    StructureAvoidanceCache.IN_FLIGHT_ROUTE_CORRIDORS.remove(routeKey, created));
            routeFutures.add(created);
        }
        return routeFutures.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(routeFutures.toArray(CompletableFuture[]::new));
    }

    private static CompletableFuture<Void> preparePathCorridorSnapshotWithPermit(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        if (trackInitialProgress) {
            return preparePathCorridorSnapshotsNow(level, paths, true, requestedRole);
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        return CompletableFuture.runAsync(() -> {
            try {
                StructureAvoidanceCache.RUNTIME_CORRIDOR_PERMITS.acquire();
                acquired.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interrupted);
            }
        }).thenCompose(ignored ->
                preparePathCorridorSnapshotsNow(level, paths, false, requestedRole))
                .whenComplete((completed, failure) -> {
                    if (acquired.get()) StructureAvoidanceCache.RUNTIME_CORRIDOR_PERMITS.release();
                });
    }

    private static CompletableFuture<Void> preparePathCorridorSnapshotsNow(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole requestedRole) {
        long startedAt = System.nanoTime();
        long epoch = ThreadPoolManager.currentEpoch();
        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        ThreadPoolManager.TaskRole predictionRole = trackInitialProgress
                ? ThreadPoolManager.TaskRole.INITIAL
                : requestedRole == null ? ThreadPoolManager.TaskRole.STRUCTURE : requestedRole;
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "predicting_road_corridor_structures");
        }

        return ThreadPoolManager.supplyAsync(predictionRole, () -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                throw new CancellationException("RoadWeaver world changed before corridor prediction");
            }
            Map<RouteKey, PathCorridorPlan> corridors = buildPathCorridors(level, paths, true);
            Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> obstacleCandidates =
                    predictObstacleCandidatesForCorridors(level, corridors, trackInitialProgress);
            Map<RouteKey, RouteStartPlan> routePlans = new LinkedHashMap<>();
            Set<PreparedStartKey> uniqueStarts = new LinkedHashSet<>();
            int reusedStarts = 0;
            for (Map.Entry<RouteKey, PathCorridorPlan> entry : corridors.entrySet()) {
                PathCorridorPlan corridor = entry.getValue();
                Set<PreparedStartKey> collected = collectCorridorStartKeys(
                        level,
                        storageSnapshot,
                        corridor.candidateChunks(),
                        corridor.path(),
                        obstacleCandidates.getOrDefault(entry.getKey(), List.of()));
                PlanningSnapshot previous = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(entry.getKey());
                Set<PreparedStartKey> starts = new LinkedHashSet<>(collected);
                if (previous != null && !previous.resolvedStarts().isEmpty()) {
                    int before = starts.size();
                    starts.removeAll(previous.resolvedStarts());
                    reusedStarts += before - starts.size();
                }
                DetailedRoadGenerationStats.recordCorridorNewStarts(starts.size());
                routePlans.put(entry.getKey(), new RouteStartPlan(
                        corridor.connection(), starts, corridor.candidateChunks()));
                uniqueStarts.addAll(starts);
            }
            if (reusedStarts > 0) {
                LOGGER.info("RoadWeaver path corridor reused exact structure starts: routes={} reused={} newlyRequired={}",
                        routePlans.size(), reusedStarts, uniqueStarts.size());
            }
            return new SnapshotPreparation(routePlans, List.copyOf(uniqueStarts));
        }).thenCompose(preparation -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                return CompletableFuture.failedFuture(
                        new CancellationException("RoadWeaver world changed before corridor start resolution"));
            }
            if (trackInitialProgress) {
                InitialGenerationProgressTracker.setPlanningStartPlan(
                        preparation.uniqueStarts().size(), "loading_road_corridor_structure_starts");
            }
            ThreadPoolManager.TaskRole resolutionRole = trackInitialProgress
                    ? ThreadPoolManager.TaskRole.INITIAL
                    : requestedRole == null ? ThreadPoolManager.TaskRole.STRUCTURE : requestedRole;
            return resolvePreparedStartsAsync(
                    level, preparation.uniqueStarts(), trackInitialProgress, resolutionRole)
                    .thenAccept(resolvedStarts -> {
                        if (!ThreadPoolManager.isEpoch(epoch)) return;
                        int mergedBoxes = 0;
                        for (Map.Entry<RouteKey, RouteStartPlan> entry : preparation.routePlans().entrySet()) {
                            Set<OwnedStructureBox> boxes = new HashSet<>();
                            for (PreparedStartKey start : entry.getValue().starts()) {
                                List<OwnedStructureBox> prepared = resolvedStarts.get(start);
                                if (prepared != null) boxes.addAll(prepared);
                            }
                            RouteKey key = entry.getKey();
                            indexConstructionBoxes(boxes);
                            AtomicReference<PlanningSnapshot> previousRef = new AtomicReference<>();
                            AtomicReference<PlanningSnapshot> mergedRef = new AtomicReference<>();
                            StructureAvoidanceCache.PLANNING_SNAPSHOTS.compute(key, (ignored, current) -> {
                                previousRef.set(current);
                                Set<OwnedStructureBox> allBoxes = new HashSet<>();
                                if (current != null) allBoxes.addAll(current.boxes());
                                allBoxes.addAll(boxes);
                                Set<StructureOwnerKey> endpointOwners = resolveEndpointOwners(
                                        entry.getValue().connection(), storageSnapshot, allBoxes);
                                PlanningSnapshot mergedSnapshot = PlanningSnapshot.merge(
                                        entry.getValue().connection(), current, boxes,
                                        entry.getValue().starts(), entry.getValue().coveredCandidateChunks(),
                                        endpointOwners);
                                mergedRef.set(mergedSnapshot);
                                return mergedSnapshot;
                            });
                            PlanningSnapshot previous = previousRef.get();
                            PlanningSnapshot merged = mergedRef.get();
                            mergedBoxes += Math.max(0, merged.boxes().size()
                                    - (previous == null ? 0 : previous.boxes().size()));
                            StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(key, merged.endpointOwners());
                            if (previous == null) {
                                StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER.add(key);
                                trimBounded(StructureAvoidanceCache.PLANNING_SNAPSHOTS, StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER,
                                        PLANNING_SNAPSHOT_CACHE_CAPACITY);
                            }
                        }
                        if (trackInitialProgress) {
                            InitialGenerationProgressTracker.completePlanningSnapshots(
                                    "road_corridor_structures_ready");
                        }
                        LOGGER.info("RoadWeaver path corridor snapshots: dimension={} routes={} uniqueStarts={} newlyMergedBoxes={} totalMs={}",
                                level.dimension().location(), preparation.routePlans().size(),
                                preparation.uniqueStarts().size(), mergedBoxes, elapsedMillis(startedAt));
                    });
        });
    }

    /** 规划前过滤结果：kept 保留参与规划，filtered 被标记为不参与规划/施工/局部修复。 */
    public record EndpointFilterOutcome(List<StructureConnection> kept,
                                        List<StructureConnection> filtered,
                                        boolean heldBack,
                                        String heldBackReason) {
        public int filteredCount() {
            return filtered == null ? 0 : filtered.size();
        }
    }

    /** 一轮过滤超过这个条数就整体放弃（只告警），避免一次砍掉太多路。 */
    private static final int UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND = 4;

    // ==================== E 阶段：结构起点预筛 ====================

    /** 预筛开关：放置数学判定失败的候选不再进入精确解析。 */
    private static final AtomicBoolean STRUCTURE_PREFILTER_ENABLED = new AtomicBoolean(true);
    /** 预筛统计：被跳过的候选数 / 判定为可能的候选数。 */
    private static final AtomicLong PREFILTERED_CANDIDATES = new AtomicLong(0);
    private static final AtomicLong PREFILTER_KEPT_CANDIDATES = new AtomicLong(0);
    /** 一致性护栏是否已经跑过（每次进程一次，失败即永久关闭预筛）。 */
    private static final AtomicBoolean PREFILTER_GUARD_RUN = new AtomicBoolean(false);
    /** 护栏最多核对多少个"被跳过"的候选（全量双跑代价太大）。 */
    private static final int PREFILTER_GUARD_SAMPLE = 64;

    /**
     * E 阶段：放置数学预筛。
     *
     * <p>用 {@link RandomSpreadStructurePlacement#getPotentialStructureChunk(long, int, int)}
     * 判断候选区块是否可能是该结构集的起点：候选区块必须正好等于它所属 region 的
     * 潜在起点区块。不等则 {@code isStructureChunk} 必然为 false（
     * {@code isPlacementChunk} 就是这条判定），精确解析一定返回空盒子，可以直接跳过。</p>
     *
     * <p>非随机散布的放置类型（ConcentricRings / 自定义）一律<b>保守放行</b>，
     * 不做数学判定。</p>
     */
    private static boolean isPotentialStructureChunk(ServerLevel level,
                                                      Holder<StructureSet> structureSetHolder,
                                                      int chunkX, int chunkZ) {
        if (!STRUCTURE_PREFILTER_ENABLED.get()) return true;
        if (level == null || structureSetHolder == null) return true;
        try {
            if (!ConfigService.get().planning().prefilterStructureStarts()) return true;
        } catch (Throwable ignored) {
            return true;
        }
        StructurePlacement placement = structureSetHolder.value().placement();
        if (!(placement instanceof RandomSpreadStructurePlacement randomSpread)) return true;
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        ChunkPos potential = randomSpread.getPotentialStructureChunk(seed, chunkX, chunkZ);
        return potential.x == chunkX && potential.z == chunkZ;
    }

    /**
     * E 阶段护栏：预筛前后 exactPieceBoxes 必须一致。
     *
     * <p>做法：对同一批候选起点分别按"预筛后"和"不预筛"精确解析一遍，比较盒子数量与
     * owner 集合。不一致就<b>自动关闭预筛</b>并打警告
     * {@code structure prefilter disabled: box count mismatch}，宁可慢，不可丢村庄。</p>
     *
     * <p>只在进程内第一次解析时跑一遍（{@link #PREFILTER_GUARD_RUN}），失败后永久关闭，
     * 不进入常规路径。</p>
     */
    private static void verifyPrefilterExactness(ServerLevel level, List<PreparedStartKey> starts) {
        if (!STRUCTURE_PREFILTER_ENABLED.get() || starts == null || starts.isEmpty()) return;
        if (!PREFILTER_GUARD_RUN.compareAndSet(false, true)) return;
        try {
            List<PreparedStartKey> skipped = new ArrayList<>();
            for (PreparedStartKey start : starts) {
                if (start.source() != StructureFileStorage.SOURCE_PREDICTED) continue;
                if (start.structureSet() == null) continue;
                ChunkPos pos = new ChunkPos(start.chunkKey());
                if (!isPotentialStructureChunk(level, start.structureSet(), pos.x, pos.z)) {
                    skipped.add(start);
                }
            }
            if (skipped.isEmpty()) {
                LOGGER.info("RoadWeaver structure prefilter guard: skipped=0 checked=0 boxes=0 verdict=match");
                return;
            }
            // 只核对"被预筛跳过"的候选：预筛的风险正是"跳掉了本来有盒子的候选"。
            // 全量双跑代价太大，这里按固定采样核对，任何一次出现盒子就判不一致。
            int checked = 0;
            long boxes = 0L;
            for (PreparedStartKey start : skipped) {
                if (checked >= PREFILTER_GUARD_SAMPLE) break;
                checked++;
                boxes += resolvePredictedStructureSetBoxes(
                        level, new ChunkPos(start.chunkKey()), start.structureSet()).size();
            }
            boolean match = boxes == 0L;
            LOGGER.info("RoadWeaver structure prefilter guard: skipped={} checked={} boxes={} verdict={}",
                    skipped.size(), checked, boxes, match ? "match" : "mismatch");
            if (!match) {
                STRUCTURE_PREFILTER_ENABLED.set(false);
                LOGGER.warn("structure prefilter disabled: box count mismatch skipped={} checked={} boxes={}",
                        skipped.size(), checked, boxes);
            }
        } catch (Throwable failure) {
            STRUCTURE_PREFILTER_ENABLED.set(false);
            LOGGER.warn("structure prefilter disabled: box count mismatch (guard failed: {})", failure.toString());
        }
    }

    /**
     * B 阶段：规划前过滤"村庄 V ↔ V 结构盒内的未知端点"。
     *
     * <p>判定（两条同时成立才过滤）：</p>
     * <ol>
     *   <li>连接某一端点是<b>未知端点</b>——既不在结构库的已核验结构位点里，
     *       也无法从附近已核验村庄建筑推断出 owner；</li>
     *   <li>该端点 XZ 落在<b>某个村庄 V 的结构聚合盒</b>内，且另一端点恰好就是
     *       那个村庄 V。</li>
     * </ol>
     *
     * <p>动作：该连接不进规划、不进施工、不进局部修复。</p>
     *
     * <p>安全阀：一轮过滤超过 {@value #UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND} 条，
     * 或过滤后某端点变成 0 连接，则整体放弃过滤（只告警），宁可不优化也不砍路。</p>
     *
     * <p>0.8.315：距离不是判据。早先复用 {@code ENDPOINT_REPLACEMENT_MATCH_BLOCKS}(24)
     * 限制两端点距离，导致大村庄（聚合盒 60+ 格）的"村庄 ↔ 盒内未知端点"永远不触发。
     * 现在只看"未知端点在该村庄聚合盒内 + 另一端点就是该村庄"，两条安全阀兜底。</p>
     */
    public static EndpointFilterOutcome filterUnknownEndpointInsideVillage(
            ServerLevel level, List<StructureConnection> connections) {
        if (level == null || connections == null || connections.isEmpty()) {
            return new EndpointFilterOutcome(connections == null ? List.of() : connections, List.of(), false, "");
        }
        try {
            if (!ConfigService.get().planning().filterUnknownEndpointInsideVillage()) {
                return new EndpointFilterOutcome(connections, List.of(), false, "");
            }
        } catch (Throwable ignored) {
            return new EndpointFilterOutcome(connections, List.of(), false, "");
        }

        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        List<StructureInfo> knownInfos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        List<StructureConnection> kept = new ArrayList<>(connections.size());
        List<StructureConnection> filtered = new ArrayList<>();
        // 0.8.331：两端点都在同一个村庄盒内的"村内连接"（真机 village_iberian
        // (-864,752)↔(-813,716)，14 格）不算"这个村庄的路"，因此不参与安全阀二。
        // 否则阀门会把它保下来，而它必然规划失败 → 每局固定 failed=1。
        Set<StructureConnection> villageInternal = new HashSet<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            RouteKey key = routeKey(level, connection);
            PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
            if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
                kept.add(connection);
                continue;
            }
            boolean fromUnknown = isUnknownEndpoint(level, connection.from(), storageSnapshot, snapshot);
            boolean toUnknown = isUnknownEndpoint(level, connection.to(), storageSnapshot, snapshot);
            StructureConnection matched = null;
            // 0.8.315：不再要求两端点距离 ≤ ENDPOINT_REPLACEMENT_MATCH_BLOCKS(24)。
            // villageOwnerAt(unknown) 已经证明未知端点落在该村庄的建筑聚合盒内；距离不提供
            // 额外信息，而大村庄的盒子动辄 60+ 格（真机 village_iberian：(-864,752)↔(-813,716)
            // 相距 62.4 格），24 格闸门会让这条过滤对大村庄永远失效。
            //
            // 0.8.317：另一端必须**确实就是那个村庄**。旧代码用 ownsEndpoint(village)，
            // 而 endpointOwners 是两个端点 owner 的并集——未知端点自己也会被
            // "从附近已核验建筑推断 owner"（真机日志 inferred endpoint owner ... (-813,716)
            // owner=village_iberian）而进入该集合。于是**别的村庄**通往这个未知点的正常路
            // （真机 (-1104,304)->(-813,716)、(-480,480)->(-813,716)，两条都是 COMPLETED）
            // 也会被判为"村庄 V ↔ V 盒内未知点"而被过滤。现在改为按已核验结构位点核对
            // 另一端点的区块与结构 id，必须与村庄 owner 完全一致。
            if (fromUnknown && !toUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.from().getX(), connection.from().getZ());
                if (village != null && endpointMatchesStructure(connection.to(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.from(), connection.to(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.to().getX(), connection.to().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            } else if (toUnknown && !fromUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.to().getX(), connection.to().getZ());
                if (village != null && endpointMatchesStructure(connection.from(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.to(), connection.from(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.from().getX(), connection.from().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            }
            if (matched == null) kept.add(connection);
            else filtered.add(matched);
        }
        if (filtered.isEmpty()) {
            return new EndpointFilterOutcome(kept, List.of(), false, "");
        }
        // 安全阀一：一轮过滤太多就整体放弃。
        if (filtered.size() > UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=too_many_per_round",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "too_many_per_round");
        }
        // 安全阀二：过滤后某端点变成 0 连接就整体放弃。村内连接（两端同一村庄盒）
        // 不参与该判定 —— 它本来就不是"这个村庄通往别处的路"。
        if (leavesEndpointWithoutConnection(kept, filtered, villageInternal)) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=endpoint_left_without_connection",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "endpoint_left_without_connection");
        }
        return new EndpointFilterOutcome(kept, List.copyOf(filtered), false, "");
    }

    private static void logFilteredVillageEndpoint(StructureOwnerKey village,
                                                   BlockPos unknown,
                                                   BlockPos villageEndpoint,
                                                   StructureConnection connection) {
        LOGGER.info("RoadWeaver route filtered: village {} owns endpoint, unknown endpoint inside its box "
                        + "village={} unknown={} connection={}->{}",
                village.structureId(), villageEndpoint, unknown, connection.from(), connection.to());
    }

    /**
     * 端点是否"未知"：不在已核验结构位点。
     *
     * <p>0.8.312 修正：旧实现还要求 {@code villageOwnerAt(endpoint) == null}，于是
     * "落在村庄盒内的未知端点"——正是本过滤要处理的那一类——被判成"已知"，
     * 过滤器因此永不触发（真机 0.8.311 日志 {@code filtered=0}，失败路仍在）。
     * 未知的判定只看它是不是已核验结构位点；是否落在村庄盒内由调用方单独判定。</p>
     */
    private static boolean isUnknownEndpoint(ServerLevel level,
                                              BlockPos endpoint,
                                              StructureFileStorage.StructureSnapshot storageSnapshot,
                                              PlanningSnapshot snapshot) {
        if (endpoint == null) return false;
        List<StructureInfo> infos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) return false;
        }
        return true;
    }

    /**
     * 0.8.317：端点是否**就是**该 owner 对应的已核验结构位点。
     *
     * <p>比 {@code snapshot.ownsEndpoint(owner)} 严格：后者是"两个端点 owner 的并集里
     * 有它就返回 true"，而未知端点自己也会因为"从附近建筑推断 owner"而进入并集，于是
     * 别的村庄通往同一个未知点的正常路会被误判。这里直接核对端点区块与结构 id。</p>
     */
    private static boolean endpointMatchesStructure(BlockPos endpoint,
                                                     StructureOwnerKey owner,
                                                     List<StructureInfo> knownInfos) {
        if (endpoint == null || owner == null || knownInfos == null || knownInfos.isEmpty()) return false;
        if (ChunkPos.asLong(endpoint.getX() >> 4, endpoint.getZ() >> 4) != owner.startChunkKey()) return false;
        for (StructureInfo info : knownInfos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() != endpoint.getX() || info.pos().getZ() != endpoint.getZ()) continue;
            return owner.structureId().equals(info.structureId());
        }
        return false;
    }

    /** 过滤后是否有端点从"有连接"变成"0 连接"。 */
    private static boolean leavesEndpointWithoutConnection(List<StructureConnection> kept,
                                                           List<StructureConnection> filtered,
                                                           Set<StructureConnection> exempt) {
        Map<Long, Integer> keptCounts = new HashMap<>();
        for (StructureConnection connection : kept) {
            if (connection == null) continue;
            keptCounts.merge(PlanningUtils.pos2dKey(connection.from()), 1, Integer::sum);
            keptCounts.merge(PlanningUtils.pos2dKey(connection.to()), 1, Integer::sum);
        }
        for (StructureConnection connection : filtered) {
            if (connection == null) continue;
            if (exempt != null && exempt.contains(connection)) continue;
            for (BlockPos endpoint : List.of(connection.from(), connection.to())) {
                if (endpoint == null) continue;
                if (keptCounts.getOrDefault(PlanningUtils.pos2dKey(endpoint), 0) == 0) return true;
            }
        }
        return false;
    }

    private static CompletableFuture<Void> preparePlanningSnapshotsOnServerThread(
            ServerLevel level, Collection<StructureConnection> connections,
            PrepareTiming timing) {
        var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Structure snapshots must be submitted on the server thread"));
        }
        if (timing != null) timing.markRan();

        long snapshotStartedAt = System.nanoTime();
        long predictedBefore = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get();
        long manualBefore = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get();
        long cacheBefore = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get();
        long emptyBefore = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get();
        long boxesBefore = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get();
        long tilesBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get();
        long predictionChunksBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get();
        long tileCacheBefore = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get();
        long candidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get();
        long corridorCandidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get();
        long directStartsBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get();
        long directFallbacksBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get();
        boolean trackInitialProgress = InitialGenManager.isActive();
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "predicting_structure_obstacles");
        }
        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        long epoch = ThreadPoolManager.currentEpoch();
        ThreadPoolManager.TaskRole predictionRole = trackInitialProgress
                ? ThreadPoolManager.TaskRole.INITIAL
                : ThreadPoolManager.TaskRole.STRUCTURE;
        // 0.9.059（①）：服务器线程上的前缀段（取快照 + 取 epoch），不计入下面任何子段。
        if (timing != null) timing.addServerInit(System.nanoTime() - snapshotStartedAt);
        return ThreadPoolManager.supplyAsync(predictionRole, () -> {
            long predictionStartedAt = System.nanoTime();
            if (!ThreadPoolManager.isEpoch(epoch)) {
                throw new java.util.concurrent.CancellationException("RoadWeaver world changed before structure prediction");
            }
            long tileSampleStartedAt = System.nanoTime();
            Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> obstacleCandidates =
                    predictObstacleCandidates(level, connections);
            if (timing != null) timing.addTileSample(System.nanoTime() - tileSampleStartedAt);
            long startKeyStartedAt = System.nanoTime();
            Map<RouteKey, RouteStartPlan> routePlans = new LinkedHashMap<>();
            Set<PreparedStartKey> uniqueStarts = new LinkedHashSet<>();
            for (StructureConnection connection : connections) {
                if (connection == null) continue;
                RouteKey key = routeKey(level, connection);
                if (StructureAvoidanceCache.PLANNING_SNAPSHOTS.containsKey(key)) continue;
                Set<PreparedStartKey> starts = collectPlanningStartKeys(
                        level, connection, storageSnapshot, obstacleCandidates.getOrDefault(key, List.of()));
                routePlans.put(key, new RouteStartPlan(connection, starts, Set.of()));
                uniqueStarts.addAll(starts);
            }
            if (timing != null) {
                timing.addStartKeys(System.nanoTime() - startKeyStartedAt);
                timing.setRoutes(routePlans.size());
                timing.setStarts(uniqueStarts.size());
            }
            SnapshotPreparation preparation = new SnapshotPreparation(routePlans, List.copyOf(uniqueStarts));
            LOGGER.debug("RoadWeaver structure prediction timing: dimension={} routes={} uniqueStarts={} elapsedMs={}",
                    level.dimension().location(), routePlans.size(), uniqueStarts.size(),
                    elapsedMillis(predictionStartedAt));
            return preparation;
        }).thenCompose(preparation -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                        "RoadWeaver world changed before structure snapshot loading"));
            }
            if (trackInitialProgress) {
                InitialGenerationProgressTracker.setPlanningStartPlan(
                        preparation.uniqueStarts().size(), "loading_exact_structure_starts");
            }
            long resolutionStartedAt = System.nanoTime();
            return resolvePreparedStartsAsync(level, preparation.uniqueStarts(), trackInitialProgress, timing)
                    .thenAccept(resolvedStarts -> {
                if (!ThreadPoolManager.isEpoch(epoch)) return;
                // 0.9.059（①）：结构占用叠加段 = 精确起点解析整段。
                if (timing != null) timing.addStructureOverlay(System.nanoTime() - resolutionStartedAt);
                long cacheWriteStartedAt = System.nanoTime();
                for (Map.Entry<RouteKey, RouteStartPlan> entry : preparation.routePlans().entrySet()) {
                    Set<OwnedStructureBox> boxes = new HashSet<>();
                    for (PreparedStartKey start : entry.getValue().starts()) {
                        List<OwnedStructureBox> prepared = resolvedStarts.get(start);
                        if (prepared != null) boxes.addAll(prepared);
                    }
                    indexConstructionBoxes(boxes);
                    Set<StructureOwnerKey> endpointOwners = resolveEndpointOwners(
                            entry.getValue().connection(), storageSnapshot, boxes);
                    PlanningSnapshot snapshot = PlanningSnapshot.forRoute(
                            entry.getValue().connection(), boxes, entry.getValue().starts(), Set.of(), endpointOwners);
                    StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(entry.getKey(), snapshot.endpointOwners());
                    putBounded(StructureAvoidanceCache.PLANNING_SNAPSHOTS, StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER, entry.getKey(), snapshot,
                            PLANNING_SNAPSHOT_CACHE_CAPACITY);
                }
                if (trackInitialProgress) {
                    InitialGenerationProgressTracker.completePlanningSnapshots("structure_snapshots_ready");
                }
                // 0.9.059（①）：写缓存段 = 合并进 StructureAvoidanceCache.PLANNING_SNAPSHOTS + 施工盒索引 + 有界淘汰。
                if (timing != null) {
                    timing.addCacheWrite(System.nanoTime() - cacheWriteStartedAt);
                    timing.observeWorkerThread();
                }

                long predicted = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get() - predictedBefore;
                long manual = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get() - manualBefore;
                long cacheHits = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get() - cacheBefore;
                long empty = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get() - emptyBefore;
                long boxes = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get() - boxesBefore;
                long tiles = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get() - tilesBefore;
                long predictionChunks = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get() - predictionChunksBefore;
                long tileCacheHits = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get() - tileCacheBefore;
                long candidates = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get() - candidatesBefore;
                long corridorCandidates = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get() - corridorCandidatesBefore;
                long directStarts = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get() - directStartsBefore;
                long directFallbacks = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get() - directFallbacksBefore;
                if (timing != null) {
                    timing.setObstacleTiles((int) Math.min(Integer.MAX_VALUE, tiles));
                    timing.setResolvedBoxes((int) Math.min(Integer.MAX_VALUE, boxes));
                }
                if (predicted + manual + cacheHits + tiles + tileCacheHits > 0L) {
                    LOGGER.info("RoadWeaver structure snapshots: obstacleTiles={} predictionChunks={} obstacleTileCacheHits={} obstacleCandidates={} corridorCandidates={} predictedDirect={} nativeDirectStarts={} realChunkFallbacks={} manualReferenceFallback={} cacheHits={} emptyResults={} exactPieceBoxes={} prefilterSkipped={} prefilterKept={} prefilterEnabled={}",
                            tiles, predictionChunks, tileCacheHits, candidates, corridorCandidates,
                            predicted, directStarts, directFallbacks,
                            manual, cacheHits, empty, boxes,
                            PREFILTERED_CANDIDATES.get(), PREFILTER_KEPT_CANDIDATES.get(),
                            STRUCTURE_PREFILTER_ENABLED.get());
                }
                LOGGER.info("RoadWeaver structure snapshot timing: dimension={} routes={} uniqueStarts={} resolutionMs={} totalMs={}",
                        level.dimension().location(), preparation.routePlans().size(),
                        preparation.uniqueStarts().size(), elapsedMillis(resolutionStartedAt),
                        elapsedMillis(snapshotStartedAt));
                // 0.9.034（R-07 计数模式）：累计值（进程内），不改任何结果。
                // wouldRejectNonEmpty 就是「开群系预筛会丢掉的真实结构数」—— 必须是 0 才算可证明安全。
                LOGGER.info("RoadWeaver structure biome-prefilter probe (count only, results unchanged): "
                                + "probed={} wouldReject={} wouldRejectNonEmpty={} emptyResults={}",
                        StructureAvoidanceDiagnostics.BIOME_PROBE_PROBED.get(), StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED.get(),
                        StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED_NONEMPTY.get(), empty);
            });
        });
    }

    /** Activates the immutable snapshot for the current pathfinding worker. */
    public static PlanningScope activatePlanningSnapshot(ServerLevel level, StructureConnection connection) {
        return activatePlanningSnapshot(level, connection, 0);
    }

    /**
     * Activates the immutable route snapshot with a temporary extra footprint
     * radius. This is used only after exact post-processing proves that the
     * rasterized road is one column wider than the center-path approximation.
     */
    public static PlanningScope activatePlanningSnapshot(ServerLevel level,
                                                         StructureConnection connection,
                                                         int additionalRoadRadius) {
        PlanningSnapshot previous = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        PlanningSnapshot selected = level == null || connection == null
                ? PlanningSnapshot.EMPTY
                : StructureAvoidanceCache.PLANNING_SNAPSHOTS.getOrDefault(routeKey(level, connection), PlanningSnapshot.EMPTY);
        if (additionalRoadRadius > 0) {
            selected = selected.withAdditionalRoadRadius(additionalRoadRadius);
        }
        StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(selected);
        return () -> {
            if (previous == null) StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
            else StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(previous);
        };
    }

    /** Returns true only for a verified structure piece intersecting the road construction band. */
    public static boolean shouldAvoidPlanning(BlockPos pos) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        return snapshot != null && snapshot.blocks(pos);
    }

    /** Checks a planning position with an explicit horizontal safety margin. */
    public static boolean shouldAvoidPlanning(BlockPos pos, int horizontalClearance) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        return snapshot != null && snapshot.blocks(pos, Math.max(0, horizontalClearance));
    }

    /**
     * 规划期建筑障碍强度。
     *
     * <p>{@link #PENALTY} 是历史行为：已验证建筑在 A* 里只是高代价，可以穿过，
     * 剩下的冲突交给局部绕行/保护缺口兜底。{@link #HARD_BUILDINGS} 下 A* 不允许
     * 穿过已验证建筑；{@link #HARD_BUILDINGS_AND_CLUSTERS} 还会把"村庄群"
     * （同一结构的多栋建筑聚合成一个包围盒）整片挡掉。</p>
     *
     * <p>硬障碍必须配合"搜索失败自动降级"使用，否则可能整条路无解。</p>
     */
    /** 已打过「端点 owner 重映射/推断」日志的端点（0.9.023 / N-01 同族去重）。 */
    private static final java.util.Set<String> ENDPOINT_OWNER_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public enum BuildingObstacleMode { PENALTY, HARD_BUILDINGS, HARD_BUILDINGS_AND_CLUSTERS }

    private static final ThreadLocal<BuildingObstacleMode> BUILDING_OBSTACLE_MODE =
            ThreadLocal.withInitial(() -> BuildingObstacleMode.PENALTY);

    /** 可 try-with-resources 的作用域句柄。 */
    public interface BuildingObstacleScope extends AutoCloseable {
        @Override
        void close();
    }

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
        if (snapshot == null) return false;
        return snapshot.blocksCluster(pos.getX(), pos.getZ(),
                pos.getY() - CONSTRUCTION_BELOW, pos.getY() + CONSTRUCTION_ABOVE,
                Math.max(0, horizontalClearance));
    }

    /** 至少这么多栋已验证建筑才算"村庄群"，避免把单体结构误当整片。 */
    private static final int VILLAGE_CLUSTER_MIN_PIECES = 3;
    private static final int CLUSTER_BOX_CACHE_CAP = 16;
    private static final ThreadLocal<IdentityHashMap<PlanningSnapshot, Map<StructureOwnerKey, BoundingBox>>>
            CLUSTER_BOX_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);

    /** 按 snapshot 缓存的"村庄群聚合盒"。快照不可变，所以按实例缓存永不失效。 */
    private static Map<StructureOwnerKey, BoundingBox> clusterBoxes(PlanningSnapshot snapshot) {
        if (snapshot == null) return Map.of();
        IdentityHashMap<PlanningSnapshot, Map<StructureOwnerKey, BoundingBox>> cache = CLUSTER_BOX_CACHE.get();
        Map<StructureOwnerKey, BoundingBox> cached = cache.get(snapshot);
        if (cached != null) return cached;
        if (cache.size() >= CLUSTER_BOX_CACHE_CAP) cache.clear();
        Map<StructureOwnerKey, BoundingBox> built = buildClusterBoxes(snapshot);
        cache.put(snapshot, built);
        return built;
    }

    private static Map<StructureOwnerKey, BoundingBox> buildClusterBoxes(PlanningSnapshot snapshot) {
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
        return snapshot == null ? "no-active-snapshot" : snapshot.describeCollision(pos);
    }

    /** Describes the first protected sample across a planning edge. */
    public static String describePlanningCollision(BlockPos from, BlockPos to) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        return snapshot == null ? "no-active-snapshot" : snapshot.describeCollision(from, to);
    }

    /** Returns the protected structure box at a planning conflict, if any. */
    public static BoundingBox planningCollisionBox(BlockPos pos, int horizontalClearance) {
        PlanningSnapshot snapshot = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        if (snapshot == null || pos == null) return null;
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

    /** Validates spline/raster post-processing against the same immutable obstacle snapshot. */
    public static boolean intersectsPreparedRoute(ServerLevel level,
                                                  StructureConnection connection,
                                                  List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.isEmpty()) return false;
        if (!ConfigService.get().structureAvoidanceEnabled()) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        for (RoadSegmentPlacement segment : segments) {
            if (snapshot.blocksSegmentFootprint(segment)) return true;
        }
        return false;
    }

    /** True when a route intersects only decoded structure blocks, not unknown fallback boxes. */
    public static boolean intersectsPreparedRouteWithUnresolvedStructure(
            ServerLevel level,
            StructureConnection connection,
            List<RoadSegmentPlacement> segments) {
        // A bounding-box-only prediction is metadata, not a building. It must
        // never reject an otherwise valid route or trigger topology fallback.
        return false;
    }

    /**
     * A verified-building collision may be retained as a bounded, explicit
     * construction gap. The route remains valid only when both sides have
     * enough usable road and the gap is a small fraction of the route.
     */
    public static boolean isProtectedGapAcceptable(ServerLevel level,
                                                    StructureConnection connection,
                                                    List<BlockPos> path) {
        return hasAcceptableBuildingGaps(level, connection, path);
    }

    /** Returns persisted gap spans for a finalized road, if all gaps are valid. */
    public static List<RoadSpan> protectedGapSpans(ServerLevel level,
                                                    StructureConnection connection,
                                                    List<RoadSegmentPlacement> segments) {
        if (level == null || connection == null || segments == null || segments.size() < 3) return List.of();
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        boolean[] collisions = new boolean[segments.size()];
        // T-23: 过路村庄聚合盒分段裁剪 —— 非端点村庄(如 dark_forest 这类仅被
        // 巨路穿过、并非本连接端点的村庄)聚合盒内的站点落为 PROTECTED_GAP,
        // 施工器据此跳过密度 stamp 与装饰,消除切村、路灯与反复重试。
        // 端点村庄被显式排除:它们由 stopAtVillageBuilding 的着陆逻辑处理,
        // 其盒永远不产生缺口。开关关闭时掩码全 false,行为与旧版一致。
        boolean[] passThroughVillage = snapshot.passThroughVillageGapMask(
                segments.stream().map(segment -> segment == null ? null : segment.middlePos()).toList());
        double[] distance = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            collisions[i] = segment != null
                    && (snapshot.blocksSegmentFootprint(segment) || passThroughVillage[i]);
            if (i > 0 && segment != null && segments.get(i - 1) != null) {
                distance[i] = distance[i - 1] + Math.hypot(
                        segment.middlePos().getX() - segments.get(i - 1).middlePos().getX(),
                        segment.middlePos().getZ() - segments.get(i - 1).middlePos().getZ());
            }
        }
        double total = distance[distance.length - 1];
        if (total <= 0.0D) return List.of();
        List<RoadSpan> result = new ArrayList<>();
        for (int i = 0; i < collisions.length;) {
            if (!collisions[i]) { i++; continue; }
            int start = i;
            while (i + 1 < collisions.length && collisions[i + 1]) i++;
            int end = i;
            double gap = Math.max(1.0D, distance[end] - distance[start]);
            // Endpoint structures are valid targets too. Leave the occupied
            // endpoint stations unpaved so the road stops at the building
            // edge instead of cutting through the target structure. A route
            // fully consumed by one building is still unusable.
            if (start == 0 && end == collisions.length - 1
                    || distance[start] < 0.0D
                    || total - distance[end] < 0.0D) return List.of();
            BlockPos from = segments.get(start).middlePos();
            BlockPos to = segments.get(end).middlePos();
            if (from != null && to != null) result.add(new RoadSpan(from, to, SpanType.PROTECTED_GAP));
            i++;
        }
        return List.copyOf(result);
    }

    /**
     * T-23.2: SharedEndpointPathReuse 合并完成后重新执行的过路村庄检查入口。
     * 返回 path 上落入非端点村庄聚合盒的站点数;快照不可用或开关关闭时返回 -1
     * (调用方据此跳过检查,finalize 阶段的缺口计算仍会在快照就绪后执行)。
     */
    public static int passThroughVillageCrossings(ServerLevel level,
                                                  StructureConnection connection,
                                                  List<BlockPos> path) {
        if (level == null || connection == null || path == null || path.isEmpty()) return 0;
        try {
            if (!ConfigService.get().planning().passThroughVillageClipping()) return -1;
        } catch (Throwable ignored) {
            return -1;
        }
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
                || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return -1;
        boolean[] mask = snapshot.passThroughVillageGapMask(path);
        int crossings = 0;
        for (boolean value : mask) if (value) crossings++;
        return crossings;
    }

    /**
     * A gap at the approach to a selected endpoint is an intentional road
     * termination at the building edge. It must not make the whole connection
     * eligible for topology replacement. Internal gaps retain the existing
     * candidate fallback behavior.
     */
    public static boolean hasOnlyEndpointProtectedGaps(ServerLevel level,
                                                        StructureConnection connection,
                                                        RoadData road) {
        if (road == null || connection == null || !road.hasProtectedGap()
                || road.roadSegmentList() == null || road.roadSegmentList().size() < 2) {
            return false;
        }
        BlockPos first = road.roadSegmentList().getFirst().middlePos();
        BlockPos last = road.roadSegmentList().getLast().middlePos();
        if (first == null || last == null) return false;
        return endpointOnlyProtectedGapPattern(
                List.of(first, last), road.spans());
    }

    static boolean endpointOnlyProtectedGapPatternForTest(List<BlockPos> endpoints,
                                                           List<RoadSpan> spans) {
        return endpointOnlyProtectedGapPattern(endpoints, spans);
    }

    private static boolean endpointOnlyProtectedGapPattern(List<BlockPos> endpoints,
                                                            List<RoadSpan> spans) {
        if (endpoints == null || endpoints.size() < 2 || spans == null || spans.isEmpty()) return false;
        BlockPos first = endpoints.getFirst();
        BlockPos last = endpoints.getLast();
        if (first == null || last == null) return false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            BlockPos start = span.start();
            BlockPos end = span.end();
            if (start == null && end == null) return false;
            long nearest = Math.min(
                    Math.min(horizontalDistanceSquared(start, first), horizontalDistanceSquared(start, last)),
                    Math.min(horizontalDistanceSquared(end, first), horizontalDistanceSquared(end, last)));
            if (nearest > (long) ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS
                    * ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS) {
                return false;
            }
        }
        return true;
    }

    /** Validates a candidate path against the configured road-body footprint. */
    public static boolean intersectsPreparedPath(ServerLevel level,
                                                 StructureConnection connection,
                                                 List<BlockPos> path) {
        return intersectsPreparedPath(level, connection, path, null);
    }

    /**
     * Checks only the configured road body.  Building avoidance is deliberately
     * independent from the optional terrain shoulders: a detour is valid when
     * the five-block road itself can pass without touching a real building.
     */
    public static boolean intersectsPreparedPath(ServerLevel level,
                                                 StructureConnection connection,
                                                 List<BlockPos> path,
                                                 AccurateHeightSampler terrainSampler) {
        if (level == null || connection == null || path == null || path.isEmpty()) return false;
        if (!ConfigService.get().structureAvoidanceEnabled()) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        BlockPos previous = null;
        for (BlockPos current : path) {
            if (current == null) continue;
            if (previous == null) {
                if (snapshot.blocks(current)) return true;
            } else {
                double distance = Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ());
                int samples = Math.max(1, (int) Math.ceil(distance));
                for (int i = 0; i <= samples; i++) {
                    double t = i / (double) samples;
                    BlockPos sample = new BlockPos(
                            (int) Math.round(previous.getX() + (current.getX() - previous.getX()) * t),
                            (int) Math.round(previous.getY() + (current.getY() - previous.getY()) * t),
                            (int) Math.round(previous.getZ() + (current.getZ() - previous.getZ()) * t));
                    if (snapshot.blocks(sample)) return true;
                }
            }
            previous = current;
        }
        return false;
    }

    /** True only when a center path touches an unresolved fallback structure. */
    public static boolean intersectsPreparedPathWithUnresolvedStructure(
            ServerLevel level,
            StructureConnection connection,
            List<BlockPos> path) {
        // Unknown structure boxes are retained for diagnostics only. They do
        // not trigger local detours or replacement candidates.
        return false;
    }

    /**
     * A verified building may leave an unpaved protection gap. The gap can be
     * long, but it must be an internal span with usable road on both sides.
     * The path points are the swept center samples; the snapshot already
     * applies the configured road footprint radius.
     */
    public static boolean hasAcceptableBuildingGaps(ServerLevel level,
                                                     StructureConnection connection,
                                                     List<BlockPos> path) {
        if (level == null || connection == null || path == null || path.size() < 3) return false;
        PlanningSnapshot snapshot = requirePlanningSnapshot(level, connection);
        boolean[] collisions = sweptBuildingCollisionMask(snapshot, path);
        return evaluateProtectedGapPattern(path, collisions, connection);
    }

    /**
     * A protected gap is a construction decision, so inspect the same swept
     * horizontal road footprint used by the planning collision query. Testing
     * only path nodes misses a building that lies between two coarse stations
     * and incorrectly rejects an otherwise usable route.
     */
    private static boolean[] sweptBuildingCollisionMask(PlanningSnapshot snapshot,
                                                         List<BlockPos> path) {
        boolean[] collisions = new boolean[path.size()];
        for (int i = 0; i < path.size(); i++) {
            BlockPos current = path.get(i);
            if (current == null) continue;
            collisions[i] |= snapshot.blocks(current);
            if (i == 0) continue;
            BlockPos previous = path.get(i - 1);
            if (previous == null) continue;
            int samples = Math.max(1, (int) Math.ceil(Math.hypot(
                    current.getX() - previous.getX(), current.getZ() - previous.getZ())));
            for (int sample = 1; sample <= samples; sample++) {
                double t = sample / (double) samples;
                BlockPos point = new BlockPos(
                        (int) Math.round(previous.getX() + (current.getX() - previous.getX()) * t),
                        (int) Math.round(previous.getY() + (current.getY() - previous.getY()) * t),
                        (int) Math.round(previous.getZ() + (current.getZ() - previous.getZ()) * t));
                if (snapshot.blocks(point)) {
                    collisions[i] = true;
                    break;
                }
            }
        }
        return collisions;
    }

    /** Package-private route policy hook used by the structure regression harness. */
    static boolean protectedGapPatternAcceptableForTest(List<BlockPos> path, boolean[] collisions) {
        return evaluateProtectedGapPattern(path, collisions, null);
    }

    private static boolean evaluateProtectedGapPattern(List<BlockPos> path,
                                                       boolean[] collisions,
                                                       StructureConnection connection) {
        if (path == null || collisions == null || path.size() < 3 || collisions.length != path.size()) {
            return false;
        }
        double total = routeHorizontalLength(path, 0, path.size() - 1);
        if (total <= 0.0D) return false;

        boolean foundBuilding = false;
        for (int i = 0; i < path.size();) {
            if (!collisions[i]) {
                i++;
                continue;
            }
            foundBuilding = true;
            int start = i;
            while (i + 1 < path.size() && collisions[i + 1]) i++;
            int end = i;
            if (start == 0 && end >= path.size() - 1) {
                if (connection != null) {
                    LOGGER.warn("RoadWeaver rejected building protection gap because the building consumes the whole route connection={} -> {} span={}..{}",
                            connection.from(), connection.to(), start, end);
                }
                return false;
            }

            // Evaluate each contiguous building collision independently. A
            // second building farther along the route must not turn the road
            // between the two buildings into one artificial gap. There is no
            // arbitrary maximum gap length: every collided building column is
            // simply left unpaved, while the safe road before and after it is
            // retained.
            double before = routeHorizontalLength(path, 0, start);
            double after = routeHorizontalLength(path, end, path.size() - 1);
            boolean endpointGap = start == 0 || end >= path.size() - 1;
            if ((!endpointGap && (before <= 0.0D || after <= 0.0D))
                    || (start == 0 && after <= 0.0D)
                    || (end >= path.size() - 1 && before <= 0.0D)) {
                if (connection != null) {
                    LOGGER.warn("RoadWeaver rejected building protection gap without usable road outside the building connection={} -> {} span={}..{} before={} after={}",
                            connection.from(), connection.to(), start, end, before, after);
                }
                return false;
            }
            i++;
        }
        return foundBuilding;
    }

    private static double routeHorizontalLength(List<BlockPos> path, int from, int to) {
        double length = 0.0D;
        for (int i = Math.max(1, from + 1); i <= to && i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            if (a != null && b != null) length += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        return length;
    }

    public static PlanningSnapshot requirePlanningSnapshot(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) {
            throw new PlanningSnapshotUnavailableException("Missing level or structure connection");
        }
        RouteKey key = routeKey(level, connection);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
            throw new PlanningSnapshotUnavailableException(
                    "No complete structure snapshot for route " + connection.from() + " -> " + connection.to());
        }
        return snapshot;
    }

    public static PlanningSnapshot requirePlanningSnapshot(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) {
            throw new PlanningSnapshotUnavailableException("Road has no persistent endpoint owners");
        }
        RouteKey key = routeKey(level, road);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
            throw new PlanningSnapshotUnavailableException(
                    "No complete structure snapshot for finalized road owners="
                            + road.ownerA2dKey() + " -> " + road.ownerB2dKey());
        }
        return snapshot;
    }

    public static void forceVerticalObstacle(ServerLevel level,
                                      StructureConnection connection,
                                      StructureOwnerKey owner) {
        if (level == null || connection == null || owner == null) return;
        RouteKey key = routeKey(level, connection);
        StructureAvoidanceCache.PLANNING_SNAPSHOTS.computeIfPresent(key, (ignored, snapshot) -> snapshot.forceVerticalOwner(owner));
    }

    /**
     * Final construction validation uses the full road shoulder volume, so it
     * can find a building block that the centre-line pass did not sample. The
     * caller may convert that internal station into a protected gap, but only
     * for a verified template building and never for a fallback box or an
     * endpoint target.
     */
    public static boolean isVerifiedBuildingCollision(ServerLevel level,
                                                      StructureConnection connection,
                                                      StructureOwnerKey owner,
                                                      BlockPos position,
                                                      int minY,
                                                      int maxY) {
        if (level == null || connection == null || owner == null || position == null) return false;
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) return false;
        OwnedStructureBox owned = snapshot.findCollision(position.getX(), position.getZ(), minY, maxY);
        return owned != null
                && owner.equals(owned.owner())
                && isVerifiedBuilding(owned)
                && !snapshot.ownsEndpoint(owner);
    }

    public static final class PlanningSnapshotUnavailableException extends RuntimeException {
        public PlanningSnapshotUnavailableException(String message) {
            super(message);
        }
    }

    private static Map<RouteKey, PathCorridorPlan> buildPathCorridors(
            ServerLevel level,
            Map<StructureConnection, List<BlockPos>> paths,
            boolean onlyUncoveredChunks) {
        Map<RouteKey, PathCorridorPlan> result = new LinkedHashMap<>();
        int reachChunks = STRUCTURE_START_REACH_CHUNKS + PATH_CORRIDOR_MARGIN_CHUNKS;
        int reachSquared = reachChunks * reachChunks;
        for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
            StructureConnection connection = entry.getKey();
            List<BlockPos> path = entry.getValue();
            if (connection == null || path == null || path.isEmpty()) continue;

            Set<Long> pathChunks = new LinkedHashSet<>();
            BlockPos previous = null;
            for (BlockPos point : path) {
                if (point == null) continue;
                int chunkX = point.getX() >> 4;
                int chunkZ = point.getZ() >> 4;
                if (previous == null) {
                    pathChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                } else {
                    addChunkLine(pathChunks,
                            previous.getX() >> 4, previous.getZ() >> 4, chunkX, chunkZ);
                }
                previous = point;
            }
            if (pathChunks.isEmpty()) continue;

            RouteKey routeKey = routeKey(level, connection);
            PlanningSnapshot previousSnapshot = onlyUncoveredChunks
                    ? StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey)
                    : null;
            LongSet coveredChunks = previousSnapshot == null
                    ? LongSets.emptySet()
                    : previousSnapshot.coveredCandidateChunks();
            LongSet candidateChunks = new LongOpenHashSet();
            for (long pathChunkKey : pathChunks) {
                ChunkPos pathChunk = new ChunkPos(pathChunkKey);
                for (int dx = -reachChunks; dx <= reachChunks; dx++) {
                    for (int dz = -reachChunks; dz <= reachChunks; dz++) {
                        if (dx * dx + dz * dz > reachSquared) continue;
                        long candidateChunk = ChunkPos.asLong(pathChunk.x + dx, pathChunk.z + dz);
                        if (!coveredChunks.contains(candidateChunk)) candidateChunks.add(candidateChunk);
                    }
                }
            }
            boolean skipped = candidateChunks.isEmpty();
            DetailedRoadGenerationStats.recordCorridorRefresh(candidateChunks.size(), 0, skipped);
            if (!skipped) {
                result.put(routeKey, new PathCorridorPlan(
                        connection, List.copyOf(path), Set.copyOf(pathChunks),
                        LongSets.unmodifiable(candidateChunks)));
            }
        }
        return result;
    }

    private static void addChunkLine(Set<Long> chunks, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int error = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            chunks.add(ChunkPos.asLong(x, z));
            if (x == x1 && z == z1) return;
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x += sx;
            }
            if (doubled < dx) {
                error += dx;
                z += sz;
            }
        }
    }

    private static Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>>
    predictObstacleCandidatesForCorridors(
            ServerLevel level,
            Map<RouteKey, PathCorridorPlan> corridors,
            boolean trackInitialProgress) {
        Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> result = new HashMap<>();
        Set<ObstacleTileKey> requiredTiles = new LinkedHashSet<>();
        for (PathCorridorPlan corridor : corridors.values()) {
            for (long chunkKey : corridor.candidateChunks()) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                requiredTiles.add(new ObstacleTileKey(
                        level.dimension().location().toString(),
                        level.getSeed(),
                        Math.floorDiv(chunk.x, AVOIDANCE_PREDICTION_TILE_CHUNKS),
                        Math.floorDiv(chunk.z, AVOIDANCE_PREDICTION_TILE_CHUNKS)));
            }
        }

        if (trackInitialProgress) {
            InitialGenerationProgressTracker.setPlanningTilePlan(
                    requiredTiles.size(), "predicting_road_corridor_structure_tiles");
        }
        for (ObstacleTileKey tileKey : requiredTiles) {
            List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                    StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
            if (tileCandidates == null) {
                int tileMinChunkX = tileKey.tileX() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMinChunkZ = tileKey.tileZ() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                tileCandidates = List.copyOf(StructurePredictor.predictAvoidanceStructuresInRect(
                        level,
                        tileMinChunkX,
                        tileMinChunkZ,
                        tileMinChunkX + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1,
                        tileMinChunkZ + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1));
                List<StructurePredictor.AvoidanceStructureCandidate> existing = putIfAbsentBounded(
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES, StructureAvoidanceCache.OBSTACLE_TILE_ORDER, tileKey, tileCandidates,
                        OBSTACLE_TILE_CACHE_CAPACITY);
                if (existing == null) {
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.incrementAndGet();
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.addAndGet(
                            (long) AVOIDANCE_PREDICTION_TILE_CHUNKS * AVOIDANCE_PREDICTION_TILE_CHUNKS);
                    StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.addAndGet(tileCandidates.size());
                } else {
                    tileCandidates = existing;
                    StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
                }
            } else {
                StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
            }
            if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningTileDone();
        }

        for (Map.Entry<RouteKey, PathCorridorPlan> routeEntry : corridors.entrySet()) {
            PathCorridorPlan corridor = routeEntry.getValue();
            Set<PredictedCandidateKey> seen = new HashSet<>();
            List<StructurePredictor.AvoidanceStructureCandidate> candidates = new ArrayList<>();
            Set<ObstacleTileKey> routeTiles = new LinkedHashSet<>();
            for (long chunkKey : corridor.candidateChunks()) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                routeTiles.add(new ObstacleTileKey(
                        level.dimension().location().toString(),
                        level.getSeed(),
                        Math.floorDiv(chunk.x, AVOIDANCE_PREDICTION_TILE_CHUNKS),
                        Math.floorDiv(chunk.z, AVOIDANCE_PREDICTION_TILE_CHUNKS)));
            }
            for (ObstacleTileKey tileKey : routeTiles) {
                List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
                if (tileCandidates == null) continue;
                for (StructurePredictor.AvoidanceStructureCandidate candidate : tileCandidates) {
                    if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                    long chunkKey = ChunkPos.asLong(candidate.pos().getX() >> 4, candidate.pos().getZ() >> 4);
                    if (!corridor.candidateChunks().contains(chunkKey)) continue;
                    PredictedCandidateKey candidateKey = new PredictedCandidateKey(
                            chunkKey, candidate.structureSet());
                    if (seen.add(candidateKey)) candidates.add(candidate);
                }
            }
            StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.addAndGet(candidates.size());
            result.put(routeEntry.getKey(), List.copyOf(candidates));
        }
        long pathChunks = corridors.values().stream().mapToLong(corridor -> corridor.pathChunks().size()).sum();
        long candidateChunks = corridors.values().stream()
                .mapToLong(corridor -> corridor.candidateChunks().size()).sum();
        long predictedCandidates = result.values().stream().mapToLong(List::size).sum();
        Map<String, Integer> candidatesBySet = new HashMap<>();
        for (List<StructurePredictor.AvoidanceStructureCandidate> candidates : result.values()) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : candidates) {
                String id = candidate.structureSet().unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse(candidate.structureSet().value().getClass().getName());
                candidatesBySet.merge(id, 1, Integer::sum);
            }
        }
        String topSets = candidatesBySet.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(8)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        LOGGER.info("RoadWeaver corridor prediction scope: routes={} pathChunks={} candidateChunks={} predictionTiles={} predictedCandidates={} topSets={}",
                corridors.size(), pathChunks, candidateChunks, requiredTiles.size(), predictedCandidates, topSets);
        return result;
    }

    private static Set<PreparedStartKey> collectCorridorStartKeys(
            ServerLevel level,
            StructureFileStorage.StructureSnapshot storageSnapshot,
            Set<Long> candidateChunks,
            List<BlockPos> corridorPath,
            List<StructurePredictor.AvoidanceStructureCandidate> obstacleCandidates) {
        Set<PreparedStartKey> starts = new LinkedHashSet<>();
        List<StructureInfo> known = storageSnapshot.locations().structureInfos();
        if (known != null) {
            for (StructureInfo info : known) {
                if (info == null || info.pos() == null) continue;
                long chunkKey = ChunkPos.asLong(info.pos().getX() >> 4, info.pos().getZ() >> 4);
                // T-35: 圆盘候选集只覆盖路径区块附近的 chunk,绕山路(U 型/沿山脊)
                // 会让村庄锚点 chunk 落在圆盘外,落库村庄因此从未进入快照,过路
                // 掩码全空,道路整片穿过村庄(施工期才逐建筑补救)。沿用老路径
                // collectPlanningStartKeys 的"距实际路径 <= 192 格"标准,把这类
                // 村庄强制纳入候选,使其能参与 passThroughVillageGapMask。
                boolean reachable = candidateChunks.contains(chunkKey)
                        || withinCorridorPath(info.pos(), corridorPath);
                if (!reachable) continue;
                collectCandidateStartKey(level, info, storageSnapshot.sourceAt(info.pos()), starts);
            }
        }
        if (obstacleCandidates != null) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : obstacleCandidates) {
                if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                int candidateChunkX = candidate.pos().getX() >> 4;
                int candidateChunkZ = candidate.pos().getZ() >> 4;
                // E 阶段预筛：放置数学判定不可能的候选不进精确解析。
                if (!isPotentialStructureChunk(level, candidate.structureSet(), candidateChunkX, candidateChunkZ)) {
                    PREFILTERED_CANDIDATES.incrementAndGet();
                    continue;
                }
                PREFILTER_KEPT_CANDIDATES.incrementAndGet();
                starts.add(preparedStartKey(
                        level,
                        candidateChunkX,
                        candidateChunkZ,
                        StructureFileStorage.SOURCE_PREDICTED,
                        candidate.structureSet()));
            }
        }
        return Set.copyOf(starts);
    }

    /**
     * True when any polyline segment of the corridor path lies within
     * {@link #PLANNING_CORRIDOR_RADIUS} blocks of the given position.
     */
    private static boolean withinCorridorPath(BlockPos pos, List<BlockPos> corridorPath) {
        if (pos == null || corridorPath == null || corridorPath.isEmpty()) return false;
        long reachSquared = (long) PLANNING_CORRIDOR_RADIUS * PLANNING_CORRIDOR_RADIUS;
        BlockPos previous = null;
        for (BlockPos point : corridorPath) {
            if (point == null) continue;
            if (previous == null) {
                previous = point;
                continue;
            }
            if (distanceSquaredToSegment(pos.getX(), pos.getZ(), previous, point) <= reachSquared) {
                return true;
            }
            previous = point;
        }
        return false;
    }

    private static Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> predictObstacleCandidates(
            ServerLevel level, Collection<StructureConnection> connections) {
        Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> result = new HashMap<>();
        int reachBlocks = PLANNING_CORRIDOR_RADIUS + STRUCTURE_START_REACH_CHUNKS * 16;
        long reachSquared = (long) reachBlocks * reachBlocks;
        Map<RouteKey, RoutePredictionBounds> routeBounds = new LinkedHashMap<>();
        Set<ObstacleTileKey> requiredTiles = new LinkedHashSet<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            RouteKey routeKey = routeKey(level, connection);
            if (StructureAvoidanceCache.PLANNING_SNAPSHOTS.containsKey(routeKey)) continue;
            int minChunkX = Math.floorDiv(Math.min(connection.from().getX(), connection.to().getX()) - reachBlocks, 16);
            int maxChunkX = Math.floorDiv(Math.max(connection.from().getX(), connection.to().getX()) + reachBlocks, 16);
            int minChunkZ = Math.floorDiv(Math.min(connection.from().getZ(), connection.to().getZ()) - reachBlocks, 16);
            int maxChunkZ = Math.floorDiv(Math.max(connection.from().getZ(), connection.to().getZ()) + reachBlocks, 16);
            int minTileX = Math.floorDiv(minChunkX, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int maxTileX = Math.floorDiv(maxChunkX, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int minTileZ = Math.floorDiv(minChunkZ, AVOIDANCE_PREDICTION_TILE_CHUNKS);
            int maxTileZ = Math.floorDiv(maxChunkZ, AVOIDANCE_PREDICTION_TILE_CHUNKS);

            RoutePredictionBounds bounds = new RoutePredictionBounds(
                    connection, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    minTileX, maxTileX, minTileZ, maxTileZ, reachSquared);
            routeBounds.put(routeKey, bounds);
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                    requiredTiles.add(new ObstacleTileKey(
                            level.dimension().location().toString(), level.getSeed(), tileX, tileZ));
                }
            }
        }

        boolean trackInitialProgress = InitialGenManager.isActive();
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.setPlanningTilePlan(
                    requiredTiles.size(), "predicting_structure_obstacle_tiles");
        }
        for (ObstacleTileKey tileKey : requiredTiles) {
            List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                    StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
            if (tileCandidates == null) {
                int tileMinChunkX = tileKey.tileX() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMinChunkZ = tileKey.tileZ() * AVOIDANCE_PREDICTION_TILE_CHUNKS;
                int tileMaxChunkX = tileMinChunkX + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1;
                int tileMaxChunkZ = tileMinChunkZ + AVOIDANCE_PREDICTION_TILE_CHUNKS - 1;
                tileCandidates = List.copyOf(StructurePredictor.predictAvoidanceStructuresInRect(
                        level, tileMinChunkX, tileMinChunkZ, tileMaxChunkX, tileMaxChunkZ));
                List<StructurePredictor.AvoidanceStructureCandidate> existing = putIfAbsentBounded(
                        StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES, StructureAvoidanceCache.OBSTACLE_TILE_ORDER, tileKey, tileCandidates,
                        OBSTACLE_TILE_CACHE_CAPACITY);
                if (existing == null) {
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.incrementAndGet();
                    StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.addAndGet(
                            (long) AVOIDANCE_PREDICTION_TILE_CHUNKS * AVOIDANCE_PREDICTION_TILE_CHUNKS);
                    StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.addAndGet(tileCandidates.size());
                } else {
                    StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
                }
            } else {
                StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.incrementAndGet();
            }
            if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningTileDone();
        }

        for (Map.Entry<RouteKey, RoutePredictionBounds> routeEntry : routeBounds.entrySet()) {
            RoutePredictionBounds bounds = routeEntry.getValue();
            Set<PredictedCandidateKey> seenStarts = new HashSet<>();
            List<StructurePredictor.AvoidanceStructureCandidate> routeCandidates = new ArrayList<>();
            for (int tileX = bounds.minTileX(); tileX <= bounds.maxTileX(); tileX++) {
                for (int tileZ = bounds.minTileZ(); tileZ <= bounds.maxTileZ(); tileZ++) {
                    ObstacleTileKey tileKey = new ObstacleTileKey(
                            level.dimension().location().toString(), level.getSeed(), tileX, tileZ);
                    List<StructurePredictor.AvoidanceStructureCandidate> tileCandidates =
                            StructureAvoidanceCache.OBSTACLE_TILE_CANDIDATES.get(tileKey);
                    if (tileCandidates == null) continue;
                    for (StructurePredictor.AvoidanceStructureCandidate candidate : tileCandidates) {
                        if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                        BlockPos pos = candidate.pos();
                        int cx = pos.getX() >> 4;
                        int cz = pos.getZ() >> 4;
                        if (cx < bounds.minChunkX() || cx > bounds.maxChunkX()
                                || cz < bounds.minChunkZ() || cz > bounds.maxChunkZ()) continue;
                        if (distanceSquaredToSegment(pos.getX(), pos.getZ(),
                                bounds.connection().from(), bounds.connection().to()) > bounds.reachSquared()) continue;
                        PredictedCandidateKey candidateKey = new PredictedCandidateKey(
                                ChunkPos.asLong(cx, cz), candidate.structureSet());
                        if (seenStarts.add(candidateKey)) routeCandidates.add(candidate);
                    }
                }
            }
            result.put(routeEntry.getKey(), List.copyOf(routeCandidates));
        }
        return result;
    }

    private static Set<PreparedStartKey> collectPlanningStartKeys(ServerLevel level,
                                                                  StructureConnection connection,
                                                                  StructureFileStorage.StructureSnapshot storageSnapshot,
                                                                  List<StructurePredictor.AvoidanceStructureCandidate> obstacleCandidates) {
        int minX = Math.min(connection.from().getX(), connection.to().getX()) - PLANNING_CORRIDOR_RADIUS;
        int maxX = Math.max(connection.from().getX(), connection.to().getX()) + PLANNING_CORRIDOR_RADIUS;
        int minZ = Math.min(connection.from().getZ(), connection.to().getZ()) - PLANNING_CORRIDOR_RADIUS;
        int maxZ = Math.max(connection.from().getZ(), connection.to().getZ()) + PLANNING_CORRIDOR_RADIUS;
        List<StructureInfo> known = storageSnapshot.locations().structureInfos();

        Set<PreparedStartKey> starts = new LinkedHashSet<>();
        if (known != null) {
            for (StructureInfo info : known) {
                if (info == null || info.pos() == null) continue;
                BlockPos pos = info.pos();
                if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
                if (distanceSquaredToSegment(pos.getX(), pos.getZ(), connection.from(), connection.to())
                        > (long) PLANNING_CORRIDOR_RADIUS * PLANNING_CORRIDOR_RADIUS) continue;
                collectCandidateStartKey(level, info, storageSnapshot.sourceAt(pos), starts);
            }
        }
        if (obstacleCandidates != null) {
            for (StructurePredictor.AvoidanceStructureCandidate candidate : obstacleCandidates) {
                if (candidate == null || candidate.pos() == null || candidate.structureSet() == null) continue;
                int cx = candidate.pos().getX() >> 4;
                int cz = candidate.pos().getZ() >> 4;
                // E 阶段预筛：放置数学判定不可能的候选不进精确解析。
                if (!isPotentialStructureChunk(level, candidate.structureSet(), cx, cz)) {
                    PREFILTERED_CANDIDATES.incrementAndGet();
                    continue;
                }
                PREFILTER_KEPT_CANDIDATES.incrementAndGet();
                starts.add(preparedStartKey(level, cx, cz,
                        StructureFileStorage.SOURCE_PREDICTED, candidate.structureSet()));
            }
        }
        return Set.copyOf(starts);
    }

    private static void collectCandidateStartKey(ServerLevel level,
                                                 StructureInfo info,
                                                 int source,
                                                 Set<PreparedStartKey> starts) {
        if (info == null || info.pos() == null) return;
        int cx = info.pos().getX() >> 4;
        int cz = info.pos().getZ() >> 4;
        starts.add(preparedStartKey(level, cx, cz, source, null));
    }

    private static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level, List<PreparedStartKey> starts, boolean trackInitialProgress) {
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, (PrepareTiming) null);
    }

    /** 0.9.059（①）：{@code timing} 非 null 时记录并发列宽与真实 worker 线程数（纯观测）。 */
    private static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level, List<PreparedStartKey> starts, boolean trackInitialProgress,
            PrepareTiming timing) {
        ThreadPoolManager.TaskRole role = trackInitialProgress && InitialGenManager.isActive()
                ? ThreadPoolManager.TaskRole.INITIAL
                : ThreadPoolManager.TaskRole.STRUCTURE;
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, role, timing);
    }

    private static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole resolutionRole) {
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, resolutionRole, null);
    }

    private static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole resolutionRole,
            PrepareTiming timing) {
        if (starts == null || starts.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        // E 阶段护栏：第一次解析时验证"预筛前后 exactPieceBoxes 一致"，不一致自动关闭预筛。
        verifyPrefilterExactness(level, starts);
        Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts = new ConcurrentHashMap<>();
        List<PreparedStartKey> unresolved = new ArrayList<>();
        for (PreparedStartKey start : starts) {
            List<OwnedStructureBox> cached = cachedPreparedStart(start);
            if (cached != null) {
                StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.incrementAndGet();
                resolvedStarts.put(start, cached);
                if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningStartDone(cached.isEmpty());
            } else {
                unresolved.add(start);
            }
        }
        int batchWindow = structureStartBatchWindow(resolutionRole);
        if (timing != null) {
            timing.setColumns(STRUCTURE_START_BATCH_SIZE * batchWindow);
            timing.observeWorkerThread();
        }
        LOGGER.info("RoadWeaver exact structure start plan: dimension={} total={} cached={} unresolved={} batchSize={} windowBatches={}",
                level.dimension().location(), starts.size(), starts.size() - unresolved.size(),
                unresolved.size(), STRUCTURE_START_BATCH_SIZE, batchWindow);
        return resolvePreparedStartWindow(
                        level, unresolved, trackInitialProgress, resolvedStarts, resolutionRole, batchWindow, timing)
                .thenApply(ignored -> Map.copyOf(resolvedStarts))
                .whenComplete((ignored, failure) -> {
                    if (trackInitialProgress) InitialGenerationProgressTracker.setPlanningActiveWorkers(0);
                });
    }

    private static CompletableFuture<Void> resolvePreparedStartWindow(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts,
            ThreadPoolManager.TaskRole resolutionRole,
            int batchWindow,
            PrepareTiming timing) {
        if (starts.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Object pumpLock = new Object();
        java.util.concurrent.atomic.AtomicInteger nextOffset = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger activeBatches = new java.util.concurrent.atomic.AtomicInteger();
        long windowStartedAt = System.nanoTime();

        Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            List<Integer> offsets = new ArrayList<>(batchWindow);
            synchronized (pumpLock) {
                if (completed.isDone()) return;
                while (activeBatches.get() < batchWindow) {
                    int offset = nextOffset.getAndAdd(STRUCTURE_START_BATCH_SIZE);
                    if (offset >= starts.size()) break;
                    activeBatches.incrementAndGet();
                    offsets.add(offset);
                }
                if (trackInitialProgress) {
                    int estimatedActiveThreads = Math.min(
                            activeBatches.get() * STRUCTURE_START_BATCH_SIZE,
                            resolutionRole == ThreadPoolManager.TaskRole.INITIAL
                                    ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                                    : ThreadPoolManager.maxRuntimeStructureThreads());
                    InitialGenerationProgressTracker.setPlanningActiveWorkers(
                            Math.min(starts.size(), estimatedActiveThreads));
                }
            }
            for (int offset : offsets) {
                resolvePreparedStartBatchOnce(
                                level, starts, offset, trackInitialProgress, 0, resolvedStarts, resolutionRole,
                                timing)
                        .whenComplete((ignored, failure) -> {
                            boolean finished;
                            if (timing != null) timing.observeWorkerThread();
                            synchronized (pumpLock) {
                                int remaining = activeBatches.decrementAndGet();
                                if (trackInitialProgress) {
                                    int estimatedActiveThreads = Math.min(
                                            remaining * STRUCTURE_START_BATCH_SIZE,
                                            resolutionRole == ThreadPoolManager.TaskRole.INITIAL
                                                    ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                                                    : ThreadPoolManager.maxRuntimeStructureThreads());
                                    InitialGenerationProgressTracker.setPlanningActiveWorkers(
                                            Math.min(starts.size(), estimatedActiveThreads));
                                }
                                if (failure != null) {
                                    completed.completeExceptionally(unwrapCompletionFailure(failure));
                                    return;
                                }
                                finished = nextOffset.get() >= starts.size() && remaining == 0;
                                if (finished) {
                                    long elapsedMs = elapsedMillis(windowStartedAt);
                                    double startsPerSecond = elapsedMs <= 0L
                                            ? starts.size()
                                            : starts.size() * 1000.0D / elapsedMs;
                                    LOGGER.info(
                                            "RoadWeaver exact structure window timing: dimension={} starts={} windowBatches={} elapsedMs={} startsPerSecond={}",
                                            level.dimension().location(), starts.size(),
                                            batchWindow, elapsedMs,
                                            String.format(java.util.Locale.ROOT, "%.2f", startsPerSecond));
                                    completed.complete(null);
                                }
                            }
                            if (!finished) {
                                CompletableFuture.delayedExecutor(0L, TimeUnit.MILLISECONDS).execute(pump[0]);
                            }
                        });
            }
        };
        pump[0].run();
        return completed;
    }

    private static int structureStartBatchWindow(ThreadPoolManager.TaskRole role) {
        if (role == ThreadPoolManager.TaskRole.INITIAL && InitialGenManager.isActive()) {
            return Math.max(STRUCTURE_START_BATCH_WINDOW,
                    Math.min(16, ThreadPoolManager.startupNativeStructureThreadCapacity()));
        }
        return STRUCTURE_START_BATCH_WINDOW;
    }

    private static CompletableFuture<Void> resolvePreparedStartBatchOnce(
            ServerLevel level, List<PreparedStartKey> starts, int offset,
            boolean trackInitialProgress, int batchAttempt,
            Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts,
            ThreadPoolManager.TaskRole resolutionRole,
            PrepareTiming timing) {
        if (offset >= starts.size()) return CompletableFuture.completedFuture(null);
        int end = Math.min(starts.size(), offset + STRUCTURE_START_BATCH_SIZE);
        List<PreparedStartKey> batch = List.copyOf(starts.subList(offset, end));
        long batchStartedAt = System.nanoTime();
        if (timing != null) timing.observeWorkerThread();
        List<CompletableFuture<List<OwnedStructureBox>>> futures = new ArrayList<>(batch.size());
        for (PreparedStartKey start : batch) {
            CompletableFuture<List<OwnedStructureBox>> future = resolvePreparedStartAsync(
                    level, start, resolutionRole);
            future.whenComplete((ignored, failure) -> {
                if (failure != null) StructureAvoidanceCache.IN_FLIGHT_STARTS.remove(start, future);
            });
            futures.add(future);
        }
        CompletableFuture<?>[] pending = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending)
                .handle((ignored, failure) -> {
                    boolean milestone = failure != null
                            || offset == 0
                            || end == starts.size()
                            || offset % (STRUCTURE_START_BATCH_SIZE * 64) == 0;
                    if (milestone) {
                        LOGGER.info("RoadWeaver exact structure batch timing: dimension={} offset={} size={} attempt={} elapsedMs={} success={}",
                                level.dimension().location(), offset, batch.size(), batchAttempt + 1,
                                elapsedMillis(batchStartedAt), failure == null);
                    } else {
                        LOGGER.debug("RoadWeaver exact structure batch timing: dimension={} offset={} size={} attempt={} elapsedMs={} success={}",
                                level.dimension().location(), offset, batch.size(), batchAttempt + 1,
                                elapsedMillis(batchStartedAt), failure == null);
                    }
                    return failure;
                })
                .thenCompose(failure -> {
                    Throwable cause = unwrapCompletionFailure(failure);
                    if (cause != null) return CompletableFuture.failedFuture(cause);
                    for (int i = 0; i < batch.size(); i++) {
                        PreparedStartKey start = batch.get(i);
                        List<OwnedStructureBox> accepted = storePreparedStart(
                                start, futures.get(i).join(), trackInitialProgress);
                        resolvedStarts.put(start, accepted);
                        probeBiomePrefilter(level, start, accepted);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    private static CompletableFuture<List<OwnedStructureBox>> resolvePreparedStartAsync(
            ServerLevel level,
            PreparedStartKey start,
            ThreadPoolManager.TaskRole resolutionRole) {
        List<OwnedStructureBox> cached = cachedPreparedStart(start);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return StructureAvoidanceCache.IN_FLIGHT_STARTS.computeIfAbsent(
                start, ignored -> createPreparedStartFuture(level, start, resolutionRole));
    }

    private static CompletableFuture<List<OwnedStructureBox>> createPreparedStartFuture(
            ServerLevel level,
            PreparedStartKey start,
            ThreadPoolManager.TaskRole resolutionRole) {
        ChunkPos pos = new ChunkPos(start.chunkKey());
        boolean startupServerThreadBlocked = InitialGenManager.isRoadGenerationWorkersActive();
        if (start.source() == StructureFileStorage.SOURCE_MANUAL) {
            // Manual endpoints (including the spawn cabin) use the same
            // bounded structure-reference lookup during startup.  The batch
            // collector pumps the server executor, so rejecting an uncached
            // endpoint here would incorrectly fail the spawn route.
            return resolveManualPointBoxesAsync(level, pos.x, pos.z);
        }
        StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.incrementAndGet();
        if (start.structureSet() != null) {
            if (!startupServerThreadBlocked && StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) {
                return resolvePredictedChunkBoxesAsync(level, pos);
            }
            long epoch = ThreadPoolManager.currentEpoch();
            StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.incrementAndGet();
            Supplier<List<OwnedStructureBox>> nativeResolution = () -> {
                if (!ThreadPoolManager.isEpoch(epoch)) {
                    throw new CancellationException("RoadWeaver world epoch changed before direct structure generation");
                }
                if (!startupServerThreadBlocked && StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) {
                    throw new NativeDirectCircuitOpenException();
                }
                if (!startupServerThreadBlocked) acquireNativeDirectLane();
                try {
                    return resolvePredictedStructureSetBoxes(level, pos, start.structureSet());
                } finally {
                    if (!startupServerThreadBlocked) StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.decrementAndGet();
                }
            };
            CompletableFuture<List<OwnedStructureBox>> nativeFuture = startupServerThreadBlocked
                    ? ThreadPoolManager.supplyAsync(resolutionRole, nativeResolution)
                    : CompletableFuture.supplyAsync(nativeResolution, nativeDirectExecutor());
            return nativeFuture.exceptionallyCompose(failure -> {
                Throwable cause = unwrapCompletionFailure(failure);
                if (cause instanceof CancellationException || cause instanceof Error) {
                    return CompletableFuture.failedFuture(cause);
                }
                if (startupServerThreadBlocked) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Native structure prediction failed during startup corridor refresh at " + pos,
                            cause));
                }
                long fallback = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.incrementAndGet();
                if (fallback <= 8 || fallback % 1000 == 0) {
                    LOGGER.warn("RoadWeaver native structure prediction failed at {}; falling back to real STRUCTURE_STARTS chunk (count={}): {}",
                            pos, fallback, cause.toString());
                }
                return resolvePredictedChunkBoxesAsync(level, pos);
            });
        }
        // A persisted predicted structure does not retain its StructureSet
        // holder, so it must be resolved from the real STRUCTURE_STARTS
        // chunk. Initial road batches use managedBlock while collecting
        // worker futures, allowing this bounded server task to run instead
        // of turning an uncached endpoint into a route failure.
        return resolvePredictedChunkBoxesAsync(level, pos);
    }

    /**
     * Runs the same Structure.generate path used by ChunkGenerator.createStructures,
     * but without allocating or scheduling a real chunk. This preserves modded
     * Structure implementations while removing the startup chunk-executor cycle.
     */
    private static List<OwnedStructureBox> resolvePredictedStructureSetBoxes(
            ServerLevel level, ChunkPos pos, Holder<StructureSet> structureSetHolder) {
        StructureSet structureSet = structureSetHolder.value();
        var generatorState = level.getChunkSource().getGeneratorState();
        if (!structureSet.placement().isStructureChunk(generatorState, pos.x, pos.z)) return List.of();
        List<StructureSet.StructureSelectionEntry> entries = structureSet.structures();
        if (entries.isEmpty()) return List.of();
        ArrayList<StructureSet.StructureSelectionEntry> remaining = entries.size() == 1
                ? null
                : new ArrayList<>(entries);
        WorldgenRandom random = StructureAvoidanceCache.STRUCTURE_SELECTION_RANDOM.get();
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        random.setLargeFeatureSeed(seed, pos.x, pos.z);
        var generator = level.getChunkSource().getGenerator();
        while (remaining == null || !remaining.isEmpty()) {
            int totalWeight = 0;
            List<StructureSet.StructureSelectionEntry> candidates = remaining == null ? entries : remaining;
            for (StructureSet.StructureSelectionEntry entry : candidates) {
                totalWeight += entry.weight();
            }
            int selected = random.nextInt(totalWeight);
            int selectedIndex = 0;
            for (int i = 0; i < candidates.size(); i++) {
                selected -= candidates.get(i).weight();
                if (selected < 0) {
                    selectedIndex = i;
                    break;
                }
            }
            StructureSet.StructureSelectionEntry entry = candidates.get(selectedIndex);
            Structure structure = entry.structure().value();
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    generatorState.randomState(),
                    level.getStructureManager(),
                    seed,
                    pos,
                    0,
                    level,
                    structure.biomes()::contains);
            if (start != null && start.isValid()) {
                Set<OwnedStructureBox> collected = new HashSet<>();
                collectPieces(level, levelOwner(level, structure, pos.toLong()), start, collected);
                return List.copyOf(collected);
            }
            if (remaining == null) return List.of();
            remaining.remove(selectedIndex);
        }
        return List.of();
    }

    private static void acquireNativeDirectLane() {
        while (!Thread.currentThread().isInterrupted()) {
            if (StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) throw new NativeDirectCircuitOpenException();
            net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.beforeBackgroundUnit(false);
            int configuredLimit = InitialGenManager.isActive()
                    ? nativeDirectWorkerMaximum()
                    : net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.recommendedRoadWorkerThreads();
            int limit = Math.max(1, Math.min(nativeDirectWorkerMaximum(), configuredLimit));
            int active = StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.get();
            if (active < limit && StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.compareAndSet(active, active + 1)) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new CancellationException("Interrupted while waiting for native structure capacity");
    }

    private static ExecutorService nativeDirectExecutor() {
        ExecutorService existing = StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.get();
        if (existing != null && !existing.isShutdown()) return existing;
        synchronized (StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR) {
            existing = StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.get();
            if (existing != null && !existing.isShutdown()) return existing;
            AtomicLong nextThread = new AtomicLong(1L);
            ExecutorService created = Executors.newFixedThreadPool(nativeDirectWorkerMaximum(), task -> {
                Thread thread = new Thread(task, "RW-NativeStructure-" + nextThread.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
            StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.set(created);
            return created;
        }
    }

    private static int nativeDirectWorkerMaximum() {
        // The enlarged lane is for startup prewarm only. Runtime structure
        // prediction keeps the normal player-safe structure budget.
        return InitialGenManager.isActive()
                ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                : ThreadPoolManager.maxRuntimeStructureThreads();
    }

    private static CompletableFuture<List<OwnedStructureBox>> resolvePredictedChunkBoxesAsync(
            ServerLevel level, ChunkPos pos) {
        var chunkFuture = requestChunkAsync(level, pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS);
        return chunkFuture.thenCompose(result -> callOnServerThread(level, () -> {
            ChunkAccess chunk = result.orElse(null);
            if (chunk == null) {
                throw new IllegalStateException("Unable to load predicted structure start chunk " + pos);
            }
            Set<OwnedStructureBox> collected = new HashSet<>();
            for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                collectPieces(level, levelOwner(level, entry.getKey(), pos.toLong()), entry.getValue(), collected);
            }
            return List.copyOf(collected);
        }));
    }

    private static List<OwnedStructureBox> storePreparedStart(PreparedStartKey startKey,
                                                        List<OwnedStructureBox> resolved,
                                                        boolean trackInitialProgress) {
        if (resolved == null) {
            throw new IllegalStateException("Unable to resolve structure start " + new ChunkPos(startKey.chunkKey()));
        }
        if (resolved.isEmpty()) StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.incrementAndGet();
        else StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.addAndGet(resolved.size());
        List<OwnedStructureBox> existing;
        if (InitialGenManager.isActive()
                && (startKey.source() == StructureFileStorage.SOURCE_MANUAL
                || startKey.structureSet() == null)) {
            existing = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.putIfAbsent(startKey, resolved);
        } else {
            existing = putIfAbsentBounded(
                    StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER, startKey, resolved,
                    PREPARED_START_CACHE_CAPACITY);
        }
        List<OwnedStructureBox> accepted = existing == null ? resolved : existing;
        StructureAvoidanceCache.IN_FLIGHT_STARTS.remove(startKey);
        if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningStartDone(accepted.isEmpty());
        return accepted;
    }

    /**
     * 0.9.034（R-07 计数模式）：**只读**探针 —— 记录「如果开了群系预筛，这个起点会不会被挡掉」，
     * 以及「被挡掉的起点里有多少真的产出了方块」。
     *
     * <p>真机实测（0.9.028 那局）：`exact structure start plan total=4374`、71 批共 57,831 ms、
     * `structure snapshot timing resolutionMs=74,408`（占 async planning 140,502 ms 的 53%），
     * 而 `emptyResults=3997 / 4374 = 91.4%` 解析完是空的。候选由
     * {@code predictAvoidanceStructuresInRect} 枚举，那条路**没有任何群系预筛**；现有的
     * `isPotentialStructureChunk` 是数学 placement 判定，对预测器枚举出来的候选恒真
     * （所以日志里 `prefilterSkipped` 一直是 0）。</p>
     *
     * <p>本方法**不改变任何结果**，只是把判据先量出来。判定为可安全开启的条件是
     * {@code wouldRejectNonEmpty == 0}。</p>
     */
    private static void probeBiomePrefilter(ServerLevel level, PreparedStartKey start,
                                            List<OwnedStructureBox> accepted) {
        if (start == null || start.structureSet() == null) return;
        if (start.source() != StructureFileStorage.SOURCE_PREDICTED) return;
        if (!probeEnabled()) return;
        Boolean wouldReject;
        try {
            wouldReject = StructurePredictor.biomeWouldReject(
                    level, start.structureSet(), new ChunkPos(start.chunkKey()));
        } catch (Throwable unavailable) {
            return;
        }
        if (wouldReject == null) return;
        StructureAvoidanceDiagnostics.BIOME_PROBE_PROBED.incrementAndGet();
        if (!wouldReject.booleanValue()) return;
        StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED.incrementAndGet();
        if (accepted != null && !accepted.isEmpty()) StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED_NONEMPTY.incrementAndGet();
    }

    private static boolean probeEnabled() {
        String flag = System.getProperty(BIOME_PROBE_PROPERTY);
        return flag == null || !"false".equalsIgnoreCase(flag.trim());
    }

    private static List<OwnedStructureBox> cachedPreparedStart(PreparedStartKey start) {
        List<OwnedStructureBox> pinned = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.get(start);
        if (pinned != null) {
            DIAG_PINNED_HITS.incrementAndGet();
            return pinned;
        }
        List<OwnedStructureBox> main = StructureAvoidanceCache.PREPARED_START_BOXES.get(start);
        if (main != null) {
            DIAG_MAIN_HITS.incrementAndGet();
        } else {
            DIAG_MAIN_MISSES.incrementAndGet();
            // ★ 0.8.402 诊断：**探测「同语义 key 但不同 identity」的漏命中**。
            //
            //  假设：PreparedStartKey 含 Holder<StructureSet> 字段，而
            //  Minecraft 的 Holder.Reference 未重写 equals ⇒ 按 identity 比较。
            //  若该假设成立，则「同一 (dimension, seed, chunkKey, source, 结构集语义)」
            //  的两次调用会产生**两个不同 key**，主缓存必然 miss。
            //
            //  本探测刻意**不假设假设成立**：它按 (dimension, seed, chunkKey, source)
            //  这 4 个稳定字段做一次线性扫描，直接数出「有多少个已缓存的 entry
            //  仅因 structureSet 不同而未被命中」。命中数 > 0 即坐实 Holder 假设。
            if (DIAG_ENABLED.compareAndSet(false, true)) {
                LOGGER.info("RoadWeaver prepared-start 诊断启动：开始探测 Holder<StructureSet> identity 漏命中");
            }
            probeIdentityLeak(start);
        }
        return main;
    }

    /**
     * 扫描主缓存，统计「除 structureSet 外其余字段全同」的条目数。
     *
     * <p>若该计数持续 &gt; 0，说明缓存键被 {@code Holder} identity 污染：
     * 语义相同的 start 因 Holder 实例不同而被当成不同键，导致重复解析。
     * 反之若恒为 0，则 miss 是真实的新 start（缓存本身没问题）。</p>
     *
     * <p>为控制开销，每 {@value #DIAG_PROBE_INTERVAL} 次 miss 才扫一次。</p>
     */
    /**
     * 0.9.010：prepared-start 命中诊断。**默认关闭**，用 {@code -Droadweaver.preparedStartDiag=true} 打开。
     *
     * <p>为什么默认关：旧实现只要 dimension/seed/chunkKey/source 四个稳定字段相同就计入"泄漏"，
     * 但同一 chunk 里本来就有多个不同 start、不同 structureSet 的 key ⇒ 大量误计（真机日志刷屏），
     * 反而让人以为存在真实的 Holder identity 问题。这是纯诊断，误报只污染日志。</p>
     *
     * <p>为什么改成"语义比较"：真正的"Holder identity 漏命中"必须连 structureSet 值也相等
     * （那就说明 record 的 equals 本该命中却没命中）。而结构上这几乎不可能发生——所以打开后
     * 数量应为 0；不为 0 才是真 bug。其余"同 chunk 不同 structureSet"的 miss 属正常，单独计数、
     * 不再冒充泄漏。</p>
     */
    private static void probeIdentityLeak(PreparedStartKey missKey) {
        if (!PREPARED_START_DIAG) return;
        long n = DIAG_MAIN_MISSES.get();
        if (n % DIAG_PROBE_INTERVAL != 0L) return;
        int sameStableFields = 0;
        int sameEverything = 0;
        int sourceDiffers = 0;
        for (PreparedStartKey cached : StructureAvoidanceCache.PREPARED_START_BOXES.keySet()) {
            if (!cached.dimension().equals(missKey.dimension())) continue;
            if (cached.seed() != missKey.seed()) continue;
            if (cached.chunkKey() != missKey.chunkKey()) continue;
            if (cached.source() != missKey.source()) {
                // 0.9.013：只差 source。原来这里直接 continue，于是这一类 miss 完全不可见 ——
                // 而真机那 0.1% 的命中率最可能就来自它。
                sourceDiffers++;
                continue;
            }
            sameStableFields++;
            if (Objects.equals(cached.structureSet(), missKey.structureSet())) sameEverything++;
        }
        if (sourceDiffers > 0) DIAG_SOURCE_DIFFERS.addAndGet(sourceDiffers);
        if (sameStableFields > 0) {
            DIAG_SAME_CHUNK_MISSES.addAndGet(sameStableFields);
            // 只有 structureSet 也相等才算"Holder identity 漏命中"；其余是同 chunk 不同 start，属正常。
            if (sameEverything > 0) {
                DIAG_IDENTITY_LEAKS.addAndGet(sameEverything);
            }
            long leaks = DIAG_IDENTITY_LEAKS.get();
            if (leaks > 0 && (leaks <= 20 || leaks % 500 == 0)) {
                LOGGER.warn("RoadWeaver prepared-start 诊断：**结构集相同却 miss（疑似 Holder identity 漏命中）** "
                                + "dim={} seed={} chunkKey={} source={} "
                                + "structureSetEqual={} sameChunkDifferentSet={} (累计真泄漏={})",
                        missKey.dimension(), missKey.seed(), missKey.chunkKey(), missKey.source(),
                        sameEverything, sameStableFields - sameEverything, leaks);
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RoadWeaver prepared-start 诊断：同 chunk 但不同 structureSet（正常 miss） "
                                + "dim={} seed={} chunkKey={} source={} sameStableFields={} "
                                + "sameChunkDifferentSet={} (累计={})",
                        missKey.dimension(), missKey.seed(), missKey.chunkKey(), missKey.source(),
                        sameStableFields, sameStableFields - sameEverything,
                        DIAG_SAME_CHUNK_MISSES.get());
            }
        }
    }

    /** 每 64 次 miss 扫描一次缓存（控制开销）。 */
    private static final long DIAG_PROBE_INTERVAL = 64L;

    /** 诊断计数器（0.8.402，验证 Holder identity 假设）。 */
    private static final AtomicLong DIAG_PINNED_HITS = new AtomicLong();
    private static final AtomicLong DIAG_MAIN_HITS = new AtomicLong();
    private static final AtomicLong DIAG_MAIN_MISSES = new AtomicLong();
    private static final AtomicLong DIAG_IDENTITY_LEAKS = new AtomicLong();
    /** 0.9.010：prepared-start 诊断开关，默认关（纯诊断，误报只会污染日志）。 */
    private static final boolean PREPARED_START_DIAG =
            Boolean.getBoolean("roadweaver.preparedStartDiag");
    /** 0.9.010：同 chunk 但 structureSet 不同（正常 miss）的累计数，与真泄漏分开计。 */
    private static final AtomicLong DIAG_SAME_CHUNK_MISSES = new AtomicLong();
    /**
     * 0.9.013：同 (dim, seed, chunkKey) 但 **source 不同** 的累计数。
     *
     * <p>为什么单列：原来的探针在 {@code cached.source() != missKey.source()} 时直接 continue，
     * 于是「只差 source」这一类 miss 完全不可见。真机一局 {@code size=7668 / hits=18 /
     * misses=15336 / 淘汰=0 / identity泄漏探测=0} —— 缓存装得下、key 也没在 Holder 身份上跑偏，
     * 那 0.1% 的命中率就只能来自「同一个起点被两轮用不同 source 记了两次」。
     * 这个计数器就是下一个要看的数。</p>
     */
    private static final AtomicLong DIAG_SOURCE_DIFFERS = new AtomicLong();
    private static final AtomicBoolean DIAG_ENABLED = new AtomicBoolean();

    /** 诊断快照（限频日志用）。 */
    private static void logPreparedStartDiagnostics() {
        long pinned = DIAG_PINNED_HITS.get();
        long hits = DIAG_MAIN_HITS.get();
        long misses = DIAG_MAIN_MISSES.get();
        long total = pinned + hits + misses;
        double hitRate = total <= 0 ? 0.0 : (pinned + hits) * 100.0 / total;
        LOGGER.info("RoadWeaver prepared-start 诊断汇总：pinned={} mainHits={} misses={} "
                        + "总命中率={}% identity泄漏探测={} 同chunk同source却miss={} 同chunk仅source不同={} "
                        + "主缓存size={} evictions={}",
                pinned, hits, misses,
                String.format(java.util.Locale.ROOT, "%.1f", hitRate),
                DIAG_IDENTITY_LEAKS.get(), DIAG_SAME_CHUNK_MISSES.get(), DIAG_SOURCE_DIFFERS.get(),
                StructureAvoidanceCache.PREPARED_START_BOXES.size(),
                PREPARED_CACHE_EVICTIONS.get());
    }

    /**
     * Initial generation reuses exact starts while grade repair and structure
     * rerouting refine the same roads. Compact only after all startup workers
     * have joined so an early start is never evicted and regenerated mid-run.
     */
    public static void compactPreparedStartCacheAfterInitialGeneration() {
        int before = StructureAvoidanceCache.PREPARED_START_BOXES.size();
        int pinned = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.size();
        for (Map.Entry<PreparedStartKey, List<OwnedStructureBox>> entry : StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.entrySet()) {
            putIfAbsentBounded(StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER,
                    entry.getKey(), entry.getValue(), PREPARED_START_CACHE_CAPACITY);
        }
        StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.clear();
        trimBounded(StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER, PREPARED_START_CACHE_CAPACITY);
        LOGGER.info("RoadWeaver exact structure cache compacted after initial generation: before={} pinned={} after={} capacity={}",
                before, pinned, StructureAvoidanceCache.PREPARED_START_BOXES.size(), PREPARED_START_CACHE_CAPACITY);
        // ★ 2026-09-26：把"是否有重复解析"的实测一并打出来。
        //   WorkBuddy §6 的 O-1/O-2（加大缓存 / 持久化解析结果）只有在这条
        //   显示"淘汰数显著"时才值得做 —— 否则纯属改动红线却零收益。
        LOGGER.info("RoadWeaver prepared-start cache stats: hits={} evictions={} capacity={} size={}",
                StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get(), PREPARED_CACHE_EVICTIONS.get(),
                PREPARED_START_CACHE_CAPACITY, StructureAvoidanceCache.PREPARED_START_BOXES.size());
        // ★ 0.8.402：同时输出诊断汇总（Holder identity 漏命中探测结果）。
        logPreparedStartDiagnostics();
    }

    /** Manual endpoints may reference starts in other chunks, so resolve both levels without blocking ticks. */
    private static CompletableFuture<List<OwnedStructureBox>> resolveManualPointBoxesAsync(
            ServerLevel level, int cx, int cz) {
        StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.incrementAndGet();
        return resolveReferenceChunkBoxesAsync(level, cx, cz);
    }

    private static CompletableFuture<List<OwnedStructureBox>> resolveReferenceChunkBoxesAsync(
            ServerLevel level, int cx, int cz) {
        var referencesFuture = requestChunkAsync(level, cx, cz, ChunkStatus.STRUCTURE_REFERENCES);
        return referencesFuture.thenCompose(result -> callOnServerThread(level, () -> {
            ChunkAccess referenceChunk = result.orElse(null);
            if (referenceChunk == null) {
                throw new IllegalStateException(
                        "Unable to load manual structure reference chunk [" + cx + "," + cz + "]");
            }
            List<ManualStartReference> references = new ArrayList<>();
            for (Map.Entry<Structure, LongSet> entry : referenceChunk.getAllReferences().entrySet()) {
                for (long startChunkLong : entry.getValue()) {
                    references.add(new ManualStartReference(entry.getKey(), startChunkLong));
                }
            }
            return List.copyOf(references);
        })).thenCompose(references -> requestManualStartChunks(level, references));
    }

    private static CompletableFuture<List<OwnedStructureBox>> requestManualStartChunks(
            ServerLevel level, List<ManualStartReference> references) {
        if (references.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return callOnServerThread(level, () -> {
            List<CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> futures =
                    new ArrayList<>(references.size());
            for (ManualStartReference reference : references) {
                ChunkPos pos = new ChunkPos(reference.chunkKey());
                futures.add(requestChunkOnServerThread(
                        level, pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS));
            }
            return new ManualStartBatch(references, futures);
        }).thenCompose(batch -> {
            CompletableFuture<?>[] pending = batch.futures().toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(pending).thenCompose(ignored -> callOnServerThread(level, () -> {
                Set<OwnedStructureBox> collected = new HashSet<>();
                for (int i = 0; i < batch.references().size(); i++) {
                    ManualStartReference reference = batch.references().get(i);
                    ChunkAccess chunk = batch.futures().get(i).join().orElse(null);
                    if (chunk == null) {
                        throw new IllegalStateException("Unable to load manual structure start chunk "
                                + new ChunkPos(reference.chunkKey()));
                    }
                    collectPieces(level, levelOwner(level, reference.structure(), reference.chunkKey()),
                            chunk.getStartForStructure(reference.structure()), collected);
                }
                return List.copyOf(collected);
            }));
        });
    }

    private static CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> requestChunkAsync(
            ServerLevel level, int cx, int cz, ChunkStatus status) {
        return callOnServerThread(level, () -> requestChunkOnServerThread(level, cx, cz, status))
                .thenCompose(future -> future);
    }

    private static CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> requestChunkOnServerThread(
            ServerLevel level, int cx, int cz, ChunkStatus status) {
        var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Chunk futures must be submitted on the server thread"));
        }
        if (InitialGenManager.isActive()) {
            try {
                // During prepareLevels there is no normal server tick yet.
                // ServerChunkCache#getChunk performs its own managed wait on
                // the chunk main-thread executor, unlike MinecraftServer#managedBlock.
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, status, true);
                return CompletableFuture.completedFuture(
                        net.minecraft.server.level.ChunkResult.of(chunk));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (!(level.getChunkSource() instanceof NonBlockingChunkFutureAccess invoker)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("RoadWeaver non-blocking chunk future invoker is unavailable"));
        }
        ChunkRequestKey requestKey = new ChunkRequestKey(
                level.dimension().location().toString(), level.getSeed(), ChunkPos.asLong(cx, cz), status);
        CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> existing =
                StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.get(requestKey);
        if (existing != null) return existing;

        CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> shared = new CompletableFuture<>();
        existing = StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.putIfAbsent(requestKey, shared);
        if (existing != null) return existing;

        long epoch = ThreadPoolManager.currentEpoch();
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        chunkSource.addRegionTicket(STRUCTURE_SNAPSHOT_TICKET, chunkPos, 0, Unit.INSTANCE);
        invoker.roadweaver$invokeGetChunkFutureMainThread(cx, cz, status, true)
                .whenComplete((result, failure) -> {
                    try {
                        if (failure != null) shared.completeExceptionally(failure);
                        else if (!ThreadPoolManager.isEpoch(epoch)) {
                            shared.completeExceptionally(new java.util.concurrent.CancellationException(
                                    "RoadWeaver world epoch changed while loading " + new ChunkPos(cx, cz)));
                        } else {
                            shared.complete(result);
                        }
                    } finally {
                        StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.remove(requestKey, shared);
                    }
                });
        shared.whenCompleteAsync((ignored, failure) ->
                chunkSource.removeRegionTicket(
                        STRUCTURE_SNAPSHOT_TICKET, chunkPos, 0, Unit.INSTANCE), server);
        return shared;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletableFuture<T> callOnServerThread(ServerLevel level, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        var server = level == null ? null : level.getServer();
        if (server == null) {
            result.completeExceptionally(new IllegalStateException("Missing server while preparing structure starts"));
            return result;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        Runnable task = () -> {
            try {
                if (!ThreadPoolManager.isEpoch(epoch)) {
                    result.completeExceptionally(new CancellationException(
                            "RoadWeaver world epoch changed before structure main-thread work"));
                    return;
                }
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (server.isSameThread()) task.run();
        else server.execute(task);
        return result;
    }

    private static int normalizeSource(int source) {
        return source == StructureFileStorage.SOURCE_MANUAL
                ? StructureFileStorage.SOURCE_MANUAL
                : StructureFileStorage.SOURCE_PREDICTED;
    }

    private static PreparedStartKey preparedStartKey(ServerLevel level,
                                                     int cx,
                                                     int cz,
                                                     int source,
                                                     Holder<StructureSet> structureSet) {
        return new PreparedStartKey(
                level.dimension().location().toString(),
                level.getSeed(),
                ChunkPos.asLong(cx, cz),
                normalizeSource(source),
                structureSet);
    }

    private static void indexConstructionBoxes(Collection<OwnedStructureBox> boxes) {
        if (boxes == null || boxes.isEmpty()) return;
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.box() == null) continue;
            BoundingBox box = owned.box();
            int minChunkX = box.minX() >> 4;
            int maxChunkX = box.maxX() >> 4;
            int minChunkZ = box.minZ() >> 4;
            int maxChunkZ = box.maxZ() >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    synchronized (StructureAvoidanceCache.CHUNK_BBOX_CACHE) {
                        ChunkEntry existing = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
                        Set<OwnedStructureBox> merged = new HashSet<>();
                        if (existing != null && existing.ready) merged.addAll(existing.boxes);
                        merged.add(owned);
                        StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Set.copyOf(merged), true));
                    }
                }
            }
        }
    }

    private static long distanceSquaredToSegment(int px, int pz, BlockPos start, BlockPos end) {
        long dx = (long) end.getX() - start.getX();
        long dz = (long) end.getZ() - start.getZ();
        if (dx == 0L && dz == 0L) {
            long ox = px - start.getX(), oz = pz - start.getZ();
            return ox * ox + oz * oz;
        }
        double t = ((px - start.getX()) * (double) dx + (pz - start.getZ()) * (double) dz)
                / (double) (dx * dx + dz * dz);
        t = Math.max(0D, Math.min(1D, t));
        double ox = px - (start.getX() + t * dx);
        double oz = pz - (start.getZ() + t * dz);
        return (long) Math.ceil(ox * ox + oz * oz);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static RouteKey routeKey(ServerLevel level, StructureConnection connection) {
        long from = new BlockPos(connection.from().getX(), 0, connection.from().getZ()).asLong();
        long to = new BlockPos(connection.to().getX(), 0, connection.to().getZ()).asLong();
        return from <= to
                ? new RouteKey(level.dimension().location().toString(), level.getSeed(), from, to)
                : new RouteKey(level.dimension().location().toString(), level.getSeed(), to, from);
    }

    private static RouteKey routeKey(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) {
            return new RouteKey("", 0L, Long.MIN_VALUE, Long.MIN_VALUE);
        }
        BlockPos from = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
        BlockPos to = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
        long first = new BlockPos(from.getX(), 0, from.getZ()).asLong();
        long second = new BlockPos(to.getX(), 0, to.getZ()).asLong();
        return first <= second
                ? new RouteKey(level.dimension().location().toString(), level.getSeed(), first, second)
                : new RouteKey(level.dimension().location().toString(), level.getSeed(), second, first);
    }

    private static Set<StructureOwnerKey> endpointOwnersForRoad(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) return Set.of();
        RouteKey key = routeKey(level, road);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot != null && snapshot != PlanningSnapshot.EMPTY) {
            StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(key, snapshot.endpointOwners());
            return snapshot.endpointOwners();
        }
        return StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.computeIfAbsent(key, ignored -> {
            BlockPos from = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
            BlockPos to = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
            StructureConnection connection = new StructureConnection(from, to);
            return resolveEndpointOwners(
                    connection, StructureFileStorage.getStructureSnapshot(level), Set.of());
        });
    }

    private static Set<StructureOwnerKey> resolveEndpointOwners(
            StructureConnection connection,
            StructureFileStorage.StructureSnapshot storageSnapshot,
            Collection<OwnedStructureBox> boxes) {
        if (connection == null) return Set.of();
        List<StructureInfo> infos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        return resolveEndpointOwnersFromInfos(connection, infos, boxes);
    }

    static Set<StructureOwnerKey> resolveEndpointOwnersForTest(
            StructureConnection connection,
            List<StructureInfo> infos,
            Collection<OwnedStructureBox> boxes) {
        return resolveEndpointOwnersFromInfos(connection, infos, boxes);
    }

    /**
     * True when the endpoint is a real village intersection. Only these use the
     * precise crossing terrain height. Spawn and non-village endpoints keep the
     * structure bounding-box height so the road climbs to the structure instead
     * of dipping to a lower crossing column (which broke the connection).
     */
    /** True when this endpoint is a known (named/village) structure location, regardless of snapshot timing. */
    public static boolean isKnownStructureEndpoint(ServerLevel level, StructureConnection connection, boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        StructureFileStorage.StructureSnapshot snapshot = StructureFileStorage.getStructureSnapshot(level);
        if (snapshot == null || snapshot.locations() == null) return false;
        for (StructureInfo info : snapshot.locations().structureInfos()) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) return true;
        }
        return false;
    }

    public static boolean isVillageEndpoint(ServerLevel level, StructureConnection connection, boolean from) {
        if (level == null || connection == null) return false;
        BlockPos endpoint = from ? connection.from() : connection.to();
        if (endpoint == null) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        if (spawn != null && spawn.getX() == endpoint.getX() && spawn.getZ() == endpoint.getZ()) {
            return false;
        }
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
                || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return false;
        long maxDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
                * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
        for (OwnedStructureBox owned : snapshot.boxes()) {
            if (owned == null || owned.box() == null || owned.owner() == null) continue;
            if (!isVillageStructure(owned.owner().structureId())) continue;
            if (horizontalDistanceSquared(owned.box(), endpoint) <= maxDistanceSquared) return true;
        }
        return false;
    }

    private static Set<StructureOwnerKey> resolveEndpointOwnersFromInfos(
            StructureConnection connection,
            List<StructureInfo> infos,
            Collection<OwnedStructureBox> boxes) {
        if (connection == null) return Set.of();
        Set<StructureOwnerKey> result = new HashSet<>();
        if (infos == null || infos.isEmpty()) {
            resolveEndpointOwner(connection.from(), List.of(), boxes, result);
            resolveEndpointOwner(connection.to(), List.of(), boxes, result);
            return result.isEmpty() ? Set.of() : Set.copyOf(result);
        }
        resolveEndpointOwner(connection.from(), infos, boxes, result);
        resolveEndpointOwner(connection.to(), infos, boxes, result);
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    private static void resolveEndpointOwner(BlockPos endpoint,
                                             List<StructureInfo> infos,
                                             Collection<OwnedStructureBox> boxes,
                                             Set<StructureOwnerKey> result) {
        if (endpoint == null) return;
        StructureInfo identity = null;
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) {
                identity = info;
                break;
            }
        }
        if (identity == null) {
            InferredEndpointOwner inferred = inferNearbyEndpointOwner(endpoint, boxes);
            if (inferred != null) {
                result.add(inferred.owner());
                LOGGER.info("RoadWeaver inferred endpoint owner from nearby verified building endpoint={} owner={} distance={}",
                        endpoint, inferred.owner(), Math.sqrt(inferred.distanceSquared()));
            }
            return;
        }
        StructureOwnerKey expected = new StructureOwnerKey(
                ChunkPos.asLong(identity.pos().getX() >> 4, identity.pos().getZ() >> 4),
                identity.structureId());
        // A selected endpoint is allowed to receive its own road.  The owner
        // key is still exact (structure start chunk plus registry id), so a
        // nearby instance of the same type remains protected.
        result.add(expected);
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        for (OwnedStructureBox owned : boxes) {
            if (owned != null && expected.equals(owned.owner())) {
                result.add(expected);
                return;
            }
        }

        Map<StructureOwnerKey, BoundingBox> matching = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.owner() == null || owned.box() == null) continue;
            matching.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        StructureOwnerKey unique = null;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : matching.entrySet()) {
            if (!identity.structureId().equals(entry.getKey().structureId())) continue;
            if (!containsHorizontal(entry.getValue(), endpoint)) continue;
            if (unique != null && !unique.equals(entry.getKey())) return;
            unique = entry.getKey();
        }
        if (unique != null) {
            result.add(unique);
            return;
        }

        // Some worldgen packs replace a vanilla predicted village with a
        // namespaced variant (for example minecraft:village_taiga becomes
        // ctov:small/village_taiga).  The selected coordinate is still the
        // same logical endpoint, but the registry id and start chunk identity
        // differ. Match only a unique, nearby start with the same terminal
        // family name; unrelated structures remain protected.
        String family = endpointStructureFamily(identity.structureId());
        if (family.isEmpty()) return;
        StructureOwnerKey replacement = null;
        long replacementDistance = Long.MAX_VALUE;
        boolean ambiguous = false;
        long maximumDistanceSquared = (long) ENDPOINT_REPLACEMENT_MATCH_BLOCKS
                * ENDPOINT_REPLACEMENT_MATCH_BLOCKS;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : matching.entrySet()) {
            StructureOwnerKey owner = entry.getKey();
            if (owner == null || !family.equals(endpointStructureFamily(owner.structureId()))) continue;
            long distance = horizontalDistanceSquared(entry.getValue(), endpoint);
            if (distance > maximumDistanceSquared) continue;
            if (distance < replacementDistance) {
                replacement = owner;
                replacementDistance = distance;
                ambiguous = false;
            } else if (distance == replacementDistance && replacement != null && !replacement.equals(owner)) {
                ambiguous = true;
            }
        }
        if (replacement != null && !ambiguous) {
            result.add(replacement);
            if (ENDPOINT_OWNER_LOGGED.add(endpoint.toString())) {
                LOGGER.info("RoadWeaver remapped selected endpoint owner endpoint={} registered={} actual={} distance={}",
                        endpoint, identity.structureId(), replacement.structureId(), Math.sqrt(replacementDistance));
            }
            return;
        }

        // A number of worldgen packs replace a predicted village start with a
        // differently named template structure. The generated building is
        // still the selected endpoint when it is the only verified building
        // immediately beside that endpoint. Treat it as the road exit only;
        // buildings farther away and ambiguous neighbours remain protected.
        InferredEndpointOwner inferred = inferNearbyEndpointOwner(endpoint, boxes);
        if (inferred != null) {
            result.add(inferred.owner());
            if (ENDPOINT_OWNER_LOGGED.add(endpoint.toString())) {
                LOGGER.info("RoadWeaver inferred endpoint exit owner endpoint={} registered={} actual={} distance={}",
                        endpoint, identity.structureId(), inferred.owner().structureId(),
                        Math.sqrt(inferred.distanceSquared()));
            }
        }
    }

    private static InferredEndpointOwner inferNearbyEndpointOwner(
            BlockPos endpoint, Collection<OwnedStructureBox> boxes) {
        if (endpoint == null || boxes == null || boxes.isEmpty()) return null;
        Map<StructureOwnerKey, BoundingBox> merged = new HashMap<>();
        for (OwnedStructureBox owned : boxes) {
            if (!isVerifiedBuilding(owned) || owned.owner() == null || owned.box() == null) continue;
            if (!isVillageStructure(owned.owner().structureId())) continue;
            merged.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
        }
        long maximumDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
                * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
        StructureOwnerKey nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (Map.Entry<StructureOwnerKey, BoundingBox> entry : merged.entrySet()) {
            long distance = horizontalDistanceSquared(entry.getValue(), endpoint);
            if (distance > maximumDistanceSquared) continue;
            if (distance < nearestDistance) {
                nearest = entry.getKey();
                nearestDistance = distance;
                ambiguous = false;
            } else if (distance == nearestDistance && nearest != null
                    && !nearest.equals(entry.getKey())) {
                ambiguous = true;
            }
        }
        return nearest == null || ambiguous
                ? null
                : new InferredEndpointOwner(nearest, nearestDistance);
    }

    private record InferredEndpointOwner(StructureOwnerKey owner, long distanceSquared) {
    }

    private static StructureOwnerKey levelOwner(ServerLevel level, Structure structure, long startChunkKey) {
        String structureId = "unknown";
        if (level != null && structure != null) {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var key = registry.getKey(structure);
            if (key != null) structureId = key.toString();
        }
        return new StructureOwnerKey(startChunkKey, structureId);
    }

    private static BoundingBox encapsulate(BoundingBox left, BoundingBox right) {
        return new BoundingBox(
                Math.min(left.minX(), right.minX()),
                Math.min(left.minY(), right.minY()),
                Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()),
                Math.max(left.maxY(), right.maxY()),
                Math.max(left.maxZ(), right.maxZ()));
    }

    private static boolean containsHorizontal(BoundingBox box, BlockPos pos) {
        return box != null && pos != null
                && pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
    }

    private static String endpointStructureFamily(String structureId) {
        if (structureId == null || structureId.isBlank()) return "";
        int namespace = structureId.indexOf(':');
        String path = namespace >= 0 ? structureId.substring(namespace + 1) : structureId;
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private static boolean isVillageStructure(String structureId) {
        return structureId != null && structureId.toLowerCase(java.util.Locale.ROOT).contains("village");
    }

    private static long horizontalDistanceSquared(BoundingBox box, BlockPos pos) {
        if (box == null || pos == null) return Long.MAX_VALUE;
        long dx = pos.getX() < box.minX() ? (long) box.minX() - pos.getX()
                : pos.getX() > box.maxX() ? (long) pos.getX() - box.maxX() : 0L;
        long dz = pos.getZ() < box.minZ() ? (long) box.minZ() - pos.getZ()
                : pos.getZ() > box.maxZ() ? (long) pos.getZ() - box.maxZ() : 0L;
        return dx * dx + dz * dz;
    }

    private static long horizontalDistanceSquared(BlockPos left, BlockPos right) {
        if (left == null || right == null) return Long.MAX_VALUE;
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    public interface PlanningScope extends AutoCloseable {
        @Override
        void close();
    }

    record RouteKey(String dimension, long seed, long first, long second) {
    }

    record PreparedStartKey(String dimension,
                                    long seed,
                                    long chunkKey,
                                    int source,
                                    Holder<StructureSet> structureSet) {
    }

    public static record StructureOwnerKey(long startChunkKey, String structureId) {
    }

    public static record OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, PieceOccupancy occupancy) {
        OwnedStructureBox(StructureOwnerKey owner, BoundingBox box) {
            this(owner, box, (PieceOccupancy) null);
        }

        OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, Map<BlockPos, BlockState> testBlocks) {
            this(owner, box, PieceOccupancy.forTest(testBlocks));
        }

        BoundingBox collisionBox() {
            if (occupancy == null) return box;
            BoundingBox resolved = occupancy.collisionBounds();
            return resolved == null ? box : resolved;
        }

        boolean intersects(int x, int z, int minY, int maxY, int horizontalRadius) {
            if (box == null || x < box.minX() - horizontalRadius || x > box.maxX() + horizontalRadius
                    || z < box.minZ() - horizontalRadius || z > box.maxZ() + horizontalRadius
                    || maxY < box.minY() || minY > box.maxY()) return false;
            // A fallback box is metadata only. Planning deliberately ignores
            // it, so final validation must not turn it into a hard collision.
            if (occupancy == null) return false;
            Boolean exact = occupancy.intersects(x, z, minY, maxY, horizontalRadius);
            return Boolean.TRUE.equals(exact);
        }

        boolean intersectsSolid(int x, int z, int minY, int maxY) {
            if (box == null || x < box.minX() || x > box.maxX()
                    || z < box.minZ() || z > box.maxZ()
                    || maxY < box.minY() || minY > box.maxY()) return false;
            // Unknown occupancy is not proof of a real building block.
            if (occupancy == null) return false;
            Boolean exact = occupancy.intersectsSolid(x, z, minY, maxY);
            return Boolean.TRUE.equals(exact);
        }

        FallingCapOccupancy fallingCapOccupancyAt(int x, int y, int z) {
            if (box == null || x < box.minX() || x > box.maxX()
                    || y < box.minY() || y > box.maxY()
                    || z < box.minZ() || z > box.maxZ()) return FallingCapOccupancy.AIR;
            return occupancy == null
                    ? FallingCapOccupancy.UNKNOWN
                    : occupancy.fallingCapOccupancyAt(x, y, z);
        }

        String occupancyKind() {
            return occupancy == null ? "fallback-box" : occupancy.kind();
        }
    }

    record PredictedCandidateKey(long chunkKey, Holder<StructureSet> structureSet) {
    }

    private static final class NativeDirectCircuitOpenException extends RuntimeException {
        private NativeDirectCircuitOpenException() {
            super("native structure prediction circuit is open", null, false, false);
        }
    }

    record ChunkRequestKey(String dimension, long seed, long chunkKey, ChunkStatus status) {
    }

    record RuntimePreloadKey(String dimension, long seed, long chunkKey) {
    }

    private record ManualStartReference(Structure structure, long chunkKey) {
    }

    private record ManualStartBatch(
            List<ManualStartReference> references,
            List<CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> futures) {
    }

    record ObstacleTileKey(String dimension, long seed, int tileX, int tileZ) {
    }

    private record RoutePredictionBounds(StructureConnection connection,
                                         int minChunkX,
                                         int maxChunkX,
                                         int minChunkZ,
                                         int maxChunkZ,
                                         int minTileX,
                                         int maxTileX,
                                         int minTileZ,
                                         int maxTileZ,
                                         long reachSquared) {
    }

    private record RouteStartPlan(StructureConnection connection,
                                  Set<PreparedStartKey> starts,
                                  Set<Long> coveredCandidateChunks) {
    }

    private record PathCorridorPlan(StructureConnection connection,
                                    List<BlockPos> path,
                                    Set<Long> pathChunks,
                                    LongSet candidateChunks) {
    }

    private record SnapshotPreparation(Map<RouteKey, RouteStartPlan> routePlans,
                                       List<PreparedStartKey> uniqueStarts) {
    }

    public static record PlanningSnapshot(Set<OwnedStructureBox> boxes,
                                     Map<Long, List<OwnedStructureBox>> boxesByChunk,
                                     Set<StructureOwnerKey> endpointOwners,
                                     Set<StructureOwnerKey> forcedVerticalOwners,
                                     Set<PreparedStartKey> resolvedStarts,
                                     LongSet coveredCandidateChunks,
                                     int roadRadius) {
        public static final PlanningSnapshot EMPTY = new PlanningSnapshot(
                Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), LongSets.emptySet(), 0);

        static PlanningSnapshot forRoute(StructureConnection connection, Collection<OwnedStructureBox> boxes) {
            return forRoute(connection, boxes, Set.of());
        }

        static PlanningSnapshot forRoute(StructureConnection connection,
                                         Collection<OwnedStructureBox> boxes,
                                         Collection<PreparedStartKey> resolvedStarts) {
            return forRoute(connection, boxes, resolvedStarts, Set.of());
        }

        static PlanningSnapshot forRoute(StructureConnection connection,
                                         Collection<OwnedStructureBox> boxes,
                                         Collection<PreparedStartKey> resolvedStarts,
                                         Collection<Long> coveredCandidateChunks) {
            return forRoute(connection, boxes, resolvedStarts, coveredCandidateChunks, null);
        }

        static PlanningSnapshot forRoute(StructureConnection connection,
                                         Collection<OwnedStructureBox> boxes,
                                         Collection<PreparedStartKey> resolvedStarts,
                                         Collection<Long> coveredCandidateChunks,
                                         Collection<StructureOwnerKey> explicitEndpointOwners) {
            int roadWidth = Math.max(1, ConfigService.get().roadAppearance().roadWidth());
            // Match the rasterized road footprint. The construction planner
            // covers ceil(width / 2) columns from the centerline, including
            // the far edge of even-width roads.
            int roadRadius = Math.max(0, (int) Math.ceil(roadWidth / 2.0D));
            Set<PreparedStartKey> immutableStarts = resolvedStarts == null || resolvedStarts.isEmpty()
                    ? Set.of()
                    : Set.copyOf(resolvedStarts);
            LongSet immutableCoveredChunks;
            if (coveredCandidateChunks == null || coveredCandidateChunks.isEmpty()) {
                immutableCoveredChunks = LongSets.emptySet();
            } else {
                immutableCoveredChunks = LongSets.unmodifiable(new LongOpenHashSet(coveredCandidateChunks));
            }
            Set<StructureOwnerKey> immutableEndpointOwners = explicitEndpointOwners == null
                    ? null
                    : explicitEndpointOwners.isEmpty() ? Set.of() : Set.copyOf(explicitEndpointOwners);
            if (boxes == null || boxes.isEmpty()) {
                return new PlanningSnapshot(
                        Set.of(), Map.of(), immutableEndpointOwners == null ? Set.of() : immutableEndpointOwners,
                        Set.of(), immutableStarts, immutableCoveredChunks, roadRadius);
            }
            Set<OwnedStructureBox> immutableBoxes = Set.copyOf(boxes);
            Set<StructureOwnerKey> endpointOwners = immutableEndpointOwners == null
                    ? findEndpointOwners(connection, immutableBoxes)
                    : immutableEndpointOwners;
            endpointOwners = expandEndpointVillageOwners(connection, immutableBoxes, endpointOwners);
            Map<Long, List<OwnedStructureBox>> mutable = new HashMap<>();
            for (OwnedStructureBox owned : immutableBoxes) {
                if (owned == null || owned.box() == null) continue;
                BoundingBox box = owned.box();
                for (int cx = (box.minX() - roadRadius) >> 4;
                     cx <= (box.maxX() + roadRadius) >> 4; cx++) {
                    for (int cz = (box.minZ() - roadRadius) >> 4;
                         cz <= (box.maxZ() + roadRadius) >> 4; cz++) {
                        mutable.computeIfAbsent(ChunkPos.asLong(cx, cz), ignored -> new ArrayList<>()).add(owned);
                    }
                }
            }
            Map<Long, List<OwnedStructureBox>> immutable = new HashMap<>();
            for (Map.Entry<Long, List<OwnedStructureBox>> entry : mutable.entrySet()) {
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new PlanningSnapshot(
                    immutableBoxes, Map.copyOf(immutable), endpointOwners, Set.of(), immutableStarts,
                    immutableCoveredChunks, roadRadius);
        }

        static PlanningSnapshot merge(StructureConnection connection,
                                       PlanningSnapshot previous,
                                       Collection<OwnedStructureBox> added,
                                       Collection<PreparedStartKey> addedStarts,
                                       Collection<Long> addedCoveredChunks) {
            return merge(connection, previous, added, addedStarts, addedCoveredChunks, null);
        }

        static PlanningSnapshot merge(StructureConnection connection,
                                       PlanningSnapshot previous,
                                       Collection<OwnedStructureBox> added,
                                       Collection<PreparedStartKey> addedStarts,
                                       Collection<Long> addedCoveredChunks,
                                       Collection<StructureOwnerKey> explicitEndpointOwners) {
            if (previous == null || previous == EMPTY || previous.boxes().isEmpty()) {
                Set<PreparedStartKey> starts = new HashSet<>();
                if (previous != null) starts.addAll(previous.resolvedStarts());
                if (addedStarts != null) starts.addAll(addedStarts);
                LongSet coveredChunks = new LongOpenHashSet();
                if (previous != null) coveredChunks.addAll(previous.coveredCandidateChunks());
                if (addedCoveredChunks != null) {
                    for (long chunk : addedCoveredChunks) coveredChunks.add(chunk);
                }
                PlanningSnapshot created = forRoute(
                        connection, added, starts, coveredChunks, explicitEndpointOwners);
                if (previous != null && !previous.forcedVerticalOwners().isEmpty()) {
                    return created.withForcedVerticalOwners(previous.forcedVerticalOwners());
                }
                return created;
            }
            boolean boxesUnchanged = added == null || added.isEmpty() || previous.boxes().containsAll(added);
            boolean startsUnchanged = addedStarts == null || addedStarts.isEmpty()
                    || previous.resolvedStarts().containsAll(addedStarts);
            boolean coveredChunksUnchanged = addedCoveredChunks == null || addedCoveredChunks.isEmpty()
                    || previous.coveredCandidateChunks().containsAll(addedCoveredChunks);
            boolean endpointOwnersUnchanged = explicitEndpointOwners == null
                    || previous.endpointOwners().equals(Set.copyOf(explicitEndpointOwners));
            if (boxesUnchanged && startsUnchanged && coveredChunksUnchanged && endpointOwnersUnchanged) {
                return previous;
            }
            Set<OwnedStructureBox> merged = new HashSet<>(previous.boxes());
            if (added != null) merged.addAll(added);
            Set<PreparedStartKey> mergedStarts = new HashSet<>(previous.resolvedStarts());
            if (addedStarts != null) mergedStarts.addAll(addedStarts);
            LongSet mergedCoveredChunks = new LongOpenHashSet(previous.coveredCandidateChunks());
            if (addedCoveredChunks != null) {
                for (long chunk : addedCoveredChunks) mergedCoveredChunks.add(chunk);
            }
            PlanningSnapshot result = forRoute(
                    connection, merged, mergedStarts, mergedCoveredChunks, explicitEndpointOwners);
            return result.withForcedVerticalOwners(previous.forcedVerticalOwners());
        }

        boolean blocks(BlockPos pos) {
            return blocks(pos, roadRadius);
        }

        boolean blocksFootprint(BlockPos pos) {
            return blocks(pos, 0);
        }

        /**
         * Endpoint structures are excluded from ordinary avoidance so a road
         * can reach them. Endpoint trimming needs the opposite query: only a
         * decoded real building inside an endpoint structure should stop the
         * final approach.
         */
        boolean blocksEndpointBuildingFootprint(BlockPos pos) {
            if (pos == null) return false;
            int minY = pos.getY() - CONSTRUCTION_BELOW;
            int maxY = pos.getY() + CONSTRUCTION_ABOVE;
            return findEndpointBuildingCollision(pos.getX(), pos.getZ(), minY, maxY, 0) != null;
        }

        boolean blocksSegmentFootprint(RoadSegmentPlacement segment) {
            if (segment == null) return false;
            BlockPos center = segment.middlePos();
            if (segment.positions() != null && !segment.positions().isEmpty()) {
                for (BlockPos footprint : segment.positions()) {
                    if (footprint == null) continue;
                    BlockPos constructionPos = center == null
                            ? footprint
                            : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                    if (blocksFootprint(constructionPos)) return true;
                }
                return false;
            }
            return center != null && blocks(center);
        }

        boolean blocksEndpointBuildingSegmentFootprint(RoadSegmentPlacement segment) {
            if (segment == null) return false;
            BlockPos center = segment.middlePos();
            if (segment.positions() != null && !segment.positions().isEmpty()) {
                for (BlockPos footprint : segment.positions()) {
                    if (footprint == null) continue;
                    BlockPos constructionPos = center == null
                            ? footprint
                            : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                    if (blocksEndpointBuildingFootprint(constructionPos)) return true;
                }
                return false;
            }
            return center != null && blocksEndpointBuildingFootprint(center);
        }

        boolean hasUnresolvedSegmentFootprint(RoadSegmentPlacement segment) {
            if (segment == null) return false;
            BlockPos center = segment.middlePos();
            if (segment.positions() != null && !segment.positions().isEmpty()) {
                for (BlockPos footprint : segment.positions()) {
                    if (footprint == null) continue;
                    BlockPos constructionPos = center == null
                            ? footprint
                            : new BlockPos(footprint.getX(), center.getY(), footprint.getZ());
                    if (hasUnresolvedBlock(constructionPos)) return true;
                }
                return false;
            }
            return center != null && hasUnresolvedBlock(center);
        }

        boolean hasUnresolvedBlock(BlockPos pos) {
            if (pos == null) return false;
            int minY = pos.getY() - CONSTRUCTION_BELOW;
            int maxY = pos.getY() + CONSTRUCTION_ABOVE;
            OwnedStructureBox owned = findCollision(pos.getX(), pos.getZ(), minY, maxY, 0);
            return owned != null && "fallback-box".equals(owned.occupancyKind());
        }

        private boolean blocks(BlockPos pos, int horizontalRadius) {
            if (pos == null) return false;
            int minY = pos.getY() - CONSTRUCTION_BELOW;
            int maxY = pos.getY() + CONSTRUCTION_ABOVE;
            return findBuildingCollision(pos.getX(), pos.getZ(), minY, maxY, horizontalRadius) != null;
        }

        /** 村庄群聚合盒判定（端点结构豁免）。 */
        boolean blocksCluster(int x, int z, int minY, int maxY, int horizontalRadius) {
            Map<StructureOwnerKey, BoundingBox> clusters = clusterBoxes(this);
            if (clusters.isEmpty()) return false;
            int radius = Math.max(0, horizontalRadius);
            for (BoundingBox box : clusters.values()) {
                if (box.maxX() < x - radius || box.minX() > x + radius) continue;
                if (box.maxZ() < z - radius || box.minZ() > z + radius) continue;
                if (box.maxY() < minY || box.minY() > maxY) continue;
                return true;
            }
            return false;
        }

        public OwnedStructureBox findCollision(int x, int z, int minY, int maxY) {
            return findCollision(x, z, minY, maxY, 0);
        }

        public OwnedStructureBox findFallingCapCollision(int x, int z, int minY, int maxY) {
            if (boxesByChunk.isEmpty() || minY > maxY) return null;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return null;
            for (int y = minY; y <= maxY; y++) {
                boolean solidStop = false;
                for (OwnedStructureBox owned : candidates) {
                    if (owned == null) continue;
                    FallingCapOccupancy occupancy = owned.fallingCapOccupancyAt(x, y, z);
                    if (ownsEndpoint(owned.owner())) {
                        // Endpoint structures are allowed to be modified, but
                        // an untouched solid voxel still physically stops the
                        // runtime falling-column scan.
                        solidStop |= occupancy == FallingCapOccupancy.SOLID;
                        continue;
                    }
                    if (occupancy == FallingCapOccupancy.REMOVABLE) return owned;
                    solidStop |= occupancy == FallingCapOccupancy.SOLID;
                }
                if (solidStop) return null;
                if (y == Integer.MAX_VALUE) break;
            }
            return null;
        }

        private OwnedStructureBox findCollision(int x, int z, int minY, int maxY, int horizontalRadius) {
            if (boxesByChunk.isEmpty()) return null;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return null;
            for (OwnedStructureBox owned : candidates) {
                if (owned == null || ownsEndpoint(owned.owner())) continue;
                if (forcedVerticalOwners.contains(owned.owner())) {
                    // A forced-vertical obstacle blocks every band that can
                    // reach its box. A band entirely below the box floor (an
                    // underground tunnel passing under a surface structure)
                    // only has to clear the foundation, which the ordinary
                    // band-overlap test already guarantees; it must not be
                    // hard-rerouted by the footprint (task 6).
                    BoundingBox box = owned.box();
                    if (box != null && maxY >= box.minY()
                            && owned.intersects(x, z, box.minY(), box.maxY(), horizontalRadius)) {
                        return owned;
                    }
                } else if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) {
                    return owned;
                }
            }
            return null;
        }

        private OwnedStructureBox findBuildingCollision(int x, int z, int minY, int maxY,
                                                         int horizontalRadius) {
            if (boxesByChunk.isEmpty()) return null;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return null;
            for (OwnedStructureBox owned : candidates) {
                if (!isVerifiedBuilding(owned) || ownsEndpoint(owned.owner())) continue;
                if (forcedVerticalOwners.contains(owned.owner())) {
                    // Same underground-pass rule as findCollision: a band lying
                    // entirely below the structure floor is judged by the
                    // ordinary band overlap, not by the full footprint.
                    BoundingBox box = owned.box();
                    if (box != null && maxY >= box.minY()
                            && owned.intersects(x, z, box.minY(), box.maxY(), horizontalRadius)) return owned;
                } else if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) {
                    return owned;
                }
            }
            return null;
        }

        private OwnedStructureBox findEndpointBuildingCollision(int x, int z, int minY, int maxY,
                                                                 int horizontalRadius) {
            if (boxesByChunk.isEmpty()) return null;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return null;
            for (OwnedStructureBox owned : candidates) {
                if (!isVerifiedBuilding(owned) || !ownsEndpoint(owned.owner())) continue;
                if (intersectsPlanningBand(owned, x, z, minY, maxY, horizontalRadius)) return owned;
            }
            return null;
        }

        boolean ownsEndpoint(StructureOwnerKey owner) {
            return owner != null && endpointOwners.contains(owner);
        }

        /**
         * 端点 XZ 是否落在快照里任意结构（含建筑片）盒的 XZ 范围内。
         * 供规划前过滤使用：不涉及 Y 与施工高度带，只回答"这个坐标是不是
         * 处在某个已解析结构的水平范围里"。
         */
        boolean insideAnyStructureBox(int x, int z) {
            if (boxesByChunk.isEmpty()) return false;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return false;
            for (OwnedStructureBox owned : candidates) {
                if (owned == null || owned.box() == null) continue;
                if (insideXZ(owned.box(), x, z)) return true;
            }
            return false;
        }

        /**
         * 端点 XZ 所属的村庄 owner（聚合该村庄全部建筑片盒后判定 XZ 包含）。
         * 同一点落在多个村庄聚合盒内时返回 null（歧义，不参与过滤）。
         */
        StructureOwnerKey villageOwnerAt(int x, int z) {
            if (boxesByChunk.isEmpty()) return null;
            List<OwnedStructureBox> candidates = boxesByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates == null || candidates.isEmpty()) return null;
            Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
            for (OwnedStructureBox owned : candidates) {
                if (owned == null || owned.owner() == null || owned.box() == null) continue;
                if (!isVillageStructure(owned.owner().structureId())) continue;
                aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
            }
            StructureOwnerKey unique = null;
            for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
                if (!insideXZ(entry.getValue(), x, z)) continue;
                if (unique != null && !unique.equals(entry.getKey())) return null;
                unique = entry.getKey();
            }
            return unique;
        }

        String describeCollision(BlockPos pos) {
            if (pos == null) return "position=null";
            int minY = pos.getY() - CONSTRUCTION_BELOW;
            int maxY = pos.getY() + CONSTRUCTION_ABOVE;
            OwnedStructureBox owned = findCollision(pos.getX(), pos.getZ(), minY, maxY, roadRadius);
            if (owned == null) {
                return "position=" + pos + " collision=none endpointOwners=" + endpointOwners.size();
            }
            BoundingBox box = owned.box();
            String boxText = box == null ? "null" : "["
                    + box.minX() + "," + box.minY() + "," + box.minZ() + " -> "
                    + box.maxX() + "," + box.maxY() + "," + box.maxZ() + "]";
            return "position=" + pos + " band=" + minY + ".." + maxY
                    + " owner=" + owned.owner() + " box=" + boxText
                    + " occupancy=" + owned.occupancyKind()
                    + " endpointOwner=" + ownsEndpoint(owned.owner());
        }

        String describeCollision(BlockPos from, BlockPos to) {
            if (from == null || to == null) return "edge=" + from + " -> " + to;
            double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
            int samples = Math.max(1, (int) Math.ceil(distance));
            for (int i = 0; i <= samples; i++) {
                double t = i / (double) samples;
                BlockPos sample = new BlockPos(
                        (int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                        (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                        (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
                int minY = sample.getY() - CONSTRUCTION_BELOW;
                int maxY = sample.getY() + CONSTRUCTION_ABOVE;
                if (findCollision(sample.getX(), sample.getZ(), minY, maxY, roadRadius) != null) {
                    return "edge=" + from + " -> " + to + " " + describeCollision(sample);
                }
            }
            return "edge=" + from + " -> " + to + " collision=none endpointOwners=" + endpointOwners.size();
        }

        List<RoadSegmentPlacement> stopAtVillageBuilding(StructureConnection connection,
                                                         List<RoadSegmentPlacement> segments) {
            if (connection == null || segments == null || segments.size() < 3
                    || endpointOwners.isEmpty() || boxes.isEmpty()) return segments;
            BlockPos firstCenter = segments.getFirst().middlePos();
            BlockPos lastCenter = segments.getLast().middlePos();
            List<OwnedStructureBox> firstVillage = endpointVillageBoxes(
                    closestEndpoint(connection, firstCenter, true));
            List<OwnedStructureBox> lastVillage = endpointVillageBoxes(
                    closestEndpoint(connection, lastCenter, false));

            // Land each end at the structure-boundary interaction point: the
            // first station that exits the village's aggregate bounding box.
            // This stops the road at the village edge instead of running to its
            // centre (which drove tunnels into the mountain and flattened the
            // terrain inside the village).
            int first = firstOutsideVillageBox(segments, firstVillage, true);
            int last = firstOutsideVillageBox(segments, lastVillage, false);
            VillageBuildingCollision firstCollision = null;
            VillageBuildingCollision lastCollision = null;
            // Prefer landing at the structure boundary. Only fall back to the
            // first real building when the road never leaves the boundary box.
            if (first < 0) {
                firstCollision = firstVillageBuildingCollision(
                        segments, 0, 1, firstVillage);
                first = firstCollision == null ? 0 : firstCollision.segmentIndex() + 1;
            }
            if (last < 0) {
                lastCollision = firstVillageBuildingCollision(
                        segments, segments.size() - 1, -1, lastVillage);
                last = lastCollision == null
                        ? segments.size() - 1 : lastCollision.segmentIndex() - 1;
            }

            if (first == 0 && last == segments.size() - 1) return segments;
            if (first < 0 || last >= segments.size() || last - first + 1 < 2) {
                LOGGER.warn("RoadWeaver village building approach left no safe road connection={}->{} "
                                + "firstCollision={} lastCollision={}",
                        connection.from(), connection.to(), firstCollision, lastCollision);
                return null;
            }
            List<RoadSegmentPlacement> clipped = List.copyOf(segments.subList(first, last + 1));
            LOGGER.info("RoadWeaver landed at structure boundary connection={}->{} segments={} -> {} "
                            + "first={} last={} firstBuildingCollision={} lastBuildingCollision={}",
                    connection.from(), connection.to(), segments.size(), clipped.size(),
                    first, last, firstCollision, lastCollision);
            return clipped;
        }

        private int firstOutsideVillageBox(List<RoadSegmentPlacement> segments,
                                           List<OwnedStructureBox> village,
                                           boolean fromStart) {
            if (segments == null || village == null || village.isEmpty()) return -1;
            BoundingBox box = aggregateStructureBox(village);
            if (box == null) return -1;
            if (fromStart) {
                for (int i = 0; i < segments.size(); i++) {
                    BlockPos pos = segments.get(i).middlePos();
                    if (pos != null && !insideXZ(box, pos.getX(), pos.getZ())) return i;
                }
            } else {
                for (int i = segments.size() - 1; i >= 0; i--) {
                    BlockPos pos = segments.get(i).middlePos();
                    if (pos != null && !insideXZ(box, pos.getX(), pos.getZ())) return i;
                }
            }
            return -1;
        }

        private static boolean insideXZ(BoundingBox box, int x, int z) {
            return box != null && x >= box.minX() && x <= box.maxX()
                    && z >= box.minZ() && z <= box.maxZ();
        }

        /**
         * T-23: 过路村庄聚合盒掩码。对每个"非端点 + 村庄类"owner 求聚合盒,
         * 站点 XZ 落入盒内且施工高度带([y-CONSTRUCTION_BELOW, y+CONSTRUCTION_ABOVE])
         * 与盒 Y 范围相交时标记为真 —— 即道路施工体积真正触及村庄 aggregated
         * 边界时才产生缺口,低空/深埋穿越不受影响。端点村庄(endpointOwners)
         * 被显式排除:它们由 stopAtVillageBuilding 的着陆逻辑处理,避免把真正
         * 的村庄端点裁剪掉(requirement 5)。
         */
        private boolean[] passThroughVillageGapMask(List<BlockPos> points) {
            boolean[] mask = new boolean[points == null ? 0 : points.size()];
            try {
                if (!ConfigService.get().planning().passThroughVillageClipping()) return mask;
            } catch (Throwable ignored) {
                return mask;
            }
            Map<StructureOwnerKey, BoundingBox> passThrough = passThroughVillageBoxes();
            if (passThrough.isEmpty()) return mask;
            // 按用户口径：只要道路中心线在 XZ 上穿过"非端点村庄"的聚合包围盒，
            // 就按该盒把路裁开（站点落为保护缺口），不再要求施工高度带与盒 Y 相交。
            // 之前的 Y 判定会放过"从村子上/下方掠过"的路线，而剖面调整（坡度/洞口
            // 规划）之后它们又会落进建筑盒，只能等结构修复事后补救、绕不开就留断口。
            int clipped = 0;
            for (int i = 0; i < mask.length; i++) {
                BlockPos point = points.get(i);
                if (point == null) continue;
                for (BoundingBox box : passThrough.values()) {
                    if (insideXZ(box, point.getX(), point.getZ())) {
                        mask[i] = true;
                        clipped++;
                        break;
                    }
                }
            }
            if (clipped > 0) {
                LOGGER.info("RoadWeaver pass-through village clip: stations={}/{} villageBoxes={}",
                        clipped, mask.length, passThrough.size());
            }
            return mask;
        }

        private Map<StructureOwnerKey, BoundingBox> passThroughVillageBoxes() {
            Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
            for (OwnedStructureBox owned : boxes) {
                if (owned == null || owned.owner() == null || owned.box() == null
                        || endpointOwners.contains(owned.owner())
                        || !isVillageStructure(owned.owner().structureId())) continue;
                aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
            }
            return aggregate;
        }

        private static BoundingBox aggregateStructureBox(List<OwnedStructureBox> village) {
            if (village == null || village.isEmpty()) return null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (OwnedStructureBox owned : village) {
                if (owned == null || owned.box() == null) continue;
                BoundingBox b = owned.box();
                minX = Math.min(minX, b.minX()); minY = Math.min(minY, b.minY()); minZ = Math.min(minZ, b.minZ());
                maxX = Math.max(maxX, b.maxX()); maxY = Math.max(maxY, b.maxY()); maxZ = Math.max(maxZ, b.maxZ());
            }
            if (minX == Integer.MAX_VALUE) return null;
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private List<OwnedStructureBox> endpointVillageBoxes(BlockPos endpoint) {
            if (endpoint == null) return List.of();
            Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
            for (OwnedStructureBox owned : boxes) {
                if (owned == null || owned.owner() == null || owned.box() == null
                        || !endpointOwners.contains(owned.owner())
                        || !isVillageStructure(owned.owner().structureId())) continue;
                aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
            }
            long maximumDistanceSquared = (long) ENDPOINT_REPLACEMENT_MATCH_BLOCKS
                    * ENDPOINT_REPLACEMENT_MATCH_BLOCKS;
            long nearestDistance = Long.MAX_VALUE;
            Set<StructureOwnerKey> nearestOwners = new HashSet<>();
            for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
                long distance = StructureAvoidanceService.horizontalDistanceSquared(entry.getValue(), endpoint);
                if (distance > maximumDistanceSquared) continue;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestOwners.clear();
                    nearestOwners.add(entry.getKey());
                } else if (distance == nearestDistance) {
                    nearestOwners.add(entry.getKey());
                }
            }
            if (nearestOwners.isEmpty()) return List.of();
            List<OwnedStructureBox> selected = new ArrayList<>();
            for (OwnedStructureBox owned : boxes) {
                if (owned != null && nearestOwners.contains(owned.owner())) selected.add(owned);
            }
            return List.copyOf(selected);
        }

        private static BlockPos closestEndpoint(StructureConnection connection,
                                                BlockPos position,
                                                boolean preferFrom) {
            if (connection == null || position == null) return null;
            long fromDistance = horizontalDistanceSquared(connection.from(), position);
            long toDistance = horizontalDistanceSquared(connection.to(), position);
            if (fromDistance == toDistance) return preferFrom ? connection.from() : connection.to();
            return fromDistance < toDistance ? connection.from() : connection.to();
        }

        private static long horizontalDistanceSquared(BlockPos left, BlockPos right) {
            if (left == null || right == null) return Long.MAX_VALUE;
            long dx = (long) left.getX() - right.getX();
            long dz = (long) left.getZ() - right.getZ();
            return dx * dx + dz * dz;
        }

        private static VillageBuildingCollision firstVillageBuildingCollision(
                List<RoadSegmentPlacement> segments,
                int start,
                int step,
                List<OwnedStructureBox> village) {
            if (segments == null || village == null || village.isEmpty() || step == 0) return null;
            for (int index = start; index >= 0 && index < segments.size(); index += step) {
                RoadSegmentPlacement segment = segments.get(index);
                if (segment == null || segment.middlePos() == null || segment.positions() == null) continue;
                int minY = segment.middlePos().getY() + 1;
                int maxY = segment.middlePos().getY() + CONSTRUCTION_ABOVE;
                for (BlockPos position : segment.positions()) {
                    if (position == null) continue;
                    for (OwnedStructureBox owned : village) {
                        if (owned != null && owned.intersectsSolid(
                                position.getX(), position.getZ(), minY, maxY)) {
                            return new VillageBuildingCollision(index, position, owned.owner(), owned.box());
                        }
                    }
                }
            }
            return null;
        }

        private record VillageBuildingCollision(int segmentIndex,
                                                BlockPos position,
                                                StructureOwnerKey owner,
                                                BoundingBox box) {
        }

        PlanningSnapshot forceVerticalOwner(StructureOwnerKey owner) {
            if (owner == null || ownsEndpoint(owner) || forcedVerticalOwners.contains(owner)) return this;
            Set<StructureOwnerKey> forced = new HashSet<>(forcedVerticalOwners);
            forced.add(owner);
            return withForcedVerticalOwners(forced);
        }

        private PlanningSnapshot withForcedVerticalOwners(Collection<StructureOwnerKey> owners) {
            Set<StructureOwnerKey> forced = owners == null || owners.isEmpty() ? Set.of() : Set.copyOf(owners);
            return new PlanningSnapshot(boxes, boxesByChunk, endpointOwners, forced,
                    resolvedStarts, coveredCandidateChunks, roadRadius);
        }

        PlanningSnapshot withAdditionalRoadRadius(int additionalRoadRadius) {
            if (additionalRoadRadius <= 0 || this == EMPTY) return this;
            int expandedRadius = roadRadius + additionalRoadRadius;
            if (expandedRadius == roadRadius) return this;
            Map<Long, List<OwnedStructureBox>> mutable = new HashMap<>();
            for (OwnedStructureBox owned : boxes) {
                if (owned == null || owned.box() == null) continue;
                BoundingBox box = owned.box();
                for (int cx = (box.minX() - expandedRadius) >> 4;
                     cx <= (box.maxX() + expandedRadius) >> 4; cx++) {
                    for (int cz = (box.minZ() - expandedRadius) >> 4;
                         cz <= (box.maxZ() + expandedRadius) >> 4; cz++) {
                        mutable.computeIfAbsent(ChunkPos.asLong(cx, cz), ignored -> new ArrayList<>()).add(owned);
                    }
                }
            }
            Map<Long, List<OwnedStructureBox>> expandedByChunk = new HashMap<>();
            for (Map.Entry<Long, List<OwnedStructureBox>> entry : mutable.entrySet()) {
                expandedByChunk.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new PlanningSnapshot(boxes, Map.copyOf(expandedByChunk), endpointOwners,
                    forcedVerticalOwners, resolvedStarts, coveredCandidateChunks, expandedRadius);
        }

        private static Set<StructureOwnerKey> findEndpointOwners(
                StructureConnection connection, Collection<OwnedStructureBox> boxes) {
            if (connection == null || boxes == null || boxes.isEmpty()) return Set.of();
            Map<StructureOwnerKey, BoundingBox> aggregate = new HashMap<>();
            for (OwnedStructureBox owned : boxes) {
                if (owned == null || owned.owner() == null || owned.box() == null) continue;
                aggregate.merge(owned.owner(), owned.box(), StructureAvoidanceService::encapsulate);
            }
            Set<StructureOwnerKey> owners = new HashSet<>();
            for (Map.Entry<StructureOwnerKey, BoundingBox> entry : aggregate.entrySet()) {
                if (containsHorizontal(entry.getValue(), connection.from())
                        || containsHorizontal(entry.getValue(), connection.to())) {
                    owners.add(entry.getKey());
                }
            }
            return Set.copyOf(owners);
        }

        private static Set<StructureOwnerKey> expandEndpointVillageOwners(
                StructureConnection connection,
                Collection<OwnedStructureBox> boxes,
                Collection<StructureOwnerKey> current) {
            Set<StructureOwnerKey> expanded = new HashSet<>();
            if (current != null) expanded.addAll(current);
            if (connection == null || boxes == null || boxes.isEmpty() || expanded.isEmpty()) {
                return Set.copyOf(expanded);
            }
            // 端点村庄的家族名集合。Lithostitched 替换会把同一村庄拆成多个
            // owner(如 ctov:medium/village_dark_forest -> ctov:small/village_dark_forest),
            // 但这些拆分片的 family 相同;必须只聚合同 family 的村庄建筑片。
            // 旧实现按"距任一端点 <= 96 格"无条件聚合,会把两个相距 73 格但
            // 完全独立的村庄(如 biomeswevegone:village/salem 与
            // ctov:medium/village_dark_forest)误当成同一端点村庄群,导致过路村庄
            // 被豁免、道路整片切穿(T-35)。
            Set<String> endpointVillageFamilies = new HashSet<>();
            for (StructureOwnerKey owner : expanded) {
                if (owner != null && isVillageStructure(owner.structureId())) {
                    endpointVillageFamilies.add(endpointStructureFamily(owner.structureId()));
                }
            }
            if (endpointVillageFamilies.isEmpty()) return Set.copyOf(expanded);
            long radiusSquared = (long) ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS
                    * ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS;
            for (OwnedStructureBox owned : boxes) {
                if (owned == null || owned.owner() == null || owned.box() == null
                        || !isVerifiedBuilding(owned)
                        || !isVillageStructure(owned.owner().structureId())) continue;
                // 只聚合与端点村庄同 family 的建筑片,相邻独立村庄保持为过路障碍。
                if (!endpointVillageFamilies.contains(
                        endpointStructureFamily(owned.owner().structureId()))) continue;
                long fromDistance = StructureAvoidanceService.horizontalDistanceSquared(
                        owned.box(), connection.from());
                long toDistance = StructureAvoidanceService.horizontalDistanceSquared(
                        owned.box(), connection.to());
                if (Math.min(fromDistance, toDistance) <= radiusSquared) {
                    expanded.add(owned.owner());
                }
            }
            return Set.copyOf(expanded);
        }
    }

    private static void requestMainThreadPreload(ServerLevel level, int cx, int cz, long key) {
        StructureAvoidanceCache.CHUNK_BBOX_CACHE.putIfAbsent(key, ChunkEntry.UNREADY);
        RuntimePreloadKey preloadKey = new RuntimePreloadKey(
                level.dimension().location().toString(), level.getSeed(), key);
        if (StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.containsKey(preloadKey)) return;
        if (StructureAvoidanceDiagnostics.PENDING_REQUESTS.incrementAndGet() > MAX_PENDING_REQUESTS) {
            decrementPendingRequests();
            return;
        }
        var server = level.getServer();
        if (server == null) {
            decrementPendingRequests();
            return;
        }
        CompletableFuture<List<OwnedStructureBox>> shared = new CompletableFuture<>();
        CompletableFuture<List<OwnedStructureBox>> existing = StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.putIfAbsent(preloadKey, shared);
        if (existing != null) {
            decrementPendingRequests();
            return;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        resolveReferenceChunkBoxesAsync(level, cx, cz)
                .whenComplete((boxes, failure) -> {
                    try {
                        if (!ThreadPoolManager.isEpoch(epoch) || failure != null) {
                            StructureAvoidanceCache.CHUNK_BBOX_CACHE.remove(key);
                            if (failure != null) shared.completeExceptionally(failure);
                            else shared.completeExceptionally(new java.util.concurrent.CancellationException(
                                    "RoadWeaver world epoch changed while preloading " + new ChunkPos(cx, cz)));
                            return;
                        }
                        StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Set.copyOf(boxes), true));
                        shared.complete(boxes);
                    } finally {
                        StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.remove(preloadKey, shared);
                        decrementPendingRequests();
                    }
                });
    }

    private static void decrementPendingRequests() {
        StructureAvoidanceDiagnostics.PENDING_REQUESTS.updateAndGet(value -> value <= 0L ? 0L : value - 1L);
    }

    private static <K, V> V putIfAbsentBounded(ConcurrentMap<K, V> map,
                                                ConcurrentLinkedQueue<K> order,
                                                K key,
                                                V value,
                                                int capacity) {
        V existing = map.putIfAbsent(key, value);
        if (existing == null) {
            order.add(key);
            trimBounded(map, order, capacity);
        }
        return existing;
    }

    private static <K, V> void putBounded(ConcurrentMap<K, V> map,
                                          ConcurrentLinkedQueue<K> order,
                                          K key,
                                          V value,
                                          int capacity) {
        V previous = map.put(key, value);
        if (previous == null) {
            order.add(key);
            trimBounded(map, order, capacity);
        }
    }

    private static <K, V> void trimBounded(ConcurrentMap<K, V> map,
                                           ConcurrentLinkedQueue<K> order,
                                           int capacity) {
        while (map.size() > capacity) {
            K eldest = order.poll();
            if (eldest == null) return;
            map.remove(eldest);
            // ★ 2026-09-26（WorkBuddy 报告 §6 的建议：先拿实测再定优化）：
            //   记录淘汰次数。**这是判断"是否需要更大缓存/持久化"的唯一直接依据** ——
            //   若淘汰数远小于解析数，说明容量充足、没有"反复重解析"，
            //   任何"加大缓存/持久化"的改动都不会有收益（反而有红线风险）。
            PREPARED_CACHE_EVICTIONS.incrementAndGet();
        }
    }

    /**
     * **缓存淘汰计数**（★ 2026-09-26 新增，用于回答 WorkBuddy 的
     * 「是否有重复解析」这个问题）。
     *
     * <p>WorkBuddy 报告 §6 建议"先做重复解析计数拿实测，再定 O-1/O-2"。
     * 本计数器与既有的 {@code StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS}（命中）/
     * {@code StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES}（解析出的 box 数）配成一对：</p>
     * <pre>
     *   命中高 + 淘汰低  ⇒ 容量充足，缓存有效，无需优化
     *   淘汰高          ⇒ 容量不足导致"解析 → 淘汰 → 再解析"，才值得加大/持久化
     * </pre>
     */
    private static final AtomicLong PREPARED_CACHE_EVICTIONS = new AtomicLong(0);

    /**
     * A road only avoids a structure when its actual construction corridor
     * overlaps the piece.  Underground rooms and sky structures at the same
     * X/Z no longer create a false road gap.
     */
    private static boolean intersectsConstructionBand(OwnedStructureBox owned,
                                                      int x, int z, int minY, int maxY) {
        return owned != null && owned.intersects(x, z, minY, maxY, 0);
    }

    private static boolean intersectsPlanningBand(OwnedStructureBox owned,
                                                  int x,
                                                  int z,
                                                  int minY,
                                                  int maxY,
                                                  int horizontalMargin) {
        return owned != null && owned.intersects(x, z, minY, maxY, horizontalMargin);
    }
}
