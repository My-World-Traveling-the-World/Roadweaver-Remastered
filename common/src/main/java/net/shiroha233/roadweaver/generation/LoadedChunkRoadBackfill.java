/* 文件职责：已加载区块的道路被动回填 —— 每次限量，刻意不主动加载区块。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathlogic.PathFeature;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import net.shiroha233.roadweaver.util.ConstructionTimeBudget;

/** 仅对已达到FULL的已加载区块回填，避免后台道路强制加载新区块。 */
public final class LoadedChunkRoadBackfill {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    // 逐块计时只在显式诊断时开启，避免每次落块都把日志开销带入预算。
    private static final boolean CHUNK_TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.deferredChunkTimings", "off"));
    private static final long RANDOM_SALT = 0x524F41444241434BL;
    private static final long SAME_CHUNK_RETRY_NANOS = 5_000_000_000L;
    /**
     * T-20A:单次主线程回填最多预算内处理的 chunk 数(含 placeChunk 与一次性
     * 空计划终态标记)。台账已完成/已标记无施工的 chunk 是廉价跳过,不占预算,
     * 因此队列头部不会饿死后续进度。
     */
    private static final int MAX_BACKFILL_CHUNKS_PER_INVOCATION = 8;
    private static final Map<UUID, PlayerBackfillState> PLAYER_BACKFILL = new ConcurrentHashMap<>();

    private LoadedChunkRoadBackfill() {
    }

    public static int placeLoadedChunks(ServerLevel level, Collection<Long> candidateChunkKeys) {
        return placeLoadedChunks(level, candidateChunkKeys, true);
    }

    /** 只考虑给出的键，仍然必须确认已有 FULL 区块。 */
    public static int placeLoadedChunksExact(ServerLevel level, Collection<Long> candidateChunkKeys) {
        return placeLoadedChunks(level, candidateChunkKeys, false);
    }

    // 只接受当前同步 drain 的结果，不能跨 tick 保留作为已加载证明。
    static int placeSelectedLoadedChunks(ServerLevel level, Map<Long, LevelChunk> knownLoaded) {
        return placeSelectedLoadedChunks(level, knownLoaded, new HashSet<>());
    }

    static int placeSelectedLoadedChunks(ServerLevel level, Map<Long, LevelChunk> knownLoaded, Set<Long> attempted) {
        return placeLoadedChunks(level, knownLoaded.keySet(), false, MAX_BACKFILL_CHUNKS_PER_INVOCATION,
                knownLoaded, null, attempted);
    }

    /** 手绘在本次 tick 已确认 FULL；回传台账结案和空计划，区别施工还需重试。 */
    public static Set<Long> placePreloadedChunks(ServerLevel level, Map<Long, LevelChunk> knownLoaded) {
        return placePreloadedChunks(level, knownLoaded, new HashSet<>());
    }

    /** 回传真正尝试过的键；预算让出的区块仍由原任务持票等待，不消费重试。 */
    public static Set<Long> placePreloadedChunks(ServerLevel level, Map<Long, LevelChunk> knownLoaded, Set<Long> attempted) {
        Set<Long> verified = new HashSet<>();
        placeLoadedChunks(level, knownLoaded.keySet(), false, knownLoaded.size(), knownLoaded, verified, attempted);
        return verified;
    }

    /** 新路回填只处理调用方给定的已加载区块，不扩大范围或请求加载。
 *
     * Replays a newly completed route into chunks that are already FULL.
     * This is deliberately exact: it never expands the set and never asks the
     * chunk source to load anything, so manual routes do not defeat the
     * no-force-load policy.
     */
    public static int placeLoadedRoad(ServerLevel level, RoadData road) {
        if (level == null || road == null || road.roadSegmentList().isEmpty()) return 0;
        Set<Long> routeChunks = new LinkedHashSet<>();
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            if (segment == null) continue;
            addChunk(routeChunks, segment.middlePos());
            if (segment.positions() != null) {
                segment.positions().forEach(position -> addChunk(routeChunks, position));
            }
        }
        return placeLoadedChunksExact(level, routeChunks);
    }

    /** 补充候选使用同一回填边界，避免保留候选触发额外加载。 */
    public static int placeLoadedConnection(ServerLevel level,
                                            net.minecraft.core.BlockPos from,
                                            net.minecraft.core.BlockPos to) {
        if (level == null || from == null || to == null) return 0;
        long fromKey = PlanningUtils.pos2dKey(from);
        long toKey = PlanningUtils.pos2dKey(to);
        int placed = 0;
        for (RoadData road : RoadShardStorage.loadAll(level)) {
            if (road == null || !road.hasOwnerPair()) continue;
            boolean sameDirection = road.ownerA2dKey() == fromKey && road.ownerB2dKey() == toKey;
            boolean reverseDirection = road.ownerA2dKey() == toKey && road.ownerB2dKey() == fromKey;
            if (sameDirection || reverseDirection) placed += placeLoadedRoad(level, road);
        }
        return placed;
    }

    private static void addChunk(Set<Long> chunks, net.minecraft.core.BlockPos position) {
        if (position == null) return;
        chunks.add(ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4));
    }

    private static int placeLoadedChunks(ServerLevel level,
                                         Collection<Long> candidateChunkKeys,
                                         boolean expandNeighbors) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || candidateChunkKeys == null || candidateChunkKeys.isEmpty()) return 0;
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("loaded road backfill must run on the server thread");
        }

        return placeLoadedChunks(level, candidateChunkKeys, expandNeighbors, MAX_BACKFILL_CHUNKS_PER_INVOCATION);
    }

    /**
     * ★ 2026-09-25：允许调用方指定**本次预算**。
     *
     * <h2>为什么需要这个重载</h2>
     * 原实现硬编码 {@code MAX_BACKFILL_CHUNKS_PER_INVOCATION = 8}，
     * 而 {@link net.shiroha233.roadweaver.route.RouteChunkPreloader} 是
     * **先把区块强制加载好再调本方法**的 —— 此时区块已经全部在内存里，
     * 再按 8 个一批切就纯属浪费（等于白分批，361 个还是要走 46 轮）。
     *
     * <p>所以让调用方传预算：预加载路径传与它自己分批一致的批次大小
     * （用户要求 32），被动回填路径保持 8 不变。</p>
     */
    public static int placeLoadedChunks(ServerLevel level,
                                         Collection<Long> candidateChunkKeys,
                                         boolean expandNeighbors,
                                         int budget) {
        return placeLoadedChunks(level, candidateChunkKeys, expandNeighbors, budget, null);
    }

    private static int placeLoadedChunks(ServerLevel level, Collection<Long> candidateChunkKeys,
                                         boolean expandNeighbors, int budget, Map<Long, LevelChunk> knownLoaded) {
        return placeLoadedChunks(level, candidateChunkKeys, expandNeighbors, budget, knownLoaded, null);
    }

    private static int placeLoadedChunks(ServerLevel level, Collection<Long> candidateChunkKeys,
                                         boolean expandNeighbors, int budget, Map<Long, LevelChunk> knownLoaded,
                                         Set<Long> verified) {
        return placeLoadedChunks(level, candidateChunkKeys, expandNeighbors, budget, knownLoaded, verified, null);
    }

    private static int placeLoadedChunks(ServerLevel level, Collection<Long> candidateChunkKeys,
                                         boolean expandNeighbors, int budget, Map<Long, LevelChunk> knownLoaded,
                                         Set<Long> verified, Set<Long> attempted) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || candidateChunkKeys == null || candidateChunkKeys.isEmpty()) return 0;
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("loaded road backfill must run on the server thread");
        }
        long backfillStartedAt = System.nanoTime();
        Set<Long> expanded = expandNeighbors ? expand(candidateChunkKeys) : new LinkedHashSet<>(candidateChunkKeys);
        // 单块计时看不到选块、复核台账和同步落盘；同一轮另记分段时间才能判断卡顿来源。
        long selectionDoneAt = CHUNK_TIMINGS ? System.nanoTime() : 0;
        BackfillPass pass = runBackfill(expanded, budget, knownLoaded,
                key -> level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)),
                key -> DeferredInitialRoadConstructionService.isMarkedNoConstructionRequired(level, key)
                        || DeferredInitialRoadConstructionService.isConstructionComplete(level, new ChunkPos(key)),
                (key, chunk) -> replayTimed(level, key, chunk), verified, attempted,
                new ConstructionTimeBudget(System::nanoTime));
        long replayDoneAt = CHUNK_TIMINGS ? System.nanoTime() : 0;
        if (!pass.noConstruction().isEmpty()) {
            DeferredInitialRoadConstructionService.markNoConstructionRequired(level, pass.noConstruction());
            if (verified != null) verified.addAll(pass.noConstruction());
        }
        boolean flushRequested = pass.placed() > 0 || !pass.noConstruction().isEmpty();
        if (flushRequested) {
            RoadConstructionLedger.flush(level);
        }
        long finishedAt = CHUNK_TIMINGS ? System.nanoTime() : 0;
        if (flushRequested) {
            LOGGER.info("【已加载区块回填的查询、施工和结案统计】 " + "RoadWeaver loaded-chunk construction backfill: candidates={} loaded={} processed={} "
                            + "placed={} noConstruction={} fullLookups={} elapsedMs={}",
                    expanded.size(), pass.loaded(), pass.processed(), pass.placed(), pass.noConstruction().size(),
                    pass.fullLookups(), (System.nanoTime() - backfillStartedAt) / 1_000_000L);
        }
        if (CHUNK_TIMINGS) {
            LOGGER.info("【道路回填分段耗时】 [BACKFILL-PASS-TIMING] candidates={} loaded={} processed={} "
                            + "flushRequested={} selectionUs={} checkAndReplayUs={} settlementUs={} totalUs={}",
                    expanded.size(), pass.loaded(), pass.processed(), flushRequested,
                    (selectionDoneAt - backfillStartedAt) / 1_000L,
                    (replayDoneAt - selectionDoneAt) / 1_000L,
                    (finishedAt - replayDoneAt) / 1_000L, (finishedAt - backfillStartedAt) / 1_000L);
        }
        return pass.placed();
    }

    private static PathFeature.LoadedPlacement replayTimed(ServerLevel level, long key, LevelChunk chunk) {
        if (!CHUNK_TIMINGS) return PathFeature.replayLoadedChunk(level, chunk.getPos(), level.getSeed() ^ key ^ RANDOM_SALT);
        long start = System.nanoTime();
        PathFeature.LoadedPlacement outcome = null;
        try {
            outcome = PathFeature.replayLoadedChunk(level, chunk.getPos(), level.getSeed() ^ key ^ RANDOM_SALT);
            return outcome;
        } finally {
            // 异常保留exception状态，与正常无落块分开；单块超时不能靠下一块让出补救。
            LOGGER.info("【单区块道路回填耗时，异常不算正常无施工】 [CHUNK-REPLAY-TIMING] chunk={} outcome={} replayUs={}",
                    chunk.getPos(), outcome == null ? "exception" : outcome, (System.nanoTime() - start) / 1_000L);
        }
    }

    record BackfillPass(int loaded, int processed, int placed, List<Long> noConstruction, int fullLookups) {}

    @FunctionalInterface
    interface Replay<T> {
        PathFeature.LoadedPlacement place(long key, T loaded);
    }

    static <T> BackfillPass runBackfill(Collection<Long> keys, int budget, Map<Long, T> knownLoaded,
                                         LongFunction<T> loadedNow, LongPredicate settled, Replay<T> replay) {
        return runBackfill(keys, budget, knownLoaded, loadedNow, settled, replay, null);
    }

    static <T> BackfillPass runBackfill(Collection<Long> keys, int budget, Map<Long, T> knownLoaded,
                                         LongFunction<T> loadedNow, LongPredicate settled, Replay<T> replay,
                                         Set<Long> verified) {
        return runBackfill(keys, budget, knownLoaded, loadedNow, settled, replay, verified, null, null);
    }

    static <T> BackfillPass runBackfill(Collection<Long> keys, int budget, Map<Long, T> knownLoaded,
                                         LongFunction<T> loadedNow, LongPredicate settled, Replay<T> replay,
                                         Set<Long> verified, Set<Long> attempted,
                                         ConstructionTimeBudget timeBudget) {
        // 预算只改变本轮推进量，未尝试的键由调用方保留，不能当作失败或已完成。
        List<Long> replayed = verified == null ? null : new ArrayList<>();
        int loaded = 0, processed = 0, placed = 0, fullLookups = 0;
        int limit = Math.max(1, budget);
        List<Long> noConstruction = new ArrayList<>();
        for (long key : keys) {
            if (processed >= limit || (processed > 0 && timeBudget != null && timeBudget.exhausted())) break;
            if (knownLoaded == null) fullLookups++;
            T chunk = knownLoaded == null ? loadedNow.apply(key) : knownLoaded.get(key);
            if (chunk == null) continue;
            loaded++;
            if (attempted != null) attempted.add(key);
            // 前一块可能写台账或保护缺口；只能复用 FULL 句柄，不能复用排队时的施工资格。
            if (settled.test(key)) {
                if (verified != null) verified.add(key);
                continue;
            }
            PathFeature.LoadedPlacement outcome = replay.place(key, chunk);
            if (replayed != null && outcome != PathFeature.LoadedPlacement.EMPTY_PLAN) replayed.add(key);
            switch (outcome) {
                case PLACED -> placed++;
                case EMPTY_PLAN -> noConstruction.add(key);
                case NO_PLACEMENT -> { /* 保留重试资格，不能把失败记成空计划。 */ }
            }
            processed++;
        }
        if (replayed != null) {
            // PLACED 也有可能只完成了同区块的一部分道路；重查台账才能确认结案。
            for (long key : replayed) {
                if (settled.test(key)) verified.add(key);
            }
        }
        return new BackfillPass(loaded, processed, placed, List.copyOf(noConstruction), fullLookups);
    }

    public static int placeLoadedSpawnChunks(ServerLevel level, int radiusChunks) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return 0;
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        int radius = Math.max(1, radiusChunks);
        Set<Long> candidates = new LinkedHashSet<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                candidates.add(ChunkPos.asLong(spawn.x + dx, spawn.z + dz));
            }
        }
        return placeLoadedChunks(level, candidates);
    }

    public static int placeLoadedPlayerChunks(ServerPlayer player, int radiusChunks) {
        if (player == null || !Level.OVERWORLD.equals(player.serverLevel().dimension())) return 0;
        ChunkPos center = player.chunkPosition();
        long currentChunk = center.toLong();
        long now = System.nanoTime();
        PlayerBackfillState previous = PLAYER_BACKFILL.get(player.getUUID());
        if (previous != null && previous.chunkKey() == currentChunk && now < previous.retryAfterNanos()) {
            return 0;
        }
        PLAYER_BACKFILL.put(player.getUUID(), new PlayerBackfillState(
                currentChunk, now + SAME_CHUNK_RETRY_NANOS));
        int radius = Math.max(0, radiusChunks);
        Set<Long> candidates = new LinkedHashSet<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                candidates.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
        }
        return placeLoadedChunks(player.serverLevel(), candidates);
    }

    public static void clearRuntime() {
        PLAYER_BACKFILL.clear();
    }

    private static Set<Long> expand(Collection<Long> chunkKeys) {
        Set<Long> expanded = new LinkedHashSet<>();
        for (long chunkKey : chunkKeys) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    expanded.add(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                }
            }
        }
        return expanded;
    }

    /** 保留短期回填重试窗口，覆盖道路晚发布及区块稍后达到FULL的情况。
 *
     * A road can be persisted after a player already entered its chunk, or a
     * previously partial chunk can become FULL without a player movement.
     * Keep a small retry window rather than treating one unchanged position as
     * permanently constructed.
     */
    private record PlayerBackfillState(long chunkKey, long retryAfterNanos) {
    }
}
