/* 文件职责：手绘路线 → 施工参数的**逐段映射**。 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 手绘路线 → 施工参数的**逐段映射**。
 *
 * <h2>为什么需要这个类</h2>
 * 现有管线里，路径点的高度会被 {@code AccuratePathHeightResolver} **重新吸附回地面**：
 * <pre>
 *   y = tunnelHints[i] ? min(targetY, gradeProfile) : gradeProfile[i]
 *                        ↑ 仅当「玩家 Y 比地面低 ≥7 格且连续 3 点」
 *                          ↑ 否则一律贴地
 * </pre>
 * 这对算法找出的路是对的（算法只会找地面路径），但对**玩家手绘**来说：
 * <ul>
 *   <li>{@link RouteKind#ROAD} —— 贴地正确，高度重算没问题</li>
 *   <li>{@link RouteKind#BRIDGE} —— 玩家把点抬高表示「这里架桥」，
 *       重算会把桥压回地面，**违背「画什么生成什么」**</li>
 *   <li>{@link RouteKind#TUNNEL} —— 只有「低于地面 ≥7 格」才被识别为隧道，
 *       玩家画的浅隧道（低 3~6 格）会被填平</li>
 * </ul>
 *
 * <p>所以注入时必须**绕开高度重解析**：几何仍由现有管线生成（宽度、平滑、
 * 避开结构建筑这些都是成熟逻辑），但 {@code targetY} 用玩家给的高度，
 * {@code spans} 用玩家选的类型。</p>
 *
 * <h2>本类做什么</h2>
 * 把「施工管线产出的分段」逐个映射回「玩家原始意图」，得到每段的：
 * <ul>
 *   <li>类型（{@link RouteKind}，来自离该段最近的控制点）</li>
 *   <li>目标高度（来自玩家的点，桥/隧道保持原值；路交给地形）</li>
 * </ul>
 */
public final class RouteSegmentMapper {

    private RouteSegmentMapper() {
    }

    /**
     * 一段施工分段的映射结果。
     *
     * @param kind    该段应该建成什么
     * @param targetY 该段的目标高度（桥/隧道 = 玩家值；路 = 由调用方填地形高度）
     */
    public record MappedSegment(RouteKind kind, int targetY, boolean heightFromPlayer) {
    }

    /**
     * 把施工分段的中心点逐个映射到玩家意图。
     *
     * @param segmentCenters 施工管线产出的分段中心（按顺序）
     * @param smoothedPath   玩家路线的平滑路径点（由
     *                       {@link RouteSmoother#smooth} 产出，与 centers 点数不一定相同）
     * @param route          原始手绘路线（取 kind 用）
     * @return 与 {@code segmentCenters} 等长的映射结果
     */
    public static List<MappedSegment> map(List<BlockPos> segmentCenters,
                                          List<BlockPos> smoothedPath,
                                          PlayerRoute route) {
        List<MappedSegment> out = new ArrayList<>();
        if (segmentCenters == null || segmentCenters.isEmpty()) {
            return out;
        }

        // ★ 审计 R3-P1-5：类型判定统一走 RouteKindResolver（规则 A = 段起点）。
        //
        // 原实现用 nearestControlIndexXZ（「离哪个控制点最近」）—— 那是规则 B，
        // 与 RouteSmoother 的规则 A 冲突：一条 A(BRIDGE)→B(ROAD) 的线上，
        // 中点之后离 B 更近 ⇒ 被判成 ROAD ⇒ **桥只建前半截**，
        // 并在中点处制造 16 格高度断崖（进而触发 P1-3 整条丢弃）。
        //
        // RouteKindResolver 问的是「这个中心落在哪一段上」——与平滑器、
        // 渲染器同源，三处不可能再分叉。
        //
        // 游标是单调的：segmentCenters 沿路径顺序排列，整体 O(N+M)。
        RouteKindResolver.Cursor cursor = new RouteKindResolver.Cursor();

        // 逐段找最近的玩家路径点（只比较 XZ，因为高度本来就要覆盖）
        for (BlockPos center : segmentCenters) {
            int bestIdx = nearestIndexXZ(smoothedPath, center);
            RouteKind kind = RouteKind.ROAD;
            int height = center.getY();
            boolean fromPlayer = false;

            if (bestIdx >= 0 && smoothedPath != null && bestIdx < smoothedPath.size()) {
                height = smoothedPath.get(bestIdx).getY();
            }
            // ★ 规则 A：中心点落在哪一段，就用那一段起点的 kind
            kind = cursor.advance(route, center);

            // 桥/隧道：高度以玩家为准（不从地形重算）
            fromPlayer = (kind == RouteKind.BRIDGE || kind == RouteKind.TUNNEL);

            // 桥/隧道的高度必须来自玩家控制点（平滑点的高度已经是插值结果，
            // 但端点处应以控制点为锚，避免累积误差）
            if (fromPlayer && route != null) {
                height = interpolatePlayerHeight(route, cursor.segmentIndex(), smoothedPath, center);
            }

            out.add(new MappedSegment(kind, height, fromPlayer));
        }
        return out;
    }

    /**
     * 在玩家控制点上按「该中心所在的段」内插值，得到该处应该是的高度。
     *
     * <h2>★ 审计 R3-P1-5：改为按「所在段」插值（而不是全局重扫）</h2>
     * 原实现每次都对全部控制点线段重扫一遍找最近段（O(N) 每中心 ⇒ O(N·M) 总量），
     * 而调用方已经在 {@link RouteKindResolver.Cursor} 里算出了精确的所在段。
     * 直接用那个段插值：
     * <ul>
     *   <li><b>更快</b>：O(1) 每中心，长路线（1000 控制点）不再白烧 CPU</li>
     *   <li><b>更一致</b>：与类型判定用的是同一个段，不会出现
     *       「类型按段 A、高度按段 B」的错配</li>
     *   <li><b>仍然连续</b>：第 k 段终点高度 = 第 k+1 段起点高度（都等于
     *       控制点 k+1 的 Y），所以桥面/隧道底在整个跨度上是分段但连续的折线</li>
     * </ul>
     *
     * <p>比「取最近控制点的高度」准：玩家画 A(高70) → B(高80) 的桥，
     * 中间点应该平滑过渡到 75 而不是在 A 附近一直是 70。</p>
     *
     * @param segmentIdx 该中心所在的段下标（来自游标；越界时退回全局查找）
     */
    private static int interpolatePlayerHeight(PlayerRoute route, int segmentIdx,
                                               List<BlockPos> smoothed, BlockPos at) {
        List<RoutePoint> pts = route.points();
        if (pts.isEmpty()) {
            return at.getY();
        }
        if (pts.size() == 1) {
            return pts.get(0).pos().getY();
        }

        int seg = segmentIdx;
        if (seg < 0 || seg + 1 >= pts.size()) {
            // 游标没给出有效段（例如空路线或越界）→ 退回全局最近段
            seg = nearestSegmentIndex(route, at);
        }
        if (seg < 0 || seg + 1 >= pts.size()) {
            return pts.get(0).pos().getY();
        }
        double t = RouteKindResolver.projectionT(route, seg, at);
        int ya = pts.get(seg).pos().getY();
        int yb = pts.get(seg + 1).pos().getY();
        return (int) Math.round(ya + (yb - ya) * t);
    }

    /** 全局最近段（仅在游标不可用时作为兜底）。 */
    private static int nearestSegmentIndex(PlayerRoute route, BlockPos at) {
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i + 1 < route.pointCount(); i++) {
            double d2 = RouteKindResolver.distanceSqToSegment(route, i, at);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    /** 在给定点列表里找 XZ 最近的下标（忽略 Y）。 */
    private static int nearestIndexXZ(List<BlockPos> list, BlockPos at) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
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
}
