/* 文件职责：编排逐列与规则网格精确采样，共享瓦片认领及原缓存端口。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static net.shiroha233.roadweaver.pathfinding.sampling.SamplerStatics.*;

/** 整块调度不进入此类；逐列和网格必须共享同一实例的瓦片状态。 */
final class AccurateColumnBatch {
    /** 高度缓存由门面统一持有，保留整块派生列、分片与容量口径。 */
    interface Cache {
        AccurateHeightSample get(long key, int x, int z);
        void put(long key, AccurateHeightSample sample);
        void ensureOpen();
    }

    private final AccurateHeightBackend backend;
    private final AccurateSampleStore store;
    private final Cache cache;
    private final java.util.function.LongSupplier timeoutMs;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tileInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> acceleratedTileInFlight = new ConcurrentHashMap<>();
    // 同线程已经持有的瓦片不能再等待自己；沿用原逐列递归保护。
    private final ThreadLocal<java.util.Set<Long>> ownedTiles =
            ThreadLocal.withInitial(java.util.HashSet::new);

    AccurateColumnBatch(AccurateHeightBackend backend, AccurateSampleStore store, Cache cache) {
        this(backend, store, cache, SamplerStatics::inFlightTimeoutMs);
    }

    /** 只注入等待预算，生产仍读取同一配置，不更换任何高度计算实现。 */
    AccurateColumnBatch(AccurateHeightBackend backend, AccurateSampleStore store, Cache cache,
                        java.util.function.LongSupplier timeoutMs) {
        this.backend = backend;
        this.store = store;
        this.cache = cache;
        this.timeoutMs = timeoutMs;
    }

    private void awaitTiles(Map<Long, CompletableFuture<Void>> waiting,
                            ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer) {
        if (!waiting.isEmpty()) joinWaitingTiles(waiting, coalescer, timeoutMs.getAsLong());
    }

    /** 先唤醒共享结果的等待者，资源真正关闭仍由门面统一负责。 */
    void close(IllegalStateException failure) {
        tileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        tileInFlight.clear();
        acceleratedTileInFlight.values().forEach(future -> future.completeExceptionally(failure));
        acceleratedTileInFlight.clear();
    }

    Optional<AccurateHeightGrid> loadStoredGrid(AccurateHeightGridRequest request) {
        cache.ensureOpen();
        int sampleCount = request.sampleCount();
        int[] worldSurface = new int[sampleCount];
        int[] oceanFloor = new int[sampleCount];
        int[] motionBlocking = new int[sampleCount];
        LinkedHashMap<Long, Integer> missingIndexes = new LinkedHashMap<>();
        for (int index = 0; index < sampleCount; index++) {
            int blockX = request.blockX(index);
            int blockZ = request.blockZ(index);
            long key = AccurateHeightSample.key(blockX, blockZ);
            AccurateHeightSample sample = cache.get(key, blockX, blockZ);
            if (sample == null) {
                missingIndexes.put(key, index);
            } else {
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, index, sample);
            }
        }
        if (!missingIndexes.isEmpty()) {
            Map<Long, AccurateHeightSample> stored = store.loadSamples(missingIndexes.keySet());
            cache.ensureOpen();
            for (Map.Entry<Long, Integer> entry : missingIndexes.entrySet()) {
                AccurateHeightSample sample = stored.get(entry.getKey());
                if (sample == null) return Optional.empty();
                cache.put(entry.getKey(), sample);
                fillGridColumn(worldSurface, oceanFloor, motionBlocking, entry.getValue(), sample);
            }
        }
        return Optional.of(new AccurateHeightGrid(request, worldSurface, oceanFloor, motionBlocking));
    }

    AccurateHeightGrid sampleGridInternal(AccurateHeightGridRequest request,
                                          AccurateSamplingProgress progress, boolean acceleratedOnly) {
        // 等待他人后重新检查缓存，冲突次数不能变成递归深度。
        for (;;) {
            AccurateHeightGrid resolved = sampleGridOnce(request, progress, acceleratedOnly);
            if (resolved != null) return resolved;
        }
    }

    private AccurateHeightGrid sampleGridOnce(AccurateHeightGridRequest request,
                                              AccurateSamplingProgress progress, boolean acceleratedOnly) {
        cache.ensureOpen();
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
            AccurateHeightSample cached = cache.get(key, blockX, blockZ);
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
        // 关闭可能发生在读盘期间，不能再发布这份迟到快照。
        cache.ensureOpen();
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
                cache.put(key, sample);
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

    Map<Long, AccurateHeightSample> samplePositionsInternal(Collection<BlockPos> positions,
                                                                    AccurateSamplingProgress progress,
                                                                    boolean recordStats,
                                                                    boolean acceleratedOnly) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        cache.ensureOpen();

        LinkedHashMap<Long, BlockPos> unique = uniquePositions(positions);
        if (unique.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<Long, AccurateHeightSample> result = new LinkedHashMap<>(unique.size());
        LinkedHashMap<Long, BlockPos> unresolved = new LinkedHashMap<>(unique.size());

        for (Map.Entry<Long, BlockPos> entry : unique.entrySet()) {
            long key = entry.getKey();
            BlockPos position = entry.getValue();
            AccurateHeightSample cached = cache.get(key, position.getX(), position.getZ());
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
            awaitTiles(waiting, coalescer);
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
            cache.ensureOpen();
            store.saveSamples(verified);
            for (Map.Entry<Long, AccurateHeightSample> entry : verified.entrySet()) {
                long key = entry.getKey();
                AccurateHeightSample sample = entry.getValue();
                cache.put(key, sample);
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

    /** 整网格与逐列请求共用瓦片认领，避免两类请求无约束地重复计算。 */
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
                if (ownedTiles.get().contains(tileKey)) {
                    throw new IllegalStateException("recursive accurate tile request on the same thread: " + tileKey);
                }
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
            awaitTiles(waiting, coalescer);
            // null 仅表示已等待、需重新查缓存；所有求值和保存异常仍直接抛出。
            return null;
        }
        // 网格求值期间必须登记全部瓦片，进度回调和后端重入才不会等待自己。
        ownedTiles.get().addAll(owned.keySet());
        try {
            AccurateHeightGrid sampled = acceleratedOnly
                    ? backend.sampleAcceleratedGrid(request, progress)
                    : backend.sampleGrid(request, progress);
            if (sampled == null || !request.equals(sampled.request())) {
                throw new IllegalStateException("Accurate height backend returned a different grid request");
            }
            cache.ensureOpen();
            // 与逐列批次一致，保存成功后才发布；否则缓存命中会掩盖失败、吞掉重试。
            store.saveGrid(sampled);
            cache.ensureOpen();
            cacheGridColumns(sampled);
            owned.values().forEach(future -> future.complete(null));
            return sampled;
        } catch (Throwable failure) {
            owned.values().forEach(future -> future.completeExceptionally(failure));
            throw failure;
        } finally {
            ownedTiles.get().removeAll(owned.keySet());
            synchronized (coalescer) {
                for (Map.Entry<Long, CompletableFuture<Void>> entry : owned.entrySet()) {
                    coalescer.remove(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    void resolveCachedColumns(LinkedHashMap<Long, BlockPos> unresolved,
                                      Map<Long, AccurateHeightSample> result) {
        Iterator<Map.Entry<Long, BlockPos>> iterator = unresolved.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, BlockPos> entry = iterator.next();
            BlockPos position = entry.getValue();
            AccurateHeightSample sample = cache.get(entry.getKey(), position.getX(), position.getZ());
            if (sample == null) continue;
            result.put(entry.getKey(), sample);
            iterator.remove();
        }
    }

    void resolveStoredColumns(LinkedHashMap<Long, BlockPos> unresolved,
                                      Map<Long, AccurateHeightSample> result) {
        if (unresolved.isEmpty()) {
            return;
        }
        Map<Long, AccurateHeightSample> stored = store.loadSamples(unresolved.keySet());
        cache.ensureOpen();
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
            cache.put(entry.getKey(), sample);
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
            cache.put(
                    AccurateHeightSample.key(grid.request().blockX(index), grid.request().blockZ(index)),
                    new AccurateHeightSample(
                            grid.worldSurface()[index],
                            grid.oceanFloor()[index],
                            grid.motionBlocking()[index]));
        }
    }

}
