/* 文件职责：在分配数组前统一计算采样点数、坐标边界和原始数组载荷。 */
package net.shiroha233.roadweaver.metric;

/** 只描述网格，不采样也不分配与面积成正比的存储，便于先审查大范围请求。 */
public record GridLayout(int minX, int minZ, int columns, int rows, int step) {
    public GridLayout {
        if (columns <= 0 || rows <= 0 || step <= 0) {
            throw new IllegalArgumentException("grid dimensions and step must be positive");
        }
        // 先升为 long 再乘加；不能让合法的负原点被中间 int 乘法误判为越界。
        Math.toIntExact((long) minX + (columns - 1L) * step);
        Math.toIntExact((long) minZ + (rows - 1L) * step);
    }

    public static GridLayout coveringBlocks(int minX, int minZ, long width, long depth, int step) {
        if (width <= 0 || depth <= 0 || step <= 0) {
            throw new IllegalArgumentException("block dimensions and step must be positive");
        }
        // 保留原来的向下取整口径，不为了对齐额外采样核心外的一列。
        Math.toIntExact(Math.addExact((long) minX, width - 1));
        Math.toIntExact(Math.addExact((long) minZ, depth - 1));
        return new GridLayout(minX, minZ, Math.toIntExact((width - 1) / step + 1),
                Math.toIntExact((depth - 1) / step + 1), step);
    }

    public static GridLayout aroundBounds(int minX, int minZ, int maxX, int maxZ,
                                          int padding, int step) {
        if (maxX < minX || maxZ < minZ || padding < 0) {
            throw new IllegalArgumentException("invalid bounds or padding");
        }
        // 四边余量都必须能表示；拒绝溢出请求，不把它绕回小网格或暗中裁掉核心。
        int paddedX = Math.toIntExact((long) minX - padding);
        int paddedZ = Math.toIntExact((long) minZ - padding);
        int paddedMaxX = Math.toIntExact((long) maxX + padding);
        int paddedMaxZ = Math.toIntExact((long) maxZ + padding);
        return coveringBlocks(paddedX, paddedZ, (long) paddedMaxX - paddedX + 1,
                (long) paddedMaxZ - paddedZ + 1, step);
    }

    public long cellCount() { return (long) columns * rows; }

    public int arrayLength() {
        // HotSpot 的单数组上限低于 int 最大值；这里只拒绝确定无法表示的布局，
        // 不把剩余堆内存当成可靠的准入预算，也不承诺通过此检查就不会 OOM。
        long count = cellCount();
        if (count > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("dense grid array too large: cells=" + count
                    + " grid=" + columns + "x" + rows + " step=" + step);
        }
        return (int) count;
    }

    public int maxX() { return (int) ((long) minX + (columns - 1L) * step); }
    public int maxZ() { return (int) ((long) minZ + (rows - 1L) * step); }
    public long blockWidth() { return (columns - 1L) * step + 1; }
    public long blockDepth() { return (rows - 1L) * step + 1; }

    public long payloadBytes(int bytesPerCell) {
        if (bytesPerCell < 0) throw new IllegalArgumentException("negative element payload");
        return Math.multiplyExact(cellCount(), bytesPerCell);
    }

    public long bitsetPayloadBytes() { return ((cellCount() + 63) / 64) * Long.BYTES; }

    public int snapX(int x) { return snap(x, minX, columns, step); }
    public int snapZ(int z) { return snap(z, minZ, rows, step); }

    private static int snap(int value, int origin, int count, int step) {
        // 延续 float 舍入以保持既有选格结果，只修正相减在转换前溢出的问题。
        int index = Math.round(((long) value - origin) / (float) step);
        return Math.max(0, Math.min(count - 1, index));
    }
}
