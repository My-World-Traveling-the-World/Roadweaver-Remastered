/* 文件职责：把「一张高度数组」包装成地形场 —— 让度量层能被独立测试与复用。 */
package net.shiroha233.roadweaver.metric;

/**
 * 网格地形场：{@link TerrainField} 的最简实现。
 *
 * <h2>用途</h2>
 * <ol>
 *   <li><b>测试</b>：不需要 Minecraft 就能构造任意地形（平地、斜坡、山脊、坑）。</li>
 *   <li><b>真实采样的落地形态</b>：真实适配器（粗瓦片缓存 / 精确采样）只需要把
 *       采样结果填进一个 {@code int[]}，然后交给本类 —— 这样度量层与采样方式的
 *       解耦是物理上的，不是约定上的。</li>
 * </ol>
 *
 * <h2>坐标系</h2>
 * <p>数组下标 = {@code (x − minX) + (z − minZ) * width}，与
 * {@link FastMarchingSolver} 的内部布局一致，避免二次映射。</p>
 *
 * @see TerrainField
 */
public final class GridTerrainField implements TerrainField {

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] heights;

    /**
     * @param minX    区域最小 x
     * @param minZ    区域最小 z
     * @param width   区域宽（格）
     * @param depth   区域高（格）
     * @param heights 高度数组，下标 = {@code (x-minX) + (z-minZ)*width}
     */
    public GridTerrainField(int minX, int minZ, int width, int depth, int[] heights) {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + width + "x" + depth);
        }
        if (heights == null || heights.length != width * depth) {
            throw new IllegalArgumentException(
                    "高度数组长度必须是 " + (width * depth) + "，实际 "
                            + (heights == null ? "null" : heights.length));
        }
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.heights = heights;
    }

    /** 用地形函数构造（测试友好）。 */
    public static GridTerrainField fromFunction(int minX, int minZ, int width, int depth,
                                                java.util.function.IntBinaryOperator heightFn) {
        int[] heights = new int[width * depth];
        for (int iz = 0; iz < depth; iz++) {
            for (int ix = 0; ix < width; ix++) {
                heights[ix + iz * width] = heightFn.applyAsInt(minX + ix, minZ + iz);
            }
        }
        return new GridTerrainField(minX, minZ, width, depth, heights);
    }

    @Override
    public int height(int x, int z) {
        if (!contains(x, z)) {
            throw new IllegalArgumentException(
                    "点 (" + x + "," + z + ") 不在区域内 [" + minX + ".." + (minX + width - 1)
                            + "] x [" + minZ + ".." + (minZ + depth - 1) + "]");
        }
        return heights[(x - minX) + (z - minZ) * width];
    }

    @Override
    public int minX() { return minX; }

    @Override
    public int maxX() { return minX + width - 1; }

    @Override
    public int minZ() { return minZ; }

    @Override
    public int maxZ() { return minZ + depth - 1; }
}
