/* 文件职责：按原旋转和倒伏规则生成保序去重的巨树占地列。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** 树体只向道路发布二维占地；同列多层只保留一次，避免逐体素创建旋转数组和重复登记。 */
final class TreeColumnProjection {
    private TreeColumnProjection() {}

    record Projection(Long2ObjectLinkedOpenHashMap<long[]> chunks, int columns) {}

    static Projection project(short[] shape, BlockPos center, int[] rotationMirrored, int fallenDirection) {
        int rotation = rotationMirrored != null && rotationMirrored.length > 0 ? rotationMirrored[0] : 1;
        int mirrored = rotationMirrored != null && rotationMirrored.length > 1 ? rotationMirrored[1] : 0;
        // 每区块只用256位；逐列哈希表在稀疏树体中比旧列表占更多内存。
        var masks = new Long2ObjectLinkedOpenHashMap<long[]>();
        long lastChunk = 0;
        long[] mask = null;
        int columns = 0;
        for (int i = 0; i + 3 < shape.length; i += 4) {
            int type = Math.abs((int) shape[i]);
            if (type < 1100 || type >= 1200) continue;
            int x = shape[i + 1], y = shape[i + 2], z = shape[i + 3];
            int rx = x, rz = z;
            switch (rotation) {
                case 2 -> { rx = z; rz = -x; }
                case 3 -> { rx = -x; rz = -z; }
                case 4 -> { rx = -z; rz = x; }
                default -> { /* 非旋转值沿用旧实现的原向，保持外部调用兼容。 */ }
            }
            if (mirrored == 1) rx = -rx;
            else if (mirrored == 2) rz = -rz;
            switch (fallenDirection) {
                case 1 -> rx = y;
                case 2 -> rz = y;
                case 3 -> rx = -y;
                case 4 -> rz = -y;
                default -> { /* 未倒伏时保持原二维投影。 */ }
            }
            long column = BlockPos.asLong(center.getX() + rx, 0, center.getZ() + rz);
            int columnX = BlockPos.getX(column), columnZ = BlockPos.getZ(column);
            long chunk = ChunkPos.asLong(columnX >> 4, columnZ >> 4);
            if (mask == null || chunk != lastChunk) {
                mask = masks.get(chunk);
                if (mask == null) {
                    mask = new long[4];
                    masks.put(chunk, mask);
                }
                lastChunk = chunk;
            }
            int bit = ((columnZ & 15) << 4) | (columnX & 15);
            long flag = 1L << (bit & 63);
            // 必须保留首次出现顺序，否则区块容量淘汰顺序会随散列表遍历发生变化。
            if ((mask[bit >> 6] & flag) == 0) {
                mask[bit >> 6] |= flag;
                columns++;
            }
        }
        return new Projection(masks, columns);
    }
}
