/* 文件职责：描述一次规则区域精确高度采样的世界坐标网格。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

/**
 * 按固定步长排列的区域列请求。
 */
public record AccurateHeightGridRequest(int minX, int minZ, int width, int height, int step) {
    public AccurateHeightGridRequest {
        if (width <= 0 || height <= 0 || step <= 0) {
            throw new IllegalArgumentException("invalid accurate height grid");
        }
        Math.multiplyExact(width, height);
        // 在调度与分配前检查最后一列，避免半批采完才发现世界坐标溢出。
        Math.toIntExact((long) minX + (width - 1L) * step);
        Math.toIntExact((long) minZ + (height - 1L) * step);
    }

    public int sampleCount() {
        return Math.multiplyExact(width, height);
    }

    public int blockX(int index) {
        checkIndex(index);
        return Math.toIntExact((long) minX + (long) (index % width) * step);
    }

    public int blockZ(int index) {
        checkIndex(index);
        return Math.toIntExact((long) minZ + (long) (index / width) * step);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= sampleCount()) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
