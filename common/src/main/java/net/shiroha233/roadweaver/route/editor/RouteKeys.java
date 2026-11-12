/* 文件职责：手绘路线的 15 个可绑定键位 —— ★ UiFeatureGuardTest 锁的就是这份清单，少一个就是功能丢失。 */
package net.shiroha233.roadweaver.route.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 手绘路线编辑器的**按键绑定**。
 *
 * <h2>为什么要有这个类</h2>
 * 第一版把这些键写成硬编码轮询（{@code InputConstants.isKeyDown(window, GLFW_KEY_...)}），
 * 理由是「世界里没有通用按键事件，方向键要按住连续移动」。
 * 但代价很明显：
 * <ul>
 *   <li><b>玩家改不了</b> —— 不出现在「选项 → 按键」里</li>
 *   <li><b>和原版打架</b> —— Tab 会同时弹出玩家列表、Enter 会同时打开聊天，
 *       而原版的按键冲突检测对硬编码轮询完全不知情</li>
 * </ul>
 * 所以全部改成真正的 {@link KeyMapping}，玩家可自由改键，原版也能标出冲突。
 *
 * <h2>为什么放在 common</h2>
 * {@link KeyMapping} 是 Minecraft 自己的类，common 模块能看见。
 * 只有 {@code KeyBindingHelper}（Fabric API）在 common 看不见 ——
 * 所以这里只**定义**，注册交给
 * {@link RouteKeysBridge#registerAll()}（{@code @ExpectPlatform}）在 fabric 侧做。
 *
 * <h2>默认键的选择</h2>
 * 刻意避开会与原版撞车的 Tab / Enter：
 * <pre>
 *   Tab  → 原版 key.playerlist（按住显示玩家列表）
 *   Enter→ 原版 key.chat（打开聊天）
 * </pre>
 * 默认给不冲突的键，玩家想改回 Tab/Enter 也能自己在设置里改。
 */
public final class RouteKeys {

    /** 所有手绘路线按键归到这一个分类，便于在设置里成组找到。 */
    public static final String CATEGORY = "key.categories.roadweaver.route";

    // ─────────────────── 世界内编辑 ───────────────────

    /** 前进（沿玩家朝向）。 */
    public static KeyMapping MOVE_FORWARD;
    /** 后退。 */
    public static KeyMapping MOVE_BACK;
    /** 左移。 */
    public static KeyMapping MOVE_LEFT;
    /** 右移。 */
    public static KeyMapping MOVE_RIGHT;

    /** 循环切换「下一段」的类型：路 → 桥 → 隧道。 */
    public static KeyMapping CYCLE_KIND;
    /** 删除当前选中的控制点。 */
    public static KeyMapping DELETE_POINT;
    /** 在当前点与下一点之间插入一个中点。 */
    public static KeyMapping INSERT_POINT;
    /** 撤销世界内的编辑。 */
    public static KeyMapping UNDO;
    /**
     * 给选中点**输入高度**（打开输入框，回车确认）。
     *
     * <p>默认 Y —— 取自「Y 轴」，好记。只在**地图界面**生效；
     * 世界里调高用滚轮，不需要这个键。</p>
     */
    public static KeyMapping OPEN_HEIGHT;
    /** 确认施工。 */
    public static KeyMapping CONFIRM_BUILD;
    /** 开关施工预览。 */
    public static KeyMapping TOGGLE_PREVIEW;
    /**
     * 开关**编辑 HUD**（★ 用户 2026-09-25 要求）。
     *
     * <p>用户原话：「hud 就是改成左侧竖排，按某键才显示，地图里面加个按钮开关」——
     * 左侧竖排会占掉屏幕左边一块，所以必须能收起来。</p>
     *
     * <p>默认 <b>{@code K}</b>（未被本功能其它按键占用；也不与原版默认键冲突）。</p>
     */
    public static KeyMapping TOGGLE_HUD;

    /**
     * **中止预加载**（★ UX-11，用户 2026-09-25 要求）。
     *
     * <h2>为什么需要它</h2>
     * 用户要求预加载「不设置超时，必须全部加载完成」，但一条含未探索区域的长路线
     * 可能等很久 —— 玩家该有"我不想等了"的出口。按下后**停止拉新批次**，
     * 已加载并铺好的保留，未处理的部分照旧交给后台回填兜底。
     *
     * <h2>键位</h2>
     * 默认 <b>{@code U}</b>（未被本功能占用，也不与原版默认键冲突）。
     *
     * <p>⚠️ <b>必须配合编辑修饰符（默认 Ctrl）</b>才能触发 ——
     * 用户 2026-09-25 明确要求「必须按 ctrl + 按键才行」，避免误按。</p>
     */
    public static KeyMapping ABORT_PRELOAD;

    // ★ 任务 A（2026-09-26 修订）：原「端点标记开关」按键（默认 M）**已删除**。
    //   用户明确：「这里下方不是有一排按钮么加个格外的按钮就行，**不用绑定键盘了**」
    //   ⇒ 入口只做在地图底部 Dock 的独立按钮上（MapDockAction.TOGGLE_MARKERS），
    //     状态仍存 ClientConfig.mapEndpointMarkersEnabled（默认关）。

    /**
     * 加速键（按住时挪位与调高步长 ×8）。
     *
     * <h2>为什么它也做成可绑定的</h2>
     * 用户明确要求「修饰键一样可以自定义」。而且原来的实现**三处不一致**：
     * <pre>
     *   世界内挪位   → InputConstants.isKeyDown(window, GLFW_KEY_LEFT_SHIFT)  ← 硬编码，
     *                 玩家把潜行改成别的键后，这里仍然认 Shift
     *   世界内调高   → mc.options.keyShift.isDown()                          ← 跟随原版潜行
     *   地图上拖动   → mc.options.keyShift.isDown()                          ← 跟随原版潜行
     * </pre>
     * 三种行为各不相同，玩家改键后表现会乱。统一成一个独立绑定后，
     * 语义清晰（「加速就是加速，和潜行无关」），玩家也能自己改。
     *
     * <p>默认仍是左 Shift —— 与大多数玩家的直觉一致。</p>
     */
    public static KeyMapping SPEED_BOOST;

    /**
     * 「编辑修饰符」（★ 审计 R3-P3-5：现在是**可绑定**的 KeyMapping）。
     *
     * <h2>为什么从硬编码改成绑定</h2>
     * 用户明确要求「修饰键一样可以自定义」（{@link #SPEED_BOOST} 已落实），
     * 而 Ctrl 是硬编码的 {@code InputConstants.isKeyDown(GLFW_KEY_LEFT_CONTROL)} ——
     * 违背该要求，且带来两个真实问题：
     * <ul>
     *   <li>Ctrl 同时是原版**疾跑**键 ⇒ 按住 Ctrl 编辑会连带疾跑</li>
     *   <li>Ctrl+滚轮与其它 mod 的缩放类绑定冲突，玩家改不掉</li>
     * </ul>
     *
     * <p>默认仍是左 Ctrl（保持现有手感），但玩家可以改到 Alt 等不冲突的键。</p>
     */
    public static KeyMapping EDIT_MODIFIER;

    /**
     * 每按住加速键，步长放大的倍数。
     *
     * <p>原先是硬编码的 {@code 8}，现在集中成常量，便于以后调。</p>
     */
    public static final int BOOST_MULTIPLIER = 8;

    private RouteKeys() {
    }

    /**
     * 创建全部 KeyMapping 对象（**不注册**）。
     *
     * <p>由 {@link RouteKeysBridge#registerAll()} 在平台侧调用：
     * 先建对象，再逐个交给 {@code KeyBindingHelper.registerKeyBinding(...)}。</p>
     *
     * <p>重复调用是幂等的（第二次直接返回），因为 {@code ClientTickEvents}
     * 可能在某些环境下被多次触发初始化。</p>
     */
    public static void create() {
        if (MOVE_FORWARD != null) {
            return;
        }
        // 方向键：与用户原需求的「方向键挪位」一致，且不与原版任何默认键冲突
        MOVE_FORWARD = new KeyMapping("key.roadweaver.route.move_forward",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);
        MOVE_BACK = new KeyMapping("key.roadweaver.route.move_back",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);
        MOVE_LEFT = new KeyMapping("key.roadweaver.route.move_left",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);
        MOVE_RIGHT = new KeyMapping("key.roadweaver.route.move_right",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);

        // ★ 换类型默认 G（不是 Tab）—— Tab 是原版玩家列表，会同时弹出
        CYCLE_KIND = new KeyMapping("key.roadweaver.route.cycle_kind",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
        DELETE_POINT = new KeyMapping("key.roadweaver.route.delete_point",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, CATEGORY);
        INSERT_POINT = new KeyMapping("key.roadweaver.route.insert_point",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_INSERT, CATEGORY);
        UNDO = new KeyMapping("key.roadweaver.route.undo",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
        OPEN_HEIGHT = new KeyMapping("key.roadweaver.route.open_height",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY);
        // ★ 审计 R3-P1-1：确认施工默认 **B**（不是 F）。
        //
        // F 与**原版 key.swapOffhand（换副手）同键** —— 已用 javap 核对
        // 1.21.1 的 Options 字节码：`key.swapOffhand` 默认键码 70 = GLFW_KEY_F。
        // 后果不是「按键冲突提示」，而是真实事故：玩家日常换个副手，
        // 这个 KeyMapping 的 clickCount 就 +1（KeyboardHandler.keyPress 在
        // screen == null 时无条件 click），之后一按住 Ctrl 就触发施工提交。
        // B 取 build，且不与原版任何默认键冲突。
        CONFIRM_BUILD = new KeyMapping("key.roadweaver.route.confirm_build",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
        TOGGLE_PREVIEW = new KeyMapping("key.roadweaver.route.toggle_preview",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY);
        // ★ 2026-09-25：HUD 开关。左侧竖排会挡屏幕左边，所以做成可收起。
        // K 未被本功能占用，且不与原版默认键冲突。
        TOGGLE_HUD = new KeyMapping("key.roadweaver.route.toggle_hud",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
        // ★ UX-11（2026-09-25）：中止预加载。U 未被占用，且不与原版默认键冲突。
        // 触发要求按住编辑修饰符（默认 Ctrl）—— 见 RouteWorldEditor.tick。
        ABORT_PRELOAD = new KeyMapping("key.roadweaver.route.abort_preload",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);
        // ★ 任务 A（2026-09-26 修订）：原 TOGGLE_MAP_MARKERS（默认 M）已删除 ——
        //   改为地图底部 Dock 按钮，不占键盘绑定（用户明确要求）。
        // 加速键：默认左 Shift，但独立于原版潜行 —— 玩家可单独改
        SPEED_BOOST = new KeyMapping("key.roadweaver.route.speed_boost",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, CATEGORY);
        // ★ 编辑修饰符：默认左 Ctrl（保持既有手感），但**可自定义**（R3-P3-5）。
        // 玩家若被「Ctrl 同时是疾跑」困扰，可在设置里改到 Alt。
        EDIT_MODIFIER = new KeyMapping("key.roadweaver.route.edit_modifier",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, CATEGORY);
    }

    /**
     * 加速键当前是否按住。
     *
     * <p>统一入口 —— 三处（世界内挪位 / 世界内调高 / 地图上拖动）都调它，
     * 保证行为一致。绑定未初始化或读取异常时返回 false（不加速），
     * 而不是让调用方各自判空。</p>
     */
    public static boolean isBoostHeld() {
        if (SPEED_BOOST == null) {
            return false;
        }
        try {
            return SPEED_BOOST.isDown();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 「编辑修饰符」当前是否按住（默认左 Ctrl，也接受右 Ctrl）。
     *
     * <h2>它的作用（用户要求，地图与世界内统一）</h2>
     * <pre>
     *   不按 Ctrl → 完全放行给原生行为
     *                地图：左键拖动平移 / 滚轮缩放
     *                世界：左键正常挖掘放置，方向键不动路线点
     *   按住 Ctrl → 才归手绘路线编辑器
     *                地图：左键放点/选点、滚轮调高度
     *                世界：方向键挪点、滚轮调高度、左键选中
     * </pre>
     *
     * <p>为什么需要它：不加修饰符时，编辑模式会**吞掉**地图的拖动/缩放
     * （玩家反馈「地图无法拖动了 缩放也是」），也会让世界里的方向键
     * 与走路/快捷栏产生意外交互。加一层 Ctrl 闸后，
     * 编辑模式下的地图与世界都还是「正常的那张地图/那个世界」。</p>
     *
     * <p><b>★ 现在是可绑定 KeyMapping（审计 R3-P3-5）</b>：默认左 Ctrl，
     * 玩家可在「选项 → 按键」里改。原先硬编码 GLFW 键的做法违背了
     * 「修饰键一样可以自定义」的既定要求，且 Ctrl 与原版疾跑同键、</p>
     */
    public static boolean isEditModifierHeld() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            // ★ V10（审计待验证项 4）：窗口失焦时一律视为没按。
            //
            // GLFW 不承诺失焦后清空按键状态，而按住 Ctrl → Alt+Tab 切走 →
            // 松开 Ctrl → 切回来，可能让 isKeyDown 卡在 true（玩家会看到
            // 方向键一直在挪点）。用 GLFW 的 FOCUSED 窗口属性兜住这个边界，
            // 代价是真实的失焦期间不能编辑 —— 本来就看不到画面。
            //
            // 注意：Window 本身没有 isFocused()，只能问 GLFW（已核对 1.21.1
            // 字节码里 Window 的公开方法列表）。
            var win = mc == null ? null : mc.getWindow();
            if (win == null
                    || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(win.getWindow(),
                            org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != org.lwjgl.glfw.GLFW.GLFW_TRUE) {
                return false;
            }
            // ① 优先看玩家绑定的修饰键（R3-P3-5：可自定义）
            if (EDIT_MODIFIER != null && EDIT_MODIFIER.isDown()) {
                return true;
            }
            // ② 兼容：仍然接受左右 Ctrl（默认绑定的就是左 Ctrl，
            //    这条同时兜住「玩家把 EDIT_MODIFIER 改到别处但仍习惯按 Ctrl」的场景）
            long window = win.getWindow();
            return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        } catch (RuntimeException e) {
            // 取不到窗口时不拦（放行给原生行为），宁可编辑不生效也不要卡死交互
            return false;
        }
    }

    /**
     * 按键是否**已初始化**（即平台实现有没有真的注册）。
     *
     * <h2>为什么需要这个检查</h2>
     * 审计 P2-12 指出：{@code neoforge/} 目录下 0 个 java 文件，
     * 两个 {@code @ExpectPlatform} 桥都没有实现 → {@code create()} 从不执行
     * → 12 个 {@code KeyMapping} 全为 null → 世界内编辑整体失效（不崩，但半残）。
     *
     * <p>需要说明的是：本项目**只发布 Fabric**
     * （{@code settings.gradle} 的 {@code include 'common', 'fabric'}，
     * 注释明确写了 "PlainCraft ships Fabric only"），
     * neoforge 是保留的上游参考目录、不参与构建。所以我们发布的 jar 里
     * 不会出现这个场景。</p>
     *
     * <p>但保留这个检查作为**防御**：万一将来有人加了别的平台实现却忘了注册，
     * 界面能给出「当前平台不支持」的明确提示，而不是静默失效。</p>
     */
    public static boolean isInitialized() {
        return MOVE_FORWARD != null;
    }
}
