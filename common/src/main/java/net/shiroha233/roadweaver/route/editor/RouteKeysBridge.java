package net.shiroha233.roadweaver.route.editor;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * 手绘路线按键的**跨平台注册桥**。
 *
 * <h2>为什么需要桥接</h2>
 * {@link RouteKeys} 里定义的是 {@code KeyMapping}（Minecraft 的类，common 可见），
 * 但真正把它注册进游戏、让它出现在「选项 → 按键」里的
 * {@code KeyBindingHelper.registerKeyBinding(...)} 属于 <b>Fabric API</b>，
 * common 模块编译时看不见（见 {@code common/build.gradle} 的依赖）。
 *
 * <p>所以沿用工程既有的 {@code @ExpectPlatform} 模式
 * （与 {@code RouteWorldRendererBridge} / {@code OpenMapKeyTextBridge} 一致）：
 * common 只声明，fabric 侧给实现。</p>
 */
public final class RouteKeysBridge {

    private RouteKeysBridge() {
    }

    /**
     * 创建并注册全部手绘路线按键。
     *
     * <p>由 {@code ClientInit} 在客户端初始化时调用一次。</p>
     */
    @ExpectPlatform
    public static void registerAll() {
        throw new AssertionError("RouteKeysBridge.registerAll not implemented for this platform");
    }
}
