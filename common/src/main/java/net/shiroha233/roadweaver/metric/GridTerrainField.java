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
    private final int step;
    private final int[] heights;

    /**
     * @param minX    区域最小 x
     * @param minZ    区域最小 z
     * @param width   区域宽（格）
     * @param depth   区域高（格）
     * @param heights 高度数组，下标 = {@code (x-minX) + (z-minZ)*width}
     */
    public GridTerrainField(int minX, int minZ, int width, int depth, int[] heights) {
        this(minX, minZ, width, depth, 1, heights);
    }

    /**
     * 带采样步长的构造。
     *
     * <h3>2026-11-13：补上一个一直缺的约定</h3>
     * <p>{@link TerrainField#step()} 的注释写得很清楚 —— 「真实路线的包围盒动辄
     * 1500×1500，逐格采样是 225 万列，<b>既采不完也存不下</b>」。
     * 但本类作为该接口<b>唯一的具体实现</b>，此前却完全没有实现 step：
     * 数组恒按 {@code width × depth} 逐格布局、{@code maxX()} 恒等于
     * {@code minX + width - 1}。于是任何想按步长采样的调用方
     * （典型是 {@code SampledTerrainField.fromAccurateSampler}）
     * 只能硬编码 {@code step = 1} ⇒ 网络级规划用不了它 ⇒ 相关组件全部零引用。</p>
     *
     * <p>现在 {@code width}/{@code depth} 表示<b>采样点数</b>（列/行），
     * 覆盖的方块范围是 {@code (width-1)*step + 1}。两种取值的对应关系：</p>
     * <ul>
     *   <li>{@code step() == 1} ⇒ 与原实现<b>逐位一致</b>（旧构造器就是这么委托的）；</li>
     *   <li>{@code step() > 1} ⇒ {@link #height} 把方块坐标<b>就近吸附</b>到采样点，
     *       {@link #gradient} 除以 step 归一（不除会让坡度放大 step 倍 ——
     *       step=8 时坡罚炸 64 倍，表现为「平原上也开始绕坡」）。</li>
     * </ul>
     *
     * @param width  采样列数（不是方块宽）
     * @param depth  采样行数（不是方块深）
     * @param step   采样步长（格，≥1）
     */
    public GridTerrainField(int minX, int minZ, int width, int depth, int step, int[] heights) {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + width + "x" + depth);
        }
        // 校验必须早于数组比长；int 连乘溢出曾可把巨大请求伪装成合法的小数组。
        GridLayout layout = new GridLayout(minX, minZ, width, depth, Math.max(1, step));
        int expected = layout.arrayLength();
        if (heights == null || heights.length != expected) {
            throw new IllegalArgumentException(
                    "高度数组长度必须是 " + expected + "，实际 "
                            + (heights == null ? "null" : heights.length));
        }
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.step = Math.max(1, step);
        this.heights = heights;
    }

    /** 用地形函数构造（测试友好）。 */
    public static GridTerrainField fromFunction(int minX, int minZ, int width, int depth,
                                                java.util.function.IntBinaryOperator heightFn) {
        int[] heights = new int[new GridLayout(minX, minZ, width, depth, 1).arrayLength()];
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
                    "点 (" + x + "," + z + ") 不在区域内 [" + minX + ".." + maxX()
                            + "] x [" + minZ + ".." + maxZ() + "]");
        }
        return heights[snapX(x) + snapZ(z) * width];
    }

    /**
     * 梯度按<b>每格</b>归一 —— 采样点之间的水平距离是 {@code step} 格。
     *
     * <p>与 {@link TerrainField#gradient} 的默认实现相比，这里不能直接用
     * {@code height(x±1, z)}：step > 1 时 {@code x±1} 会吸附到同一个采样点，
     * 差分成 0（坡度永远为零，坡罚静默失效）。所以必须按<b>采样点索引</b>取邻居，
     * 再把距离除以 step。</p>
     */
    @Override
    public double[] gradient(int x, int z) {
        if (!contains(x, z)) {
            throw new IllegalArgumentException(
                    "点 (" + x + "," + z + ") 不在区域内 [" + minX + ".." + maxX()
                            + "] x [" + minZ + ".." + maxZ() + "]");
        }
        int ix = snapX(x);
        int iz = snapZ(z);
        int ixm = Math.max(0, ix - 1);
        int ixp = Math.min(width - 1, ix + 1);
        int izm = Math.max(0, iz - 1);
        int izp = Math.min(depth - 1, iz + 1);

        double gx = (ixp == ixm) ? 0.0
                : (heights[ixp + iz * width] - heights[ixm + iz * width])
                / ((double) (ixp - ixm) * step);
        double gz = (izp == izm) ? 0.0
                : (heights[ix + izp * width] - heights[ix + izm * width])
                / ((double) (izp - izm) * step);
        return new double[]{gx, gz};
    }

    /** 方块坐标 → 列下标（就近吸附，与 {@code StridedTerrainField} 同一口径）。 */
    private int snapX(int x) {
        int ix = Math.round(((long) x - minX) / (float) step);
        return Math.max(0, Math.min(width - 1, ix));
    }

    /** 方块坐标 → 行下标（就近吸附）。 */
    private int snapZ(int z) {
        int iz = Math.round(((long) z - minZ) / (float) step);
        return Math.max(0, Math.min(depth - 1, iz));
    }

    @Override
    public int step() { return step; }

    @Override
    public int minX() { return minX; }

    @Override
    public int maxX() { return minX + (width - 1) * step; }

    @Override
    public int minZ() { return minZ; }

    @Override
    public int maxZ() { return minZ + (depth - 1) * step; }
}
