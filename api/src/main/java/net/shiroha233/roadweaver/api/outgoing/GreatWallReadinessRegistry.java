/* 文件职责：长城就绪契约的注册表：按 ownerId 注册/注销，重名直接拒绝。 */
package net.shiroha233.roadweaver.api.outgoing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GreatWallReadiness} 的全局注册表。长城在自己的初始化里注册。
 *
 * <p>注册表放在 api 模块内、<b>不经过 RoadWeaver 的 {@code RoadWeaverBackend}</b>：
 * api 模块由 {@code depends} 保证先于所有消费方加载，所以注册表一定先于任何
 * <code>register</code> 调用存在，不存在「后端还没注入」这个状态。</p>
 *
 * <p>为空时全部查询返回「不在/未就绪」，<b>任何情况下都不抛异常</b> ——
 * 一个 mod 的探针坏掉不该让别人崩。</p>
 */
public final class GreatWallReadinessRegistry {

    private static final Map<String, GreatWallReadiness> PROVIDERS = new ConcurrentHashMap<>();

    private GreatWallReadinessRegistry() {
    }

    /** 注册。重复注册同一 {@code ownerId()} 会覆盖旧的。 */
    public static void register(GreatWallReadiness provider) {
        if (provider == null) return;
        String id = provider.ownerId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("GreatWallReadiness.ownerId() must not be blank");
        }
        PROVIDERS.put(id, provider);
    }

    /** 注销。 */
    public static void unregister(String ownerId) {
        if (ownerId != null) PROVIDERS.remove(ownerId);
    }

    /** 是否有提供方注册。 */
    public static boolean isRegistered() {
        return !PROVIDERS.isEmpty();
    }

    /** 取一个提供方；多个时取第一个（正常情况下只有一个）。没有则 {@code null}。 */
    public static GreatWallReadiness get() {
        if (PROVIDERS.isEmpty()) return null;
        return PROVIDERS.values().iterator().next();
    }

    /** 已注册的 id 列表，供日志与诊断。 */
    public static java.util.List<String> providers() {
        return java.util.List.copyOf(PROVIDERS.keySet());
    }
}
