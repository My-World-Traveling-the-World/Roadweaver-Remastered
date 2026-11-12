package net.shiroha233.roadweaver.api.incoming;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.RoadWeaverBackend;

/**
 * 规划就绪查询与协调。chunky-autogen 用它在「道路还没规划完」时排队区块任务。
 *
 * <p>原先 chunky-autogen 用 {@code Class.forName} + 反射调
 * {@code planning.RoadPlanningReadinessIndex} 上的十几个 {@code public static} 方法。
 * 那是<b>事实上的 API，却没有任何文档和测试保护</b>：RoadWeaver 改个方法名，
 * chunky 只会静默退回「视为就绪」。</p>
 *
 * <p>本门面把返回值从裸 {@code String} 换成 {@link ReadinessState} 枚举 ——
 * 拼错枚举常量编译期就报错。</p>
 *
 * <p>RoadWeaver 未安装时全部返回 {@link ReadinessState#UNKNOWN}，调用方应按「不阻塞」处理。</p>
 */
public final class PlanningReadinessApi {

    /** 一个规划区域的边长（区块）。 */
    public static final int REGION_CHUNKS = 32;

    private PlanningReadinessApi() {
    }

    /** 请求规划某区域。 */
    public static ReadinessState requestRegion(ServerLevel level, int regionX, int regionZ) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return ReadinessState.UNKNOWN;
        }
        try {
            ReadinessState s = b.requestRegion(level, regionX, regionZ);
            return s == null ? ReadinessState.UNKNOWN : s;
        } catch (RuntimeException | LinkageError e) {
            return ReadinessState.UNKNOWN;
        }
    }

    /** 查询某区域当前状态（不触发请求）。 */
    public static ReadinessState regionState(ServerLevel level, int regionX, int regionZ) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return ReadinessState.UNKNOWN;
        }
        try {
            ReadinessState s = b.regionState(level, regionX, regionZ);
            return s == null ? ReadinessState.UNKNOWN : s;
        } catch (RuntimeException | LinkageError e) {
            return ReadinessState.UNKNOWN;
        }
    }

    /** 人类可读的进度摘要（日志 / 界面用）。 */
    public static String progressSummary(ServerLevel level) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return "roadweaver unavailable";
        }
        try {
            String s = b.progressSummary(level);
            return s == null ? "" : s;
        } catch (RuntimeException | LinkageError e) {
            return "roadweaver error: " + e.getClass().getSimpleName();
        }
    }

    /** 告诉 RoadWeaver：Chunky 预检阶段开始 / 结束，请让路 / 恢复。 */
    public static void setChunkyPreflightActive(boolean active) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.setChunkyPreflightActive(active);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 告诉 RoadWeaver：Chunky 独占阶段开始 / 结束，请让路 / 恢复。 */
    public static void setChunkyExclusiveActive(boolean active) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.setChunkyExclusiveActive(active);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 标记某区域已派发（供进度统计用）。 */
    public static void markRegionDispatched(ServerLevel level, int regionX, int regionZ) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.markRegionDispatched(level, regionX, regionZ);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
