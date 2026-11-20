/* 文件职责：通过河流公开契约读取不可变计划与板块，不再反射其内部数据类。 */
package net.shiroha233.roadweaver.compat;

import dev.streamsreflowing.api.RiverPlanApi;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** 仅检测到公开契约后加载；读计划不再反射ColumnPlan/Lake/PlateKey内部类。 */
final class StreamsPlanContractAccess implements StreamsPlanAccess {
    @Override public Object open(ServerLevel level) {
        return openHandle(level);
    }

    static RiverPlanApi.Session openHandle(Object world) {
        var opened = RiverPlanApi.open(world);
        if (opened.availability() != RiverPlanApi.Availability.AVAILABLE)
            throw new IllegalStateException("河流计划接口" + opened.availability() + ": " + opened.reason());
        return opened.session();
    }

    @Override public Object identity(Object session) { return ((RiverPlanApi.Session) session).identity(); }
    @Override public StreamsReflowingCompat.RiverColumn column(Object session, int x, int z) throws Exception {
        var plan = ((RiverPlanApi.Session) session).column(x, z);
        if (!plan.inChannel() && !plan.inCorridor()) return StreamsReflowingCompat.RiverColumn.NONE;
        return new StreamsReflowingCompat.RiverColumn(plan.inChannel(), plan.inCorridor(),
                plan.waterSurfaceY(), plan.bedY(), plan.distance(), plan.width());
    }
    @Override public StreamsReflowingCompat.LakeColumn lake(Object session, int x, int z) throws Exception {
        var lake = ((RiverPlanApi.Session) session).lake(x, z);
        return lake.present() ? new StreamsReflowingCompat.LakeColumn(true, lake.surfaceY(), lake.bedY())
                : StreamsReflowingCompat.LakeColumn.NONE;
    }
    @Override public List<?> platesNear(Object session, double x, double z, double radius) throws Exception {
        return ((RiverPlanApi.Session) session).platesNear(x, z, radius);
    }
    @Override public void prewarm(Object session, Object plate) throws Exception {
        ((RiverPlanApi.Session) session).prewarm((RiverPlanApi.Plate) plate);
    }
}
