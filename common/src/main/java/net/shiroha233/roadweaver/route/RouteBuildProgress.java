/* 文件职责：手绘路线的施工进度。 */
package net.shiroha233.roadweaver.route;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import org.jetbrains.annotations.Nullable;

/**
 * 手绘路线的**施工进度统计**。
 *
 * <h2>为什么需要它</h2>
 * 提交施工时路线进入 {@link RouteStatus#READY}，但**之后没人再更新状态** ——
 * 玩家会看到「已提交 N 段」然后就没了，不知道建到哪、建完没有。
 *
 * <p>而施工的进度其实**已经存在**于每条 {@link StructureConnection} 的
 * {@link ConnectionStatus} 里（现有管线在维护它）。所以这里不新增状态存储，
 * 而是**实时从连接状态汇总**出来 —— 好处是不会与现有状态机不同步。</p>
 *
 * <h2>为什么不做成「写回路线状态」</h2>
 * 如果能实时汇总，就不需要把 DONE/BUILDING 写进存档。
 * 写回反而有两个坑：① 每次刷新都要写盘；② 汇总值与存档值可能不一致。
 * 汇总是无状态、无副作用的，更稳。
 */
public final class RouteBuildProgress {

    private RouteBuildProgress() {
    }

    /**
     * 一条路线的施工进度快照。
     *
     * @param total      提交的连接总数
     * @param planned    还在排队
     * @param generating 正在建
     * @param completed  已建成
     * @param failed     失败
     * @param removed    已被判为冗余而移除（两端已被别的路连通）
     */
    public record Snapshot(int total, int planned, int generating,
                           int completed, int failed, int removed) {

        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0);

        /** 还在管线里的（含排队与施工中）。 */
        public int active() {
            return planned + generating;
        }

        /** 是否全部有结论（成功/失败/冗余移除）。 */
        public boolean isSettled() {
            return total > 0 && active() == 0;
        }

        /** 是否全部建成（其余被判冗余也算成功：路已经存在了）。 */
        public boolean isDone() {
            return isSettled() && failed == 0;
        }

        /** 进度文本，给 HUD / 界面显示。 */
        public String describe() {
            if (total == 0) {
                return "尚未提交施工";
            }
            if (!isSettled()) {
                return "施工中 " + (completed + removed) + "/" + total
                        + "（排队 " + planned + "，在建 " + generating + "）";
            }
            if (failed == 0) {
                return "✔ 已完成 " + total + " 段";
            }
            if (completed + removed == 0) {
                return "✘ 全部失败（" + failed + " 段）";
            }
            return "⚠ 完成 " + (completed + removed) + " 段，失败 " + failed + " 段";
        }

        /** 0.0 ~ 1.0 的完成比例（含冗余移除，因为那也是"不用建了"）。 */
        public float ratio() {
            if (total == 0) {
                return 0.0F;
            }
            return Math.min(1.0F, (completed + removed + failed) / (float) total);
        }
    }

    /**
     * **统计所有手绘路线中「还没结论」的连接数**（★ 2026-09-26 修 WorkBuddy W-1/I-1）。
     *
     * <h2>为什么需要它（而不是数 QUEUE）</h2>
     * 原先 {@code RoadGenerationService.countPendingPlayerRouteConnections}
     * 遍历的是调度队列 {@code QUEUE}，但 **QUEUE 只在
     * {@code pauseScheduling() == false} 时才由 {@code refreshQueue} 填充** ——
     * 于是形成死锁：
     * <pre>
     *   玩家点施工（确认框是 Screen）⇒ isScreenPauseActive() 真
     *     ⇒ pauseScheduling() 真 ⇒ tick 在 refreshQueue 之前就 return
     *     ⇒ QUEUE 永远空 ⇒ 计数 0 ⇒ 不豁免 ⇒ 循环
     * </pre>
     * 即「开着地图盯进度」恰恰让施工不动。
     *
     * <h2>为什么用 provider 口径</h2>
     * {@code WorldDataProvider.getStructureConnections} 是**连接表的真源**，
     * 与暂停状态**完全无关**。而且它天然包含状态为 {@code GENERATING} 的连接
     * （已出队、正在建）—— 这正好补上了原先 javadoc 承诺却从未实现的
     * 「把正在施工的也算进去」（W-3）。
     *
     * <h2>与 {@link #snapshot} 的关系</h2>
     * 同一套遍历口径，只是不按 routeId 过滤（统计**全部**手绘路线的）。
     * 这样「待施工数」只有一个真源，不会再出现两处口径不一致。
     *
     * @return 状态为 PLANNED 或 GENERATING 的手绘连接数
     */
    public static int countActivePlayerRouteConnections(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        int n = 0;
        try {
            var all = WorldDataProvider.getInstance().getStructureConnections(level);
            if (all == null) {
                return 0;
            }
            for (StructureConnection c : all) {
                if (c == null) {
                    continue;
                }
                ConnectionStatus st = c.status();
                // 只数"还没结论"的 —— PLANNED（排队中）/ GENERATING（在建）
                if (st != ConnectionStatus.PLANNED && st != ConnectionStatus.GENERATING) {
                    continue;
                }
                // 只数手绘路线的（自动规划的不算优先级）
                if (RouteBuildRegistry.isPlayerRoute(level, c)) {
                    n++;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        return n;
    }

    /**
     * 汇总一条路线的施工进度。
     *
     * <p>数据源是「当前所有连接 + 连接→路线登记表」。已完成的连接可能已被
     * 归档到别处而不再出现在 pending 列表里，所以 {@code removed} 里也统计
     * 「登记过但当前不在列表里」的那些（说明它们已结算或已归档）。</p>
     */
    public static Snapshot snapshot(ServerLevel level, @Nullable UUID routeId) {
        if (level == null || routeId == null) {
            return Snapshot.EMPTY;
        }
        int planned = 0, generating = 0, completed = 0, failed = 0;
        int registered = 0;

        var all = WorldDataProvider.getInstance().getStructureConnections(level);
        if (all != null) {
            for (StructureConnection c : all) {
                RouteBuildRegistry.Entry e = RouteBuildRegistry.lookup(level, c);
                if (e == null || !routeId.equals(e.routeId())) {
                    continue;
                }
                registered++;
                switch (c.status()) {
                    case PLANNED -> planned++;
                    case GENERATING -> generating++;
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case PROTECTED_GAP, FILTERED -> completed++;   // 有结论，不再重试
                }
            }
        }

        // 登记过但已不在列表里的：说明被 reconcile 判为冗余（两端已连通）
        // 或已归档 —— 两种情况都算「不用再建」。
        int totalRegistered = RouteBuildRegistry.countForRoute(level, routeId);
        int removed = Math.max(0, totalRegistered - registered);

        return new Snapshot(totalRegistered, planned, generating, completed, failed, removed);
    }

    /**
     * 便捷：直接给会话里当前路线的进度。
     *
     * <h2>★ 审计 R3-P3-10：这个便捷方法**已删除**</h2>
     * 它原先 import 了客户端类 {@code RouteEditorSession}（内含 {@code Minecraft}）——
     * 而本类位于 {@code route} 包、被服务端路径引用。一旦有人从服务端调用它，
     * 就是 {@code NoClassDefFoundError}。
     *
     * <p>现在把「取当前路线」这一步交回**调用方**（它本来就在客户端侧，
     * 例如 {@code RouteMapRenderer}），本类只保留纯粹的
     * {@link #snapshot(ServerLevel, UUID)} / {@link #cachedSnapshot(ServerLevel, UUID)}
     * —— 服务端与客户端都能安全调，不再有任何客户端类依赖。</p>
     *
     * <p>调用方写法：</p>
     * <pre>
     *   var session = RouteEditorSession.get();
     *   var r = session.current();
     *   var level = session.serverLevelOrNull();
     *   var progress = (r == null || level == null)
     *       ? RouteBuildProgress.Snapshot.EMPTY
     *       : RouteBuildProgress.cachedSnapshot(level, r.id());
     * </pre>
     */

    // ─────────────────── 缓存（审计 P2-3）───────────────────
    //
    // HUD 由 HudRenderCallback 每帧调用，而 snapshot() 会走
    // WorldDataProvider.getStructureConnections →
    // StructureFileStorage.getStructureConnections
    // （public static synchronized，且每次 new ArrayList<>(...) 全量拷贝）。
    //
    // 也就是：客户端渲染线程每帧抢这把锁，而服务端线程在
    // setStructureConnections 里还持有同一把锁做磁盘 save ——
    // 提交施工的瞬间会造成明显的客户端卡顿。
    //
    // 这里加一层时间窗缓存：每 CACHE_TTL_MS 才真正汇总一次。
    // 进度是给人看的，半秒延迟完全无感。

    private static final long CACHE_TTL_MS = 500L;

    private static volatile Snapshot cache = Snapshot.EMPTY;
    private static volatile UUID cacheRouteId;
    /**
     * ★ 审计 R3-P3-9：缓存必须**按 level 键控**。
     *
     * <p>原先只按 routeId + 时间键控，而不同维度的 routeId 理论上是不同 UUID，
     * 所以实际很难撞上 —— 但「500ms 内切世界且 routeId 相同」（例如同一个
     * 存档在两个维度间来回切，而路线对象被复用）会让 HUD 显示错误进度。
     * 加上 level 引用比较是零成本的正确性保证。</p>
     */
    private static volatile ServerLevel cacheLevel;
    private static volatile long cacheAtMs;

    /** 带时间窗缓存的进度汇总（HUD 每帧调用走这里）。 */
    public static Snapshot cachedSnapshot(ServerLevel level, @Nullable UUID routeId) {
        long now = System.currentTimeMillis();
        if (routeId != null && routeId.equals(cacheRouteId) && cacheLevel == level
                && now - cacheAtMs < CACHE_TTL_MS) {
            return cache;
        }
        Snapshot fresh = snapshot(level, routeId);
        cache = fresh;
        cacheRouteId = routeId;
        cacheLevel = level;
        cacheAtMs = now;
        return fresh;
    }

    /** 手动失效缓存（提交施工后立刻刷新用）。 */
    public static void invalidateCache() {
        cacheAtMs = 0L;
        cacheRouteId = null;
        cacheLevel = null;
    }
}
