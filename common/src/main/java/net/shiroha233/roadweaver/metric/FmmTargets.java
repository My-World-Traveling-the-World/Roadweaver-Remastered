/* 文件职责：按目标数选择原始整数集合或位图，避免早停条件占用整场布尔数组。 */
package net.shiroha233.roadweaver.metric;

import java.util.BitSet;

final class FmmTargets {
    private final int cells;
    private final int expected;
    private final int[] table;
    private final BitSet dense;
    private int single = -1;
    private int count;

    FmmTargets(int cells, int expected) {
        if (cells <= 0 || expected < 0) throw new IllegalArgumentException("invalid target dimensions");
        this.cells = cells;
        this.expected = expected;
        // 单目标直接存字段；稀疏时只付端点数的代价，密集时用位图避免整数表反而更大。
        long sparseBytes = expected <= 1 ? 0 : sparseCapacity(expected) * Integer.BYTES;
        long denseBytes = ((cells + 63L) / 64) * Long.BYTES;
        boolean useDense = expected > 1 && denseBytes <= sparseBytes;
        dense = useDense ? new BitSet(cells) : null;
        table = !useDense && expected > 1 ? new int[(int) sparseCapacity(expected)] : null;
    }

    static long payloadBytesUpperBound(long cells, int expected) {
        if (cells <= 0 || expected < 0) throw new IllegalArgumentException("invalid target dimensions");
        if (expected <= 1) return 0;
        return Math.min(sparseCapacity(expected) * Integer.BYTES, ((cells + 63) / 64) * Long.BYTES);
    }

    private static long sparseCapacity(int expected) {
        long capacity = 2;
        // 容量计算也用 long；大量端点会选位图，不允许乘二先溢出成微型哈希表。
        while (capacity < expected * 2L) capacity <<= 1;
        return capacity;
    }

    void add(int index) {
        if (index < 0 || index >= cells) throw new IndexOutOfBoundsException(index);
        if (contains(index)) return;
        if (count >= expected) throw new IllegalStateException("target count exceeds request");
        if (dense != null) dense.set(index);
        else if (table != null) {
            int slot = slot(index);
            while (table[slot] != 0) slot = (slot + 1) & (table.length - 1);
            // 用下标加一避开空槽零值，首格与最后一格都必须可以登记。
            table[slot] = index + 1;
        } else single = index;
        count++;
    }

    boolean contains(int index) {
        if (index < 0 || index >= cells) return false;
        if (dense != null) return dense.get(index);
        if (table == null) return single == index;
        int slot = slot(index);
        while (table[slot] != 0) {
            if (table[slot] == index + 1) return true;
            slot = (slot + 1) & (table.length - 1);
        }
        return false;
    }

    private int slot(int index) {
        int hash = index * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & (table.length - 1);
    }

    int count() { return count; }

    long storagePayloadBytes() {
        // 返回实际原始数组长度，用于验证选型；对象头与标量字段不在此口径内。
        return dense != null ? Integer.toUnsignedLong(dense.size()) / 8 : table == null ? 0 : (long) table.length * Integer.BYTES;
    }
}
