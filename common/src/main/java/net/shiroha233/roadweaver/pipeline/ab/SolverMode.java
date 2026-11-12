/* 文件职责：求解算法的切换开关 —— 出厂默认走旧链，新算法必须显式选择。 */
package net.shiroha233.roadweaver.pipeline.ab;

import java.util.Locale;

/**
 * 求解算法模式。
 *
 * <h2>为什么默认是 CLASSIC</h2>
 * <p>换算法必须是<b>显式</b>选择。如果默认走新链，那么玩家一升级就会毫无预警地
 * 换掉全部道路形态 —— 而新算法还没经过真机验证。0.9.098 的行为是当前唯一
 * 被真机证明过的基线，所以默认必须指向它。</p>
 *
 * <h2>AB 模式下谁生效</h2>
 * <p><b>旧链生效，新链只观察。</b>AB 的目的是收集对比数据，不是试验田 ——
 * 如果 AB 模式下新链生效，那这个模式就没法用来「安全地看新算法表现」。</p>
 */
public enum SolverMode {

    /** 旧链（A* + 后处理 8 段）。**出厂默认**，行为与 0.9.098 一致。 */
    CLASSIC("classic"),

    /** 新三层内核（metric + topology + band）。 */
    BAND("band"),

    /** 两套都跑，旧链生效，输出逐项对比。 */
    AB("ab");

    private final String configKey;

    SolverMode(String configKey) {
        this.configKey = configKey;
    }

    /** 配置里使用的字符串键。 */
    public String configKey() {
        return configKey;
    }

    /** 本模式下是否需要跑新链。 */
    public boolean runsBand() {
        return this == BAND || this == AB;
    }

    /** 本模式下是否需要跑旧链。 */
    public boolean runsClassic() {
        return this == CLASSIC || this == AB;
    }

    /** 本模式下哪一套的结果真正生效。 */
    public SolverMode effective() {
        return this == BAND ? BAND : CLASSIC;
    }

    /**
     * 解析配置字符串。
     *
     * <p>无法识别时<b>回落到 CLASSIC</b> 并返回它 —— 静默换算法是绝对不能接受的行为，
     * 所以「不认识」必须等价于「不换」。</p>
     */
    public static SolverMode parse(String raw) {
        if (raw == null) return CLASSIC;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SolverMode mode : values()) {
            if (mode.configKey.equals(normalized)) return mode;
        }
        return CLASSIC;
    }
}
