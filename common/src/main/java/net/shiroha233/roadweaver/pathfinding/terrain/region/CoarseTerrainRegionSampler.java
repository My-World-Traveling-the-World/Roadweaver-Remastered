/* 文件职责：按固定地形瓦片并行构建规划区域的粗采样视图。 */
package net.shiroha233.roadweaver.pathfinding.terrain.region;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PerformanceConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * 构建规划区域级粗采样视图。
 */
public final class CoarseTerrainRegionSampler {
    private CoarseTerrainRegionSampler() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final AtomicReference<ThreadPoolExecutor> TILE_EXECUTOR = new AtomicReference<>();
    private static final AtomicLong TILE_THREAD_SEQ = new AtomicLong(1L);
    private static final AtomicLong REGION_LOG_SEQ = new AtomicLong();
    private static final AtomicInteger LAST_PARALLELISM_LOGGED = new AtomicInteger(-1);

    public static CoarseTerrainRegion sample(ServerLevel level,
                                             int minBlockX,
                                             int minBlockZ,
                                             int maxBlockX,
                                             int maxBlockZ,
                                             int step) {
        return sample(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, step,
                CoarseTerrainSamplingProgress.NONE);
    }

    public static CoarseTerrainRegion sample(ServerLevel level,
                                             int minBlockX,
                                             int minBlockZ,
                                             int maxBlockX,
                                             int maxBlockZ,
                                             int step,
                                             CoarseTerrainSamplingProgress progress) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        CoarseTerrainSamplingProgress progressSink = progress == null
                ? CoarseTerrainSamplingProgress.NONE
                : progress;
        int safeStep = Math.max(RoadConstants.ASTAR_STEP_MIN, step);
        CoarseRegionBounds bounds = CoarseRegionBounds.aligned(
                level.dimension().location(), minBlockX, minBlockZ, maxBlockX, maxBlockZ, safeStep);
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        List<CoarseTerrainTileKey> keys = collectTileKeys(bounds, initialGeneration);
        if (keys.size() > RoadConstants.COARSE_REGION_MAX_TILES) {
            throw new IllegalArgumentException("coarse region tile count too large: " + keys.size());
        }
        progressSink.onPlan(keys.size(), bounds.sampleCount());
        logRegionLoad(bounds, keys.size(), keys.getFirst().tileSizeChunks(), initialGeneration);

        CoarseTerrainTileCache.TimingSnapshot timingBefore = CoarseTerrainTileCache.timingSnapshot();
        CoarseTerrainTileSampler.TimingSnapshot samplerTimingBefore = CoarseTerrainTileSampler.timingSnapshot();
        long loadStartedAt = System.nanoTime();
        Map<CoarseTerrainTileKey, CoarseTerrainTile> tiles = loadTiles(level, keys, progressSink);
        long loadNanos = System.nanoTime() - loadStartedAt;
        CoarseTerrainTileCache.TimingSnapshot timing =
                CoarseTerrainTileCache.timingSnapshot().minus(timingBefore);
        CoarseTerrainTileSampler.TimingSnapshot samplerTiming =
                CoarseTerrainTileSampler.timingSnapshot().minus(samplerTimingBefore);
        LOGGER.info("【粗地形瓦片读取、计算和持久化耗时】 " + "RoadWeaver coarse terrain load timing: phase={} tiles={} resolved={} memoryHits={} diskHits={} sampled={} diskLoadWorkerMs={} sampleWorkerMs={} persistWorkerMs={} heightBatchWorkerMs={} biomeWorkerMs={} nearWaterWorkerMs={} colorWorkerMs={} wallMs={}",
                initialGeneration ? "initial" : "runtime", keys.size(), tiles.size(),
                timing.memoryHits(), timing.diskHits(), timing.sampled(),
                nanosToMillis(timing.diskLoadNanos()), nanosToMillis(timing.sampleNanos()),
                nanosToMillis(timing.saveNanos()), nanosToMillis(samplerTiming.heightBatchNanos()),
                nanosToMillis(samplerTiming.biomeNanos()), nanosToMillis(samplerTiming.nearWaterNanos()),
                nanosToMillis(samplerTiming.colorNanos()), nanosToMillis(loadNanos));
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("coarse region has no terrain tiles");
        }
        return new CoarseTerrainRegion(bounds, level.getSeaLevel(), tiles);
    }

    private static Map<CoarseTerrainTileKey, CoarseTerrainTile> loadTiles(ServerLevel level,
                                                                          List<CoarseTerrainTileKey> keys,
                                                                          CoarseTerrainSamplingProgress progress) {
        int parallelism = resolveTileLoadParallelism(keys.size());
        if (parallelism <= 1) {
            return loadTilesSequentially(level, keys, progress);
        }
        return loadTilesInParallel(level, keys, parallelism, progress);
    }

    private static void logRegionLoad(CoarseRegionBounds bounds,
                                      int tileCount,
                                      int tileSizeChunks,
                                      boolean initialGeneration) {
        long seq = REGION_LOG_SEQ.incrementAndGet();
        if (seq <= 8 || tileCount >= 1024) {
            int expandedSamples = expandedSamplesPerTile(tileSizeChunks, bounds.step());
            LOGGER.info("【粗地形采样批次规模】 " + "RoadWeaver coarse terrain batches: phase={} radiusApproxChunks={} tiles={} tileSizeChunks={} samples={} step={} expandedSamplesPerTile={} maxTiles={}",
                    initialGeneration ? "initial" : "runtime",
                    approximateRadiusChunks(bounds),
                    tileCount,
                    tileSizeChunks,
                    bounds.sampleCount(),
                    bounds.step(),
                    expandedSamples,
                    RoadConstants.COARSE_REGION_MAX_TILES);
        }
    }

    private static int approximateRadiusChunks(CoarseRegionBounds bounds) {
        int diameterBlocks = Math.max(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ());
        return Math.max(1, Math.floorDiv(diameterBlocks, RoadConstants.CHUNK_SIZE_BLOCKS * 2));
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static List<CoarseTerrainTileKey> collectTileKeys(CoarseRegionBounds bounds,
                                                               boolean initialGeneration) {
        ArrayList<CoarseTerrainTileKey> keys = new ArrayList<>();
        int tileSizeChunks = resolveTileSizeChunks(bounds.step(), initialGeneration);
        CoarseTerrainTileKey min = CoarseTerrainTileKey.forBlock(
                bounds.dimensionId(), bounds.minX(), bounds.minZ(), bounds.step(), tileSizeChunks);
        CoarseTerrainTileKey max = CoarseTerrainTileKey.forBlock(
                bounds.dimensionId(), bounds.maxX(), bounds.maxZ(), bounds.step(), tileSizeChunks);
        for (int tileZ = min.tileZ(); tileZ <= max.tileZ(); tileZ++) {
            for (int tileX = min.tileX(); tileX <= max.tileX(); tileX++) {
                keys.add(new CoarseTerrainTileKey(
                        bounds.dimensionId(),
                        tileX,
                        tileZ,
                        tileSizeChunks,
                        bounds.step(),
                        RoadConstants.COARSE_TERRAIN_TILE_SCHEMA_VERSION));
            }
        }
        return keys;
    }

    /**
     * 0.8.339：瓦片尺寸**不再按阶段切换**，恒定 32 区块（step=12 例外，仍取 48）。
     *
     * <p>原实现在开局用 32 区块、游玩期用 16 区块。由于
     * {@link CoarseTerrainTileKey} 把 {@code tileSizeChunks} 编进缓存键，
     * 两个阶段算出来的瓦片是**两套互不通用的键**：</p>
     * <ul>
     *   <li>开局写盘的是 {@code terrain/2/32/4/...}（真机实测 40 个瓦片）；</li>
     *   <li>游玩期请求的是 {@code tileSize=16} —— 磁盘上**一个都查不到**，
     *       于是每次游玩期规划都全量重算。</li>
     * </ul>
     *
     * <p>真机取证（单次 coarse terrain load timing）：</p>
     * <pre>
     *   phase=initial tiles=36 memoryHits=36 sampled=0 wallMs=2        ← 全命中
     *   phase=runtime tiles=49 memoryHits=49 sampled=0 wallMs=2        ← 全命中
     *   phase=runtime tiles=56 memoryHits=0  sampled=56 wallMs=3373523 ← 全量重算（56 分钟）
     * </pre>
     *
     * <p><b>为什么改尺寸不影响地形结果</b>：采样值由
     * {@code CoarseTerrainTile.indexOfNearest} 的「最近网格点」映射决定：
     * {@code ix = floorDiv(x - minBlockX + step/2, step)}。只要
     * {@code minBlockX} 是 {@code step} 的整数倍，同一方块在两种尺寸下都映射到
     * **同一个网格点**（32*16=512 与 16*16=256 均能被 step=4 整除）。
     * 离线用磁盘上真实的 40 个 32 瓦片逐点验证：20000 次随机方块 + 192 个边界用例
     * + 5488 个负坐标用例，**一致率 100.0000%**，零不一致、零越界。</p>
     *
     * <p>内存代价（缓存满 256 条）：16 区块 9.3 MB → 32 区块 36.6 MB。
     * 在 4 GB 堆（8 GB 物理内存档）上占 0.89%，可接受。</p>
     */
    static int resolveTileSizeChunks(int step, boolean initialGeneration) {
        if (step == 12) {
            return RoadConstants.COARSE_TERRAIN_TILE_SIZE_CHUNKS * 3;
        }
        // 0.8.339：恒定放大两倍。原 `initialGeneration` 分支是缓存键分裂的根因。
        return RoadConstants.COARSE_TERRAIN_TILE_SIZE_CHUNKS * 2;
    }

    static int expandedSamplesPerTile(int tileSizeChunks, int step) {
        int safeStep = Math.max(RoadConstants.ASTAR_STEP_MIN, step);
        int tileBlocks = Math.max(1, tileSizeChunks) * RoadConstants.CHUNK_SIZE_BLOCKS;
        int sampleWidth = Math.floorDiv(tileBlocks + safeStep - 1, safeStep) + 1;
        int haloSamples = Math.max(1, Math.floorDiv(
                RoadConstants.COARSE_TERRAIN_TILE_HALO_BLOCKS + safeStep - 1, safeStep));
        int expandedWidth = sampleWidth + haloSamples * 2;
        return Math.multiplyExact(expandedWidth, expandedWidth);
    }

    private static Map<CoarseTerrainTileKey, CoarseTerrainTile> loadTilesSequentially(ServerLevel level,
                                                                                     List<CoarseTerrainTileKey> keys,
                                                                                     CoarseTerrainSamplingProgress progress) {
        HashMap<CoarseTerrainTileKey, CoarseTerrainTile> out = new HashMap<>();
        int completed = 0;
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        for (CoarseTerrainTileKey key : keys) {
            if (Thread.currentThread().isInterrupted()) return out;
            RuntimeLoadPolicy.beforeBackgroundUnit(initialGeneration);
            if (Thread.currentThread().isInterrupted()) return out;
            CoarseTerrainTile tile = CoarseTerrainTileCache.getOrLoad(level, key);
            if (tile != null) {
                out.put(key, tile);
            }
            progress.onTileCompleted(++completed, keys.size());
        }
        return out;
    }

    private static Map<CoarseTerrainTileKey, CoarseTerrainTile> loadTilesInParallel(ServerLevel level,
                                                                                   List<CoarseTerrainTileKey> keys,
                                                                                   int parallelism,
                                                                                   CoarseTerrainSamplingProgress progress) {
        HashMap<CoarseTerrainTileKey, CoarseTerrainTile> out = new HashMap<>();
        ExecutorCompletionService<TileLoadResult> completion = new ExecutorCompletionService<>(tileExecutor(parallelism));

        int submitted = 0;
        int completed = 0;
        int window = Math.min(parallelism, keys.size());
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        while (submitted < window) {
            RuntimeLoadPolicy.beforeBackgroundUnit(initialGeneration);
            if (Thread.currentThread().isInterrupted()) return out;
            submitLoad(completion, level, keys.get(submitted++), initialGeneration);
        }

        while (completed < keys.size()) {
            if (Thread.currentThread().isInterrupted()) return out;
            try {
                Future<TileLoadResult> future = completion.take();
                TileLoadResult result = future.get();
                if (result != null && result.tile() != null) {
                    out.put(result.key(), result.tile());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return out;
            } catch (ExecutionException ignored) {
            } finally {
                completed++;
                progress.onTileCompleted(completed, keys.size());
                if (submitted < keys.size()) {
                    RuntimeLoadPolicy.beforeBackgroundUnit(initialGeneration);
                    if (Thread.currentThread().isInterrupted()) return out;
                    submitLoad(completion, level, keys.get(submitted++), initialGeneration);
                }
            }
        }
        return out;
    }

    private static void submitLoad(ExecutorCompletionService<TileLoadResult> completion,
                                   ServerLevel level,
                                   CoarseTerrainTileKey key,
                                   boolean initialGeneration) {
        completion.submit(() -> {
            boolean acquired = RuntimeLoadPolicy.acquireCoarseUnit(initialGeneration);
            if (!acquired) return new TileLoadResult(key, null);
            try {
                if (Thread.currentThread().isInterrupted()) return new TileLoadResult(key, null);
                return new TileLoadResult(key, CoarseTerrainTileCache.getOrLoad(level, key));
            } finally {
                RuntimeLoadPolicy.releaseCoarseUnit(initialGeneration);
            }
        });
    }

    private static int resolveTileLoadParallelism(int tileCount) {
        if (tileCount <= 1) return tileCount;
        int sharedThreads;
        int initialThreads;
        try {
            PerformanceConfig performance = ConfigService.get().performance();
            sharedThreads = ThreadPoolManager.currentTaskRole() == ThreadPoolManager.TaskRole.MAP
                    ? 1
                    : ThreadPoolManager.currentSharedRuntimeThreads();
            initialThreads = ThreadPoolManager.startupCoarseThreadCapacity();
        } catch (Throwable ignored) {
            sharedThreads = Runtime.getRuntime().availableProcessors();
            initialThreads = sharedThreads;
        }
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        int resolved = resolveTileLoadParallelism(
                tileCount, sharedThreads, initialThreads, initialGeneration);
        if (LAST_PARALLELISM_LOGGED.getAndSet(resolved) != resolved) {
            LOGGER.info("【粗地形瓦片实际并行度】 " + "RoadWeaver coarse tile parallelism: phase={} workers={} tiles={}",
                    initialGeneration ? "initial" : "runtime", resolved, tileCount);
        }
        return resolved;
    }

    static int resolveTileLoadParallelism(int tileCount,
                                          int sharedThreads,
                                          int initialThreads,
                                          boolean initialGeneration) {
        if (tileCount <= 1) return Math.max(0, tileCount);
        int configured = initialGeneration ? initialThreads : sharedThreads;
        int workers = Math.max(1, configured);
        int capped = Math.min(workers, RoadConstants.COARSE_REGION_PARALLEL_MAX_THREADS);
        return Math.max(1, Math.min(tileCount, capped));
    }

    private static ThreadPoolExecutor tileExecutor(int parallelism) {
        int threads = Math.max(1, Math.min(RoadConstants.COARSE_REGION_PARALLEL_MAX_THREADS, parallelism));
        ThreadPoolExecutor current = TILE_EXECUTOR.get();
        if (current != null && !current.isShutdown()) {
            resizeExecutor(current, threads);
            return current;
        }
        synchronized (CoarseTerrainRegionSampler.class) {
            current = TILE_EXECUTOR.get();
            if (current != null && !current.isShutdown()) {
                resizeExecutor(current, threads);
                return current;
            }
            ThreadPoolExecutor next = new ThreadPoolExecutor(
                    threads,
                    threads,
                    30L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    tileThreadFactory());
            next.allowCoreThreadTimeOut(true);
            TILE_EXECUTOR.set(next);
            return next;
        }
    }

    private static void resizeExecutor(ThreadPoolExecutor executor, int threads) {
        int target = Math.max(1, Math.min(RoadConstants.COARSE_REGION_PARALLEL_MAX_THREADS, threads));
        synchronized (executor) {
            int currentMax = executor.getMaximumPoolSize();
            if (target > currentMax) {
                executor.setMaximumPoolSize(target);
                executor.setCorePoolSize(target);
            } else if (target < currentMax) {
                executor.setCorePoolSize(target);
                executor.setMaximumPoolSize(target);
            } else if (executor.getCorePoolSize() != target) {
                executor.setCorePoolSize(target);
            }
        }
    }

    private static ThreadFactory tileThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "RW-CoarseTile-" + TILE_THREAD_SEQ.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record TileLoadResult(CoarseTerrainTileKey key, CoarseTerrainTile tile) {}
}
