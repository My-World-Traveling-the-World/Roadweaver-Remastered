package net.shiroha233.roadweaver.route.editor;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * 世界渲染注册的跨平台桥。
 *
 * <h2>为什么需要桥接</h2>
 * 世界内渲染用的是 Fabric 的 {@code WorldRenderEvents}，它在
 * <b>fabric-api</b> 里。而 {@code common} 模块只编译依赖 Minecraft + Fabric Loader
 * （见 {@code common/build.gradle}），**看不到 fabric-api**。
 *
 * <p>直接 import 会编译失败。Architectury 的标准做法就是本类这种
 * {@code @ExpectPlatform} 桥：common 只声明签名，各平台模块给实现。
 * 仓库里 {@code OpenMapKeyTextBridge} / {@code ClientNetBridge} 等
 * 都是同一个模式，这里照抄保持一致。</p>
 */
public final class RouteWorldRendererBridge {

    private RouteWorldRendererBridge() {
    }

    /**
     * 注册世界内渲染回调。
     *
     * <p>由 fabric 模块实现，在 {@code ClientInit} 里调用一次。</p>
     */
    @ExpectPlatform
    public static void registerWorldRender() {
        throw new AssertionError("RouteWorldRendererBridge.registerWorldRender not implemented for this platform");
    }
}
