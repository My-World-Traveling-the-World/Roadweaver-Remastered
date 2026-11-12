package net.shiroha233.roadweaver.pathlogic.junction;

import net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator.Cluster;
import net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator.CrossingPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.057「并行 → 协商 → 并行」中间那段的回归。
 *
 * <p>钉四条契约，任何一条破了都会在真机上表现成「两条路还是各架各的桥」或者
 * 「同一颗种子两台机器路不一样」：</p>
 * <ol>
 *   <li>只有一条路 ⇒ 不产生共享点（一条路没有可协商对象，不能凭空多一条优惠带）；</li>
 *   <li>两簇距离 &gt; clusterRadius ⇒ 不得合并（否则八竿子打不着的两条河会被硬吸到一起）；</li>
 *   <li>同一簇内选点与输入顺序无关（否则「谁先算完」又变成了影响结果的因素，
 *       等于把 0.9.056 的串行顺序依赖换个地方藏起来）；</li>
 *   <li>距离 ≤ clusterRadius 的两个点 ⇒ 合并成 1 簇，且 {@code sharedX/Z} 等于排序键最优那个。</li>
 * </ol>
 */
final class CrossingNegotiatorTest {

    /** 与 RoadConstants.DEFAULT_CROSSING_CLUSTER_RADIUS 同值；这里刻意写字面量，改默认值必须同时看这里。 */
    private static final int RADIUS = 24;

    @Test
    void singleRoadYieldsNoSharedPoint() {
        // 一条路、一个过河点：自己是自己，没有「共享」可言。
        assertTrue(CrossingNegotiator.negotiate(
                        List.of(new CrossingPoint("road-a", 236, -170, 48, 3)), RADIUS).isEmpty(),
                "一条路不得产生共享点（单点簇必须被丢弃）");

        // 退化输入：null / 空 / 全是 null ⇒ 空列表，且不抛异常。
        assertTrue(CrossingNegotiator.negotiate(null, RADIUS).isEmpty(), "null 输入返回空列表");
        assertTrue(CrossingNegotiator.negotiate(List.of(), RADIUS).isEmpty(), "空输入返回空列表");
        List<CrossingPoint> withNulls = new ArrayList<>();
        withNulls.add(null);
        assertTrue(CrossingNegotiator.negotiate(withNulls, RADIUS).isEmpty(), "全是 null 的输入返回空列表");
    }

    @Test
    void clustersFartherThanRadiusAreNotMerged() {
        // 100 格 &gt; 24 格：两个点各自成簇 ⇒ 都不构成共享，结果里一条簇都没有。
        assertTrue(CrossingNegotiator.negotiate(List.of(
                        new CrossingPoint("road-a", 0, 0, 32, 2),
                        new CrossingPoint("road-b", 100, 0, 32, 2)), RADIUS).isEmpty(),
                "距离 100 > 24 的两点不得合并成共享过河点");

        // 三个点：a/c 近（12 格），b 远（100 格）⇒ 只应合成 1 簇，且 b 不得被拉进来。
        List<Cluster> clusters = CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-a", 0, 0, 32, 2),
                new CrossingPoint("road-b", 100, 0, 32, 2),
                new CrossingPoint("road-c", 12, 0, 32, 2)), RADIUS);
        assertEquals(1, clusters.size(), "只应有一簇（a 与 c），b 单独成簇被丢弃");
        List<String> owners = new ArrayList<>();
        for (CrossingPoint member : clusters.get(0).members()) owners.add(member.ownerKey());
        assertEquals(List.of("road-a", "road-c"), owners, "远点的 road-b 绝不能进这一簇");
        // 精确边界：距离恰好 == radius 仍算同簇（判据是「≤」）。
        assertEquals(1, CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-a", 0, 0, 32, 2),
                new CrossingPoint("road-b", RADIUS, 0, 32, 2)), RADIUS).size(),
                "距离恰好等于 clusterRadius 必须合并（判据是 ≤ 而不是 <）");
    }

    @Test
    void sharedPointIsIndependentOfInputOrder() {
        // 可变副本：全排列要就地交换元素。
        List<CrossingPoint> canonical = new ArrayList<>(List.of(
                new CrossingPoint("road-a", 100, 0, 24, 2),
                new CrossingPoint("road-b", 110, 6, 24, 2),
                new CrossingPoint("road-c", 300, 300, 32, 4),
                new CrossingPoint("road-d", 306, 300, 40, 1)));

        String expected = signature(CrossingNegotiator.negotiate(canonical, RADIUS));
        assertEquals(2, CrossingNegotiator.negotiate(canonical, RADIUS).size(), "应分成两簇");

        // 全排列（24 种）逐一比对：任何一种输入顺序都必须得到逐字段相同的结果。
        List<List<CrossingPoint>> permutations = new ArrayList<>();
        permute(canonical, 0, permutations);
        assertEquals(24, permutations.size(), "4 个点的全排列应为 24 种");
        for (List<CrossingPoint> permutation : permutations) {
            assertEquals(expected, signature(CrossingNegotiator.negotiate(permutation, RADIUS)),
                    "打乱输入顺序后结果必须完全相同：" + owners(permutation));
        }
    }

    @Test
    void pointsWithinRadiusMergeIntoOneClusterLedByTheBestPoint() {
        // 距离 12 ≤ 24 ⇒ 1 簇；road-b 更宽（40 > 16）⇒ 排序键最优者是 road-b。
        List<Cluster> clusters = CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-a", 236, -170, 16, 3),
                new CrossingPoint("road-b", 248, -170, 40, 2)), RADIUS);
        assertEquals(1, clusters.size(), "距离 ≤ radius 的两个点必须合并成 1 簇");
        assertEquals(248, clusters.get(0).sharedX(), "sharedX 必须是排序键最优那个（更宽 = road-b）");
        assertEquals(-170, clusters.get(0).sharedZ(), "sharedZ 必须是排序键最优那个（更宽 = road-b）");
        assertEquals(List.of("road-b", "road-a"), owners(clusters.get(0)),
                "簇成员必须按「最优在前」排列");

        // 宽度并列时看水深：waterDepth 更大的那个是最优者。
        List<Cluster> byDepth = CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-a", 0, 0, 32, 2),
                new CrossingPoint("road-b", 10, 0, 32, 5)), RADIUS);
        assertEquals(1, byDepth.size());
        assertEquals("road-b", byDepth.get(0).members().get(0).ownerKey(), "宽度并列时水深更大的胜出");
        assertEquals(10, byDepth.get(0).sharedX(), "sharedX 必须跟随胜出者");

        // 宽度与水深都并列时看 ownerKey（升序）：road-a 胜出 —— 全序的最后一档保证确定性。
        List<Cluster> byOwner = CrossingNegotiator.negotiate(List.of(
                new CrossingPoint("road-b", 10, 0, 32, 2),
                new CrossingPoint("road-a", 0, 0, 32, 2)), RADIUS);
        assertEquals(1, byOwner.size());
        assertEquals("road-a", byOwner.get(0).members().get(0).ownerKey(), "前两档并列时 ownerKey 小的胜出");
        assertEquals(0, byOwner.get(0).sharedX(), "sharedX 必须跟随 ownerKey 胜出者");
    }

    private static List<String> owners(Cluster cluster) {
        List<String> result = new ArrayList<>();
        for (CrossingPoint member : cluster.members()) result.add(member.ownerKey());
        return result;
    }

    private static List<String> owners(List<CrossingPoint> points) {
        List<String> result = new ArrayList<>();
        for (CrossingPoint point : points) result.add(point.ownerKey());
        return result;
    }

    /** 簇的完整指纹：簇顺序 + 每簇的共享点 + 每簇成员顺序，一个字都不能差。 */
    private static String signature(List<Cluster> clusters) {
        StringBuilder builder = new StringBuilder();
        for (Cluster cluster : clusters) {
            builder.append('[').append(cluster.sharedX()).append(',')
                    .append(cluster.sharedZ()).append('|')
                    .append(String.join(",", owners(cluster))).append(']');
        }
        return builder.toString();
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
