package net.shiroha233.roadweaver.generation;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies roads planned during world entry once the relevant chunks are loaded.
 *
 * <h2>Never requests a chunk, never blocks the server</h2>
 * The queue drains through {@link #tick(ServerLevel)}: it only ever looks at
 * chunks that are already in memory, takes a small bounded sample, and gives up
 * the rest of the tick. Unloaded chunks simply stay pending.
 *
 * <p>★ 2026-10-15: before this, {@code pending} was <b>write-only</b> — nothing
 * read it, so "deferred construction" silently meant "never". The only other
 * path that could place a planned road into an existing chunk walks a square of
 * radius {@link #PLAYER_RADIUS_CHUNKS} around each online player, which a
 * pregenerated region never has. The result was a save whose map showed blue
 * planned lines and whose world had no road under them.</p>
 */
public final class DeferredInitialRoadConstructionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int PLAYER_RADIUS_CHUNKS = 8;
    private static final int MAX_CHUNKS_PER_TICK = 8;
    /**
     * 两次采样之间至少间隔的 tick 数。单次采样最多 {@link #MAX_CHUNKS_PER_TICK}
     * 个区块，所以 10 tick(=0.5 秒)一次 ≈ 16 区块/秒 —— 足够在预生成期间追上
     * 进度，又不会把主线程铺满。
     */
    private static final int DRAIN_INTERVAL_TICKS = 10;
    /**
     * 同一个区块连续采样多少次仍无法结案(既没铺成、也没进台账/终态标记)就
     * 放弃。没有这条兜底，一组「永远铺不动」的区块会每 10 tick 被重采一次，
     * 队列再也不前进。
     */
    private static final int MAX_DRAIN_ATTEMPTS = 3;
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();
    /**
     * 终态:已确认编译计划为空(道路 disc 仅擦边或路线已迁移)、无需任何施工的
     * chunk。这些 chunk 一旦标记就永久退出 pending,不再被回填/deferred 重选,
     * 也不参与 recover 重入队。仅内存态;重启后首个回填周期会重新标记一次。
     */
    private static final Map<ServerLevel, Set<Long>> NO_CONSTRUCTION_REQUIRED = new ConcurrentHashMap<>();

    private DeferredInitialRoadConstructionService() {
    }

    public static void enqueue(ServerLevel level, Collection<Long> chunkKeys) {
        if (!isOverworld(level) || chunkKeys == null || chunkKeys.isEmpty()) return;
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        state.pending.addAll(chunkKeys);
        state.total = Math.max(state.total, state.completed + state.pending.size());
        LOGGER.info("RoadWeaver deferred initial construction queued: chunks={}", state.pending.size());
    }

    /**
     * Rebuilds the in-memory queue after a server restart. Road plans and the
     * construction ledger are persistent, but the old queue intentionally was
     * not. Only incomplete chunks are recovered; player proximity is still
     * enforced by tick().
     */
    public static void recover(ServerLevel level) {
        if (!isOverworld(level)) return;
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        int recovered = 0;
        for (var road : RoadShardStorage.loadAll(level)) {
            if (road == null) continue;
            for (long key : RoadFootprint.from(road).chunkKeys()) {
                ChunkPos chunk = new ChunkPos(key);
                if (!RoadConstructionLedger.isComplete(level, chunk,
                        RoadShardStorage.computeFingerprint(road))
                        && state.pending.add(key)) {
                    recovered++;
                }
            }
        }
        state.total = Math.max(state.total, state.completed + state.pending.size());
        if (recovered > 0) {
            LOGGER.info("RoadWeaver recovered deferred construction queue after restart: chunks={}", recovered);
        }
    }

    /**
     * ★ 2026-10-15：{@link State#pending} 的**唯一消费者** —— 延迟施工排水口。
     *
     * <h2>为什么必须有</h2>
     * 本类此前的 {@code pending} 只写不读：{@link #enqueue} 与 {@link #recover}
     * 往里塞，没有任何代码取出来。于是「延后施工」实际等于「永不施工」——开局
     * 规划落盘时区块往往还没生成（世界生成路径编译出空计划、什么都不铺），而唯一
     * 能补铺的 {@code LoadedChunkRoadBackfill.placeLoadedPlayerChunks} 只在**玩家
     * 周围 {@value #PLAYER_RADIUS_CHUNKS} 区块**内找候选，预生成器（Chunky）推进
     * 的区域没有玩家，永远不会被枚举。用户看到的现象就是「地图上有蓝线，世界里
     * 没有路」。
     *
     * <h2>三条硬约束</h2>
     * <ul>
     *   <li><b>绝不请求区块</b>：只处理 {@code getChunkNow != null} 的已加载区块，
     *       未加载的留在 pending 等以后；</li>
     *   <li><b>绝不阻塞</b>：单次采样最多 {@value #MAX_CHUNKS_PER_TICK} 个区块，
     *       且每 {@value #DRAIN_INTERVAL_TICKS} tick 才采一次；</li>
     *   <li><b>幂等</b>：施工台账按（区块, 道路指纹）判定，重复铺过的区块廉价跳过
     *       并直接从 pending 移除；同一区块连续 {@value #MAX_DRAIN_ATTEMPTS} 次
     *       采不出结果就放弃（防止队列被一组铺不动的区块永久卡住）。</li>
     * </ul>
     *
     * @return 本次真正铺出道路的区块数（0 表示被节流、无活可干或没有已加载区块）
     */
    public static int tick(ServerLevel level) {
        if (!isOverworld(level) || !level.getServer().isSameThread()) return 0;
        State state = STATES.get(level);
        if (state == null || state.pending.isEmpty()) return 0;
        if (state.cooldownTicks > 0) {
            state.cooldownTicks--;
            return 0;
        }
        Set<Long> sample = new LinkedHashSet<>();
        int settled = 0;
        for (Long key : state.pending) {
            if (key == null) continue;
            ChunkPos chunk = new ChunkPos(key);
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) continue;
            if (isMarkedNoConstructionRequired(level, key) || isComplete(level, key)) {
                if (state.pending.remove(key)) {
                    settled++;
                    state.attempts.remove(key);
                }
                continue;
            }
            sample.add(key);
            if (sample.size() >= MAX_CHUNKS_PER_TICK) break;
        }
        state.completed += settled;
        if (sample.isEmpty()) {
            state.cooldownTicks = DRAIN_INTERVAL_TICKS;
            logDrainedIfIdle(level, state, 0, settled);
            return 0;
        }
        int placed = LoadedChunkRoadBackfill.placeLoadedChunksExact(level, sample);
        int settledNow = 0;
        int abandoned = 0;
        for (Long key : sample) {
            if (key == null) continue;
            if (isMarkedNoConstructionRequired(level, key)
                    || isComplete(level, key)) {
                if (state.pending.remove(key)) {
                    settledNow++;
                    state.attempts.remove(key);
                }
                continue;
            }
            int attempts = state.attempts.merge(key, 1, Integer::sum);
            if (attempts >= MAX_DRAIN_ATTEMPTS) {
                state.pending.remove(key);
                state.attempts.remove(key);
                abandoned++;
            }
        }
        state.completed += settledNow;
        state.cooldownTicks = DRAIN_INTERVAL_TICKS;
        LOGGER.info("RoadWeaver deferred initial construction drain: pending={} sample={} placed={} settled={} abandoned={}",
                state.pending.size(), sample.size(), placed, settled + settledNow, abandoned);
        if (abandoned > 0) {
            LOGGER.warn("RoadWeaver gave up on {} deferred chunk(s) after {} attempts each; "
                            + "their plan never settled (nothing placed, no ledger entry, no terminal mark).",
                    abandoned, MAX_DRAIN_ATTEMPTS);
        }
        logDrainedIfIdle(level, state, placed, settledNow);
        return placed;
    }

    private static void logDrainedIfIdle(ServerLevel level, State state, int placed, int settled) {
        if (!state.pending.isEmpty() || state.total <= 0) return;
        LOGGER.info("RoadWeaver deferred initial construction queue is empty: settled={} lastPlaced={} lastSettled={}",
                state.completed, placed, settled);
        state.total = 0;
        state.attempts.clear();
    }

    public static void clear(ServerLevel level) {        if (level != null) {
            STATES.remove(level);
            NO_CONSTRUCTION_REQUIRED.remove(level);
        }
    }

    public static void clearAll() {
        STATES.clear();
        NO_CONSTRUCTION_REQUIRED.clear();
    }

    /**
     * T-20A 终态:把"编译计划为空、无需施工"的 chunk 永久移出 pending 并记录,
     * 供回填路径廉价跳过。只应由主线程施工路径调用(回填与 deferred tick 均
     * 强制主线程)。
     */
    public static void markNoConstructionRequired(ServerLevel level, Collection<Long> chunkKeys) {
        if (!isOverworld(level) || chunkKeys == null || chunkKeys.isEmpty()) return;
        Set<Long> marked = NO_CONSTRUCTION_REQUIRED.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet());
        State state = STATES.get(level);
        int newlyMarked = 0;
        for (Long key : chunkKeys) {
            if (key == null) continue;
            if (marked.add(key)) newlyMarked++;
            if (state != null) state.pending.remove(key);
        }
        if (newlyMarked > 0) {
            LOGGER.info("RoadWeaver marked {} chunk(s) no-construction-required (empty compiled plan; terminal).",
                    newlyMarked);
        }
    }

    /** True when the chunk was already proven to need no road construction. */
    public static boolean isMarkedNoConstructionRequired(ServerLevel level, long chunkKey) {
        if (level == null) return false;
        Set<Long> marked = NO_CONSTRUCTION_REQUIRED.get(level);
        return marked != null && marked.contains(chunkKey);
    }

    /**
     * **解除「无需施工」终态标记**（★ 2026-09-26 修 WorkBuddy R-2）。
     *
     * <h2>为什么必须有</h2>
     * {@link #markNoConstructionRequired} 的语义是「**永久**移出 pending」——
     * 但那只在「该区块确实没有路」时成立。一旦**新的道路数据落盘**并经过该区块，
     * 原来的"无计划"结论就**失效了**：
     * <pre>
     *   ① 某区块恰好在"路还没生成完"时被扫过 → 编译出空计划
     *   ② 被标成 NO_CONSTRUCTION_REQUIRED（永久）
     *   ③ 随后真正的 RoadData 落盘（自动道路，或**下一条手绘路线**）
     *   ④ {@code RoadShardStorage.addRoad} 只失效了计划缓存，
     *      **没解除标记** ⇒ 回填器在 isMarkedNoConstructionRequired 处直接跳过
     *   ⑤ 该区块在本会话内**永远不铺**，要重进世界才恢复
     * </pre>
     *
     * <p>所以道路数据落盘时必须同步解标 —— 由
     * {@code RoadShardStorage.addRoad/addRoads} 调用。</p>
     *
     * @return 实际解除的区块数
     */
    public static int unmarkNoConstructionRequired(ServerLevel level,
                                                   Collection<Long> chunkKeys) {
        if (!isOverworld(level) || chunkKeys == null || chunkKeys.isEmpty()) return 0;
        Set<Long> marked = NO_CONSTRUCTION_REQUIRED.get(level);
        if (marked == null || marked.isEmpty()) return 0;
        int removed = 0;
        for (Long key : chunkKeys) {
            if (key != null && marked.remove(key)) removed++;
        }
        if (removed > 0) {
            LOGGER.info("RoadWeaver cleared {} chunk(s) from no-construction-required "
                    + "(new road data landed; the earlier empty-plan conclusion is stale).",
                    removed);
        }
        return removed;
    }

    /** True when every road shard covering this chunk is already ledger-complete. */
    public static boolean isConstructionComplete(ServerLevel level, ChunkPos chunkPos) {
        return level != null && chunkPos != null
                && isComplete(level, ChunkPos.asLong(chunkPos.x, chunkPos.z));
    }

    private static boolean isComplete(ServerLevel level, long key) {
        ChunkPos chunk = new ChunkPos(key);
        var roads = RoadShardStorage.queryChunk(level, chunk);
        if (roads.isEmpty()) return true;
        for (var road : roads) {
            if (!RoadConstructionLedger.isComplete(level, chunk,
                    RoadShardStorage.computeFingerprint(road))) return false;
        }
        return true;
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

    private static final class State {
        private final Set<Long> pending = ConcurrentHashMap.newKeySet();
        /** 采样节流（见 {@link #DRAIN_INTERVAL_TICKS}）。 */
        private int cooldownTicks;
        /** 已采样但还没结案的区块 → 次数（见 {@link #MAX_DRAIN_ATTEMPTS}）。 */
        private final Map<Long, Integer> attempts = new ConcurrentHashMap<>();
        private int completed;
        private int total;

        private State() {
            total = 0;
        }
    }
}
