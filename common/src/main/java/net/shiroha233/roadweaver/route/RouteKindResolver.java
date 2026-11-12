package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 段类型的**唯一判定规则**（★ 审计 R3-P1-5 / R3-P2-1 的统一修复）。
 *
 * <h2>要解决什么问题</h2>
 * 段类型（路 / 桥 / 隧道）曾经有**三份互相矛盾的实现**：
 * <pre>
 *   RouteSmoother       按「段起点的 kind」硬切        ← 规则 A
 *   RouteSegmentMapper  按「离施工中心最近的控制点」    ← 规则 B
 *   RoutePreviewRenderer 按「单调游标 + 最近控制点」    ← 规则 C（≈B）
 * </pre>
 * 每个模块都有自己的测试，各自都"对" —— 但叠加后出现的是**桥在段中点被截断**
 * （实测 11 个施工中心里只有 6 个是 BRIDGE），并因此制造 16 格高度断崖，
 * 反过来触发 {@code invalid-height-step} 让整条覆盖被丢弃
 * ⇒ 玩家看到「一条桥都没建，全是贴地路」。
 *
 * <h2>现在是哪一套规则</h2>
 * 统一到**规则 A**：<b>一段（控制点 i → i+1）的类型由该段起点的 kind 决定</b>。
 * 选择它的理由：
 * <ul>
 *   <li>与 {@link PlayerRoute#kindOfSegment(int)} 的既有约定一致
 *       （那个方法就是 {@code points.get(index).kind()}）</li>
 *   <li>与地图/世界渲染「在类型切换处画小三角」的直觉一致</li>
 *   <li>与 {@link RouteSmoother} 的硬切实现一致（几何与语义同源）</li>
 * </ul>
 *
 * <h2>「点落在哪一段」怎么算</h2>
 * 把点投影到**控制点多段线**上，取距离最小的那个**区间**（不是最近的控制点）。
 * 这就是 A 与 B 的差别：B 问「离哪个控制点近」，A 问「在哪一段上」。
 * 一条 A(BRIDGE) → B(ROAD) 的线上，中点之后离 B 更近（B 的答案是 ROAD），
 * 但它仍然**落在段 0 上**（A 的答案是 BRIDGE，整段是桥）。
 *
 * <h2>为什么要游标</h2>
 * 施工中心与渲染路径点都是**沿路径单调排列**的。所以这里提供一个只前进不后退的
 * {@link Cursor}，把「每个点都重扫全部控制点」的 O(N·M) 降到 O(N+M) ——
 * 预览渲染每帧都要跑，这个差别很重要（1000 控制点 × 4000 路径点 = 400 万次距离计算）。
 */
public final class RouteKindResolver {

    private RouteKindResolver() {
    }

    /**
     * 单调前移的段游标。
     *
     * <p>{@link #advance} 传入的点必须沿路径**单调前进**（施工中心、渲染路径点
     * 都满足）。若传入顺序错乱的点，游标会停在当前位置 —— 结果退化为
     * 「沿用上一次的段」，而不是抛异常。</p>
     */
    public static final class Cursor {

        private int segment;

        public Cursor() {
            this(0);
        }

        public Cursor(int startSegment) {
            this.segment = Math.max(0, startSegment);
        }

        /** 当前段下标。 */
        public int segmentIndex() {
            return segment;
        }

        /** 当前段的参数（投影在段内的 0..1 位置）。 */
        public double lastT() {
            return lastT;
        }

        private double lastT;

        /**
         * 推进到「包含 {@code at} 的那一段」并返回其类型。
         *
         * @return 段类型；路线不足两点时返回 {@link RouteKind#ROAD}
         */
        public RouteKind advance(PlayerRoute route, BlockPos at) {
            if (route == null || at == null) {
                return RouteKind.ROAD;
            }
            int n = route.segmentCount();
            if (n <= 0) {
                return RouteKind.ROAD;
            }
            if (segment >= n) {
                segment = n - 1;
            }
            // 只前进：下一个区间更近才走。因为点沿路径单调前进，
            // 这样收敛到的就是「该点所在的段」，且总体是线性的。
            double current = distanceSqToSegment(route, segment, at);
            while (segment + 1 < n) {
                double next = distanceSqToSegment(route, segment + 1, at);
                if (next < current) {
                    segment++;
                    current = next;
                } else {
                    break;
                }
            }
            lastT = projectionT(route, segment, at);
            // ★ 规则 A：段类型 = 该段**起点**控制点的 kind
            return route.kindOfSegment(segment);
        }

        /** 手工跳回某一段（用于「每段独立重算」的场景）。 */
        public void reset(int startSegment) {
            this.segment = Math.max(0, startSegment);
        }
    }

    /**
     * 一次算完：为给定点求它所在的段下标（无游标）。
     *
     * <p>用于点数很少的场合（如单段校验）；大批量请用 {@link Cursor}。</p>
     */
    public static int segmentIndexAt(PlayerRoute route, BlockPos at) {
        if (route == null || at == null || route.segmentCount() <= 0) {
            return -1;
        }
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i < route.segmentCount(); i++) {
            double d2 = distanceSqToSegment(route, i, at);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    /** 点所在的段类型（规则 A）。 */
    public static RouteKind kindAt(PlayerRoute route, BlockPos at) {
        int idx = segmentIndexAt(route, at);
        return idx < 0 ? RouteKind.ROAD : route.kindOfSegment(idx);
    }

    /**
     * 为整条路径逐点算段类型。
     *
     * <p>渲染器只需要它（不需要投影参数），所以单独给一个便捷方法。</p>
     */
    public static List<RouteKind> kindsAlong(PlayerRoute route, List<BlockPos> path) {
        List<RouteKind> out = new ArrayList<>();
        if (route == null || path == null || path.isEmpty() || route.segmentCount() <= 0) {
            return out;
        }
        Cursor cursor = new Cursor();
        for (BlockPos p : path) {
            out.add(cursor.advance(route, p));
        }
        return out;
    }

    // ─────────────────── 几何 ───────────────────

    /** 点到「控制点 i → i+1」这段线段的 XZ 距离平方。 */
    static double distanceSqToSegment(PlayerRoute route, int index, BlockPos at) {
        if (route == null || index < 0 || index + 1 >= route.pointCount()) {
            return Double.MAX_VALUE;
        }
        BlockPos a = route.point(index).pos();
        BlockPos b = route.point(index + 1).pos();
        double ax = a.getX(), az = a.getZ();
        double dx = b.getX() - ax, dz = b.getZ() - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-6 ? 0
                : ((at.getX() - ax) * dx + (at.getZ() - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double px = ax + dx * t;
        double pz = az + dz * t;
        return (px - at.getX()) * (px - at.getX()) + (pz - at.getZ()) * (pz - at.getZ());
    }

    /** 点在「控制点 i → i+1」段内的投影参数（0..1，已钳制）。 */
    static double projectionT(PlayerRoute route, int index, BlockPos at) {
        if (route == null || index < 0 || index + 1 >= route.pointCount()) {
            return 0;
        }
        BlockPos a = route.point(index).pos();
        BlockPos b = route.point(index + 1).pos();
        double ax = a.getX(), az = a.getZ();
        double dx = b.getX() - ax, dz = b.getZ() - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            return 0;
        }
        double t = ((at.getX() - ax) * dx + (at.getZ() - az) * dz) / len2;
        return Math.max(0, Math.min(1, t));
    }
}
