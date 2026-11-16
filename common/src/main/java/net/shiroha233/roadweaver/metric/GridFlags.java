/* 文件职责：为已校验下标的固定网格保存一位状态，避免可变位图的有效长度维护。 */
package net.shiroha233.roadweaver.metric;

final class GridFlags {
    private final long[] words;

    GridFlags(int size) {
        if (size <= 0) throw new IllegalArgumentException("grid flag size must be positive");
        words = new long[(int) ((size + 63L) >>> 6)];
    }

    boolean get(int index) {
        return (words[index >>> 6] & (1L << index)) != 0;
    }

    void set(int index) {
        // 求解器只会把状态从零置一，不需要可变集合的扩容、清位和最高非零字追踪。
        words[index >>> 6] |= 1L << index;
    }

    long storagePayloadBytes() { return (long) words.length * Long.BYTES; }
}
