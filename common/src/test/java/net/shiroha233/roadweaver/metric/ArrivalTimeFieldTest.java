/* 文件职责：钉住到达时间场的回溯方向判据 —— 不许系统性偏爱对角方向。 */
package net.shiroha233.roadweaver.metric;

import java.util.List;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到达时间场回溯测试（2026-11-14，审计 NEW-5 / E）。
 *
 * <h2>缺陷是什么</h2>
 * <p>回溯原本在 8 邻域里选 <b>T 最小</b>的邻居。这系统性偏爱对角方向：
 * 沿 φ 方向的邻居 ΔT = h·(cosφ + sinφ)，对角（步长 √2·h）拿到的 ΔT
 * 恒为轴向的 (cosφ+sinφ) 倍 —— φ 越小越明显。φ=10° 时
 * 对角 ΔT=1.159h、轴向 0.985h ⇒ 对角 T 更小被选中，
 * 但它实际步长 1.414h，真实下降率只有 0.82 对 0.985 ⇒ 最陡方向被跳过，
 * 最大偏差 √2−1 = 41.4%。这是「之字」的一个成因。</p>
 *
 * <p>修复后的判据是「单位弧长下降量」最大化：(T_cur − T_nb) / 邻接弧长。</p>
 *
 * @see ArrivalTimeField
 */
class ArrivalTimeFieldTest {

    @Test
    void 原始数组堆与旧队列逐项同序包括同权和扩容() {
        record Entry(double time, int index) {}
        var legacy = new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::time));
        var frontier = new FastMarchingSolver.Frontier();
        var random = new Random(323761);
        // 同权密集，交错重复格号和出入队。只比较排序后的权重会漏掉早停差异。
        for (int i = 0; i < 5000; i++) {
            double time = random.nextInt(32);
            int index = i % 313;
            legacy.add(new Entry(time, index));
            frontier.offer(time, index);
            if (i % 3 == 0) assertEquals(legacy.remove().index(), frontier.poll(), "交错出队 " + i);
        }
        while (!legacy.isEmpty()) assertEquals(legacy.remove().index(), frontier.poll());
        assertTrue(frontier.isEmpty());
    }

    @Test
    void 多源复用不能让前一个早停留下的队列进入下一次() {
        var metric = FinslerMetric.of(step8(-32, -32), ObstacleField.NONE);
        var reused = new FastMarchingSolver(metric);
        assertTrue(reused.solve(-24, -24, -16, -24).reachable(-16, -24));
        // 首次早停的西邻点还在堆中；第二次把它设成远处终点，漏清理会提前结束。
        var expected = new FastMarchingSolver(metric).solve(24, 24, -32, -24);
        var actual = reused.solve(24, 24, -32, -24);
        assertTrue(expected.reachable(-32, -24));
        assertTrue(actual.reachable(-32, -24), "不能把遗留的终点条目当作已到达");
        for (int z = -32; z <= 32; z += 8) {
            for (int x = -32; x <= 32; x += 8) assertEquals(expected.at(x, z), actual.at(x, z));
        }
    }

    @Test
    void 非对齐目标只影响选格而不会平移回溯路径() {
        // 非零负坐标原点暴露用绝对坐标取整的错误；正反方向各对拍同一个真实采样格。
        var solver = new FastMarchingSolver(FinslerMetric.of(step8(-31, -29), ObstacleField.NONE));
        var forward = solver.solve(-24, -20, 3, -10);
        var reverse = solver.solve(3, -10, -24, -20);
        assertSameTrace(forward.pathTo(1, -13), forward.pathTo(3, -10), -23, -21, 1, -13);
        assertSameTrace(reverse.pathTo(-23, -21), reverse.pathTo(-24, -20), 1, -13, -23, -21);
    }

    @Test
    void 对齐目标的回溯点序保持原样() {
        // 水平路径没有同权方向的歧义，精确点序能抓到漏掉首格、末格或跳格。
        var solver = new FastMarchingSolver(FinslerMetric.of(step8(0, 0), ObstacleField.NONE));
        var path = solver.solve(8, 8, 32, 8).pathTo(32, 8);
        assertEquals(4, path.size());
        for (int i = 0; i < 4; i++) assertArrayEquals(new int[]{8 + 8 * i, 8}, path.get(i));
    }

    @Test
    void 真实源点自由但吸附格被挡时不得传播() {
        var terrain = step8(0, 0);
        ObstacleField blocked = new ObstacleField() {
            @Override public double potential(int x, int z) { return 0; }
            @Override public boolean impassable(int x, int z) { return x == 8 && z == 8; }
        };
        var metric = FinslerMetric.of(terrain, blocked);
        assertTrue(metric.isPassable(7, 7), "前提：只有吸附格被禁");
        assertTrue(new FastMarchingSolver(metric).solve(0, 0).reachable(24, 16),
                "正控：同一区域从合法源格能够传播到目标");
        var field = new FastMarchingSolver(metric).solve(7, 7);
        assertFalse(field.reachable(8, 8), "T=0 不能注进硬禁格");
        assertFalse(field.reachable(24, 16), "禁止从硬禁格向外传播");
    }

    private static TerrainField step8(int minX, int minZ) {
        return new TerrainField() {
            @Override public int height(int x, int z) { return 64; }
            @Override public int minX() { return minX; }
            @Override public int maxX() { return minX + 64; }
            @Override public int minZ() { return minZ; }
            @Override public int maxZ() { return minZ + 64; }
            @Override public int step() { return 8; }
        };
    }

    private static void assertSameTrace(List<int[]> aligned, List<int[]> query,
                                        int sx, int sz, int tx, int tz) {
        assertTrue(aligned.size() >= 4, "两点相隔多个采样格，禁止空列对拍");
        assertEquals(aligned.size(), query.size());
        assertArrayEquals(new int[]{sx, sz}, query.getFirst());
        assertArrayEquals(new int[]{tx, tz}, query.getLast());
        for (int i = 0; i < query.size(); i++) assertArrayEquals(aligned.get(i), query.get(i));
    }

    private static final double STEP = 8.0;      // 推导中的 h（采样步长）

    /**
     * 复刻审计里那个 φ=10° 的反例：原始 T 更小的是对角，但下降率更大的是轴向。
     */
    @Test
    void 对角原始T更小时必须仍然选轴向() {
        double current = 100.0;
        double[] times = new double[8];
        for (int dir = 0; dir < 8; dir++) {
            times[dir] = Double.POSITIVE_INFINITY;
        }
        // 轴向 +x（dir 0）：ΔT = 0.985h
        times[0] = current - 0.985 * STEP;
        // 对角（dir 4）：ΔT = 1.159h —— 原始 T 更小！
        times[4] = current - 1.159 * STEP;

        assertTrue(times[4] < times[0], "前提：对角方向的原始 T 确实更小");

        int dir = ArrivalTimeField.bestDescentDirection(current, times);

        assertEquals(0, dir,
                "必须选轴向（下降率 0.985 对 1.414 分之 1.159 = 0.820）；"
                        + "选 4 就是原来那个 41.4% 的对角偏爱");
    }

    @Test
    void 对角确实更陡时才选对角() {
        double current = 100.0;
        double[] times = new double[8];
        for (int dir = 0; dir < 8; dir++) {
            times[dir] = Double.POSITIVE_INFINITY;
        }
        times[0] = current - 0.5 * STEP;              // 轴向很缓
        times[4] = current - 1.4 * STEP;              // 对角很陡（1.4/1.414 ≈ 0.99）

        assertEquals(4, ArrivalTimeField.bestDescentDirection(current, times),
                "对角下降率更大时必须选对角，不能一律压成轴向");
    }

    @Test
    void 没有任何下降时返回负一() {
        double current = 5.0;
        double[] times = new double[8];
        for (int dir = 0; dir < 8; dir++) {
            times[dir] = current;                      // 平台
        }
        assertEquals(-1, ArrivalTimeField.bestDescentDirection(current, times),
                "平台必须返回 -1（停止），不能原地打转");
    }

    @Test
    void 不可达邻居不参与比选() {
        double current = 5.0;
        double[] times = new double[8];
        for (int dir = 0; dir < 8; dir++) {
            times[dir] = Double.POSITIVE_INFINITY;
        }
        times[2] = current - 1.0 * STEP;              // 只有 +z 下降

        assertEquals(2, ArrivalTimeField.bestDescentDirection(current, times),
                "只有唯一下降方向时必须选它");
    }

    /**
     * 平地直路的回溯必须完全沿轴向、不许有 z 抖动。
     */
    @Test
    void 平地直路回溯不带对角抖动() {
        TerrainField flat = new TerrainField() {
            @Override public int height(int x, int z) { return 0; }
            @Override public int minX() { return 0; }
            @Override public int maxX() { return 40; }
            @Override public int minZ() { return 0; }
            @Override public int maxZ() { return 40; }
        };
        FinslerMetric metric = FinslerMetric.of(flat, ObstacleField.NONE);
        ArrivalTimeField field = new FastMarchingSolver(metric).solve(2, 20);

        List<int[]> path = field.pathTo(37, 20);

        assertTrue(path.size() >= 2, "应回溯出至少两点");
        for (int[] p : path) {
            assertEquals(20, p[1],
                    "平地上源点与终点同一 z，路径必须完全水平；实际点 (" + p[0] + "," + p[1] + ")");
        }
        int[] first = path.get(0);
        int[] last = path.get(path.size() - 1);
        assertEquals(2, first[0], "路径起点必须是源点 x");
        assertEquals(37, last[0], "路径终点必须是目标 x");
    }
}
