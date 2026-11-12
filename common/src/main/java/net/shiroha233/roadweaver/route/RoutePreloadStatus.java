package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 手绘路线**预加载阶段**的状态（供 UI 显示 + 紧急停止）。
 *
 * <h2>为什么单独一个类</h2>
 * {@link RouteBuildProgress} 统计的是「连接（道路段）的施工状态」，
 * 而本类统计的是**更早一步**的「强制加载区块并铺设」阶段 ——
 * 用户 2026-09-25 要求「提交施工时立即拉、必须全部加载完成」之后，
 * 这一步可能持续十几秒到几分钟，期间玩家需要看到：
 * <ul>
 *   <li>总共要处理多少区块、已经处理多少（★ UX-2 进度）</li>
 *   <li>其中有多少是**未探索区域**（会触发地形生成，明显更慢 —— ★ UX-3 警告）</li>
 *   <li>哪些区块失败了（★ UX-5 可视化）</li>
 *   <li>能不能**中止**（★ UX-11 紧急停止）</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 写入在**服务端线程**（预加载循环里），读取在**客户端渲染线程**。
 * 所以内部用 {@link ConcurrentHashMap} + {@code volatile} 快照字段。
 */
public final class RoutePreloadStatus {

    private RoutePreloadStatus() {
    }

    /**
     * 一条路线的预加载快照（不可变，可安全跨线程传递）。
     *
     * @param routeId       路线 id
     * @param total         本次要处理的区块总数（含扩 1 圈）
     * @param processed     已处理（成功加载并交给回填）
     * @param failed        重试用尽仍失败
     * @param unexplored    其中**未探索**（需触发生成）的区块数 —— UX-3 警告依据
     * @param active        是否正在进行
     * @param stage         当前阶段的文字描述（准备中 / 加载中 / 铺设中）
     * @param failedChunks  失败的区块坐标（UX-5 地图标记用；上限 64 条以免快照过大）
     * @param stopped       是否被玩家中止（UX-11）
     */
    public record Snapshot(UUID routeId,
                           int total,
                           int processed,
                           int failed,
                           int unexplored,
                           boolean active,
                           String stage,
                           List<ChunkPos> failedChunks,
                           boolean stopped) {

        public static final Snapshot IDLE =
                new Snapshot(null, 0, 0, 0, 0, false, "", List.of(), false);

        /** 进度比例 0~1（total=0 时返回 0）。 */
        public float ratio() {
            return total <= 0 ? 0f : Math.min(1f, (float) processed / total);
        }

        public boolean hasUnexplored() {
            return unexplored > 0;
        }
    }

    /** 按 level 分区（WeakHashMap，level 回收后自动消失）。 */
    private static final Map<ServerLevel, Map<UUID, Snapshot>> BY_LEVEL =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * **中止标志**（★ UX-11：预加载紧急停止）。
     *
     * <p>预加载循环每处理一批就检查一次；置位后**停止拉新的批次**，
     * 已加载的保留。不设超时是用户的要求，但玩家仍该有"我不想等了"的出口。</p>
     */
    private static final Map<ServerLevel, java.util.Set<UUID>> STOP_REQUESTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // ─────────────────── 写（服务端线程）───────────────────

    public static void begin(ServerLevel level, UUID routeId, int total, int unexplored) {
        if (level == null || routeId == null) {
            return;
        }
        put(level, routeId, new Snapshot(routeId, total, 0, 0, unexplored,
                true, "准备中", List.of(), false));
    }

    public static void update(ServerLevel level, UUID routeId, int total,
                              int processed, int failed, int unexplored,
                              String stage, List<ChunkPos> failedChunks) {
        if (level == null || routeId == null) {
            return;
        }
        put(level, routeId, new Snapshot(routeId, total, processed, failed, unexplored,
                true, stage == null ? "" : stage,
                failedChunks == null ? List.of() : List.copyOf(limit(failedChunks)),
                isStopRequested(level, routeId)));
    }

    public static void finish(ServerLevel level, UUID routeId, int total,
                              int processed, int failed, int unexplored,
                              List<ChunkPos> failedChunks, boolean stopped) {
        if (level == null || routeId == null) {
            return;
        }
        put(level, routeId, new Snapshot(routeId, total, processed, failed, unexplored,
                false, stopped ? "已中止" : "完成",
                failedChunks == null ? List.of() : List.copyOf(limit(failedChunks)),
                stopped));
    }

    /** 请求中止某条路线的预加载（★ UX-11）。 */
    public static void requestStop(ServerLevel level, UUID routeId) {
        if (level == null || routeId == null) {
            return;
        }
        STOP_REQUESTED.computeIfAbsent(level,
                k -> ConcurrentHashMap.newKeySet()).add(routeId);
    }

    /** 是否已被请求中止（预加载循环每批检查一次）。 */
    public static boolean isStopRequested(ServerLevel level, UUID routeId) {
        if (level == null || routeId == null) {
            return false;
        }
        var set = STOP_REQUESTED.get(level);
        return set != null && set.contains(routeId);
    }

    /** 清除中止标志（重新提交同一条路线时调用，否则新的预加载一上来就被中止）。 */
    public static void clearStop(ServerLevel level, UUID routeId) {
        if (level == null || routeId == null) {
            return;
        }
        var set = STOP_REQUESTED.get(level);
        if (set != null) {
            set.remove(routeId);
        }
    }

    // ─────────────────── 读（客户端渲染线程）───────────────────

    /** 取某条路线的最新快照（没有则返回 {@link Snapshot#IDLE}）。 */
    public static Snapshot snapshot(ServerLevel level, UUID routeId) {
        if (level == null || routeId == null) {
            return Snapshot.IDLE;
        }
        var m = BY_LEVEL.get(level);
        if (m == null) {
            return Snapshot.IDLE;
        }
        Snapshot s = m.get(routeId);
        return s == null ? Snapshot.IDLE : s;
    }

    /** 当前**正在预加载**的路线（可能多条同时）。 */
    public static List<Snapshot> active(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        var m = BY_LEVEL.get(level);
        if (m == null) {
            return List.of();
        }
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : m.values()) {
            if (s != null && s.active()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * **有失败区块、或已被中止、且已结束**的路线
     * （★ UX-5 的「重新尝试」按钮据此出现；★ 2026-09-26 修 W-16/I-6 纳入 stopped）。
     *
     * <p>⚠️ 关键：不能用"当前编辑对象"来判断 —— 0.8.388 起施工成功后
     * 编辑对象会切到**新的空路线**，失败其实属于**刚提交的那条**。
     * 所以这里直接按 level 列出所有符合条件的快照。</p>
     *
     * <p>★ 修 W-16：原先只收 {@code failed > 0}，于是**玩家自己按 Ctrl+U 中止后
     * 完全没有出口** —— 中止时保留了去重占位，却没有重试按钮能让它重新跑。
     * 现在把 {@code stopped} 也纳入（中止 = 用户主动放弃本轮，
     * 但之后可能想再试一次）。</p>
     */
    public static List<Snapshot> retryable(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        var m = BY_LEVEL.get(level);
        if (m == null) {
            return List.of();
        }
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot s : m.values()) {
            if (s != null && !s.active() && s.routeId() != null
                    && (s.failed() > 0 || s.stopped())) {
                out.add(s);
            }
        }
        return out;
    }

    // ─────────────────── 清理 ───────────────────

    /** 离开世界时全清（与 RouteBuildRegistry.onWorldLeave 配套）。 */
    public static void onWorldLeave(ServerLevel level) {
        if (level == null) {
            return;
        }
        BY_LEVEL.remove(level);
        STOP_REQUESTED.remove(level);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
        STOP_REQUESTED.clear();
    }

    // ─────────────────── 内部 ───────────────────

    private static void put(ServerLevel level, UUID routeId, Snapshot snap) {
        BY_LEVEL.computeIfAbsent(level, k -> new ConcurrentHashMap<>()).put(routeId, snap);
    }

    /** 失败坐标列表上限（避免快照过大；UX-5 只需要标记主要缺口）。 */
    private static final int MAX_FAILED_LISTED = 64;

    private static List<ChunkPos> limit(List<ChunkPos> in) {
        return in.size() <= MAX_FAILED_LISTED ? in : in.subList(0, MAX_FAILED_LISTED);
    }
}
