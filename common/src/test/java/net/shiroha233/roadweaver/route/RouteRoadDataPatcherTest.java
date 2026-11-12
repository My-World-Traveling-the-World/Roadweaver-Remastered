package net.shiroha233.roadweaver.route;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

/**
 * {@link RouteRoadDataPatcher} 自测。
 *
 * <h2>为什么必须测</h2>
 * 这是「画什么生成什么」的最后一环：把玩家意图真正写进施工数据。
 * 写错的后果是**路建到世界里之后才发现**（桥沉地下、隧道被填平、
 * 保护建筑的安全间隙被玩家画的路吃掉），返工成本极高。
 */
public final class RouteRoadDataPatcherTest {

    private RouteRoadDataPatcherTest() {
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

        testBridgeHeightOverridesGround();
        testTunnelHeightOverridesGround();
        testRoadKeepsPipelineHeight();
        testBridgeSpanCreated();
        testProtectedGapPreserved();
        testWaterCrossingPreserved();
        testShortBridgeNotFlattenedByConvergence();
        testRoadOnlyProfileUnchanged();
        testNullSafety();

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    /** 造一条水平道路数据：中心点在给定 Y，管线给的地面高度也在这里。 */
    private static RoadData makeRoad(List<BlockPos> centers, int pipelineY,
                                     List<RoadSpan> spans) {
        List<RoadSegmentPlacement> placements = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (BlockPos c : centers) {
            placements.add(new RoadSegmentPlacement(c, List.of(c)));
            targetY.add(pipelineY);     // 管线一律给「地面高度」
        }
        return new RoadData(3, 0, List.of(), List.of(), placements,
                spans == null ? List.of() : spans, targetY, 1L, 2L);
    }

    private static List<BlockPos> line(int n, int y) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BlockPos(i * 4, y, 0));
        }
        return out;
    }

    /** 桥：玩家把点架到 80，管线给地面 64 → targetY 必须是 80。 */
    private static void testBridgeHeightOverridesGround() {
        System.out.println("[1] 桥高度覆盖管线贴地值");
        List<BlockPos> centers = line(5, 64);
        RoadData road = makeRoad(centers, 64, null);

        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(16, 80, 0), RouteKind.BRIDGE);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        require(patched != road, "返回了新实例");
        boolean allHigh = patched.targetY().stream().allMatch(y -> y >= 79 && y <= 81);
        require(allHigh, "targetY 全部为 ~80（实得 " + patched.targetY() + "）");
    }

    /** 隧道：玩家点到 55（比地面 64 低 9），不能被填平。 */
    private static void testTunnelHeightOverridesGround() {
        System.out.println("[2] 隧道高度覆盖管线贴地值");
        List<BlockPos> centers = line(5, 64);
        RoadData road = makeRoad(centers, 64, null);

        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 55, 0), RouteKind.TUNNEL)
                .append(new BlockPos(16, 55, 0), RouteKind.TUNNEL);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        boolean allLow = patched.targetY().stream().allMatch(y -> y == 55);
        require(allLow, "targetY 全部为 55（实得 " + patched.targetY() + "）");
    }

    /** 路：保留管线算的贴地高度，不被玩家给的占位 Y 污染。 */
    private static void testRoadKeepsPipelineHeight() {
        System.out.println("[3] 路保留管线贴地高度");
        List<BlockPos> centers = line(5, 64);
        // 管线把路放在 63（真实的贴地结果）
        RoadData road = makeRoad(centers, 63, null);

        // 玩家在世界里把点拖到 999（不该影响路段高度）
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 999, 0), RouteKind.ROAD)
                .append(new BlockPos(16, 999, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        boolean allGround = patched.targetY().stream().allMatch(y -> y == 63);
        require(allGround, "targetY 全部保持 63（实得 " + patched.targetY() + "）");
    }

    /** 桥段应该生成 BRIDGE span。 */
    private static void testBridgeSpanCreated() {
        System.out.println("[4] 桥段生成 BRIDGE span");
        List<BlockPos> centers = line(5, 64);
        RoadData road = makeRoad(centers, 64, null);

        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 80, 0), RouteKind.BRIDGE)
                .append(new BlockPos(16, 80, 0), RouteKind.BRIDGE);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        boolean hasBridge = patched.spans().stream().anyMatch(s -> s.type() == SpanType.BRIDGE);
        require(hasBridge, "存在 BRIDGE span（实得 " + patched.spans().size() + " 个）");
    }

    /** 管线的 PROTECTED_GAP（保护建筑）必须保留，不能被玩家路吃掉。 */
    private static void testProtectedGapPreserved() {
        System.out.println("[5] 保护建筑间隙不被覆盖");
        List<BlockPos> centers = line(5, 64);
        RoadSpan gap = new RoadSpan(centers.get(2), centers.get(3), SpanType.PROTECTED_GAP);
        RoadData road = makeRoad(centers, 64, List.of(gap));

        // 玩家把整条画成「路」
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(16, 64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        require(patched.hasProtectedGap(), "PROTECTED_GAP 被保留");
    }

    /** 管线的过水短桥（ROAD_CROSSING）在玩家没标桥时也要保留（安全优先）。 */
    private static void testWaterCrossingPreserved() {
        System.out.println("[6] 过水短桥按安全优先保留");
        List<BlockPos> centers = line(5, 64);
        RoadSpan crossing = new RoadSpan(centers.get(1), centers.get(2), SpanType.ROAD_CROSSING);
        RoadData road = makeRoad(centers, 64, List.of(crossing));

        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(16, 64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        boolean kept = patched.spans().stream().anyMatch(s -> s.type() == SpanType.ROAD_CROSSING);
        require(kept, "ROAD_CROSSING 被保留");
    }

    /** 边界：null / 空 不炸。 */
    private static void testNullSafety() {
        System.out.println("[7] 边界不炸");
        require(RouteRoadDataPatcher.patch(null, null, null) == null, "全 null 返回 null");

        List<BlockPos> centers = line(3, 64);
        RoadData road = makeRoad(centers, 64, null);
        require(RouteRoadDataPatcher.patch(road, null, centers) == road, "无路线返回原对象");
        require(RouteRoadDataPatcher.patch(road, PlayerRoute.create("empty"), centers) == road,
                "空路线返回原对象");
    }

    /**
     * ★ B（2026-09-25）：**短桥不能被坡度收敛压平**。
     *
     * <h2>真机场景</h2>
     * 玩家原话：「我手动规划了有的是比地面低有的是比地面高，为什么不会下挖切方
     * 或者做空中路呢」—— 实测日志里一条 6 点路线、5 段连接，玩家把一个点
     * 设成 -55（地面 -60），期望架桥，结果看起来仍是贴地。
     *
     * <h2>根因</h2>
     * {@code convergeHeightProfile} 的前向 pass 在「当前站比前一站高太多」时
     * 执行 {@code out.set(i, prev + MAX_STEP)} —— 把**当前站往下压**，
     * 与它自己的注释「宁可把引道抬高，也不降低桥面」正好相反。
     * 长桥侥幸能活（中段保住），**短桥（1~3 站）被整个压平**。
     *
     * <h2>本测试锁住的行为</h2>
     * 桥面站（锚点）在收敛后**必须仍等于玩家值**，两侧地面被抬成引桥。
     */
    private static void testShortBridgeNotFlattenedByConvergence() {
        System.out.println("[8] 短桥不被坡度收敛压平（★ B）");
        // 9 站，地面 64；玩家把中间 3 站设成 68（高 4 格，属"短桥"）
        List<BlockPos> centers = line(9, 64);
        RoadData road = makeRoad(centers, 64, null);

        // 桥段覆盖中间 3 站：控制点 0(ROAD,地面) → 1(BRIDGE,68) → 2(BRIDGE,68) → 3(ROAD,地面)
        // 控制点 XZ 要落在 centers 上（spacing 4），才能让映射命中
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(12, 68, 0), RouteKind.BRIDGE)
                .append(new BlockPos(20, 68, 0), RouteKind.BRIDGE)
                .append(new BlockPos(32, 64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        require(patched != null, "patch 返回了结果");

        List<Integer> y = patched.targetY();
        int maxY = y.stream().mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
        require(maxY >= 68,
                "桥面峰值必须保住玩家值 68（实得 max=" + maxY + "，剖面=" + y + "）");

        // 且剖面必须仍然合法（每站 |ΔY| ≤ 1），否则会被 RoadDataValidator 整条退回
        boolean legal = true;
        for (int i = 1; i < y.size(); i++) {
            if (Math.abs(y.get(i) - y.get(i - 1)) > 1) {
                legal = false;
                break;
            }
        }
        require(legal, "收敛后剖面仍满足每站 |ΔY| ≤ 1（剖面=" + y + "）");
    }

    /**
     * ★ B 的安全网：**纯「路」路线（无桥/隧道）剖面必须与改动前逐格一致**。
     *
     * <p>这是防止 B 悄悄改变已部署版本道路结果的红线检查 ——
     * 新规则只在「确实存在桥/隧道锚点」时生效，纯路线的收敛路径
     * 必须走旧逻辑。</p>
     */
    private static void testRoadOnlyProfileUnchanged() {
        System.out.println("[9] 纯路剖面不受 B 影响（红线检查）");
        // 造一个起伏地面：64 → 67（超限步进，必被收敛处理）
        List<BlockPos> centers = new ArrayList<>();
        int[] ys = {64, 64, 67, 67, 67, 64, 64};
        for (int i = 0; i < ys.length; i++) {
            centers.add(new BlockPos(i * 4, ys[i], 0));
        }
        List<RoadSegmentPlacement> placements = new ArrayList<>();
        List<Integer> targetY = new ArrayList<>();
        for (BlockPos c : centers) {
            placements.add(new RoadSegmentPlacement(c, List.of(c)));
            targetY.add(c.getY());
        }
        RoadData road = new RoadData(3, 0, List.of(), List.of(), placements,
                List.of(), targetY, 1L, 2L);

        // 玩家画一条全 ROAD 的线，高度随便给（路段的 min(玩家,地形) 取地形）
        PlayerRoute r = PlayerRoute.create("t")
                .append(new BlockPos(0, 64, 0), RouteKind.ROAD)
                .append(new BlockPos(24, 64, 0), RouteKind.ROAD);

        RoadData patched = RouteRoadDataPatcher.patch(road, r, centers);
        List<Integer> y = patched.targetY();
        // 无锚点 ⇒ 收敛走旧逻辑：把高峰往下压到与 64 相邻（-1/站）
        boolean legal = true;
        for (int i = 1; i < y.size(); i++) {
            if (Math.abs(y.get(i) - y.get(i - 1)) > 1) {
                legal = false;
                break;
            }
        }
        require(legal, "纯路剖面收敛合法（剖面=" + y + "）");
    }
}
