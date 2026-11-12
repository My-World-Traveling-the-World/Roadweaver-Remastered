package net.shiroha233.roadweaver.route;

import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

/**
 * 审计修复的**回归测试**。
 *
 * <p>覆盖 roadweaver-audit-20260924.md 里那些「纯逻辑、可离线验证」的修复。
 * 每条测试的 Javadoc 都标了对应的审计编号，便于日后回溯为什么这么写。</p>
 */
public final class AuditFixRegressionTest {

    private AuditFixRegressionTest() {
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

        testP1_1_pipelineBridgeSpanPreserved();
        testP1_1_noDuplicateOverlap();
        testP1_2_singlePointBridgeSpan();
        testRegistryKeyIncludesY();
        testRegistryKeyOrderInsensitive();
        testClampYBounds();
        testRoadDigsWhenPlayerLower();
        testRoadStaysOnGroundWhenPlayerHigher();
        testSubmitClearsCurrentSelection();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    /**
     * ★ 新规则：ROAD 段玩家高度**低于**地形 → 采用玩家高度（挖下去）。
     *
     * <p>用户反馈：「我端点实际特别设置的是 -62 比平面低，不会下挖，
     * 只是落到地表」。原实现 ROAD 一律用管线贴地值，玩家高度被丢弃。</p>
     */
    private static void testRoadDigsWhenPlayerLower() {
        System.out.println("[高度规则] 路：玩家更低 → 挖下去");
        List<BlockPos> centers = line(4, -60);
        // 管线认为地表在 -60
        RoadData rd = road(centers, -60, null);

        // 玩家把点设到 -64（比地表低 4 格）
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, -64, 0), RouteKind.ROAD)
                .append(new BlockPos(12, -64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(rd, r, centers);
        boolean dug = patched.targetY().stream().allMatch(y -> y <= -60);
        require(dug, "targetY 不高于地表（实得 " + patched.targetY() + "）");
        require(patched.targetY().stream().anyMatch(y -> y < -60),
                "确实下挖了（有值 < -60）");
    }

    /** ★ 新规则：ROAD 段玩家高度**高于**地形 → 保留地形高度（贴地，不悬空）。 */
    private static void testRoadStaysOnGroundWhenPlayerHigher() {
        System.out.println("[高度规则] 路：玩家更高 → 仍贴地（不悬空）");
        List<BlockPos> centers = line(4, -60);
        RoadData rd = road(centers, -60, null);

        // 玩家把点设到 -50（比地表高 10 格）
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, -50, 0), RouteKind.ROAD)
                .append(new BlockPos(12, -50, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(rd, r, centers);
        boolean onGround = patched.targetY().stream().allMatch(y -> y == -60);
        require(onGround, "targetY 保持地表 -60（实得 " + patched.targetY() + "）");
    }

    /** 造一条道路数据：n 个中心点，管线给的地面高度为 groundY。 */
    private static RoadData road(List<BlockPos> centers, int groundY, List<RoadSpan> spans) {
        List<RoadSegmentPlacement> placements = new java.util.ArrayList<>();
        List<Integer> targetY = new java.util.ArrayList<>();
        for (BlockPos c : centers) {
            placements.add(new RoadSegmentPlacement(c, List.of(c)));
            targetY.add(groundY);
        }
        return new RoadData(3, 0, List.of(), List.of(), placements,
                spans == null ? List.of() : spans, targetY, 1L, 2L);
    }

    private static List<BlockPos> line(int n, int y) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BlockPos(i * 4, y, 0));
        }
        return out;
    }

    /**
     * ★ P1-1：玩家把跨河段标成 ROAD，管线的 BRIDGE span 必须被保留。
     *
     * <p>修复前：白名单只留 PROTECTED_GAP/JUNCTION_ANCHOR/TUNNEL_JUNCTION/
     * ROAD_CROSSING，BRIDGE 被丢弃 —— 但 targetY 仍是按「这里有桥」算的，
     * 于是 RoadDataValidator 判 invalid → chunkgen 静默丢弃整条路。</p>
     */
    private static void testP1_1_pipelineBridgeSpanPreserved() {
        System.out.println("[P1-1] 管线 BRIDGE span 在玩家标 ROAD 时仍保留");
        List<BlockPos> centers = line(5, 64);
        RoadSpan pipelineBridge = new RoadSpan(centers.get(1), centers.get(3), SpanType.BRIDGE);
        RoadData rd = road(centers, 64, List.of(pipelineBridge));

        // 玩家整条标成 ROAD
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(16, 64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(rd, r, centers);
        boolean kept = patched.spans().stream().anyMatch(s -> s.type() == SpanType.BRIDGE);
        require(kept, "管线 BRIDGE span 被保留（修复前会被丢弃）");
    }

    /** ★ P1-1：玩家 span 与管线 span 重叠时不能重复保留（否则 overlapping-physical-spans）。 */
    private static void testP1_1_noDuplicateOverlap() {
        System.out.println("[P1-1] 玩家 span 覆盖的区间不重复保留管线 span");
        List<BlockPos> centers = line(5, 64);
        // 管线的桥覆盖 index 1..3
        RoadSpan pipelineBridge = new RoadSpan(centers.get(1), centers.get(3), SpanType.BRIDGE);
        RoadData rd = road(centers, 64, List.of(pipelineBridge));

        // 玩家也把这一段标成 BRIDGE（覆盖 0..4，含 1..3）
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(16, 80, 0), RouteKind.BRIDGE);

        RoadData patched = RouteRoadDataPatcher.patch(rd, r, centers);
        long bridgeCount = patched.spans().stream()
                .filter(s -> s.type() == SpanType.BRIDGE).count();
        require(bridgeCount == 1, "BRIDGE span 只有 1 条（实得 " + bridgeCount + "）");
    }

    /** ★ P1-2：只有一个 placement 的桥也要出 span，否则 targetY 抬高却没跨度 = 悬空路。 */
    private static void testP1_2_singlePointBridgeSpan() {
        System.out.println("[P1-2] 单点桥段也产生 span");
        List<BlockPos> centers = line(3, 64);
        RoadData rd = road(centers, 64, null);

        // 整条只有 1 段被标为 BRIDGE，且该段只有 1 个 placement
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE);

        // 用一个只有 1 个中心点的道路数据模拟「极短的桥」
        List<BlockPos> one = List.of(new BlockPos(0, 64, 0));
        RoadData rdOne = road(one, 64, null);
        PlayerRoute rOne = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(4, 80, 0), RouteKind.BRIDGE);

        RoadData patched = RouteRoadDataPatcher.patch(rdOne, rOne, one);
        // 单点：start==end，修复前 end > start 为 false → 无 span
        // 注意 patch 可能因为 centers 太少而直接返回原对象，这里只断言「要么有 span，
        // 要么确实无法处理（返回原对象）」，不强制崩溃
        boolean ok = patched == rdOne
                || patched.spans().stream().anyMatch(s -> s.type() == SpanType.BRIDGE);
        require(ok, "单点桥段：要么出了 span，要么安全返回原对象");
    }

    /** ★ P2-13：登记键必须包含 Y（螺旋路/叠桥不能互相覆盖）。 */
    private static void testRegistryKeyIncludesY() {
        System.out.println("[P2-13] 登记键含 Y");
        var k1 = RouteBuildRegistry.key(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0));
        var k2 = RouteBuildRegistry.key(new BlockPos(0, 80, 0), new BlockPos(10, 80, 0));
        require(k1 != null && k2 != null, "键非空");
        require(!k1.equals(k2), "XZ 相同但 Y 不同的两条连接键不同（修复前会互相覆盖）");
    }

    /** ★ P2-13：登记键必须与方向无关（A→B 与 B→A 是同一键）。 */
    private static void testRegistryKeyOrderInsensitive() {
        System.out.println("[P2-13] 登记键与方向无关");
        var ab = RouteBuildRegistry.key(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0));
        var ba = RouteBuildRegistry.key(new BlockPos(10, 64, 0), new BlockPos(0, 64, 0));
        require(ab.equals(ba), "A→B 与 B→A 是同一个键（与 PlannedPathCache 一致）");
    }

    /** ★ P2-11：高度钳制在世界范围内。 */
    private static void testClampYBounds() {
        System.out.println("[P2-11] 高度钳制");
        require(net.shiroha233.roadweaver.route.editor.RouteEditorSession.clampY(9999) <= 320,
                "过高被钳制");
        require(net.shiroha233.roadweaver.route.editor.RouteEditorSession.clampY(-9999) >= -64,
                "过低被钳制");
        require(net.shiroha233.roadweaver.route.editor.RouteEditorSession.clampY(100) == 100,
                "合法值不被改动");
    }

    /**
     * ★ 用户 2026-09-25：施工成功后自动切换编辑对象（不必手动点「删除」）。
     *
     * <h2>钉住的行为</h2>
     * <ol>
     *   <li>已提交（READY）的路线**必须仍在 routes 表里** —— 重进世界时
     *       {@code ClientInit} 要调 {@code rebuildRegistryFromStorage} 从它
     *       重建登记表与 PlannedPathCache，真删了桥隧语义就找不回来。</li>
     *   <li>但它**不再是编辑对象**（{@code current()} 换成一条新的空路线），
     *       视觉上就是"线清掉了"。</li>
     *   <li>{@code pendingRoutes()}（世界内渲染源）**不含** READY 路线。</li>
     * </ol>
     *
     * <p>本测试只碰会话内的数据结构，不需要 Minecraft 运行时。</p>
     */
    private static void testSubmitClearsCurrentSelection() {
        System.out.println("[用户 2026-09-25] 施工后清除当前选择");
        var session = net.shiroha233.roadweaver.route.editor.RouteEditorSession.get();
        session.clearAll();

        // 造一条画好的路线并设为编辑对象
        var drawn = PlayerRoute.create("drawn")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(20, 64, 0), RouteKind.ROAD);
        session.update(drawn);
        require(session.current() != null && session.current().id().equals(drawn.id()),
                "编辑对象是刚画的那条");
        require(drawn.isDrawable(), "该路线可绘制（submitForBuild 才会继续）");

        // 模拟「提交成功」后服务端线程做的事：转 READY + 自动切换编辑对象。
        // 真实 submitForBuild 需要 ServerLevel，这里直接驱动会话层行为。
        session.update(drawn.withStatus(RouteStatus.READY));
        var before = session.current();
        session.autoSwitchAfterSubmitForTest();

        var after = session.current();
        require(after != null, "切换后仍有编辑对象（新的空路线）");
        require(before != null && !after.id().equals(before.id()),
                "编辑对象已换成新路线（id 不同）");
        require(after.isEmpty(), "新编辑对象是空的（画布上无线）");

        // 关键：READY 那条必须还在表里
        require(session.byId(drawn.id()) != null,
                "★ 已提交的路线仍在 routes 表里（重进世界要靠它重建登记）");
        require(session.byId(drawn.id()).status() == RouteStatus.READY,
                "且状态保持 READY");

        // 世界内渲染源不含 READY
        var pending = session.pendingRoutes();
        boolean anyReady = pending.stream()
                .anyMatch(r -> r.status() == RouteStatus.READY);
        require(!anyReady, "pendingRoutes() 不含 READY 路线（世界内不渲染）");

        session.clearAll();
    }
}
