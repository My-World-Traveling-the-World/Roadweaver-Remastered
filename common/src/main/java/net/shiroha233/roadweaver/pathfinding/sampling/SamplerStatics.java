/* 文件职责：采样器的纯静态辅助 —— 列分桶与网格键、顺序整理、整批清理。 */
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

// 仍留在同包其它类里的常量与工具。
import static net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler.*;

/**
 * 采样器的纯静态辅助。
 *
 * <h2>为什么这些能单独搬</h2>
 * <p>它们<b>不碰实例状态</b>（不读 this.store / this.closed / 各级缓存），只吃参数、吐结果。
 * 这就是「哪些方法能搬」的判据 —— 前两次尝试把实例方法一起带进来，编译直接崩。</p>
 *
 * <h2>★ 判据不是「起点终点都 static」</h2>
 * <p>第一次我取了「uniquePositions 到 ensureOpen」这个区间，两端都是 static，
 * 中间却夹着实例方法 resolveCachedColumns ⇒ 一起搬走就引用不到 this。
 * **正确判据是「区间内每一个方法都必须是 static」**，所以本类只收连续全 static 段。</p>
 *
 * <h2>★ 列分桶与网格键必须与 ChunkPos 布局一致</h2>
 * <p>{@code tileKey} / {@code groupColumnsByTile} 用「低 32 位 = x、高 32 位 = z」打包。
 * 任何一处写反，分桶会把相邻两列分到不相邻的桶里，缓存命中率悄悄变差、
 * 结果仍「看起来对」—— 这类错误最难查。</p>
 *
 * <p>本类是从 1091 行的 {@code AccurateHeightSampler} 里<b>纯搬迁</b>出来的，
 * 算法与打包口径一字未改（含缩进）。</p>
 */
public final class SamplerStatics {
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

    public static void joinWaitingTiles(Map<Long, CompletableFuture<Void>> waiting,
                                         ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer) {
        joinWaitingTiles(waiting, coalescer, inFlightTimeoutMs());
    }

    /** 超时作为参数传入，离线测试可控制接管时机，不修改全局配置。 */
    static void joinWaitingTiles(Map<Long, CompletableFuture<Void>> waiting,
                                 ConcurrentHashMap<Long, CompletableFuture<Void>> coalescer, long timeoutMs) {
        for (Map.Entry<Long, CompletableFuture<Void>> entry : waiting.entrySet()) {
            try {
                entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timedOut) {
                coalescer.remove(entry.getKey(), entry.getValue());
            } catch (InterruptedException interrupted) {
                // 取消仅属于当前等待线程，共用瓦片的所有者和其他路线仍需拿到结果。
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

    public static long inFlightTimeoutMs() {
        try {
            return Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
        } catch (Throwable ignored) {
            return 3_000L;
        }
    }

    public static LinkedHashMap<Long, BlockPos> uniquePositions(Collection<BlockPos> positions) {
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos position : positions) {
            if (position != null) {
                unique.putIfAbsent(AccurateHeightSample.key(position.getX(), position.getZ()), position);
            }
        }
        return unique;
    }

    public static LinkedHashMap<Long, LinkedHashMap<Long, BlockPos>> groupColumnsByTile(
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

    public static long tileKey(int blockX, int blockZ) {
        return ChunkPos.asLong(Math.floorDiv(blockX, 256), Math.floorDiv(blockZ, 256));
    }

    public static AccurateHeightSample sampleFromChunk(AccurateHeightChunk chunk, int blockX, int blockZ) {
        return new AccurateHeightSample(
                chunk.worldSurfaceWg(blockX, blockZ),
                chunk.oceanFloorWg(blockX, blockZ),
                chunk.motionBlockingNoLeaves(blockX, blockZ));
    }

    public static Map<Long, AccurateHeightChunk> orderedChunks(Collection<Long> order,
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

    public static Map<Long, AccurateHeightSample> orderedSamples(Collection<Long> order,
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
