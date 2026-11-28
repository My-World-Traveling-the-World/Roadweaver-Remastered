/* 文件职责：定义道路地图读取外部底图的绘制边界。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.client.gui.GuiGraphics;

/** 可选底图只负责绘制，路线、点击和存档仍归道路地图。 */
public interface MapTerrainBackground {
    MapTerrainBackground NONE = (graphics, view, width, height) -> false;

    /** 能绘制有效底图才返回 true，让未安装或未探索时的现有底图仍可用。 */
    boolean render(GuiGraphics graphics, MapView view, int width, int height);

    default void reset() {}
}
