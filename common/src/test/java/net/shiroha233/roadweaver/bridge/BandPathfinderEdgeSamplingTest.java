/* 文件职责：钉住 BandPathfinder 的边级障碍采样几何 —— 8 格长的边不会被建筑「从缝里钻过去」。 */
package net.shiroha233.roadweaver.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边级障碍采样测试（2026-11-14，审计 N1）。
 *
 * <h2>要证明的判据</h2>
 * <p>格点采样只看网格点（间隔 step=8 格），宽度小于 step 的建筑可以整个夹在两个
 * 格点之间而不被发现。修复是在每条边上再采 3 个中间点（1/4、1/2、3/4）。
 * 这里不依赖 {@code PlanningSnapshot}（那要解析真实结构，单测搭不起来），
 * 而是用注入式 {@link BandPathfinder.EdgeProbe} 直接验证<b>采样几何</b>：
 * 哪些坐标被采到、硬/软命中怎么合并、能否提前退出。</p>
 *
 * @see BandPathfinder
 */
class BandPathfinderEdgeSamplingTest {

    /** 记录被采样的坐标，并按给定判定返回硬/软命中。 */
    private static final class RecordingProbe implements BandPathfinder.EdgeProbe {
        final StringBuilder sampled = new StringBuilder();
        /** 硬障碍占据的 x 区间 [hardFrom, hardTo]（含两端）；默认空区间。 */
        int hardFrom = Integer.MAX_VALUE;
        int hardTo = Integer.MIN_VALUE;
        /** 软障碍占据的 x 区间 [softFrom, softTo]（含两端）；默认空区间。 */
        int softFrom = Integer.MAX_VALUE;
        int softTo = Integer.MIN_VALUE;

        private static boolean in(int x, int from, int to) {
            return x >= from && x <= to;
        }

        @Override
        public boolean hard(int x, int y, int z) {
            sampled.append("H(").append(x).append(",").append(y).append(",").append(z).append(") ");
            return in(x, hardFrom, hardTo);
        }

        @Override
        public boolean soft(int x, int y, int z) {
            sampled.append("S(").append(x).append(",").append(y).append(",").append(z).append(") ");
            return in(x, softFrom, softTo);
        }
    }

    @Test
    void 采样点落在边的四分之一二分之一四分之三处() {
        RecordingProbe probe = new RecordingProbe();
        // 从 (0,64,0) 到 (8,64,0)：step=8 的 +x 边
        int hits = BandPathfinder.sampleEdge(0, 64, 0, 8, 64, 0, probe);

        assertEquals(0, hits, "无障碍时应返回 0");
        assertTrue(probe.sampled.toString().contains("H(2,64,0)"), "应采到 1/4 处 x=2：" + probe.sampled);
        assertTrue(probe.sampled.toString().contains("H(4,64,0)"), "应采到 1/2 处 x=4：" + probe.sampled);
        assertTrue(probe.sampled.toString().contains("H(6,64,0)"), "应采到 3/4 处 x=6：" + probe.sampled);
    }

    /**
     * 宽度 2 的建筑无论落在 8 格长边的哪个位置都必须被发现。
     *
     * <p>采样点是 2/4/6（+ 两端格点 0 和 8，它们由格点采样负责）。
     * 宽度 2 的区间起点 a ∈ 1..6：a=1→含 2、a=2→含 2、a=3→含 4、
     * a=4→含 4、a=5→含 6、a=6→含 6，逐一穷举覆盖，不留缺口。</p>
     */
    @Test
    void 夹在两格点之间的两格宽建筑在任何位置都必须被发现() {
        for (int a = 1; a <= 6; a++) {
            RecordingProbe probe = new RecordingProbe();
            probe.hardFrom = a;
            probe.hardTo = a + 1;                // 宽度 2：[a, a+1]
            int hits = BandPathfinder.sampleEdge(0, 64, 0, 8, 64, 0, probe);

            assertEquals(1, hits & 1,
                    "宽度 2 的建筑 [" + a + "," + (a + 1) + "] 必须被发现，但采样点 "
                            + probe.sampled + " 全部落空");
        }
    }

    @Test
    void 硬命中后立即返回不再继续采样() {
        RecordingProbe probe = new RecordingProbe();
        probe.hardFrom = 2;                      // 1/4 处即硬命中
        probe.hardTo = 2;
        int hits = BandPathfinder.sampleEdge(0, 64, 0, 8, 64, 0, probe);

        assertEquals(1, hits & 1, "应报硬命中");
        assertTrue(hits == 1, "硬命中后不应再叠加软命中位，实际 hits=" + hits);
        assertTrue(!probe.sampled.toString().contains("H(4,64,0)"),
                "1/4 处硬命中后应提前退出，不该采到 1/2 处：" + probe.sampled);
    }

    @Test
    void 只有软命中时返回软位且继续采样() {
        RecordingProbe probe = new RecordingProbe();
        probe.softFrom = 4;                      // 只有 1/2 处命中软障碍
        probe.softTo = 4;
        int hits = BandPathfinder.sampleEdge(0, 64, 0, 8, 64, 0, probe);

        assertEquals(2, hits, "应只报软命中位（EDGE_SOFT=2），实际 " + hits);
        assertTrue(probe.sampled.toString().contains("H(6,64,0)"),
                "软命中不提前退出，应继续采到 3/4 处：" + probe.sampled);
    }

    @Test
    void 沿z方向的边采样同样覆盖中间点() {
        RecordingProbe probe = new RecordingProbe();
        int hits = BandPathfinder.sampleEdge(0, 64, 0, 0, 64, 8, probe);

        assertEquals(0, hits, "无障碍时应返回 0");
        assertTrue(probe.sampled.toString().contains("H(0,64,2)"), "应采到 z=2：" + probe.sampled);
        assertTrue(probe.sampled.toString().contains("H(0,64,4)"), "应采到 z=4：" + probe.sampled);
        assertTrue(probe.sampled.toString().contains("H(0,64,6)"), "应采到 z=6：" + probe.sampled);
    }
}