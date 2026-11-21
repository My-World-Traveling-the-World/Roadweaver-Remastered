/* 文件职责：配置项的功能分类（UI 分组、搜索过滤的依据）。 */
package net.shiroha233.roadweaver.config.meta;

/**
 * 功能分类。
 *
 * <p>分类只影响<b>展示分组</b>，不影响任何行为。分组的依据是
 * 「玩家想调某个效果时，会先去哪一组找」。</p>
 */
public enum ConfigCategory {
    /** 按玩家目标排序，开局与后台范围放在同一页，采样预算归性能页。 */
    PATHFIND("预热与规划"),
    STRUCTURE("结构与避让"),
    PROFILE("地形与坡度"),
    CROSSING("桥隧与过水"),
    /** 施工：铺什么方块、怎么铺、栏杆路灯。 */
    BUILD("路面与装饰"),
    /** 路边建筑与沿路村庄共页，但开关保留独立限定身份。 */
    ROADSIDE("路边建筑"),
    /** 性能：线程、预算、缓存。 */
    PERFORMANCE("性能与调度"),
    /** 客户端：界面、地图、进度的显示开关。 */
    CLIENT("地图与界面");

    private final String label;
    ConfigCategory(String label) { this.label = label; }

    /** 中文显示名。 */
    public String label() { return this.label; }
}
