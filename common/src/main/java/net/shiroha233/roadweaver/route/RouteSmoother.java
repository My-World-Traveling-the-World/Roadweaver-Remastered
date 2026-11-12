/* 文件职责：手绘折线的平滑（Catmull-Rom）—— 与地图绘制、与施工路径共用同一套曲线，做到所见即所建。 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 控制点 → 密集路径点的平滑器。
 *
 * <h2>算法：Catmull-Rom 样条</h2>
 * 玩家画的是 CAD 那种「三点一个弯」的折线，要做到「画完自动平滑成曲线」。
 * 选用 Catmull-Rom 而不是贝塞尔，理由是：
 * <ul>
 *   <li><b>穿过每一个控制点</b> —— 玩家放的点必须落在路上，
 *       否则「我明明点在山脊上，路却偏出去了」。贝塞尔不保证这点。</li>
 *   <li>局部性 —— 改一个点只影响相邻两段，符合「画一段调一段」的直觉。</li>
 * </ul>
 *
 * <h2>★ 类型切换处硬切</h2>
 * 与用户确认过的规则一致：
 * <pre>
 *   点1 ─ 点2 ─ 点3 ─ 点4
 *      [  路  ][  桥  ]
 *            ↑
 *      这里不跨类型平滑，各段独立成曲线
 * </pre>
 * 物理上桥和路本来就不该连成一条曲线，接缝处硬切最干净。
 * 实现上就是**按 kind 把控制点切成若干「同类型连续段」，每段单独平滑**。
 *
 * <h2>高度处理</h2>
 * 控制点自带 Y（平面地图上玩家输数字定的，或世界内滚轮调的）。
 * 这里对 Y **不插值**，而是按段类型分别处理：
 * <ul>
 *   <li>{@link RouteKind#ROAD} —— 保留控制点 Y，后续交给现有地形逻辑贴地面</li>
 *   <li>{@link RouteKind#BRIDGE} / {@link RouteKind#TUNNEL} —— 在控制点之间线性插值 Y，
 *       玩家放两个点、中间就是一条平直的桥面/隧道</li>
 * </ul>
 */
public final class RouteSmoother {

    /**
     * 相邻控制点之间**至少**插入多少个采样点（越大越圆滑、点越多）。
     *
     * <h2>★ 审计 R3-P2-3：从「固定值」改成「下限」</h2>
     * 原先这是固定值，与段长无关：一段 500 格的直线只会得到 9 个采样点，
     * 且因为首段 p0==p1 的钳制，间距还不均匀（两端约 42 格、中间约 77 格）。
     * 曲线段由此变成「77 格一根弦」的折线，后续管线正是按这些点铺路面 ——
     * 玩家看到的路面有明显的棱角。
     *
     * <p>现在实际采样数按**段长自适应**：{@code clamp(ceil(段长/2), 8, 64)}，
     * 即「每隔约 2 格一个采样点」。这与施工站点的步长量级一致
     * （{@code RoadDataValidator.MAX_CENTER_STEP = 2} 佐证），
     * 保证路面贴合平滑曲线而不是被拉直。</p>
     */
    public static final int SAMPLES_PER_SEGMENT = 8;

    /** 单段采样数的上限（长段也不会把点数撑爆）。 */
    public static final int MAX_SAMPLES_PER_SEGMENT = 64;

    /**
     * 整条路线的采样点总数上限。
     *
     * <p>手绘路线可能有上千个控制点，逐段最多 64 个采样点会得到几万个点，
     * 后续「注入 + 地形采样 + 施工」全都要按这个规模展开。超过上限时
     * **按比例整体降采样**（而不是截断路线）—— 玩家画多长都还是完整一条线，
     * 只是长线段的采样密度相应降低。</p>
     */
    public static final int MAX_TOTAL_POINTS = 4000;

    private RouteSmoother() {
    }

    /**
     * 把一条路线的控制点平滑成密集路径点。
     *
     * @param route       手绘路线
     * @param yProvider   取某个 (x,z) 处地面高度的回调（用于 ROAD 贴地面）；
     *                    传 null 时全部沿用控制点自带的 Y
     * @return 从起点到终点的密集路径点（按顺序）
     */
    public static List<BlockPos> smooth(PlayerRoute route, HeightProvider yProvider) {
        List<BlockPos> out = new ArrayList<>();
        if (route == null || route.isEmpty()) {
            return out;
        }
        List<RoutePoint> pts = route.points();
        if (pts.size() == 1) {
            out.add(resolveY(pts.get(0), yProvider));
            return out;
        }

        // ── ① 算采样密度（审计 R3-P2-3）──
        //
        // 先算「不限预算时每段想要多少采样点」，再按全局上限整体缩放。
        // 缩放系数 >= 1.0 时按各自期望值，< 1.0 时统一降采样。
        double budgetScale = sampleBudgetScale(pts);

        // ── ② 按 kind 切成「同类型连续段」 ──
        //    段的类型由该段**起点**的 kind 决定（与 RoutePoint.kind 的约定一致）。
        int runStart = 0;
        for (int i = 1; i <= pts.size() - 1; i++) {
            boolean last = (i == pts.size() - 1);
            RouteKind cur = pts.get(i - 1).kind();
            RouteKind nextKind = last ? null : pts.get(i).kind();
            if (last || cur != nextKind) {
                // [runStart, i] 是一段同类型的点区间
                appendRun(out, pts, runStart, i, cur, yProvider, budgetScale);
                runStart = i;
            }
        }
        return out;
    }

    /**
     * 全局点预算的缩放系数（审计 R3-P2-3）。
     *
     * @return {@code <= 1.0} 表示需要整体降采样；{@code 1.0} 表示按各段期望值即可
     */
    private static double sampleBudgetScale(List<RoutePoint> pts) {
        long wanted = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            wanted += samplesFor(pts.get(i).pos(), pts.get(i + 1).pos());
        }
        if (wanted <= MAX_TOTAL_POINTS) {
            return 1.0;
        }
        return MAX_TOTAL_POINTS / (double) wanted;
    }

    /**
     * 一段（两个控制点之间）应插多少个采样区间。
     *
     * <p>目标间距约 2 格；下限 {@value #SAMPLES_PER_SEGMENT}（短段也要够圆滑），
     * 上限 {@value #MAX_SAMPLES_PER_SEGMENT}。</p>
     */
    private static int samplesFor(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.ceil(len / 2.0);
        return Math.max(SAMPLES_PER_SEGMENT,
                Math.min(MAX_SAMPLES_PER_SEGMENT, n));
    }

    /**
     * 平滑一个「同类型连续段」并追加到输出。
     *
     * @param from 段首控制点下标（含）
     * @param to   段尾控制点下标（含）
     */
    private static void appendRun(List<BlockPos> out, List<RoutePoint> pts,
                                  int from, int to, RouteKind kind,
                                  HeightProvider yProvider, double budgetScale) {
        if (to <= from) {
            return;
        }
        for (int i = from; i < to; i++) {
            BlockPos p0 = pts.get(i).pos();
            BlockPos p1 = pts.get(i + 1).pos();
            // ★ 每段自己的采样数：按段长自适应 + 全局预算缩放（审计 R3-P2-3）。
            // 至少 1，避免预算缩放把某段压成 0（会丢掉该段的曲线）。
            int samples = Math.max(1,
                    (int) Math.round(samplesFor(p0, p1) * budgetScale));
            // 段内采样：i==from 时从 0 开始（含起点），否则从 1 开始（避免与上一段末点重复）
            int sampleFrom = (i == from) ? 0 : 1;
            for (int s = sampleFrom; s <= samples; s++) {
                // 最后一段的最后一个采样点才落到终点，避免中间段重复放点
                boolean isLastSample = (s == samples);
                if (isLastSample && i < to - 1) {
                    continue;   // 交给下一段去放
                }
                float t = (float) s / samples;

                double x = catmullRom(neighborX(pts, from, to, i, -1),
                        p0.getX(), p1.getX(),
                        neighborX(pts, from, to, i + 1, +1), t);
                double z = catmullRom(neighborZ(pts, from, to, i, -1),
                        p0.getZ(), p1.getZ(),
                        neighborZ(pts, from, to, i + 1, +1), t);

                int bx = (int) Math.round(x);
                int bz = (int) Math.round(z);

                int by;
                if (kind == RouteKind.ROAD && yProvider != null) {
                    // 路：贴地面（用真实地面高度）
                    by = yProvider.heightAt(bx, bz);
                } else {
                    // 桥 / 隧道：两端控制点高度线性插值（桥面 / 隧道底面保持平直）
                    by = (int) Math.round(p0.getY() + (p1.getY() - p0.getY()) * t);
                }

                BlockPos candidate = new BlockPos(bx, by, bz);
                // 去重：相邻重复点只保留一个。
                // 审计 P3-1：原先这里还有个 `else if (first && out.isEmpty())` ——
                // 那是死代码，能走到 else 就说明 out.isEmpty() 已是 false。已删。
                if (out.isEmpty() || !out.get(out.size() - 1).equals(candidate)) {
                    out.add(candidate);
                }
            }
        }
        // 保证段尾控制点一定在结果里
        BlockPos tail = resolveY(pts.get(to), yProvider, kind);
        if (out.isEmpty() || !out.get(out.size() - 1).equals(tail)) {
            out.add(tail);
        }
    }

    /** 取相邻控制点的 X（越界时用端点自身，等价于「端点处切线自然」）。 */
    private static double neighborX(List<RoutePoint> pts, int from, int to, int i, int delta) {
        int idx = clamp(i + delta, from, to);
        return pts.get(idx).pos().getX();
    }

    private static double neighborZ(List<RoutePoint> pts, int from, int to, int i, int delta) {
        int idx = clamp(i + delta, from, to);
        return pts.get(idx).pos().getZ();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Catmull-Rom 插值。
     *
     * <p>标准形式（张力 0.5）：</p>
     * <pre>
     *   0.5 * ( 2*p1
     *         + (-p0 + p2) * t
     *         + (2*p0 - 5*p1 + 4*p2 - p3) * t²
     *         + (-p0 + 3*p1 - 3*p2 + p3) * t³ )
     * </pre>
     *
     * <p><b>public</b>：地图渲染器（{@code route.editor} 包）也要用同一套曲线
     * 画预览，必须两边算法完全一致，否则「地图上看到的」和「实际生成的」会对不上。</p>
     */
    public static double catmullRom(double p0, double p1, double p2, double p3, float t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2 * p1)
                + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static BlockPos resolveY(RoutePoint p, HeightProvider yProvider) {
        return resolveY(p, yProvider, p.kind());
    }

    private static BlockPos resolveY(RoutePoint p, HeightProvider yProvider, RouteKind kind) {
        if (kind == RouteKind.ROAD && yProvider != null) {
            return p.withY(yProvider.heightAt(p.pos().getX(), p.pos().getZ())).pos();
        }
        return p.pos();
    }

    /** 取 (x,z) 处地面高度的回调；返回值为方块 Y（脚下方块之上那一格）。 */
    @FunctionalInterface
    public interface HeightProvider {
        int heightAt(int x, int z);
    }
}
