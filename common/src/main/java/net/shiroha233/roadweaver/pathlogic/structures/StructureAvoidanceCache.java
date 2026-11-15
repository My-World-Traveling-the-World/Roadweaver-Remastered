/* 文件职责：结构避让的核心缓存（从 {@link StructureAvoidanceService} 拆出）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;
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

    /**
     * 当前线程是否装有规划快照（2026-11-15 新增）。
     *
     * <p><b>为什么需要这个查询：</b>{@code shouldAvoidPlanning} 在快照不存在时
     * 静默返回 {@code false} —— 与「这个位置确实没有结构」完全同形。
     * 桥隧观测需要区分这两件事：前者是<b>没有证据</b>，后者是<b>有证据说明没有</b>。
     * 混为一谈会把「保护区来源没生效」误读成「保护区没有贡献」。</p>
     *
     * @return {@code true} 表示本线程处于规划快照作用域内，查询结果可信
     */
    public static boolean planningSnapshotPresent() {
        // TODO(批 2 B 方案，待真机数据)：这里目前仍用 `!= null`，因此 EMPTY 会被判成
        //   「有快照」—— 与下面 isUsableSnapshot 的口径**不一致**。
        //   改成 isUsableSnapshot(...) 会让 structureSnapshotAvailable 从 true 变 false，
        //   进而把 ModeDpBridge 的 AB 日志从 snapshot=ok 改成 snapshot=absent（**变正确**），
        //   但那会改变批 2 既往 AB 结论的解读口径，需要真机数据配合，故本轮只记录不切。
        return ACTIVE_PLANNING_SNAPSHOT.get() != null;
    }

    /**
     * 快照是否<b>真的可用</b>（2026-11-15 新增，修「静默降级只修了 null、没修 EMPTY」）。
     *
     * <h2>为什么 {@code != null} 不够</h2>
     * <p>{@code PlanningSnapshot.EMPTY} 是<b>非 null</b> 的空快照，它同样「什么都不挡」。
     * 而 {@code activatePlanningSnapshot} 在拿不到该路线的快照时，
     * 兜底写进去的正是 {@code PlanningSnapshot.EMPTY}：
     * <pre>
     *   selected = PLANNING_SNAPSHOTS.getOrDefault(routeKey, PlanningSnapshot.EMPTY);
     * </pre>
     * ⇒ 只看 {@code != null} 时，「路线快照没准备好」与「附近真没建筑」
     * 在观测上<b>完全同形</b>，正是病根 C 的原话。</p>
     *
     * <h2>本轮改动范围（A 方案：只加观测）</h2>
     * <p>本方法先把 {@code shouldAvoidPlanning} 的两个重载的「无证据」判据
     * 从 {@code null} 放宽到「null 或 EMPTY」，<b>返回值不变</b>（仍然是 fail-open 的 false），
     * 唯一变化是这种情况现在会记进 {@code blindQueryCount}。</p>
     *
     * @param snapshot 当前线程的规划快照，可为 null
     * @return 非 null 且不是 {@code PlanningSnapshot.EMPTY}
     */
    static boolean isUsableSnapshot(PlanningSnapshot snapshot) {
        return snapshot != null && snapshot != PlanningSnapshot.EMPTY;
    }

    // ── 结构避让查询 + 静默降级观测（2026-11-15 从 Service 搬来，见下）──
    //
    // 实现放在本类而不是 StructureAvoidanceService 的原因很实际：
    // Service 已经 998 行，贴着「单文件不超过 1000 行」的硬约束（守卫见
    // SourceFileSizeGuardTest），加任何实现都会破线。而这些方法的全部状态
    // （ACTIVE_PLANNING_SNAPSHOT）本来就归本类管，放这里更内聚。

    private static final java.util.concurrent.atomic.AtomicLong BLIND_QUERIES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean BLIND_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("roadweaver");

    /**
     * 结构避让查询（带水平安全边距）。
     *
     * <p><b>快照缺失时不再「静默」</b>：本方法返回 false 的两种原因曾经完全同形 ——</p>
     * <ul>
     *   <li>「这个位置确实没有已验证结构」（正常，可以铺路）；</li>
     *   <li>「本线程根本没装规划快照」（异常，<b>整条链什么都不会挡</b>）。</li>
     * </ul>
     * <p>第二种是真机上出现过的「路穿建筑」病根之一：规划跑在 worker 线程上，
     * 而快照是 ThreadLocal —— 作用域没铺到就会全链失明，且日志里一个字都没有。
     * 现在缺失时会记一次警告 + 累加计数，让「失明」这件事可见。</p>
     *
     * <p>注意：返回值语义<b>不变</b>（仍然返回 false）。这是可观测性改动，
     * 不是行为改动 —— 贸然改成 true 会把整个路网判成处处不可铺。</p>
     */
    public static boolean shouldAvoidPlanning(BlockPos pos, int horizontalClearance) {
        PlanningSnapshot snapshot = ACTIVE_PLANNING_SNAPSHOT.get();
        // 2026-11-15（A 方案）：判据从 `== null` 放宽到「null 或 EMPTY」。
        // 返回值不变（仍是 fail-open 的 false），但 EMPTY 这种情况现在也会记盲查 ——
        // 否则「路线快照没准备好」与「附近真没建筑」在计数上完全同形。
        if (!isUsableSnapshot(snapshot)) {
            noteBlindQuery();
            return false;
        }
        return snapshot.blocks(pos, Math.max(0, horizontalClearance));
    }

    /** 结构避让查询（无额外边距）。语义与观测同上面的重载。 */
    public static boolean shouldAvoidPlanning(BlockPos pos) {
        PlanningSnapshot snapshot = ACTIVE_PLANNING_SNAPSHOT.get();
        if (!isUsableSnapshot(snapshot)) {
            noteBlindQuery();
            return false;
        }
        return snapshot.blocks(pos);
    }

    /**
     * 记一次「无快照查询」。
     *
     * <p>只在<b>首次</b>打警告（用 compareAndSet 保证一次），之后只累加计数 ——
     * 这条路径可能被每秒调用上万次（规划期逐站逐列问），
     * 每次都打日志会把 latest.log 冲爆。</p>
     */
    private static void noteBlindQuery() {
        long n = BLIND_QUERIES.incrementAndGet();
        if (BLIND_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("RoadWeaver structure avoidance queried WITHOUT a planning snapshot"
                    + " (first of possibly many) — each such query returns false,"
                    + " which is indistinguishable from \"no structure here\"."
                    + " If routes cut through buildings, check the planning-snapshot thread scope.");
        }
        if (n % 100_000L == 0L) {
            LOGGER.warn("RoadWeaver structure avoidance blind queries reached {}", n);
        }
    }

    /** 累计「无快照查询」次数（诊断用；真机看这个数就能判断作用域有没有铺到）。 */
    public static long blindQueryCount() {
        return BLIND_QUERIES.get();
    }

    /** 重置计数与警告位（测试用）。 */
    public static void resetBlindQueryCount() {
        BLIND_QUERIES.set(0);
        BLIND_WARNED.set(false);
    }

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
