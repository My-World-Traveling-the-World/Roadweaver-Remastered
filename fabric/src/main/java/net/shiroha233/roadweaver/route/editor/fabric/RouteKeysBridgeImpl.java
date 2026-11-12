package net.shiroha233.roadweaver.route.editor.fabric;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.shiroha233.roadweaver.route.editor.RouteKeys;

/**
 * {@link RouteKeysBridge} 的 Fabric 实现。
 *
 * <p>common 模块看不见 {@code KeyBindingHelper}，所以真正注册必须在这里做。</p>
 */
public final class RouteKeysBridgeImpl {

    private RouteKeysBridgeImpl() {
    }

    /** 由 {@code RouteKeysBridge.registerAll()} 经 Architectury 分派到这里。 */
    public static void registerAll() {
        // 先创建 KeyMapping 对象，再逐个注册 —— 注册时必须非 null
        RouteKeys.create();
        KeyBindingHelper.registerKeyBinding(RouteKeys.MOVE_FORWARD);
        KeyBindingHelper.registerKeyBinding(RouteKeys.MOVE_BACK);
        KeyBindingHelper.registerKeyBinding(RouteKeys.MOVE_LEFT);
        KeyBindingHelper.registerKeyBinding(RouteKeys.MOVE_RIGHT);
        KeyBindingHelper.registerKeyBinding(RouteKeys.CYCLE_KIND);
        KeyBindingHelper.registerKeyBinding(RouteKeys.DELETE_POINT);
        KeyBindingHelper.registerKeyBinding(RouteKeys.INSERT_POINT);
        KeyBindingHelper.registerKeyBinding(RouteKeys.UNDO);
        KeyBindingHelper.registerKeyBinding(RouteKeys.OPEN_HEIGHT);
        KeyBindingHelper.registerKeyBinding(RouteKeys.CONFIRM_BUILD);
        KeyBindingHelper.registerKeyBinding(RouteKeys.TOGGLE_PREVIEW);
        // 加速键（修饰键也做成可绑定 —— 用户要求「修饰键一样可以自定义」）
        KeyBindingHelper.registerKeyBinding(RouteKeys.SPEED_BOOST);
        // ★ 审计 R3-P3-5：编辑修饰符同样做成可绑定（默认左 Ctrl）
        KeyBindingHelper.registerKeyBinding(RouteKeys.EDIT_MODIFIER);
    }
}
