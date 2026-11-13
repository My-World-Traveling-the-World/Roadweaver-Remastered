/* 文件职责：路网规划器工厂，根据算法枚举返回对应实现。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.config.sub.PlanningConfig.PlanningAlgorithm;
import net.shiroha233.roadweaver.planning.impl.DelaunayPlanner;
import net.shiroha233.roadweaver.planning.impl.KNNPlanner;
import net.shiroha233.roadweaver.planning.impl.MSTPlanner;
import net.shiroha233.roadweaver.planning.impl.RNGPlanner;
import net.shiroha233.roadweaver.planning.impl.SnapBranchPlanner;
import net.shiroha233.roadweaver.planning.impl.AlphaModelPlanner;
import net.shiroha233.roadweaver.planning.impl.PipelinePlanner;

import net.minecraft.server.level.ServerLevel;

/**
 * 路网规划器工厂，根据算法枚举返回对应实现
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
        return switch (algorithm) {
            case KNN -> new KNNPlanner();
            case DELAUNAY -> new DelaunayPlanner();
            case RNG -> new RNGPlanner();
            case MST -> new MSTPlanner();
            case SNAP_BRANCH -> new SnapBranchPlanner();
            case ALPHA_MODEL -> new AlphaModelPlanner();
            case PIPELINE -> new PipelinePlanner(level);
        };
    }
}
