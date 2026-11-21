/* 文件职责：将完整配置元数据绑定到真实对象和公开setter，供菜单与离线守卫共用。 */
package net.shiroha233.roadweaver.config.meta;

import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.SubConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 限定对象名参与身份，避免三个enabled开关被裸字段名错绑到一起。 */
public final class ConfigOption {
    private final String section;
    private final ConfigKey key;
    private final SubConfig owner;
    private final Field field;
    private final Method setter;

    private ConfigOption(String section, ConfigKey key, SubConfig owner) throws ReflectiveOperationException {
        this.section = section;
        this.key = key;
        this.owner = owner;
        this.field = owner.getClass().getDeclaredField(key.jsonKey());
        field.setAccessible(true);
        String name = key.jsonKey();
        // 查找失败必须让界面构建失败并留下异常，不能悄悄漏掉新增参数。
        this.setter = owner.getClass().getMethod(
                "set" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1),
                field.getType() == Boolean.class ? boolean.class : field.getType());
    }

    public static List<ConfigOption> bind(ModConfig config) {
        List<ConfigOption> result = new ArrayList<>();
        try {
            for (String section : ConfigKeyTable.registeredConfigs()) {
                SubConfig owner = (SubConfig) ModConfig.class.getMethod(section).invoke(config);
                for (ConfigKey key : ConfigKeyTable.forConfig(section)) {
                    if (key.status() == ConfigStatus.UNWIRED || key.status() == ConfigStatus.DEPRECATED
                            || key.tier() == ConfigTier.DEPRECATED) {
                        throw new IllegalStateException("新版菜单不得保留无效配置：" + section + "." + key.jsonKey());
                    }
                    result.add(new ConfigOption(section, key, owner));
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("配置菜单绑定失败，字段与元数据不同步", failure);
        }
        return List.copyOf(result);
    }

    public String section() { return section; }
    public ConfigKey key() { return key; }
    public String id() { return section + "." + key.jsonKey(); }
    public String labelKey() { return "config.roadweaver.option." + id(); }
    public Class<?> valueType() { return field.getType(); }

    public Object value() {
        try {
            Object value = field.get(owner);
            return value instanceof List<?> list ? new ArrayList<>(list) : value;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("读取配置失败：" + id(), failure);
        }
    }

    public Object defaultValue() {
        if (field.getType().isEnum()) {
            for (Object constant : field.getType().getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(key.defaultValue())) return constant;
            }
            throw new IllegalStateException("枚举默认值不存在：" + id());
        }
        return key.defaultValue();
    }

    public void save(Object value) {
        if (!accepts(value)) throw new IllegalArgumentException("配置值超出范围或不合法：" + id() + "=" + value);
        try {
            // 复用原setter的语义；列表先复制，界面继续编辑时不能反向修改已保存对象。
            setter.invoke(owner, value instanceof List<?> list ? new ArrayList<>(list) : value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("保存配置失败：" + id(), failure);
        }
    }

    /** 菜单提交前拒绝会被静默修正的单项输入；成对字段仍按保存时的sanitize统一校验。 */
    public boolean accepts(Object value) {
        if (value == null) return false;
        if (key.type() == ConfigType.INT || key.type() == ConfigType.DOUBLE) {
            if (!(value instanceof Number number)) return false;
            if (key.type() == ConfigType.INT && !(value instanceof Integer)) return false;
            if (key.type() == ConfigType.DOUBLE && !(value instanceof Double)) return false;
            double v = number.doubleValue();
            if (!Double.isFinite(v) || v < key.min().doubleValue() || v > key.max().doubleValue()) return false;
            if (key.type() == ConfigType.INT && v != Math.rint(v)) return false;
            // 连续输入框无法表达“0或一个区间”和“仅奇数”，必须显式报错而非保存后偷偷改值。
            if (id().equals("roadAppearance.roadWidth") && (number.intValue() & 1) == 0) return false;
            if (id().equals("planning.coarsePathWaitTimeoutMs") && v != 0 && v < 10000) return false;
            if (id().equals("planning.localRepairWindowClusterRadiusBlocks") && v != 0 && v < 8) return false;
            return true;
        }
        if (key.type() == ConfigType.ENUM) return field.getType().isInstance(value);
        if (key.type() == ConfigType.BOOLEAN) return value instanceof Boolean;
        if (key.type() == ConfigType.LIST) {
            return value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String);
        }
        if (!(value instanceof String text)) return false;
        // 字符串落盘的设备偏好也必须使用有限选择，不把拼写错误保存为“自动”。
        if (id().equals("performance.openclDevicePreference")) return List.of("AUTO", "CPU", "GPU").contains(text);
        if (id().equals("performance.playerRoutePriorityExemptPauseReasons")) {
            if (text.isBlank()) return true;
            return java.util.Arrays.stream(text.split(",", -1)).allMatch(
                    reason -> List.of("PLAYER_INTERACTION", "RECOVERY", "PLAYER_TRAVEL", "CHUNKY", "SYSTEM_PRESSURE")
                            .contains(reason.trim()));
        }
        return true;
    }
}
