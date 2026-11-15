/* 文件职责：把长度、坡度、障碍折成一个与参数化无关的路段代价（Finsler 度量）。 */
package net.shiroha233.roadweaver.metric;

/**
 * Finsler 度量：路段代价只依赖「位置 + 方向」。
 *
 * <h2>公式</h2>
 * <pre>
 * c(x, θ̂) = 1 + λ_grade·(∇h(x)·θ̂)² + λ_obs·V(x)
 * </pre>
 * <ul>
 *   <li>{@code 1} —— 基础代价，等于 1 单位长度</li>
 *   <li>{@code ∇h·θ̂} —— <b>沿该方向</b>的地形坡度（点积，不是梯度模长）</li>
 *   <li>{@code V(x)} —— 障碍势</li>
 * </ul>
 *
 * <h2>为什么这个写法能根治锯齿</h2>
 * <p>旧实现的坡度罚是<b>按步收</b>而基础代价<b>按长度收</b>：同样爬 1 格，
 * 正交步总代价 8008.5、对角步 210.9 —— <b>38 倍不对称</b>（2026-11-07 用线上
 * 47 个默认值复算）。结果是「南北向直、对角线不走」的 ±8 幅锯齿，
 * 而且是<b>全局唯一最优解</b>，不是 tie-break 问题。</p>
 *
 * <p>这里坡度乘的是<b>方向分量</b>而不是「步数」，且整个代价按弧长积分，所以
 * 正交步与对角步的坡度罚天然一致 —— 那个 38 倍的不对称在这个形式里**不存在**。</p>
 *
 * <h2>与参数化无关</h2>
 * <p>{@code c} 只吃位置与单位方向，不知道曲线是怎么参数化的 ⇒ 这是 Finsler 度量
 * ⇒ 测地线有确定解，可以用 Eikonal 类方法精确求解（不需要初值、不会掉局部极小）。</p>
 *
 * <h2>两个权重</h2>
 * <ul>
 *   <li>{@code gradeWeight} 越大越不愿意爬坡（平原上会绕着缓坡走）</li>
 *   <li>{@code obstacleWeight} 越大越远离障碍（等价于加宽安全距离）</li>
 * </ul>
 * <p>两者都是软约束。硬约束（一定不能穿结构）由 {@code band} 层的投影负责
 * <b>（2026-11-14 起真的做了：{@code ElasticBandSolver.smooth(input, BlockedCheck)}，
 * 接收本类 {@code isPassable} 的适配端口；此前这句话是空头承诺，审计缺陷 B）</b>，
 * 不在度量里叠加 —— 把一个硬约束写成「很大的软代价」是旧实现
 * {@code PROTECTED_GAP} 缺 fail-safe 的成因。</p>
 *
 * @see FastMarchingSolver
 * @see TerrainField
 * @see ObstacleField
 */
public final class FinslerMetric {

    /** 出厂坡罚权重。经验起点，真机标定后再调。 */
    public static final double DEFAULT_GRADE_WEIGHT = 1.0;
    /** 出厂避障权重。 */
    public static final double DEFAULT_OBSTACLE_WEIGHT = 4.0;

    private final TerrainField terrain;
    private final ObstacleField obstacles;
    private final double gradeWeight;
    private final double obstacleWeight;
    private final CostScale costScale;
    private final EdgeBlock edgeBlock;

    /** 位置代价倍率：给桥位共用这类软偏好留端口，度量层无需认识配置或世界。 */
    @FunctionalInterface
    public interface CostScale {
        double at(int x, int z);
    }

    /** 绝对禁止通过的边：粗网格的两端可能合法，边本身仍可能穿过窄禁区。 */
    @FunctionalInterface
    public interface EdgeBlock {
        boolean blocks(int x0, int z0, int x1, int z1);
    }

    /**
     * @param terrain        地形场
     * @param obstacles      障碍势场（可用 {@link ObstacleField#NONE}）
     * @param gradeWeight    坡罚权重（&gt;= 0）
     * @param obstacleWeight 避障权重（&gt;= 0）
     */
    public FinslerMetric(TerrainField terrain, ObstacleField obstacles,
                         double gradeWeight, double obstacleWeight) {
        this(terrain, obstacles, gradeWeight, obstacleWeight, null, null);
    }

    public FinslerMetric(TerrainField terrain, ObstacleField obstacles,
                         double gradeWeight, double obstacleWeight, CostScale costScale, EdgeBlock edgeBlock) {
        if (terrain == null) throw new IllegalArgumentException("terrain 不能为 null");
        this.terrain = terrain;
        this.obstacles = obstacles == null ? ObstacleField.NONE : obstacles;
        this.gradeWeight = Math.max(0.0, gradeWeight);
        this.obstacleWeight = Math.max(0.0, obstacleWeight);
        this.costScale = costScale;
        this.edgeBlock = edgeBlock;
    }

    /** 用出厂权重构造。 */
    public static FinslerMetric of(TerrainField terrain, ObstacleField obstacles) {
        return new FinslerMetric(terrain, obstacles,
                DEFAULT_GRADE_WEIGHT, DEFAULT_OBSTACLE_WEIGHT);
    }

    /** 地形场（求解器需要它的边界）。 */
    public TerrainField terrain() { return terrain; }

    /**
     * 各向异性代价：沿方向 {@code (dirX, dirZ)} 走单位长度的相对代价。
     *
     * @return 正代价（启用优惠时允许小于 1）；方向为零向量时返回 1
     */
    public double cost(int x, int z, double dirX, double dirZ) {
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-12) return 1.0;
        double ux = dirX / len;
        double uz = dirZ / len;
        double[] g = terrain.gradient(x, z);
        double along = g[0] * ux + g[1] * uz;      // 沿方向的坡度
        return (1.0 + gradeWeight * along * along + obstacleCost(x, z)) * scaleAt(x, z);
    }

    /** FMM 同时需要两轴，不重复读取坡度、障碍势与优惠走廊。 */
    void writeAxialCosts(int x, int z, int step, int index, double[] costX, double[] costZ) {
        double[] g = terrain.gradient(x, z);
        double obstacle = obstacleCost(x, z), scale = scaleAt(x, z);
        // 保留旧计算顺序：先算单位代价，再乘格距；避免改变平局和回溯选线。
        costX[index] = Math.max(1e-9, step * ((1.0 + gradeWeight * g[0] * g[0] + obstacle) * scale));
        costZ[index] = Math.max(1e-9, step * ((1.0 + gradeWeight * g[1] * g[1] + obstacle) * scale));
    }

    private double obstacleCost(int x, int z) {
        return obstacleWeight * Math.max(0.0, obstacles.potential(x, z));
    }

    private double scaleAt(int x, int z) {
        return costScale == null ? 1.0 : costScale.at(x, z);
    }

    /**
     * 各向同性代价：用梯度模长代替方向分量。
     *
     * <h2>什么时候用它</h2>
     * <p>完整的各向异性快速行进（每个方向一个 upwind stencil）实现复杂，
     * 而且在强各向异性下有数值不稳定的已知问题。第一版先用各向同性版本：
     * 它仍然保留「坡度越大越贵」「障碍附近越贵」两条真实约束，只是不区分
     * 朝上坡还是下坡。方向性的精确约束留给 {@code band} 层。</p>
     *
     * <p>这是<b>有意的取舍</b>，不是遗漏 —— 各向异性求解器在 V2 里替换本方法即可，
     * 接口不变。</p>
     */
    public double isotropicCost(int x, int z) {
        double[] g = terrain.gradient(x, z);
        double slope2 = g[0] * g[0] + g[1] * g[1];
        return (1.0 + gradeWeight * slope2 + obstacleCost(x, z)) * scaleAt(x, z);
    }

    /**
     * 该点是否可通行（＝障碍场没把它标成绝对不可通行）。
     *
     * <p>求解器必须先问这个再看代价 —— 不可通行的格子<b>不进队列</b>，
     * 而不是给一个很大的代价。这样「连通性」才是真的连通性。</p>
     */
    public boolean isPassable(int x, int z) {
        return !obstacles.impassable(x, z);
    }

    /** 没配置禁行走廊时不需要预计算边掩码。 */
    public boolean hasEdgeBlock() {
        return edgeBlock != null;
    }

    public boolean blocksEdge(int x0, int z0, int x1, int z1) {
        return edgeBlock != null && edgeBlock.blocks(x0, z0, x1, z1);
    }

    /** 坡罚权重。 */
    public double gradeWeight() { return gradeWeight; }

    /** 避障权重。 */
    public double obstacleWeight() { return obstacleWeight; }
}
