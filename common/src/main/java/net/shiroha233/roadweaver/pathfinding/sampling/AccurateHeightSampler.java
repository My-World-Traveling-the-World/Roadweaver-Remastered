/* 文件职责：统一编排精确高度采样的内存缓存、持久化命中与后端补采。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.shiroha233.roadweaver.pathfinding.sampling.SamplerStatics.*;

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
    static final int CHUNK_CACHE_CAPACITY = 8192;
    static final int COLUMN_CACHE_CAPACITY = 262_144;
    /**
     * P1-6: the two caches used to sit behind a single {@code cacheLock}, so
     * every worker serialised on every column lookup. They are now split into
     * independently locked LRU buckets. Cache contents only decide reuse, never
     * the sampled values, so sharding cannot change any road result.
     */
    static final int CACHE_SHARDS = 16;
    static final int SHARD_MASK = CACHE_SHARDS - 1;
    static final int CHUNK_SHARD_CAPACITY = Math.max(1, CHUNK_CACHE_CAPACITY / CACHE_SHARDS);
    static final int COLUMN_SHARD_CAPACITY = Math.max(1, COLUMN_CACHE_CAPACITY / CACHE_SHARDS);
    static final Map<ServerLevel, AccurateHeightSampler> SHARED_SAMPLERS = new IdentityHashMap<>();

    final ServerLevel level;
    final int seaLevel;
    final AccurateHeightBackend backend;
    final AccurateSampleStore store;
    final List<Shard<AccurateHeightChunk>> chunkShards;
    final List<Shard<AccurateHeightSample>> columnShards;
    private final AccurateChunkBatch chunkBatch;
    final ConcurrentHashMap<Long, CompletableFuture<Void>> tileInFlight = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long, CompletableFuture<Void>> acceleratedTileInFlight = new ConcurrentHashMap<>();
    final AtomicBoolean closed = new AtomicBoolean();

    /** Tiles currently owned by this thread; prevents recursive self-joins. */
    final ThreadLocal<java.util.Set<Long>> ownedTiles =
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
        this.chunkBatch = new AccurateChunkBatch(this.backend, this.store, new AccurateChunkBatch.Cache() {
            @Override
            public AccurateHeightChunk get(long key) {
                return getCached(key);
            }

            @Override
            public void put(long key, AccurateHeightChunk chunk) {
                putCached(key, chunk);
            }

            @Override
            public void ensureOpen() {
                AccurateHeightSampler.this.ensureOpen();
            }
        }, SamplerStatics::inFlightTimeoutMs);
    }

    /** One independently locked LRU bucket of a shared sampler cache. */
    static final class Shard<V> {
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

    static int shardIndex(long key) {
        return ((int) (key ^ (key >>> 32))) & SHARD_MASK;
    }

    Shard<AccurateHeightChunk> chunkShard(long key) {
        return chunkShards.get(shardIndex(key));
    }

    Shard<AccurateHeightSample> columnShard(long key) {
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

    /** 整块调度独立维护认领，避免与逐列瓦片的锁序和生命周期混在一起。 */
    public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> chunkKeys) {
        return chunkBatch.sample(chunkKeys);
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

    AccurateHeightGrid sampleGridInternal(AccurateHeightGridRequest request,
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

    void closeInternal() {
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
        chunkBatch.close(failure);
        tileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        tileInFlight.clear();
        acceleratedTileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        acceleratedTileInFlight.clear();
        // 资源按声明的逆序关闭：先存储后后端；即使两者都失败也保留首个异常及 suppressed。
        try (backend; store) {
            // 求值认领已经结案，这里只负责确保每个资源都获得一次关闭机会。
        }
    }

    AccurateHeightChunk chunkAt(int x, int z) {
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

    Map<Long, AccurateHeightSample> samplePositionsInternal(Collection<BlockPos> positions,
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

    void completeOwnedColumnTiles(
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
    AccurateHeightGrid sampleWholeGridWithTileLease(AccurateHeightGridRequest request,
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

    AccurateHeightChunk getCached(long key) {
        return chunkShard(key).get(key);
    }

    void putCached(long key, AccurateHeightChunk chunk) {
        chunkShard(key).put(key, chunk);
    }

    AccurateHeightSample cachedSample(long key, int blockX, int blockZ) {
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

    void putColumnCached(long key, AccurateHeightSample sample) {
        columnShard(key).put(key, sample);
    }

    public int surfaceHeight(AccurateHeightSample sample) {
        return sample.motionBlockingNoLeaves() > seaLevel + 2
                ? sample.motionBlockingNoLeaves()
                : sample.worldSurfaceWg();
    }

    void resolveCachedColumns(LinkedHashMap<Long, BlockPos> unresolved,
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

    static List<BlockPos> interpolatePositions(List<BlockPos> path, int divisor) {
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

    void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Accurate height sampler is closed");
        }
    }

    void resolveStoredColumns(LinkedHashMap<Long, BlockPos> unresolved,
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

    void fillGridColumn(int[] worldSurface,
                                int[] oceanFloor,
                                int[] motionBlocking,
                                int index,
                                AccurateHeightSample sample) {
        worldSurface[index] = sample.worldSurfaceWg();
        oceanFloor[index] = sample.oceanFloorWg();
        motionBlocking[index] = sample.motionBlockingNoLeaves();
    }

    void cacheGridColumns(AccurateHeightGrid grid) {
        for (int index = 0; index < grid.request().sampleCount(); index++) {
            putColumnCached(
                    AccurateHeightSample.key(grid.request().blockX(index), grid.request().blockZ(index)),
                    new AccurateHeightSample(
                            grid.worldSurface()[index],
                            grid.oceanFloor()[index],
                            grid.motionBlocking()[index]));
        }
    }

}
