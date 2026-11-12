package net.shiroha233.roadweaver.pathlogic.junction;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator.Cluster;
import net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator.CrossingPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.058：定甲板与共享甲板的回归（缺陷 A / 缺陷 B）。
 *
 * <p>真机取证：一局 32 个跨度**全部** {@code highestWater=none}（连按定义必过水的
 * {@code type=ROAD_CROSSING} 也一样），于是每一跨都走干峡谷分支
 * {@code minimumDeck = max(两端站Y) + 2}（51=49+2、54=52+2…），桥梁甲板贴在水面上。
 * 根因是**两套水面口径**：认跨度用逐列水掩码，定甲板用 Streams 一条独立查询。
 * 缺陷 B：0.9.057 的协商只统一 XZ，不统一甲板（两条并行桥 48 与 65，差 17 格），
 * 且一条路多次过水时只交出最长的一段 ⇒ 冲突那次不是最长段时该路静默缺席。</p>
 *
 * <p>本组钉三件事，任何一条破了真机上立刻能看出来：</p>
 * <ol>
 *   <li>一簇内两个过河点 ⇒ {@code sharedDeckY} 与输入顺序无关（且取簇内最大值）；</li>
 *   <li>干跨度（无水）仍然走 {@code max(站Y) + clearance}，**不得**被改成 0；</li>
 *   <li>有水面时 {@code minimumDeck >= 最高水面 + clearance}。</li>
 * </ol>
 */
final class CrossingSharedDeckTest {

    /** 与 RoadConstants.DEFAULT_CROSSING_CLUSTER_RADIUS 同值；改默认值必须同时看这里。 */
    private static final int RADIUS = 24;

    // ---------------- ① sharedDeckY 与输入顺序无关 ----------------

    @Test
    void sharedDeckYIsIndependentOfInputOrder() {
        CrossingPoint a = new CrossingPoint("road-a", 236, -170, 48, 3, 50);
        CrossingPoint b = new CrossingPoint("road-b", 248, -170, 40, 2, 67);

        List<Cluster> forward = CrossingNegotiator.negotiate(List.of(a, b), RADIUS);
        List<Cluster> backward = CrossingNegotiator.negotiate(List.of(b, a), RADIUS);

        assertEquals(1, forward.size(), "距离 12 ≤ radius 24 必须合成一簇");
        assertEquals(1, backward.size(), "输入顺序不得改变成簇结果");
        assertEquals(67, forward.get(0).sharedDeckY(), "共享甲板取簇内最大值（67 > 50）");
        assertEquals(forward.get(0).sharedDeckY(), backward.get(0).sharedDeckY(),
                "sharedDeckY 必须与输入顺序无关");
        assertEquals(forward.get(0).sharedX(), backward.get(0).sharedX(),
                "sharedX 同样不得随输入顺序变化");
        assertEquals(forward.get(0).sharedZ(), backward.get(0).sharedZ());

        // 三个点、六种顺序：max 是全序无关的，任何一种排列都必须得到同一个 67。
        CrossingPoint c = new CrossingPoint("road-c", 240, -166, 32, 4, 61);
        List<CrossingPoint> canonical = new ArrayList<>(List.of(a, b, c));
        for (List<CrossingPoint> permutation : permutations(canonical)) {
            List<Cluster> clusters = CrossingNegotiator.negotiate(permutation, RADIUS);
            assertEquals(1, clusters.size(), "三点必须仍在同一簇内：" + permutation.size());
            assertEquals(67, clusters.get(0).sharedDeckY(),
                    "打乱输入顺序后 sharedDeckY 必须完全相同：" + owners(permutation));
        }

        // 甲板未知（旧 5 参数调用）时不得凭空造一个 0 出来。
        List<Cluster> unknown = CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-a", 0, 0, 32, 2),
                new CrossingPoint("road-b", 10, 0, 32, 2)), RADIUS);
        assertEquals(Integer.MIN_VALUE, unknown.get(0).sharedDeckY(),
                "没有甲板信息时必须保持 MIN_VALUE（调用方据此跳过压剖面），不能是 0");

        // 0.9.058：一条路可以交多个湿段，但「同一条路的两段水」自己跟自己不算共享。
        assertTrue(CrossingNegotiator.negotiate(List.of(
                        new CrossingPoint("road-a", 0, 0, 32, 2, 60),
                        new CrossingPoint("road-a", 10, 0, 32, 2, 60)), RADIUS).isEmpty(),
                "同一条路的两个湿段不得互相构成共享点（否则单条路会平白被第二遍重算）");
    }

    // ---------------- ② 干跨度仍然走 max(站Y)+clearance ----------------

    @Test
    void drySpanKeepsTheBankFloorInsteadOfZero() {
        // 真机实测的那几个值：51 = 49 + 2、54 = 52 + 2、62 = 60 + 2、71 = 69 + 2、73 = 71 + 2。
        assertEquals(51, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 49, 49, 2));
        assertEquals(52, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 49, 50, 2),
                "两端不等时取较大者：max(49,50)+2");
        assertEquals(52, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 50, 49, 2),
                "取 max 而不是「起点/终点」，与两端顺序无关：max(50,49)+2");
        assertEquals(62, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 60, 60, 2));
        assertEquals(73, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 71, 68, 2));
        assertNotEquals(0, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 49, 49, 2),
                "干峡谷分支绝不能退化成 0");

        // 负坐标峡谷：基准跟着站 Y 走，不得被夹到 0 或正数。
        assertEquals(-62, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, -64, -80, 2),
                "深干峡谷（Y=-64/-80）的甲板必须在负坐标区，max(-64,-80)+2");
        assertTrue(BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, -64, -80, 2) < 0);

        // clearance 下限是 1（与调用方 Math.max(1, cfg.bridgeDeckClearance()) 同口径）。
        assertEquals(50, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 49, 49, 0));
        assertEquals(50, BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 49, 49, -5));
    }

    // ---------------- ③ 有水面时 minimumDeck >= 水面 + clearance ----------------

    @Test
    void waterSpanDeckClearsTheWaterSurface() {
        for (int clearance = 1; clearance <= 4; clearance++) {
            for (int water = 50; water <= 70; water++) {
                int minimumDeck = BridgeDeckProfile.minimumDeckY(water, 70, 71, clearance);
                assertEquals(water + clearance, minimumDeck,
                        "有水面时甲板只由水面决定，不被高岸拖走：water=" + water);
                assertTrue(minimumDeck >= water + clearance,
                        "minimumDeck 必须 ≥ highestWater + clearance");
            }
        }

        // 河在谷里（两岸低于水面）：甲板仍然要盖过水面，否则就是「桥泡在水里」。
        assertTrue(BridgeDeckProfile.minimumDeckY(63, 55, 56, 2) >= 63 + 2,
                "两岸比水面低时甲板不得被岸站拖到水面以下");

        // 水面未知哨兵 Integer.MIN_VALUE 绝不能被当成「很低的 Y」参与算术。
        int dryDeck = BridgeDeckProfile.minimumDeckY(Integer.MIN_VALUE, 60, 61, 2);
        assertEquals(63, dryDeck);
        assertTrue(dryDeck > 0, "MIN_VALUE 哨兵被当数值算会得到一个巨大的负数");
    }

    // ---------------- 压纵断面：每站最多 1 格、确定、不改原对象 ----------------

    @Test
    void pressingToSharedDeckNeverStepsMoreThanOneBlockPerStation() {
        List<BlockPos> path = new ArrayList<>();
        for (int i = 0; i < 40; i++) path.add(new BlockPos(i * 2, 70, 0));

        List<BlockPos> pressed = CrossingNegotiator.pressProfileToSharedDeck(path, 40, 0, 56, 2, 32);
        assertEquals(56, pressed.get(20).getY(), "离共享过河点最近的一站必须坐到共享甲板上");
        assertEquals(56, pressed.get(19).getY(), "平整核内的站同样在甲板上");
        for (int i = 1; i < pressed.size(); i++) {
            assertTrue(Math.abs(pressed.get(i).getY() - pressed.get(i - 1).getY()) <= 1,
                    "第 " + i + " 站与前一站的高差超过 1 格（会造出 MAX_HEIGHT_STEP 违规）");
        }
        assertEquals(70, path.get(20).getY(), "压剖面不得改动传入的路径对象");

        // 确定性：同样的输入两次必须逐字段相同。
        assertEquals(pressed, CrossingNegotiator.pressProfileToSharedDeck(path, 40, 0, 56, 2, 32));

        // 甲板未知 / 限幅为 0 ⇒ 原样返回（逐位保持第一遍产出）。
        assertSame(path, CrossingNegotiator.pressProfileToSharedDeck(path, 40, 0, Integer.MIN_VALUE, 2, 32));
        assertSame(path, CrossingNegotiator.pressProfileToSharedDeck(path, 40, 0, 56, 2, 0));
    }

    private static List<String> owners(List<CrossingPoint> points) {
        List<String> result = new ArrayList<>();
        for (CrossingPoint point : points) result.add(point.ownerKey());
        return result;
    }

    private static List<List<CrossingPoint>> permutations(List<CrossingPoint> source) {
        List<List<CrossingPoint>> out = new ArrayList<>();
        permute(new ArrayList<>(source), 0, out);
        return out;
    }

    private static void permute(List<CrossingPoint> source, int cursor, List<List<CrossingPoint>> out) {
        if (cursor == source.size()) {
            out.add(List.copyOf(source));
            return;
        }
        for (int i = cursor; i < source.size(); i++) {
            java.util.Collections.swap(source, cursor, i);
            permute(source, cursor + 1, out);
            java.util.Collections.swap(source, cursor, i);
        }
    }
}
