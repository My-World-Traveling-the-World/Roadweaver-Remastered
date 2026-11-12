package net.shiroha233.roadweaver.route;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import org.jetbrains.annotations.Nullable;

/**
 * 运行时登记表：**哪些施工连接来自玩家手绘路线**。
 *
 * <h2>要解决什么问题</h2>
 * 施工管线（{@code RoadGenerationService} → {@code Road.generateRoad}）只认识
 * {@link StructureConnection}，不知道它来自算法还是玩家。但我们必须在
 * {@code RoadData} 产出的那一刻用玩家意图覆盖它（见 {@link RouteRoadDataPatcher}）。
 *
 * <p>所以这里维护一张「连接 → 玩家路线」的登记表：
 * 注入时登记，施工时查询，施工完成后清理。</p>
 *
 * <h2>为什么用连接本身当键</h2>
 * 施工全流程都围绕 {@code StructureConnection} 走（状态机、持久化、重试都用它）。
 * 用它的起终点做键，就能在任何环节把「这条连接属于哪条手绘路线」查回来，
 * 不需要改动 {record} 本身（那会牵动编解码与全部既有调用点）。
 *
 * <h2>并发</h2>
 * 施工在多个 worker 线程上跑，登记表用 {@link ConcurrentHashMap}。
 * 键是「起终点坐标」，不含状态，所以是稳定的。
 */
public final class RouteBuildRegistry {

    /** 一个待施工的手绘分段登记项。 */
    public record Entry(UUID routeId, int controlPointIndex, RouteKind kind) {
    }

    /** 登记表：连接键 → 登记项。键用 {@link PlannedPathKey}，与管线同一套键空间。 */
    private static final Map<ServerLevel, Map<PlannedPathKey, Entry>> BY_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 历史登记总数（P2-2）。
     *
     * <p>独立于 {@link #BY_LEVEL} 计数，**不随 clear / 分区回收而减少** ——
     * 这才是「这条路线一共提交过多少段」的真实值。
     * 进度统计用「历史总数 − 当前表里的数」推算「已结算/已归档」的段数。</p>
     */
    private static final Map<ServerLevel, java.util.concurrent.atomic.AtomicInteger> TOTAL_BY_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 按路线 id 分的历史登记数（用于 countForRoute）。 */
    private static final Map<ServerLevel, Map<UUID, java.util.concurrent.atomic.AtomicInteger>>
            ROUTE_TOTALS_BY_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 服务端持有的路线对象，按 id 索引。
     *
     * <p>{@link Entry} 里只有 routeId，但 {@code RouteRoadDataPatcher} 需要
     * **完整的 PlayerRoute**（要读控制点的高度与类型）。所以这里同时存一份路线本体。</p>
     *
     * <p>用 {@link ServerLevel} 分区，避免跨维度串味。</p>
     */
    private static final Map<ServerLevel, Map<UUID, PlayerRoute>> ROUTES_BY_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 路线本体（供 patcher 用）。 */
    private static Map<UUID, PlayerRoute> routes(ServerLevel level) {
        return ROUTES_BY_LEVEL.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
    }

    private RouteBuildRegistry() {
    }

    private static Map<PlannedPathKey, Entry> map(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
    }

    /** 该维度的按路线历史计数表。 */
    private static Map<UUID, java.util.concurrent.atomic.AtomicInteger> routeTotals(ServerLevel level) {
        return ROUTE_TOTALS_BY_LEVEL.computeIfAbsent(level, k -> new ConcurrentHashMap<>());
    }

    /**
     * 连接 → 登记键。
     *
     * <h2>★ 审计 P2-13：改用 {@code PlannedPathKey}，与管线同一套键空间</h2>
     * 原实现是自己压一个 long：
     * <pre>
     *   long a = (from.x << 32) ^ (from.z & 0xFFFFFFFF);
     *   long b = (to.x   << 32) ^ (to.z   & 0xFFFFFFFF);
     *   return a * 31 + b;      // 忽略 Y、方向敏感
     * </pre>
     * 两个问题：
     * <ol>
     *   <li><b>忽略 Y</b> —— 螺旋上升的路、上下叠的桥（XZ 相同 Y 不同）
     *       会互相覆盖登记项，后提交的把先提交的顶掉。</li>
     *   <li><b>方向敏感</b> —— A→B 与 B→A 是两个键，而
     *       {@code PlannedPathCache} 的 {@code PlannedPathKey} 构造时会按坐标排序、
     *       是同一个键 ⇒ 反向连接会命中正向的路径、倒着走。</li>
     * </ol>
     * {@link PlannedPathKey} 是 record（精确 equals/hashCode）且构造时排序，
     * 直接当 map 键即可 —— 含 Y、无序，两个问题一起解决，且**无碰撞风险**
     * （不用 hashCode 压成 long：那会把碰撞风险引回来）。</p>
     */
    public static PlannedPathKey key(StructureConnection connection) {
        return connection == null ? null : PlannedPathKey.of(connection);
    }

    public static PlannedPathKey key(BlockPos from, BlockPos to) {
        return (from == null || to == null) ? null : PlannedPathKey.of(from, to);
    }

    /**
     * 登记一条来自手绘路线的连接。
     *
     * <p>同时累加历史计数（P2-2）—— 那个计数只在**首次**登记某个键时递增，
     * 重复登记不会重复计数。</p>
     */
    public static void register(ServerLevel level, StructureConnection connection,
                                UUID routeId, int controlPointIndex, RouteKind kind) {
        if (level == null || connection == null) {
            return;
        }
        PlannedPathKey k = key(connection);
        if (k == null) {
            return;
        }
        Entry previous = map(level).put(k, new Entry(routeId, controlPointIndex, kind));
        // ★ 审计 R3-P2-10：**换了一条路线**用同一个键，也必须为该路线计数。
        //
        // 原判据是 `previous == null` —— 于是「先建 A→B，再画一条 A→B 并提交」
        // 时，第二条路线的 register 发现键已存在就完全不计数，
        // 它的 countForRoute 恒为 0 ⇒ HUD 永远显示「尚未提交施工」，
        // 玩家以为没提交成功，反复重试。
        //
        // 现在按「(routeId, key) 组合首次出现」计数：键从属于别的路线时，
        // 新路线自己的历史计数要 +1。
        boolean firstForThisRoute = previous == null
                || !java.util.Objects.equals(previous.routeId(), routeId);
        if (firstForThisRoute) {
            TOTAL_BY_LEVEL.computeIfAbsent(level, x -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            if (routeId != null) {
                routeTotals(level)
                        .computeIfAbsent(routeId,
                                x -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
            }
        }
    }

    /**
     * ★ 审计 R3-P1-4：施工完工后清理登记项。
     *
     * <p>原先只有 {@code ConnectionDeletionService} 会清（删除连接时），
     * 正常完工的项**永远留在表里** —— 长会话下累积，且让
     * {@code RouteBuildProgress} 的「registered vs removed」统计失真。</p>
     *
     * <p>注意：清理**不影响历史计数**（{@code TOTAL_BY_LEVEL} / {@code ROUTE_TOTALS_BY_LEVEL}
     * 独立维护），所以 HUD 的「已提交 N 段」在完工后依然正确。</p>
     */
    public static void clearCompleted(ServerLevel level, StructureConnection connection) {
        clear(level, connection);
    }

    /** 查询该连接是否来自手绘路线。 */
    @Nullable
    public static Entry lookup(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) {
            return null;
        }
        PlannedPathKey k = key(connection);
        return k == null ? null : map(level).get(k);
    }

    public static boolean isPlayerRoute(ServerLevel level, StructureConnection connection) {
        return lookup(level, connection) != null;
    }

    /** 施工完成后清理单条（**不影响历史计数** —— 完成后才算"已结算"）。 */
    public static void clear(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) {
            return;
        }
        PlannedPathKey k = key(connection);
        if (k != null) {
            map(level).remove(k);
        }
    }

    /**
     * 清空该维度的**全部**登记（离开世界 / 重置时调用）。
     *
     * <h2>★ Codex 审查 D-1（2026-09-25）：原先漏了路线本体表</h2>
     * 原实现只清了 {@link #BY_LEVEL} / {@link #ROUTE_TOTALS_BY_LEVEL} /
     * {@link #TOTAL_BY_LEVEL} 三个，**漏了 {@link #ROUTES_BY_LEVEL}** ——
     * 于是离开世界后，那一维度的 {@code PlayerRoute} 对象仍被持有。
     *
     * <p>影响面：{@code ROUTES_BY_LEVEL} 的 key 是 {@code WeakHashMap} 的弱引用
     * {@link ServerLevel}，世界卸载且 level 被 GC 后整个分区会**自动消失**，
     * 所以**不是永久泄漏**，只是「level 存活期间多占一点内存」。
     * 但清理不完整本身是逻辑错误（方法名承诺清空，实际没清），故补上。</p>
     *
     * <h2>⚠️ 调用方注意（比漏清表更值得注意的事）</h2>
     * 本方法**必须由「离开世界」的路径显式调用**才有效果 ——
     * 仅仅在这里补一行是不够的。当前调用点是
     * {@code ClientInit} 的 {@code ClientPlayConnectionEvents.DISCONNECT}
     * （见 {@code RouteEditorSession.clearAll} 的配套说明）。
     */
    public static void clearAll(ServerLevel level) {
        if (level == null) {
            return;
        }
        map(level).clear();
        routeTotals(level).clear();
        routes(level).clear();               // ★ D-1：补上路线本体表
        var total = TOTAL_BY_LEVEL.get(level);
        if (total != null) {
            total.set(0);
        }
    }

    /**
     * **无条件**清空该维度的全部登记，并**连同分区条目一起移除**
     * （{@code WeakHashMap} 里的 key 也去掉，不等 GC）。
     *
     * <h2>为什么不直接调 {@link #clearAll}</h2>
     * {@code clearAll} 只清「分区里的 map 内容」，仍留着空的
     * {@code ServerLevel → 空 map} 条目。离开世界时连条目一起移掉更干净，
     * 也避免「同一 level 对象被复用」时读到上一轮的残留。
     *
     * <p>由 {@code ClientInit} 的 DISCONNECT 调用。传 null 时静默返回。</p>
     */
    public static void clearAllAndDropPartitions(@Nullable ServerLevel level) {
        if (level == null) {
            return;
        }
        BY_LEVEL.remove(level);
        ROUTE_TOTALS_BY_LEVEL.remove(level);
        ROUTES_BY_LEVEL.remove(level);
        TOTAL_BY_LEVEL.remove(level);
    }

    /**
     * **离开世界时的统一清理入口**（★ Codex 审查 D-1 的配套修复）。
     *
     * <p>为什么要单独一个方法：{@code ClientInit} 的 DISCONNECT 原先只调了
     * {@code RouteEditorSession.clearAll()}（那只清**会话的**路线表），
     * 完全没碰注册表 —— 于是三张按维度表在 level 存活期间一直留着旧世界的登记。
     * 这个方法的职责就是「世界退出 → 注册表全清」，供 DISCONNECT 调用。</p>
     *
     * <p>与 {@link #clearAllAndDropPartitions} 的区别：本方法额外
     * **失效进度缓存**，因为 HUD 下一帧可能还会读一次（避免显示上一世界的进度）。</p>
     */
    public static void onWorldLeave(@Nullable ServerLevel level) {
        if (level == null) {
            return;
        }
        clearAllAndDropPartitions(level);
        RouteBuildProgress.invalidateCache();
    }

    // ─────────────────── 路线本体（供 patcher 使用）───────────────────

    /** 存一条路线的服务端副本。 */
    public static void putRoute(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null) {
            return;
        }
        routes(level).put(route.id(), route);
    }

    /** 取路线的服务端副本。 */
    @Nullable
    public static PlayerRoute getRoute(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        return routes(level).get(id);
    }

    /** 按连接查路线本体（施工时最常用：拿到 connection 就要路线）。 */
    @Nullable
    public static PlayerRoute routeFor(ServerLevel level, StructureConnection connection) {
        Entry e = lookup(level, connection);
        return e == null ? null : getRoute(level, e.routeId());
    }

    /** 移除路线本体。 */
    public static void removeRoute(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return;
        }
        routes(level).remove(id);
    }

    /**
     * 登记一整条注入计划。
     *
     * @return 登记条数
     */
    public static int registerPlan(ServerLevel level, UUID routeId, RouteInjector.InjectionPlan plan) {
        if (level == null || plan == null) {
            return 0;
        }
        int n = 0;
        for (RouteInjector.InjectionSegment seg : plan.segments()) {
            register(level, seg.connection(), routeId, seg.controlPointIndex(), seg.kind());
            n++;
        }
        return n;
    }

    /** 当前登记的所有路线 id（用于进度汇报）。 */
    public static List<UUID> registeredRouteIds(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (Entry e : map(level).values()) {
            if (e.routeId() != null && !out.contains(e.routeId())) {
                out.add(e.routeId());
            }
        }
        return out;
    }

    /**
     * 某条路线**历史登记过**多少条连接。
     *
     * <h2>★ 审计 P2-2：原来数的是当前表，假设不成立</h2>
     * 原实现的 Javadoc 声称「不会因为 reconcile 移除而减少」，
     * 但它只是**遍历当前 map 计数** —— 之所以"看起来"对，
     * 仅仅是因为当时没有任何地方在完工时调 {@code clear()}。
     * 一旦 {@code cancel} 或分区回收（{@code WeakHashMap} 的 ServerLevel 被 GC），
     * 计数就归零 → HUD 进度回到「尚未提交施工」，即使路已经建完。
     *
     * <p>现在改读**独立的历史计数器**，它只在首次登记时递增、
     * 不随 clear 或分区回收减少 —— 这才是真实值。</p>
     */
    public static int countForRoute(ServerLevel level, UUID routeId) {
        if (level == null || routeId == null) {
            return 0;
        }
        var counter = routeTotals(level).get(routeId);
        return counter == null ? 0 : counter.get();
    }

    /** 该维度历史登记的总段数（不随 clear 减少）。 */
    public static int totalRegistered(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        var total = TOTAL_BY_LEVEL.get(level);
        return total == null ? 0 : total.get();
    }
}
