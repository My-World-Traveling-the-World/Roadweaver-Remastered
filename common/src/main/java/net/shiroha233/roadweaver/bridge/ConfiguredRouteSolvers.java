/* 文件职责：把配置快照翻译为数学内核参数，保持度量层和弹性带不依赖配置服务。 */
package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;

/** 拓扑与逐路选线共用参数入口，防止菜单只接到其中一条生产链。 */
public final class ConfiguredRouteSolvers {
    private ConfiguredRouteSolvers() {}

    public static FinslerMetric metric(PathfindingCostConfig config, TerrainField terrain,
                                       ObstacleField obstacles, FinslerMetric.CostScale scale,
                                       FinslerMetric.EdgeBlock block) {
        // 接收已发布的任务快照，不在数学循环中重复读取全局可变配置。
        return new FinslerMetric(terrain, obstacles, config.finslerGradeWeight(),
                config.finslerObstacleWeight(), scale, block);
    }

    public static ElasticBandSolver smoother(PathfindingCostConfig config) {
        // 只开放几何偏好，迭代上限与数值精度继续由求解器负责。
        return new ElasticBandSolver(config.elasticBandStiffness(),
                ElasticBandSolver.DEFAULT_MAX_ITERATIONS, ElasticBandSolver.DEFAULT_TOLERANCE);
    }
}
