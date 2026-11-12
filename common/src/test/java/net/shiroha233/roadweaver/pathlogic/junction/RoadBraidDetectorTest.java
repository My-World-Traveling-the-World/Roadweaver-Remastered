package net.shiroha233.roadweaver.pathlogic.junction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorBan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.018：D2 平行双桥的「并排重叠」全批次预检，以及改道用的禁行走廊。
 *
 * <p>这组用例钉住三件事，任何一条回归都会让 D2 回到原样：</p>
 * <ol>
 *   <li><b>完全平行必须判成并排</b>。已有的 {@code RoadSnapService.detectsBraid}
 *       把点积 {@code > braidNearParallelDot(0.90)} 排除在麻花之外（它服务的是
 *       「已经在尝试合并」的上下文），而 D2 的真机缺陷恰好是点积≈1 的平行双桥。
 *       这里必须反着来。</li>
 *   <li><b>正交交叉不能判成并排</b>。丁字/十字是玩家要的路口，不能被改道拆掉。</li>
 *   <li><b>短擦边不能判成并排</b>。否则相邻两个村子的正常取水口都会被改道。</li>
 * </ol>
 */
final class RoadBraidDetectorTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadData road(List<int[]> stations) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int[] station : stations) {
            BlockPos center = new BlockPos(station[0], station[1], station[2]);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(station[1]);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights,
                RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    /** 沿 -z 方向、x=100 的一条直路，41 站。 */
    private static RoadData straightAlongZ(int x) {
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            stations.add(new int[]{x, 64, -i});
        }
        return road(stations);
    }

    /** 完全平行、间隔 6 格、方向点积 = 1.0 ⇒ 必须判成并排（D2 本体）。 */
    @Test
    void fullyParallelCorridorIsBraidEvenAboveNearParallelDot() {
        RoadData primary = straightAlongZ(100);
        RoadData secondary = straightAlongZ(106);

        RoadBraidDetector.Report report = RoadBraidDetector.compare(primary, secondary);

        assertTrue(report.braided(), "严格平行(点积≈1)必须判成并排重叠，正是 D2 的形态");
        assertTrue(report.worstDot() > 0.90D,
                "本用例的点积必须落在 detectsBraid 会排除的区间(>0.90)，否则它没有覆盖到 D2");
        assertEquals(41, report.stations(), "整条路应当都在重叠段里");
        assertEquals(0, report.secondaryFrom());
        assertEquals(40, report.secondaryTo());
        assertEquals(6, report.medianDistance());
        assertTrue(report.coversLastStation(secondary.roadSegmentList().size()));
        assertTrue(report.primaryFrom() >= 0 && report.primaryTo() >= report.primaryFrom(),
                "必须给出前一条路上的重叠区间，改道的禁令靠它定位");
    }

    /** 正交十字：站距够近但方向点积≈0 ⇒ 不是并排，不能改道。 */
    @Test
    void perpendicularCrossingIsNotBraid() {
        List<int[]> crossing = new ArrayList<>();
        for (int x = 0; x <= 40; x++) {
            crossing.add(new int[]{x, 64, 0});
        }
        RoadData primary = road(crossing);
        List<int[]> northSouth = new ArrayList<>();
        for (int z = -20; z <= 20; z++) {
            northSouth.add(new int[]{20, 64, z});
        }
        RoadData secondary = road(northSouth);

        RoadBraidDetector.Report report = RoadBraidDetector.compare(primary, secondary);

        assertFalse(report.braided(), "十字路口是玩家要的，方向正交时不得判成并排");
    }

    /** 只在 3 站上擦边（< MIN_RUN）⇒ 不是并排。 */
    @Test
    void shortGrazingTouchIsNotBraid() {
        RoadData primary = straightAlongZ(100);
        List<int[]> stations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stations.add(new int[]{106, 64, -i});
        }
        for (int i = 3; i < 20; i++) {
            stations.add(new int[]{160, 64, -i});
        }
        RoadData secondary = road(stations);

        assertFalse(RoadBraidDetector.compare(primary, secondary).braided(),
                "只有 3 站贴在一起，不足 " + RoadBraidDetector.MIN_RUN + " 站，不得判成并排");
    }

    /** 间隔超过站距上限 ⇒ 不是并排。 */
    @Test
    void distantParallelRoadsAreNotBraid() {
        RoadData primary = straightAlongZ(100);
        RoadData secondary = straightAlongZ(100 + RoadBraidDetector.MAX_DISTANCE + 4);

        assertFalse(RoadBraidDetector.compare(primary, secondary).braided(),
                "站距超过 ROAD_SNAP_THRESHOLD 的平行路互不干扰");
    }

    /** 禁行走廊：重叠段内命中、段外不命中。 */
    @Test
    void corridorBanCoversTheOverlapRunOnly() {
        RoadData primary = straightAlongZ(100);
        CorridorBan ban = CorridorBan.aroundRoadRun(primary, 10, 20, 18);

        assertFalse(ban.isEmpty());
        assertTrue(ban.blocks(100, -15), "重叠段正中必须禁行");
        assertTrue(ban.blocks(100 + 12, -15), "半径内的侧向偏移必须禁行（否则改道只是挪开几格）");
        assertFalse(ban.blocks(100, -48), "重叠段之外（z=-48，距段端 28 格 > 18+量化余量）不得禁行");
        assertFalse(ban.blocks(100 + 40, -15), "半径之外不得禁行");
    }

    /** 空禁令必须是一个零开销的 false，而不是 NPE 或全禁。 */
    @Test
    void emptyBanNeverBlocks() {
        assertTrue(CorridorBan.NONE.isEmpty());
        assertEquals(0, CorridorBan.NONE.cellCount());
        assertFalse(CorridorBan.NONE.blocks(0, 0));
        assertFalse(CorridorBan.NONE.blocks(-1000, 1000));
    }

    /** 线程作用域禁令：作用域内可见、退出后清除，异常路径也要恢复。 */
    @Test
    void scopedBanIsVisibleOnlyInsideTheScope() {
        RoadData primary = straightAlongZ(100);
        CorridorBan ban = CorridorBan.aroundRoadRun(primary, 10, 20, 18);
        assertNull(CorridorBan.current(), "进入作用域前当前线程不应挂着禁令");

        boolean inside = CorridorBan.withScoped(ban, () -> CorridorBan.current() == ban);
        assertTrue(inside);
        assertNull(CorridorBan.current(), "退出作用域后必须清除，否则静默影响后续规划");

        try {
            CorridorBan.withScoped(ban, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 故意走异常路径
        }
        assertNull(CorridorBan.current(), "异常路径也必须恢复，否则残留禁令会改掉整局道路");
    }

    /**
     * 两参重载（读当前配置）在同一组几何上必须给出同样的结论。
     *
     * <p>它只是把配置里的 {@code braidMinDirectionDot} 与 {@code ROAD_SNAP_THRESHOLD}
     * 喂给四参版本；这条用例保证「配置服务不可用时退回编译期默认值」那条兜底
     * 路径本身不会改变判定。</p>
     */
    @Test
    void configDrivenOverloadAgreesWithExplicitThresholds() {
        RoadData primary = straightAlongZ(100);
        RoadData secondary = straightAlongZ(106);
        RoadBraidDetector.Report fromConfig = RoadBraidDetector.compare(primary, secondary);
        RoadBraidDetector.Report explicit = RoadBraidDetector.compare(
                primary, secondary, RoadBraidDetector.MIN_DOT, RoadBraidDetector.MAX_DISTANCE);
        assertEquals(explicit.braided(), fromConfig.braided());
        assertEquals(explicit.stations(), fromConfig.stations());
        assertSame(true, fromConfig.braided());
    }
}
