/* 文件职责：隔离整块精确采样的认领、批量求值、等待和持久化补采。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import static net.shiroha233.roadweaver.pathfinding.sampling.SamplerStatics.orderedChunks;

/** 每个世界采样器独有一份调度器，不跨世界共享认领或缓存。 */
final class AccurateChunkBatch {
    /** 缓存仍由门面管理，调度器只决定何时读写，避免复制容量和分片策略。 */
    interface Cache {
        AccurateHeightChunk get(long key);
        void put(long key, AccurateHeightChunk chunk);
        void ensureOpen();
    }

    private final AccurateHeightBackend backend;
    private final AccurateSampleStore store;
    private final Cache cache;
    private final LongSupplier timeoutMs;
    private final AccurateSamplePublications publications;
    private final ConcurrentHashMap<Long, CompletableFuture<AccurateHeightChunk>> inFlight =
            new ConcurrentHashMap<>();
    // 同线程重入不能等待自己；与其他线程的正常合并分开处理。
    private final ThreadLocal<Set<Long>> ownedChunks = ThreadLocal.withInitial(HashSet::new);

    AccurateChunkBatch(AccurateHeightBackend backend, AccurateSampleStore store,
                       Cache cache, LongSupplier timeoutMs) {
        this(backend, store, cache, timeoutMs, new AccurateSamplePublications());
    }

    /** 整块发布同样会解决逐列未命中，生产与列/网格调度器共用这一份版本。 */
    AccurateChunkBatch(AccurateHeightBackend backend, AccurateSampleStore store,
                       Cache cache, LongSupplier timeoutMs, AccurateSamplePublications publications) {
        this.publications = publications;
        this.backend = backend;
        this.store = store;
        this.cache = cache;
        this.timeoutMs = timeoutMs;
    }

    /** 关闭时唤醒等待者；后端返回后的开放性检查负责拒绝迟到结果。 */
    void close(IllegalStateException failure) {
        inFlight.values().forEach(future -> future.completeExceptionally(failure));
        inFlight.clear();
    }

    Map<Long, AccurateHeightChunk> sample(Collection<Long> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return Map.of();
        }
        cache.ensureOpen();

        LinkedHashSet<Long> uniqueKeys = new LinkedHashSet<>(chunkKeys);
        LinkedHashMap<Long, AccurateHeightChunk> result = new LinkedHashMap<>(uniqueKeys.size());
        LinkedHashSet<Long> unresolved = new LinkedHashSet<>(uniqueKeys.size());
        LinkedHashMap<Long, CompletableFuture<AccurateHeightChunk>> owned = new LinkedHashMap<>();
        LinkedHashMap<Long, CompletableFuture<AccurateHeightChunk>> waiting = new LinkedHashMap<>();

        for (long key : uniqueKeys) {
            AccurateHeightChunk cached = cache.get(key);
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
            // 先计算自己认领的无冲突子集，再等待他人；等待前释放全部认领，
            // 避免两个批次各持一半区块并互相等待，同时保留并行批量求值。
            synchronized (inFlight) {
                for (long key : unresolved) {
                    if (ownedChunks.get().contains(key)) {
                        throw new IllegalStateException("recursive accurate chunk request on the same thread: " + key);
                    }
                }
                // 重入检查整批通过后才写入认领，避免前面的无冲突键成为无人完成的孤儿。
                for (long key : unresolved) {
                    // 读盘期间别的所有者可能已发布缓存并释放认领，锁内复查防止重复求值。
                    AccurateHeightChunk cached = cache.get(key);
                    if (cached != null) {
                        result.put(key, cached);
                        continue;
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
            // 锁内复查的成功项不属于本轮 owned，需独立结案才能退出循环。
            unresolved.removeAll(result.keySet());
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
                // 超时项尚未得到结果，必须留给下一轮接管，不能仅因等待过就宣告完成。
                unresolved.removeAll(result.keySet());
                resolveStoredChunks(unresolved, result);
            }
        }
        return orderedChunks(uniqueKeys, result);
    }

    void completeOwned(Map<Long, CompletableFuture<AccurateHeightChunk>> owned,
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
            cache.ensureOpen();
            store.saveChunks(verified);
            for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : owned.entrySet()) {
                long key = entry.getKey();
                AccurateHeightChunk chunk = verified.get(key);
                cache.put(key, chunk);
                result.put(key, chunk);
            }
            // 所有缓存先完整发布，再记录瓦片版本，最后唤醒共享结果的等待者。
            LinkedHashSet<Long> tiles = new LinkedHashSet<>();
            for (long key : owned.keySet()) {
                tiles.add(ChunkPos.asLong(Math.floorDiv(ChunkPos.getX(key), 16), Math.floorDiv(ChunkPos.getZ(key), 16)));
            }
            publications.publishedTiles(tiles);
            owned.forEach((key, future) -> future.complete(verified.get(key)));
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

    /** 等待有超时上限，失联认领必须可释放，避免整条路线永久阻塞。 */
    void joinWaiting(Map<Long, CompletableFuture<AccurateHeightChunk>> waiting,
                             Map<Long, AccurateHeightChunk> result) {
        long timeoutMs = this.timeoutMs.getAsLong();
        for (Map.Entry<Long, CompletableFuture<AccurateHeightChunk>> entry : waiting.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timedOut) {
                inFlight.remove(entry.getKey(), entry.getValue());
            } catch (InterruptedException interrupted) {
                // Future 属于共享求值；只取消当前等待者，否则其他道路也会收到取消。
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

    void resolveStoredChunks(LinkedHashSet<Long> unresolved,
                                     Map<Long, AccurateHeightChunk> result) {
        if (unresolved.isEmpty()) {
            return;
        }
        Map<Long, AccurateHeightChunk> stored = store.loadChunks(unresolved);
        cache.ensureOpen();
        if (stored.isEmpty()) {
            return;
        }
        LinkedHashSet<Long> loadedTiles = new LinkedHashSet<>();
        Iterator<Long> iterator = unresolved.iterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            AccurateHeightChunk chunk = stored.get(key);
            if (chunk == null) {
                continue;
            }
            cache.put(key, chunk);
            result.put(key, chunk);
            loadedTiles.add(ChunkPos.asLong(Math.floorDiv(ChunkPos.getX(key), 16),
                    Math.floorDiv(ChunkPos.getZ(key), 16)));
            iterator.remove();
        }
        // 整块查盘填充可供列/网格直接读取，同样必须淘汰它们的旧未命中观察。
        publications.publishedTiles(loadedTiles);
    }

}
