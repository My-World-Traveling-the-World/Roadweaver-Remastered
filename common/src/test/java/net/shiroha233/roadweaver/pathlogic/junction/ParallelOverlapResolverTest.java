/* 文件职责：并行走廊预筛与连接还原的纯判定测试（批 4 补覆盖，2026-11-15）。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批 4「路口与合并」补覆盖：并行走廊预筛的纯判定面。
 *
 * <h2>为什么补这个</h2>
 * <p>2026-11-15 实测：{@code ParallelOverlapResolver}（386 行 / 9 个方法）
 * 在全部 {@code common/src/test} 里被**实际调用 0 次** —— 唯一的「提及」是
 * {@code RoadBoundsGapTest:14} 的一行 javadoc。本类补的是其中**不依赖
 * {@code ServerLevel}** 的那部分：{@link ParallelOverlapResolver#boxMayOverlap}
 * 与 {@link ParallelOverlapResolver#connectionOf}。</p>
 *
 * <h2>预筛的判据是「宁可多算，不可漏判」</h2>
 * <p>{@code boxMayOverlap} 是三步判定里最便宜的第一步（包围盒预筛）。
 * 它<b>故意 fail-open</b>：读不到包围盒或抛异常时返回 {@code true}
 * （多跑一次贵的真判，也不要漏掉真并排）。这几条用例正是钉住这个方向性 ——
 * <b>如果哪天有人把它改成 fail-close（读不到就返回 false），
 * 表现会是「某些并排路静默不再被消解」，而不是崩溃。</b></p>
 */
class ParallelOverlapResolverTest {

    /**
     * 预筛的固定容差 —— 直接引用生产常量，不重新实现魔数。
     * 生产判据是 {@code gap <= maxDistance + BRAID_BOX_MARGIN}。
     */
    private static final int MARGIN = ExistingRoadJunctionService.BRAID_BOX_MARGIN;

    /**
     * 造一条沿 X 轴的直路：站点 x 从 {@code x0} 每 {@code step} 一格，共 {@code n} 站。
     * Z 固定，Y 固定 —— 包围盒就是 [x0, x0+(n-1)*step] × [z, z]。
     */
    private static RoadData straightRoad(int x0, int z, int n, int step) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos center = new BlockPos(x0 + i * step, 64, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights, 1L, 2L);
    }

    // ══════════ 一、包围盒预筛：远离 / 重叠 / 边界 ══════════

    @Test
    void 两路相距很远时预筛必须为假() {
        RoadData a = straightRoad(0, 0, 10, 4);          // 包围盒 x∈[0,36], z=0
        RoadData b = straightRoad(0, 1000, 10, 4);       // 包围盒 z=1000
        assertFalse(ParallelOverlapResolver.boxMayOverlap(a, b, 0),
                "Z 向相距 1000 格，远超容差 " + MARGIN + "，预筛必须直接排除，否则每题都跑最贵的真判");
    }

    @Test
    void 两路包围盒重叠时预筛必须为真() {
        RoadData a = straightRoad(0, 0, 10, 4);
        RoadData b = straightRoad(10, 0, 10, 4);         // 与 a 的 x 区间重叠
        assertTrue(ParallelOverlapResolver.boxMayOverlap(a, b, 0),
                "包围盒相交（gap=0）时必须进入下一步真判");
    }

    @Test
    void 预筛容差是闭区间_恰好等于阈值仍算可能重叠() {
        // 把 b 放在「gap 恰好 = maxDistance + MARGIN」的位置
        RoadData a = straightRoad(0, 0, 10, 4);          // x∈[0,36]
        int gap = 0 + MARGIN;                            // maxDistance=0 时的阈值
        RoadData atThreshold = straightRoad(36 + gap, 0, 1, 1);
        assertTrue(ParallelOverlapResolver.boxMayOverlap(a, atThreshold, 0),
                "判据是 `gap <= maxDistance + MARGIN`（闭区间）。"
                        + "若这里红了，说明边界被改成了开区间，恰好卡在阈值上的并排会被漏掉");

        RoadData justOver = straightRoad(36 + gap + 1, 0, 1, 1);
        assertFalse(ParallelOverlapResolver.boxMayOverlap(a, justOver, 0),
                "超出阈值 1 格必须被排除，否则预筛等于没有（永远为真就没有预筛意义）");
    }

    @Test
    void maxDistance_参数必须真的放宽阈值() {
        int base = 36;                                   // a 的包围盒右边界
        RoadData a = straightRoad(0, 0, 10, 4);
        int beyond = MARGIN + 50;                        // 默认阈值下不可能重叠的位置
        RoadData b = straightRoad(base + beyond, 0, 1, 1);
        assertFalse(ParallelOverlapResolver.boxMayOverlap(a, b, 0),
                "构造前提：默认阈值（0）时这两条路应当被排除");
        assertTrue(ParallelOverlapResolver.boxMayOverlap(a, b, 100),
                "把 maxDistance 放宽到 100 后必须进入候选 —— "
                        + "否则说明该参数没被用上（预筛会变成只看固定容差）");
    }

    // ══════════ 二、fail-open：读不到包围盒时绝不排除 ══════════

    @Test
    void 没有站点的路读不到包围盒时必须_fail_open() {
        RoadData empty = new RoadData(4, 1, List.of(), List.of(),
                List.of(), List.of(), List.of(), 1L, 2L);
        RoadData normal = straightRoad(0, 0, 10, 4);
        assertTrue(ParallelOverlapResolver.boxMayOverlap(empty, normal, 0),
                "空站点 ⇒ RoadBounds.of 返回 null ⇒ 必须 fail-open 返回 true。"
                        + "改成 false 会让「某些路读不到包围盒」变成静默漏判");
        assertTrue(ParallelOverlapResolver.boxMayOverlap(normal, empty, 0),
                "对称方向同样必须 fail-open");
    }

    @Test
    void null_输入也必须_fail_open() {
        RoadData normal = straightRoad(0, 0, 10, 4);
        assertTrue(ParallelOverlapResolver.boxMayOverlap(null, normal, 0),
                "null 一侧读不到包围盒 ⇒ 必须 fail-open");
        assertTrue(ParallelOverlapResolver.boxMayOverlap(normal, null, 0),
                "null 另一侧同样必须 fail-open");
        assertTrue(ParallelOverlapResolver.boxMayOverlap(null, null, 0),
                "两侧都 null 也必须 fail-open（宁多算不漏判）");
    }

    // ══════════ 三、connectionOf：用首末站点还原连接 ══════════

    @Test
    void 连接还原取首末站点且状态为_PLANNED() {
        RoadData road = straightRoad(100, 200, 5, 4);    // 首 (100,64,200)、末 (116,64,200)
        StructureConnection c = ParallelOverlapResolver.connectionOf(road);
        assertNotNull(c, "5 站的路必须能还原出连接");
        assertEquals(new BlockPos(100, 64, 200), c.from(), "from 必须取首站的中心点");
        assertEquals(new BlockPos(116, 64, 200), c.to(), "to 必须取末站的中心点");
        assertEquals(ConnectionStatus.PLANNED, c.status(),
                "还原出来的连接必须是 PLANNED（供重算使用，不是既有路）");
    }

    @Test
    void 站点不足两条时连接还原必须返回_null() {
        assertNull(ParallelOverlapResolver.connectionOf(null),
                "null 路必须返回 null");
        RoadData one = straightRoad(0, 0, 1, 4);
        assertNull(ParallelOverlapResolver.connectionOf(one),
                "只有 1 站时没有「首末」可言，必须返回 null（否则会造出 from==to 的退化连接）");
        RoadData empty = new RoadData(4, 1, List.of(), List.of(),
                List.of(), List.of(), List.of(), 1L, 2L);
        assertNull(ParallelOverlapResolver.connectionOf(empty),
                "空站点必须返回 null");
    }

    @Test
    void 首末重合时连接还原必须返回_null() {
        // 两站都在同一坐标 —— from.equals(to)，属于退化连接
        BlockPos same = new BlockPos(50, 64, 50);
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            segments.add(new RoadSegmentPlacement(same, List.of(same)));
            heights.add(64);
        }
        RoadData degenerate = new RoadData(4, 1, List.of(), List.of(),
                segments, List.of(), heights, 1L, 2L);
        assertNull(ParallelOverlapResolver.connectionOf(degenerate),
                "首末重合（from.equals(to)）必须返回 null —— "
                        + "否则重算会拿到一条零长度连接，A* 直接返回失败或死循环");
    }
}