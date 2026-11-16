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
    private final AccurateTileClaims claims;
    private final AccurateSamplePublications publications;

    AccurateColumnBatch(AccurateHeightBackend backend, AccurateSampleStore store, Cache cache) {
        this(backend, store, cache, SamplerStatics::inFlightTimeoutMs);
    }

    /** 只注入等待预算，生产仍读取同一配置，不更换任何高度计算实现。 */
    AccurateColumnBatch(AccurateHeightBackend backend, AccurateSampleStore store, Cache cache,
                        java.util.function.LongSupplier timeoutMs) {
        this(backend, store, cache, timeoutMs, new AccurateSamplePublications());
    }

    /** 生产可让整块与列/网格共用版本，独立夹具仍默认拥有自己的上下文。 */
    AccurateColumnBatch(AccurateHeightBackend backend, AccurateSampleStore store, Cache cache,
                        java.util.function.LongSupplier timeoutMs, AccurateSamplePublications publications) {
        this.publications = publications;
        this.backend = backend;
        this.store = store;
        this.cache = cache;
        this.claims = new AccurateTileClaims(cache::ensureOpen, timeoutMs);
    }

    /** 先结案认领，存储和后端资源仍由门面统一关闭。 */
    void close(IllegalStateException failure) {
        claims.close(failure);
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
        // 必须早于首次缓存扫描；磁盘未命中快照可能在返回前已被其他批次的发布淘汰。
        long observedVersion = publications.version();
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
            return sampleWholeGridWithTileLease(request, sink, acceleratedOnly, observedVersion);
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
        long observedVersion = publications.version();

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
        while (!unresolved.isEmpty()) {
            LinkedHashMap<Long, LinkedHashMap<Long, BlockPos>> byTile = groupColumnsByTile(unresolved);
            try (AccurateTileClaims.Lease lease = claims.claim(byTile.keySet(), acceleratedOnly)) {
                if (lease.isOwner()) {
                    try {
                        // 只重查本次认领涉及的发布槽，冷批次不会无条件二次扫描全部列。
                        if (publications.changedSince(lease.keys(), observedVersion)) {
                            resolveCachedColumns(unresolved, result);
                            resolveStoredColumns(unresolved, result);
                            byTile = groupColumnsByTile(unresolved);
                        }
                        completeOwnedColumnTiles(lease, byTile, unresolved, result, progress, acceleratedOnly);
                    } catch (Throwable failure) {
                        // 新增重查也在持有认领期间；异常必须通知等待者，不能只移除未完成 Future。
                        lease.fail(failure);
                        throw failure;
                    }
                } else {
                    lease.await();
                }
            }
            // 下一轮的观察点仍须早于缓存和磁盘重查，否则会漏掉重查期间的新发布。
            observedVersion = publications.version();
            resolveCachedColumns(unresolved, result);
            resolveStoredColumns(unresolved, result);
        }
        return orderedSamples(unique.keySet(), result);
    }

    void completeOwnedColumnTiles(
            AccurateTileClaims.Lease lease,
            Map<Long, LinkedHashMap<Long, BlockPos>> positionsByTile,
            Map<Long, BlockPos> unresolved,
            Map<Long, AccurateHeightSample> result,
            AccurateSamplingProgress progress,
            boolean acceleratedOnly) {
        try {
            LinkedHashMap<Long, BlockPos> requestedByKey = new LinkedHashMap<>();
            for (long tileKey : lease.keys()) {
                Map<Long, BlockPos> remaining = positionsByTile.get(tileKey);
                if (remaining != null) requestedByKey.putAll(remaining);
            }
            if (requestedByKey.isEmpty()) {
                // 相关发布已经解决全部列，只结案认领，不调用空后端或虚增发布版本。
                lease.succeed();
                return;
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
            // 保存可能与关闭交错；持久化已进入也不能在关闭后重新发布内存样本。
            cache.ensureOpen();
            for (Map.Entry<Long, AccurateHeightSample> entry : verified.entrySet()) {
                long key = entry.getKey();
                AccurateHeightSample sample = entry.getValue();
                cache.put(key, sample);
                result.put(key, sample);
                unresolved.remove(key);
            }
            publications.publishedTiles(positionsByTile.keySet());
            lease.succeed();
        } catch (Throwable failure) {
            lease.fail(failure);
            throw failure;
        }
    }

    /** 整网格与逐列请求共用瓦片认领，避免两类请求无约束地重复计算。 */
    AccurateHeightGrid sampleWholeGridWithTileLease(AccurateHeightGridRequest request,
                                                            AccurateSamplingProgress progress,
                                                            boolean acceleratedOnly, long observedVersion) {
        LinkedHashSet<Long> tileKeys = new LinkedHashSet<>();
        for (int index = 0; index < request.sampleCount(); index++) {
            tileKeys.add(tileKey(request.blockX(index), request.blockZ(index)));
        }
        try (AccurateTileClaims.Lease lease = claims.claim(tileKeys, acceleratedOnly)) {
            if (!lease.isOwner()) {
                lease.await();
                // 已等待后迭代重查缓存，不把争用次数累计成递归深度。
                return null;
            }
            // 新版本只要求重查，不当作高度结果；释放临时认领后沿原网格路径补缺。
            if (publications.changedSince(tileKeys, observedVersion)) {
                lease.succeed();
                return null;
            }
            try {
                AccurateHeightGrid sampled = acceleratedOnly
                        ? backend.sampleAcceleratedGrid(request, progress)
                        : backend.sampleGrid(request, progress);
                if (sampled == null || !request.equals(sampled.request())) {
                    throw new IllegalStateException("Accurate height backend returned a different grid request");
                }
                cache.ensureOpen();
                // 与逐列一致，保存调用返回后才发布；端口抛错不能被缓存吞掉。
                // 文件缓存后端会告警并吞掉部分 IO 错误，正常返回不等于确认数据已落盘。
                store.saveGrid(sampled);
                cache.ensureOpen();
                cacheGridColumns(sampled);
                publications.publishedTiles(tileKeys);
                lease.succeed();
                return sampled;
            } catch (Throwable failure) {
                lease.fail(failure);
                throw failure;
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
