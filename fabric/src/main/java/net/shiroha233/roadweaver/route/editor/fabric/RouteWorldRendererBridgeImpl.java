package net.shiroha233.roadweaver.route.editor.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.shiroha233.roadweaver.route.editor.RouteEditorSession;
import net.shiroha233.roadweaver.route.editor.RouteMapRenderer;
import net.shiroha233.roadweaver.route.editor.RoutePreviewRenderer;
import net.shiroha233.roadweaver.route.editor.RouteWorldRenderer;

/**
 * {@link net.shiroha233.roadweaver.route.editor.RouteWorldRenderer} 的 Fabric 实现。
 *
 * <p>common 模块看不见 fabric-api，所以真正调 {@code WorldRenderEvents} /
 * {@code HudRenderCallback} 的注册必须放在这里。
 * 见 {@code RouteWorldRendererBridge} 的注释。</p>
 */
public final class RouteWorldRendererBridgeImpl {

    private RouteWorldRendererBridgeImpl() {
    }

    /** 由 {@code RouteWorldRendererBridge.registerWorldRender()} 经 Architectury 分派到这里。 */
    public static void registerWorldRender() {
        // ① 世界内画中心线（控制点 + 平滑曲线 + 竖直引线）
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                RouteWorldRenderer.render(
                        context.matrixStack(),
                        context.consumers(),
                        context.camera() == null ? null : context.camera().getPosition()));

        // ② 施工预览：虚线画出的路面足迹 + 桥/隧道起止标记柱
        // 用户要求「按颜色区别路/桥/隧道」，这里与中心线用同一套类型色。
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                RoutePreviewRenderer.render(
                        context.matrixStack(),
                        context.consumers(),
                        context.camera() == null ? null : context.camera().getPosition()));

        // ② 编辑模式 HUD（状态行 + 操作提示 + 施工反馈）
        // 3D 里玩家看不到地图上的工具条，必须把当前状态贴在屏幕上。
        // 注意 1.21.1 的回调签名是 onHudRender(GuiGraphics, DeltaTracker)，
        // 不是旧版的 (GuiGraphics, float)。
        HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> {
            if (!RouteEditorSession.get().isActive()) {
                return;
            }
            // ★ 审计 R3-P2-5：有界面打开时**不画**世界 HUD。
            //
            // 原先只判断「是否处于编辑模式」，于是开着背包 / 聊天 / 地图时
            // 提示文字照样画在那些界面之上，直接盖住原版内容；
            // 地图界面里更是与世界 HUD 内容重复（两边都画一份）。
            //
            // 加 screen == null 判断后，只有真正「在世界里看着画面」时才画。
            // 玩家开界面时本来也不在编辑（RouteWorldEditor.tick 同样有这个判断）。
            if (net.minecraft.client.Minecraft.getInstance().screen != null) {
                return;
            }
            var window = net.minecraft.client.Minecraft.getInstance().getWindow();
            RouteMapRenderer.renderWorldHud(guiGraphics, window.getGuiScaledWidth(),
                    window.getGuiScaledHeight());
        });
    }
}
