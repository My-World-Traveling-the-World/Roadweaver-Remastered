/* 文件职责：开局道路的延迟施工：等条件满足再铺，避免和预热抢线程。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 给已加载区块补施工；预生成区可以没有玩家，因此不能只靠玩家附近的回填。 */
public final class DeferredInitialRoadConstructionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    // 默认不计时，避免把诊断日志 I/O 混入常态主线程耗时。
    private static final boolean TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.deferredChunkTimings", "off"));
    /**
     * 两次采样之间至少间隔的 tick 数。单次施工最多 {@link DeferredChunkQueue#BUILD_LIMIT}
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
        // 逐键入队，不让大批规划持锁到整张路网入队完。
        for (long key : chunkKeys) state.pending.add(key);
        state.total = Math.max(state.total, state.completed + state.pending.size());
        LOGGER.info("【初始道路区块已加入延迟施工队列】 " + "RoadWeaver deferred initial construction queued: chunks={}", state.pending.size());
    }

    /** 内存队列不会写入存档，重开时由已存道路和施工台账补回未完工键。 */
    public static void recover(ServerLevel level) {
        if (!isOverworld(level)) return;
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        int recovered = 0;
        for (var road : RoadShardStorage.loadAll(level)) {
            if (road == null) continue;
            // 同一条路跨多个区块，身份与检查的区块无关，不必重复计算。
            long fingerprint = RoadShardStorage.computeFingerprint(road);
            for (long key : RoadFootprint.from(road).chunkKeys()) {
                ChunkPos chunk = new ChunkPos(key);
                if (!RoadConstructionLedger.isComplete(level, chunk, fingerprint)
                        && state.pending.add(key)) {
                    recovered++;
                }
            }
        }
        state.total = Math.max(state.total, state.completed + state.pending.size());
        if (recovered > 0) {
            LOGGER.info("【已从存档恢复延迟施工队列】 " + "RoadWeaver recovered deferred construction queue after restart: chunks={}", recovered);
        }
    }

    /** 加载事件只提示已待施工的键，避免在事件栈里读台账或落块。 */
    public static void onChunkLoaded(ServerLevel level, long chunkKey) {
        State state = STATES.get(level);
        if (state != null) state.pending.hint(chunkKey);
    }

    /** 当前已加载才尝试施工，未加载留在队列；返回本轮真正铺出道路的区块数。 */
    public static int tick(ServerLevel level) {
        if (!isOverworld(level) || !level.getServer().isSameThread()) return 0;
        State state = STATES.get(level);
        if (state == null || state.pending.isEmpty()) return 0;
        if (state.cooldownTicks > 0) {
            state.cooldownTicks--;
            return 0;
        }
        long startedAt = TIMINGS ? System.nanoTime() : 0;
        // 按选中顺序保留本次服务端线程已拿到的 FULL 区块，回填不再重查加载状态。
        var loadedForThisDrain = new LinkedHashMap<Long, LevelChunk>();
        var selection = state.pending.select(key -> {
            // 事件与施工之间可能卸载；只有当前可取的区块才查台账或施工。
            LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (loaded == null) {
                return DeferredChunkQueue.Probe.WAITING;
            }
            if (isMarkedNoConstructionRequired(level, key) || isComplete(level, key)) {
                if (state.pending.remove(key)) {
                    state.attempts.remove(key);
                    return DeferredChunkQueue.Probe.SETTLED;
                }
                return DeferredChunkQueue.Probe.WAITING;
            }
            loadedForThisDrain.put(key, loaded);
            return DeferredChunkQueue.Probe.BUILDABLE;
        });
        var sample = selection.build();
        int settled = selection.settled();
        if (TIMINGS) {
            LOGGER.info("【延迟施工候选选择次数与耗时】 " + "[DEFERRED-CHUNK-TIMING] pending={} hintSteps={} sweepSteps={} buildable={} settled={} selectUs={}",
                    state.pending.size(), selection.hintSteps(), selection.sweepSteps(), sample.size(), settled,
                    (System.nanoTime() - startedAt) / 1_000L);
        }
        state.completed += settled;
        if (sample.isEmpty()) {
            state.cooldownTicks = DRAIN_INTERVAL_TICKS;
            logDrainedIfIdle(level, state, 0, settled);
            return 0;
        }
        int placed = LoadedChunkRoadBackfill.placeSelectedLoadedChunks(level, loadedForThisDrain);
        int settledNow = 0;
        int abandoned = 0;
        for (long key : sample) {
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
            } else {
                // 未结案的已加载区块继续尽早重试，但仍与新提示共享额度。
                state.pending.hint(key);
            }
        }
        state.completed += settledNow;
        state.cooldownTicks = DRAIN_INTERVAL_TICKS;
        LOGGER.info("【本轮延迟施工处理结果】 " + "RoadWeaver deferred initial construction drain: pending={} sample={} placed={} settled={} abandoned={}",
                state.pending.size(), sample.size(), placed, settled + settledNow, abandoned);
        if (abandoned > 0) {
            LOGGER.warn("【延迟施工重试耗尽，区块未结案且没有完工台账】 " + "RoadWeaver gave up on {} deferred chunk(s) after {} attempts each; "
                            + "their plan never settled (nothing placed, no ledger entry, no terminal mark).",
                    abandoned, MAX_DRAIN_ATTEMPTS);
        }
        logDrainedIfIdle(level, state, placed, settledNow);
        return placed;
    }

    private static void logDrainedIfIdle(ServerLevel level, State state, int placed, int settled) {
        if (!state.pending.isEmpty() || state.total <= 0) return;
        LOGGER.info("【延迟施工队列已排空】 " + "RoadWeaver deferred initial construction queue is empty: settled={} lastPlaced={} lastSettled={}",
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
            if (state != null) {
                state.pending.remove(key);
                // 后续道路可能解标并重入队，不继承旧计划已用掉的重试次数。
                state.attempts.remove(key);
            }
        }
        if (newlyMarked > 0) {
            LOGGER.info("【编译计划为空，区块标记为无需施工】 " + "RoadWeaver marked {} chunk(s) no-construction-required (empty compiled plan; terminal).",
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
            LOGGER.info("【新道路使旧空计划失效，撤销无需施工标记】 " + "RoadWeaver cleared {} chunk(s) from no-construction-required "
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
        private final DeferredChunkQueue pending = new DeferredChunkQueue();
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
