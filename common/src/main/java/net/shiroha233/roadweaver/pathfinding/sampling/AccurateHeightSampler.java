/* 文件职责：统一编排精确高度采样的内存缓存、持久化命中与后端补采。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.persistence.files.FileBackedAccurateSampleStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 精确高度采样统一门面。
 */
public final class AccurateHeightSampler {
    // T-18:缓存容量必须能容纳"一条最长道路的最终校验足迹"(实测 1804 站 ×
    // 宽 5 道路 ≈ 2 万列、~200 chunk),否则同一条路在 continuity 修复、水/结构
    // 校验的多次 validate() 之间自我驱逐,反复触发 "final validation filling
    // missing terrain columns" 整路重采样(日志实证同一路线重复出现)。缓存只
    // 影响重用,不影响采样值本身,道路结果不变。
    //
    // C 阶段（0.8.308）：按 shard 看容量才是真实上限。旧值 1024/16=64 chunk、
    // 32768/16=2048 列，单条路 200+ chunk 就超过一个 shard 的容量，于是"刚预取
    // 完整块、走到 volume walk 就被别的 shard 访问挤掉"，missingColumns 反复出现
    // （真机 totalColumns=29333 missingColumns=6439）。容量按"一条路足迹 + 若干
    // 条相邻路复用"放大，逐列缓存与整块缓存都留得住，跨路也能命中。
    private static final int CHUNK_CACHE_CAPACITY = 8192;
    private static final int COLUMN_CACHE_CAPACITY = 262_144;
    /**
     * P1-6: the two caches used to sit behind a single {@code cacheLock}, so
     * every worker serialised on every column lookup. They are now split into
     * independently locked LRU buckets. Cache contents only decide reuse, never
     * the sampled values, so sharding cannot change any road result.
     */
    private static final int CACHE_SHARDS = 16;
    private static final int SHARD_MASK = CACHE_SHARDS - 1;
    private static final int CHUNK_SHARD_CAPACITY = Math.max(1, CHUNK_CACHE_CAPACITY / CACHE_SHARDS);
    private static final int COLUMN_SHARD_CAPACITY = Math.max(1, COLUMN_CACHE_CAPACITY / CACHE_SHARDS);
    private static final Map<ServerLevel, AccurateHeightSampler> SHARED_SAMPLERS = new IdentityHashMap<>();

    private final ServerLevel level;
    private final int seaLevel;
    private final AccurateHeightBackend backend;
    private final AccurateSampleStore store;
    private final List<Shard<AccurateHeightChunk>> chunkShards;
    private final List<Shard<AccurateHeightSample>> columnShards;
    private final ConcurrentHashMap<Long, CompletableFuture<AccurateHeightChunk>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tileInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> acceleratedTileInFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Tiles currently owned by this thread; prevents recursive self-joins. */
    private final ThreadLocal<java.util.Set<Long>> ownedTiles =
            ThreadLocal.withInitial(java.util.HashSet::new);

    /** Chunks currently owned by this thread; prevents recursive self-joins. */
    private final ThreadLocal<java.util.Set<Long>> ownedChunks =
            ThreadLocal.withInitial(java.util.HashSet::new);

    AccurateHeightSampler(ServerLevel level, AccurateHeightBackend backend) {
        this(level, backend, AccurateSampleStore.noop());
    }

    AccurateHeightSampler(ServerLevel level, AccurateHeightBackend backend, AccurateSampleStore store) {        this.level = level;
        this.seaLevel = level == null ? 63 : level.getSeaLevel();
        this.backend = Objects.requireNonNull(backend, "backend");
        this.store = store == null ? AccurateSampleStore.noop() : store;
        List<Shard<AccurateHeightChunk>> chunks = new ArrayList<>(CACHE_SHARDS);
        List<Shard<AccurateHeightSample>> columns = new ArrayList<>(CACHE_SHARDS);
        for (int index = 0; index < CACHE_SHARDS; index++) {
            chunks.add(new Shard<>(CHUNK_SHARD_CAPACITY));
            columns.add(new Shard<>(COLUMN_SHARD_CAPACITY));
        }
        this.chunkShards = chunks;
        this.columnShards = columns;
    }

    /** One independently locked LRU bucket of a shared sampler cache. */
    private static final class Shard<V> {
        private final Object lock = new Object();
        private final Map<Long, V> entries;

        Shard(int capacity) {
            this.entries = new LinkedHashMap<>(Math.min(64, capacity), 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                    return size() > capacity;
                }
            };
        }

        V get(long key) {
            synchronized (lock) {
                return entries.get(key);
            }
        }

        void put(long key, V value) {
            synchronized (lock) {
                entries.put(key, value);
            }
        }

        void clear() {
            synchronized (lock) {
                entries.clear();
            }
        }

        int size() {
            synchronized (lock) {
                return entries.size();
            }
        }
    }

    private static int shardIndex(long key) {
        return ((int) (key ^ (key >>> 32))) & SHARD_MASK;
    }

    private Shard<AccurateHeightChunk> chunkShard(long key) {
        return chunkShards.get(shardIndex(key));
    }

    private Shard<AccurateHeightSample> columnShard(long key) {
        return columnShards.get(shardIndex(key));
    }

    public static AccurateHeightSampler create(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        synchronized (SHARED_SAMPLERS) {
            AccurateHeightSampler existing = SHARED_SAMPLERS.get(level);
            if (existing != null && !existing.closed.get()) {
                return existing;
            }
            CpuAccurateHeightBackend cpu = CpuAccurateHeightBackend.create(level);
            AccurateHeightSampler created = new AccurateHeightSampler(
                    level,
                    AccurateHeightBackends.create(level, cpu),
                    FileBackedAccurateSampleStore.create(level));
            SHARED_SAMPLERS.put(level, created);
            return created;
        }
    }

    public int motionBlockingNoLeaves(int x, int z) {
        return chunkAt(x, z).motionBlockingNoLeaves(x, z);
    }

    public int worldSurfaceWg(int x, int z) {
        return chunkAt(x, z).worldSurfaceWg(x, z);
    }

    public int oceanFloorWg(int x, int z) {
        return chunkAt(x, z).oceanFloorWg(x, z);
    }

    public int surfaceHeight(int x, int z) {
        AccurateHeightChunk chunk = chunkAt(x, z);
        int motion = chunk.motionBlockingNoLeaves(x, z);
        return motion > seaLevel + 2 ? motion : chunk.worldSurfaceWg(x, z);
    }

    public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return Map.of();
        }
        ensureOpen();

        LinkedHashSet<Long> uniqueKeys = new LinkedHashSet<>(chunkKeys);
        LinkedHashMap<Long, AccurateHeightChunk> result = new LinkedHashMap<>(uniqueKeys.size());
        LinkedHashSet<Long> unresolved = new LinkedHashSet<>(uniqueKeys.size());
        LinkedHashMap<Long, CompletableFuture<AccurateHeightChunk>> owned = new LinkedHashMap<>();
        LinkedHashMap<Long, CompletableFuture<AccurateHeightChunk>> waiting = new LinkedHashMap<>();

        for (long key : uniqueKeys) {
            AccurateHeightChunk cached = getCached(key);
            if (cached != null) {
                AccurateSamplingStats.recordCacheHit();
                result.put(key, cached);
                continue;
            }
            AccurateSamplingStats.recordCacheMiss();
            unresolved.add(key);
        }

        while (!unresolved.isEmpty()) {
            resolveStoredChunks(unresolved, result);
            if (unresolved.isEmpty()) break;
            owned.clear();
            waiting.clear();
            // Claim every key nobody else is generating, and wait for the ones that are already in flight.
            //
            // This used to be all-or-nothing: one chunk claimed by another thread made this thread claim
            // NOTHING and join the whole set instead. A route needs 45-60 chunks, so at 14 concurrent routes
            // almost every attempt collided, and each colliding attempt then paid a full timeout per entry
            // before retrying - which is why the accurate-height phase measured 78.6 s of a 142 s generation
            // on sixteen cores while only ~2.7 lanes were busy. Claiming the conflict-free subset keeps the
            // sampler saturated without changing a single sample: a key is still generated by exactly one
            // thread, and the others read that thread's future.
            //
            // The old comment warned that partial claims deadlock (A owns 1 and waits for 2 while B owns 2
            // and waits for 1). That holds only for hold-and-wait, and this loop does not hold while it
            // waits: completeOwned releases every claim (inFlight.remove, see completeOwned) before
            // joinWaiting runs, so no thread ever waits with a claim outstanding.
            synchronized (inFlight) {
                for (long key : unresolved) {
                    if (ownedChunks.get().contains(key)) {
                        throw new IllegalStateException("recursive accurate chunk request on the same thread: " + key);
                    }
                    CompletableFuture<AccurateHeightChunk> existing = inFlight.get(key);
                    if (existing != null) {
                        waiting.put(key, existing);
                        continue;
                    }
                    CompletableFuture<AccurateHeightChunk> future = new CompletableFuture<>();
                    inFlight.put(key, future);
                    owned.put(key, future);
                }
            }
            if (!owned.isEmpty()) {
                ownedChunks.get().addAll(owned.keySet());
                try {
                    completeOwned(owned, result);
                } finally {
                    ownedChunks.get().removeAll(owned.keySet());
                }
                // C 阶段修根因：completeOwned 只写 result/缓存，不会动 unresolved，
                // 旧代码因此永远满足 while 条件 —— 整块预取路径（FinalRoadStructureValidator
                // 的 sampleChunks）会无限重采同一批 chunk。这正是真机
                // "final validation filling missing terrain columns: missingColumns=6439"
                // 之后卡住 ~93 秒的直接原因。这里显式移除已完成项。
                unresolved.removeAll(owned.keySet());
            }
            if (!waiting.isEmpty()) {
                joinWaiting(waiting, result);
                unresolved.removeAll(waiting.keySet());
                resolveStoredChunks(unresolved, result);
            }
        }
        return orderedChunks(uniqueKeys, result);
    }

    public List<BlockPos> samplePathHeights(List<BlockPos> path, int divisor) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        List<BlockPos> positions = interpolatePositions(path, Math.max(1, divisor));
        Map<Long, AccurateHeightSample> samples = samplePositions(positions);
        List<BlockPos> result = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            int height = sample != null ? surfaceHeight(sample) : surfaceHeight(position.getX(), position.getZ());
            result.add(new BlockPos(position.getX(), height, position.getZ()));
        }
        return result;
    }

    public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions) {
        return samplePositions(positions, AccurateSamplingProgress.NONE);
    }

    public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions,
                                                           AccurateSamplingProgress progress) {
        return samplePositionsInternal(positions, progress, true, false);
    }

    /**
     * D 阶段：按 chunk 批量取样。
     *
     * <p>逐点请求会被后端按 chunk 分组成批；这里先按 chunk 归一，让
     * {@link #sampleChunks} 一次取整块（CPU 后端缓存整块，后续同区块的任何列都命中），
     * 稀疏 chunk 才退回逐列路径。两条路径都来自同一个 NoiseChunk 采样器，取值完全一致，
     * 只影响缓存与批次形状，不改变任何高度。</p>
     *
     * <p>阈值是 {@code >= 2} 而不是"覆盖率"判据，这是经过真机 A/B 验证的选择：
     * 整块预取算出来的列正好覆盖后续 {@code final validation} 的整块需求
     * （真机 {@code column source: cached=26424/26425}），而把阈值抬高只会让这些列
     * 退回最终校验再算一遍（{@code sampleByChunk} 的稀疏列 + 校验自己的整块判据），
     * 总计算量反而增加。因此这里只记录放大倍数供观测，不再调阈值。</p>
     */
    public Map<Long, AccurateHeightSample> sampleByChunk(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return Map.of();
        ensureOpen();
        LinkedHashMap<Long, BlockPos> unique = uniquePositions(positions);
        if (unique.isEmpty()) return Map.of();
        LinkedHashMap<Long, List<BlockPos>> byChunk = new LinkedHashMap<>();
        for (BlockPos position : unique.values()) {
            long chunkKey = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
            byChunk.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(position);
        }
        LinkedHashSet<Long> wholeChunks = new LinkedHashSet<>();
        List<BlockPos> sparse = new ArrayList<>();
        for (Map.Entry<Long, List<BlockPos>> entry : byChunk.entrySet()) {
            if (entry.getValue().size() >= 2) wholeChunks.add(entry.getKey());
            else sparse.addAll(entry.getValue());
        }
        if (!wholeChunks.isEmpty()) sampleChunks(wholeChunks);
        if (!sparse.isEmpty()) samplePositionsInternal(sparse, AccurateSamplingProgress.NONE, true, false);
        if (!wholeChunks.isEmpty() || !sparse.isEmpty()) {
            // 放大诊断：请求列 → 实际计算列（整块 256 列/块 + 稀疏列）。
            // 与真机 final validation totalColumns 对比即可判断整块预取有没有白算。
            AccurateSamplingStats.recordChunkBatch(unique.size(), wholeChunks.size(),
                    wholeChunks.size() * AccurateHeightChunk.COLUMN_COUNT + sparse.size());
        }
        LinkedHashMap<Long, AccurateHeightSample> result = new LinkedHashMap<>(unique.size());
        for (Map.Entry<Long, BlockPos> entry : unique.entrySet()) {
            AccurateHeightSample sample = cachedSample(entry.getKey(),
                    entry.getValue().getX(), entry.getValue().getZ());
            if (sample != null) result.put(entry.getKey(), sample);
        }
        return result;
    }

    /** Returns only already cached/persisted samples and never invokes worldgen. */
    public Map<Long, AccurateHeightSample> cachedSamples(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return Map.of();
        LinkedHashMap<Long, BlockPos> unique = uniquePositions(positions);
        LinkedHashMap<Long, AccurateHeightSample> result = new LinkedHashMap<>();
        LinkedHashSet<Long> missing = new LinkedHashSet<>();
        for (Map.Entry<Long, BlockPos> entry : unique.entrySet()) {
            AccurateHeightSample sample = cachedSample(entry.getKey(),
                    entry.getValue().getX(), entry.getValue().getZ());
            if (sample != null) result.put(entry.getKey(), sample);
            else missing.add(entry.getKey());
        }
        if (!missing.isEmpty()) {
            Map<Long, AccurateHeightSample> stored = store.loadSamples(missing);
            for (Map.Entry<Long, AccurateHeightSample> entry : stored.entrySet()) {
                putColumnCached(entry.getKey(), entry.getValue());
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return orderedSamples(unique.keySet(), result);
    }

    public AccurateHeightSample cachedSample(int x, int z) {
        long key = AccurateHeightSample.key(x, z);
        AccurateHeightSample sample = cachedSample(key, x, z);
        if (sample != null) return sample;
        Map<Long, AccurateHeightSample> stored = store.loadSamples(java.util.Set.of(key));
        sample = stored.get(key);
        if (sample != null) putColumnCached(key, sample);
        return sample;
    }

    public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request) {
        return sampleGrid(request, AccurateSamplingProgress.NONE);
    }

    public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request,
                                         AccurateSamplingProgress progress) {
        return sampleGridInternal(request, progress, false);
    }

    public boolean supportsAcceleratedSampling() {
        return backend.supportsAcceleratedSampling();
    }

    public AccurateHeightGrid sampleAcceleratedGrid(AccurateHeightGridRequest request,
                                                     AccurateSamplingProgress progress) {
        return sampleGridInternal(request, progress, true);
    }

    public Optional<AccurateHeightGrid> loadStoredGrid(AccurateHeightGridRequest request) {
        ensureOpen();
        int sampleCount = request.sampleCount();
        int[] worldSurface = new int[sampleCount];
        int[] oceanFloor = new int[sampleCount];
        int[] motionBlocking = new int[sampleCount];
        LinkedHashMap<Long, Integer> missingIndexes = new LinkedHashMap<>();
        for (int index = 0; index < sampleCount; index++) {
            int blockX = request.blockX(index);
            int blockZ = request.blockZ(index);
            long key = AccurateHeightSample.key(blockX, blockZ);
            AccurateHeightSample sample = cachedSample(key, blockX, blockZ);
            if (sample == null) {
                missingIndexes.put(key, index);
            } else {
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, index, sample);
            }
        }
        if (!missingIndexes.isEmpty()) {
            Map<Long, AccurateHeightSample> stored = store.loadSamples(missingIndexes.keySet());
            for (Map.Entry<Long, Integer> entry : missingIndexes.entrySet()) {
                AccurateHeightSample sample = stored.get(entry.getKey());
                if (sample == null) return Optional.empty();
                putColumnCached(entry.getKey(), sample);
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, entry.getValue(), sample);
            }
        }
        return Optional.of(new AccurateHeightGrid(request, worldSurface, oceanFloor, motionBlocking));
    }

    private AccurateHeightGrid sampleGridInternal(AccurateHeightGridRequest request,
                                                   AccurateSamplingProgress progress,
                                                   boolean acceleratedOnly) {
        ensureOpen();
        AccurateSamplingProgress sink = progress == null ? AccurateSamplingProgress.NONE : progress;
        int sampleCount = request.sampleCount();
        int[] worldSurface = null;
        int[] oceanFloor = null;
        int[] motionBlocking = null;
        IntArrayList missingIndexes = new IntArrayList(sampleCount);
        int resolvedCount = 0;

        for (int index = 0; index < sampleCount; index++) {
            int blockX = request.blockX(index);
            int blockZ = request.blockZ(index);
            long key = AccurateHeightSample.key(blockX, blockZ);
            AccurateHeightSample cached = cachedSample(key, blockX, blockZ);
            if (cached != null) {
                if (worldSurface == null) {
                    worldSurface = new int[sampleCount];
                    oceanFloor = new int[sampleCount];
                    motionBlocking = new int[sampleCount];
                }
                AccurateSamplingStats.recordColumnCacheHit();
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, index, cached);
                resolvedCount++;
                continue;
            }
            AccurateSamplingStats.recordColumnCacheMiss();
            missingIndexes.add(index);
        }

        if (missingIndexes.isEmpty()) {
            return new AccurateHeightGrid(request, worldSurface, oceanFloor, motionBlocking);
        }

        LongArrayList missingKeys = new LongArrayList(missingIndexes.size());
        for (int offset = 0; offset < missingIndexes.size(); offset++) {
            int index = missingIndexes.getInt(offset);
            missingKeys.add(AccurateHeightSample.key(request.blockX(index), request.blockZ(index)));
        }
        Map<Long, AccurateHeightSample> stored = store.loadSamples(missingKeys);
        if (stored.isEmpty() && resolvedCount == 0 && missingIndexes.size() == sampleCount) {
            return sampleWholeGridWithTileLease(request, sink, acceleratedOnly);
        }

        if (worldSurface == null) {
            worldSurface = new int[sampleCount];
            oceanFloor = new int[sampleCount];
            motionBlocking = new int[sampleCount];
        }

        IntArrayList unresolvedIndexes = new IntArrayList(missingIndexes.size());
        for (int offset = 0; offset < missingIndexes.size(); offset++) {
            int index = missingIndexes.getInt(offset);
            long key = AccurateHeightSample.key(request.blockX(index), request.blockZ(index));
            AccurateHeightSample sample = stored.get(key);
            if (sample != null) {
                putColumnCached(key, sample);
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, index, sample);
                resolvedCount++;
                continue;
            }
            unresolvedIndexes.add(index);
        }

        if (!unresolvedIndexes.isEmpty()) {
            ArrayList<BlockPos> unresolvedPositions = new ArrayList<>(unresolvedIndexes.size());
            for (int offset = 0; offset < unresolvedIndexes.size(); offset++) {
                int index = unresolvedIndexes.getInt(offset);
                unresolvedPositions.add(new BlockPos(request.blockX(index), 0, request.blockZ(index)));
            }
            Map<Long, AccurateHeightSample> sampled = samplePositionsInternal(
                    unresolvedPositions, sink, false, acceleratedOnly);
            for (int offset = 0; offset < unresolvedIndexes.size(); offset++) {
                int index = unresolvedIndexes.getInt(offset);
                long key = AccurateHeightSample.key(request.blockX(index), request.blockZ(index));
                AccurateHeightSample sample = sampled.get(key);
                if (sample == null) {
                    throw new IllegalStateException("Missing accurate height column "
                            + request.blockX(index) + "," + request.blockZ(index));
                }
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, index, sample);
            }
        }

        return new AccurateHeightGrid(request, worldSurface, oceanFloor, motionBlocking);
    }

    public void prefetchPositions(Collection<BlockPos> positions) {
        samplePositions(positions);
    }

    /**
     * Startup-only prefetch path. It waits for the shared OpenCL lane instead
     * of silently diverting a large batch to the serial CPU backend.
     */
    public Map<Long, AccurateHeightSample> prefetchAcceleratedPositions(
            Collection<BlockPos> positions, AccurateSamplingProgress progress) {
        if (!supportsAcceleratedSampling()) {
            throw new AcceleratedSamplingUnavailableException(
                    "OpenCL accurate sampling is unavailable for this level");
        }
        return samplePositionsInternal(positions, progress, true, true);
    }

    public String backendName() {
        return backend.backendName();
    }

    public String deviceName() {
        return backend.deviceName();
    }

    /** C 阶段诊断：当前内存里保留的整块数（全部 shard 合计）。 */
    public int cachedChunkCount() {
        int total = 0;
        for (Shard<AccurateHeightChunk> shard : chunkShards) {
            total += shard.size();
        }
        return total;
    }

    /** C 阶段诊断：当前内存里保留的逐列样本数（全部 shard 合计）。 */
    public int cachedColumnCount() {
        int total = 0;
        for (Shard<AccurateHeightSample> shard : columnShards) {
            total += shard.size();
        }
        return total;
    }

    public void clear() {
        synchronized (SHARED_SAMPLERS) {
            if (level != null && SHARED_SAMPLERS.get(level) == this) {
                SHARED_SAMPLERS.remove(level);
            }
        }
        closeInternal();
    }

    private void closeInternal() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Shard<AccurateHeightChunk> shard : chunkShards) {
            shard.clear();
        }
        for (Shard<AccurateHeightSample> shard : columnShards) {
            shard.clear();
        }
        IllegalStateException failure = new IllegalStateException("Accurate height sampler is closed");
        inFlight.values().forEach(future -> future.completeExceptionally(failure));
        inFlight.clear();
        tileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        tileInFlight.clear();
        acceleratedTileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        acceleratedTileInFlight.clear();
        store.close();
        backend.close();
    }

    public static void clearForLevel(ServerLevel level) {
        if (level == null) {
            return;
        }
        AccurateHeightSampler sampler;
        synchronized (SHARED_SAMPLERS) {
            sampler = SHARED_SAMPLERS.remove(level);
        }
        if (sampler != null) {
            sampler.closeInternal();
        }
    }

    public static void clearAll() {
        List<AccurateHeightSampler> samplers;
        synchronized (SHARED_SAMPLERS) {
            samplers = new ArrayList<>(SHARED_SAMPLERS.values());
            SHARED_SAMPLERS.clear();
        }
        for (AccurateHeightSampler sampler : samplers) {
            sampler.closeInternal();
        }
    }

    private AccurateHeightChunk chunkAt(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        AccurateHeightChunk cached = getCached(key);
        if (cached != null) {
            AccurateSamplingStats.recordCacheHit();
            return cached;
        }
        AccurateHeightChunk sampled = sampleChunks(List.of(key)).get(key);
        if (sampled == null) {
            throw new IllegalStateException("Accurate height backend returned no chunk for " + (x >> 4) + "," + (z >> 4));
        }
        return sampled;
    }

    private Map<Long, AccurateHeightSample> samplePositionsInternal(Collection<BlockPos> positions,
                                                                    AccurateSamplingProgress progress,
                                                                    boolean recordStats,
                                                                    boolean acceleratedOnly) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        ensureOpen();

        LinkedHashMap<Long, BlockPos> unique = uniquePositions(positions);
        if (unique.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<Long, AccurateHeightSample> result = new LinkedHashMap<>(unique.size());
        LinkedHashMap<Long, BlockPos> unresolved = new LinkedHashMap<>(unique.size());

        for (Map.Entry<Long, BlockPos> entry : unique.entrySet()) {
            long key = entry.getKey();
            BlockPos position = entry.getValue();
            AccurateHeightSample cached = cachedSample(key, position.getX(), position.getZ());
            if (cached != null) {
                if (recordStats) {
                    AccurateSamplingStats.recordColumnCacheHit();
                }
                result.put(key, cached);
                continue;
            }
            if (recordStats) {
                AccurateSamplingStats.recordColumnCacheMiss();
            }
            unresolved.put(key, position);
        }

        resolveStoredColumns(unresolved, result);
        ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer = acceleratedOnly
                ? acceleratedTileInFlight
                : tileInFlight;
        while (!unresolved.isEmpty()) {
            LinkedHashMap<Long, LinkedHashMap<Long, BlockPos>> byTile = groupColumnsByTile(unresolved);
            LinkedHashMap<Long, CompletableFuture<Void>> owned = new LinkedHashMap<>();
            LinkedHashMap<Long, CompletableFuture<Void>> waiting = new LinkedHashMap<>();
            synchronized (coalescer) {
                boolean conflict = false;
                for (long tileKey : byTile.keySet()) {
                    if (ownedTiles.get().contains(tileKey)) {
                        throw new IllegalStateException("recursive accurate tile request on the same thread: " + tileKey);
                    }
                    if (coalescer.containsKey(tileKey)) conflict = true;
                }
                if (conflict) {
                    for (long tileKey : byTile.keySet()) {
                        CompletableFuture<Void> existing = coalescer.get(tileKey);
                        if (existing != null) waiting.put(tileKey, existing);
                    }
                } else {
                    for (long tileKey : byTile.keySet()) {
                        CompletableFuture<Void> future = new CompletableFuture<>();
                        coalescer.put(tileKey, future);
                        owned.put(tileKey, future);
                    }
                }
            }
            if (!owned.isEmpty()) {
                ownedTiles.get().addAll(owned.keySet());
                try {
                    completeOwnedColumnTiles(
                            owned, byTile, unresolved, result, progress, acceleratedOnly, coalescer);
                } finally {
                    ownedTiles.get().removeAll(owned.keySet());
                }
            }
            joinWaitingTiles(waiting, coalescer);
            resolveCachedColumns(unresolved, result);
            resolveStoredColumns(unresolved, result);
        }
        return orderedSamples(unique.keySet(), result);
    }

    private void completeOwned(Map<Long, CompletableFuture<AccurateHeightChunk>> owned,
                               Map<Long, AccurateHeightChunk> result) {
        try {
            Map<Long, AccurateHeightChunk> sampled = backend.sampleChunks(owned.keySet());
            LinkedHashMap<Long, AccurateHeightChunk> verified = new LinkedHashMap<>(owned.size());
            for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : owned.entrySet()) {
                long key = entry.getKey();
                AccurateHeightChunk chunk = sampled.get(key);
                if (chunk == null) {
                    throw new IllegalStateException("Missing accurate height chunk " + ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
                }
                verified.put(key, chunk);
            }
            ensureOpen();
            store.saveChunks(verified);
            for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : owned.entrySet()) {
                long key = entry.getKey();
                AccurateHeightChunk chunk = verified.get(key);
                putCached(key, chunk);
                result.put(key, chunk);
                entry.getValue().complete(chunk);
            }
        } catch (Throwable failure) {
            for (CompletableFuture<AccurateHeightChunk> future : owned.values()) {
                future.completeExceptionally(failure);
            }
            throw failure;
        } finally {
            for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : owned.entrySet()) {
                inFlight.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Waits for chunk futures owned by other threads, bounded by the configured
     * in-flight timeout. On timeout the stalled owner's claim is released so
     * the outer sampleChunks loop can re-own the chunk on this thread instead
     * of blocking forever.
     */
    private void joinWaiting(Map<Long, CompletableFuture<AccurateHeightChunk>> waiting,
                             Map<Long, AccurateHeightChunk> result) {
        long timeoutMs = inFlightTimeoutMs();
        for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : waiting.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timedOut) {
                inFlight.remove(entry.getKey(), entry.getValue());
            } catch (InterruptedException interrupted) {
                entry.getValue().cancel(true);
                Thread.currentThread().interrupt();
                throw new CancellationException("interrupted while waiting for accurate height chunk");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof CancellationException cancelled) throw cancelled;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("accurate height chunk sampling failed", cause);
            }
        }
    }

    private void completeOwnedColumnTiles(
            Map<Long, CompletableFuture<Void>> owned,
            Map<Long, LinkedHashMap<Long, BlockPos>> positionsByTile,
            Map<Long, BlockPos> unresolved,
            Map<Long, AccurateHeightSample> result,
            AccurateSamplingProgress progress,
            boolean acceleratedOnly,
            ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer) {
        try {
            LinkedHashMap<Long, BlockPos> requestedByKey = new LinkedHashMap<>();
            for (long tileKey : owned.keySet()) {
                requestedByKey.putAll(positionsByTile.get(tileKey));
            }
            AccurateSamplingProgress sink = progress == null ? AccurateSamplingProgress.NONE : progress;
            Map<Long, AccurateHeightSample> sampled = acceleratedOnly
                    ? backend.sampleAcceleratedPositions(requestedByKey.values(), sink)
                    : backend.samplePositions(requestedByKey.values(), sink);
            LinkedHashMap<Long, AccurateHeightSample> verified = new LinkedHashMap<>(requestedByKey.size());
            for (Map.Entry<Long, BlockPos> entry : requestedByKey.entrySet()) {
                long key = entry.getKey();
                AccurateHeightSample sample = sampled.get(key);
                if (sample == null) {
                    BlockPos position = entry.getValue();
                    throw new IllegalStateException("Missing accurate height column " + position.getX() + "," + position.getZ());
                }
                verified.put(key, sample);
            }
            ensureOpen();
            store.saveSamples(verified);
            for (Map.Entry<Long, AccurateHeightSample> entry : verified.entrySet()) {
                long key = entry.getKey();
                AccurateHeightSample sample = entry.getValue();
                putColumnCached(key, sample);
                result.put(key, sample);
                unresolved.remove(key);
            }
            owned.values().forEach(future -> future.complete(null));
        } catch (Throwable failure) {
            for (CompletableFuture<Void> future : owned.values()) {
                future.completeExceptionally(failure);
            }
            throw failure;
        } finally {
            for (Map.Entry<Long, CompletableFuture<Void>> entry : owned.entrySet()) {
                coalescer.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Waits for tile futures owned by other threads, bounded by the configured
     * in-flight timeout. On timeout the stalled owner's claim is released from
     * the coalescer so the caller's next claim round can take the tile over
     * instead of blocking forever; the real owner's finally remove is a no-op.
     */
    private static void joinWaitingTiles(Map<Long, CompletableFuture<Void>> waiting,
                                         ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer) {
        long timeoutMs = inFlightTimeoutMs();
        for (Map.Entry<Long, CompletableFuture<Void>> entry : waiting.entrySet()) {
            try {
                entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timedOut) {
                coalescer.remove(entry.getKey(), entry.getValue());
            } catch (InterruptedException interrupted) {
                entry.getValue().cancel(true);
                Thread.currentThread().interrupt();
                throw new CancellationException("interrupted while waiting for accurate tile");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof CancellationException cancelled) throw cancelled;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("accurate tile sampling failed", cause);
            }
        }
    }

    static long inFlightTimeoutMs() {
        try {
            return Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
        } catch (Throwable ignored) {
            return 3_000L;
        }
    }

    private AccurateHeightGrid sampleWholeGridWithTileLease(AccurateHeightGridRequest request,
                                                            AccurateSamplingProgress progress,
                                                            boolean acceleratedOnly) {
        ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer = acceleratedOnly
                ? acceleratedTileInFlight
                : tileInFlight;
        LinkedHashSet<Long> tileKeys = new LinkedHashSet<>();
        for (int index = 0; index < request.sampleCount(); index++) {
            tileKeys.add(tileKey(request.blockX(index), request.blockZ(index)));
        }
        LinkedHashMap<Long, CompletableFuture<Void>> owned = new LinkedHashMap<>();
        LinkedHashMap<Long, CompletableFuture<Void>> waiting = new LinkedHashMap<>();
        synchronized (coalescer) {
            for (long tileKey : tileKeys) {
                CompletableFuture<Void> existing = coalescer.get(tileKey);
                if (existing != null) waiting.put(tileKey, existing);
            }
            if (waiting.isEmpty()) {
                for (long tileKey : tileKeys) {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    coalescer.put(tileKey, future);
                    owned.put(tileKey, future);
                }
            }
        }
        if (!waiting.isEmpty()) {
            joinWaitingTiles(waiting, coalescer);
            return sampleGridInternal(request, progress, acceleratedOnly);
        }
        try {
            AccurateHeightGrid sampled = acceleratedOnly
                    ? backend.sampleAcceleratedGrid(request, progress)
                    : backend.sampleGrid(request, progress);
            ensureOpen();
            cacheGridColumns(sampled);
            store.saveGrid(sampled);
            owned.values().forEach(future -> future.complete(null));
            return sampled;
        } catch (Throwable failure) {
            owned.values().forEach(future -> future.completeExceptionally(failure));
            throw failure;
        } finally {
            synchronized (coalescer) {
                for (Map.Entry<Long, CompletableFuture<Void>> entry : owned.entrySet()) {
                    coalescer.remove(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private AccurateHeightChunk getCached(long key) {
        return chunkShard(key).get(key);
    }

    private void putCached(long key, AccurateHeightChunk chunk) {
        chunkShard(key).put(key, chunk);
    }

    private AccurateHeightSample cachedSample(long key, int blockX, int blockZ) {
        AccurateHeightSample sample = columnShard(key).get(key);
        if (sample != null) {
            return sample;
        }
        // P1-6: no nested locks. The chunk shard is consulted after the column
        // shard lock has been released, and the derived sample is a pure
        // function of the immutable chunk, so a concurrent duplicate insert is
        // harmless.
        AccurateHeightChunk chunk = getCached(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
        if (chunk == null) {
            return null;
        }
        AccurateHeightSample fromChunk = sampleFromChunk(chunk, blockX, blockZ);
        columnShard(key).put(key, fromChunk);
        return fromChunk;
    }

    private void putColumnCached(long key, AccurateHeightSample sample) {
        columnShard(key).put(key, sample);
    }

    public int surfaceHeight(AccurateHeightSample sample) {
        return sample.motionBlockingNoLeaves() > seaLevel + 2
                ? sample.motionBlockingNoLeaves()
                : sample.worldSurfaceWg();
    }

    private static LinkedHashMap<Long, BlockPos> uniquePositions(Collection<BlockPos> positions) {
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos position : positions) {
            if (position != null) {
                unique.putIfAbsent(AccurateHeightSample.key(position.getX(), position.getZ()), position);
            }
        }
        return unique;
    }

    private static LinkedHashMap<Long, LinkedHashMap<Long, BlockPos>> groupColumnsByTile(
            Map<Long, BlockPos> positions) {
        LinkedHashMap<Long, LinkedHashMap<Long, BlockPos>> grouped = new LinkedHashMap<>();
        for (Map.Entry<Long, BlockPos> entry : positions.entrySet()) {
            BlockPos position = entry.getValue();
            long tileKey = tileKey(position.getX(), position.getZ());
            grouped.computeIfAbsent(tileKey, ignored -> new LinkedHashMap<>())
                    .put(entry.getKey(), position);
        }
        return grouped;
    }

    private static long tileKey(int blockX, int blockZ) {
        return ChunkPos.asLong(Math.floorDiv(blockX, 256), Math.floorDiv(blockZ, 256));
    }

    private void resolveCachedColumns(LinkedHashMap<Long, BlockPos> unresolved,
                                      Map<Long, AccurateHeightSample> result) {
        Iterator<Map.Entry<Long, BlockPos>> iterator = unresolved.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, BlockPos> entry = iterator.next();
            BlockPos position = entry.getValue();
            AccurateHeightSample sample = cachedSample(entry.getKey(), position.getX(), position.getZ());
            if (sample == null) continue;
            result.put(entry.getKey(), sample);
            iterator.remove();
        }
    }

    private static List<BlockPos> interpolatePositions(List<BlockPos> path, int divisor) {
        if (divisor <= 1 || path.size() == 1) {
            return new ArrayList<>(path);
        }
        List<BlockPos> result = new ArrayList<>((path.size() - 1) * divisor + 1);
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos start = path.get(i);
            BlockPos end = path.get(i + 1);
            for (int part = 0; part < divisor; part++) {
                double t = (double) part / divisor;
                int x = (int) Math.round(start.getX() + (end.getX() - start.getX()) * t);
                int z = (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * t);
                result.add(new BlockPos(x, 0, z));
            }
        }
        BlockPos last = path.get(path.size() - 1);
        result.add(new BlockPos(last.getX(), 0, last.getZ()));
        return result;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Accurate height sampler is closed");
        }
    }

    private void resolveStoredChunks(LinkedHashSet<Long> unresolved,
                                     Map<Long, AccurateHeightChunk> result) {
        if (unresolved.isEmpty()) {
            return;
        }
        Map<Long, AccurateHeightChunk> stored = store.loadChunks(unresolved);
        ensureOpen();
        if (stored.isEmpty()) {
            return;
        }
        Iterator<Long> iterator = unresolved.iterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            AccurateHeightChunk chunk = stored.get(key);
            if (chunk == null) {
                continue;
            }
            putCached(key, chunk);
            result.put(key, chunk);
            iterator.remove();
        }
    }

    private void resolveStoredColumns(LinkedHashMap<Long, BlockPos> unresolved,
                                      Map<Long, AccurateHeightSample> result) {
        if (unresolved.isEmpty()) {
            return;
        }
        Map<Long, AccurateHeightSample> stored = store.loadSamples(unresolved.keySet());
        ensureOpen();
        if (stored.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, BlockPos>> iterator = unresolved.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, BlockPos> entry = iterator.next();
            AccurateHeightSample sample = stored.get(entry.getKey());
            if (sample == null) {
                continue;
            }
            putColumnCached(entry.getKey(), sample);
            result.put(entry.getKey(), sample);
            iterator.remove();
        }
    }

    private void fillGridColumn(int[] worldSurface,
                                int[] oceanFloor,
                                int[] motionBlocking,
                                int index,
                                AccurateHeightSample sample) {
        worldSurface[index] = sample.worldSurfaceWg();
        oceanFloor[index] = sample.oceanFloorWg();
        motionBlocking[index] = sample.motionBlockingNoLeaves();
    }

    private void cacheGridColumns(AccurateHeightGrid grid) {
        for (int index = 0; index < grid.request().sampleCount(); index++) {
            putColumnCached(
                    AccurateHeightSample.key(grid.request().blockX(index), grid.request().blockZ(index)),
                    new AccurateHeightSample(
                            grid.worldSurface()[index],
                            grid.oceanFloor()[index],
                            grid.motionBlocking()[index]));
        }
    }

    private static AccurateHeightSample sampleFromChunk(AccurateHeightChunk chunk, int blockX, int blockZ) {
        return new AccurateHeightSample(
                chunk.worldSurfaceWg(blockX, blockZ),
                chunk.oceanFloorWg(blockX, blockZ),
                chunk.motionBlockingNoLeaves(blockX, blockZ));
    }

    private static Map<Long, AccurateHeightChunk> orderedChunks(Collection<Long> order,
                                                                Map<Long, AccurateHeightChunk> chunks) {
        LinkedHashMap<Long, AccurateHeightChunk> ordered = new LinkedHashMap<>(order.size());
        for (long key : order) {
            AccurateHeightChunk chunk = chunks.get(key);
            if (chunk != null) {
                ordered.put(key, chunk);
            }
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<Long, AccurateHeightSample> orderedSamples(Collection<Long> order,
                                                                  Map<Long, AccurateHeightSample> samples) {
        LinkedHashMap<Long, AccurateHeightSample> ordered = new LinkedHashMap<>(order.size());
        for (long key : order) {
            AccurateHeightSample sample = samples.get(key);
            if (sample != null) {
                ordered.put(key, sample);
            }
        }
        return Collections.unmodifiableMap(ordered);
    }
}
