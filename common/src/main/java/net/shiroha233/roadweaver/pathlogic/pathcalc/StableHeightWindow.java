/* 文件职责：在显式稳定读取窗口内按列取高一次，用前缀和计算站点均值。 */
package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.util.List;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** 仅服务同步主线程的已加载世界；异步worldgen不能借此缓存动态高度。 */
final class StableHeightWindow {
    private StableHeightWindow() {}

    static int[] build(List<BlockPos> positions, ChunkPos chunk, int radius, IntBinaryOperator height) {
        int n = positions.size();
        long[] sums = new long[n + 1];
        int[] counts = new int[n + 1];
        int[] columns = new int[256];
        boolean[] sampled = new boolean[256];
        for (int i = 0; i < n; i++) {
            BlockPos pos = positions.get(i);
            sums[i + 1] = sums[i];
            counts[i + 1] = counts[i];
            if (!inside(pos, chunk)) continue;
            int column = index(pos);
            if (!sampled[column]) {
                columns[column] = height.applyAsInt(pos.getX(), pos.getZ());
                sampled[column] = true;
            }
            sums[i + 1] += columns[column];
            counts[i + 1]++;
        }
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            // 负半径沿原规则走单站回落；long边界避免半径算术回绕。
            int from = radius < 0 ? i : (int) Math.max(0L, (long) i - radius);
            int to = radius < 0 ? i : (int) Math.min(n, (long) i + radius + 1);
            int count = counts[to] - counts[from];
            BlockPos pos = positions.get(i);
            result[i] = count == 0 ? (inside(pos, chunk) ? columns[index(pos)] : pos.getY())
                    : (int) Math.round((double) (sums[to] - sums[from]) / count);
        }
        return result;
    }

    private static boolean inside(BlockPos pos, ChunkPos chunk) {
        return (pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z;
    }

    private static int index(BlockPos pos) { return ((pos.getZ() & 15) << 4) | (pos.getX() & 15); }
}
