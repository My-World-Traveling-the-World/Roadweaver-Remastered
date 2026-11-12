/* 文件职责：持久化 32×32 区块的规划覆盖状态，供外部预生成器查询。
 *
 * READY 的含义是「拓扑、地形路径、结构精确快照都已持久化」，
 * **不代表方块已铺完** —— 物理施工可以晚得多。这个区分是预生成器能否
 * 安全跳过某片区域的前提。
 */
package net.shiroha233.roadweaver.planning;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprintService;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import net.shiroha233.roadweaver.persistence.files.FileStoragePathResolver;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Persists 32x32-chunk planning coverage for external pregenerators. READY
 * means topology, terrain paths and exact structure snapshots are durable;
 * physical road blocks may still be constructed later.
 */
public final class RoadPlanningReadinessIndex {
    public static final int REGION_CHUNKS = 32;

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int READINESS_SCHEMA = 2;
    private static final int PLAN_MARGIN_CHUNKS = REGION_CHUNKS;
    private static final int FALLBACK_CORRIDOR_CHUNKS = 12;
    private static final int MAX_PLAN_ATTEMPTS = 3;
    private static final long MISSING_PATH_RETRY_MILLIS = 2_000L;
    private static final String READY_FILE = "chunky-ready-regions-v2.txt";

    private static final Map<WorldKey, WorldState> WORLDS = new ConcurrentHashMap<>();
    private static final Map<RegionKey, RegionEntry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<WorldKey, Map<ConnectionKey, CachedPath>> PLANNED_PATHS =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionKey> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<RegionKey> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean runtimePausedForDimension;
    private static volatile int currentSupplyLanes = 1;
    private static volatile int currentStreamsThreads = 1;

    /**
     * 单调递增的 tick 计数（★ 0.8.401）。
     *
     * <p>用途：给 {@link #backlogSaturated} 的「按 tick 记忆化」提供代价为零的
     * 失效键 —— 无需挂任何显式失效逻辑，tick 一变缓存自然失效。</p>
     *
     * <p>由 {@link #tick(ServerLevel)} 每次调用递增。注意 requestRegion 是 Chunky
     * 通过反射投进服务端线程的，同一 tick 内的多次调用共享同一个值，
     * 这正是我们要的语义（一个 tick 内饱和状态不会变）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong CURRENT_TICK =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 供诊断 / 测试：当前 tick 计数。
     */
    static long currentTickForTest() {
        return CURRENT_TICK.get();
    }

    private RoadPlanningReadinessIndex() {
    }

    /** Reflection-friendly API used by PlainCraft Chunky AutoGen. */
    public static String requestRegion(Object levelObject, int regionX, int regionZ) {
        if (!(levelObject instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) {
            return State.UNSUPPORTED.name();
        }
        WorldState world = worldState(level);
        long packed = packRegion(regionX, regionZ);
        if (world.ready.contains(packed)) return State.READY.name();

        RegionKey key = key(level, regionX, regionZ);

        // ★ 0.8.401：**把已入项检查提到 adoption 之前**。
        //
        // 真机取证（3× jstack，2026-09-27）：预热期 Chunky 每 tick 遍历 3190 个
        // region，每个都走 `adoptCompletedInitialCoverage` → 该函数内部又要读
        // checkpoint 文件 + 遍历连接表。而这些 region **绝大多数早就 QUEUED 了**，
        // 重算结果不会改变 —— 属于纯浪费。
        //
        // 现在的顺序（最便宜的先判）：
        //   ① ready 集合（内存）
        //   ② ENTRIES 已有项（内存）—— 已 QUEUED/PLANNING 直接返回状态
        //   ③ 饱和短路（内存，按 tick 记忆化）—— 饱和且未入项直接 QUEUED，
        //      **跳过 adoption**（被拒的 region 永远进不了 ENTRIES，
        //      让它们跑 adoption 是最大的那笔浪费）
        //   ④ adoption（可能读盘 / 遍历连接表）
        //   ⑤ 入队
        //
        // ⚠️ 必须保留 FAILED 且 attempts < MAX_PLAN_ATTEMPTS 的重排分支
        //    （原逻辑在此处把失败项重新入队）。
        RegionEntry existing = ENTRIES.get(key);
        if (existing != null) {
            if (existing.state == State.FAILED && existing.attempts < MAX_PLAN_ATTEMPTS) {
                existing.state = State.QUEUED;
                QUEUE.add(key);
            }
            return existing.state.name();
        }

        // 饱和短路：队列已满且是**新** region ⇒ 不做 adoption（省掉最贵的一步），
        // 直接回 QUEUED 让 Chunky 按自己节奏重试（对外 API 语义不变）。
        if (backlogSaturated(worldKey(level))) {
            return State.QUEUED.name();
        }

        if (adoptCompletedInitialCoverage(level, regionX, regionZ)) return State.READY.name();

        RegionEntry created = new RegionEntry(State.QUEUED);
        RegionEntry entry = ENTRIES.putIfAbsent(key, created);
        if (entry == null) {
            QUEUE.add(key);
            entry = created;
            LOGGER.info("RoadWeaver queued Chunky readiness region [{},{}].", regionX, regionZ);
        } else if (entry.state == State.FAILED && entry.attempts < MAX_PLAN_ATTEMPTS) {
            entry.state = State.QUEUED;
            QUEUE.add(key);
        }
        return entry.state.name();
    }

    // ─────────────────── 入队侧护栏（审计 P1-2）───────────────────
    //
    // 真机 2026-09-24-1.log：905 个 region（≈92.7 万 chunk）一次性入队，
    // 而 Chunky 全程 active=false、completed=0 —— readyAhead 涨到 751 无人消费，
    // 堆在崩溃前 1 小时就贴顶 12 GB。对照：Chunky 真在跑那次只有 8.4–9.4 GB、0 OOM。
    //
    // 上面 recommendedCoordinatorLanes 已按堆占用率压 lane（降速），
    // 这里再补一道**入队侧**护栏：backlog 本身也要有上界，
    // 否则光是 ENTRIES 里的条目与排队 key 就会持续吃内存。

    /**
     * 队列内（QUEUED + PLANNING）region 数的上界。
     *
     * <p>取 512：真机那次是 905（≈92.7 万 chunk 的规划量），压到 512 仍能让
     * Chunky 预检的整批需求顺畅推进，但把峰值内存与排队条目砍掉约一半。
     * 超限时 {@code requestRegion} 返回 {@code QUEUED}（而不是拒绝），
     * 由 Chunky 侧按自己的节奏重试 —— 对外 API 语义不变，不会让它判定失败。</p>
     */
    private static final int MAX_BACKLOG_REGIONS = 512;

    /**
     * 队列入队是否已到上限（只统计本世界的 QUEUED/PLANNING）。
     *
     * <p>★ 0.8.401：**按 tick 记忆化**。原实现每次调用都遍历
     * {@code ACTIVE} + {@code QUEUE}（约 512 项），而 requestRegion 在
     * Chunky 预热期每 tick 被调用约 2754×2 遍 ⇒ 每次约 512 次 map 查找
     * ⇒ 每 tick 约 280 万次。饱和状态在一个 tick 内不会变（入队/出队
     * 都发生在服务端线程的 tick 边界内），因此按 tick 缓存是安全且等价的。</p>
     *
     * <p>用 {@code CURRENT_TICK} 作 key：它是单调递增的 tick 计数（见 tick()）。
     * 这样无需额外失效逻辑 —— 新 tick 自动重新计算一次。</p>
     */
    private static boolean backlogSaturated(WorldKey world) {
        long tick = CURRENT_TICK.get();
        CacheKey cacheKey = new CacheKey(world, tick);
        Boolean cached = SATURATED_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean result = computeBacklogSaturated(world);
        synchronized (SATURATED_CACHE) {
            // 双检：并发进入时可能已被别的线程填好（结果等价，直接返回即可）。
            Boolean racer = SATURATED_CACHE.get(cacheKey);
            if (racer != null) {
                return racer;
            }
            // 只保留当前 tick 的结果 —— 旧的 tick key 永不复用，直接清掉。
            SATURATED_CACHE.clear();
            SATURATED_CACHE.put(cacheKey, result);
        }
        return result;
    }

    /** 饱和缓存键：(世界, tick)。 */
    private record CacheKey(WorldKey world, long tick) {
    }

    /**
     * 饱和缓存。**逻辑上容量恒为 1**（当前 tick 的当前世界）—— 每次填充前
     * {@code clear()} 完成轮换。用 synchronized 保护 clear+put 的原子性
     * （单条 ConcurrentHashMap 操作原子，但两者组合不是）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<CacheKey, Boolean> SATURATED_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 供测试：清空饱和缓存。 */
    static void resetSaturatedCacheForTest() {
        SATURATED_CACHE.clear();
    }

    private static boolean computeBacklogSaturated(WorldKey world) {
        int n = 0;
        for (RegionKey k : ACTIVE) {
            if (!k.world.equals(world)) continue;
            RegionEntry e = ENTRIES.get(k);
            if (e != null && (e.state == State.PLANNING || e.state == State.QUEUED)) n++;
        }
        for (RegionKey k : QUEUE) {
            if (!k.world.equals(world)) continue;
            RegionEntry e = ENTRIES.get(k);
            if (e != null && e.state == State.QUEUED) n++;
            if (n >= MAX_BACKLOG_REGIONS) return true;
        }
        return n >= MAX_BACKLOG_REGIONS;
    }

    /** Reflection-friendly read-only API used by PlainCraft Chunky AutoGen. */
    public static String regionState(Object levelObject, int regionX, int regionZ) {
        if (!(levelObject instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) {
            return State.UNSUPPORTED.name();
        }
        if (worldState(level).ready.contains(packRegion(regionX, regionZ))) return State.READY.name();
        RegionEntry entry = ENTRIES.get(key(level, regionX, regionZ));
        return entry == null ? State.UNPLANNED.name() : entry.state.name();
    }

    public static String progressSummary(Object levelObject) {
        if (!(levelObject instanceof ServerLevel level)) return "RoadWeaver unavailable";
        WorldKey worldKey = worldKey(level);
        int queued = 0;
        int planning = 0;
        int readyAhead = 0;
        int failed = 0;
        for (Map.Entry<RegionKey, RegionEntry> entry : ENTRIES.entrySet()) {
            if (!entry.getKey().world.equals(worldKey)) continue;
            switch (entry.getValue().state) {
                case QUEUED -> queued++;
                case PLANNING -> planning++;
                case READY -> {
                    if (!entry.getValue().dispatched) readyAhead++;
                }
                case FAILED -> failed++;
                default -> { }
            }
        }
        return "ready=" + worldState(level).ready.size()
                + ", queued=" + queued
                + ", planning=" + planning
                + ", readyAhead=" + readyAhead
                + ", lanes=" + currentSupplyLanes
                + ", streams=" + currentStreamsThreads
                + ", failed=" + failed;
    }

    /** Reflection-friendly lifecycle signal from PlainCraft Chunky AutoGen. */
    public static void setChunkyPreflightActive(boolean active) {
        RuntimeLoadPolicy.setChunkyPreflightActive(active);
    }

    /** Reflection-friendly lifecycle signal while Chunky owns worldgen. */
    public static void setChunkyExclusiveActive(boolean active) {
        RuntimeLoadPolicy.setChunkyExclusiveActive(active);
    }

    /** Reflection-friendly notification after Chunky accepts a ready region. */
    public static void markRegionDispatched(Object levelObject, int regionX, int regionZ) {
        if (!(levelObject instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) return;
        RegionEntry entry = ENTRIES.get(key(level, regionX, regionZ));
        if (entry != null) entry.dispatched = true;
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        // ★ 0.8.401：推进 tick 计数（让 backlogSaturated 的按-tick 缓存失效）。
        //   注意放在 runtimePausedForDimension 检查**之前** —— 暂停期同样要推进，
        //   否则恢复后会用过期的 (world, tick) 命中旧缓存。
        CURRENT_TICK.incrementAndGet();
        if (runtimePausedForDimension) return;
        worldState(level);
        WorldKey world = worldKey(level);
        SupplySnapshot supply = supplySnapshot(world);
        int desiredLanes = RuntimeLoadPolicy.recommendedCoordinatorLanes(
                supply.pending(), supply.readyAhead(), currentSupplyLanes);
        applySupplyCapacity(desiredLanes, supply);
        if (desiredLanes <= 0) return;

        int activeForWorld = activeCount(world);
        while (activeForWorld < desiredLanes) {
            RegionKey activeKey = pollForWorld(world);
            if (activeKey == null) return;
            RegionEntry entry = ENTRIES.get(activeKey);
            if (entry == null || entry.state != State.QUEUED || !ACTIVE.add(activeKey)) continue;
            startPlanning(level, activeKey, entry);
            activeForWorld++;
        }
    }

    /** Initial world generation calls this after road shards and statuses flush. */
    public static void markInitialCoverageReady(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        int radius = Math.max(1, ConfigService.get().planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int centerChunkX = spawn.getX() >> 4;
        int centerChunkZ = spawn.getZ() >> 4;
        int minChunkX = centerChunkX - radius;
        int maxChunkX = centerChunkX + radius;
        int minChunkZ = centerChunkZ - radius;
        int maxChunkZ = centerChunkZ + radius;
        int marked = 0;
        for (int regionX = Math.floorDiv(minChunkX, REGION_CHUNKS);
             regionX <= Math.floorDiv(maxChunkX, REGION_CHUNKS); regionX++) {
            for (int regionZ = Math.floorDiv(minChunkZ, REGION_CHUNKS);
                 regionZ <= Math.floorDiv(maxChunkZ, REGION_CHUNKS); regionZ++) {
                if (!coverageContainsRegion(
                        minChunkX, minChunkZ, maxChunkX, maxChunkZ, regionX, regionZ)) continue;
                if (!regionPlanningDataReady(level, regionX, regionZ)) continue;
                if (markCoverageReady(level, regionX, regionZ)) marked++;
            }
        }
        LOGGER.info("RoadWeaver registered initial Chunky-ready coverage: marked={} {}.",
                marked, progressSummary(level));
    }

    /** Construction no longer gates pregeneration once durable planning exists. */
    public static StructureConnection nextPlannedConnection(ServerLevel level) {
        return null;
    }

    public static boolean shouldReserveForCoordinator(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) return false;
        WorldKey world = worldKey(level);
        for (RegionKey activeKey : ACTIVE) {
            if (!activeKey.world.equals(world)) continue;
            RegionEntry entry = ENTRIES.get(activeKey);
            if (entry != null && entry.state == State.PLANNING
                    && connectionTouchesRegion(level, connection, activeKey)) return true;
        }
        return false;
    }

    public static void clearRuntime() {
        for (RegionEntry entry : ENTRIES.values()) {
            CompletableFuture<Void> future = entry.planningFuture;
            if (future != null) future.cancel(true);
        }
        ENTRIES.clear();
        QUEUE.clear();
        ACTIVE.clear();
        WORLDS.clear();
        PLANNED_PATHS.clear();
        RuntimeLoadPolicy.setChunkyPreflightActive(false);
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        currentSupplyLanes = 1;
        currentStreamsThreads = 1;
        ThreadPoolManager.setChunkySupplyThreads(0);
        RuntimeLoadPolicy.setStreamsSupplyThreads(0);
        runtimePausedForDimension = false;
    }

    /** Pause readiness planning outside the Overworld without affecting Chunky. */
    public static void setRuntimePausedForDimension(boolean paused) {
        if (runtimePausedForDimension == paused) return;
        runtimePausedForDimension = paused;
        if (!paused) {
            LOGGER.info("RoadWeaver Chunky readiness planning resumed in minecraft:overworld.");
            return;
        }
        for (RegionEntry entry : ENTRIES.values()) {
            CompletableFuture<Void> future = entry.planningFuture;
            if (future != null) future.cancel(true);
            entry.planningFuture = null;
            if (entry.state == State.PLANNING) entry.state = State.QUEUED;
        }
        ACTIVE.clear();
        QUEUE.clear();
        for (Map.Entry<RegionKey, RegionEntry> queued : ENTRIES.entrySet()) {
            if (queued.getValue().state == State.QUEUED) QUEUE.add(queued.getKey());
        }
        ThreadPoolManager.setChunkySupplyThreads(0);
        RuntimeLoadPolicy.setStreamsSupplyThreads(0);
        LOGGER.info("RoadWeaver Chunky readiness planning paused outside minecraft:overworld.");
    }

    private static void startPlanning(ServerLevel level, RegionKey key, RegionEntry entry) {
        entry.state = State.PLANNING;
        entry.attempts++;
        int minChunkX = key.regionX * REGION_CHUNKS - PLAN_MARGIN_CHUNKS;
        int minChunkZ = key.regionZ * REGION_CHUNKS - PLAN_MARGIN_CHUNKS;
        int maxChunkX = (key.regionX + 1) * REGION_CHUNKS - 1 + PLAN_MARGIN_CHUNKS;
        int maxChunkZ = (key.regionZ + 1) * REGION_CHUNKS - 1 + PLAN_MARGIN_CHUNKS;
        long epoch = ThreadPoolManager.currentEpoch();
        LOGGER.info("RoadWeaver preparing Chunky region [{},{}], attempt={}/{} with guaranteed coordinator progress.",
                key.regionX, key.regionZ, entry.attempts, MAX_PLAN_ATTEMPTS);
        CompletableFuture<Void> future = AsyncPlanningOps.planRectForChunkyAsync(
                level, minChunkX << 4, minChunkZ << 4, (maxChunkX << 4) + 15, (maxChunkZ << 4) + 15)
                .thenCompose(ignored -> regionPlanningDataReady(level, key.regionX, key.regionZ)
                        ? prewarmRegionStreamsAsync(level, key)
                        : CompletableFuture.completedFuture(null));
        entry.planningFuture = future;
        future.whenComplete((ignored, failure) -> {
            if (RoadLifecycle.isStopping()) return;
            var server = level.getServer();
            if (server == null) return;
            server.execute(() -> {
                if (RoadLifecycle.isStopping()
                        || !ThreadPoolManager.isEpoch(epoch)
                        || ENTRIES.get(key) != entry) return;
                entry.planningFuture = null;
                if (failure == null && regionPlanningDataReady(level, key.regionX, key.regionZ)) {
                    markReady(level, key, entry);
                } else if (entry.attempts < MAX_PLAN_ATTEMPTS) {
                    entry.state = State.QUEUED;
                    QUEUE.add(key);
                    ACTIVE.remove(key);
                    LOGGER.warn("RoadWeaver planning retry for Chunky region [{},{}].",
                            key.regionX, key.regionZ,
                            failure == null
                                    ? new IllegalStateException("final persisted path verification failed")
                                    : failure);
                } else {
                    entry.state = State.FAILED;
                    ACTIVE.remove(key);
                    LOGGER.error("RoadWeaver could not prepare Chunky region [{},{}].",
                            key.regionX, key.regionZ, failure);
                }
            });
        });
    }

    /**
     * Chunky must not start against cold river data. Prewarm only the paths
     * touching this readiness region, then let the existing callback publish
     * READY. The plate work runs on the dedicated Streams prewarm executor.
     */
    private static CompletableFuture<Void> prewarmRegionStreamsAsync(
            ServerLevel level, RegionKey region) {
        List<BlockPos> positions = new ArrayList<>();
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        List<StructureConnection> connections = WorldDataProvider.getInstance().getStructureConnections(level);
        if (connections == null) return CompletableFuture.completedFuture(null);
        for (StructureConnection connection : connections) {
            if (connection == null || !connectionTouchesRegion(level, connection, region)) continue;
            List<BlockPos> path = plannedPath(level, connection);
            if (path == null) continue;
            for (BlockPos position : path) {
                if (position == null) continue;
                long key = ((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL);
                if (seen.add(key)) positions.add(new BlockPos(position.getX(), 0, position.getZ()));
            }
        }
        if (positions.isEmpty() || !StreamsReflowingCompat.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        LOGGER.info("RoadWeaver Chunky prewarm phase=PREWARM region=[{},{}] positions={}",
                region.regionX, region.regionZ, positions.size());
        return StreamsReflowingCompat.prewarmRoutesAsync(level, positions,
                "chunky-region-" + region.regionX + "," + region.regionZ);
    }

    private static boolean adoptCompletedInitialCoverage(ServerLevel level, int regionX, int regionZ) {
        // ★ 0.8.401：先判**纯内存态**，再判需要读盘/遍历的项。
        //
        // 真机 3× jstack（2026-09-27，预热期 10 小时未完成）显示服务端线程
        // 三次采样**全部**卡在 shouldRunInitialGeneration → Files.readAllLines。
        // 虽然 InitialGenerationCheckpointStore 已加内存缓存（消除读盘），
        // 这里仍保持「最便宜的先判」的顺序，避免无谓调用。
        if (InitialGenManager.isActive()) return false;
        if (InitialGenManager.shouldRunInitialGeneration(level)) return false;
        int radius = Math.max(1, ConfigService.get().planning().initialPlanRadiusChunks());
        BlockPos spawn = level.getSharedSpawnPos();
        int centerChunkX = spawn.getX() >> 4;
        int centerChunkZ = spawn.getZ() >> 4;
        if (!coverageContainsRegion(
                centerChunkX - radius,
                centerChunkZ - radius,
                centerChunkX + radius,
                centerChunkZ + radius,
                regionX,
                regionZ)) return false;
        if (!regionPlanningDataReady(level, regionX, regionZ)) return false;
        if (markCoverageReady(level, regionX, regionZ)) {
            LOGGER.info("RoadWeaver adopted existing initial planning coverage for Chunky region [{},{}].",
                    regionX, regionZ);
        }
        return true;
    }

    private static boolean coverageContainsRegion(
            int plannedMinChunkX,
            int plannedMinChunkZ,
            int plannedMaxChunkX,
            int plannedMaxChunkZ,
            int regionX,
            int regionZ) {
        int regionMinChunkX = regionX * REGION_CHUNKS;
        int regionMinChunkZ = regionZ * REGION_CHUNKS;
        int regionMaxChunkX = regionMinChunkX + REGION_CHUNKS - 1;
        int regionMaxChunkZ = regionMinChunkZ + REGION_CHUNKS - 1;
        // Initial planning has already completed its own terrain and structure
        // margins. Readiness only promises that the region Chunky will generate
        // is fully inside the durable planned footprint.
        return regionMinChunkX >= plannedMinChunkX
                && regionMinChunkZ >= plannedMinChunkZ
                && regionMaxChunkX <= plannedMaxChunkX
                && regionMaxChunkZ <= plannedMaxChunkZ;
    }

    private static boolean regionPlanningDataReady(ServerLevel level, int regionX, int regionZ) {
        List<StructureConnection> connections = WorldDataProvider.getInstance().getStructureConnections(level);
        if (connections == null) return false;
        RegionKey key = key(level, regionX, regionZ);
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            if (connection.status() == ConnectionStatus.FAILED
                    || connection.status() == ConnectionStatus.COMPLETED
                    || connection.status() == ConnectionStatus.PROTECTED_GAP) continue;
            if (!connectionTouchesRegion(level, connection, key)) continue;
            if (plannedPath(level, connection) == null) return false;
        }
        return true;
    }

    /**
     * A readiness sweep touches many 32x32 cells that share the same road.
     * Resolve its persisted path once, rather than reopening it for every
     * cell. A missing path is retried shortly so an active planner remains
     * observable without making stale data look ready.
     */
    private static List<BlockPos> plannedPath(ServerLevel level, StructureConnection connection) {
        WorldKey world = worldKey(level);
        ConnectionKey key = ConnectionKey.of(connection);
        Map<ConnectionKey, CachedPath> cache = PLANNED_PATHS.computeIfAbsent(
                world, ignored -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        CachedPath cached = cache.get(key);
        if (cached != null && (cached.path != null || now < cached.retryAfterMillis)) {
            return cached.path;
        }

        List<BlockPos> path = PlannedPathCache.find(
                level,
                connection,
                TerrainSamplingSessions.forLevel(level).effectiveMode(),
                ConfigService.get().pathfindingCost()).orElse(null);
        if (path != null && path.isEmpty()) path = null;
        if (path != null) path = List.copyOf(path);
        cache.put(key, new CachedPath(path, path == null ? now + MISSING_PATH_RETRY_MILLIS : Long.MAX_VALUE));
        return path;
    }

    private static boolean connectionTouchesRegion(
            ServerLevel level,
            StructureConnection connection,
            RegionKey key) {
        int minChunkX = key.regionX * REGION_CHUNKS;
        int minChunkZ = key.regionZ * REGION_CHUNKS;
        int maxChunkX = minChunkX + REGION_CHUNKS - 1;
        int maxChunkZ = minChunkZ + REGION_CHUNKS - 1;

        List<BlockPos> planned = plannedPath(level, connection);
        if (planned != null) {
            for (BlockPos position : planned) {
                int chunkX = position.getX() >> 4;
                int chunkZ = position.getZ() >> 4;
                if (chunkX >= minChunkX && chunkX <= maxChunkX
                        && chunkZ >= minChunkZ && chunkZ <= maxChunkZ) return true;
            }
            return false;
        }

        int fromX = connection.from().getX() >> 4;
        int fromZ = connection.from().getZ() >> 4;
        int toX = connection.to().getX() >> 4;
        int toZ = connection.to().getZ() >> 4;
        int edgeMinX = Math.min(fromX, toX) - FALLBACK_CORRIDOR_CHUNKS;
        int edgeMaxX = Math.max(fromX, toX) + FALLBACK_CORRIDOR_CHUNKS;
        int edgeMinZ = Math.min(fromZ, toZ) - FALLBACK_CORRIDOR_CHUNKS;
        int edgeMaxZ = Math.max(fromZ, toZ) + FALLBACK_CORRIDOR_CHUNKS;
        return edgeMaxX >= minChunkX && edgeMinX <= maxChunkX
                && edgeMaxZ >= minChunkZ && edgeMinZ <= maxChunkZ;
    }

    private static boolean markCoverageReady(ServerLevel level, int regionX, int regionZ) {
        WorldState world = worldState(level);
        boolean added = world.ready.add(packRegion(regionX, regionZ));
        if (added) persistReady(level, world);
        RegionEntry entry = ENTRIES.get(key(level, regionX, regionZ));
        if (entry != null) entry.state = State.READY;
        return added;
    }

    private static void markReady(ServerLevel level, RegionKey key, RegionEntry entry) {
        entry.state = State.READY;
        markCoverageReady(level, key.regionX, key.regionZ);
        ACTIVE.remove(key);
        LOGGER.info("RoadWeaver Chunky region READY [{},{}]; {}.",
                key.regionX, key.regionZ, progressSummary(level));
    }

    private static SupplySnapshot supplySnapshot(WorldKey world) {
        int queued = 0;
        int planning = 0;
        int readyAhead = 0;
        for (Map.Entry<RegionKey, RegionEntry> candidate : ENTRIES.entrySet()) {
            if (!candidate.getKey().world.equals(world)) continue;
            RegionEntry entry = candidate.getValue();
            if (entry.state == State.QUEUED) queued++;
            else if (entry.state == State.PLANNING) planning++;
            else if (entry.state == State.READY && !entry.dispatched) readyAhead++;
        }
        return new SupplySnapshot(queued, planning, readyAhead);
    }

    private static int activeCount(WorldKey world) {
        int count = 0;
        for (RegionKey key : ACTIVE) {
            if (key.world.equals(world)) count++;
        }
        return count;
    }

    private static void applySupplyCapacity(int desiredLanes, SupplySnapshot supply) {
        int roadThreads = Math.max(0, desiredLanes);
        // ★ 0.8.401：预热期把 streams 供给**解耦**到 ≤4，不再紧贴 lanes。
        //
        // 原实现 `streamsThreads = min(maxThreads, lanes)` 会让 lanes=1 时
        // 河流供给也被压到 1 —— 而每 tick 的 readiness 推进恰好要等河流板块
        // 预热（StreamsReflowingCompat 内 allOf(...).get(60s)），于是
        // 「lane 低 → 河流更慢 → 单 region 更久 → tick 更慢 → lane 更低」
        // 形成第二重正反馈。这是此前三方分析均未提及的隐藏乘数。
        //
        // 为什么上限 4 而不是与 lanes 同为 8：
        // 避免 8(road) + 8(streams) + 服务端线程 > 16 逻辑核 的超额订阅形状
        // （0.8.372 的事故正是这一类）。
        int streamsThreads;
        if (RuntimeLoadPolicy.isChunkyPreflightActive()) {
            streamsThreads = desiredLanes <= 0 ? 0
                    : Math.max(1, Math.min(4, desiredLanes));
        } else {
            streamsThreads = desiredLanes <= 0 ? 0
                    : Math.max(1, Math.min(RuntimeLoadPolicy.maximumRoadWorkerThreads(), desiredLanes));
        }
        if (roadThreads != Math.max(0, currentSupplyLanes)) {
            ThreadPoolManager.setChunkySupplyThreads(roadThreads);
        }
        if (streamsThreads != currentStreamsThreads) {
            RuntimeLoadPolicy.setStreamsSupplyThreads(streamsThreads);
        }
        if (currentSupplyLanes == desiredLanes && currentStreamsThreads == streamsThreads) return;
        currentSupplyLanes = desiredLanes;
        currentStreamsThreads = streamsThreads;
        LOGGER.info(
                "RoadWeaver Chunky supply capacity: pending={}, queued={}, inFlight={}, readyAhead={}, lanes={}, streams={}.",
                supply.pending(), supply.queued(), supply.planning(), supply.readyAhead(),
                desiredLanes, streamsThreads);
    }

    private static RegionKey pollForWorld(WorldKey world) {
        int attempts = Math.max(1, QUEUE.size());
        while (attempts-- > 0) {
            RegionKey key = QUEUE.poll();
            if (key == null) return null;
            if (key.world.equals(world)) return key;
            QUEUE.add(key);
        }
        return null;
    }

    private static WorldState worldState(ServerLevel level) {
        return WORLDS.computeIfAbsent(worldKey(level), ignored -> loadReady(level));
    }

    private static WorldState loadReady(ServerLevel level) {
        Set<Long> ready = ConcurrentHashMap.newKeySet();
        String expectedFingerprint = readinessFingerprint(level);
        Path path = readyPath(level);
        if (Files.isRegularFile(path)) {
            try {
                int schema = 0;
                String storedFingerprint = "";
                List<Long> storedRegions = new ArrayList<>();
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#")) continue;
                    if (value.startsWith("schema=")) {
                        schema = Integer.parseInt(value.substring("schema=".length()).trim());
                    } else if (value.startsWith("fingerprint=")) {
                        storedFingerprint = value.substring("fingerprint=".length()).trim();
                    } else if (value.startsWith("region=")) {
                        String[] parts = value.substring("region=".length()).split(",", 2);
                        if (parts.length == 2) {
                            storedRegions.add(packRegion(
                                    Integer.parseInt(parts[0].trim()),
                                    Integer.parseInt(parts[1].trim())));
                        }
                    }
                }
                if (schema == READINESS_SCHEMA && expectedFingerprint.equals(storedFingerprint)) {
                    ready.addAll(storedRegions);
                } else {
                    LOGGER.info("RoadWeaver ignored stale Chunky readiness coverage: schema={} fingerprintMatch={}.",
                            schema, expectedFingerprint.equals(storedFingerprint));
                }
            } catch (RuntimeException | IOException failure) {
                LOGGER.warn("RoadWeaver could not read Chunky readiness state {}.", path, failure);
            }
        }
        return new WorldState(ready, expectedFingerprint);
    }

    private static void persistReady(ServerLevel level, WorldState world) {
        List<Long> ordered = new ArrayList<>(world.ready);
        ordered.sort(Long::compare);
        StringBuilder output = new StringBuilder("# RoadWeaver Chunky planning coverage\n");
        output.append("schema=").append(READINESS_SCHEMA).append('\n');
        output.append("fingerprint=").append(world.fingerprint).append('\n');
        for (long packed : ordered) {
            output.append("region=")
                    .append((int) (packed >> 32))
                    .append(',')
                    .append((int) packed)
                    .append('\n');
        }
        try {
            FileStorageIO.writeStringAtomic(readyPath(level), output.toString());
        } catch (IOException failure) {
            LOGGER.error("RoadWeaver could not persist Chunky readiness state.", failure);
        }
    }

    /**
     * Chunky 预热复用指纹：**世界种子 + 算法标识**。
     *
     * <p>0.8.359 起大幅收窄。原实现把「7 段配置的完整 JSON」与「全部已注册
     * STRUCTURE id」一并纳入，于是：</p>
     * <ul>
     *   <li>改一个纯运行参数（{@code initialPlanRadiusChunks} 只影响预热覆盖范围，
     *       不改变任何道路形态）→ 千级区块的预热整份作废，玩家从头重跑；</li>
     *   <li>一次 mod 升级（结构注册表增删）→ 同样整份作废。</li>
     * </ul>
     *
     * <p>现在的输入只有三类，且都语义明确：</p>
     * <ol>
     *   <li><b>域分隔串</b> —— 防止与其它用途的哈希串味。</li>
     *   <li><b>世界指纹</b>（{@code WorldgenFingerprintService}）—— 维度 + 种子 +
     *       区块生成器 + 噪声设置。换种子/换维度/换生成器自然失效，这是必须的。</li>
     *   <li><b>算法标识</b>（{@link ConfigService#planningAlgorithmFingerprintPayload()}）
     *       —— 规划/寻路算法与其代价。算法变了旧规划确实不再适用（等于废弃重算，
     *       符合预期），而这些项玩家几乎不会改动。</li>
     * </ol>
     *
     * <p>刻意<b>不再</b>纳入结构注册表：预热数据描述的是「道路拓扑与地形路径」，
     * 结构列表的增删由规划阶段的实时查询处理；把它写进指纹会让一次常规 mod 升级
     * 摧毁玩家数小时的预热成果。</p>
     */
    private static String readinessFingerprint(ServerLevel level) {
        Hasher hasher = Hashing.sha256().newHasher();
        putString(hasher, "roadweaver:chunky_readiness");
        putString(hasher, WorldgenFingerprintService.forLevel(level, READINESS_SCHEMA).namespace());
        putString(hasher, ConfigService.planningAlgorithmFingerprintPayload());
        return hasher.hash().toString();
    }

    private static void putString(Hasher hasher, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hasher.putInt(bytes.length);
        hasher.putBytes(bytes);
    }

    private static Path readyPath(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, "planning-readiness").resolve(READY_FILE);
    }

    private static RegionKey key(ServerLevel level, int regionX, int regionZ) {
        return new RegionKey(worldKey(level), regionX, regionZ);
    }

    private static WorldKey worldKey(ServerLevel level) {
        return new WorldKey(level.dimension().location().toString(), level.getSeed());
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
    }

    private enum State { UNPLANNED, QUEUED, PLANNING, READY, FAILED, UNSUPPORTED }

    private record WorldKey(String dimension, long seed) { }
    private record RegionKey(WorldKey world, int regionX, int regionZ) { }
    private record ConnectionKey(
            int fromX, int fromY, int fromZ,
            int toX, int toY, int toZ,
            ConnectionStatus status) {
        static ConnectionKey of(StructureConnection connection) {
            return new ConnectionKey(
                    connection.from().getX(), connection.from().getY(), connection.from().getZ(),
                    connection.to().getX(), connection.to().getY(), connection.to().getZ(),
                    connection.status());
        }
    }
    private record CachedPath(List<BlockPos> path, long retryAfterMillis) { }
    private record WorldState(Set<Long> ready, String fingerprint) { }
    private record SupplySnapshot(int queued, int planning, int readyAhead) {
        int pending() {
            return queued + planning;
        }
    }

    private static final class RegionEntry {
        volatile State state;
        volatile int attempts;
        volatile CompletableFuture<Void> planningFuture;
        volatile boolean dispatched;

        private RegionEntry(State state) {
            this.state = state;
        }
    }
}
