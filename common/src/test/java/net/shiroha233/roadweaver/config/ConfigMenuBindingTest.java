package net.shiroha233.roadweaver.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.shiroha233.roadweaver.config.meta.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 测真实菜单绑定、setter与序列化，禁止靠源码正则数到几个控件就冒充接线。 */
final class ConfigMenuBindingTest {
    private static final Gson GSON = new Gson();

    @Test
    void everyPersistedFieldHasExactlyOneQualifiedMenuBinding() throws Exception {
        var config = new ModConfig();
        var expected = new HashSet<String>();
        for (var owner : ModConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(owner.getModifiers())) continue;
            owner.setAccessible(true);
            for (var field : owner.get(config).getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) continue;
                expected.add(owner.getName() + "." + field.getName());
            }
        }
        assertEquals(196, expected.size(), "字段变化须审查菜单、缓存与适用阶段");
        var options = ConfigOption.bind(config);
        assertEquals(expected, new HashSet<>(options.stream().map(ConfigOption::id).toList()));
        assertEquals(expected.size(), options.size(), "重复控件也会导致保存时覆盖另一个选择");
        assertEquals(8, ConfigCategory.values().length);
        assertEquals(8, options.stream().map(v -> v.key().category()).distinct().count());
        for (var option : options) {
            assertFalse(option.key().readOnlyInUi(), option.id());
            assertTrue(option.accepts(option.defaultValue()), option.id());
        }
    }

    @Test
    void settersWriteOnlyTheirOwnQualifiedFieldsAndDraftRemainsIndependent() {
        var original = new ModConfig();
        var draft = original.snapshot();
        var options = ConfigOption.bind(draft);
        String initial = GSON.toJson(original);
        int changed = 0;
        for (var option : options) {
            var before = GSON.toJsonTree(draft).getAsJsonObject();
            Object next = switch (option.key().type()) {
                case BOOLEAN -> !(Boolean) option.value();
                case INT -> option.key().max().intValue();
                case DOUBLE -> option.key().max().doubleValue();
                case ENUM -> option.valueType().getEnumConstants()[0];
                case LIST -> List.of("minecraft:village_plains");
                case STRING -> option.id().endsWith("openclDevicePreference") ? "CPU" : "";
            };
            option.save(next);
            var after = GSON.toJsonTree(draft).getAsJsonObject();
            var expected = before.deepCopy();
            var actual = after.getAsJsonObject(option.section()).get(option.key().jsonKey());
            expected.getAsJsonObject(option.section()).add(option.key().jsonKey(), actual);
            assertEquals(expected, after, "错绑到别的字段：" + option.id());
            assertEquals(GSON.toJsonTree(next), actual, "setter没有保存请求值：" + option.id());
            if (!before.equals(after)) changed++;
        }
        assertTrue(changed > 150, "必须真正改变大量字段，不能只重复保存默认值");
        assertEquals(initial, GSON.toJson(original), "编辑/取消不能提前污染生产对象");
        var loaded = GSON.fromJson(GSON.toJson(draft), ModConfig.class);
        assertEquals(GSON.toJsonTree(draft), GSON.toJsonTree(loaded.snapshot()), "新参数必须穿过JSON与任务快照");
    }

    @Test
    void declaredNumericEndpointsSurviveActualSanitizers() {
        for (boolean upper : new boolean[]{false, true}) {
            var config = new ModConfig();
            var options = ConfigOption.bind(config);
            int checked = 0;
            for (var option : options) {
                if (option.key().min() == null) continue;
                Number endpoint = upper ? option.key().max() : option.key().min();
                if (option.key().type() == ConfigType.INT) option.save(endpoint.intValue());
                else option.save(endpoint.doubleValue());
                checked++;
            }
            var before = GSON.toJsonTree(config);
            config.sanitize();
            assertEquals(before, GSON.toJsonTree(config),
                    "界面范围包含保存后被偷偷夹取的值，upper=" + upper);
            assertTrue(checked > 100);
        }
    }

    @Test
    void invalidNumericInputsAndDiscreteHolesAreRejected() {
        var options = ConfigOption.bind(new ModConfig());
        for (var option : options) {
            if (option.key().min() == null) continue;
            assertFalse(option.accepts(Double.NaN), option.id());
            assertFalse(option.accepts(Double.POSITIVE_INFINITY), option.id());
            assertFalse(option.accepts(option.key().min().doubleValue() - 1), option.id());
            assertFalse(option.accepts(option.key().max().doubleValue() + 1), option.id());
        }
        assertFalse(find(options, "roadAppearance.roadWidth").accepts(4));
        assertFalse(find(options, "planning.coarsePathWaitTimeoutMs").accepts(500));
        assertTrue(find(options, "planning.coarsePathWaitTimeoutMs").accepts(0));
        assertFalse(find(options, "planning.localRepairWindowClusterRadiusBlocks").accepts(4));
        assertTrue(find(options, "planning.localRepairWindowClusterRadiusBlocks").accepts(0));
        assertFalse(find(options, "planning.dynamicPlanRadiusChunks").accepts(4096));
        assertTrue(find(options, "planning.initialPlanRadiusChunks").accepts(4096));
        assertFalse(find(options, "performance.openclDevicePreference").accepts("random GPU"));
    }

    @Test
    void everyFloatingSettingRejectsNonFiniteJsonBeforePublication() {
        int checked = 0;
        for (var option : ConfigOption.bind(new ModConfig())) {
            if (option.key().type() != ConfigType.DOUBLE) continue;
            for (String invalid : List.of("NaN", "Infinity", "-Infinity")) {
                var tree = GSON.toJsonTree(new ModConfig()).getAsJsonObject();
                tree.getAsJsonObject(option.section()).addProperty(option.key().jsonKey(), invalid);
                var loaded = ConfigService.decodeCurrent(tree);
                var actual = ConfigOption.bind(loaded).stream().filter(v -> v.id().equals(option.id())).findFirst().orElseThrow();
                assertTrue(Double.isFinite(((Number) actual.value()).doubleValue()), option.id() + ":" + invalid);
                assertEquals(option.defaultValue(), actual.value(), option.id() + ":" + invalid);
            }
            checked++;
        }
        assertTrue(checked > 30, "必须实际覆盖浮点字段而非空集合");
    }

    @Test
    void bothLanguagesCoverEveryActualOptionAndEnumValue() throws Exception {
        for (String language : List.of("zh_cn", "en_us")) {
            try (var in = getClass().getResourceAsStream("/assets/roadweaver/lang/" + language + ".json")) {
                assertNotNull(in);
                var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (var option : ConfigOption.bind(new ModConfig())) {
                    for (String key : List.of(option.labelKey(), option.labelKey() + ".tooltip")) {
                        assertTrue(json.has(key), language + ":" + key);
                        assertFalse(json.get(key).getAsString().isBlank(), language + ":" + key);
                    }
                    // 数值范围与单位在真实界面中共同展示，不能只检查参数标题翻译。
                    if (option.key().min() != null) {
                        assertTrue(json.has("config.roadweaver.value_range"), language);
                        assertTrue(json.has("config.roadweaver.unit." + option.key().unit()),
                                language + ":" + option.id());
                    }
                    if (option.valueType().isEnum()) {
                        for (Object value : option.valueType().getEnumConstants()) {
                            String key = option.labelKey() + ".value." + ((Enum<?>) value).name().toLowerCase(java.util.Locale.ROOT);
                            assertTrue(json.has(key), language + ":" + key);
                        }
                    }
                }
                for (ConfigCategory page : ConfigCategory.values()) {
                    assertTrue(json.has("config.roadweaver.page." + page.name()));
                }
            }
        }
    }

    private static ConfigOption find(List<ConfigOption> options, String id) {
        return options.stream().filter(option -> option.id().equals(id)).findFirst().orElseThrow();
    }
}
