/* 文件职责：在 Diorama 底图与地图控件之间接入道路叠线。 */
package net.shiroha233.roadweaver.mixin.diorama;

import dev.diorama.MapScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.shiroha233.roadweaver.client.fabric.diorama.DioramaRoadOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 地图底图画完再叠线，保留上层标记和按钮的绘制次序。 */
@Mixin(value = MapScreen.class, remap = false)
public abstract class DioramaMapScreenMixin {
    @Unique
    private final DioramaRoadOverlay roadweaver$dioramaOverlay = new DioramaRoadOverlay();

    // MapScreen 属于第三方，开发包和发行包的原版 render 方法使用两套名称。
    // 底图绘制方法在此入口中只有一次，所有者和方法名均由 Diorama 定义。
    @Inject(method = {
            "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            "method_25394(Lnet/minecraft/class_332;IIF)V"
    }, at = @At(value = "INVOKE", target = "Ldev/diorama/render/MapViewport;draw", shift = At.Shift.AFTER),
            remap = false, require = 1, allow = 1)
    private void roadweaver$drawRoads(GuiGraphics graphics, int x, int y, float tick, CallbackInfo ci) {
        roadweaver$dioramaOverlay.render((MapScreen) (Object) this, graphics);
    }
}
