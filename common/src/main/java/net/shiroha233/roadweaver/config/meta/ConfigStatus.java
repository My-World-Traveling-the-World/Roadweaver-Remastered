/* 文件职责：配置项的接线状态 —— 标注「这个键到底有没有生效」。 */
package net.shiroha233.roadweaver.config.meta;

/**
 * 接线状态。
 *
 * <h2>为什么需要这个状态</h2>
 * <p>旧仓配置里有<b>玩家改了完全没效果</b>的键，但长得和正常键一样、没有任何提示。
 * 玩家会以为自己在调参，实际什么也没发生 —— 这是最消耗信任的一类缺陷。</p>
 *
 * <p>{@code ConfigKnobHonestyTest} 已经在守这件事；本枚举把结论落到元数据上，
 * 让 UI 能直接显示出来。</p>
 */
public enum ConfigStatus {
    /** 已接线，改了有效。 */
    ACTIVE("已接线"),
    /** 已废弃：代码里不再读取，改了无效。 */
    DEPRECATED("已废弃"),
    /** 有字段但<b>尚未接线</b>（改了暂时无效，属已知待办）。 */
    UNWIRED("未接线"),
    /** 实验性：接线了但效果未真机验证。 */
    EXPERIMENTAL("实验性");

    private final String label;
    ConfigStatus(String label) { this.label = label; }

    /** 中文显示名。 */
    public String label() { return this.label; }
}
