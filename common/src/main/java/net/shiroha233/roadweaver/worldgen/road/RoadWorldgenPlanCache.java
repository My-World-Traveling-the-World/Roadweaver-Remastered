/* 文件职责：按世界会话缓存不可变道路区块计划，隔离持久化与世界生成热路径。 */
package net.shiroha233.roadweaver.worldgen.road;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 世界生成计划的会话入口。磁盘存储只在首次编译计划时被访问，计划本身不可变。
 */
public final class RoadWorldgenPlanCache {
    private static final int QUERY_MARGIN_BLOCKS = 32;
    private static final int INVALIDATION_RADIUS_CHUNKS =
            (QUERY_MARGIN_BLOCKS + 15) / 16;
    /** 有内容计划的 LRU 上限(与旧版一致)。 */
    private static final int MAX_PLANS_PER_LEVEL = 4096;
    /** T-20B:空计划(擦边/无道路)单独预算,远大于有内容计划,避免 Chunky
     * 海量擦边 chunk 把真正需要施工的计划挤出缓存。 */
    private static final int MAX_EMPTY_PLANS_PER_LEVEL = 16384;
    private static final long FAILURE_RETRY_NANOS = 5_000_000_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final ConcurrentHashMap<ServerLevel, Session> SESSIONS = new ConcurrentHashMap<>();

    private RoadWorldgenPlanCache() {
    }

    public static RoadChunkPlan get(ServerLevel level, ChunkPos chunkPos, ModConfig config) {
        if (level == null || chunkPos == null || !Level.OVERWORLD.equals(level.dimension())
                || config == null || !config.roadAppearance().roadsEnabled()) {
            return RoadChunkPlan.unavailable(chunkPos == null ? new ChunkPos(0, 0) : chunkPos,
                    config == null ? 4 : config.roadAppearance().roadClearHeight(), 0L, RoadChunkPlan.Outcome.DISABLED);
        }
        PlanConfig planConfig = PlanConfig.from(config);
        Session session = session(level);
        return session.get(level, chunkPos, planConfig);
    }

    public static void invalidate(ServerLevel level) {
        if (level == null) return;
        Session session = SESSIONS.get(level);
        if (session != null) session.invalidate();
    }

    public static void invalidate(ServerLevel level, Collection<Long> chunkKeys) {
        if (level == null || chunkKeys == null || chunkKeys.isEmpty()) return;
        Session session = SESSIONS.get(level);
        if (session != null) session.invalidate(chunkKeys);
    }

    public static void clear(ServerLevel level) {
        if (level == null) return;
        SESSIONS.remove(level);
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    private static Session session(ServerLevel level) {
        return SESSIONS.computeIfAbsent(level, ignored -> new Session());
    }

    private static RoadChunkPlan compile(ServerLevel level,
                                         ChunkPos chunkPos,
                                         PlanConfig config,
                                         long revision) {
        return compile(chunkPos, config, revision, new RoadChunkPlanCompiler.Query(
                (minX, minZ, maxX, maxZ) -> RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ),
                chunk -> RoadShardStorage.queryChunk(level, chunk)));
    }

    // 离线与生产执行同一编译正文，只有真正读取世界的两处调用允许注入。
    static RoadChunkPlan compile(ChunkPos chunkPos, PlanConfig config, long revision, RoadChunkPlanCompiler.Query query) {
        int minX = chunkPos.getMinBlockX() - QUERY_MARGIN_BLOCKS;
        int minZ = chunkPos.getMinBlockZ() - QUERY_MARGIN_BLOCKS;
        int maxX = chunkPos.getMaxBlockX() + QUERY_MARGIN_BLOCKS;
        int maxZ = chunkPos.getMaxBlockZ() + QUERY_MARGIN_BLOCKS;
        return compileSafely(chunkPos, config.clearHeight(), revision, () -> {
            var roads = query.rectangle().read(minX, minZ, maxX, maxZ);
            RoadChunkPlan plan = RoadChunkPlanCompiler.compile(chunkPos, roads,
                    !config.bridgeEnabled(), config.clearHeight(), revision);
            // The broad rect query goes through the physical-ownership dedup,
            // which can claim a merged road's corridor for a nearby primary
            // and suppress it here even though that primary never actually
            // builds this chunk. When the chunk's own shard store lists a
            // road but the broad compile came back empty, recompile from the
            // exact chunk shards (single road, no dedup against neighbours) so
            // a planned road is never dropped from construction.
            if (plan.slices().isEmpty()) {
                var shardRoads = query.chunk().apply(chunkPos);
                if (shardRoads != null && !shardRoads.isEmpty()) {
                    RoadChunkPlan shardPlan = RoadChunkPlanCompiler.compile(chunkPos, shardRoads,
                            !config.bridgeEnabled(), config.clearHeight(), revision);
                    if (!shardPlan.slices().isEmpty()) {
                        plan = shardPlan;
                    }
                }
            }
            if (roads != null && !roads.isEmpty()) {
                RoadDensityStamp stamp = plan.densityStamp();
                int carveColumns = 0;
                if (stamp != null) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (stamp.carveLateral(x, z) > 0.0F) carveColumns++;
                        }
                    }
                }
                LOGGER.info("【区块世界生成计划编译结果】 " + "RoadWeaver worldgen plan chunk={},{} roads={} slices={} revision={}",
                        chunkPos.x, chunkPos.z, roads.size(), plan.slices().size(), revision);
                if (!plan.slices().isEmpty() && carveColumns == 0) {
                    // Task 4: carve=0 is expected when every slice station is a
                    // physical span (tunnel built by RoadTunnelBuilder, bridge
                    // template, network reuse, protected gap) - paving still
                    // happens per station in PathFeature and does not need the
                    // density carve. Keep the diagnostic at info with that
                    // semantics instead of a misleading warning.
                    LOGGER.info("【区块无需地形雕刻，桥隧及其他跨度仍按站点施工】 " + "RoadWeaver chunk={},{} has {} slice(s) but 0 carve column(s); "
                                    + "expected when all stations are tunnel/bridge/reuse/gap spans, "
                                    + "paving is station-based and unaffected",
                            chunkPos.x, chunkPos.z, plan.slices().size());
                }
            }
            return plan;
        });
    }

    static RoadChunkPlan compileSafely(ChunkPos chunk, int height, long revision, Supplier<RoadChunkPlan> compile) {
        try { return compile.get(); }
        catch (RuntimeException failure) {
            LOGGER.warn("【道路区块计划编译失败，保留重试资格】 chunk={}", chunk, failure);
            return RoadChunkPlan.unavailable(chunk, height, revision, RoadChunkPlan.Outcome.RETRYABLE_FAILURE);
        }
    }

    @FunctionalInterface
    interface Compiler { RoadChunkPlan compile(ChunkPos chunk, PlanConfig config, long revision); }

    static final class Session {
        private final Object cacheLock = new Object();
        /**
         * T-20B:access-order LRU。旧实现是 ConcurrentHashMap + trimIfNeeded()
         * 任意驱逐 —— Chunky 海量生成区块把缓存顶过上限后,玩家周边热点计划被
         * 随机挤出,同一 (chunk,road) 反复全量重编译(日志实证单组合 106 次)。
         * 空计划与有内容计划分表独立预算,热条目只按最久未用顺序驱逐。
         */
        private final LinkedHashMap<Long, CachedPlan> nonEmptyPlans =
                new LinkedHashMap<>(256, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, CachedPlan> eldest) {
                        return nonEmptyPlans.size() > MAX_PLANS_PER_LEVEL;
                    }
                };
        private final LinkedHashMap<Long, CachedPlan> emptyPlans =
                new LinkedHashMap<>(256, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, CachedPlan> eldest) {
                        return emptyPlans.size() > MAX_EMPTY_PLANS_PER_LEVEL;
                    }
                };
        // 故障只在独立的短期表退避，不污染正常空计划；大量坏块也有容量上限。
        private final LinkedHashMap<Long, CachedPlan> failures =
                new LinkedHashMap<>(64, 0.75F, true) {
                    @Override protected boolean removeEldestEntry(Map.Entry<Long, CachedPlan> eldest) {
                        return size() > 1024;
                    }
                };
        private final ConcurrentHashMap<Long, Long> chunkRevisions = new ConcurrentHashMap<>();
        private final AtomicLong revisionSequence = new AtomicLong();
        private volatile long globalRevision;

        RoadChunkPlan get(ServerLevel level, ChunkPos chunkPos, PlanConfig requested) {
            return get(chunkPos, requested, (chunk, config, revision) -> compile(level, chunk, config, revision),
                    System::nanoTime);
        }

        RoadChunkPlan get(ChunkPos chunkPos, PlanConfig requested, Compiler compiler, LongSupplier clock) {
            long key = chunkPos.toLong();
            while (true) {
                CachedPlan cached = lookup(key);
                long expectedRevision = revision(key);
                if (cached != null && cached.matches(requested, expectedRevision, clock.getAsLong())) {
                    return cached.plan();
                }

                // 编译在锁外执行:compile 可能做整片 shard 查询与逐站遍历,
                // 持锁编译会把所有 worldgen 线程串行化。并发同 chunk 重复编译
                // 的结果是确定性的,后写覆盖先写,无正确性影响。
                long compileRevision = revision(key);
                RoadChunkPlan compiled = compiler.compile(chunkPos, requested, compileRevision);
                if (revision(key) != compileRevision) continue;

                store(key, requested, compiled, clock.getAsLong());
                return compiled;
            }
        }

        private CachedPlan lookup(long key) {
            synchronized (cacheLock) {
                CachedPlan plan = nonEmptyPlans.get(key);
                if (plan == null) plan = emptyPlans.get(key);
                return plan != null ? plan : failures.get(key);
            }
        }

        private void store(long key, PlanConfig requested, RoadChunkPlan compiled, long now) {
            CachedPlan cached = new CachedPlan(requested, compiled, now + FAILURE_RETRY_NANOS);
            synchronized (cacheLock) {
                if (!compiled.isAvailable()) {
                    nonEmptyPlans.remove(key);
                    emptyPlans.remove(key);
                    failures.put(key, cached);
                    return;
                }
                failures.remove(key);
                boolean empty = compiled.slices().isEmpty();
                LinkedHashMap<Long, CachedPlan> own = empty ? emptyPlans : nonEmptyPlans;
                LinkedHashMap<Long, CachedPlan> other = empty ? nonEmptyPlans : emptyPlans;
                other.remove(key);
                own.put(key, cached);
            }
        }

        void invalidate() {
            globalRevision = revisionSequence.incrementAndGet();
            synchronized (cacheLock) {
                nonEmptyPlans.clear();
                emptyPlans.clear();
                failures.clear();
            }
            chunkRevisions.clear();
        }

        void invalidate(Collection<Long> chunkKeys) {
            long revision = revisionSequence.incrementAndGet();
            for (long packed : chunkKeys) {
                int chunkX = ChunkPos.getX(packed);
                int chunkZ = ChunkPos.getZ(packed);
                for (int deltaZ = -INVALIDATION_RADIUS_CHUNKS;
                     deltaZ <= INVALIDATION_RADIUS_CHUNKS; deltaZ++) {
                    for (int deltaX = -INVALIDATION_RADIUS_CHUNKS;
                         deltaX <= INVALIDATION_RADIUS_CHUNKS; deltaX++) {
                        long affected = ChunkPos.asLong(chunkX + deltaX, chunkZ + deltaZ);
                        chunkRevisions.put(affected, revision);
                    }
                }
            }
            synchronized (cacheLock) {
                for (long packed : chunkKeys) {
                    int chunkX = ChunkPos.getX(packed);
                    int chunkZ = ChunkPos.getZ(packed);
                    for (int deltaZ = -INVALIDATION_RADIUS_CHUNKS;
                         deltaZ <= INVALIDATION_RADIUS_CHUNKS; deltaZ++) {
                        for (int deltaX = -INVALIDATION_RADIUS_CHUNKS;
                             deltaX <= INVALIDATION_RADIUS_CHUNKS; deltaX++) {
                            long affected = ChunkPos.asLong(chunkX + deltaX, chunkZ + deltaZ);
                            nonEmptyPlans.remove(affected);
                            emptyPlans.remove(affected);
                            failures.remove(affected);
                        }
                    }
                }
            }
        }

        private long revision(long chunkKey) {
            return Math.max(globalRevision, chunkRevisions.getOrDefault(chunkKey, 0L));
        }
    }

    static int invalidationRadiusChunksForTests() {
        return INVALIDATION_RADIUS_CHUNKS;
    }

    private record CachedPlan(PlanConfig config, RoadChunkPlan plan, long retryAt) {
        boolean matches(PlanConfig requested, long revision, long now) {
            return config.equals(requested) && plan.revision() == revision
                    && (plan.isAvailable() || now - retryAt < 0);
        }
    }

    record PlanConfig(int clearHeight, boolean bridgeEnabled) {
        static PlanConfig from(ModConfig config) {
            return new PlanConfig(config.roadAppearance().roadClearHeight(), config.bridgeEnabled());
        }
    }
}
