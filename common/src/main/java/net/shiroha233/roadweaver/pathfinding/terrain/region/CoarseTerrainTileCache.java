package net.shiroha233.roadweaver.pathfinding.terrain.region;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.persistence.files.CoarseTerrainTileFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 粗采样地形瓦片内存缓存和持久化加载入口。
 *
 * <p>The memory cache is sharded (one LRU per shard) so worker threads do not
 * serialize on a single lock, and in-flight waits are bounded by
 * {@code PlanningConfig.samplingInFlightTimeoutMs()}: a stalled owner is taken
 * over or the request degrades to a miss instead of blocking forever.
 */
public final class CoarseTerrainTileCache {
    private CoarseTerrainTileCache() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MEMORY_CAPACITY = RoadConstants.COARSE_TERRAIN_TILE_CACHE_MAX_ENTRIES;
    private static final int MEMORY_SHARD_COUNT = 16;
    private static final int DEFAULT_IN_FLIGHT_TIMEOUT_MS = 3_000;
    private static ConcurrentHashMap<CoarseTerrainTileKey, CompletableFuture<CoarseTerrainTile>> IN_FLIGHT =
            new ConcurrentHashMap<>();
    private static volatile LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile>[] MEMORY_SHARDS =
            createMemoryShards();
    private static final AtomicLong TIMING_MEMORY_HITS = new AtomicLong();
    private static final AtomicLong TIMING_DISK_HITS = new AtomicLong();
    private static final AtomicLong TIMING_SAMPLED = new AtomicLong();
    private static final AtomicLong TIMING_DISK_LOAD_NANOS = new AtomicLong();
    private static final AtomicLong TIMING_SAMPLE_NANOS = new AtomicLong();
    private static final AtomicLong TIMING_SAVE_NANOS = new AtomicLong();
    private static final AtomicLong CACHE_GENERATION = new AtomicLong();
    private static final ThreadLocal<java.util.Set<CoarseTerrainTileKey>> OWNED_KEYS =
            ThreadLocal.withInitial(java.util.HashSet::new);

    private enum AwaitOutcome { COMPLETED, TAKE_OVER, FAILED }

    private record AwaitResult(AwaitOutcome outcome, CoarseTerrainTile tile) {}

    public record TimingSnapshot(long memoryHits,
                                 long diskHits,
                                 long sampled,
                                 long diskLoadNanos,
                                 long sampleNanos,
                                 long saveNanos) {
        public TimingSnapshot minus(TimingSnapshot before) {
            if (before == null) return this;
            return new TimingSnapshot(
                    Math.max(0L, memoryHits - before.memoryHits),
                    Math.max(0L, diskHits - before.diskHits),
                    Math.max(0L, sampled - before.sampled),
                    Math.max(0L, diskLoadNanos - before.diskLoadNanos),
                    Math.max(0L, sampleNanos - before.sampleNanos),
                    Math.max(0L, saveNanos - before.saveNanos));
        }
    }

    public static TimingSnapshot timingSnapshot() {
        return new TimingSnapshot(
                TIMING_MEMORY_HITS.get(), TIMING_DISK_HITS.get(), TIMING_SAMPLED.get(),
                TIMING_DISK_LOAD_NANOS.get(), TIMING_SAMPLE_NANOS.get(), TIMING_SAVE_NANOS.get());
    }

    public static CoarseTerrainTile getOrLoad(ServerLevel level, CoarseTerrainTileKey key) {
        if (level == null || key == null) return null;

        CoarseTerrainTile cached = getMemory(key);
        if (cached != null) {
            TIMING_MEMORY_HITS.incrementAndGet();
            InitialGenerationProgressTracker.recordTileMemoryHit();
            return cached;
        }
        return coordinateInFlight(key, () -> loadOrSample(level, key));
    }

    /**
     * In-flight coordination, kept package-visible so the concurrency
     * regression can drive it without a Minecraft level. Exactly one thread
     * owns a key at a time; every other thread waits at most the configured
     * in-flight timeout, then either takes the stalled entry over or degrades
     * to a miss — a hung sampler can never block workers forever.
     */
    static CoarseTerrainTile coordinateInFlight(CoarseTerrainTileKey key,
                                                Supplier<CoarseTerrainTile> loader) {
        CompletableFuture<CoarseTerrainTile> future = new CompletableFuture<>();
        CompletableFuture<CoarseTerrainTile> existing = IN_FLIGHT.putIfAbsent(key, future);
        if (existing != null) {
            if (OWNED_KEYS.get().contains(key)) {
                // Same-thread recursion would wait on its own future; fail fast
                // instead of spinning or deadlocking.
                throw new IllegalStateException("recursive coarse tile request on the same thread: " + key);
            }
            AwaitResult awaited = awaitExisting(key, existing);
            switch (awaited.outcome()) {
                case COMPLETED:
                    return awaited.tile();
                case FAILED:
                    return null;
                case TAKE_OVER:
                    // This thread removed the stalled entry and now runs the
                    // load itself below. If another thread won the takeover
                    // race, wait on that winner once with the same bound.
                    CompletableFuture<CoarseTerrainTile> raced = IN_FLIGHT.putIfAbsent(key, future);
                    if (raced != null) {
                        AwaitResult second = awaitExisting(key, raced);
                        if (second.outcome() == AwaitOutcome.COMPLETED) return second.tile();
                        return null;
                    }
                    break;
            }
        }

        long generation = CACHE_GENERATION.get();
        OWNED_KEYS.get().add(key);
        try {
            CoarseTerrainTile loaded = loader.get();
            ensureCurrentGeneration(generation);
            future.complete(loaded);
            return loaded;
        } catch (CancellationException cancelled) {
            future.completeExceptionally(cancelled);
            return null;
        } catch (Throwable t) {
            future.completeExceptionally(t);
            LOGGER.warn("加载粗采样地形瓦片失败 tile=[{},{}]", key.tileX(), key.tileZ(), t);
            return null;
        } finally {
            OWNED_KEYS.get().remove(key);
            IN_FLIGHT.remove(key, future);
        }
    }

    private static AwaitResult awaitExisting(CoarseTerrainTileKey key,
                                             CompletableFuture<CoarseTerrainTile> existing) {
        long timeoutMs = inFlightTimeoutMs();
        try {
            return new AwaitResult(AwaitOutcome.COMPLETED,
                    existing.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException timedOut) {
            // Owner is stalled (native call, worldgen). Take the entry over
            // instead of blocking forever; if the owner finished meanwhile the
            // remove fails and this request degrades to a miss.
            if (IN_FLIGHT.remove(key, existing)) {
                return new AwaitResult(AwaitOutcome.TAKE_OVER, null);
            }
            return new AwaitResult(AwaitOutcome.FAILED, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new AwaitResult(AwaitOutcome.FAILED, null);
        } catch (Throwable failed) {
            // Owner completed exceptionally: same contract as before, the
            // caller sees a miss and may retry later.
            return new AwaitResult(AwaitOutcome.FAILED, null);
        }
    }

    static long inFlightTimeoutMs() {
        try {
            return Math.max(100, Math.min(30_000,
                    ConfigService.get().planning().samplingInFlightTimeoutMs()));
        } catch (Throwable ignored) {
            return DEFAULT_IN_FLIGHT_TIMEOUT_MS;
        }
    }

    /**
     * 释放所有缓存。用新实例替换而非 clear()，确保内部 table 数组被 GC 回收；
     * 换代后旧代任务通过 ensureCurrentGeneration 直接取消。
     */
    public static void clearAll() {
        CACHE_GENERATION.incrementAndGet();
        MEMORY_SHARDS = createMemoryShards();
        IN_FLIGHT = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile>[] createMemoryShards() {
        int perShardCapacity = Math.max(8, MEMORY_CAPACITY / MEMORY_SHARD_COUNT);
        LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile>[] shards =
                new LinkedHashMap[MEMORY_SHARD_COUNT];
        for (int i = 0; i < MEMORY_SHARD_COUNT; i++) {
            shards[i] = new LinkedHashMap<>(perShardCapacity * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CoarseTerrainTileKey, CoarseTerrainTile> eldest) {
                    return size() > perShardCapacity;
                }
            };
        }
        return shards;
    }

    private static LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile> shardFor(CoarseTerrainTileKey key) {
        LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile>[] shards = MEMORY_SHARDS;
        return shards[Math.floorMod(key.hashCode(), shards.length)];
    }

    private static CoarseTerrainTile loadOrSample(ServerLevel level, CoarseTerrainTileKey key) {
        CoarseTerrainTile cached = getMemory(key);
        if (cached != null) return cached;

        long generation = CACHE_GENERATION.get();
        long diskStartedAt = System.nanoTime();
        CoarseTerrainTile stored = CoarseTerrainTileFileStorage.loadTile(level, key);
        TIMING_DISK_LOAD_NANOS.addAndGet(System.nanoTime() - diskStartedAt);
        if (stored != null) {
            ensureCurrentGeneration(generation);
            TIMING_DISK_HITS.incrementAndGet();
            InitialGenerationProgressTracker.recordTileDiskHit();
            putMemory(key, stored);
            return stored;
        }

        long sampleStartedAt = System.nanoTime();
        CoarseTerrainTile sampled = CoarseTerrainTileSampler.sample(level, key);
        TIMING_SAMPLE_NANOS.addAndGet(System.nanoTime() - sampleStartedAt);
        if (sampled != null) {
            ensureCurrentGeneration(generation);
            TIMING_SAMPLED.incrementAndGet();
            InitialGenerationProgressTracker.recordTileSampled();
            long saveStartedAt = System.nanoTime();
            CoarseTerrainTileFileStorage.saveTile(level, sampled);
            TIMING_SAVE_NANOS.addAndGet(System.nanoTime() - saveStartedAt);
            putMemory(key, sampled);
        }
        return sampled;
    }

    private static void ensureCurrentGeneration(long generation) {
        if (CACHE_GENERATION.get() != generation) {
            throw new CancellationException("Coarse terrain cache generation changed");
        }
    }

    private static CoarseTerrainTile getMemory(CoarseTerrainTileKey key) {
        LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile> shard = shardFor(key);
        synchronized (shard) {
            return shard.get(key);
        }
    }

    private static void putMemory(CoarseTerrainTileKey key, CoarseTerrainTile tile) {
        LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile> shard = shardFor(key);
        synchronized (shard) {
            shard.put(key, tile);
        }
    }
}
