/* 文件职责：单个配置项的元数据记录 —— UI、校验与文档的唯一真相。 */
package net.shiroha233.roadweaver.config.meta;

/**
 * 一个配置项的元数据。
 *
 * <h2>为什么要有这张表</h2>
 * <p>旧仓有 243 个配置字段，但 UI 只暴露了 45 个（19%）。剩下 196 个玩家改不到，
 * 其中若干是<b>死键</b>（改了完全无效）却和正常键长得一样。新增一个键要改三处
 * （字段声明 / JSON 读写 / UI 控件 + 中英翻译各一份），所以越加越乱。</p>
 *
 * <p>这张表把这些信息集中到<b>一处</b>：JSON 键（= 字段名，因为本仓用 GSON
 * 自动序列化）、类型、默认值、范围、分类、分级、接线状态、中英名称、说明、单位。</p>
 *
 * <h2>与配置类的同步由测试强制</h2>
 * <p>{@code ConfigKeyTableConsistencyTest} 反射读配置类字段，双向校验：
 * 表里有的键必须在类里有字段、类里的字段必须在表里有条目，
 * 且<b>类型与默认值必须逐项相等</b>。不同步 = 测试红。</p>
 *
 * <h2>为什么 JSON 形状不用改</h2>
 * <p>{@code ConfigService} 用 {@code GSON.toJson(INSTANCE)} 直接序列化整个
 * {@code ModConfig} 对象图，所以 <b>JSON 键就是 Java 字段名</b>。
 * 配置类本身不动，玩家的 {@code roadweaver.json} 就完全兼容。</p>
 *
 * @param jsonKey       JSON 键 = Java 字段名（GSON 默认命名）
 * @param type          取值类型
 * @param defaultValue  出厂默认值（必须与配置类字段初值相等）
 * @param min           数值下限（非数值型为 null）
 * @param max           数值上限（非数值型为 null）
 * @param category      功能分类（UI 分组）
 * @param tier          可见层级
 * @param status        接线状态
 * @param nameZh        中文名
 * @param nameEn        英文名
 * @param tooltipZh     中文说明（写清「改了会发生什么」）
 * @param unit          单位
 * @param noteZh        备注：废弃原因 / 未接线说明 / 调参建议（可为空串）
 */
public record ConfigKey(
        String jsonKey,
        ConfigType type,
        Object defaultValue,
        Number min,
        Number max,
        ConfigCategory category,
        ConfigTier tier,
        ConfigStatus status,
        String nameZh,
        String nameEn,
        String tooltipZh,
        String unit,
        String noteZh) {

    /** 布尔开关。 */
    public static ConfigKey bool(String jsonKey, boolean def,
                                 ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                 String nameZh, String nameEn, String tooltipZh, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.BOOLEAN, def, null, null,
                cat, tier, status, nameZh, nameEn, tooltipZh, "", noteZh);
    }

    /** 整数。 */
    public static ConfigKey integer(String jsonKey, int def, int min, int max,
                                    ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                    String nameZh, String nameEn, String tooltipZh,
                                    String unit, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.INT, def, min, max,
                cat, tier, status, nameZh, nameEn, tooltipZh, unit, noteZh);
    }

    /** 浮点。 */
    public static ConfigKey decimal(String jsonKey, double def, double min, double max,
                                    ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                    String nameZh, String nameEn, String tooltipZh,
                                    String unit, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.DOUBLE, def, min, max,
                cat, tier, status, nameZh, nameEn, tooltipZh, unit, noteZh);
    }

    /** 字符串列表。默认值直接存 List，便于与配置类字段做元素级比较。 */
    public static ConfigKey list(String jsonKey, java.util.List<String> def,
                                 ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                 String nameZh, String nameEn, String tooltipZh, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.LIST, java.util.List.copyOf(def), null, null,
                cat, tier, status, nameZh, nameEn, tooltipZh, "", noteZh);
    }

    /** 字符串。 */
    public static ConfigKey string(String jsonKey, String def,
                                   ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                   String nameZh, String nameEn, String tooltipZh, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.STRING, def, null, null,
                cat, tier, status, nameZh, nameEn, tooltipZh, "", noteZh);
    }

    /** 枚举。 */
    public static ConfigKey enumeration(String jsonKey, String def,
                                        ConfigCategory cat, ConfigTier tier, ConfigStatus status,
                                        String nameZh, String nameEn, String tooltipZh, String noteZh) {
        return new ConfigKey(jsonKey, ConfigType.ENUM, def, null, null,
                cat, tier, status, nameZh, nameEn, tooltipZh, "", noteZh);
    }

    /** 是否默认折叠（专家 / 已废弃档）。 */
    public boolean collapsedByDefault() {
        return tier == ConfigTier.EXPERT || tier == ConfigTier.DEPRECATED;
    }

    /** 是否应在 UI 上灰显。 */
    public boolean readOnlyInUi() {
        return status == ConfigStatus.DEPRECATED;
    }
}
