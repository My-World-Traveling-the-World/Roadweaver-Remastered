/* 文件职责：把长城 API 就绪状态接入道路开局预热门。 */
package net.shiroha233.roadweaver.compat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.incoming.GreatWallReadinessApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 河流模组广播就绪后长城自行预热，道路在这里等待长城发布或退出。
 * 门本身不阻塞；开局协调器选择等待返回的 Future，普通 tick 不调用阻塞等待。
 */
public final class GreatWallPrewarmCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/GreatWallGate");
    /** 非正值不设调用方期限，仍沿用停滞与绝对期限，避免无限卡住加载。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 0L;
    private static final long POLL_MILLIS = 250L;
    // 长城在 request 之前可能短暂返回 ready，已安装时仍保留五秒宽限。
    private static final long FIRST_READY_GRACE_MILLIS = 5_000L;
    private static final long STALL_WINDOW_MILLIS =
            Long.getLong("roadweaver.greatwallStallWindowMillis", 600_000L);
    private static final long ABSOLUTE_CAP_MILLIS =
            Long.getLong("roadweaver.greatwallAbsoluteCapMillis", 1_800_000L);
    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "roadweaver-greatwall-gate");
        thread.setDaemon(true);
        return thread;
    });

    private GreatWallPrewarmCompat() {}

    /** 安装但未注册也必须等，不能把提供方接线故障当成预热成功。 */
    public static boolean wallPresent() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("plaincraft_great_wall");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAvailable() {
        return GreatWallReadinessApi.isLinked();
    }

    /** API 内部故障返回未就绪，保留既有 fail-closed 契约。 */
    public static boolean isPrewarmComplete() {
        return isAvailable() ? GreatWallReadinessApi.isReady() : !wallPresent();
    }

    /** 没装长城直接通过，已安装仍保留原先的宽限和退出策略。 */
    public static CompletableFuture<Void> awaitPrewarmAsync(long timeoutMillis) {
        return awaitPrewarmAsync(timeoutMillis, PROBE, (task, delay) -> {
            POLLER.schedule(task, delay, TimeUnit.MILLISECONDS);
            return true;
        }, System::nanoTime, GreatWallPrewarmCompat::logResolution);
    }

    private static final PrewarmGateWork.Probe PROBE = new PrewarmGateWork.Probe() {
        @Override public boolean required() { return wallPresent() || isAvailable(); }
        @Override public boolean ready() { return isPrewarmComplete(); }
        @Override public String signature() { return GreatWallReadinessApi.progressSignature(); }
    };

    // 测试只替换外部状态、时间和调度边界，生产决策和默认限制不另造一套。
    static CompletableFuture<Void> awaitPrewarmAsync(long timeoutMillis, PrewarmGateWork.Probe probe,
            PrewarmGateWork.Scheduler scheduler, java.util.function.LongSupplier clock,
            java.util.function.Consumer<PrewarmGateWork.Resolution> resolved) {
        if (probe.required()) {
            LOGGER.info("RoadWeaver initial generation is waiting for the Great Wall prewarm (timeout {} ms).", timeoutMillis);
        }
        return PrewarmGateWork.await(probe, scheduler, clock, resolved,
                new PrewarmGateWork.Limits(POLL_MILLIS, FIRST_READY_GRACE_MILLIS,
                        STALL_WINDOW_MILLIS, ABSOLUTE_CAP_MILLIS), timeoutMillis);
    }

    private static void logResolution(PrewarmGateWork.Resolution reason) {
        switch (reason) {
            case ABSENT -> { /* 没有长城就没有等待阶段，不打印虚假的完成日志。 */ }
            case READY -> LOGGER.info("Great Wall prewarm gate resolved; RoadWeaver initial generation may start.");
            case TIMEOUT, STALLED, ABSOLUTE_CAP, POLLER_UNAVAILABLE -> LOGGER.warn(
                    "Great Wall prewarm gate released without confirmed readiness: reason={}; RoadWeaver initial generation may start.", reason);
        }
    }

    public static CompletableFuture<Void> awaitPrewarmAsync() {
        return awaitPrewarmAsync(DEFAULT_TIMEOUT_MILLIS);
    }

    /** 长城仅影响主世界，其他维度仍直接通过。 */
    public static CompletableFuture<Void> awaitPrewarmForInitialGeneration(ServerLevel level) {
        if (level == null || !net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) {
            return CompletableFuture.completedFuture(null);
        }
        return awaitPrewarmAsync();
    }
}
