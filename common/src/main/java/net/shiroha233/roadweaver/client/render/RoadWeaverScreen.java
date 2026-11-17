/* 文件职责：本模组自定义屏幕的基类。 */
package net.shiroha233.roadweaver.client.render;

import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** 统一自定义界面的背景行为，避免1.21默认模糊干扰地图与配置界面。
 *
 * Shared screen base that keeps RoadWeaver UI backgrounds deterministic and non-blurred on 1.21.
 */
public abstract class RoadWeaverScreen extends Screen {
    protected RoadWeaverScreen(Component title) {
        super(title);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void renderTransparentBackground(GuiGraphics graphics) {
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }
}
