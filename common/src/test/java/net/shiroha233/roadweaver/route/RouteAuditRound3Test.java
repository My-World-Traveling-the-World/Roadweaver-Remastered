package net.shiroha233.roadweaver.route;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;

/**
 * 第三轮审计（0.8.383-route-ctrl-edit）的**缺陷复现**测试。
 *
 * <h2>这个测试为什么是「红」的</h2>
 * 与其它 {@code *Test} 不同，本类**故意断言当前实现里的错误行为应当被修正** ——
 * 也就是说，在修复前它会失败（并打印出具体数值），修好后才会全绿。
 * 前两轮的教训是「修了但没测，下一版又回归」，所以每个结论都配一个可执行断言。
 *
 * <h2>覆盖的结论</h2>
 * <ol>
 *   <li><b>A-1 Catmull-Rom 的 p3 取错邻居</b>：{@code RouteSmoother:112-117} 把
 *       {@code pts[i+1]}（= p2）当成 p3，样条末端切线退化成弦方向 ⇒
 *       每个内部控制点处有折角；而三个渲染器用的是正确的 p3 ⇒
 *       「玩家看到的曲线」与「实际施工的曲线」不是同一条。</li>
 *   <li><b>A-2 段类型判定规则不一致</b>：{@link RouteSmoother} 按「段起点的 kind」
 *       硬切，{@link RouteSegmentMapper} 按「最近控制点」判定 —— 同一条段，
 *       两处给出不同的类型，桥/隧道在中点被截断。</li>
 *   <li><b>A-3 闭合回路最后一段丢平滑</b>：{@code RouteInjector.subPath} 用
 *       全局最近点定位，终点与起点重合时区间为空，退回「两点直线」。</li>
 *   <li><b>A-4 退化输入</b>：两点重合时平滑结果只剩 1 个点（注入计划为空）。</li>
 *   <li><b>A-5 长段采样过疏且不均</b>：{@code SAMPLES_PER_SEGMENT=8} 与段长无关，
 *       500 格的段采样间距两端 42、中间 77 格，路面被拉成折线。</li>
 * </ol>
 *
 * <p>风格与工程既有测试一致（{@code main} + {@code require}），不引入 JUnit。
 * 本类会**收集全部失败**再统一抛出，避免第一条断言就中断后续诊断。</p>
 */
public final class RouteAuditRound3Test {

    private RouteAuditRound3Test() {
    }

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void require(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            System.out.println("  FAIL " + what);
            failures.add(what);
        }
    }

    /** 仅打印诊断数值，不断言。 */
    private static void info(String what) {
        System.out.println("  ..   " + what);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testCatmullRomOvershoot();
        testKindBoundaryRuleMismatch();
        testClosedLoopSubPathFallsBackToChord();
        testCoincidentPointsDegenerate();
        testLongSegmentSamplingTooSparse();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
            return;
        }
        System.out.println("失败 " + failures.size() + "/" + checks + " 项：");
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        throw new AssertionError("RouteAuditRound3Test: " + failures.size() + " 项未修复");
    }

    // ───────────────────── ① Catmull-Rom p3 取错邻居 ─────────────────────

    /**
     * {@link RouteSmoother#appendRun} 传给 {@link RouteSmoother#catmullRom} 的
     * <b>第四个控制点（p3）取错了下标</b>：
     * <pre>
     *   // RouteSmoother.java:112-117（现状）
     *   catmullRom(neighborX(pts, from, to, i, -1),   // = pts[i-1]  正确
     *              p0.getX(),                          // = pts[i]
     *              p1.getX(),                          // = pts[i+1]
     *              neighborX(pts, from, to, i, +1),    // = pts[i+1]  ← 应为 pts[i+2]
     *              t);
     * </pre>
     * {@code neighbor(..., i, +1)} 求的是 {@code clamp(i+1)} = {@code pts[i+1]}，
     * 也就是 p2 自己，于是 **p3 恒等于 p2**，样条的末端切线退化成弦方向。
     *
     * <p>后果：每个内部控制点处**切向不连续**（出现折角），"平滑"只做了一半。
     * 而三个渲染器（{@code RouteWorldRenderer:176}、{@code RouteMapRenderer:96}、
     * {@code RoutePreviewRenderer} 走 smooth）里，前两者写的是
     * {@code i + 2 < n ? pts.get(i + 2) : p1} —— 也就是**正确**的 p3。
     * 于是「玩家看到的曲线」与「实际施工用的曲线」不是同一条。</p>
     *
     * <p>本用例直接拿公开的 {@link RouteSmoother#catmullRom} 按正确 p3 算出
     * 期望位置，再在平滑结果里找最近的采样点比对。</p>
     *
     * <p>复现坐标：A(0,64,0) B(100,64,0) C(100,64,100) D(0,64,100)，
     * 取 B→C 段 t=0.5：正确 x=112.5，现状 x=106.25（差 6.25 格）。</p>
     */
    private static void testCatmullRomOvershoot() {
        System.out.println("[A-1] Catmull-Rom p3：平滑曲线是否等于「渲染器画的那条」");
        PlayerRoute r = PlayerRoute.create("p3")
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 64, 100), RouteKind.BRIDGE)
                .append(new BlockPos(0, 64, 100), RouteKind.BRIDGE);

        List<BlockPos> path = RouteSmoother.smooth(r, null);
        List<BlockPos> pts = r.points().stream().map(RoutePoint::pos).toList();

        // B→C 段（下标 i=1）的 t=0.5 采样点：按**正确**的 p3 = pts[i+2] 算期望值
        int i = 1;
        double expectX = RouteSmoother.catmullRom(
                pts.get(i - 1).getX(), pts.get(i).getX(),
                pts.get(i + 1).getX(), pts.get(i + 2).getX(), 0.5F);
        double expectZ = RouteSmoother.catmullRom(
                pts.get(i - 1).getZ(), pts.get(i).getZ(),
                pts.get(i + 1).getZ(), pts.get(i + 2).getZ(), 0.5F);

        double closest = Double.MAX_VALUE;
        BlockPos nearest = null;
        for (BlockPos p : path) {
            double d = Math.hypot(p.getX() - expectX, p.getZ() - expectZ);
            if (d < closest) {
                closest = d;
                nearest = p;
            }
        }
        info("期望（正确 p3）中点 ≈ (" + round(expectX) + "," + round(expectZ) + ")"
                + "，实际最近的采样点 " + (nearest == null ? "无"
                : "(" + nearest.getX() + "," + nearest.getZ() + ")")
                + "，偏差 " + round(closest) + " 格");
        require(closest <= 1.5,
                "施工用的平滑曲线应与渲染器所画（正确 p3）一致，实测偏差 " + round(closest)
                        + " 格 —— 超出即代表「所见非所建」，且每个控制点处有折角");
    }

    // ───────────────── ② 段类型判定规则不一致 ─────────────────

    /**
     * 单段路线 A(BRIDGE) → B(ROAD)。
     *
     * <p>约定（{@link RouteSmoother} 与 {@link PlayerRoute#kindOfSegment}）是
     * 「段类型由该段**起点**的 kind 决定」⇒ 整段都该是 BRIDGE。</p>
     *
     * <p>但 {@link RouteSegmentMapper#map} 用「离施工中心最近的**控制点**」取类型，
     * 于是中点之后的中心会取到 B 的 ROAD ⇒ 桥在中点被截断成地面路。</p>
     */
    private static void testKindBoundaryRuleMismatch() {
        System.out.println("[A-2] 段类型：平滑器（段起点）与映射器（最近点）规则是否一致");
        PlayerRoute r = PlayerRoute.create("mixed")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 80, 0), RouteKind.ROAD);

        // 平滑器视角：整段 BRIDGE ⇒ 高度用玩家值（80），不贴地
        List<BlockPos> smoothed = RouteSmoother.smooth(r, null);
        boolean smootherAllBridgeHeight = smoothed.stream().allMatch(p -> p.getY() == 80);
        info("平滑器产出 " + smoothed.size() + " 点，全部 Y=80? " + smootherAllBridgeHeight);
        require(smootherAllBridgeHeight, "平滑器把整段当 BRIDGE（Y 保持玩家值 80）");

        // 映射器视角：施工中心沿段分布
        List<BlockPos> centers = new ArrayList<>();
        for (int x = 0; x <= 100; x += 10) {
            centers.add(new BlockPos(x, 64, 0));
        }
        List<RouteSegmentMapper.MappedSegment> mapped =
                RouteSegmentMapper.map(centers, smoothed, r);

        int bridgeCount = 0;
        int firstRoadAt = -1;
        for (int i = 0; i < mapped.size(); i++) {
            if (mapped.get(i).kind() == RouteKind.BRIDGE) {
                bridgeCount++;
            } else if (firstRoadAt < 0) {
                firstRoadAt = i;
            }
        }
        info("施工中心 " + centers.size() + " 个 → BRIDGE " + bridgeCount + " 个，"
                + "首个非 BRIDGE 在下标 " + firstRoadAt
                + (firstRoadAt >= 0 ? "（x=" + centers.get(firstRoadAt).getX() + "）" : ""));
        require(bridgeCount == centers.size(),
                "整段（0..100）都应被判为 BRIDGE，实测只有 " + bridgeCount + "/"
                        + centers.size() + " 个 —— 差值即为「桥被截断成地面路」的格数区间");
    }

    // ───────────── ③ 闭合回路最后一段退回直线 ─────────────

    /**
     * 闭合回路（终点回到起点）时，{@code RouteInjector.subPath} 的
     * {@code nearestXZ(to)} 会命中**路径开头**（同坐标，取首次出现），
     * 于是 {@code startIdx >= endIdx} ⇒ 返回空 ⇒ 调用方退回
     * {@code List.of(from, to)} 的**两点直线**，最后一段的平滑被丢掉。
     *
     * <p>用反射直接验证私有 helper —— 它没有公开入口（{@code plan} 需要
     * {@code ServerLevel}，单元测试里造不出来），但它是注入路径的核心，
     * 必须能被回归测试钉住。</p>
     */
    private static void testClosedLoopSubPathFallsBackToChord() throws Exception {
        System.out.println("[A-3] 闭合回路：最后一段能否取到平滑子路径");
        PlayerRoute r = PlayerRoute.create("loop")
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 64, 100), RouteKind.BRIDGE)
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE);

        List<BlockPos> smoothed = RouteSmoother.smooth(r, null);
        Method subPath = RouteInjector.class.getDeclaredMethod(
                "subPath", List.class, BlockPos.class, BlockPos.class);
        subPath.setAccessible(true);

        // 最后一段：(100,64,100) → (0,64,0)，终点与起点重合
        BlockPos from = r.point(2).pos();
        BlockPos to = r.point(3).pos();
        @SuppressWarnings("unchecked")
        List<BlockPos> last = (List<BlockPos>) subPath.invoke(null, smoothed, from, to);
        info("最后一段取到 " + last.size() + " 个路径点（直线回退时为 2）");

        require(last.size() > 2,
                "闭合回路的最后一段应取到平滑子路径（>2 点），实测 " + last.size()
                        + " —— 等于 2 说明退回了两点直线，转角被切掉");
    }

    // ───────────────── ④ 退化输入：两点重合 ─────────────────

    /**
     * 两个重合控制点（地图上连点两下同一格就会发生）：
     * {@code RouteSmoother.smooth} 只剩 1 个点 ⇒ {@code RouteInjector.plan}
     * 返回空计划 ⇒ {@code RouteBuildService.submit} 返回 0。
     *
     * <p>注意：{@code RouteEditorSession.submitForBuild} 的 lambda 里
     * <b>无论 added 是否为 0 都会把状态写成 READY</b>（RouteEditorSession:382），
     * 而 READY 的路线既不参与渲染（pendingRoutes 只取 DRAFT）也没有任何
     * 退回 DRAFT 的入口 —— 玩家会永久失去这条路线。这里先钉住「平滑结果为 1 点」
     * 这个前置事实。</p>
     */
    private static void testCoincidentPointsDegenerate() {
        System.out.println("[A-4] 退化输入：两点重合");
        PlayerRoute r = PlayerRoute.create("degenerate")
                .append(new BlockPos(7, 64, 7), RouteKind.ROAD)
                .append(new BlockPos(7, 64, 7), RouteKind.ROAD);

        List<BlockPos> path = RouteSmoother.smooth(r, null);
        info("两点重合 → 平滑结果 " + path.size() + " 个点");
        require(r.isDrawable(), "isDrawable() 对「两点重合」返回 true（所以 submitForBuild 会继续走）");
        require(path.size() < 2,
                "两点重合的平滑结果应 < 2（触发空注入计划），实测 " + path.size());
    }

    // ───────────────── ⑤ 长段采样过疏 ─────────────────

    /**
     * {@code SAMPLES_PER_SEGMENT = 8} 是固定值，与段长无关：
     * 一段 500 格的直线只会得到 9 个采样点，且由于首段 p0==p1 的钳制，
     * 间距还不均匀（两端约 42 格、中间约 77 格）。
     * 曲线段由此变成「77 格一根弦」的折线，后续管线正是按这些点铺路面。
     */
    private static void testLongSegmentSamplingTooSparse() {
        System.out.println("[A-5] 长段采样密度");
        PlayerRoute r = PlayerRoute.create("long")
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(500, 64, 0), RouteKind.BRIDGE);

        List<BlockPos> path = RouteSmoother.smooth(r, null);
        int maxGap = 0;
        int minGap = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < path.size(); i++) {
            int gap = path.get(i + 1).getX() - path.get(i).getX();
            maxGap = Math.max(maxGap, gap);
            minGap = Math.min(minGap, gap);
        }
        info("500 格段 → 采样 " + path.size() + " 点，间距 " + minGap + "~" + maxGap + " 格（不均匀）");
        require(maxGap <= 16,
                "单段采样间距应 <= 16 格（平滑曲线才不被拉成折线），实测 " + maxGap);
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
