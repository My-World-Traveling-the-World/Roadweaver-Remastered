package net.shiroha233.roadweaver.core.model;

/**
 * 道路跨度类型（桥梁/隧道）
 */
public enum SpanType {
    BRIDGE,
    ROAD_CROSSING,
    TUNNEL,
    JUNCTION_ANCHOR,
    TUNNEL_JUNCTION,
    NETWORK_REUSE,
    PROTECTED_GAP,
    /**
     * 涵洞：一段窄水道上路面抬高、水从路下继续流。
     *
     * <p>它不是桥：没有桥墩、没有大跨度，只有一段被抬起来的连续路面板，两侧各留一个进水口。
     */
    CULVERT
}
