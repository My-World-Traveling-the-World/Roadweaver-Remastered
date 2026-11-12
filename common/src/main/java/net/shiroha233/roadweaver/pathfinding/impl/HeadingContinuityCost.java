package net.shiroha233.roadweaver.pathfinding.impl;

/**
 * 航向连续性代价（0.9.030 / Z-2 根治「爱平缓盘旋」）。纯几何，无世界访问。
 *
 * <p>核心判据不是「转角多大」，而是**横向速度的符号翻转**：左右来回换向才算抖动；
 * 一直朝同一侧弯（盘山公路那种长弧）不罚。这正是用户要的「绕远但一路缓弯」——
 * 绕远不设上限，但不许短腿来回折返。
 *
 * <p>三种情形：
 * <ul>
 *   <li><b>同向续弯</b>：本步与上一步的横向速度同号（继续往同一侧偏）⇒ 只收很小的基础转向价</li>
 *   <li><b>换向</b>：横向速度变号（左-右 / 右-左）⇒ 收换向价；若上一段**短腿**（&lt; 阈值），再乘短腿系数</li>
 *   <li><b>直行</b>：横向速度≈0 ⇒ 免费</li>
 * </ul>
 *
 * <p>三个寻路器共用这一份公式，保证「势场 / 梯度下降 / 双向 A*」转向口径一致。
 * 与 {@code MountainSwitchbackPlanner} 的 {@code turnPenalty} 量级对齐（换向基础价 7.0）。
 */
public final class HeadingContinuityCost {

    /** 换向基础价（与 MountainSwitchbackPlanner 的 7.0 对齐）。 */
    public static final double DEFAULT_FLIP_COST = 7.0D;
    /** 同向续弯的基础价（远小于换向，鼓励长弧而不是直线）。 */
    public static final double DEFAULT_CURVE_COST = 1.0D;
    /** 短腿阈值（格）。上一段水平长度小于它 ⇒ 换向更贵。 */
    public static final double DEFAULT_SHORT_LEG_BLOCKS = 48.0D;
    /** 短腿换向的乘数。 */
    public static final double DEFAULT_SHORT_LEG_MULTIPLIER = 3.0D;
    /** 判定「直行」的横向速度死区（相对值）。 */
    private static final double STRAIGHT_EPS = 1e-9D;

    private HeadingContinuityCost() {}

    /**
     * 两条向量的叉积 z 分量。其符号就是「往左还是往右偏」（横向速度方向）。
     * 连续两步若该值同号 ⇒ 同侧续弯（长弧）；异号 ⇒ 换向（抖动）。
     */
    public static double cross(double ax, double az, double bx, double bz) {
        return ax * bz - az * bx;
    }

    /** 上一步与本步是否换向：{@code prevCross} 与 {@code cross} 异号且都非零。 */
    public static boolean isFlip(double prevCross, double cross) {
        if (Math.abs(prevCross) < STRAIGHT_EPS || Math.abs(cross) < STRAIGHT_EPS) return false;
        return (prevCross > 0.0D) != (cross > 0.0D);
    }

    /** 是否同侧续弯：{@code prevCross} 与 {@code cross} 同号且都非零。 */
    public static boolean isSameSideCurve(double prevCross, double cross) {
        if (Math.abs(prevCross) < STRAIGHT_EPS || Math.abs(cross) < STRAIGHT_EPS) return false;
        return (prevCross > 0.0D) == (cross > 0.0D);
    }

    /**
     * 本步应付的航向代价。
     *
     * @param flipCost   换向基础价（&lt;=0 且 curveCost&lt;=0 时整族关闭）
     * @param curveCost  同向续弯基础价
     * @param prevLegBlocks 上一段的水平长度（格），用于短腿判定
     */
    public static double cost(double prevCross, double cross, double prevLegBlocks,
                              double flipCost, double curveCost,
                              double shortLegBlocks, double shortLegMultiplier) {
        if (flipCost <= 0.0D && curveCost <= 0.0D) return 0.0D;
        if (isFlip(prevCross, cross)) {
            double base = Math.max(0.0D, flipCost);
            return prevLegBlocks < shortLegBlocks
                    ? base * Math.max(1.0D, shortLegMultiplier)
                    : base;
        }
        if (isSameSideCurve(prevCross, cross)) {
            return Math.max(0.0D, curveCost);
        }
        return 0.0D; // 直行
    }
}
