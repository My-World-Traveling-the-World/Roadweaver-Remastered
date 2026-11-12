/* 文件职责：强制配置元数据表与配置类字段双向一致，防止两边漂移。 */
package net.shiroha233.roadweaver.config.meta;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置元数据表 ↔ 配置类的双向一致性守卫。
 *
 * <h2>为什么必须有</h2>
 * <p>元数据表一旦和配置类漂移，UI 就会显示错误的范围、错误的默认值，
 * 甚至给一个不存在的键生成控件。这种漂移<b>不会崩</b>，只会静默地骗玩家，
 * 所以必须由测试钉死。</p>
 *
 * <h2>三个方向都查</h2>
 * <ol>
 *   <li><b>表 → 类</b>：表里每个键，配置类必须有同名字段</li>
 *   <li><b>类 → 表</b>：配置类每个字段，表里必须有条目（键数必须相等）</li>
 *   <li><b>值一致</b>：类型、默认值必须逐项相等</li>
 * </ol>
 *
 * <p>已经接入的配置类会检查；尚未接入（{@link ConfigKeyTable#forConfig} 返回空）
 * 的直接跳过，等接入时自动开始生效。</p>
 */
class ConfigKeyTableConsistencyTest {

    /** 已接入的配置类：ModConfig 字段名 → 全限定类名。 */
    private static final Map<String, String> CONFIG_CLASSES = new LinkedHashMap<>();

    static {
        CONFIG_CLASSES.put("planning",
                "net.shiroha233.roadweaver.config.sub.PlanningConfig");
        CONFIG_CLASSES.put("bridge", "net.shiroha233.roadweaver.config.sub.BridgeConfig");
    }

    @Test
    void 表里每个键都必须在配置类里有字段() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : CONFIG_CLASSES.entrySet()) {
            List<ConfigKey> keys = ConfigKeyTable.forConfig(e.getKey());
            if (keys.isEmpty()) continue;              // 尚未接入，跳过
            Class<?> cls = Class.forName(e.getValue());
            for (ConfigKey key : keys) {
                Field f = findField(cls, key.jsonKey());
                if (f == null) {
                    problems.add(e.getValue() + " 缺字段 " + key.jsonKey());
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "元数据表里的键在配置类里找不到对应字段（表和类漂移了）：\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    void 配置类每个字段都必须在表里有条目() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : CONFIG_CLASSES.entrySet()) {
            List<ConfigKey> keys = ConfigKeyTable.forConfig(e.getKey());
            if (keys.isEmpty()) continue;
            Class<?> cls = Class.forName(e.getValue());
            List<Field> fields = instanceFields(cls);
            assertEquals(fields.size(), keys.size(),
                    e.getValue() + " 有 " + fields.size() + " 个字段，但元数据表只有 "
                            + keys.size() + " 条。每加一个配置字段必须同时加一条元数据。");
            for (Field f : fields) {
                if (ConfigKeyTable.byJsonKey(f.getName()).isEmpty()) {
                    problems.add(e.getValue() + "." + f.getName() + " 没有元数据条目");
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "配置类字段缺元数据（新增配置必须先填表）：\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    void 类型与默认值必须逐项相等() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : CONFIG_CLASSES.entrySet()) {
            List<ConfigKey> keys = ConfigKeyTable.forConfig(e.getKey());
            if (keys.isEmpty()) continue;
            Class<?> cls = Class.forName(e.getValue());
            Object sample = cls.getDeclaredConstructor().newInstance();
            for (ConfigKey key : keys) {
                Field f = findField(cls, key.jsonKey());
                if (f == null) continue;
                f.setAccessible(true);
                Object actual = f.get(sample);

                ConfigType expectedType = typeOf(f.getType());
                if (expectedType != key.type()) {
                    problems.add(key.jsonKey() + "：表声明 " + key.type()
                            + "，字段实际是 " + f.getType().getSimpleName());
                    continue;
                }
                if (!valuesEqual(actual, key.defaultValue())) {
                    problems.add(key.jsonKey() + "：表默认值 " + key.defaultValue()
                            + "，字段实际 " + actual);
                }
                if (key.min() != null && key.max() != null
                        && key.min().doubleValue() > key.max().doubleValue()) {
                    problems.add(key.jsonKey() + "：min(" + key.min() + ") > max(" + key.max() + ")");
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "元数据与配置类的类型/默认值不一致：\n  " + String.join("\n  ", problems));
    }

    @Test
    void 键名不得重复() {
        List<String> seen = new ArrayList<>();
        List<String> dup = new ArrayList<>();
        for (ConfigKey key : ConfigKeyTable.all()) {
            if (seen.contains(key.jsonKey())) dup.add(key.jsonKey());
            seen.add(key.jsonKey());
        }
        assertTrue(dup.isEmpty(), "元数据表里键名重复: " + String.join(", ", dup));
    }

    @Test
    void 已废弃的键必须写明原因() {
        List<String> problems = new ArrayList<>();
        for (ConfigKey key : ConfigKeyTable.all()) {
            if (key.status() == ConfigStatus.DEPRECATED && key.noteZh().isBlank()) {
                problems.add(key.jsonKey() + "：标了 DEPRECATED 但没写废弃原因");
            }
            if (key.status() == ConfigStatus.UNWIRED && key.noteZh().isBlank()) {
                problems.add(key.jsonKey() + "：标了 UNWIRED 但没写待办说明");
            }
        }
        assertTrue(problems.isEmpty(),
                "废弃 / 未接线的键必须对玩家解释原因（否则等于骗人）：\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    void 每个键都必须有中文名与中文说明() {
        List<String> problems = new ArrayList<>();
        for (ConfigKey key : ConfigKeyTable.all()) {
            if (key.nameZh().isBlank()) problems.add(key.jsonKey() + "：缺中文名");
            if (key.tooltipZh().isBlank()) problems.add(key.jsonKey() + "：缺中文说明");
            if (key.nameEn().isBlank()) problems.add(key.jsonKey() + "：缺英文名");
        }
        assertTrue(problems.isEmpty(),
                "本仓要求完整中文注释，配置项也不例外：\n  "
                        + String.join("\n  ", problems));
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static Field findField(Class<?> cls, String name) {
        try {
            return cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** 只取真正参与 GSON 序列化的实例字段（排除 static / transient / synthetic）。 */
    private static List<Field> instanceFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Modifier.isTransient(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            out.add(f);
        }
        return out;
    }

    private static ConfigType typeOf(Class<?> t) {
        if (t == boolean.class || t == Boolean.class) return ConfigType.BOOLEAN;
        if (t == int.class || t == Integer.class || t == long.class || t == Long.class) {
            return ConfigType.INT;
        }
        if (t == double.class || t == Double.class || t == float.class || t == Float.class) {
            return ConfigType.DOUBLE;
        }
        if (t == String.class) return ConfigType.STRING;
        if (t.isEnum()) return ConfigType.ENUM;
        if (List.class.isAssignableFrom(t)) return ConfigType.LIST;
        return ConfigType.STRING;
    }

    private static boolean valuesEqual(Object actual, Object expected) {
        if (actual == null || expected == null) return actual == expected;
        if (actual instanceof Number a && expected instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        // 枚举：配置类里是枚举常量，元数据表里存的是名字字符串。
        // 这与实际落盘口径一致 —— GSON 序列化枚举写的就是 name()。
        if (actual instanceof Enum<?> constant) {
            return constant.name().equals(String.valueOf(expected));
        }
        return actual.equals(expected);
    }
}
