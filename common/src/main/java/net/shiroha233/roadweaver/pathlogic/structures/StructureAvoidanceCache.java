/* 文件职责：结构避让的核心缓存（从 {@link StructureAvoidanceService} 拆出）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.shiroha233.roadweaver.search.StructurePredictor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 结构避让的核心缓存（从 {@link StructureAvoidanceService} 拆出）。
 *
 * <p>集中管理规划快照 / 预备起点 / 障碍瓦片 / 在飞请求等并发状态，以及它们的
 * 淘汰队列。主类 {@link StructureAvoidanceService} 只负责避让逻辑，通过
 * {@code StructureAvoidanceCache.XXX} 访问这些状态。</p>
 */
public final class StructureAvoidanceCache {
    private StructureAvoidanceCache() {}

    /** CHUNK_BBOX_CACHE 的 LRU 容量。 */
    static final int CACHE_CAPACITY = 8192;

    static final ConcurrentMap<RouteKey, PlanningSnapshot> PLANNING_SNAPSHOTS = new ConcurrentHashMap<>();
    static final ConcurrentMap<RouteKey, Set<StructureOwnerKey>> ROUTE_ENDPOINT_OWNERS = new ConcurrentHashMap<>();
    static final ConcurrentMap<PreparedStartKey, List<OwnedStructureBox>> PREPARED_START_BOXES = new ConcurrentHashMap<>();
    static final ConcurrentMap<PreparedStartKey, List<OwnedStructureBox>> STARTUP_PINNED_START_BOXES = new ConcurrentHashMap<>();
    static final ConcurrentMap<ObstacleTileKey, List<StructurePredictor.AvoidanceStructureCandidate>> OBSTACLE_TILE_CANDIDATES = new ConcurrentHashMap<>();
    static final ConcurrentMap<PreparedStartKey, CompletableFuture<List<OwnedStructureBox>>> IN_FLIGHT_STARTS = new ConcurrentHashMap<>();
    static final ConcurrentMap<ChunkRequestKey, CompletableFuture<ChunkResult<ChunkAccess>>> IN_FLIGHT_CHUNK_REQUESTS = new ConcurrentHashMap<>();
    static final ConcurrentMap<RuntimePreloadKey, CompletableFuture<List<OwnedStructureBox>>> IN_FLIGHT_RUNTIME_PRELOADS = new ConcurrentHashMap<>();
    static final ConcurrentMap<RouteKey, CompletableFuture<Void>> IN_FLIGHT_ROUTE_CORRIDORS = new ConcurrentHashMap<>();
    static final Semaphore RUNTIME_CORRIDOR_PERMITS = new Semaphore(2, true);
    static final AtomicReference<ExecutorService> NATIVE_DIRECT_EXECUTOR = new AtomicReference<>();
    static final Set<String> UNSUPPORTED_PIECE_CLASSES = ConcurrentHashMap.newKeySet();
    static final ConcurrentLinkedQueue<RouteKey> PLANNING_SNAPSHOT_ORDER = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<PreparedStartKey> PREPARED_START_ORDER = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<ObstacleTileKey> OBSTACLE_TILE_ORDER = new ConcurrentLinkedQueue<>();
    static final ThreadLocal<PlanningSnapshot> ACTIVE_PLANNING_SNAPSHOT = new ThreadLocal<>();
    static final ThreadLocal<WorldgenRandom> STRUCTURE_SELECTION_RANDOM =
            ThreadLocal.withInitial(() -> new WorldgenRandom(new LegacyRandomSource(0L)));
    static final Map<Long, ChunkEntry> CHUNK_BBOX_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<Long, ChunkEntry>(CACHE_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ChunkEntry> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });
}
