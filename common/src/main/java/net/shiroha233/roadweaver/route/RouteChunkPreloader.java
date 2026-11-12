package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手绘路线提交施工后，**强制加载该路线经过的区块并立即施工**。
 *
 * <h2>用户需求（2026-09-25）</h2>
 * <blockquote>
 * 「提交施工时立即拉；不设置超时；必须全部加载完成；但是不要多拉区块，
 *   道路没有经过的不要拉。」
 * </blockquote>
 *
 * <h2>要解决什么问题</h2>
 * 原先手绘路线提交后，施工完全依赖 {@code LoadedChunkRoadBackfill} 的
 * **被动回填** —— 它只捡「当前恰好已加载」的区块，而且每次上限
 * {@code MAX_BACKFILL_CHUNKS_PER_INVOCATION = 8}。实测日志：
 * <pre>
 *   loaded-chunk construction backfill: candidates=361 loaded=289 processed=8 placed=8
 * </pre>
 * 361 个候选区块、每次只吃 8 个 ⇒ 要几十次回填才清完。
 * 玩家看到的现象是「地图上已标蓝（连接 COMPLETED），但世界里走过去仍是空地」。
 *
 * <h2>与 {@code InitialRoadConstructionService} 的关系</h2>
 * 那个类已经在做「强制加载到 FULL 再铺」这件事，但**只用于开局预热**。
 * 本类把手绘路线也接上同一条路径 —— 逻辑刻意保持一致（分批 / 重试 / 验证），
 * 区别只有一个：**候选区块来自玩家画的路线**，而不是全局规划结果。
 *
 * <h2>⚠️ 为什么这是一次「策略变更」</h2>
 * {@code LoadedChunkRoadBackfill.placeLoadedRoad} 的注释明确写着：
 * <blockquote>
 *   This is deliberately exact: it never expands the set and never asks the
 *   chunk source to load anything, so <b>manual routes do not defeat the
 *   no-force-load policy</b>.
 * </blockquote>
 * 也就是说「手绘路线不强加载区块」**原本是刻意的设计**。这里按用户 2026-09-25
 * 的要求**显式改变了该策略**：只为**玩家自己画的路线**强制加载，
 * 且**严格限定在路线经过的区块**内（不做半径正方形扫描；仅对路线区块扩 1 圈，
 * 见 {@link #collectRouteChunks} 的说明 —— 那是为覆盖道路宽度的边界溢出）。
 *
 * <h2>线程与「会不会卡住服务器」</h2>
 * 必须在**服务端线程**上调用（{@code server.managedBlock} 等）。
 *
 * <p>⚠️ <b>2026-09-25 更正（Codex 用 1.21.1 字节码核对）</b>：
 * 本类注释原先写「{@code managedBlock} 会让服务端等待但 <b>ticks 继续跑</b>」——
 * 这个说法<b>不准确</b>。{@code managedBlock} 的循环只做两件事：
 * 处理事件循环/区块任务队列（{@code pollTask()} + {@code waitForTasks()}），
 * <b>不会进入 {@code MinecraftServer.tickServer()}</b>。
 * 也就是说：等待期间**普通游戏 tick（实体、红石、计分板等）是暂停的**，
 * 只有区块相关任务在推进。</p>
 *
 * <p>实际影响：本等待发生在**服务端线程**上，所以长路线/未探索区块会表现为
 * <b>服务器停顿时长</b>（而不是"ticks 照跑、只是生成变忙"）。
 * 用户已知情并接受（「等待 10 来秒都可以接受」、要求「不设超时、必须全部完成」）。</p>
 *
 * <p>缓解事实：用户指出手绘路线画在**已生成过**的区块上 ⇒ 绝大多数是磁盘二次加载，
 * 停顿远小于真世界生成。但**若路线含未探索区域**，停顿会明显变长。</p>
 */
public final class RouteChunkPreloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 每批至少这么多区块（与开局预热保持一致）。 */
    /**
     * 每批处理的区块数（★ 用户 2026-09-25 明确要求 **32**）。
     *
     * <h2>为什么可以这么大</h2>
     * 用户指出关键点：**手绘路线画在「已经生成过」的区块上** ——
     * 玩家是看着地图上已有的地形画的线，所以这些区块早就在 region 文件里。
     * 因此这里的 {@code getChunkFuture(FULL, true)} 绝大多数是
     * **从磁盘二次加载**，而不是真跑世界生成 ⇒ 单批开销远小于"生成新地形"，
     * 32 一批不会造成开局预热那种级别的卡顿。
     *
     * <p>（仍保留真正的世界生成路径：万一路线端点落在未探索区域，
     * {@code forceLoad=true} 会正常生成它，不会漏。）</p>
     */
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
        if (level == null || routeId == null) {
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
    }

    /**
     * **每 tick 排空待办队列**（★ 0.8.400 的核心兜底）。
     *
     * <p>必须在服务端线程调用（{@code preloadAndConstruct} 会 {@code managedBlock}）。</p>
     *
     * <p>语义：对每条待办路线</p>
     * <ol>
     *   <li>路线对象没了 ⇒ 丢弃（无意义）</li>
     *   <li>连接还没登记（{@code total == 0}）⇒ 等，超过宽容期就丢弃</li>
     *   <li>连接**尚未全部结算**（还有 PLANNED / GENERATING）⇒ 等下一个 tick</li>
     *   <li>已全部结算 ⇒ **强制执行预加载**；成功（进入 PRELOADED 去重集）
     *       就出队，否则按冷却重试，用尽次数后告警出队</li>
     * </ol>
     *
     * <p>「成功」以 {@link #preloaded} 去重集为准 —— 那是
     * {@code preloadAndConstruct} 在**全部区块都铺成功**时才保留的标记；
     * 有失败块时它会主动释放该标记以便重试，两者语义天然一致。</p>
     *
     * @return 本次真正执行了强制预加载的路线数
     */
    public static int drainPending(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        java.util.Map<java.util.UUID, PendingState> map = PENDING.get(level);
        // ★ 快路径：没有待办时零开销（绝大多数 tick 走这里）
        if (map == null || map.isEmpty()) {
            return 0;
        }

        long now = level.getGameTime();

        // ★ 0.5 秒节流（用户要求：不要给太多性能压力）。
        //   注意顺序：**先判空再判节流** —— 没有待办时不消耗节流配额，
        //   这样"提交后立刻检测"不会被上一轮的空闲节流挡住。
        Long next = NEXT_DRAIN.get(level);
        if (next != null && now < next) {
            return 0;
        }
        NEXT_DRAIN.put(level, now + DRAIN_INTERVAL_TICKS);

        int ran = 0;

        for (java.util.Map.Entry<java.util.UUID, PendingState> entry : map.entrySet()) {
            java.util.UUID routeId = entry.getKey();
            PendingState state = entry.getValue();
            if (routeId == null || state == null) {
                map.remove(routeId);
                continue;
            }
            // 已经铺完了（去重集里有）⇒ 出队
            if (preloaded(level).contains(routeId)) {
                map.remove(routeId);
                continue;
            }
            if (now < state.nextAttemptAt) {
                continue;   // 冷却中
            }

            PlayerRoute route = net.shiroha233.roadweaver.route.RouteBuildRegistry
                    .getRoute(level, routeId);
            if (route == null) {
                // 路线对象丢了（世界重进且未重建登记）——留着也无法加载，丢弃
                LOGGER.debug("RouteWeaver 预加载待办丢弃（路线对象不存在）route={}", routeId);
                map.remove(routeId);
                continue;
            }

            net.shiroha233.roadweaver.route.RouteBuildProgress.Snapshot snap =
                    net.shiroha233.roadweaver.route.RouteBuildProgress.snapshot(level, routeId);
            if (snap.total() == 0) {
                // 连接登记还没出现 —— 提交后通常立刻就有；迟迟没有就放弃
                if (now - state.enqueuedAt > REGISTRATION_GRACE_TICKS) {
                    LOGGER.warn("RouteWeaver 预加载待办丢弃（连接登记在 {} tick 内未出现）route={}",
                            REGISTRATION_GRACE_TICKS, routeId);
                    map.remove(routeId);
                }
                continue;
            }
            if (!snap.isSettled()) {
                // 还在生成/排队 —— 等它结算。这是正常的等待路径，不消耗尝试次数。
                continue;
            }

            // ── 已全部结算 ⇒ 强制执行 ──
            state.attempts++;
            ran++;
            try {
                LOGGER.info("RouteWeaver 预加载兜底触发（连接已全部结算）route={} chunks 加载开始 attempts={}",
                        routeId, state.attempts);
                preloadAndConstruct(level, route);
            } catch (Throwable t) {
                // 失败不能让 tick 崩掉；交给下面的重试逻辑
                LOGGER.warn("RouteWeaver 预加载兜底执行异常 route={}", routeId, t);
            }

            if (preloaded(level).contains(routeId)) {
                // 全部区块铺设成功 ⇒ 出队
                LOGGER.info("RouteWeaver 预加载兜底完成 route={} attempts={}", routeId, state.attempts);
                map.remove(routeId);
            } else if (state.attempts >= MAX_PRELOAD_ATTEMPTS) {
                LOGGER.warn("RouteWeaver 预加载兜底放弃（尝试 {} 次仍有区块未加载）route={} "
                                + "—— 剩余区块转交被动回填",
                        state.attempts, routeId);
                map.remove(routeId);
            } else {
                state.nextAttemptAt = now + RETRY_COOLDOWN_TICKS;
            }
        }

        return ran;
    }

    /**
     * 强制加载并施工这条手绘路线经过的**全部**区块。
     *
     * <p>阻塞式：本方法返回时，该路线的区块都已被铺过（或已判定无需施工）。
     * 用户明确要求「不设置超时、必须全部加载完成」，所以这里**不设超时**，
     * 只在单块重试用尽后**记录并跳过**（不抛异常中断整条路线 ——
     * 一块失败不该让玩家画了一小时的路线整体作废）。</p>
     *
     * @param level 服务端主世界
     * @param route 已提交（READY）的手绘路线
     * @return 实际处理的区块数
     */
    public static int preloadAndConstruct(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null) {
            return 0;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return 0;
        }
        if (!server.isSameThread()) {
            // 本方法会 managedBlock，必须在服务端线程调用
            LOGGER.warn("RouteChunkPreloader 被非服务端线程调用，已跳过 route={}", route.id());
            return 0;
        }

        // ── ⓪ 路线级屏障（★ 2026-09-25 修 R-1，**关键正确性修复**）──
        //
        // ❌ 原实现（有严重 bug）：直接拿**整条路线**的区块去 placeLoadedChunks。
        //    但本方法是被「**任意一条**连接完成」触发的 —— 第一条连接完成时，
        //    后面几条连接的 RoadData **还没落盘**。那些区块查到的是**空计划**，
        //    于是被 LoadedChunkRoadBackfill 当成"无需施工"，
        //    进而调 DeferredInitialRoadConstructionService.markNoConstructionRequired，
        //    其注释写明是「**永久**移出 pending」⇒ 这些区块**永久跳过施工**，
        //    且因 routeId 去重不会重试 ⇒ 玩家看到"路断了一截"。
        //
        // ✅ 修法：**等到这条路线所有区块的计划都非空**（= 所有连接的 RoadData
        //    都已落盘）再预加载。没就绪就**直接返回不做事**，由**下一条连接完成**
        //    时再进本方法 —— 最后一条连接完成时必然全部就绪。
        //
        //    这不需要额外的计数/注册表：`RoadWorldgenPlanCache.get` 的"空计划"
        //    本来就能反映"这条连接还没落盘"。判据自洽且无新增状态。
        Set<Long> chunks = collectRouteChunks(level, route);
        if (chunks.isEmpty()) {
            return 0;
        }
        if (!allChunksHavePlan(level, chunks)) {
            // ★ 2026-09-26 修 Codex/WorkBuddy R-1：**改判据，不再用轮数上限**。
            //
            // ❌ 原实现用 `waits < route.segmentCount()` 作上限，假设
            //    「每条连接成功或失败都会触发一次本方法」——**这个假设是错的**：
            //    本方法的唯一自动触发点在 RoadGenerationService 的
            //    `if (attempt.outcome().road() != null)` 分支里，也就是
            //    **只有成功**的连接才触发。失败连接走的是另一条
            //    （`updateConnectionStatus(FAILED)`）不触发。
            //    ⇒ N 段路线失败 1 段，最多触发 N−1 次 < N ⇒ 上限**永不可达**
            //      ⇒ 整条路线（含成功段的区块）永远不预加载，静默退回被动回填。
            //      （这是 0.8.391 核心承诺在"有连接失败"时完全失效。）
            //
            // ✅ 现在改用**连接状态机**作判据（见 decideReadiness）：
            //    不再依赖"触发了几次" —— 那个量本身不可靠。
            int stillActive = remainingActiveConnections(level, route);
            if (decideReadiness(false, stillActive) == Readiness.WAIT) {
                LOGGER.info("RouteWeaver 手绘路线预加载等待：尚有 {} 条连接在生成中 route={} chunks={}",
                        stillActive, route.id(), chunks.size());
                return 0;
            }
            LOGGER.warn("RouteWeaver 手绘路线预加载放行（连接已全部结算，但部分区块无计划 "
                            + "⇒ 对应连接可能已失败）route={} chunks={} —— "
                            + "有计划的区块照常铺设，其余交给被动回填",
                    route.id(), chunks.size());
        }
        // ★ 2026-09-26 修 I-10/W-9：**删掉 `forgetWait` 调用**。
        //   原先就在这里清等待计数 —— 但那套「等待轮数」机制在 0.8.395
        //   改用 decideReadiness（连接状态机）之后就已经**完全没人读了**，
        //   属于死代码。留着它会造成"对不存在机制的维护错觉"（下一个 R-10）。

        // ── ① 去重：路线**就绪后**只预加载一次 ──
        //
        // 放在就绪判定**之后**：否则第一条连接完成时就占了位，
        // 后面全被跳过（这正是 R-1 的成因之一）。
        //
        // ★ 2026-09-25 修 R-4：占位后**所有**退出路径都必须负责清理 ——
        //   见下面的 try/finally。原实现只在"正常走到 failed>0"时清标记，
        //   若中途抛异常（placeLoadedChunks / 日志 / 存储），标记会残留，
        //   后续连接被永久跳过。
        boolean claimed = false;
        if (route.id() != null) {
            java.util.Set<java.util.UUID> done = preloaded(level);
            if (!done.add(route.id())) {
                LOGGER.debug("RouteWeaver 手绘路线预加载已做过，跳过 route={}", route.id());
                return 0;
            }
            claimed = true;
        }

        int handled;
        try {
            Result r = runPreload(level, server, route, chunks);
            handled = r.handled();
            // 有失败 ⇒ 释放占位，让后续连接能再试（成功则保留，避免重复劳动）
            if (claimed && r.failed() > 0) {
                forgetPreloaded(level, route.id());
                LOGGER.info("RouteWeaver 手绘路线预加载有 {} 块失败，已清除去重标记以便重试 route={}",
                        r.failed(), route.id());
            }
        } catch (Throwable t) {
            // ★ 修 R-4：抛异常时也要释放占位，否则后续连接被永久跳过
            if (claimed) {
                forgetPreloaded(level, route.id());
            }
            throw t;
        }
        return handled;
    }

    /**
     * 预加载结果。
     *
     * @param handled 实际交给回填器处理的区块数
     * @param failed  重试用尽仍失败的区块数（&gt;0 时调用方会释放去重占位以便重试）
     */
    private record Result(int handled, int failed) {
    }

    /** 真正执行预加载（占位与释放由调用方负责）。 */
    private static Result runPreload(ServerLevel level, MinecraftServer server,
                                  PlayerRoute route, Set<Long> chunks) {
        // 从玩家当前位置（或路线起点）由近到远 —— 玩家先看到身边的路建起来
        ChunkPos origin = originChunk(level, route);
        List<Long> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingLong(key -> distanceSquared(origin, key)));

        int batchSize = BATCH_SIZE;

        int done = 0;
        int failed = 0;
        // ★ UX-11：是否被玩家中止
        boolean stopped = false;
        long startedAt = System.nanoTime();
        // ★ UX-2/3/5/11：把进度暴露给 UI（不在内存的区块数在下面单独统计）
        int notInMemory = countNotInMemory(level, ordered);
        List<ChunkPos> failedChunks = new ArrayList<>();
        RoutePreloadStatus.begin(level, route.id(), ordered.size(), notInMemory);
        LOGGER.info("RouteWeaver 手绘路线预加载开始 route={} chunks={} batch={} notInMemory={}",
                route.id(), ordered.size(), batchSize, notInMemory);

        for (int offset = 0; offset < ordered.size(); offset += batchSize) {
            int end = Math.min(ordered.size(), offset + batchSize);
            List<Long> batch = ordered.subList(offset, end);

            Set<Long> ready = new LinkedHashSet<>();
            Set<Long> pending = new LinkedHashSet<>(batch);
            Map<Long, RuntimeException> failures = new LinkedHashMap<>();

            for (int attempt = 1; attempt <= MAX_CHUNK_ATTEMPTS && !pending.isEmpty(); attempt++) {
                Map<Long, CompletableFuture<?>> futures = new LinkedHashMap<>();
                for (long key : pending) {
                    // forceLoad = true ⇒ 走区块源的生成管线（C2ME 环境下即其多线程生成）
                    futures.put(key, level.getChunkSource().getChunkFuture(
                            ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, true));
                }
                CompletableFuture<?> all = CompletableFuture.allOf(
                        futures.values().toArray(CompletableFuture[]::new));
                // 等服务端把这一批区块生成/加载好。
                // ⚠️ managedBlock 只推进区块任务队列，**不跑普通 tickServer**
                //    （见类 javadoc 的更正说明）—— 所以等待期间游戏 tick 是暂停的。
                server.managedBlock(all::isDone);

                Set<Long> nextPending = new LinkedHashSet<>();
                for (Map.Entry<Long, CompletableFuture<?>> entry : futures.entrySet()) {
                    long key = entry.getKey();
                    int cx = ChunkPos.getX(key);
                    int cz = ChunkPos.getZ(key);
                    try {
                        entry.getValue().join();
                        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                            ready.add(key);
                            failures.remove(key);
                        } else {
                            nextPending.add(key);
                            failures.put(key, new IllegalStateException(
                                    "FULL chunk was not available after completion"));
                        }
                    } catch (RuntimeException failure) {
                        nextPending.add(key);
                        failures.put(key, failure);
                        LOGGER.warn("RouteWeaver 手绘路线预加载失败 attempt={}/{} chunk={},{}",
                                attempt, MAX_CHUNK_ATTEMPTS, cx, cz, failure);
                    }
                }
                pending = nextPending;
            }

            if (!pending.isEmpty()) {
                // ★ 不抛异常中断：一块失败不该让整条路线作废。
                //   失败的块会被计入日志，交由原有回填兜底（玩家走过去时再补）。
                failed += pending.size();
                for (long key : pending) {
                    RuntimeException f = failures.get(key);
                    failedChunks.add(new ChunkPos(key));   // ★ UX-5：记录坐标供地图标记
                    LOGGER.warn("RouteWeaver 手绘路线预加载放弃 chunk={},{} （重试 {} 次仍失败，转交后台回填兜底）",
                            ChunkPos.getX(key), ChunkPos.getZ(key), MAX_CHUNK_ATTEMPTS,
                            f == null ? null : f.getMessage());
                }
            }

            if (!ready.isEmpty()) {
                // 加载好了立刻铺。
                //
                // ★ 必须传 budget=BATCH_SIZE：{@code placeLoadedChunks} 内部原来
                //   硬编码每次最多处理 8 个区块 —— 区块已被我们强制加载到内存，
                //   再按 8 个切就纯属浪费（等于白分批，361 个仍要走 46 轮）。
                //   传 32 与本类自己的分批一致。
                LoadedChunkRoadBackfill.placeLoadedChunks(level, ready, false, BATCH_SIZE);
                done += ready.size();
                RoadConstructionLedger.flush(level);
            }

            // ★ UX-2：每批更新进度；★ UX-11：每批检查中止请求
            RoutePreloadStatus.update(level, route.id(), ordered.size(), done, failed,
                    notInMemory, "加载并铺设中", failedChunks);
            if (RoutePreloadStatus.isStopRequested(level, route.id())) {
                stopped = true;
                LOGGER.info("RouteWeaver 手绘路线预加载被玩家中止 route={} 已完成 {}/{}",
                        route.id(), done, ordered.size());
                break;
            }
        }

        LOGGER.info("RouteWeaver 手绘路线预加载完成 route={} chunks={}/{} failed={} stopped={} elapsedMs={}",
                route.id(), done, ordered.size(), failed, stopped,
                (System.nanoTime() - startedAt) / 1_000_000L);
        // ★ UX-2/5/11：落终态（保留失败坐标供地图标记）
        RoutePreloadStatus.finish(level, route.id(), ordered.size(), done, failed,
                notInMemory, failedChunks, stopped);
        // 中止后清掉标志 —— 否则下次重新提交会被立刻中止
        if (stopped) {
            RoutePreloadStatus.clearStop(level, route.id());
        }
        return new Result(done, failed);
    }

    /**
     * 统计其中有多少区块**尚未生成**（★ UX-3：提交前警告依据）。
     *
     * <p>判据：区块不在内存里（{@code getChunkNow == null}）**且**磁盘上也没有 ——
     * 后者难以廉价判断，所以这里用「不在内存」作近似上界（偏保守：
     * 已生成但恰好没加载的也会被算进来，但那些加载很快，警告不误导）。</p>
     */
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

    /**
     * **这条路线还有多少条连接没有结论**（★ 2026-09-26 修 R-1 的核心判据）。
     *
     * <p>复用 {@link RouteBuildProgress#snapshot} 的汇总 —— 它本来就是
     * 「按连接状态机数 PLANNED / GENERATING / COMPLETED / FAILED」，
     * 而 {@code active()} = planned + generating 正好是"还没结论"的数量。</p>
     *
     * <p>⚠️ 为什么不用"等了几轮"：预加载的自动触发点只在连接**成功**回调里
     * （见 {@code RoadGenerationService} 的 {@code road != null} 分支），
     * 失败连接**不触发**。所以"触发次数"这个量本身就不可靠 ——
     * 用它做上限会导致永不满足。状态机判据与触发次数无关，因此稳健。</p>
     *
     * @return 仍未结算的连接数；查不到时返回 0（保守放行，不卡住流程）
     */
    private static int remainingActiveConnections(ServerLevel level, PlayerRoute route) {
        try {
            if (route.id() == null) {
                return 0;
            }
            var snap = net.shiroha233.roadweaver.route.RouteBuildProgress
                    .snapshot(level, route.id());
            return snap == null ? 0 : snap.active();
        } catch (RuntimeException | LinkageError e) {
            // 查不到就放行 —— 宁可多跑一次预加载，也不要卡在等待
            LOGGER.warn("手绘路线预加载：连接状态查询失败，按已结算处理", e);
            return 0;
        }
    }

    /**
     * **路线级就绪判定**（★ 修 R-1 的核心）。
     *
     * <h2>为什么必须存在</h2>
     * 本方法由「**任意一条**连接完成」触发，而一条路线会拆成 N 条连接。
     * 第一条完成时，其余连接的 {@code RoadData} **还没落盘** ——
     * 那些区块查 {@code RoadWorldgenPlanCache} 会得到**空计划**，
     * 被回填器误判为「无需施工」并**永久**移除（见
     * {@code DeferredInitialRoadConstructionService.markNoConstructionRequired} 的注释）。
     *
     * <p>所以预加载必须等「**所有**区块的计划都非空」。判据直接复用
     * {@code RoadWorldgenPlanCache.get} 的"空计划"语义 —— 它本来就能反映
     * 「这条连接还没落盘」，无需额外维护计数。</p>
     *
     * <h2>为什么可以安全地"不做事就返回"</h2>
     * 每次有连接完成都会再进 {@link #preloadAndConstruct}。
     * 只要还有连接没完成，就继续返回；**最后一条**连接完成时必然全部就绪。
     * 所以不需要队列/重试调度，天然收敛。
     *
     * <h2>⚠️ 有界等待（避免死等）</h2>
     * 若某条连接**永久失败**（RoadData 永远不会有），"等所有区块就绪"会永不满足。
     * 所以按 {@code route.segmentCount()} 设等待上限 —— 每条连接至多贡献一次触发，
     * 超过就放行。放行时无计划的区块会被回填器判为"无需施工"，
     * 但那本来就是**失败的连接**对应的区块（没有任何可铺的数据），
     * 语义上与"永久跳过"一致，不是新引入的损失。
     *
     * @return true 表示所有区块都已能编译出非空计划（= 该路线所有连接都已落盘）
     */
    private static boolean allChunksHavePlan(ServerLevel level, Set<Long> chunks) {
        try {
            net.shiroha233.roadweaver.config.ModConfig config =
                    net.shiroha233.roadweaver.config.ConfigService.get();
            if (config == null) {
                // 配置读不到就别卡住 —— 按"就绪"处理，交给后续流程自然失败
                return true;
            }
            for (long key : chunks) {
                ChunkPos pos = new ChunkPos(key);
                var plan = net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache
                        .get(level, pos, config);
                if (plan == null || plan.slices().isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            // 判定本身失败时保守放行（宁可多跑一次预加载，也不要永远卡在等待）
            LOGGER.warn("手绘路线预加载就绪判定失败，按就绪处理", e);
            return true;
        }
    }
}
