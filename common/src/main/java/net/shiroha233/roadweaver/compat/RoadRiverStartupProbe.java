/* 文件职责：按存档核对河流会话，防止其他世界的就绪通知提前放行道路。 */
package net.shiroha233.roadweaver.compat;

import java.util.function.BooleanSupplier;

/** 每次道路启动只查询本存档的河流会话。跨存档共享的 ready 广播不能代表当前世界。 */
public final class RoadRiverStartupProbe {
    private long session;
    private RoadRiverStartupProbe() {}

    public static BooleanSupplier forWorld(Object world, boolean installed) {
        if (!installed) return () -> true;
        var probe = new RoadRiverStartupProbe();
        // 将可选类首次链接延迟到门内；API 版本不匹配也由道路记录为前置失败。
        return () -> Contract.ready(world, probe);
    }

    private static final class Contract {
        static boolean ready(Object world, RoadRiverStartupProbe probe) {
            if (!dev.streamsreflowing.api.RiverPlanApi.isInstalled())
                throw new IllegalStateException("河流已加载但未注册计划接口");
            var status = dev.streamsreflowing.api.RiverPlanApi.startup(world);
            // 创建世界后可能先看到会话 0；真实编号一旦确定就不能切到别的会话继续。
            if (probe.session != 0 && probe.session != status.session())
                throw new IllegalStateException("河流启动会话已更换：" + probe.session + " -> " + status.session()
                        + " phase=" + status.phase() + " reason=" + status.reason());
            probe.session = status.session();
            return switch (status.phase()) {
                case COMPLETE, DISABLED -> true;
                case NOT_STARTED, BUILDING -> false;
                case FAILED, STOPPED -> throw new IllegalStateException("河流未就绪 phase=" + status.phase()
                        + " session=" + status.session() + " reason=" + status.reason());
            };
        }
    }
}
