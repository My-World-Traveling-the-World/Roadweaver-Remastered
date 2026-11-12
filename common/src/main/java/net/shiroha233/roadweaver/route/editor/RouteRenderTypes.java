/* 文件职责：手绘路线 3D 渲染专用的 {@link RenderType}（★ 2026-09-26 任务 B）。 */
package net.shiroha233.roadweaver.route.editor;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 手绘路线 3D 渲染专用的 {@link RenderType}（★ 2026-09-26 任务 B）。
 *
 * <h2>要解决什么</h2>
 * 用户反馈：「如果点还有线进了地下还是看不到的啊。能做成可以透视的么」。
 *
 * <p>根因：{@code RouteWorldRenderer} 用的是 {@link RenderType#lines()} ——
 * 它**开着深度测试**（{@code DEPTH_TEST}），所以地下的控制点与线段
 * 被地表方块挡住，玩家看不到自己放在地下的点。</p>
 *
 * <h2>做法：双 pass</h2>
 * <pre>
 *   pass 1（穿透层）：本类提供的 seenThroughLines()，**关闭深度测试**
 *                     ⇒ 地下的线能透过地面显示出来
 *   pass 2（正常层）：原 RenderType.lines()，照旧
 *                     ⇒ 近处的线仍被正确遮挡，保留立体层次
 * </pre>
 * 两层叠加后：地下的线**淡显**（pass 1 用较低 alpha），
 * 地上的线**清晰**（pass 2 正常），近处该挡的还是挡。
 *
 * <h2>⚠️ 为什么必须自建</h2>
 * 1.21.1 的 {@code RenderType} **没有**现成的"无深度线段"变体
 * （{@code lines()} 固定带 {@code DEPTH_TEST}），
 * 所以只能自己用 {@link RenderType#create} 拼一个。
 * 本项目此前**没有任何自定义 RenderType 先例**，这是第一个。
 *
 * <h2>受什么限制</h2>
 * <ul>
 *   <li>必须用 {@link DefaultVertexFormat#POSITION_COLOR_NORMAL} ——
 *       与 {@code lines()} 同一顶点格式，才能复用既有的顶点提交代码。</li>
 *   <li>顶点数必须是**偶数**（线段成对），与 {@code lines()} 的要求一致。</li>
 *   <li>本类只做"关深度测试"，不改线宽/光照/混合 —— 那会破坏与 pass 2 的视觉一致性。</li>
 * </ul>
 */
public final class RouteRenderTypes {

    private RouteRenderTypes() {
    }

    /**
     * **穿透线段** —— 关掉深度测试的 {@code lines()}。
     *
     * <p>用 {@code RenderType.create} 复制 {@code lines()} 的全部状态，
     * 只把 {@code DEPTH_TEST} 换成 {@code NO_DEPTH_TEST}。</p>
     *
     * <p>⚠️ 惰性初始化：{@code RenderType.create} 内部会注册到
     * {@code RenderType} 的静态表，必须在**渲染线程**首次调用
     * （类加载早于资源初始化会抛异常）。所以用 holder 懒加载。</p>
     */
    public static RenderType seenThroughLines() {
        return Holder.SEEN_THROUGH;
    }

    /**
     * 持有实际实例的懒加载 holder。
     *
     * <p>用静态嵌套类实现 —— JVM 保证类初始化在**首次主动使用**时发生，
     * 所以实例一定是在第一次真正渲染时创建的（那时渲染线程与资源都已就绪）。</p>
     */
    private static final class Holder {
        private static final RenderType SEEN_THROUGH = createSeenThroughLines();

        private Holder() {
        }
    }

    /**
     * 渲染层名字（★ 2026-09-26 修 I-11 后的**诚实版本**）。
     *
     * <h2>为什么不在这里做"重名探测"</h2>
     * 我最初想先查 {@code RenderType.chunkBufferLayers()} 是否已有同名条目，
     * 但**核对后发现做不到可靠判定**：那个集合返回的是**实例**，
     * 而名字是 `create` 时传入的私有字段，**外部无法按名字查询**
     * （{@code toString()} 不返回名字；重名的行为是 put 进内部 name→type 表）。
     *
     * <p>与其写一段"看起来在防护、实际什么都没做"的代码（那正是本项目
     * 反复出现的"注释与实现不符"问题），不如把**真实约束**写清楚：</p>
     *
     * <ul>
     *   <li>名字由本类的**静态嵌套类 holder 保证只创建一次**（JVM 类初始化语义）
     *       ⇒ **同一类加载器下不可能自我重名**。</li>
     *   <li>唯一风险是**将来有人新增 RenderType 撞名** —— 那属于新增代码时的
     *       人工检查项，已在本行注释里点名，无需运行时守卫。</li>
     * </ul>
     */
    private static final String LAYER_NAME = "roadweaver_route_seen_through_lines";

    private static RenderType createSeenThroughLines() {
        // ★ 2026-09-26 修 W-14/I-8：**与 1.21.1 的 lines() 逐项对齐**。
        //
        // WorkBuddy 用 javap 反汇编 named jar 核对了 lines() 的真实参数，
        // 指出本类原先漏了 setOutputState(ITEM_ENTITY_TARGET) 且
        // 写错了 write-mask（应为 COLOR_DEPTH_WRITE，不是 COLOR_WRITE）。
        // 后果：Fabulous 画质下穿透层不参与半透明排序，合成顺序未定义（可能闪烁）。
        //
        // 现在**只保留一处刻意差异**：DEPTH_TEST → NO_DEPTH_TEST（这就是"穿透"本身）。
        //
        // ⚠️ 新增 RenderType 时请检查此名是否与既有撞名（参见 LAYER_NAME 的说明）。
        return RenderType.create(
                LAYER_NAME,
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(java.util.OptionalDouble.empty()))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)   // ★ W-14 补上
                        // ★ 唯一与 lines() 的区别：不做深度测试 ⇒ 穿透地表
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)  // ★ W-14 修正
                        .createCompositeState(false));
    }
}
