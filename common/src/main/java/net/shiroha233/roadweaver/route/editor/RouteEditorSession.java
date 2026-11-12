package net.shiroha233.roadweaver.route.editor;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteStatus;
import net.shiroha233.roadweaver.route.RouteStorage;
import net.shiroha233.roadweaver.route.RouteUndoStack;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端**跨界面存活**的手绘路线会话。
 *
 * <h2>为什么需要它</h2>
 * 第一版把 {@link RouteEditorBridge} 放在 {@code RoadMapScreen} 的实例字段里，
 * 于是**关掉地图再打开，画的路线就没了** —— 因为 Screen 被重建、字段跟着重建。
 *
 * <p>但用户要的流程是「地图上粗画 → 关地图 → 走进世界看 → 回来接着调」，
 * 路线必须活得比界面久。所以这里放一个静态会话，界面只是**视图**。</p>
 *
 * <h2>职责划分</h2>
 * <pre>
 *   RouteEditorSession   ← 单例，持有「当前编辑中的路线」与撤销栈
 *   RouteEditorBridge    ← 每个 Screen 一个，负责坐标换算与输入分发，
 *                          内部读写 Session（而不是自己持有路线）
 *   RouteStorage         ← 落盘
 * </pre>
 *
 * <h2>为什么不做成 Minecraft 的 WorldDataAttachment</h2>
 * 那是**服务端**数据。手绘路线在编辑期间是客户端草稿，
 * 玩家点「确定施工」才需要同步给服务端。用客户端单例最简单，
 * 也避免编辑中途每改一个点就发包。
 */
public final class RouteEditorSession {

    private static final RouteEditorSession INSTANCE = new RouteEditorSession();

    /**
     * 所有已保存/正在编辑的路线，按 id 索引。
     *
     * <h2>★ 为什么是 ConcurrentHashMap 而不是 LinkedHashMap</h2>
     * 这个表会被**两个线程**碰：
     * <ul>
     *   <li><b>客户端线程</b>：界面编辑、{@code tickAutoSave} 遍历保存</li>
     *   <li><b>服务端线程</b>：{@code submitForBuild} 经 {@code server.execute(...)}
     *       投递上去的 lambda 里调 {@code update(...)} 写状态</li>
     * </ul>
     * 原来用的是 {@code LinkedHashMap} —— 非线程安全，
     * 服务端线程写入时客户端正好在遍历保存，会抛
     * {@code ConcurrentModificationException} 或读到半更新的结构。
     *
     * <p>代价是失去插入顺序。但路线数量是个位数到几十条，
     * 顺序不影响任何功能（界面按 id 取，不按顺序遍历展示）。</p>
     */
    private final Map<UUID, PlayerRoute> routes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 地图界面的撤销栈（★ 审计 R3-P2-9）。
     *
     * <p>原先它是 {@code RouteEditorState} 的实例字段，而那个对象挂在
     * {@code RoadMapScreen} 上 —— **关掉地图再打开就是新对象、空栈**，
     * 玩家画的十几步撤销全部丢失（而用户明确要求「必须能撤销」）。</p>
     *
     * <p>挪到这里之后跨界面存活。与世界内编辑器的撤销栈**各自独立** ——
     * 两者操作的是不同输入方式，混在一起会让「按一下 Z 退掉哪个」变得不可预期。</p>
     */
    private final RouteUndoStack mapUndo = new RouteUndoStack();

    public RouteUndoStack mapUndoStack() {
        return mapUndo;
    }

    /** 当前正在编辑的那条（null 表示还没开始）。 */
    @Nullable
    private volatile UUID editingId;

    /** 当前工具条选中的类型（跨界面保留，符合「上次选的」直觉）。 */
    private net.shiroha233.roadweaver.route.RouteKind currentKind =
            net.shiroha233.roadweaver.route.RouteKind.ROAD;

    private volatile boolean active;

    /**
     * 当前选中的控制点下标，-1 表示没选。
     *
     * <p>放在会话里而不是各个界面里 —— 因为玩家会「在地图上选一个点 →
     * 关地图进世界看 → 回来还是这个点被选中」。放在界面里就会丢。</p>
     */
    private volatile int selectedPointIndex = -1;

    private RouteEditorSession() {
    }

    public static RouteEditorSession get() {
        return INSTANCE;
    }

    // ─────────────────── 编辑模式 ───────────────────

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
        this.active = value;
    }

    // ─────────────────── 当前路线 ───────────────────

    /**
     * 取当前正在编辑的路线；没有则新建一条草稿。
     *
     * <p>新建后**立即进 routes 表**，这样即使玩家马上关界面，
     * 这条空路线也在会话里，不会每次进地图都换一个 UUID。</p>
     */
    public PlayerRoute currentOrCreate() {
        if (editingId != null) {
            PlayerRoute r = routes.get(editingId);
            if (r != null) {
                return r;
            }
        }
        PlayerRoute created = PlayerRoute.create("路线 " + (routes.size() + 1));
        routes.put(created.id(), created);
        editingId = created.id();
        return created;
    }

    @Nullable
    public PlayerRoute current() {
        return editingId == null ? null : routes.get(editingId);
    }

    /**
     * 替换当前路线（编辑操作后用）。
     *
     * <p>这是**所有编辑操作的唯一汇聚点**（放点/删点/挪位/改高/改类型都走它），
     * 所以自动保存的脏标记也在这里打 —— 保证任何编辑都会被自动保存覆盖。</p>
     */
    public void update(PlayerRoute route) {
        if (route == null) {
            return;
        }
        routes.put(route.id(), route);
        editingId = route.id();
        markDirty();
        bumpRevision();
    }

    public void setEditing(UUID id) {
        this.editingId = id;
    }

    @Nullable
    public UUID editingId() {
        return editingId;
    }

    // ─────────────────── 全部路线 ───────────────────

    public List<PlayerRoute> allRoutes() {
        return new ArrayList<>(routes.values());
    }

    /**
     * **未施工**的路线（{@link net.shiroha233.roadweaver.route.RouteStatus#DRAFT}）。
     *
     * <p>用户要求：施工完成的路线不再预览显示，只保留还没施工的；
     * 多条未施工的要同时显示（各用不同颜色）。渲染侧只画这些。</p>
     *
     * <p>按创建时间升序 —— 保证配色分配稳定（不会每帧换色）。</p>
     *
     * <h2>★ 审计 R3-P3-6：结果按「路线表版本号」缓存</h2>
     * 本方法由渲染线程**每帧**调用，而原实现每帧都 new ArrayList +
     * new Comparator + 排序。路线数量是个位数、且绝大多数帧内没有变化，
     * 所以缓存到「有编辑发生时」即可 —— 用一个单调递增的 revision 判断，
     * 比逐条比较路线对象更便宜。</p>
     */
    public List<PlayerRoute> pendingRoutes() {
        long rev = routesRevision;
        List<PlayerRoute> cached = pendingCache;
        if (cached != null && pendingCacheRevision == rev) {
            return cached;
        }
        List<PlayerRoute> out = new ArrayList<>();
        for (PlayerRoute r : routes.values()) {
            if (r != null
                    && r.status() == net.shiroha233.roadweaver.route.RouteStatus.DRAFT
                    && !r.isEmpty()) {
                out.add(r);
            }
        }
        out.sort(java.util.Comparator.comparingLong(PlayerRoute::createdAtEpochMs));
        // 不可变快照：渲染线程拿到后不会被后续编辑改动
        List<PlayerRoute> snapshot = List.copyOf(out);
        pendingCache = snapshot;
        pendingCacheRevision = rev;
        return snapshot;
    }

    /** 路线表版本号：任何编辑操作都会 +1（见 {@link #bumpRevision()}）。 */
    private volatile long routesRevision;
    private volatile List<PlayerRoute> pendingCache;
    private volatile long pendingCacheRevision = -1L;

    /** 标记路线表已变化，使缓存失效。 */
    private void bumpRevision() {
        routesRevision++;
    }

    public int routeCount() {
        return routes.size();
    }

    public PlayerRoute byId(UUID id) {
        return routes.get(id);
    }

    public void removeRoute(UUID id) {
        routes.remove(id);
        if (id != null && id.equals(editingId)) {
            editingId = routes.isEmpty() ? null : routes.keySet().iterator().next();
        }
        bumpRevision();
    }

    /** 丢弃当前路线（重新画）。 */
    public void discardCurrent() {
        if (editingId != null) {
            routes.remove(editingId);
            editingId = routes.isEmpty() ? null : routes.keySet().iterator().next();
        }
        bumpRevision();
    }

    public void clearAll() {
        routes.clear();
        editingId = null;
        active = false;
        selectedPointIndex = -1;
        // ★ 审计 R3-P3-8：跨世界切换必须连撤销栈一起清。
        // 否则「编辑模式中退出世界 → 进另一世界」后按 Z，
        // 旧世界的快照会被 pop 出来，把旧世界的路线复活到新世界。
        mapUndo.clear();
        bumpRevision();
    }

    // ─────────────────── 路线生命周期入口（★ 审计 R3-P2-8）───────────────────
    //
    // 原先 discardCurrent / removeRoute / RouteBuildService.cancel / isEditable
    // 全都是**死代码**（全仓无调用者），于是：
    //   · 「新建路线」不存在 ⇒ 第二条路线永远画不出来（多路线配色不可达）
    //   · 「删除路线」不存在 ⇒ 画错只能改存档
    //   · READY 路线无法退回编辑 ⇒ 一旦提交就永久锁死
    // 下面这一组把入口补齐，界面（工具栏 / HUD）接上即可。

    /**
     * 施工成功后：把编辑对象切到一条**新的空路线**（用户 2026-09-25 要求）。
     *
     * <h2>为什么不是「删掉刚提交的那条」</h2>
     * 已提交（READY）的路线必须**留在 {@code routes} 表里** —— 重进世界时
     * {@code ClientInit} 会调 {@code RouteBuildService.rebuildRegistryFromStorage}
     * 从它重建「连接 → 路线」登记表与 {@code PlannedPathCache}（桥隧语义、覆盖高度
     * 都依赖它）。真删掉的话，重进世界后就找不回来了。
     *
     * <h2>那"清除"体现在哪</h2>
     * <ul>
     *   <li>编辑对象换成新建的空路线 ⇒ 画布上立刻没有线了，可以直接画下一条</li>
     *   <li>READY 路线本来就不参与世界内渲染（{@code pendingRoutes} 只收 DRAFT）</li>
     *   <li>施工进度仍能在 HUD 上看到（按 routeId 汇总连接状态）</li>
     * </ul>
     *
     * <p>本方法只在**服务端线程**被 {@code submitForBuild} 的 lambda 调用。</p>
     */
    private void autoSwitchToNewRouteAfterSubmit() {
        PlayerRoute created = PlayerRoute.create("路线 " + (routes.size() + 1));
        routes.put(created.id(), created);
        editingId = created.id();
        selectedPointIndex = -1;
        // 撤销栈必须清 —— 否则在新空路线上按 Ctrl+Z，会把刚提交那条的快照
        // pop 出来（它的 id 与新路线不同，update() 会把它塞回表并改回编辑对象），
        // 表现为"刚施工完的线又冒出来了"。
        mapUndo.clear();
        markDirty();
        bumpRevision();
    }

    /**
     * **仅测试用**：从测试代码驱动「施工成功后自动切换编辑对象」这一行为。
     *
     * <p>真实的触发点是 {@code submitForBuild} 投递到服务端线程的 lambda，
     * 而那个 lambda 需要 {@code ServerLevel}（跑不了单元测试）。
     * 这里把同一段逻辑暴露出来，让回归测试能钉住「READY 留下、编辑对象换新」。</p>
     */
    public void autoSwitchAfterSubmitForTest() {
        autoSwitchToNewRouteAfterSubmit();
    }

    /**
     * 新建一条空路线并切换为编辑对象（★ R3-P2-8）。
     *
     * @return 新路线
     */
    public PlayerRoute createNew() {
        PlayerRoute created = PlayerRoute.create("路线 " + (routes.size() + 1));
        routes.put(created.id(), created);
        editingId = created.id();
        selectedPointIndex = -1;
        markDirty();
        bumpRevision();
        return created;
    }

    /**
     * 删除指定路线（★ R3-P2-8）。
     *
     * <p>若这条路线已经提交过施工，调用方应同时调
     * {@code RouteBuildService.cancel(level, route)} 撤掉还没开工的连接 ——
     * 那需要 ServerLevel，所以放在 {@link #deleteRoute(Minecraft, UUID)} 里统一做。</p>
     *
     * @return 是否确实删掉了一条
     */
    public boolean deleteRoute(UUID id) {
        if (id == null || routes.remove(id) == null) {
            return false;
        }
        if (id.equals(editingId)) {
            // ★ R3-P3-7：不再用迭代器取「随便一条」，取创建时间最新的
            editingId = null;
            long newest = Long.MIN_VALUE;
            for (PlayerRoute r : routes.values()) {
                if (r != null && r.createdAtEpochMs() > newest) {
                    newest = r.createdAtEpochMs();
                    editingId = r.id();
                }
            }
        }
        selectedPointIndex = -1;
        markDirty();
        bumpRevision();
        return true;
    }

    /**
     * 删除路线并**连带撤掉尚未开工的施工连接**（★ R3-P2-8）。
     *
     * <p>只移除还没开始施工的连接 —— 已经建了一半的不能凭空撤掉
     * （{@code RouteBuildService.cancel} 内部就是这么做的）。</p>
     *
     * @return 是否确实删掉了路线
     */
    public boolean deleteRoute(Minecraft mc, UUID id) {
        PlayerRoute target = id == null ? null : routes.get(id);
        if (target == null) {
            return false;
        }
        // 先撤施工（需要服务端 level），再删本地
        ServerLevel level = resolveServerLevel(mc);
        if (level != null && target.status() != RouteStatus.DRAFT) {
            // ★ 2026-09-26 修 WorkBuddy R-3：**必须投递到服务端线程**。
            //
            // 原先直接在**客户端渲染线程**调 RouteBuildService.cancel() ——
            // 它内部对 WorldDataProvider 的连接表做「读 → 改 → 写」，
            // 而服务端线程同时在 RoadGenerationService.tick 里改同一份表。
            // 这是典型的丢更新竞争（与 submitForBuild 已修复的那类完全同类，
            // 当时漏了这条路径）。
            //
            // 修法与 submitForBuild 一致：拿到 level 后 server.execute，
            // 在服务端线程里做服务端状态变更。
            final ServerLevel levelRef = level;
            final PlayerRoute targetRef = target;
            var server = level.getServer();
            if (server != null) {
                server.execute(() -> {
                    try {
                        net.shiroha233.roadweaver.route.RouteBuildService
                                .cancel(levelRef, targetRef);
                        net.shiroha233.roadweaver.route.RouteBuildRegistry
                                .removeRoute(levelRef, targetRef.id());
                    } catch (RuntimeException | LinkageError e) {
                        // 撤销失败不阻止本地删除（玩家意图是删掉它，残留连接由 reconcile 处理）
                    }
                });
            }
        }
        boolean ok = deleteRoute(id);
        if (ok && level != null) {
            // saveAll 写的就是「删掉之后」的完整集合，不需要再单独 remove
            // （RouteStorage.remove 会再 loadAll 一次，是多余的往返）。
            RouteStorage.saveAll(level, routes);
            net.shiroha233.roadweaver.route.RouteBuildProgress.invalidateCache();
        }
        return ok;
    }

    /**
     * 把当前路线退回 DRAFT 重新编辑（★ R3-P2-8 / R3-P1-2 配套）。
     *
     * <p>提交施工后路线进入 READY，原先**没有任何退回入口** ——
     * 玩家改不了也删不掉。现在工具栏 / HUD 可以调这个把它放回编辑态。
     * （已入队的施工连接不受影响，仍由现有管线按原路径施工。）</p>
     *
     * @return 是否确实改成了 DRAFT
     */
    public boolean reopenForEdit() {
        PlayerRoute r = current();
        if (r == null || r.status() == RouteStatus.DRAFT) {
            return false;
        }
        update(r.withStatus(RouteStatus.DRAFT));
        // ★ R3-P2-9：退回编辑后选中项清掉，避免指向已不存在的点
        selectedPointIndex = -1;
        return true;
    }

    // ─────────────────── ★ UX-6 / UX-10：提交追踪 ───────────────────

    /**
     * **最近提交的路线摘要**（★ UX-10，用户 2026-09-25）。
     *
     * <h2>为什么需要</h2>
     * 0.8.388 起「施工成功后画布自动清空」（切到新空路线）——
     * 这解决"还得手动点删除"，但带来新问题：**画布空了，玩家找不到刚提交的那条路**。
     * 所以这里记住最近一次提交的信息，在 HUD 上显示，让
     * 「画布清空」不等于「路线消失」。
     */
    public record LastSubmitted(String name, int points, int segments, long atEpochMs) {
    }

    private volatile LastSubmitted lastSubmitted;

    /** 记录最近一次成功提交（由 submitForBuild 的 lambda 调用）。 */
    public void noteSubmitted(PlayerRoute route, int added) {
        if (route == null) {
            return;
        }
        lastSubmitted = new LastSubmitted(
                route.name() == null ? "" : route.name(),
                route.pointCount(),
                added,
                System.currentTimeMillis());
    }

    /** 最近提交的摘要（没有则返回 null）。 */
    @Nullable
    public LastSubmitted lastSubmitted() {
        return lastSubmitted;
    }

    /**
     * 某条路线的**提交次数**（★ UX-6：重新编辑后显示"第几次提交"）。
     *
     * <p>背景：{@code reopenForEdit()} 只改状态、**UUID 不变**，
     * 玩家反复「施工 → 重新编辑 → 改 → 再提交」时 UI 看不出这是第几轮。
     * 这里按 routeId 累计（进程内，不持久化）。</p>
     */
    private final Map<UUID, Integer> submitCounts = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记一次提交并返回累计次数（从 1 开始）。 */
    public int noteSubmit(PlayerRoute route) {
        if (route == null || route.id() == null) {
            return 0;
        }
        return submitCounts.merge(route.id(), 1, Integer::sum);
    }

    /** 该路线已提交过几次（0 = 还没提交过）。 */
    public int submitCount(PlayerRoute route) {
        if (route == null || route.id() == null) {
            return 0;
        }
        Integer n = submitCounts.get(route.id());
        return n == null ? 0 : n;
    }

    // ─────────────────── 选中（跨界面共享）───────────────────

    public int selectedPointIndex() {
        return selectedPointIndex;
    }

    public void setSelectedPointIndex(int index) {
        this.selectedPointIndex = index;
    }

    public void clearSelection() {
        this.selectedPointIndex = -1;
    }

    // ─────────────────── 路线变更（两个界面共用）───────────────────
    //
    // 这些操作放在会话层而不是各界面里，因为地图界面和世界内编辑器
    // 都要改同一条路线。放两处会出现「改完不同步」的 bug。

    /** 拖动挪点（只改 XZ，保持 Y）。 */
    public void movePointXZ(int index, int x, int z) {
        PlayerRoute r = current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        // ★ 审计 R3-P2-12：x/z 必须钳到世界边界（原先只钳 Y）
        update(r.replacePoint(index, r.point(index).withXZ(clampXZ(x), clampXZ(z))));
    }

    /**
     * 挪点 —— 带撤销记录（时间窗分组）。
     *
     * <p>世界内按住方向键挪位是**没有结束事件的连续操作**，
     * 用时间窗把连按归为一次撤销点，避免一次挪位塞满撤销栈。</p>
     */
    public void movePointXZUndoable(int index, int x, int z, RouteUndoStack undo) {
        PlayerRoute r = current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        if (undo != null) {
            long now = System.currentTimeMillis();
            if (now - lastMoveUndoAtMs >= 600L) {
                undo.record(r);
                lastMoveUndoAtMs = now;
            }
        }
        // ★ 审计 R3-P2-12：x/z 钳到世界边界
        update(r.replacePoint(index, r.point(index).withXZ(clampXZ(x), clampXZ(z))));
    }

    private volatile long lastMoveUndoAtMs;

    /**
     * 结束当前的挪位撤销时间窗（松开 Ctrl 时调用）。
     *
     * <p>把时间窗推到「很久以前」，使下一次挪点必然重新记录撤销点 ——
     * 这样「按住 Ctrl 调一次 → 松开 → 再按住 Ctrl 调一次」是**两个**
     * 可独立撤销的手势，而不是被合并成一个。</p>
     */
    public void endMoveUndoWindow() {
        lastMoveUndoAtMs = 0L;
    }

    /**
     * 改高度。
     *
     * <h2>★ 审计 P2-11：必须钳制到世界高度范围</h2>
     * 滚轮是**每帧可触发**的（按住/连滚），加 Shift 还有 ×8 加速 ——
     * 不钳制的话能把 Y 滚到 ±2000+。注入后 {@code targetY} 越界，
     * 施工时要么静默失败，要么在世界上下限附近做无意义的大工程。
     *
     * <p>范围取主世界的建造高度限制（手绘路线本来就只支持主世界，
     * 见 {@code RouteBuildService.submit} 的维度检查）。</p>
     */
    public void setPointY(int index, int y) {
        PlayerRoute r = current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        update(r.replacePoint(index, r.point(index).withY(clampY(y))));
    }

    /** 世界高度下限（主世界）。 */
    private static final int Y_MIN = -64;
    /** 世界高度上限（主世界）。 */
    private static final int Y_MAX = 320;

    /**
     * 水平坐标的绝对值上限（★ 审计 R3-P2-12）。
     *
     * <p>原实现**只钳 Y**，x/z 没有任何边界检查。玩家用方向键把点推出
     * 世界边界（±30,000,000）后，地形采样与施工都会按越界坐标走 ——
     * 轻则采样失败、重则让区块生成器做无意义的巨大工程。
     * 取原版的世界边界值。</p>
     */
    public static final int XZ_LIMIT = 29_999_984;

    /** 把坐标钳制到世界边界内（★ 审计 R3-P2-12）。 */
    public static int clampXZ(int v) {
        return Math.max(-XZ_LIMIT, Math.min(XZ_LIMIT, v));
    }

    /** 把高度钳制到合法范围。 */
    public static int clampY(int y) {
        return Math.max(Y_MIN, Math.min(Y_MAX, y));
    }

    /** 改某一段的类型。 */
    public void setSegmentKind(int index, net.shiroha233.roadweaver.route.RouteKind kind) {
        PlayerRoute r = current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        update(r.withSegmentKind(index, kind));
    }

    /** 删除某个控制点。 */
    public void removePoint(int index) {
        PlayerRoute r = current();
        if (r == null || index < 0 || index >= r.pointCount()) {
            return;
        }
        update(r.removePoint(index));
    }

    /** 在第 index 个点之后插入一个点。 */
    public int insertPointAfter(int index, net.minecraft.core.BlockPos pos) {
        PlayerRoute r = current();
        if (r == null) {
            return -1;
        }
        PlayerRoute next = r.insertAfter(index, pos);
        update(next);
        return Math.min(index + 1, next.pointCount() - 1);
    }

    // ─────────────────── 工具条 ───────────────────

    public net.shiroha233.roadweaver.route.RouteKind currentKind() {
        return currentKind;
    }

    public void setCurrentKind(net.shiroha233.roadweaver.route.RouteKind kind) {
        if (kind != null) {
            this.currentKind = kind;
        }
    }

    public void cycleKind() {
        this.currentKind = this.currentKind.next();
    }

    // ─────────────────── 落盘 / 读盘 ───────────────────

    /**
     * 提交当前路线进入施工（玩家点「确定」）。
     *
     * <h2>★ 线程安全：必须切到服务端线程执行</h2>
     * 本方法由**客户端渲染/输入线程**调用（点按钮、按 Enter 都在那一侧），
     * 但它要改的是服务端数据（{@code WorldDataProvider.setStructureConnections}
     * 会写文件）。虽然那个方法带 {@code synchronized}，但
     * {@code RoadGenerationService.tick()} 也在服务端线程读写同一份状态，
     * 跨线程直接改会与 tick 的「读-改-写」序列竞争（可能丢掉刚加进的连接）。
     *
     * <p>所以这里用 {@code server.execute(...)} 把真正的工作投递到服务端主线程，
     * 与 {@code tick} 串行执行。单人游戏下这是同一个进程内的集成服务器，
     * 延迟只有 1 tick，玩家感知不到。</p>
     *
     * @return 实际加入施工的连接数；-1 表示当前环境不支持（多人或投递失败）
     */
    public int submitForBuild(Minecraft mc) {
        PlayerRoute route = current();
        if (route == null || !route.isDrawable()) {
            return 0;
        }
        ServerLevel level = resolveServerLevel(mc);
        if (level == null) {
            return -1;
        }
        var server = level.getServer();
        if (server == null) {
            return -1;
        }
        try {
            final PlayerRoute target = route;
            // ★ 审计 P2-6：把 level 先捕获进 lambda，lambda 内不再碰 mc。
            //
            // 原实现在 lambda 里调 saveToWorld(mc) → resolveServerLevel(mc)
            // → mc.level.dimension()。而 lambda 跑在**服务端线程**上，
            // mc.level 是客户端字段 —— 跨线程读客户端状态是不安全的。
            final ServerLevel resolvedLevel = level;
            // 投递到服务端主线程，与 RoadGenerationService.tick 串行
            server.execute(() -> {
                int added;
                try {
                    added = net.shiroha233.roadweaver.route.RouteBuildService.submit(resolvedLevel, target);
                    // ★ 审计 R3-P1-2：**只有真的提交了分段才转 READY**。
                    //
                    // 原实现无条件写 READY，于是 added == 0 的路线会进入
                    // 「永久失联」状态：
                    //   · 世界内不渲染（pendingRoutes 只收 DRAFT）
                    //   · 没有退回 DRAFT 的入口
                    //   · 没有删除入口
                    // 触发很简单：地图上连点两下同一格（两点重合 → 平滑结果只有 1 点
                    // → 注入计划为空 → added = 0），或者对已提交的线再点一次施工。
                    // 而 saveAll 已经把 status=ready 写进存档，重启也还是 READY。
                    if (added > 0) {
                        // ★ UX-6 / UX-10：记录本次提交（版本计数 + 最近提交摘要）
                        noteSubmit(target);
                        noteSubmitted(target, added);
                        update(target.withStatus(RouteStatus.READY));
                        // ★ 用户 2026-09-25：「点了施工后直接清除当前线路选择，
                        //   由后台慢慢生成，要不我还得手动点删除才行」。
                        //
                        // 施工成功后自动把编辑对象切到一条**新的空路线** ——
                        // 效果上等于「线从画布上清掉了」，玩家可以直接画下一条，
                        // 不必手动点「删除」。
                        //
                        // ⚠️ 为什么不是真删掉这条路线：
                        //   重进世界时 ClientInit 会调
                        //   RouteBuildService.rebuildRegistryFromStorage(level, routes)
                        //   从存档重建「连接→路线」登记表与 PlannedPathCache。
                        //   真删掉的话，重进世界后桥隧语义/覆盖高度就找不回来了。
                        //   所以已提交的路线必须**留在 routes 表里**（status=READY），
                        //   只是不再作为编辑对象 —— READY 路线本来就不在世界里渲染
                        //   （pendingRoutes 只收 DRAFT），视觉上就是"清掉了"。
                        autoSwitchToNewRouteAfterSubmit();
                        // 直接用捕获的 level 保存，不经过 mc
                        if (RouteStorage.saveAll(resolvedLevel, routes)) {
                            dirty = false;
                            lastAutoSaveAtMs = System.currentTimeMillis();
                            saveFailed = false;
                        } else {
                            saveFailed = true;
                        }
                    }
                    // added <= 0：保持 DRAFT（仍可见、仍可编辑、仍可删），
                    // 由 UI 通过 consumeSubmitResult() 给出明确失败提示。
                    net.shiroha233.roadweaver.route.RouteBuildProgress.invalidateCache();

                    // ★ 2026-09-25：**这里不能马上强制加载区块**。
                    //
                    // 曾打算在此调 RouteChunkPreloader —— 但时序是错的：
                    // submit() 只写了 PLANNED 连接，RoadData 要等
                    // RoadGenerationService.tick 推进后才产出。此时就强制加载，
                    // LoadedChunkRoadBackfill 会拿到**空计划**并把区块记成
                    // 「终态无需施工」⇒ 之后**永久跳过**，比原来的问题更糟。
                    //
                    // 正确挂钩点是「道路数据生成完成」那一刻 ——
                    // 见 RoadGenerationService 里调 LoadedChunkRoadBackfill 的位置，
                    // 那里已改为对手绘路线走强制加载版本。
                } catch (Throwable t) {
                    added = -1;
                }
                // volatile 写：客户端界面下一帧就能读到
                pendingSubmitResult = added;
            });
            // 乐观返回预估段数（真实值稍后由 consumeSubmitResult 取）
            return estimateSegmentCount(target);
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** 预估能拆出几段（仅用于即时反馈；真实值以服务端结果为准）。 */
    private static int estimateSegmentCount(PlayerRoute route) {
        int n = 0;
        for (int i = 0; i + 1 < route.pointCount(); i++) {
            if (!route.point(i).pos().equals(route.point(i + 1).pos())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 服务端线程算完的真实结果。
     *
     * <p>★ 2026-09-26 修 WorkBuddy R-9：原先用 {@code -1} 同时表示
     * 「还没算完」与「提交抛异常」，两者不可区分 ⇒ 消费者拿到 -1 时
     * 无法判断该"继续等"还是"报失败"，异常会被静默。
     * 现在用 {@link #NOT_READY}（{@code Integer.MIN_VALUE}）表示"还没算完"，
     * 与业务值彻底分开 —— 与 {@code RouteWorldEditor.lastSubmitResult()}
     * 的约定一致。</p>
     *
     * <p>volatile：跨线程读取。</p>
     */
    private volatile int pendingSubmitResult = NOT_READY;

    /** "服务端还没算完"哨兵值（与业务结果 -1/0/N 区分开）。 */
    private static final int NOT_READY = Integer.MIN_VALUE;

    /**
     * 服务端线程算完后的真实结果；**没有新结果时返回 {@link #NOT_READY}**。
     *
     * <p>读取即消费（清空），避免同一条结果被界面反复显示。</p>
     *
     * <p>★ 2026-09-26 修 R-9：返回值有三种语义，消费者必须分开判：</p>
     * <pre>
     *   NOT_READY (Integer.MIN_VALUE)  还没算完 → 继续等，别当成失败
     *   -1                             提交时抛异常 → 明确的失败
     *   0                              没有可施工的分段
     *   N &gt; 0                          成功提交了 N 段
     * </pre>
     */
    public int consumeSubmitResult() {
        int v = pendingSubmitResult;
        pendingSubmitResult = NOT_READY;
        return v;
    }

    /**
     * 从服务端存档载入路线（进世界时调用一次）。
     *
     * <p>只在**单人游戏**下直接读；多人下由服务端下发（后续接网络层）。
     * 拿不到 ServerLevel 就静默跳过 —— 编辑功能在没有持久化时仍可当次使用。</p>
     */
    public void loadFromWorld(Minecraft mc) {
        ServerLevel level = resolveServerLevel(mc);
        // ★ 审计 R3-P2-7：拿不到 level（多人游戏）也必须**先清空**。
        //
        // 原实现在 level == null 时直接 return，于是「单人画线 → 退出 →
        // 进多人服务器」会保留上个世界/存档的路线并照样显示、照样可编辑 ——
        // 玩家在服务器上看到的是别的存档的线，改了半天又不会落盘（退出即丢）。
        //
        // 先清空再判断，保证「进任何世界都从干净状态开始」。
        clearAll();
        if (level == null) {
            // 多人：手绘路线需要走网络同步，属于后续工作。
            // 明确禁用编辑（而不是让玩家对着"能画但不保存"发呆）。
            return;
        }
        try {
            Map<UUID, PlayerRoute> loaded = RouteStorage.loadAll(level);
            // ★ 审计 R3-P3-7：整体替换而不是 clear + putAll。
            //
            // clearAll() 已清空，这里逐个 put 就行了 —— 渲染线程不会看到
            // 「已被清空但还没填回来」的中间态（clear + putAll 之间渲染
            // 正好跑一帧的话，玩家会看到路线闪一下全部消失）。
            //
            // editingId 取**最近编辑过**的那条（createdAt 最大），
            // 而不是 keySet().iterator().next() —— ConcurrentHashMap 的
            // 迭代顺序不确定，原来每次进世界编辑对象都可能不一样。
            PlayerRoute newest = null;
            for (PlayerRoute r : loaded.values()) {
                if (r != null && r.id() != null) {
                    routes.put(r.id(), r);
                    if (newest == null || r.createdAtEpochMs() > newest.createdAtEpochMs()) {
                        newest = r;
                    }
                }
            }
            editingId = newest == null ? null : newest.id();
        } catch (RuntimeException e) {
            // 读失败不影响当前编辑
        }
    }

    /**
     * 多人游戏下是否禁用编辑（★ 审计 R3-P2-7）。
     *
     * <p>{@link #resolveServerLevel} 只对单人返回非 null，所以这个判断
     * 等价于「当前是不是单人」。界面据此给出明确提示，
     * 而不是让玩家画完才发现存不下来。</p>
     */
    public boolean isMultiplayerDisabled() {
        return resolveServerLevel(Minecraft.getInstance()) == null;
    }

    /**
     * 把当前所有路线写回存档。
     *
     * <h2>★ 审计 P1-8：只有真正写成功才清 dirty</h2>
     * 原实现在 {@code saveAll} 返回后**无条件** {@code dirty = false}。
     * 而 {@code saveAll} 现在会返回写盘是否成功 —— 失败时保持 dirty，
     * 下一轮 autosave 会重试，不会静默丢数据。
     */
    public void saveToWorld(Minecraft mc) {
        ServerLevel level = resolveServerLevel(mc);
        if (level == null) {
            return;
        }
        try {
            if (RouteStorage.saveAll(level, routes)) {
                lastAutoSaveAtMs = System.currentTimeMillis();
                dirty = false;
                // ★ 审计 R3-P2-15：成功后清掉失败标志
                saveFailed = false;
            } else {
                // ★ 审计 R3-P2-15：失败必须让玩家看见。
                // 原先只在日志里记 error，玩家以为已保存，退出后全丢。
                // 这里置标志，HUD 显示「保存失败，正在重试」。
                saveFailed = true;
            }
            // 写失败：保持 dirty，等下一次 autosave 重试
        } catch (RuntimeException e) {
            // 写失败已在 RouteStorage 内记日志，这里不额外处理
            saveFailed = true;
        }
    }

    /**
     * 上一次保存是否失败（★ 审计 R3-P2-15）。
     *
     * <p>磁盘满 / 文件被占用 / 权限拒绝时 {@link RouteStorage#saveAll} 返回 false，
     * 但玩家界面原先毫无提示。HUD 现在读这个标志显示「保存失败，正在重试」。
     * 自动保存会持续重试，成功一次即自动清除。</p>
     */
    public boolean isSaveFailed() {
        return saveFailed;
    }

    private volatile boolean saveFailed;

    // ─────────────────── 自动保存 ───────────────────
    //
    // 只在「离开世界」时保存是有风险的：玩家画一小时线、游戏崩溃或强退，
    // 全部白画。而用户要的流程是「画完跑图看一遍再调」，中途本来就不会退出世界。
    //
    // 所以做脏标记 + 定时落盘：编辑动作置 dirty，每 AUTOSAVE_INTERVAL_MS
    // 且确实有改动时才写一次。这样既不丢数据，也不会每帧写盘。

    /** 自动保存间隔。 */
    private static final long AUTOSAVE_INTERVAL_MS = 15_000L;

    private volatile boolean dirty;
    private volatile long lastAutoSaveAtMs;

    /** 标记有未保存改动（每次编辑后调用）。 */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * 每 tick 调用：到点了且确实有改动才自动落盘。
     *
     * <p>落盘走 {@code RouteStorage} 的原子写（先写 .tmp 再 ATOMIC_MOVE），
     * 所以中途崩也不会写出半个文件。</p>
     */
    public void tickAutoSave(Minecraft mc) {
        if (!dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoSaveAtMs < AUTOSAVE_INTERVAL_MS) {
            return;
        }
        saveToWorld(mc);
    }

    /** 取当前服务端 level（拿不到返回 null）——供施工进度统计等使用。 */
    @Nullable
    public ServerLevel serverLevelOrNull() {
        return resolveServerLevel(Minecraft.getInstance());
    }

    /**
     * 取用于手绘路线的服务端 level。
     *
     * <h2>★ 审计 P1-5：固定取主世界，不跟随当前维度</h2>
     * 原实现返回 {@code server.getLevel(mc.level.dimension())}（**当前**维度），
     * 而 {@code saveToWorld} 会把**整个 session 的 routes** 写进那个维度的
     * {@code routes.json}。但 {@code PlayerRoute} 没有维度字段 —— 于是：
     * <pre>
     *   在主世界画线 → 进下界 → 触发一次 autosave
     *     ⇒ 主世界路线的副本被写进 the_nether/routes.json（维度文件互相污染）
     * </pre>
     * 而且 RoadWeaver 的施工本来就**只处理主世界**（见 P1-6），
     * 所以手绘路线只可能在主世界有意义。这里固定返回 overworld 即可：
     * 既修掉跨维度污染，也顺带去掉了 P1-6 的僵尸连接来源。
     */
    @Nullable
    private static ServerLevel resolveServerLevel(Minecraft mc) {
        if (mc == null || mc.level == null) {
            return null;
        }
        // 单人：直接用集成服务器的主世界 level（内置服务器由客户端进程托管）。
        // 多人：getSingleplayerServer() 返回 null，这里就跳过 ——
        // 手绘路线在多人下需要走网络同步，属于后续工作。
        try {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            return server.overworld();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
