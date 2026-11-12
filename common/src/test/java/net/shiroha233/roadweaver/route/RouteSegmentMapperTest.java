package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;

/**
 * {@link RouteSegmentMapper} 自测。
 *
 * <h2>为什么这段逻辑必须测</h2>
 * 施工管线会用 {@code AccuratePathHeightResolver} 把路径高度**吸附回地面**。
 * 手绘路线要保留玩家给桥/隧道的高度，就必须在映射阶段覆盖它。
 * 这个覆盖如果写错（比如把路的高度也覆盖了、或桥的高度插值错了），
 * 表现是「路浮在半空」或「桥沉到地下」—— 编译期完全发现不了。
 */
public final class RouteSegmentMapperTest {

    private RouteSegmentMapperTest() {
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

        testRoadHeightNotFromPlayer();
        testBridgeHeightFromPlayer();
        testTunnelHeightFromPlayer();
        testBridgeInterpolation();
        testKindPickedByNearestControl();
        testEmptyInputs();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    /** 路：高度不应由玩家决定（要贴地面）。 */
    private static void testRoadHeightNotFromPlayer() {
        System.out.println("[1] 路：高度不由玩家决定");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 999, 0), RouteKind.ROAD)
                .append(new BlockPos(100, 999, 0), RouteKind.ROAD);
        List<BlockPos> path = RouteSmoother.smooth(r, null);
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(50, 64, 0));

        List<RouteSegmentMapper.MappedSegment> m = RouteSegmentMapper.map(centers, path, r);
        require(m.size() == 2, "输出 2 段");
        require(m.get(0).kind() == RouteKind.ROAD, "类型是路");
        require(!m.get(0).heightFromPlayer(), "标记为『高度不来自玩家』");
    }

    /** 桥：高度必须保留玩家给的值。 */
    private static void testBridgeHeightFromPlayer() {
        System.out.println("[2] 桥：高度保留玩家值");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 80, 0), RouteKind.BRIDGE);
        List<BlockPos> path = RouteSmoother.smooth(r, null);
        // 施工中心的地面在 y=64，但玩家把桥放在 80
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(50, 64, 0),
                new BlockPos(100, 64, 0));

        List<RouteSegmentMapper.MappedSegment> m = RouteSegmentMapper.map(centers, path, r);
        require(m.get(0).kind() == RouteKind.BRIDGE, "类型是桥");
        require(m.get(0).heightFromPlayer(), "标记为『高度来自玩家』");
        boolean allHigh = m.stream().allMatch(s -> s.targetY() >= 79 && s.targetY() <= 81);
        require(allHigh, "桥高保持在 ~80，没有被地面 64 覆盖");
    }

    /** 隧道：高度同样保留玩家给的值。 */
    private static void testTunnelHeightFromPlayer() {
        System.out.println("[3] 隧道：高度保留玩家值（含浅隧道）");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 55, 0), RouteKind.TUNNEL)
                .append(new BlockPos(100, 55, 0), RouteKind.TUNNEL);
        List<BlockPos> path = RouteSmoother.smooth(r, null);
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(50, 64, 0));

        List<RouteSegmentMapper.MappedSegment> m = RouteSegmentMapper.map(centers, path, r);
        require(m.get(0).kind() == RouteKind.TUNNEL, "类型是隧道");
        require(m.get(0).heightFromPlayer(), "标记为『高度来自玩家』");
        // 关键：只低 9 格（64->55），不满足现有 resolver 的「低≥7且连续3点」以外的情况
        // 也能保住 —— 这正是覆盖 heightFromPlayer 的意义
        boolean allLow = m.stream().allMatch(s -> s.targetY() == 55);
        require(allLow, "隧道高保持在 55，没有被地面 64 覆盖");
    }

    /** 桥两端不同高：中间应线性过渡。 */
    private static void testBridgeInterpolation() {
        System.out.println("[4] 桥高度沿控制点插值");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 70, 0), RouteKind.BRIDGE)
                .append(new BlockPos(100, 90, 0), RouteKind.BRIDGE);
        List<BlockPos> path = RouteSmoother.smooth(r, null);

        List<BlockPos> centers = List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(50, 0, 0),
                new BlockPos(100, 0, 0));
        List<RouteSegmentMapper.MappedSegment> m = RouteSegmentMapper.map(centers, path, r);

        int y0 = m.get(0).targetY();
        int y50 = m.get(1).targetY();
        int y100 = m.get(2).targetY();

        require(Math.abs(y0 - 70) <= 1, "起点约 70（实得 " + y0 + "）");
        require(Math.abs(y100 - 90) <= 1, "终点约 90（实得 " + y100 + "）");
        require(y50 > y0 && y50 < y100, "中点介于两端之间（实得 " + y50 + "）");
        require(Math.abs(y50 - 80) <= 2, "中点约 80（实得 " + y50 + "）");
    }

    /** 类型由最近的控制点决定（混合路线）。 */
    private static void testKindPickedByNearestControl() {
        System.out.println("[5] 混合路线：各段类型按最近控制点判定");
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)      // 0→10 路
                .append(new BlockPos(10, 80, 0), RouteKind.BRIDGE)   // 10→20 桥
                .append(new BlockPos(20, 55, 0), RouteKind.TUNNEL)   // 20→30 隧道
                .append(new BlockPos(30, 55, 0), RouteKind.TUNNEL);
        List<BlockPos> path = RouteSmoother.smooth(r, null);

        List<BlockPos> centers = List.of(
                new BlockPos(2, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(22, 64, 0),
                new BlockPos(30, 64, 0));
        List<RouteSegmentMapper.MappedSegment> m = RouteSegmentMapper.map(centers, path, r);

        require(m.get(0).kind() == RouteKind.ROAD, "第 0 段是路");
        require(m.get(1).kind() == RouteKind.BRIDGE, "第 1 段是桥");
        require(m.get(2).kind() == RouteKind.TUNNEL, "第 2 段是隧道");
        require(m.get(3).kind() == RouteKind.TUNNEL, "第 3 段也是隧道");

        // 桥段高度应比地面(64)高，隧道段应低
        require(m.get(1).targetY() > 64, "桥段高于地面（实得 " + m.get(1).targetY() + "）");
        require(m.get(2).targetY() < 64, "隧道段低于地面（实得 " + m.get(2).targetY() + "）");
    }

    /** 边界：空输入不炸。 */
    private static void testEmptyInputs() {
        System.out.println("[6] 空输入边界");
        require(RouteSegmentMapper.map(null, null, null).isEmpty(), "全 null 返回空表");
        require(RouteSegmentMapper.map(List.of(), List.of(), null).isEmpty(), "空表返回空表");

        PlayerRoute r = PlayerRoute.create("t");
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0));
        List<RouteSegmentMapper.MappedSegment> m =
                RouteSegmentMapper.map(centers, new ArrayList<>(), r);
        require(m.size() == 1, "无路径点时仍能给出 1 段");
        require(m.get(0).kind() == RouteKind.ROAD, "默认类型是路");
    }
}
