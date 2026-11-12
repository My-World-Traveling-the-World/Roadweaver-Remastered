/* 文件职责：拓扑层的一个节点 —— 道路要连到一个结构端点（或路口）。 */
package net.shiroha233.roadweaver.topology;

/**
 * 拓扑节点。
 *
 * @param id 稳定标识（结构 id 或「路口#序号」），用于日志与去重
 * @param x  方块坐标 x
 * @param z  方块坐标 z
 */
public record RoadNode(String id, int x, int z) {

    public RoadNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
    }

    /** 便于日志输出。 */
    public String describe() {
        return id + "@(" + x + "," + z + ")";
    }
}
