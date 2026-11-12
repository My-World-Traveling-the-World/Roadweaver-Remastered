/* 文件职责：手绘路线「提交施工」的服务端流程：校验 → 落数据 → 入队强制加载与铺装。 */
package net.shiroha233.roadweaver.route;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.route.RouteInjector.InjectionPlan;
import net.shiroha233.roadweaver.route.RouteInjector.InjectionSegment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手绘路线的**施工调度**（玩家确认后才执行）。
 *
 * <h2>触发方式（与用户确认）</h2>
 * <pre>
 *   玩家画完 → 编辑器里点「确定」 → 进入 READY
 *        ↓
 *   本服务把路线拆成连接、写进 WorldDataProvider
 *        ↓
 *   现有 RoadGenerationService.tick 自动接管
 *        ↓
 *   PLANNED → GENERATING → COMPLETED（复用整套状态机）
 * </pre>
 *
 * <h2>为什么把连接写进 WorldDataProvider，而不是直接调 generateTask</h2>
 * {@code RoadGenerationService.refreshQueue} 的数据源就是
 * {@code WorldDataProvider.getStructureConnections(level)}。
 * 一旦写进去，我们**免费获得**：
 * <ul>
 *   <li>状态机（PLANNED/GENERATING/COMPLETED/FAILED）与进度追踪</li>
 *   <li>按玩家距离排队的施工顺序（{@code pollNearest}）</li>
 *   <li>持久化（进存档，重进游戏继续施工）</li>
 *   <li>Chunky 联动（{@code RoadPlanningReadinessIndex}）</li>
 *   <li>并发限流（{@code RuntimeLoadPolicy}）</li>
 * </ul>
 * 自己另起一套调度会把这些全部重写一遍，且必然会和现有节流机制打架。
 */
public final class RouteBuildService {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver/route-build");

    private RouteBuildService() {
    }

    /**
     * 提交一条路线进入施工。
     *
     * <p>幂等：同一条路线重复提交不会产生重复连接（按 {@code PlannedPathKey} 去重）。</p>
     *
     * <h2>★ 审计 P1-6：非主世界直接拒绝</h2>
     * {@code RoadGenerationService.generateTaskInternal} 开头就检查
     * {@code Level.OVERWORLD.equals(level.dimension())}，非主世界一律返回失败。
     * 而 {@code RouteEditorSession.resolveServerLevel} 在下界/末地也能拿到 level
     * （不为 null），所以原先 {@code submitForBuild} 会**真的执行提交**：
     * 连接写进 {@code WorldDataProvider} 并落盘，但调度永远不会处理它 ——
     * 存档里留下永久 PLANNED 的僵尸连接，HUD 进度永远停在「施工中 0/N」。
     *
     * <p>所以在入口就拦掉，返回 0 让界面给出明确提示。</p>
     *
     * @return 实际加入的连接数；0 表示没有可施工分段或维度不支持
     */
    public static int submit(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null || !route.isDrawable()) {
            return 0;
        }
        if (!net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) {
            LOGGER.warn("手绘路线只支持主世界，已拒绝提交 route={} dimension={}",
                    route.id(), level.dimension().location());
            return 0;
        }

        // ── ① 拆成连接 + 平滑路径 ──
        TerrainSamplingCache cache = new TerrainSamplingCache();
        RouteSmoother.HeightProvider heightProvider = groundProvider(level, cache);
        InjectionPlan plan = RouteInjector.plan(level, route, heightProvider);
        if (plan.isEmpty()) {
            LOGGER.warn("手绘路线无可施工分段 route={} points={}", route.id(), route.pointCount());
            return 0;
        }

        // ── ② 登记：让施工时知道这些连接来自手绘（patcher 据此覆盖高度/类型）──
        RouteBuildRegistry.putRoute(level, route);
        RouteBuildRegistry.registerPlan(level, route.id(), plan);

        // ── ③ 预置路径：让管线走「不搜索」分支 ──
        primePlannedPaths(level, plan);

        // ── ④ 把连接写进 WorldDataProvider，交给现有调度 ──
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> existing = provider.getStructureConnections(level);
        List<StructureConnection> merged = new ArrayList<>(existing == null ? List.of() : existing);

        int added = 0;
        for (InjectionSegment seg : plan.segments()) {
            if (!containsConnection(merged, seg.connection())) {
                merged.add(new StructureConnection(seg.connection().from(),
                        seg.connection().to(), ConnectionStatus.PLANNED));
                added++;
            }
        }
        if (added > 0) {
            provider.setStructureConnections(level, merged);
        }

        LOGGER.info("手绘路线已提交施工 route={} 分段={} 新增连接={} 总连接={}",
                route.id(), plan.size(), added, merged.size());

        // ★ 2026-09-25 修 R-2：**每次提交都重新武装预加载**。
        //
        // 背景：`reopenForEdit()` 只把状态改回 DRAFT，**UUID 不变**。
        // 玩家「施工 → 重新编辑 → 改路线 → 再提交」时，预加载去重标记
        // 还带着旧值 ⇒ 新一轮的区块被直接跳过，新改的区域不会被强制加载。
        //
        // 所以每次提交都清掉该路线的标记 —— 让下一轮预加载重新走一遍。
        // （预加载本身还会经过"就绪判定"，不会重复劳动。）
        RouteChunkPreloader.forgetPreloaded(level, route.id());

        // ★ 0.8.400：**同时登记 tick 兜底**（关键修复）。
        //
        // 原先预加载只依赖 RoadGenerationService 里那条 4 跳异步链
        // （worker → 主线程取 routeFor → snap future → whenComplete），
        // 而最后一跳会在 epoch 失效时**静默丢弃**整个动作。
        // 真机取证（2026-09-26）：路建好了，但全日志 0 条「手绘路线预加载开始」
        // ⇒ 区块强制加载一次都没跑，玩家走过去仍是空地。
        //
        // 现在提交即登记意图；由 RouteChunkPreloader.drainPending（服务端 tick 驱动）
        // 观察「连接全部结算」后强制执行。不碰 future、不碰 epoch —— 那条链
        // 无论哪里断掉，兜底都照样生效。
        RouteChunkPreloader.enqueuePending(level, route.id());

        // ★ UX-11：同时清掉中止标志 —— 否则重新提交的路线一上来就被中止。
        RoutePreloadStatus.clearStop(level, route.id());

        // ★ 2026-09-26 修 I-10：原先这里还调 `forgetWaitFor`（清「等待轮数」）——
        //   但那套机制在 0.8.395 改用连接状态机判据后已成死代码，已整体删除。

        return added;
    }

    /**
     * ★ 2026-09-25 用户要求：提交施工时**立即强制加载该路线经过的区块并施工**。
     *
     * <h2>为什么需要它</h2>
     * 原先提交后完全依赖 {@code LoadedChunkRoadBackfill} 的被动回填 ——
     * 只捡「当前恰好已加载」的区块、每次上限 8 个。实测日志：
     * <pre>
     *   candidates=361 loaded=289 processed=8 placed=8
     * </pre>
     * ⇒ 玩家看到「地图已标蓝但世界里走过去仍是空地」。
     *
     * <h2>为什么放在这里而不是 submit() 内部</h2>
     * {@code submit()} 只写「连接清单」（PLANNED），**道路数据还没生成** ——
     * 强制加载后也没有 RoadData 可铺。所以本方法的调用时机是
     * **在 submit 之后、由服务端线程再跑一轮生成**，让连接先走完生成管线
     * （产出 RoadData 并落盘），此时再强加载 + 铺路才有意义。
     *
     * <p>调用方：{@code RouteEditorSession.submitForBuild} 的 lambda ——
     * 它在 submit 之后立即调本方法。此时 {@code added} 个连接已入队，
     * 生成由 {@code RoadGenerationService.tick} 推进；本方法**主动等一下**，
     * 让区块先加载好，玩家就不必走过去才看到路。</p>
     *
     * <p>⚠️ 本方法会 {@code managedBlock} —— 必须在服务端线程调用，
     * 且**不设超时**（用户明确要求「必须全部加载完成」）。</p>
     *
     * @return 实际处理的区块数
     */
    public static int preloadRouteChunks(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null) {
            return 0;
        }
        try {
            return RouteChunkPreloader.preloadAndConstruct(level, route);
        } catch (Throwable t) {
            // 预加载失败不能影响已提交的连接 —— 后台回填仍会兜底
            LOGGER.warn("手绘路线预加载失败（已提交的连接不受影响，转交后台回填）route={}",
                    route.id(), t);
            return 0;
        }
    }

    /**
     * 世界退出 / 重置时清掉预加载去重标记。
     *
     * <p>与 {@code RouteBuildRegistry.onWorldLeave} 配套 —— 否则同一 level
     * 对象被复用时（理论上可能），上一轮"已预加载"的标记会让新一轮直接跳过。</p>
     */
    public static void clearPreloadState(ServerLevel level) {
        RouteChunkPreloader.clearRuntime(level);
    }

    /**
     * **重试预加载失败的区块**（★ UX-5 的「重新尝试」按钮）。
     *
     * <h2>为什么按 level 而不是按"当前路线"</h2>
     * 0.8.388 起施工成功后编辑对象会切到**新的空路线**，失败其实属于
     * 刚提交的那条 —— 用"当前编辑对象"会找不到要重试的东西。
     * 所以这里遍历 {@link RoutePreloadStatus#retryable} 列出的所有路线。
     *
     * <p>做法：清掉这些路线的去重占位 + 中止标志，然后重新跑一次预加载。
     * 预加载内部本身有"就绪判定"，不会做多余的活。</p>
     *
     * @return 触发重试的路线数
     */
    public static int retryFailedPreloads(ServerLevel level, PlayerRoute currentOrNull) {
        if (level == null || level.getServer() == null) {
            return 0;
        }
        int n = 0;
        // ① 当前编辑中的路线（若它自己就是失败的那条）
        if (currentOrNull != null && currentOrNull.id() != null) {
            RouteChunkPreloader.forgetPreloaded(level, currentOrNull.id());
            RoutePreloadStatus.clearStop(level, currentOrNull.id());
        }
        // ② 所有"有失败且已结束"的路线
        for (RoutePreloadStatus.Snapshot snap : RoutePreloadStatus.retryable(level)) {
            if (snap.routeId() == null) {
                continue;
            }
            RouteChunkPreloader.forgetPreloaded(level, snap.routeId());
            RoutePreloadStatus.clearStop(level, snap.routeId());
            n++;
        }
        LOGGER.info("手绘路线预加载重试：触发 {} 条路线（已清去重占位与中止标志）", n);
        return n;
    }

    /**
     * 预置平滑路径到 {@code PlannedPathCache}，使 {@code Road.generateRoad}
     * 命中缓存、走 {@code calculateFromPlannedPath}（不跑 A*）。
     *
     * <h2>★ 为什么要循环注册多个 step 版本</h2>
     * {@code PlannedPathCache} 的命中依赖**指纹匹配**，而指纹里包含
     * {@code config.effectiveAStarStep()}（见 {@code PlannedPathFingerprintService} 行 48）。
     *
     * <p>问题是 {@code RoadGenerationService.generateTaskInternal} 在**精度重试**路径下会
     * 改写这一个字段：</p>
     * <pre>
     *   genCfg.pathfinding().setAStarStep(precisionStep);   // 重试时 step 可能不是默认值
     * </pre>
     *
     * <p>如果只按默认 step 注册，重试时指纹就对不上 —— 缓存未命中，
     * 管线**静默退回 A\* 搜索**，玩家画的线被算法路径悄悄替换掉。
     * 这是最难发现的一类 bug（不报错、结果看起来"也能建成路"）。</p>
     *
     * <p>所以按「默认 step + 常见重试 step（1、2）」各注册一份。
     * 同一连接只是多几个缓存条目，开销可忽略，换来的是任何 step 下都能命中。
     * 这沿用了工程既有做法（{@code RoadPlanningService.rebindGeneratedRouteForRetry}
     * 也是用 {@code withAStarStep(...)} 造匹配 config 再注册）。</p>
     */
    private static void primePlannedPaths(ServerLevel level, InjectionPlan plan) {
        try {
            var mode = net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions
                    .forLevel(level).effectiveMode();
            var base = net.shiroha233.roadweaver.config.ConfigService.get().pathfindingCost();
            if (base == null) {
                LOGGER.warn("无法取得 pathfinding 配置，跳过路径预置（将以 A* 搜索生成）");
                return;
            }
            // 默认 step + 重试可能用到的 step，全部注册，保证任何精度下都命中
            java.util.LinkedHashSet<Integer> steps = new java.util.LinkedHashSet<>();
            steps.add(base.effectiveAStarStep());
            steps.add(1);
            steps.add(2);
            for (int step : steps) {
                var cfg = base.withAStarStep(step);
                RouteInjector.primeCache(level, plan, mode, cfg);
            }
            LOGGER.info("手绘路径已预置 steps={} 分段={}", steps, plan.size());
        } catch (Throwable t) {
            // 预置失败不致命：管线会退回 A* 搜索（路仍然会建，只是不完全按玩家画的）
            LOGGER.warn("预置手绘路径失败，将退回 A* 搜索", t);
        }
    }

    /**
     * 按连接起终点去重时用的判据。
     *
     * <h2>★ 审计 R3-P2-13：必须**方向无关**，与缓存键同一套语义</h2>
     * 原实现用 {@code from/to} **顺序比较**，而 {@code PlannedPathCache} 的
     * {@link net.shiroha233.roadweaver.planning.path.PlannedPathKey} 构造时会
     * 按坐标排序（**方向无关**）。两者不一致会造成「键空间分叉」：
     * <pre>
     *   玩家画 A→B→A（原路返回 / U 形掉头）
     *     → 连接 (A,B) 与 (B,A) 都通过去重（顺序不同 ⇒ 都算"新"）
     *     → 但两条连接在缓存里是**同一个 PlannedPathKey**
     *     → 后提交的覆盖先提交的，其中一条被倒序使用
     * </pre>
     * 现在改用 {@code PlannedPathKey} 比较，与缓存、与登记表全部统一 ——
     * 一条路径对应一条连接，不会出现"两条连接共用一个缓存条目"。
     *
     * <p>注意：{@code StructureConnection} 是含 {@code status} 的 record、
     * 没有任何方向归一化（已核对 {@code StructureConnection.java:12}），
     * 所以这个判据必须在这里做，不能指望上游。</p>
     */
    private static boolean containsConnection(List<StructureConnection> list, StructureConnection target) {
        if (target == null) {
            return false;
        }
        var targetKey = net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(target);
        for (StructureConnection c : list) {
            if (c == null) {
                continue;
            }
            // 方向无关：PlannedPathKey 构造时对端点排序
            if (targetKey != null && targetKey.equals(
                    net.shiroha233.roadweaver.planning.path.PlannedPathKey.of(c))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提供「(x,z) → 地面高度」的回调，供 {@link RouteSmoother} 让路段贴地。
     *
     * <p>取不到就退回海平面，绝不抛异常 —— 平滑是编辑器实时操作，
     * 不能因为采样失败让界面卡死。</p>
     */
    public static RouteSmoother.HeightProvider groundProvider(ServerLevel level,
                                                              TerrainSamplingCache cache) {
        return (x, z) -> {
            try {
                AccurateHeightSampler sampler = cache.getAccurateSampler(level);
                return sampler.surfaceHeight(x, z) + 1;
            } catch (Throwable t) {
                return 63;
            }
        };
    }

    /**
     * 取消一条已提交但还没建完的路线。
     *
     * @return 移除的连接数
     */
    public static int cancel(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null) {
            return 0;
        }
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> existing = provider.getStructureConnections(level);
        if (existing == null || existing.isEmpty()) {
            return 0;
        }

        // 只移除「还没开始施工」的连接 —— 已经建了一半的不能凭空撤掉
        List<StructureConnection> remaining = new ArrayList<>(existing.size());
        int removed = 0;
        for (StructureConnection c : existing) {
            // 查一次登记表就够：拿到 Entry 后比对 routeId
            RouteBuildRegistry.Entry entry = RouteBuildRegistry.lookup(level, c);
            boolean isOurs = entry != null && route.id().equals(entry.routeId());
            if (isOurs && c.status() == ConnectionStatus.PLANNED) {
                removed++;
                RouteBuildRegistry.clear(level, c);
                continue;
            }
            remaining.add(c);
        }
        if (removed > 0) {
            provider.setStructureConnections(level, remaining);
        }
        LOGGER.info("取消手绘路线 route={} 移除连接={}", route.id(), removed);
        return removed;
    }

    /**
     * 重进世界时**从持久化数据重建登记表**（★ 审计 R3-P1-4 的核心修复）。
     *
     * <h2>要解决什么问题</h2>
     * 这是本轮审计里唯一的**架构级**缺陷：玩家意图与路径**分居两处、
     * 生命周期不同**。
     * <pre>
     *   路径      → PlannedPathCache → FilePlannedPathStore   【持久化】
     *   kind/高度 → RouteBuildRegistry                          【纯内存，从不清理】
     * </pre>
     * 于是「提交后没建完就退出/重进世界」时：
     * <ul>
     *   <li>{@code PlannedPathCache.find} 命中（磁盘有）⇒ **路的位置是对的**</li>
     *   <li>{@code RouteBuildRegistry.routeFor} = null ⇒ 不加 patcher ⇒
     *       **桥隧退化成贴地路**</li>
     * </ul>
     * 「位置还对」让问题看起来只是「高度不对」，这是最难发现的一类。
     *
     * <h2>为什么这样修（而不是新增一个持久化旁路表）</h2>
     * 玩家意图的**权威副本本来就在 {@code routes.json} 里**（{@code PlayerRoute}
     * 内含每个控制点的坐标、高度与 kind，且是持久化的）。
     * 内存登记表只是「连接 → 路线」的索引 —— 一个**可重建的派生结构**。
     *
     * <p>所以正确做法不是「把标脏的数据也存一份」，而是
     * <b>在进入世界时用权威副本重建索引</b>：</p>
     * <ol>
     *   <li>读 {@code routes.json} 拿到全部路线（含 READY 的）</li>
     *   <li>对每条路线调 {@link RouteInjector#plan} 重新算出连接与 kind</li>
     *   <li>{@code registerPlan} 把「连接 → kind/高度」写回登记表</li>
     * </ol>
     * 这样消除了「两份数据必须手动保持同步」这个根本问题 ——
     * 无论何时重进世界，索引都能从权威副本重新长出来。
     *
     * <p>对未提交（DRAFT）的路线也一并登记：它随时可能被提交，
     * 提前登记无副作用（登记表按连接键索引，不影响渲染与调度）。</p>
     *
     * @return 重建的连接条数
     */
    public static int rebuildRegistryFromStorage(ServerLevel level,
                                                 java.util.Collection<PlayerRoute> routes) {
        if (level == null || routes == null || routes.isEmpty()) {
            return 0;
        }
        int total = 0;
        TerrainSamplingCache cache = new TerrainSamplingCache();
        RouteSmoother.HeightProvider heightProvider = groundProvider(level, cache);
        for (PlayerRoute route : routes) {
            if (route == null || !route.isDrawable()) {
                continue;
            }
            try {
                // 路线本体也放回登记表 —— RouteRoadDataPatcher 需要完整 PlayerRoute
                RouteBuildRegistry.putRoute(level, route);
                InjectionPlan plan = RouteInjector.plan(level, route, heightProvider);
                if (plan.isEmpty()) {
                    continue;
                }
                int n = RouteBuildRegistry.registerPlan(level, route.id(), plan);
                total += n;
                // 顺带把路径预置回缓存：重进世界后 PlannedPathCache 可能已被清空
                // （RoadGenerationService.reset / CacheManager 都会 clearAll），
                // 不预置的话施工会退回 A* 搜索、位置也不再是玩家画的。
                primePlannedPaths(level, plan);
            } catch (Throwable t) {
                LOGGER.warn("重建手绘路线登记失败 route={}", route.id(), t);
            }
        }
        if (total > 0) {
            LOGGER.info("RoadWeaver 重进世界：从存档重建手绘路线登记 routes={} 连接={}",
                    routes.size(), total);
        }
        return total;
    }

    /**
     * 查询某条路线当前登记了多少待施工连接。
     *
     * <p>用于 HUD 显示「待施工 N 段」。注意它数的是**当前登记表**里的项，
     * 完工项会被 {@code RouteBuildRegistry.clearCompleted} 移走 ——
     * 要「历史提交总数」请用 {@code RouteBuildRegistry.countForRoute}。</p>
     */
    public static int pendingCount(ServerLevel level, PlayerRoute route) {
        if (level == null || route == null) {
            return 0;
        }
        int n = 0;
        var ids = RouteBuildRegistry.registeredRouteIds(level);
        if (!ids.contains(route.id())) {
            return 0;
        }
        List<StructureConnection> all = WorldDataProvider.getInstance().getStructureConnections(level);
        if (all == null) {
            return 0;
        }
        for (StructureConnection c : all) {
            var e = RouteBuildRegistry.lookup(level, c);
            if (e != null && route.id().equals(e.routeId())) {
                n++;
            }
        }
        return n;
    }
}
