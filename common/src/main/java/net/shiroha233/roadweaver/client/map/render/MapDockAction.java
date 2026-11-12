/* 文件职责：定义地图底部 Dock 的固定主操作。 */
package net.shiroha233.roadweaver.client.map.render;

public enum MapDockAction {
    SEARCH("gui.roadweaver.map.dock.search"),
    FILTER("gui.roadweaver.map.dock.filter"),
    REFRESH("gui.roadweaver.map.dock.refresh"),
    MANUAL_CONNECT("gui.roadweaver.map.dock.manual_connect"),
    DELETE_CONNECTION("gui.roadweaver.map.dock.delete_connection"),
    /**
     * 玩家手绘路线编辑模式。
     *
     * <p>与 {@link #MANUAL_CONNECT} 是两回事：那个是「点两个结构、连一条路」，
     * 这个是「点任意多个点、画一条可自由编辑的折线」，见
     * {@code net.shiroha233.roadweaver.route.editor} 包。</p>
     */
    ROUTE_EDIT("gui.roadweaver.map.dock.route_edit"),
    /**
     * **开关地图端点标记**（★ 2026-09-26 任务 A）。
     *
     * <p>用户要求：「这里下方不是有一排按钮么加个格外的按钮就行，不用绑定键盘了」——
     * 所以这是入口只做在 Dock 上，**不占键盘绑定**。</p>
     */
    TOGGLE_MARKERS("gui.roadweaver.map.dock.toggle_markers"),
    CONFIG("gui.roadweaver.map.dock.config"),
    CLOSE("gui.roadweaver.map.dock.close");

    private final String tooltipKey;

    MapDockAction(String tooltipKey) {
        this.tooltipKey = tooltipKey;
    }

    public String tooltipKey() {
        return tooltipKey;
    }
}
