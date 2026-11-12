/* 文件职责：配置项的可见层级 —— 决定默认展开还是折叠。 */
package net.shiroha233.roadweaver.config.meta;

/**
 * 可见层级。
 *
 * <h2>为什么要分级</h2>
 * <p>本仓共 243 个配置键，全平铺在一个页面里没人找得到。分级后：
 * 基础档是玩家真正会改的，专家档默认折叠但可搜索。</p>
 *
 * <h2>判断标准</h2>
 * <ul>
 *   <li><b>BASIC</b> —— 改了对体验有直接、可感知的影响，不需要懂算法。</li>
 *   <li><b>ADVANCED</b> —— 需要知道一点原理，改错了会明显变差但不至于坏。</li>
 *   <li><b>EXPERT</b> —— 调参级，默认值就是真机调出来的结果，一般不建议改。</li>
 *   <li><b>DEPRECATED</b> —— 已废弃，改了没有任何效果（UI 灰显并说明原因）。</li>
 * </ul>
 */
public enum ConfigTier {
    /** 基础：默认展示。 */
    BASIC("基础"),
    /** 进阶：分类折叠展示。 */
    ADVANCED("进阶"),
    /** 专家：默认折叠，靠搜索找。 */
    EXPERT("专家"),
    /** 已废弃：灰显 + 原因说明。 */
    DEPRECATED("已废弃");

    private final String label;
    ConfigTier(String label) { this.label = label; }

    /** 中文显示名。 */
    public String label() { return this.label; }
}
