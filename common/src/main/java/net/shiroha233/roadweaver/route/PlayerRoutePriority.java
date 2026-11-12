package net.shiroha233.roadweaver.route;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;

/**
 * **手绘路线施工的"最高优先级"状态**（★ 2026-09-26 任务 C）。
 *
 * <h2>要解决什么</h2>
 * 用户真机日志实证：提交手绘路线后，施工被**暂停了 98 秒**才真正开始。
 * <pre>
 *   11:29:36  手绘路线已提交施工（4 段）
 *   11:29:48  runtime work paused for foreground recovery cooldown
 *   11:29:53  runtime work paused for player interaction priority   ← 暂停
 *   ⋯⋯ 60 秒没有任何 resume ⋯⋯
 *   11:30:43  coarse planning timing: samples=252525 samplingMs=164155  ← 后台粗规划
 *   11:31:14  route PLANNED_OK（手绘的 8 条连接）← 才开始
 * </pre>
 *
 * <p>根因：{@code RoadGenerationService.tick} 开头就是
 * <pre>
 *   if (RuntimeLoadPolicy.pauseScheduling()) return;
 * </pre>
 * 于是「为性能让路」的暂停**把玩家主动点的施工也一起停了**。
 * 这是设计缺陷：后台自动规划可以等（用户口径 10~20 分钟都能接受），
 * 但玩家点了施工正盯着屏幕。</p>
 *
 * <h2>用户要求</h2>
 * <blockquote>
 *   「手动道路优先级最高」<br>
 *   「建完为止　超长路线分段提交防止彻底卡死」
 * </blockquote>
 *
 * <h2>本类做什么</h2>
 * 只记录「当前有多少条手绘连接待施工/在施工」这一个事实，
 * 供 {@code RoadGenerationService} 判断是否豁免暂停、是否给足 worker。
 * <b>不持有队列、不做调度</b> —— 调度逻辑仍在那一个地方。
 *
 * <h2>线程</h2>
 * 写入在服务端线程（tick / submit），读取也在服务端线程；
 * 但仍用并发结构，避免任何边界下的可见性问题。
 */
public final class PlayerRoutePriority {

    private PlayerRoutePriority() {
    }

    /**
     * 按 level 记录「手绘路线待施工/在施工的连接数」。
     *
     * <p>用 {@code WeakHashMap} 与登记表同风格 —— level 回收后分区自动消失。</p>
     */
    private static final java.util.Map<ServerLevel, AtomicInteger> ACTIVE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 是否处于「玩家手绘施工优先」模式（>0 表示有手绘连接在排队或在建）。 */
    public static boolean isActive(ServerLevel level) {
        if (level == null) {
            return false;
        }
        AtomicInteger n = ACTIVE.get(level);
        return n != null && n.get() > 0;
    }

    /** 当前手绘连接数（0 = 没有）。 */
    public static int activeCount(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        AtomicInteger n = ACTIVE.get(level);
        return n == null ? 0 : n.get();
    }

    /** 设置当前手绘连接数（由 RoadGenerationService.tick 每次刷新后调用）。 */
    public static void setActiveCount(ServerLevel level, int count) {
        if (level == null) {
            return;
        }
        if (count <= 0) {
            ACTIVE.remove(level);
            return;
        }
        ACTIVE.computeIfAbsent(level, k -> new AtomicInteger()).set(count);
    }

    /** 离开世界时清理。 */
    public static void onWorldLeave(ServerLevel level) {
        if (level == null) {
            return;
        }
        ACTIVE.remove(level);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /**
     * 第一次进入「手绘优先」时记录时间戳，便于诊断"豁免了多久"。
     *
     * <p>用户选择「建完为止」（不设硬上限），但要求能观察 ——
     * 所以这里只记时间，不强行中断。</p>
     */
    private static final java.util.Map<ServerLevel, Long> STARTED_AT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 进入优先模式时调用（若已在模式中则忽略）。 */
    public static void notePriorityStart(ServerLevel level, UUID routeId, int connections) {
        if (level == null) {
            return;
        }
        Long prev = STARTED_AT.putIfAbsent(level, System.currentTimeMillis());
        if (prev == null) {
            org.slf4j.LoggerFactory.getLogger("roadweaver/route-build").info(
                    "手绘路线优先模式开始：route={} connections={} —— 暂停期间也为它开路，建完为止",
                    routeId, connections);
        }
    }

    /** 退出优先模式时调用，返回本次持续毫秒数（未处于模式则返回 -1）。 */
    public static long notePriorityEnd(ServerLevel level, UUID routeId) {
        if (level == null) {
            return -1L;
        }
        Long started = STARTED_AT.remove(level);
        if (started == null) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - started;
        org.slf4j.LoggerFactory.getLogger("roadweaver/route-build").info(
                "手绘路线优先模式结束：route={} 持续 {} ms", routeId, elapsed);
        return elapsed;
    }

    /**
     * **长时优先模式的安全阀**（★ 任务 C，用户要求「超长路线分段提交防止彻底卡死」）。
     *
     * <h2>为什么不设硬上限</h2>
     * 用户明确选了「**建完为止**」，所以这里**不中断**。
     *
     * <h2>为什么还是要一个阀</h2>
     * 「建完为止」的前提是**施工本身是分段的** —— 而它确实是：
     * <ul>
     *   <li>施工：一条路线拆成 N 条连接，各自独立入队，
     *       {@code tick} 每次只取一条 ⇒ 天然分段，不会"一口气做完整条路线"</li>
     *   <li>预加载：{@code RouteChunkPreloader} 按 {@code BATCH_SIZE=32} 分批，
     *       <b>每批之间</b>回到 {@code managedBlock} 之外 ⇒ 会让出控制权，
     *       且每批都检查中止请求（UX-11）</li>
     * </ul>
     * 所以「超长路线」不会造成单次不可中断的长任务。
     *
     * <p>这个阀只做**可观测性**：优先模式持续超过 {@value #LONG_RUN_WARN_MS} ms 时
     * 打一条 warn（每 {@value #LONG_RUN_LOG_INTERVAL_MS} ms 最多一条），
     * 让玩家/日志能看出"后台被豁免了很久"——
     * 若真出现问题，据此再决定要不要加硬上限。</p>
     *
     * @return true 表示本次调用打了一条长时警告
     */
    private static final long LONG_RUN_WARN_MS = 120_000L;
    private static final long LONG_RUN_LOG_INTERVAL_MS = 60_000L;

    /**
     * **安全上限：10 分钟**（★ 2026-09-26，用户要求）。
     *
     * <h2>用户的完整口径</h2>
     * <blockquote>
     *   「建完为止　**安全上限10分钟**」
     * </blockquote>
     * 即：正常情况下「建完为止」（不打断玩家），
     * 但若优先模式持续超过 10 分钟，说明出问题了（例如某条连接反复重试、
     * 或后台被饿死太久），此时**强制退出优先模式**让后台恢复，
     * 避免"玩家施工卡住 + 后台也全停"的最坏组合。
     *
     * <h2>为什么这个值合理</h2>
     * 用户对整体生成耗时的口径是「10~20 分钟可接受」——
     * 10 分钟足够建完一条很长的手绘路线，又不会让后台被无限饿死。
     *
     * <p>超过上限时打 warn 说明原因，并在日志里留下"已强制恢复"的记录，
     * 便于事后判断是不是上限设小了。</p>
     */
    private static final long PRIORITY_HARD_LIMIT_MS = 10 * 60 * 1000L;

    private static final java.util.Map<ServerLevel, Long> LAST_LONG_WARN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 优先模式是否**已超过安全上限**（10 分钟）。
     *
     * <p>调用方（{@code RoadGenerationService.tick}）据此**不再豁免暂停** ——
     * 让后台规划恢复，避免两者都被卡住。</p>
     *
     * <p>⚠️ 这只是"不再优先"，**施工本身照常继续**（它一直在正常队列里），
     * 只是重新受让路规则约束。玩家的路不会被取消。</p>
     */
    public static boolean isOverHardLimit(ServerLevel level) {
        if (level == null) {
            return false;
        }
        Long started = STARTED_AT.get(level);
        if (started == null) {
            return false;
        }
        long limitMs = hardLimitMsFromConfig();
        if (limitMs <= 0) {
            return false;   // 0 = 不限
        }
        return System.currentTimeMillis() - started > limitMs;
    }

    /**
     * 从配置读安全上限（分钟）；{@code 0} = 不限（★ 2026-09-26 改为配置项）。
     *
     * <p>用户口径「建完为止 + 安全上限10分钟」—— 做成配置后，
     * 真机觉得 10 分钟太短时**不用改代码**。</p>
     */
    private static long hardLimitMsFromConfig() {
        try {
            var cfg = net.shiroha233.roadweaver.config.ConfigService.get();
            if (cfg == null || cfg.performance() == null) {
                return PRIORITY_HARD_LIMIT_MS;   // 读不到就用默认 10 分钟
            }
            int minutes = cfg.performance().playerRoutePriorityHardLimitMinutes();
            return minutes <= 0 ? 0L : (long) minutes * 60_000L;
        } catch (RuntimeException | LinkageError e) {
            return PRIORITY_HARD_LIMIT_MS;
        }
    }

    /** 已持续毫秒数（未进入优先模式返回 -1）。 */
    public static long elapsedMs(ServerLevel level) {
        if (level == null) {
            return -1L;
        }
        Long started = STARTED_AT.get(level);
        return started == null ? -1L : System.currentTimeMillis() - started;
    }

    public static boolean warnIfLongRunning(ServerLevel level, int connections) {
        if (level == null) {
            return false;
        }
        Long started = STARTED_AT.get(level);
        if (started == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed < LONG_RUN_WARN_MS) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_LONG_WARN.get(level);
        if (last != null && now - last < LONG_RUN_LOG_INTERVAL_MS) {
            return false;
        }
        LAST_LONG_WARN.put(level, now);
        org.slf4j.LoggerFactory.getLogger("roadweaver/route-build").warn(
                "手绘路线优先模式已持续 {} ms（剩余 {} 条连接）—— 后台因让路给玩家施工而暂停。"
                        + "施工是分段的（每 tick 一条连接 / 预加载每批 32 区块），不会单次卡死；"
                        + "此日志仅作可观测性提示。",
                elapsed, connections);
        return true;
    }

    /** 离开世界时连警告节流状态一起清。 */
    public static void clearRuntimeFor(ServerLevel level) {
        if (level == null) {
            return;
        }
        ACTIVE.remove(level);
        STARTED_AT.remove(level);
        LAST_LONG_WARN.remove(level);
        HARD_LIMIT_WARNED.remove(level);
    }

    /** 已打过"超上限"警告的 level（避免每 tick 刷屏）。 */
    private static final java.util.Set<ServerLevel> HARD_LIMIT_WARNED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /**
     * 优先模式超过 10 分钟安全上限时告警**一次**。
     *
     * <p>用户要求「建完为止 + 安全上限 10 分钟」——
     * 超过后不再豁免暂停（让后台恢复），但**施工照常继续**。
     * 这条日志说明为什么后台突然恢复了。</p>
     */
    public static void warnIfOverLimitOnce(ServerLevel level, int connections) {
        if (level == null || !HARD_LIMIT_WARNED.add(level)) {
            return;
        }
        org.slf4j.LoggerFactory.getLogger("roadweaver/route-build").warn(
                "手绘路线优先模式已达 10 分钟安全上限（剩余 {} 条连接）—— "
                        + "**停止豁免让路暂停**，后台规划恢复；手绘施工本身照常继续，只是重新受让路约束。"
                        + "若这是误判（路线确实很长），可考虑调大该上限。",
                connections);
    }
}
