/* 文件职责：地图界面的纯布局度量 —— 粗细、点径、工作区底边。 */
package net.shiroha233.roadweaver.client.map;

/**
 * 地图布局的纯度量。
 *
 * <h2>为什么这几个小函数值得单独一个类</h2>
 * <p>它们都不画东西、不改状态，只把「缩放比例」算成「画多粗」。但它们是
 * <b>地图可读性的核心</b>：粗细随缩放变化，放大时太粗会糊、缩小时太细会看不见。
 * 藏在 1200 行的界面类里就永远测不到边界（恰好等于 MIN/MAX 时会怎样）。</p>
 *
 * @see MapTheme
 */
public final class MapLayoutMetrics {

    /** Dock 与工作区底边之间的空隙（格）。 */
    public static final int WORKSPACE_DOCK_GAP = 6;

    private MapLayoutMetrics() {}

    /**
     * 由像素/格比例算出线条粗细。
     *
     * <p>取整后夹到 {@code [MIN_THICKNESS, MAX_THICKNESS]}：缩得很小时至少还有 1px，
     * 放得很大时也不会变成一条粗带。</p>
     */
    public static int thickness(double pxPerBlock) {
        int t = (int) Math.round(pxPerBlock);
        return Math.max(MapTheme.MIN_THICKNESS, Math.min(t, MapTheme.MAX_THICKNESS));
    }

    /** 点位直径 = 基础点径 + 线条粗细。 */
    public static int pointSize(int thickness) {
        return MapTheme.BASE_POINT_SIZE + thickness;
    }

    /** 工作区底边：Dock 顶边再往上留一点空隙，最少为 1。 */
    public static int workspaceBottomLimit(int dockTopY) {
        return Math.max(1, dockTopY - WORKSPACE_DOCK_GAP);
    }
}
