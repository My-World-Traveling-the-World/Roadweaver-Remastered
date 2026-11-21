/* 文件职责：把现有高度采样接成度量层地形场，并按规模选择整场或瓦片读入。 */
package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.GridLayout;
import net.shiroha233.roadweaver.metric.DenseGridWorkload;
import net.shiroha233.roadweaver.metric.FastMarchingSolver;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;

/**
 * 用现有精确采样喂饱度量层的 {@link TerrainField}。
 *
 * <h2>为什么按规模选择批次</h2>
 * <p>小域一次取整块，梯度直接读数组；大域用有界瓦片，避免在早停传播之前
 * 先分配全域三张高度图。两种策略都经同一精确采样入口，不另造采样线程或高度近似。</p>
 *
 * <h2>用哪张高度图</h2>
 * <p>用 {@code motionBlocking}（含障碍物、不含树叶）—— 那是<b>行走面</b>，
 * 与道路纵断面的参考面一致。{@code worldSurface} 会把树叶算进去，
 * 道路会莫名抬高；{@code oceanFloor} 是水下地形，只在过水判定时用。</p>
 *
 * <h2>构造入口</h2>
 * <ul>
 *   <li>{@link #of} —— 接受一个 {@link GridSampler} 函数，<b>不依赖 Minecraft</b>，
 *       可以在纯 JUnit 里测；</li>
 *   <li>{@link #planningSurface} —— 网络规划按规模选择整场或瓦片；</li>
 *   <li>{@link #fromAccurateSampler} —— 兼容整场入口，走真实的
 *       {@link AccurateHeightSampler}（需要 {@code ServerLevel}）。</li>
 * </ul>
 *
 * @see net.shiroha233.roadweaver.metric.TerrainField
 */
public final class SampledTerrainField {

    private SampledTerrainField() {}

    /**
     * 矩形区域采样器。
     *
     * <p>返回数组下标约定 {@code (x−minX) + (z−minZ)*width}，与
     * {@link GridTerrainField} 及 Fast Marching 的内部布局一致，避免二次映射。</p>
     */
    @FunctionalInterface
    public interface GridSampler {
        /** 矩形高度采样必须返回完整宽乘深数组，避免索引与坐标边界错位。
 *
         * @param minX   区域最小 x
         * @param minZ   区域最小 z
         * @param width  区域宽（格）
         * @param depth  区域高（格）
         * @return 高度数组，长度必须是 {@code width * depth}
         */
        int[] sampleHeights(int minX, int minZ, int width, int depth);
    }

    /**
     * 用任意矩形采样器构造地形场（不依赖 Minecraft，测试用）。
     */
    public static TerrainField of(int minX, int minZ, int width, int depth,
                                  GridSampler sampler) {
        return of(minX, minZ, width, depth, 1, sampler);
    }

    /**
     * 带采样步长地构造地形场。
     *
     * <h3>为什么要这个重载（2026-11-13）</h3>
     * <p>{@link TerrainField#step()} 的注释写着：真实路线的包围盒动辄 1500×1500，
     * 逐格采样是 225 万列，<b>既采不完也存不下</b>。而本类此前硬编码 step=1，
     * 于是它只能用于小区域 —— 网络级规划（{@code RoadPipeline}）因此用不了它，
     * 相关的一整条链路都停在「写好了但接不上」的状态。</p>
     *
     * <p>现在 {@code blockWidth}/{@code blockDepth} 是<b>方块覆盖范围</b>
     * （调用方视角），内部按 step 折算成列数/行数去采样。
     * {@code step = 1} 时与旧行为逐位一致。</p>
     */
    public static TerrainField of(int minX, int minZ, int blockWidth, int blockDepth,
                                  int step, GridSampler sampler) {
        if (sampler == null) throw new IllegalArgumentException("sampler 不能为 null");
        if (blockWidth <= 0 || blockDepth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + blockWidth + "x" + blockDepth);
        }
        int safeStep = Math.max(1, step);
        // 先验证尺寸与坐标，再调用世界采样器，避免异常请求先制造大批临时列。
        GridLayout layout = GridLayout.coveringBlocks(minX, minZ, blockWidth, blockDepth, safeStep);
        layout.arrayLength();
        int columns = layout.columns();
        int rows = layout.rows();
        int[] heights = sampler.sampleHeights(minX, minZ, columns, rows);
        // 长度由 GridTerrainField 的构造器校验；这里先给出更有指向性的错误
        if (heights == null || heights.length != layout.arrayLength()) {
            throw new IllegalArgumentException(
                    "采样器返回的长度必须是 " + layout.arrayLength() + "，实际 "
                            + (heights == null ? "null" : heights.length));
        }
        return new GridTerrainField(minX, minZ, columns, rows, safeStep, heights);
    }

    /**
     * 用真实的精确采样器构造地形场。
     *
     * <p>一次请求整块区域（{@code step = 1}，即逐格），取
     * {@code motionBlocking}（行走面，不含树叶）。</p>
     *
     * @param sampler 精确采样器（来自 {@code AccurateHeightSampler.create(level)}）
     * @param minX    区域最小 x
     * @param minZ    区域最小 z
     * @param width   区域宽（格）
     * @param depth   区域高（格）
     */
    public static TerrainField fromAccurateSampler(AccurateHeightSampler sampler,
                                                   int minX, int minZ,
                                                   int width, int depth) {
        return fromAccurateSampler(sampler, minX, minZ, width, depth, 1);
    }

    /**
     * 带采样步长的整场兼容入口；网络规划由 planningSurface 选择读入策略。
     *
     * <h3>精度与规模的取舍</h3>
     * <p>规划要到「哪两个结构连一条路」的粒度，1500×1500 的区域在 step=8 时是
     * 187×187 ≈ 3.5 万列 —— 比逐格的 225 万列小 64 倍，而拓扑决策根本不需要方格精度。
     * 真正要方格精度的是单条路线的几何，那由 {@code BandRouteSolver} 在
     * 自己的包围盒里做（它的搜索步长本来就等于 A* 的 {@code effectivePathGridStep}）。</p>
     *
     * <p>{@link AccurateHeightGridRequest} 的 {@code width}/{@code height} 是
     * <b>采样点数</b>（不是方块宽），{@code blockX(i) = minX + (i % width) * step}，
     * 所以这里按 step 折算列数再请求。</p>
     */
    public static TerrainField fromAccurateSampler(AccurateHeightSampler sampler,
                                                   int minX, int minZ,
                                                   int blockWidth, int blockDepth,
                                                   int step) {
        return sampleSurface(sampler, minX, minZ, blockWidth, blockDepth, step).terrain();
    }

    // 在读世界前计算同一份布局和载荷，让大范围诊断不再依赖成功分配数组。
    public static GridLayout planningLayout(int minX, int minZ, int maxX, int maxZ, int padding, int step) {
        return GridLayout.aroundBounds(minX, minZ, maxX, maxZ, padding, step);
    }

    public static DenseGridWorkload estimate(GridLayout grid, boolean keepAllHeightMaps, int targetCount) {
        return DenseGridWorkload.estimate(grid, keepAllHeightMaps, false, targetCount);
    }

    /** 网络规划的规模选择入口；瓦片化只搬读入边界，不缩减全局绕行范围。 */
    public static SurfaceGrid planningSurface(GridLayout layout, TiledTerrainField.GridSampler sampler) {
        layout.arrayLength();
        if (FastMarchingSolver.usesPagedStorage(layout)) {
            TiledTerrainField terrain = new TiledTerrainField(layout, sampler);
            return new SurfaceGrid(terrain, terrain::waterProxy);
        }
        AccurateHeightGridRequest request = new AccurateHeightGridRequest(layout.minX(), layout.minZ(),
                layout.columns(), layout.rows(), layout.step());
        AccurateHeightGrid heights = sampler.sample(request);
        if (!request.equals(heights.request())) throw new IllegalArgumentException("sampled grid request mismatch");
        return new SurfaceGrid(new GridTerrainField(layout.minX(), layout.minZ(), layout.columns(),
                layout.rows(), layout.step(), heights.motionBlocking()), heights);
    }

    // 影子回溯只读这次采样已有的高度差，避免为了日志再采一块地形或加载区块。
    public static SurfaceGrid sampleSurface(AccurateHeightSampler sampler, int minX, int minZ,
                                            int blockWidth, int blockDepth, int step) {
        if (sampler == null) throw new IllegalArgumentException("sampler 不能为 null");
        if (blockWidth <= 0 || blockDepth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + blockWidth + "x" + blockDepth);
        }
        int safeStep = Math.max(1, step);
        // 先验证尺寸与坐标，再调用世界采样器，避免异常请求先制造大批临时列。
        GridLayout layout = GridLayout.coveringBlocks(minX, minZ, blockWidth, blockDepth, safeStep);
        layout.arrayLength();
        int columns = layout.columns();
        int rows = layout.rows();

        AccurateHeightGridRequest request =
                new AccurateHeightGridRequest(minX, minZ, columns, rows, safeStep);
        AccurateHeightGrid grid = sampler.sampleGrid(request);

        // motionBlocking 是「行走面」：含障碍物、不含树叶。
        // worldSurface 会把树冠算进去，道路会莫名抬高；oceanFloor 只在过水判定用。
        return new SurfaceGrid(new GridTerrainField(minX, minZ, columns, rows, safeStep,
                grid.motionBlocking()), grid);
    }

    public static final class SurfaceGrid {
        private final TerrainField terrain;
        private final java.util.function.IntBinaryOperator water;

        SurfaceGrid(TerrainField terrain, AccurateHeightGrid heights) {
            this(terrain, (x, z) -> waterProxy(heights, x, z));
        }

        private SurfaceGrid(TerrainField terrain, java.util.function.IntBinaryOperator water) {
            this.terrain = terrain;
            this.water = water;
        }

        public TerrainField terrain() {
            return terrain;
        }

        public int waterProxy(int x, int z) {
            return water.applyAsInt(x, z);
        }

        private static int waterProxy(AccurateHeightGrid heights, int x, int z) {
            var request = heights.request();
            // 与瓦片分支一致先升 long，跨零点的大步长请求也不能错判为越界。
            long dx = (long) x - request.minX(), dz = (long) z - request.minZ();
            int step = request.step();
            // 高度图能提供湿列的代理证据，但高度相等不等于确认干列（这里没有群系和流体）。
            if (dx < 0 || dz < 0 || dx % step != 0 || dz % step != 0
                    || dx / step >= request.width() || dz / step >= request.height()) return -1;
            int i = (int) (dx / step + dz / step * request.width());
            int gap = heights.worldSurface()[i] - heights.oceanFloor()[i];
            return gap > 0 ? gap : -1;
        }
    }
}
