/* 文件职责：用静态包围盒层次树筛选桥对，查询输出仍按原桥序。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 只做宽阶段裁剪，不改变原距离、重叠或选主判据；密集重叠时仍须输出全部桥对。 */
final class FrozenBridgePairIndex {
    private static final int LEAF_SIZE = 8;
    private final List<BridgeShareDetector.Bounds> boxes;
    private final Node root;

    FrozenBridgePairIndex(List<BridgeShareDetector.Bounds> boxes) {
        this.boxes = List.copyOf(boxes);
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        root = build(order, 0, order.length);
    }

    /** 建树只适用于大批稀疏输入；固定采样只选择等价枚举策略，绝不决定哪一对桥能参与。 */
    static FrozenBridgePairIndex sparseBatch(List<BridgeShareDetector.Bounds> boxes, int radius) {
        if (boxes.size() < 512) return null;
        int hits = 0;
        for (int sample = 0; sample < 64; sample++) {
            int a = (int)((long)sample * boxes.size() / 64);
            int b = (a + boxes.size() / 2 + sample % 7) % boxes.size();
            if (BridgeShareDetector.overlapsPrepared(boxes.get(a), boxes.get(b), radius)) hits++;
        }
        return hits > 8 ? null : new FrozenBridgePairIndex(boxes);
    }

    /** 只分配当前源桥的候选，不预存密集图的二次数量边；排序恢复原i/j遍历语义。 */
    List<Integer> after(int source, int radius) {
        return after(source, radius, null);
    }

    List<Integer> after(int source, int radius, long[] checks) {
        var result = new ArrayList<Integer>();
        int expand = Math.max(0, radius);
        // 旧叶子判据做int减法；极端越界输入不做空间剪枝，避免“修正”旧溢出口径而漏候选。
        boolean safe = root == null || ((long)root.bounds.minX() - expand >= Integer.MIN_VALUE
                && (long)root.bounds.minZ() - expand >= Integer.MIN_VALUE);
        query(root, source, expand, result, checks, safe);
        result.sort(Integer::compare);
        return result;
    }

    private void query(Node node, int source, int radius, List<Integer> out, long[] checks, boolean safe) {
        if (node == null || node.maxIndex <= source) return;
        var target = boxes.get(source);
        // long防止树节点范围加半径溢出；叶子仍执行旧判据，保持其合法输入行为。
        if (safe && !overlaps(node.bounds, target, radius)) return;
        if (node.indices != null) {
            for (int candidate : node.indices) {
                if (candidate <= source) continue;
                if (checks != null) checks[0]++;
                if (BridgeShareDetector.overlapsPrepared(target, boxes.get(candidate), radius)) out.add(candidate);
            }
        } else {
            query(node.left, source, radius, out, checks, safe);
            query(node.right, source, radius, out, checks, safe);
        }
    }

    private static boolean overlaps(BridgeShareDetector.Bounds a, BridgeShareDetector.Bounds b, int radius) {
        return (long)a.minX() - radius <= b.maxX() && (long)b.minX() - radius <= a.maxX()
                && (long)a.minZ() - radius <= b.maxZ() && (long)b.minZ() - radius <= a.maxZ();
    }

    private Node build(Integer[] order, int from, int to) {
        if (from == to) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, maxIndex = -1;
        for (int i = from; i < to; i++) {
            var box = boxes.get(order[i]);
            minX = Math.min(minX, box.minX()); minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX()); maxZ = Math.max(maxZ, box.maxZ());
            maxIndex = Math.max(maxIndex, order[i]);
        }
        var bounds = new BridgeShareDetector.Bounds(minX, minZ, maxX, maxZ);
        if (to - from <= LEAF_SIZE) {
            int[] indices = new int[to - from];
            for (int i = from; i < to; i++) indices[i - from] = order[i];
            return new Node(bounds, maxIndex, indices, null, null);
        }
        boolean xAxis = (long)maxX - minX >= (long)maxZ - minZ;
        // 沿较长轴按中心中分，长桥不需要铺进成千上万空间桶，树深受桥数约束。
        Arrays.sort(order, from, to, Comparator.<Integer>comparingLong(index -> {
            var box = boxes.get(index);
            return xAxis ? (long)box.minX() + box.maxX() : (long)box.minZ() + box.maxZ();
        }).thenComparingInt(Integer::intValue));
        int middle = (from + to) >>> 1;
        return new Node(bounds, maxIndex, null, build(order, from, middle), build(order, middle, to));
    }

    private record Node(BridgeShareDetector.Bounds bounds, int maxIndex, int[] indices, Node left, Node right) {}
}
