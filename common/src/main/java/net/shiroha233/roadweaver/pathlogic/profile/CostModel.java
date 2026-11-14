/* 文件职责：把「地形 + 允许窗口」翻译成 DP 的代价矩阵 —— 唯一的「什么叫好」定义处。 */
package net.shiroha233.roadweaver.pathlogic.profile;

/**
 * DP 的代价模型：<b>唯一</b>定义「什么叫好剖面」的地方。
 *
 * <h2>为什么单独抽出来</h2>
 * <p>旧版把「好」散在 15 道 pass 里：这道 pass 认为贴地好、那道认为平顺好、
 * 第三道认为桥头不许有台阶…… 判断标准分散就必然互相打架。
 * 这里把全部偏好压成一个可加的函数，{@code ProfileDpSolver} 只负责在约束下最小化它。</p>
 *
 * <h2>代价口径</h2>
 * <ul>
 *   <li><b>填方</b>：路面高于地面 {@code d} 格，代价 {@code fillWeight · d}；</li>
 *   <li><b>挖方</b>：路面低于地面 {@code d} 格，代价 {@code cutWeight · d}；</li>
 *   <li><b>超深惩罚</b>：{@code d > deepThreshold} 的部分按 {@code deepMultiplier} 加权 ——
 *       鼓励「浅填浅挖多条小台阶」胜过「一条深沟」，这正是旧版
 *       {@code fill-too-deep} 想表达的意思，现在它是代价的一部分而不是一道 pass。</li>
 * </ul>
 *
 * <p>平顺性<b>不</b>在这里惩罚 —— 它由 {@code maxStep} 硬约束保证。
 * 两件事分开：约束管「合法」，代价管「偏好」。</p>
 */
public final class CostModel {

    private CostModel() {
    }

    /**
     * 代价参数。
     *
     * <p><b>默认值（2026-11-14 修正，审计 NEW-7）</b>：{@code cutWeight=0.7}、
     * {@code fillWeight=1.0} —— <b>填方严格比挖方贵</b>，也就是 prefer-cut。</p>
     *
     * <p>原来这里是 {@code (1.0, 0.7)}，即填方比挖方便宜 30%，语义正好写反了：
     * DP 会系统性选择「垫土抬高」而不是「削平开挖」，产出长距离人工路堤与天桥。
     * 旧版（oracle）的做法是<b>硬约束</b>：{@code limits.fill()} 默认 0，
     * 剖面只许降不许升（{@code RoadGradePlanner} 与 {@code RoadSnapService}
     * 的多处注释都写着「prefer-cut：只降不升，绝不造天桥」）。</p>
     *
     * <p>DP 内不能直接用 0 当权重（那样填方永远不被选择、桥面也就无从谈起），
     * 所以把 prefer-cut 表达成<b>权重次序</b>：同一深度下 {@code fillCost > cutCost}，
     * 只要挖方可行就优先挖。数值取镜像（0.7 / 1.0）而不是放大到 10:1，
     * 是为了让总代价量级与旧的 (1.0, 0.7) 保持一致 —— 深挖惩罚
     * {@code deepMultiplier=3.0} 与阈值 {@code deepThreshold=16} 的相对关系不变。</p>
     */
    public record Settings(
            double cutWeight,
            double fillWeight,
            int deepThreshold,
            double deepMultiplier) {

        public static Settings defaults() {
            return new Settings(0.7, 1.0, 16, 3.0);
        }

        public Settings {
            if (cutWeight < 0 || fillWeight < 0) throw new IllegalArgumentException("权重不能为负");
            if (deepThreshold < 0) throw new IllegalArgumentException("deepThreshold 不能为负");
            if (deepMultiplier < 1.0) throw new IllegalArgumentException("deepMultiplier 不应小于 1");
        }
    }

    /**
     * 由地形高度构造代价矩阵。
     *
     * @param terrain 每站的地面高度
     * @param lo      每站允许的最低高度
     * @param hi      每站允许的最高高度
     * @return {@code cost[i][y - lo[i]]}
     */
    public static double[][] fromTerrain(int[] terrain, int[] lo, int[] hi, Settings s) {
        if (terrain == null || lo == null || hi == null) {
            throw new IllegalArgumentException("terrain/lo/hi 不能为 null");
        }
        if (terrain.length != lo.length || lo.length != hi.length) {
            throw new IllegalArgumentException("三个数组长度必须一致");
        }
        double[][] cost = new double[terrain.length][];
        for (int i = 0; i < terrain.length; i++) {
            int w = hi[i] - lo[i] + 1;
            cost[i] = new double[w];
            for (int k = 0; k < w; k++) {
                int y = lo[i] + k;
                int d = y - terrain[i];
                cost[i][k] = d >= 0 ? fillCost(d, s) : cutCost(-d, s);
            }
        }
        return cost;
    }

    /** 填 {@code d} 格的代价。 */
    public static double fillCost(int d, Settings s) {
        return weighted(d, s.fillWeight(), s);
    }

    /** 挖 {@code d} 格的代价。 */
    public static double cutCost(int d, Settings s) {
        return weighted(d, s.cutWeight(), s);
    }

    private static double weighted(int d, double weight, Settings s) {
        if (d <= 0) return 0.0;
        int shallow = Math.min(d, s.deepThreshold());
        int deep = d - shallow;
        return weight * shallow + weight * s.deepMultiplier() * deep;
    }
}