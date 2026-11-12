package net.shiroha233.roadweaver.generation;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathlogic.PathFeature;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan;
import net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Replays road construction only for chunks that are already loaded at FULL status. */
public final class LoadedChunkRoadBackfill {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
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

    /** Places only the supplied loaded chunks; used by the non-blocking player queue. */
    public static int placeLoadedChunksExact(ServerLevel level, Collection<Long> candidateChunkKeys) {
        return placeLoadedChunks(level, candidateChunkKeys, false);
    }

    /**
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

    /** Replays a persisted route selected as a supplemental candidate. */
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
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || candidateChunkKeys == null || candidateChunkKeys.isEmpty()) return 0;
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("loaded road backfill must run on the server thread");
        }
        long backfillStartedAt = System.nanoTime();
        Set<Long> expanded = expandNeighbors ? expand(candidateChunkKeys) : new LinkedHashSet<>(candidateChunkKeys);
        ModConfig config = ConfigService.get();
        int loaded = 0;
        int placed = 0;
        int processed = 0;
        int limit = Math.max(1, budget);
        List<Long> noConstruction = new ArrayList<>();
        for (long chunkKey : expanded) {
            if (processed >= limit) break;
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) continue;
            loaded++;
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            // 终态:已确认无需施工的 chunk 廉价跳过,不占本次预算。
            if (DeferredInitialRoadConstructionService.isMarkedNoConstructionRequired(level, chunkKey)) continue;
            // 台账已完成:无需重铺,廉价跳过,不占预算。
            if (DeferredInitialRoadConstructionService.isConstructionComplete(level, chunkPos)) continue;
            // 只编译一次(会话级 LRU 缓存):空计划 = 道路 disc 仅擦边或路线已
            // 迁移,标记为无需施工终态并永久退出 pending/回填候选。
            RoadChunkPlan plan = RoadWorldgenPlanCache.get(level, chunkPos, config);
            if (plan.slices().isEmpty()) {
                noConstruction.add(chunkKey);
                processed++;
                continue;
            }
            RandomSource random = RandomSource.create(level.getSeed() ^ chunkKey ^ RANDOM_SALT);
            if (PathFeature.placeChunk(level, level, chunkPos, random)) placed++;
            processed++;
        }
        if (!noConstruction.isEmpty()) {
            DeferredInitialRoadConstructionService.markNoConstructionRequired(level, noConstruction);
        }
        if (placed > 0 || !noConstruction.isEmpty()) {
            RoadConstructionLedger.flush(level);
            LOGGER.info("RoadWeaver loaded-chunk construction backfill: candidates={} loaded={} processed={} placed={} noConstruction={} elapsedMs={}",
                    expanded.size(), loaded, processed, placed, noConstruction.size(),
                    (System.nanoTime() - backfillStartedAt) / 1_000_000L);
        }
        return placed;
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

    /**
     * A road can be persisted after a player already entered its chunk, or a
     * previously partial chunk can become FULL without a player movement.
     * Keep a small retry window rather than treating one unchanged position as
     * permanently constructed.
     */
    private record PlayerBackfillState(long chunkKey, long retryAfterNanos) {
    }
}
