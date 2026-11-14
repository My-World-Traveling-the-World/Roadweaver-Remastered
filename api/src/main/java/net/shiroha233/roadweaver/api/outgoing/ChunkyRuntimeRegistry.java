/* 文件职责：chunky 契约的注册表：按 ownerId 注册/注销，重名直接拒绝。 */
package net.shiroha233.roadweaver.api.outgoing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ChunkyRuntime} 的全局注册表。chunky-autogen 在自己的 {@code onInitialize} 里注册。
 *
 * <p>注册表放在 api 模块内、不经过 RoadWeaver 后端，所以不存在「后端尚未注入」这个中间态。</p>
 */
public final class ChunkyRuntimeRegistry {

    private static final Map<String, ChunkyRuntime> PROVIDERS = new ConcurrentHashMap<>();

    private ChunkyRuntimeRegistry() {
    }

    /** 注册。重复注册同一 {@code ownerId()} 会覆盖旧的。 */
    public static void register(ChunkyRuntime runtime) {
        if (runtime == null) return;
        String id = runtime.ownerId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ChunkyRuntime.ownerId() must not be blank");
        }
        PROVIDERS.put(id, runtime);
    }

    /** 注销。 */
    public static void unregister(String ownerId) {
        if (ownerId != null) PROVIDERS.remove(ownerId);
    }

    /** 是否有提供方注册。 */
    public static boolean isRegistered() {
        return !PROVIDERS.isEmpty();
    }

    /** 取一个提供方；没有则 {@code null}。 */
    public static ChunkyRuntime get() {
        if (PROVIDERS.isEmpty()) return null;
        return PROVIDERS.values().iterator().next();
    }

    /** 已注册的 id 列表。 */
    public static List<String> providers() {
        return List.copyOf(PROVIDERS.keySet());
    }
}
