/* 文件职责：证明度量与快速行进法满足设计中的数学性质（含旧实现 38× 不对称的回归钉子）。 */
package net.shiroha233.roadweaver.metric;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 度量层与快速行进法的性质测试。
 *
 * <h2>为什么要测数学性质</h2>
 * <p>旧实现的问题不是「算错了」，而是<b>算法形式本身给了错的最优解</b>：
 * 坡度罚按步收、基础代价按长度收 ⇒ 正交步与对角步差 38 倍 ⇒ ±8 幅锯齿
 * 是<b>全局唯一最优解</b>。这种缺陷靠调参修不掉，只能靠换形式；
 * 所以这里的测试钉的是<b>形式</b>（对称性、测地距离、绕障），不是具体数值。</p>
 *
 * @see FinslerMetric
 * @see FastMarchingSolver
 */
class FinslerMetricTest {

    // ── 测试用地形 ────────────────────────────────────────────────

    /** 平地：高度恒为 0。 */
    private static TerrainField flat(int min, int max) {
        return new TerrainField() {
            @Override public int height(int x, int z) { return 0; }
            @Override public int minX() { return min; }
            @Override public int maxX() { return max; }
            @Override public int minZ() { return min; }
            @Override public int maxZ() { return max; }
        };
    }

    /** 沿 +x 每格升 1 的斜坡（梯度 = (1, 0)）。 */
    private static TerrainField ramp(int min, int max) {
        return new TerrainField() {
            @Override public int height(int x, int z) { return x; }
            @Override public int minX() { return min; }
            @Override public int maxX() { return max; }
            @Override public int minZ() { return min; }
            @Override public int maxZ() { return max; }
        };
    }

    // ── 性质 1：正交与对角代价必须相等（旧实现差 38 倍）──────────

    @Test
    void 正交与对角代价必须相等() {
        FinslerMetric metric = FinslerMetric.of(flat(0, 40), ObstacleField.NONE);

        // 平地上没有任何坡度，三个方向的单位长度代价必须完全相同。
        // 旧实现：正交步 8008.5 / 对角步 210.9 —— 差 38.0 倍（2026-11-07 实证）。
        double orthoX = metric.cost(20, 20, 1, 0);
        double orthoZ = metric.cost(20, 20, 0, 1);
        double diagonal = metric.cost(20, 20, 1, 1);

        assertEquals(orthoX, orthoZ, 1e-12, "x 向与 z 向代价必须相等");
        assertEquals(orthoX, diagonal, 1e-12,
                "对角与正交代价必须相等 —— 旧实现这里是 38 倍不对称，"
                        + "导致 ±8 幅锯齿成为全局唯一最优解");
        assertEquals(1.0, orthoX, 1e-12, "平地单位长度代价应为 1");
    }

    // ── 性质 2：坡度只惩罚「沿坡方向」────────────────────────────

    @Test
    void 坡度只惩罚沿坡方向() {
        FinslerMetric metric = new FinslerMetric(ramp(0, 40), ObstacleField.NONE, 1.0, 0.0);

        double upSlope = metric.cost(20, 20, 1, 0);    // 沿 +x（上坡）
        double crossSlope = metric.cost(20, 20, 0, 1); // 沿 +z（等高线）

        // c = 1 + λg·(∇h·θ̂)²。梯度 (1,0) ⇒ 上坡项 = 1，等高线项 = 0。
        assertEquals(2.0, upSlope, 1e-9, "上坡代价应为 1 + 1·1² = 2");
        assertEquals(1.0, crossSlope, 1e-9, "沿等高线不该有坡度罚");
        assertTrue(upSlope > crossSlope * 1.5,
                "上坡必须明显比沿等高线贵（方向性坡度约束）");
    }

    // ── 性质 3：快速行进法逼近欧氏距离（Dijkstra 做不到）────────

    @Test
    void 平地上到达时间逼近欧氏距离() {
        FastMarchingSolver solver = new FastMarchingSolver(
                FinslerMetric.of(flat(0, 40), ObstacleField.NONE));
        ArrivalTimeField field = solver.solve(20, 20);

        // 正交方向：应该几乎精确
        assertEquals(10.0, field.at(30, 20), 1e-6, "正交 10 格的到达时间应为 10");

        // 对角方向：一阶 4 邻域 Fast Marching 在 45° 上会高估约 21%
        // （理论值 a + s/√2 = 1 + 0.707 每步，真值 √2）。这是**已知且已量化**的
        // 一阶格式误差，不是 bug —— 上层只用它决定「拓扑与走位」，
        // 精确几何由 band 层的最小平曲率求解修正。
        // 换二阶格式或 8 邻域可把误差压到 2~5%，属 V2。
        double euclidean = Math.hypot(10, 10);
        double actual = field.at(30, 30);
        assertTrue(actual >= euclidean - 1e-9, "不应低于欧氏距离下界");
        assertTrue(actual <= euclidean * 1.25,
                "对角到达时间 " + actual + " 与欧氏距离 " + euclidean
                        + " 的偏差应在已知的一阶格式误差内（≤25%）");
    }

    // ── 性质 4：障碍会被绕开，且路径不穿障碍 ─────────────────────

    @Test
    void 软势场_障碍会被尽量少穿() {
        // 在 x=25 处放一道墙，只在 z >= 31 处留缺口。
        // 起点 (20,20) 与终点 (30,20) 的直线被完全挡住。
        ObstacleField wall = (x, z) -> (x == 25 && z <= 30) ? 1.0 : 0.0;
        FinslerMetric metric = new FinslerMetric(flat(0, 40), wall, 0.0, 8.0);

        // 度量必须真的看见墙，且代价就是 1 + 8·1 = 9
        assertEquals(9.0, metric.isotropicCost(25, 20), 1e-9, "墙格代价应为 9");
        assertEquals(1.0, metric.isotropicCost(24, 20), 1e-9, "墙前一格代价应为 1");
        assertEquals(1.0, metric.isotropicCost(25, 31), 1e-9, "缺口代价应为 1");

        ArrivalTimeField field = new FastMarchingSolver(metric).solve(20, 20);

        // 到达时间必须反映「进入墙格要付 9」：
        //   T(24,20)=4（墙前 4 格）  T(25,20)=4+9=13  T(26,20)=14  T(30,20)=18
        assertEquals(4.0, field.at(24, 20), 1e-6, "墙前 4 格应为 4");
        assertEquals(13.0, field.at(25, 20), 1e-6,
                "进入墙格必须付 9 的代价（4 + 9 = 13）。"
                        + "若这里等于 5，说明慢度取错格了 —— 用已冻结格的代价更新邻居"
                        + "会让墙后的便宜格子把值倒灌回墙格，墙变成完全透明");
        assertEquals(14.0, field.at(26, 20), 1e-6, "离开墙格后 13 + 1 = 14");
        assertEquals(18.0, field.at(30, 20), 1e-6, "终点 14 + 4 = 18");

        // 软势场的语义是「尽量少穿」而不是「绝不穿」：
        // 穿一格付 9（总 18）比绕缺口（约 32）便宜，所以最优解是穿一次。
        // 「绝不穿」由 band 层的硬投影负责，不在度量里塞一个「很大的软代价」——
        // 那正是旧实现 PROTECTED_GAP 缺 fail-safe 的成因。
        List<int[]> path = field.pathTo(30, 20);
        assertFalse(path.isEmpty(), "路径不应为空");
        int crossings = 0;
        for (int[] station : path) {
            if (wall.potential(station[0], station[1]) > 0.0) crossings++;
        }
        assertEquals(1, crossings,
                "最优解应恰好穿墙一次（软势场把穿越次数压到最少），实际 " + crossings + " 次");
    }

    @Test
    void 高代价格子必须被完全绕开() {
        // 把避障权重提到 100：穿一格付 101，绕缺口只要约 32 ⇒ 必须绕开。
        ObstacleField wall = (x, z) -> (x == 25 && z <= 30) ? 1.0 : 0.0;
        FinslerMetric metric = new FinslerMetric(flat(0, 40), wall, 0.0, 100.0);
        ArrivalTimeField field = new FastMarchingSolver(metric).solve(20, 20);

        assertTrue(field.reachable(30, 20), "缺口后面的点必须可达");

        // 绕行代价应接近 32（北上到 z=31、东行 10 格、南下 11 格）
        double detour = field.at(30, 20);
        assertTrue(detour > 25.0 && detour < 45.0,
                "绕行代价应接近 32，实际 " + detour);

        // 路径上一个高势点都不能有
        List<int[]> path = field.pathTo(30, 20);
        assertFalse(path.isEmpty(), "路径不应为空");
        for (int[] station : path) {
            assertEquals(0.0, wall.potential(station[0], station[1]), 1e-9,
                    "路径穿过了障碍点 (" + station[0] + "," + station[1] + ")");
        }
    }

    // ── 性质 5：一次求解可回答多个终点（相对旧实现的结构优势）──

    @Test
    void 一次求解可以回答任意多个终点() {
        ArrivalTimeField field = new FastMarchingSolver(
                FinslerMetric.of(flat(0, 40), ObstacleField.NONE)).solve(20, 20);

        // 24 个结构节点那种场景：一次求解、多个终点。旧实现每对端点跑一次 A*。
        int reachable = 0;
        for (int x = 0; x <= 40; x += 5) {
            for (int z = 0; z <= 40; z += 5) {
                if (field.reachable(x, z)) reachable++;
            }
        }
        assertEquals(81, reachable, "41x41 平地上 9x9 个采样点应全部可达");

        // 距离与曼哈顿无关、与欧氏一致
        assertEquals(Math.hypot(15, 20), field.at(35, 40), Math.hypot(15, 20) * 0.08,
                "对角远处仍应逼近欧氏距离");
    }

    // ── 性质 6：源的邻居时间连续、无洞 ───────────────────────────

    @Test
    void 到达时间场必须连续无洞() {
        ArrivalTimeField field = new FastMarchingSolver(
                FinslerMetric.of(flat(0, 20), ObstacleField.NONE)).solve(10, 10);

        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 20; z++) {
                double t = field.at(x, z);
                assertTrue(Double.isFinite(t), "(" + x + "," + z + ") 应可达");
                // 每格最多走 1 格正交（代价 1），所以 T 不应小于切比雪夫距离
                double chebyshev = Math.max(Math.abs(x - 10), Math.abs(z - 10));
                assertTrue(t >= chebyshev - 1e-9,
                        "(" + x + "," + z + ") 的 T=" + t + " 不应小于切比雪夫距离 " + chebyshev);
            }
        }
    }
}
