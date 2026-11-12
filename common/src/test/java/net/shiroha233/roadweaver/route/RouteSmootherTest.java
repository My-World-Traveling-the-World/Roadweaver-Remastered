package net.shiroha233.roadweaver.route;

import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;

/**
 * {@link RouteSmoother} 与 {@link PlayerRoute} 的自测。
 *
 * <p>风格与工程既有测试一致（{@code main} + {@code require}），
 * 不引入 JUnit 依赖。</p>
 *
 * <h2>重点验证</h2>
 * <ol>
 *   <li>直线三点不产生多余弯折</li>
 *   <li>拐角处确实被平滑（中间出现不在原折线上的点）</li>
 *   <li>★ 类型切换处硬切（桥段不越界到路段的点）</li>
 *   <li>桥的高度在两端之间线性</li>
 *   <li>撤销栈语义正确</li>
 *   <li>增删点后点的顺序与 kind 延续正确</li>
 * </ol>
 */
public final class RouteSmootherTest {

    private RouteSmootherTest() {
    }

    private static int checks = 0;

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("  ok  " + what);
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testStraightLineStaysStraight();
        testCornerIsSmoothed();
        testKindBoundaryHardCut();
        testBridgeHeightInterpolates();
        testRoadSnapsToGround();
        testUndoStack();
        testPointInsertRemove();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    // ───────────────────── ① 直线 ─────────────────────

    private static void testStraightLineStaysStraight() {
        System.out.println("[1] 三点共线 → 平滑后仍是直线");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(10, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 64, 0), RouteKind.BRIDGE);

        List<BlockPos> pts = RouteSmoother.smooth(r, null);
        require(!pts.isEmpty(), "产生了路径点");
        // 共线的三点，Catmull-Rom 不应把 Z 拉偏
        boolean allOnAxis = pts.stream().allMatch(p -> p.getZ() == 0);
        require(allOnAxis, "全部落在 z=0 上（共线不产生弯折）");
        require(pts.get(0).equals(new BlockPos(0, 64, 0)), "起点是第一个控制点");
        require(pts.get(pts.size() - 1).equals(new BlockPos(20, 64, 0)), "终点是最后一个控制点");
    }

    // ───────────────────── ② 拐角平滑 ─────────────────────

    private static void testCornerIsSmoothed() {
        System.out.println("[2] 直角拐弯 → 出现不在原折线上的中间点");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 64, 20), RouteKind.BRIDGE);

        List<BlockPos> pts = RouteSmoother.smooth(r, null);
        // 原折线只有 (0,0)->(20,0)->(20,20) 三个点；
        // 平滑后应出现 x!=0/20 且 z!=0/20 的点（说明确实在走曲线）
        boolean hasCurved = pts.stream().anyMatch(p ->
                p.getX() != 0 && p.getX() != 20 && p.getZ() != 0 && p.getZ() != 20);
        require(hasCurved, "出现拐角内侧的过渡点（曲线生效）");
        require(pts.get(0).equals(new BlockPos(0, 64, 0)), "起点保持");
        require(pts.get(pts.size() - 1).equals(new BlockPos(20, 64, 20)), "终点保持");
    }

    // ─────────────────── ③ 类型切换硬切 ───────────────────

    private static void testKindBoundaryHardCut() {
        System.out.println("[3] 路→桥切换 → 桥段不越过切换点");
        // (0,0) 路 (10,0) | 桥 (20,0) (30,0)
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(10, 64, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 64, 100), RouteKind.BRIDGE)
                .append(new BlockPos(30, 64, 100), RouteKind.BRIDGE);

        // 桥的 Y 给 70，路的 Y 给 64 —— 若硬切生效，桥段所有点的 Y 应该都在 64~70 之间且
        // 不会跑到路段的 z=0 之外去做曲线过渡
        PlayerRoute r2 = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(10, 70, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 70, 0), RouteKind.BRIDGE);

        List<BlockPos> pts = RouteSmoother.smooth(r2, null);
        require(!pts.isEmpty(), "有输出");

        // 切换点是第 2 个控制点 (10,70,0)；它必须出现在路径里
        boolean hasBoundary = pts.contains(new BlockPos(10, 70, 0));
        require(hasBoundary, "类型切换点存在于路径中");

        // 桥段（下标 >= 切换点）的 Y 必须 >= 70（桥不贴地面，保持玩家给的高度）
        int boundaryIdx = pts.indexOf(new BlockPos(10, 70, 0));
        boolean bridgeStaysHigh = pts.subList(boundaryIdx, pts.size()).stream()
                .allMatch(p -> p.getY() >= 70);
        require(bridgeStaysHigh, "桥段高度不被路的贴地逻辑污染");

        // 用带地形的 provider 再跑一遍，验证路贴地面、桥不贴
        List<BlockPos> pts2 = RouteSmoother.smooth(r2, (x, z) -> 50);
        int bi2 = pts2.indexOf(new BlockPos(10, 70, 0));
        require(bi2 >= 0, "带地形时切换点仍在");
        boolean roadOnGround = pts2.subList(0, bi2).stream().allMatch(p -> p.getY() == 50);
        require(roadOnGround, "路段的 Y 取地面高度（50）");
        boolean bridgeUnaffected = pts2.subList(bi2, pts2.size()).stream()
                .allMatch(p -> p.getY() >= 70);
        require(bridgeUnaffected, "桥段不受地面高度影响");
    }

    // ─────────────────── ④ 桥高度插值 ───────────────────

    private static void testBridgeHeightInterpolates() {
        System.out.println("[4] 桥两端不同高 → 中间线性过渡");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 70, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 80, 0), RouteKind.BRIDGE);

        List<BlockPos> pts = RouteSmoother.smooth(r, null);
        int minY = pts.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = pts.stream().mapToInt(BlockPos::getY).max().orElse(0);
        require(minY >= 70 && maxY <= 80, "所有点 Y 在 70~80 之间（线性插值不越界）");
        require(minY == 70 && maxY == 80, "覆盖了完整区间");
    }

    // ─────────────────── ⑤ 路贴地面 ───────────────────

    private static void testRoadSnapsToGround() {
        System.out.println("[5] 路 → 取地形高度");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 999, 0), RouteKind.ROAD)
                .append(new BlockPos(10, 999, 0), RouteKind.ROAD);

        List<BlockPos> pts = RouteSmoother.smooth(r, (x, z) -> 63 + (x % 3));
        boolean allNearGround = pts.stream().allMatch(p -> p.getY() >= 63 && p.getY() <= 65);
        require(allNearGround, "路的 Y 全部来自地形采样，忽略控制点上的 999");
    }

    // ─────────────────── ⑥ 撤销栈 ───────────────────

    private static void testUndoStack() {
        System.out.println("[6] 撤销栈");
        RouteUndoStack stack = new RouteUndoStack(4);
        PlayerRoute a = PlayerRoute.create("t");
        stack.record(a);
        PlayerRoute b = a.append(new BlockPos(1, 64, 1), RouteKind.ROAD);
        stack.record(b);
        PlayerRoute c = b.append(new BlockPos(2, 64, 2), RouteKind.ROAD);

        require(stack.canUndo(), "可以撤销");
        PlayerRoute back1 = stack.undo(c);
        require(back1.pointCount() == 1, "撤销一步回到 1 个点");
        PlayerRoute back2 = stack.undo(back1);
        require(back2.pointCount() == 0, "再撤销回到 0 个点");
        require(!stack.canUndo(), "没有更多可撤销");

        require(stack.canRedo(), "可以重做");
        PlayerRoute fwd = stack.redo(back2);
        require(fwd.pointCount() == 1, "重做回到 1 个点");

        // 深度限制
        RouteUndoStack limited = new RouteUndoStack(2);
        PlayerRoute p = PlayerRoute.create("t");
        for (int i = 0; i < 5; i++) {
            limited.record(p);
            p = p.append(new BlockPos(i, 64, 0), RouteKind.ROAD);
        }
        require(limited.undoDepth() == 2, "栈深被限制在 2");
    }

    // ─────────────────── ⑦ 增删点 ───────────────────

    private static void testPointInsertRemove() {
        System.out.println("[7] 增删点与 kind 延续");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(10, 64, 0), RouteKind.BRIDGE);

        PlayerRoute withMid = r.insertAfter(0, new BlockPos(5, 64, 0));
        require(withMid.pointCount() == 3, "插入后 3 个点");
        require(withMid.point(1).kind() == RouteKind.ROAD, "插入点继承前一点的 kind（路）");
        require(withMid.point(2).kind() == RouteKind.BRIDGE, "原点顺序不变，桥段仍在");

        PlayerRoute removed = withMid.removePoint(1);
        require(removed.pointCount() == 2, "删除后 2 个点");
        require(removed.point(1).pos().equals(new BlockPos(10, 64, 0)), "删的是中点");

        // 改段类型
        PlayerRoute retyped = r.withSegmentKind(0, RouteKind.TUNNEL);
        require(retyped.kindOfSegment(0) == RouteKind.TUNNEL, "第 0 段改为隧道");

        // 挪位 / 改高
        PlayerRoute moved = r.replacePoint(0, r.point(0).withXZ(99, 88));
        require(moved.point(0).pos().getX() == 99 && moved.point(0).pos().getY() == 64,
                "挪位只改 XZ，Y 不变");
        PlayerRoute lifted = r.replacePoint(0, r.point(0).withY(120));
        require(lifted.point(0).pos().getY() == 120, "改高生效");

        // 就近查找
        int idx = r.findNearestPoint(new BlockPos(9, 64, 0), 3);
        require(idx == 1, "就近查到第 1 个点");
        require(r.findNearestPoint(new BlockPos(500, 64, 0), 3) == -1, "超出距离查不到");
    }
}
