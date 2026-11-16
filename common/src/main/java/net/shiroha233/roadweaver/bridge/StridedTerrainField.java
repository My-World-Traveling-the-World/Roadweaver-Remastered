/* 文件职责：按步长采样的地形场 —— 让 1500×1500 的包围盒不必逐格采样。 */
package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.metric.TerrainField;

/**
 * 按固定步长采样的地形场。
 *
 * <h2>为什么必须能按步长采样</h2>
 * <p>真实路线的包围盒动辄 1500×1500 = 225 万列。逐格采一列要过一次高度图查询 +
 * 缓存查找，225 万次既不现实也存不下（{@code int[]} 就是 9 MB，每条路线一份）。</p>
 *
 * <p>而旧实现的 A* 本来就是 {@code aStarStep = 8} 的粗网格 —— 步长不是我引入的
 * 额外妥协，而是<b>旧实现一直就有的现实约束</b>，只是以前散在
 * {@code aStarStep} 里、没人说清它到底是什么。</p>
 *
 * <h2>三件必须做对的事</h2>
 * <ol>
 *   <li><b>对外仍是逐格定义</b>：{@link #height} 收方块坐标，超出网格的坐标
 *       <b>就近吸附</b>到最近采样点 —— 求解器只会在网格点上访问，吸附只是防御。</li>
 *   <li><b>梯度必须除以步长</b>：网格相邻两点的水平距离是 {@code step} 格，
 *       不是 1 格。不除会让坡度被放大 {@code step} 倍，坡罚跟着炸掉。</li>
 *   <li><b>边界退化</b>：网格边缘只能取到单侧邻居，退化为单侧差分。</li>
 * </ol>
 *
 * @see TerrainField
 * @see SampledTerrainField
 */
public final class StridedTerrainField implements TerrainField {

    private final int minX;
    private final int minZ;
    private final int columns;
    private final int rows;
    private final int step;
    private final int[] heights;

    private StridedTerrainField(int minX, int minZ, int columns, int rows,
                                int step, int[] heights) {
        this.minX = minX;
        this.minZ = minZ;
        this.columns = columns;
        this.rows = rows;
        this.step = step;
        this.heights = heights;
    }

    /**
     * 按步长采样一块区域。
     *
     * @param minX       区域最小 x（方块坐标）
     * @param minZ       区域最小 z（方块坐标）
     * @param blockWidth 区域宽（格）
     * @param blockDepth 区域高（格）
     * @param step       采样步长（格，≥1）
     * @param sampler    采样器（收方块坐标范围）
     */
    public static StridedTerrainField sample(int minX, int minZ,
                                             int blockWidth, int blockDepth,
                                             int step,
                                             SampledTerrainField.GridSampler sampler) {
        if (sampler == null) throw new IllegalArgumentException("sampler 不能为 null");
        if (blockWidth <= 0 || blockDepth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + blockWidth + "x" + blockDepth);
        }
        int safeStep = Math.max(1, step);
        int columns = (blockWidth - 1) / safeStep + 1;
        int rows = (blockDepth - 1) / safeStep + 1;
        int gridWidth = (columns - 1) * safeStep + 1;
        int gridDepth = (rows - 1) * safeStep + 1;

        int[] full = sampler.sampleHeights(minX, minZ, gridWidth, gridDepth);
        if (full == null || full.length != gridWidth * gridDepth) {
            throw new IllegalArgumentException(
                    "采样器返回长度必须是 " + (gridWidth * gridDepth) + "，实际 "
                            + (full == null ? "null" : full.length));
        }

        int[] heights = new int[columns * rows];
        for (int iz = 0; iz < rows; iz++) {
            for (int ix = 0; ix < columns; ix++) {
                heights[ix + iz * columns] = full[(ix * safeStep) + (iz * safeStep) * gridWidth];
            }
        }
        return new StridedTerrainField(minX, minZ, columns, rows, safeStep, heights);
    }

    /** 用已采好的子采样数组构造（测试与已有网格复用）。 */
    public static StridedTerrainField of(int minX, int minZ, int columns, int rows,
                                         int step, int[] heights) {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("网格尺寸必须为正");
        }
        if (heights == null || heights.length != columns * rows) {
            throw new IllegalArgumentException(
                    "高度数组长度必须是 " + (columns * rows) + "，实际 "
                            + (heights == null ? "null" : heights.length));
        }
        return new StridedTerrainField(minX, minZ, columns, rows, Math.max(1, step), heights);
    }

    @Override
    public int step() { return step; }

    @Override
    public int columns() { return columns; }

    @Override
    public int rows() { return rows; }

    @Override
    public int minX() { return minX; }

    @Override
    public int minZ() { return minZ; }

    @Override
    public int maxX() { return minX + (columns - 1) * step; }

    @Override
    public int maxZ() { return minZ + (rows - 1) * step; }

    @Override
    public int height(int x, int z) {
        if (x < minX || x > maxX() || z < minZ || z > maxZ()) {
            throw new IllegalArgumentException(
                    "点 (" + x + "," + z + ") 不在区域内 [" + minX + ".." + maxX()
                            + "] x [" + minZ + ".." + maxZ() + "]");
        }
        return heights[indexAt(x, z)];
    }

    // 伴随的水域数组与高度必须吸附到同一格，尤其是负坐标和半步位置。
    int indexAt(int x, int z) {
        return snapX(x) + snapZ(z) * columns;
    }

    /**
     * 梯度按<b>每格</b>归一 —— 网格相邻两点的水平距离是 {@code step} 格。
     *
     * <p>不除这一步，坡度会被放大 {@code step} 倍。步长 8 时坡罚就是 64 倍 ——
     * 表现为「平原上也开始绕坡」，而且很难看出是这个原因。</p>
     */
    @Override
    public double[] gradient(int x, int z) {
        int ix = snapX(x);
        int iz = snapZ(z);
        int ixm = Math.max(0, ix - 1);
        int ixp = Math.min(columns - 1, ix + 1);
        int izm = Math.max(0, iz - 1);
        int izp = Math.min(rows - 1, iz + 1);

        double gx = (ixp == ixm) ? 0.0
                : (heights[ixp + iz * columns] - heights[ixm + iz * columns])
                / ((double) (ixp - ixm) * step);
        double gz = (izp == izm) ? 0.0
                : (heights[ix + izp * columns] - heights[ix + izm * columns])
                / ((double) (izp - izm) * step);
        return new double[]{gx, gz};
    }

    /** 方块坐标 → 列下标（就近吸附）。 */
    private int snapX(int x) {
        int ix = Math.round((x - minX) / (float) step);
        return Math.max(0, Math.min(columns - 1, ix));
    }

    /** 方块坐标 → 行下标（就近吸附）。 */
    private int snapZ(int z) {
        int iz = Math.round((z - minZ) / (float) step);
        return Math.max(0, Math.min(rows - 1, iz));
    }
}
