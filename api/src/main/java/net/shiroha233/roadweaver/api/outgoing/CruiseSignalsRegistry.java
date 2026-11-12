package net.shiroha233.roadweaver.api.outgoing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CruiseSignals} 的全局注册表。超速飞行在自己的客户端初始化里注册。
 *
 * <p>与 {@code RoadLoadSignals} 同一个套路：注册表在 api 模块内，不经过 RoadWeaver 后端，
 * 所以不存在「后端尚未注入」这个状态。</p>
 *
 * <p>为空时全部查询返回 {@code false} / {@code -1} / {@code null} 这类「没意见」的值，
 * <b>任何情况下都不抛异常</b>。</p>
 */
public final class CruiseSignalsRegistry {

    private static final Map<String, CruiseSignals> PROVIDERS = new ConcurrentHashMap<>();

    private CruiseSignalsRegistry() {
    }

    /** 注册。重复注册同一 {@code ownerId()} 会覆盖旧的。 */
    public static void register(CruiseSignals signals) {
        if (signals == null) return;
        String id = signals.ownerId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("CruiseSignals.ownerId() must not be blank");
        }
        PROVIDERS.put(id, signals);
    }

    /** 注销。 */
    public static void unregister(String ownerId) {
        if (ownerId != null) PROVIDERS.remove(ownerId);
    }

    /** 是否有提供方注册。 */
    public static boolean isRegistered() {
        return !PROVIDERS.isEmpty();
    }

    /** 取一个提供方（正常只有一个）；没有则 {@code null}。 */
    public static CruiseSignals get() {
        if (PROVIDERS.isEmpty()) return null;
        return PROVIDERS.values().iterator().next();
    }

    /** 已注册的 id 列表，供日志与诊断。 */
    public static List<String> providers() {
        return List.copyOf(PROVIDERS.keySet());
    }
}
