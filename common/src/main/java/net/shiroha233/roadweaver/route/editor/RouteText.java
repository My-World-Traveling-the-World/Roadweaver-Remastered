/* 文件职责：手绘路线界面的**本地化文本**入口（★ 审计 R3-P3-3）。 */
package net.shiroha233.roadweaver.route.editor;

import net.minecraft.network.chat.Component;

/**
 * 手绘路线界面的**本地化文本**入口（★ 审计 R3-P3-3）。
 *
 * <h2>为什么需要它</h2>
 * 原先 {@code RouteMapRenderer} / {@code RouteWorldRenderer} 里所有
 * HUD 与工具条文案都是硬编码中文字面量 —— 英文客户端玩家看到的是中文，
 * 而且改文案必须动 Java 代码。
 *
 * <h2>设计：可翻译 + 中文兜底</h2>
 * 每次取文本都走 {@link Component#translatable}，并在 lang 文件里给出
 * {@code en_us} / {@code zh_cn} 两份。这样：
 * <ul>
 *   <li>英文客户端显示英文，中文客户端显示中文 —— 由原版语言系统决定</li>
 *   <li>翻译键缺失时退回调用方传入的中文兜底串，**绝不显示裸 key**
 *       （原版 {@code Component.translatable} 缺失时会直接显示
 *       {@code roadweaver.route.hud.help} 这种键名，比中文更糟）</li>
 * </ul>
 *
 * <p>兜底串直接写在调用点（而不是这里维护一张表），是为了让「文案就在
 * 用到它的那行旁边」—— 改文案时不用在两个文件之间跳。</p>
 */
public final class RouteText {

    private RouteText() {
    }

    /**
     * 取本地化文本。
     *
     * @param key      翻译键（如 {@code roadweaver.route.hud.help}）
     * @param fallback 键缺失时使用的中文兜底串
     * @return 当前语言下的文本；取不到时返回 {@code fallback}
     */
    public static String get(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        try {
            Component c = Component.translatable(key);
            String s = c.getString();
            // 原版在键缺失时会把 key 原样返回 —— 此时用兜底串。
            // 也兜住「翻译成空串」的坏 lang 文件。
            if (s == null || s.isEmpty() || s.equals(key)) {
                return fallback;
            }
            return s;
        } catch (RuntimeException | LinkageError e) {
            // 语言系统不可用（早期初始化 / 非客户端环境）时退回兜底串
            return fallback;
        }
    }

    /**
     * 带参数的本地化文本（{@code %s} 形式）。
     *
     * @param key      翻译键
     * @param fallback 键缺失时的中文兜底模板（同样含 {@code %s}）
     * @param args     格式化参数
     */
    public static String get(String key, String fallback, Object... args) {
        String pattern = get(key, fallback);
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            // 翻译串里的占位符数量与调用点不一致时，绝不抛异常打断渲染
            try {
                return String.format(fallback, args);
            } catch (RuntimeException ignored) {
                return pattern;
            }
        }
    }
}
