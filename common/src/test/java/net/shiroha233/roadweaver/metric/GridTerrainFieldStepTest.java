/* 文件职责：钉住 GridTerrainField 的采样步长语义 —— 尤其是梯度必须按每格归一。 */
package net.shiroha233.roadweaver.metric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GridTerrainField} 的步长语义。
 *
 * <h2>为什么这几条必须钉住</h2>
 * <p>{@link TerrainField#step()} 的注释写明：真实路线的包围盒动辄 1500×1500，
 * 逐格采样是 225 万列，<b>既采不完也存不下</b>。所以步长不是可选优化，
 * 而是这个接口能不能用于网络级规划的前提。</p>
 *
 * <p>而 {@code GridTerrainField} 作为接口<b>唯一的具体实现</b>，在 2026-11-13 之前
 * 完全没有实现 step —— 数组恒按 {@code width × depth} 逐格布局，
 * {@code maxX()} 恒等于 {@code minX + width - 1}。后果是整条网络级链路
 * （{@code SampledTerrainField} → {@code RoadPipeline}）都只能硬编码 step=1，
 * 于是「写好了但接不上」。</p>
 *
 * <h2>三条最容易悄悄错的地方</h2>
 * <ol>
 *   <li><b>梯度不除步长</b>：采样点之间隔 {@code step} 格，不除会让坡度放大
 *       {@code step} 倍。step=8 时坡罚炸 64 倍 —— 表现为「平原上也开始绕坡」，
 *       而且极难看出是这个原因。这条用「每 2 格升 2 格 = 坡度 1.0」的斜坡钉住。</li>
 *   <li><b>高度不吸附</b>：方块坐标落在两个采样点之间时必须<b>就近吸附</b>，
 *       不能越界报错（调用方看到的是逐格定义的地形）。</li>
 *   <li><b>step=1 的行为漂移</b>：旧构造器必须与新构造器逐位等价，
 *       否则所有既有调用方会静默受影响。</li>
 * </ol>
 */
class GridTerrainFieldStepTest {

    /** 沿 x 每 2 格升 2 格（真实坡度 = 1.0）的斜坡，4 列 × 2 行，step=2。 */
    private static GridTerrainField rampStep2() {
        // 采样点：x = 0, 2, 4, 6；z = 0, 2
        // 高度（坡度 1.0）：x=0→0, x=2→2, x=4→4, x=6→6
        int[] heights = new int[]{
                0, 2, 4, 6,     // z = 0
                0, 2, 4, 6,     // z = 2
        };
        return new GridTerrainField(0, 0, 4, 2, 2, heights);
    }

    @Test
    void 步长为一时的行为与旧构造器逐位一致() {
        int[] heights = new int[]{
                10, 11, 12,
                20, 21, 22,
        };
        GridTerrainField oldWay = new GridTerrainField(0, 0, 3, 2, heights);
        GridTerrainField newWay = new GridTerrainField(0, 0, 3, 2, 1, heights);

        assertEquals(1, oldWay.step(), "旧构造器必须等价于 step=1");
        assertEquals(1, newWay.step());
        assertEquals(2, oldWay.maxX());
        assertEquals(2, newWay.maxX());
        assertEquals(1, oldWay.maxZ());
        assertEquals(1, newWay.maxZ());

        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 1; z++) {
                assertEquals(oldWay.height(x, z), newWay.height(x, z),
                        "step=1 时新旧构造器在 (" + x + "," + z + ") 必须一致");
            }
        }
    }

    @Test
    void 步长覆盖范围按采样点数折算() {
        GridTerrainField f = rampStep2();
        assertEquals(2, f.step());
        assertEquals(4, f.columns(), "4 个采样列");
        assertEquals(2, f.rows(), "2 个采样行");
        assertEquals(6, f.maxX(), "maxX = minX + (columns-1)*step = 0 + 3*2");
        assertEquals(2, f.maxZ(), "maxZ = minZ + (rows-1)*step = 0 + 1*2");
        assertEquals(7, f.width(), "覆盖的方块宽 = 6-0+1");
        assertEquals(3, f.depth(), "覆盖的方块深 = 2-0+1");
    }

    @Test
    void 方块坐标必须就近吸附到采样点() {
        GridTerrainField f = rampStep2();
        // 采样点 x = 0/2/4/6，高度 0/2/4/6
        assertEquals(0, f.height(0, 0));
        assertEquals(2, f.height(2, 0));
        assertEquals(4, f.height(4, 0));

        // x=1 夹在 0 与 2 之间：Math.round(0.5)=1 ⇒ 吸附到 2 号采样点（高度 2）
        assertEquals(2, f.height(1, 0), "x=1 应吸附到最近的采样点 x=2");
        // x=3 夹在 2 与 4 之间：Math.round(1.5)=2 ⇒ 吸附到 x=4（高度 4）
        assertEquals(4, f.height(3, 0), "x=3 应吸附到最近的采样点 x=4");
        // z=1 夹在 0 与 2 之间：Math.round(0.5)=1 ⇒ 吸附到 z=2（同一斜坡，高度不变）
        assertEquals(0, f.height(0, 1));
    }

    @Test
    void 梯度必须按每格归一并除以步长() {
        GridTerrainField f = rampStep2();
        // 真实坡度 = 每格升 1 格 = 1.0
        double[] g = f.gradient(2, 0);
        assertEquals(1.0, g[0], 1e-9,
                "坡度必须是 1.0。若这里得到 2.0 说明忘了除以 step —— 采样点间距是 2 格，"
                        + "不除会让坡罚放大 2 倍（step=8 时放大 64 倍，表现为平原上也绕坡）");
        assertEquals(0.0, g[1], 1e-9, "沿 z 没有坡度");
    }

    @Test
    void 步长大于一时界外仍然快速失败() {
        GridTerrainField f = rampStep2();
        assertThrows(IllegalArgumentException.class, () -> f.height(7, 0),
                "x=7 超出 maxX=6");
        assertThrows(IllegalArgumentException.class, () -> f.height(0, 3),
                "z=3 超出 maxZ=2");
        assertThrows(IllegalArgumentException.class, () -> f.gradient(7, 0));
    }

    @Test
    void 采样器入口必须按步长折算列数而不是方块宽() {
        // 覆盖 0..6 共 7 格方块，step=2 ⇒ 4 个采样列
        TerrainField field = net.shiroha233.roadweaver.bridge.SampledTerrainField.of(
                0, 0, 7, 3, 2,
                (minX, minZ, columns, rows) -> {
                    assertEquals(0, minX);
                    assertEquals(0, minZ);
                    assertEquals(4, columns, "7 格方块 / step=2 ⇒ 4 个采样列");
                    assertEquals(2, rows, "3 格方块 / step=2 ⇒ 2 个采样行");
                    int[] out = new int[columns * rows];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = i;
                    }
                    return out;
                });
        assertEquals(2, field.step());
        assertEquals(6, field.maxX());
        assertTrue(field.contains(6, 2));
        assertTrue(!field.contains(7, 0), "覆盖范围之外不应包含");
    }
}