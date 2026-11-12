/* 文件职责：度量层对「地形」的唯一依赖 —— 高度与坡度；不含任何采样实现。
 * ⚠ 本文件不得 import pathlogic.* —— 度量层是最底层，反向依赖会破坏分层。
 *   2026-11-13：这里曾残留一行 import static ...ObstacleJudge.*;（零引用、同步脚本副产物），
 *   修好守卫的 import 正则后才被发现。 */
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
     * <h2>边界必须自己处理</h2>
     * <p>中心差分要在 {@code x±1} 取样，但边界上会越界。这里把取样点<b>夹回</b>区域内、
     * 并除以真实的取样距离 —— 于是边界退化成单侧差分，而内部仍是中心差分。</p>
     *
     * <p><b>为什么不索性让 {@code height} 越界返回 0</b>：那会把「采样漏了一块」
     * 变成幽灵 bug（坡度看起来正常、路径却莫名贴边）。越界必须在真正的采样接口处
     * 快速失败，而梯度是<b>几何量</b>，在区域边界上本来就应该有定义。</p>
     */
    default double[] gradient(int x, int z) {
        int xm = Math.max(minX(), x - 1);
        int xp = Math.min(maxX(), x + 1);
        int zm = Math.max(minZ(), z - 1);
        int zp = Math.min(maxZ(), z + 1);

        double gx = (xp == xm) ? 0.0
                : (height(xp, z) - height(xm, z)) / (double) (xp - xm);
        double gz = (zp == zm) ? 0.0
                : (height(x, zp) - height(x, zm)) / (double) (zp - zm);
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

    /**
     * 采样步长（格）。默认为 1（逐格）。
     *
     * <h2>为什么需要步长</h2>
     * <p>真实路线的包围盒动辄 1500×1500，逐格采样是 225 万列 —— 既采不完也存不下。
     * 而旧实现的 A\* 本来就是 {@code aStarStep=8} 的粗网格。
     * 步长让新内核既能粗搜（拓扑决策）也能细算（单条路线几何）。</p>
     *
     * <p><b>关键约定</b>：{@link #height} 与 {@link #gradient} 收的是<b>方块坐标</b>，
     * 步长只影响内部采样密度与求解器的网格间距 —— 调用方看到的仍然是逐格定义的地形。</p>
     */
    default int step() {
        return 1;
    }

    /** 沿 x 的采样点数 = ⌊(maxX−minX)/step⌋ + 1。 */
    default int columns() {
        return (maxX() - minX()) / step() + 1;
    }

    /** 沿 z 的采样点数 = ⌊(maxZ−minZ)/step⌋ + 1。 */
    default int rows() {
        return (maxZ() - minZ()) / step() + 1;
    }

    /** 可行区域宽度（格，含两端）。 */
    default int width() {
        return maxX() - minX() + 1;
    }

    /** 可行区域高度（格，含两端）。 */
    default int depth() {
        return maxZ() - minZ() + 1;
    }

    /** 点是否在可行区域内。 */
    default boolean contains(int x, int z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }
}
