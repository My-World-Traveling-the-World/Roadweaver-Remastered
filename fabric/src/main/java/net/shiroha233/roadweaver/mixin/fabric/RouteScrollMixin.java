/* 文件职责：鼠标滚轮注入：手绘路线编辑器里用滚轮缩放/滚动。 */
package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.client.MouseHandler;
import net.shiroha233.roadweaver.route.editor.RouteWorldEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界内手绘路线编辑：滚轮调高度。
 *
 * <h2>为什么需要这个 mixin</h2>
 * Fabric 没有「世界内滚轮」事件。玩家在世界里滚轮默认是切换快捷栏，
 * 而编辑模式下我们要把它改成「调所选点的高度」，所以必须在
 * {@code MouseHandler.onScroll} 的 HEAD 处拦截并取消。
 *
 * <p>只在编辑模式且选中了控制点时才取消 —— 其余情况完全不干扰原版行为，
 * 这也是为什么用 {@code require = 0}（即使未来 MC 版本改了方法签名，
 * 也只是这个功能失效，不会让整个 mod 崩）。</p>
 */
@Mixin(MouseHandler.class)
public abstract class RouteScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void roadweaver$routeScrollHeight(long window, double xOffset, double yOffset,
                                               CallbackInfo ci) {
        // ★ 审计 R3-P2-4：有界面打开时**必须直接放行**。
        //
        // MouseHandler.onScroll 在 Screen 存在时仍会被调用，而本 mixin 注入在
        // HEAD 且会 ci.cancel() —— 于是地图界面（RoadMapScreen）自己的
        // Ctrl+滚轮调高度永远收不到事件：RouteEditorBridge.onScroll 成了死代码，
        // 高度改动改由世界内那条路径记录进**世界撤销栈**，
        // 地图的 Ctrl+Z 因此退到别的状态（用户按下「撤销」看到的不是刚那次调高）。
        //
        // 加这一道短路后，滚轮在有界面时完全交回原版 → Screen 链，
        // 地图的缩放与 Ctrl+调高都恢复正常。
        if (net.minecraft.client.Minecraft.getInstance().screen != null) {
            return;
        }
        // yOffset > 0 向上滚（升高），< 0 向下滚（降低）
        if (RouteWorldEditor.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}
