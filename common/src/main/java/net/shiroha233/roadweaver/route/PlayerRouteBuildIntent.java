/* 文件职责：把手绘高度与桥隧意图交给同一套终态校验、存储和实际施工。 */
package net.shiroha233.roadweaver.route;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerRouteBuildIntent {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private PlayerRouteBuildIntent() {}

    public static java.util.function.UnaryOperator<RoadData> forConnection(ServerLevel level, StructureConnection conn) {
        var entry = RouteBuildRegistry.lookup(level, conn);
        if (entry == null) return java.util.function.UnaryOperator.identity();
        var route = forSegment(RouteBuildRegistry.getRoute(level, entry.routeId()), entry.controlPointIndex());
        return route == null ? java.util.function.UnaryOperator.identity() : generated -> apply(route, conn, generated);
    }

    private static RoadData apply(PlayerRoute route, StructureConnection conn, RoadData generated) {
        try {
            var centers = generated.roadSegmentList().stream().map(RoadSegmentPlacement::middlePos).toList();
            RoadData patched = RouteRoadDataPatcher.patch(generated, route, centers);
            if (patched == generated) {
                return generated;
            }

            // 优先只撤销出错站点附近的高度修改，让其他合法的桥隧仍可施工。
            var validation = RoadDataValidator.validateFinalRoad(patched);
            if (!validation.valid()) {
                RoadData partial = RouteRoadDataPatcher.revertRangeToPipeline(patched, generated, validation.index());
                if (partial != null && RoadDataValidator.validateFinalRoad(partial).valid()) {
                    LOGGER.info("【手绘覆盖局部不合法，已撤销对应站点改写】 " + "RoadWeaver player-route override partially reverted at idx={} "
                                    + "connection={}->{} route={} reason={}",
                            validation.index(), conn.from(), conn.to(), route.id(), validation);
                    return partial;
                }
                LOGGER.warn("【手绘覆盖产生无效道路，保留当前规划链结果】 " + "RoadWeaver player-route override produced invalid RoadData; "
                                + "falling back to pipeline result connection={}->{} route={} reason={}",
                        conn.from(), conn.to(), route.id(), validation);
                return generated;
            }

            LOGGER.info("【手绘路线覆盖已应用】 " + "RoadWeaver applied player-route overrides connection={}->{} route={} segments={}",
                    conn.from(), conn.to(), route.id(), centers.size());
            return patched;
        } catch (RuntimeException t) {
            // 覆盖失败不能让整条路建不出来 —— 退回管线结果并记日志
            LOGGER.warn("【手绘覆盖执行失败，保留当前规划链结果】 " + "RoadWeaver player-route override failed; keeping pipeline result connection={}->{}",
                    conn.from(), conn.to(), t);
            return generated;
        }
    }

    static PlayerRoute forSegment(PlayerRoute route, int index) {
        if (route == null || index < 0 || index >= route.segmentCount()) return null;
        // 一次施工只负责这两个控制点，折返或交叉时不能从整条线的第一段重新猜桥隧类型。
        return new PlayerRoute(route.id(), route.name(), route.points().subList(index, index + 2),
                route.status(), route.createdAtEpochMs(), route.updatedAtEpochMs());
    }
}
