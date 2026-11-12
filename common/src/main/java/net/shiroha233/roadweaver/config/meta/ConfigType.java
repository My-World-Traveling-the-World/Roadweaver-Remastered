/* 文件职责：配置项的取值类型（决定 UI 控件与 JSON 读写方式）。 */
package net.shiroha233.roadweaver.config.meta;

/** 配置项类型。类型决定 UI 用哪一个控件。 */
public enum ConfigType {
    /** 布尔开关 → 复选框。 */
    BOOLEAN,
    /** 整数 → 滑块（有范围）或整数输入框（无范围）。 */
    INT,
    /** 浮点 → 精确输入框（滑块会丢精度）。 */
    DOUBLE,
    /** 字符串 → 文本框。 */
    STRING,
    /** 枚举 → 下拉框。 */
    ENUM,
    /** 字符串列表 → 可增删列表。 */
    LIST
}
