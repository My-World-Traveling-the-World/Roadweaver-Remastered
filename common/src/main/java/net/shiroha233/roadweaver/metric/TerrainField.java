/* 文件职责：度量层对「地形」的唯一依赖 —— 高度与坡度；不含任何采样实现。 */
package net.shiroha233.roadweaver.metric;

/**
 * 地形场（度量层的输入端口）。
 *
 * <h2>为什么是接口</h2>
 * <p>度量层只关心「某点多高、朝某方向爬升多少」，<b>不关心高度是怎么来的</b> ——
 * 粗瓦片缓存、精确采样、OpenCL 后端、测试用的解析函数，对这里都是一样的。
 * 用接口把依赖倒置过来，度量层就能在没有 Minecraft 的纯 JUnit 环境里测试。</p>
 *
 * <p>历史教训：旧实现让寻路算法直接摸 {@code CoarseTerrainRegion} 这类具体类，
 * 结果同一个算法有 4 份「硬障碍口径」的补丁，且 {@code blocksPlanningObstacle}
 * 只被 {@code BasicAStarPathfinder} 读、其余四个寻路器全部无条件跳过
 * （2026-11-07 实证）。接口隔离就是为了不再出现这种分叉。</p>
 *
 * <h2>坐标约定</h2>
 * <p>全部是<b>方块坐标</b>（格），不是区块坐标，也不是站索引。x/z 各自独立。</p>
 *
 * @see FinslerMetric
 */
public interface TerrainField {

    /** 该点的地形高度（格）。 */
    int height(int x, int z);

    /**
     * 该点的高度梯度（每格的水平变化率），返回 {@code [dh/dx, dh/dz]}。
     *
     * <p>默认用中心差分，步长 1 格。只有需要更精确的坡度时子类才覆写。</p>
     */
    default double[] gradient(int x, int z) {
        double gx = (height(x + 1, z) - height(x - 1, z)) / 2.0;
        double gz = (height(x, z + 1) - height(x, z - 1)) / 2.0;
        return new double[]{gx, gz};
    }

    /** 可行区域的最小 x（含）。 */
    int minX();

    /** 可行区域的最大 x（含）。 */
    int maxX();

    /** 可行区域的最小 z（含）。 */
    int minZ();

    /** 可行区域的最大 z（含）。 */
    int maxZ();

    /** 可行区域宽度（格）。 */
    default int width() {
        return maxX() - minX() + 1;
    }

    /** 可行区域高度（格）。 */
    default int depth() {
        return maxZ() - minZ() + 1;
    }

    /** 点是否在可行区域内。 */
    default boolean contains(int x, int z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }
}
