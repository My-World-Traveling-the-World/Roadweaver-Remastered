/* 文件职责：全仓配置元数据的总注册表 —— UI 与校验的唯一入口。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 配置元数据总表。
 *
 * <h2>用途</h2>
 * <ol>
 *   <li><b>UI</b>：配置页按 {@link ConfigCategory} 分组、按 {@link ConfigTier} 分级，
 *       从本表直接生成，不再手写控件。</li>
 *   <li><b>校验</b>：{@link ConfigKey#min()} / {@link ConfigKey#max()} 与各配置类的
 *       {@code sanitize()} 使用的是同一批常量。</li>
 *   <li><b>文档</b>：中英名称与说明集中在这里，不再散落于 UI 代码。</li>
 * </ol>
 *
 * <h2>渐进接入</h2>
 * <p>本仓共 243 个键。目前只有 {@link BridgeKeys}（17 个）接入，其余 226 个
 * 仍由旧的手写代码管理。{@code ConfigKeyTableConsistencyTest} 会在两边<b>双向</b>校验：
 * 已接入的配置类必须与表完全一致；未接入的暂时跳过。</p>
 *
 * @see BridgeKeys
 */
public final class ConfigKeyTable {

    private ConfigKeyTable() {}

    /** 已接入元数据的配置类 → 它对应的键集合。按这个顺序在 UI 上分组。 */
    private static final Map<String, List<ConfigKey>> BY_CONFIG = new LinkedHashMap<>();

    static {
        BY_CONFIG.put("bridge", BridgeKeys.ALL);
    }

    /** 全部已接入的键（不可变）。 */
    public static List<ConfigKey> all() {
        return BY_CONFIG.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /** 某个配置类的键（类名用 ModConfig 里的字段名，如 {@code bridge}）。 */
    public static List<ConfigKey> forConfig(String configField) {
        return BY_CONFIG.getOrDefault(configField, List.of());
    }

    /** 按 JSON 键（= Java 字段名）查元数据。 */
    public static Optional<ConfigKey> byJsonKey(String jsonKey) {
        for (List<ConfigKey> keys : BY_CONFIG.values()) {
            for (ConfigKey key : keys) {
                if (key.jsonKey().equals(jsonKey)) return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** 已接入的配置类字段名集合（测试用）。 */
    public static List<String> registeredConfigs() {
        return List.copyOf(BY_CONFIG.keySet());
    }

    /** 按分类分组（UI 用）。 */
    public static Map<ConfigCategory, List<ConfigKey>> byCategory() {
        Map<ConfigCategory, List<ConfigKey>> grouped = new LinkedHashMap<>();
        for (ConfigCategory cat : ConfigCategory.values()) {
            List<ConfigKey> keys = all().stream()
                    .filter(k -> k.category() == cat)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!keys.isEmpty()) {
                grouped.put(cat, Collections.unmodifiableList(keys));
            }
        }
        return grouped;
    }

    /** 统计：某接线状态的键有多少个（供台账与 UI 顶部提示用）。 */
    public static long countByStatus(ConfigStatus status) {
        return all().stream().filter(k -> k.status() == status).count();
    }
}
