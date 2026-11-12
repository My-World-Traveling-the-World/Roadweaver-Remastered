package net.shiroha233.roadweaver.apiimpl;

import net.shiroha233.roadweaver.api.outgoing.LoadKind;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSnapshot;
import net.shiroha233.roadweaver.api.incoming.ChunkyRuntimeApi;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import net.shiroha233.roadweaver.api.outgoing.ChunkyRuntimeRegistry;
import net.shiroha233.roadweaver.api.outgoing.CruiseSignalsRegistry;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;
import net.shiroha233.roadweaver.api.outgoing.StreamsThreadControl;

/**
 * 跨 mod 负载信号的<b>注册表优先</b>解析层。
 *
 * <h2>它解决什么问题</h2>
 * <p>{@code RuntimeLoadPolicy} 里有 9 处硬编码反射探测别的 mod（chunky / 超速飞行 / streams）。
 * 那些探测是「静默失效」的重灾区：对方改个方法名，道路就退回默认线程数，
 * 表现为「超速飞行时道路还是很卡」这种无从排查的现象。</p>
 *
 * <h2>迁移策略：注册表优先，反射兜底</h2>
 * <p>每个信号先问 {@link RoadLoadSignalsRegistry}：</p>
 * <ul>
 *   <li><b>有 provider 认领</b> → 用 provider 的值（标准 API，编译期受约束）；</li>
 *   <li><b>没有 provider</b> → 返回 {@code null}，调用方回退到原来的反射（行为与改造前逐位一致）。</li>
 * </ul>
 * <p>这样每个提供方 mod 都可以<b>独立迁移、独立验证</b>，不需要一次改完 6 个仓库；
 * 等全部迁完后，把 {@code RuntimeLoadPolicy} 里的反射路径删掉即可。</p>
 *
 * <h2>ownerId 约定</h2>
 * <p>提供方注册时必须用下列 id，否则本桥认不出来（会一直走反射兜底）：</p>
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

    // ── 供 RuntimeLoadPolicy 的反射包装器调用（按 类名#方法名 分派）──────────

    /**
     * 布尔型信号。
     *
     * @return {@code null} 表示无 provider，调用方回退反射
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
     * {@code null} 表示回退反射。
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
            // -1 表示 provider 明确「无建议」→ 也回退反射，保持与改造前一致
            return v < 0 ? null : v;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 单参整型信号：{@code recommendedRoadWorkerThreads(currentThreads)}。{@code null} 表示回退反射。 */
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
     * @return {@code true} 表示已由 API 处理；{@code false} 表示调用方回退反射
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
     * 一行日志，说明当前哪些信号走 API、哪些还在走反射。
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
}
