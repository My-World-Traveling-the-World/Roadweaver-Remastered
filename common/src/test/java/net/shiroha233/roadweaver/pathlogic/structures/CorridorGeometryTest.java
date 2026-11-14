/* 文件职责：钉住「避让走廊」的两个纯几何工具 —— 区块连线与点到线段距离。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 避让走廊几何工具测试（2026-11-15）。
 *
 * <h2>为什么测这三个纯函数</h2>
 * <p>{@code PathCorridorBuilder} 本身重度依赖世界（491 行、9 处世界引用），
 * 但它的<b>几何底座</b>是两个不碰世界的纯函数：</p>
 * <ul>
 *   <li>{@code addChunkLine} —— Bresenham 把一条路线经过的区块全部点亮；</li>
 *   <li>{@code distanceSquaredToSegment} —— 点到线段的最短距离平方；</li>
 *   <li>{@code withinCorridorPath} —— 由前者拼出的「这个位置在不在走廊内」。</li>
 * </ul>
 * <p>这三个判错的后果都是<b>静默的</b>：连漏区块 ⇒ 走廊覆盖不全、结构避让漏判；
 * 距离算大 ⇒ 该避让的不避让；算小 ⇒ 大片无关结构被卷进候选，白跑规划。</p>
 *
 * <h2>★ 一个需要知道的行为：单点路径恒为 false</h2>
 * <p>{@code withinCorridorPath} 的循环要凑够 {@code previous} 与 {@code point} 两点
 * 才会调距离函数。所以<b>只有一个点的走廊路径永远返回 false</b> ——
 * 任何位置都不算「在走廊内」。生产上路径至少两点，所以碰不到；
 * 但这是个真实的边界，测试把它钉住（见 {@code 单点路径恒为false}），
 * 免得将来有人改成「按点半径判断」时以为行为没变。</p>
 */
class CorridorGeometryTest {

    // ───────────────────── A. addChunkLine（Bresenham） ─────────────────────

    @Test
    void 起终点相同时只点亮一个区块() {
        Set<Long> chunks = new HashSet<>();
        PathCorridorBuilder.addChunkLine(chunks, 5, 7, 5, 7);

        assertEquals(1, chunks.size(),
                "同一点只该点亮一个区块。若这里 >1，说明循环终止条件写错，会死循环或无谓放大器");
        assertTrue(chunks.contains(net.minecraft.world.level.ChunkPos.asLong(5, 7)),
                "点亮的必须就是那个点");
    }

    @Test
    void 水平与垂直线长度等于格数加一() {
        Set<Long> horizontal = new HashSet<>();
        PathCorridorBuilder.addChunkLine(horizontal, 0, 3, 4, 3);
        assertEquals(5, horizontal.size(), "从 0 到 4 共 5 个整数点");

        Set<Long> vertical = new HashSet<>();
        PathCorridorBuilder.addChunkLine(vertical, 3, 0, 3, 4);
        assertEquals(5, vertical.size(), "竖直方向同样 5 个点");
    }

    @Test
    void 完美对角线不重复也不遗漏() {
        Set<Long> diagonal = new HashSet<>();
        PathCorridorBuilder.addChunkLine(diagonal, 0, 0, 5, 5);

        // 对角线上每步同时推进 x 与 z ⇒ 恰好 6 个点
        assertEquals(6, diagonal.size(),
                "dx == dz 时每步两轴同时前进，点数应是 max(dx,dz)+1 = 6");
        for (int i = 0; i <= 5; i++) {
            assertTrue(diagonal.contains(net.minecraft.world.level.ChunkPos.asLong(i, i)),
                    "对角线必须包含 (" + i + "," + i + ")");
        }
    }

    @Test
    void 反向线段与正向点亮同一组区块() {
        Set<Long> forward = new HashSet<>();
        PathCorridorBuilder.addChunkLine(forward, 0, 0, 7, 3);
        Set<Long> backward = new HashSet<>();
        PathCorridorBuilder.addChunkLine(backward, 7, 3, 0, 0);

        assertEquals(forward, backward,
                "Bresenham 必须对称 —— 若不对称，说明 sx/sz 或 error 初值只考虑了一个方向");
    }

    @Test
    void 陡峭与平缓斜率都覆盖两端() {
        // 陡峭：z 走得多；平缓：x 走得多。两种都要包含起终点
        Set<Long> steep = new HashSet<>();
        PathCorridorBuilder.addChunkLine(steep, 0, 0, 2, 9);
        assertTrue(steep.contains(net.minecraft.world.level.ChunkPos.asLong(0, 0)), "陡峭线含起点");
        assertTrue(steep.contains(net.minecraft.world.level.ChunkPos.asLong(2, 9)), "陡峭线含终点");

        Set<Long> gentle = new HashSet<>();
        PathCorridorBuilder.addChunkLine(gentle, 0, 0, 9, 2);
        assertTrue(gentle.contains(net.minecraft.world.level.ChunkPos.asLong(0, 0)), "平缓线含起点");
        assertTrue(gentle.contains(net.minecraft.world.level.ChunkPos.asLong(9, 2)), "平缓线含终点");

        assertEquals(10, steep.size(), "陡峭线点数应是 max(dx,dz)+1 = 10");
        assertEquals(10, gentle.size(), "平缓线点数同样是 10");
    }

    @Test
    void 负坐标也正确() {
        Set<Long> negative = new HashSet<>();
        PathCorridorBuilder.addChunkLine(negative, -3, -3, 2, 2);

        assertEquals(6, negative.size(), "跨原点的对角线仍是 max(dx,dz)+1 = 6 个点");
        assertTrue(negative.contains(net.minecraft.world.level.ChunkPos.asLong(-3, -3)));
        assertTrue(negative.contains(net.minecraft.world.level.ChunkPos.asLong(2, 2)));
    }

    // ──────────────── B. distanceSquaredToSegment ────────────────

    @Test
    void 退化线段的距离等于点到点的距离() {
        BlockPos p = new BlockPos(10, 0, 10);
        BlockPos same = new BlockPos(0, 0, 0);

        // 起点终点相同 ⇒ 退化成点，距离 = 到该点的平方距离 = 100 + 100 = 200
        assertEquals(200L, StructureAvoidanceService.distanceSquaredToSegment(10, 10, same, same),
                "退化线段必须走特判分支，否则 dx=dz=0 会让 t 的分母为 0（NaN）");
    }

    @Test
    void 投影落在线段内时按垂距算() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(10, 0, 0);
        // 点在 (5, 3)：垂足 (5,0) 在段内 ⇒ 距离平方 = 9
        assertEquals(9L, StructureAvoidanceService.distanceSquaredToSegment(5, 3, a, b),
                "垂足落在段内时应取垂距平方");
    }

    @Test
    void 投影落在线段外时夹到端点() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(10, 0, 0);
        // 点在 (20, 0)：垂足在段外 ⇒ t 被夹到 1 ⇒ 距离到端点 b = 10 ⇒ 平方 100
        assertEquals(100L, StructureAvoidanceService.distanceSquaredToSegment(20, 0, a, b),
                "t 必须夹到 [0,1] —— 否则会算成到「无限延长线」的距离（这里是 0，严重偏小）");
        // 点在 (-5, 0)：t 夹到 0 ⇒ 到起点距离 5 ⇒ 25
        assertEquals(25L, StructureAvoidanceService.distanceSquaredToSegment(-5, 0, a, b));
    }

    @Test
    void 距离平方向上取整而不是截断() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(10, 0, 0);
        // 点 (5,1)：垂距平方 = 1（整数，看不出取整）
        assertEquals(1L, StructureAvoidanceService.distanceSquaredToSegment(5, 1, a, b));
        // 斜线段上的非整数垂距：点 (5,5) 到 (0,0)-(10,10) 的垂距 = 0（在线上）
        BlockPos c = new BlockPos(0, 0, 0);
        BlockPos d = new BlockPos(10, 0, 10);
        assertEquals(0L, StructureAvoidanceService.distanceSquaredToSegment(5, 5, c, d),
                "点正好落在斜线上 ⇒ 距离 0");
        // 偏一格的点：垂距 = |6-4|/√2 = √2 ⇒ 平方 = 2（不是 1 —— 这里是沿对角线偏，
        // 直角边差 2、垂直方向的距离是 2/√2，平方后正好 2）
        assertEquals(2L, StructureAvoidanceService.distanceSquaredToSegment(6, 4, c, d));
    }

    // ──────────────── C. withinCorridorPath ────────────────

    @Test
    void 空路径与null一律为false() {
        assertFalse(PathCorridorBuilder.withinCorridorPath(new BlockPos(0, 0, 0), null));
        assertFalse(PathCorridorBuilder.withinCorridorPath(new BlockPos(0, 0, 0), List.of()));
        assertFalse(PathCorridorBuilder.withinCorridorPath(null, List.of(new BlockPos(0, 0, 0))));
    }

    @Test
    void 单点路径恒为false() {
        // ★ 这是实现的一个真实边界：循环要凑够 previous+point 两点才调距离函数。
        //   生产上路径至少两点（起点+终点），所以碰不到；但如果将来有人把
        //   withinCorridorPath 改成「按点半径判断」，这条会红 —— 那时应同步更新 javadoc。
        List<BlockPos> single = List.of(new BlockPos(0, 0, 0));
        assertFalse(PathCorridorBuilder.withinCorridorPath(new BlockPos(0, 0, 0), single),
                "即使查询点就在那个点上，单点路径也返回 false —— "
                        + "因为没有任何线段可算距离。这是当前实现的边界，不是查询点的问题");
    }

    @Test
    void 半径内为true半径外为false() {
        // 走廊是沿 z 轴从 (0,0) 到 (0,100) 的一条线
        List<BlockPos> corridor = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 100));
        int radius = StructureAvoidanceService.PLANNING_CORRIDOR_RADIUS;   // 192

        assertTrue(PathCorridorBuilder.withinCorridorPath(new BlockPos(0, 64, 50), corridor),
                "正落在线上 ⇒ 在走廊内");
        assertTrue(PathCorridorBuilder.withinCorridorPath(
                        new BlockPos(radius - 1, 64, 50), corridor),
                "距线 radius-1 格 ⇒ 仍在走廊内");
        assertTrue(PathCorridorBuilder.withinCorridorPath(
                        new BlockPos(radius, 64, 50), corridor),
                "距线恰好 radius 格 ⇒ 仍在走廊内（判据用 <=，边界含在内）");
        assertFalse(PathCorridorBuilder.withinCorridorPath(
                        new BlockPos(radius + 1, 64, 50), corridor),
                "距线 radius+1 格 ⇒ 出走廊。"
                        + "这条同时证明半径常数没有变成 0/负数（否则前面的断言不会过）");
    }

    @Test
    void 多段折线任一段命中即为true() {
        // L 形走廊：(0,0)→(0,50)→(50,50)
        List<BlockPos> corridor = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 50),
                new BlockPos(50, 64, 50));

        assertTrue(PathCorridorBuilder.withinCorridorPath(new BlockPos(0, 64, 25), corridor),
                "落在第一段上");
        assertTrue(PathCorridorBuilder.withinCorridorPath(new BlockPos(25, 64, 50), corridor),
                "落在第二段上 —— 多段折线必须逐段检查，只看首尾会漏掉拐角后的整段");
        assertFalse(PathCorridorBuilder.withinCorridorPath(
                        new BlockPos(-5000, 64, -5000), corridor),
                "远在千里之外");
    }
}