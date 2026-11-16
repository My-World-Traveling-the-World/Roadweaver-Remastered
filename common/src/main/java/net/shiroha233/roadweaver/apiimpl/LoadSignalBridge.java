/* 文件职责：跨模组加载信号桥的实现（ownerId 是其对外契约，改名会断掉外部集成）。 */
package net.shiroha233.roadweaver.apiimpl;

import net.shiroha233.roadweaver.api.outgoing.LoadKind;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.shiroha233.roadweaver.api.incoming.ChunkyRuntimeApi;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import net.shiroha233.roadweaver.api.outgoing.ChunkyRuntimeRegistry;
import net.shiroha233.roadweaver.api.outgoing.CruiseSignalsRegistry;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;
import net.shiroha233.roadweaver.api.outgoing.StreamsThreadControl;

/**
 * 跨 mod 负载信号的 API 适配层；旧身份查询与运行时多源聚合分别保留。
 *
 * <h2>它解决什么问题</h2>
 * <p>{@code RuntimeLoadPolicy} 曾有 9 处硬编码反射探测别的 mod（chunky / 超速飞行 / streams）。
 * 那些探测是「静默失效」的重灾区：对方改个方法名，道路就退回默认线程数，
 * 表现为「超速飞行时道路还是很卡」这种无从排查的现象。</p>
 *
 * <h2>当前消费边界</h2>
 * <p>运行时通过 {@link RuntimeSignals} 合并全部来源的预算与保护；无建议使用道路默认值。
 * 旧按身份查询仍返回可空值，供 Chunky 所有权和既有调用保持精确匹配。
 * 道路策略已不再反射兜底，不能把注册缺失误记为另一条集成路径仍在工作。</p>
 *
 * <h2>ownerId 约定</h2>
 * <p>下面两个旧身份保留兼容；其他 owner 同样能参与运行时预算与显式保护：</p>
 * <ul>
 *   <li>{@code "chunky-autogen"}</li>
 *   <li>{@code "hyperspeed"}</li>
 * </ul>
 */
public final class LoadSignalBridge {

    /** chunky-autogen 的注册 id。 */
    public static final String OWNER_CHUNKY = "chunky-autogen";
    /** 超速飞行的注册 id。 */
    public static final String OWNER_HYPERSPEED = "hyperspeed";

    private LoadSignalBridge() {
    }

    // ── 旧身份查询保持独立，避免普通 busy 信号冒充 Chunky 所有权 ──────────

    /**
     * 布尔型信号。
     *
     * @return {@code null} 表示无 provider，调用方使用无建议默认值
     */
    public static Boolean booleanSignal(String className, String methodName) {
        if (className == null || methodName == null) {
            return null;
        }
        RoadLoadSignals provider = providerFor(className);
        if (provider == null) {
            return null;
        }
        try {
            return switch (methodName) {
                case "hasActiveTasks" -> provider.busy();
                case "shouldPauseAuxiliaryWorldgen" -> provider.shouldPauseAuxiliaryWorldgen();
                case "shouldPauseRoadCoordinator" -> provider.shouldPauseRoadCoordinator();
                case "isInteractionActive" -> provider.foregroundInteraction();
                case "isForegroundActivityActive" -> provider.foregroundActivity();
                case "isCpuProtectionActive" -> provider.cpuProtection();
                case "active", "teleportAdmissionPauseActive" -> provider.busy();
                default -> null;
            };
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 三参整型信号：{@code recommendedRoadPlanningLanes(pending, readyAhead, currentLanes)}。
     * {@code null} 表示使用无建议默认值。
     */
    public static Integer intSignal(String className, String methodName,
                                    int pendingRegions, int readyAheadRegions, int currentLanes) {
        RoadLoadSignals provider = providerFor(className);
        if (provider == null) {
            return null;
        }
        try {
            if (!"recommendedRoadPlanningLanes".equals(methodName)) {
                return null;
            }
            int v = provider.recommendedRoadPlanningLanes(pendingRegions, readyAheadRegions, currentLanes);
            // -1 表示 provider 明确「无建议」→ 也使用无建议默认值，保持负值语义
            return v < 0 ? null : v;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 单参整型信号：{@code recommendedRoadWorkerThreads(currentThreads)}。{@code null} 表示使用无建议默认值。 */
    public static Integer intOneArgSignal(String className, String methodName, int currentThreads) {
        RoadLoadSignals provider = providerFor(className);
        if (provider == null) {
            return null;
        }
        try {
            if (!"recommendedRoadWorkerThreads".equals(methodName)) {
                return null;
            }
            int v = provider.recommendedRoadWorkerThreads(currentThreads);
            return v < 0 ? null : v;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 命令型信号：写 streams 的静态方法。
     *
     * @return {@code true} 表示已由 API 处理；{@code false} 表示调用方使用无建议默认值
     */
    public static boolean voidIntSignal(String className, String methodName, int arg) {
        StreamsThreadControl control = RoadLoadSignalsRegistry.streamsThreadControl();
        if (control == null) {
            return false;
        }
        try {
            if ("setChunkySupplyThreads".equals(methodName)) {
                control.setChunkySupplyThreads(arg);
                return true;
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 命令型信号：{@code setChunkyExclusive(boolean)}。 */
    public static boolean booleanOneArgSignal(String className, String methodName, boolean arg) {
        StreamsThreadControl control = RoadLoadSignalsRegistry.streamsThreadControl();
        if (control == null) {
            return false;
        }
        try {
            if ("setChunkyExclusive".equals(methodName)) {
                control.setChunkyExclusive(arg);
                return true;
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ── 诊断 ─────────────────────────────────────────────────────────────

    /**
     * 一行日志列出 API 注册情况；注册存在不等于外部模组已经采用所有信号。
     *
     * <p>接入一个 mod 之后看这行就知道有没有生效 —— 不用去猜。</p>
     */
    public static String describeResolution() {
        RoadLoadSnapshot snapshot = RoadLoadSignalsRegistry.snapshot();
        return "api-signals: load-signals=" + ownerIds(RoadLoadSignalsRegistry.sources())
                + " kind-busy=" + snapshot.highestBusyKind()
                + " streams-control=" + (RoadLoadSignalsRegistry.streamsThreadControl() != null)
                + " chunky-runtime=" + ChunkyRuntimeRegistry.providers()
                + " cruise-signals=" + CruiseSignalsRegistry.providers()
                + " great-wall-readiness=" + GreatWallReadinessRegistry.providers()
                + " river-ready=" + RiverReadinessApi.isReady()
                + " river-listeners=" + RiverReadinessApi.listenerCount();
    }

    /**
     * 注册表里各提供方的 id，按字母序排好。
     *
     * <p>排过序是为了日志与测试可复现 —— 注册表底层是 {@code ConcurrentHashMap}，
     * 遍历顺序不保证。这些 id 都是<b>运行时</b>从 provider 自己身上读的，
     * 本类不写死任何 mod 名，所以道路单独发布时这行照样成立。</p>
     */
    private static java.util.List<String> ownerIds(java.util.List<RoadLoadSignals> sources) {
        java.util.List<String> ids = new java.util.ArrayList<>(sources.size());
        for (RoadLoadSignals signals : sources) {
            ids.add(signals.ownerId());
        }
        ids.sort(null);
        return ids;
    }

    /** 指定类名对应的 provider。 */
    private static RoadLoadSignals providerFor(String className) {
        if (className == null) {
            return null;
        }
        String owner = null;
        if (className.startsWith("local.plaincraftchunkyautogen.")) {
            owner = OWNER_CHUNKY;
        } else if (className.startsWith("local.plaincraft.hyperspeed.")) {
            owner = OWNER_HYPERSPEED;
        }
        if (owner == null) {
            return null;
        }
        for (RoadLoadSignals s : RoadLoadSignalsRegistry.sources()) {
            if (owner.equals(s.ownerId())) {
                return s;
            }
        }
        return null;
    }

    /** 供诊断：某类别是否忙碌（走注册表）。 */
    public static boolean isBusy(LoadKind kind) {
        return RoadLoadSignalsRegistry.isBusy(kind);
    }

    /** 按信号语义聚合，避免新增提供方必须冒用 Chunky 或 Hyperspeed 的身份才能生效。 */
    public static final class RuntimeSignals {
        private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
        private static final AtomicLong NEXT_FAILURE_LOG = new AtomicLong();
        private static final long FAILURE_LOG_INTERVAL = TimeUnit.SECONDS.toNanos(5);

        public static final Flag AUXILIARY_PAUSE = () -> any(RoadLoadSignals::shouldPauseAuxiliaryWorldgen);
        public static final Flag COORDINATOR_PAUSE = () -> any(RoadLoadSignals::shouldPauseRoadCoordinator);
        public static final Flag INTERACTION = () -> any(RoadLoadSignals::foregroundInteraction);
        public static final Flag CPU_PROTECTION = () -> any(RoadLoadSignals::cpuProtection);

        private RuntimeSignals() {
        }

        /** 保留 invoke 调用形态，让既有预热分支顺序守卫仍能检查真实生产入口。 */
        @FunctionalInterface
        public interface Flag {
            boolean invoke();
        }

        public static int recommendedWorkers(int currentThreads) {
            // 诊断快照传的是 -1，不能拿它决定预算；提供方需要真实上下文才能自适应。
            return minimum(source -> source.recommendedThreadsFor(RoadLoadSignals.CONSUMER_ROAD, currentThreads));
        }

        /** 道数必须携带现场队列状态，诊断快照的未知参数不能用于派发。 */
        public static int recommendedLanes(int pendingRegions, int readyAheadRegions, int currentLanes) {
            return minimum(source -> source.recommendedRoadPlanningLanes(
                    pendingRegions, readyAheadRegions, currentLanes));
        }

        /** 旧调用键仅负责选择信号；预算和保护不再按两个固定 owner 过滤。 */
        public static boolean booleanSignal(String className, String methodName) {
            return switch (methodName) {
                case "shouldPauseAuxiliaryWorldgen" -> AUXILIARY_PAUSE.invoke();
                case "shouldPauseRoadCoordinator" -> COORDINATOR_PAUSE.invoke();
                case "isInteractionActive" -> INTERACTION.invoke();
                case "isCpuProtectionActive" -> CPU_PROTECTION.invoke();
                // Chunky 独占和泄漏看门狗必须读取真正的 Chunky 身份，不能套其他模组的 busy。
                default -> Boolean.TRUE.equals(LoadSignalBridge.booleanSignal(className, methodName));
            };
        }

        /** 保护请求按或合并，一个来源故障不能屏蔽其他来源的保护。 */
        private static boolean any(Predicate<RoadLoadSignals> query) {
            for (RoadLoadSignals source : RoadLoadSignalsRegistry.sources()) {
                try {
                    if (query.test(source)) return true;
                } catch (RuntimeException | LinkageError failure) {
                    reportFailure(source, failure);
                }
            }
            return false;
        }

        /** 多个调度器给的是资源上限，取最小有效建议才能同时遵守它们。 */
        private static int minimum(ToIntFunction<RoadLoadSignals> query) {
            int best = -1;
            for (RoadLoadSignals source : RoadLoadSignalsRegistry.sources()) {
                try {
                    int value = query.applyAsInt(source);
                    // 0 是有效的 lane 暂停建议，只有负值表示放弃投票，不能把两者合并。
                    if (value >= 0) best = best < 0 ? value : Math.min(best, value);
                } catch (RuntimeException | LinkageError failure) {
                    reportFailure(source, failure);
                }
            }
            return best;
        }

        private static void reportFailure(RoadLoadSignals source, Throwable failure) {
            // 查询出错与正常无建议必须可区分；全局限频避免坏提供方在热循环里刷屏。
            long now = System.nanoTime();
            long next = NEXT_FAILURE_LOG.get();
            if (now >= next && NEXT_FAILURE_LOG.compareAndSet(next, now + FAILURE_LOG_INTERVAL)) {
                LOGGER.warn("RoadWeaver load signal query failed: provider={} failure={}",
                        source.getClass().getName(), failure.toString());
            }
        }
    }
}
