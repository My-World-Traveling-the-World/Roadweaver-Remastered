package net.shiroha233.roadweaver.route;

/**
 * 手绘路线的状态。
 *
 * <p>与 {@code ConnectionStatus} 的区别：那个描述的是**算法规划出来的连接**
 * 在生成流水线上的位置；本枚举描述的是**玩家画的这条线**处在编辑-施工的哪个阶段。</p>
 */
public enum RouteStatus {
    /** 草稿：正在画 / 正在调，可随意增删改。 */
    DRAFT("draft"),

    /** 玩家已点「确定」，等待施工。 */
    READY("ready"),

    /** 施工中。 */
    BUILDING("building"),

    /** 施工完成。 */
    DONE("done"),

    /** 玩家放弃。 */
    CANCELLED("cancelled");

    private final String serializedName;

    RouteStatus(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** 宽容解析，认不出来按 {@link #DRAFT}。 */
    public static RouteStatus fromSerializedName(String name) {
        if (name == null) {
            return DRAFT;
        }
        for (RouteStatus s : values()) {
            if (s.serializedName.equalsIgnoreCase(name) || s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return DRAFT;
    }
}
