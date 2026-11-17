/* 文件职责：持久化 32×32 区块的规划覆盖状态，供外部预生成器查询。
 *
 * READY 的含义是「拓扑、地形路径、结构精确快照都已持久化」，
 * **不代表方块已铺完** —— 物理施工可以晚得多。这个区分是预生成器能否
 * 安全跳过某片区域的前提。
 */
package net.shiroha233.roadweaver.planning;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import net.shiroha233.roadweaver.persistence.files.FileStoragePathResolver;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Entry;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.State;
import net.shiroha233.roadweaver.planning.ReadinessRegionTable.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final boolean READINESS_TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.chunkyReadinessTimings", "off"));
    private static final int READINESS_SCHEMA = ReadinessCoverageStore.SCHEMA;
    private static final int PLAN_MARGIN_CHUNKS = REGION_CHUNKS;
    private static final int FALLBACK_CORRIDOR_CHUNKS = 12;
    private static final int MAX_PLAN_ATTEMPTS = 3;
    private static final long MISSING_PATH_RETRY_MILLIS = 2_000L;
    private static final String READY_FILE = "chunky-ready-regions-v2.txt";

    private static final Map<WorldKey, ReadinessCoverageStore> WORLDS = new ConcurrentHashMap<>();
    private static final ReadinessRegionTable<RegionKey, WorldKey> ENTRIES =
            new ReadinessRegionTable<>(RegionKey::world);
    private static final Map<WorldKey, Map<ConnectionKey, CachedPath>> PLANNED_PATHS =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionKey> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<RegionKey> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean runtimePausedForDimension;
    private static volatile int currentSupplyLanes = 1;
    private static volatile int currentStreamsThreads = 1;

    // 仅用于周期诊断；准入必须立即观察同一 tick 内的转换，不能按 tick 缓存旧计数。
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
        ReadinessCoverageStore world = worldState(level);
        long packed = packRegion(regionX, regionZ);
        if (world.contains(packed)) return State.READY.name();

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
        //   ③ 饱和短路（内存，即时计数）—— 饱和且未入项直接 QUEUED，
        //      **跳过 adoption**（被拒的 region 永远进不了 ENTRIES，
        //      让它们跑 adoption 是最大的那笔浪费）
        //   ④ adoption（可能读盘 / 遍历连接表）
        //   ⑤ 入队
        //
        // ⚠️ 必须保留 FAILED 且 attempts < MAX_PLAN_ATTEMPTS 的重排分支
        //    （原逻辑在此处把失败项重新入队）。
        Entry existing = ENTRIES.get(key);
        if (existing != null) {
            if (existing.state() == State.FAILED && existing.attempts < MAX_PLAN_ATTEMPTS) {
                ENTRIES.transition(key, existing, State.QUEUED);
                QUEUE.add(key);
            }
            return existing.state().name();
        }

        // 饱和短路：队列已满且是**新** region ⇒ 不做 adoption（省掉最贵的一步），
        // 直接回 QUEUED 让 Chunky 按自己节奏重试（对外 API 语义不变）。
        if (backlogSaturated(worldKey(level))) {
            return State.QUEUED.name();
        }

        if (adoptCompletedInitialCoverage(level, regionX, regionZ)) return State.READY.name();

        Entry created = new Entry(State.QUEUED);
        Entry entry = ENTRIES.putIfAbsent(key, created);
        if (entry == null) {
            QUEUE.add(key);
            entry = created;
            LOGGER.info("【区域规划请求已加入Chunky就绪队列】 " + "RoadWeaver queued Chunky readiness region [{},{}].", regionX, regionZ);
        } else if (entry.state() == State.FAILED && entry.attempts < MAX_PLAN_ATTEMPTS) {
            ENTRIES.transition(key, entry, State.QUEUED);
            QUEUE.add(key);
        }
        return entry.state().name();
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

    /** 唯一区域的 QUEUED + PLANNING 为实际积压；历史 READY 和重复队列键不占名额。 */
    private static boolean backlogSaturated(WorldKey world) {
        return supplySnapshot(world).pending() >= MAX_BACKLOG_REGIONS;
    }

    /** Reflection-friendly read-only API used by PlainCraft Chunky AutoGen. */
    public static String regionState(Object levelObject, int regionX, int regionZ) {
        if (!(levelObject instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) {
            return State.UNSUPPORTED.name();
        }
        if (worldState(level).contains(packRegion(regionX, regionZ))) return State.READY.name();
        Entry entry = ENTRIES.get(key(level, regionX, regionZ));
        return entry == null ? State.UNPLANNED.name() : entry.state().name();
    }

    public static String progressSummary(Object levelObject) {
        if (!(levelObject instanceof ServerLevel level)) return "RoadWeaver unavailable";
        WorldKey worldKey = worldKey(level);
        // 进度与调度读取同一口径，避免两个独立扫描在并发转换间互相矛盾。
        Snapshot supply = supplySnapshot(worldKey);
        return "ready=" + worldState(level).size()
                + ", queued=" + supply.queued()
                + ", planning=" + supply.planning()
                + ", readyAhead=" + supply.readyAhead()
                + ", lanes=" + currentSupplyLanes
                + ", streams=" + currentStreamsThreads
                + ", failed=" + supply.failed();
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
        ENTRIES.markDispatched(key(level, regionX, regionZ));
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        // 暂停期间也推进诊断时钟；状态计数随每次转换立即更新。
        long tick = CURRENT_TICK.incrementAndGet();
        if (runtimePausedForDimension) return;
        worldState(level);
        WorldKey world = worldKey(level);
        // 每 20 个 tick 取一份样本，避免计时期间的日志本身占满 tick。
        boolean sampleScan = READINESS_TIMINGS && tick % 20L == 0L;
        long scanStart = sampleScan ? System.nanoTime() : 0L;
        Snapshot supply = supplySnapshot(world);
        if (sampleScan) {
            long scanUs = (System.nanoTime() - scanStart) / 1000;
            LOGGER.info("【READY即时统计读取耗时】 " + "[CHUNKY-READINESS-TIMING] phase=scan source=incremental tick={} entriesApprox={} queued={} planning={} readyAhead={} scanUs={}",
                    tick, ENTRIES.size(), supply.queued(), supply.planning(), supply.readyAhead(), scanUs);
        }
        int desiredLanes = RuntimeLoadPolicy.recommendedCoordinatorLanes(
                supply.pending(), supply.readyAhead(), currentSupplyLanes);
        applySupplyCapacity(desiredLanes, supply);
        if (desiredLanes <= 0) return;

        int activeForWorld = activeCount(world);
        while (activeForWorld < desiredLanes) {
            RegionKey activeKey = pollForWorld(world);
            if (activeKey == null) return;
            Entry entry = ENTRIES.get(activeKey);
            if (entry == null || entry.state() != State.QUEUED || !ACTIVE.add(activeKey)) continue;
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
                try {
                    if (markCoverageReady(level, regionX, regionZ)) marked++;
                } catch (IOException | RuntimeException failure) {
                    // 初始批次继续处理其他区域；失败区域保持未就绪，后续请求可重新入队。
                    LOGGER.error("【初始READY覆盖持久化失败】 " + "RoadWeaver could not persist initial readiness region [{},{}].", regionX, regionZ, failure);
                }
            }
        }
        LOGGER.info("【初始Chunky规划覆盖已登记】 " + "RoadWeaver registered initial Chunky-ready coverage: marked={} {}.",
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
            Entry entry = ENTRIES.get(activeKey);
            if (entry != null && entry.state() == State.PLANNING
                    && connectionTouchesRegion(level, connection, activeKey)) return true;
        }
        return false;
    }

    public static void clearRuntime() {
        for (Entry entry : ENTRIES.values()) {
            CompletableFuture<Void> future = entry.planningFuture;
            if (future != null) future.cancel(true);
        }
        ENTRIES.clear();
        QUEUE.clear();
        ACTIVE.clear();
        // 同步写入也必须先排空，再允许同一路径的新会话加载或提交。
        for (ReadinessCoverageStore store : WORLDS.values()) store.close();
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
            LOGGER.info("【返回主世界，恢复Chunky就绪规划】 " + "RoadWeaver Chunky readiness planning resumed in minecraft:overworld.");
            return;
        }
        for (Map.Entry<RegionKey, Entry> candidate : ENTRIES.entrySet()) {
            Entry entry = candidate.getValue();
            CompletableFuture<Void> future = entry.planningFuture;
            if (future != null) future.cancel(true);
            entry.planningFuture = null;
            if (entry.state() == State.PLANNING) ENTRIES.transition(candidate.getKey(), entry, State.QUEUED);
        }
        ACTIVE.clear();
        QUEUE.clear();
        for (Map.Entry<RegionKey, Entry> queued : ENTRIES.entrySet()) {
            if (queued.getValue().state() == State.QUEUED) QUEUE.add(queued.getKey());
        }
        ThreadPoolManager.setChunkySupplyThreads(0);
        RuntimeLoadPolicy.setStreamsSupplyThreads(0);
        LOGGER.info("【离开主世界，暂停Chunky就绪规划】 " + "RoadWeaver Chunky readiness planning paused outside minecraft:overworld.");
    }

    private static void startPlanning(ServerLevel level, RegionKey key, Entry entry) {
        ENTRIES.transition(key, entry, State.PLANNING);
        entry.attempts++;
        int minChunkX = key.regionX * REGION_CHUNKS - PLAN_MARGIN_CHUNKS;
        int minChunkZ = key.regionZ * REGION_CHUNKS - PLAN_MARGIN_CHUNKS;
        int maxChunkX = (key.regionX + 1) * REGION_CHUNKS - 1 + PLAN_MARGIN_CHUNKS;
        int maxChunkZ = (key.regionZ + 1) * REGION_CHUNKS - 1 + PLAN_MARGIN_CHUNKS;
        long epoch = ThreadPoolManager.currentEpoch();
        LOGGER.info("【开始准备Chunky区域，当前重试次数如下】 " + "RoadWeaver preparing Chunky region [{},{}], attempt={}/{} with guaranteed coordinator progress.",
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
                if (finishPlanning(worldState(level), key, entry, epoch, failure,
                        failure == null && regionPlanningDataReady(level, key.regionX, key.regionZ))) {
                    LOGGER.info("【Chunky区域规划已持久化就绪，尚不代表方块竣工】 " + "RoadWeaver Chunky region READY [{},{}]; {}.",
                            key.regionX, key.regionZ, progressSummary(level));
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
        LOGGER.info("【Chunky区域河流预热开始】 " + "RoadWeaver Chunky prewarm phase=PREWARM region=[{},{}] positions={}",
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
        return adoptCoverageReady(worldState(level), key(level, regionX, regionZ));
    }

    private static boolean adoptCoverageReady(ReadinessCoverageStore store, RegionKey key) {
        try {
            if (markCoverageReady(store, key)) {
                LOGGER.info("【已复用初始规划覆盖作为Chunky就绪结果】 " + "RoadWeaver adopted existing initial planning coverage for Chunky region [{},{}].",
                        key.regionX, key.regionZ);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            // 写失败不能仍向 requestRegion 返回 READY；让它走原有入队与限次重试。
            LOGGER.error("【复用READY覆盖时持久化失败】 " + "RoadWeaver could not persist adopted readiness region [{},{}].", key.regionX, key.regionZ, failure);
            return false;
        }
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

    private static boolean markCoverageReady(ServerLevel level, int regionX, int regionZ) throws IOException {
        return markCoverageReady(worldState(level), key(level, regionX, regionZ));
    }

    private static boolean markCoverageReady(ReadinessCoverageStore store, RegionKey key) throws IOException {
        Entry entry = ENTRIES.get(key);
        boolean added = store.markReady(packRegion(key.regionX, key.regionZ));
        if (entry != null) ENTRIES.transition(key, entry, State.READY);
        return added;
    }

    private static boolean finishPlanning(ReadinessCoverageStore store, RegionKey key, Entry entry,
                                          long epoch, Throwable failure, boolean pathsReady) {
        if (RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(epoch) || ENTRIES.get(key) != entry) return false;
        entry.planningFuture = null;
        Throwable problem = failure;
        if (problem == null) {
            if (!pathsReady) problem = new IllegalStateException("final persisted path verification failed");
            else {
                try {
                    // READY 的集合和运行态都在同步文件提交成功后发布，失败仍走同一重试预算。
                    store.markReady(packRegion(key.regionX, key.regionZ));
                    // 文件端口可能阻塞；提交期间换代或清理后，不得修改新条目的运行态。
                    if (RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(epoch) || ENTRIES.get(key) != entry) return false;
                    ENTRIES.transition(key, entry, State.READY);
                    entry.lastFailure = null;
                    ACTIVE.remove(key);
                    return true;
                } catch (IOException | RuntimeException writeFailure) {
                    problem = writeFailure;
                }
            }
        }
        // 写失败也可能伴随清理/换代；不能把已经失效的 key 重新塞回新会话队列。
        if (RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(epoch) || ENTRIES.get(key) != entry) return false;
        entry.lastFailure = problem;
        if (entry.attempts < MAX_PLAN_ATTEMPTS) {
            ENTRIES.transition(key, entry, State.QUEUED);
            QUEUE.add(key);
            LOGGER.warn("【Chunky区域规划失败，将重试】 " + "RoadWeaver planning retry for Chunky region [{},{}].", key.regionX, key.regionZ, problem);
        } else {
            ENTRIES.transition(key, entry, State.FAILED);
            LOGGER.error("【Chunky区域规划重试耗尽，准备失败】 " + "RoadWeaver could not prepare Chunky region [{},{}].", key.regionX, key.regionZ, problem);
        }
        ACTIVE.remove(key);
        return false;
    }

    private static Snapshot supplySnapshot(WorldKey world) {
        return ENTRIES.snapshot(world);
    }

    private static int activeCount(WorldKey world) {
        int count = 0;
        for (RegionKey key : ACTIVE) {
            if (key.world.equals(world)) count++;
        }
        return count;
    }

    private static void applySupplyCapacity(int desiredLanes, Snapshot supply) {
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
                "【Chunky规划供给状态与道路、河流并发通道】 " + "RoadWeaver Chunky supply capacity: pending={}, queued={}, inFlight={}, readyAhead={}, lanes={}, streams={}.",
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

    private static ReadinessCoverageStore worldState(ServerLevel level) {
        return WORLDS.computeIfAbsent(worldKey(level), ignored -> loadReady(level));
    }

    private static ReadinessCoverageStore loadReady(ServerLevel level) {
        // 世界相关路径和指纹只在门面解析；文件边界负责原格式，离线可直接打开同一实现。
        return ReadinessCoverageStore.open(readyPath(level), readinessFingerprint(level));
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
}
