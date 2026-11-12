/* 文件职责：玩家手绘路线中「一段」的用途类型。 */
package net.shiroha233.roadweaver.route;

/**
 * 玩家手绘路线中「一段」的用途类型。
 *
 * <h2>为什么不用现成的 {@code SpanType}</h2>
 * {@code net.shiroha233.roadweaver.core.model.SpanType} 描述的是**算法规划完之后**
 * 每一段的地形结论（BRIDGE / TUNNEL / ROAD_CROSSING / JUNCTION_ANCHOR /
 * TUNNEL_JUNCTION / NETWORK_REUSE / PROTECTED_GAP），其中很多值是规划器内部产物。
 *
 * 玩家手绘时只需要三选一，语义也必须更简单直接 —— 玩家说「这段是桥」，
 * 就是桥，不需要算法再判断一次。所以单独定义这个枚举，
 * 在**注入阶段**再翻译成 {@code SpanType}：
 *
 * <pre>
 *   RouteKind.ROAD   → SpanType 由地形决定（水上是 ROAD_CROSSING，陆地无 span）
 *   RouteKind.BRIDGE → SpanType.BRIDGE
 *   RouteKind.TUNNEL → SpanType.TUNNEL
 * </pre>
 */
public enum RouteKind {
    /** 普通道路：贴地面，遇水由现有逻辑决定是否短桥。 */
    ROAD("road", 0x7CD44F),

    /** 桥梁：按玩家给的高度架桥，不贴地面。 */
    BRIDGE("bridge", 0x4FA8FF),

    /** 隧道：按玩家给的高度挖穿，不贴地面。 */
    TUNNEL("tunnel", 0xFF9A3C);

    private final String serializedName;
    private final int editorColor;

    RouteKind(String serializedName, int editorColor) {
        this.serializedName = serializedName;
        this.editorColor = editorColor;
    }

    /** 存档 / 网络用的名字（小写，稳定，不随枚举改名而变）。 */
    public String serializedName() {
        return serializedName;
    }

    /**
     * 编辑器中该类型的显示颜色（RGB）。
     *
     * <p>默认值；实际渲染时优先取配置项，因为光影/亮度不同、
     * 以及色弱玩家都可能需要改。</p>
     */
    public int editorColor() {
        return editorColor;
    }

    /** 宽容解析：认不出来时退回 {@link #ROAD}，不抛异常。 */
    public static RouteKind fromSerializedName(String name) {
        if (name == null) {
            return ROAD;
        }
        for (RouteKind kind : values()) {
            if (kind.serializedName.equalsIgnoreCase(name) || kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return ROAD;
    }

    /** 下一个类型（工具条上循环切换用：路 → 桥 → 隧道 → 路）。 */
    public RouteKind next() {
        RouteKind[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
