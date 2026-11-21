/* 文件职责：把完整配置绑定清单转换为Cloth控件，分类、分级和范围与元数据同源。 */
package net.shiroha233.roadweaver.client.fabric;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.meta.ConfigCategory;
import net.shiroha233.roadweaver.config.meta.ConfigOption;
import net.shiroha233.roadweaver.config.meta.ConfigTier;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 控件只负责呈现与提交，数学参数和真实setter均由可离线测的绑定对象负责。 */
final class MetadataConfigScreen {
    private MetadataConfigScreen() {}

    static void populate(ConfigBuilder builder, ModConfig draft) {
        var eb = builder.entryBuilder();
        var options = ConfigOption.bind(draft);
        for (ConfigCategory group : ConfigCategory.values()) {
            var category = builder.getOrCreateCategory(
                    Component.translatable("config.roadweaver.page." + group.name()));
            category.addEntry(eb.startTextDescription(
                    Component.translatable("config.roadweaver.apply_scope")).build());
            if (group == ConfigCategory.STRUCTURE) category.addEntry(new OpenStructureSelectionEntry());
            if (group == ConfigCategory.BUILD) category.addEntry(new OpenPresetEditorEntry());
            if (group == ConfigCategory.PERFORMANCE) {
                // 固定后端只展示说明，不能重新生成一个可以修改但无效的精确GPU开关。
                category.addEntry(eb.startTextDescription(
                        Component.translatable("config.roadweaver.accurate_cpu_fixed")).build());
            }
            for (ConfigTier tier : List.of(ConfigTier.BASIC, ConfigTier.ADVANCED, ConfigTier.EXPERT)) {
                var entries = options.stream()
                        .filter(option -> option.key().category() == group && option.key().tier() == tier)
                        .map(option -> entry(eb, option)).toList();
                if (tier == ConfigTier.BASIC) {
                    for (var item : entries) category.addEntry(item);
                } else if (!entries.isEmpty()) {
                    var section = eb.startSubCategory(
                            Component.translatable("config.roadweaver.level." + tier.name()))
                            .setExpanded(false);
                    section.addAll(entries);
                    category.addEntry(section.build());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static AbstractConfigListEntry<?> entry(ConfigEntryBuilder eb, ConfigOption option) {
        var meta = option.key();
        Component name = Component.translatable(option.labelKey());
        // 范围与单位取同一份元数据；专家成本也必须区分格、站和毫秒。
        Component description = Component.translatable(option.labelKey() + ".tooltip");
        Component[] tip = meta.min() == null ? new Component[]{description} : new Component[]{description,
                Component.translatable("config.roadweaver.value_range", meta.min(), meta.max(),
                        Component.translatable("config.roadweaver.unit." + meta.unit()))};
        Object value = option.value(), def = option.defaultValue();
        return switch (meta.type()) {
            case BOOLEAN -> eb.startBooleanToggle(name, (Boolean) value).setDefaultValue((Boolean) def)
                    .setTooltip(tip).setSaveConsumer(option::save).build();
            case INT -> eb.startIntField(name, ((Number) value).intValue())
                    .setMin(meta.min().intValue()).setMax(meta.max().intValue())
                    .setDefaultValue(((Number) def).intValue()).setTooltip(tip)
                    .setErrorSupplier(v -> error(option, v)).setSaveConsumer(option::save).build();
            case DOUBLE -> eb.startDoubleField(name, ((Number) value).doubleValue())
                    .setMin(meta.min().doubleValue()).setMax(meta.max().doubleValue())
                    .setDefaultValue(((Number) def).doubleValue()).setTooltip(tip)
                    .setErrorSupplier(v -> error(option, v)).setSaveConsumer(option::save).build();
            case ENUM -> eb.startSelector(name, option.valueType().getEnumConstants(), value)
                    .setNameProvider(v -> Component.translatable(
                            option.labelKey() + ".value." + ((Enum<?>) v).name().toLowerCase(Locale.ROOT)))
                    .setDefaultValue(def).setTooltip(tip).setSaveConsumer(option::save).build();
            case LIST -> eb.startStrList(name, (List<String>) value)
                    .setDefaultValue((List<String>) def).setTooltip(tip)
                    .setExpanded(false).setSaveConsumer(option::save).build();
            case STRING -> stringEntry(eb, option, name, tip, (String) value, (String) def);
        };
    }

    private static AbstractConfigListEntry<?> stringEntry(ConfigEntryBuilder eb, ConfigOption option,
                                                          Component name, Component[] tip,
                                                          String value, String def) {
        if (option.id().equals("performance.openclDevicePreference")) {
            return eb.startSelector(name, new String[]{"AUTO", "GPU", "CPU"}, value)
                    .setDefaultValue(def).setTooltip(tip).setSaveConsumer(option::save).build();
        }
        if (option.id().equals("performance.playerRoutePriorityExemptPauseReasons")) {
            // 用具名布尔项替代任意字符串输入，防止拼错后静默失去玩家设定的让路规则。
            var section = eb.startSubCategory(name).setExpanded(false).setTooltip(tip);
            var selected = new java.util.LinkedHashSet<>(split(value));
            for (String reason : List.of("PLAYER_INTERACTION", "RECOVERY", "PLAYER_TRAVEL", "CHUNKY", "SYSTEM_PRESSURE")) {
                section.add(eb.startBooleanToggle(
                                Component.translatable(option.labelKey() + ".reason." + reason), selected.contains(reason))
                        .setDefaultValue(split(def).contains(reason)).setTooltip(tip)
                        .setSaveConsumer(enabled -> {
                            if (enabled) selected.add(reason); else selected.remove(reason);
                            option.save(String.join(",", selected));
                        }).build());
            }
            return section.build();
        }
        return eb.startStrField(name, value).setDefaultValue(def).setTooltip(tip)
                .setErrorSupplier(v -> error(option, v)).setSaveConsumer(option::save).build();
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private static Optional<Component> error(ConfigOption option, Object value) {
        return option.accepts(value) ? Optional.empty()
                : Optional.of(Component.translatable("config.roadweaver.invalid_value"));
    }
}
