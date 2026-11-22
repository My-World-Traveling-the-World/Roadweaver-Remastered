/* 文件职责：按已验收的端点边统计连通性，保护缺口和失败只贡献端点，不连接两个分量。 */
package net.shiroha233.roadweaver.generation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;

final class InitialNetworkConnectivity {
    record Summary(int endpoints, int components, int largest, int spawnComponent) {}

    private final Map<Long, Node> nodes = new HashMap<>();
    private int components;
    private int largest;

    static Summary summarize(List<StructureConnection> connections, BlockPos spawn) {
        var graph = from(connections);
        var atSpawn = spawn == null ? null : graph.nodes.get(key(spawn));
        // 不把最近结构冒充出生点端点；未配置出生点接入时这里自然为零。
        return new Summary(graph.nodes.size(), graph.components, graph.largest,
                atSpawn == null ? 0 : root(atSpawn).size);
    }

    static InitialNetworkConnectivity from(List<StructureConnection> connections) {
        var graph = new InitialNetworkConnectivity();
        if (connections == null) return graph;
        for (StructureConnection edge : connections) {
            if (edge == null || edge.from() == null || edge.to() == null
                    || edge.status() == ConnectionStatus.FILTERED) continue;
            if (edge.status() == ConnectionStatus.COMPLETED) {
                graph.join(edge);
            } else {
                graph.node(edge.from());
                graph.node(edge.to());
            }
        }
        return graph;
    }

    /** X/Z 是结构端点身份，不能因几何端点重绑时的 Y 不同而分成两个点。 */
    boolean connected(BlockPos from, BlockPos to) {
        if (key(from) == key(to)) return true;
        var first = nodes.get(key(from));
        var second = nodes.get(key(to));
        return first != null && second != null && root(first) == root(second);
    }

    /** 按大小合并并做路径压缩，几十万条边也不依赖递归栈。 */
    void join(StructureConnection edge) {
        Node a = root(node(edge.from()));
        Node b = root(node(edge.to()));
        if (a == b) return;
        if (a.size < b.size) {
            Node swap = a;
            a = b;
            b = swap;
        }
        b.parent = a;
        a.size += b.size;
        largest = Math.max(largest, a.size);
        components--;
    }

    private Node node(BlockPos position) {
        return nodes.computeIfAbsent(key(position), ignored -> {
            components++;
            largest = Math.max(1, largest);
            return new Node();
        });
    }

    private static long key(BlockPos position) {
        return (long) position.getX() << 32 | position.getZ() & 0xffffffffL;
    }

    private static Node root(Node node) {
        while (node != node.parent) {
            node.parent = node.parent.parent;
            node = node.parent;
        }
        return node;
    }

    private static final class Node {
        private Node parent = this;
        private int size = 1;
    }
}
