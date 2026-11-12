/* 文件职责：把现有高度采样接成度量层要的地形场（bridge 层，唯一同时认识两边的类）。 */
package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.metric.GridTerrainField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;

/**
 * 用现有精确采样喂饱度量层的 {@link TerrainField}。
 *
 * <h2>为什么不在调用时逐点采样</h2>
 * <p>度量层一次求解要查询上万个点（Fast Marching 每个格点都要高度与梯度）。
 * 逐点调用采样器会反复穿越 1090 行的缓存逻辑，而且<b>梯度需要邻域</b> ——
 * 逐点查询意味着同一列被查 3 次。</p>
 *
 * <p>所以做法是<b>一次取整块</b>：把矩形区域一次性采成数组，之后所有查询都是
 * 纯内存读。{@code AccurateHeightSampler.sampleGrid} 本来就是按矩形设计的，
 * 而且它内部有列缓存 —— 一次性请求能让缓存命中率最高。</p>
 *
 * <h2>用哪张高度图</h2>
 * <p>用 {@code motionBlocking}（含障碍物、不含树叶）—— 那是<b>行走面</b>，
 * 与道路纵断面的参考面一致。{@code worldSurface} 会把树叶算进去，
 * 道路会莫名抬高；{@code oceanFloor} 是水下地形，只在过水判定时用。</p>
 *
 * <h2>两个构造入口</h2>
 * <ul>
 *   <li>{@link #of} —— 接受一个 {@link GridSampler} 函数，<b>不依赖 Minecraft</b>，
 *       可以在纯 JUnit 里测；</li>
 *   <li>{@link #fromAccurateSampler} —— 生产入口，走真实的
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
        /**
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
        if (sampler == null) throw new IllegalArgumentException("sampler 不能为 null");
        int[] heights = sampler.sampleHeights(minX, minZ, width, depth);
        // 长度由 GridTerrainField 的构造器校验；这里先给出更有指向性的错误
        if (heights == null || heights.length != width * depth) {
            throw new IllegalArgumentException(
                    "采样器返回的长度必须是 " + (width * depth) + "，实际 "
                            + (heights == null ? "null" : heights.length));
        }
        return new GridTerrainField(minX, minZ, width, depth, heights);
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
        if (sampler == null) throw new IllegalArgumentException("sampler 不能为 null");
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + width + "x" + depth);
        }

        AccurateHeightGridRequest request =
                new AccurateHeightGridRequest(minX, minZ, width, depth, 1);
        AccurateHeightGrid grid = sampler.sampleGrid(request);

        // motionBlocking 是「行走面」：含障碍物、不含树叶。
        // worldSurface 会把树冠算进去，道路会莫名抬高；oceanFloor 只在过水判定用。
        return new GridTerrainField(minX, minZ, width, depth, grid.motionBlocking());
    }
}
