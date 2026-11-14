/* 文件职责：负载信号契约的注册表：按 ownerId 注册/注销，并对「多源负载」做聚合。 */
package net.shiroha233.roadweaver.api.outgoing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RoadLoadSignals} 的全局注册表。
 *
 * <p>提供方在自己的初始化里注册；RoadWeaver 与 c2me QoS 每次决策前查表。</p>
 *
 * <h2>降级语义</h2>
 * <p>表为空时 {@link #snapshot()} 返回 {@link RoadLoadSnapshot#EMPTY}，
 * 全部查询返回「无建议」。<b>任何情况下都不抛异常</b> —— 一个 mod 的负载查询失败
 * 不应该让另一个 mod 的道路生成或区块预生成崩掉。</p>
 */
public final class RoadLoadSignalsRegistry {

    private static final Map<String, RoadLoadSignals> PROVIDERS = new ConcurrentHashMap<>();

    /** 命令类通道：streams 的采样线程控制。由 streams 实现并注册。 */
    private static volatile StreamsThreadControl streamsThreadControl;

    private RoadLoadSignalsRegistry() {
    }

    /**
     * 注册一个负载信号源。重复注册同一 {@code ownerId()} 会覆盖旧的。
     *
     * @throws IllegalArgumentException {@code ownerId()} 为空时
     */
    public static void register(RoadLoadSignals signals) {
        if (signals == null) {
            return;
        }
        String id = signals.ownerId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RoadLoadSignals.ownerId() must not be blank");
        }
        PROVIDERS.put(id, signals);
    }

    /** 注销。 */
    public static void unregister(String ownerId) {
        if (ownerId != null) {
            PROVIDERS.remove(ownerId);
        }
    }

    /** 已注册的信号源（只读快照）。 */
    public static List<RoadLoadSignals> sources() {
        return List.copyOf(PROVIDERS.values());
    }

    /**
     * 聚合全部信号源。
     *
     * <p><b>注意</b>：聚合时没有调用现场的上下文，所以两个「推荐值」按
     * {@code -1}（表示未知）透传给提供方。提供方看到 {@code -1} 应返回 {@code -1}；
     * 真正的决策走 {@code LoadSignalBridge} 那条带上下文的路径，
     * 这里只是给诊断用的近似值。</p>
     *
     *
     * <p>单个源抛异常时<b>跳过该源</b>并继续，不中断聚合 ——
     * 一个 mod 的 bug 不该拖垮别人的线程决策。</p>
     */
    public static RoadLoadSnapshot snapshot() {
        List<RoadLoadSignals> all = sources();
        if (all.isEmpty()) {
            return RoadLoadSnapshot.EMPTY;
        }
        boolean busy = false;
        LoadKind highest = null;
        int workers = -1;
        int lanes = -1;
        boolean pauseCoordinator = false;
        boolean pauseAux = false;
        boolean foreground = false;
        boolean cpuProtect = false;
        List<String> names = new ArrayList<>(all.size());
        for (RoadLoadSignals s : all) {
            try {
                names.add(s.ownerId());
                if (s.busy()) {
                    busy = true;
                    LoadKind k = s.kind();
                    if (k != null && (highest == null || k.priority() > highest.priority())) {
                        highest = k;
                    }
                }
                workers = minPositive(workers, s.recommendedRoadWorkerThreads(-1));
                lanes = minPositive(lanes, s.recommendedRoadPlanningLanes(-1, -1, -1));
                pauseCoordinator |= s.shouldPauseRoadCoordinator();
                pauseAux |= s.shouldPauseAuxiliaryWorldgen();
                foreground |= s.foregroundInteraction();
                cpuProtect |= s.cpuProtection();
            } catch (RuntimeException | LinkageError ignored) {
                // 单个源失效不影响整体
            }
        }
        return new RoadLoadSnapshot(busy, highest, workers, lanes,
                pauseCoordinator, pauseAux, foreground, cpuProtect, List.copyOf(names));
    }

    /** 指定类别的源当前是否忙碌。 */
    public static boolean isBusy(LoadKind kind) {
        if (kind == null) {
            return false;
        }
        for (RoadLoadSignals s : sources()) {
            try {
                if (s.kind() == kind && s.busy()) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return false;
    }

    /** 一行诊断，形如 {@code chunky-autogen[PREGENERATION,busy,threads=6] hyperspeed[CRUISE,cpu-protect]}。 */
    public static String describeAll() {
        List<RoadLoadSignals> all = sources();
        if (all.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (RoadLoadSignals s : all) {
            try {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.ownerId()).append('[').append(s.kind());
                if (s.busy()) {
                    sb.append(",busy");
                }
                int w = s.recommendedRoadWorkerThreads(-1);
                if (w >= 0) {
                    sb.append(",threads=").append(w);
                }
                int l = s.recommendedRoadPlanningLanes(-1, -1, -1);
                if (l >= 0) {
                    sb.append(",lanes=").append(l);
                }
                if (s.cpuProtection()) {
                    sb.append(",cpu-protect");
                }
                sb.append(']');
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return sb.toString();
    }

    // ── streams 命令通道 ───────────────────────────────────────────────

    /** 由 streams 实现并注册。 */
    public static void registerStreamsThreadControl(StreamsThreadControl control) {
        streamsThreadControl = control;
    }

    /** 当前 streams 控制通道，可能为 {@code null}。 */
    public static StreamsThreadControl streamsThreadControl() {
        return streamsThreadControl;
    }

    private static int minPositive(int current, int candidate) {
        if (candidate < 0) {
            return current;
        }
        return current < 0 ? candidate : Math.min(current, candidate);
    }
}
