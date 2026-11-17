/* 文件职责：自定义 UI 屏幕共用的非模糊背景。 */
package net.shiroha233.roadweaver.client.render;

import net.minecraft.client.gui.GuiGraphics;

/** 共享无模糊背景绘制，保证各自定义界面呈现一致。
 *
 * Shared non-blur screen background used by custom UI screens.
 */
public final class ScreenBackgrounds {
    private static final int TOP_COLOR = 0xC0101010;
    private static final int BOTTOM_COLOR = 0xD0101010;

    private ScreenBackgrounds() {}

    public static void render(GuiGraphics graphics, int width, int height) {
        graphics.fillGradient(0, 0, width, height, TOP_COLOR, BOTTOM_COLOR);
    }
}
