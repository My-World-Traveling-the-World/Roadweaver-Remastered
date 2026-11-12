/* 文件职责：配置项的功能分类（UI 分组、搜索过滤的依据）。 */
package net.shiroha233.roadweaver.config.meta;

/**
 * 功能分类。
 *
 * <p>分类只影响<b>展示分组</b>，不影响任何行为。分组的依据是
 * 「玩家想调某个效果时，会先去哪一组找」。</p>
 */
public enum ConfigCategory {
    /** 选线：什么形状、往哪走。 */
    PATHFIND("选线"),
    /** 地形采样：什么时候采、采多细、用哪个后端。 */
    TERRAIN("地形采样"),
    /** 纵断面：高度怎么变、能填能挖多少。 */
    PROFILE("纵断面"),
    /** 桥与隧道。 */
    CROSSING("桥与隧道"),
    /** 结构与村庄：避让、连哪些结构。 */
    STRUCTURE("结构与村庄"),
    /** 施工：铺什么方块、怎么铺、栏杆路灯。 */
    BUILD("施工与装饰"),
    /** 性能：线程、预算、缓存。 */
    PERFORMANCE("性能"),
    /** 客户端：界面、地图、进度的显示开关。 */
    CLIENT("客户端"),
    /** 其它。 */
    MISC("其它");

    private final String label;
    ConfigCategory(String label) { this.label = label; }

    /** 中文显示名。 */
    public String label() { return this.label; }
}
