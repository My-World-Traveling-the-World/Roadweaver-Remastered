/* 文件职责：路网规划器工厂，根据算法枚举返回对应实现。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.config.sub.PlanningConfig.PlanningAlgorithm;
import net.shiroha233.roadweaver.planning.impl.PipelinePlanner;

import net.minecraft.server.level.ServerLevel;

/**
 * 路网规划器工厂，根据算法枚举返回对应实现。
 *
 * <h2>2026-11-13：旧规划器全部退役</h2>
 * <p>用户拍板「全用最新的，旧的全部删除」。此后：</p>
 * <ul>
 *   <li>KNN/Delaunay/RNG/MST/SnapBranch/AlphaModel 六个旧规划器全部删除；</li>
 *   <li>枚举名保留仅为老配置的 GSON 解析不炸，sanitize() 已把它们全部映射到 PIPELINE；</li>
 *   <li>无论枚举写什么，工厂都只产出 {@link PipelinePlanner}（测地距离 MST）。</li>
 * </ul>
 */
public final class NetworkPlannerFactory {
    private NetworkPlannerFactory() {}

    /**
     * 只需算法枚举的入口（无世界时可用）。
     *
     * <p>{@code PIPELINE} 在这里会拿到一个没有世界的实例 —— 它会退回直线距离的 MST
     * （见 {@code PipelinePlanner.plan} 的降级分支）。生产路径请用
     * {@link #create(PlanningAlgorithm, ServerLevel)}，否则测地距离永远用不上。</p>
     */
    public static NetworkPlanner create(PlanningAlgorithm algorithm) {
        return create(algorithm, null);
    }

    /** 生产入口：带上世界，测地距离规划器才拿得到地形。 */
    public static NetworkPlanner create(PlanningAlgorithm algorithm, ServerLevel level) {
        // 2026-11-13：旧规划器全部退役，无论枚举写什么都是新内核。
        return new PipelinePlanner(level);
    }
}
