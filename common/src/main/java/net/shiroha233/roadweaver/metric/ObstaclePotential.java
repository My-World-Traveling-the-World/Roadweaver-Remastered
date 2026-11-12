/* 文件职责：用欧氏距离变换把「哪些格是障碍」变成连续的障碍势场。 */
package net.shiroha233.roadweaver.metric;

/**
 * 距离变换障碍势场 —— {@link ObstacleField} 的默认实现。
 *
 * <h2>为什么用距离变换而不是逐个查询</h2>
 * <p>旧实现（{@code StructureAvoidanceService}，4,766 行）对每个候选点逐个查询
 * 包围盒、逐个判避让。这种做法有两个问题：</p>
 * <ol>
 *   <li><b>布尔输出没有梯度</b>：搜索算法只知道「这里不能走」，不知道「哪边更远离障碍」，
 *       于是只能贴着边界走或直接撞墙 —— 这是「端点禁区要单独挖洞」、
 *       {@code PROTECTED_GAP} 缺 fail-safe 的土壤。</li>
 *   <li><b>复杂度是 O(候选数 × 障碍数)</b>：候选上万个、障碍上万个时是平方级。</li>
 * </ol>
 *
 * <p>距离变换把整张地图<b>一次</b>算成距离场（{@code O(n)}），之后每点查询 {@code O(1)}，
 * 且输出是连续值 —— 搜索算法自然往势低处走。</p>
 *
 * <h2>算法：Felzenszwalb–Huttenlocher 精确欧氏距离变换</h2>
 * <p>二维欧氏距离变换可分解为两次一维变换（先行后列），每次用「抛物线下包络」算法
 * 在线性时间求出 {@code d(i) = min_j ( (i−j)² + f(j) )}。结果与暴力解<b>逐点相同</b>
 * （不是近似）—— 测试用暴力解做了对照。</p>
 *
 * <h2>势的取法</h2>
 * <pre>
 * V(x) = 1                     障碍格本身（dist = 0）
 *      = 1 − dist / falloff    0 &lt; dist &lt; falloff   （线性衰减）
 *      = 0                     dist ≥ falloff
 * </pre>
 * <p>取值域被压在 [0, 1]，方便上层用 {@code obstacleWeight} 统一缩放。
 * <b>注意</b>：这里的势是<b>软</b>约束 —— 它让路径「尽量少穿」，不保证「绝不穿」。
 * 「绝不穿结构」由 {@code band} 层的硬投影负责。把硬约束写成「很大的软代价」
 * 是旧实现已有的教训。</p>
 *
 * @see ObstacleField
 * @see FinslerMetric
 */
public final class ObstaclePotential implements ObstacleField {

    /** 1D 变换里代表「不是种子点」的大数（用有限值避免 NaN）。 */
    private static final double FAR = 1e18;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final double[] distance;
    private final double falloff;

    private ObstaclePotential(int minX, int minZ, int width, int depth,
                              double[] distance, double falloff) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.distance = distance;
        this.falloff = Math.max(1e-9, falloff);
    }

    /**
     * 从布尔障碍掩码构建。
     *
     * @param minX     区域最小 x
     * @param minZ     区域最小 z
     * @param width    区域宽（格）
     * @param depth    区域高（格）
     * @param blocked  掩码，{@code blocked[ix + iz * width]} 为真表示该格是障碍
     * @param falloff  势的衰减半径（格）；距离超过它的地方势为 0
     */
    public static ObstaclePotential fromMask(int minX, int minZ, int width, int depth,
                                             boolean[] blocked, double falloff) {
        if (width <= 0 || depth <= 0) {
            throw new IllegalArgumentException("区域尺寸必须为正: " + width + "x" + depth);
        }
        if (blocked == null || blocked.length != width * depth) {
            throw new IllegalArgumentException(
                    "掩码长度必须是 " + (width * depth) + "，实际 "
                            + (blocked == null ? "null" : blocked.length));
        }

        // 平方距离场：障碍处为 0，其余为 FAR
        double[] squared = new double[width * depth];
        for (int i = 0; i < squared.length; i++) {
            squared[i] = blocked[i] ? 0.0 : FAR;
        }

        // 先行（沿 x），再列（沿 z）—— 两次一维变换即得二维精确欧氏距离
        double[] f = new double[Math.max(width, depth)];
        double[] d = new double[Math.max(width, depth)];
        int[] v = new int[Math.max(width, depth)];
        double[] z = new double[Math.max(width, depth) + 1];

        for (int iz = 0; iz < depth; iz++) {
            int base = iz * width;
            System.arraycopy(squared, base, f, 0, width);
            edt1d(f, width, d, v, z);
            System.arraycopy(d, 0, squared, base, width);
        }
        for (int ix = 0; ix < width; ix++) {
            for (int iz = 0; iz < depth; iz++) {
                f[iz] = squared[ix + iz * width];
            }
            edt1d(f, depth, d, v, z);
            for (int iz = 0; iz < depth; iz++) {
                squared[ix + iz * width] = d[iz];
            }
        }

        for (int i = 0; i < squared.length; i++) {
            squared[i] = Math.sqrt(squared[i]);
        }
        return new ObstaclePotential(minX, minZ, width, depth, squared, falloff);
    }

    /**
     * 一维欧氏距离变换：{@code out[i] = min_j ( (i−j)² + in[j] )}。
     *
     * <p>用「抛物线下包络」算法（Felzenszwalb–Huttenlocher），线性时间。</p>
     */
    private static void edt1d(double[] in, int n, double[] out, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;

        for (int q = 1; q < n; q++) {
            double s = intersection(in, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = intersection(in, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }

        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double delta = q - v[k];
            out[q] = delta * delta + in[v[k]];
        }
    }

    /** 两条抛物线的交点横坐标。 */
    private static double intersection(double[] in, int q, int p) {
        return ((in[q] + (double) q * q) - (in[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }

    @Override
    public double potential(int x, int z) {
        if (x < minX || x >= minX + width || z < minZ || z >= minZ + depth) {
            return 1.0;         // 区域外视为最差，防止路径走出规划范围
        }
        double dist = distance[(x - minX) + (z - minZ) * width];
        if (dist <= 0.0) return 1.0;
        if (dist >= falloff) return 0.0;
        return 1.0 - dist / falloff;
    }

    /** 到最近障碍的欧氏距离（格）；障碍格自身为 0。 */
    public double distanceAt(int x, int z) {
        if (x < minX || x >= minX + width || z < minZ || z >= minZ + depth) {
            return Double.POSITIVE_INFINITY;
        }
        return distance[(x - minX) + (z - minZ) * width];
    }

    /** 区域最小 x。 */
    public int minX() { return minX; }

    /** 区域最小 z。 */
    public int minZ() { return minZ; }

    /** 区域宽。 */
    public int width() { return width; }

    /** 区域高。 */
    public int depth() { return depth; }

    /** 势的衰减半径。 */
    public double falloff() { return falloff; }
}
