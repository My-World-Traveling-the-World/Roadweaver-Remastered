/* 文件职责：手绘路线提交施工后，强制加载它经过的区块并立即施工。
 *
 * ⚠ 这是对「不做强制加载」策略的**显式例外**：只为玩家自己画的路线强制加载，
 * 且严格限定在路线经过的区块（仅扩 1 圈以覆盖路宽边界溢出）。
 * 请求在服务端发出，分 tick 消费，避免同步等待区块生成。
 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务端手绘路线的提交、预加载和施工调度。 */
public final class RouteChunkPreloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 保留原来 32 块的上限；跨 tick 也不能无限堆积区块票据。 */
    private static final int BATCH_SIZE = 32;
    /** 单块最多重试几次（与开局预热一致）。 */
    private static final int MAX_CHUNK_ATTEMPTS = 3;

    private RouteChunkPreloader() {
    }

    /**
     * **已预加载过的路线**（按 level + routeId 去重）。
     *
     * <h2>为什么必须去重</h2>
     * 手绘路线 A-B-C-D-E 会被拆成 **4 条独立连接**，每条完成后各自触发一次
     * {@code reconcileSavedRoadsAndBackfill} ⇒ 若不去重，同一条路线会被
     * 预加载 **4 遍**（每遍都拉整条路线的区块），白白浪费几分钟。
     *
     * <p>用 {@code routeId} 而不是连接做键 —— 因为预加载本来就是**整条路线级别**
     * 的操作（一次把所有区块拉完），跟"是哪条连接完成的"无关。</p>
     *
     * <p>⚠️ 用 {@code WeakHashMap<ServerLevel, ...>} 与登记表同风格：
     * level 被 GC 后分区自动消失，不会泄漏。</p>
     */
    private static final java.util.Map<ServerLevel, java.util.Set<java.util.UUID>> PRELOADED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.util.Set<java.util.UUID> preloaded(ServerLevel level) {
        return PRELOADED.computeIfAbsent(level,
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    // ═══════════════════ tick 驱动的预加载兜底（★ 0.8.400）═══════════════════
    //
    // 【要解决什么】
    // 0.8.391 起，预加载的**唯一自动触发点**是 RoadGenerationService 里
    // `if (attempt.outcome().road() != null) { ... preloadRouteChunks(...) }`
    // 那段。而它埋在一条 **4 跳异步**链路里：
    //
    //   ① worker 生成完成
    //   ② 主线程 executeOnMainThread 里取 RouteBuildRegistry.routeFor(conn)
    //   ③ RoadSnapService.snapAroundConnectionAsync 的 future
    //   ④ future.whenComplete → executeOnMainThread(level, epoch, ...)
    //
    // ④ 的 wrapper 会在 `ThreadPoolManager.isEpoch(epoch)` 为假时**静默丢弃**
    // 整个动作 —— 不打任何日志。真机取证（2026-09-26）：
    //
    //   21:06:18 手绘路线已提交施工 route=9a711f3c 分段=4 新增连接=4
    //   21:06:23~27 applied player-route overrides ×4（路确实建好了）
    //   但**全日志 0 条「手绘路线预加载开始」**
    //   顶上来的只有 loaded-chunk construction backfill: processed=8
    //
    // 也就是说：路建好了，但**区块强制加载一次都没跑**，玩家走过去仍是空地，
    // 只能靠被动回填每次 8 块地慢慢补。
    //
    // 【为什么改成 tick 驱动】
    // 这是同一处第三次翻车（0.8.391 过早预加载 / 0.8.392 上限不可达 / 现在触发链断裂），
    // 根因始终是同一个：**让一条脆弱的异步链承担一个「必须完成」的责任**。
    //
    // 用户口径本来就是「不设超时、必须全部加载完成」。所以这里改成
    // **服务端 tick 驱动的待办队列**：提交时登记意图，之后每个 tick 检查
    // 「该路线的连接是否都已结算」，结算完就强制执行预加载。
    // 不碰 future、不碰 epoch —— 那 4 跳无论哪里断了，兜底都照样生效。
    //
    // 1 tick = 50ms（20TPS），所以结算后的触发延迟 ≤0.05 秒，开销可忽略
    // （无待办时是 `map == null || isEmpty()` 一次判空，零成本）。

    /** 单条待预加载路线的重试状态。 */
    private static final class PendingState {
        /** 已尝试强制预加载的次数（用于封顶，避免病态情况下无限阻塞 tick）。 */
        int attempts;
        /** 下一次允许尝试的游戏刻（冷却，避免每 tick 都 managedBlock）。 */
        long nextAttemptAt;
        /** 入队时的游戏刻（用于「登记迟迟不出现」的宽容期）。 */
        final long enqueuedAt;

        PendingState(long now) {
            this.enqueuedAt = now;
            this.nextAttemptAt = now;
        }
    }

    /** 待预加载路线：level → (routeId → 状态)。 */
    private static final java.util.Map<ServerLevel, java.util.Map<java.util.UUID, PendingState>> PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 连接登记迟迟不出现的宽容期（10 秒）——超过就放弃，避免永久占位。 */
    private static final long REGISTRATION_GRACE_TICKS = 200L;
    /** 每次强制预加载失败后的冷却（5 秒），避免每 tick 都 managedBlock。 */
    private static final long RETRY_COOLDOWN_TICKS = 100L;
    /** 最多尝试几次强制预加载；用尽后放弃并告警（不影响已提交的连接）。 */
    private static final int MAX_PRELOAD_ATTEMPTS = 3;

    /**
     * **检测间隔：10 tick = 0.5 秒**（★ 用户 2026-09-26 要求）。
     *
     * <p>1 tick = 50ms（20 TPS），故 0.5 秒 = 10 tick。</p>
     *
     * <h2>为什么不是每 tick</h2>
     * 用户明确要求「改成 0.5 秒，不要给太多性能压力」。
     * 结算检测本身要走
     * {@code RouteBuildProgress.snapshot} → 遍历全部连接 + 登记表查找，
     * 在连接数上百时并非零成本。10 tick 一次把这份开销直接降到 1/10，
     * 而代价只是「结算后最坏 0.5 秒才触发加载」—— 对一条要铺几秒到几分
     * 钟的路线完全无感。</p>
     *
     * <p>无待办时仍然走 {@code map == null || isEmpty()} 的零成本快路径，
     * 所以空闲期（绝大多数时间）没有任何额外开销。</p>
     */
    private static final long DRAIN_INTERVAL_TICKS = 10L;

    /** 每个 level 下一次允许检测的游戏刻（节流用）。 */
    private static final java.util.Map<ServerLevel, Long> NEXT_DRAIN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.util.Map<java.util.UUID, PendingState> pending(ServerLevel level) {
        return PENDING.computeIfAbsent(level,
                k -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /**
     * **登记一条「提交后待强制加载」的路线**（★ 0.8.400）。
     *
     * <p>由 {@code RouteBuildService.submit()} 在提交成功后调用。
     * 之所以不在这里直接加载：提交时 RoadData 还没生成（连接才刚写成
     * PLANNED），此时强加载会拿到空计划并把区块误标成「终态无需施工」
     * （这正是 0.8.391 的原始事故）。所以只登记意图，等 tick 观察到
     * 「连接全部结算」再执行。</p>
     *
     * <p>幂等：同一 routeId 重复登记不会重置已累计的尝试次数与冷却。</p>
     */
    public static void enqueuePending(ServerLevel level, java.util.UUID routeId) {
        if (level == null || routeId == null || !level.getServer().isRunning()) {
            return;
        }
        pending(level).computeIfAbsent(routeId, k -> new PendingState(level.getGameTime()));
    }

    /** 当前待办数量（供诊断 / 测试）。 */
    public static int pendingCount(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        java.util.Map<java.util.UUID, PendingState> map = PENDING.get(level);
        return map == null ? 0 : map.size();
    }

    /** 清掉某条路线的待办（玩家取消 / 重新提交时用）。 */
    public static void forgetPending(ServerLevel level, java.util.UUID routeId) {
        if (level == null || routeId == null) {
            return;
        }
        java.util.Map<java.util.UUID, PendingState> map = PENDING.get(level);
        if (map != null) {
            map.remove(routeId);
        }
        // 删除、重提都撤销旧任务，等同 UUID 的新任务会另建状态。
        ActivePreload active = ACTIVE.get(level);
        if (active != null && routeId.equals(active.route().id())) {
            finish(level, active, true, true, false);
        }
        RoutePreloadStatus.forget(level, routeId);
    }

    /**
     * 每 tick 推进至多一条正在加载的路线。仅待办的连接结算检测沿用 10 tick 节流，
     * 因为已发请求的票据、取消和施工不该跟着低频状态扫描等。
     */
    public static int drainPending(ServerLevel level, boolean dimensionPaused) {
        if (level == null) return 0;
        if (dimensionPaused) {
            pauseActive(level);
            return 0;
        }
        ActivePreload active = ACTIVE.get(level);
        if (active != null) {
            // UUID 在重新提交时可复用，因此还要核对实际登记的路线对象。
            boolean obsolete = !active.route().equals(RouteBuildRegistry.getRoute(level, active.route().id()));
            if (obsolete || RoutePreloadStatus.isStopRequested(level, active.route().id())) {
                finish(level, active, true, obsolete, true);
            } else {
                advance(level, active);
            }
            return 0;
        }

        Map<UUID, PendingState> map = PENDING.get(level);
        if (map == null || map.isEmpty()) return 0;
        long now = level.getGameTime();
        Long next = NEXT_DRAIN.get(level);
        if (next != null && now < next) return 0;
        NEXT_DRAIN.put(level, now + DRAIN_INTERVAL_TICKS);

        for (var entry : map.entrySet()) {
            UUID routeId = entry.getKey();
            PendingState state = entry.getValue();
            if (preloaded(level).contains(routeId)) {
                map.remove(routeId, state);
                continue;
            }
            PlayerRoute route = RouteBuildRegistry.getRoute(level, routeId);
            if (route == null) {
                map.remove(routeId, state);
                continue;
            }
            if (now < state.nextAttemptAt) continue;
            var progress = RouteBuildProgress.snapshot(level, routeId);
            if (progress.total() == 0) {
                if (now - state.enqueuedAt > REGISTRATION_GRACE_TICKS) {
                    LOGGER.warn("RouteWeaver 预加载待办超出登记宽容期 route={}", routeId);
                    map.remove(routeId, state);
                }
                continue;
            }
            // 连接仍在生成时，区块计划可能为空；提前回填会把将来的道路当成无需施工。
            if (!progress.isSettled()) continue;

            state.attempts++;
            try {
                Set<Long> chunks = collectRouteChunks(level, route);
                if (chunks.isEmpty()) {
                    map.remove(routeId, state);
                    continue;
                }
                ChunkPos origin = originChunk(level, route);
                List<Long> ordered = new ArrayList<>(chunks);
                ordered.sort(Comparator.comparingLong(key -> distanceSquared(origin, key)));
                ActivePreload created = new ActivePreload(route, state,
                        new RoutePreloadWork<>(ordered, BATCH_SIZE, MAX_CHUNK_ATTEMPTS),
                        countNotInMemory(level, ordered), System.nanoTime());
                ACTIVE.put(level, created);
                RoutePreloadStatus.begin(level, routeId, ordered.size(), created.notInMemory());
                if (RoutePreloadStatus.isStopRequested(level, routeId)) {
                    finish(level, created, true, false, true);
                } else {
                    advance(level, created);
                }
                return 1;
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("RouteWeaver 无法开始手绘预加载 route={}", routeId, failure);
                retryRoute(level, routeId, state);
                return 0;
            }
        }
        return 0;
    }

    private record ActivePreload(PlayerRoute route, PendingState attempt, RoutePreloadWork<LevelChunk> work,
                                 int notInMemory, long startedAt) {}

    private static final Map<ServerLevel, ActivePreload> ACTIVE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public record ChunkRequest(CompletableFuture<?> future, Runnable release) {}

    @FunctionalInterface
    public interface ChunkRequester {
        ChunkRequest request(ServerLevel level, ChunkPos pos);
    }

    private static volatile ChunkRequester chunkRequester = (level, pos) -> {
        throw new IllegalStateException("Nonblocking hand-drawn chunk requester is not installed");
    };

    /** 平台提供非阻塞请求与撤票，普通回填的入口仍然只消费已经加载的区块。 */
    public static void installChunkRequester(ChunkRequester requester) {
        chunkRequester = java.util.Objects.requireNonNull(requester);
    }

    private static void pauseActive(ServerLevel level) {
        ActivePreload active = ACTIVE.get(level);
        if (active == null) return;
        // 主世界无人时仍尊重维度暂停，撤票而不丢掉已持久化的完工进度。
        if (RoutePreloadStatus.isStopRequested(level, active.route().id())) {
            finish(level, active, true, false, true);
        } else {
            active.work().pause();
        }
    }

    private static void advance(ServerLevel level, ActivePreload active) {
        RoutePreloadWork<LevelChunk> work = active.work();
        int processedBefore = work.processed();
        int failuresBefore = work.failures();
        UUID id = active.route().id();
        try {
            work.tick(new WorldAccess(level, id));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("RouteWeaver 手绘路线施工异常，重试时由台账跳过已完工区块 route={}", id, failure);
            finish(level, active, false, false, false);
            return;
        }
        if (work.done()) {
            finish(level, active, false, false, work.failures() == 0);
        } else if (processedBefore != work.processed() || failuresBefore != work.failures()) {
            RoutePreloadStatus.update(level, id, work.total(), work.processed(), work.failures(),
                    active.notInMemory(), "加载并铺设中", failedPositions(work));
        }
    }

    private record WorldAccess(ServerLevel level, UUID routeId) implements RoutePreloadWork.Access<LevelChunk> {
        @Override
        public RoutePreloadWork.Request request(long key) {
            var request = chunkRequester.request(level, new ChunkPos(key));
            return new RoutePreloadWork.Request(request.future(), request.release());
        }

        @Override
        public LevelChunk loaded(long key) {
            return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        }

        @Override
        public Set<Long> place(Map<Long, LevelChunk> ready) {
            // 铺路仍在服务端 tick；只把结案信息还给进度，不以 Future 完成代替施工。
            return LoadedChunkRoadBackfill.placePreloadedChunks(level, ready);
        }

        @Override
        public RoutePreloadWork.Placement placeBatch(Map<Long, LevelChunk> ready) {
            var attempted = new java.util.HashSet<Long>();
            Set<Long> settled = LoadedChunkRoadBackfill.placePreloadedChunks(level, ready, attempted);
            return new RoutePreloadWork.Placement(settled, attempted);
        }

        @Override
        public void failed(long key, RuntimeException failure, boolean finalAttempt) {
            if (finalAttempt) {
                LOGGER.warn("RouteWeaver 手绘预加载区块重试用尽 route={} chunk={}", routeId, new ChunkPos(key), failure);
            } else {
                LOGGER.debug("RouteWeaver 手绘预加载区块稍后重试 route={} chunk={}", routeId, new ChunkPos(key), failure);
            }
        }
    }

    private static void finish(ServerLevel level, ActivePreload active, boolean stopped,
                               boolean obsolete, boolean success) {
        UUID id = active.route().id();
        if (!ACTIVE.remove(level, active)) return;
        RoutePreloadWork<LevelChunk> work = active.work();
        work.stop();
        Map<UUID, PendingState> map = PENDING.get(level);
        if (obsolete) {
            if (map != null) map.remove(id, active.attempt());
            RoutePreloadStatus.forget(level, id);
            return;
        }
        int failures = stopped ? work.failures() : success ? 0
                : Math.max(work.failures(), work.total() - work.processed());
        RoutePreloadStatus.finish(level, id, work.total(), work.processed(), failures,
                active.notInMemory(), failedPositions(work), stopped);
        LOGGER.info("RouteWeaver 手绘预加载结束 route={} chunks={}/{} failed={} stopped={} elapsedMs={}",
                id, work.processed(), work.total(), failures, stopped, (System.nanoTime() - active.startedAt()) / 1_000_000L);
        if (stopped || success) {
            if (stopped) RoutePreloadStatus.clearStop(level, id);
            // 取消也占终态，直到用户明确点重试或重新提交，旧连接的晚回调不能重启任务。
            preloaded(level).add(id);
            if (map != null) map.remove(id, active.attempt());
        } else {
            retryRoute(level, id, active.attempt());
        }
    }

    private static void retryRoute(ServerLevel level, UUID id, PendingState state) {
        if (state.attempts < MAX_PRELOAD_ATTEMPTS) {
            state.nextAttemptAt = level.getGameTime() + RETRY_COOLDOWN_TICKS;
        } else {
            Map<UUID, PendingState> map = PENDING.get(level);
            if (map != null) map.remove(id, state);
            LOGGER.warn("RouteWeaver 手绘路线预加载用尽重试，未处理区块转交被动回填 route={}", id);
        }
    }

    private static List<ChunkPos> failedPositions(RoutePreloadWork<?> work) {
        return work.failureKeys().stream().map(ChunkPos::new).toList();
    }

    /**
     * 统计其中有多少区块**当前不在内存里**（★ UX-3 警告依据）。
     *
     * <p>⚠️ <b>2026-09-26 更正（WorkBuddy R-14）</b>：原先本方法名与文案都叫
     * "unexplored（未探索）"，但判据是 {@code getChunkNow == null} ——
     * 那**同时包含**"确实没生成过"与"已生成但当前没加载"两种。
     * 后者加载很快，却被算进"要生成地形，会很慢"的警告里 ⇒
     * <b>警告数字虚高、几乎恒触发</b>，玩家会逐渐忽略它。</p>
     *
     * <p>代码层面无法廉价区分这两者（项目里没有"区块是否已在磁盘"的查询接口）。
     * 所以这里**保留计数**（它仍是"需要 I/O 的区块数"这个有意义的上界），
     * 但把 UI 文案改为**不谎称"未探索"** —— 改成"需要从磁盘读取或生成"，
     * 这样数字准确、警告不误导。</p>
     */
    private static int countNotInMemory(ServerLevel level, List<Long> ordered) {
        int n = 0;
        for (long key : ordered) {
            try {
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
                    n++;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 计数失败不影响主流程
            }
        }
        return n;
    }

    /**
     * 收集这条路线经过的区块。
     *
     * <p>⚠️ <b>2026-09-25 更正措辞（Codex 指出与实现不符）</b>：
     * 原注释写「严格：不扩展邻居」，但方法最后会调 {@link #expandOneRing}
     * 把每个路线区块扩到切比雪夫距离 1 —— **确实会碰到路线未直接踩到的相邻区块**。
     * 那是为覆盖「道路宽度向两侧溢出约 2~3 格」的边界，但措辞不该说成"不扩展"。</p>
     *
     * <p>与用户口径的关系：用户要求「不要多拉区块，道路没有经过的不要拉」——
     * 这里**没有**做半径正方形扫描（不像 {@code placeLoadedSpawnChunks} 扫 r²），
     * 只对路线区块扩 1 圈。若用户认为连这 1 圈也算"多拉"，可改回严格模式
     * （会牺牲跨区块边界处的宽度完整）。</p>
     *
     * <h2>为什么用「控制点 + 平滑路径」两层</h2>
     * <ul>
     *   <li><b>平滑路径</b>是施工实际走的线 —— 它决定最终铺在哪，
     *       所以必须包含（否则路会铺到没加载的区块上）。</li>
     *   <li><b>控制点</b>是玩家亲手放的点。平滑会穿过每个控制点，
     *       但为稳妥起见一并加入（代价极小，且避免平滑降采样时漏点）。</li>
     * </ul>
     */
    private static Set<Long> collectRouteChunks(ServerLevel level, PlayerRoute route) {
        Set<Long> chunks = new LinkedHashSet<>();
        // ① 控制点
        for (RoutePoint p : route.points()) {
            if (p != null && p.pos() != null) {
                addChunk(chunks, p.pos().getX(), p.pos().getZ());
            }
        }
        // ② 平滑路径（施工实际路径）—— 与注入用同一条平滑（yProvider 传 null 只为拿 XZ）
        try {
            List<net.minecraft.core.BlockPos> smoothed = RouteSmoother.smooth(route, null);
            for (net.minecraft.core.BlockPos pos : smoothed) {
                if (pos != null) {
                    addChunk(chunks, pos.getX(), pos.getZ());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 平滑失败时至少已有控制点覆盖，不致命
            LOGGER.warn("手绘路线预加载：平滑路径计算失败，只用控制点确定区块 route={}", route.id(), e);
        }
        // ③ 道路宽度会向两侧各溢出约 2~3 格 ⇒ 把路线区块的**紧邻 1 圈**也纳入。
        //
        //    这不是"半径扫描"：只是覆盖宽度溢出，避免「路在区块边界缺一条边」。
        //    代价：单块路线最多变成 9 块候选（3x3）。
        return expandOneRing(chunks);
    }

    /** 把区块集合向外扩一圈（切比雪夫距离 1）。用于覆盖道路宽度的边界溢出。 */
    private static Set<Long> expandOneRing(Set<Long> chunks) {
        Set<Long> out = new LinkedHashSet<>(chunks.size() * 2);
        for (long key : chunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    out.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        return out;
    }

    private static void addChunk(Set<Long> chunks, int blockX, int blockZ) {
        chunks.add(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
    }

    /** 排序基准：优先用玩家所在区块（先建玩家看得见的），拿不到就用路线首点。 */
    private static ChunkPos originChunk(ServerLevel level, PlayerRoute route) {
        try {
            List<net.minecraft.server.level.ServerPlayer> players = level.players();
            if (players != null && !players.isEmpty()) {
                return players.get(0).chunkPosition();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 取不到玩家就用路线首点
        }
        if (route.pointCount() > 0) {
            var p = route.point(0).pos();
            return new ChunkPos(p.getX() >> 4, p.getZ() >> 4);
        }
        return new ChunkPos(0, 0);
    }

    private static long distanceSquared(ChunkPos origin, long key) {
        long dx = (long) ChunkPos.getX(key) - origin.x;
        long dz = (long) ChunkPos.getZ(key) - origin.z;
        return dx * dx + dz * dz;
    }

    /** 世界退出 / 重置时清掉去重标记（由 {@code RouteBuildService.clearPreloadState} 转发）。 */
    public static void clearRuntime(ServerLevel level) {
        if (level == null) {
            return;
        }
        // 客户端断开也会调用清理；票据只能在其所属服务端线程撤销。
        var server = level.getServer();
        if (!server.isSameThread()) {
            if (server.isRunning()) server.execute(() -> clearRuntime(level));
            return;
        }
        ActivePreload active = ACTIVE.remove(level);
        if (active != null) active.work().stop();
        RoutePreloadStatus.onWorldLeave(level);
        PRELOADED.remove(level);
        // ★ 0.8.400：待办队列一并清掉 —— 否则重进世界后会拿旧 routeId
        //   反复尝试加载（路线登记已被 RouteBuildRegistry.onWorldLeave 清空）。
        PENDING.remove(level);
        NEXT_DRAIN.remove(level);
    }

    // ★ 2026-09-26 修 I-10/W-9：**删除了「等待轮数」整套机制**
    //   （`WAITED` 表、`waited()`、`forgetWait()`、`forgetWaitFor()`）。
    //
    //   它是 0.8.392 为「有界等待」引入的；但 0.8.395 改用
    //   decideReadiness（连接状态机）之后，**没有任何代码再读它** ——
    //   纯死代码。而且它还会误导后来者以为存在一套"等待预算"机制
    //   （WorkBuddy 指出的"下一个 R-10"隐患）。
    //
    //   `RouteBuildService.submit` 里原先对这一项的 `forgetWaitFor` 调用
    //   也已同步删除。

    /**
     * 忘掉某条路线的预加载标记（★ 2026-09-25 修 R-2）。     *
     * <h2>为什么需要</h2>
     * {@code RouteEditorSession.reopenForEdit()} 把路线退回 DRAFT 时
     * **UUID 不变**。玩家「施工 → 重新编辑 → 改路线 → 再提交」时，
     * 去重标记还留着旧值 ⇒ 新一轮的区块被直接跳过，**新改的区域不会被强制加载**。
     *
     * <p>修法：{@code RouteBuildService.submit()} 每次提交都调本方法 ——
     * 让下一轮预加载重新走一遍（预加载自身还有"就绪判定"，不会重复劳动）。</p>
     */
    public static void forgetPreloaded(ServerLevel level, java.util.UUID routeId) {
        if (level == null || routeId == null) {
            return;
        }
        PRELOADED.computeIfPresent(level, (k, set) -> {
            set.remove(routeId);
            return set.isEmpty() ? null : set;   // 空了就把分区也去掉
        });
    }

    /**
     * 预加载就绪判定的结果（★ 2026-09-26 引入，供测试直接验证）。
     *
     * <p>抽成**纯函数 + 枚举**是为了能被离线回归钉住 —— R-1 这类
     * "时序假设错误"的缺陷在真机上极难复现（要恰好让某条连接失败），
     * 但它本质上是**一个可以用几个整数验证的判断**。</p>
     */
    public enum Readiness {
        /** 全部区块已有计划 ⇒ 可以预加载了。 */
        READY,
        /** 还有连接未结算 ⇒ 继续等，下一轮触发再判。 */
        WAIT,
        /** 连接已全部结算但仍有区块无计划（有连接失败）⇒ 放行，只铺有计划的。 */
        PROCEED_PARTIAL
    }

    /**
     * **就绪判定的纯逻辑**（★ 可离线测试，见 {@code RoutePreloadTimingTest}）。
     *
     * <h2>为什么必须是纯函数</h2>
     * 原先的判据是「等待轮数 &lt; {@code route.segmentCount()}」——
     * 它隐含假设「每条连接**成功或失败**都会触发一次预加载」。
     * 而实际触发点只在连接**成功**回调里（{@code RoadGenerationService}
     * 的 {@code road != null} 分支），失败连接**不触发**。
     * 于是 N 段路线失败 1 段 ⇒ 最多触发 N−1 次 &lt; N ⇒ 上限永不可达
     * ⇒ 整条路线永不预加载（0.8.391 的核心承诺在有失败时完全失效）。
     *
     * <p>根因是「用**触发次数**推断**连接状态**」这个错误映射。
     * 现在直接用连接状态机：</p>
     * <pre>
     *   allChunksHavePlan == true                 → READY        （不用看轮数）
     *   allChunksHavePlan == false, active &gt; 0    → WAIT         （真的还在生成）
     *   allChunksHavePlan == false, active == 0   → PROCEED_PARTIAL（已结算，有人失败）
     * </pre>
     *
     * <p><b>关键性质</b>：{@code WAIT} 的判定**完全不含"次数"**，
     * 所以"失败连接少触发一次"不会让它永真 —— 只要那些连接结算完，
     * {@code active} 归零，下一次触发必然放行。而"下一次触发"是必然发生的：
     * 还有 {@code active} 就说明还有连接在跑，它终会有结论。</p>
     *
     * @param allChunksHavePlan 该路线的区块是否都编译出了非空计划
     * @param activeConnections 仍未结算的连接数（PLANNED + GENERATING）
     */
    public static Readiness decideReadiness(boolean allChunksHavePlan, int activeConnections) {
        if (allChunksHavePlan) {
            return Readiness.READY;
        }
        return activeConnections > 0 ? Readiness.WAIT : Readiness.PROCEED_PARTIAL;
    }

}
