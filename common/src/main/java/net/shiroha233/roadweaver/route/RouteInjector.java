package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;

/**
 * 把手绘路线**注入**到现有施工管线。
 *
 * <h2>注入策略：预置 PlannedPathCache</h2>
 * 现有流程是：
 * <pre>
 *   RoadGenerationService.generateTask(level, connection)
 *     → Road.generateRoad()
 *        → PlannedPathCache.find(level, connection, mode, cfg)   ← 查缓存
 *            命中 → RoadPathCalculator.calculateFromPlannedPath(...)  ← 不搜索，只后处理
 *            未命中 → calculateAStarRoadPathDetailed(...)             ← A* 搜索
 * </pre>
 *
 * <p>所以我们只要在生成前把玩家画的路径 {@code register} 进
 * {@link PlannedPathCache}，现有管线就会走「不搜索」分支，
 * 自动完成宽度、平滑、避开建筑、桥隧分段识别等成熟处理。
 *
 * <p><b>核心生成逻辑一行都不用改</b> —— 这是选这条路线的最大理由：
 * 不动 {@code RoadPathCalculator} / {@code PathSpanExtractor} /
 * {@code RoadChunkPlanCompiler} 这些高风险代码。</p>
 *
 * <h2>★ 高度与类型的覆盖</h2>
 * 上述管线会把高度**吸附回地面**（见 {@code AccuratePathHeightResolver}），
 * 并在后处理阶段**自己判定**哪段是桥/隧道。玩家手绘时需要覆盖这两点：
 * <ul>
 *   <li>高度：{@link RouteSegmentMapper} 算出每段的 {@code targetY}</li>
 *   <li>类型：{@link RouteSegmentMapper} 按最近控制点给出 {@code RouteKind}</li>
 * </ul>
 * 覆盖发生在 {@code RoadData} 产出之后（{@link RouteRoadDataPatcher}），
 * 因为在此之前几何还没定型。
 */
public final class RouteInjector {

    private RouteInjector() {
    }

    /**
     * 把一条手绘路线拆成「可施工的连接」并预置其路径。
     *
     * <h2>为什么拆成多段连接</h2>
     * {@link StructureConnection} 天生是「两点一条路」。玩家画的折线有多个控制点，
     * 拆成「控制点 i → 控制点 i+1」的连续连接后：
     * <ul>
     *   <li>每条连接都能被现有状态机（PLANNED/GENERATING/COMPLETED）独立管理</li>
     *   <li>施工可以逐段推进、逐段失败重试，不会「一条线全失败」</li>
     *   <li>复用现有 {@code RoadShardStorage} 持久化，自动规划也能看到这些路</li>
     * </ul>
     *
     * @return 拆出的连接列表（按路线顺序），以及每个连接对应的平滑路径
     */
    public static InjectionPlan plan(ServerLevel level, PlayerRoute route,
                                     RouteSmoother.HeightProvider heightProvider) {
        InjectionPlan plan = new InjectionPlan();
        if (level == null || route == null || route.pointCount() < 2) {
            return plan;
        }

        List<BlockPos> smoothed = RouteSmoother.smooth(route, heightProvider);
        if (smoothed.size() < 2) {
            return plan;
        }

        // 逐段拆：每个控制点区间一条连接，路径用该区间的平滑点子集
        List<RoutePoint> ctrl = route.points();
        // ★ 审计 R3-P2-2：用**单调游标**定位每段的起止，而不是每次全局找最近点。
        //
        // 原实现对每段都独立调 nearestXZ(smoothed, from/to)，而最近点是
        // **全局**取的 —— 闭合回路（终点回到起点）时 to 与 from 坐标相同，
        // nearestXZ(to) 会命中**路径开头**（同坐标取首次出现），
        // 于是 startIdx >= endIdx ⇒ 返回空 ⇒ 调用方退回 List.of(from,to)
        // 的**两点直线**，最后一段的平滑被丢掉、转角被切掉。
        //
        // 现在记下上一段的结束下标，作为下一段搜索的**下界**，
        // 保证 startIdx 单调不减；只在 [prevEnd, size) 里找 to。
        int searchFrom = 0;
        for (int i = 0; i + 1 < ctrl.size(); i++) {
            BlockPos from = ctrl.get(i).pos();
            BlockPos to = ctrl.get(i + 1).pos();
            if (from.equals(to)) {
                continue;
            }
            // 取该区间对应的平滑点
            List<BlockPos> segPath = subPathMonotonic(smoothed, from, to, searchFrom);
            if (segPath.size() < 2) {
                segPath = List.of(from, to);
            } else {
                // 下一段从本段终点之后开始找
                searchFrom = smoothed.indexOf(segPath.get(segPath.size() - 1));
                if (searchFrom < 0) {
                    searchFrom = 0;
                }
            }
            StructureConnection conn = new StructureConnection(from, to);
            plan.segments.add(new InjectionSegment(conn, segPath, ctrl.get(i).kind(), i));
        }
        return plan;
    }

    /**
     * 把计划里的路径预置进 {@link PlannedPathCache}，让后续生成走「不搜索」分支。
     *
     * <p>必须在调用 {@code generateTask} **之前**执行。</p>
     */
    public static void primeCache(ServerLevel level, InjectionPlan plan,
                                  net.shiroha233.roadweaver.config.sub.TerrainSamplingMode mode,
                                  net.shiroha233.roadweaver.config.sub.PathfindingCostConfig config) {
        if (level == null || plan == null || plan.segments.isEmpty()) {
            return;
        }
        var map = new java.util.LinkedHashMap<StructureConnection, List<BlockPos>>();
        for (InjectionSegment seg : plan.segments) {
            map.put(seg.connection(), seg.path());
        }
        PlannedPathCache.register(level, mode, config, map);
    }

    /**
     * 从平滑路径里截出「从 from 到 to 之间」的那一段。
     *
     * <p>用 XZ 最近点定位，而不是精确相等 —— 因为平滑点会偏移原控制点所在的格子。</p>
     *
     * <h2>★ 审计 R3-P2-2：搜索 to 时必须从 startIdx 之后开始</h2>
     * 原先 startIdx 与 endIdx 都是**全局**最近点。闭合回路（终点回到起点）时
     * {@code to} 与 {@code from} 坐标相同，{@code nearestXZ(to)} 会命中
     * **路径开头**（同坐标取首次出现），于是 {@code startIdx >= endIdx} ⇒ 空 ⇒
     * 调用方退回两点直线，最后一段的平滑被丢掉。
     *
     * <p>现在 {@code to} 只在 {@code [startIdx, size)} 里找 —— 取的是
     * 「从起点往前走遇到的最近点」，闭合回路因此能正确取到最后一段。</p>
     */
    private static List<BlockPos> subPath(List<BlockPos> smoothed, BlockPos from, BlockPos to) {
        return subPathMonotonic(smoothed, from, to, 0);
    }

    /**
     * 单调版子路径截取：{@code from} 只在 {@code [searchFrom, size)} 里找，
     * {@code to} 只在 {@code [startIdx, size)} 里找。
     *
     * @param searchFrom 本段起点搜索的下界（上一段的结束下标）——
     *                   保证多段依次截取时不会回头命中前面的点
     */
    private static List<BlockPos> subPathMonotonic(List<BlockPos> smoothed, BlockPos from,
                                                  BlockPos to, int searchFrom) {
        if (smoothed == null || smoothed.isEmpty()) {
            return List.of();
        }
        int lo = Math.max(0, Math.min(searchFrom, smoothed.size() - 1));
        int startIdx = nearestXZ(smoothed, from, lo, smoothed.size());
        if (startIdx < 0) {
            return List.of();
        }
        int endIdx = nearestXZ(smoothed, to, startIdx, smoothed.size());
        if (endIdx < 0 || startIdx >= endIdx) {
            return List.of();
        }
        return new ArrayList<>(smoothed.subList(startIdx, endIdx + 1));
    }

    /** 在 {@code [from, to)} 区间内找 XZ 最近的下标。 */
    private static int nearestXZ(List<BlockPos> list, BlockPos at, int from, int to) {
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        int lo = Math.max(0, from);
        int hi = Math.min(list.size(), to);
        for (int i = lo; i < hi; i++) {
            BlockPos p = list.get(i);
            double dx = p.getX() - at.getX();
            double dz = p.getZ() - at.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    // ───────────────────────── 数据 ─────────────────────────

    /** 一条可施工的分段。 */
    public record InjectionSegment(StructureConnection connection,
                                   List<BlockPos> path,
                                   RouteKind kind,
                                   int controlPointIndex) {
    }

    /** 整条路线的注入计划。 */
    public static final class InjectionPlan {
        private final List<InjectionSegment> segments = new ArrayList<>();

        public List<InjectionSegment> segments() {
            return segments;
        }

        public boolean isEmpty() {
            return segments.isEmpty();
        }

        public int size() {
            return segments.size();
        }

        /** 该计划涉及的所有连接（供逐个 generateTask）。 */
        public List<StructureConnection> connections() {
            List<StructureConnection> out = new ArrayList<>(segments.size());
            for (InjectionSegment s : segments) {
                out.add(s.connection());
            }
            return out;
        }

        public RouteKind kindOf(int index) {
            return index >= 0 && index < segments.size() ? segments.get(index).kind() : RouteKind.ROAD;
        }
    }

    /** 供调用方回填路线 id（施工进度追踪用）。 */
    public record PlannedRouteId(UUID routeId, InjectionPlan plan) {
    }
}
